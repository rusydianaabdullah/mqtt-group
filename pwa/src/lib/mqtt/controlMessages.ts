import { fromBase64, fromUtf8, toBase64, utf8 } from '$lib/util/bytes';

/**
 * The plaintext inside a control envelope. These are the only things that change a group's
 * membership, so each one carries who it is about and is judged against who sent it.
 *
 * There is no server to arbitrate, so authority is a local rule: a kick or a delete is obeyed
 * only when the sender is the group's admin. That is honest but limited — a member who keeps
 * the group key can ignore a kick and carry on reading, because the key is still the key. Real
 * revocation is key rotation. The UI should not imply otherwise.
 */

/** "I am here, and this is what I look like." Sent on join and whenever the profile changes. */
export interface ProfileControl {
	kind: 'profile';
	userId: string;
	displayName: string;
	avatar: Uint8Array | null;
}

/** Admin only. Everyone else's copy of the member list acts on it. */
export interface KickControl {
	kind: 'kick';
	targetUserId: string;
}

/** Sent by whoever is leaving, about themselves. */
export interface LeaveControl {
	kind: 'leave';
	userId: string;
}

/** Admin only. The group is over; every device drops its messages and its key. */
export interface DeleteControl {
	kind: 'delete';
}

/**
 * Admin only. The group's location rules.
 *
 * These are the group's settings rather than each device's preference on purpose — a family
 * group where everyone picks their own interval produces a map where some people are five
 * minutes stale and others thirty, and nobody can tell which.
 *
 * What it deliberately does *not* carry is who shares. Turning this on does not put anyone on
 * the map; each member still has their own switch, off until they touch it. An admin sets the
 * tempo, not the consent.
 */
export interface LocationSettingsControl {
	kind: 'locset';
	enabled: boolean;
	sampleSec: number;
	pushSec: number;
	minDistanceM: number;
	retentionHours: number;
}

/** "I have this message." Sent the moment a chat message is stored, so the sender's bubble can
 * move from a single check to a double one. */
export interface DeliveredControl {
	kind: 'delivered';
	messageId: string;
}

/** "I have looked at this message." Sent when the chat screen is opened, never automatically —
 * read status is the one receipt a person can actually feel watched by. */
export interface ReadControl {
	kind: 'read';
	messageId: string;
}

export type Control =
	| ProfileControl
	| KickControl
	| LeaveControl
	| DeleteControl
	| LocationSettingsControl
	| DeliveredControl
	| ReadControl;

const TYPE = 't';
const USER = 'u';
const NAME = 'n';
const AVATAR = 'a';
const TARGET = 'x';
const MESSAGE = 'm';
const ENABLED = 'e';
const SAMPLE = 's';
const PUSH = 'p';
const DISTANCE = 'd';
const RETENTION = 'r';

/** Ten seconds is as fast as this app will ever poll for a position, admin or not. */
export const MIN_SAMPLE_SEC = 10;
/** Six hours. Past this an interval is indistinguishable from "off", and saying off is clearer
 * than a number nobody will wait out. */
export const MAX_SEC = 6 * 60 * 60;
export const MAX_DISTANCE_M = 1000;
/** One year. 0 is the separate, explicit "keep forever". */
export const MAX_RETENTION_HOURS = 24 * 365;

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.trunc(n)));

export function encodeControl(control: Control): Uint8Array {
	const json: Record<string, unknown> = { [TYPE]: control.kind };
	switch (control.kind) {
		case 'profile':
			json[USER] = control.userId;
			json[NAME] = control.displayName;
			if (control.avatar) json[AVATAR] = toBase64(control.avatar);
			break;
		case 'kick':
			json[TARGET] = control.targetUserId;
			break;
		case 'leave':
			json[USER] = control.userId;
			break;
		case 'locset':
			json[ENABLED] = control.enabled;
			json[SAMPLE] = control.sampleSec;
			json[PUSH] = control.pushSec;
			json[DISTANCE] = control.minDistanceM;
			json[RETENTION] = control.retentionHours;
			break;
		case 'delivered':
		case 'read':
			json[MESSAGE] = control.messageId;
			break;
		case 'delete':
			break;
	}
	return utf8(JSON.stringify(json));
}

/** Returns null rather than throwing: a malformed control message is one to drop. */
export function decodeControl(payload: Uint8Array): Control | null {
	try {
		const json = JSON.parse(fromUtf8(payload));
		switch (json[TYPE]) {
			case 'profile':
				if (typeof json[USER] !== 'string' || typeof json[NAME] !== 'string') return null;
				return {
					kind: 'profile',
					userId: json[USER],
					displayName: json[NAME],
					avatar: typeof json[AVATAR] === 'string' && json[AVATAR] ? fromBase64(json[AVATAR]) : null
				};
			case 'kick':
				return typeof json[TARGET] === 'string'
					? { kind: 'kick', targetUserId: json[TARGET] }
					: null;
			case 'leave':
				return typeof json[USER] === 'string' ? { kind: 'leave', userId: json[USER] } : null;
			case 'delete':
				return { kind: 'delete' };
			// Clamped on the way in, not merely on the way out. An admin's slider cannot produce
			// a zero sample interval, but a modified build could, and a zero here becomes a
			// position request that never sleeps — a flat battery by lunchtime on every device in
			// the group. The bounds are the same ones the settings screen offers.
			case 'locset':
				return {
					kind: 'locset',
					enabled: json[ENABLED] === true,
					sampleSec: clamp(num(json[SAMPLE], 60), MIN_SAMPLE_SEC, MAX_SEC),
					pushSec: clamp(num(json[PUSH], 300), MIN_SAMPLE_SEC, MAX_SEC),
					minDistanceM: clamp(num(json[DISTANCE], 25), 0, MAX_DISTANCE_M),
					retentionHours: clamp(num(json[RETENTION], 168), 0, MAX_RETENTION_HOURS)
				};
			case 'delivered':
				return typeof json[MESSAGE] === 'string'
					? { kind: 'delivered', messageId: json[MESSAGE] }
					: null;
			case 'read':
				return typeof json[MESSAGE] === 'string'
					? { kind: 'read', messageId: json[MESSAGE] }
					: null;
			default:
				return null;
		}
	} catch {
		return null;
	}
}

function num(value: unknown, fallback: number): number {
	return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
