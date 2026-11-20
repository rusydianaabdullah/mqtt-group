/**
 * Whether a newer build is waiting, and the two things a person can do about it.
 *
 * Registering the service worker is only half of staying current. The browser checks for a new
 * worker when the page is *navigated to*, and an installed PWA is very often not navigated to
 * for days — it is resumed, minimised, resumed again, and its one tab outlives several
 * deployments. Without something asking on its behalf, a phone can sit on an old build
 * indefinitely while the server has moved on twice.
 *
 * So this asks: once on startup, whenever the app comes back to the front, and on a slow timer
 * while it is open. `registration.update()` is a conditional request for one small file, which
 * is why it is cheap enough to do on every resume.
 */

/** Slow on purpose: the resume check is what actually catches a deploy, and this is the backstop
 * for a device left open on the same screen all day. */
const CHECK_EVERY_MS = 60 * 60 * 1000;
/** A resume a few seconds after the last check is the same visit; do not re-ask on every glance. */
const MIN_GAP_MS = 60 * 1000;

class AppUpdate {
	/** A new version is built, downloaded and waiting for the word. */
	ready = $state(false);
	/** A check is in flight, for a button that should say so. */
	checking = $state(false);
	lastCheckedAt = $state<number | null>(null);
	/** True once the worker is registered — nothing below can do anything before that. */
	registered = $state(false);

	#registration: ServiceWorkerRegistration | null = null;
	#apply: (reload?: boolean) => Promise<void> = async () => {};
	#started = false;

	/**
	 * Registers the worker and starts the checks. Called once, from the root layout's prompt.
	 *
	 * `registerType: 'prompt'` in vite.config.ts is what makes `onNeedRefresh` the signal rather
	 * than a silent swap: a worker that took over mid-session would reload the page out from
	 * under a half-typed message and drop a live MQTT connection with it.
	 */
	async start(): Promise<void> {
		if (this.#started || typeof window === 'undefined' || !('serviceWorker' in navigator)) return;
		this.#started = true;

		const { registerSW } = await import('virtual:pwa-register');
		this.#apply = registerSW({
			immediate: true,
			onNeedRefresh: () => (this.ready = true),
			onRegisteredSW: (_url: string, registration: ServiceWorkerRegistration | undefined) => {
				if (!registration) return;
				this.#registration = registration;
				this.registered = true;
				// A worker already sitting in `waiting` when the page loads — the ordinary case
				// for "closed the app yesterday, a deploy happened, opened it today" — fires no
				// event of its own, so it has to be noticed rather than waited for.
				if (registration.waiting) this.ready = true;
				void this.check();
			}
		});

		const onVisible = () => {
			if (document.visibilityState === 'visible') void this.check();
		};
		document.addEventListener('visibilitychange', onVisible);
		window.addEventListener('focus', onVisible);
		setInterval(() => void this.check(), CHECK_EVERY_MS);
	}

	/**
	 * Asks the server whether the worker has changed. Resolves once the browser has looked, so a
	 * button can wait on it and then say what it found.
	 *
	 * `force` is for that button: a person who taps "Check for updates" means now, whatever was
	 * asked a moment ago.
	 */
	async check(force = false): Promise<boolean> {
		const registration = this.#registration;
		if (!registration || this.checking) return this.ready;
		const since = this.lastCheckedAt === null ? Infinity : Date.now() - this.lastCheckedAt;
		if (!force && since < MIN_GAP_MS) return this.ready;
		this.checking = true;
		try {
			await registration.update();
			// `update()` resolves as soon as the new worker is installing, not once it is ready,
			// so the prompt still comes from onNeedRefresh. This only says whether one is there.
			if (registration.waiting) this.ready = true;
		} catch {
			// Offline, or the server briefly unreachable. Nothing to report — the next resume
			// asks again, and an app that cannot reach the network has bigger news to give.
		} finally {
			this.lastCheckedAt = Date.now();
			this.checking = false;
		}
		return this.ready;
	}

	/** Swaps the new worker in and reloads onto it. */
	async apply(): Promise<void> {
		await this.#apply(true);
	}

	dismiss(): void {
		this.ready = false;
	}
}

export const appUpdate = new AppUpdate();
