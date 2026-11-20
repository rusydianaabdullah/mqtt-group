# Family Group Web: Chat & Location

Private group chat and location sharing over MQTT, in a browser tab.

**It only works while it is open.** A browser cannot hold a connection or read your location
once you close the tab, so this app receives messages and positions while it is on screen and
keeps everything it already received readable offline. For sharing that keeps running in your
pocket, the Android app in [`../family-group`](../family-group) is the one to use — this is its
companion, for desktops, tablets and anyone who will not install an APK.

There is no server to run and no account to create. The app talks to an MQTT broker you choose
over `wss://`, and everything it publishes is encrypted with a key that only the group holds —
the broker sees ciphertext and topic names, nothing else. The build is static files; you can
serve them from any host, or from a local directory.

Wire-compatible with the Android app: same envelope, same topics, same group keys. A phone and
a browser tab in the same group talk to each other.

## Status

All seven phases are in. Identity and an encrypted store; the whole wire format, tested against
vectors the Android app verifies too (`vectors/wire-vectors.json`, checked by
`WireVectorsTest.kt` over there); brokers over `wss://` with a durable outbox and a leader-tab
election; groups, QR invites and a scanner; chat with photos, voice notes, replies and delivery
receipts; live location with the same publish gate and admin-set rules the phone uses; history
with paths and playback; multiple brokers and an encrypted `.fgbk` backup that a phone can read.

Not built, and outstanding on Android too: key rotation on kick. Until it exists a kick is a
request honest clients obey, not a lock on the door, and both apps say so where it matters.

[`plan.md`](plan.md) has the architecture, what a browser cannot do, the threat model and the
phase order.

## Building

Needs Node 20 or newer.

```
./build.sh              # production build into build/
./build.sh dev          # dev server with hot reload
./build.sh preview      # serve the production build locally
./build.sh test         # unit tests
./build.sh check        # svelte-check + TypeScript
./build.sh all          # check + test + build
```

`npm run dev` works too if you would rather skip the wrapper.

### Deploying

`build/` is static and path-relative, so it can be served from anywhere — which is the point,
and the real answer to "the origin is trusted absolutely" below.

The instance at `family-group.larus.id` is served by nginx straight out of a directory committed
to the **larus-compro** repository, which has no Node and no build step of its own:

```
cd family-group-pwa && ./build.sh
rsync -a --delete build/ ~/app/larus-compro/family-group.larus.id/
```

`--delete` matters: without it every past build's hashed assets accumulate there forever. See
that repository's README for the server side.

Two things ship alongside a build rather than after it:

- **The wire vectors.** A browser build whose codecs are ahead of the phones is how a tab and a
  handset stop talking, so a wire change lands in `../family-group` and here together.
- **`fcm-service`**, when a release needs a push shape it does not know yet. It is
  backwards-compatible by design so that it can always be deployed first.

## Where it is weaker than the Android app

Stated here rather than buried, because someone deciding which one to trust deserves to know:

- **At rest.** Group keys are non-extractable `CryptoKey` objects in IndexedDB — script cannot
  read them out — but they are protected by the browser's origin sandbox and your disk, not by
  a secure element. A stolen, unlocked laptop gives up the group; a stolen Android phone does not.
- **The origin is trusted absolutely.** Whoever serves these files can serve different files
  tomorrow, and they would run with the keys already stored. A signed APK has no equivalent hole.
  The build is static and path-relative so you can host it yourself, which is the real answer.
- **Certificates.** There is no "trust this anyway" toggle — the browser decides what it trusts
  and will not be argued with. A self-signed broker that worked on Android will refuse to
  connect until the certificate is trusted by your OS.
