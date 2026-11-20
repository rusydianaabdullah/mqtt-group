/**
 * Which tab owns the connection.
 *
 * This has no counterpart on the phone, where a foreground service is the obvious single owner.
 * In a browser every tab runs the whole app, and two tabs each opening their own MQTT client
 * with the same client id means a broker disconnecting one the instant the other connects —
 * forever, in a loop that looks exactly like a broken network. Distinct client ids per tab would
 * avoid that and cost a duplicate of every subscription and every retained replay instead, plus
 * two copies of every message written into one shared database.
 *
 * So exactly one tab connects. Web Locks decides which: a lock held for as long as the tab
 * lives is released by the browser when it dies, crashes or is discarded, with no heartbeat to
 * tune and no stale-lease window. A follower waits on the same lock and becomes the leader the
 * moment the holder is gone.
 *
 * Followers are not idle: they read the same IndexedDB, write to the same outbox, and see the
 * leader's writes through Dexie's own cross-tab change events. What they do not do is hold a
 * socket. `BroadcastChannel` carries the one thing the database does not — "drain the outbox
 * now", so a message typed in a follower goes out immediately rather than on the leader's next
 * idle poll.
 *
 * Where Web Locks does not exist, every tab is its own leader. That is the old duplicate-client
 * problem, and it is the honest fallback: the alternative is a heartbeat protocol over
 * localStorage that fails differently and less predictably.
 */

const LOCK = 'family-group-connection';
const CHANNEL = 'family-group-bus';

export type BusMessage = { type: 'wake' } | { type: 'outbox-changed' };

export class Leadership {
	isLeader = $state(false);
	/** True when this browser cannot elect at all, so every tab connects. Shown in settings. */
	unsupported = $state(false);

	private release: (() => void) | null = null;
	private channel: BroadcastChannel | null = null;
	private busListeners = new Set<(m: BusMessage) => void>();
	private leaderListeners = new Set<(isLeader: boolean) => void>();

	/**
	 * Starts campaigning. Resolves once the outcome for *this* tab is settled — leader, or
	 * follower queued behind one — rather than when leadership is won, since a follower has to
	 * render too.
	 */
	async start(): Promise<void> {
		if (typeof BroadcastChannel !== 'undefined') {
			this.channel = new BroadcastChannel(CHANNEL);
			this.channel.onmessage = (event) => {
				for (const l of this.busListeners) l(event.data as BusMessage);
			};
		}

		const locks = navigator.locks;
		if (!locks) {
			this.unsupported = true;
			this.setLeader(true);
			return;
		}

		// Two requests, not one, because the caller needs an answer *now* and a plain request
		// gives none: its callback runs when the lock is granted, which for a follower is
		// whenever the leader's tab happens to die. `ifAvailable` settles the first tab's
		// outcome deterministically — waiting a turn of the event loop for the grant instead
		// looked like it worked and left the only open tab believing it was a follower, holding
		// no socket, showing "No broker" forever.
		const granted = await new Promise<boolean>((resolve) => {
			void locks
				.request(LOCK, { ifAvailable: true }, (lock) => {
					if (!lock) {
						resolve(false);
						return;
					}
					this.setLeader(true);
					resolve(true);
					return this.holdUntilReleased();
				})
				.catch(() => resolve(false));
		});
		if (granted) return;

		// A follower. Queue behind the holder: the browser releases their lock when their tab
		// dies, crashes or is discarded, and this tab becomes the leader there and then.
		void locks
			.request(LOCK, (lock) => {
				if (!lock) return;
				this.setLeader(true);
				return this.holdUntilReleased();
			})
			.catch(() => {});
	}

	/** Held for the life of the tab: the promise resolves only when leadership ends. */
	private holdUntilReleased(): Promise<void> {
		return new Promise<void>((resolve) => {
			this.release = () => {
				this.setLeader(false);
				resolve();
			};
		});
	}

	private setLeader(value: boolean): void {
		if (this.isLeader === value) return;
		this.isLeader = value;
		for (const l of this.leaderListeners) l(value);
	}

	/** Fires whenever this tab gains or loses the connection, including long after `start`. */
	onLeadershipChange(listener: (isLeader: boolean) => void): () => void {
		this.leaderListeners.add(listener);
		return () => this.leaderListeners.delete(listener);
	}

	/** Sends to every other tab. The sender does not hear its own message. */
	post(message: BusMessage): void {
		this.channel?.postMessage(message);
	}

	onBus(listener: (m: BusMessage) => void): () => void {
		this.busListeners.add(listener);
		return () => this.busListeners.delete(listener);
	}

	stop(): void {
		this.release?.();
		this.release = null;
		this.channel?.close();
		this.channel = null;
	}
}

export const leadership = new Leadership();
