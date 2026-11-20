import { db, type Broker, type Group, type Member, type Message } from '$lib/data/db/schema';
import { importKey, randomKeyBytes } from '$lib/crypto/aead';
import { seal } from '$lib/crypto/envelope';
import { openVault } from '$lib/crypto/vault';
import type { Invite } from '$lib/crypto/invite';
import { deleteBlob, getBlob, putBlob } from '$lib/data/blob/blobStore';
import { enqueue, enqueueLatest } from './outboxRepo';
import { encodeControl, type LocationSettingsControl } from '$lib/mqtt/controlMessages';
import { encodeChat } from '$lib/mqtt/chatMessages';
import { encodeLocation, type LocationPayload } from '$lib/mqtt/locationMessages';
import {
	attachmentTopic,
	controlTopic,
	locationTopic,
	messagesTopic,
	normalisePrefix,
	settingsTopic
} from '$lib/mqtt/topics';
import { randomUuid, sha256Hex } from '$lib/util/bytes';

/**
 * Groups, their keys, and everything published under them.
 *
 * Every outbound payload in the app goes through `publish` here, so the rules about epochs and
 * counters exist once. Nothing calls the MQTT client directly — a message becomes an outbox row
 * and the pump takes it from there.
 */

export async function listGroups(): Promise<Group[]> {
	return db.groups.orderBy('createdAt').toArray();
}

export async function getGroup(groupId: string): Promise<Group | undefined> {
	return db.groups.get(groupId);
}

export async function listMembers(groupId: string): Promise<Member[]> {
	return db.members.where('groupId').equals(groupId).toArray();
}

const GROUP_DEFAULTS = {
	locationEnabled: false,
	locSampleSec: 60,
	locPushSec: 300,
	locMinDistanceM: 25,
	locRetentionHours: 168,
	shareLocation: false
};

/**
 * Creates a group. The key is generated here and never leaves this device except inside an
 * invite QR — there is no key exchange to get wrong because there is no exchange at all.
 */
export async function createGroup(name: string, broker: Broker): Promise<Group> {
	const identity = await db.identity.get(0);
	if (!identity) throw new Error('no identity yet');
	const { vault } = await openVault();
	const now = Date.now();
	const group: Group = {
		groupId: randomUuid(),
		name: name.trim(),
		brokerId: broker.id,
		topicPrefix: normalisePrefix(broker.topicPrefix),
		keyEpoch: 0,
		isAdmin: true,
		createdAt: now,
		sendCounter: 0,
		...GROUP_DEFAULTS
	};
	await db.groups.put(group);
	await db.groupKeys.put({
		groupId: group.groupId,
		epoch: 0,
		wrappedKey: await vault.wrap(randomKeyBytes()),
		createdAt: now
	});
	await db.members.put({
		groupId: group.groupId,
		userId: identity.userId,
		displayName: identity.displayName,
		avatarBlobId: identity.avatarBlobId,
		isAdmin: true,
		lastSeenAt: now,
		isRemoved: false,
		lastCounterMsg: 0,
		lastCounterCtl: 0
	});
	return group;
}

/**
 * Joins from a scanned code. Returns null if this device is already in the group, so a second
 * scan is a no-op rather than a way to reset your own membership.
 */
export async function joinGroup(invite: Invite, brokerId: string): Promise<Group | null> {
	const identity = await db.identity.get(0);
	if (!identity) throw new Error('no identity yet');
	if (await db.groups.get(invite.groupId)) return null;

	const { vault } = await openVault();
	const now = Date.now();
	const group: Group = {
		groupId: invite.groupId,
		name: invite.groupName,
		brokerId,
		topicPrefix: normalisePrefix(invite.topicPrefix),
		keyEpoch: invite.epoch,
		isAdmin: invite.adminId === identity.userId,
		createdAt: now,
		sendCounter: 0,
		...GROUP_DEFAULTS
	};
	await db.groups.put(group);
	await db.groupKeys.put({
		groupId: group.groupId,
		epoch: invite.epoch,
		wrappedKey: await vault.wrap(invite.groupKey),
		createdAt: now
	});
	// The admin is known from the invite, so their authority is established before they have
	// said anything — a kick that arrives before their profile does still counts.
	await db.members.put({
		groupId: group.groupId,
		userId: invite.adminId,
		displayName: '',
		avatarBlobId: null,
		isAdmin: true,
		lastSeenAt: 0,
		isRemoved: false,
		lastCounterMsg: 0,
		lastCounterCtl: 0
	});
	await db.members.put({
		groupId: group.groupId,
		userId: identity.userId,
		displayName: identity.displayName,
		avatarBlobId: identity.avatarBlobId,
		isAdmin: group.isAdmin,
		lastSeenAt: now,
		isRemoved: false,
		lastCounterMsg: 0,
		lastCounterCtl: 0
	});
	return group;
}

/** The invite to show as a QR. Only meaningful for a group this device administers. */
export async function buildInvite(groupId: string, broker: Broker): Promise<Invite | null> {
	const group = await db.groups.get(groupId);
	const identity = await db.identity.get(0);
	if (!group || !identity) return null;
	const key = await groupKeyBytes(groupId, group.keyEpoch);
	if (!key) return null;

	// The invite's host/port describe a raw MQTT socket, which is what an Android phone needs
	// and what this app cannot use. Both are derived from the WebSocket URL so a code shown here
	// still scans there: the host is right, and the port is the conventional TLS MQTT one rather
	// than this browser's WebSocket port, which would be useless to a phone.
	const url = new URL(broker.url);
	const tls = url.protocol === 'wss:';
	return {
		groupId: group.groupId,
		groupName: group.name,
		adminId: identity.userId,
		epoch: group.keyEpoch,
		groupKey: key,
		host: url.hostname,
		port: tls ? 8883 : 1883,
		tls,
		allowInsecureTls: false,
		username: broker.username,
		password: broker.password,
		topicPrefix: group.topicPrefix
	};
}

export async function groupKeyBytes(groupId: string, epoch: number): Promise<Uint8Array | null> {
	const row = await db.groupKeys.get([groupId, epoch]);
	if (!row) return null;
	const { vault } = await openVault();
	return vault.unwrap(row.wrappedKey);
}

/**
 * The next counter for a group, taken from the database so a reload never reuses one — a reused
 * counter looks to every receiver exactly like a replay, and is dropped.
 */
async function nextCounter(groupId: string): Promise<number> {
	return db.transaction('rw', db.groups, async () => {
		const group = await db.groups.get(groupId);
		if (!group) return 0;
		const next = group.sendCounter + 1;
		await db.groups.update(groupId, { sendCounter: next });
		return next;
	});
}

/**
 * Seals `plaintext` for a group and queues it. Returns the counter it was sealed with, so a
 * caller recording its own copy does not have to ask the database for a second one — or null if
 * there was nothing to seal it with.
 */
export async function publish(
	groupId: string,
	topicFor: (prefix: string, groupId: string) => string,
	plaintext: Uint8Array,
	options: {
		qos?: number;
		retained?: boolean;
		refMessageId?: string | null;
		/** How long this is worth publishing, or omitted to keep trying until it goes, which is
		 * what everything but call signalling wants — see `Outbox.expiresAt`. */
		expiresInMillis?: number;
	} = {}
): Promise<number | null> {
	const group = await db.groups.get(groupId);
	const identity = await db.identity.get(0);
	if (!group || !identity) return null;
	const keyBytes = await groupKeyBytes(groupId, group.keyEpoch);
	if (!keyBytes) return null;

	const counter = await nextCounter(groupId);
	const payload = await seal(
		await importKey(keyBytes),
		group.keyEpoch,
		identity.userId,
		BigInt(counter),
		plaintext
	);
	await enqueue({
		groupId,
		topic: topicFor(group.topicPrefix, groupId),
		payload,
		qos: options.qos ?? 1,
		retained: options.retained ?? false,
		refMessageId: options.refMessageId ?? null,
		expiresInMillis: options.expiresInMillis
	});
	return counter;
}

/**
 * Seals a voice packet and publishes it straight through the broker connection, skipping the
 * outbox entirely. The one place in this app that does — see `MqttConnection.publishNow`.
 *
 * Everything it needs is passed in rather than looked up, because this is called seventeen times
 * a second and a Dexie read per packet is exactly the cost the outbox bypass exists to avoid.
 *
 * Returns false when this tab is not the one holding the socket. A follower tab can still be in
 * a call — it rings, answers and hears nothing — and saying so is better than silently routing
 * voice through the outbox, which would arrive late enough to be useless anyway.
 */
export async function publishVoice(
	brokerId: string,
	topic: string,
	key: CryptoKey,
	epoch: number,
	senderId: string,
	counter: number,
	plaintext: Uint8Array
): Promise<boolean> {
	const { connection } = await import('$lib/stores/connection.svelte');
	const live = connection.pool.peek(brokerId);
	if (!live) return false;
	live.publishNow(topic, await seal(key, epoch, senderId, BigInt(counter), plaintext));
	return true;
}

// --- Chat -------------------------------------------------------------------------------------

export async function listMessages(groupId: string): Promise<Message[]> {
	return db.messages
		.where('[groupId+createdAt]')
		.between([groupId, 0], [groupId, Infinity])
		.toArray();
}

/**
 * Sends a text message: sealed and queued like anything else, plus a local copy so the sender
 * sees their own message immediately rather than waiting for the broker to echo it back. It
 * starts PENDING and the outbox flips it to SENT once the broker actually has it.
 */
export async function sendText(
	groupId: string,
	body: string,
	replyToId: string | null = null
): Promise<Message | null> {
	const identity = await db.identity.get(0);
	if (!identity || !body.trim()) return null;
	const id = randomUuid();
	const message: Message = {
		id,
		groupId,
		senderId: identity.userId,
		type: 'text',
		body: body.trim(),
		blobId: null,
		createdAt: Date.now(),
		state: 'PENDING',
		counter: 0,
		read: true,
		replyToId
	};
	await db.messages.put(message);
	const counter = await publish(
		groupId,
		messagesTopic,
		encodeChat({ kind: 'text', id, body: message.body!, replyToId }),
		{ refMessageId: id }
	);
	if (counter === null) {
		await db.messages.update(id, { state: 'FAILED' });
		return null;
	}
	await db.messages.update(id, { counter });
	return { ...message, counter };
}

/**
 * Sends an attachment: the bytes on their own topic, the reference on `/msg`.
 *
 * Two publishes rather than one, because a device that only wants to render "so-and-so sent a
 * photo" should not have to receive every image in the group to do it — and because one
 * near-megabyte payload does not then block the small control and text traffic behind it in a
 * single ordered stream.
 */
export async function sendAttachment(
	groupId: string,
	kind: 'image' | 'audio',
	bytes: Uint8Array,
	mime: string,
	caption: string | null = null,
	replyToId: string | null = null
): Promise<Message | null> {
	const identity = await db.identity.get(0);
	if (!identity) return null;

	const blobId = await putBlob(bytes);
	const sha256 = await sha256Hex(bytes);
	await db.attachments.put({
		blobId,
		groupId,
		mime,
		sizeBytes: bytes.length,
		sha256,
		createdAt: Date.now()
	});

	// The bytes first. If the reference went out ahead of them, everyone else would show a
	// photo-shaped hole for as long as the upload took.
	await publish(groupId, (prefix, gid) => attachmentTopic(prefix, gid, blobId), bytes);

	const id = randomUuid();
	const message: Message = {
		id,
		groupId,
		senderId: identity.userId,
		type: kind,
		body: caption,
		blobId,
		createdAt: Date.now(),
		state: 'PENDING',
		counter: 0,
		read: true,
		replyToId
	};
	await db.messages.put(message);
	const counter = await publish(
		groupId,
		messagesTopic,
		encodeChat({
			kind: 'attachment',
			id,
			attachmentKind: kind,
			blobId,
			mime,
			sizeBytes: bytes.length,
			sha256,
			caption,
			replyToId
		}),
		{ refMessageId: id }
	);
	if (counter === null) {
		await db.messages.update(id, { state: 'FAILED' });
		return null;
	}
	await db.messages.update(id, { counter });
	return { ...message, counter };
}

// --- Membership -------------------------------------------------------------------------------

/** Announces who we are, which is how everyone else's member list fills in. */
export async function announceSelf(groupId: string): Promise<void> {
	const identity = await db.identity.get(0);
	if (!identity) return;
	const avatar = identity.avatarBlobId ? await getBlob(identity.avatarBlobId) : null;
	await publish(
		groupId,
		controlTopic,
		encodeControl({
			kind: 'profile',
			userId: identity.userId,
			displayName: identity.displayName,
			avatar
		})
	);
	// And apply it here, because nothing else will. The receiver drops our own messages coming
	// back from the broker — they are already in the database — so the one member row this
	// announcement never reached was our own, which would keep whatever name and picture it was
	// created with, on screen, in our own group, for as long as the group exists.
	const existing = await db.members.get([groupId, identity.userId]);
	const group = await db.groups.get(groupId);
	await db.members.put({
		groupId,
		userId: identity.userId,
		displayName: identity.displayName,
		avatarBlobId: identity.avatarBlobId,
		// Everything that is not the profile is preserved: admin standing, the counters used for
		// replay detection, and whether we were removed.
		isAdmin: existing?.isAdmin ?? group?.isAdmin ?? false,
		lastSeenAt: Date.now(),
		isRemoved: existing?.isRemoved ?? false,
		lastCounterMsg: existing?.lastCounterMsg ?? 0,
		lastCounterCtl: existing?.lastCounterCtl ?? 0
	});
}

/**
 * Re-states the group's location rules, retained, without changing them. Admin only and a no-op
 * for anyone else.
 *
 * Sent when a new member announces themselves — the same moment `announceSelf` answers with a
 * name — so a joiner picks up the rules rather than sitting on "location sharing is off" while
 * everyone else is on the map.
 */
export async function republishLocationSettings(groupId: string): Promise<void> {
	const group = await db.groups.get(groupId);
	if (!group?.isAdmin) return;
	const settings = encodeControl(settingsOf(group));
	await publish(groupId, settingsTopic, settings, { retained: true });
	await publish(groupId, controlTopic, settings);
}

function settingsOf(group: Group): LocationSettingsControl {
	return {
		kind: 'locset',
		enabled: group.locationEnabled,
		sampleSec: group.locSampleSec,
		pushSec: group.locPushSec,
		minDistanceM: group.locMinDistanceM,
		retentionHours: group.locRetentionHours
	};
}

/**
 * Removes someone. Only an admin's kick is obeyed by other devices, and even then it is a
 * request other clients honour rather than a lock on the door: the person still holds the group
 * key. The UI must say so.
 */
export async function kickMember(groupId: string, userId: string): Promise<void> {
	const group = await db.groups.get(groupId);
	if (!group?.isAdmin) return;
	// A compound key goes through `where`, not `update`: Dexie only types `update` against a
	// table that declared a single primary-key property.
	await db.members.where('[groupId+userId]').equals([groupId, userId]).modify({ isRemoved: true });
	await publish(groupId, controlTopic, encodeControl({ kind: 'kick', targetUserId: userId }));
}

export async function leaveGroup(groupId: string): Promise<void> {
	const identity = await db.identity.get(0);
	if (!identity) return;
	await publish(groupId, controlTopic, encodeControl({ kind: 'leave', userId: identity.userId }));
	await forgetGroup(groupId);
}

export async function deleteForEveryone(groupId: string): Promise<void> {
	const group = await db.groups.get(groupId);
	if (!group?.isAdmin) return;
	await publish(groupId, controlTopic, encodeControl({ kind: 'delete' }));
	await forgetGroup(groupId);
}

/**
 * Drops a group from this device: its messages, its members and — the part that matters — its
 * key, after which anything of it still in the database or on the broker is unreadable here.
 */
export async function forgetGroup(groupId: string): Promise<void> {
	// Blobs first: once the rows are gone there is nothing left pointing at them, and an
	// orphaned encrypted blob would sit in IndexedDB until the whole app was deleted.
	const attachments = await db.attachments.where('groupId').equals(groupId).toArray();
	for (const a of attachments) await deleteBlob(a.blobId);
	await db.attachments.where('groupId').equals(groupId).delete();
	await db.receipts.where('groupId').equals(groupId).delete();
	await db.messages.where('groupId').equals(groupId).delete();
	await db.locations.where('groupId').equals(groupId).delete();
	await db.members.where('groupId').equals(groupId).delete();
	await db.groupKeys.where('groupId').equals(groupId).delete();
	await db.outbox.where('groupId').equals(groupId).delete();
	await db.groups.delete(groupId);
}

// --- Receipts ---------------------------------------------------------------------------------

/**
 * Marks everything from someone else as opened, and tells each sender so — this is the one
 * receipt that reflects an actual person looking at the screen, so it is never sent for
 * anything but that.
 */
export async function markRead(groupId: string, selfUserId: string): Promise<void> {
	const unread = await db.messages
		.where('[groupId+createdAt]')
		.between([groupId, 0], [groupId, Infinity])
		.filter((m) => !m.read && m.senderId !== selfUserId)
		.toArray();
	if (unread.length === 0) return;
	await db.messages.bulkUpdate(unread.map((m) => ({ key: m.id, changes: { read: true } })));
	for (const m of unread) {
		await publish(groupId, controlTopic, encodeControl({ kind: 'read', messageId: m.id }));
	}
}

/** Fired the moment an incoming message is stored, so its sender learns it arrived even before
 * anyone has opened the chat to read it. */
export async function sendDeliveredReceipt(groupId: string, messageId: string): Promise<void> {
	await publish(groupId, controlTopic, encodeControl({ kind: 'delivered', messageId }));
}

export async function unreadCounts(selfUserId: string): Promise<Record<string, number>> {
	const counts: Record<string, number> = {};
	await db.messages
		.filter((m) => !m.read && m.senderId !== selfUserId)
		.each((m) => {
			counts[m.groupId] = (counts[m.groupId] ?? 0) + 1;
		});
	return counts;
}

// --- Location ---------------------------------------------------------------------------------

/** Admin only: changes the group's rules and publishes them, retained. */
export async function setLocationSettings(
	groupId: string,
	settings: LocationSettingsControl
): Promise<void> {
	const group = await db.groups.get(groupId);
	if (!group?.isAdmin) return;
	await applyLocationSettings(groupId, settings);
	const encoded = encodeControl(settings);
	await publish(groupId, settingsTopic, encoded, { retained: true });
	await publish(groupId, controlTopic, encoded);
}

/** Applies rules that arrived from the admin. Never touches `shareLocation`, which is ours. */
export async function applyLocationSettings(
	groupId: string,
	settings: LocationSettingsControl
): Promise<void> {
	await db.groups.update(groupId, {
		locationEnabled: settings.enabled,
		locSampleSec: settings.sampleSec,
		locPushSec: settings.pushSec,
		locMinDistanceM: settings.minDistanceM,
		locRetentionHours: settings.retentionHours
	});
}

export async function setShareLocation(groupId: string, share: boolean): Promise<void> {
	await db.groups.update(groupId, { shareLocation: share });
}

/**
 * Publishes our own position, retained, superseding anything still queued for the same topic.
 *
 * The counter still comes from the same sequence as everything else — it is one sender's global
 * sequence, not one per topic — even though the receiver deliberately does not replay-check
 * positions.
 */
export async function publishLocation(groupId: string, payload: LocationPayload): Promise<void> {
	const group = await db.groups.get(groupId);
	const identity = await db.identity.get(0);
	if (!group || !identity) return;
	const keyBytes = await groupKeyBytes(groupId, group.keyEpoch);
	if (!keyBytes) return;

	const counter = await nextCounter(groupId);
	const sealed = await seal(
		await importKey(keyBytes),
		group.keyEpoch,
		identity.userId,
		BigInt(counter),
		encodeLocation(payload)
	);
	await enqueueLatest({
		groupId,
		topic: locationTopic(group.topicPrefix, groupId, identity.userId),
		payload: sealed
	});
	await db.locations.add({
		groupId,
		userId: identity.userId,
		lat: payload.lat,
		lon: payload.lon,
		accuracy: payload.accuracy,
		speed: payload.speed,
		bearing: payload.bearing,
		capturedAt: payload.capturedAt
	});
}
