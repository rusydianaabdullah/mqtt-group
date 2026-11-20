import { describe, expect, it } from 'vitest';
import vectors from '../../../vectors/wire-vectors.json';
import { buildHeader, HEADER_BYTES, open, peek, seal } from './envelope';
import { importKey, randomKeyBytes } from './aead';
import { fromBase64, fromHex, fromUtf8, toHex, utf8 } from '$lib/util/bytes';

const key = () => importKey(fromHex(vectors.keyHex));

describe('envelope', () => {
	it('opens every committed vector', async () => {
		for (const v of vectors.envelopes) {
			const payload = fromBase64(v.sealed);
			const header = peek(payload);
			expect(header, v.plaintextUtf8).not.toBeNull();
			expect(header!.epoch).toBe(v.epoch);
			expect(header!.senderId).toBe(v.senderId);
			expect(header!.counter).toBe(BigInt(v.counter));

			const opened = await open(await key(), payload);
			expect(opened, v.plaintextUtf8).not.toBeNull();
			expect(fromUtf8(opened!.plaintext)).toBe(v.plaintextUtf8);
		}
	});

	it('builds the exact header bytes the vectors record', () => {
		for (const v of vectors.envelopes) {
			expect(toHex(buildHeader(v.epoch, v.senderId, BigInt(v.counter)))).toBe(v.header);
		}
	});

	it('round-trips what it seals', async () => {
		const k = await importKey(randomKeyBytes());
		const sealed = await seal(k, 3, vectors.envelopes[0].senderId, 42n, utf8('round trip'));
		const opened = await open(k, sealed);
		expect(fromUtf8(opened!.plaintext)).toBe('round trip');
		expect(opened!.counter).toBe(42n);
		expect(opened!.epoch).toBe(3);
	});

	it('refuses a payload whose header was rewritten', async () => {
		// The whole reason the header is AAD: a broker that rewinds the counter or renames the
		// sender must produce something that no longer opens, not something that opens and lies.
		const payload = fromBase64(vectors.envelopes[0].sealed);
		payload[HEADER_BYTES - 1] ^= 1;
		expect(await open(await key(), payload)).toBeNull();
	});

	it('refuses a payload sealed under a different key', async () => {
		const other = await importKey(randomKeyBytes());
		expect(await open(other, fromBase64(vectors.envelopes[0].sealed))).toBeNull();
	});

	it('rejects the things a broker can hand us that are not envelopes', () => {
		expect(peek(new Uint8Array(0))).toBeNull();
		expect(peek(utf8('not an envelope at all'))).toBeNull();
		// Right magic, too short to hold a nonce and a tag.
		expect(peek(new Uint8Array([...utf8('FG1'), ...new Uint8Array(HEADER_BYTES)]))).toBeNull();
	});

	it('refuses an epoch that would not fit in its byte', () => {
		expect(() => buildHeader(256, vectors.envelopes[0].senderId, 0n)).toThrow();
		expect(() => buildHeader(-1, vectors.envelopes[0].senderId, 0n)).toThrow();
	});
});
