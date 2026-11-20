import { beforeEach, describe, expect, it } from 'vitest';
import { blobExists, blobSha256, deleteBlob, getBlob, putBlob, putBlobAs } from './blobStore';
import { db } from '$lib/data/db/schema';
import { closeVault } from '$lib/crypto/vault';
import { utf8 } from '$lib/util/bytes';

beforeEach(async () => {
	await db.blobs.clear();
	await db.meta.clear();
	closeVault();
});

describe('blob store', () => {
	it('round-trips bytes through encryption at rest', async () => {
		const id = await putBlob(utf8('the photo'));
		expect(await getBlob(id)).toEqual(utf8('the photo'));
	});

	it('stores nothing readable', async () => {
		// The point of the whole exercise: what IndexedDB holds is not the attachment.
		const id = await putBlob(utf8('the photo'));
		const row = await db.blobs.get(id);
		expect(new TextDecoder().decode(row!.sealed)).not.toContain('the photo');
	});

	it('accepts a caller-chosen id, because a received attachment names itself', async () => {
		await putBlobAs('b-from-someone-else', utf8('their photo'));
		expect(await getBlob('b-from-someone-else')).toEqual(utf8('their photo'));
		expect(await blobExists('b-from-someone-else')).toBe(true);
	});

	it('returns null for a blob that is missing or not ours', async () => {
		expect(await getBlob('never-existed')).toBeNull();
		await db.blobs.put({ blobId: 'corrupt', sealed: utf8('not ciphertext'), createdAt: 0 });
		expect(await getBlob('corrupt')).toBeNull();
	});

	it('deletes', async () => {
		const id = await putBlob(utf8('x'));
		await deleteBlob(id);
		expect(await getBlob(id)).toBeNull();
	});

	it('digests the plaintext, so a receiver can verify what it was handed', async () => {
		expect(await blobSha256(utf8('abc'))).toBe(
			'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
		);
	});
});
