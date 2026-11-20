/**
 * Shrinks images before they are encrypted, because ciphertext never compresses: a 5 MB photo
 * sealed is 5 MB on the wire and 5 MB in the database. Everything that becomes a blob passes
 * through here first — the browser's answer to the Android app's `Images` and `ImagePrep`.
 *
 * `createImageBitmap` handles EXIF orientation itself when told to, which is the one thing a
 * naive canvas draw gets wrong: a portrait photo from a phone camera is stored landscape with a
 * rotation flag, and ignoring it puts everyone's avatar on its side.
 */

/** Comfortably under the 1 MB the blob store assumes, with room for the envelope's overhead. */
export const MAX_BYTES = 900_000;
const MAX_DIMENSION = 1600;

/** Avatars are only ever drawn small; 256px square is generous and lands around 20 KB. */
export async function prepareAvatar(source: Blob): Promise<Uint8Array | null> {
	return downscale(source, 256, 0.85);
}

/**
 * Turns whatever a file picker hands back into something that fits the cap: fits it inside
 * `maxEdge`, re-encodes as JPEG, and steps the quality down until it is small enough. Returns
 * null for bytes that are not a decodable image, or for one that will not fit at any quality
 * this is willing to use.
 */
export async function downscale(
	source: Blob,
	maxEdge = MAX_DIMENSION,
	quality = 0.8,
	maxBytes: number | null = null
): Promise<Uint8Array | null> {
	let bitmap: ImageBitmap;
	try {
		bitmap = await createImageBitmap(source, { imageOrientation: 'from-image' });
	} catch {
		return null;
	}

	const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
	const width = Math.max(1, Math.round(bitmap.width * scale));
	const height = Math.max(1, Math.round(bitmap.height * scale));

	const canvas = document.createElement('canvas');
	canvas.width = width;
	canvas.height = height;
	const context = canvas.getContext('2d');
	if (!context) return null;
	// JPEG has no alpha, and an unpainted canvas is transparent black — which a PNG avatar with
	// a transparent background would turn into a silhouette on a black square.
	context.fillStyle = '#ffffff';
	context.fillRect(0, 0, width, height);
	context.drawImage(bitmap, 0, 0, width, height);
	bitmap.close();

	let q = quality;
	let bytes = await encode(canvas, q);
	// A few steps down is enough for any normal photo; if it still does not fit, this is not a
	// photo an MQTT broker meant for chat should be carrying anyway.
	const cap = maxBytes ?? MAX_BYTES;
	while (bytes.length > cap && q > 0.2) {
		q -= 0.15;
		bytes = await encode(canvas, q);
	}
	return bytes.length <= cap ? bytes : null;
}

async function encode(canvas: HTMLCanvasElement, quality: number): Promise<Uint8Array> {
	const blob = await new Promise<Blob | null>((resolve) =>
		canvas.toBlob(resolve, 'image/jpeg', quality)
	);
	if (!blob) return new Uint8Array(0);
	return new Uint8Array(await blob.arrayBuffer());
}
