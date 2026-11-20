import { readFileSync } from 'node:fs';
import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'));

export default defineConfig({
	// A string constant, not a JSON import: pulling in package.json directly would hand the
	// bundler the whole dependency tree's metadata to print six characters. This substitutes the
	// literal at build time instead, so nothing but the version number ever reaches the client.
	define: { __APP_VERSION__: JSON.stringify(pkg.version) },
	plugins: [
		tailwindcss(),
		sveltekit(),
		SvelteKitPWA({
			registerType: 'prompt',
			injectRegister: false,
			/**
			 * The app shell is a fallback page the *adapter* writes, after this plugin has already
			 * generated the worker — so without this it is absent from the precache manifest, and
			 * the worker's `createHandlerBoundToURL('index.html')` throws `non-precached-url` while
			 * it is being evaluated. A service worker that throws on evaluation never installs.
			 *
			 * That is one failure with a long tail, and it accounts for every push symptom this app
			 * has had on iOS: no worker meant `navigator.serviceWorker.ready` waiting forever,
			 * which meant no FCM token, which meant no /subscribe and nothing in the relay's log.
			 * It also meant the app was never precached at all, so it never actually worked
			 * offline, and each new build failed to install exactly like the one before it.
			 *
			 * `spa: true` is what adds the fallback to the manifest, revisioned from
			 * `_app/version.json` since the page itself does not exist yet at that point.
			 */
			kit: { adapterFallback: 'index.html', spa: true },
			// generateSW can only produce a plain precaching worker from config; FCM's web push
			// needs actual code running in the service worker (a 'push' and a 'notificationclick'
			// listener — see src/service-worker.ts), which only injectManifest allows.
			strategies: 'injectManifest',
			srcDir: 'src',
			filename: 'service-worker.ts',
			manifest: {
				name: 'Family Group: Chat & Location',
				short_name: 'Family Group',
				description: 'Private group chat and location sharing over MQTT.',
				id: '/',
				start_url: '.',
				scope: '.',
				display: 'standalone',
				orientation: 'portrait',
				background_color: '#08090c',
				theme_color: '#08090c',
				categories: ['social', 'navigation'],
				icons: [
					{ src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
					{ src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
					{
						src: 'icons/icon-512.png',
						sizes: '512x512',
						type: 'image/png',
						purpose: 'maskable'
					}
				]
			},
			injectManifest: {
				// The shell only. Map tiles get their own runtime cache in phase 5, and MQTT
				// traffic is deliberately never cached — a stale encrypted envelope replayed
				// out of a cache is worse than no message at all.
				globPatterns: ['**/*.{js,css,html,svg,png,woff2}']
			},
			devOptions: { enabled: false }
		})
	]
});
