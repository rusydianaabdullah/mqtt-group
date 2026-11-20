<script lang="ts">
	import { base } from '$app/paths';
	import { page } from '$app/state';
	import TopBar from '$lib/ui/TopBar.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import { memberAvatars } from '$lib/ui/memberAvatars.svelte';
	import { live } from '$lib/stores/live.svelte';
	import { db, type LocationFix } from '$lib/data/db/schema';
	import { identity } from '$lib/stores/identity.svelte';
	import {
		colourFor,
		MAP_LAYERS,
		stalenessOpacity,
		styleFor,
		type MapLayer
	} from '$lib/map/mapStyle';
	import {
		decimate,
		HISTORY_RANGES,
		resolveRange,
		type HistoryRangeId
	} from '$lib/data/historyRange';
	import { relativeTime } from '$lib/ui/relativeTime';
	import { setShareLocation } from '$lib/data/repo/groupRepo';
	import { locationEngine } from '$lib/location/locationEngine';
	import { onMount } from 'svelte';
	import type { Map as MapLibreMap, Marker } from 'maplibre-gl';

	const groupId = $derived(page.params.groupId!);

	/**
	 * The pin: the person's face in a ring, with a tail pointing at the spot they are actually at.
	 *
	 * The Android app draws this as a bitmap because a SymbolLayer icon has to be one; here a
	 * marker is a DOM element, so it is markup and the photo is the same object URL the rest of
	 * the app already decrypted. A face is what makes two pins on the same street tellable apart
	 * at a glance — the initial only ever managed that when two people's names began differently.
	 *
	 * The ring is `currentColor`, and the element carries the per-person colour, so the pin and
	 * that person's trail are always the same colour without either having to look the other up.
	 */
	const PIN_HTML = `<span class="fg-pin-disc"><img class="fg-pin-photo" alt="" hidden><span class="fg-pin-initial"></span></span>`;

	const group = live(() => db.groups.get(groupId), undefined);
	const members = live(() => db.members.where('groupId').equals(groupId).toArray(), []);

	const avatars = memberAvatars(() => members.value);

	/**
	 * Who is singled out, if anyone. Tapping a face — on a pin or in the strip below — flies the
	 * camera to them and lifts their trail out of everyone else's; tapping again lets go.
	 */
	let selectedUserId = $state<string | null>(null);

	// The map opens live. History is a mode you turn on, not the default — the question anyone
	// opens a map to answer is "where are they now".
	let historyOn = $state(false);
	let rangeId = $state<HistoryRangeId>('lastHour');
	let customSince = $state(new Date().toISOString().slice(0, 10));
	let customUntil = $state(new Date().toISOString().slice(0, 10));
	// Google roads by default: OpenStreetMap's coverage of the residential streets this is
	// actually used on is thin enough that the honest default is the one that answers "where are
	// they". The picker says what each provider costs in privacy.
	let layer = $state<MapLayer>('m');

	/**
	 * Re-resolved once a minute rather than once when it is chosen. A relative range fixed at the
	 * moment it was tapped silently grows: leave the map open for an hour on "Last hour" and it is
	 * showing two. A fixed range ignores the tick, since its window does not move.
	 */
	let clock = $state(Date.now());
	onMount(() => {
		const timer = setInterval(() => (clock = Date.now()), 60_000);
		return () => clearInterval(timer);
	});

	// `clock` is read unconditionally, which is the point: it is what makes a relative range
	// re-resolve on the tick. A fixed range reads it too and simply computes the same window
	// again, so no branch is needed to tell them apart — `isRelative` documents which is which
	// for anyone reading, and the tick costs nothing when the answer does not move.
	const range = $derived(
		resolveRange(
			rangeId,
			clock,
			rangeId === 'custom'
				? { since: new Date(customSince).getTime(), until: new Date(customUntil).getTime() }
				: undefined
		)
	);

	const latest = live<LocationFix[]>(async () => {
		const all = await db.locations
			.where('[groupId+capturedAt]')
			.between([groupId, -Infinity], [groupId, Infinity])
			.toArray();
		const byUser = new Map<string, LocationFix>();
		for (const fix of all) {
			const held = byUser.get(fix.userId);
			if (!held || fix.capturedAt > held.capturedAt) byUser.set(fix.userId, fix);
		}
		return [...byUser.values()];
	}, []);

	const paths = live<Record<string, LocationFix[]>>(async () => {
		if (!historyOn) return {};
		const rows = await db.locations
			.where('[groupId+capturedAt]')
			.between([groupId, range.since], [groupId, range.until])
			.toArray();
		const byUser: Record<string, LocationFix[]> = {};
		for (const fix of rows) (byUser[fix.userId] ??= []).push(fix);
		for (const id of Object.keys(byUser)) {
			byUser[id].sort((a, b) => a.capturedAt - b.capturedAt);
		}
		return byUser;
	}, {});

	// --- Playback ------------------------------------------------------------------------------
	// Deliberately not persisted and deliberately not in a store: it is presentation over paths
	// already loaded, and losing your place on a reload is what a video player does too.
	//
	// The scrubber works in *offsets from the start of the span*, not epoch milliseconds. A
	// range input carries a double, but the value it round-trips through the DOM is a string
	// parsed back — and an absolute timestamp there costs every digit of precision to the left of
	// the part that matters. Offsets keep the whole resolution where the movement is.
	let playhead = $state<number | null>(null);
	let playing = $state(false);
	let speed = $state(4);

	onMount(() => {
		const timer = setInterval(() => {
			if (!playing || playhead === null) return;
			const span = range.until - range.since;
			const next = playhead + 250 * speed;
			if (next >= span) {
				playhead = span;
				playing = false;
			} else {
				playhead = next;
			}
		}, 250);
		return () => clearInterval(timer);
	});

	/** Where the playhead is in wall-clock terms, or now when it is parked. */
	const asOf = $derived(playhead === null ? clock : range.since + playhead);

	// --- The map -------------------------------------------------------------------------------
	let container = $state<HTMLDivElement | null>(null);
	/**
	 * The map, as a rune, because three effects wait on it. As a plain `let` it was invisible to
	 * them: they ran once before the lazy import had resolved, found null, and were never woken.
	 */
	let map = $state<MapLibreMap | null>(null);
	const markers = new Map<string, Marker>();
	/**
	 * MapLibre's module, kept once it has loaded.
	 *
	 * Awaiting the dynamic import *inside* an effect was the bug that emptied this map: an effect
	 * tracks only what it reads before its first await, so an effect that awaited the import and
	 * then read the pins depended on neither, ran once against an empty database, and never ran
	 * again. Everything reactive is read synchronously now, and this is what makes that possible.
	 */
	let maplibre = $state<typeof import('maplibre-gl') | null>(null);
	/**
	 * Bumped whenever a style finishes loading.
	 *
	 * This, rather than the `load` event, is what says the map can hold a layer. `load` fires once
	 * and only once the first tiles are in, which for a raster style pointing at a host the
	 * network does not reach is never — and gating the pins on it meant a blocked tile server also
	 * cost you everyone's position, which is the one thing on this screen that does not come from
	 * the network at all.
	 */
	let styleEpoch = $state(0);

	const dark = $derived(
		typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches
	);

	onMount(() => {
		let cancelled = false;
		void (async () => {
			// Imported lazily: MapLibre is by far the largest thing in the app, and every screen
			// that is not the map should not pay for it.
			const module = await import('maplibre-gl');
			await import('maplibre-gl/dist/maplibre-gl.css');
			if (cancelled || !container) return;
			maplibre = module;
			map = new module.Map({
				container,
				style: styleFor(layer, dark),
				center: [107.60981, -6.914744],
				zoom: 11,
				attributionControl: { compact: true }
			});
			appliedLayer = layer;
			map.addControl(new module.NavigationControl({ showCompass: false }), 'top-right');
			// `setStyle` throws away every source and layer the old style had, ours included, so
			// the trail has to be re-added each time one finishes loading rather than once.
			map.on('styledata', () => (styleEpoch += 1));
		})();
		return () => {
			cancelled = true;
			for (const marker of markers.values()) marker.remove();
			markers.clear();
			map?.remove();
			map = null;
		};
	});

	// Restyling keeps the camera: `setStyle` replaces sources and layers, not the view, so
	// switching provider does not throw someone back to the default centre.
	/** Which layer the map is actually wearing, so the first run does not restyle what it built. */
	let appliedLayer: MapLayer | null = null;

	$effect(() => {
		// Read first, unconditionally. Behind a readiness guard these were invisible to the effect
		// until the map happened to be ready, which is what left the layer picker inert.
		const chosen = layer;
		const style = styleFor(chosen, dark);
		const instance = map;
		if (!instance || appliedLayer === chosen) return;
		appliedLayer = chosen;
		instance.setStyle(style);
	});

	/** The pins. Where each person was at `asOf`, which is now unless the playhead is set. */
	const pins = $derived.by(() => {
		if (playhead === null) {
			return latest.value.map((fix) => ({ fix, userId: fix.userId }));
		}
		const out: { fix: LocationFix; userId: string }[] = [];
		for (const [userId, path] of Object.entries(paths.value)) {
			// The last fix at or before the playhead: where they actually were then, rather than
			// an interpolation between two points that invents a route nobody took.
			let held: LocationFix | null = null;
			for (const fix of path) {
				if (fix.capturedAt > asOf) break;
				held = fix;
			}
			if (held) out.push({ fix: held, userId });
		}
		return out;
	});

	/** The selected person's current pin, or null — including when they no longer have one. */
	const selected = $derived(pins.find((p) => p.userId === selectedUserId) ?? null);

	const nameFor = $derived((userId: string) =>
		userId === identity.current?.userId
			? 'You'
			: members.value.find((m) => m.userId === userId)?.displayName || 'Someone'
	);

	$effect(() => {
		const module = maplibre;
		const instance = map;
		const current = pins;
		const at = asOf;
		const naming = nameFor;
		const photos = avatars.urls;
		const mine = identity.avatarUrl;
		const selected = selectedUserId;
		if (!module || !instance) return;

		const seen = new Set<string>();
		for (const { fix, userId } of current) {
			seen.add(userId);
			let marker = markers.get(userId);
			if (!marker) {
				const el = document.createElement('div');
				el.className = 'fg-pin';
				el.innerHTML = PIN_HTML;
				// A pin is a control, not decoration: tapping a face is the shortest way to ask
				// "where is this one person", so it selects them and the camera follows.
				el.setAttribute('role', 'button');
				el.tabIndex = 0;
				el.addEventListener('click', () => select(userId));
				// Anchored at the tip rather than the middle: a teardrop centred on a coordinate
				// points at a spot half its own height away from the one it means.
				marker = new module.Marker({ element: el, anchor: 'bottom' })
					.setLngLat([fix.lon, fix.lat])
					.addTo(instance);
				markers.set(userId, marker);
			}
			const el = marker.getElement();
			const who = naming(userId);
			el.style.setProperty('--pin', colourFor(userId));
			// A selected pin stays solid however old its fix is, because fading the one thing the
			// person just asked to look at reads as the app having lost them.
			el.style.opacity = String(userId === selected ? 1 : stalenessOpacity(fix.capturedAt, at));
			el.classList.toggle('is-selected', userId === selected);
			el.title = `${who} · ${relativeTime(fix.capturedAt, at)}`;
			el.setAttribute('aria-label', `${who}, ${relativeTime(fix.capturedAt, at)}`);

			const url = userId === identity.current?.userId ? mine : (photos[userId] ?? null);
			const photo = el.querySelector('img.fg-pin-photo') as HTMLImageElement | null;
			const label = el.querySelector('.fg-pin-initial') as HTMLElement | null;
			if (photo) {
				if (url) photo.src = url;
				else photo.removeAttribute('src');
				photo.hidden = !url;
			}
			if (label) {
				label.textContent = (who.trim()[0] ?? '?').toUpperCase();
				label.hidden = !!url;
			}
			marker.setLngLat([fix.lon, fix.lat]);
		}
		for (const [userId, marker] of markers) {
			if (!seen.has(userId)) {
				marker.remove();
				markers.delete(userId);
			}
		}
	});

	/**
	 * Selecting someone flies the camera to them.
	 *
	 * Written as a function called from the taps rather than as an effect on `selectedUserId`: an
	 * effect would also fire when their pin moved or when the list reloaded, which would drag the
	 * camera back on its own while somebody was panning away to look at something else.
	 */
	function select(userId: string | null) {
		selectedUserId = userId === selectedUserId ? null : userId;
		flyToSelected();
	}

	function flyToSelected() {
		const instance = map;
		const userId = selectedUserId;
		if (!instance || !userId) return;
		const fix = pins.find((p) => p.userId === userId)?.fix;
		if (!fix) return;
		instance.flyTo({
			center: [fix.lon, fix.lat],
			zoom: Math.max(instance.getZoom(), 16),
			speed: 1.2,
			essential: true
		});
	}

	/** The trails, as one GeoJSON source per redraw rather than one layer per person. */
	$effect(() => {
		// Read so a restyle re-adds the layer the new style just discarded.
		styleEpoch;
		const instance = map;
		if (!instance || !instance.isStyleLoaded()) return;
		const features = Object.entries(paths.value)
			.map(([userId, path]) => {
				const upTo = playhead === null ? path : path.filter((f) => f.capturedAt <= asOf);
				// Thinned for drawing only. A week at the shortest push interval is tens of
				// thousands of points each, and the map draws everyone at once.
				const points = decimate(upTo, 500);
				return {
					type: 'Feature' as const,
					// With everyone drawn at once, selecting someone has to mean something on the
					// map too: theirs stays solid and thick, the rest drop back far enough to read
					// as context without disappearing.
					properties: {
						colour: colourFor(userId),
						opacity: selectedUserId === null ? 0.75 : userId === selectedUserId ? 0.95 : 0.25,
						width: userId === selectedUserId ? 5 : 3
					},
					geometry: {
						type: 'LineString' as const,
						coordinates: points.map((f) => [f.lon, f.lat])
					}
				};
			})
			.filter((f) => f.geometry.coordinates.length > 1);

		const data = { type: 'FeatureCollection' as const, features };
		const source = instance.getSource('trails');
		if (source && 'setData' in source) {
			(source as { setData(d: unknown): void }).setData(data);
			return;
		}
		instance.addSource('trails', { type: 'geojson', data });
		instance.addLayer({
			id: 'trails',
			type: 'line',
			source: 'trails',
			paint: {
				'line-color': ['get', 'colour'],
				'line-width': ['get', 'width'],
				'line-opacity': ['get', 'opacity']
			},
			layout: { 'line-cap': 'round', 'line-join': 'round' }
		});
	});

	async function toggleShare() {
		const current = group.value;
		if (!current) return;
		await setShareLocation(groupId, !current.shareLocation);
		await locationEngine.refresh();
	}

	async function forget(userId: string) {
		await db.locations
			.where('[groupId+userId+capturedAt]')
			.between([groupId, userId, -Infinity], [groupId, userId, Infinity])
			.delete();
	}

	function fitToPeople() {
		if (!map || pins.length === 0) return;
		void (async () => {
			const maplibre = await import('maplibre-gl');
			const bounds = new maplibre.LngLatBounds();
			for (const { fix } of pins) bounds.extend([fix.lon, fix.lat]);
			map!.fitBounds(bounds, { padding: 64, maxZoom: 16 });
		})();
	}
</script>

<svelte:head><title>Map · {group.value?.name ?? 'Family Group'}</title></svelte:head>

<TopBar title="Map" back="/groups/{groupId}">
	<button class="btn btn-quiet !px-2 !py-1" onclick={fitToPeople} aria-label="Fit to everyone"
		>⤢</button
	>
	<button
		class="btn !px-3 !py-1 {historyOn ? 'btn-primary' : 'btn-quiet'}"
		onclick={() => {
			historyOn = !historyOn;
			playhead = null;
			playing = false;
		}}>History</button
	>
</TopBar>

{#if !group.value?.locationEnabled}
	<div class="px-5 py-4">
		<p class="muted text-sm leading-relaxed">
			Location is off for this group. Only the admin can turn it on, and turning it on does not put
			anyone on the map — each person still has their own switch.
		</p>
		{#if group.value?.isAdmin}
			<a class="btn btn-primary mt-3" href="{base}/groups/{groupId}/location/">Location settings</a>
		{/if}
	</div>
{/if}

<div bind:this={container} class="relative min-h-[220px] flex-1"></div>

<!-- The controls keep their own scroll rather than squeezing the map: with history open this
     panel is taller than the space under a map worth looking at. -->
<div
	class="max-h-[55%] shrink-0 overflow-y-auto px-4 py-3"
	style="border-top: 1px solid var(--outline); padding-bottom: calc(0.75rem + env(safe-area-inset-bottom))"
>
	<div class="flex flex-wrap items-center gap-2">
		<button
			class="btn {group.value?.shareLocation ? 'btn-primary' : 'btn-quiet'}"
			disabled={!group.value?.locationEnabled}
			onclick={toggleShare}
		>
			{group.value?.shareLocation ? 'Sharing' : 'Share my location'}
		</button>

		<select class="field !w-auto !py-1.5 text-xs" bind:value={layer}>
			{#each MAP_LAYERS as option (option.id)}
				<option value={option.id} title={option.note}>{option.label}</option>
			{/each}
		</select>

		{#if group.value?.isAdmin}
			<a class="btn btn-quiet !py-1.5 text-xs" href="{base}/groups/{groupId}/location/">Settings</a>
		{/if}
	</div>

	{#if locationEngine.permission === 'denied'}
		<p class="mt-2 text-xs" style="color: var(--error)">
			The browser is refusing this page a position. That is a site permission, changed from the icon
			in the address bar.
		</p>
	{/if}

	{#if historyOn}
		<div class="mt-3 flex flex-wrap items-center gap-2">
			<select class="field !w-auto !py-1.5 text-xs" bind:value={rangeId}>
				{#each HISTORY_RANGES as option (option.id)}
					<option value={option.id}>{option.label}</option>
				{/each}
			</select>
			{#if rangeId === 'custom'}
				<input class="field !w-auto !py-1.5 text-xs" type="date" bind:value={customSince} />
				<input class="field !w-auto !py-1.5 text-xs" type="date" bind:value={customUntil} />
			{/if}
		</div>

		<div class="mt-3 flex items-center gap-2">
			<button
				class="btn btn-quiet !px-3 !py-1"
				onclick={() => {
					playhead ??= 0;
					playing = !playing;
				}}>{playing ? '⏸' : '▶'}</button
			>
			<input
				class="flex-1"
				type="range"
				min="0"
				max={range.until - range.since}
				step="1000"
				value={playhead ?? range.until - range.since}
				oninput={(e) => (playhead = Number(e.currentTarget.value))}
				aria-label="Playback position"
			/>
			<select class="field !w-auto !py-1 text-xs" bind:value={speed}>
				{#each [1, 2, 4, 8] as x (x)}
					<option value={x}>{x}×</option>
				{/each}
			</select>
			<button
				class="btn btn-quiet !px-3 !py-1"
				onclick={() => {
					playhead = null;
					playing = false;
				}}>Now</button
			>
		</div>
		{#if playhead !== null}
			<p class="muted mt-1 text-xs">Showing {new Date(asOf).toLocaleString()}</p>
		{/if}
	{/if}

	<!-- Everyone with a position, as faces. Tapping one flies the map to them; tapping the
	     selected one again lets the map go back to showing the whole group. -->
	<ul class="mt-3 flex flex-wrap gap-3">
		{#each pins as pin (pin.userId)}
			{@const isSelected = pin.userId === selectedUserId}
			<li>
				<button
					class="fg-person flex w-[72px] flex-col items-center gap-1 rounded-xl px-1 py-1.5"
					class:is-selected={isSelected}
					aria-pressed={isSelected}
					onclick={() => select(pin.userId)}
				>
					<span class="fg-person-ring rounded-full" style="--ring: {colourFor(pin.userId)}">
						<Avatar
							url={avatars.for(pin.userId)}
							name={nameFor(pin.userId)}
							userId={pin.userId}
							size={40}
						/>
					</span>
					<span class="w-full truncate text-center text-xs font-medium">{nameFor(pin.userId)}</span>
					<span class="muted w-full truncate text-center text-[11px]">
						{relativeTime(pin.fix.capturedAt, asOf)}
					</span>
				</button>
			</li>
		{/each}
		{#if pins.length === 0}
			<li class="muted text-xs">Nobody is on the map yet.</li>
		{/if}
	</ul>

	{#if selected}
		<div
			class="mt-3 flex items-center gap-3 rounded-xl px-3 py-2.5"
			style="background: var(--surface-1); border: 1px solid var(--outline)"
		>
			<Avatar
				url={avatars.for(selected.userId)}
				name={nameFor(selected.userId)}
				userId={selected.userId}
				size={40}
			/>
			<div class="min-w-0 flex-1">
				<p class="truncate text-sm font-semibold">{nameFor(selected.userId)}</p>
				<p class="muted text-xs">
					{relativeTime(selected.fix.capturedAt, asOf)}
					{#if selected.fix.accuracy > 0}· ±{Math.round(selected.fix.accuracy)} m{/if}
					{#if selected.fix.speed > 1}· {Math.round(selected.fix.speed * 3.6)} km/h{/if}
				</p>
			</div>
			<button class="btn btn-quiet !px-3 !py-1 text-xs" onclick={() => flyToSelected()}>
				Centre
			</button>
			{#if historyOn}
				<button
					class="btn btn-quiet !px-3 !py-1 text-xs"
					style="color: var(--error)"
					title="Delete this person's history"
					onclick={() => forget(selected.userId)}
				>
					Erase
				</button>
			{/if}
			<button class="btn btn-quiet !px-2 !py-1" aria-label="Close" onclick={() => select(null)}>
				×
			</button>
		</div>
	{/if}

	<p class="muted mt-3 text-xs leading-relaxed">
		A browser only reports a position while this page is open and in front. Put the tab away and
		your pin stops moving for everyone else, which looks exactly like a device that is off — so this
		is not the thing to rely on for someone who needs to be found.
	</p>
</div>

<style>
	/*
	 * The pin is a ringed disc with a tail: 34px of face, an 8px point below it, anchored at the
	 * tip so the point sits on the coordinate rather than the middle of the photo.
	 */
	:global(.fg-pin) {
		position: relative;
		width: 34px;
		height: 42px;
		color: var(--pin, #666);
		filter: drop-shadow(0 1px 3px rgb(0 0 0 / 0.45));
		cursor: pointer;
		border: 0;
		padding: 0;
		background: none;
	}

	/* The tail, in the ring's colour so it reads as part of the same shape. */
	:global(.fg-pin::after) {
		content: '';
		position: absolute;
		left: 50%;
		bottom: 0;
		translate: -50% 0;
		border-left: 5px solid transparent;
		border-right: 5px solid transparent;
		border-top: 9px solid white;
	}

	:global(.fg-pin .fg-pin-disc) {
		position: absolute;
		inset: 0 0 auto 0;
		display: grid;
		place-items: center;
		width: 34px;
		height: 34px;
		border-radius: 50%;
		background: currentColor;
		border: 2px solid white;
		overflow: hidden;
		box-sizing: border-box;
	}

	:global(.fg-pin .fg-pin-photo) {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}

	:global(.fg-pin .fg-pin-initial) {
		color: white;
		font-size: 15px;
		font-weight: 700;
		line-height: 1;
		pointer-events: none;
	}

	/* Selection, said on the pin itself: a wider ring in the accent colour, and a little taller
	   than the rest so it is findable in a cluster. */
	:global(.fg-pin.is-selected) {
		scale: 1.12;
		transform-origin: bottom center;
		z-index: 1;
	}

	:global(.fg-pin.is-selected .fg-pin-disc) {
		border-color: var(--accent);
		border-width: 3px;
	}

	:global(.fg-pin.is-selected::after) {
		border-top-color: var(--accent);
	}

	/* The strip below the map. */
	.fg-person {
		border: 1px solid transparent;
		background: none;
		color: inherit;
		cursor: pointer;
	}

	.fg-person.is-selected {
		background: var(--surface-1);
		border-color: var(--accent);
	}

	/* The same colour their trail is drawn in — without it the map is coloured lines with no key. */
	.fg-person-ring {
		display: grid;
		place-items: center;
		padding: 2px;
		box-shadow: inset 0 0 0 2px var(--ring);
	}
</style>
