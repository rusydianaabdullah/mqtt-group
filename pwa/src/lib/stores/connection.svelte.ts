import { ConnectionPool, clientIdFor } from '$lib/mqtt/connectionPool';
import { OutboxPump } from '$lib/mqtt/outboxPump';
import { GroupSync } from '$lib/mqtt/groupSync';
import { leadership } from '$lib/mqtt/leader.svelte';
import { listBrokers } from '$lib/data/repo/brokerRepo';
import { failedCount, pendingCount } from '$lib/data/repo/outboxRepo';
import type { ConnectionState } from '$lib/mqtt/connection';
import { db, type Broker } from '$lib/data/db/schema';
import { getIdentity } from '$lib/data/repo/identityRepo';

/**
 * The live connection, as the screens see it.
 *
 * One of these per tab; only the leader tab's actually holds sockets. A follower shows the same
 * status as the leader by watching the database and the bus, which is honest — the connection
 * really is the app's, not this tab's.
 */
class ConnectionState_ {
	pool = new ConnectionPool();
	status = $state<ConnectionState>({ kind: 'idle' });
	perBroker = $state<Record<string, ConnectionState>>({});
	queued = $state(0);
	failed = $state(0);
	/** False in a tab that is not the one holding the sockets. */
	isLeader = $state(false);
	/** True where the browser cannot elect a leader, so every tab connects. */
	electionUnsupported = $state(false);

	/** Whether there is a broker to publish through right now. Asked by anything that is
	 * worthless if it cannot go out promptly — a call offer lives 45 seconds. */
	get isConnected(): boolean {
		return this.status.kind === 'connected';
	}

	private pump = new OutboxPump(this.pool);
	sync = new GroupSync(this.pool);
	private started = false;

	async start(): Promise<void> {
		if (this.started) return;
		this.started = true;

		await leadership.start();
		this.isLeader = leadership.isLeader;
		this.electionUnsupported = leadership.unsupported;

		this.pool.onStateChange(() => {
			this.status = this.pool.aggregate;
			this.perBroker = Object.fromEntries(this.pool.states);
		});

		// A follower's message lands in the shared outbox and rings the bus; this is the leader
		// hearing it and publishing without waiting for its own idle poll.
		leadership.onBus((m) => {
			if (m.type === 'outbox-changed' || m.type === 'wake') this.pump.nudge();
		});

		// Leadership is not decided once. A follower inherits the connection the moment the
		// leader's tab goes away, and has to pick up the sockets there and then rather than sit
		// as a follower with no leader left to follow.
		leadership.onLeadershipChange((isLeader) => void this.applyLeadership(isLeader));

		// Dexie broadcasts its own writes across tabs, so a follower's counts follow the leader's
		// work with nothing extra on the wire.
		db.outbox.hook('creating', () => void this.refreshCounts());
		db.outbox.hook('deleting', () => void this.refreshCounts());
		db.outbox.hook('updating', () => void this.refreshCounts());
		await this.refreshCounts();

		await this.applyLeadership(leadership.isLeader);
	}

	/** Takes up the sockets on promotion, and puts them down on demotion. */
	private async applyLeadership(isLeader: boolean): Promise<void> {
		this.isLeader = isLeader;
		if (!isLeader) {
			this.sync.stop();
			this.pump.stop();
			await this.pool.retireAll();
			return;
		}
		this.pump.start();
		// Only the leader receives. A follower reads the same database and sees everything
		// the leader wrote, which is the whole reason one tab is enough.
		this.sync.start();
		await this.reconcile();
	}

	/**
	 * Brings the set of live connections in line with the saved brokers: connect what is new,
	 * retire what is gone. Called after any change on the brokers screen.
	 */
	async reconcile(): Promise<void> {
		if (!this.isLeader) {
			// A follower cannot connect, but it can tell the leader that the broker list moved.
			leadership.post({ type: 'wake' });
			return;
		}
		const identity = await getIdentity();
		if (!identity) return;
		const brokers = await listBrokers();
		const wanted = new Set(brokers.map((b) => b.id));

		for (const id of [...this.pool.states.keys()]) {
			if (!wanted.has(id)) await this.pool.retire(id);
		}
		for (const broker of brokers) {
			await this.pool.ensure(broker, clientIdFor(identity.userId, broker.id));
		}
		await this.sync.resubscribe();
		this.pump.nudge();
	}

	/** Enqueue happened somewhere; publish it now rather than on the next tick. */
	nudge(): void {
		this.pump.nudge();
		leadership.post({ type: 'outbox-changed' });
	}

	async refreshCounts(): Promise<void> {
		this.queued = await pendingCount();
		this.failed = await failedCount();
	}

	async stop(): Promise<void> {
		this.sync.stop();
		this.pump.stop();
		await this.pool.retireAll();
		leadership.stop();
		this.started = false;
	}
}

export const connection = new ConnectionState_();

/**
 * Opens a throwaway connection and reports what happened, for the "Test connection" button.
 *
 * A separate client id from the real one on purpose: testing a broker must never disconnect the
 * live session on the same broker, which is exactly what reusing the id would do.
 */
export async function testBroker(
	broker: Omit<Broker, 'id' | 'createdAt'>
): Promise<{ ok: boolean; message: string }> {
	const { MqttConnection } = await import('$lib/mqtt/connection');
	const probe = new MqttConnection();
	const candidate: Broker = { ...broker, id: 'probe', createdAt: 0 };
	try {
		await probe.connect(candidate, `fgw-probe-${Math.random().toString(36).slice(2, 10)}`);
		const state = probe.state;
		if (state.kind === 'connected') return { ok: true, message: 'Connected.' };
		return { ok: false, message: state.kind === 'waiting' ? state.reason : 'Could not connect.' };
	} catch (error) {
		const { readable } = await import('$lib/mqtt/connection');
		return { ok: false, message: readable(error) };
	} finally {
		await probe.disconnect();
	}
}
