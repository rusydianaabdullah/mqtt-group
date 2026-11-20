<script lang="ts">
	/**
	 * A person, drawn. Their photo when there is one, otherwise their initial on a colour
	 * derived from their user id — stable, so the same person is the same colour on every
	 * device in the group without anything about it being published.
	 */
	let {
		url = null,
		name = '',
		userId = '',
		size = 40
	}: { url?: string | null; name?: string; userId?: string; size?: number } = $props();

	const hue = $derived([...userId].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7));
	const initial = $derived((name.trim()[0] ?? '?').toUpperCase());
</script>

{#if url}
	<img
		src={url}
		alt=""
		width={size}
		height={size}
		class="shrink-0 rounded-full object-cover"
		style="width: {size}px; height: {size}px"
	/>
{:else}
	<span
		class="grid shrink-0 place-items-center rounded-full font-semibold text-white select-none"
		style="width: {size}px; height: {size}px; font-size: {Math.round(size * 0.42)}px;
		       background: hsl({hue} 45% 42%)"
		aria-hidden="true">{initial}</span
	>
{/if}
