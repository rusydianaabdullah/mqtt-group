import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
export default {
	preprocess: vitePreprocess(),
	kit: {
		// Everything renders in the browser against IndexedDB and a live MQTT socket, so there
		// is nothing a server could usefully prerender. `fallback` makes the whole thing one
		// SPA shell that any static host can serve for any path.
		adapter: adapter({ fallback: 'index.html', precompress: true }),
		// Relative paths mean the build also works from a subdirectory or a file:// URL, which
		// is the escape hatch for anyone who does not want to trust a host with the origin.
		paths: { relative: true },
		alias: { $lib: 'src/lib' }
	}
};
