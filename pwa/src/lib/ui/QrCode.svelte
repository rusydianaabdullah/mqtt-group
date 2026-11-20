<script lang="ts">
	import QRCode from 'qrcode';

	/**
	 * The invite, as a square someone points a phone at.
	 *
	 * Drawn at high error correction because the realistic reading conditions are a phone camera
	 * pointed at a laptop screen at an angle, with glare — which is exactly the case the lower
	 * levels give up on. Rendered black on white regardless of theme: a dark-mode QR is an
	 * inverted QR, and while most scanners cope, "most" is not a thing to rely on when the
	 * failure looks like the app being broken.
	 */
	let { value, size = 260 }: { value: string; size?: number } = $props();

	let canvas = $state<HTMLCanvasElement | null>(null);

	$effect(() => {
		if (!canvas) return;
		void QRCode.toCanvas(canvas, value, {
			width: size,
			margin: 2,
			errorCorrectionLevel: 'H',
			color: { dark: '#000000ff', light: '#ffffffff' }
		});
	});
</script>

<canvas bind:this={canvas} class="rounded-lg" width={size} height={size}></canvas>
