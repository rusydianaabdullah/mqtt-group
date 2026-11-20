import { beforeEach, describe, expect, it } from 'vitest';
import {
	backoffMillis,
	dropExpired,
	due,
	enqueue,
	enqueueLatest,
	failedCount,
	MAX_RETRIES,
	nextDueIn,
	onFailed,
	onPublished,
	retryRow
} from './outboxRepo';
import { db } from '$lib/data/db/schema';
import { utf8 } from '$lib/util/bytes';

const row = (over: Partial<Parameters<typeof enqueue>[0]> = {}) => ({
	groupId: 'g1',
	topic: 'familygroup/g1/msg',
	payload: utf8('payload'),
	...over
});

beforeEach(async () => {
	await db.outbox.clear();
	await db.messages.clear();
});

describe('outbox', () => {
	it('queues and hands back what is due, oldest first', async () => {
		await enqueue(row({ topic: 'a' }));
		await enqueue(row({ topic: 'b' }));
		expect((await due()).map((r) => r.topic)).toEqual(['a', 'b']);
	});

	it('holds back a row that is not due yet', async () => {
		const id = await enqueue(row());
		await db.outbox.update(id, { nextAttemptAt: Date.now() + 60_000 });
		expect(await due()).toHaveLength(0);
	});

	it('deletes on success rather than marking sent, so the table is the backlog', async () => {
		await db.messages.add({
			id: 'm1',
			groupId: 'g1',
			senderId: 'u1',
			type: 'text',
			body: 'hi',
			blobId: null,
			createdAt: 0,
			state: 'PENDING',
			counter: 1,
			read: false,
			replyToId: null
		});
		const id = await enqueue(row({ refMessageId: 'm1' }));
		await onPublished((await db.outbox.get(id))!);
		expect(await db.outbox.count()).toBe(0);
		expect((await db.messages.get('m1'))!.state).toBe('SENT');
	});

	it('backs a failure off and eventually parks it, rather than retrying forever', async () => {
		const id = await enqueue(row());
		for (let i = 0; i < MAX_RETRIES; i++) {
			await onFailed((await db.outbox.get(id))!);
		}
		const parked = (await db.outbox.get(id))!;
		expect(parked.status).toBe('FAILED');
		expect(await failedCount()).toBe(1);
		// Parked is not gone: a failed message is one the person can still retry.
		await retryRow(id);
		expect((await db.outbox.get(id))!.status).toBe('QUEUED');
	});

	it('marks the chat message failed too, since that is what its bubble shows', async () => {
		await db.messages.add({
			id: 'm2',
			groupId: 'g1',
			senderId: 'u1',
			type: 'text',
			body: 'hi',
			blobId: null,
			createdAt: 0,
			state: 'PENDING',
			counter: 1,
			read: false,
			replyToId: null
		});
		const id = await enqueue(row({ refMessageId: 'm2' }));
		for (let i = 0; i < MAX_RETRIES; i++) await onFailed((await db.outbox.get(id))!);
		expect((await db.messages.get('m2'))!.state).toBe('FAILED');
	});

	it('doubles the backoff and caps it at five minutes', () => {
		expect(backoffMillis(1)).toBe(1000);
		expect(backoffMillis(2)).toBe(2000);
		expect(backoffMillis(3)).toBe(4000);
		expect(backoffMillis(20)).toBe(300_000);
		expect(backoffMillis(99)).toBe(300_000);
	});

	it('supersedes a queued position rather than queueing a second one', async () => {
		// A week offline must not become a week of where-I-was replayed in sequence. Every one
		// of those publishes would overwrite the last anyway — the topic is retained.
		const topic = 'familygroup/g1/loc/u1';
		await enqueueLatest({ groupId: 'g1', topic, payload: utf8('old') });
		await enqueueLatest({ groupId: 'g1', topic, payload: utf8('new') });
		const rows = await due();
		expect(rows).toHaveLength(1);
		expect(rows[0].payload).toEqual(utf8('new'));
		expect(rows[0].qos).toBe(0);
		expect(rows[0].retained).toBe(true);
	});

	it('leaves a chat backlog alone when a position supersedes', async () => {
		await enqueue(row({ topic: 'familygroup/g1/msg' }));
		await enqueueLatest({ groupId: 'g1', topic: 'familygroup/g1/loc/u1', payload: utf8('p') });
		expect(await due()).toHaveLength(2);
	});

	it('does not throw away a publish already in flight', async () => {
		const topic = 'familygroup/g1/loc/u1';
		const id = await enqueueLatest({ groupId: 'g1', topic, payload: utf8('in flight') });
		await db.outbox.update(id, { status: 'SENDING' });
		await enqueueLatest({ groupId: 'g1', topic, payload: utf8('newer') });
		// The in-flight row survives: deleting it would leave the pump holding a completion for
		// something no longer in the table.
		expect(await db.outbox.count()).toBe(2);
	});

	it('reports how long until the next backed-off row is due', async () => {
		expect(await nextDueIn()).toBeNull();
		const id = await enqueue(row());
		expect(await nextDueIn()).toBe(0);
		const now = Date.now();
		await db.outbox.update(id, { nextAttemptAt: now + 5000 });
		expect(await nextDueIn(now)).toBe(5000);
	});

	// Call signalling is the one thing in this app where surviving a dead network is the wrong
	// behaviour: a ring published twenty minutes late rings a device about a call long over.
	it('will not publish a row whose moment has passed', async () => {
		await enqueue(row({ topic: 'familygroup/g1/call', expiresInMillis: 45_000 }));
		expect(await due()).toHaveLength(1);
		expect(await due(Date.now() + 46_000)).toHaveLength(0);
	});

	it('sweeps expired rows and leaves the rest', async () => {
		await enqueue(row({ topic: 'familygroup/g1/msg' }));
		await enqueue(row({ topic: 'familygroup/g1/call', expiresInMillis: 45_000 }));
		expect(await dropExpired(Date.now() + 46_000)).toBe(1);
		expect(await db.outbox.count()).toBe(1);
	});

	// Rows written before expiresAt existed have it undefined, and those are exactly the rows
	// that must never be swept — a chat message from last week is still worth sending.
	it('never sweeps a row that has no deadline', async () => {
		const id = await enqueue(row());
		await db.outbox.update(id, { expiresAt: undefined });
		expect(await dropExpired(Date.now() + 1_000_000)).toBe(0);
		expect(await due()).toHaveLength(1);
	});
});
