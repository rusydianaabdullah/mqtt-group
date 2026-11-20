<script lang="ts">
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';
	import { page } from '$app/state';
	import Icon from '$lib/ui/Icon.svelte';
	import TopBar from '$lib/ui/TopBar.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db } from '$lib/data/db/schema';
	import { buildInvite, deleteForEveryone, kickMember, leaveGroup } from '$lib/data/repo/groupRepo';
	import { encodeInvite } from '$lib/crypto/invite';
	import { identity } from '$lib/stores/identity.svelte';
	import { connection } from '$lib/stores/connection.svelte';
	import { relativeTime } from '$lib/ui/relativeTime';
	import { memberAvatars } from '$lib/ui/memberAvatars.svelte';
	import { calls } from '$lib/call/calls.svelte';
	import { isBusy } from '$lib/call/callMachine';

	const groupId = $derived(page.params.groupId!);

	const group = live(() => db.groups.get(groupId), undefined);
	const members = live(() => db.members.where('groupId').equals(groupId).toArray(), []);

	let inviteCode = $state<string | null>(null);
	let showingCode = $state(false);
	let confirming = $state<'leave' | 'delete' | null>(null);
	const avatars = memberAvatars(() => members.value);

	async function showCode() {
		const current = group.value;
		if (!current) return;
		const broker = await db.brokers.get(current.brokerId);
		if (!broker) return;
		const invite = await buildInvite(groupId, broker);
		inviteCode = invite ? encodeInvite(invite) : null;
		showingCode = true;
	}

	async function kick(userId: string) {
		await kickMember(groupId, userId);
		connection.nudge();
	}

	async function leave() {
		await leaveGroup(groupId);
		connection.nudge();
		await goto(`${base}/`);
	}

	async function destroy() {
		await deleteForEveryone(groupId);
		connection.nudge();
		await goto(`${base}/`);
	}
</script>

<svelte:head><title>{group.value?.name ?? 'Group'} · Family Group</title></svelte:head>

<TopBar title={group.value?.name ?? 'Group'} back="/groups/{groupId}" />

<main class="flex-1 px-5 py-6">
	<!-- Two entry points, because they are two different intentions: this buzzes everyone, the
	     per-member button below buzzes one person. Both say "buzz" and neither says "call",
	     because `dial` sends a buzz — there is no voice at either end of these. -->
	<button
		class="btn btn-quiet w-full"
		disabled={!connection.isConnected || isBusy(calls.state)}
		title={connection.isConnected ? 'Buzz everyone in this group' : 'Not connected to the broker'}
		onclick={() => calls.dial(groupId, null)}
	>
		<span class="inline-flex items-center justify-center gap-2"
			><Icon name="buzz" /> Buzz everyone</span
		>
	</button>
	<p class="muted mt-2 mb-4 text-xs leading-relaxed">
		Rings every phone in this group, even on silent. No voice, no call.
	</p>

	<section class="surface rounded-xl p-4">
		<h2 class="mb-3 text-base">
			{members.value.filter((m) => !m.isRemoved).length} member{members.value.filter(
				(m) => !m.isRemoved
			).length === 1
				? ''
				: 's'}
		</h2>
		<ul class="grid gap-3">
			{#each members.value.filter((m) => !m.isRemoved) as member (member.userId)}
				{@const isSelf = member.userId === identity.current?.userId}
				<li class="flex items-center gap-3">
					<Avatar
						url={avatars.for(member.userId)}
						name={member.displayName}
						userId={member.userId}
						size={36}
					/>
					<div class="min-w-0 flex-1">
						<p class="truncate text-sm font-semibold">
							{member.displayName || 'Waiting for their name'}
							{#if isSelf}<span class="muted font-normal">· you</span>{/if}
							{#if member.isAdmin}<span class="muted font-normal">· admin</span>{/if}
						</p>
						{#if member.lastSeenAt > 0}
							<p class="muted text-xs">Last heard from {relativeTime(member.lastSeenAt)}</p>
						{:else}
							<p class="muted text-xs">Has not said anything yet</p>
						{/if}
					</div>
					{#if !isSelf}
						<!-- Disabled rather than hidden while there is no connection: an offer lives
						     45 seconds, so sending one with nothing to publish it through is a buzz
						     that was never going to happen, and a button that vanishes is harder to
						     understand than one that is greyed out. -->
						<button
							class="btn btn-quiet !px-3 !py-1"
							disabled={!connection.isConnected || isBusy(calls.state)}
							title={connection.isConnected ? 'Buzz their phone' : 'Not connected to the broker'}
							onclick={() => calls.dial(groupId, member.userId)}
						>
							<span class="inline-flex items-center gap-1.5"
								><Icon name="buzz" size={15} /> Buzz</span
							>
						</button>
					{/if}
					{#if group.value?.isAdmin && !isSelf}
						<button class="btn btn-quiet !px-3 !py-1" onclick={() => kick(member.userId)}>
							Remove
						</button>
					{/if}
				</li>
			{/each}
		</ul>

		{#if group.value?.isAdmin}
			<p class="muted mt-4 text-xs leading-relaxed">
				Removing someone tells every other device to drop them from the list, and honest ones will.
				It does not take the key back — they still hold it, and could still read the group with a
				modified app. Changing the key is the only real removal, and it is not built yet.
			</p>
		{/if}
	</section>

	<section class="surface mt-5 rounded-xl p-4">
		<h2 class="mb-1 text-base">Invite someone</h2>
		<p class="muted text-sm leading-relaxed">
			The code below is the group's key. Show it in person; anyone who photographs it can read
			everything from then on.
		</p>
		{#if showingCode}
			{#if inviteCode}
				<div class="mt-4 flex flex-col items-center gap-3">
					<div class="rounded-xl bg-white p-3">
						<QrCode value={inviteCode} />
					</div>
					<button class="btn btn-quiet" onclick={() => navigator.clipboard?.writeText(inviteCode!)}>
						Copy the code
					</button>
					<button class="btn btn-quiet" onclick={() => (showingCode = false)}>Hide</button>
				</div>
			{:else}
				<p class="mt-3 text-sm" style="color: var(--error)">
					This group's key could not be read, so no code can be made.
				</p>
			{/if}
		{:else}
			<button class="btn btn-primary mt-3" onclick={showCode}>Show the code</button>
		{/if}
	</section>

	<section class="mt-5 rounded-xl border p-4" style="border-color: var(--error)">
		<h2 class="mb-2 text-base">Leave or delete</h2>
		{#if confirming === 'leave'}
			<p class="muted text-sm leading-relaxed">
				You will lose this group's key and every message in it on this device. Nobody else is
				affected.
			</p>
			<div class="mt-3 flex gap-2">
				<button class="btn btn-danger" onclick={leave}>Yes, leave</button>
				<button class="btn btn-quiet" onclick={() => (confirming = null)}>Cancel</button>
			</div>
		{:else if confirming === 'delete'}
			<p class="muted text-sm leading-relaxed">
				Every device that is listening will drop the group and its key. Anyone offline keeps theirs
				until they reconnect, and someone running a modified app keeps theirs entirely.
			</p>
			<div class="mt-3 flex gap-2">
				<button class="btn btn-danger" onclick={destroy}>Yes, delete for everyone</button>
				<button class="btn btn-quiet" onclick={() => (confirming = null)}>Cancel</button>
			</div>
		{:else}
			<div class="flex flex-wrap gap-2">
				<button class="btn btn-danger" onclick={() => (confirming = 'leave')}>Leave group</button>
				{#if group.value?.isAdmin}
					<button class="btn btn-danger" onclick={() => (confirming = 'delete')}>
						Delete for everyone
					</button>
				{/if}
			</div>
		{/if}
	</section>
</main>
