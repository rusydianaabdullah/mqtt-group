import { open as aeadOpen, seal as aeadSeal, importKey } from '$lib/crypto/aead';
import { concat, fromUtf8, randomBytes, utf8 } from '$lib/util/bytes';

/**
 * The backup file: a header nobody has to keep secret, and one sealed blob that is everything
 * else. Byte-identical to the Android app's `BackupArchive`, so a file written on a phone opens
 * here and one written here opens there.
 *
 * ```
 * "FGBK"        4 bytes, so a file picked by mistake fails immediately rather than as a
 *               decryption error the user reads as a wrong passphrase
 * version       1 byte
 * iterations    4 bytes, big-endian — carried rather than assumed, so a file written today
 *               still opens after the cost is raised for new ones
 * salt          16 bytes
 * sealed        nonce || ciphertext || tag
 * ```
 *
 * The header is passed to AES-GCM as additional authenticated data. Without that, the salt and
 * the iteration count sit in the clear and unprotected, and an attacker who can rewrite the file
 * can set iterations to 1 and hand it back — the victim's own app would then do the cheap
 * derivation for them.
 */

export const VERSION = 1;
export const SALT_BYTES = 16;

/**
 * OWASP's recommendation for PBKDF2-HMAC-SHA256.
 *
 * PBKDF2 is not the KDF anyone would choose today — Argon2id is memory-hard and this is not,
 * which is exactly the property that matters against someone cracking a stolen file on a GPU.
 * WebCrypto could not offer Argon2 either, but the binding reason is interoperability: the
 * Android app can only use what the platform ships, and a backup that one of the two cannot open
 * is not a backup. The honest mitigation is the one the UI states: the file is only as strong as
 * the passphrase typed into it.
 */
export const ITERATIONS = 210_000;

const MAGIC = utf8('FGBK');
const HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES;
/** A hostile file could name a number that would hang the browser for a week. */
const MAX_ITERATIONS = 10_000_000;

/**
 * Why a file could not be opened. Separated from "wrong passphrase" because they call for
 * completely different things from the user, and a single "failed" would leave someone retyping
 * a correct passphrase at a file that was never a backup.
 */
export type BackupFailure =
	| { kind: 'notABackup' }
	| { kind: 'unsupportedVersion'; version: number }
	| { kind: 'wrongPassphrase' };

export async function sealBackup(payload: unknown, passphrase: string): Promise<Uint8Array> {
	const salt = randomBytes(SALT_BYTES);
	const header = buildHeader(ITERATIONS, salt);
	const key = await deriveKey(passphrase, salt, ITERATIONS);
	const sealed = await aeadSeal(key, utf8(JSON.stringify(payload)), header);
	return concat(header, sealed);
}

export async function openBackup(
	bytes: Uint8Array,
	passphrase: string
): Promise<{ ok: true; payload: unknown } | { ok: false; failure: BackupFailure }> {
	if (bytes.length <= HEADER_BYTES) return { ok: false, failure: { kind: 'notABackup' } };
	for (let i = 0; i < MAGIC.length; i++) {
		if (bytes[i] !== MAGIC[i]) return { ok: false, failure: { kind: 'notABackup' } };
	}
	const version = bytes[4];
	if (version !== VERSION) {
		return { ok: false, failure: { kind: 'unsupportedVersion', version } };
	}
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	const iterations = view.getInt32(5, false);
	if (iterations <= 0 || iterations > MAX_ITERATIONS) {
		return { ok: false, failure: { kind: 'notABackup' } };
	}
	const salt = bytes.subarray(9, HEADER_BYTES);
	const header = bytes.subarray(0, HEADER_BYTES);
	try {
		const key = await deriveKey(passphrase, salt, iterations);
		const json = await aeadOpen(key, bytes.subarray(HEADER_BYTES), header);
		return { ok: true, payload: JSON.parse(fromUtf8(json)) };
	} catch {
		// Every failure past this point is the tag not verifying, which means a wrong passphrase
		// or a damaged file — indistinguishable by design, and neither one is worth telling an
		// attacker apart.
		return { ok: false, failure: { kind: 'wrongPassphrase' } };
	}
}

function buildHeader(iterations: number, salt: Uint8Array): Uint8Array {
	const header = new Uint8Array(HEADER_BYTES);
	header.set(MAGIC, 0);
	header[4] = VERSION;
	new DataView(header.buffer).setInt32(5, iterations, false);
	header.set(salt, 9);
	return header;
}

async function deriveKey(passphrase: string, salt: Uint8Array, iterations: number) {
	const material = await crypto.subtle.importKey(
		'raw',
		utf8(passphrase) as BufferSource,
		'PBKDF2',
		false,
		['deriveBits']
	);
	const bits = await crypto.subtle.deriveBits(
		{ name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
		material,
		256
	);
	return importKey(new Uint8Array(bits));
}
