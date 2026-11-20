import { db } from '$lib/data/db/schema';
import { groupKeyBytes, publish, publishVoice } from '$lib/data/repo/groupRepo';
import { audioTopic, callTopic } from '$lib/mqtt/topics';
import type { Call, CallMode } from '$lib/mqtt/callMessages';
import type { AudioPacket } from '$lib/mqtt/audioMessages';
import { notifyCall } from '$lib/push/pushSender';
import { IDLE, TTL_SEC, callIdOf, reduce, ttlFor, type CallState, type Event } from './callMachine';
import { Ringer } from './ringer';
import { VoiceStream, voiceSupported } from './voice';
import { notifyIncomingCall } from '$lib/ui/notify';

/**
 * Everything a call needs that `callMachine` refuses to know: Dexie, the outbox, a clock, an
 * `<audio>` element and who this device is.
 *
 * The split is the point. Which message a red button sends, and whether a second offer rings or
 * is refused, are decided by a pure function with tests. This only carries events to it and does
 * what it says — so the parts that cannot be tested headlessly are also the parts with no
 * decisions in them. The Kotlin twin is `CallEngine.kt`.
 *
 * **This works only while a tab is open**, which is the same limitation everything else in this
 * app has and is stated in the UI rather than worked around. A closed tab has no MQTT client, so
 * it learns about a call from a push that can only say "missed" — see `service-worker.ts`.
 */
class Calls {
	/** The one call this device can be on. A device has one speaker and one person holding it. */
	state = $state<CallState>(IDLE);

	/** The other person's name, once the member list has been consulted. Null until then, which
	 * is briefly the case even for a call that arrives while the tab is open. */
	peerName = $state<string | null>(null);

	/** Which group's topic this call travels on. A call belongs to a group because that is whose
	 * key it is encrypted with; the wire format never says so, since the topic already does. */
	private groupId: string | null = null;

	private selfId: string | null = null;
	private timer: ReturnType<typeof setTimeout> | null = null;
	private readonly ringer = new Ringer();

	/** The "incoming call" notification, while one is up. Taken down on every path out, because
	 * a ring left on screen after the call is over is worse than none at all. */
	private incomingNotification: Notification | null = null;

	/** The microphone switch, which is this device's alone and never travels. */
	muted = $state(false);

	/** Why the other side cannot hear us, when they cannot. Null when all is well. */
	micError = $state<string | null>(null);

	/**
	 * How long the broker took to carry one message there and back, in milliseconds, or null
	 * before it is known.
	 *
	 * Measured from publishing the offer to receiving the callee's `ring` — a genuine round trip
	 * through the broker, timed entirely on this device's own clock, so it needs no agreement
	 * about the time of day.
	 *
	 * It exists because "the call is delayed" has two very different causes and the fixes differ
	 * completely. Buffering is ours to cut. The path to the broker is not: a broker on another
	 * continent costs a few hundred milliseconds no tuning here will recover, and the only
	 * answer is a closer broker. This says which one you are looking at.
	 */
	roundTripMillis = $state<number | null>(null);

	private offerAt = 0;

	/** The voice of the current call, or null when there is no call or no voice in it. */
	private voice: VoiceStream | null = null;

	toggleMute(): void {
		this.muted = !this.muted;
	}

	/**
	 * A received voice packet, straight into that speaker's jitter buffer — no await, no Dexie,
	 * because this happens seventeen times a second per speaker and every hop is latency on a
	 * path that is already the weak point.
	 */
	onVoice(senderId: string, packet: AudioPacket): void {
		this.voice?.onPacket(senderId, packet);
	}

	/**
	 * Serialises the whole reduce-then-act cycle.
	 *
	 * JavaScript is single-threaded, but every step in here awaits — Dexie reads, sealing,
	 * enqueuing — so two events arriving close together really can interleave between a read of
	 * `state` and the write that follows it. Chaining onto one promise is the cheapest way to
	 * make the cycle atomic with respect to itself.
	 */
	private queue: Promise<unknown> = Promise.resolve();

	/**
	 * Start a call: to one member, or to the whole group when `peerId` is null.
	 *
	 * Defaults to a buzz, which is what the UI offers. Voice over somebody's broker is a steady
	 * 36 kbps each way for as long as people talk, and a buzz answers most of what a call was
	 * being used for at the cost of a handful of messages. `'audio'` still works and nothing
	 * sends it — see plan.md, phase 11.
	 */
	dial(groupId: string, peerId: string | null, mode: CallMode = 'buzz'): void {
		const callId = crypto.randomUUID();
		this.groupId = groupId;
		// A group call has no single name to show; the overlay counts the room instead, which is
		// the question a group call actually asks.
		if (peerId !== null) void this.resolveName(groupId, peerId);
		else this.peerName = null;
		this.enqueue(groupId, { type: 'dial', callId, peerId, mode }, ttlFor(mode));
		// Wakes a callee who is not currently connected. Fired after, and never waited on: a
		// call whose push failed still reaches someone who was online all along.
		void this.selfUserId().then((self) => {
			if (self) notifyCall(groupId, self, callId, mode);
		});
	}

	accept(): void {
		this.fire({ type: 'accept' });
	}

	/** Decline, cancel or hang up — whichever this call's state makes it. */
	hangup(): void {
		this.fire({ type: 'hangup' });
	}

	dismiss(): void {
		this.fire({ type: 'dismiss' });
	}

	/**
	 * A call message arrived on a group's topic and opened under its key.
	 *
	 * `senderId` is the envelope's authenticated sender, never anything read out of the body.
	 * That is what stops a member publishing a ring on another member's behalf, and it is the
	 * same rule profile announcements follow.
	 */
	onMessage(groupId: string, senderId: string, message: Call): void {
		if (message.kind === 'offer') {
			this.groupId = groupId;
			void this.resolveName(groupId, senderId);
		} else if (groupId !== this.groupId) {
			// Signalling for a call in a different group than the one we are on. The machine
			// would drop it on the call id anyway; stopping here saves the round trip.
			return;
		}
		this.enqueue(
			groupId,
			{ type: 'received', senderId, message },
			message.kind === 'offer' ? message.ttlSec : undefined
		);
	}

	private fire(event: Event): void {
		const groupId = this.groupId;
		if (!groupId) return;
		this.enqueue(groupId, event, undefined);
	}

	private enqueue(groupId: string, event: Event, ttlSec: number | undefined): void {
		this.queue = this.queue
			.then(() => this.apply(groupId, event, ttlSec))
			.catch((err) => console.warn('call event failed', err));
	}

	/**
	 * The one path that changes state: reduce, publish what the machine asked for, then make the
	 * device match the new state.
	 */
	private async apply(groupId: string, event: Event, ttlSec: number | undefined): Promise<void> {
		const self = await this.selfUserId();
		if (!self) return;

		const before = this.state;
		// Started before the publish rather than after, so the measurement covers the whole path
		// the offer actually travels — a person waiting for a call to connect waits for all of it.
		if (event.type === 'dial') {
			this.offerAt = performance.now();
			this.roundTripMillis = null;
		}

		const { state: after, publish: outgoing } = reduce(before, event, self, Date.now());
		if (after === before && !outgoing) return;

		this.state = after;
		if (
			before.phase === 'outgoing' &&
			!before.reached &&
			after.phase === 'outgoing' &&
			after.reached &&
			this.offerAt !== 0
		) {
			this.roundTripMillis = Math.round(performance.now() - this.offerAt);
		}
		if (outgoing) await this.send(groupId, outgoing);

		// A callee tells the caller it is ringing only once it really is — after the state has
		// moved and the ringer has been told — because the whole value of that message is that it
		// reports a fact about this device rather than an intention.
		const startedRinging = before.phase !== 'incoming' && after.phase === 'incoming';

		this.react(before, after, ttlSec);

		if (startedRinging && after.phase === 'incoming') {
			await this.send(groupId, { kind: 'ring', callId: after.callId });
		}
	}

	/** Makes the device match the state: ring or stop, arm or cancel the timer. Nothing here
	 * decides anything. */
	private react(before: CallState, after: CallState, ttlSec: number | undefined): void {
		switch (after.phase) {
			case 'incoming':
				this.ringer.start();
				// Both, deliberately: a tab that has never been clicked may refuse to play audio,
				// and a person looking at another tab will not see the overlay. They fail
				// independently, so neither is relied on alone.
				this.incomingNotification = notifyIncomingCall(
					this.peerName ?? 'Someone in this group',
					'mode' in after && after.mode === 'buzz'
				);
				this.arm(after.callId, ttlSec ?? TTL_SEC);
				break;
			case 'outgoing':
				// No ringback tone in this phase: that is media, and media is phase 9.
				if (before.phase !== 'outgoing') this.arm(after.callId, ttlSec ?? TTL_SEC);
				break;
			case 'connected':
				this.ringer.stop();
				this.dismissNotification();
				this.disarm();
				// Once, on the transition. Answering does not happen twice, but this runs on
				// other events too and starting a second microphone would be a memorable way to
				// discover that.
				// A buzz has no voice in it, which is the entire point: no microphone, no
				// stream, no bandwidth beyond the signalling.
				if (before.phase !== 'connected' && after.mode !== 'buzz') void this.startVoice();
				break;
			case 'ended':
				this.ringer.stop();
				this.dismissNotification();
				this.disarm();
				this.stopVoice();
				break;
			case 'idle':
				this.ringer.stop();
				this.dismissNotification();
				this.disarm();
				this.stopVoice();
				this.peerName = null;
				this.groupId = null;
				break;
		}
	}

	/**
	 * Arms the one timer a call has, counted from *now* rather than from any timestamp on the
	 * wire — see `OfferCall.ttlSec`. The call id rides along so a timer that fires after its call
	 * has been replaced can be recognised and ignored by the machine.
	 */
	private arm(callId: string, ttlSec: number): void {
		this.disarm();
		this.timer = setTimeout(() => {
			if (callIdOf(this.state) === callId) this.fire({ type: 'timeout', callId });
		}, ttlSec * 1000);
	}

	/**
	 * Brings up the microphone and the speakers for an answered call.
	 *
	 * Everything the stream needs is resolved once rather than per packet: the group, its topic
	 * and our own id. At seventeen packets a second a Dexie read per packet is not a small waste.
	 */
	private async startVoice(): Promise<void> {
		const groupId = this.groupId;
		if (!groupId) return;
		this.stopVoice();
		if (!voiceSupported()) {
			this.micError = 'This browser cannot carry voice, but the call still connects.';
			return;
		}
		const self = await this.selfUserId();
		const group = await db.groups.get(groupId);
		if (!self || !group) return;
		const keyBytes = await groupKeyBytes(groupId, group.keyEpoch);
		if (!keyBytes) return;
		const { importKey } = await import('$lib/crypto/aead');
		const key = await importKey(keyBytes);

		const topic = audioTopic(group.topicPrefix, groupId, self);
		// The envelope counter lives in memory for the life of the call and is never written
		// back. Nothing reads it — /aud is exempt from the replay check exactly as /loc is —
		// and the GCM nonce is drawn fresh per payload rather than derived from the counter, so
		// a counter that restarts is harmless. See plan.md, phase 9.
		let counter = 0;
		const stream = new VoiceStream(
			(payload) =>
				void publishVoice(group.brokerId, topic, key, group.keyEpoch, self, counter++, payload),
			() => this.muted
		);
		this.voice = stream;
		await stream.start();
		this.micError = stream.micError;
		// The call may have ended while the microphone dialog was open, which is exactly long
		// enough for it to happen. Leaving it running would hold the recording indicator lit.
		if (this.state.phase !== 'connected') this.stopVoice();
	}

	private stopVoice(): void {
		this.voice?.stop();
		this.voice = null;
		this.muted = false;
		this.micError = null;
	}

	private dismissNotification(): void {
		try {
			this.incomingNotification?.close();
		} catch {
			// Already closed, or a browser that refuses. Either way it is not on screen.
		}
		this.incomingNotification = null;
	}

	private disarm(): void {
		if (this.timer !== null) clearTimeout(this.timer);
		this.timer = null;
	}

	/**
	 * Onto the outbox like everything else in this app, and with a deadline, which nothing else
	 * in this app has. A ring that survives an hour with the tab closed is a device announcing a
	 * call that is long over — see `Outbox.expiresAt`.
	 */
	private async send(groupId: string, message: Call): Promise<void> {
		const { encodeCall } = await import('$lib/mqtt/callMessages');
		const sent = await publish(groupId, callTopic, encodeCall(message), {
			qos: 1,
			retained: false,
			expiresInMillis: TTL_SEC * 1000
		});
		if (sent === null) console.warn(`no key for ${groupId}; call message dropped`);
	}

	private async selfUserId(): Promise<string | null> {
		this.selfId ??= (await db.identity.get(0))?.userId ?? null;
		return this.selfId;
	}

	/**
	 * Looks the other person's name up from the member list.
	 *
	 * A call can start ringing before this resolves, which is deliberate — ringing late so the
	 * screen can show a name is worse than ringing now and filling it in. And when it does
	 * appear it is a *claim*, not a proof: a shared group key gives no per-sender authenticity,
	 * so any member could have sent this offer under any member's id.
	 */
	private async resolveName(groupId: string, userId: string): Promise<void> {
		this.peerName = (await db.members.get([groupId, userId]))?.displayName ?? null;
	}
}

export const calls = new Calls();
