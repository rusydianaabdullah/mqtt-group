import { fromUtf8, utf8 } from '$lib/util/bytes';

/**
 * The plaintext inside a `loc` envelope: one position, from one person, at one moment.
 *
 * Deliberately the same six raw fields the `locations` table stores and nothing else — no map
 * vendor's types, no address, no "place name". Reverse geocoding would mean handing someone
 * else's coordinates to a third party, which is the one thing this app exists not to do; the
 * map draws from numbers and nothing looks them up.
 *
 * Published QoS 0 and retained. Retained is what makes opening the map show everyone
 * immediately instead of an empty screen until the next tick. QoS 0 because a position that did
 * not arrive is already replaced by a better one.
 */
export interface LocationPayload {
	lat: number;
	lon: number;
	/** Metres of horizontal uncertainty. Drawn as the halo around a pin. */
	accuracy: number;
	/** Metres per second, or 0 when the fix carried none. */
	speed: number;
	/** Degrees from true north, or 0 when the fix carried none. */
	bearing: number;
	/**
	 * When the fix was taken, not when it was sent. Those differ by however long the device was
	 * offline, and the difference is the whole reason to carry it: a receiver showing "4 minutes
	 * ago" from the send time would confidently mislabel a position from yesterday.
	 */
	capturedAt: number;
}

const LAT = 'la';
const LON = 'lo';
const ACC = 'ac';
const SPD = 'sp';
const BRG = 'br';
const AT = 'at';

export function encodeLocation(p: LocationPayload): Uint8Array {
	return utf8(
		JSON.stringify({
			[LAT]: p.lat,
			[LON]: p.lon,
			[ACC]: p.accuracy,
			[SPD]: p.speed,
			[BRG]: p.bearing,
			[AT]: p.capturedAt
		})
	);
}

/**
 * Returns null rather than throwing, like every other codec here.
 *
 * The range checks are not paranoia about our own encoder — they are about what a broker can
 * hand us. A payload only reaches this point after opening under the group key, so it came from
 * a member, but a member running a modified build could still put a latitude of 900 on the
 * wire, and MapLibre's answer to that is a WebGL context that gives up rather than an exception
 * anyone can catch.
 */
export function decodeLocation(plaintext: Uint8Array): LocationPayload | null {
	try {
		const json = JSON.parse(fromUtf8(plaintext));
		const lat = json[LAT];
		const lon = json[LON];
		if (
			typeof lat !== 'number' ||
			typeof lon !== 'number' ||
			Number.isNaN(lat) ||
			Number.isNaN(lon) ||
			lat < -90 ||
			lat > 90 ||
			lon < -180 ||
			lon > 180
		) {
			return null;
		}
		return {
			lat,
			lon,
			accuracy: num(json[ACC]),
			speed: num(json[SPD]),
			bearing: num(json[BRG]),
			capturedAt: typeof json[AT] === 'number' ? json[AT] : Date.now()
		};
	} catch {
		return null;
	}
}

function num(value: unknown): number {
	return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}
