/**
 * The noise an incoming call makes, in a tab that is open.
 *
 * This is the one place where the browser can actually ring, and it is worth being clear about
 * why it is the *only* one. A closed tab is woken by a push whose handler runs in the service
 * worker, and a service worker has no `Audio`, no `AudioContext` and no way to make
 * `showNotification`'s unimplemented `sound` option do anything. What plays there is the
 * operating system's one notification chime, once. So `service-worker.ts` says "missed call" and
 * this exists for the case where a real ring is possible.
 *
 * Two browser rules shape the rest:
 *
 * - **Autoplay.** A tab that has never been interacted with may refuse to play audio at all. The
 *   failure is a rejected promise, not an exception, and it is not worth surfacing: the call
 *   still rings visually and the vibration still fires where there is one. Silently degrading is
 *   right here in a way it would not be for a missed notification.
 * - **`navigator.vibrate` is Chrome-on-Android only** and ignored elsewhere, so it is an
 *   addition to the ringtone rather than a substitute for it.
 *
 * The tone is synthesised rather than fetched. A ringtone file would be one more asset to cache,
 * to get wrong offline, and to have no rights to; two alternating sine tones on a loop is what a
 * ring has sounded like since before any of this, and it costs nothing to ship.
 */
export class Ringer {
	/**
	 * True once a burst has actually reached the speakers, so the UI can tell "ringing" from
	 * "ringing silently because this tab has never been interacted with".
	 *
	 * That distinction matters more than it looks. A browser refuses audio to a page nobody has
	 * clicked, and the refusal is a suspended `AudioContext` rather than an error — so a call
	 * arriving in a tab that was opened and left alone rings to nobody, with nothing on screen
	 * saying why.
	 */
	audible = false;

	private context: AudioContext | null = null;
	private stopAt: number | null = null;
	private loop: ReturnType<typeof setInterval> | null = null;

	start(): void {
		this.stop();
		this.burst();
		// The cadence every phone has: a short double ring, then a gap. Repeating on a timer
		// rather than scheduling the whole call's worth of tones up front, because a call that is
		// answered after two seconds should go quiet after two seconds.
		this.loop = setInterval(() => this.burst(), CADENCE_MS);
		if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
			// Chrome on Android only; everywhere else this is a no-op rather than an error.
			try {
				navigator.vibrate([0, 700, 600, 700, 1400]);
			} catch {
				// A browser that exposes the method and refuses the call. Nothing to do.
			}
		}
	}

	stop(): void {
		this.audible = false;
		if (this.loop !== null) clearInterval(this.loop);
		this.loop = null;
		this.stopAt = null;
		if (this.context) {
			const context = this.context;
			this.context = null;
			void context.close().catch(() => {
				// Already closed, or closing. Either way there is nothing left making noise.
			});
		}
		if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
			try {
				navigator.vibrate(0);
			} catch {
				// As above.
			}
		}
	}

	/** One double-ring. */
	private burst(): void {
		try {
			this.context ??= new AudioContext();
			const context = this.context;
			// A tab backgrounded before the call arrived can have a suspended context; resuming
			// is allowed because the ring is a response to a real event, not an autoplay. A tab
			// that has *never* been interacted with is the case this cannot fix: resume() is
			// rejected and the ring is silent until somebody touches the page.
			if (context.state === 'suspended') {
				void context.resume().then(
					() => (this.audible = true),
					() => (this.audible = false)
				);
			} else {
				this.audible = context.state === 'running';
			}
			const now = context.currentTime;
			for (let i = 0; i < 2; i++) {
				this.tone(context, now + i * (TONE_S + GAP_S));
			}
			this.stopAt = now;
		} catch {
			// No AudioContext, or the tab is not allowed one yet. The call still shows.
		}
	}

	private tone(context: AudioContext, at: number): void {
		const oscillator = context.createOscillator();
		const gain = context.createGain();
		oscillator.type = 'sine';
		oscillator.frequency.value = FREQ_HZ;
		// Ramped rather than switched, because an abrupt start and stop on a sine wave is a
		// click at each end, and two clicks a second is what a broken ringtone sounds like.
		gain.gain.setValueAtTime(0, at);
		gain.gain.linearRampToValueAtTime(VOLUME, at + 0.02);
		gain.gain.setValueAtTime(VOLUME, at + TONE_S - 0.02);
		gain.gain.linearRampToValueAtTime(0, at + TONE_S);
		oscillator.connect(gain).connect(context.destination);
		oscillator.start(at);
		oscillator.stop(at + TONE_S);
	}
}

const FREQ_HZ = 440;
const VOLUME = 0.18;
const TONE_S = 0.4;
const GAP_S = 0.2;
const CADENCE_MS = 2600;
