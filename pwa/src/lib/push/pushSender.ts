import { PUBLIC_FCM_AUTH } from '$env/static/public';
/**
 * Talks to fcm.larus.id, the same service the Android app uses — see its README for the wire
 * format. A web client cannot subscribe its own registration token to a topic the way Android's
 * SDK can on-device through Play services, so /subscribe and /unsubscribe exist server-side for
 * exactly that; /notify is the same wake-up-bell call both platforms make after a chat send.
 *
 * Every call here is cross-origin — this app is served from family-group.larus.id — and carries
 * an Authorization header, so the browser preflights each one. fcm-service has to answer that
 * preflight (see its app/cors.py); when it did not, every call below failed before it was sent.
 */

const ENDPOINT = 'https://fcm.larus.id';

// Shared infrastructure credential behind Basic auth, not a per-user secret — same trust level as
// the MQTT broker credentials already shipped in this app's default broker config. Anyone who
// extracted it could only publish to or subscribe onto this app's own topics, whose names are
// random UUIDs they would first have to know.
const AUTH_HEADER = `Basic ${btoa(PUBLIC_FCM_AUTH)}`;

function topicFor(groupId: string): string {
	return `group-${groupId}`;
}

/** The last thing that went wrong, for the Settings screen to show. Push failing used to be
 * invisible: a console warning in a tab nobody has DevTools attached to, on a phone. */
let lastError: string | null = null;

export function lastPushError(): string | null {
	return lastError;
}

/**
 * Resolves true only when fcm.larus.id actually accepted the call. The old version returned
 * void, which let callers record a subscription that had in fact been refused — and a
 * subscription recorded but never made is a device that silently never gets woken again.
 */
async function post(path: string, body: unknown): Promise<boolean> {
	try {
		const response = await fetch(`${ENDPOINT}${path}`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json', Authorization: AUTH_HEADER },
			body: JSON.stringify(body)
		});
		if (!response.ok) {
			lastError = `${path}: HTTP ${response.status}`;
			console.warn(`push ${path} rejected: HTTP ${response.status}`, await response.text());
			return false;
		}
		lastError = null;
		return true;
	} catch (err) {
		// A blocked CORS preflight lands here as an opaque TypeError with no status, which is
		// exactly how this stayed hidden for so long. Name it rather than logging "failed".
		lastError = `${path}: unreachable (network or CORS)`;
		console.warn(`push ${path} failed`, err);
		return false;
	}
}

/**
 * Tells fcm.larus.id to publish to a group's topic after a chat message goes out, so a member
 * whose browser is not connected to the broker right now gets a system notification and can open
 * the app — MQTT then reconnects the ordinary way, through the page's own connection lifecycle.
 *
 * fcm-service always sends a real FCM `notification` payload (see its app/notify.py), so title
 * and body stay generic: no message content, no sender name, no group name — everything that
 * actually matters keeps travelling only as MQTT ciphertext, exactly as before. [senderId] rides
 * along as data so a receiving browser that turns out to be the sender's own — every member,
 * sender included, is subscribed to the same topic — can recognise its own outgoing message.
 */
export function notifyGroup(groupId: string, senderId: string): void {
	void post('/notify', {
		topic: topicFor(groupId),
		title: 'Family Group',
		body: 'New message',
		data: { groupId, senderId }
	});
}

/**
 * Wakes the callee's devices for an incoming call.
 *
 * `kind: 'call'` is a different shape of push, not merely a different label: the relay sends it
 * with **no** `notification` payload and a 45-second TTL instead of an hour (see fcm-service's
 * README, "The two kinds"). Both matter. A `notification` payload is drawn by the OS itself
 * before any of our code runs, which on Android is what would stop the phone ever ringing, and
 * here would mean a browser auto-displaying "New message" for a call that the service worker
 * could then not take back. The short TTL is what stops a push held behind a tunnel from
 * announcing a call that ended an hour ago.
 *
 * `callId` rides along so the worker can tell one call's wake-ups apart from another's, and
 * `senderId` for the same reason it does on a chat push: every member of the group, the caller
 * included, is subscribed to this topic.
 *
 * It still says nothing. The callee's name, the caller's name and the fact that this is even a
 * call rather than a message are all things the *recipient* works out after decrypting the MQTT
 * offer; all that crosses Google is "something happened in this group".
 */
export function notifyCall(
	groupId: string,
	senderId: string,
	callId: string,
	mode: 'audio' | 'buzz' = 'buzz'
): void {
	void post('/notify', {
		topic: topicFor(groupId),
		kind: 'call',
		// The mode rides along because the service worker has to act on it before anything has
		// been decrypted: a buzz re-alerts on a timer so it reads as ringing rather than as one
		// chime, and a voice call it could not answer anyway says "missed".
		data: { groupId, senderId, callId, mode }
	});
}

/**
 * Asks the relay to push to this browser alone, so "is this device reachable at all" can be
 * answered without involving groups, topics or another phone.
 *
 * Worth having as a button rather than a curl recipe, because the two failure modes are
 * indistinguishable from the outside and the difference is the whole diagnosis. A token send,
 * unlike a topic send, is checked by FCM against a real registration: a dead or unknown token
 * comes back as an error, which fcm-service turns into a 502. So the relay accepting this means
 * FCM believes the token is live and has handed the message to Apple — and if nothing then
 * appears, the fault is on the phone (notification settings, Low Power Mode, a scheduled
 * summary) rather than anywhere in this app or the relay.
 */
export async function sendTestPush(token: string): Promise<boolean> {
	return post('/notify', {
		token,
		title: 'Family Group',
		body: 'Test notification',
		data: { test: 'true' }
	});
}

export function subscribeGroup(token: string, groupId: string): Promise<boolean> {
	return post('/subscribe', { token, topic: topicFor(groupId) });
}

export function unsubscribeGroup(token: string, groupId: string): Promise<boolean> {
	return post('/unsubscribe', { token, topic: topicFor(groupId) });
}
