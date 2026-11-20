import { describe, expect, it } from 'vitest';
import { decimate, isRelative, resolveRange } from './historyRange';

const NOON = new Date('2026-09-09T12:00:00').getTime();
const MIDNIGHT = new Date('2026-09-09T00:00:00').getTime();
const DAY = 86_400_000;

describe('history ranges', () => {
	it('resolves the relative ones against the clock it is given', () => {
		expect(resolveRange('lastHour', NOON)).toEqual({ since: NOON - 3_600_000, until: NOON });
		expect(resolveRange('last24h', NOON)).toEqual({ since: NOON - DAY, until: NOON });
		expect(resolveRange('last7d', NOON)).toEqual({ since: NOON - 7 * DAY, until: NOON });
	});

	it('ends today at now rather than at midnight tonight', () => {
		// A window reaching into the future would put the playback scrubber's end hours past the
		// last thing there is to show.
		expect(resolveRange('today', NOON)).toEqual({ since: MIDNIGHT, until: NOON });
	});

	it('makes yesterday a whole day that stops where today starts', () => {
		expect(resolveRange('yesterday', NOON)).toEqual({ since: MIDNIGHT - DAY, until: MIDNIGHT });
	});

	it('treats a custom end date as inclusive', () => {
		// Picking the 9th twice should show the 9th, not nothing.
		const range = resolveRange('custom', NOON, { since: NOON, until: NOON });
		expect(range.since).toBe(MIDNIGHT);
		expect(range.until).toBe(MIDNIGHT + DAY);
	});

	it('knows which ranges have to be re-resolved as time passes', () => {
		// Fixed at the moment it was tapped, "last hour" silently becomes two.
		expect(isRelative('lastHour')).toBe(true);
		expect(isRelative('last7d')).toBe(true);
		expect(isRelative('yesterday')).toBe(false);
		expect(isRelative('custom')).toBe(false);
	});
});

describe('decimation', () => {
	it('leaves a path alone when it is already short enough', () => {
		const points = [1, 2, 3];
		expect(decimate(points, 500)).toBe(points);
	});

	it('thins to the limit and keeps both ends', () => {
		const points = Array.from({ length: 10_000 }, (_, i) => i);
		const thinned = decimate(points, 500);
		expect(thinned).toHaveLength(500);
		expect(thinned[0]).toBe(0);
		expect(thinned.at(-1)).toBe(9999);
	});

	it('keeps the points in order', () => {
		const thinned = decimate(
			Array.from({ length: 1234 }, (_, i) => i),
			100
		);
		expect(thinned).toEqual([...thinned].sort((a, b) => a - b));
	});
});
