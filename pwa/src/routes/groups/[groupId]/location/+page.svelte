<script lang="ts">
	import { page } from '$app/state';
	import TopBar from '$lib/ui/TopBar.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db } from '$lib/data/db/schema';
	import { setLocationSettings, setShareLocation } from '$lib/data/repo/groupRepo';
	import { connection } from '$lib/stores/connection.svelte';
	import { locationEngine, pruneHistory } from '$lib/location/locationEngine';

	const groupId = $derived(page.params.groupId!);
	const group = live(() => db.groups.get(groupId), undefined);

	// The same ladders the Android app offers, so a group set up on a phone reads the same here.
	const SAMPLE = [10, 30, 60, 120, 300, 900];
	const PUSH = [30, 60, 300, 900, 1800, 3600];
	const DISTANCE = [0, 10, 25, 50, 100, 250];
	const RETENTION = [1, 6, 24, 168, 720, 0];

	let saving = $state(false);

	const label = (seconds: number) =>
		seconds < 60 ? `${seconds}s` : seconds < 3600 ? `${seconds / 60} min` : `${seconds / 3600} hr`;

	const retentionLabel = (hours: number) =>
		hours === 0 ? 'Forever' : hours < 24 ? `${hours} hr` : `${hours / 24} days`;

	async function update(changes: Partial<Record<string, number | boolean>>) {
		const current = group.value;
		if (!current || !current.isAdmin || saving) return;
		saving = true;
		const merged = { ...current, ...changes };
		await setLocationSettings(groupId, {
			kind: 'locset',
			enabled: merged.locationEnabled,
			sampleSec: merged.locSampleSec,
			// The engine would clamp this anyway; doing it here means the number on screen is the
			// number in force, rather than one silently overridden somewhere downstream.
			pushSec: Math.max(merged.locPushSec, merged.locSampleSec),
			minDistanceM: merged.locMinDistanceM,
			retentionHours: merged.locRetentionHours
		});
		connection.nudge();
		await locationEngine.refresh();
		saving = false;
	}

	async function toggleShare() {
		if (!group.value) return;
		await setShareLocation(groupId, !group.value.shareLocation);
		await locationEngine.refresh();
	}

	async function pruneNow() {
		await pruneHistory();
	}
</script>

<svelte:head><title>Location · {group.value?.name ?? 'Family Group'}</title></svelte:head>

<TopBar title="Location" back="/groups/{groupId}/map" />

<main class="flex-1 px-5 py-6">
	<section class="surface rounded-xl p-4">
		<div class="flex items-center justify-between gap-4">
			<div>
				<h2 class="text-base">Share my location</h2>
				<p class="muted text-sm">Yours alone. No admin can turn this on for you.</p>
			</div>
			<button
				class="btn {group.value?.shareLocation ? 'btn-primary' : 'btn-quiet'}"
				disabled={!group.value?.locationEnabled}
				onclick={toggleShare}>{group.value?.shareLocation ? 'On' : 'Off'}</button
			>
		</div>
		<p class="muted mt-3 text-xs leading-relaxed">
			Whether you share is never published. Other members infer it from whether positions arrive,
			which is the honest signal anyway — a device that is off, flat or out of range looks identical
			to one that opted out. In a browser it is also what a closed tab looks like, which is most of
			the time.
		</p>
	</section>

	{#if group.value?.isAdmin}
		<section class="surface mt-5 rounded-xl p-4">
			<div class="flex items-center justify-between gap-4">
				<div>
					<h2 class="text-base">Location for this group</h2>
					<p class="muted text-sm">The rules everyone follows. Only you can change them.</p>
				</div>
				<button
					class="btn {group.value.locationEnabled ? 'btn-primary' : 'btn-quiet'}"
					disabled={saving}
					onclick={() => update({ locationEnabled: !group.value!.locationEnabled })}
					>{group.value.locationEnabled ? 'On' : 'Off'}</button
				>
			</div>

			{#if group.value.locationEnabled}
				<div class="mt-5 grid gap-4">
					<label class="block">
						<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
							Take a position every
						</span>
						<select
							class="field"
							value={group.value.locSampleSec}
							onchange={(e) => update({ locSampleSec: Number(e.currentTarget.value) })}
						>
							{#each SAMPLE as s (s)}<option value={s}>{label(s)}</option>{/each}
						</select>
					</label>

					<label class="block">
						<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
							Tell the group every
						</span>
						<select
							class="field"
							value={group.value.locPushSec}
							onchange={(e) => update({ locPushSec: Number(e.currentTarget.value) })}
						>
							{#each PUSH as s (s)}<option value={s}>{label(s)}</option>{/each}
						</select>
					</label>

					<label class="block">
						<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
							Only after moving
						</span>
						<select
							class="field"
							value={group.value.locMinDistanceM}
							onchange={(e) => update({ locMinDistanceM: Number(e.currentTarget.value) })}
						>
							{#each DISTANCE as d (d)}
								<option value={d}>{d === 0 ? 'Always' : `${d} m`}</option>
							{/each}
						</select>
					</label>

					<label class="block">
						<span class="muted mb-1 block text-xs font-semibold tracking-wide uppercase">
							Keep history for
						</span>
						<select
							class="field"
							value={group.value.locRetentionHours}
							onchange={(e) => update({ locRetentionHours: Number(e.currentTarget.value) })}
						>
							{#each RETENTION as h (h)}<option value={h}>{retentionLabel(h)}</option>{/each}
						</select>
					</label>
				</div>

				<p class="muted mt-4 text-xs leading-relaxed">
					These are the group's rules rather than each person's preference, because a map where
					everyone picks their own rate is one where "3 minutes ago" means something different for
					every pin and nobody can tell which.
				</p>
			{/if}
		</section>
	{:else if group.value?.locationEnabled}
		<p class="muted mt-5 text-sm leading-relaxed">
			The admin sets how often positions are taken and shared for this group. Right now that is
			every {label(group.value.locSampleSec)} with an update every {label(group.value.locPushSec)},
			after moving {group.value.locMinDistanceM} m, kept for
			{retentionLabel(group.value.locRetentionHours)}.
		</p>
	{/if}

	<section class="surface mt-5 rounded-xl p-4">
		<h2 class="mb-2 text-base">History on this device</h2>
		<p class="muted text-sm leading-relaxed">
			Everyone in a group holds everyone's history, so any device can draw the past without a
			server. Retention is enforced per device — a member who set "forever" still has your track
			after you pruned yours. There is no single copy to delete, and no honest way to promise
			otherwise.
		</p>
		<button class="btn btn-quiet mt-3" onclick={pruneNow}>Prune now</button>
	</section>
</main>
