import { describe, expect, it, beforeEach } from 'vitest';
import { metresBetween, pruneHistory } from './locationEngine';
import { db } from '$lib/data/db/schema';

beforeEach(async () => {
	await db.locations.clear();
	await db.groups.clear();
});

describe('distance', () => {
	it('measures the distances a movement gate actually deals with', () => {
		// One degree of latitude is about 111 km anywhere on the planet.
		expect(metresBetween(0, 0, 1, 0)).toBeCloseTo(111_195, -2);
		// Two points 25 m apart in Bandung — the default movement gate.
		expect(metresBetween(-6.914744, 107.60981, -6.914969, 107.60981)).toBeCloseTo(25, 0);
	});

	it('is zero for a point against itself, rather than a rounding artefact', () => {
		expect(metresBetween(-6.914744, 107.60981, -6.914744, 107.60981)).toBe(0);
	});
});

describe('retention', () => {
	const group = (over = {}) => ({
		groupId: 'g1',
		name: 'g',
		brokerId: 'b',
		topicPrefix: 'p',
		keyEpoch: 0,
		isAdmin: true,
		createdAt: 0,
		sendCounter: 0,
		locationEnabled: true,
		locSampleSec: 60,
		locPushSec: 300,
		locMinDistanceM: 25,
		locRetentionHours: 24,
		shareLocation: false,
		...over
	});

	const fix = (capturedAt: number, groupId = 'g1') => ({
		groupId,
		userId: 'u1',
		lat: 0,
		lon: 0,
		accuracy: 0,
		speed: 0,
		bearing: 0,
		capturedAt
	});

	it('drops what is past the window and keeps what is inside it', async () => {
		const now = Date.now();
		await db.groups.put(group());
		await db.locations.bulkAdd([fix(now - 25 * 3_600_000), fix(now - 1000)]);
		expect(await pruneHistory(now)).toBe(1);
		expect(await db.locations.count()).toBe(1);
	});

	it('treats 0 as keep forever, not as a zero-length window', async () => {
		// Pruning everything would be the exact opposite of what "forever" asks for.
		const now = Date.now();
		await db.groups.put(group({ locRetentionHours: 0 }));
		await db.locations.add(fix(now - 365 * 24 * 3_600_000));
		expect(await pruneHistory(now)).toBe(0);
		expect(await db.locations.count()).toBe(1);
	});

	it('prunes each group against its own window', async () => {
		const now = Date.now();
		await db.groups.bulkPut([
			group({ groupId: 'short', locRetentionHours: 1 }),
			group({ groupId: 'long', locRetentionHours: 720 })
		]);
		await db.locations.bulkAdd([
			fix(now - 2 * 3_600_000, 'short'),
			fix(now - 2 * 3_600_000, 'long')
		]);
		await pruneHistory(now);
		expect(await db.locations.where('groupId').equals('short').count()).toBe(0);
		expect(await db.locations.where('groupId').equals('long').count()).toBe(1);
	});
});
