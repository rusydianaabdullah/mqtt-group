import mqtt, { type MqttClient } from 'mqtt';
import type { Broker } from '$lib/data/db/schema';

/** What the UI shows in the status line, and what the outbox pump waits on. */
export type ConnectionState =
	| { kind: 'idle' }
	| { kind: 'connecting' }
	| { kind: 'connected' }
	/** Reconnecting on its own. `reason` is for the user, not for a retry decision. */
	| { kind: 'waiting'; reason: string };

/** A payload as it arrived, before anything has tried to decrypt it. */
export interface IncomingMessage {
	topic: string;
	payload: Uint8Array;
}

export interface Will {
	topic: string;
	payload: Uint8Array;
}

/**
 * One MQTT connection over a WebSocket, shared by everything that publishes.
 *
 * Reconnection is MQTT.js's, not ours: it already backs off, and re-establishing a session
 * across a network change is exactly the fiddly case a library gets right more often than we
 * would. What we keep is the part that is ours — a durable queue of what still has to go out,
 * so nothing depends on a reconnect happening soon.
 *
 * Nothing here knows about encryption. Payloads arrive and leave as opaque bytes.
 *
 * The difference from the phone, and it is not small: this connection lives in a page. Close the
 * tab and it closes, mid-publish or not. That is why the outbox matters more here than there,
 * and why nothing above this class is allowed to assume a publish that was started will finish.
 */
export class MqttConnection {
	private client: MqttClient | null = null;
	private listeners = new Set<(m: IncomingMessage) => void>();
	private stateListeners = new Set<(s: ConnectionState) => void>();
	private filters = new Set<string>();

	state: ConnectionState = { kind: 'idle' };

	/**
	 * Which client is the live one. Bumped by every connect.
	 *
	 * Every client registers handlers that write `state`, and replacing a client does not stop
	 * the old one's callbacks — MQTT.js keeps its own timers, and a client that was
	 * mid-reconnect when we dropped it goes on firing `close` afterwards. Without this guard
	 * those late callbacks overwrite the current client's connected with a waiting from a
	 * connection nobody is using: the app reports itself offline while its socket is open, which
	 * is the one state a user can neither argue with nor fix.
	 */
	private generation = 0;

	get isConnected(): boolean {
		return this.client?.connected === true;
	}

	onMessage(listener: (m: IncomingMessage) => void): () => void {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	onState(listener: (s: ConnectionState) => void): () => void {
		this.stateListeners.add(listener);
		listener(this.state);
		return () => this.stateListeners.delete(listener);
	}

	/**
	 * Connects, and stays connected until `disconnect`. `will` is published by the broker if
	 * this tab disappears without saying goodbye, which is how everyone else learns we are gone
	 * without polling — and in a browser that is the normal way to leave, not the exception.
	 */
	async connect(broker: Broker, clientId: string, will?: Will): Promise<void> {
		await this.disconnect();
		const mine = ++this.generation;
		this.setState({ kind: 'connecting' });

		const client = mqtt.connect(broker.url, {
			clientId,
			protocolVersion: 5,
			// A session the broker keeps, so subscriptions and any QoS 1 messages missed while
			// the tab was closed are still there on return. `clean: false` is only a request;
			// whether it was honoured shows up in the CONNACK's session-present flag.
			clean: false,
			properties: { sessionExpiryInterval: SESSION_EXPIRY_SECONDS },
			keepalive: KEEP_ALIVE_SECONDS,
			reconnectPeriod: 2000,
			connectTimeout: 15_000,
			username: broker.username ?? undefined,
			password: broker.password ?? undefined,
			will: will
				? { topic: will.topic, payload: will.payload as unknown as Buffer, qos: 1, retain: true }
				: undefined
		});
		this.client = client;

		client.on('message', (topic, payload) => {
			if (mine !== this.generation) return;
			const message = { topic, payload: new Uint8Array(payload) };
			for (const l of this.listeners) l(message);
		});
		client.on('connect', () => {
			if (mine !== this.generation) return;
			this.setState({ kind: 'connected' });
			// Re-issue every filter we hold. A broker that resumed our session already has them
			// and this is a harmless no-op; a broker that started us over with none has just
			// left us looking connected while receiving nothing, and this is the only thing that
			// notices.
			for (const filter of this.filters) client.subscribe(filter, { qos: 1 });
		});
		client.on('reconnect', () => {
			if (mine !== this.generation) return;
			this.setState({ kind: 'connecting' });
		});
		client.on('close', () => {
			if (mine !== this.generation) return;
			if (this.state.kind === 'connected')
				this.setState({ kind: 'waiting', reason: 'Disconnected' });
		});
		client.on('error', (error) => {
			if (mine !== this.generation) return;
			this.setState({ kind: 'waiting', reason: readable(error) });
		});

		await new Promise<void>((resolve) => {
			const done = () => resolve();
			client.once('connect', done);
			// Not rejected on error: the automatic reconnect is still running, so a failure here
			// is a state to display rather than something the caller can handle.
			client.once('error', done);
			client.once('close', done);
		});
	}

	async subscribe(filter: string, qos: 0 | 1 = 1): Promise<void> {
		this.filters.add(filter);
		const client = this.client;
		if (!client?.connected) return;
		await new Promise<void>((resolve) => client.subscribe(filter, { qos }, () => resolve()));
	}

	async unsubscribe(filter: string): Promise<void> {
		this.filters.delete(filter);
		const client = this.client;
		if (!client?.connected) return;
		await new Promise<void>((resolve) => client.unsubscribe(filter, () => resolve()));
	}

	/**
	 * Publishes, returning false rather than throwing when it did not get through — the caller
	 * is the outbox, and a failure there means "leave the row and try again later".
	 */
	async publish(
		topic: string,
		payload: Uint8Array,
		qos: number,
		retained: boolean
	): Promise<boolean> {
		const client = this.client;
		if (!client?.connected) return false;
		return new Promise<boolean>((resolve) => {
			// The Uint8Array goes straight through: mqtt-packet writes any typed array, and
			// reaching for Buffer here would drag Node's polyfill into the bundle for nothing.
			client.publish(
				topic,
				payload as unknown as Buffer,
				{ qos: (qos === 0 ? 0 : qos === 2 ? 2 : 1) as 0 | 1 | 2, retain: retained },
				(error) => resolve(!error)
			);
		});
	}

	/**
	 * Publishes at QoS 0 without waiting for anything and without reporting whether it worked.
	 *
	 * For voice, and for nothing else. Every other publish in this app goes through the outbox
	 * precisely so "did this leave the device" has an answer; for a voice packet that answer is
	 * worthless, because by the time it could be acted on the packet is already too old to play.
	 * Seventeen of these a second must also not await anything on a path that is already the
	 * latency weak point.
	 *
	 * Silently does nothing when there is no connection: a call whose broker has dropped is
	 * already being torn down by the connection state, and seventeen log lines a second on the
	 * way there help nobody.
	 */
	publishNow(topic: string, payload: Uint8Array): void {
		const client = this.client;
		if (!client?.connected) return;
		try {
			client.publish(topic, payload as unknown as Buffer, { qos: 0, retain: false });
		} catch {
			// A socket that closed between the check and the write.
		}
	}

	async disconnect(): Promise<void> {
		const client = this.client;
		this.client = null;
		this.generation++;
		if (!client) return;
		await new Promise<void>((resolve) => {
			// MQTT 5 lets a client-sent DISCONNECT override the session expiry negotiated at
			// CONNECT, and a bare end() defaults that to zero on some brokers — telling it to
			// drop our subscriptions and anything queued for us right then. Every disconnect
			// this app sends means "be right back", never "forget me".
			client.end(false, { properties: { sessionExpiryInterval: SESSION_EXPIRY_SECONDS } }, () =>
				resolve()
			);
		});
		this.setState({ kind: 'idle' });
	}

	private setState(state: ConnectionState): void {
		this.state = state;
		for (const l of this.stateListeners) l(state);
	}
}

/**
 * A week. Long enough that a laptop shut for the weekend comes back to its subscriptions and
 * whatever QoS 1 traffic the broker held, short enough that an abandoned client id does not
 * pin broker storage forever.
 */
const SESSION_EXPIRY_SECONDS = 7 * 24 * 60 * 60;
const KEEP_ALIVE_SECONDS = 60;

/**
 * What went wrong, in words a person can act on.
 *
 * A browser deliberately tells script almost nothing about why a WebSocket failed — a refused
 * certificate, a closed port and a wrong path are all the same opaque event, because
 * distinguishing them would be a port-scanning oracle. So this names the likely causes instead
 * of pretending to know which one it was.
 */
export function readable(error: unknown): string {
	const message = error instanceof Error ? error.message : String(error);
	if (/not authorized|bad user|unauthorized/i.test(message)) {
		return 'The broker rejected the username or password.';
	}
	if (/identifier rejected|client id/i.test(message)) {
		return 'The broker rejected this client id.';
	}
	if (/WebSocket|ECONNREFUSED|failed to connect|closed before/i.test(message)) {
		return 'The socket would not open. Usually that is the wrong port or path, or a certificate the browser does not trust.';
	}
	return message || 'The connection failed.';
}
