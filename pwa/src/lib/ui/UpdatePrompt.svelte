<script lang="ts">
	import { onMount } from 'svelte';
	import { appUpdate } from './appUpdate.svelte';

	// The registration and the polling live in `appUpdate` so the About screen can offer a manual
	// check against the same registration; this component is only the bar that appears when one
	// of those checks finds something.
	onMount(() => void appUpdate.start());
</script>

{#if appUpdate.ready}
	<div
		class="fixed inset-x-3 bottom-3 z-50 mx-auto flex max-w-md items-center gap-3 rounded-xl border px-4 py-3 text-sm shadow-lg"
		style="border-color: var(--outline); background: var(--surface-1)"
		role="status"
	>
		<span class="flex-1">A new version is ready.</span>
		<button
			class="rounded-lg px-3 py-1.5 font-semibold"
			style="background: var(--primary); color: var(--on-primary)"
			onclick={() => appUpdate.apply()}
		>
			Reload
		</button>
		<button
			class="rounded-lg px-2 py-1.5"
			style="color: var(--on-surface-muted)"
			onclick={() => appUpdate.dismiss()}
		>
			Later
		</button>
	</div>
{/if}
