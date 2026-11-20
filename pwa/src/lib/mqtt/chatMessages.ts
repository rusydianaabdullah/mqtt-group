import { fromUtf8, utf8 } from '$lib/util/bytes';

/**
 * The plaintext inside a `/msg` envelope. A message carries its own id rather than relying on
 * the envelope's (sender, counter) pair to name it, because that pair is meaningless to the
 * sender's own copy — the id is what lets a reply refer back to a specific message from any
 * device.
 *
 * An attachment message is a reference, not the bytes: those go separately on the group's
 * `/att/<blobId>` topic, sealed the same way, so a device that only wants to show "so-and-so
 * sent a photo" does not have to download every image and voice note to render the chat list.
 */

export interface TextPayload {
	kind: 'text';
	id: string;
	body: string;
	replyToId: string | null;
}

export interface AttachmentPayload {
	kind: 'attachment';
	id: string;
	/** "image" or "audio" — what `Message.type` wants directly, so nothing downstream translates. */
	attachmentKind: string;
	blobId: string;
	mime: string;
	sizeBytes: number;
	sha256: string;
	/** A photo's caption, typed before sending. Voice notes never have one. */
	caption: string | null;
	replyToId: string | null;
}

export type ChatPayload = TextPayload | AttachmentPayload;

const ID = 'id';
const TYPE = 't';
const BODY = 'b';
const KIND = 'k';
const BLOB = 'blob';
const MIME = 'mime';
const SIZE = 'size';
const SHA = 'sha';
const CAPTION = 'cap';
const REPLY = 'r';

export function encodeChat(payload: ChatPayload): Uint8Array {
	const json: Record<string, unknown> = {
		[ID]: payload.id,
		[TYPE]: payload.kind === 'text' ? 'text' : 'attachment'
	};
	if (payload.replyToId) json[REPLY] = payload.replyToId;
	if (payload.kind === 'text') {
		json[BODY] = payload.body;
	} else {
		json[KIND] = payload.attachmentKind;
		json[BLOB] = payload.blobId;
		json[MIME] = payload.mime;
		json[SIZE] = payload.sizeBytes;
		json[SHA] = payload.sha256;
		if (payload.caption) json[CAPTION] = payload.caption;
	}
	return utf8(JSON.stringify(json));
}

/** Returns null for anything malformed: a message no device can parse is one to drop. */
export function decodeChat(payload: Uint8Array): ChatPayload | null {
	try {
		const json = JSON.parse(fromUtf8(payload));
		const id = json[ID];
		if (typeof id !== 'string') return null;
		const replyToId = typeof json[REPLY] === 'string' && json[REPLY] ? json[REPLY] : null;
		if (json[TYPE] === 'text') {
			if (typeof json[BODY] !== 'string') return null;
			return { kind: 'text', id, body: json[BODY], replyToId };
		}
		if (json[TYPE] === 'attachment') {
			const { [KIND]: k, [BLOB]: blob, [MIME]: mime, [SHA]: sha, [SIZE]: size } = json;
			if (
				typeof k !== 'string' ||
				typeof blob !== 'string' ||
				typeof mime !== 'string' ||
				typeof sha !== 'string' ||
				typeof size !== 'number'
			) {
				return null;
			}
			return {
				kind: 'attachment',
				id,
				attachmentKind: k,
				blobId: blob,
				mime,
				sizeBytes: size,
				sha256: sha,
				caption: typeof json[CAPTION] === 'string' && json[CAPTION] ? json[CAPTION] : null,
				replyToId
			};
		}
		return null;
	} catch {
		return null;
	}
}
