/// <reference lib="webworker" />
import {
	cleanupOutdatedCaches,
	createHandlerBoundToURL,
	precacheAndRoute
} from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
import { clientsClaim } from 'workbox-core';
import { readUserIdInWorker } from '$lib/push/swIdentity';

declare let self: ServiceWorkerGlobalScope;

// The shell, precached exactly as before switching from generateSW to injectManifest — this
// plugin-injected manifest is what makes the app open offline. MQTT traffic is deliberately never
// cached; see vite.config.ts.
precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

// generateSW's `navigateFallback` had no equivalent config knob under injectManifest — this is
// that same "any navigation falls back to the app shell" rule, written out by hand instead.
registerRoute(new NavigationRoute(createHandlerBoundToURL('index.html')));

/**
 * The waiting worker steps aside only when the page asks it to.
 *
 * This used to be a bare `self.skipWaiting()` at the top, which contradicted
 * `registerType: 'prompt'` and is why a running app never noticed a new build: a worker that
 * skips waiting never reaches the `waiting` state, so `onNeedRefresh` never fired, no prompt
 * ever appeared, and the open page carried on running the bundle it had already loaded. An
 * installed PWA that is resumed rather than launched cold could sit on a version for days.
 *
 * `virtual:pwa-register`'s updateSW(true) posts this message to the waiting worker and reloads
 * once it takes control, so the reload and the swap happen in that order and never mid-session.
 */
self.addEventListener('message', (event) => {
	if ((event.data as { type?: string } | undefined)?.type === 'SKIP_WAITING')
		void self.skipWaiting();
});

// At module scope, which is where it belongs: clientsClaim() installs its own activate handler,
// so calling it from inside one registered a listener for an event already being dispatched.
clientsClaim();

/**
 * **A call push says "missed call", not "incoming call", and that is not pessimism.**
 *
 * When this handler runs, the tab is closed or backgrounded — which is the only reason a push
 * was needed. A browser cannot hold a connection in the background, so at this moment there is
 * no MQTT client alive to have received the caller's offer, and this worker deliberately touches
 * neither Dexie nor the vault (see swIdentity.ts), so it could not decrypt one if there were.
 * Three consequences, all of them structural rather than fixable here:
 *
 * 1. The notification cannot name the caller, and unlike the Android app there is no
 *    upgrade-in-place afterwards, because there is no connection to deliver the offer to.
 * 2. It cannot ring. A service worker has no `Audio`, and the `sound` option on
 *    `showNotification` is unimplemented in every browser — what plays is the operating system's
 *    one notification chime, once.
 * 3. "Answer" could not answer. A tap opens a tab; the page boots, Dexie unlocks, leader
 *    election runs, MQTT connects, and only then does the offer arrive — plausibly 5 to 15
 *    seconds from a cold worker, by which point the 45-second offer has very likely expired.
 *
 * So the honest word is "missed". A tap that leads to a dead line is worse than one that leads
 * to a callback, and the same rule applies here as everywhere else in this app: never imply
 * background delivery. Calling works properly in this app when the tab is open, which is the
 * case worth building well — see `$lib/call/`.
 *
 * FCM is otherwise a wake-up bell here exactly as on the Android side: the payload carries no message
 * content, only a generic title/body chosen server-side (see fcm-service's app/notify.py) plus
 * `data.groupId` and `data.senderId`. The actual chat text stays MQTT ciphertext this service
 * worker never sees — there is no MQTT client running here to decrypt it even if it wanted to,
 * since a service worker only runs the moments it is needed and cannot hold a live socket open.
 *
 * That means, unlike the Android app, this can never replace the placeholder with the real
 * decrypted message once the page fetches it — the tab may not even be open. The notification
 * says only "New message" and its job is done once the tap brings a tab to the front; from there
 * the running page's own MQTT connection and GroupSync-equivalent take over exactly as if the
 * person had opened the app on their own.
 */
self.addEventListener('push', (event) => {
	let payload: { data?: Record<string, string> } = {};
	try {
		payload = event.data?.json() ?? {};
	} catch {
		// Not JSON, or no body at all. Still worth a generic notification rather than none.
	}
	const groupId = payload.data?.groupId;
	const senderId = payload.data?.senderId;
	const isCall = payload.data?.kind === 'call';
	const isBuzz = isCall && payload.data?.mode === 'buzz';
	event.waitUntil(
		(async () => {
			if (!(await worthShowing(senderId))) return;
			if (isBuzz) {
				await buzz(groupId);
				return;
			}
			await self.registration.showNotification('Family Group', {
				body: isCall ? 'Missed call' : 'New message',
				icon: './icons/icon-192.png',
				// One per group, replaced rather than stacked, same reasoning as notify.ts's tag.
				tag: groupId ? `group:${groupId}` : 'family-group-push',
				data: { groupId }
			});
		})()
	);
});

/**
 * As close to ringing as a browser gets with no page running.
 *
 * A service worker has no `Audio`, no `AudioContext`, and `showNotification`'s `sound` option is
 * unimplemented everywhere — so a single notification is a single chime, which is a message
 * arriving, not a phone ringing. What *can* be done is re-show the same notification: with the
 * same `tag` and `renotify: true`, each one re-alerts, so the device chimes and vibrates again.
 * Repeat it on a timer and the result reads as ringing.
 *
 * Bounded deliberately, and to less than the offer's own 90-second life. A service worker is
 * killed when the browser decides it has run long enough, and a promise held open forever is the
 * fastest way to be killed mid-buzz; thirty seconds of insisting is also about as much as anyone
 * should be able to do to someone else's phone from a text field.
 *
 * `vibrate` is Chrome-on-Android only and silently ignored elsewhere, which is the correct way
 * for an addition like this to fail.
 */
async function buzz(groupId: string | undefined): Promise<void> {
	const options: NotificationOptions = {
		body: 'Buzzing your phone — open to stop it',
		icon: './icons/icon-192.png',
		tag: groupId ? `buzz:${groupId}` : 'family-group-buzz',
		data: { groupId },
		renotify: true,
		requireInteraction: true,
		vibrate: [0, 600, 400, 600]
	} as NotificationOptions;

	for (let i = 0; i < BUZZ_REPEATS; i++) {
		// Stop the moment somebody dismisses it: a notification the person has already dealt
		// with should not come back nine more times.
		if (i > 0) {
			const showing = await self.registration.getNotifications({ tag: options.tag });
			if (showing.length === 0) return;
		}
		await self.registration.showNotification('Family Group', options);
		if (i < BUZZ_REPEATS - 1) await new Promise((r) => setTimeout(r, BUZZ_GAP_MS));
	}
}

const BUZZ_REPEATS = 10;
const BUZZ_GAP_MS = 3000;

/**
 * Two pushes this device should not raise a notification for.
 *
 * **Its own.** Every member of a group is subscribed to the same topic, the sender included, so
 * FCM fans a chat message back to the phone it was typed on — which is why sending from the
 * iPhone buzzed the iPhone. `senderId` rides along in the data payload for exactly this check,
 * and the Android app has always made it (see PushMessagingService); the browser never did.
 *
 * **One that arrives while the app is open and being looked at.** MQTT is connected and the
 * message is already on screen, or a keystroke away from it — the same reasoning as notify.ts's
 * own visibility check. A tab that is merely open but not focused still gets the notification.
 *
 * Anything it cannot determine, it shows. A missed notification is a message somebody never
 * learns about; a spurious one is a glance.
 */
async function worthShowing(senderId: string | undefined): Promise<boolean> {
	if (senderId) {
		const own = await readUserIdInWorker().catch(() => null);
		if (own && own === senderId) return false;
	}
	const windows = await self.clients.matchAll({ type: 'window' });
	return !windows.some((client) => client.visibilityState === 'visible' && client.focused);
}

/** Focuses an already-open tab rather than always opening a new one, and lands on the group the
 * push was for when nothing is open yet. */
self.addEventListener('notificationclick', (event) => {
	event.notification.close();
	const groupId = (event.notification.data as { groupId?: string } | undefined)?.groupId;
	const url = groupId ? `./groups/${groupId}` : './';
	event.waitUntil(
		(async () => {
			const clientsList = await self.clients.matchAll({
				type: 'window',
				includeUncontrolled: true
			});
			for (const client of clientsList) {
				if ('focus' in client) {
					await client.focus();
					if ('navigate' in client) await (client as WindowClient).navigate(url);
					return;
				}
			}
			await self.clients.openWindow(url);
		})()
	);
});
