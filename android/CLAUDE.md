# family-group

Private group chat and live location over MQTT, end-to-end encrypted, with no server of its own.
`applicationId` `id.larus.mqttgroup`, minSdk 29, compileSdk 36, Kotlin + Compose + Material 3.
Read the repo-root `CLAUDE.md` for the house style first.

**`plan.md` is the source of truth** for architecture, the wire format, the threat model and the
phase order. Read the relevant section of it before changing anything in `crypto/`, `mqtt/` or the
database schema — decisions there were made deliberately and are written down with their reasons.

## Build

```
./build.sh              # debug APK
./build.sh test         # unit tests — crypto, envelope, wire vectors
./build.sh install
./build.sh release      # signed when keystore.properties exists
./build.sh bundle       # AAB for Play
```

`RELEASE.md` covers the Play flow. The signing keystore is `andro-key0.jks` — the Play upload
key, and the one to keep working with; `family-group.jks` is an earlier key still sitting in this
directory and signing with it produces an APK that cannot update an existing install. Both, and
`keystore.properties`, are untracked.

## Layout

| Path | What lives there |
|---|---|
| `crypto/` | `Envelope.kt` (the wire format), `Aead.kt` (AES-256-GCM), `KeyVault.kt` (Keystore-wrapped DB key), `Invite.kt` (QR payload), `PassphraseKey.kt`. |
| `data/db/` | Room over SQLCipher. Identity and broker config are rows here, not EncryptedSharedPreferences — one encrypted store, one thing to wipe. |
| `data/blob/` | Attachments as separate AES-GCM files in app-internal storage. |
| `data/repo/`, `data/backup/` | Repositories, and the encrypted `.fgbk` backup a browser tab can also read. |
| `mqtt/` | `MqttConnection` (HiveMQ MQTT 5), `MqttService` (foreground), `OutboxPump` (durable send), `Topics`, `GroupSync`, chat/control/location message codecs. |
| `location/` | `LocationEngine` (publish gate, admin rules) and `RetentionWorker`. |
| `map/` | MapLibre + OpenFreeMap tiles, isolated so the database never knows the map vendor. |
| `push/` | FCM wake-ups only — the payload still arrives over MQTT. |
| `ui/` | Compose screens plus `MainViewModel`. |

## Things that will catch you

- **The QR code is the group secret.** Anything that logs, exports, screenshots or backs up an
  invite is a disclosure. Treat invite payloads like passwords.
- **Wire compatibility with `../family-group-pwa` is a contract.** Both apps verify the same
  vectors (`../family-group-pwa/vectors/wire-vectors.json`, checked here by `WireVectorsTest.kt`).
  Any change to the envelope, topics or key derivation must land in both, with regenerated
  vectors, or a phone and a browser tab stop talking.
- **Kicking is not cryptographic yet.** No key rotation on kick — a removed member who kept the
  key can still decrypt. Both apps say so in the UI; do not write copy that claims otherwise.
- **A shared group key gives no per-sender authenticity.** A member can impersonate a member.
  Documented and accepted; do not build features that assume sender proof.
- **The broker sees ciphertext plus topic names.** Keep anything identifying out of topic
  structure.
- **Everything outbound goes through the outbox**, so it survives a dead network. Do not publish
  straight from a screen.
