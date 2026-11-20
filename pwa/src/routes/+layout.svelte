<script lang="ts">
	import '../app.css';
	import UpdatePrompt from '$lib/ui/UpdatePrompt.svelte';
	import CallOverlay from '$lib/ui/CallOverlay.svelte';
	import Onboarding from '$lib/ui/Onboarding.svelte';
	import { identity } from '$lib/stores/identity.svelte';
	import { connection } from '$lib/stores/connection.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db } from '$lib/data/db/schema';
	import { setUnreadBadge } from '$lib/ui/notify';
	import { locationEngine, pruneHistory } from '$lib/location/locationEngine';
	import { listenForForegroundPush, syncPushTopics } from '$lib/push/pushTopics';
	import { notificationsAllowed } from '$lib/ui/notify';
	import { onMount } from 'svelte';

	let { children } = $props();

	// Resyncs topic subscriptions whenever the group list changes — creating, joining, leaving,
	// being kicked, or restoring a backup. A no-op until notifications have been granted; see
	// the Settings screen for where that happens and why nothing here ever prompts for it.
	const groups = live(() => db.groups.toArray(), []);
	$effect(() => {
		groups.value;
		if (notificationsAllowed()) void syncPushTopics();
	});

	// The identity row is created here rather than on a first-run screen, so every route can
	// assume it exists. Naming yourself is a separate question, and the only one asked before
	// the app is usable.
	// The tab title is the notification that works in every browser and costs no permission.
	const unread = live(
		async () =>
			identity.current
				? db.messages.filter((m) => !m.read && m.senderId !== identity.current!.userId).count()
				: 0,
		0
	);
	$effect(() => setUnreadBadge(unread.value));

	onMount(async () => {
		await identity.load();
		// The connection starts even before a name is chosen: campaigning for the leader lock
		// and reconciling an empty broker list both cost nothing, and doing it here means no
		// screen has to remember to.
		await connection.start();
		// Sampling follows the group settings and the per-group share switch; refreshing here
		// covers a reload while sharing was already on.
		await locationEngine.refresh();
		// Retention has no background worker to run it — there is no background. Pruning on open
		// is the honest equivalent: a window measured in hours or days is not meaningfully served
		// by finer granularity than "whenever the app is actually used".
		await pruneHistory();
		void listenForForegroundPush();
	});
</script>

<!--
	Exactly the viewport's height, not "at least" it, and the column itself never scrolls.
	`min-h-dvh` let the page grow past the fold, which put the chat composer below it: sending a
	message pushed the input off screen and you had to scroll down to find it again. Scrolling
	belongs to the one region that owns it — the message list, or a screen's `<main>`, both of
	which get it from the rule in app.css.
-->
<div class="mx-auto flex h-dvh w-full max-w-2xl flex-col overflow-hidden">
	{#if !identity.loaded}
		<!-- Deliberately blank. Opening the database takes a few milliseconds; a spinner that
		     flashes for one frame is worse than nothing at all. -->
	{:else if identity.needsName}
		<Onboarding />
	{:else}
		{@render children()}
	{/if}
</div>

<!-- Outside the column and above everything, because a call outlives the screen that started
     it: navigating during one must not end it, and an offer arriving while someone is reading a
     different group still has to ring. -->
<CallOverlay />

<UpdatePrompt />
