<script lang="ts">
	import { base } from '$app/paths';
	import { page } from '$app/state';
	import TopBar from '$lib/ui/TopBar.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import ConnectionBadge from '$lib/ui/ConnectionBadge.svelte';
	import MessageBubble from '$lib/ui/MessageBubble.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db, type Message } from '$lib/data/db/schema';
	import { markRead, sendAttachment, sendText } from '$lib/data/repo/groupRepo';
	import { identity } from '$lib/stores/identity.svelte';
	import { connection } from '$lib/stores/connection.svelte';
	import { downscale } from '$lib/ui/images';
	import { canRecord, VoiceRecorder } from '$lib/ui/audio.svelte';
	import { dayLabel } from '$lib/ui/relativeTime';
	import { retryRow } from '$lib/data/repo/outboxRepo';
	import { notifyGroup } from '$lib/push/pushSender';
	import { memberAvatars } from '$lib/ui/memberAvatars.svelte';
	import { calls } from '$lib/call/calls.svelte';
	import { isBusy } from '$lib/call/callMachine';
	import type { Member } from '$lib/data/db/schema';
	import { onMount, tick } from 'svelte';

	const groupId = $derived(page.params.groupId!);

	const group = live(() => db.groups.get(groupId), undefined);
	const messages = live<Message[]>(
		() =>
			db.messages.where('[groupId+createdAt]').between([groupId, 0], [groupId, Infinity]).toArray(),
		[]
	);
	const members = live(() => db.members.where('groupId').equals(groupId).toArray(), []);
	const receipts = live(() => db.receipts.where('groupId').equals(groupId).toArray(), []);
	const failedRows = live(
		() =>
			db.outbox
				.where('groupId')
				.equals(groupId)
				.filter((r) => r.status === 'FAILED')
				.toArray(),
		[]
	);

	const avatars = memberAvatars(() => members.value);

	/** Whose card is open, if any. Tapping a face in the thread is the shortest path from
	 * "who said this" to doing something about it. */
	let showing = $state<Member | null>(null);

	let draft = $state('');
	let replyTo = $state<Message | null>(null);
	let scroller = $state<HTMLDivElement | null>(null);
	let picker = $state<HTMLInputElement | null>(null);
	let sending = $state(false);
	let notice = $state<string | null>(null);
	const recorder = new VoiceRecorder();
	let recordingFor = $state(0);
	let recordTimer: ReturnType<typeof setInterval> | null = null;

	const nameFor = $derived((userId: string) =>
		userId === identity.current?.userId
			? 'You'
			: (members.value.find((m) => m.userId === userId)?.displayName ?? 'Someone')
	);

	// The chat being on screen is what makes a read receipt honest — it is the one receipt that
	// reports a person actually looking, so it fires from an open and from nothing else.
	onMount(() => {
		connection.sync.activeGroupId = groupId;
		return () => {
			connection.sync.activeGroupId = null;
			if (recordTimer) clearInterval(recordTimer);
			recorder.cancel();
		};
	});

	$effect(() => {
		if (messages.value.length && identity.current) {
			void markRead(groupId, identity.current.userId).then(() => connection.nudge());
		}
	});

	/**
	 * Stick to the bottom on a new message, but only when the reader is already there — yanking
	 * someone back down while they are reading yesterday is worse than a missed scroll.
	 *
	 * The first pass is the exception: opening a chat should land on the newest message, and at
	 * that point there is no reading position to protect.
	 */
	let landed = false;
	$effect(() => {
		messages.value.length;
		void tick().then(() => {
			if (!scroller) return;
			const nearBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 200;
			if (!landed || nearBottom) scroller.scrollTop = scroller.scrollHeight;
			if (messages.value.length) landed = true;
		});
	});

	async function send() {
		const body = draft.trim();
		if (!body || sending) return;
		sending = true;
		draft = '';
		const reply = replyTo?.id ?? null;
		replyTo = null;
		await sendText(groupId, body, reply);
		connection.nudge();
		if (identity.current) notifyGroup(groupId, identity.current.userId);
		sending = false;
	}

	async function pickImage(event: Event) {
		const file = (event.target as HTMLInputElement).files?.[0];
		if (picker) picker.value = '';
		if (!file) return;
		sending = true;
		notice = null;
		// Shrunk before it is encrypted: ciphertext never compresses, so a 5 MB camera photo
		// sealed is 5 MB on the wire and 5 MB in the database.
		try {
			const bytes = await downscale(file);
			if (!bytes) {
				notice = 'That image could not be read, or would not fit under the size cap.';
				return;
			}
			const reply = replyTo?.id ?? null;
			replyTo = null;
			const sent = await sendAttachment(
				groupId,
				'image',
				bytes,
				'image/jpeg',
				draft.trim() || null,
				reply
			);
			// Null means the group has no key this device can seal with, which is not something a
			// silent no-op should hide: without this the picture simply never appeared and there
			// was nothing on screen to say why.
			if (!sent) {
				notice = 'That photo could not be sent. This group has no key on this device.';
				return;
			}
			draft = '';
			connection.nudge();
			if (identity.current) notifyGroup(groupId, identity.current.userId);
		} catch (error) {
			notice = `That photo could not be sent: ${error instanceof Error ? error.message : 'unknown error'}`;
		} finally {
			sending = false;
		}
	}

	async function toggleRecording() {
		if (recorder.recording) {
			if (recordTimer) clearInterval(recordTimer);
			recordTimer = null;
			recordingFor = 0;
			const result = await recorder.stop();
			if (!result) {
				notice = 'That recording came back empty.';
				return;
			}
			sending = true;
			try {
				const sent = await sendAttachment(
					groupId,
					'audio',
					result.bytes,
					result.mime,
					null,
					replyTo?.id ?? null
				);
				if (!sent) {
					notice = 'That recording could not be sent. This group has no key on this device.';
					return;
				}
				replyTo = null;
				connection.nudge();
				if (identity.current) notifyGroup(groupId, identity.current.userId);
			} catch (error) {
				notice = `That recording could not be sent: ${error instanceof Error ? error.message : 'unknown error'}`;
			} finally {
				sending = false;
			}
			return;
		}
		notice = null;
		try {
			await recorder.start();
			recordTimer = setInterval(() => (recordingFor = Math.floor(recorder.elapsed / 1000)), 250);
		} catch (error) {
			// Almost always a refused microphone permission, and saying so is the difference
			// between a button that looks broken and one that tells you where to fix it.
			notice =
				error instanceof DOMException && error.name === 'NotAllowedError'
					? 'The browser is refusing this page the microphone. That is a site permission, changed from the icon in the address bar.'
					: 'The microphone is not available.';
		}
	}

	async function retry(message: Message) {
		const row = failedRows.value.find((r) => r.refMessageId === message.id);
		if (!row) return;
		await retryRow(row.id);
		await db.messages.update(message.id, { state: 'PENDING' });
		connection.nudge();
	}
</script>

<svelte:head><title>{group.value?.name ?? 'Chat'} · Family Group</title></svelte:head>

<TopBar title={group.value?.name ?? ''} back="/">
	{#snippet lead()}
		<a href="{base}/groups/{groupId}/info/" aria-label="Group info" class="shrink-0">
			<Avatar name={group.value?.name ?? ''} userId={groupId} size={32} />
		</a>
	{/snippet}
	<ConnectionBadge />
	<a
		href="{base}/groups/{groupId}/map/"
		aria-label="Map"
		title="Map"
		class="btn btn-quiet !px-2 !py-1"
	>
		<Icon name="map" />
	</a>
	<a
		href="{base}/groups/{groupId}/info/"
		aria-label="Group info"
		title="Group info"
		class="btn btn-quiet !px-2 !py-1"
	>
		<Icon name="info" />
	</a>
</TopBar>

<!-- The one part of this screen that scrolls. `min-h-0` is what lets it shrink to the space the
     composer leaves rather than growing the column and pushing the composer past the bottom. -->
<div bind:this={scroller} class="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4">
	{#if messages.value.length === 0}
		<p class="muted mt-10 text-center text-sm">
			Nothing here yet. Anything you send is encrypted before it leaves this device.
		</p>
	{/if}

	{#each messages.value as message, i (message.id)}
		{@const previous = messages.value[i - 1]}
		{@const newDay = !previous || dayLabel(previous.createdAt) !== dayLabel(message.createdAt)}
		{#if newDay}
			<p class="muted my-4 text-center text-xs font-semibold">{dayLabel(message.createdAt)}</p>
		{/if}
		<MessageBubble
			{message}
			mine={message.senderId === identity.current?.userId}
			senderName={nameFor(message.senderId)}
			senderId={message.senderId}
			avatarUrl={avatars.for(message.senderId)}
			startOfRun={newDay || previous?.senderId !== message.senderId}
			replyTo={message.replyToId
				? (messages.value.find((m) => m.id === message.replyToId) ?? null)
				: null}
			replyName={message.replyToId
				? nameFor(messages.value.find((m) => m.id === message.replyToId)?.senderId ?? '')
				: ''}
			receipts={receipts.value.filter((r) => r.messageId === message.id)}
			memberCount={members.value.filter((m) => !m.isRemoved).length}
			onReply={() => (replyTo = message)}
			onRetry={() => retry(message)}
			onAvatarTap={() =>
				(showing = members.value.find((m) => m.userId === message.senderId) ?? null)}
		/>
	{/each}
</div>

{#if notice}
	<p class="shrink-0 px-4 pb-2 text-xs" style="color: var(--error)">{notice}</p>
{/if}

{#if replyTo}
	<div
		class="flex shrink-0 items-center gap-2 px-4 py-2 text-xs"
		style="border-top: 1px solid var(--outline); background: var(--surface-1)"
	>
		<span class="muted">Replying to {nameFor(replyTo.senderId)}:</span>
		<span class="min-w-0 flex-1 truncate">{replyTo.body ?? 'attachment'}</span>
		<button class="btn btn-quiet !px-2 !py-0.5" onclick={() => (replyTo = null)}>×</button>
	</div>
{/if}

<!-- Pinned: a sibling of the scroll region rather than something inside it, so sending a message
     moves the list and never the input. The safe-area padding keeps it clear of a phone's home
     indicator when this is installed and running full-screen. -->
<form
	class="composer flex shrink-0 items-center gap-2 px-4 py-3"
	style="border-top: 1px solid var(--outline); padding-bottom: calc(0.75rem + env(safe-area-inset-bottom))"
	onsubmit={(e) => {
		e.preventDefault();
		void send();
	}}
>
	<button
		type="button"
		class="btn btn-quiet !w-11 !px-0"
		aria-label="Send a photo"
		disabled={sending || recorder.recording}
		onclick={() => picker?.click()}><Icon name="image" /></button
	>
	<input bind:this={picker} type="file" accept="image/*" class="hidden" onchange={pickImage} />

	{#if recorder.recording}
		<span class="field flex flex-1 items-center" style="color: var(--error)">
			Recording… {recordingFor}s
		</span>
	{:else}
		<input
			class="field flex-1"
			bind:value={draft}
			placeholder="Message"
			maxlength="4000"
			aria-label="Message"
		/>
	{/if}

	{#if canRecord()}
		<button
			type="button"
			class="btn btn-quiet !w-11 !px-0"
			aria-label={recorder.recording ? 'Send the recording' : 'Record a voice message'}
			disabled={sending}
			onclick={toggleRecording}><Icon name={recorder.recording ? 'stop' : 'mic'} /></button
		>
	{/if}

	<button class="btn btn-primary" disabled={!draft.trim() || sending || recorder.recording}>
		Send
	</button>
</form>

<!--
	Who said that, and the one thing you are likely to want to do about it.

	Deliberately thin: a large avatar, a name, whatever the member list already knows, and Buzz.
	Anything more would be a second group screen.
-->
{#if showing}
	<div
		class="fixed inset-0 z-40 flex items-end justify-center"
		style="background: #0006"
		role="presentation"
		onclick={() => (showing = null)}
	>
		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div
			class="w-full max-w-md rounded-t-2xl p-6 pb-10 text-center"
			style="background: var(--surface)"
			role="dialog"
			aria-modal="true"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
		>
			<div class="flex justify-center">
				<Avatar
					url={avatars.for(showing.userId)}
					name={showing.displayName}
					userId={showing.userId}
					size={88}
				/>
			</div>
			<h2 class="mt-4 text-xl font-semibold">{showing.displayName || 'Someone'}</h2>
			{#if showing.isRemoved}
				<p class="muted mt-1 text-sm">No longer in this group</p>
			{:else if showing.isAdmin}
				<p class="muted mt-1 text-sm">Admin of this group</p>
			{:else if showing.lastSeenAt === 0}
				<p class="muted mt-1 text-sm">Not heard from yet</p>
			{/if}

			<!-- Not for yourself, and not for someone who has left: neither would ring anything. -->
			{#if showing.userId !== identity.current?.userId && !showing.isRemoved}
				{@const target = showing}
				<button
					class="btn mt-6 w-full"
					disabled={!connection.isConnected || isBusy(calls.state)}
					onclick={() => {
						calls.dial(groupId, target.userId);
						showing = null;
					}}
				>
					<span class="inline-flex items-center justify-center gap-2"
						><Icon name="buzz" /> Buzz</span
					>
				</button>
				<p class="muted mt-2 text-xs leading-relaxed">
					Rings their phone even if it is on silent. No voice, no call — just a noise, until
					somebody stops it.
				</p>
			{/if}
		</div>
	</div>
{/if}

<style>
	/*
	 * Every control on the composer is exactly one height, set once here rather than left to
	 * whatever each control's own padding happens to produce. An icon button, a text field and a
	 * label button do not agree on their own, and lining their bottoms up only hid that they were
	 * three different sizes. 2.75rem is 44px, which is also the smallest comfortable tap target.
	 */
	.composer :global(.btn),
	.composer :global(.field) {
		height: 2.75rem;
		padding-top: 0;
		padding-bottom: 0;
	}
</style>
