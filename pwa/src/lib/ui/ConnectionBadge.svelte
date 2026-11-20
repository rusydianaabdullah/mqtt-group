<script lang="ts">
	import { connection } from '$lib/stores/connection.svelte';

	/**
	 * The status line. Cyan only for connected, because cyan is this app's "now" colour and it
	 * only works as a signal while nothing else on the screen uses it.
	 */
	const label = $derived.by(() => {
		switch (connection.status.kind) {
			case 'connected':
				return connection.queued > 0 ? `Sending ${connection.queued}` : 'Connected';
			case 'connecting':
				return 'Connecting';
			case 'waiting':
				return 'Offline';
			default:
				return 'No broker';
		}
	});

	const dot = $derived(
		connection.status.kind === 'connected'
			? 'var(--accent)'
			: connection.status.kind === 'connecting'
				? 'var(--on-surface-muted)'
				: 'var(--error)'
	);
</script>

<span
	class="inline-flex items-center gap-1.5 text-xs font-medium whitespace-nowrap"
	title={connection.status.kind === 'waiting' ? connection.status.reason : label}
>
	<span class="size-2 rounded-full" style="background: {dot}"></span>
	<span class="muted">{label}</span>
</span>
