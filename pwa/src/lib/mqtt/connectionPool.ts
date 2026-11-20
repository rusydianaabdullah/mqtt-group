import {
	MqttConnection,
	type ConnectionState,
	type IncomingMessage,
	type Will
} from './connection';
import type { Broker } from '$lib/data/db/schema';

export interface RoutedMessage extends IncomingMessage {
	brokerId: string;
}

/**
 * One connection per broker, because a group names its own broker and two groups may not share.
 *
 * Nothing above this counts connections: `aggregate` answers "are we online" for the status
 * line, `onMessage` merges every broker's traffic into one stream tagged with where it came
 * from, and callers that need a specific broker ask for it by id.
 */
export class ConnectionPool {
	private connections = new Map<string, MqttConnection>();
	private unsubscribers = new Map<string, () => void>();
	private messageListeners = new Set<(m: RoutedMessage) => void>();
	private stateListeners = new Set<() => void>();

	states = new Map<string, ConnectionState>();

	peek(brokerId: string): MqttConnection | undefined {
		return this.connections.get(brokerId);
	}

	get anyConnected(): boolean {
		return [...this.states.values()].some((s) => s.kind === 'connected');
	}

	/**
	 * The status line's answer, and deliberately pessimistic. With three brokers and one down,
	 * "connected" would be a lie to the groups on the broken one. Per-broker state is on the
	 * brokers screen, which is where "which half of my groups is stuck" can be answered.
	 */
	get aggregate(): ConnectionState {
		const all = [...this.states.values()];
		if (all.length === 0) return { kind: 'idle' };
		if (all.every((s) => s.kind === 'connected')) return { kind: 'connected' };
		if (all.some((s) => s.kind === 'connecting')) return { kind: 'connecting' };
		return all.find((s) => s.kind === 'waiting') ?? { kind: 'connecting' };
	}

	onMessage(listener: (m: RoutedMessage) => void): () => void {
		this.messageListeners.add(listener);
		return () => this.messageListeners.delete(listener);
	}

	onStateChange(listener: () => void): () => void {
		this.stateListeners.add(listener);
		return () => this.stateListeners.delete(listener);
	}

	/** Connects a broker, or leaves an already-connected one alone. */
	async ensure(broker: Broker, clientId: string, will?: Will): Promise<MqttConnection> {
		const existing = this.connections.get(broker.id);
		if (existing?.isConnected) return existing;
		if (existing) await this.retire(broker.id);

		const connection = new MqttConnection();
		this.connections.set(broker.id, connection);
		const offMessage = connection.onMessage((m) => {
			for (const l of this.messageListeners) l({ ...m, brokerId: broker.id });
		});
		const offState = connection.onState((s) => {
			this.states.set(broker.id, s);
			for (const l of this.stateListeners) l();
		});
		this.unsubscribers.set(broker.id, () => {
			offMessage();
			offState();
		});
		await connection.connect(broker, clientId, will);
		return connection;
	}

	async retire(brokerId: string): Promise<void> {
		this.unsubscribers.get(brokerId)?.();
		this.unsubscribers.delete(brokerId);
		await this.connections.get(brokerId)?.disconnect();
		this.connections.delete(brokerId);
		this.states.delete(brokerId);
		for (const l of this.stateListeners) l();
	}

	async retireAll(): Promise<void> {
		await Promise.all([...this.connections.keys()].map((id) => this.retire(id)));
	}
}

/**
 * The client id for this device on this broker.
 *
 * Per broker as well as per device. Two connections from one device sharing an identifier means
 * a broker kicking one off the moment the other connects, forever — and it looks exactly like a
 * flapping network. The user id is the stable half so a reconnect resumes the same session
 * rather than orphaning it.
 */
export function clientIdFor(userId: string, brokerId: string): string {
	return `fgw-${userId.slice(0, 8)}-${brokerId.slice(0, 8)}`;
}
