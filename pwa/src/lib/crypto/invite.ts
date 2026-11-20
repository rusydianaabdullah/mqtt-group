import { KEY_BYTES } from './aead';
import { fromBase64Url, fromUtf8, toBase64Url, utf8 } from '$lib/util/bytes';

/**
 * What a QR code contains: everything a device needs to join a group and nothing it could get
 * anywhere else. Identical to the Android app's `Invite`, field name for field name, so a code
 * shown by a phone scans here and one shown here scans on a phone.
 *
 * This *is* the group secret. Whoever photographs this code can read the group — there is no
 * server to also authenticate against, which is the whole point and also the whole risk. It
 * should be shown in person, not messaged.
 *
 * One thing it cannot give a browser: how to reach the broker. `host`/`port`/`tls` describe a
 * raw MQTT socket, which no browser can open — a browser needs `wss://` on the broker's
 * *WebSocket* listener, a different port and usually a path. So joining from a QR asks for
 * those two extra details, prefilled with the common defaults, and keeps everything else the
 * code already carried. Adding them to the format would have meant a QR that older phones
 * partly ignore for the sake of a field only browsers read.
 */
export interface Invite {
	groupId: string;
	groupName: string;
	adminId: string;
	epoch: number;
	groupKey: Uint8Array;
	host: string;
	port: number;
	tls: boolean;
	allowInsecureTls: boolean;
	username: string | null;
	password: string | null;
	topicPrefix: string;
}

export const PREAMBLE = 'FG1:';

const GROUP_ID = 'g';
const NAME = 'n';
const ADMIN = 'a';
const EPOCH = 'e';
const KEY = 'k';
const HOST = 'h';
const PORT = 'p';
const TLS = 't';
const INSECURE = 'i';
const USER = 'u';
const PASS = 'w';
const PREFIX = 'x';

export function encodeInvite(invite: Invite): string {
	const json: Record<string, unknown> = {
		[GROUP_ID]: invite.groupId,
		[NAME]: invite.groupName,
		[ADMIN]: invite.adminId,
		[EPOCH]: invite.epoch,
		[KEY]: toBase64Url(invite.groupKey),
		[HOST]: invite.host,
		[PORT]: invite.port,
		[TLS]: invite.tls,
		[INSECURE]: invite.allowInsecureTls,
		[PREFIX]: invite.topicPrefix
	};
	if (invite.username) json[USER] = invite.username;
	if (invite.password) json[PASS] = invite.password;
	return PREAMBLE + toBase64Url(utf8(JSON.stringify(json)));
}

/**
 * Returns null for anything that is not one of our invites — a URL, a Wi-Fi QR, a truncated
 * scan, a code from a newer version. The scanner points at whatever the user happens to aim it
 * at, so this is the common path, not the error path.
 */
export function decodeInvite(scanned: string): Invite | null {
	const trimmed = scanned.trim();
	if (!trimmed.startsWith(PREAMBLE)) return null;
	try {
		const json = JSON.parse(fromUtf8(fromBase64Url(trimmed.slice(PREAMBLE.length))));
		const groupKey = fromBase64Url(String(json[KEY]));
		// A short key would mean a corrupt or hostile code, and letting it through would fail
		// later somewhere far less obvious.
		if (groupKey.length !== KEY_BYTES) return null;
		const groupId = json[GROUP_ID];
		const groupName = json[NAME];
		const adminId = json[ADMIN];
		const host = json[HOST];
		const topicPrefix = json[PREFIX];
		if (
			typeof groupId !== 'string' ||
			typeof groupName !== 'string' ||
			typeof adminId !== 'string' ||
			typeof host !== 'string' ||
			typeof topicPrefix !== 'string' ||
			typeof json[EPOCH] !== 'number' ||
			typeof json[PORT] !== 'number'
		) {
			return null;
		}
		return {
			groupId,
			groupName,
			adminId,
			epoch: json[EPOCH],
			groupKey,
			host,
			port: json[PORT],
			tls: json[TLS] === true,
			allowInsecureTls: json[INSECURE] === true,
			username: typeof json[USER] === 'string' && json[USER] ? json[USER] : null,
			password: typeof json[PASS] === 'string' && json[PASS] ? json[PASS] : null,
			topicPrefix
		};
	} catch {
		return null;
	}
}

/**
 * The WebSocket URL to try first for a broker an invite described as a raw MQTT socket.
 *
 * A guess, and presented as one: 8083 and 8084 are what Mosquitto's own documentation uses for
 * plain and TLS WebSockets, EMQX ships the same pair, and `/mqtt` is the near-universal path.
 * The join screen shows it in an editable field rather than trying it silently, because when it
 * is wrong the failure is a socket that never opens and no way to tell why.
 */
export function guessWebSocketUrl(invite: Invite): string {
	const scheme = invite.tls ? 'wss' : 'ws';
	const port = invite.tls ? 8084 : 8083;
	return `${scheme}://${invite.host}:${port}/mqtt`;
}
