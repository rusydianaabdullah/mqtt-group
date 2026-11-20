/**
 * This device's user id, in a place the service worker can read it.
 *
 * The worker has to answer one question on every push — "did I send this?" — because every
 * member of a group is subscribed to the same topic, the sender included, so FCM fans a chat
 * message out to the phone it was typed on as readily as to anyone else's. The Android app
 * answers it from Room in `PushMessagingService`; here the answer has to survive in something a
 * worker can open on its own, from a cold start, with no page running.
 *
 * Dexie's database is the obvious place and the wrong one: reading it from the worker would
 * couple the worker to the app's schema and its migrations, and the worker is the one piece of
 * this app that can be a version behind the page. So this is a separate one-key database that
 * holds a single opaque string and will never need a migration.
 *
 * Deliberately not the user's name or anything else: a user id is already public within the
 * group — it rides on every message — and the worker has no use for more.
 */
const DB_NAME = 'family-group-sw';
const STORE = 'meta';
const KEY = 'userId';

function open(): Promise<IDBDatabase | null> {
	return new Promise((resolve) => {
		if (typeof indexedDB === 'undefined') return resolve(null);
		let request: IDBOpenDBRequest;
		try {
			request = indexedDB.open(DB_NAME, 1);
		} catch {
			return resolve(null);
		}
		request.onupgradeneeded = () => {
			if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE);
		};
		request.onsuccess = () => resolve(request.result);
		// Private browsing, a blocked storage setting, or a browser that refuses IndexedDB to a
		// worker. The push still shows; it just cannot be attributed, which is the safe way round.
		request.onerror = () => resolve(null);
		request.onblocked = () => resolve(null);
	});
}

/** Called from the page whenever the identity is loaded or changes. */
export async function publishUserIdToWorker(userId: string): Promise<void> {
	const db = await open();
	if (!db) return;
	try {
		await new Promise<void>((resolve) => {
			const tx = db.transaction(STORE, 'readwrite');
			tx.objectStore(STORE).put(userId, KEY);
			tx.oncomplete = () => resolve();
			tx.onerror = () => resolve();
			tx.onabort = () => resolve();
		});
	} finally {
		db.close();
	}
}

/** Called from the service worker. Null when it has never been written or cannot be read. */
export async function readUserIdInWorker(): Promise<string | null> {
	const db = await open();
	if (!db) return null;
	try {
		return await new Promise<string | null>((resolve) => {
			const tx = db.transaction(STORE, 'readonly');
			const request = tx.objectStore(STORE).get(KEY);
			request.onsuccess = () => resolve(typeof request.result === 'string' ? request.result : null);
			request.onerror = () => resolve(null);
		});
	} finally {
		db.close();
	}
}
