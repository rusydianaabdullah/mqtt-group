import { NONCE_BYTES, TAG_BYTES } from './aead';
import { concat } from '$lib/util/bytes';

/**
 * The browser's answer to the Android Keystore, and the place where this app is honestly weaker
 * than the phone it mirrors.
 *
 * The shape is the same: one master key that never leaves its container, and every group key
 * stored wrapped by it, so what sits in the database is useless on its own. On Android that
 * container is hardware. Here it is a non-extractable `CryptoKey` in IndexedDB — script cannot
 * read the key material out, not even this app's own script, and neither can an XSS; but it is
 * protected by the browser's origin sandbox and the disk, not by a secure element. A stolen,
 * unlocked laptop gives up the group. The UI says so; see README.
 *
 * Wrapping rather than storing group keys as `CryptoKey` objects directly is deliberate, and it
 * is what makes phase 7's encrypted backup possible: an exportable copy has to be derivable
 * from something, and a non-extractable group key could never be written into a backup file. So
 * the *master* key is the one that cannot be exported, and the group keys are ciphertext under
 * it — exactly the arrangement `KeyVault` has on the phone.
 */

const WRAP_ALGORITHM = 'AES-GCM';

export class KeyVault {
	constructor(private readonly master: CryptoKey) {}

	/** Seals raw key bytes for storage. Layout is `nonce || ciphertext || tag`, as everywhere. */
	async wrap(raw: Uint8Array): Promise<Uint8Array> {
		const nonce = crypto.getRandomValues(new Uint8Array(NONCE_BYTES));
		const body = await crypto.subtle.encrypt(
			{ name: WRAP_ALGORITHM, iv: nonce as BufferSource, tagLength: TAG_BYTES * 8 },
			this.master,
			raw as BufferSource
		);
		return concat(nonce, new Uint8Array(body));
	}

	/** Reverses `wrap`. Throws if the vault has been reset since the bytes were written. */
	async unwrap(wrapped: Uint8Array): Promise<Uint8Array> {
		const plain = await crypto.subtle.decrypt(
			{
				name: WRAP_ALGORITHM,
				iv: wrapped.subarray(0, NONCE_BYTES) as BufferSource,
				tagLength: TAG_BYTES * 8
			},
			this.master,
			wrapped.subarray(NONCE_BYTES) as BufferSource
		);
		return new Uint8Array(plain);
	}

	/**
	 * Generates the master key. Non-extractable, which is the entire point: once this resolves,
	 * the key material is reachable only through `encrypt` and `decrypt` on this object, and
	 * only from this origin in this browser profile.
	 */
	static async generateMaster(): Promise<CryptoKey> {
		return crypto.subtle.generateKey({ name: WRAP_ALGORITHM, length: 256 }, false, [
			'encrypt',
			'decrypt'
		]);
	}
}
