import { readFileSync } from 'node:fs';
import { defineConfig } from 'vitest/config';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'));

// Separate from vite.config.ts on purpose: that one carries the SvelteKit and PWA plugins,
// neither of which a unit test needs, and both of which slow every run down. __APP_VERSION__
// still needs defining here too, or importing $lib/version under vitest throws a ReferenceError.
export default defineConfig({
	define: { __APP_VERSION__: JSON.stringify(pkg.version) },
	test: {
		environment: 'node',
		include: ['src/**/*.test.ts'],
		setupFiles: ['./vitest-setup.ts']
	},
	resolve: {
		alias: { $lib: new URL('./src/lib', import.meta.url).pathname }
	}
});
