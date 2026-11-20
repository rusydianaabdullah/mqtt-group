import { beforeEach, describe, expect, it, vi } from 'vitest';
import { hostOf, listBrokers, saveBroker, validateUrl } from './brokerRepo';
import { db } from '$lib/data/db/schema';

beforeEach(async () => {
	await db.brokers.clear();
	vi.stubGlobal('location', { protocol: 'https:' });
});

describe('brokers', () => {
	it('saves, defaults the label to the host, and normalises the prefix', async () => {
		await saveBroker({
			label: '',
			url: 'wss://broker.example:8084/mqtt',
			username: '',
			password: '',
			topicPrefix: '  /family/  '
		});
		const [saved] = await listBrokers();
		expect(saved.label).toBe('broker.example:8084');
		expect(saved.topicPrefix).toBe('family');
		// Empty credentials are null rather than "", so the connection omits them entirely
		// instead of offering a blank username the broker then rejects.
		expect(saved.username).toBeNull();
		expect(saved.password).toBeNull();
	});

	it('keeps the original creation time when a broker is edited', async () => {
		const id = await saveBroker({
			label: 'a',
			url: 'wss://a/mqtt',
			username: null,
			password: null,
			topicPrefix: 'p'
		});
		const before = (await db.brokers.get(id))!.createdAt;
		await saveBroker({
			id,
			label: 'b',
			url: 'wss://b/mqtt',
			username: null,
			password: null,
			topicPrefix: 'p'
		});
		expect((await db.brokers.get(id))!.createdAt).toBe(before);
		expect(await db.brokers.count()).toBe(1);
	});

	it('explains the mistake an Android invite invites', () => {
		// The invite carries 8883, which is a raw MQTT port. Pasting it here fails as a socket
		// that never opens, with nothing in the console to say why.
		expect(validateUrl('mqtts://broker.example:8883')).toMatch(/cannot open a raw MQTT socket/);
		expect(validateUrl('https://broker.example')).toMatch(/must start with wss/);
		// `host:port` with no scheme parses as a URL whose protocol is the hostname, so it
		// lands on the scheme message rather than the unparseable one — which is the more
		// useful of the two for what is almost always a pasted host and port.
		expect(validateUrl('broker.example:8084')).toMatch(/must start with wss/);
		expect(validateUrl('http://[')).toMatch(/not a URL/);
		expect(validateUrl('  ')).toMatch(/required/);
		expect(validateUrl('wss://broker.example:8084/mqtt')).toBeNull();
	});

	it('rejects a plain socket from an https page, which the browser would block anyway', () => {
		expect(validateUrl('ws://192.168.1.10:8083/mqtt')).toMatch(/block a plain ws/);
		vi.stubGlobal('location', { protocol: 'http:' });
		expect(validateUrl('ws://192.168.1.10:8083/mqtt')).toBeNull();
	});

	it('falls back to the raw string when a URL will not parse', () => {
		expect(hostOf('not a url')).toBe('not a url');
	});
});
