# Releasing to Google Play

Application ID: `id.larus.mqttgroup` (set in `app/build.gradle.kts`).

Store listing text, graphics and the screenshot script live in `deploy/`.

## 1. Signing

The upload key is `andro-key0.jks` in this directory (gitignored — keep a backup somewhere safe;
losing it means you cannot update the listing without Play's key-reset process).

```bash
cp keystore.properties.example keystore.properties
```

Fill in `storeFile`, `storePassword`, `keyAlias` and `keyPassword` yourself. To see which aliases
the keystore holds:

```bash
keytool -list -v -keystore andro-key0.jks
```

`app/build.gradle.kts` also reads the same four values from the environment
(`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`), so CI needs no file on disk.

## 2. Build the bundle

Play takes an AAB, not an APK:

```bash
./build.sh bundle
```

Output lands in `app/build/outputs/bundle/release/app-release.aab`. The script says so if
`keystore.properties` is missing, because an unsigned bundle is rejected on upload rather than
at build time.

Sanity-check the signed release on a real device before uploading — an AAB cannot be installed
directly, so build the APK for that:

```bash
./build.sh release && adb install -r app/build/outputs/apk/release/app-release.apk
```

## 3. Bumping versions

Every upload needs a higher `versionCode` than the last. Both live in `app/build.gradle.kts`
`defaultConfig`. They are still at the placeholders — `versionCode = 1`, `versionName = "0.1"` —
so set them deliberately before the first upload. A `versionCode` is spent permanently once
uploaded, even to a closed track.

## 4. Declarations this app will be asked for

This app is on the demanding end of Play's review, and all of it comes from the manifest:

- **Location** — fine and coarse only; `ACCESS_BACKGROUND_LOCATION` is deliberately not
  declared, so no demonstration video is required. Play still asks for a written declaration,
  written out field by field in `deploy/listing/location-declaration.md`.
- **Foreground service types `location` and `dataSync`** — each needs its use described. Data
  sync holds the MQTT connection so messages arrive; location publishes positions on the
  interval the group's admin set.
- **`CAMERA`** — scanning an invite QR code, and taking a photo to send.
- **`RECORD_AUDIO`** — voice notes, on user request.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — optional, asked for in Settings, and the app
  explains in plain words what declining costs. Play scrutinises this one; the honest answer is
  that a chat app whose connection is killed on backgrounding delivers messages late.

**Data safety** — answer from what the app does, not from what sounds safest. Nothing is
collected by us and there is no server of ours, but messages, photos, voice notes and precise
location *are* transferred off the device to the user's own broker. Declare them, purpose "App
functionality", encrypted in transit. `deploy/listing/en-US.md` has the full breakdown.

A **privacy policy URL** is required for the listing and again in Data Safety.

## 5. Before the first public release

**Switch the map off Google's tile endpoint.** `MapStyle.styleJson` points at
`mt1-3.google.com/vt`, which is Google's internal endpoint, not the Maps Platform API. Using it
outside a Google Maps product is against Google's terms — acceptable for a private build among
family, a real risk on a public listing, and particularly so when the listing is on Google Play.
`MapStyle.openFreeMapStyleUrl` is the one-line switch back to OpenStreetMap vector tiles.

**Take the screenshots.** `deploy/screenshots/` is empty, and unlike adzan-time this app cannot
be photographed from a fresh install — it needs a broker, a group and a second member. See
`deploy/README.md`.

**Finish phase 7.** Key rotation on kick is still open (see `plan.md`). Shipping a group app
where a removed member keeps a working key is a defensible v1 as long as nothing in the listing
claims otherwise — the current description does not.
