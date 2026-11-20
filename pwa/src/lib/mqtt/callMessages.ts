import { fromUtf8, utf8 } from '$lib/util/bytes';

/**
 * The plaintext inside a call envelope: the signalling for one call, and no media at all.
 *
 * Kept apart from the control messages rather than added to them because the two have opposite
 * lifetimes. A control message is a durable fact about a group — who is a member, what the
 * location rules are — and is worth applying whenever it arrives. These are the opposite: they
 * expire, they are worthless late, and replaying one rings a device about a call that is long
 * over. The topic they travel on is never retained for the same reason.
 *
 * **Nobody's identity is in the body.** Who is calling is the envelope's authenticated sender,
 * exactly as for a profile announcement, so no member can publish a ring on another member's
 * behalf. What that still does not buy is authenticity *between* members: a shared group key
 * means any member can forge an offer that appears to come from any other member, unchanged
 * from every other message in this app. The incoming-call UI must not claim more certainty
 * about who is calling than the rest of the app does.
 *
 * Wire-identical to `mqtt/CallMessages.kt` in `../family-group`; see `vectors/wire-vectors.json`.
 */

/**
 * What kind of call this is. An unknown mode is dropped rather than assumed, which is what lets
 * a new one be added without an older build misreading it — it simply does not ring.
 *
 * - `audio` — voice over the broker. Built and working, and nothing in the UI sends it today: a
 *   call costs a steady 36 kbps each way through somebody's broker for as long as people talk.
 *   The path is kept rather than deleted, because it is tested and becomes reasonable again the
 *   moment the broker is one you own.
 * - `buzz` — make that device ring, and nothing else. No microphone, no stream, no bandwidth
 *   beyond the handful of signalling messages.
 */
export type CallMode = 'audio' | 'buzz';

export type DeclineReason = 'declined' | 'busy';

/**
 * "I am calling you." Sent once, by the caller.
 *
 * `toUserId` is the one member being dialled, or **null to ring the whole group**. Both are
 * ordinary: a direct call names a member, a group call names nobody and every member rings.
 * Everyone who is not being rung decrypts this and ignores it.
 *
 * An older build refuses to decode an offer with no target, so it does not ring for a group call
 * rather than misreading one — which is the right way round for this to degrade.
 */
export interface OfferCall {
	kind: 'offer';
	callId: string;
	toUserId: string | null;
	mode: CallMode;
	/**
	 * How long this offer is good for, **as a duration and never as a deadline**.
	 *
	 * Each side counts it from when it locally minted or received the offer, never from a
	 * timestamp in the body compared against the local clock. Two devices in one family are
	 * routinely tens of seconds apart and occasionally hours; a wall-clock deadline gives either
	 * a ring that never starts or one that never stops, and the bug only ever shows up on
	 * somebody else's device. The cost is that an offer delayed in the network rings for its
	 * full life late rather than being discarded on arrival — which is what `ring` is for, since
	 * it is the caller who can tell the difference.
	 */
	ttlSec: number;
}

/**
 * "Your offer reached me and I am ringing."
 *
 * The caller's UI says "calling" until this arrives and "ringing" afterwards, which is the
 * difference between having published into the void and having reached a device that is awake.
 * In this app that distinction is not cosmetic: a browser tab that is closed cannot ring at
 * all, so a caller who never sees this knows to expect a callback rather than an answer.
 */
export interface RingCall {
	kind: 'ring';
	callId: string;
}

/**
 * "I am joining this call." Sent by whoever answers, and by anyone already in the room when
 * somebody new joins — which is how a late joiner learns who is already there, since nothing on
 * this topic is retained. For two people this is simply "I am picking up".
 */
export interface AcceptCall {
	kind: 'accept';
	callId: string;
}

/** "I am not picking up." `busy` is sent automatically when another call is already up;
 * `declined` is a person pressing the red button. */
export interface DeclineCall {
	kind: 'decline';
	callId: string;
	reason: DeclineReason;
}

/** "Never mind." Sent by the caller, before any answer. */
export interface CancelCall {
	kind: 'cancel';
	callId: string;
}

/**
 * **"I have left."** Sent by anyone in a connected call, and the reason it is not another
 * `cancel`: before an answer the caller cancels and the callee declines, and after one there is
 * no caller and no callee, only people in a room.
 *
 * For two people "I have left" and "the call is over" are the same sentence, which is why phase
 * 8 read it as the latter. For a room they are not, so phase 10 reads it as the former. No bytes
 * changed; only the meaning.
 */
export interface EndCall {
	kind: 'end';
	callId: string;
}

export type Call = OfferCall | RingCall | AcceptCall | DeclineCall | CancelCall | EndCall;

const TYPE = 't';
const CALL = 'c';
const TARGET = 'x';
const MODE = 'm';
const LIFE = 'l';
const REASON = 'r';

/** Long enough that someone can reach a device in another room, short enough that the caller
 * has not given up. Also what the wake-up push's TTL is set to. */
export const DEFAULT_TTL_SEC = 45;

/** Clamped on the way in, not merely on the way out. This slider does not exist in the UI, but
 * a modified build could send a ttl of a day — a device that rings until the battery dies — or
 * of zero, an offer that expires before it can be shown. */
export const MIN_TTL_SEC = 10;
export const MAX_TTL_SEC = 120;

const MODES: readonly string[] = ['audio', 'buzz'];
const REASONS: readonly string[] = ['declined', 'busy'];

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.trunc(n)));

export function encodeCall(call: Call): Uint8Array {
	const json: Record<string, unknown> = { [TYPE]: call.kind, [CALL]: call.callId };
	switch (call.kind) {
		case 'offer':
			// Absent, not empty: a key that is present and blank is a target nobody has, which an
			// older build would read as a direct call to no one.
			if (call.toUserId !== null) json[TARGET] = call.toUserId;
			json[MODE] = call.mode;
			json[LIFE] = call.ttlSec;
			break;
		case 'decline':
			json[REASON] = call.reason;
			break;
		case 'ring':
		case 'accept':
		case 'cancel':
		case 'end':
			break;
	}
	return utf8(JSON.stringify(json));
}

/** Returns null rather than throwing: a malformed call message is one to drop. */
export function decodeCall(payload: Uint8Array): Call | null {
	try {
		const json = JSON.parse(fromUtf8(payload));
		const callId = json[CALL];
		if (typeof callId !== 'string' || !callId) return null;
		switch (json[TYPE]) {
			case 'offer': {
				// A missing target is a group call, not a malformed message.
				const toUserId = typeof json[TARGET] === 'string' && json[TARGET] ? json[TARGET] : null;
				const mode = json[MODE];
				if (typeof mode !== 'string' || !MODES.includes(mode)) return null;
				return {
					kind: 'offer',
					callId,
					toUserId,
					mode: mode as CallMode,
					ttlSec: clamp(
						typeof json[LIFE] === 'number' && Number.isFinite(json[LIFE])
							? json[LIFE]
							: DEFAULT_TTL_SEC,
						MIN_TTL_SEC,
						MAX_TTL_SEC
					)
				};
			}
			case 'ring':
				return { kind: 'ring', callId };
			case 'accept':
				return { kind: 'accept', callId };
			case 'decline':
				// An unrecognised reason is still a decline; only the wording is lost.
				return {
					kind: 'decline',
					callId,
					reason: REASONS.includes(json[REASON]) ? (json[REASON] as DeclineReason) : 'declined'
				};
			case 'cancel':
				return { kind: 'cancel', callId };
			case 'end':
				return { kind: 'end', callId };
			// An older build ignoring a newer one's traffic, rather than guessing.
			default:
				return null;
		}
	} catch {
		return null;
	}
}
