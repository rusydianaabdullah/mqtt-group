import { beforeEach, describe, expect, it } from 'vitest';
import {
	deleteAllData,
	ensureIdentity,
	getIdentity,
	setAvatar,
	setDisplayName
} from './identityRepo';
import { getBlob } from '$lib/data/blob/blobStore';
import { db } from '$lib/data/db/schema';
import { closeVault } from '$lib/crypto/vault';
import { utf8 } from '$lib/util/bytes';

beforeEach(async () => {
	await deleteAllData();
	closeVault();
});

describe('identity', () => {
	it('creates exactly one row on first launch and keeps it', async () => {
		const first = await ensureIdentity();
		const again = await ensureIdentity();
		expect(again.userId).toBe(first.userId);
		expect(await db.identity.count()).toBe(1);
	});

	it('generates a user id rather than letting anyone choose one', async () => {
		// Two devices claiming the same id would be indistinguishable to everyone else in the
		// group, since the id is what the envelope header carries.
		const id = (await ensureIdentity()).userId;
		expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
	});

	it('starts with no display name rather than a guess', async () => {
		expect((await ensureIdentity()).displayName).toBe('');
		await setDisplayName('  Ayah  ');
		expect((await getIdentity())!.displayName).toBe('Ayah');
	});

	it('replaces an avatar and drops the blob the old one used', async () => {
		await ensureIdentity();
		await setAvatar(utf8('first'));
		const old = (await getIdentity())!.avatarBlobId!;
		await setAvatar(utf8('second'));
		const now = (await getIdentity())!.avatarBlobId!;
		expect(now).not.toBe(old);
		expect(await getBlob(old)).toBeNull();
		expect(await getBlob(now)).toEqual(utf8('second'));
	});

	it('deletes everything, including the vault the rest was encrypted under', async () => {
		await ensureIdentity();
		await setAvatar(utf8('avatar'));
		await deleteAllData();
		expect(await getIdentity()).toBeUndefined();
		expect(await db.blobs.count()).toBe(0);
		expect(await db.meta.count()).toBe(0);
	});
});
