<script lang="ts">
	import { base } from '$app/paths';
	import TopBar from '$lib/ui/TopBar.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import ConnectionBadge from '$lib/ui/ConnectionBadge.svelte';
	import { identity } from '$lib/stores/identity.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db } from '$lib/data/db/schema';
	import { unreadCounts } from '$lib/data/repo/groupRepo';
	import { relativeTime } from '$lib/ui/relativeTime';
	import { version } from '$lib/version';

	const groups = live(() => db.groups.orderBy('createdAt').toArray(), []);
	const brokerCount = live(() => db.brokers.count(), 0);

	// The last message per group, for the subtitle. Read through the same live query as the list
	// so a new arrival moves both at once rather than in two frames.
	const previews = live<Record<string, { body: string; at: number }>>(async () => {
		const rows = await db.messages.orderBy('createdAt').reverse().toArray();
		const latest: Record<string, { body: string; at: number }> = {};
		for (const m of rows) {
			if (latest[m.groupId]) continue;
			latest[m.groupId] = {
				body: m.type === 'text' ? (m.body ?? '') : m.type === 'audio' ? 'Voice message' : 'Photo',
				at: m.createdAt
			};
		}
		return latest;
	}, {});

	const unread = live<Record<string, number>>(
		async () => (identity.current ? unreadCounts(identity.current.userId) : {}),
		{}
	);
</script>

<svelte:head><title>Family Group</title></svelte:head>

<TopBar title="Groups">
	<ConnectionBadge />
	<a href="{base}/settings/" aria-label="Settings" class="btn btn-quiet !px-2 !py-1">⚙</a>
</TopBar>

<main class="flex-1 px-5 py-5">
	{#if groups.value.length === 0}
		<div class="surface mt-2 rounded-xl p-5">
			<h2 class="mb-2 text-base">No groups yet</h2>
			<p class="muted text-sm leading-relaxed">
				A group is a shared key and a topic on a broker. Create one and show its code to someone, or
				scan a code from a phone that already has one.
			</p>
			<!-- Both, always, the way the Android app offers them. Hiding them behind "add a broker
			     first" made the app look like it had nothing to do on a first run, and it was not even
			     true of joining: a code carries its own broker. Creating a group asks for the broker
			     on its own screen, where the answer is needed. -->
			<div class="mt-4 flex flex-wrap gap-2">
				<a class="btn btn-primary" href="{base}/groups/new/">Create a group</a>
				<a class="btn btn-quiet" href="{base}/groups/join/">Join with a code</a>
			</div>
			{#if brokerCount.value === 0}
				<p class="muted mt-4 text-sm leading-relaxed">
					Neither works without a broker — the one piece of infrastructure this app cannot do
					without. Creating a group will ask for one, and a join code brings its own.
				</p>
			{/if}
		</div>
	{:else}
		<ul class="grid gap-2">
			{#each groups.value as group (group.groupId)}
				{@const preview = previews.value[group.groupId]}
				{@const count = unread.value[group.groupId] ?? 0}
				<li>
					<a
						href="{base}/groups/{group.groupId}/"
						class="surface flex items-center gap-3 rounded-xl p-3.5"
					>
						<Avatar name={group.name} userId={group.groupId} size={44} />
						<div class="min-w-0 flex-1">
							<div class="flex items-baseline gap-2">
								<p class="truncate font-semibold">{group.name}</p>
								{#if preview}
									<span class="muted ml-auto shrink-0 text-xs">{relativeTime(preview.at)}</span>
								{/if}
							</div>
							<p class="muted truncate text-sm">
								{preview?.body || 'No messages yet'}
							</p>
						</div>
						{#if count > 0}
							<span
								class="grid size-6 shrink-0 place-items-center rounded-full text-xs font-bold"
								style="background: var(--accent); color: var(--on-accent)">{count}</span
							>
						{/if}
					</a>
				</li>
			{/each}
		</ul>

		<div class="mt-5 flex flex-wrap gap-2">
			<a class="btn btn-primary" href="{base}/groups/new/">Create a group</a>
			<a class="btn btn-quiet" href="{base}/groups/join/">Join with a code</a>
		</div>
	{/if}

	<div class="surface mt-6 flex items-center gap-3 rounded-xl p-3.5">
		<Avatar
			url={identity.avatarUrl}
			name={identity.current?.displayName ?? ''}
			userId={identity.current?.userId ?? ''}
			size={36}
		/>
		<div class="min-w-0 flex-1">
			<p class="truncate text-sm font-semibold">{identity.current?.displayName}</p>
			<p class="muted truncate text-xs">Messages arrive only while this page is open.</p>
		</div>
		<a class="btn btn-quiet !px-3 !py-1.5" href="{base}/settings/">Edit</a>
	</div>
	<p class="muted mt-3 text-center text-xs">v{version}</p>
</main>
