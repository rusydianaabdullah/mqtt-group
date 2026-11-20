<script lang="ts">
	import TopBar from '$lib/ui/TopBar.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import { identity } from '$lib/stores/identity.svelte';
	import { deleteAllData, setAvatar, setDisplayName } from '$lib/data/repo/identityRepo';
	import { prepareAvatar } from '$lib/ui/images';
	import { connection } from '$lib/stores/connection.svelte';
	import { base } from '$app/paths';
	import {
		askForNotifications,
		notificationsAllowed,
		notificationsAvailable,
		notificationsRefused
	} from '$lib/ui/notify';
	import { forgetPushSubscriptions, pushStatus, syncPushTopics } from '$lib/push/pushTopics';
	import { sendTestPush } from '$lib/push/pushSender';
	import { onMount } from 'svelte';

	let notifyState = $state(
		notificationsAllowed() ? 'granted' : notificationsRefused() ? 'denied' : 'prompt'
	);

	// Already granted from a previous visit: mint (or confirm) a token and make sure every
	// current group has a live topic subscription, the same as the button below does on a fresh
	// grant. Cheap and idempotent, so doing it on every settings visit is fine.
	onMount(() => {
		if (notifyState === 'granted') void refreshPush();
	});

	// Push failing is otherwise completely invisible from a phone — the notification simply
	// never arrives, which looks identical to nobody having sent anything — so the one screen
	// that talks about push reports what it actually knows, including the token's tail. A topic
	// send returns a message id whether or not anyone is subscribed to that topic, so the relay
	// answering 200 proves nothing about this device; aiming a send at the token does.
	let status = $state(pushStatus());
	let retrying = $state(false);

	async function refreshPush(force = false) {
		status = await syncPushTopics(force);
	}

	async function enableNotifications() {
		const granted = await askForNotifications();
		notifyState = granted ? 'granted' : 'denied';
		if (granted) await refreshPush();
	}

	/**
	 * Drops the record of what is already subscribed, mints a genuinely new token, and resubscribes
	 * every group under it.
	 *
	 * It says what it found afterwards. A button that reports nothing is indistinguishable from a
	 * button that did nothing, and for a while this one really was doing nothing — so the token's
	 * new tail and the number of groups it resubscribed are the proof that it ran.
	 */
	async function retryPush() {
		retrying = true;
		refreshResult = null;
		const before = status.token;
		forgetPushSubscriptions();
		await refreshPush(true);
		refreshResult = !status.token
			? (status.tokenError ?? 'No push token could be minted for this browser.')
			: `${status.token === before ? 'Same token' : 'New token'} …${status.tokenTail} · ` +
				`subscribed ${status.subscribedGroups} ` +
				`${status.subscribedGroups === 1 ? 'group' : 'groups'}` +
				(status.subscribeFailures > 0
					? `, ${status.subscribeFailures} refused by the relay (${status.relayError ?? 'unknown error'})`
					: '') +
				'.';
		retrying = false;
	}

	let refreshResult = $state<string | null>(null);

	// The test send, and the one piece of wording on this screen that has to be exact: the relay
	// accepting a token send means FCM checked the token and handed the message to the phone's
	// push service, so if nothing appears after that, the remaining suspects are all on the
	// phone. Saying "sent" would throw that distinction away.
	let testing = $state(false);
	let testResult = $state<string | null>(null);

	async function testPush() {
		if (!status.token) return;
		testing = true;
		testResult = null;
		const accepted = await sendTestPush(status.token);
		status = pushStatus();
		testResult = accepted
			? 'Accepted for delivery. If nothing appears within a few seconds, the phone is ' +
				'holding it back — check this app under Settings → Notifications, and that Low ' +
				'Power Mode and any Focus or scheduled summary are off.'
			: `The relay refused it (${status.relayError ?? 'unknown error'}). This browser's push ` +
				'token is no longer valid — "Refresh push token" mints a fresh one.';
		testing = false;
	}

	let name = $state(identity.current?.displayName ?? '');
	let picker: HTMLInputElement;
	let busy = $state(false);
	let avatarError = $state<string | null>(null);
	let confirmingDelete = $state(false);

	const dirty = $derived(name.trim() !== (identity.current?.displayName ?? '') && !!name.trim());

	async function saveName() {
		busy = true;
		await setDisplayName(name);
		await identity.refresh();
		busy = false;
	}

	async function pickAvatar(event: Event) {
		const file = (event.target as HTMLInputElement).files?.[0];
		if (!file) return;
		busy = true;
		avatarError = null;
		// Shrunk before it is encrypted, because ciphertext never compresses — and this one is
		// published to every member of every group, so it is the one image worth being strict
		// about.
		const bytes = await prepareAvatar(file);
		if (bytes) {
			await setAvatar(bytes);
			await identity.refresh();
		} else {
			avatarError = 'That file could not be read as an image.';
		}
		busy = false;
		picker.value = '';
	}

	async function removeAvatar() {
		busy = true;
		await setAvatar(null);
		await identity.refresh();
		busy = false;
	}

	async function wipe() {
		busy = true;
		await deleteAllData();
		// A full reload rather than re-running onboarding in place: every module-level cache in
		// the app — the vault handles above all — was built around data that no longer exists.
		location.reload();
	}
</script>

<svelte:head><title>Settings · Family Group</title></svelte:head>

<TopBar title="Settings" back="/" />

<main class="flex-1 px-5 py-6">
	<section class="surface rounded-xl p-4">
		<h2 class="mb-4 text-base">You</h2>

		<div class="flex items-center gap-4">
			<Avatar
				url={identity.avatarUrl}
				name={identity.current?.displayName ?? ''}
				userId={identity.current?.userId ?? ''}
				size={64}
			/>
			<div class="flex flex-wrap gap-2">
				<button class="btn btn-quiet" disabled={busy} onclick={() => picker.click()}>
					{identity.avatarUrl ? 'Change photo' : 'Add photo'}
				</button>
				{#if identity.avatarUrl}
					<button class="btn btn-quiet" disabled={busy} onclick={removeAvatar}>Remove</button>
				{/if}
			</div>
			<input bind:this={picker} type="file" accept="image/*" class="hidden" onchange={pickAvatar} />
		</div>
		{#if avatarError}
			<p class="mt-2 text-xs" style="color: var(--error)">{avatarError}</p>
		{/if}

		<label class="mt-5 block">
			<span class="muted mb-1 block text-xs font-semibold uppercase tracking-wide">Name</span>
			<input class="field" bind:value={name} maxlength="40" />
		</label>
		<button class="btn btn-primary mt-3" disabled={!dirty || busy} onclick={saveName}>Save</button>

		<dl class="muted mt-5 grid gap-1 text-xs">
			<div class="flex gap-2">
				<dt class="font-semibold">Your id</dt>
				<dd class="truncate font-mono">{identity.current?.userId}</dd>
			</div>
		</dl>
		<p class="muted mt-2 text-xs leading-relaxed">
			Generated on this device and never chosen by hand — it is what every message you send is
			signed under, so two devices claiming the same one would be indistinguishable to everyone
			else.
		</p>
	</section>

	<section class="surface mt-6 rounded-xl p-4">
		<h2 class="mb-2 text-base">Brokers</h2>
		<p class="muted text-sm leading-relaxed">
			The servers your groups talk through. They carry ciphertext and never hold a key.
		</p>
		<a class="btn btn-quiet mt-3" href="{base}/brokers/">Manage brokers</a>
		{#if connection.electionUnsupported}
			<p class="muted mt-3 text-xs leading-relaxed">
				This browser has no Web Locks, so every open tab holds its own connection. Two tabs on one
				broker will fight over the same client id — keep it to one tab here.
			</p>
		{:else if !connection.isLeader}
			<p class="muted mt-3 text-xs leading-relaxed">
				Another tab is holding the connection. This one shares its database and its outbox, so
				anything you send still goes out — just through that tab.
			</p>
		{/if}
	</section>

	<section class="surface mt-6 rounded-xl p-4">
		<h2 class="mb-2 text-base">Notifications</h2>
		<p class="muted text-sm leading-relaxed">
			The tab title carries the unread count in every browser and costs nothing. Allowing
			notifications adds a real one for messages that arrive while this page is open but you are
			looking elsewhere, and — through fcm.larus.id, the same push relay the Android app uses — one
			for a message that arrives while no tab is open at all. That relay sees only a generic "new
			message" and which group's topic it was for; the message itself keeps travelling only as MQTT
			ciphertext it never touches.
		</p>
		{#if !notificationsAvailable()}
			<p class="muted mt-3 text-xs">This browser has no notification support.</p>
		{:else if notifyState === 'granted'}
			<p class="muted mt-3 text-xs">On. Turn it off from this site's permissions in the browser.</p>
			{#if status.tokenError}
				<p class="mt-2 text-xs text-red-500">{status.tokenError}</p>
				{#if status.serviceWorker}
					<p class="muted mt-1 text-xs">Service worker: {status.serviceWorker}.</p>
				{/if}
			{:else if status.relayError}
				<p class="mt-2 text-xs text-red-500">
					The push relay is not reachable ({status.relayError}), so messages arriving while this
					page is closed will not raise a notification. Everything else works.
				</p>
			{:else if status.token}
				<p class="muted mt-2 text-xs">
					Push token …{status.tokenTail} · subscribed to {status.subscribedGroups}
					{status.subscribedGroups === 1 ? 'group' : 'groups'}{status.subscribeFailures > 0
						? `, ${status.subscribeFailures} the relay refused`
						: ''}. If notifications are not arriving, send a test — it is the only thing that can
					tell this phone apart from the relay.
				</p>
			{/if}
			<!--
				Both buttons are always offered once notifications are on, rather than appearing
				only when something already looks wrong. The failures they address are the ones
				that leave no trace to condition on: a token FCM has quietly retired still reads
				as a healthy token here, and the only way to find out is to mint a new one or
				send to it. Hiding the fix until the app can prove it is needed means hiding it
				in exactly the case it was written for.
			-->
			<div class="mt-2 flex flex-wrap gap-2">
				<button class="btn btn-quiet" disabled={retrying} onclick={retryPush}>
					{retrying ? 'Refreshing…' : 'Refresh push token'}
				</button>
				{#if status.token}
					<button class="btn btn-quiet" disabled={testing} onclick={testPush}>
						{testing ? 'Sending…' : 'Send test notification'}
					</button>
				{/if}
			</div>
			{#if refreshResult}
				<p class="muted mt-2 text-xs">{refreshResult}</p>
			{/if}
			{#if testResult}
				<p class="muted mt-2 text-xs">{testResult}</p>
			{/if}
		{:else if notifyState === 'denied'}
			<p class="muted mt-3 text-xs">
				Refused. That is a site permission, changed from the icon in the address bar.
			</p>
		{:else}
			<button class="btn btn-quiet mt-3" onclick={enableNotifications}>
				Allow notifications
			</button>
		{/if}
	</section>

	<section class="surface mt-6 rounded-xl p-4">
		<h2 class="mb-2 text-base">Backup</h2>
		<p class="muted text-sm leading-relaxed">
			Your identity, brokers, groups and keys, in one encrypted file the Android app can read too.
			Not messages or history.
		</p>
		<a class="btn btn-quiet mt-3" href="{base}/settings/backup/">Save or restore a backup</a>
	</section>

	<section class="surface mt-6 rounded-xl p-4">
		<h2 class="mb-2 text-base">Where your data lives</h2>
		<p class="muted text-sm leading-relaxed">
			Everything is in this browser, on this device. Attachments are encrypted before they are
			stored and the group keys are wrapped by a key this app itself cannot read out. That key is
			protected by your browser profile, not by secure hardware — a phone does better, and the
			Android app is where to keep anything that would matter if this laptop were taken unlocked.
		</p>
	</section>

	<section class="surface mt-6 rounded-xl p-4">
		<h2 class="mb-2 text-base">About</h2>
		<p class="muted text-sm leading-relaxed">
			What this app is, what it deliberately cannot do, and what your broker can see.
		</p>
		<a class="btn btn-quiet mt-3" href="{base}/about/">About Family Group</a>
	</section>

	<section class="mt-6 rounded-xl border p-4" style="border-color: var(--error)">
		<h2 class="mb-2 text-base">Delete all data</h2>
		<p class="muted text-sm leading-relaxed">
			Removes your identity, every group and key, every message and position, and the vault they
			were encrypted under. There is no backup anywhere else, so this cannot be undone.
		</p>
		{#if confirmingDelete}
			<div class="mt-3 flex gap-2">
				<button class="btn btn-danger" disabled={busy} onclick={wipe}>Yes, delete everything</button
				>
				<button class="btn btn-quiet" disabled={busy} onclick={() => (confirmingDelete = false)}>
					Cancel
				</button>
			</div>
		{:else}
			<button class="btn btn-danger mt-3" onclick={() => (confirmingDelete = true)}>
				Delete all data
			</button>
		{/if}
	</section>
</main>
