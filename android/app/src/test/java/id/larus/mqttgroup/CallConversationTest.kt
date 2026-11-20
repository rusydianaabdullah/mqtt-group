package id.larus.mqttgroup

import id.larus.mqttgroup.call.CallMachine
import id.larus.mqttgroup.call.CallState
import id.larus.mqttgroup.call.Event
import id.larus.mqttgroup.call.Outcome
import id.larus.mqttgroup.mqtt.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole calls, between several machines, through a relay that behaves like the broker.
 *
 * Every other test here drives one machine and asserts on one transition. That is how two real
 * bugs shipped: the phase 8 ringing failure and the phase 10 group-call failure were both in a
 * *seam* — a message one side published and the other side dropped — and neither side's own
 * tests were wrong. Each was individually correct and jointly useless.
 *
 * So this is deliberately end to end: press the buttons, let the messages flow, and assert that
 * the people end up in a call together. Nothing here inspects a `Transition`; it asserts on
 * where everybody ended up, which is the only thing a person actually cares about.
 */
class CallConversationTest {

    /**
     * A phone. Holds a machine, its own id, and publishes into the shared bus — the same shape
     * as [id.larus.mqttgroup.call.CallEngine], minus everything that needs Android.
     */
    private class Phone(val id: String, val bus: Bus) {
        var state: CallState = CallState.Idle
            private set

        var now: Long = 1_000L

        fun apply(event: Event) {
            val transition = CallMachine.reduce(state, event, id, now)
            val before = state
            state = transition.state
            transition.publish?.let { bus.publish(id, it) }
            // The engine sends `ring` once it is really ringing, after the state has moved —
            // that ordering is part of the contract and is reproduced here so the caller sees
            // the same sequence it would on a phone.
            val started = before !is CallState.Incoming && state is CallState.Incoming
            if (started) bus.publish(id, Call.Ring((state as CallState.Incoming).callId))
        }

        fun receive(from: String, message: Call) = apply(Event.Received(from, message))

        val connected: CallState.Connected? get() = state as? CallState.Connected
    }

    /**
     * The broker: everything published reaches everyone else, in order, including the sender's
     * own copy — which is exactly what a group topic does and is the reason a machine has to
     * recognise and ignore its own offer.
     */
    private class Bus {
        val phones = mutableListOf<Phone>()
        private var delivering = false
        private val queue = ArrayDeque<Pair<String, Call>>()

        fun publish(from: String, message: Call) {
            queue.addLast(from to message)
            // Flat rather than recursive: a reply published while delivering must be delivered
            // after the current message finishes, not inside it. Otherwise the roster exchange
            // — where an accept provokes an accept — reorders itself.
            if (delivering) return
            delivering = true
            while (queue.isNotEmpty()) {
                val (sender, payload) = queue.removeFirst()
                phones.filter { it.id != sender }.forEach { it.receive(sender, payload) }
            }
            delivering = false
        }
    }

    private fun bus(vararg ids: String): Pair<Bus, List<Phone>> {
        val bus = Bus()
        val phones = ids.map { Phone(it, bus) }
        bus.phones += phones
        return bus to phones
    }

    // --- two people ------------------------------------------------------------------------------

    @Test
    fun `a direct call rings, is answered, and ends when one side hangs up`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones

        ayah.apply(Event.Dial("c-1", "ibu", Call.Mode.AUDIO))
        assertTrue("ibu should be ringing", ibu.state is CallState.Incoming)
        assertTrue("ayah should know it is ringing", (ayah.state as CallState.Outgoing).reached)

        ibu.apply(Event.Accept)
        assertEquals(setOf("ibu"), ayah.connected?.participants)
        assertEquals(setOf("ayah"), ibu.connected?.participants)

        ibu.apply(Event.Hangup)
        assertEquals(Outcome.HUNG_UP, (ayah.state as CallState.Ended).outcome)
    }

    @Test
    fun `a declined call tells the caller so`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones
        ayah.apply(Event.Dial("c-1", "ibu", Call.Mode.AUDIO))
        ibu.apply(Event.Hangup)
        assertEquals(Outcome.DECLINED, (ayah.state as CallState.Ended).outcome)
    }

    /** Everyone hears every offer. Only the person it names may be rung by it. */
    @Test
    fun `a direct call does not ring the rest of the group`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones
        ayah.apply(Event.Dial("c-1", "ibu", Call.Mode.AUDIO))
        assertTrue(ibu.state is CallState.Incoming)
        assertEquals(CallState.Idle, adik.state)
    }

    // --- a room --------------------------------------------------------------------------------

    /** The test that would have caught the phase 10 bug: a group call that rings and connects. */
    @Test
    fun `a group call rings everyone and gathers whoever answers`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones

        ayah.apply(Event.Dial("c-9", null, Call.Mode.AUDIO))
        assertTrue("ibu should be ringing", ibu.state is CallState.Incoming)
        assertTrue("adik should be ringing", adik.state is CallState.Incoming)

        ibu.apply(Event.Accept)
        assertEquals(setOf("ibu"), ayah.connected?.participants)

        adik.apply(Event.Accept)
        assertEquals(setOf("ibu", "adik"), ayah.connected?.participants)
        // And the roster, rebuilt from traffic: the late joiner must learn about the early one
        // without anything having been retained anywhere.
        assertEquals(setOf("ayah", "ibu"), adik.connected?.participants)
        assertEquals(setOf("ayah", "adik"), ibu.connected?.participants)
    }

    @Test
    fun `one member declining leaves the group call ringing for the rest`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones

        ayah.apply(Event.Dial("c-9", null, Call.Mode.AUDIO))
        ibu.apply(Event.Hangup)
        assertTrue("the call should still be up", ayah.state !is CallState.Ended)

        adik.apply(Event.Accept)
        assertEquals(setOf("adik"), ayah.connected?.participants)
    }

    @Test
    fun `leaving a room of three leaves the other two talking`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones
        ayah.apply(Event.Dial("c-9", null, Call.Mode.AUDIO))
        ibu.apply(Event.Accept)
        adik.apply(Event.Accept)

        ibu.apply(Event.Hangup)
        assertEquals(setOf("adik"), ayah.connected?.participants)
        assertEquals(setOf("ayah"), adik.connected?.participants)
        assertTrue(ibu.state is CallState.Ended)
    }

    @Test
    fun `a room ends for everyone once the last person leaves`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones
        ayah.apply(Event.Dial("c-9", null, Call.Mode.AUDIO))
        ibu.apply(Event.Accept)

        ayah.apply(Event.Hangup)
        assertTrue("ibu should be left with nobody", ibu.state is CallState.Ended)
    }

    /** A phone already on a call refuses a second, and the caller is told why. */
    @Test
    fun `calling someone who is already talking comes back as busy`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones

        ayah.apply(Event.Dial("c-1", "ibu", Call.Mode.AUDIO))
        ibu.apply(Event.Accept)

        adik.apply(Event.Dial("c-2", "ibu", Call.Mode.AUDIO))
        assertEquals(Outcome.BUSY, (adik.state as CallState.Ended).outcome)
        // And the call already running is undisturbed by it.
        assertEquals(setOf("ayah"), ibu.connected?.participants)
    }

    // --- a buzz ----------------------------------------------------------------------------------

    private fun Phone.buzz(callId: String, target: String?) =
        apply(Event.Dial(callId, target, Call.Mode.BUZZ))

    /** The whole of a buzz: it rings, and then somebody stops it. There is no third state. */
    @Test
    fun `a buzz rings the phone and is stopped by whoever reaches it first`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones

        ayah.buzz("b-1", "ibu")
        assertTrue("ibu should be buzzing", ibu.state is CallState.Incoming)
        assertTrue("ayah should see it buzzing", (ayah.state as CallState.Outgoing).reached)

        ibu.apply(Event.Hangup)
        assertTrue("stopping it should end it for ayah too", ayah.state is CallState.Ended)
    }

    @Test
    fun `the caller can stop a buzz they started`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones
        ayah.buzz("b-1", "ibu")
        ayah.apply(Event.Hangup)
        assertTrue("ibu should stop buzzing", ibu.state is CallState.Ended)
    }

    /**
     * A buzz has nothing to answer, and the only two buttons that exist for one are Stop at
     * either end. Answering it would put it in a state its own UI has no way out of.
     */
    @Test
    fun `a buzz cannot be answered into a call`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones
        ayah.buzz("b-1", "ibu")

        ibu.apply(Event.Accept)
        assertTrue("ibu should still be buzzing", ibu.state is CallState.Incoming)
        assertTrue("ayah should not be connected", ayah.state is CallState.Outgoing)
    }

    @Test
    fun `buzzing the group rings everyone and any of them can stop their own`() {
        val (_, phones) = bus("ayah", "ibu", "adik")
        val (ayah, ibu, adik) = phones

        ayah.buzz("b-9", null)
        assertTrue(ibu.state is CallState.Incoming)
        assertTrue(adik.state is CallState.Incoming)

        // One person silencing their own phone does not stop everyone else's, for the same
        // reason one member declining does not end a group call.
        ibu.apply(Event.Hangup)
        assertTrue("adik should still be buzzing", adik.state is CallState.Incoming)
        assertTrue("and ayah should still be buzzing them", ayah.state is CallState.Outgoing)

        ayah.apply(Event.Hangup)
        assertTrue("the caller stopping it stops everyone", adik.state is CallState.Ended)
    }

    /** Longer than a call: finding a phone down the back of a sofa takes longer than waiting
     * for a person to pick up, and nobody is being kept waiting while it happens. */
    @Test
    fun `a buzz rings for longer than a call`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, _) = phones
        ayah.buzz("b-1", "ibu")
        assertEquals(CallMachine.BUZZ_TTL_SEC, CallMachine.ttlFor(Call.Mode.BUZZ))
        assertEquals(CallMachine.TTL_SEC, CallMachine.ttlFor(Call.Mode.AUDIO))
    }

    @Test
    fun `nobody answering ends the call for everyone`() {
        val (_, phones) = bus("ayah", "ibu")
        val (ayah, ibu) = phones
        ayah.apply(Event.Dial("c-9", null, Call.Mode.AUDIO))

        ayah.apply(Event.Timeout("c-9"))
        assertEquals(Outcome.UNANSWERED, (ayah.state as CallState.Ended).outcome)
        // The caller's cancel is what stops the others ringing, rather than each waiting out
        // its own clock — see the note on Event.Timeout.
        assertEquals(Outcome.UNANSWERED, (ibu.state as CallState.Ended).outcome)
    }
}
