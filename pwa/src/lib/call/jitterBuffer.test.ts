import { beforeEach, describe, expect, it } from 'vitest';
import { JitterBuffer, MAX_PACKETS } from './jitterBuffer';
import type { AudioPacket } from '$lib/mqtt/audioMessages';

/**
 * The jitter buffer is the only part of carrying voice that can be tested without a microphone,
 * a speaker or a second person, so everything that decides what to play next lives in it. The
 * Kotlin twin is `JitterBufferTest.kt`; the two should stay the same list of behaviours.
 */
describe('jitter buffer', () => {
	const STREAM = 1;

	/** One packet of frames, each a single distinguishable byte. */
	const packet = (seq: number, ...marks: number[]): AudioPacket => ({
		streamId: STREAM,
		seq: seq >>> 0,
		frames: marks.map((m) => new Uint8Array([m]))
	});

	const play = (buffer: JitterBuffer, times: number): (number | null)[] =>
		Array.from({ length: times }, () => {
			const slot = buffer.pull();
			return slot === null ? null : slot[0];
		});

	let buffer: JitterBuffer;
	beforeEach(() => {
		buffer = new JitterBuffer(3);
	});

	const fill = () => {
		buffer.push(packet(0, 1, 2));
		buffer.push(packet(1, 3, 4));
		buffer.push(packet(2, 5, 6));
	};

	// --- the cushion -------------------------------------------------------------------------

	// Playing before the buffer has its cushion spends it immediately and stutters all call.
	it('conceals until enough has arrived, then plays in order', () => {
		buffer.push(packet(0, 1, 2));
		expect(play(buffer, 2)).toEqual([null, null]);
		buffer.push(packet(1, 3, 4));
		buffer.push(packet(2, 5, 6));
		expect(play(buffer, 6)).toEqual([1, 2, 3, 4, 5, 6]);
	});

	it('hands out one frame per pull, not one packet', () => {
		fill();
		expect(play(buffer, 3)).toEqual([1, 2, 3]);
	});

	// --- what the network does to it -----------------------------------------------------------

	it('sorts packets that arrive out of order', () => {
		buffer.push(packet(2, 5, 6));
		buffer.push(packet(0, 1, 2));
		buffer.push(packet(1, 3, 4));
		expect(play(buffer, 6)).toEqual([1, 2, 3, 4, 5, 6]);
	});

	// A packet that is genuinely lost must be skipped rather than waited for: later packets are
	// already here, so waiting only adds delay to everything behind it.
	it('skips a lost packet once later ones have arrived', () => {
		const b = new JitterBuffer(2);
		b.push(packet(0, 1, 2));
		b.push(packet(2, 5, 6));
		expect(play(b, 5)).toEqual([1, 2, null, 5, 6]);
	});

	// The opposite case, and telling the two apart is the whole job. Nothing buffered means the
	// sender has gone quiet or the network has stalled — hold the playhead, because advancing
	// would race ahead of a stream that is merely paused and leave the call permanently offset.
	it('waits rather than skipping when nothing at all is buffered', () => {
		const b = new JitterBuffer(1);
		b.push(packet(0, 1));
		expect(play(b, 1)).toEqual([1]);
		expect(play(b, 3)).toEqual([null, null, null]);
		b.push(packet(1, 2));
		expect(play(b, 1)).toEqual([2]);
	});

	// Its moment has already been concealed; playing it now would be worse than dropping it.
	it('drops a packet that arrives after its slot has passed', () => {
		const b = new JitterBuffer(2);
		b.push(packet(0, 1, 2));
		b.push(packet(2, 5, 6));
		play(b, 3);
		b.push(packet(1, 3, 4));
		expect(play(b, 2)).toEqual([5, 6]);
	});

	// --- stream boundaries ----------------------------------------------------------------------

	// What streamId is for. Without it a second call reusing the same sequence space looks like
	// one stream that jumped, and the buffer sits waiting for packets never coming.
	it('resets everything for a new stream', () => {
		fill();
		play(buffer, 2);
		buffer.push({ streamId: 2, seq: 900, frames: [new Uint8Array([9])] });
		expect(buffer.depth).toBe(1);
	});

	// --- the wrap ---------------------------------------------------------------------------------

	// The sequence number rolls over at 2^32. An unsigned `<` would start playout at 0 rather
	// than at 0xFFFFFFFE and lose two packets — silently, and on one implementation not the other.
	it('orders correctly across the point where the sequence number wraps', () => {
		buffer.push(packet(0xffffffff, 3, 4));
		buffer.push(packet(0, 5, 6));
		buffer.push(packet(0xfffffffe, 1, 2));
		expect(play(buffer, 6)).toEqual([1, 2, 3, 4, 5, 6]);
	});

	// --- bounds -------------------------------------------------------------------------------------

	it('stops growing without bound', () => {
		for (let i = 0; i < MAX_PACKETS + 20; i++) buffer.push(packet(i, 1));
		expect(buffer.depth).toBe(MAX_PACKETS);
	});

	it('does not play a duplicate packet twice', () => {
		const b = new JitterBuffer(2);
		b.push(packet(0, 1, 2));
		b.push(packet(0, 1, 2));
		b.push(packet(1, 3, 4));
		expect(play(b, 4)).toEqual([1, 2, 3, 4]);
	});
});
