import { liveQuery } from 'dexie';

/**
 * A Dexie live query as a rune.
 *
 * Dexie already knows exactly which queries a write invalidates, and it broadcasts that across
 * tabs — so a follower tab's screens update from the leader's work with nothing extra on the
 * wire and no polling. This wraps that observable in the shape Svelte components want.
 *
 * The initial value is what renders before the first result arrives, which is a frame or two.
 * Passing an empty array rather than null keeps every consumer from needing a null branch for a
 * state that is over before anyone sees it.
 */
export function live<T>(query: () => T | Promise<T>, initial: T) {
	const state = $state<{ value: T }>({ value: initial });

	$effect(() => {
		const subscription = liveQuery(query).subscribe({
			next: (value) => (state.value = value as T),
			// A query that throws is a bug, not a state to render: log it and keep the last good
			// value rather than blanking the screen.
			error: (error: unknown) => console.error('live query failed', error)
		});
		return () => subscription.unsubscribe();
	});

	return state;
}
