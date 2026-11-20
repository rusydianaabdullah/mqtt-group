import { describe, expect, it } from 'vitest';
import vectors from '../../../vectors/wire-vectors.json';
import { decodeInvite, encodeInvite, guessWebSocketUrl, PREAMBLE } from './invite';
import { toHex } from '$lib/util/bytes';

describe('invite', () => {
	it('decodes the committed vector', () => {
		const invite = decodeInvite(vectors.invite);
		expect(invite).not.toBeNull();
		expect(invite!.groupName).toBe('Keluarga');
		expect(invite!.host).toBe('broker.example');
		expect(invite!.port).toBe(8883);
		expect(invite!.tls).toBe(true);
		expect(invite!.username).toBe('ayah');
		expect(invite!.password).toBe('rahasia');
		expect(toHex(invite!.groupKey)).toHaveLength(64);
	});

	it('round-trips', () => {
		const decoded = decodeInvite(vectors.invite)!;
		expect(decodeInvite(encodeInvite(decoded))).toEqual(decoded);
	});

	it('returns null for whatever else a camera is pointed at', () => {
		// This is the common path, not the error path: a scanner sees URLs, Wi-Fi codes and
		// half-focused frames far more often than it sees one of ours.
		expect(decodeInvite('https://example.com')).toBeNull();
		expect(decodeInvite('WIFI:S=home;T=WPA;P=hunter2;;')).toBeNull();
		expect(decodeInvite('')).toBeNull();
		expect(decodeInvite(PREAMBLE + 'not-base64!!')).toBeNull();
		expect(decodeInvite(vectors.invite.slice(0, 30))).toBeNull();
	});

	it('rejects a code carrying a key of the wrong length', () => {
		const invite = decodeInvite(vectors.invite)!;
		const short = encodeInvite({ ...invite, groupKey: invite.groupKey.subarray(0, 16) });
		expect(decodeInvite(short)).toBeNull();
	});

	it('guesses a WebSocket URL to offer, since the code cannot carry one', () => {
		const invite = decodeInvite(vectors.invite)!;
		expect(guessWebSocketUrl(invite)).toBe('wss://broker.example:8084/mqtt');
		expect(guessWebSocketUrl({ ...invite, tls: false })).toBe('ws://broker.example:8083/mqtt');
	});
});
