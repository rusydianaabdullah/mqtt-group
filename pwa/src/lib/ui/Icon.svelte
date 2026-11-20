<script lang="ts">
	/**
	 * The app's icons, as paths rather than as emoji.
	 *
	 * Emoji looked like a way to get icons for free, and on a phone it nearly is. On a desktop it
	 * is a lottery: 🗺 and ⏹ have no glyph in the default font on most Linux installs and plenty of
	 * Windows ones, so the map button rendered as an empty box — an icon that says nothing at all.
	 * These are drawn, so they are the same everywhere and they take the surrounding text colour.
	 *
	 * Feather's 24×24 grid, stroked rather than filled, because everything else in this app is
	 * outlines on flat surfaces.
	 */
	let { name, size = 18 }: { name: keyof typeof PATHS; size?: number } = $props();

	const PATHS = {
		map: 'M1 6v16l7-4 8 4 7-4V2l-7 4-8-4-7 4zM8 2v16M16 6v16',
		info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01',
		image: 'M3 3h18v18H3zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM21 15l-5-5L5 21',
		mic: 'M12 1a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3zM19 10v1a7 7 0 0 1-14 0v-1M12 18v4M8 22h8',
		stop: 'M6 6h12v12H6z',
		refresh:
			'M23 4v6h-6M1 20v-6h6M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15',
		about: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01',
		// A phone with sound coming off both sides, not a handset: a buzz is a noise a phone
		// makes, and a handset would promise the voice call this deliberately is not.
		buzz:
			'M9 3h6a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z' +
			'M4.5 9.5v5M1.5 11v2M19.5 9.5v5M22.5 11v2'
	} as const;
</script>

<svg
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width="2"
	stroke-linecap="round"
	stroke-linejoin="round"
	aria-hidden="true"
	focusable="false"
	style="display: block"
>
	<path d={PATHS[name]} />
</svg>
