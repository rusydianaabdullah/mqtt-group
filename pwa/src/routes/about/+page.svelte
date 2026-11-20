<script lang="ts">
	import TopBar from '$lib/ui/TopBar.svelte';
	import { connection } from '$lib/stores/connection.svelte';
	import { version } from '$lib/version';
	import Icon from '$lib/ui/Icon.svelte';
	import { appUpdate } from '$lib/ui/appUpdate.svelte';
	import { base } from '$app/paths';

	/**
	 * Reload, next to the version it would change.
	 *
	 * An installed app checks for a new build on its own when it is resumed, but "am I actually
	 * on the newest one" is a question people ask while looking at a version number, and silence
	 * is indistinguishable from a check that never ran. So this asks the server, and reloads
	 * either way — somebody pressing a reload button means reload.
	 *
	 * The order matters: the check has to finish before the page goes away, or the reload lands
	 * back on the same build it started from. When one is waiting, `apply()` swaps the new worker
	 * in and reloads onto it; when there is none, an ordinary reload is what was asked for.
	 */
	let reloading = $state(false);
	let checkResult = $state<string | null>(null);

	async function reloadForUpdate() {
		reloading = true;
		checkResult = null;
		const found = await appUpdate.check(true);
		if (found) {
			await appUpdate.apply();
			return;
		}
		// Said before the reload because the reload wipes it: on the way back the version above
		// is the answer, and this only reports what the check found on the way out.
		checkResult = 'Already the newest version. Reloading…';
		location.reload();
	}

	/**
	 * What this app is, what it cannot do, and what it costs you in privacy.
	 *
	 * Written as prose rather than as a feature list on purpose. The things worth knowing here are
	 * all limits — a tab that stops receiving when it closes, a key that lives in a browser
	 * profile, a code that cannot be revoked — and a bullet list of features would bury every one
	 * of them under the parts that are merely nice.
	 */
	const capabilities = [
		{
			label: 'Notifications',
			ok: typeof Notification !== 'undefined',
			note: 'A message that arrives while this tab is in the background.'
		},
		{
			label: 'Location',
			ok: typeof navigator !== 'undefined' && !!navigator.geolocation,
			note: 'Reported only while this page is open and in front.'
		},
		{
			label: 'Microphone',
			ok: typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia,
			note: 'Voice notes. Needs a secure context — https, or localhost.'
		},
		{
			label: 'Camera',
			ok: typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia,
			note: 'Scanning an invite code.'
		},
		{
			label: 'One tab holds the connection',
			ok: typeof navigator !== 'undefined' && !!navigator.locks,
			note: 'Without it every open tab connects separately, and brokers disconnect each other.'
		}
	];
</script>

<svelte:head><title>About · Family Group</title></svelte:head>

<TopBar title="About" back="/settings" />

<main class="flex-1 px-5 py-6">
	<section class="surface rounded-xl p-4">
		<div class="flex items-center gap-3">
			<img src="{base}/icons/icon.svg" alt="" width="48" height="48" class="rounded-xl" />
			<div>
				<h2 class="text-base">Family Group</h2>
				<p class="muted flex items-center gap-1.5 font-mono text-xs">
					<span>Version {version}</span>
					<!-- Small and right after the number, because it is that number's button: it
					     asks the server whether there is a newer one and reloads onto it. -->
					<button
						class="fg-reload"
						class:is-busy={reloading}
						disabled={reloading}
						aria-label="Reload to get the newest version"
						title="Reload to get the newest version"
						onclick={reloadForUpdate}
					>
						<Icon name="refresh" size={13} />
					</button>
				</p>
			</div>
		</div>
		{#if checkResult}
			<p class="muted mt-2 text-xs">{checkResult}</p>
		{/if}
		<p class="muted mt-3 text-sm leading-relaxed">
			Private group chat and location sharing over MQTT, in a browser tab. There is no server to
			sign in to and no account to create: the app talks to a broker you choose, and everything it
			publishes is encrypted with a key only the group holds.
		</p>
	</section>

	<section class="surface mt-4 rounded-xl p-4">
		<h2 class="mb-2 text-base">What it cannot do</h2>
		<p class="muted text-sm leading-relaxed">
			It only works while it is open. A browser cannot hold a connection or read a position once the
			tab is closed, so messages and locations arrive while this page is on screen and not a moment
			after. Put the tab away and your pin stops moving for everyone else, which looks exactly like
			a device that is off — so this is not the thing to rely on for someone who needs to be found.
			The Android app is the companion that keeps running in a pocket.
		</p>
	</section>

	<section class="surface mt-4 rounded-xl p-4">
		<h2 class="mb-2 text-base">What the broker sees</h2>
		<p class="muted text-sm leading-relaxed">
			Ciphertext, topic names, and when traffic happens. Never a message, a photo, a voice note or a
			coordinate — those are sealed on this device before they are published and opened on the other
			end. Which is also why a broker cannot help you if a key is lost: there is nothing there to
			recover.
		</p>
		<p class="muted mt-3 text-sm leading-relaxed">
			An invite code <em>is</em> the group's key. Anyone who photographs one — off a screen, out of a
			chat app, over your shoulder — can read everything the group says from then on, and there is no
			server to notice or to revoke it.
		</p>
	</section>

	<section class="surface mt-4 rounded-xl p-4">
		<h2 class="mb-2 text-base">Where the keys live</h2>
		<p class="muted text-sm leading-relaxed">
			In this browser, on this device, wrapped by a key the app itself cannot read out. That key is
			protected by your browser profile rather than by secure hardware, and clearing the site's data
			deletes it along with every group it was holding. A backup is the only copy that survives
			that.
		</p>
	</section>

	<section class="surface mt-4 rounded-xl p-4">
		<h2 class="mb-2 text-base">What this browser supports</h2>
		<ul class="mt-2 grid gap-2">
			{#each capabilities as capability (capability.label)}
				<li class="flex items-start gap-2 text-sm">
					<span
						class="mt-0.5 font-semibold"
						style="color: {capability.ok ? 'var(--accent)' : 'var(--error)'}"
						aria-label={capability.ok ? 'Available' : 'Not available'}
					>
						{capability.ok ? '✓' : '×'}
					</span>
					<span class="min-w-0">
						<span class="font-medium">{capability.label}</span>
						<span class="muted block text-xs leading-relaxed">{capability.note}</span>
					</span>
				</li>
			{/each}
		</ul>
		{#if connection.electionUnsupported}
			<p class="mt-3 text-xs leading-relaxed" style="color: var(--error)">
				This browser has no Web Locks, so every open tab connects on its own. Two tabs on one broker
				will disconnect each other in a loop; keep one open at a time here.
			</p>
		{/if}
	</section>

	<section class="surface mt-4 rounded-xl p-4">
		<h2 class="mb-2 text-base">Compatibility</h2>
		<p class="muted text-sm leading-relaxed">
			Wire-compatible with the Android app: the same envelope, the same topics, the same group keys.
			A phone and a browser tab in the same group talk to each other, and a backup written by one
			restores on the other.
		</p>
	</section>
</main>

<style>
	/* A quiet round icon button, sized to the version line rather than to the other buttons on
	   the screen — it belongs to that line, not to the section. */
	.fg-reload {
		display: inline-grid;
		place-items: center;
		width: 1.5rem;
		height: 1.5rem;
		border-radius: 9999px;
		color: var(--on-surface-muted);
		cursor: pointer;
	}

	.fg-reload:hover:not(:disabled) {
		background: var(--surface-2);
		color: var(--on-surface);
	}

	.fg-reload:disabled {
		cursor: progress;
	}

	/* Turning while it asks the server, so a slow network looks like waiting rather than like a
	   button that did nothing. */
	.fg-reload.is-busy :global(svg) {
		animation: fg-spin 900ms linear infinite;
	}

	@keyframes fg-spin {
		to {
			rotate: 360deg;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.fg-reload.is-busy :global(svg) {
			animation: none;
			opacity: 0.5;
		}
	}
</style>
