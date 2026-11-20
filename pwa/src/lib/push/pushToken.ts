/** This browser's current FCM registration token, kept in localStorage so a page reload does not
 * have to re-mint one (and re-subscribe every group) on every visit. */
const KEY = 'family-group:push-token';

export function getStoredPushToken(): string | null {
	if (typeof localStorage === 'undefined') return null;
	try {
		return localStorage.getItem(KEY);
	} catch {
		return null;
	}
}

export function setStoredPushToken(token: string): void {
	if (typeof localStorage === 'undefined') return;
	try {
		localStorage.setItem(KEY, token);
	} catch {
		// Private browsing, storage full, or disabled — a fresh token is minted next visit.
	}
}
