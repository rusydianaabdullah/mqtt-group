import { open, seal } from '$lib/crypto/aead';
import { openVault } from '$lib/crypto/vault';
import { db } from '$lib/data/db/schema';
import { randomUuid, sha256Hex } from '$lib/util/bytes';

/**
 * Attachments: avatars, photos, voice notes. Each blob is stored as `nonce || ciphertext ||
 * tag` under the vault's blob key, so what IndexedDB holds is unreadable without this profile's
 * master key.
 *
 * The Android app writes one file per blob; here they are rows, because a browser has no app
 * directory and IndexedDB is the only storage that survives reliably. The invariant is the same
 * either way: a blob either decrypts and matches its digest, or it does not exist.
 *
 * Blobs are read and written whole — fine for a 1 MB cap, and it keeps the invariant simple.
 */

/** Stores `bytes` and returns the id to reference it by. */
export async function putBlob(bytes: Uint8Array): Promise<string> {
	const blobId = randomUuid();
	await putBlobAs(blobId, bytes);
	return blobId;
}

/**
 * Stores `bytes` under a caller-chosen id. For an attachment received over MQTT the id is not
 * ours to pick — it came from whoever sent it, and has to match so the chat message that
 * references it and the bytes that satisfy it agree.
 */
export async function putBlobAs(blobId: string, bytes: Uint8Array): Promise<void> {
	const { blobKey } = await openVault();
	await db.blobs.put({ blobId, sealed: await seal(blobKey, bytes), createdAt: Date.now() });
}

/** Returns the plaintext, or null if the blob is missing, truncated or not ours. */
export async function getBlob(blobId: string): Promise<Uint8Array | null> {
	const row = await db.blobs.get(blobId);
	if (!row) return null;
	try {
		const { blobKey } = await openVault();
		return await open(blobKey, row.sealed);
	} catch {
		return null;
	}
}

export async function blobExists(blobId: string): Promise<boolean> {
	return (await db.blobs.where('blobId').equals(blobId).count()) > 0;
}

export async function deleteBlob(blobId: string): Promise<void> {
	await db.blobs.delete(blobId);
}

/** Drops every blob. The caller is responsible for the rows that referenced them. */
export async function deleteAllBlobs(): Promise<void> {
	await db.blobs.clear();
}

/** Digest of the *plaintext*, so a receiver can verify what it was handed. */
export const blobSha256 = sha256Hex;

/**
 * An object URL for a blob, for an `<img>` or an `<audio>`. The caller owns the URL and must
 * revoke it: every one of these pins the decrypted bytes in memory until it is released, and a
 * chat scrolled through for a while would otherwise hold every photo it ever rendered.
 */
export async function blobObjectUrl(blobId: string, mime: string): Promise<string | null> {
	const bytes = await getBlob(blobId);
	if (!bytes) return null;
	return URL.createObjectURL(
		new Blob([bytes as BlobPart], { type: mime || 'application/octet-stream' })
	);
}
