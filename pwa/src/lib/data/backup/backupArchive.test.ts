import { describe, expect, it } from 'vitest';
import { openBackup, sealBackup, VERSION } from './backupArchive';
import { utf8 } from '$lib/util/bytes';

const PASSPHRASE = 'correct horse battery staple';

describe('backup archive', () => {
	it('round-trips a payload', async () => {
		const sealed = await sealBackup(
			{ version: VERSION, groups: [{ name: 'Keluarga' }] },
			PASSPHRASE
		);
		const opened = await openBackup(sealed, PASSPHRASE);
		expect(opened.ok).toBe(true);
		expect(opened.ok && opened.payload).toEqual({
			version: VERSION,
			groups: [{ name: 'Keluarga' }]
		});
	});

	it('writes the header the Android app expects', async () => {
		const sealed = await sealBackup({}, PASSPHRASE);
		expect(String.fromCharCode(...sealed.subarray(0, 4))).toBe('FGBK');
		expect(sealed[4]).toBe(VERSION);
		// Iterations, big-endian, carried rather than assumed so a file written today still opens
		// after the cost is raised for new ones.
		expect(new DataView(sealed.buffer).getInt32(5, false)).toBe(210_000);
	});

	it('tells a wrong passphrase apart from a file that was never a backup', async () => {
		// One "failed" would leave someone retyping a correct passphrase at the wrong file.
		const sealed = await sealBackup({}, PASSPHRASE);
		const wrong = await openBackup(sealed, 'not the passphrase');
		expect(wrong.ok).toBe(false);
		expect(!wrong.ok && wrong.failure.kind).toBe('wrongPassphrase');

		const notABackup = await openBackup(
			utf8('this is a text file, not a backup at all'),
			PASSPHRASE
		);
		expect(!notABackup.ok && notABackup.failure.kind).toBe('notABackup');
	});

	it('names an unsupported version rather than blaming the passphrase', async () => {
		const sealed = await sealBackup({}, PASSPHRASE);
		sealed[4] = 99;
		const opened = await openBackup(sealed, PASSPHRASE);
		expect(!opened.ok && opened.failure).toEqual({ kind: 'unsupportedVersion', version: 99 });
	});

	it('refuses a rewritten iteration count, because the header is authenticated', async () => {
		// Without binding the header as AAD, an attacker could set iterations to 1 and let the
		// victim's own app do the cheap derivation for them.
		const sealed = await sealBackup({}, PASSPHRASE);
		new DataView(sealed.buffer).setInt32(5, 1, false);
		const opened = await openBackup(sealed, PASSPHRASE);
		expect(opened.ok).toBe(false);
	});

	it('refuses an iteration count that would hang the browser for a week', async () => {
		const sealed = await sealBackup({}, PASSPHRASE);
		new DataView(sealed.buffer).setInt32(5, 2_000_000_000, false);
		const opened = await openBackup(sealed, PASSPHRASE);
		expect(!opened.ok && opened.failure.kind).toBe('notABackup');
	});

	it('refuses a truncated file', async () => {
		const sealed = await sealBackup({}, PASSPHRASE);
		const opened = await openBackup(sealed.subarray(0, 20), PASSPHRASE);
		expect(!opened.ok && opened.failure.kind).toBe('notABackup');
	});
});
