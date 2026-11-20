import { db } from '$lib/data/db/schema';
import { importKey } from '$lib/crypto/aead';
import { open, peek } from '$lib/crypto/envelope';
import { deleteBlob, putBlob, putBlobAs } from '$lib/data/blob/blobStore';
import {
	announceSelf,
	applyLocationSettings,
	forgetGroup,
	groupKeyBytes,
	republishLocationSettings,
	sendDeliveredReceipt
} from '$lib/data/repo/groupRepo';
import { decodeChat } from './chatMessages';
import { decodeControl } from './controlMessages';
import { decodeLocation } from './locationMessages';
import { everythingTopic, groupIdFor, kindOf } from './topics';
import { decodeCall } from './callMessages';
import { decodeAudio } from './audioMessages';
import { calls } from '$lib/call/calls.svelte';
import { sha256Hex } from '$lib/util/bytes';
import { notifyMessage } from '$lib/ui/notify';
import type { ConnectionPool, RoutedMessage } from './connectionPool';

/**
 * The receiving half: subscribes to every group this device belongs to, opens what arrives, and
 * applies it.
 *
 * Everything here is defensive. A payload from a broker is attacker-controlled until it has
 * opened under a group key, and even then it only proves *someone in the group* sent it — a
 * shared key cannot tell two members apart. So authority is checked against the local member
 * list, replays are dropped by counter, and anything unrecognised is ignored rather than guessed
 * at.
 *
 * Only the leader tab runs one of these. A follower sees the results through the database.
 */
export class GroupSync {
	private off: (() => void)[] = [];
	private subscribed = new Set<string>();
	/** The group whose chat is on screen right now, so its arrivals do not raise a notification. */
	activeGroupId: string | null = null;
	private onChange: (() => void) | null = null;

	constructor(private readonly pool: ConnectionPool) {}

	start(notify?: () => void): void {
		if (this.off.length > 0) return;
		this.onChange = notify ?? null;
		this.off.push(this.pool.onMessage((m) => void this.handle(m)));
		this.off.push(
			this.pool.onStateChange(() => {
				// Re-subscribes on every reconnect, not only the first time. Whether a broker
				// actually kept our subscriptions across a disconnect is not something to gamble
				// on: re-issuing them is a harmless no-op when they were already there, and the
				// only thing that saves us when they were not. Without it a reconnect can leave
				// the app quietly unsubscribed from everything, looking connected while receiving
				// nothing.
				void this.resubscribe();
			})
		);
		void this.resubscribe();
	}

	stop(): void {
		for (const off of this.off) off();
		this.off = [];
		this.subscribed.clear();
	}

	/** Called after a group is created or joined, so it is subscribed without a reconnect. */
	async resubscribe(): Promise<void> {
		const groups = await db.groups.toArray();
		// A broker that dropped forgets its filters, so its entries go too and the next connect
		// re-issues exactly the ones that connection lost and no others.
		for (const handle of [...this.subscribed]) {
			const brokerId = handle.split(' ')[0];
			if (this.pool.states.get(brokerId)?.kind !== 'connected') this.subscribed.delete(handle);
		}
		for (const group of groups) {
			if (this.pool.states.get(group.brokerId)?.kind !== 'connected') continue;
			const handle = `${group.brokerId} ${group.groupId}`;
			if (this.subscribed.has(handle)) continue;
			this.subscribed.add(handle);
			await this.pool
				.peek(group.brokerId)
				?.subscribe(everythingTopic(group.topicPrefix, group.groupId));
		}
	}

	private async handle(message: RoutedMessage): Promise<void> {
		const header = peek(message.payload);
		if (!header) return;
		const groupId = groupIdFor(message.topic);
		if (!groupId) return;
		if (!(await db.groups.get(groupId))) return;
		const identity = await db.identity.get(0);
		if (!identity) return;

		const senderId = header.senderId;
		// Our own messages come back to us from the broker; they are already in the database.
		if (senderId === identity.userId) return;

		const keyBytes = await groupKeyBytes(groupId, header.epoch);
		if (!keyBytes) return;
		const envelope = await open(await importKey(keyBytes), message.payload);
		if (!envelope) return;

		const kind = kindOf(message.topic);
		if (!kind) return;
		const known = await db.members.get([groupId, senderId]);

		// Attachments are exempt: they are content-addressed and harmless to apply twice, and
		// MQTT guarantees delivery order only *within* a topic — so a photo's bytes on /att can
		// legitimately arrive after its reference on /msg already advanced this sender's counter.
		// Checking them against a shared high-water mark makes real photos look like replays and
		// drops them forever. /set is exempt for the same reason /loc is: the broker replays the
		// retained copy on every resubscribe, under the counter it was published with.
		if (kind === 'msg' || kind === 'ctl') {
			const last = kind === 'msg' ? (known?.lastCounterMsg ?? 0) : (known?.lastCounterCtl ?? 0);
			// A counter that does not advance is a replay of something we have already applied.
			if (known && Number(envelope.counter) <= last) return;
		}

		switch (kind) {
			// Settings carry the same control bodies as /ctl; only their durability differs.
			case 'ctl':
			case 'set':
				await this.applyControl(groupId, senderId, envelope.plaintext);
				break;
			case 'msg':
				await this.applyMessage(groupId, senderId, Number(envelope.counter), envelope.plaintext);
				break;
			case 'att':
				await this.applyAttachment(groupId, message.topic, envelope.plaintext);
				break;
			case 'loc':
				await this.applyLocation(groupId, senderId, envelope.plaintext);
				break;
			// Handed straight over without touching Dexie: a call is entirely in-memory state
			// that stops existing when it stops, and `calls` is the only thing that knows
			// whether this one is ours.
			case 'call': {
				const call = decodeCall(envelope.plaintext);
				if (call) calls.onMessage(groupId, senderId, call);
				break;
			}
			// Voice. Exempt from the counter check above for the same reason /loc is, and handed
			// over without touching Dexie — seventeen packets a second per speaker is not a rate
			// at which to consult anything.
			case 'aud': {
				const packet = decodeAudio(envelope.plaintext);
				if (packet) calls.onVoice(senderId, packet);
				break;
			}
			// presence is subscribed and deliberately not acted on yet. Dropping what we do not
			// understand is correct: an older build should ignore a newer one's traffic, not
			// guess at it.
			default:
				break;
		}

		if (kind === 'msg' || kind === 'ctl') {
			const field = kind === 'msg' ? 'lastCounterMsg' : 'lastCounterCtl';
			await db.members
				.where('[groupId+userId]')
				.equals([groupId, senderId])
				.modify({ [field]: Number(envelope.counter), lastSeenAt: Date.now() });
		}
		this.onChange?.();
	}

	private async applyControl(
		groupId: string,
		senderId: string,
		plaintext: Uint8Array
	): Promise<void> {
		const control = decodeControl(plaintext);
		if (!control) return;

		switch (control.kind) {
			case 'profile': {
				// The sender id comes from the authenticated envelope header, not from the body,
				// so nobody can announce a profile on someone else's behalf.
				const existing = await db.members.get([groupId, senderId]);
				const avatarBlobId = control.avatar
					? await putBlob(control.avatar)
					: (existing?.avatarBlobId ?? null);
				if (existing?.avatarBlobId && control.avatar) await deleteBlob(existing.avatarBlobId);
				await db.members.put({
					groupId,
					userId: senderId,
					displayName: control.displayName,
					avatarBlobId,
					isAdmin: existing?.isAdmin ?? false,
					lastSeenAt: Date.now(),
					isRemoved: false,
					lastCounterMsg: existing?.lastCounterMsg ?? 0,
					lastCounterCtl: existing?.lastCounterCtl ?? 0
				});
				// A stranger just announced themselves, and has no way to know who was already
				// here — a join only carries the joiner's own profile. Answering with ours is how
				// an admin's name reaches a member who joined after them, instead of sitting as a
				// blank until the admin happens to edit their profile. Bounded to one reply per
				// newly-seen sender: the next message from someone we already know skips this.
				if (!existing) {
					await announceSelf(groupId);
					await republishLocationSettings(groupId);
				}
				break;
			}

			case 'kick': {
				if (!(await isAdmin(groupId, senderId))) return;
				const identity = await db.identity.get(0);
				if (!identity) return;
				if (control.targetUserId === identity.userId) {
					// Kicked ourselves out: drop the group and its key rather than sitting in one
					// we have been asked to leave.
					await forgetGroup(groupId);
				} else {
					await db.members
						.where('[groupId+userId]')
						.equals([groupId, control.targetUserId])
						.modify({ isRemoved: true });
				}
				break;
			}

			case 'leave':
				await db.members
					.where('[groupId+userId]')
					.equals([groupId, senderId])
					.modify({ isRemoved: true });
				break;

			case 'delete':
				// Admin only, checked the same way a kick is: the envelope proves a member sent
				// this, never which member claimed what about themselves.
				if (!(await isAdmin(groupId, senderId))) return;
				await forgetGroup(groupId);
				break;

			case 'locset':
				if (!(await isAdmin(groupId, senderId))) return;
				await applyLocationSettings(groupId, control);
				break;

			case 'delivered':
				await this.applyReceipt(groupId, senderId, control.messageId, 'DELIVERED');
				break;
			case 'read':
				await this.applyReceipt(groupId, senderId, control.messageId, 'READ');
				break;
		}
	}

	/** READ implies DELIVERED, so a delivery receipt that arrives after a read one — out-of-order
	 * delivery, or simply a slower link — must never downgrade it back. */
	private async applyReceipt(
		groupId: string,
		senderId: string,
		messageId: string,
		state: string
	): Promise<void> {
		const existing = await db.receipts.get([messageId, senderId]);
		if (existing?.state === 'READ') return;
		await db.receipts.put({ messageId, groupId, userId: senderId, state, at: Date.now() });
	}

	private async applyMessage(
		groupId: string,
		senderId: string,
		counter: number,
		plaintext: Uint8Array
	): Promise<void> {
		const payload = decodeChat(plaintext);
		if (!payload) return;

		// The reference is where the receiver learns what the bytes *are*. The `/att` topic carries
		// only the bytes, so an attachment row written by the arrival of those bytes has an empty
		// mime — and an empty mime on a voice note is the difference between an `<audio>` element
		// that plays and one that silently refuses, since this app records WebM and Android
		// records MP4. Either half may land first, so both halves fill in what they know.
		if (payload.kind === 'attachment') {
			const existing = await db.attachments.get(payload.blobId);
			await db.attachments.put({
				blobId: payload.blobId,
				groupId,
				mime: payload.mime,
				sizeBytes: existing?.sizeBytes ?? payload.sizeBytes,
				sha256: existing?.sha256 ?? payload.sha256,
				createdAt: existing?.createdAt ?? Date.now()
			});
		}

		await db.messages.put({
			id: payload.id,
			groupId,
			senderId,
			type: payload.kind === 'text' ? 'text' : payload.attachmentKind,
			body: payload.kind === 'text' ? payload.body : payload.caption,
			blobId: payload.kind === 'text' ? null : payload.blobId,
			createdAt: Date.now(),
			state: 'RECEIVED',
			counter,
			read: false,
			replyToId: payload.replyToId
		});

		// "Got it" goes out immediately, before anyone has opened the chat — that is what a
		// second grey check reports. The blue one is a separate, later receipt from markRead.
		await sendDeliveredReceipt(groupId, payload.id);

		// Not for the chat already on screen: the person is looking straight at it.
		if (this.activeGroupId !== groupId) {
			const group = await db.groups.get(groupId);
			const sender = (await db.members.get([groupId, senderId]))?.displayName || 'Someone';
			const preview =
				payload.kind === 'text'
					? payload.body
					: payload.attachmentKind === 'audio'
						? 'Voice message'
						: (payload.caption ?? 'Photo');
			if (group) notifyMessage(group.name, sender, preview);
		}
	}

	/**
	 * Someone else's position.
	 *
	 * Not counter-checked, and exempted from that gate above for the same reason attachments are
	 * though by a different route: `loc` is published QoS 0 and *retained*, so the broker replays
	 * the last one on every subscribe. Under a shared high-water mark that replay is
	 * indistinguishable from a replay attack, and the map would then sit empty until the person's
	 * next interval tick — exactly the wait retaining it was meant to remove.
	 */
	private async applyLocation(
		groupId: string,
		senderId: string,
		plaintext: Uint8Array
	): Promise<void> {
		const payload = decodeLocation(plaintext);
		if (!payload) return;
		// A retained fix arrives again on every resubscribe. Storing a duplicate each time would
		// inflate the trail into a stack of identical points at one spot — and the last fix from
		// someone who has stopped sharing would grow a new copy on every reconnect, forever.
		const latest = await db.locations
			.where('[groupId+userId+capturedAt]')
			.between([groupId, senderId, -Infinity], [groupId, senderId, Infinity])
			.last();
		if (latest && latest.capturedAt >= payload.capturedAt) return;
		await db.locations.add({ groupId, userId: senderId, ...payload });
	}

	/**
	 * The bytes for an attachment, on their own topic. The topic ends in `.../att/<blobId>`; the
	 * id is not carried in the plaintext because the topic already names it and repeating it
	 * would just be one more thing to check agrees.
	 */
	private async applyAttachment(
		groupId: string,
		topic: string,
		plaintext: Uint8Array
	): Promise<void> {
		const blobId = topic.slice(topic.lastIndexOf('/') + 1);
		await putBlobAs(blobId, plaintext);
		// The reference message carries the real mime type and may already have arrived, so it is
		// preserved rather than overwritten with the empty string this side has no way to fill.
		const existing = await db.attachments.get(blobId);
		await db.attachments.put({
			blobId,
			groupId,
			mime: existing?.mime ?? '',
			sizeBytes: plaintext.length,
			sha256: await sha256Hex(plaintext),
			createdAt: existing?.createdAt ?? Date.now()
		});
		// The chat message referencing this blob may already be in the database with nothing to
		// back it. Writing it again does not change it, but it does move the row's version, which
		// is what a live query watches.
		const message = await db.messages.where('blobId').equals(blobId).first();
		if (message) await db.messages.put(message);
	}
}

async function isAdmin(groupId: string, userId: string): Promise<boolean> {
	return (await db.members.get([groupId, userId]))?.isAdmin === true;
}
