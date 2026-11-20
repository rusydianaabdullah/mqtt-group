import { db, type Outbox } from '$lib/data/db/schema';

/**
 * The durable side of publishing. Nothing in this app calls the MQTT client directly: a message
 * becomes a row here first, and only stops existing once the broker has acknowledged it. That
 * is what makes "I sent that while the lift was between floors" survive a dead network, a
 * closed tab, or a browser deciding to discard the page.
 *
 * Rows are deleted on success rather than marked sent, so the table stays the size of the
 * backlog rather than the size of the history.
 */

export const MAX_RETRIES = 12;

/** 1s, 2s, 4s … capped at five minutes, which is where a phone's radio economics land. */
export function backoffMillis(attempt: number): number {
	return Math.min(1000 * 2 ** Math.min(attempt - 1, 20), 5 * 60_000);
}

export async function enqueue(row: {
	groupId: string;
	topic: string;
	payload: Uint8Array;
	qos?: number;
	retained?: boolean;
	refMessageId?: string | null;
	/**
	 * How long this row is worth publishing, in milliseconds, or omitted to keep it until it
	 * goes. Only call signalling sets it; see `Outbox.expiresAt` for why the outbox's usual
	 * promise is the wrong one there.
	 */
	expiresInMillis?: number;
}): Promise<number> {
	return db.outbox.add({
		groupId: row.groupId,
		topic: row.topic,
		payload: row.payload,
		qos: row.qos ?? 1,
		retained: row.retained ?? false,
		createdAt: Date.now(),
		retryCount: 0,
		status: 'QUEUED',
		nextAttemptAt: 0,
		refMessageId: row.refMessageId ?? null,
		expiresAt: row.expiresInMillis === undefined ? null : Date.now() + row.expiresInMillis
	});
}

/**
 * Enqueues a payload for a *retained latest-value* topic, replacing anything already queued for
 * it.
 *
 * Location is the only such topic, and it inverts the outbox's usual promise. Everywhere else a
 * backlog is the point: a chat message written offline must still arrive an hour later. A
 * position is the opposite — after a week away, publishing seven days of where-I-was in
 * sequence would spend the radio replaying a journey nobody asked for, and every one of those
 * publishes is immediately overwritten by the next, since the topic is retained and holds only
 * the last value.
 *
 * This is the plan's "drop stale loc rows" rule, done by superseding rather than by a timeout:
 * it needs no threshold to tune, and it cannot get the answer wrong — the row it throws away is
 * one it is replacing in the same breath.
 *
 * SENDING rows are left alone: that publish is already in flight, and deleting its row would
 * leave the pump holding a completion for something no longer in the table.
 */
export async function enqueueLatest(row: {
	groupId: string;
	topic: string;
	payload: Uint8Array;
	qos?: number;
	retained?: boolean;
}): Promise<number> {
	await db.outbox
		.where('topic')
		.equals(row.topic)
		.filter((r) => r.status === 'QUEUED')
		.delete();
	return enqueue({ ...row, qos: row.qos ?? 0, retained: row.retained ?? true });
}

/** Rows that are ready to try, oldest first. Rows whose moment has passed are not among them. */
export async function due(now = Date.now()): Promise<Outbox[]> {
	return db.outbox
		.where('status')
		.equals('QUEUED')
		.filter((r) => r.nextAttemptAt <= now && (r.expiresAt == null || r.expiresAt > now))
		.sortBy('createdAt');
}

/**
 * Discards rows whose moment has passed. `due()` already skips them; this is what keeps them
 * from accumulating over a long stretch with the tab closed.
 *
 * `expiresAt == null` rather than `=== null`, deliberately: rows written before this field
 * existed have it `undefined`, and they are exactly the rows that must never be swept.
 */
export async function dropExpired(now = Date.now()): Promise<number> {
	return db.outbox.filter((r) => r.expiresAt != null && r.expiresAt <= now).delete();
}

/** The row actually left the device — the point `Message.state` "SENT" is meant to describe, as
 * opposed to merely being queued for it. */
export async function onPublished(row: Outbox): Promise<void> {
	await db.outbox.delete(row.id);
	if (row.refMessageId) await db.messages.update(row.refMessageId, { state: 'SENT' });
}

/**
 * Backs a failed row off and puts it back. After `MAX_RETRIES` it is parked as FAILED and
 * surfaced in the UI, because silently retrying something forever is how a message gets lost
 * without anyone noticing — and, if this row is a chat message, the same word is what the
 * message's own bubble shows.
 */
export async function onFailed(row: Outbox, now = Date.now()): Promise<void> {
	const attempt = row.retryCount + 1;
	if (attempt >= MAX_RETRIES) {
		await db.outbox.update(row.id, { retryCount: attempt, status: 'FAILED', nextAttemptAt: now });
		if (row.refMessageId) await db.messages.update(row.refMessageId, { state: 'FAILED' });
	} else {
		await db.outbox.update(row.id, {
			retryCount: attempt,
			status: 'QUEUED',
			nextAttemptAt: now + backoffMillis(attempt)
		});
	}
}

/** Puts a parked row back in the queue, for the retry button on a failed message. */
export async function retryRow(id: number): Promise<void> {
	await db.outbox.update(id, { status: 'QUEUED', retryCount: 0, nextAttemptAt: 0 });
}

export async function failedCount(): Promise<number> {
	return db.outbox.where('status').equals('FAILED').count();
}

export async function pendingCount(): Promise<number> {
	return db.outbox.where('status').equals('QUEUED').count();
}

/** How long until the earliest backed-off row is due, or null when there is nothing waiting. */
export async function nextDueIn(now = Date.now()): Promise<number | null> {
	const rows = await db.outbox.where('status').equals('QUEUED').toArray();
	if (rows.length === 0) return null;
	const soonest = Math.min(...rows.map((r) => r.nextAttemptAt));
	return Math.max(0, soonest - now);
}
