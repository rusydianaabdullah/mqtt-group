import { describe, expect, it } from 'vitest';
import { dayLabel, relativeTime } from './relativeTime';

const NOW = new Date('2026-09-09T12:00:00').getTime();

describe('relative time', () => {
	it('says now rather than counting seconds nobody reads', () => {
		expect(relativeTime(NOW, NOW)).toBe('just now');
		expect(relativeTime(NOW - 59_000, NOW)).toBe('just now');
	});

	it('steps up through minutes, hours and days', () => {
		expect(relativeTime(NOW - 5 * 60_000, NOW)).toBe('5 min ago');
		expect(relativeTime(NOW - 3 * 3_600_000, NOW)).toBe('3 hr ago');
		expect(relativeTime(NOW - 86_400_000, NOW)).toBe('1 day ago');
		expect(relativeTime(NOW - 3 * 86_400_000, NOW)).toBe('3 days ago');
	});

	it('gives a date once ago is harder to place than the day', () => {
		expect(relativeTime(NOW - 30 * 86_400_000, NOW)).toMatch(/\d/);
		expect(relativeTime(NOW - 30 * 86_400_000, NOW)).not.toMatch(/ago/);
	});

	it('never reports the future as elapsed time', () => {
		// A fix whose capturedAt is ahead of this clock is not impossible: it came from someone
		// else's device, and clocks differ.
		expect(relativeTime(NOW + 60_000, NOW)).toBe('just now');
	});

	it('labels days by name where a name is clearer than a date', () => {
		expect(dayLabel(NOW, NOW)).toBe('Today');
		expect(dayLabel(NOW - 86_400_000, NOW)).toBe('Yesterday');
		expect(dayLabel(NOW - 5 * 86_400_000, NOW)).not.toMatch(/Today|Yesterday/);
	});
});
