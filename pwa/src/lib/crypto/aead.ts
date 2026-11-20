/**
 * AES-256-GCM, the one symmetric primitive this app uses — for MQTT payloads, for attachment
 * blobs, and for wrapping keys. A direct transliteration of the Android app's `Aead`, down to
 * the layout of what it returns, because the two have to open each other's output.
 *
 * Nonces are 12 random bytes: the standard GCM size and the only one WebCrypto and the JCA
 * both accept without an extra derivation step.
 */

export const KEY_BYTES = 32;
export const NONCE_BYTES = 12;
export const TAG_BYTES = 16;

export function randomKeyBytes(): Uint8Array {
	return crypto.getRandomValues(new Uint8Array(KEY_BYTES));
}

/** Raw key bytes to a `CryptoKey`. Extractable, because the caller already holds the bytes. */
export async function importKey(raw: Uint8Array): Promise<CryptoKey> {
	if (raw.length !== KEY_BYTES) {
		throw new Error(`expected a ${KEY_BYTES * 8}-bit key, got ${raw.length * 8}`);
	}
	return crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, [
		'encrypt',
		'decrypt'
	]);
}

/**
 * Encrypts `plaintext`, returning `nonce || ciphertext || tag`. `aad` is authenticated but not
 * encrypted: pass the framing bytes that must not be tampered with, such as the sender and
 * counter of an envelope.
 *
 * WebCrypto appends the tag to the ciphertext, which is what the JCA does too, so the two
 * layouts already agree and nothing has to be rearranged.
 */
export async function seal(
	key: CryptoKey,
	plaintext: Uint8Array,
	aad?: Uint8Array
): Promise<Uint8Array> {
	const nonce = crypto.getRandomValues(new Uint8Array(NONCE_BYTES));
	const body = await crypto.subtle.encrypt(params(nonce, aad), key, plaintext as BufferSource);
	const out = new Uint8Array(NONCE_BYTES + body.byteLength);
	out.set(nonce, 0);
	out.set(new Uint8Array(body), NONCE_BYTES);
	return out;
}

/** Reverses `seal`. Throws on a wrong key or tampering, as WebCrypto does. */
export async function open(
	key: CryptoKey,
	sealed: Uint8Array,
	aad?: Uint8Array
): Promise<Uint8Array> {
	if (sealed.length < NONCE_BYTES + TAG_BYTES) {
		throw new Error('sealed payload is too short to be valid');
	}
	const plain = await crypto.subtle.decrypt(
		params(sealed.subarray(0, NONCE_BYTES), aad),
		key,
		sealed.subarray(NONCE_BYTES) as BufferSource
	);
	return new Uint8Array(plain);
}

/**
 * The GCM parameters, with `additionalData` present only when there is any.
 *
 * Setting the key to `undefined` is not the same as leaving it out. WebIDL says an explicit
 * `undefined` means "absent", and Chrome disagrees: it type-checks the property it can see and
 * throws `AeadParams: additionalData: Not a BufferSource`. Everything sealed without AAD goes
 * through here — every attachment blob, every avatar, the wrapped group keys — so that one
 * difference took out photos and voice notes entirely while chat text, which always passes an
 * envelope header, kept working and made it look like a media bug.
 */
function params(nonce: Uint8Array, aad?: Uint8Array): AesGcmParams {
	const base: AesGcmParams = {
		name: 'AES-GCM',
		iv: nonce as BufferSource,
		tagLength: TAG_BYTES * 8
	};
	return aad ? { ...base, additionalData: aad as BufferSource } : base;
}
