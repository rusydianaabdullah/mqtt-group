/**
 * The plaintext inside an audio envelope: a few Opus frames and enough to put them back in order.
 *
 * Binary rather than the JSON every other codec in this app uses, and that is not premature
 * optimisation. A control message is a few hundred bytes sent now and then; this is seventeen
 * packets a second for the length of a conversation, and base64 inside JSON would add a third to
 * every one of them for no reason a reader will ever benefit from.
 *
 * ```
 * "FGA1" | streamId:u32 | seq:u32 | frameCount:u8 | (len:u16, frame)*
 * ```
 *
 * - `streamId` is minted per call, per speaker. Its job is to make the boundary between two
 *   streams unmistakable: without it, a call that ends and another that starts moments later
 *   look like one stream whose sequence numbers jumped, and the jitter buffer would sit waiting
 *   for packets that are never coming. It is not a call id — this layer does not know about
 *   calls, and does not need to.
 * - `seq` counts packets, not frames, and wraps at 2^32, which at seventeen packets a second is
 *   about eight years of continuous talking. It is unsigned here and *signed* on the Kotlin
 *   side, which holds the same bits in an `Int` — a packet numbered 0xFFFFFFFE reads as
 *   4294967294 here and -2 there. Nothing may compare two sequence numbers with `<`; the jitter
 *   buffer subtracts and looks at the sign, which is wrap-safe and is the only correct way to
 *   order a counter that rolls over anyway.
 * - Several frames ride in one packet. An envelope costs 56 bytes of header, nonce and tag, and
 *   one 20ms Opus frame at 24 kbps is 60 — so a packet per frame is very nearly half overhead.
 *   Three frames makes it a fifth, at the cost of losing 60ms of speech rather than 20ms when a
 *   packet goes missing. The format does not care how many there are; that is the sender's
 *   decision and can change without either side minding.
 *
 * There is no timestamp. It would only ever be compared against a different device's clock — the
 * same trap `OfferCall.ttlSec` avoids — and the sequence number already says everything the
 * jitter buffer needs to know about order and loss.
 *
 * Wire-identical to `mqtt/AudioMessages.kt` in `../family-group`; see `vectors/wire-vectors.json`.
 */

export interface AudioPacket {
	streamId: number;
	seq: number;
	frames: Uint8Array[];
}

const MAGIC = [0x46, 0x47, 0x41, 0x31]; // "FGA1"
const HEADER_BYTES = 4 + 4 + 4 + 1;

/** Generous against what the sender actually packs (three), so the limit is a sanity check on a
 * hostile payload rather than a format decision to revisit. */
export const MAX_FRAMES = 12;

/** An Opus frame at 24 kbps mono is around 60 bytes; 1500 is absurd and still cheap to refuse.
 * Without a bound, a crafted length field makes this allocate whatever it likes. */
export const MAX_FRAME_BYTES = 1500;

export function encodeAudio(packet: AudioPacket): Uint8Array {
	if (packet.frames.length === 0)
		throw new Error('an audio packet with no frames is nothing to send');
	if (packet.frames.length > MAX_FRAMES)
		throw new Error(`too many frames: ${packet.frames.length}`);
	for (const frame of packet.frames) {
		if (frame.length > MAX_FRAME_BYTES) {
			throw new Error(`frame of ${frame.length} bytes is not Opus at 24 kbps`);
		}
	}

	const size = HEADER_BYTES + packet.frames.reduce((n, f) => n + 2 + f.length, 0);
	const out = new Uint8Array(size);
	const view = new DataView(out.buffer);
	out.set(MAGIC, 0);
	// Big-endian throughout, to match ByteBuffer's default on the Kotlin side.
	view.setUint32(4, packet.streamId >>> 0, false);
	view.setUint32(8, packet.seq >>> 0, false);
	out[12] = packet.frames.length;

	let at = HEADER_BYTES;
	for (const frame of packet.frames) {
		view.setUint16(at, frame.length, false);
		out.set(frame, at + 2);
		at += 2 + frame.length;
	}
	return out;
}

/** Returns null for anything malformed, which is a packet to drop. At this rate, dropping is what
 * happens to bad packets anyway — there is no room to complain about one. */
export function decodeAudio(payload: Uint8Array): AudioPacket | null {
	if (payload.length < HEADER_BYTES) return null;
	for (let i = 0; i < MAGIC.length; i++) if (payload[i] !== MAGIC[i]) return null;

	const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
	const streamId = view.getUint32(4, false);
	const seq = view.getUint32(8, false);
	const count = payload[12];
	if (count === 0 || count > MAX_FRAMES) return null;

	const frames: Uint8Array[] = [];
	let at = HEADER_BYTES;
	for (let i = 0; i < count; i++) {
		if (at + 2 > payload.length) return null;
		const length = view.getUint16(at, false);
		at += 2;
		if (length === 0 || length > MAX_FRAME_BYTES || at + length > payload.length) return null;
		// A copy, not a subarray: the caller keeps these in a jitter buffer for a few hundred
		// milliseconds, and a subarray would pin the whole received payload alive behind each one.
		frames.push(payload.slice(at, at + length));
		at += length;
	}
	return { streamId, seq, frames };
}
