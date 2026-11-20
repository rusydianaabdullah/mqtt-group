import { db } from '$lib/data/db/schema';
import { dropExpired, due, nextDueIn, onFailed, onPublished } from '$lib/data/repo/outboxRepo';
import type { ConnectionPool } from './connectionPool';

/**
 * Drains the outbox whenever there is a connection to drain it through.
 *
 * The loop does nothing while disconnected — it waits to be woken rather than polling — so an
 * offline tab spends nothing discovering it is still offline. When a row is due later, it sleeps
 * exactly that long.
 *
 * Only the leader tab runs one. A follower's messages reach the broker because they land in the
 * same outbox and the follower rings the bus; the leader is the one that publishes.
 */
export class OutboxPump {
	private running = false;
	private wake: (() => void) | null = null;
	private timer: ReturnType<typeof setTimeout> | null = null;

	constructor(private readonly pool: ConnectionPool) {}

	start(): void {
		if (this.running) return;
		this.running = true;
		void this.loop();
	}

	/** Nudged whenever something is enqueued, so a new message goes out without waiting. */
	nudge(): void {
		this.wake?.();
	}

	stop(): void {
		this.running = false;
		if (this.timer) clearTimeout(this.timer);
		this.wake?.();
	}

	private async loop(): Promise<void> {
		while (this.running) {
			// Not "all brokers up": with three brokers and one down, the two that work should
			// still be draining, and a row for the broken one simply fails its attempt and backs
			// off the way it always has.
			if (this.pool.anyConnected) {
				// Before anything else, because a tab that has just been reopened may be holding
				// rings that expired while it was closed, and the first thing it should do with
				// them is nothing.
				await dropExpired();

				const batch = await due();
				for (const row of batch) {
					if (!this.running) return;
					if (!this.pool.anyConnected) break;
					// Each row goes out on its own group's broker. A row whose group is gone, or
					// whose broker is not up, counts as a failed attempt: `onFailed` backs it off
					// rather than dropping it, which is right for a broker that is temporarily
					// down and harmless for one that is gone for good, since deleting a group
					// deletes its rows.
					const group = await db.groups.get(row.groupId);
					const connection = group ? this.pool.peek(group.brokerId) : undefined;
					const sent = connection
						? await connection.publish(row.topic, row.payload, row.qos, row.retained)
						: false;
					if (sent) await onPublished(row);
					else await onFailed(row);
				}
			}
			await this.idle();
		}
	}

	/**
	 * Sleeps until something is enqueued, or until a backed-off row is likely due. The wake-up
	 * covers the case that matters; the timeout is only there to pick up retries.
	 */
	private idle(): Promise<void> {
		return new Promise<void>((resolve) => {
			let settled = false;
			const finish = () => {
				if (settled) return;
				settled = true;
				this.wake = null;
				if (this.timer) clearTimeout(this.timer);
				resolve();
			};
			this.wake = finish;
			void nextDueIn().then((wait) => {
				if (settled) return;
				this.timer = setTimeout(finish, Math.min(wait ?? IDLE_POLL_MILLIS, IDLE_POLL_MILLIS));
			});
		});
	}
}

const IDLE_POLL_MILLIS = 30_000;
