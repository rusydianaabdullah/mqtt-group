<script lang="ts">
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';
	import TopBar from '$lib/ui/TopBar.svelte';
	import QrScanner from '$lib/ui/QrScanner.svelte';
	import { decodeInvite, guessWebSocketUrl, type Invite } from '$lib/crypto/invite';
	import { announceSelf, joinGroup } from '$lib/data/repo/groupRepo';
	import { saveBroker, validateUrl } from '$lib/data/repo/brokerRepo';
	import { connection } from '$lib/stores/connection.svelte';
	import { db } from '$lib/data/db/schema';
	import { onMount } from 'svelte';

	/**
	 * Joining, in two steps, because a code from a phone cannot tell a browser how to reach the
	 * broker.
	 *
	 * The invite carries a host and a raw MQTT port — 8883, say — which is exactly right for
	 * another phone and useless here: a browser can only open a WebSocket. Rather than change the
	 * code format for a field only browsers read, this asks. The guess is filled in and usually
	 * correct; when it is not, the alternative was a socket that silently never opens.
	 */
	let scanner = $state<QrScanner | null>(null);
	let scanning = $state(false);
	let pasted = $state('');
	let error = $state<string | null>(null);
	let invite = $state<Invite | null>(null);
	let wsUrl = $state('');
	let busy = $state(false);

	const urlError = $derived(invite ? validateUrl(wsUrl) : null);

	onMount(() => {
		// Offer the camera immediately: this screen exists to point one at a code, and asking
		// twice for something the person just navigated here to do is friction for nothing.
		void begin();
	});

	async function begin() {
		error = null;
		scanning = true;
		await scanner?.start();
	}

	function accept(text: string) {
		const decoded = decodeInvite(text);
		if (!decoded) {
			// Keep scanning: a camera sees far more things that are not invites than things that
			// are, and stopping on each one would make the screen unusable.
			return;
		}
		scanner?.stop();
		scanning = false;
		invite = decoded;
		wsUrl = guessWebSocketUrl(decoded);
	}

	function acceptPasted() {
		const decoded = decodeInvite(pasted);
		if (!decoded) {
			error = 'That is not an invite code. It should start with FG1:';
			return;
		}
		error = null;
		invite = decoded;
		wsUrl = guessWebSocketUrl(decoded);
	}

	async function join() {
		if (!invite || urlError || busy) return;
		busy = true;
		error = null;

		// Reuse a broker already pointing at this URL rather than accumulating duplicates every
		// time someone joins a second group on the same server.
		const existing = await db.brokers.filter((b) => b.url === wsUrl.trim()).first();
		const brokerId =
			existing?.id ??
			(await saveBroker({
				label: new URL(wsUrl).host,
				url: wsUrl,
				username: invite.username,
				password: invite.password,
				topicPrefix: invite.topicPrefix
			}));

		const group = await joinGroup(invite, brokerId);
		if (!group) {
			error = 'You are already in that group.';
			busy = false;
			return;
		}
		await connection.reconcile();
		// Announcing ourselves is what fills in everyone else's member list — and what prompts
		// them to answer with theirs, which is how this device learns who is here.
		await announceSelf(group.groupId);
		connection.nudge();
		await goto(`${base}/groups/${group.groupId}/`);
	}
</script>

<svelte:head><title>Join a group · Family Group</title></svelte:head>

<TopBar title="Join a group" back="/" />

<main class="flex-1 px-5 py-6">
	{#if invite}
		<section class="surface rounded-xl p-4">
			<h2 class="text-base">{invite.groupName}</h2>
			<p class="muted mt-1 text-sm">
				Broker <span class="font-mono">{invite.host}</span>, prefix
				<span class="font-mono">{invite.topicPrefix}</span>.
			</p>

			<label class="mt-4 block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
					WebSocket URL
				</span>
				<input class="field font-mono text-sm" bind:value={wsUrl} />
			</label>
			<p class="muted mt-2 text-xs leading-relaxed">
				The code carries port {invite.port}, which is a raw MQTT socket — a browser cannot open one.
				This needs the same broker's WebSocket listener instead, usually 8083 plain or 8084 with
				TLS, and usually on the path <span class="font-mono">/mqtt</span>. The guess above is right
				more often than not; if it is wrong the socket simply never opens, so it is worth checking
				with whoever runs the broker.
			</p>
			{#if urlError}
				<p class="mt-2 text-xs" style="color: var(--error)">{urlError}</p>
			{/if}

			<div class="mt-4 flex gap-2">
				<button class="btn btn-primary" disabled={!!urlError || busy} onclick={join}>
					{busy ? 'Joining…' : 'Join'}
				</button>
				<button
					class="btn btn-quiet"
					disabled={busy}
					onclick={() => {
						invite = null;
						void begin();
					}}>Scan another</button
				>
			</div>
		</section>
	{:else}
		<QrScanner
			bind:this={scanner}
			onScan={accept}
			onError={(m) => ((error = m), (scanning = false))}
		/>

		{#if !scanning}
			<button class="btn btn-quiet mt-3" onclick={begin}>Use the camera</button>
		{:else}
			<p class="muted mt-3 text-sm">Point the camera at the code on the other device.</p>
		{/if}

		<div class="mt-6">
			<label class="block">
				<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
					Or paste the code
				</span>
				<textarea class="field font-mono text-xs" rows="3" bind:value={pasted}></textarea>
			</label>
			<button class="btn btn-quiet mt-2" disabled={!pasted.trim()} onclick={acceptPasted}>
				Use this code
			</button>
		</div>
	{/if}

	{#if error}
		<p class="mt-4 text-sm" style="color: var(--error)">{error}</p>
	{/if}

	<p class="muted mt-8 text-xs leading-relaxed">
		A code is the group's key. Anyone who photographs one — off a screen, out of a chat app, over
		your shoulder — can read everything the group says from then on, and there is no server to
		notice or to revoke it.
	</p>
</main>
