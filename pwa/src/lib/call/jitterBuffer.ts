import type { AudioPacket } from '$lib/mqtt/audioMessages';

/**
 * Turns packets that arrive whenever the network feels like it into one frame every 20ms, which
 * is the only thing an audio decoder will accept.
 *
 * Pure, like `callMachine` and for the same reason: everything that decides *what to play next*
 * is here and testable, and what is left outside is something calling `pull()` on a timer. It
 * owns no clock — `pull()` is the tick — and knows nothing about Opus beyond the fact that
 * frames are opaque bytes. The Kotlin twin is `JitterBuffer.kt`, and the two should stay
 * recognisably the same list of behaviours.
 *
 * The problem it exists for is that this app's audio crosses a broker. Packets arrive early,
 * late, out of order, twice, or not at all, and a decoder fed them in that order produces
 * something nobody would call a voice. So packets are held briefly, sorted, and released on
 * time — and the length of "briefly" is the whole trade: too short and every hiccup is a gap,
 * too long and the conversation drifts apart. `TARGET_PACKETS` is that dial.
 */

/** Decode and play these bytes, or `null` to conceal — nothing to play for this 20ms slot.
 *
 * The decoder should conceal rather than be handed silence, since Opus interpolates a lost frame
 * far better than a gap sounds; but either way the slot has to be filled, because an audio
 * device that is not fed on time stutters for everyone. */
export type Slot = Uint8Array | null;

/**
 * How many packets to collect before starting to play, and roughly how much delay this adds.
 * Two 40ms packets is ~80ms on top of the broker round trip.
 *
 * Three 60ms packets — 180ms — was the first guess, and it was the single largest thing this app
 * added to the delay. The first real call over a broker made the trade obvious: the network's own
 * jitter is small compared to its latency, so spending 180ms insuring against reordering buys
 * very little and costs the conversation a great deal.
 *
 * This is the dial to turn if a call sounds choppy rather than slow. Raising it makes the call
 * more tolerant of a network that delivers unevenly, and more delayed.
 */
export const TARGET_PACKETS = 2;

/** A ceiling on what is held. Reached only when playout has stopped while packets keep arriving,
 * and at that point the newest are no more playable than the oldest. */
export const MAX_PACKETS = 50;

/**
 * Wrap-safe comparison: negative when `a` is before `b`.
 *
 * Never `<`. The sequence number is unsigned and rolls over at 2^32, and `| 0` reinterprets the
 * difference as a signed 32-bit integer, which is exactly the comparison that stays correct
 * across the wrap. It is also how the Kotlin side gets it for free, since its `Int` already
 * holds those bits.
 */
const compare = (a: number, b: number): number => (a - b) | 0;

/** Sequence numbers are kept unsigned here, exactly as `decodeAudio` produces them, so that one
 * representation is in play and `compare` is the only place the wrap is reasoned about. */
const nextSeq = (seq: number): number => (seq + 1) >>> 0;

export class JitterBuffer {
	private packets = new Map<number, Uint8Array[]>();
	private streamId: number | null = null;

	/** The packet being played out of, and how far into it. Null until enough has arrived. */
	private playhead: number | null = null;
	private frameIndex = 0;

	constructor(private readonly targetPackets: number = TARGET_PACKETS) {}

	/** How many packets are waiting. For a "connection quality" reading, not used here. */
	get depth(): number {
		return this.packets.size;
	}

	/**
	 * Takes a packet. Late ones are dropped rather than played: their moment has passed and
	 * `pull()` has already concealed it.
	 *
	 * A packet from a different `streamId` resets everything. That is what the field is for — a
	 * second call reusing the same sequence space would otherwise look like one stream that
	 * jumped, and this would sit waiting for packets that are never coming.
	 */
	push(packet: AudioPacket): void {
		if (packet.streamId !== this.streamId) {
			this.reset();
			this.streamId = packet.streamId;
		}
		if (this.playhead !== null && compare(packet.seq, this.playhead) < 0) return;
		if (this.packets.size >= MAX_PACKETS) return;
		this.packets.set(packet.seq, packet.frames);
	}

	/**
	 * One 20ms slot, every time it is called. Never blocks, never returns nothing: an audio
	 * device that is not fed on time stutters, so an absent packet becomes a conceal.
	 */
	pull(): Slot {
		if (this.playhead === null) {
			// Still filling. Playing before the buffer has its cushion means spending it
			// immediately and stuttering through the whole call.
			if (this.packets.size < this.targetPackets) return null;
			// Wrap-safe minimum, and it has to be: an unsigned comparison would pick 0 over
			// 0xFFFFFFFE and start the call two packets into the future, silently, once every
			// few years. Everything buffered is within a handful of packets of everything else,
			// so comparing pairwise is exact.
			let first: number | null = null;
			for (const seq of this.packets.keys()) {
				if (first === null || compare(seq, first) < 0) first = seq;
			}
			if (first === null) return null;
			this.playhead = first;
			this.frameIndex = 0;
		}

		const head = this.playhead;
		const frames = this.packets.get(head);
		if (!frames) {
			// Nothing for this slot. Two different situations, and telling them apart is what
			// keeps a bad network from turning into a permanently desynchronised call:
			if (this.packets.size === 0) {
				// Underrun — the sender has gone quiet, or the network has stalled. Hold the
				// playhead and wait. Advancing would race ahead of a stream that is merely
				// paused and turn a hiccup into a permanent offset.
				return null;
			}
			// Later packets are already here, so this one is genuinely lost. Skip it; waiting
			// for something that is not coming only adds delay to everything after.
			this.playhead = nextSeq(head);
			this.frameIndex = 0;
			return null;
		}

		const frame = frames[this.frameIndex];
		this.frameIndex++;
		if (this.frameIndex >= frames.length) {
			this.packets.delete(head);
			this.playhead = nextSeq(head);
			this.frameIndex = 0;
		}
		return frame;
	}

	reset(): void {
		this.packets.clear();
		this.streamId = null;
		this.playhead = null;
		this.frameIndex = 0;
	}
}
