/**
 * Telling someone a message arrived, within what a browser actually allows.
 *
 * The tab title carries the unread count, which works in every browser and costs no permission
 * at all. A real notification needs permission, so it is offered rather than requested on load —
 * an unprompted permission dialog on a first visit is the most reliably ignored thing on the web.
 *
 * `notifyMessage` below only ever covers the tab-open case: something arrived while this page
 * had focus but was showing a different group. The tab-closed case — a message arriving with
 * nothing open at all — is FCM's job, not this file's; see $lib/push for the fcm.larus.id relay
 * and the service worker's own 'push' handler, which is what actually shows something when there
 * is no running page left to call this function from.
 */

let baseTitle = 'Family Group';

export function setUnreadBadge(count: number): void {
	if (typeof document === 'undefined') return;
	document.title = count > 0 ? `(${count}) ${baseTitle}` : baseTitle;
	// The app badge on an installed PWA, where it exists. Chromium-only, and silently absent
	// elsewhere, which is exactly how an optional nicety should fail.
	const nav = navigator as Navigator & {
		setAppBadge?(n?: number): Promise<void>;
		clearAppBadge?(): Promise<void>;
	};
	if (count > 0) void nav.setAppBadge?.(count).catch(() => undefined);
	else void nav.clearAppBadge?.().catch(() => undefined);
}

export function rememberTitle(title: string): void {
	baseTitle = title;
}

export function notificationsAvailable(): boolean {
	return typeof Notification !== 'undefined';
}

export function notificationsAllowed(): boolean {
	return notificationsAvailable() && Notification.permission === 'granted';
}

export function notificationsRefused(): boolean {
	return notificationsAvailable() && Notification.permission === 'denied';
}

/** Asks, and only ever from a button someone pressed. */
export async function askForNotifications(): Promise<boolean> {
	if (!notificationsAvailable()) return false;
	return (await Notification.requestPermission()) === 'granted';
}

/**
 * Raises one, if it would tell the person anything. A message for the chat already on screen is
 * not news, and neither is one that arrived while they were looking at the tab.
 */
export function notifyMessage(groupName: string, sender: string, preview: string): void {
	if (!notificationsAllowed()) return;
	if (typeof document !== 'undefined' && document.visibilityState === 'visible') return;
	try {
		new Notification(groupName, {
			body: `${sender}: ${preview}`,
			icon: './icons/icon-192.png',
			// One notification per group rather than a stack of them: a conversation that arrives
			// in six messages should replace itself, not queue six times.
			tag: `group:${groupName}`
		});
	} catch {
		// Some browsers throw for constructed notifications outside a service worker. Nothing to
		// do about it, and nothing worth breaking the message pipeline over.
	}
}

/**
 * An incoming call or buzz, for the case the call overlay cannot cover: the tab is open but the
 * person is looking at something else. A buzz never says "incoming call" — it is not one, and
 * the word would promise a voice that is never coming.
 *
 * Unlike a message, this is worth raising even though a ringtone is also playing — a browser
 * that has never been interacted with may refuse to play audio at all (see `Ringer`), and on a
 * phone the notification is often the only thing that reaches past the browser. It is the same
 * reasoning as the ringtone being ours to play rather than the channel's on Android: the visible
 * part and the audible part fail independently, so both are attempted.
 *
 * `requireInteraction` keeps it up rather than letting it fade after a few seconds, because a
 * call that has stopped ringing is a call the person had no chance to answer. Chrome honours it;
 * Safari ignores it, and there is nothing to be done about that.
 *
 * Returns the notification so the caller can take it down the moment the call is answered,
 * declined or missed — a ring left on screen after the call is over is worse than none.
 */
export function notifyIncomingCall(from: string, isBuzz = false): Notification | null {
	if (!notificationsAllowed()) return null;
	if (typeof document !== 'undefined' && document.visibilityState === 'visible') return null;
	try {
		return new Notification(from, {
			body: isBuzz ? 'Buzzing your phone' : 'Incoming call',
			icon: './icons/icon-192.png',
			tag: 'family-group-call',
			requireInteraction: true
		});
	} catch {
		// As above: some browsers refuse a constructed Notification. The overlay and the
		// ringtone are unaffected.
		return null;
	}
}
