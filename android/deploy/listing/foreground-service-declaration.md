# Play Console — Foreground service permissions declaration

For **App content → Foreground service permissions**, which produced "Your app uses the following
undeclared foreground service permissions".

The app declares one service, `MqttService`, with `android:foregroundServiceType="dataSync|location"`
(`AndroidManifest.xml:75`). Both types need their own declaration and their own video.

---

## `dataSync` would have crashed the app on Android 15+ — fixed

Not a Play-form problem; a shipping bug that answering this form uncovered.

Since Android 15, a foreground service of type `dataSync` may run for at most **6 hours in any
24-hour period**. When that budget runs out the system calls `Service.onTimeout()`, and the app
must stop the service — or remove the `dataSync` type — within seconds. An app that does neither
is killed with `ForegroundServiceDidNotStopInTimeException`.

`MqttService` holds the MQTT connection open indefinitely and **does not implement
`onTimeout()`**. `targetSdk` is 36, so the behaviour applies. On any Android 15 or 16 device,
roughly six hours after the service starts, the app crashes.

That has to be fixed regardless of what this form says. The options, roughly in order of
honesty:

1. **Implement `onTimeout()`** and, when it fires, either drop to `location` only if the user is
   sharing, or stop the connection and let the existing keep-alive worker bring it back. The
   worker already exists (`ConnectionKeepAliveWorker`) and already restarts the service.
2. **Stop using `dataSync`** and accept that messages arrive when the app is opened, plus
   whatever the keep-alive worker catches. Cheapest, and much weaker as a messenger.
3. Do nothing and ship a six-hour timer on a crash. Not an option.

**Fixed.** `MqttService` now implements both `onTimeout` overloads: if the phone is genuinely
sharing location it re-enters the foreground as a `location`-only service, which carries no such
budget and keeps the connection; otherwise it stops, and the keep-alive worker and the next app
launch bring it back. The budget itself resets when the user next brings the app to the
foreground.

---

## Data sync — "What tasks require your app to use FOREGROUND_SERVICE_DATA_SYNC?"

```
The app keeps one MQTT connection open to the broker the user has configured, so that messages,
photos and voice notes sent by other members of their group arrive and raise a notification while
the app is closed. This app has no push service: there is no account, no Google Play Services
dependency and no server of ours anywhere, so the connection to the user's own broker is the only
way a message can reach the phone. The service also drains a durable outbox, delivering anything
the user sent while offline once a network returns. A persistent notification shows the
connection state for as long as the service runs, and the user can stop it from Settings.
```

**Why an alternative API will not do**, which is the follow-up question Play asks:

```
WorkManager and JobScheduler are batched and deferrable by design; the shortest periodic work
Android will schedule is 15 minutes, and Doze can extend that considerably. A message that
arrives a quarter of an hour late is not a message in a chat app. Firebase Cloud Messaging is the
usual answer and is deliberately not available here: the app's premise is that no third party —
including Google — sees who is talking to whom, and FCM would route every notification through
one. The app already uses WorkManager as a safety net for reconnection, but it cannot carry
delivery.
```

## Location — "What tasks require your app to use FOREGROUND_SERVICE_LOCATION?"

```
When a member has switched on location sharing for a group, the app publishes their position to
that group at the interval the group's admin has set — by default every minute, and only after
they have moved 25 metres. The point of a shared family map is that it keeps working while the
phone is in a pocket, so the sampling has to continue while the app is closed. The location type
is only added when the user has granted location permission, and the service runs with a
permanent notification whenever it is active. Sharing is off by default, must be enabled by the
group's admin for the group and then by each member for themselves, and stops immediately when
the member switches it off.
```

## About the notification: a correction

An earlier version of this file claimed the service could run indefinitely with no visible
notification, on the strength of a `dumpsys` reading. That reading was wrong — the grep pattern
did not match this platform's output format at all, so "zero live notifications" meant "zero
lines matched", not "nothing is showing".

What was actually happening in that session: **the app deliberately stops `MqttService` when it
is backgrounded**, unless the user has granted the background-connection exemption in Settings
(`MainActivity`'s `LifecycleStartEffect` → `BackgroundPolicy.canPersistInBackground`). No
service, therefore no notification and no delivery — which is the documented, intended design,
not a defect.

One narrower concern does survive and is now handled: since Android 14 a user can swipe a
foreground-service notification away, and the service used to re-post only when the connection
*state* changed, so a dismissal could persist for as long as the connection simply stayed up.
`MqttService` now re-posts it every four minutes as well. Cheap, and it means a dismissal is
temporary rather than permanent.

## User-visible evidence, for both types

A reviewer will look for the service being noticeable to the user. It is:

- A persistent notification, channel "Connection", from the moment the service starts. It states
  the connection state — Connected, Reconnecting, Offline — and cannot be swiped away.
- Tapping it opens the app.
- Settings has a background-connection control, and turning off location sharing (per group)
  removes the location half of the service's work.

## Videos

Both are recorded and in `deploy/videos/`. They are **files**, and the Play form wants a **link**
— upload each to YouTube as **unlisted** and paste the URL.

```
deploy/videos/data-sync.mp4    ~55s   FOREGROUND_SERVICE_DATA_SYNC
deploy/videos/location.mp4     ~58s   FOREGROUND_SERVICE_LOCATION
```

**data-sync.mp4** — the chat open and "Connected"; Home, so the app is closed; the shade showing
the ongoing "Family Group · Connected" notification; a message sent from a second phone arriving
as a heads-up notification **with the app still closed**; the shade showing both notifications;
the app reopened with the message in the conversation.

**location.mp4** — the group's Location screen with "Share my location" switched on by the user
(which is the "user-initiated location sharing" box ticked on the form); the map showing the
position; Home; the shade with the service notification; the phone moving while the app is
closed; the app reopened with the position updated to "just now".

Both were recorded on a device with the battery-optimisation exemption granted. Without it the
app stops its own service when backgrounded by design, and neither demonstration is possible —
worth knowing if you re-record.

## One correction to the form itself

The screenshot of the form shows **"Backing up, restoring"** ticked under Data sync → Network
processing. That is the wrong box and it contradicts the video.

The app backs up and restores nothing over the network. What the service does is hold a
connection so that *messages* arrive — and the video shows exactly that, a chat message landing
while the app is closed. A reviewer comparing the two sees a declaration that does not match the
demonstration, which is a rejection.

Tick **Other** under Network processing instead, and paste the "Data sync" answer above into the
description it asks for.

The Location side is already right: **"User-initiated location sharing"** is precisely what the
app does, and it is what the video shows.
