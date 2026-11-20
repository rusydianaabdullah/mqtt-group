// No server, no server-side rendering: every screen reads IndexedDB and a live socket, both of
// which exist only in the browser. `ssr = false` also means the shell that ships is the shell
// that runs, which is what makes the whole build a set of static files.
export const ssr = false;
export const prerender = false;
export const trailingSlash = 'always';
