import { db, type Broker } from '$lib/data/db/schema';
import { DEFAULT_PREFIX, normalisePrefix } from '$lib/mqtt/topics';
import { randomUuid } from '$lib/util/bytes';

/**
 * The brokers this device knows how to reach.
 *
 * A browser can only speak MQTT over WebSocket, so a broker here is a `wss://` URL rather than
 * the host/port/tls triple the Android app stores. That is not a simplification: it is the
 * whole difference, and it is why an invite's broker details cannot be used unchanged.
 *
 * The default points at a public broker so a first run works before anyone stands up their own.
 * A public broker sees topic names and traffic timing — never message content — which the
 * broker screen says in plain words.
 */

/** Mosquitto's own public test broker, on its TLS WebSocket listener. */
export const SUGGESTED_URL = 'wss://test.mosquitto.org:8081/mqtt';

export async function listBrokers(): Promise<Broker[]> {
	return db.brokers.orderBy('createdAt').toArray();
}

export async function getBroker(id: string): Promise<Broker | undefined> {
	return db.brokers.get(id);
}

export async function saveBroker(
	broker: Omit<Broker, 'id' | 'createdAt'> & { id?: string }
): Promise<string> {
	const id = broker.id ?? randomUuid();
	const existing = broker.id ? await db.brokers.get(broker.id) : undefined;
	await db.brokers.put({
		id,
		label: broker.label.trim() || hostOf(broker.url),
		url: broker.url.trim(),
		username: broker.username?.trim() || null,
		password: broker.password || null,
		topicPrefix: normalisePrefix(broker.topicPrefix || DEFAULT_PREFIX),
		createdAt: existing?.createdAt ?? Date.now()
	});
	return id;
}

export async function deleteBroker(id: string): Promise<void> {
	await db.brokers.delete(id);
}

/** How many groups would be stranded by deleting this broker. */
export async function groupsOnBroker(brokerId: string): Promise<number> {
	return db.groups.where('brokerId').equals(brokerId).count();
}

export function hostOf(url: string): string {
	try {
		return new URL(url).host;
	} catch {
		return url;
	}
}

/**
 * What is wrong with a URL, in the words the broker screen shows — or null when it is fine.
 *
 * Checked here rather than left to the socket because the failures a browser reports for a bad
 * MQTT URL are uniformly useless: a wrong scheme throws a `SyntaxError` with no detail, and a
 * `wss://` to a port speaking raw MQTT simply never opens.
 */
export function validateUrl(url: string): string | null {
	const trimmed = url.trim();
	if (!trimmed) return 'A broker URL is required.';
	let parsed: URL;
	try {
		parsed = new URL(trimmed);
	} catch {
		return 'That is not a URL. It should look like wss://broker.example:8084/mqtt';
	}
	if (parsed.protocol === 'mqtt:' || parsed.protocol === 'mqtts:') {
		return 'A browser cannot open a raw MQTT socket — only a WebSocket one. Use wss:// and the broker’s WebSocket port, usually 8084.';
	}
	if (parsed.protocol !== 'ws:' && parsed.protocol !== 'wss:') {
		return 'The URL must start with wss:// (or ws:// on a local network).';
	}
	if (parsed.protocol === 'ws:' && location.protocol === 'https:') {
		return 'This page is served over HTTPS, so the browser will block a plain ws:// socket. Use wss://.';
	}
	return null;
}
