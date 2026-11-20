import { initializeApp, type FirebaseApp } from 'firebase/app';
import { getMessaging, isSupported, type Messaging } from 'firebase/messaging';

import {
	PUBLIC_FIREBASE_API_KEY,
	PUBLIC_FIREBASE_APP_ID,
	PUBLIC_FIREBASE_AUTH_DOMAIN,
	PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
	PUBLIC_FIREBASE_PROJECT_ID,
	PUBLIC_FIREBASE_STORAGE_BUCKET
} from '$env/static/public';

// Read from .env (gitignored) at build time; see .env.example.
const firebaseConfig = {
	apiKey: PUBLIC_FIREBASE_API_KEY,
	authDomain: PUBLIC_FIREBASE_AUTH_DOMAIN,
	projectId: PUBLIC_FIREBASE_PROJECT_ID,
	storageBucket: PUBLIC_FIREBASE_STORAGE_BUCKET,
	messagingSenderId: PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
	appId: PUBLIC_FIREBASE_APP_ID
};

let app: FirebaseApp | undefined;
let messaging: Promise<Messaging | undefined> | undefined;

/** Undefined wherever the Web Push stack does not exist at all — Safari before 16.4, any
 * browser with third-party cookies or service workers disabled, and every server-side render. */
export function firebaseMessaging(): Promise<Messaging | undefined> {
	if (typeof window === 'undefined') return Promise.resolve(undefined);
	if (!messaging) {
		messaging = isSupported().then((supported) => {
			if (!supported) return undefined;
			app ??= initializeApp(firebaseConfig);
			return getMessaging(app);
		});
	}
	return messaging;
}
