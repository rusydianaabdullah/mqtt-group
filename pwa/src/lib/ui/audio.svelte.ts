/**
 * Voice notes.
 *
 * The one place this app and the Android app cannot agree on a format. Android records AAC in
 * an MP4 container, which is what `MediaRecorder` in a browser will not reliably produce;
 * browsers record Opus in WebM, which Android's own recorder cannot make either. Rather than
 * transcode on one side — which would mean shipping a codec — both sides accept both and play
 * whatever they are handed. Android's `MediaPlayer` reads Opus in WebM, and `<audio>` reads AAC
 * in MP4, so the intersection is "play it" and nobody has to convert anything.
 *
 * So the mime type on the wire is whatever was actually recorded, and it is never assumed.
 */

/** Preference order. The first the browser admits to supporting is the one used. */
const CANDIDATES = [
	'audio/webm;codecs=opus',
	'audio/ogg;codecs=opus',
	'audio/mp4;codecs=mp4a.40.2',
	'audio/webm',
	'audio/mp4'
];

export function pickMimeType(): string | null {
	if (typeof MediaRecorder === 'undefined') return null;
	return CANDIDATES.find((t) => MediaRecorder.isTypeSupported(t)) ?? null;
}

export function canRecord(): boolean {
	return typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
}

export class VoiceRecorder {
	/**
	 * Whether a recording is running, as a rune rather than as `recorder.state`.
	 *
	 * A getter reading the `MediaRecorder`'s own state is correct and completely invisible to
	 * Svelte: nothing tells a component when a media element changes, so the button stayed on the
	 * microphone icon and the "Recording…" line never appeared while a recording really was in
	 * progress. This file is `.svelte.ts` so that this one field can be a signal.
	 */
	recording = $state(false);

	private recorder: MediaRecorder | null = null;
	private stream: MediaStream | null = null;
	private chunks: Blob[] = [];
	private startedAt = 0;

	/** Milliseconds since recording began, for the timer on the button. */
	get elapsed(): number {
		return this.startedAt ? Date.now() - this.startedAt : 0;
	}

	async start(): Promise<void> {
		const mime = pickMimeType();
		if (!mime) throw new Error('This browser cannot record audio.');
		this.stream = await navigator.mediaDevices.getUserMedia({
			// Mono, and the browser's own noise handling. A voice note is speech in a room, not
			// a recording session, and these three do more for it than any bitrate would.
			audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
		});
		this.chunks = [];
		this.recorder = new MediaRecorder(this.stream, { mimeType: mime, audioBitsPerSecond: 32_000 });
		this.recorder.ondataavailable = (event) => {
			if (event.data.size > 0) this.chunks.push(event.data);
		};
		this.recorder.start();
		this.startedAt = Date.now();
		this.recording = true;
	}

	/** Stops and hands back the recording, or null if it was cancelled or produced nothing. */
	async stop(): Promise<{ bytes: Uint8Array; mime: string } | null> {
		const recorder = this.recorder;
		if (!recorder || recorder.state === 'inactive') {
			this.release();
			return null;
		}
		this.recording = false;
		const finished = new Promise<void>((resolve) => (recorder.onstop = () => resolve()));
		recorder.stop();
		await finished;
		const mime = recorder.mimeType || 'audio/webm';
		this.release();
		if (this.chunks.length === 0) return null;
		const blob = new Blob(this.chunks, { type: mime });
		return { bytes: new Uint8Array(await blob.arrayBuffer()), mime };
	}

	cancel(): void {
		if (this.recorder?.state === 'recording') this.recorder.stop();
		this.recording = false;
		this.chunks = [];
		this.release();
	}

	private release(): void {
		this.recording = false;
		for (const track of this.stream?.getTracks() ?? []) track.stop();
		this.stream = null;
		this.recorder = null;
		this.startedAt = 0;
	}
}
