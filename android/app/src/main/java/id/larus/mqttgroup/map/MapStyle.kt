package id.larus.mqttgroup.map

/**
 * Where the tiles come from — the only place in the app that knows, which is the point.
 *
 * plan.md's rule was that the database never learns about the map vendor, and the `location`
 * table keeps to it: six raw numbers, no vendor types. This object is the other half of that
 * bargain, so changing tile provider is one style rather than a migration.
 *
 * ### Google's raster tiles
 *
 * `mt{1,2,3}.google.com/vt?lyrs=m` — the road map behind Google Maps, as 256px raster tiles.
 * Chosen because it is the map people in this app's part of the world actually recognise:
 * OpenStreetMap's coverage of Indonesian residential streets and their names is patchy enough
 * that "where are they" was answerable on a Google map and guesswork on an OSM one.
 *
 * Two things should be said plainly rather than dressed up. First, this is Google's internal
 * tile endpoint, not the Maps Platform API; using it outside a Google Maps product is against
 * Google's terms, so it is a choice for a private build among family and not something to ship
 * to a store. Second, the privacy trade the old OpenFreeMap comment described gets worse, not
 * better: Google sees the IP address and the tile coordinates, and Google is very good at
 * joining that to everything else it knows. It still cannot see whose position is drawn on top —
 * that never leaves the device — but it can see where someone is looking.
 *
 * [openFreeMapStyleUrl] is kept for whoever would rather have the old vector map back: swapping
 * the one call in MapScreen restores it.
 */
/**
 * The three maps worth offering, and no more.
 *
 * Google publishes a dozen `lyrs` combinations, but past these three they are either
 * near-duplicates or specialist (terrain-only, transit-only, traffic overlays). Three is also
 * the number that fits a row of choices someone reads in one glance rather than a menu they
 * have to study.
 *
 * [lyrs] is Google's own layer code: `m` roads, `s` raw imagery, `y` imagery with the road and
 * place labels drawn on top.
 */
enum class MapLayer(
    val lyrs: String,
    val label: String,
    val detail: String,
) {
    STREET("m", "Street", "Roads and place names. Lightest on data."),
    SATELLITE("s", "Satellite", "Photography only, with nothing written on it."),
    HYBRID("y", "Hybrid", "The photography with roads and names over it.");

    /** True where a night version makes sense. Dimming a photograph does not read as a dark
     * theme, it reads as a photograph taken at dusk, so imagery is left alone. */
    val supportsDarkTint: Boolean get() = this == STREET

    companion object {
        val DEFAULT = STREET

        /** Tolerant of a stored value from a build that spelled them differently. */
        fun fromName(name: String?): MapLayer =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object MapStyle {

    /**
     * A whole style document rather than a URL, because a raster source has no style server to
     * ask — the four lines of JSON below *are* the style.
     *
     * Dark mode is the same tiles put through MapLibre's raster paint filters instead of a
     * second set of tiles, since Google publishes no dark `lyrs`. Dimmed and desaturated is not
     * a designed night style, but it stops the map being a torch in a dark room, which is the
     * problem being solved.
     */
    fun styleJson(layer: MapLayer, darkTheme: Boolean): String = """
    {
      "version": 8,
      "name": "Google ${layer.name}",
      "glyphs": "$GLYPHS",
      "sources": {
        "$SOURCE": {
          "type": "raster",
          "tiles": [
            "https://mt1.google.com/vt/lyrs=${layer.lyrs}&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=${layer.lyrs}&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=${layer.lyrs}&x={x}&y={y}&z={z}"
          ],
          "tileSize": 256,
          "minzoom": 0,
          "maxzoom": 20,
          "attribution": "${attribution(layer)}"
        }
      },
      "layers": [
        {
          "id": "$BASE_LAYER",
          "type": "raster",
          "source": "$SOURCE",
          "paint": ${if (darkTheme && layer.supportsDarkTint) DARK_PAINT else LIGHT_PAINT}
        }
      ]
    }
    """.trimIndent()

    /** The vector styles this app used before, kept as the one-line way back. */
    fun openFreeMapStyleUrl(darkTheme: Boolean): String =
        if (darkTheme) "https://tiles.openfreemap.org/styles/dark"
        else "https://tiles.openfreemap.org/styles/liberty"

    /**
     * Attribution wherever the tiles are shown. The style itself carries the string, but
     * MapLibre's built-in attribution control is an Android view we are not using inside a
     * Compose surface, so the map screen draws this itself.
     */
    fun attribution(layer: MapLayer): String =
        if (layer == MapLayer.STREET) "Map data © Google" else "Imagery © Google"

    /** The base tile layer's id, so app layers can be inserted relative to it if that is ever
     * needed — and so nothing accidentally names it twice. */
    const val BASE_LAYER = "google-roads"

    private const val SOURCE = "google-raster"

    /**
     * A raster style has no fonts of its own, and MapLibre draws no text at all — not even the
     * app's own pin labels — without a glyph endpoint. OpenFreeMap's is public and keyless, so
     * borrowing it costs nothing beyond a font request; [PIN_FONT] is a stack it serves.
     */
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    /** The font the map screen must ask for by name, since the style declares no default. */
    val PIN_FONT = arrayOf("Noto Sans Regular")

    private const val LIGHT_PAINT = """{ "raster-opacity": 1.0 }"""

    /** Dimmed, desaturated and with the white end of the range pulled down, which is what turns
     * a daylight map into something readable at night. */
    private const val DARK_PAINT = """
        {
          "raster-opacity": 1.0,
          "raster-brightness-min": 0.0,
          "raster-brightness-max": 0.6,
          "raster-saturation": -0.6,
          "raster-contrast": -0.1
        }"""
}
