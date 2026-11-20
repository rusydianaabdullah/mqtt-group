import { describe, expect, it } from 'vitest';
import {
	BUZZ_TTL_SEC,
	IDLE,
	TTL_SEC,
	reduce,
	ttlFor,
	type CallState,
	type ConnectedState,
	type EndedState,
	type Event,
	type OutgoingState
} from './callMachine';
import type { Call, CallMode } from '$lib/mqtt/callMessages';

/**
 * Whole calls, between several machines, through a relay that behaves like the broker.
 *
 * Every other test here drives one machine and asserts on one transition. That is how two real
 * bugs shipped: the phase 8 ringing failure and the phase 10 group-call failure were both in a
 * *seam* — a message one side published and the other side dropped — and neither side's own
 * tests were wrong. Each was individually correct and jointly useless.
 *
 * So this is deliberately end to end: press the buttons, let the messages flow, and assert that
 * the people end up in a call together. The Kotlin twin is `CallConversationTest.kt` and runs the
 * same scenarios, which is what keeps the two implementations honest about behaviour rather than
 * only about bytes.
 */
describe('a whole call', () => {
	class Bus {
		readonly phones: Phone[] = [];
		private delivering = false;
		private readonly queue: [string, Call][] = [];

		publish(from: string, message: Call): void {
			this.queue.push([from, message]);
			// Flat rather than recursive: a reply published while delivering must be delivered
			// after the current message finishes, not inside it. Otherwise the roster exchange —
			// where an accept provokes an accept — reorders itself.
			if (this.delivering) return;
			this.delivering = true;
			while (this.queue.length > 0) {
				const [sender, payload] = this.queue.shift()!;
				for (const phone of this.phones) {
					if (phone.id !== sender) phone.receive(sender, payload);
				}
			}
			this.delivering = false;
		}
	}

	class Phone {
		state: CallState = IDLE;
		now = 1000;

		constructor(
			readonly id: string,
			private readonly bus: Bus
		) {}

		apply(event: Event): void {
			const before = this.state;
			const { state, publish } = reduce(this.state, event, this.id, this.now);
			this.state = state;
			if (publish) this.bus.publish(this.id, publish);
			// The store sends `ring` once it is really ringing, after the state has moved — that
			// ordering is part of the contract and is reproduced here.
			if (before.phase !== 'incoming' && state.phase === 'incoming') {
				this.bus.publish(this.id, { kind: 'ring', callId: state.callId });
			}
		}

		receive(from: string, message: Call): void {
			this.apply({ type: 'received', senderId: from, message });
		}

		dial(callId: string, peerId: string | null, mode: CallMode = 'audio'): void {
			this.apply({ type: 'dial', callId, peerId, mode });
		}

		get connected(): ConnectedState | null {
			return this.state.phase === 'connected' ? this.state : null;
		}

		get outcome(): string | null {
			return this.state.phase === 'ended' ? (this.state as EndedState).outcome : null;
		}
	}

	const group = (...ids: string[]) => {
		const bus = new Bus();
		const phones = ids.map((id) => new Phone(id, bus));
		bus.phones.push(...phones);
		return phones;
	};

	// --- two people ---------------------------------------------------------------------------

	it('rings, is answered, and ends when one side hangs up', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		ayah.dial('c-1', 'ibu');
		expect(ibu.state.phase).toBe('incoming');
		expect((ayah.state as OutgoingState).reached).toBe(true);

		ibu.apply({ type: 'accept' });
		expect(ayah.connected?.participants).toEqual(['ibu']);
		expect(ibu.connected?.participants).toEqual(['ayah']);

		ibu.apply({ type: 'hangup' });
		expect(ayah.outcome).toBe('hung-up');
	});

	it('tells the caller when a call is declined', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		ayah.dial('c-1', 'ibu');
		ibu.apply({ type: 'hangup' });
		expect(ayah.outcome).toBe('declined');
	});

	// Everyone hears every offer. Only the person it names may be rung by it.
	it('does not ring the rest of the group for a direct call', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		ayah.dial('c-1', 'ibu');
		expect(ibu.state.phase).toBe('incoming');
		expect(adik.state).toEqual(IDLE);
	});

	// --- a room -------------------------------------------------------------------------------

	// The test that would have caught the phase 10 bug: a group call that rings and connects.
	it('rings everyone for a group call and gathers whoever answers', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		ayah.dial('c-9', null);
		expect(ibu.state.phase).toBe('incoming');
		expect(adik.state.phase).toBe('incoming');

		ibu.apply({ type: 'accept' });
		expect(ayah.connected?.participants).toEqual(['ibu']);

		adik.apply({ type: 'accept' });
		expect(ayah.connected?.participants.sort()).toEqual(['adik', 'ibu']);
		// And the roster, rebuilt from traffic: the late joiner must learn about the early one
		// without anything having been retained anywhere.
		expect(adik.connected?.participants.sort()).toEqual(['ayah', 'ibu']);
		expect(ibu.connected?.participants.sort()).toEqual(['adik', 'ayah']);
	});

	it('leaves a group call ringing for the rest when one member declines', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		ayah.dial('c-9', null);
		ibu.apply({ type: 'hangup' });
		expect(ayah.state.phase).not.toBe('ended');

		adik.apply({ type: 'accept' });
		expect(ayah.connected?.participants).toEqual(['adik']);
	});

	it('leaves the other two talking when one leaves a room of three', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		ayah.dial('c-9', null);
		ibu.apply({ type: 'accept' });
		adik.apply({ type: 'accept' });

		ibu.apply({ type: 'hangup' });
		expect(ayah.connected?.participants).toEqual(['adik']);
		expect(adik.connected?.participants).toEqual(['ayah']);
		expect(ibu.state.phase).toBe('ended');
	});

	it('ends the room for everyone once the last person leaves', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		ayah.dial('c-9', null);
		ibu.apply({ type: 'accept' });

		ayah.apply({ type: 'hangup' });
		expect(ibu.state.phase).toBe('ended');
	});

	// A device already on a call refuses a second, and the caller is told why.
	it('comes back busy when calling someone already talking', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		ayah.dial('c-1', 'ibu');
		ibu.apply({ type: 'accept' });

		adik.dial('c-2', 'ibu');
		expect(adik.outcome).toBe('busy');
		// And the call already running is undisturbed by it.
		expect(ibu.connected?.participants).toEqual(['ayah']);
	});

	// --- a buzz --------------------------------------------------------------------------------

	const buzz = (phone: Phone, callId: string, target: string | null) =>
		phone.dial(callId, target, 'buzz');

	// The whole of a buzz: it rings, and then somebody stops it. There is no third state.
	it('rings the phone and is stopped by whoever reaches it first', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		buzz(ayah, 'b-1', 'ibu');
		expect(ibu.state.phase).toBe('incoming');
		expect((ayah.state as OutgoingState).reached).toBe(true);

		ibu.apply({ type: 'hangup' });
		expect(ayah.state.phase).toBe('ended');
	});

	it('lets the caller stop a buzz they started', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		buzz(ayah, 'b-1', 'ibu');
		ayah.apply({ type: 'hangup' });
		expect(ibu.state.phase).toBe('ended');
	});

	// A buzz has nothing to answer, and the only two buttons that exist for one are Stop at
	// either end. Answering it would put it in a state its own UI has no way out of.
	it('cannot be answered into a call', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		buzz(ayah, 'b-1', 'ibu');
		ibu.apply({ type: 'accept' });
		expect(ibu.state.phase).toBe('incoming');
		expect(ayah.state.phase).toBe('outgoing');
	});

	it('buzzes a whole group, and one person silencing theirs leaves the rest ringing', () => {
		const [ayah, ibu, adik] = group('ayah', 'ibu', 'adik');
		buzz(ayah, 'b-9', null);
		expect(ibu.state.phase).toBe('incoming');
		expect(adik.state.phase).toBe('incoming');

		ibu.apply({ type: 'hangup' });
		expect(adik.state.phase).toBe('incoming');
		expect(ayah.state.phase).toBe('outgoing');

		ayah.apply({ type: 'hangup' });
		expect(adik.state.phase).toBe('ended');
	});

	// Longer than a call: finding a phone down the back of a sofa takes longer than waiting for
	// a person to pick up, and nobody is being kept waiting while it happens.
	it('rings for longer than a call', () => {
		expect(ttlFor('buzz')).toBe(BUZZ_TTL_SEC);
		expect(ttlFor('audio')).toBe(TTL_SEC);
	});

	it('ends the call for everyone when nobody answers', () => {
		const [ayah, ibu] = group('ayah', 'ibu');
		ayah.dial('c-9', null);

		ayah.apply({ type: 'timeout', callId: 'c-9' });
		expect(ayah.outcome).toBe('unanswered');
		// The caller's cancel is what stops the others ringing, rather than each waiting out its
		// own clock — see the note on the timeout event.
		expect(ibu.outcome).toBe('unanswered');
	});
});
