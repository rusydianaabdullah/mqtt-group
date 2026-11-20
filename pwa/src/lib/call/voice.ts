import { encodeAudio, type AudioPacket } from '$lib/mqtt/audioMessages';
import { JitterBuffer } from './jitterBuffer';

/**
 * The voice of one call in a browser: the microphone out, and everyone else in.
 *
 * WebCodecs either way, which is what makes this shorter than the Android twin. `AudioEncoder`
 * and `AudioDecoder` speak Opus directly, with no library and no container — where Android has
 * to query `MediaCodec` for an Opus encoder that may not exist and hand its decoder a synthesised
 * `OpusHead`, a browser that has WebCodecs has Opus by definition.
 *
 * Mixing is free for the same reason. Each speaker gets a decoder and their own place in the
 * audio graph, and the `AudioContext` sums them — no manual 32-bit accumulation and clipping,
 * which the Kotlin side does by hand.
 *
 * What is not free is that this only works with the tab open, like everything else here. It is
 * also the one place in this app that publishes outside the outbox: a late voice packet is noise
 * from a conversation that has ended. See plan.md, phase 9.
 */

/** 20ms at 48 kHz mono — the unit everything deals in, matching `OpusCodec.FRAME_SAMPLES`. */
const SAMPLE_RATE = 48_000;
const CHANNELS = 1;
const FRAME_SAMPLES = 960;
const BITRATE = 24_000;
/**
 * Two 20ms frames to a 40ms packet. See `audioMessages.ts` for why not one.
 *
 * Three was the original figure, chosen to amortise the 56-byte envelope. But every frame in a
 * packet is a frame the sender holds back while it fills, and the first real call over a broker
 * made plain that latency is the scarce thing here and bandwidth is not. Purely a sender's
 * choice: the format carries a frame count, so an older build packing three still talks to one
 * packing two.
 */
const FRAMES_PER_PACKET = 2;
/** How long a speaker is kept after they go quiet, so a long call does not hold a decoder for
 * everyone who ever spoke. */
const SPEAKER_IDLE_MS = 10_000;

export function voiceSupported(): boolean {
	return (
		typeof window !== 'undefined' &&
		typeof window.AudioEncoder === 'function' &&
		typeof window.AudioDecoder === 'function' &&
		typeof navigator !== 'undefined' &&
		!!navigator.mediaDevices?.getUserMedia
	);
}

interface Speaker {
	jitter: JitterBuffer;
	decoder: AudioDecoder;
	lastHeardAt: number;
	/** When the next decoded frame should start, in the context's clock. Kept per speaker
	 * because two people's streams are independent and scheduling them off one cursor would
	 * make every packet of the slower one late. */
	playAt: number;
}

export class VoiceStream {
	/** True once the microphone is actually captured, so the UI can say "muted" rather than
	 * implying the other end can hear something that was never recorded. */
	capturing = false;

	/** Why there is no microphone, when there is none — shown rather than guessed at. */
	micError: string | null = null;

	private context: AudioContext | null = null;
	private stream: MediaStream | null = null;
	private encoder: AudioEncoder | null = null;
	private worklet: ScriptProcessorNode | null = null;
	private source: MediaStreamAudioSourceNode | null = null;
	private readonly speakers = new Map<string, Speaker>();

	/** Minted per call, random so two calls in quick succession cannot collide. */
	private readonly streamId = Math.floor(Math.random() * 0xffffffff) >>> 0;
	private seq = 0;
	private pending: Uint8Array[] = [];
	private running = false;

	constructor(
		private readonly publish: (payload: Uint8Array) => void,
		private readonly muted: () => boolean
	) {}

	async start(): Promise<void> {
		if (this.running) return;
		this.running = true;
		if (!voiceSupported()) {
			this.micError = 'This browser has no WebCodecs, so it cannot carry voice.';
			return;
		}
		// One context for both directions: the decoders play into it and the microphone is
		// captured through it, which is also what lets the browser pair them for echo control.
		this.context = new AudioContext({ sampleRate: SAMPLE_RATE });
		if (this.context.state === 'suspended') await this.context.resume().catch(() => undefined);
		await this.startCapture();
	}

	stop(): void {
		if (!this.running) return;
		this.running = false;
		this.capturing = false;

		// The microphone first and unconditionally. A MediaStream whose tracks are not stopped
		// leaves the browser's recording indicator lit — correctly, because the microphone
		// really is still open — and that is the most alarming way for a bug here to show.
		this.stream?.getTracks().forEach((t) => t.stop());
		this.stream = null;

		this.source?.disconnect();
		this.source = null;
		this.worklet?.disconnect();
		this.worklet = null;

		// Every decoder, not just the last: a long group call accumulates one per speaker, and
		// a closed context does not close them for you.
		closeQuietly(this.encoder);
		this.encoder = null;
		for (const speaker of this.speakers.values()) closeQuietly(speaker.decoder);
		this.speakers.clear();

		void this.context?.close().catch(() => undefined);
		this.context = null;
	}

	/** A received packet, routed to that speaker's own jitter buffer. */
	onPacket(senderId: string, packet: AudioPacket): void {
		if (!this.running || !this.context) return;
		const speaker = this.speakerFor(senderId);
		if (!speaker) return;
		speaker.lastHeardAt = Date.now();
		speaker.jitter.push(packet);
		this.drain(senderId, speaker);
	}

	// --- the microphone ------------------------------------------------------------------------

	private async startCapture(): Promise<void> {
		const context = this.context;
		if (!context) return;
		try {
			this.stream = await navigator.mediaDevices.getUserMedia({
				audio: {
					// The browser's own echo canceller, which is the work Android needs
					// MODE_IN_COMMUNICATION and an AcousticEchoCanceler for — and which some
					// devices implement badly. At a broker round trip of latency this is the
					// difference between a call and a howl.
					echoCancellation: true,
					noiseSuppression: true,
					autoGainControl: true,
					channelCount: CHANNELS,
					sampleRate: SAMPLE_RATE
				}
			});
		} catch (err) {
			// Refused, or no microphone at all. Playback still runs: hearing someone while they
			// cannot hear you is a degraded call, and a degraded call beats a dead one.
			this.micError =
				err instanceof DOMException && err.name === 'NotAllowedError'
					? 'Microphone permission was refused, so they cannot hear you.'
					: 'No microphone is available, so they cannot hear you.';
			return;
		}

		this.encoder = new AudioEncoder({
			output: (chunk) => this.onEncoded(chunk),
			error: (err) => {
				this.micError = `The encoder stopped: ${err.message}`;
				this.capturing = false;
			}
		});
		this.encoder.configure({
			codec: 'opus',
			sampleRate: SAMPLE_RATE,
			numberOfChannels: CHANNELS,
			bitrate: BITRATE
		});

		this.source = context.createMediaStreamSource(this.stream);
		// ScriptProcessorNode is deprecated and is still the only way to pull raw PCM that works
		// in every browser with WebCodecs — an AudioWorklet needs a separate module file served
		// alongside the bundle, and MediaStreamTrackProcessor is Chromium-only. It runs on the
		// audio thread for 20ms at a time, which is well within its budget.
		this.worklet = context.createScriptProcessor(1024, CHANNELS, CHANNELS);
		let carry = new Float32Array(0);
		this.worklet.onaudioprocess = (event) => {
			if (!this.running || !this.encoder) return;
			const input = event.inputBuffer.getChannelData(0);
			// Muting sends silence rather than stopping the encoder: stopping leaves a gap the
			// far end reads as an underrun and holds for, so every unmute would resume a beat
			// late. The microphone is genuinely not listened to either way.
			const samples = this.muted() ? new Float32Array(input.length) : input;

			const joined = new Float32Array(carry.length + samples.length);
			joined.set(carry);
			joined.set(samples, carry.length);

			let at = 0;
			while (joined.length - at >= FRAME_SAMPLES) {
				const frame = joined.slice(at, at + FRAME_SAMPLES);
				at += FRAME_SAMPLES;
				const data = new AudioData({
					format: 'f32-planar',
					sampleRate: SAMPLE_RATE,
					numberOfFrames: FRAME_SAMPLES,
					numberOfChannels: CHANNELS,
					timestamp: this.timestampUs,
					data: frame
				});
				this.timestampUs += (FRAME_SAMPLES * 1_000_000) / SAMPLE_RATE;
				try {
					this.encoder.encode(data);
				} finally {
					data.close();
				}
			}
			carry = joined.slice(at);
		};
		// Connected to the destination because some browsers will not run a ScriptProcessor that
		// goes nowhere. It emits nothing, so this adds no sound.
		this.source.connect(this.worklet);
		this.worklet.connect(context.destination);
		this.capturing = true;
		this.micError = null;
	}

	private timestampUs = 0;

	private onEncoded(chunk: EncodedAudioChunk): void {
		const bytes = new Uint8Array(chunk.byteLength);
		chunk.copyTo(bytes);
		this.pending.push(bytes);
		if (this.pending.length < FRAMES_PER_PACKET) return;
		const frames = this.pending;
		this.pending = [];
		try {
			this.publish(encodeAudio({ streamId: this.streamId, seq: this.seq, frames }));
		} catch {
			// A publish that fails is a packet the jitter buffer conceals. Not worth a log line
			// seventeen times a second on a broker that has dropped.
		}
		this.seq = (this.seq + 1) >>> 0;
	}

	// --- everyone else --------------------------------------------------------------------------

	private speakerFor(senderId: string): Speaker | null {
		const existing = this.speakers.get(senderId);
		if (existing) return existing;
		if (!this.context) return null;
		const speaker: Speaker = {
			jitter: new JitterBuffer(),
			lastHeardAt: Date.now(),
			playAt: 0,
			decoder: new AudioDecoder({
				output: (data) => this.play(speaker, data),
				error: () => {
					// One speaker's decoder failing is one voice lost, not the call.
					this.speakers.delete(senderId);
				}
			})
		};
		speaker.decoder.configure({
			codec: 'opus',
			sampleRate: SAMPLE_RATE,
			numberOfChannels: CHANNELS
		});
		this.speakers.set(senderId, speaker);
		return speaker;
	}

	/** Hands the jitter buffer's decision to the decoder, and reaps speakers who have gone. */
	private drain(senderId: string, speaker: Speaker): void {
		const now = Date.now();
		for (const [id, other] of this.speakers) {
			if (id !== senderId && now - other.lastHeardAt > SPEAKER_IDLE_MS) {
				closeQuietly(other.decoder);
				this.speakers.delete(id);
			}
		}
		// One slot per frame that arrived, so the buffer drains at the rate it fills. A
		// concealed slot is simply not decoded: WebCodecs has no packet-loss-concealment entry
		// point, so the gap is silence, and the schedule below leaves exactly its length.
		for (;;) {
			const frame = speaker.jitter.pull();
			if (!frame) break;
			try {
				speaker.decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: 0, data: frame }));
			} catch {
				break;
			}
		}
	}

	/**
	 * Plays one decoded frame, scheduled rather than fired immediately.
	 *
	 * Each speaker keeps its own cursor: two people's streams are independent, and scheduling
	 * both off one clock would make every packet of the slower one late. When a cursor falls
	 * behind the context — which is what a stall looks like — it is reset forward, because
	 * playing a backlog at full speed is worse than dropping it.
	 */
	private play(speaker: Speaker, data: AudioData): void {
		const context = this.context;
		if (!context) {
			data.close();
			return;
		}
		try {
			const frames = data.numberOfFrames;
			const buffer = context.createBuffer(CHANNELS, frames, SAMPLE_RATE);
			const channel = new Float32Array(frames);
			data.copyTo(channel, { planeIndex: 0, format: 'f32-planar' });
			buffer.copyToChannel(channel, 0);

			const source = context.createBufferSource();
			source.buffer = buffer;
			// The graph sums every speaker for us. This is the whole of "mixing" in a browser.
			source.connect(context.destination);

			const now = context.currentTime;
			if (speaker.playAt < now) speaker.playAt = now + 0.02;
			source.start(speaker.playAt);
			speaker.playAt += frames / SAMPLE_RATE;
		} catch {
			// A frame that will not play is a frame missed, not a call ended.
		} finally {
			data.close();
		}
	}
}

function closeQuietly(codec: { close(): void; state?: string } | null): void {
	try {
		if (codec && codec.state !== 'closed') codec.close();
	} catch {
		// Already closed, or closing.
	}
}
