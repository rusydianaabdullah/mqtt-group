import { db, type Group } from '$lib/data/db/schema';
import { publishLocation } from '$lib/data/repo/groupRepo';
import type { LocationPayload } from '$lib/mqtt/locationMessages';

/**
 * Where the battery is saved, and where the honesty about a browser has to be loudest.
 *
 * The gate is the Android app's, unchanged, because it is about what is worth telling the group
 * rather than about any platform:
 *
 * ```
 * a fix arrives
 *    ↓  has pushSec elapsed since the last publish?
 *    ├─ no  → store only
 *    └─ yes ↓  moved ≥ minDistanceM since the last published position?
 *           ├─ yes → publish
 *           └─ no  ↓  has 3× pushSec elapsed since the last publish?
 *                  ├─ yes → publish anyway (the heartbeat)
 *                  └─ no  → store only
 * ```
 *
 * The heartbeat is what separates "stationary" from "the device is gone", which the map cannot
 * otherwise tell apart — and which is the single question anyone opening it is actually asking.
 *
 * What is different here, and it is the whole difference: this runs only while the page is open
 * and visible. `watchPosition` in a backgrounded tab is throttled to nothing by every mobile
 * browser and suspended outright by iOS Safari. So a browser goes quiet the moment it is put
 * away, and everyone else sees the pin age — which, on the phone, meant the device was off. Here
 * it means the tab was. The map says "last seen" in words for exactly this reason, and the
 * settings screen says it in a sentence.
 */
export class LocationEngine {
	private watchId: number | null = null;
	private sharing: Group[] = [];
	/**
	 * The last position actually published, per group — not the last one taken. The gate measures
	 * against what the group has been told, since measuring against the last *sample* would let a
	 * slow drift of sub-threshold steps carry someone across town without a single publish.
	 */
	private lastPublished = new Map<string, LocationPayload>();
	private lastPublishedAt = new Map<string, number>();
	private active: { sampleSec: number } | null = null;
	private onChange: (() => void) | null = null;

	/** What the geolocation permission looks like right now, for the settings screen. */
	permission: PermissionState | 'unsupported' = 'prompt';

	async refresh(notify?: () => void): Promise<void> {
		this.onChange = notify ?? this.onChange;
		if (!('geolocation' in navigator)) {
			this.permission = 'unsupported';
			return;
		}
		const groups = await db.groups.toArray();
		this.apply(groups.filter((g) => g.locationEnabled && g.shareLocation));
	}

	stop(): void {
		this.unwatch();
		this.sharing = [];
		this.lastPublished.clear();
		this.lastPublishedAt.clear();
	}

	private apply(groupsSharing: Group[]): void {
		this.sharing = groupsSharing;
		// Positions for a group we no longer share with would otherwise pin its gate open
		// forever, and quietly leak a last-known position across a "stop sharing".
		const wanted = new Set(groupsSharing.map((g) => g.groupId));
		for (const id of [...this.lastPublished.keys()]) {
			if (!wanted.has(id)) {
				this.lastPublished.delete(id);
				this.lastPublishedAt.delete(id);
			}
		}
		if (groupsSharing.length === 0) {
			this.unwatch();
			return;
		}
		// The loosest requirement across every group we share with, so no group is starved of a
		// fix it needed by another group's slower settings.
		const sampleSec = Math.min(...groupsSharing.map((g) => g.locSampleSec));
		if (this.active?.sampleSec === sampleSec && this.watchId !== null) return;
		this.watch(sampleSec);
	}

	private watch(sampleSec: number): void {
		this.unwatch();
		this.active = { sampleSec };
		this.watchId = navigator.geolocation.watchPosition(
			(position) => void this.onFix(position),
			() => {
				// A refused or failed fix is not an error to raise repeatedly — the settings
				// screen already shows the permission state, and a browser that has said no will
				// go on saying no.
				this.permission = 'denied';
				this.onChange?.();
			},
			{
				enableHighAccuracy: true,
				// The browser may hand back a cached fix, and up to one sampling interval old is
				// exactly as fresh as asking for a new one would have been.
				maximumAge: sampleSec * 1000,
				timeout: 30_000
			}
		);
	}

	private unwatch(): void {
		if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
		this.watchId = null;
		this.active = null;
	}

	private async onFix(position: GeolocationPosition): Promise<void> {
		this.permission = 'granted';
		const payload: LocationPayload = {
			lat: position.coords.latitude,
			lon: position.coords.longitude,
			accuracy: position.coords.accuracy ?? 0,
			speed: position.coords.speed ?? 0,
			bearing: position.coords.heading ?? 0,
			// When the fix was taken, not when it was sent. A receiver showing "4 minutes ago"
			// from the send time would confidently mislabel a position from an hour ago.
			capturedAt: position.timestamp || Date.now()
		};

		for (const group of this.sharing) {
			if (this.shouldPublish(group, payload)) {
				this.lastPublished.set(group.groupId, payload);
				this.lastPublishedAt.set(group.groupId, Date.now());
				await publishLocation(group.groupId, payload);
			} else {
				// Stored but not published: the local trail stays continuous even when the group
				// is only told every five minutes, which is what makes our own history worth
				// having at a finer grain than everyone else's.
				await db.locations.add({
					groupId: group.groupId,
					userId: (await db.identity.get(0))!.userId,
					...payload
				});
			}
		}
		this.onChange?.();
	}

	private shouldPublish(group: Group, fix: LocationPayload): boolean {
		const last = this.lastPublished.get(group.groupId);
		const lastAt = this.lastPublishedAt.get(group.groupId);
		if (!last || !lastAt) return true;

		const sincePublish = Date.now() - lastAt;
		if (sincePublish < group.locPushSec * 1000) return false;
		if (metresBetween(last.lat, last.lon, fix.lat, fix.lon) >= group.locMinDistanceM) return true;
		// The heartbeat: three intervals of silence is where "not moving" starts to look like
		// "not there", and those are the two answers the map exists to tell apart.
		return sincePublish >= group.locPushSec * 3000;
	}
}

/**
 * Great-circle distance in metres.
 *
 * The haversine formula, because `Location.distanceBetween` on Android is Vincenty on the WGS-84
 * ellipsoid and this is not: over the tens to hundreds of metres a movement gate deals with, the
 * two differ by centimetres, and the gate's own threshold is a whole number of metres. Anywhere
 * this mattered, the difference would already be lost in GPS accuracy.
 */
export function metresBetween(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const R = 6_371_008.8;
	const toRad = Math.PI / 180;
	const dLat = (lat2 - lat1) * toRad;
	const dLon = (lon2 - lon1) * toRad;
	const a =
		Math.sin(dLat / 2) ** 2 +
		Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
	return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * Throws away location history past each group's retention window.
 *
 * Per group rather than one global cutoff, because retention is one of the admin's settings and
 * two groups can reasonably disagree about it.
 *
 * The uncomfortable part, stated plainly because the UI says it too: this is *this device's*
 * retention. Everyone in a group holds everyone's history, so a member who set "keep forever"
 * still has your track after you pruned yours. Serverless means there is no single copy to
 * delete, and no honest way to promise otherwise.
 */
export async function pruneHistory(now = Date.now()): Promise<number> {
	let removed = 0;
	for (const group of await db.groups.toArray()) {
		// 0 is the explicit "keep forever", not a zero-length window — pruning everything would
		// be the exact opposite of what it asks for.
		if (group.locRetentionHours <= 0) continue;
		const cutoff = now - group.locRetentionHours * 3_600_000;
		removed += await db.locations
			.where('[groupId+capturedAt]')
			.between([group.groupId, -Infinity], [group.groupId, cutoff])
			.delete();
	}
	return removed;
}

export const locationEngine = new LocationEngine();
