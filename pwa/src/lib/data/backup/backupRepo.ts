import { db, IDENTITY_SINGLETON, type Broker, type Group } from '$lib/data/db/schema';
import { openVault } from '$lib/crypto/vault';
import { openBackup, sealBackup, type BackupFailure } from './backupArchive';
import { fromBase64, toBase64 } from '$lib/util/bytes';
import { normalisePrefix } from '$lib/mqtt/topics';

/**
 * What goes into a backup and what comes out.
 *
 * Contents: identity, brokers, groups with their settings, every epoch of every group key, and
 * member lists. Not messages, location history or attachments — those are the bulk, and unlike
 * the keys they are replaceable.
 *
 * **Group keys are unwrapped on the way out and re-wrapped on the way in.** They live in the
 * database wrapped by the vault's master key, which is non-extractable and belongs to *this*
 * browser profile — backing up the wrapped bytes would produce a file that restores to a device
 * which cannot open them. It would look like a backup right up until it was needed. The cost is
 * that the plaintext group keys really are in the file, behind the passphrase and nothing else,
 * which the UI states plainly.
 *
 * The broker rows are the one place the two apps genuinely differ, and the file carries both
 * shapes: `host`/`port`/`tls` so an Android phone can read it, and `wsUrl` so this app does not
 * have to guess its way back to a WebSocket endpoint. Each side ignores what it does not need.
 */

const VERSION = 1;

export interface RestoreSummary {
	brokers: number;
	groups: number;
	keys: number;
	members: number;
	identityRestored: boolean;
}

export async function exportBackup(passphrase: string): Promise<Uint8Array> {
	const { vault } = await openVault();
	const identity = await db.identity.get(IDENTITY_SINGLETON);
	const brokers = await db.brokers.toArray();
	const groups = await db.groups.toArray();
	const members = await db.members.toArray();

	const keys: unknown[] = [];
	for (const row of await db.groupKeys.toArray()) {
		try {
			keys.push({
				groupId: row.groupId,
				epoch: row.epoch,
				key: toBase64(await vault.unwrap(row.wrappedKey)),
				createdAt: row.createdAt
			});
		} catch {
			// One key the vault cannot open should cost that group, not the whole backup.
		}
	}

	return sealBackup(
		{
			version: VERSION,
			createdAt: Date.now(),
			identity: identity
				? {
						userId: identity.userId,
						displayName: identity.displayName,
						createdAt: identity.createdAt
					}
				: null,
			brokers: brokers.map(brokerJson),
			groups: groups.map(groupJson),
			members: members.map((m) => ({
				groupId: m.groupId,
				userId: m.userId,
				displayName: m.displayName,
				isAdmin: m.isAdmin,
				lastSeenAt: m.lastSeenAt,
				isRemoved: m.isRemoved
			})),
			keys
		},
		passphrase
	);
}

export async function importBackup(
	bytes: Uint8Array,
	passphrase: string
): Promise<{ ok: true; summary: RestoreSummary } | { ok: false; failure: BackupFailure }> {
	const opened = await openBackup(bytes, passphrase);
	if (!opened.ok) return opened;

	const payload = opened.payload as Record<string, unknown>;
	const { vault } = await openVault();
	const summary: RestoreSummary = {
		brokers: 0,
		groups: 0,
		keys: 0,
		members: 0,
		identityRestored: false
	};

	const identity = payload.identity as
		{ userId: string; displayName: string; createdAt: number } | null | undefined;
	if (identity?.userId) {
		// The user id is the whole point of restoring an identity: it is what every message this
		// device has ever sent was signed under, and a new one would make its own history look
		// like a stranger's. The avatar is not in the file, so it is left as it is.
		const current = await db.identity.get(IDENTITY_SINGLETON);
		await db.identity.put({
			id: IDENTITY_SINGLETON,
			userId: identity.userId,
			displayName: identity.displayName,
			avatarBlobId: current?.avatarBlobId ?? null,
			createdAt: identity.createdAt
		});
		summary.identityRestored = true;
	}

	for (const raw of array(payload.brokers)) {
		const broker = raw as Record<string, unknown>;
		await db.brokers.put({
			id: String(broker.id),
			label: String(broker.label ?? ''),
			// A file written by this app carries the WebSocket URL. One written by a phone does
			// not, and cannot — so it is reconstructed from the host, with the conventional
			// WebSocket ports rather than the raw MQTT one the file names. The broker screen is
			// where a wrong guess gets fixed, and a socket that never opens is what a wrong one
			// looks like.
			url: typeof broker.wsUrl === 'string' && broker.wsUrl ? broker.wsUrl : guessUrl(broker),
			username: nullableString(broker.username),
			password: nullableString(broker.password),
			topicPrefix: normalisePrefix(String(broker.topicPrefix ?? '')),
			createdAt: Number(broker.createdAt ?? Date.now())
		});
		summary.brokers++;
	}

	for (const raw of array(payload.groups)) {
		const g = raw as Record<string, unknown>;
		await db.groups.put({
			groupId: String(g.groupId),
			name: String(g.name ?? ''),
			brokerId: String(g.brokerId),
			topicPrefix: normalisePrefix(String(g.topicPrefix ?? '')),
			keyEpoch: Number(g.keyEpoch ?? 0),
			isAdmin: g.isAdmin === true,
			createdAt: Number(g.createdAt ?? Date.now()),
			// The send counter travels. Restoring to zero would make every message this device
			// sends look like a replay of one already applied, and every other member would drop
			// them — silently, and for as long as it took to catch up to where it had been.
			sendCounter: Number(g.sendCounter ?? 0),
			locationEnabled: g.locationEnabled === true,
			locSampleSec: Number(g.locSampleSec ?? 60),
			locPushSec: Number(g.locPushSec ?? 300),
			locMinDistanceM: Number(g.locMinDistanceM ?? 25),
			locRetentionHours: Number(g.locRetentionHours ?? 168),
			shareLocation: g.shareLocation === true
		});
		summary.groups++;
	}

	for (const raw of array(payload.keys)) {
		const k = raw as Record<string, unknown>;
		await db.groupKeys.put({
			groupId: String(k.groupId),
			epoch: Number(k.epoch ?? 0),
			// Re-wrapped under *this* device's master key, which is the only form the rest of the
			// app knows how to read.
			wrappedKey: await vault.wrap(fromBase64(String(k.key))),
			createdAt: Number(k.createdAt ?? Date.now())
		});
		summary.keys++;
	}

	for (const raw of array(payload.members)) {
		const m = raw as Record<string, unknown>;
		const existing = await db.members.get([String(m.groupId), String(m.userId)]);
		await db.members.put({
			groupId: String(m.groupId),
			userId: String(m.userId),
			displayName: String(m.displayName ?? ''),
			// Avatars are not in the file; whatever this device already had is kept.
			avatarBlobId: existing?.avatarBlobId ?? null,
			isAdmin: m.isAdmin === true,
			lastSeenAt: Number(m.lastSeenAt ?? 0),
			isRemoved: m.isRemoved === true,
			// Counters are not carried: they are one device's view of what it has already applied,
			// and starting from zero costs at most re-applying a message that is idempotent
			// anyway. Carrying a stale one would silently drop everything below it.
			lastCounterMsg: existing?.lastCounterMsg ?? 0,
			lastCounterCtl: existing?.lastCounterCtl ?? 0
		});
		summary.members++;
	}

	return { ok: true, summary };
}

function brokerJson(broker: Broker) {
	// Both shapes, so either app can read the file. A phone needs a host and a raw MQTT port; a
	// browser needs the WebSocket URL and cannot derive one from the other with any confidence.
	let host = broker.url;
	let tls = true;
	try {
		const url = new URL(broker.url);
		host = url.hostname;
		tls = url.protocol === 'wss:';
	} catch {
		// A URL that will not parse is one the broker screen already refuses; carry it as-is
		// rather than losing the row.
	}
	return {
		id: broker.id,
		label: broker.label,
		host,
		port: tls ? 8883 : 1883,
		tls,
		allowInsecureTls: false,
		wsUrl: broker.url,
		username: broker.username,
		password: broker.password,
		topicPrefix: broker.topicPrefix,
		createdAt: broker.createdAt
	};
}

function groupJson(group: Group) {
	return {
		groupId: group.groupId,
		name: group.name,
		brokerId: group.brokerId,
		topicPrefix: group.topicPrefix,
		keyEpoch: group.keyEpoch,
		isAdmin: group.isAdmin,
		createdAt: group.createdAt,
		sendCounter: group.sendCounter,
		locationEnabled: group.locationEnabled,
		locSampleSec: group.locSampleSec,
		locPushSec: group.locPushSec,
		locMinDistanceM: group.locMinDistanceM,
		locRetentionHours: group.locRetentionHours,
		shareLocation: group.shareLocation
	};
}

function guessUrl(broker: Record<string, unknown>): string {
	const tls = broker.tls === true;
	return `${tls ? 'wss' : 'ws'}://${String(broker.host)}:${tls ? 8084 : 8083}/mqtt`;
}

function array(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}

function nullableString(value: unknown): string | null {
	return typeof value === 'string' && value.length > 0 ? value : null;
}
