import { KEY_BYTES, NONCE_BYTES, TAG_BYTES, open as aeadOpen, seal as aeadSeal } from './aead';
import { bytesToUuid, readUint64, utf8, uuidToBytes, writeUint64 } from '$lib/util/bytes';

/**
 * The single wire format, byte for byte the same as the Android app's `Envelope`:
 *
 * ```
 * "FG1" | epoch:u8 | senderId:16 | counter:u64 | nonce:12 | ciphertext+tag
 * └──────────── header, authenticated as AAD ────────────┘
 * ```
 *
 * The header travels in the clear because a receiver needs it to pick a key and to reject a
 * replay before it can decrypt anything. Binding it as AAD means a broker that rewrites the
 * sender or rewinds the counter produces a payload that no longer opens.
 *
 * `epoch` is the group-key generation. It is 0 for every group created today; it exists so
 * that rotating a key on kick becomes a new epoch rather than a new wire format.
 */

const MAGIC = utf8('FG1');
export const HEADER_BYTES = 3 + 1 + 16 + 8;

export interface EnvelopeHeader {
	epoch: number;
	senderId: string;
	counter: bigint;
}

export interface Envelope extends EnvelopeHeader {
	plaintext: Uint8Array;
}

export function buildHeader(epoch: number, senderId: string, counter: bigint): Uint8Array {
	if (!Number.isInteger(epoch) || epoch < 0 || epoch > 255) {
		throw new Error(`epoch must fit in a byte, got ${epoch}`);
	}
	const header = new Uint8Array(HEADER_BYTES);
	header.set(MAGIC, 0);
	header[3] = epoch;
	header.set(uuidToBytes(senderId), 4);
	writeUint64(new DataView(header.buffer), 20, counter);
	return header;
}

export async function seal(
	key: CryptoKey,
	epoch: number,
	senderId: string,
	counter: bigint,
	plaintext: Uint8Array
): Promise<Uint8Array> {
	const header = buildHeader(epoch, senderId, counter);
	const body = await aeadSeal(key, plaintext, header);
	const out = new Uint8Array(header.length + body.length);
	out.set(header, 0);
	out.set(body, header.length);
	return out;
}

/**
 * Reads the header without decrypting, so a receiver can look up the right key for the epoch —
 * and drop a replayed counter — before spending any work on the body.
 */
export function peek(payload: Uint8Array): EnvelopeHeader | null {
	if (payload.length < HEADER_BYTES + NONCE_BYTES + TAG_BYTES) return null;
	for (let i = 0; i < MAGIC.length; i++) if (payload[i] !== MAGIC[i]) return null;
	const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
	return {
		epoch: payload[3],
		senderId: bytesToUuid(payload.subarray(4, 20)),
		counter: readUint64(view, 20)
	};
}

/** Returns null for anything that is not a well-formed envelope this key can open. */
export async function open(key: CryptoKey, payload: Uint8Array): Promise<Envelope | null> {
	const header = peek(payload);
	if (!header) return null;
	try {
		const plaintext = await aeadOpen(
			key,
			payload.subarray(HEADER_BYTES),
			payload.subarray(0, HEADER_BYTES)
		);
		return { ...header, plaintext };
	} catch {
		// Wrong key, wrong epoch, or a tampered payload. All the same to us: drop it.
		return null;
	}
}

export { KEY_BYTES };
