/**
 * The app's version, in one place.
 *
 * `__APP_VERSION__` is substituted at build time from package.json's own `version` field (see
 * vite.config.ts and vitest.config.ts) rather than imported directly — importing package.json
 * into the client would pull the whole dependency tree's metadata into the bundle to print six
 * characters.
 */
export const version = __APP_VERSION__;
