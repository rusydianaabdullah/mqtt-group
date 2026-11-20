import { describe, expect, it } from 'vitest';
import vectors from '../../../vectors/wire-vectors.json';
import { decodeChat, encodeChat } from './chatMessages';
import { decodeControl, encodeControl, MAX_SEC, MIN_SAMPLE_SEC } from './controlMessages';
import { decodeLocation, encodeLocation } from './locationMessages';
import {
	decodeCall,
	encodeCall,
	DEFAULT_TTL_SEC,
	MAX_TTL_SEC,
	MIN_TTL_SEC,
	type OfferCall
} from './callMessages';
import { decodeAudio, encodeAudio, MAX_FRAMES } from './audioMessages';
import { fromBase64, toHex, utf8 } from '$lib/util/bytes';

describe('chat payloads', () => {
	it('decodes the committed vectors', () => {
		const text = decodeChat(fromBase64(vectors.chat.text));
		expect(text).toEqual({ kind: 'text', id: 'm-1', body: 'Sudah sampai?', replyToId: null });

		const att = decodeChat(fromBase64(vectors.chat.attachment));
		expect(att).toMatchObject({
			kind: 'attachment',
			id: 'm-2',
			attachmentKind: 'image',
			blobId: 'b-1',
			mime: 'image/jpeg',
			sizeBytes: 51234,
			caption: 'di depan rumah',
			replyToId: 'm-1'
		});
	});

	it('round-trips', () => {
		for (const encoded of [vectors.chat.text, vectors.chat.attachment]) {
			const decoded = decodeChat(fromBase64(encoded))!;
			expect(decodeChat(encodeChat(decoded))).toEqual(decoded);
		}
	});

	it('drops what it cannot parse', () => {
		expect(decodeChat(utf8('{}'))).toBeNull();
		expect(decodeChat(utf8('not json'))).toBeNull();
		expect(decodeChat(utf8('{"id":"x","t":"something-new"}'))).toBeNull();
		// An attachment missing its digest is not one we can verify, so it is not one we take.
		expect(decodeChat(utf8('{"id":"x","t":"attachment","k":"image","blob":"b"}'))).toBeNull();
	});
});

describe('control payloads', () => {
	it('decodes the committed vectors', () => {
		const profile = decodeControl(fromBase64(vectors.control.profile));
		expect(profile).toMatchObject({ kind: 'profile', displayName: 'Ayah' });
		expect(toHex((profile as { avatar: Uint8Array }).avatar)).toBe('cafebabe');

		expect(decodeControl(fromBase64(vectors.control.kick))).toMatchObject({ kind: 'kick' });
		expect(decodeControl(fromBase64(vectors.control.locset))).toEqual({
			kind: 'locset',
			enabled: true,
			sampleSec: 60,
			pushSec: 300,
			minDistanceM: 25,
			retentionHours: 168
		});
	});

	it('round-trips every kind', () => {
		const all = [
			{ kind: 'profile', userId: 'u', displayName: 'A', avatar: null },
			{ kind: 'kick', targetUserId: 'u' },
			{ kind: 'leave', userId: 'u' },
			{ kind: 'delete' },
			{ kind: 'delivered', messageId: 'm' },
			{ kind: 'read', messageId: 'm' }
		] as const;
		for (const c of all) expect(decodeControl(encodeControl(c))).toEqual(c);
	});

	it('clamps location settings on the way in, not merely on the way out', () => {
		// A modified build could publish a zero sample interval, which is a position request
		// that never sleeps — a flat battery by lunchtime on every device in the group.
		const hostile = utf8('{"t":"locset","e":true,"s":0,"p":-5,"d":999999,"r":-1}');
		expect(decodeControl(hostile)).toEqual({
			kind: 'locset',
			enabled: true,
			sampleSec: MIN_SAMPLE_SEC,
			pushSec: MIN_SAMPLE_SEC,
			minDistanceM: 1000,
			retentionHours: 0
		});
		expect(decodeControl(utf8('{"t":"locset","s":999999,"p":999999}'))).toMatchObject({
			sampleSec: MAX_SEC,
			pushSec: MAX_SEC
		});
	});

	it('drops what it cannot parse', () => {
		expect(decodeControl(utf8('{"t":"nonsense"}'))).toBeNull();
		expect(decodeControl(utf8('{"t":"kick"}'))).toBeNull();
		expect(decodeControl(utf8('garbage'))).toBeNull();
	});
});

describe('location payloads', () => {
	it('decodes the committed vector', () => {
		expect(decodeLocation(fromBase64(vectors.location))).toEqual({
			lat: -6.914744,
			lon: 107.60981,
			accuracy: 12.5,
			speed: 1.25,
			bearing: 180,
			capturedAt: 1757404800000
		});
	});

	it('round-trips', () => {
		const decoded = decodeLocation(fromBase64(vectors.location))!;
		expect(decodeLocation(encodeLocation(decoded))).toEqual(decoded);
	});

	it('refuses coordinates that are off the planet', () => {
		// Not paranoia about our own encoder — a member running a modified build can put a
		// latitude of 900 on the wire, and the map's answer to that is not an exception.
		expect(decodeLocation(utf8('{"la":900,"lo":0}'))).toBeNull();
		expect(decodeLocation(utf8('{"la":0,"lo":-181}'))).toBeNull();
		expect(decodeLocation(utf8('{"la":"north","lo":0}'))).toBeNull();
		expect(decodeLocation(utf8('{}'))).toBeNull();
	});

	it('defaults the fields a fix may genuinely not carry', () => {
		const p = decodeLocation(utf8('{"la":1,"lo":2,"at":5}'))!;
		expect(p).toEqual({ lat: 1, lon: 2, accuracy: 0, speed: 0, bearing: 0, capturedAt: 5 });
	});
});

describe('call payloads', () => {
	it('decodes the committed vectors', () => {
		expect(decodeCall(fromBase64(vectors.call.groupOffer))).toEqual({
			kind: 'offer',
			callId: 'c-2',
			toUserId: null,
			mode: 'audio',
			ttlSec: 45
		});
		expect(decodeCall(fromBase64(vectors.call.offer))).toEqual({
			kind: 'offer',
			callId: 'c-1',
			toUserId: '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
			mode: 'audio',
			ttlSec: 45
		});
		expect(decodeCall(fromBase64(vectors.call.ring))).toEqual({ kind: 'ring', callId: 'c-1' });
		expect(decodeCall(fromBase64(vectors.call.accept))).toEqual({ kind: 'accept', callId: 'c-1' });
		expect(decodeCall(fromBase64(vectors.call.declineBusy))).toEqual({
			kind: 'decline',
			callId: 'c-1',
			reason: 'busy'
		});
		expect(decodeCall(fromBase64(vectors.call.cancel))).toEqual({ kind: 'cancel', callId: 'c-1' });
		expect(decodeCall(fromBase64(vectors.call.end))).toEqual({ kind: 'end', callId: 'c-1' });
	});

	it('round-trips', () => {
		for (const encoded of Object.values(vectors.call)) {
			const decoded = decodeCall(fromBase64(encoded))!;
			expect(decodeCall(encodeCall(decoded))).toEqual(decoded);
		}
	});

	it('drops what it cannot parse', () => {
		expect(decodeCall(utf8('not json'))).toBeNull();
		expect(decodeCall(utf8('{}'))).toBeNull();
		// No call id is no call.
		expect(decodeCall(utf8('{"t":"ring"}'))).toBeNull();
		// An older build must ignore a newer one's traffic rather than guess at it.
		expect(decodeCall(utf8('{"t":"transfer","c":"c-1"}'))).toBeNull();
	});

	// An offer with no target rings the whole group — phase 10. It used to be malformed, which is
	// why an older build drops a group offer rather than misreading it, and why this test changed
	// rather than the format.
	it('reads an offer with no target as a group call', () => {
		expect(decodeCall(utf8('{"t":"offer","c":"c-1","m":"audio","l":45}'))).toMatchObject({
			kind: 'offer',
			toUserId: null,
			mode: 'audio'
		});
	});

	// An empty target is not "everyone" — it is a call to nobody, and a mistake either way.
	it('round-trips a group offer without inventing a target', () => {
		const offer = {
			kind: 'offer' as const,
			callId: 'c-1',
			toUserId: null,
			mode: 'audio' as const,
			ttlSec: 45
		};
		expect(decodeCall(encodeCall(offer))).toEqual(offer);
		expect(new TextDecoder().decode(encodeCall(offer))).not.toContain('"x"');
	});

	it('drops an offer missing the mode, which decides what to do with it', () => {
		expect(decodeCall(utf8('{"t":"offer","c":"c-1","x":"u-2","l":45}'))).toBeNull();
	});

	// Adding video later must not make this build answer a video call with its microphone.
	it('drops an offer in a mode it does not know', () => {
		expect(decodeCall(utf8('{"t":"offer","c":"c-1","x":"u-2","m":"video","l":45}'))).toBeNull();
	});

	// A ttl is not a slider anywhere in the UI, so these can only come from a modified build —
	// which is why they are clamped rather than trusted. A day-long ttl is a device that rings
	// until the battery dies; a zero is an offer that expires before it can be shown.
	it('clamps a ttl it would not have sent', () => {
		const offer = (json: string) => decodeCall(utf8(json)) as OfferCall;
		expect(offer('{"t":"offer","c":"c","x":"u","m":"audio","l":86400}').ttlSec).toBe(MAX_TTL_SEC);
		expect(offer('{"t":"offer","c":"c","x":"u","m":"audio","l":0}').ttlSec).toBe(MIN_TTL_SEC);
		expect(offer('{"t":"offer","c":"c","x":"u","m":"audio"}').ttlSec).toBe(DEFAULT_TTL_SEC);
	});

	// Only the wording is lost, never the fact that it was a no.
	it('treats an unrecognised decline reason as a decline', () => {
		expect(decodeCall(utf8('{"t":"decline","c":"c-1","r":"meeting"}'))).toEqual({
			kind: 'decline',
			callId: 'c-1',
			reason: 'declined'
		});
	});
});

describe('audio packets', () => {
	const frame = (...bytes: number[]) => new Uint8Array(bytes);

	it('decodes the committed vector', () => {
		const p = decodeAudio(fromBase64(vectors.audio))!;
		expect(p.streamId).toBe(0x01020304);
		// Unsigned here, and the same bits read as -2 on the Kotlin side.
		expect(p.seq).toBe(4294967294);
		expect(p.frames.map((f) => f.length)).toEqual([4, 1, 8]);
		expect(toHex(p.frames[0])).toBe('cafebabe');
	});

	it('round-trips', () => {
		const p = { streamId: 9, seq: 42, frames: [frame(1, 2, 3), frame(4)] };
		expect(decodeAudio(encodeAudio(p))).toEqual(p);
	});

	it('keeps frame order and boundaries', () => {
		const p = { streamId: 7, seq: 1, frames: [frame(1), frame(2, 2), frame(3, 3, 3)] };
		const back = decodeAudio(encodeAudio(p))!;
		expect(back.frames.map((f) => f.length)).toEqual([1, 2, 3]);
		expect(back.frames[2][0]).toBe(3);
	});

	it('drops what it cannot parse', () => {
		expect(decodeAudio(new Uint8Array(0))).toBeNull();
		expect(decodeAudio(utf8('not an audio packet'))).toBeNull();
		// Right length, wrong magic — an older build's traffic, or another protocol entirely.
		expect(decodeAudio(new Uint8Array(32))).toBeNull();
	});

	// A truncated packet is the ordinary result of a broker or a network cutting one short.
	it('drops a packet whose frame runs off the end', () => {
		const encoded = encodeAudio({ streamId: 1, seq: 1, frames: [frame(1, 2, 3, 4)] });
		expect(decodeAudio(encoded.slice(0, encoded.length - 2))).toBeNull();
	});

	// Without a bound, a crafted length field is an allocation of whatever size the sender
	// chooses — on a topic every member of the group can publish to.
	it('refuses a frame count and a frame length it could not have sent', () => {
		const header = new Uint8Array([0x46, 0x47, 0x41, 0x31, 0, 0, 0, 1, 0, 0, 0, 1]);
		const withTail = (...tail: number[]) => new Uint8Array([...header, ...tail]);
		expect(decodeAudio(withTail(0))).toBeNull();
		expect(decodeAudio(withTail(MAX_FRAMES + 1))).toBeNull();
		expect(decodeAudio(withTail(1, 0xff, 0xff))).toBeNull();
	});

	it('refuses to encode nothing', () => {
		expect(() => encodeAudio({ streamId: 1, seq: 1, frames: [] })).toThrow();
	});
});
