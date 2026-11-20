package id.larus.mqttgroup.call

import id.larus.mqttgroup.mqtt.Call

/**
 * One call's life, as a pure function.
 *
 * Everything that decides *what a call does* lives here and touches nothing: no database, no
 * MQTT client, no Android. What is left outside is only plumbing — publish this, ring that,
 * cancel a timer — which is the part that could not be unit tested anyway. The same machine is
 * written again in `../family-group-pwa/src/lib/call/callMachine.ts`, and the two are expected
 * to stay recognisably the same shape.
 *
 * The rules that are easy to get wrong, in one place:
 *
 * - **A phone holds at most one call.** A second offer arriving during one is declined as busy
 *   without ever reaching a person, because a phone that rings during a call is a phone whose
 *   owner now has two problems.
 * - **Every message names a call id, and one for a call we are not in is ignored.** That is
 *   what makes a late `cancel` for a call that already ended harmless, rather than something
 *   that tears down the call that replaced it.
 * - **Timeouts are durations counted locally**, never deadlines read off a message — see
 *   [Call.Offer.ttlSec]. The machine is handed [Event.Timeout] by whoever owns a clock; it
 *   does not read one.
 */
sealed interface CallState {

    /** Null in [Idle], and the id of the one call this phone is on otherwise. */
    val callId: String?

    /** Who that call is with. */
    val peerId: String?

    /** True while this phone should be refusing new calls. */
    val isBusy: Boolean
        get() = this is Outgoing || this is Incoming || this is Connected

    /** No call. The state a phone is in almost all of the time. */
    data object Idle : CallState {
        override val callId: String? = null
        override val peerId: String? = null
    }

    /**
     * We are calling someone. [reached] flips when their [Call.Ring] arrives, and is the whole
     * reason that message exists: "Calling…" means we published into the void, "Ringing…" means
     * a device that is awake has it. With a browser tab that may be closed, that is the most
     * useful thing a caller can be told.
     */
    data class Outgoing(
        override val callId: String,
        /** The member being dialled, or null when the whole group is being rung. */
        override val peerId: String?,
        val mode: Call.Mode,
        val reached: Boolean = false,
    ) : CallState {
        /** A group call rings everyone; a direct one rings one person. */
        val isGroup: Boolean get() = peerId == null
    }

    /** Someone is calling us and this phone is ringing. [peerId] is who called. */
    data class Incoming(
        override val callId: String,
        override val peerId: String,
        val mode: Call.Mode,
        /** True when this rang because the whole group was called rather than us by name. */
        val isGroup: Boolean = false,
    ) : CallState

    /**
     * Answered. Silent in this phase — there is no media yet — but a real state with a real
     * end, so that phase 9 adds audio to a call that already knows how to start and stop.
     */
    data class Connected(
        override val callId: String,
        /**
         * Everyone else in the room, which is a set because a group call is a room and not a
         * pair. It grows as people join and shrinks as they leave, and when it empties the call
         * is over — sitting alone in a room waiting is not a state worth showing anyone.
         *
         * It may be empty for a moment at the very start of a group call we placed: we are
         * connected to the room before anybody has answered.
         */
        val participants: Set<String>,
        val mode: Call.Mode,
        /** For the call timer. Taken from the same clock [Event] timestamps come from. */
        val since: Long,
        /** Whether this was a group call. Kept because an emptied room ends a direct call and
         * a group one alike, but only one of them can meaningfully be rejoined. */
        val isGroup: Boolean = false,
    ) : CallState {
        /** The single other person, when there is exactly one — which is every direct call. */
        override val peerId: String? get() = participants.singleOrNull()
    }

    /** Over, and why. Held briefly so the UI can say what happened before returning to [Idle]. */
    data class Ended(
        override val callId: String,
        override val peerId: String?,
        val outcome: Outcome,
    ) : CallState
}

enum class Outcome {
    /** They pressed the red button. */
    DECLINED,

    /** They were already on a call. Never reached a person on their end. */
    BUSY,

    /** We gave up before they answered. */
    CANCELLED,

    /** Nobody answered before the offer's life ran out. On the callee's side this is the missed
     * call; on the caller's it is the unanswered one. */
    UNANSWERED,

    /** A connected call that one side hung up. */
    HUNG_UP,
}

sealed interface Event {
    /** This phone wants to call [peerId], or the whole group when it is null. [callId] is
     * minted by the caller. */
    data class Dial(val callId: String, val peerId: String?, val mode: Call.Mode) : Event

    /** A call message arrived and opened under the group key. [senderId] is the *envelope's*
     * authenticated sender, never anything from the body. */
    data class Received(val senderId: String, val message: Call) : Event

    /** The person pressed answer. */
    data object Accept : Event

    /** The person pressed the red button: decline while ringing, hang up while connected,
     * cancel while dialling. Which of the three it means is the machine's business, not the
     * button's. */
    data object Hangup : Event

    /** The offer's life ran out, counted locally by whoever owns the clock. */
    data class Timeout(val callId: String) : Event

    /** The UI has shown [CallState.Ended] for long enough. */
    data object Dismiss : Event
}

/**
 * What the machine decided: the state to move to, and at most one message to put on the wire.
 *
 * [publish] is deliberately not "send this now" — it becomes a row in the outbox like
 * everything else in this app, carrying the short deadline call signalling gets. Nothing in
 * this file knows that, which is the point.
 */
data class Transition(
    val state: CallState,
    val publish: Call? = null,
)

object CallMachine {

    /** How long our own ringing lasts, and what we put in an offer we send. */
    const val TTL_SEC = Call.DEFAULT_TTL_SEC

    /**
     * A buzz rings for longer than a call, because it is answering a different question.
     * Forty-five seconds is how long someone will wait for a person to pick up; finding a phone
     * down the back of a sofa takes longer than that, and nobody is being kept waiting while it
     * happens.
     */
    const val BUZZ_TTL_SEC = 90

    fun ttlFor(mode: Call.Mode): Int = if (mode == Call.Mode.BUZZ) BUZZ_TTL_SEC else TTL_SEC

    /**
     * @param selfId this phone's user id, so an offer addressed to another member of the group
     *   can be told from one addressed to us. Every member decrypts every offer — the topic is
     *   the group's — so this check is the ordinary path and not a defence against anything.
     */
    fun reduce(state: CallState, event: Event, selfId: String, now: Long): Transition = when (event) {
        is Event.Dial -> when (state) {
            // Dialling while already in a call is a UI that should not have offered the button.
            // Refusing here rather than trusting that is what keeps one-call-at-a-time an
            // invariant of the machine instead of a habit of its callers.
            is CallState.Idle, is CallState.Ended -> Transition(
                CallState.Outgoing(event.callId, event.peerId, event.mode),
                Call.Offer(event.callId, event.peerId, event.mode, ttlFor(event.mode)),
            )
            else -> Transition(state)
        }

        is Event.Received -> received(state, event, selfId, now)

        Event.Accept -> when (state) {
            // A buzz has nothing to answer. It rings until somebody stops it, and the only two
            // buttons that exist for one are Stop at either end — so an accept here would put a
            // call into a state its own UI has no way out of.
            is CallState.Incoming -> if (state.mode == Call.Mode.BUZZ) {
                Transition(state)
            } else {
                Transition(
                // The caller is the only person we know is here. Anyone else already in the
                // room answers our accept with their own, which is how the rest arrive — see
                // plan.md, phase 10.
                    CallState.Connected(
                        callId = state.callId,
                        participants = setOf(state.peerId),
                        mode = state.mode,
                        since = now,
                        isGroup = state.isGroup,
                    ),
                    Call.Accept(state.callId),
                )
            }
            else -> Transition(state)
        }

        Event.Hangup -> when (state) {
            // Three different words for one red button, and the difference is real: a cancel
            // says "I gave up", a decline says "I said no", an end says "we are done".
            is CallState.Outgoing -> Transition(
                CallState.Ended(state.callId, state.peerId, Outcome.CANCELLED),
                Call.Cancel(state.callId),
            )
            is CallState.Incoming -> Transition(
                CallState.Ended(state.callId, state.peerId, Outcome.DECLINED),
                Call.Decline(state.callId, Call.Reason.DECLINED),
            )
            // "I have left." For two people that is the same as ending the call; for a room
            // it is not, and the others carry on without us.
            is CallState.Connected -> Transition(
                CallState.Ended(state.callId, state.peerId, Outcome.HUNG_UP),
                Call.End(state.callId),
            )
            else -> Transition(state)
        }

        is Event.Timeout -> when {
            // A timer for a call that has already moved on. Firing it would end the call that
            // replaced this one, which is exactly what the id check is here to prevent.
            state.callId != event.callId -> Transition(state)
            state is CallState.Outgoing -> Transition(
                CallState.Ended(state.callId, state.peerId, Outcome.UNANSWERED),
                // Tell them to stop ringing. Their own timer would do it a moment later, but
                // only if their offer arrived the instant ours was sent — and the whole reason
                // the ttl is a duration is that we cannot assume that.
                //
                // A group call that somebody has already joined never reaches here: the state
                // is Connected by then, and a timer for a call that has moved on is ignored
                // above. Late joiners stay welcome for as long as the room lasts.
                Call.Cancel(state.callId),
            )
            state is CallState.Incoming -> Transition(
                CallState.Ended(state.callId, state.peerId, Outcome.UNANSWERED),
                // No message. A caller still waiting will time out on their own clock, and one
                // who gave up already sent a cancel. Answering a timeout with a decline would
                // also make "nobody was near the phone" indistinguishable from "they looked at
                // it and said no" — a small lie the other end's UI would then repeat.
            )
            else -> Transition(state)
        }

        Event.Dismiss ->
            if (state is CallState.Ended) Transition(CallState.Idle) else Transition(state)
    }

    private fun received(
        state: CallState,
        event: Event.Received,
        selfId: String,
        now: Long,
    ): Transition {
        val message = event.message

        if (message is Call.Offer) {
            val forGroup = message.toUserId == null
            // Someone else's call, overheard. Every member of the group decrypts every offer,
            // so this is ordinary traffic and not an attack — it is simply not for us. A group
            // offer is for everybody, so nobody is excluded by this.
            if (!forGroup && message.toUserId != selfId) return Transition(state)
            // Our own offer coming back from the broker — which a group offer always does,
            // since we are a member of the group we just rang.
            if (event.senderId == selfId) return Transition(state)
            return when (state) {
                is CallState.Idle, is CallState.Ended -> Transition(
                    CallState.Incoming(message.callId, event.senderId, message.mode, forGroup),
                )
                // Already in a call. Declined without a person ever hearing it ring.
                else -> Transition(state, Call.Decline(message.callId, Call.Reason.BUSY))
            }
        }

        // Everything else is about a call in progress, and only about *ours*. A message for a
        // call we are not in is not an error — a late cancel for a call that already ended is
        // ordinary — so it is dropped rather than acted on.
        if (state.callId != message.callId) return Transition(state)
        // And only from someone actually in this call, so a member who is not cannot hang up a
        // call between others. A shared key cannot prove who sent a message, but it can at
        // least require the sender to be someone the call already knows about.
        //
        // A connected group call is the exception, and deliberately: an `accept` from somebody
        // not yet in the room is exactly how a person joins one, so it is handled below rather
        // than refused here.
        val known = when (state) {
            is CallState.Connected -> event.senderId in state.participants ||
                (state.isGroup && message is Call.Accept)
            // A group call was rung to nobody in particular, so it has no peer to compare
            // against and *anyone* in the group may answer it. Without this the check reads
            // `null == senderId`, drops the accept, and the call rings and then does nothing —
            // which is exactly what it did.
            is CallState.Outgoing -> state.isGroup || state.peerId == event.senderId
            else -> state.peerId == event.senderId
        }
        if (!known) return Transition(state)

        return when (message) {
            is Call.Ring ->
                if (state is CallState.Outgoing) Transition(state.copy(reached = true))
                else Transition(state)

            is Call.Accept -> when (state) {
                // The first person to answer our call. For a direct call that is the whole
                // thing; for a group call it is the room opening, and others may follow.
                is CallState.Outgoing -> Transition(
                    CallState.Connected(
                        callId = state.callId,
                        participants = setOf(event.senderId),
                        mode = state.mode,
                        since = now,
                        isGroup = state.isGroup,
                    ),
                )
                // Somebody joining a room we are already in. We answer with our own accept so
                // that *they* learn we are here — nothing on this topic is retained, so the
                // roster is rebuilt from traffic. Only for a newcomer, and only once, which is
                // what keeps this from becoming a storm.
                is CallState.Connected ->
                    if (event.senderId in state.participants) {
                        Transition(state)
                    } else {
                        Transition(
                            state.copy(participants = state.participants + event.senderId),
                            Call.Accept(state.callId),
                        )
                    }
                else -> Transition(state)
            }

            is Call.Decline ->
                if (state is CallState.Outgoing) {
                    if (state.isGroup) {
                        // One member saying no is not the call failing: the rest are still
                        // ringing, and a group call ends when nobody answers it, not when
                        // somebody declines it.
                        Transition(state)
                    } else {
                        val outcome = if (message.reason == Call.Reason.BUSY) {
                            Outcome.BUSY
                        } else {
                            Outcome.DECLINED
                        }
                        Transition(CallState.Ended(state.callId, state.peerId, outcome))
                    }
                } else {
                    Transition(state)
                }

            is Call.Cancel ->
                if (state is CallState.Incoming) {
                    // They gave up, or their offer expired on their side. Either way, missed.
                    Transition(CallState.Ended(state.callId, state.peerId, Outcome.UNANSWERED))
                } else {
                    Transition(state)
                }

            // "I have left." Not "the call is over" — for a room those differ, and the room
            // outlives anyone who leaves it. It only ends for us when nobody else is left.
            is Call.End -> when (state) {
                is CallState.Connected -> {
                    val remaining = state.participants - event.senderId
                    if (remaining.isEmpty()) {
                        Transition(CallState.Ended(state.callId, event.senderId, Outcome.HUNG_UP))
                    } else {
                        Transition(state.copy(participants = remaining))
                    }
                }
                else -> Transition(state)
            }

            is Call.Offer -> Transition(state) // Handled above; unreachable.
        }
    }
}
