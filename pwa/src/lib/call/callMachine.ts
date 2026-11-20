import type { Call, CallMode, DeclineReason } from '$lib/mqtt/callMessages';
import { DEFAULT_TTL_SEC } from '$lib/mqtt/callMessages';

/**
 * One call's life, as a pure function.
 *
 * Everything that decides *what a call does* lives here and touches nothing: no Dexie, no MQTT
 * client, no DOM. What is left outside is only plumbing — publish this, ring that, cancel a
 * timer — which is the part that could not be unit tested anyway. The same machine is written
 * again in `../family-group/app/src/main/java/id/larus/mqttgroup/call/CallMachine.kt`, and the
 * two are expected to stay recognisably the same shape.
 *
 * The rules that are easy to get wrong, in one place:
 *
 * - **A device holds at most one call.** A second offer arriving during one is declined as busy
 *   without ever reaching a person, because a device that rings during a call is one whose
 *   owner now has two problems.
 * - **Every message names a call id, and one for a call we are not in is ignored.** That is what
 *   makes a late `cancel` for a call that already ended harmless, rather than something that
 *   tears down the call that replaced it.
 * - **Timeouts are durations counted locally**, never deadlines read off a message — see
 *   `OfferCall.ttlSec`. The machine is handed a `timeout` event by whoever owns a clock; it does
 *   not read one.
 */

/** No call. The state a device is in almost all of the time. */
export interface IdleState {
	phase: 'idle';
}

/**
 * We are calling someone. `reached` flips when their `ring` arrives, and is the whole reason
 * that message exists: "Calling…" means we published into the void, "Ringing…" means a device
 * that is awake has it. Since a browser tab that is closed cannot ring at all, that distinction
 * is not cosmetic here — it is how a caller knows to expect a callback rather than an answer.
 */
export interface OutgoingState {
	phase: 'outgoing';
	callId: string;
	/** The member being dialled, or null when the whole group is being rung. */
	peerId: string | null;
	mode: CallMode;
	reached: boolean;
	isGroup: boolean;
}

/** Someone is calling us and this device is ringing. `peerId` is who called. */
export interface IncomingState {
	phase: 'incoming';
	callId: string;
	peerId: string;
	mode: CallMode;
	/** True when this rang because the whole group was called rather than us by name. */
	isGroup: boolean;
}

/**
 * Answered. Silent in this phase — there is no media yet — but a real state with a real end, so
 * that phase 9 adds audio to a call that already knows how to start and stop.
 */
export interface ConnectedState {
	phase: 'connected';
	callId: string;
	/**
	 * Everyone else in the room, which is a set because a group call is a room and not a pair.
	 * It grows as people join and shrinks as they leave, and when it empties the call is over —
	 * sitting alone in a room waiting is not a state worth showing anyone.
	 *
	 * It may be empty for a moment at the very start of a group call we placed: we are connected
	 * to the room before anybody has answered.
	 */
	participants: string[];
	mode: CallMode;
	/** For the call timer. Taken from the same clock the events' `now` comes from. */
	since: number;
	isGroup: boolean;
}

/** Over, and why. Held briefly so the UI can say what happened before returning to idle. */
export interface EndedState {
	phase: 'ended';
	callId: string;
	peerId: string | null;
	outcome: Outcome;
}

export type CallState = IdleState | OutgoingState | IncomingState | ConnectedState | EndedState;

export type Outcome =
	/** They pressed the red button. */
	| 'declined'
	/** They were already on a call. Never reached a person on their end. */
	| 'busy'
	/** We gave up before they answered. */
	| 'cancelled'
	/** Nobody answered before the offer's life ran out. On the callee's side this is the missed
	 * call; on the caller's it is the unanswered one. */
	| 'unanswered'
	/** A connected call that one side hung up. */
	| 'hung-up';

export type Event =
	/** This device wants to call `peerId`, or the whole group when it is null. `callId` is
	 * minted by the caller. */
	| { type: 'dial'; callId: string; peerId: string | null; mode: CallMode }
	/** A call message arrived and opened under the group key. `senderId` is the *envelope's*
	 * authenticated sender, never anything from the body. */
	| { type: 'received'; senderId: string; message: Call }
	/** The person pressed answer. */
	| { type: 'accept' }
	/** The person pressed the red button: decline while ringing, hang up while connected, cancel
	 * while dialling. Which of the three it means is the machine's business, not the button's. */
	| { type: 'hangup' }
	/** The offer's life ran out, counted locally by whoever owns the clock. */
	| { type: 'timeout'; callId: string }
	/** The UI has shown the ended state for long enough. */
	| { type: 'dismiss' };

/**
 * What the machine decided: the state to move to, and at most one message to put on the wire.
 *
 * `publish` is deliberately not "send this now" — it becomes a row in the outbox like everything
 * else in this app, carrying the short deadline call signalling gets. Nothing in this file knows
 * that, which is the point.
 */
export interface Transition {
	state: CallState;
	publish?: Call;
}

export const IDLE: IdleState = { phase: 'idle' };

/** How long our own ringing lasts, and what we put in an offer we send. */
export const TTL_SEC = DEFAULT_TTL_SEC;

/**
 * A buzz rings for longer than a call, because it answers a different question. Forty-five
 * seconds is how long someone waits for a person to pick up; finding a phone down the back of a
 * sofa takes longer, and nobody is being kept waiting while it happens.
 */
export const BUZZ_TTL_SEC = 90;

export const ttlFor = (mode: CallMode): number => (mode === 'buzz' ? BUZZ_TTL_SEC : TTL_SEC);

export const callIdOf = (state: CallState): string | null =>
	state.phase === 'idle' ? null : state.callId;

export const peerIdOf = (state: CallState): string | null => {
	if (state.phase === 'idle') return null;
	// A direct call is a room with one other person in it, so this keeps reading naturally.
	if (state.phase === 'connected')
		return state.participants.length === 1 ? state.participants[0] : null;
	return state.peerId;
};

/** True while this device should be refusing new calls. */
export const isBusy = (state: CallState): boolean =>
	state.phase === 'outgoing' || state.phase === 'incoming' || state.phase === 'connected';

/**
 * @param selfId this device's user id, so an offer addressed to another member of the group can
 *   be told from one addressed to us. Every member decrypts every offer — the topic is the
 *   group's — so this check is the ordinary path and not a defence against anything.
 */
export function reduce(state: CallState, event: Event, selfId: string, now: number): Transition {
	switch (event.type) {
		case 'dial':
			// Dialling while already in a call is a UI that should not have offered the button.
			// Refusing here rather than trusting that is what keeps one-call-at-a-time an
			// invariant of the machine instead of a habit of its callers.
			if (state.phase !== 'idle' && state.phase !== 'ended') return { state };
			return {
				state: {
					phase: 'outgoing',
					callId: event.callId,
					peerId: event.peerId,
					mode: event.mode,
					reached: false,
					isGroup: event.peerId === null
				},
				publish: {
					kind: 'offer',
					callId: event.callId,
					toUserId: event.peerId,
					mode: event.mode,
					ttlSec: ttlFor(event.mode)
				}
			};

		case 'received':
			return received(state, event.senderId, event.message, selfId, now);

		case 'accept':
			if (state.phase !== 'incoming') return { state };
			// A buzz has nothing to answer. It rings until somebody stops it, and the only two
			// buttons that exist for one are Stop at either end — so an accept here would put a
			// call into a state its own UI has no way out of.
			if (state.mode === 'buzz') return { state };
			return {
				state: {
					phase: 'connected',
					callId: state.callId,
					// The caller is the only person we know is here. Anyone else already in the
					// room answers our accept with their own, which is how the rest arrive.
					participants: [state.peerId],
					mode: state.mode,
					since: now,
					isGroup: state.isGroup
				},
				publish: { kind: 'accept', callId: state.callId }
			};

		case 'hangup':
			// Three different words for one red button, and the difference is real: a cancel says
			// "I gave up", a decline says "I said no", an end says "we are done".
			switch (state.phase) {
				case 'outgoing':
					return {
						state: ended(state.callId, state.peerId, 'cancelled'),
						publish: { kind: 'cancel', callId: state.callId }
					};
				case 'incoming':
					return {
						state: ended(state.callId, state.peerId, 'declined'),
						publish: { kind: 'decline', callId: state.callId, reason: 'declined' }
					};
				// "I have left." For two people that is the same as ending the call; for a
				// room it is not, and the others carry on without us.
				case 'connected':
					return {
						state: ended(state.callId, peerIdOf(state), 'hung-up'),
						publish: { kind: 'end', callId: state.callId }
					};
				default:
					return { state };
			}

		case 'timeout':
			// A timer for a call that has already moved on. Firing it would end the call that
			// replaced this one, which is exactly what the id check is here to prevent.
			if (callIdOf(state) !== event.callId) return { state };
			if (state.phase === 'outgoing') {
				return {
					state: ended(state.callId, state.peerId, 'unanswered'),
					// Tell them to stop ringing. Their own timer would do it a moment later, but
					// only if their offer arrived the instant ours was sent — and the whole reason
					// the ttl is a duration is that we cannot assume that.
					publish: { kind: 'cancel', callId: state.callId }
				};
			}
			if (state.phase === 'incoming') {
				// No message. A caller still waiting will time out on their own clock, and one who
				// gave up already sent a cancel. Answering a timeout with a decline would also make
				// "nobody was near the device" indistinguishable from "they looked at it and said
				// no" — a small lie the other end's UI would then repeat.
				return { state: ended(state.callId, state.peerId, 'unanswered') };
			}
			return { state };

		case 'dismiss':
			return { state: state.phase === 'ended' ? IDLE : state };
	}
}

function received(
	state: CallState,
	senderId: string,
	message: Call,
	selfId: string,
	now: number
): Transition {
	if (message.kind === 'offer') {
		const forGroup = message.toUserId === null;
		// Someone else's call, overheard. Every member of the group decrypts every offer, so
		// this is ordinary traffic and not an attack — it is simply not for us. A group offer is
		// for everybody, so nobody is excluded by this.
		if (!forGroup && message.toUserId !== selfId) return { state };
		// Our own offer coming back from the broker — which a group offer always does, since we
		// are a member of the group we just rang.
		if (senderId === selfId) return { state };
		if (state.phase === 'idle' || state.phase === 'ended') {
			return {
				state: {
					phase: 'incoming',
					callId: message.callId,
					peerId: senderId,
					mode: message.mode,
					isGroup: forGroup
				}
			};
		}
		// Already in a call. Declined without a person ever hearing it ring.
		return { state, publish: { kind: 'decline', callId: message.callId, reason: 'busy' } };
	}

	// Everything else is about a call in progress, and only about *ours*. A message for a call we
	// are not in is not an error — a late cancel for a call that already ended is ordinary — so
	// it is dropped rather than acted on.
	if (callIdOf(state) !== message.callId) return { state };
	// And only from someone actually in this call, so a member who is not cannot hang up a call
	// between others. A shared key cannot prove who sent a message, but it can at least require
	// the sender to be someone the call already knows about.
	//
	// A connected group call is the exception, and deliberately: an `accept` from somebody not
	// yet in the room is exactly how a person joins one, so it is handled below.
	// A group call was rung to nobody in particular, so it has no peer to compare against and
	// *anyone* in the group may answer it. Without the `isGroup` case this reads `null ===
	// senderId`, drops the accept, and the call rings and then does nothing.
	const known =
		state.phase === 'connected'
			? state.participants.includes(senderId) || (state.isGroup && message.kind === 'accept')
			: state.phase === 'outgoing'
				? state.isGroup || state.peerId === senderId
				: peerIdOf(state) === senderId;
	if (!known) return { state };

	switch (message.kind) {
		case 'ring':
			return state.phase === 'outgoing' ? { state: { ...state, reached: true } } : { state };

		case 'accept':
			// The first person to answer our call. For a direct call that is the whole thing;
			// for a group call it is the room opening, and others may follow.
			if (state.phase === 'outgoing') {
				return {
					state: {
						phase: 'connected',
						callId: state.callId,
						participants: [senderId],
						mode: state.mode,
						since: now,
						isGroup: state.isGroup
					}
				};
			}
			// Somebody joining a room we are already in. We answer with our own accept so that
			// *they* learn we are here — nothing on this topic is retained, so the roster is
			// rebuilt from traffic. Only for a newcomer, and only once, which is what keeps this
			// from becoming a storm.
			if (state.phase === 'connected') {
				if (state.participants.includes(senderId)) return { state };
				return {
					state: { ...state, participants: [...state.participants, senderId] },
					publish: { kind: 'accept', callId: state.callId }
				};
			}
			return { state };

		case 'decline':
			if (state.phase !== 'outgoing') return { state };
			// One member saying no is not the call failing: the rest are still ringing, and a
			// group call ends when nobody answers it, not when somebody declines it.
			if (state.isGroup) return { state };
			return { state: ended(state.callId, state.peerId, outcomeOf(message.reason)) };

		case 'cancel':
			// They gave up, or their offer expired on their side. Either way, missed.
			if (state.phase !== 'incoming') return { state };
			return { state: ended(state.callId, state.peerId, 'unanswered') };

		// "I have left." Not "the call is over" — for a room those differ, and the room outlives
		// anyone who leaves it. It only ends for us when nobody else is left.
		case 'end': {
			if (state.phase !== 'connected') return { state };
			const remaining = state.participants.filter((p) => p !== senderId);
			if (remaining.length === 0) return { state: ended(state.callId, senderId, 'hung-up') };
			return { state: { ...state, participants: remaining } };
		}
	}
}

const outcomeOf = (reason: DeclineReason): Outcome => (reason === 'busy' ? 'busy' : 'declined');

const ended = (callId: string, peerId: string | null, outcome: Outcome): EndedState => ({
	phase: 'ended',
	callId,
	peerId,
	outcome
});
