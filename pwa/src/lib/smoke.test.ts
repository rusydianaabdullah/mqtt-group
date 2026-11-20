import { describe, expect, it } from 'vitest';

// Phase 0 has no logic to test. This one asserts the primitives phase 1 is about to build on
// actually exist in the runtime the tests run in, so a missing WebCrypto is discovered here
// rather than halfway through writing the key vault.
describe('runtime', () => {
	it('has WebCrypto with the algorithms the envelope needs', async () => {
		const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
			'encrypt',
			'decrypt'
		]);
		const nonce = crypto.getRandomValues(new Uint8Array(12));
		const sealed = await crypto.subtle.encrypt(
			{ name: 'AES-GCM', iv: nonce },
			key,
			new TextEncoder().encode('hello')
		);
		const opened = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, sealed);
		expect(new TextDecoder().decode(opened)).toBe('hello');
	});

	it('generates a key that cannot be exported', async () => {
		const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
			'encrypt'
		]);
		await expect(crypto.subtle.exportKey('raw', key)).rejects.toThrow();
	});
});
