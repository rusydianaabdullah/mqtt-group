import { base } from '$app/paths';

/**
 * The service worker registration FCM needs, obtained without ever awaiting
 * `navigator.serviceWorker.ready`.
 *
 * That promise is the documented way to do this and it does not work on iOS. In a standalone
 * Home Screen app WebKit routinely leaves `ready` pending forever — most reliably right after a
 * cold launch, when the page is not yet controlled — and an await on it never returns, so the
 * token mint never happens, no /subscribe is ever sent, and the relay's log stays empty. That is
 * exactly the failure this replaces: "the service worker did not answer within 15 s" on an
 * iPhone whose service worker was in fact registered and active the whole time.
 *
 * `getRegistration()` settles on iOS where `ready` does not, so the shape here is: ask for the
 * registration, and if it has no active worker yet, watch for one and poll. `ready` is still
 * raced alongside — on every browser where it behaves, it is the fastest correct answer.
 *
 * A page with no registration at all registers one. That is not the normal path (the app
 * registers its worker at startup, see appUpdate.svelte.ts) but it is the state an iOS PWA can
 * be resumed into after WebKit has evicted the worker, and re-registering is what gets push back
 * rather than reporting a browser that "cannot do push".
 */
export async function pushServiceWorker(timeoutMs = 30_000): Promise<ServiceWorkerRegistration> {
	if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
		throw new Error('This browser has no service worker, so it cannot receive push at all.');
	}
	const container = navigator.serviceWorker;
	const deadline = Date.now() + timeoutMs;

	// The fastest correct answer where it works, and harmless where it does not: nothing awaits
	// this on its own, it only races the loop below.
	let fromReady: ServiceWorkerRegistration | null = null;
	void container.ready.then((registration) => (fromReady = registration)).catch(() => {});

	let registration = (await container.getRegistration()) ?? null;
	if (!registration) {
		// `base` is '' in this build, so this is '/service-worker.js' — the same URL and the same
		// root scope vite-plugin-pwa registers, so this adopts that registration rather than
		// creating a second one.
		registration = await container.register(`${base}/service-worker.js`, {
			scope: `${base}/`,
			type: 'classic'
		});
	}

	while (!registration.active && !fromReady && Date.now() < deadline) {
		// An installing or waiting worker becomes active on its own; this is just waiting for it.
		// Polled rather than purely event-driven because the registration object an iOS PWA hands
		// back after a resume does not reliably fire `updatefound` for a worker that was already
		// on its way when the page loaded.
		await new Promise<void>((resolve) => setTimeout(resolve, 250));
		registration = (await container.getRegistration()) ?? registration;
	}

	const resolved = registration.active ? registration : (fromReady ?? null);
	if (!resolved) {
		throw new Error(
			'The service worker is registered but never became active. On iPhone, closing the app ' +
				'from the app switcher and opening it again usually clears this.'
		);
	}
	return resolved;
}

/**
 * What the service worker is doing right now, in one line, for the Settings screen.
 *
 * A phone has no DevTools, and every push failure looks identical from the outside. This is the
 * difference between "no worker at all" and "a worker that is active and a token that is dead",
 * which are fixed by completely different things.
 */
export async function serviceWorkerState(): Promise<string> {
	if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return 'unsupported';
	const registration = await navigator.serviceWorker.getRegistration();
	if (!registration) return 'not registered';
	const parts = [
		registration.active ? 'active' : null,
		registration.waiting ? 'update waiting' : null,
		registration.installing ? 'installing' : null,
		navigator.serviceWorker.controller ? 'controlling this page' : 'not controlling this page'
	].filter(Boolean);
	return parts.join(' · ');
}
