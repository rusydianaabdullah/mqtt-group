import { describe, expect, it } from 'vitest';
import {
	IDLE,
	isBusy,
	reduce,
	TTL_SEC,
	type CallState,
	type ConnectedState,
	type Event,
	type IncomingState,
	type Outcome,
	type OutgoingState,
	type Transition
} from './callMachine';

/**
 * The call state machine is the one part of calling that can be tested without a device, a
 * broker or a second person, so everything that decides what a call does lives in it and is
 * tested here. The Kotlin twin of this file is `CallMachineTest.kt`; the two should stay
 * recognisably the same list of cases.
 */
describe('call machine', () => {
	const me = 'u-me';
	const them = 'u-them';
	const other = 'u-other';

	const step = (state: CallState, event: Event, now = 1000): Transition =>
		reduce(state, event, me, now);

	const dialling = () =>
		step(IDLE, { type: 'dial', callId: 'c-1', peerId: them, mode: 'audio' }).state as OutgoingState;

	const ringing = () =>
		step(IDLE, {
			type: 'received',
			senderId: them,
			message: { kind: 'offer', callId: 'c-1', toUserId: me, mode: 'audio', ttlSec: 45 }
		}).state as IncomingState;

	const connected = () => step(ringing(), { type: 'accept' }, 5000).state as ConnectedState;

	const outcome = (t: Transition): Outcome => (t.state as { outcome: Outcome }).outcome;

	// --- the happy paths -------------------------------------------------------------------

	it('publishes an offer and waits when dialling', () => {
		const t = step(IDLE, { type: 'dial', callId: 'c-1', peerId: them, mode: 'audio' });
		expect(t.state).toEqual({
			phase: 'outgoing',
			callId: 'c-1',
			peerId: them,
			mode: 'audio',
			reached: false,
			isGroup: false
		});
		expect(t.publish).toEqual({
			kind: 'offer',
			callId: 'c-1',
			toUserId: them,
			mode: 'audio',
			ttlSec: TTL_SEC
		});
	});

	// The whole point of the `ring` message: "calling" and "ringing" are different facts, and in
	// a browser the difference is whether a tab was even open.
	it('turns calling into ringing when their ring arrives', () => {
		const t = step(dialling(), {
			type: 'received',
			senderId: them,
			message: { kind: 'ring', callId: 'c-1' }
		});
		expect((t.state as OutgoingState).reached).toBe(true);
		expect(t.publish).toBeUndefined();
	});

	it('rings this device for an offer addressed to us', () => {
		const t = step(IDLE, {
			type: 'received',
			senderId: them,
			message: { kind: 'offer', callId: 'c-1', toUserId: me, mode: 'audio', ttlSec: 45 }
		});
		expect(t.state).toEqual({
			phase: 'incoming',
			callId: 'c-1',
			peerId: them,
			mode: 'audio',
			isGroup: false
		});
		// Nothing is published here: `ring` is sent once this device has actually started
		// ringing, which is a fact about the device and not about the machine.
		expect(t.publish).toBeUndefined();
	});

	it('connects both sides on answer', () => {
		const answer = step(ringing(), { type: 'accept' }, 5000);
		expect(answer.state).toEqual({
			phase: 'connected',
			callId: 'c-1',
			participants: [them],
			mode: 'audio',
			since: 5000,
			isGroup: false
		});
		expect(answer.publish).toEqual({ kind: 'accept', callId: 'c-1' });

		const caller = step(
			dialling(),
			{ type: 'received', senderId: them, message: { kind: 'accept', callId: 'c-1' } },
			5000
		);
		expect(caller.state).toMatchObject({ phase: 'connected', since: 5000 });
	});

	// --- the red button, which means three different things ---------------------------------

	it('cancels, declines or ends depending on where the call is', () => {
		expect(step(dialling(), { type: 'hangup' }).publish).toEqual({ kind: 'cancel', callId: 'c-1' });
		expect(step(ringing(), { type: 'hangup' }).publish).toEqual({
			kind: 'decline',
			callId: 'c-1',
			reason: 'declined'
		});
		expect(step(connected(), { type: 'hangup' }).publish).toEqual({ kind: 'end', callId: 'c-1' });
	});

	it('keeps an outcome for each way a call can stop', () => {
		expect(outcome(step(dialling(), { type: 'hangup' }))).toBe('cancelled');
		expect(outcome(step(ringing(), { type: 'hangup' }))).toBe('declined');
		expect(outcome(step(connected(), { type: 'hangup' }))).toBe('hung-up');
		expect(
			outcome(
				step(dialling(), {
					type: 'received',
					senderId: them,
					message: { kind: 'decline', callId: 'c-1', reason: 'declined' }
				})
			)
		).toBe('declined');
		expect(
			outcome(
				step(dialling(), {
					type: 'received',
					senderId: them,
					message: { kind: 'decline', callId: 'c-1', reason: 'busy' }
				})
			)
		).toBe('busy');
		expect(
			outcome(
				step(ringing(), {
					type: 'received',
					senderId: them,
					message: { kind: 'cancel', callId: 'c-1' }
				})
			)
		).toBe('unanswered');
		expect(
			outcome(
				step(connected(), {
					type: 'received',
					senderId: them,
					message: { kind: 'end', callId: 'c-1' }
				})
			)
		).toBe('hung-up');
	});

	// --- one call at a time ------------------------------------------------------------------

	// A device that rings during a call is one whose owner now has two problems.
	it('declines a second offer as busy without anyone hearing it', () => {
		const t = step(connected(), {
			type: 'received',
			senderId: other,
			message: { kind: 'offer', callId: 'c-2', toUserId: me, mode: 'audio', ttlSec: 45 }
		});
		expect(t.state).toEqual(connected());
		expect(t.publish).toEqual({ kind: 'decline', callId: 'c-2', reason: 'busy' });
	});

	it('refuses to dial while a call is already up', () => {
		const t = step(connected(), { type: 'dial', callId: 'c-2', peerId: other, mode: 'audio' });
		expect(t.state).toEqual(connected());
		expect(t.publish).toBeUndefined();
	});

	// --- traffic that is not ours -------------------------------------------------------------

	// Every member of the group decrypts every offer. Most of them are for somebody else.
	it('ignores an offer for another member rather than ringing', () => {
		const t = step(IDLE, {
			type: 'received',
			senderId: them,
			message: { kind: 'offer', callId: 'c-1', toUserId: other, mode: 'audio', ttlSec: 45 }
		});
		expect(t.state).toEqual(IDLE);
		expect(t.publish).toBeUndefined();
	});

	// A late message for a call that already ended must not tear down the call that replaced it
	// — which is the reason every message names a call id at all.
	it('drops a message for a different call', () => {
		const t = step(connected(), {
			type: 'received',
			senderId: them,
			message: { kind: 'end', callId: 'c-OLD' }
		});
		expect(t.state).toEqual(connected());
	});

	// A shared group key cannot prove who sent a message, but a call can at least require the
	// same someone throughout — otherwise any member could hang up a call between two others.
	it("will not let a third member end someone else's call", () => {
		const t = step(connected(), {
			type: 'received',
			senderId: other,
			message: { kind: 'end', callId: 'c-1' }
		});
		expect(t.state).toEqual(connected());
	});

	// --- timeouts -----------------------------------------------------------------------------

	// The caller cancels rather than waiting for the callee's own clock, because the two clocks
	// did not start together — which is why the ttl is a duration in the first place.
	it('times an unanswered outgoing call out and tells the other end to stop', () => {
		const t = step(dialling(), { type: 'timeout', callId: 'c-1' });
		expect(outcome(t)).toBe('unanswered');
		expect(t.publish).toEqual({ kind: 'cancel', callId: 'c-1' });
	});

	// Silence, deliberately. A decline would make "nobody was near the device" look identical to
	// "they looked at it and said no", and the caller's screen would repeat that.
	it('says nothing back for a missed incoming call', () => {
		const t = step(ringing(), { type: 'timeout', callId: 'c-1' });
		expect(outcome(t)).toBe('unanswered');
		expect(t.publish).toBeUndefined();
	});

	it('ignores a timeout for a call that has moved on', () => {
		expect(step(connected(), { type: 'timeout', callId: 'c-OLD' }).state).toEqual(connected());
	});

	it('does not time out a connected call', () => {
		expect(step(connected(), { type: 'timeout', callId: 'c-1' }).state).toEqual(connected());
	});

	// --- housekeeping ---------------------------------------------------------------------------

	it('returns to idle on dismiss, and allows dialling again before that', () => {
		const done = step(connected(), { type: 'hangup' }).state;
		expect(step(done, { type: 'dismiss' }).state).toEqual(IDLE);
		// Without waiting for the dismissal, too — a second call should not need the first one's
		// "call ended" banner to be tapped away first.
		expect(
			step(done, { type: 'dial', callId: 'c-2', peerId: other, mode: 'audio' }).state
		).toMatchObject({
			phase: 'outgoing'
		});
	});

	// --- group calls, phase 10 ----------------------------------------------------------------

	const third = 'u-third';

	const groupRinging = () =>
		step(IDLE, {
			type: 'received',
			senderId: them,
			message: { kind: 'offer', callId: 'c-9', toUserId: null, mode: 'audio', ttlSec: 45 }
		}).state;

	const inRoom = () => step(groupRinging(), { type: 'accept' }, 5000).state as ConnectedState;

	const joined = (who: string) =>
		step(inRoom(), { type: 'received', senderId: who, message: { kind: 'accept', callId: 'c-9' } });

	it('publishes an offer with no target when dialling the group', () => {
		const t = step(IDLE, { type: 'dial', callId: 'c-9', peerId: null, mode: 'audio' });
		expect((t.state as OutgoingState).isGroup).toBe(true);
		expect(t.publish).toMatchObject({ kind: 'offer', toUserId: null });
	});

	// A group offer names nobody, so it is for everybody — including members it was not
	// addressed to, which is the entire difference from a direct call.
	it('rings every member for an offer with no target', () => {
		expect(groupRinging()).toEqual({
			phase: 'incoming',
			callId: 'c-9',
			peerId: them,
			mode: 'audio',
			isGroup: true
		});
	});

	// Our own group offer comes back from the broker, because we are in the group we rang.
	it('does not ring this device for our own group offer', () => {
		const dialling = step(IDLE, { type: 'dial', callId: 'c-9', peerId: null, mode: 'audio' }).state;
		const t = step(dialling, {
			type: 'received',
			senderId: me,
			message: { kind: 'offer', callId: 'c-9', toUserId: null, mode: 'audio', ttlSec: 45 }
		});
		expect(t.state).toEqual(dialling);
	});

	// The roster, rebuilt from traffic because nothing on this topic is retained: a newcomer's
	// accept is answered with our own, so they learn we are here.
	it('adds someone joining a room we are in, and tells them we are here', () => {
		const t = joined(third);
		expect((t.state as ConnectedState).participants).toEqual([them, third]);
		expect(t.publish).toEqual({ kind: 'accept', callId: 'c-9' });
	});

	// Only a newcomer. Answering every accept would be a storm that never settles.
	it('does not answer an accept from someone already in the room', () => {
		const room = joined(third).state;
		const t = step(room, {
			type: 'received',
			senderId: third,
			message: { kind: 'accept', callId: 'c-9' }
		});
		expect(t.state).toEqual(room);
		expect(t.publish).toBeUndefined();
	});

	// "I have left" is not "the call is over" — the room outlives whoever leaves it.
	it('keeps the call running when one of three leaves', () => {
		const room = joined(third).state;
		const t = step(room, {
			type: 'received',
			senderId: them,
			message: { kind: 'end', callId: 'c-9' }
		});
		expect((t.state as ConnectedState).participants).toEqual([third]);
	});

	// Sitting alone in a room waiting is not a state worth showing anyone.
	it('ends the call when the last other person leaves', () => {
		const t = step(inRoom(), {
			type: 'received',
			senderId: them,
			message: { kind: 'end', callId: 'c-9' }
		});
		expect(outcome(t)).toBe('hung-up');
	});

	// A direct call is a room of one, so a member who was never in it cannot join or end it.
	it('will not let a stranger join a direct call', () => {
		const t = step(connected(), {
			type: 'received',
			senderId: other,
			message: { kind: 'accept', callId: 'c-1' }
		});
		expect(t.state).toEqual(connected());
		expect(t.publish).toBeUndefined();
	});

	it('still times out a group call nobody joins', () => {
		const dialling = step(IDLE, { type: 'dial', callId: 'c-9', peerId: null, mode: 'audio' }).state;
		const t = step(dialling, { type: 'timeout', callId: 'c-9' });
		expect(outcome(t)).toBe('unanswered');
		expect(t.publish).toEqual({ kind: 'cancel', callId: 'c-9' });
	});

	// Once somebody is in, the offer expiring is irrelevant — late joiners stay welcome.
	it('does not end a joined group call on the ringing timeout', () => {
		const room = inRoom();
		expect(step(room, { type: 'timeout', callId: 'c-9' }).state).toEqual(room);
	});

	const groupDialling = () =>
		step(IDLE, { type: 'dial', callId: 'c-9', peerId: null, mode: 'audio' }).state;

	// The whole group-caller path, which had no test at all and did not work: a group offer is
	// rung to nobody in particular, so `peerId` is null, and the "is this sender part of this
	// call?" check read `null === senderId` and dropped every reply. The group rang, then nothing.
	it('connects the caller of a group call when somebody answers', () => {
		const t = step(
			groupDialling(),
			{ type: 'received', senderId: them, message: { kind: 'accept', callId: 'c-9' } },
			5000
		);
		const room = t.state as ConnectedState;
		expect(room.phase).toBe('connected');
		expect(room.participants).toEqual([them]);
		expect(room.isGroup).toBe(true);
	});

	it('shows the group caller it is ringing when anyone rings', () => {
		const t = step(groupDialling(), {
			type: 'received',
			senderId: other,
			message: { kind: 'ring', callId: 'c-9' }
		});
		expect((t.state as OutgoingState).reached).toBe(true);
	});

	// A room fills from the caller's side too: the second person to answer joins the first.
	it('joins a second answer to the room the caller is already in', () => {
		const room = step(groupDialling(), {
			type: 'received',
			senderId: them,
			message: { kind: 'accept', callId: 'c-9' }
		}).state;
		const t = step(room, {
			type: 'received',
			senderId: other,
			message: { kind: 'accept', callId: 'c-9' }
		});
		expect((t.state as ConnectedState).participants).toEqual([them, other]);
	});

	// One member saying no is not the call failing — the rest are still ringing. A direct call is
	// the opposite, and that difference is the reason this is worth a test.
	it('does not end a group call when one member declines', () => {
		const t = step(groupDialling(), {
			type: 'received',
			senderId: them,
			message: { kind: 'decline', callId: 'c-9', reason: 'declined' }
		});
		expect(t.state).toMatchObject({ phase: 'outgoing' });

		const direct = step(dialling(), {
			type: 'received',
			senderId: them,
			message: { kind: 'decline', callId: 'c-1', reason: 'declined' }
		});
		expect(outcome(direct)).toBe('declined');
	});

	it('is busy in exactly the states where a person is committed', () => {
		expect(isBusy(IDLE)).toBe(false);
		expect(isBusy(dialling())).toBe(true);
		expect(isBusy(ringing())).toBe(true);
		expect(isBusy(connected())).toBe(true);
		expect(isBusy(step(connected(), { type: 'hangup' }).state)).toBe(false);
	});
});
