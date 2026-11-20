import { describe, expect, it, vi } from 'vitest';
import { importKey, open, randomKeyBytes, seal } from './aead';
import { utf8 } from '$lib/util/bytes';

const key = () => importKey(randomKeyBytes());

describe('aead', () => {
	it('round-trips without aad', async () => {
		const k = await key();
		const plaintext = utf8('a voice note, as far as this layer is concerned');
		expect(await open(k, await seal(k, plaintext))).toEqual(plaintext);
	});

	it('round-trips with aad', async () => {
		const k = await key();
		const aad = utf8('FG1 header');
		const plaintext = utf8('hello');
		expect(await open(k, await seal(k, plaintext, aad), aad)).toEqual(plaintext);
	});

	it('refuses to open when the aad does not match', async () => {
		const k = await key();
		const sealed = await seal(k, utf8('hello'), utf8('one header'));
		await expect(open(k, sealed, utf8('another one'))).rejects.toThrow();
	});

	/**
	 * Chrome throws `AeadParams: additionalData: Not a BufferSource` for a params object that
	 * carries the key with an `undefined` value, where WebIDL says an explicit `undefined` means
	 * absent. Node's WebCrypto is the lenient one, so no round-trip test can catch this — only
	 * looking at what is actually handed to `subtle` can. Every attachment blob is sealed without
	 * aad, so getting this wrong loses photos and voice notes and nothing else.
	 */
	it('leaves additionalData out entirely when there is none', async () => {
		const k = await key();
		const encrypt = vi.spyOn(crypto.subtle, 'encrypt');
		const decrypt = vi.spyOn(crypto.subtle, 'decrypt');
		try {
			const sealed = await seal(k, utf8('no aad here'));
			await open(k, sealed);
			expect(encrypt.mock.calls[0][0]).not.toHaveProperty('additionalData');
			expect(decrypt.mock.calls[0][0]).not.toHaveProperty('additionalData');
		} finally {
			encrypt.mockRestore();
			decrypt.mockRestore();
		}
	});
});
