<script lang="ts">
	import { identity } from '$lib/stores/identity.svelte';
	import { setDisplayName } from '$lib/data/repo/identityRepo';

	/**
	 * The only thing asked before the app is usable: what to call you.
	 *
	 * Not an account, not a password, not an email — there is nowhere to send one. The user id
	 * was already generated when the database opened; this names it, and the name is what
	 * travels to the group on `/ctl` once there is a group to travel to.
	 */
	let name = $state('');
	let saving = $state(false);

	async function save(event: SubmitEvent) {
		event.preventDefault();
		if (!name.trim() || saving) return;
		saving = true;
		await setDisplayName(name);
		await identity.refresh();
	}
</script>

<main class="flex flex-1 flex-col justify-center px-6 py-12">
	<img src="./icons/icon.svg" alt="" width="64" height="64" class="mb-6 rounded-2xl" />
	<h1 class="text-2xl">What should the group call you?</h1>
	<p class="muted mt-2 text-sm leading-relaxed">
		This is the only thing this app asks for. There is no account, no email and no password —
		nothing to sign in to, because there is no server to sign in to. Your name travels to the groups
		you join, encrypted, and nowhere else.
	</p>

	<form class="mt-6 flex gap-2" onsubmit={save}>
		<!-- svelte-ignore a11y_autofocus -->
		<input
			class="field"
			bind:value={name}
			placeholder="Your name"
			maxlength="40"
			autofocus
			aria-label="Your name"
		/>
		<button class="btn btn-primary" disabled={!name.trim() || saving}>Continue</button>
	</form>

	<p class="muted mt-8 text-xs leading-relaxed">
		A note this app would rather say up front than bury: in a browser, messages and locations only
		arrive while this page is open, and what is stored here is protected by your browser profile
		rather than by a phone's secure hardware. The Android app is the one for sharing that keeps
		running.
	</p>
</main>
