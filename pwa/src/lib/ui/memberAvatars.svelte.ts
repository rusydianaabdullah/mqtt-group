import { blobObjectUrl } from '$lib/data/blob/blobStore';
import type { Member } from '$lib/data/db/schema';
import { identity } from '$lib/stores/identity.svelte';

/**
 * Every member's avatar as an object URL, keyed by user id.
 *
 * Three screens want the same thing — the member list, the chat, the map — and each one of them
 * decrypting its own copy would mean three object URLs pinning the same bytes in memory, each
 * with its own chance of being leaked. So the loop lives here once: decrypt each blob the first
 * time it is asked for, remember it, and revoke everything when the screen that asked goes away.
 *
 * Our own row is the exception. The identity store already holds a live URL for it — one that is
 * replaced the moment the photo changes — and reading it from there is what keeps the chat's own
 * avatar in step with the settings screen instead of showing the picture it had on first load.
 *
 * Call this during component init; it installs an effect and inherits that component's lifetime.
 */
export function memberAvatars(members: () => Member[]) {
	let urls = $state<Record<string, string>>({});

	$effect(() => {
		const wanted = members().filter((m) => m.avatarBlobId);
		let cancelled = false;
		const created: string[] = [];
		void (async () => {
			for (const member of wanted) {
				if (cancelled || urls[member.userId]) continue;
				const url = await blobObjectUrl(member.avatarBlobId!, 'image/jpeg');
				if (!url) continue;
				if (cancelled) {
					URL.revokeObjectURL(url);
					return;
				}
				created.push(url);
				urls = { ...urls, [member.userId]: url };
			}
		})();
		return () => {
			cancelled = true;
			// Each of these pins a decrypted photo in memory until it is released; a group left
			// open all day would otherwise hold one per member per visit.
			for (const url of created) URL.revokeObjectURL(url);
			urls = {};
		};
	});

	return {
		get urls() {
			return urls;
		},
		/** The photo to draw for one person, ours included, or null for a name-and-colour circle. */
		for(userId: string): string | null {
			if (userId === identity.current?.userId) return identity.avatarUrl;
			return urls[userId] ?? null;
		}
	};
}
