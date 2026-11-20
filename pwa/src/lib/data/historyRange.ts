/**
 * The spans of time the map can show, and the arithmetic behind each.
 *
 * The subtlety worth writing down: a *relative* range has to be re-resolved as the clock moves.
 * Fixed at the moment it was tapped, "last hour" silently grows — leave the map open for an hour
 * and it is showing two, with no indication that the window is not what the button says. A fixed
 * range ignores the tick, since its window does not move.
 */

export type HistoryRangeId = 'lastHour' | 'today' | 'yesterday' | 'last24h' | 'last7d' | 'custom';

export interface ResolvedRange {
	since: number;
	until: number;
}

export const HISTORY_RANGES: { id: HistoryRangeId; label: string }[] = [
	{ id: 'lastHour', label: 'Last hour' },
	{ id: 'today', label: 'Today' },
	{ id: 'yesterday', label: 'Yesterday' },
	{ id: 'last24h', label: 'Last 24 hours' },
	{ id: 'last7d', label: 'Last 7 days' },
	{ id: 'custom', label: 'Pick dates' }
];

/** True for the ranges whose window moves with the clock, and so must be re-resolved. */
export function isRelative(id: HistoryRangeId): boolean {
	return id === 'lastHour' || id === 'last24h' || id === 'last7d';
}

const DAY = 86_400_000;

function startOfDay(at: number): number {
	const d = new Date(at);
	return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

export function resolveRange(
	id: HistoryRangeId,
	now = Date.now(),
	custom?: { since: number; until: number }
): ResolvedRange {
	switch (id) {
		case 'lastHour':
			return { since: now - 3_600_000, until: now };
		case 'today':
			// Ends at `now`, not at midnight tonight: a window reaching into the future would put
			// the playback scrubber's end hours past the last thing there is to show.
			return { since: startOfDay(now), until: now };
		case 'yesterday':
			return { since: startOfDay(now) - DAY, until: startOfDay(now) };
		case 'last24h':
			return { since: now - DAY, until: now };
		case 'last7d':
			return { since: now - 7 * DAY, until: now };
		case 'custom':
			// The end date is inclusive, so it runs to the close of that day rather than to its
			// first instant — picking the 9th twice should show the 9th, not nothing.
			return custom
				? { since: startOfDay(custom.since), until: startOfDay(custom.until) + DAY }
				: { since: now - DAY, until: now };
	}
}

/**
 * Thins a path for drawing, keeping the first and last points.
 *
 * A week at the shortest push interval is tens of thousands of fixes per person, and the map
 * draws everyone at once. This is for the line only — pins, the detail card and the database all
 * still use the real rows.
 */
export function decimate<T>(points: T[], limit = 500): T[] {
	if (points.length <= limit) return points;
	const step = (points.length - 1) / (limit - 1);
	const out: T[] = [];
	for (let i = 0; i < limit; i++) out.push(points[Math.round(i * step)]);
	return out;
}
