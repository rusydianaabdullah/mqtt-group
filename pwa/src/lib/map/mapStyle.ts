import type { StyleSpecification } from 'maplibre-gl';

/**
 * Where the tiles come from — the only place in the app that knows, which is the point.
 *
 * The plan's rule was that the database never learns about the map vendor, and the `locations`
 * table keeps to it: six raw numbers, no vendor types. This module is the other half of that
 * bargain, so changing provider is one style object rather than a migration.
 *
 * Two providers, and the difference between them is not a matter of taste.
 *
 * **OpenFreeMap** is the default and the one this project can honestly ship. Vector tiles, no
 * API key, no account, and — unlike every keyed free tier — no attributable request from a known
 * application. It is served by a volunteer-funded project, and its coverage is OpenStreetMap's:
 * excellent in some places and thin in others.
 *
 * **Google's raster tiles** are here because the Android app added them for a specific and real
 * reason: OpenStreetMap's coverage of Indonesian residential streets and their names is patchy
 * enough that "where are they" was answerable on a Google map and guesswork on an OSM one. Two
 * things about that, said plainly rather than dressed up. It is Google's internal tile endpoint,
 * not the Maps Platform API, so using it outside a Google Maps product is against Google's
 * terms — a choice for a private build among family, not something to put in an app store. And
 * the privacy trade is worse, not better: Google sees the IP address and the tile coordinates,
 * and is very good at joining that to everything else it knows. It still cannot see whose
 * position is drawn on top — that never leaves the device — but it can see where someone looks.
 *
 * So the picker offers both and says which is which. Nothing is chosen for the user quietly.
 */

/** Google's own layer codes: `m` roads, `s` raw imagery, `y` imagery with labels drawn on top. */
export type MapLayer = 'openfreemap' | 'm' | 's' | 'y';

export const MAP_LAYERS: { id: MapLayer; label: string; note: string }[] = [
	{ id: 'openfreemap', label: 'Streets', note: 'OpenStreetMap data, no account, no tracking' },
	{ id: 'm', label: 'Google roads', note: 'Better street coverage; Google sees where you look' },
	{ id: 's', label: 'Satellite', note: 'Google imagery' },
	{ id: 'y', label: 'Hybrid', note: 'Google imagery with labels' }
];

export function styleFor(layer: MapLayer, dark: boolean): string | StyleSpecification {
	return layer === 'openfreemap' ? openFreeMapStyleUrl(dark) : googleRasterStyle(layer);
}

export function openFreeMapStyleUrl(dark: boolean): string {
	return dark
		? 'https://tiles.openfreemap.org/styles/dark'
		: 'https://tiles.openfreemap.org/styles/liberty';
}

/**
 * A minimal style wrapping Google's raster tiles.
 *
 * Three subdomains because raster tiles are requested in bulk and browsers cap connections per
 * host — one host would serialise a pan into visible tearing.
 */
function googleRasterStyle(lyrs: string): StyleSpecification {
	return {
		version: 8,
		sources: {
			google: {
				type: 'raster',
				tiles: [1, 2, 3].map((n) => `https://mt${n}.google.com/vt/lyrs=${lyrs}&x={x}&y={y}&z={z}`),
				tileSize: 256,
				maxzoom: 20,
				attribution: 'Map data © Google'
			}
		},
		layers: [{ id: 'google', type: 'raster', source: 'google' }]
	};
}

/**
 * A stable colour per person, derived from their user id.
 *
 * Stable matters more than pretty: the same person must be the same colour on every device in
 * the group, and nothing about the choice is published, so it has to be a pure function of
 * something everyone already has. Hue only — the lightness is fixed so a colour is legible on
 * both the light and the dark map.
 */
export function colourFor(userId: string): string {
	let hash = 7;
	for (const c of userId) hash = (hash * 31 + c.charCodeAt(0)) % 360;
	return `hsl(${hash} 70% 45%)`;
}

/**
 * How stale a position looks, as an opacity.
 *
 * Fresh is solid, an hour old is a ghost. This is the map's whole vocabulary for the question
 * everyone actually opens it to ask, and in a browser it carries more weight than on the phone:
 * a backgrounded tab stops reporting within seconds, so pins age constantly and normally.
 */
export function stalenessOpacity(capturedAt: number, now = Date.now()): number {
	const minutes = (now - capturedAt) / 60_000;
	if (minutes <= 2) return 1;
	if (minutes >= 60) return 0.35;
	return 1 - 0.65 * ((minutes - 2) / 58);
}
