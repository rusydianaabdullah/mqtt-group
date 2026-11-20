package id.larus.mqttgroup.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which map the person prefers. One preference for the whole app rather than one per group:
 * street-or-satellite is a statement about how someone likes to read a map, not about a
 * particular set of people.
 *
 * Plain SharedPreferences, deliberately not the encrypted database. Nothing here is private —
 * "this phone likes satellite" says nothing about anyone's position — and keeping it out of the
 * database means the map can pick its style before the Keystore has been touched, which is what
 * stops a cold start showing the wrong tiles for a moment and then swapping them.
 */
class MapPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _layer = MutableStateFlow(MapLayer.fromName(prefs.getString(KEY_LAYER, null)))

    /** The chosen layer, hot from the moment the app starts. */
    val layer: StateFlow<MapLayer> = _layer.asStateFlow()

    fun setLayer(layer: MapLayer) {
        // The flow first, so the map redraws on the tap rather than after the disk write.
        _layer.value = layer
        prefs.edit().putString(KEY_LAYER, layer.name).apply()
    }

    private companion object {
        const val PREFS = "map"
        const val KEY_LAYER = "layer"
    }
}
