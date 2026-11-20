# family-group-pwa (family-group-web)

The browser companion to `../family-group`: private group chat and location over MQTT
(`wss://`), end-to-end encrypted, built as static files. SvelteKit 5 + TypeScript + Tailwind 4 +
Vite, `adapter-static`, installable as a PWA. Read the repo-root `CLAUDE.md` for the house style.

**`plan.md` is the source of truth** for architecture, the wire format and the phase order.

## Build

```
./build.sh              # production build into build/
./build.sh dev          # dev server with hot reload
./build.sh preview      # serve the production build
./build.sh test         # vitest
./build.sh check        # svelte-check + tsc
./build.sh format       # prettier --write
./build.sh all          # check + test + build
```

npm scripts (`npm run dev|build|test|check|lint|format`) do the same; `npm run vectors`
regenerates `vectors/wire-vectors.json`. Run `check` and `test` before calling work done — this
project has real type checking and the Android side does not.

## Layout

| Path | What lives there |
|---|---|
| `src/lib/crypto/` | `envelope.ts`, `aead.ts`, `invite.ts`, `keyVault.ts`, `vault.ts` — with `.test.ts` beside each. |
| `src/lib/data/` | Dexie (IndexedDB) store, encrypted at rest. |
| `src/lib/mqtt/` | Broker connection over `wss://`, durable outbox, leader-tab election. |
| `src/lib/location/`, `src/lib/map/` | Publish gate and admin rules; map rendering. |
| `src/lib/stores/`, `src/lib/ui/` | Svelte 5 runes state and components. |
| `src/routes/` | Pages. |
| `src/service-worker.ts` | Offline shell. |
| `vectors/wire-vectors.json` | The shared wire vectors — see below. |
| `scripts/generate-vectors.ts` | Regenerates them. |

## Things that will catch you

- **Wire compatibility with `../family-group` is a contract.** Both apps verify
  `vectors/wire-vectors.json` (Kotlin side: `WireVectorsTest.kt`). Change the envelope, topics or
  key derivation only in both at once, regenerate the vectors, and run both test suites — or a
  phone and a browser tab stop talking to each other.
- **It only works while the tab is open.** A browser cannot hold a connection or read location in
  the background. Say so in any copy you write; do not imply background delivery. The Android app
  is the answer for that.
- **Leader-tab election** exists because several tabs must not each hold a broker connection.
  Anything that connects or publishes goes through it.
- **The QR invite is the group secret**, same as on Android — never log or export it.
- **No key rotation on kick**, same caveat as Android; the UI says so where it matters.
- Static output only: no server routes, no runtime secrets in the bundle.
- **`build/` is deployed by being committed to another repository.** `family-group.larus.id` is
  served by nginx out of `~/app/larus-compro/family-group.larus.id/`, which holds checked-in
  build output — so shipping the PWA means `./build.sh` here and
  `rsync -a --delete build/ ~/app/larus-compro/family-group.larus.id/` there, then a commit in
  *that* repo. Keep the `--delete`. See README's "Deploying".
