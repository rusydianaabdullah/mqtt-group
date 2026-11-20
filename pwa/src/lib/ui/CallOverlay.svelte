<script lang="ts">
	import { calls } from '$lib/call/calls.svelte';
	import { peerIdOf } from '$lib/call/callMachine';
	import Avatar from '$lib/ui/Avatar.svelte';

	/**
	 * The call, over everything else.
	 *
	 * Mounted once in the root layout rather than on a page, because a call outlives whatever
	 * screen started it: navigating to the map during a call should not end it, and an offer
	 * arriving while someone is reading a different group still has to ring.
	 *
	 * It is silent, and says so. There is no audio in this phase — a connected call here proves
	 * the signalling works and nothing more — and a screen that looked like a working call while
	 * carrying no voice would be reported as broken, correctly.
	 */

	const call = $derived(calls.state);
	// A buzz is not a call, and every part of this screen has to say so.
	const isBuzz = $derived('mode' in call && call.mode === 'buzz');
	// A group call has no single name, so it says how many are in the room — listing four names
	// in a headline is unreadable and picking one of them is a lie.
	const name = $derived.by(() => {
		if (call.phase === 'connected' && call.isGroup) {
			return `${call.participants.length + 1} in this call`;
		}
		if (call.phase === 'outgoing' && call.isGroup)
			return isBuzz ? 'Buzz everyone' : 'Call the group';
		return calls.peerName ?? 'Someone in this group';
	});

	// Ticks only while connected, and only for the timer.
	let now = $state(Date.now());
	$effect(() => {
		if (call.phase !== 'connected') return;
		const id = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(id);
	});

	const elapsed = $derived.by(() => {
		if (call.phase !== 'connected') return '';
		const seconds = Math.max(0, Math.floor((now - call.since) / 1000));
		return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
	});

	// The ended state is the call's last word; it clears itself once it has been read.
	$effect(() => {
		if (call.phase !== 'ended') return;
		const id = setTimeout(() => calls.dismiss(), 2500);
		return () => clearTimeout(id);
	});

	// Only while there is something to wait for. A pulsing dot on "Connected" would suggest the
	// call is still trying to do something, which is the opposite of what it means.
	const waiting = $derived(call.phase === 'outgoing' || call.phase === 'incoming');

	const statusColour = $derived.by(() => {
		switch (call.phase) {
			case 'connected':
				// Not a theme colour, and should not be: it means one thing here and nothing
				// anywhere else in the app.
				return '#2e7d32';
			case 'outgoing':
				return call.reached ? 'var(--accent, #2563eb)' : 'var(--muted-fg, #64748b)';
			case 'incoming':
				return 'var(--accent, #2563eb)';
			case 'ended':
				return 'var(--danger, #b91c1c)';
			default:
				return 'var(--muted-fg, #64748b)';
		}
	});

	const status = $derived.by(() => {
		switch (call.phase) {
			case 'incoming':
				return isBuzz ? 'Buzzing your phone' : 'Incoming call';
			case 'outgoing':
				// A buzz never borrows the call vocabulary: there is no ringing/answered split
				// to report, because nobody answers one.
				if (isBuzz) return call.isGroup ? 'Buzzing everyone' : 'Buzzing their phone';
				// The whole reason the `ring` message exists. In a browser the difference is
				// often whether the other end's tab was even open.
				if (call.isGroup && !call.reached) return 'Ringing everyone…';
				return call.reached ? 'Ringing…' : 'Calling…';
			case 'connected':
				return isBuzz ? 'Buzzing your phone' : 'Connected';
			case 'ended':
				// A buzz has no busy, no declined and no missed — every way it ends is somebody
				// stopping it, and inventing distinctions between those would be reporting on
				// the mechanism rather than on what happened.
				if (isBuzz) return 'Stopped';
				switch (call.outcome) {
					case 'declined':
						return 'Declined';
					case 'busy':
						return 'On another call';
					case 'cancelled':
						return 'Call cancelled';
					case 'unanswered':
						return 'No answer';
					case 'hung-up':
						return 'Call ended';
				}
				return 'Call ended';
			default:
				return '';
		}
	});
</script>

{#if call.phase !== 'idle'}
	<div
		class="fixed inset-0 z-50 flex flex-col items-center justify-between p-8"
		style="background: var(--surface)"
		role="dialog"
		aria-modal="true"
		aria-label={status}
	>
		<div class="mt-16 flex flex-col items-center gap-3 text-center">
			<Avatar name={calls.peerName ?? ''} userId={peerIdOf(call) ?? ''} size={96} />
			<!-- A claim, not a proof: a shared group key gives no per-sender authenticity, the
			     same as for every chat message in this app. -->
			<h1 class="text-2xl font-semibold">{name}</h1>
			<!-- The three states a caller passes through are not decoration: "Calling…" means the
			     offer went out and nothing came back, "Ringing…" means a device that is awake
			     actually has it — the whole reason the `ring` message exists — and "Connected"
			     means somebody answered. The dot pulses while waiting and holds steady once
			     connected, so the difference is legible without reading. -->
			<span
				class="inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-sm"
				style="background: {statusColour}22; color: {statusColour}"
			>
				<span
					class="inline-block h-2 w-2 shrink-0 rounded-full"
					class:animate-pulse={waiting}
					style="background: {statusColour}"
				></span>
				{status}
			</span>
			<!-- A delayed call has two very different causes — our buffering, which is ours to
			     cut, and the path to the broker, which is not — and this is the one that says
			     which. -->
			{#if calls.roundTripMillis !== null && (call.phase === 'connected' || call.phase === 'outgoing')}
				<p class="muted text-xs">via broker · {calls.roundTripMillis} ms round trip</p>
			{/if}
			{#if call.phase === 'connected'}
				<p class="text-lg tabular-nums">{elapsed}</p>
			{/if}
			<!-- Only when there is something to say. A permanent "your microphone works" line is
			     noise on a screen someone is using to talk to their family. -->
			{#if call.phase === 'connected' && !isBuzz && (calls.micError || calls.muted)}
				<p class="max-w-xs text-xs leading-relaxed" style="color: var(--danger, #b91c1c)">
					{calls.micError ?? 'Your microphone is off.'}
				</p>
			{/if}
		</div>

		<div class="mb-12 flex items-center gap-8">
			<!-- A buzz offers one button at either end, because there is nothing to answer:
			     whoever reaches a phone first stops it. -->
			{#if call.phase === 'incoming' && isBuzz}
				<button class="btn btn-danger !rounded-full !px-8 !py-3" onclick={() => calls.hangup()}>
					Stop
				</button>
			{:else if call.phase === 'incoming'}
				<button class="btn btn-danger !rounded-full !px-6 !py-3" onclick={() => calls.hangup()}>
					Decline
				</button>
				<button class="btn !rounded-full !px-6 !py-3" onclick={() => calls.accept()}>
					Answer
				</button>
			{:else if call.phase === 'connected'}
				<button class="btn btn-quiet !rounded-full !px-6 !py-3" onclick={() => calls.toggleMute()}>
					{calls.muted ? 'Unmute' : 'Mute'}
				</button>
				<button class="btn btn-danger !rounded-full !px-6 !py-3" onclick={() => calls.hangup()}>
					Hang up
				</button>
			{:else if call.phase === 'outgoing'}
				<button class="btn btn-danger !rounded-full !px-8 !py-3" onclick={() => calls.hangup()}>
					{isBuzz ? 'Stop' : 'Hang up'}
				</button>
			{/if}
		</div>
	</div>
{/if}
