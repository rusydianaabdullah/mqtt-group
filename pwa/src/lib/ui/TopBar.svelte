<script lang="ts">
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';

	let {
		title,
		back = null,
		lead,
		children
	}: {
		title: string;
		back?: string | null;
		/** Drawn between the back arrow and the title — a group's avatar, on the screens that have one. */
		lead?: import('svelte').Snippet;
		children?: import('svelte').Snippet;
	} = $props();
</script>

<header
	class="sticky top-0 z-10 flex items-center gap-3 px-4 py-3"
	style="background: var(--background); border-bottom: 1px solid var(--outline)"
>
	{#if back}
		<button
			class="btn btn-quiet !px-2 !py-1"
			onclick={() => goto(`${base}${back}`)}
			aria-label="Back">←</button
		>
	{/if}
	{@render lead?.()}
	<h1 class="flex-1 truncate text-lg font-semibold tracking-tight">{title}</h1>
	{@render children?.()}
</header>
