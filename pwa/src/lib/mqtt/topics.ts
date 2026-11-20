/**
 * The topic layout, in one place because both ends of every feature depend on agreeing about
 * it. Identical to the Android app's `Topics`.
 *
 * ```
 * <prefix>/<groupId>/msg                chat, QoS 1
 * <prefix>/<groupId>/ctl                join, leave, kick, profile — QoS 1
 * <prefix>/<groupId>/set                the group's location settings, QoS 1, retained
 * <prefix>/<groupId>/call               call signalling, QoS 1, never retained
 * <prefix>/<groupId>/aud/<userId>       voice, QoS 0, never retained
 * <prefix>/<groupId>/att/<blobId>       attachment bytes, QoS 1
 * <prefix>/<groupId>/loc/<userId>       latest position, QoS 0, retained
 * <prefix>/<groupId>/presence/<userId>  online flag, QoS 1, retained, cleared by the will
 * ```
 *
 * Topic names are visible to the broker. That is the one thing this design leaks — who talks to
 * whom and when, though never what — and it is why the prefix is the user's to choose.
 */

export const DEFAULT_PREFIX = 'familygroup';

export const KINDS = new Set(['msg', 'ctl', 'set', 'call', 'aud', 'att', 'loc', 'presence']);

/**
 * MQTT forbids a leading or trailing slash from meaning what people expect, and an empty level
 * is legal but confusing, so a prefix is trimmed to its useful middle.
 */
export function normalisePrefix(prefix: string): string {
	const trimmed = prefix.trim().replace(/^\/+/, '').replace(/\/+$/, '');
	return trimmed || DEFAULT_PREFIX;
}

/**
 * A prefix has to survive being pasted into a topic filter, so the wildcards and the level
 * separator are out, along with whitespace.
 */
export function isValidPrefix(prefix: string): boolean {
	const trimmed = prefix.trim().replace(/^\/+/, '').replace(/\/+$/, '');
	return trimmed.length > 0 && !/[#+\s]/.test(trimmed) && !trimmed.includes('//');
}

export const groupTopic = (prefix: string, groupId: string) =>
	`${normalisePrefix(prefix)}/${groupId}`;

export const messagesTopic = (prefix: string, groupId: string) =>
	`${groupTopic(prefix, groupId)}/msg`;

export const controlTopic = (prefix: string, groupId: string) =>
	`${groupTopic(prefix, groupId)}/ctl`;

/**
 * The group's settings, retained, so they are a *state* and not an event. On `/ctl` alone they
 * only reached whoever was subscribed at the moment the admin made the change — never the
 * member who joins tomorrow, who would then sit looking at "location sharing is off" while
 * everyone else was on the map.
 */
export const settingsTopic = (prefix: string, groupId: string) =>
	`${groupTopic(prefix, groupId)}/set`;

/**
 * Call signalling, and the one topic here that must **never** be retained.
 *
 * Everything else on this list is either an event worth replaying or a state worth keeping,
 * which is why `set`, `loc` and `presence` are retained. A retained ring is that same mechanism
 * producing the worst possible result — a device that rings on every resubscribe for a call
 * that ended last Tuesday.
 *
 * Separate from `/ctl` rather than more control cases because these expire and those do not.
 */
export const callTopic = (prefix: string, groupId: string) => `${groupTopic(prefix, groupId)}/call`;

/**
 * Voice, at QoS 0 — and the zero is the point.
 *
 * QoS 1 would have the broker redeliver a packet that was late, and a late audio packet is not
 * one that was missed: the jitter buffer has already played silence over its moment.
 * Redelivering it spends bandwidth to deliver noise.
 *
 * Per sender, like `loc`, so two people talking at once are two streams rather than one
 * interleaved mess.
 */
export const audioTopic = (prefix: string, groupId: string, userId: string) =>
	`${groupTopic(prefix, groupId)}/aud/${userId}`;

export const attachmentTopic = (prefix: string, groupId: string, blobId: string) =>
	`${groupTopic(prefix, groupId)}/att/${blobId}`;

export const locationTopic = (prefix: string, groupId: string, userId: string) =>
	`${groupTopic(prefix, groupId)}/loc/${userId}`;

export const presenceTopic = (prefix: string, groupId: string, userId: string) =>
	`${groupTopic(prefix, groupId)}/presence/${userId}`;

/** Everything for one group, which is the only subscription a client ever needs. */
export const everythingTopic = (prefix: string, groupId: string) =>
	`${groupTopic(prefix, groupId)}/#`;

/** `<prefix>/<groupId>/<kind>` — the group id is always the level before the kind. */
export function groupIdFor(topic: string): string | null {
	const parts = topic.split('/');
	const kind = parts.findIndex((p) => KINDS.has(p));
	return kind > 0 ? parts[kind - 1] : null;
}

export function kindOf(topic: string): string | null {
	return topic.split('/').find((p) => KINDS.has(p)) ?? null;
}
