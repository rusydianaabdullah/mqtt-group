import { db } from '$lib/data/db/schema';
import { KeyVault } from './keyVault';
import { importKey, randomKeyBytes } from './aead';

/**
 * Bootstrapping the vault, and the one place that decides what "first launch" means.
 *
 * Two keys are created on first run and never change: the master key, which is non-extractable
 * and lives in IndexedDB as a `CryptoKey` object, and the blob key, which is 32 random bytes
 * wrapped by it. The split mirrors the phone — there the master key is in hardware and the blob
 * key is sealed under it in preferences — and it exists so that either can be rotated without
 * touching the other.
 *
 * IndexedDB can store a `CryptoKey` directly, structured-clone and all, which is what makes a
 * non-extractable master key possible at all: there is no other way to persist a key whose
 * material script may never see.
 *
 * Nothing here runs inside a Dexie transaction, and that is not an oversight. An IndexedDB
 * transaction commits as soon as the microtask queue drains with no request outstanding, and
 * every call in this file awaits WebCrypto in between its reads and its writes — which is
 * exactly the gap that closes the transaction underneath it. So each step is its own atomic
 * operation and the races are handled by re-reading instead.
 */

const MASTER = 'vault.master';
const BLOB = 'vault.blobKey';

interface Vault {
	vault: KeyVault;
	blobKey: CryptoKey;
}

let cached: Vault | null = null;
let opening: Promise<Vault> | null = null;

export async function openVault(): Promise<Vault> {
	if (cached) return cached;
	// One in-flight open per tab. Without this, two screens mounting at once on a first run
	// would each generate a master key and the loser's wrapped blob key would decrypt under
	// nothing.
	opening ??= withLock(async () => {
		const master = await loadOrCreateMaster();
		const vault = new KeyVault(master);
		const blobKey = await importKey(await vault.unwrap(await loadOrCreateBlobKey(vault)));
		cached = { vault, blobKey };
		return cached;
	}).finally(() => (opening = null));
	return opening;
}

async function loadOrCreateMaster(): Promise<CryptoKey> {
	const existing = await db.meta.get(MASTER);
	if (existing) {
		// A browser that refuses to store a CryptoKey would silently hand back something that is
		// not one, and the first wrap would fail somewhere far less obvious than here.
		if (!(existing.value instanceof CryptoKey)) {
			throw new Error('the stored master key is not a CryptoKey — this browser cannot hold one');
		}
		return existing.value;
	}
	const created = await KeyVault.generateMaster();
	await db.meta.add({ key: MASTER, value: created }).catch(() => undefined);
	// Read back rather than trusting the write: if another context won the race, its key is the
	// one everything else was wrapped under and ours must be discarded.
	const stored = (await db.meta.get(MASTER))?.value;
	if (!(stored instanceof CryptoKey)) {
		throw new Error('this browser cannot store a CryptoKey — the vault has nowhere to live');
	}
	return stored;
}

async function loadOrCreateBlobKey(vault: KeyVault): Promise<Uint8Array> {
	const existing = await db.meta.get(BLOB);
	if (existing) return existing.value as Uint8Array;
	const wrapped = await vault.wrap(randomKeyBytes());
	await db.meta.add({ key: BLOB, value: wrapped }).catch(() => undefined);
	return ((await db.meta.get(BLOB))?.value as Uint8Array) ?? wrapped;
}

/**
 * Serialises the first run across tabs where the browser offers a way to. Web Locks is not in
 * Node and not in every browser, so its absence falls through to the single-tab guard above —
 * which covers the case that actually happens, since two tabs racing a first launch means two
 * tabs opened before either finished loading.
 */
function withLock<T>(work: () => Promise<T>): Promise<T> {
	const locks = (globalThis as { navigator?: { locks?: LockManager } }).navigator?.locks;
	if (!locks) return work();
	return locks.request('family-group-vault', work) as Promise<T>;
}

/**
 * Forgets the in-memory handles. Called by "delete all data" after the tables are cleared, so
 * the next call to `openVault` generates a fresh pair rather than re-wrapping under a master
 * key whose ciphertext no longer exists anywhere.
 */
export function closeVault(): void {
	cached = null;
	opening = null;
}
