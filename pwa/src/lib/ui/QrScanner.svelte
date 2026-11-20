<script lang="ts">
	import { onDestroy } from 'svelte';

	/**
	 * The camera, looking for an invite.
	 *
	 * Two decoders, because neither is universal: `BarcodeDetector` is native, fast and free in
	 * Chromium and recent Safari, and absent in Firefox. jsQR is the fallback — pure JS over
	 * canvas pixels, slower but everywhere. Trying the native one first and falling back on the
	 * first failure means most people never pay for the library at all, since it is imported
	 * lazily and only when it is actually needed.
	 *
	 * Every path ends in `stop()`. A camera left running is a green light on someone's laptop
	 * with no window explaining it, which is the sort of thing that makes an app untrustworthy
	 * regardless of what it actually did.
	 */
	let { onScan, onError }: { onScan: (text: string) => void; onError?: (m: string) => void } =
		$props();

	let video = $state<HTMLVideoElement | null>(null);
	let stream: MediaStream | null = null;
	let running = false;
	let frame: number | null = null;

	export async function start(): Promise<void> {
		if (running) return;
		try {
			stream = await navigator.mediaDevices.getUserMedia({
				// The back camera on a phone, and the only camera on a laptop. `ideal` rather than
				// `exact` so a device with one camera does not fail outright.
				video: { facingMode: { ideal: 'environment' } }
			});
		} catch (error) {
			onError?.(
				error instanceof DOMException && error.name === 'NotAllowedError'
					? 'Camera access was refused. You can paste the invite code instead.'
					: 'No camera is available. You can paste the invite code instead.'
			);
			return;
		}
		running = true;
		if (video) {
			video.srcObject = stream;
			await video.play().catch(() => undefined);
		}
		void loop();
	}

	export function stop(): void {
		running = false;
		if (frame !== null) cancelAnimationFrame(frame);
		frame = null;
		for (const track of stream?.getTracks() ?? []) track.stop();
		stream = null;
		if (video) video.srcObject = null;
	}

	async function loop(): Promise<void> {
		const detector = await nativeDetector();
		const canvas = document.createElement('canvas');
		const context = canvas.getContext('2d', { willReadFrequently: true });
		let jsQR: typeof import('jsqr').default | null = null;

		const tick = async () => {
			if (!running || !video || video.readyState < 2) {
				if (running) frame = requestAnimationFrame(() => void tick());
				return;
			}

			let text: string | null = null;
			if (detector) {
				try {
					const found = await detector.detect(video);
					text = found[0]?.rawValue ?? null;
				} catch {
					// A detector that throws mid-stream is one to stop asking.
					text = null;
				}
			}
			if (!text && context) {
				jsQR ??= (await import('jsqr')).default;
				canvas.width = video.videoWidth;
				canvas.height = video.videoHeight;
				context.drawImage(video, 0, 0);
				const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
				text = jsQR(pixels.data, pixels.width, pixels.height)?.data ?? null;
			}

			if (text) {
				onScan(text);
				return;
			}
			if (running) frame = requestAnimationFrame(() => void tick());
		};
		void tick();
	}

	interface Detector {
		detect(source: CanvasImageSource): Promise<{ rawValue: string }[]>;
	}

	async function nativeDetector(): Promise<Detector | null> {
		const ctor = (
			globalThis as {
				BarcodeDetector?: new (o: { formats: string[] }) => Detector & {
					constructor: { getSupportedFormats?(): Promise<string[]> };
				};
			}
		).BarcodeDetector;
		if (!ctor) return null;
		try {
			return new ctor({ formats: ['qr_code'] });
		} catch {
			return null;
		}
	}

	onDestroy(stop);
</script>

<!-- svelte-ignore a11y_media_has_caption -->
<video
	bind:this={video}
	class="w-full rounded-xl"
	style="background: var(--surface-2); aspect-ratio: 4/3; object-fit: cover"
	playsinline
	muted
></video>
