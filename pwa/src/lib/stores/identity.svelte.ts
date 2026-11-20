import { ensureIdentity, getIdentity } from '$lib/data/repo/identityRepo';
import { blobObjectUrl } from '$lib/data/blob/blobStore';
import { publishUserIdToWorker } from '$lib/push/swIdentity';
import type { Identity } from '$lib/data/db/schema';

/**
 * This device's identity, as a reactive value the screens can read.
 *
 * A single module-level rune rather than a store per component: there is exactly one identity,
 * it changes rarely, and every screen that shows a name or an avatar wants the same one.
 *
 * The avatar is an object URL, and it is revoked whenever it is replaced. Every one of these
 * pins the decrypted image in memory until it is released; leaking them would mean an app left
 * open all day holding every version of an avatar it ever rendered.
 */
class IdentityState {
	current = $state<Identity | null>(null);
	avatarUrl = $state<string | null>(null);
	loaded = $state(false);

	async load(): Promise<void> {
		this.current = await ensureIdentity();
		await this.refreshAvatar();
		await this.#tellWorker();
		this.loaded = true;
	}

	async refresh(): Promise<void> {
		this.current = (await getIdentity()) ?? null;
		await this.refreshAvatar();
		await this.#tellWorker();
	}

	/**
	 * The service worker needs the user id to recognise this device's own sends — every member of
	 * a group is subscribed to the same push topic, sender included — and it cannot read Dexie's
	 * database without being coupled to its schema. See push/swIdentity.ts.
	 */
	async #tellWorker(): Promise<void> {
		const userId = this.current?.userId;
		if (userId) await publishUserIdToWorker(userId).catch(() => {});
	}

	private async refreshAvatar(): Promise<void> {
		const previous = this.avatarUrl;
		const blobId = this.current?.avatarBlobId;
		this.avatarUrl = blobId ? await blobObjectUrl(blobId, 'image/jpeg') : null;
		if (previous) URL.revokeObjectURL(previous);
	}

	/** True until the person has told us what to call them. The one thing onboarding asks for. */
	get needsName(): boolean {
		return this.loaded && !this.current?.displayName;
	}
}

export const identity = new IdentityState();
