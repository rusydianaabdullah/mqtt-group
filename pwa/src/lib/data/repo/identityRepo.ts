import { db, IDENTITY_SINGLETON, type Identity } from '$lib/data/db/schema';
import { deleteAllBlobs, putBlob, deleteBlob } from '$lib/data/blob/blobStore';
import { closeVault } from '$lib/crypto/vault';
import { randomUuid } from '$lib/util/bytes';

/**
 * This device's own identity: one row, created the first time the app opens.
 *
 * The user id is a random UUID and is never chosen by the person — it is what the envelope
 * header carries, so two devices claiming the same one would be indistinguishable to everyone
 * else in the group. The display name is the part they pick, and it travels separately on
 * `/ctl` as a profile announcement.
 */

export async function ensureIdentity(): Promise<Identity> {
	return db.transaction('rw', db.identity, async () => {
		const existing = await db.identity.get(IDENTITY_SINGLETON);
		if (existing) return existing;
		const created: Identity = {
			id: IDENTITY_SINGLETON,
			userId: randomUuid(),
			// Empty rather than a guess. "Waiting for their name…" on someone else's screen is
			// honest; "Android" or "User" pretends they answered a question nobody asked.
			displayName: '',
			avatarBlobId: null,
			createdAt: Date.now()
		};
		await db.identity.put(created);
		return created;
	});
}

export async function getIdentity(): Promise<Identity | undefined> {
	return db.identity.get(IDENTITY_SINGLETON);
}

export async function setDisplayName(displayName: string): Promise<void> {
	await db.identity.update(IDENTITY_SINGLETON, { displayName: displayName.trim() });
}

/** Replaces the avatar, dropping the old blob. Pass null to clear it. */
export async function setAvatar(bytes: Uint8Array | null): Promise<void> {
	const current = await getIdentity();
	const blobId = bytes ? await putBlob(bytes) : null;
	await db.identity.update(IDENTITY_SINGLETON, { avatarBlobId: blobId });
	if (current?.avatarBlobId) await deleteBlob(current.avatarBlobId);
}

/**
 * Everything, gone: identity, brokers, groups, keys, messages, positions, blobs and the vault.
 *
 * The vault handles are dropped last and deliberately — leaving them cached would mean the next
 * write is wrapped under a master key whose only record was in the table just cleared, which
 * would look like it worked until the page was reloaded.
 */
export async function deleteAllData(): Promise<void> {
	// Every table, cleared together. Not one transaction: `clear` on a dozen stores in one
	// atomic step buys nothing here — there is no half-deleted state worth protecting, and the
	// caller reloads the page immediately afterwards.
	await Promise.all([
		db.identity.clear(),
		db.brokers.clear(),
		db.groups.clear(),
		db.groupKeys.clear(),
		db.members.clear(),
		db.messages.clear(),
		db.receipts.clear(),
		db.attachments.clear(),
		db.locations.clear(),
		db.outbox.clear(),
		db.meta.clear(),
		deleteAllBlobs()
	]);
	closeVault();
}
