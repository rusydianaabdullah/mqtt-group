<script lang="ts">
	import TopBar from '$lib/ui/TopBar.svelte';
	import { exportBackup, importBackup, type RestoreSummary } from '$lib/data/backup/backupRepo';
	import { identity } from '$lib/stores/identity.svelte';
	import { connection } from '$lib/stores/connection.svelte';

	let exportPass = $state('');
	let exportConfirm = $state('');
	let importPass = $state('');
	let file = $state<File | null>(null);
	let picker = $state<HTMLInputElement | null>(null);
	let busy = $state(false);
	let message = $state<{ ok: boolean; text: string } | null>(null);
	let summary = $state<RestoreSummary | null>(null);

	// Eight characters is not a security claim, it is a floor under a typo. The real strength of
	// this file is whatever gets typed here, which the text below says outright.
	const canExport = $derived(exportPass.length >= 8 && exportPass === exportConfirm);

	async function save() {
		if (!canExport || busy) return;
		busy = true;
		message = null;
		try {
			const bytes = await exportBackup(exportPass);
			const url = URL.createObjectURL(
				new Blob([bytes as BlobPart], { type: 'application/octet-stream' })
			);
			const link = document.createElement('a');
			link.href = url;
			const stamp = new Date().toISOString().slice(0, 10);
			link.download = `family-group-${stamp}.fgbk`;
			link.click();
			URL.revokeObjectURL(url);
			message = { ok: true, text: 'Saved. Keep it somewhere the passphrase is not written down.' };
			exportPass = '';
			exportConfirm = '';
		} catch {
			message = { ok: false, text: 'The backup could not be written.' };
		}
		busy = false;
	}

	async function restore() {
		if (!file || !importPass || busy) return;
		busy = true;
		message = null;
		summary = null;
		const bytes = new Uint8Array(await file.arrayBuffer());
		const result = await importBackup(bytes, importPass);
		if (result.ok) {
			summary = result.summary;
			message = { ok: true, text: 'Restored.' };
			importPass = '';
			await identity.refresh();
			await connection.reconcile();
		} else {
			message = {
				ok: false,
				text:
					result.failure.kind === 'notABackup'
						? 'That file is not a Family Group backup.'
						: result.failure.kind === 'unsupportedVersion'
							? `That backup was written by a newer version (${result.failure.version}).`
							: 'Wrong passphrase, or the file is damaged. Those look the same on purpose.'
			};
		}
		busy = false;
	}
</script>

<svelte:head><title>Backup · Family Group</title></svelte:head>

<TopBar title="Backup" back="/settings" />

<main class="flex-1 px-5 py-6">
	<p class="muted text-sm leading-relaxed">
		A backup holds your identity, your brokers, your groups and every group key. It does not hold
		messages, photos or location history — those are the bulk, and unlike the keys they are
		replaceable. The file works on a phone too: it is the same <span class="font-mono">.fgbk</span> format
		the Android app reads and writes.
	</p>

	<section class="surface mt-5 rounded-xl p-4">
		<h2 class="mb-1 text-base">Save a backup</h2>
		<p class="muted text-sm leading-relaxed">
			Your group keys go into this file in the clear, behind the passphrase and nothing else. That
			is not a shortcut — a key wrapped by this browser's master key would restore to a device that
			cannot open it, and would look like a backup right up until it was needed. So the passphrase
			is the entire protection. Make it long.
		</p>
		<div class="mt-4 grid gap-3 sm:grid-cols-2">
			<label class="block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
					Passphrase
				</span>
				<input class="field" type="password" bind:value={exportPass} autocomplete="new-password" />
			</label>
			<label class="block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase"> Again </span>
				<input
					class="field"
					type="password"
					bind:value={exportConfirm}
					autocomplete="new-password"
				/>
			</label>
		</div>
		{#if exportPass && exportPass.length < 8}
			<p class="muted mt-2 text-xs">At least eight characters.</p>
		{:else if exportConfirm && exportPass !== exportConfirm}
			<p class="mt-2 text-xs" style="color: var(--error)">Those do not match.</p>
		{/if}
		<button class="btn btn-primary mt-3" disabled={!canExport || busy} onclick={save}>
			Save backup file
		</button>
	</section>

	<section class="surface mt-5 rounded-xl p-4">
		<h2 class="mb-1 text-base">Restore</h2>
		<p class="muted text-sm leading-relaxed">
			Restoring merges into what is already here: brokers, groups and keys with the same ids are
			replaced, and anything else is left alone. Your user id comes back too, which is the point —
			it is what every message you have ever sent was signed under.
		</p>
		<div class="mt-4 flex flex-wrap items-end gap-3">
			<button class="btn btn-quiet" onclick={() => picker?.click()}>
				{file ? file.name : 'Choose a .fgbk file'}
			</button>
			<input
				bind:this={picker}
				type="file"
				accept=".fgbk,application/octet-stream"
				class="hidden"
				onchange={(e) => (file = e.currentTarget.files?.[0] ?? null)}
			/>
			<label class="block flex-1">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
					Passphrase
				</span>
				<input
					class="field"
					type="password"
					bind:value={importPass}
					autocomplete="current-password"
				/>
			</label>
		</div>
		<button class="btn btn-primary mt-3" disabled={!file || !importPass || busy} onclick={restore}>
			Restore
		</button>
	</section>

	{#if message}
		<p class="mt-4 text-sm" style="color: {message.ok ? 'var(--accent)' : 'var(--error)'}">
			{message.text}
		</p>
	{/if}
	{#if summary}
		<p class="muted mt-1 text-sm">
			{summary.brokers} broker{summary.brokers === 1 ? '' : 's'}, {summary.groups} group{summary.groups ===
			1
				? ''
				: 's'}, {summary.keys} key{summary.keys === 1 ? '' : 's'}, {summary.members} member{summary.members ===
			1
				? ''
				: 's'}{summary.identityRestored ? ', and your identity' : ''}.
		</p>
	{/if}
</main>
