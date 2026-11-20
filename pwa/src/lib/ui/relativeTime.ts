/**
 * "3 minutes ago", and the rules about when that is worth saying at all.
 *
 * Staleness is the whole vocabulary of this app's map and member list, so it is written once.
 * Anything under a minute is "now" rather than a count of seconds nobody reads, and anything
 * past a week is a date, because "12 days ago" is harder to place than the day itself.
 */
export function relativeTime(at: number, now = Date.now()): string {
	const seconds = Math.max(0, Math.round((now - at) / 1000));
	if (seconds < 60) return 'just now';
	const minutes = Math.round(seconds / 60);
	if (minutes < 60) return `${minutes} min ago`;
	const hours = Math.round(minutes / 60);
	if (hours < 24) return `${hours} hr ago`;
	const days = Math.round(hours / 24);
	if (days <= 7) return `${days} day${days === 1 ? '' : 's'} ago`;
	return new Date(at).toLocaleDateString();
}

/** Clock time for a chat bubble, where the date is already implied by the day separator. */
export function clockTime(at: number): string {
	return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** "Today", "Yesterday", or the date — the separator between days in a chat. */
export function dayLabel(at: number, now = Date.now()): string {
	const day = new Date(at);
	const today = new Date(now);
	const midnight = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
	const diff = Math.round((midnight(today) - midnight(day)) / 86_400_000);
	if (diff === 0) return 'Today';
	if (diff === 1) return 'Yesterday';
	return day.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
}
