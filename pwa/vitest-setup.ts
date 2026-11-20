// Vitest runs in Node, which has WebCrypto and structuredClone but no IndexedDB. The fake is a
// faithful implementation of the same spec, including the structured-clone rules that decide
// whether a CryptoKey can be stored at all — which is the one thing the vault depends on.
import 'fake-indexeddb/auto';
