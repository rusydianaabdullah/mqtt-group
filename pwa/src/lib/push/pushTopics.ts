import { deleteToken, getToken, onMessage } from 'firebase/messaging';
import { firebaseMessaging } from './firebaseApp';
import { getStoredPushToken, setStoredPushToken } from './pushToken';
import { lastPushError, subscribeGroup, unsubscribeGroup } from './pushSender';
import { db } from '$lib/data/db/schema';
import { notificationsAllowed } from '$lib/ui/notify';
import { pushServiceWorker, serviceWorkerState } from './serviceWorkerReady';

// Paste the "Key pair" value from Firebase Console → Project settings → Cloud Messaging → Web
// Push certificates (generate one if the project has none yet). It is public by nature — handed
// to every browser that subscribes — but without it getToken() cannot mint a token FCM will
// accept sends against.
const VAPID_KEY =
	'BNp-rCKEoG44rXHKy19dhz3rRZZtEOKrWSCh0QMXjDjeUMXUFloZKWlGLacyXyTGvHgXGWzKBDFEFWWwv4_eRHA';

/** Groups this browser has a *confirmed* subscription for, and the token they were made with.
 * Both halves matter: a subscription belongs to a token, so a token change invalidates every
 * entry even though the group list itself has not moved. */
let subscribed = new Set<string>();
let subscribedToken: string | null = null;

/** Why there is no token, when there is none. Null both when one exists and before anything
 * has tried — the Settings screen only reads it once a sync has run. */
let tokenError: string | null = null;

/** How many groups the last sync failed to subscribe, so the Settings screen can say so rather
 * than reporting a smaller number of subscriptions and leaving the reader to spot it. */
let subscribeFailures = 0;
/** When the last sync finished, for a button that has to prove it did something. */
let lastSyncAt: number | null = null;
/** What the service worker was doing at the last sync, in words — see serviceWorkerState. */
let swState: string | null = null;

let listening = false;

/**
 * Nothing here is allowed to hang forever.
 *
 * `getToken()` and the service worker lookup both return promises that can simply never settle —
 * an FCM endpoint a network is quietly swallowing, or (on iOS, reliably) `serviceWorker.ready` —
 * and the old code awaited them inside a guard that stayed locked while they were pending. That
 * is why "Refresh push token" could do nothing at all, with nothing in the server's log and
 * nothing on screen: the first sync of the session was still waiting, and every later call
 * returned early.
 */
function withTimeout<T>(work: Promise<T>, ms: number, what: string): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		const timer = setTimeout(
			() => reject(new Error(`${what} did not answer within ${Math.round(ms / 1000)}s`)),
			ms
		);
		work.then(
			(value) => {
				clearTimeout(timer);
				resolve(value);
			},
			(err) => {
				clearTimeout(timer);
				reject(err);
			}
		);
	});
}

/**
 * Mints this browser's FCM token, always asking the SDK rather than trusting what localStorage
 * remembers.
 *
 * The stored copy is a cache for the common case, not the source of truth. FCM rotates web
 * tokens on its own, and on iOS the push subscription is torn down and rebuilt by events this
 * app never sees — re-adding the PWA to the Home Screen, Safari clearing site data, the service
 * worker being evicted and re-registered. Every one of those leaves a token in localStorage
 * that FCM will no longer deliver to. Reading it back and using it forever is how push worked
 * exactly once and then went quiet: sends kept succeeding, aimed at a token that was dead.
 *
 * getToken() is cheap when nothing has changed — the SDK returns its own cached value — so
 * calling it on every sync costs a promise, not a round trip.
 *
 * Requires notification permission to already be granted; this never prompts on its own, see
 * notify.ts's askForNotifications for why.
 */
export async function ensurePushToken(force = false): Promise<string | null> {
	if (typeof window === 'undefined' || !('serviceWorker' in navigator)) {
		tokenError = 'This browser has no service worker, so it cannot receive push at all.';
		return null;
	}
	if (!notificationsAllowed()) {
		tokenError = 'Notifications have not been allowed yet.';
		return null;
	}
	const messaging = await firebaseMessaging();
	if (!messaging) {
		// Overwhelmingly the iPhone case, and the one that used to fail in total silence: iOS
		// grants Web Push only to a web app that has been added to the Home Screen. In an
		// ordinary Safari tab the APIs are missing, isSupported() resolves false, and every
		// call below here is skipped — no token, no subscription, no error, nothing sent.
		tokenError =
			'This browser cannot do Web Push. On iPhone that usually means the app is open in ' +
			'a Safari tab: use Share → Add to Home Screen, then open it from there.';
		return null;
	}
	try {
		// Not `navigator.serviceWorker.ready` — that promise never settles in an iOS Home Screen
		// app, which is what "the service worker did not answer within 15 s" was reporting on a
		// phone whose worker was active the whole time. See serviceWorkerReady.ts.
		const registration = await pushServiceWorker(30_000);
		// `force` is what makes the Settings button honest. getToken() hands back the SDK's
		// cached token when nothing has changed, so on its own "Refresh push token" refreshed
		// nothing — the one case it exists for is a token FCM has quietly retired, which still
		// reads as a perfectly good token from in here. Deleting first is what mints a new one.
		if (force) {
			try {
				await withTimeout(deleteToken(messaging), 15_000, 'Deleting the old token');
			} catch (err) {
				// A token FCM has already forgotten cannot be deleted, which is not a reason to
				// stop: minting the replacement is the part that matters.
				console.warn('FCM token delete failed', err);
			}
		}
		const token = await withTimeout(
			getToken(messaging, {
				vapidKey: VAPID_KEY,
				serviceWorkerRegistration: registration
			}),
			20_000,
			'Firebase'
		);
		if (token) {
			tokenError = null;
			setStoredPushToken(token);
			return token;
		}
		tokenError = 'Firebase returned no token for this browser.';
		return null;
	} catch (err) {
		console.warn('FCM token fetch failed', err);
		tokenError = `Could not mint a push token: ${err instanceof Error ? err.message : String(err)}`;
		// Fall back to whatever was last known to work rather than losing push entirely on one
		// bad network moment. If it is stale the next successful mint replaces it. Not after a
		// forced refresh, though: there the old token is exactly what was being thrown away, and
		// handing it back would quietly resubscribe the dead endpoint the person was replacing.
		return force ? null : getStoredPushToken();
	}
}

/**
 * What the push stack currently believes about itself, for the Settings screen.
 *
 * Worth having because every failure here is invisible by nature: there is no error to see, the
 * notification simply never arrives, which looks exactly like nobody having sent anything. The
 * token's tail is included so it can be read off the phone and used to aim a test send at this
 * one device — a topic send returns a message id whether or not anybody is subscribed to the
 * topic, so it can never tell you this browser is reachable; a token send can.
 */
export interface PushStatus {
	token: string | null;
	tokenTail: string | null;
	tokenError: string | null;
	subscribedGroups: number;
	/** Groups the relay refused to subscribe on the last run. Zero when everything landed. */
	subscribeFailures: number;
	lastSyncAt: number | null;
	/** The service worker's state in words, since a phone has no DevTools to ask it directly. */
	serviceWorker: string | null;
	relayError: string | null;
}

export function pushStatus(): PushStatus {
	const token = getStoredPushToken();
	return {
		token,
		tokenTail: token ? token.slice(-12) : null,
		tokenError,
		subscribedGroups: subscribed.size,
		subscribeFailures,
		lastSyncAt,
		serviceWorker: swState,
		relayError: lastPushError()
	};
}

/**
 * Keeps this browser's FCM topic subscriptions matching the groups it is actually in.
 *
 * There is no server tracking group membership, so nothing else knows when to unsubscribe a
 * group this browser has left — the fix is to resync against the live group list on every call
 * rather than trying to catch every place membership can change (create, join, leave, kick,
 * delete-for-everyone, a restored backup). Subscribe/unsubscribe are idempotent server-side, so
 * calling this on every group-list change costs nothing extra. Call it whenever `db.groups`
 * changes, the same way Android's PushTopics follows its own group list.
 *
 * Two things it is careful about, both of which used to break it silently:
 *
 * A group is recorded as subscribed only once fcm.larus.id says it is. Fire-and-forget marked
 * failures as successes, and since the diff is against that record, a subscribe that failed was
 * never attempted again — one blocked call meant that group never woke this browser again.
 *
 * A concurrent call is queued, not dropped, and the caller can wait for it. The layout re-runs
 * this on every change to the group list, and the first of those arrives while the very first
 * token mint is still in flight. An early return there threw away the run that had the real
 * group list in it — and worse, the flag that guarded it was only cleared when the run it was
 * guarding finished, so a mint that never settled locked every later call out for the life of
 * the tab. That is the whole of "Refresh push token does nothing, and the relay logs no call":
 * the button's sync returned before it had made one. Runs are a chain now, and the promise this
 * returns settles when *this* caller's run is done, carrying the status it produced.
 */
export function syncPushTopics(force = false): Promise<PushStatus> {
	// One run waiting behind the one in flight is enough — a third caller would only repeat the
	// second's work, since every run reads the group list at the moment it starts. A forced run
	// always gets its own, because it is somebody pressing a button and expecting an answer
	// about this press.
	if (queued >= 2 && !force) return chain.then(pushStatus, pushStatus);
	queued += 1;
	chain = chain
		.catch(() => {})
		.then(() => runSync(force))
		.catch((err) => {
			// Never leave the chain rejected: one failed sync must not make every later one
			// resolve instantly with somebody else's error.
			console.warn('push sync failed', err);
			tokenError = err instanceof Error ? err.message : String(err);
		})
		.then(() => {
			queued -= 1;
		});
	return chain.then(pushStatus, pushStatus);
}

/** The serial queue. Runs never overlap — two at once would diff against the same stale record
 * of what is subscribed and make the same calls twice. */
let chain: Promise<void> = Promise.resolve();
let queued = 0;

async function runSync(force: boolean): Promise<void> {
	// Recorded before the mint rather than after, so it still says what the worker was doing in
	// the case where the mint is what failed.
	swState = await serviceWorkerState().catch(() => null);
	const token = await ensurePushToken(force);
	lastSyncAt = Date.now();
	if (!token) return;

	// A new token owns none of the old one's subscriptions. Retire the old token from every
	// topic it held — otherwise FCM keeps fanning out to a dead endpoint forever — and treat
	// every group as unsubscribed so the loop below re-makes them all under the new token.
	if (subscribedToken && subscribedToken !== token) {
		const stale = subscribedToken;
		await Promise.all([...subscribed].map((groupId) => unsubscribeGroup(stale, groupId)));
		subscribed = new Set();
	}
	subscribedToken = token;

	const groups = await db.groups.toArray();
	const current = new Set(groups.map((g) => g.groupId));

	let failures = 0;
	await Promise.all(
		[...current]
			.filter((groupId) => !subscribed.has(groupId))
			.map(async (groupId) => {
				if (await subscribeGroup(token, groupId)) subscribed.add(groupId);
				else failures += 1;
			})
	);
	await Promise.all(
		[...subscribed]
			.filter((groupId) => !current.has(groupId))
			.map(async (groupId) => {
				if (await unsubscribeGroup(token, groupId)) subscribed.delete(groupId);
			})
	);
	subscribeFailures = failures;
	lastSyncAt = Date.now();
}

/** Forgets every confirmed subscription so the next sync re-makes them from scratch. For the
 * Settings screen's retry, where the point is to redo work that previously failed. */
export function forgetPushSubscriptions(): void {
	subscribed = new Set();
	subscribedToken = null;
}

/**
 * The foreground half of receiving a push. FCM only calls this while the page's own JS is alive
 * — a killed tab or a browser that was never open gets its notification from the service worker
 * instead (see src/service-worker.ts's 'push' handler), never from here.
 *
 * Nothing needs to happen if the tab is actually visible: MQTT is connected and GroupSync's
 * equivalent is already delivering the real message, same reasoning as notify.ts's own
 * visibility check. A background tab (open but not focused) gets the same generic placeholder
 * the service worker would have shown.
 */
export async function listenForForegroundPush(): Promise<void> {
	if (listening || typeof window === 'undefined') return;
	listening = true;
	const messaging = await firebaseMessaging();
	if (!messaging) return;
	onMessage(messaging, () => {
		if (document.visibilityState === 'visible') return;
		if (!notificationsAllowed()) return;
		try {
			new Notification('Family Group', { body: 'New message', icon: './icons/icon-192.png' });
		} catch {
			// Some browsers refuse a constructed Notification outside a service worker; nothing
			// worth breaking the message pipeline over.
		}
	});
}
