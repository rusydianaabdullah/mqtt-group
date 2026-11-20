import Dexie, { type EntityTable } from 'dexie';

/**
 * The whole schema, in one file because the tables only make sense together — the Android
 * app's `Entities.kt` transposed to IndexedDB, with the same table names and the same columns.
 *
 * What is *not* the same: there is no SQLCipher here, so this database is not encrypted as a
 * whole. Anything that would matter in the clear is encrypted individually instead — group
 * keys are wrapped by the `KeyVault`, attachment blobs are sealed before they are stored, and
 * the broker password is the one remaining plaintext secret, which is stated in the UI rather
 * than papered over. Encrypting every column with a key that has to live in the same database
 * would look like protection without being any.
 *
 * Timestamps are epoch milliseconds. Ids that travel between devices are UUID strings, so a
 * group id can go into an MQTT topic without escaping.
 */

/** This device's user. Exactly one row, id 0, created on first launch. */
export interface Identity {
	id: number;
	userId: string;
	displayName: string;
	avatarBlobId: string | null;
	createdAt: number;
}

export const IDENTITY_SINGLETON = 0;

/**
 * An MQTT broker, as a browser can reach one.
 *
 * Where Android stores host/port/tls separately, this stores a single `url`, because in a
 * browser it is always a WebSocket URL and splitting it apart only invites reassembling an
 * invalid one. `allowInsecureTls` has no equivalent and is deliberately absent: the browser
 * decides what certificates it trusts and will not be argued with.
 */
export interface Broker {
	id: string;
	label: string;
	url: string;
	username: string | null;
	password: string | null;
	topicPrefix: string;
	createdAt: number;
}

export interface Group {
	groupId: string;
	name: string;
	brokerId: string;
	topicPrefix: string;
	/** Which `GroupKey` generation this group currently publishes with. */
	keyEpoch: number;
	isAdmin: boolean;
	createdAt: number;
	/** Our own publish counter, so a reload never reuses one. */
	sendCounter: number;

	// --- Location ----------------------------------------------------------------------------
	// The five `loc*` fields are the *group's* settings, set by the admin and carried to every
	// other device by `Control.LocationSettings`. They describe how often to look and how often
	// to speak, not whether this device participates at all — that is `shareLocation`, which is
	// the one location field that never travels.
	locationEnabled: boolean;
	locSampleSec: number;
	locPushSec: number;
	locMinDistanceM: number;
	/** Hours. 0 means forever. Enforced per device. */
	locRetentionHours: number;
	/** Whether *this device* shares its own position. Local, off until touched, never published. */
	shareLocation: boolean;
}

/** A group key, sealed by the `KeyVault`. Per epoch, so rotation adds a row rather than
 * destroying the ability to read what came before it. */
export interface GroupKey {
	groupId: string;
	epoch: number;
	wrappedKey: Uint8Array;
	createdAt: number;
}

export interface Member {
	groupId: string;
	userId: string;
	displayName: string;
	avatarBlobId: string | null;
	isAdmin: boolean;
	lastSeenAt: number;
	/** Kicked or left. Kept as a tombstone so their old messages still show a name. */
	isRemoved: boolean;
	/**
	 * Highest counter seen from them, per topic kind rather than as one shared value. MQTT
	 * guarantees order within a topic and not across topics, so a photo's bytes on `/att` can
	 * legitimately arrive after its reference on `/msg` already advanced the counter — under one
	 * shared high-water mark the bytes would look like a replay and be dropped forever.
	 */
	lastCounterMsg: number;
	lastCounterCtl: number;
}

export interface Message {
	id: string;
	groupId: string;
	senderId: string;
	/** "text", "image", "audio", "file". */
	type: string;
	body: string | null;
	blobId: string | null;
	createdAt: number;
	/** PENDING, SENT, FAILED, RECEIVED. */
	state: string;
	counter: number;
	/** Whether the person using this device has opened the chat since this arrived. */
	read: boolean;
	/** The message this one replies to, by id. Not a foreign key — the original may belong to an
	 * epoch this device never had a key for, and a reply should still show up regardless. */
	replyToId: string | null;
}

/** One row per (message, other member) once that member has told us they got it or read it. */
export interface MessageReceipt {
	messageId: string;
	groupId: string;
	userId: string;
	/** DELIVERED or READ. READ implies DELIVERED; a late delivery receipt never downgrades one. */
	state: string;
	at: number;
}

export interface Attachment {
	blobId: string;
	groupId: string | null;
	mime: string;
	sizeBytes: number;
	sha256: string;
	createdAt: number;
}

export interface LocationFix {
	/** Auto-incremented. Required on the row, omitted when inserting — see `db.locations.add`. */
	id: number;
	groupId: string;
	userId: string;
	lat: number;
	lon: number;
	accuracy: number;
	speed: number;
	bearing: number;
	capturedAt: number;
}

/**
 * Outbound MQTT, durable. Nothing publishes without a row here first, so "did this leave the
 * device" has exactly one answer even across a reload or a week offline.
 */
export interface Outbox {
	/** Auto-incremented. Required on the row, omitted when inserting. */
	id: number;
	groupId: string;
	topic: string;
	payload: Uint8Array;
	qos: number;
	retained: boolean;
	createdAt: number;
	retryCount: number;
	/** QUEUED, SENDING, FAILED. Rows disappear on success rather than becoming SENT. */
	status: string;
	nextAttemptAt: number;
	/** The chat message this row is the wire copy of, if it is one. */
	refMessageId: string | null;
	/**
	 * When this row stops being worth publishing, or null for the rows that never stop — which
	 * is almost all of them.
	 *
	 * The outbox exists so that a message written offline still arrives an hour later, and for
	 * chat that is exactly right. Call signalling is the one thing here where surviving a dead
	 * network is the *wrong* behaviour: a ring delivered twenty minutes late rings a device about
	 * a call that is long over, and an accept delivered after the caller hung up connects
	 * nothing. Those rows carry a deadline and `due()` stops returning them.
	 *
	 * Not indexed, so no Dexie version bump: it is read by a filter over rows already narrowed
	 * by status, never queried on its own.
	 */
	expiresAt: number | null;
}

/** Encrypted attachment bytes, sealed by the `BlobStore` before they are put here. */
export interface Blob_ {
	blobId: string;
	sealed: Uint8Array;
	createdAt: number;
}

/** Single-row odds and ends that are not worth a table: the wrapped master key, the schema's
 * own bookkeeping. Values are opaque to everything but their owner. */
export interface Meta {
	key: string;
	value: unknown;
}

export class AppDatabase extends Dexie {
	identity!: EntityTable<Identity, 'id'>;
	brokers!: EntityTable<Broker, 'id'>;
	groups!: EntityTable<Group, 'groupId'>;
	groupKeys!: EntityTable<GroupKey>;
	members!: EntityTable<Member>;
	messages!: EntityTable<Message, 'id'>;
	receipts!: EntityTable<MessageReceipt>;
	attachments!: EntityTable<Attachment, 'blobId'>;
	locations!: EntityTable<LocationFix, 'id'>;
	outbox!: EntityTable<Outbox, 'id'>;
	blobs!: EntityTable<Blob_, 'blobId'>;
	meta!: EntityTable<Meta, 'key'>;

	constructor(name = 'family-group') {
		super(name);
		// Indexes are the ones the screens actually query by: a chat reads messages for one
		// group in time order, the map reads one person's fixes in time order, and the pump
		// reads the outbox by what is due next.
		this.version(1).stores({
			identity: 'id',
			brokers: 'id, createdAt',
			groups: 'groupId, brokerId, createdAt',
			groupKeys: '[groupId+epoch], groupId',
			members: '[groupId+userId], groupId',
			messages: 'id, [groupId+createdAt], groupId, blobId, [groupId+read]',
			receipts: '[messageId+userId], messageId, groupId',
			attachments: 'blobId, groupId',
			locations: '++id, [groupId+userId+capturedAt], [groupId+capturedAt], groupId',
			outbox: '++id, [status+nextAttemptAt], groupId, topic, refMessageId',
			blobs: 'blobId',
			meta: 'key'
		});
	}
}

export const db = new AppDatabase();
