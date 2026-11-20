package id.larus.mqttgroup

import id.larus.mqttgroup.call.CallMachine
import id.larus.mqttgroup.call.CallState
import id.larus.mqttgroup.call.Event
import id.larus.mqttgroup.call.Outcome
import id.larus.mqttgroup.call.Transition
import id.larus.mqttgroup.mqtt.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call state machine is the one part of calling that can be tested without a phone, a
 * broker or a second person, so everything that decides what a call does lives in it and is
 * tested here.
 */
class CallMachineTest {

    private val me = "u-me"
    private val them = "u-them"
    private val other = "u-other"

    private fun reduce(state: CallState, event: Event, now: Long = 1_000L): Transition =
        CallMachine.reduce(state, event, selfId = me, now = now)

    private fun dialling(): CallState.Outgoing =
        reduce(CallState.Idle, Event.Dial("c-1", them, Call.Mode.AUDIO)).state as CallState.Outgoing

    private fun ringing(): CallState.Incoming = reduce(
        CallState.Idle,
        Event.Received(them, Call.Offer("c-1", me, Call.Mode.AUDIO, 45)),
    ).state as CallState.Incoming

    private fun connected(): CallState.Connected =
        reduce(ringing(), Event.Accept, now = 5_000L).state as CallState.Connected

    // --- the happy paths ---------------------------------------------------------------------

    @Test
    fun `dialling publishes an offer and waits`() {
        val t = reduce(CallState.Idle, Event.Dial("c-1", them, Call.Mode.AUDIO))
        assertEquals(CallState.Outgoing("c-1", them, Call.Mode.AUDIO, reached = false), t.state)
        assertEquals(Call.Offer("c-1", them, Call.Mode.AUDIO, CallMachine.TTL_SEC), t.publish)
    }

    /** The whole point of the `ring` message: "calling" and "ringing" are different facts. */
    @Test
    fun `their ring is what turns calling into ringing`() {
        val t = reduce(dialling(), Event.Received(them, Call.Ring("c-1")))
        assertTrue((t.state as CallState.Outgoing).reached)
        assertNull(t.publish)
    }

    @Test
    fun `an offer addressed to us rings this phone`() {
        val t = reduce(CallState.Idle, Event.Received(them, Call.Offer("c-1", me, Call.Mode.AUDIO, 45)))
        assertEquals(CallState.Incoming("c-1", them, Call.Mode.AUDIO), t.state)
        // Nothing is published here: the engine sends `ring` once it has actually started
        // ringing, which is a fact about this phone and not about the machine.
        assertNull(t.publish)
    }

    @Test
    fun `answering connects both sides`() {
        val answer = reduce(ringing(), Event.Accept, now = 5_000L)
        assertEquals(CallState.Connected("c-1", setOf(them), Call.Mode.AUDIO, 5_000L), answer.state)
        assertEquals(Call.Accept("c-1"), answer.publish)

        val caller = reduce(dialling(), Event.Received(them, Call.Accept("c-1")), now = 5_000L)
        assertEquals(CallState.Connected("c-1", setOf(them), Call.Mode.AUDIO, 5_000L), caller.state)
    }

    /** A direct call is a room with one other person in it, so this keeps reading naturally. */
    @Test
    fun `peerId is the other person while there is exactly one`() {
        assertEquals(them, connected().peerId)
    }

    // --- the red button, which means three different things -----------------------------------

    @Test
    fun `the red button cancels, declines or ends depending on where the call is`() {
        assertEquals(Call.Cancel("c-1"), reduce(dialling(), Event.Hangup).publish)
        assertEquals(Call.Decline("c-1", Call.Reason.DECLINED), reduce(ringing(), Event.Hangup).publish)
        assertEquals(Call.End("c-1"), reduce(connected(), Event.Hangup).publish)
    }

    @Test
    fun `an outcome is kept for each way a call can stop`() {
        assertEquals(Outcome.CANCELLED, ended(reduce(dialling(), Event.Hangup)))
        assertEquals(Outcome.DECLINED, ended(reduce(ringing(), Event.Hangup)))
        assertEquals(Outcome.HUNG_UP, ended(reduce(connected(), Event.Hangup)))
        assertEquals(
            Outcome.DECLINED,
            ended(reduce(dialling(), Event.Received(them, Call.Decline("c-1", Call.Reason.DECLINED)))),
        )
        assertEquals(
            Outcome.BUSY,
            ended(reduce(dialling(), Event.Received(them, Call.Decline("c-1", Call.Reason.BUSY)))),
        )
        assertEquals(
            Outcome.UNANSWERED,
            ended(reduce(ringing(), Event.Received(them, Call.Cancel("c-1")))),
        )
        assertEquals(
            Outcome.HUNG_UP,
            ended(reduce(connected(), Event.Received(them, Call.End("c-1")))),
        )
    }

    // --- one call at a time -------------------------------------------------------------------

    /** A phone that rings during a call is a phone whose owner now has two problems. */
    @Test
    fun `a second offer is declined as busy without anyone hearing it`() {
        val t = reduce(connected(), Event.Received(other, Call.Offer("c-2", me, Call.Mode.AUDIO, 45)))
        assertEquals(connected(), t.state)
        assertEquals(Call.Decline("c-2", Call.Reason.BUSY), t.publish)
    }

    @Test
    fun `dialling is refused while a call is already up`() {
        val t = reduce(connected(), Event.Dial("c-2", other, Call.Mode.AUDIO))
        assertEquals(connected(), t.state)
        assertNull(t.publish)
    }

    // --- traffic that is not ours -------------------------------------------------------------

    /** Every member of the group decrypts every offer. Most of them are for somebody else. */
    @Test
    fun `an offer for another member is ignored, not rung`() {
        val t = reduce(CallState.Idle, Event.Received(them, Call.Offer("c-1", other, Call.Mode.AUDIO, 45)))
        assertEquals(CallState.Idle, t.state)
        assertNull(t.publish)
    }

    /**
     * A late message for a call that already ended must not tear down the call that replaced
     * it — which is the reason every message names a call id at all.
     */
    @Test
    fun `a message for a different call is dropped`() {
        val t = reduce(connected(), Event.Received(them, Call.End("c-OLD")))
        assertEquals(connected(), t.state)
    }

    /**
     * A shared group key cannot prove who sent a message, but a call can at least require the
     * same someone throughout — otherwise any member could hang up a call between two others.
     */
    @Test
    fun `a third member cannot end someone else's call`() {
        val t = reduce(connected(), Event.Received(other, Call.End("c-1")))
        assertEquals(connected(), t.state)
    }

    // --- timeouts -----------------------------------------------------------------------------

    /** The caller cancels rather than waiting for the callee's own clock, because the two
     * clocks did not start together — which is why the ttl is a duration in the first place. */
    @Test
    fun `an unanswered outgoing call times out and tells the other end to stop`() {
        val t = reduce(dialling(), Event.Timeout("c-1"))
        assertEquals(Outcome.UNANSWERED, ended(t))
        assertEquals(Call.Cancel("c-1"), t.publish)
    }

    /**
     * Silence, deliberately. A decline would make "nobody was near the phone" look identical to
     * "they looked at it and said no", and the caller's screen would repeat that.
     */
    @Test
    fun `a missed incoming call says nothing back`() {
        val t = reduce(ringing(), Event.Timeout("c-1"))
        assertEquals(Outcome.UNANSWERED, ended(t))
        assertNull(t.publish)
    }

    /** A stale timer must not end the call that replaced the one it was set for. */
    @Test
    fun `a timeout for a call that has moved on does nothing`() {
        val state = connected()
        assertEquals(state, reduce(state, Event.Timeout("c-OLD")).state)
    }

    @Test
    fun `a connected call has no timeout`() {
        val state = connected()
        assertEquals(state, reduce(state, Event.Timeout("c-1")).state)
    }

    // --- housekeeping -------------------------------------------------------------------------

    @Test
    fun `dismissing a finished call returns to idle, and dialling again is allowed`() {
        val done = reduce(connected(), Event.Hangup).state
        assertEquals(CallState.Idle, reduce(done, Event.Dismiss).state)
        // Without waiting for the dismissal, too — a second call should not need the first
        // one's "call ended" banner to be tapped away first.
        assertTrue(reduce(done, Event.Dial("c-2", other, Call.Mode.AUDIO)).state is CallState.Outgoing)
    }

    @Test
    fun `busy covers exactly the states where a person is committed`() {
        assertEquals(false, CallState.Idle.isBusy)
        assertTrue(dialling().isBusy)
        assertTrue(ringing().isBusy)
        assertTrue(connected().isBusy)
        assertEquals(false, (reduce(connected(), Event.Hangup).state).isBusy)
    }

    // --- group calls, phase 10 ------------------------------------------------------------------

    private val third = "u-third"

    private fun groupRinging(): CallState.Incoming = reduce(
        CallState.Idle,
        Event.Received(them, Call.Offer("c-9", null, Call.Mode.AUDIO, 45)),
    ).state as CallState.Incoming

    private fun inRoom(): CallState.Connected =
        reduce(groupRinging(), Event.Accept, now = 5_000L).state as CallState.Connected

    @Test
    fun `dialling the group publishes an offer with no target`() {
        val t = reduce(CallState.Idle, Event.Dial("c-9", null, Call.Mode.AUDIO))
        val out = t.state as CallState.Outgoing
        assertTrue(out.isGroup)
        assertEquals(Call.Offer("c-9", null, Call.Mode.AUDIO, CallMachine.TTL_SEC), t.publish)
    }

    /** A group offer names nobody, so it is for everybody — including members it was not
     * addressed to, which is the entire difference from a direct call. */
    @Test
    fun `an offer with no target rings every member`() {
        val t = reduce(CallState.Idle, Event.Received(them, Call.Offer("c-9", null, Call.Mode.AUDIO, 45)))
        assertEquals(CallState.Incoming("c-9", them, Call.Mode.AUDIO, isGroup = true), t.state)
    }

    /** Our own group offer comes back from the broker, because we are in the group we rang. */
    @Test
    fun `our own group offer does not ring this phone`() {
        val dialling = reduce(CallState.Idle, Event.Dial("c-9", null, Call.Mode.AUDIO)).state
        val t = reduce(dialling, Event.Received(me, Call.Offer("c-9", null, Call.Mode.AUDIO, 45)))
        assertEquals(dialling, t.state)
    }

    /**
     * The roster, rebuilt from traffic because nothing on this topic is retained: a newcomer's
     * accept is answered with our own, so they learn we are here.
     */
    @Test
    fun `someone joining a room we are in is added, and told we are here`() {
        val t = reduce(inRoom(), Event.Received(third, Call.Accept("c-9")))
        assertEquals(setOf(them, third), (t.state as CallState.Connected).participants)
        assertEquals(Call.Accept("c-9"), t.publish)
    }

    /** Only a newcomer. Answering every accept would be a storm that never settles. */
    @Test
    fun `an accept from someone already in the room is not answered again`() {
        val room = reduce(inRoom(), Event.Received(third, Call.Accept("c-9"))).state
        val t = reduce(room, Event.Received(third, Call.Accept("c-9")))
        assertEquals(room, t.state)
        assertNull(t.publish)
    }

    /** "I have left" is not "the call is over" — the room outlives whoever leaves it. */
    @Test
    fun `one person leaving a room of three leaves the call running`() {
        val room = reduce(inRoom(), Event.Received(third, Call.Accept("c-9"))).state
        val t = reduce(room, Event.Received(them, Call.End("c-9")))
        assertEquals(setOf(third), (t.state as CallState.Connected).participants)
    }

    /** Sitting alone in a room waiting is not a state worth showing anyone. */
    @Test
    fun `the call ends when the last other person leaves`() {
        val t = reduce(inRoom(), Event.Received(them, Call.End("c-9")))
        assertEquals(Outcome.HUNG_UP, ended(t))
    }

    /** Leaving is still leaving: the others carry on, we are done. */
    @Test
    fun `leaving a room ends it for us and says so once`() {
        val room = reduce(inRoom(), Event.Received(third, Call.Accept("c-9"))).state
        val t = reduce(room, Event.Hangup)
        assertEquals(Outcome.HUNG_UP, ended(t))
        assertEquals(Call.End("c-9"), t.publish)
    }

    /**
     * A direct call is a room of one, so a member who was never in it still cannot join or end
     * it — the group exception applies only to an accept on a call that was rung to the group.
     */
    @Test
    fun `a stranger cannot join a direct call`() {
        val t = reduce(connected(), Event.Received(other, Call.Accept("c-1")))
        assertEquals(connected(), t.state)
        assertNull(t.publish)
    }

    @Test
    fun `a group call that nobody joins still times out`() {
        val dialling = reduce(CallState.Idle, Event.Dial("c-9", null, Call.Mode.AUDIO)).state
        val t = reduce(dialling, Event.Timeout("c-9"))
        assertEquals(Outcome.UNANSWERED, ended(t))
        assertEquals(Call.Cancel("c-9"), t.publish)
    }

    /** Once somebody is in, the offer expiring is irrelevant — late joiners stay welcome. */
    @Test
    fun `a group call somebody joined is not ended by the ringing timeout`() {
        val room = inRoom()
        assertEquals(room, reduce(room, Event.Timeout("c-9")).state)
    }

    private fun groupDialling(): CallState =
        reduce(CallState.Idle, Event.Dial("c-9", null, Call.Mode.AUDIO)).state

    /**
     * The whole group-caller path, which had no test at all and did not work: a group offer is
     * rung to nobody in particular, so `Outgoing.peerId` is null, and the "is this sender part
     * of this call?" check read `null == senderId` and dropped every reply. The group rang and
     * then nothing happened.
     */
    @Test
    fun `the caller of a group call connects when somebody answers`() {
        val t = reduce(groupDialling(), Event.Received(them, Call.Accept("c-9")), now = 5_000L)
        val room = t.state as CallState.Connected
        assertEquals(setOf(them), room.participants)
        assertTrue(room.isGroup)
    }

    @Test
    fun `the caller of a group call sees it ringing when anyone rings`() {
        val t = reduce(groupDialling(), Event.Received(other, Call.Ring("c-9")))
        assertTrue((t.state as CallState.Outgoing).reached)
    }

    /** A room fills from the caller's side too: the second person to answer joins the first. */
    @Test
    fun `a second answer joins the room the caller is already in`() {
        val room = reduce(groupDialling(), Event.Received(them, Call.Accept("c-9"))).state
        val t = reduce(room, Event.Received(other, Call.Accept("c-9")))
        assertEquals(setOf(them, other), (t.state as CallState.Connected).participants)
    }

    /**
     * One member saying no is not the call failing — the rest are still ringing. A direct call
     * is the opposite, and that difference is the reason this is worth a test.
     */
    @Test
    fun `one member declining does not end a group call`() {
        val t = reduce(
            groupDialling(),
            Event.Received(them, Call.Decline("c-9", Call.Reason.DECLINED)),
        )
        assertTrue(t.state is CallState.Outgoing)

        val direct = reduce(dialling(), Event.Received(them, Call.Decline("c-1", Call.Reason.DECLINED)))
        assertEquals(Outcome.DECLINED, ended(direct))
    }

    private fun ended(t: Transition): Outcome = (t.state as CallState.Ended).outcome
}
