<script lang="ts">
	import type { Message, MessageReceipt } from '$lib/data/db/schema';
	import { blobObjectUrl } from '$lib/data/blob/blobStore';
	import { db } from '$lib/data/db/schema';
	import { clockTime } from './relativeTime';
	import { live } from '$lib/stores/live.svelte';
	import Avatar from './Avatar.svelte';

	let {
		message,
		mine,
		senderName,
		senderId = '',
		avatarUrl = null,
		startOfRun = true,
		replyTo = null,
		replyName = '',
		receipts = [],
		memberCount = 1,
		onReply,
		onRetry,
		onAvatarTap
	}: {
		message: Message;
		mine: boolean;
		senderName: string;
		/** Whose message it is, so the fallback circle keeps its per-person colour. */
		senderId?: string;
		avatarUrl?: string | null;
		/**
		 * Whether this is the first message of a run from the same person. The name and the photo
		 * are only drawn there — repeating a face down a whole run of someone's messages is noise
		 * that no chat app adds — but the space they take is reserved on every incoming row, so
		 * the bubbles below stay lined up under the one above them.
		 */
		startOfRun?: boolean;
		replyTo?: Message | null;
		replyName?: string;
		receipts?: MessageReceipt[];
		memberCount?: number;
		onReply: () => void;
		onRetry: () => void;
		/** Tapping a face opens the person's card — the shortest path from "who said this" to
		 * doing something about it. Absent for your own messages, which have no avatar. */
		onAvatarTap?: () => void;
	} = $props();

	/**
	 * The attachment's bytes, watched rather than fetched once.
	 *
	 * A photo's bytes and the message referencing them travel on different topics, and MQTT orders
	 * only within one — so the bubble is routinely rendered before the blob it needs exists. The
	 * old code read the blob in an effect keyed on `message.blobId`, which never changes for a
	 * given bubble: the bytes landing seconds later moved nothing, and "Waiting for the photo"
	 * stayed on screen for good. These are live queries, so the arrival of the row is the trigger.
	 */
	const blobRow = live(
		() => (message.blobId ? db.blobs.get(message.blobId) : undefined),
		undefined
	);
	const attachment = live(
		() => (message.blobId ? db.attachments.get(message.blobId) : undefined),
		undefined
	);

	let mediaUrl = $state<string | null>(null);

	$effect(() => {
		const blobId = message.blobId;
		const present = !!blobRow.value;
		// The sender's own mime is the one that matters for a voice note: this app records WebM
		// and Android records MP4, and `<audio>` needs to be told which it was handed.
		const mime = attachment.value?.mime || (message.type === 'audio' ? 'audio/webm' : 'image/jpeg');
		if (!blobId || !present) {
			mediaUrl = null;
			return;
		}

		let url: string | null = null;
		let cancelled = false;
		void (async () => {
			url = await blobObjectUrl(blobId, mime);
			if (cancelled) {
				if (url) URL.revokeObjectURL(url);
				return;
			}
			mediaUrl = url;
		})();
		return () => {
			cancelled = true;
			// Every object URL pins the decrypted bytes in memory until it is released, and a chat
			// scrolled through for a while would otherwise hold every photo it ever rendered.
			if (url) URL.revokeObjectURL(url);
		};
	});

	/**
	 * One check for "it left this device", two for "somebody has it", two in the accent colour
	 * for "everybody who is here has read it".
	 *
	 * Counted against the members other than us, so a group of one never sits on a single check
	 * forever waiting for an audience that does not exist.
	 */
	const others = $derived(Math.max(0, memberCount - 1));
	const delivered = $derived(receipts.length);
	const read = $derived(receipts.filter((r) => r.state === 'READ').length);
	const tick = $derived.by(() => {
		if (!mine) return null;
		if (message.state === 'FAILED') return 'failed';
		if (message.state === 'PENDING') return 'pending';
		if (others > 0 && read >= others) return 'read';
		if (delivered > 0) return 'delivered';
		return 'sent';
	});
</script>

<div class="mb-2 flex items-end gap-2 {mine ? 'justify-end' : 'justify-start'}">
	{#if !mine}
		<!-- Reserved on every incoming row, filled only at the top of a run. -->
		<div class="w-7 shrink-0">
			<!-- Only where there is actually a face to tap. The slot is reserved on every row of
			     a run so the bubbles line up, but the empty ones are spacing, and a click target
			     on spacing is a mystery. -->
			{#if startOfRun}
				<button
					type="button"
					class="block rounded-full"
					aria-label="About {senderName}"
					onclick={onAvatarTap}
				>
					<Avatar url={avatarUrl} name={senderName} userId={senderId} size={28} />
				</button>
			{/if}
		</div>
	{/if}

	<div class="group flex max-w-[85%] min-w-0 flex-col {mine ? 'items-end' : 'items-start'}">
		{#if !mine && startOfRun}
			<span class="muted mb-0.5 ml-1 text-xs font-semibold">{senderName}</span>
		{/if}

		<div
			class="min-w-0 rounded-2xl px-3 py-2 text-sm"
			style={mine
				? 'background: var(--primary-container); color: var(--on-primary-container)'
				: 'background: var(--surface-1); border: 1px solid var(--outline)'}
		>
			{#if replyTo}
				<div
					class="mb-1.5 rounded-lg border-l-2 px-2 py-1 text-xs"
					style="border-color: var(--accent); background: color-mix(in srgb, var(--surface) 45%, transparent)"
				>
					<span class="font-semibold">{replyName}</span>
					<span class="line-clamp-2 opacity-80">{replyTo.body ?? 'attachment'}</span>
				</div>
			{/if}

			{#if message.type === 'image'}
				{#if mediaUrl}
					<img src={mediaUrl} alt="" class="mb-1 max-h-80 rounded-lg" />
				{:else}
					<div
						class="mb-1 grid h-40 w-56 place-items-center rounded-lg text-xs"
						style="background: var(--surface-2); color: var(--on-surface-muted)"
					>
						Waiting for the photo
					</div>
				{/if}
			{:else if message.type === 'audio'}
				{#if mediaUrl}
					<!-- Whatever container the sender's device produced. Both ends accept both, so
					     nothing here decides what a voice note is supposed to be. -->
					<audio controls src={mediaUrl} class="mb-1 w-56"></audio>
				{:else}
					<p class="muted mb-1 text-xs">Waiting for the recording</p>
				{/if}
			{/if}

			{#if message.body}
				<p class="whitespace-pre-wrap break-words">{message.body}</p>
			{/if}

			<div class="mt-0.5 flex items-center justify-end gap-1.5 text-[11px] opacity-70">
				<span>{clockTime(message.createdAt)}</span>
				{#if tick === 'pending'}
					<span title="Waiting to be sent">◌</span>
				{:else if tick === 'sent'}
					<span title="The broker has it">✓</span>
				{:else if tick === 'delivered'}
					<span title="{delivered} of {others} have it">✓✓</span>
				{:else if tick === 'read'}
					<span style="color: var(--accent)" title="Everyone has read it">✓✓</span>
				{:else if tick === 'failed'}
					<button class="font-semibold" style="color: var(--error)" onclick={onRetry}>
						Failed · retry
					</button>
				{/if}
			</div>
		</div>

		<button
			class="muted mt-0.5 px-1 text-[11px] opacity-0 transition-opacity group-hover:opacity-100 focus:opacity-100"
			onclick={onReply}>Reply</button
		>
	</div>
</div>
