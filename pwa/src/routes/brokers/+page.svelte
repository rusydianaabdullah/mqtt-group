<script lang="ts">
	import TopBar from '$lib/ui/TopBar.svelte';
	import {
		deleteBroker,
		getBroker,
		groupsOnBroker,
		hostOf,
		listBrokers,
		saveBroker,
		SUGGESTED_URL,
		validateUrl
	} from '$lib/data/repo/brokerRepo';
	import { connection, testBroker } from '$lib/stores/connection.svelte';
	import { DEFAULT_PREFIX } from '$lib/mqtt/topics';
	import type { Broker } from '$lib/data/db/schema';
	import { onMount } from 'svelte';

	let brokers = $state<Broker[]>([]);
	let editing = $state<string | null>(null);
	let adding = $state(false);

	let form = $state({
		label: '',
		url: SUGGESTED_URL,
		username: '',
		password: '',
		topicPrefix: DEFAULT_PREFIX
	});
	let testing = $state(false);
	let result = $state<{ ok: boolean; message: string } | null>(null);
	let inUse = $state(0);

	const urlError = $derived(validateUrl(form.url));

	onMount(refresh);

	async function refresh() {
		brokers = await listBrokers();
	}

	function startAdd() {
		form = {
			label: '',
			url: SUGGESTED_URL,
			username: '',
			password: '',
			topicPrefix: DEFAULT_PREFIX
		};
		result = null;
		editing = null;
		adding = true;
	}

	async function startEdit(broker: Broker) {
		form = {
			label: broker.label,
			url: broker.url,
			username: broker.username ?? '',
			password: broker.password ?? '',
			topicPrefix: broker.topicPrefix
		};
		result = null;
		adding = false;
		editing = broker.id;
		inUse = await groupsOnBroker(broker.id);
	}

	async function test() {
		testing = true;
		result = null;
		result = await testBroker({
			label: form.label,
			url: form.url,
			username: form.username || null,
			password: form.password || null,
			topicPrefix: form.topicPrefix
		});
		testing = false;
	}

	async function save() {
		await saveBroker({
			...form,
			id: editing ?? undefined,
			username: form.username,
			password: form.password
		});
		adding = false;
		editing = null;
		await refresh();
		await connection.reconcile();
	}

	async function remove(broker: Broker) {
		if ((await groupsOnBroker(broker.id)) > 0) return;
		await deleteBroker(broker.id);
		editing = null;
		await refresh();
		await connection.reconcile();
	}

	function stateFor(id: string): string {
		const s = connection.perBroker[id];
		if (!s) return 'Not connected';
		if (s.kind === 'connected') return 'Connected';
		if (s.kind === 'connecting') return 'Connecting…';
		if (s.kind === 'waiting') return s.reason;
		return 'Idle';
	}
</script>

<svelte:head><title>Brokers · Family Group</title></svelte:head>

<TopBar title="Brokers" back="/settings" />

<main class="flex-1 px-5 py-6">
	<p class="muted text-sm leading-relaxed">
		A broker is the only thing between you and the people in your groups, and it never sees anything
		but ciphertext. It does see topic names and when traffic happens — which is why the prefix is
		yours to choose, and why running your own Mosquitto or EMQX is the private option.
	</p>
	<p class="muted mt-3 text-sm leading-relaxed">
		A browser can only speak MQTT over a WebSocket, so this needs the broker’s
		<strong>WebSocket</strong> port — usually 8083 plain or 8084 with TLS — and not the 1883 or 8883 an
		Android invite carries.
	</p>

	{#each brokers as broker (broker.id)}
		<section class="surface mt-4 rounded-xl p-4">
			{#if editing === broker.id}
				{@render editor()}
			{:else}
				<div class="flex items-start gap-3">
					<div class="min-w-0 flex-1">
						<p class="truncate font-semibold">{broker.label}</p>
						<p class="muted truncate font-mono text-xs">{broker.url}</p>
						<p class="muted mt-1 text-xs">
							{stateFor(broker.id)} · prefix <span class="font-mono">{broker.topicPrefix}</span>
						</p>
					</div>
					<button class="btn btn-quiet !px-3 !py-1.5" onclick={() => startEdit(broker)}>Edit</button
					>
				</div>
			{/if}
		</section>
	{/each}

	{#if adding}
		<section class="surface mt-4 rounded-xl p-4">{@render editor()}</section>
	{:else}
		<button class="btn btn-primary mt-4" onclick={startAdd}>Add a broker</button>
	{/if}

	{#if brokers.length === 0 && !adding}
		<p class="muted mt-4 text-sm">
			None yet. The suggested one is Mosquitto’s public test broker, which works immediately and is
			fine for trying the app out — anyone can subscribe to it, so its privacy rests entirely on the
			encryption, and that is a thinner margin than it sounds.
		</p>
	{/if}
</main>

{#snippet editor()}
	<div class="grid gap-3">
		<label class="block">
			<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase"
				>WebSocket URL</span
			>
			<input class="field font-mono text-sm" bind:value={form.url} placeholder={SUGGESTED_URL} />
		</label>
		{#if urlError}
			<p class="text-xs" style="color: var(--error)">{urlError}</p>
		{/if}

		<label class="block">
			<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">Name</span>
			<input class="field" bind:value={form.label} placeholder={hostOf(form.url)} />
		</label>

		<div class="grid gap-3 sm:grid-cols-2">
			<label class="block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">Username</span>
				<input class="field" bind:value={form.username} autocomplete="off" />
			</label>
			<label class="block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">Password</span>
				<input class="field" type="password" bind:value={form.password} autocomplete="off" />
			</label>
		</div>

		<label class="block">
			<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase"
				>Topic prefix</span
			>
			<input class="field font-mono text-sm" bind:value={form.topicPrefix} />
		</label>

		<div class="flex flex-wrap items-center gap-2">
			<button class="btn btn-quiet" disabled={!!urlError || testing} onclick={test}>
				{testing ? 'Testing…' : 'Test connection'}
			</button>
			<button class="btn btn-primary" disabled={!!urlError} onclick={save}>Save</button>
			<button
				class="btn btn-quiet"
				onclick={() => {
					adding = false;
					editing = null;
				}}>Cancel</button
			>
			{#if editing}
				<button
					class="btn btn-danger ml-auto"
					disabled={inUse > 0}
					title={inUse > 0 ? `${inUse} group(s) still use this broker` : ''}
					onclick={() => remove(brokers.find((b) => b.id === editing)!)}>Delete</button
				>
			{/if}
		</div>

		{#if result}
			<p
				class="text-xs leading-relaxed"
				style="color: {result.ok ? 'var(--accent)' : 'var(--error)'}"
			>
				{result.message}
			</p>
		{/if}

		<p class="muted text-xs leading-relaxed">
			The browser decides which certificates it trusts and will not be argued with, so a self-signed
			broker that works on Android will refuse to connect here until its certificate is trusted by
			your operating system. There is no “accept anyway”.
		</p>
	</div>
{/snippet}
