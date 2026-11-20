# Family Group: Chat & Location

Private group chat and location sharing over MQTT.

There is no server to run and no account to create. The app talks to an MQTT broker you
choose, and everything it publishes is encrypted with a key that only the group holds — the
broker sees ciphertext and topic names, nothing else. On the phone, the database and every
attachment are encrypted at rest with a key kept in the Android Keystore.

`applicationId` is `id.larus.mqttgroup`, minSdk 29 (Android 10), Kotlin + Compose.

## Status

Phase 2. The app generates your identity on first launch, keeps everything in a
SQLCipher-encrypted database whose key is wrapped by the Android Keystore, and connects to an
MQTT broker you configure — with a durable outbox behind it, so nothing is lost to a dead
network. Groups exist: create one, invite people by showing them a QR code, remove members,
leave or delete. Chat comes next.
[`plan.md`](plan.md) has the architecture, the threat model and the phase order.

## Building

Needs JDK 17 and the Android SDK.

```
./build.sh              # debug APK
./build.sh test         # unit tests
./build.sh install      # build and adb install to the connected device
./build.sh release
```

`build.sh` finds a JDK (preferring Android Studio's bundled runtime) and your Android SDK on
its own, and writes `local.properties` for you. `./gradlew assembleDebug` works too if you
would rather set `JAVA_HOME` yourself.
