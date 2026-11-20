package id.larus.mqttgroup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableIntStateOf
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.LocationEntity
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.data.HistoryRange
import id.larus.mqttgroup.map.MapLayer
import id.larus.mqttgroup.map.MapStyle
import id.larus.mqttgroup.mqtt.ConnectionState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

/**
 * The group's map: everyone's latest position, and one person's recent track when you tap them.
 *
 * Behaves the way a map is expected to — drag to pan, pinch to zoom, two fingers to rotate and
 * tilt, a compass that appears once the map is turned and a my-location puck — because those are
 * not features so much as the baseline for something not feeling broken. MapLibre gives all of
 * that for free once the gestures are enabled; what this screen adds is the group on top of it.
 *
 * Markers are drawn as the same round avatars the chat uses, so a face means the same thing in
 * both places, and are faded once a position is stale enough to be misleading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    group: GroupEntity,
    members: List<MemberEntity>,
    /** Everyone's most recent position. One row per person, or none if they have never shared. */
    locations: List<LocationEntity>,
    /** Every member's track inside [range], keyed by user, each oldest first. The whole group's
     * paths are drawn at once; selecting someone picks theirs out rather than hiding the rest. */
    paths: Map<String, List<LocationEntity>>,
    /** Whether history is being shown at all. Off means a live map and no paths — see the
     * history button in the corner. */
    historyVisible: Boolean,
    onToggleHistory: (Boolean) -> Unit,
    /** How far back the paths reach, and the way to change it. Only meaningful while
     * [historyVisible]. */
    range: HistoryRange,
    onRangeChange: (HistoryRange) -> Unit,
    /** Erases stored positions for one person, on this phone only. */
    onDeleteHistory: (String) -> Unit,
    selfUserId: String,
    selectedUserId: String?,
    avatarFor: (MemberEntity) -> ByteArray?,
    onSelectUser: (String?) -> Unit,
    /** The chosen tile layer, and the way to change it. Held by the caller so that the map and
     * the Settings screen are two views of one preference rather than two preferences. */
    layer: MapLayer,
    onLayerChange: (MapLayer) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    /** The broker connection, shown in the bar the same way the chat screen shows it: a map of
     * where everyone was an hour ago looks exactly like a map of where they are now, so the one
     * thing that tells them apart has to be on screen. */
    connection: ConnectionState,
    onReconnect: () -> Unit,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    // MapLibre.getInstance has to run before the first MapView is constructed. It takes no API
    // key — the single-argument overload is the whole point of the provider choice in MapStyle.
    remember { MapLibre.getInstance(context) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    /** Set once the first batch of positions has been framed, so the camera stops fighting the
     * user: nothing is more irritating than a map that yanks itself back every time a position
     * arrives while you are trying to look somewhere else. */
    var framed by remember { mutableStateOf(false) }

    val membersById = remember(members) { members.associateBy { it.userId } }

    // --- Playback ---------------------------------------------------------------------------
    //
    // Held here rather than in the view model, unlike the selection: scrubbing is pure
    // presentation over paths that are already loaded, needs no query to key off it, and losing
    // your place in a playback on rotation is what a video player does too.

    /** The span the slider covers: the real extent of the loaded fixes, not the nominal range.
     * A "last 7 days" window whose data starts on Thursday should not open with three empty
     * days of scrubbing before anything moves. */
    val timeline = remember(paths) {
        val times = paths.values.flatten().map { it.capturedAt }
        if (times.isEmpty()) null else times.min()..times.max()
    }

    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    /** Where the playhead is, or null for "the end" — which is the live map. Null rather than a
     * timestamp so that a new fix arriving while parked at the end keeps it at the end instead
     * of leaving it stranded a second in the past. */
    var playhead by remember(timeline?.first) { mutableStateOf<Long?>(null) }

    // Leaving history mode ends playback rather than pausing it invisibly, so coming back does
    // not resume mid-scrub with no sign of why the map is showing an old position.
    LaunchedEffect(historyVisible) {
        if (!historyVisible) {
            playing = false
            playhead = null
        }
    }


    /**
     * What the pins show. Live, that is each person's latest fix; scrubbed, it is where each
     * person was at the playhead — their last fix at or before it, and nothing at all for
     * someone who had not yet shared a position by then.
     */
    val shownLocations = remember(locations, paths, playhead) {
        val at = playhead ?: return@remember locations
        paths.values.mapNotNull { fixes -> fixes.lastOrNull { it.capturedAt <= at } }
    }

    val selected = selectedUserId?.let(membersById::get)
    val selectedFix = shownLocations.firstOrNull { it.userId == selectedUserId }

    // The clock that moves the playhead. One step every FRAME_MS, sized so a whole timeline
    // takes PLAYBACK_SECONDS to cross at 1x — a fixed wall-clock duration rather than a fixed
    // rate, because an hour of history and a week of it both want to be watchable in about the
    // same time.
    LaunchedEffect(playing, speed, timeline) {
        val span = timeline ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        val total = (span.last - span.first).coerceAtLeast(1L)
        val step = (total.toDouble() / (PLAYBACK_SECONDS * 1000.0 / FRAME_MS) * speed).toLong().coerceAtLeast(1L)
        // Restarting from the beginning when play is pressed at the end is what every player
        // does, and the alternative is a play button that visibly does nothing.
        var at = playhead?.takeIf { it < span.last } ?: span.first
        while (playing) {
            playhead = at
            if (at >= span.last) {
                // Stop at the end and hand the map back to the present, rather than looping —
                // this is someone's day being replayed, not an animation.
                playing = false
                playhead = null
                break
            }
            kotlinx.coroutines.delay(FRAME_MS)
            at = (at + step).coerceAtMost(span.last)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // As on the chat screen: a line to glance at rather than a pill to aim
                        // at, still carrying the tap that reconnects.
                        ConnectionLine(connection, onReconnect = onReconnect)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(BackArrow, contentDescription = "Back")
                    }
                },
                actions = {
                    // History is the mode this screen is in, not a thing floating over the map,
                    // and the bar is where a mode belongs — it is also the one control here that
                    // changes what every other one means. Tinted when on, so the bar itself says
                    // which map you are looking at.
                    IconButton(onClick = { onToggleHistory(!historyVisible) }) {
                        Icon(
                            HistoryIcon,
                            contentDescription = if (historyVisible) "Hide history" else "Show history",
                            tint = if (historyVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    // Open to everyone now that "Share my location" lives behind it. A non-admin
                    // finds their own switch there and nothing else; the group's intervals stay
                    // the admin's, gated inside the screen rather than at this door.
                    IconButton(onClick = onOpenSettings) {
                        Icon(GearIcon, contentDescription = "Location settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            if (!group.locationEnabled) {
                LocationOffNotice(isAdmin = group.isAdmin, onOpenSettings = onOpenSettings)
                return@Box
            }

            MapCanvas(
                layer = layer,
                darkTheme = dark,
                onMapReady = { ready, loadedStyle ->
                    map = ready
                    style = loadedStyle
                },
                onFeatureTapped = onSelectUser,
            )

            // --- Overlays ---------------------------------------------------------------------

            // The range chip sits at the top of the map, not with the buttons at the bottom: it
            // says what you are *looking at*, and a caption belongs above the picture rather than
            // beside the controls.
            if (historyVisible) {
                RangeChip(
                    range = range,
                    onRangeChange = onRangeChange,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = MapStyle.attribution(layer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(horizontal = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )

                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LayerButton(current = layer, onPick = onLayerChange)
                    FloatingActionButton(
                        onClick = {
                            // Frames everyone rather than following one person — the group's
                            // equivalent of "zoom to fit", which is the other thing anyone
                            // opening a shared map wants.
                            map?.let { frameAll(it, shownLocations, animate = true) }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(PinIcon, contentDescription = "Show everyone")
                    }
                    FloatingActionButton(onClick = {
                        val here = map?.locationComponent?.takeIf { it.isLocationComponentActivated }
                            ?.lastKnownLocation
                        if (here != null) {
                            map?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(here.latitude, here.longitude),
                                    CLOSE_ZOOM,
                                ),
                            )
                        }
                    }) {
                        Icon(MyLocationIcon, contentDescription = "My location")
                    }
                }

                if (historyVisible) {
                    PlaybackBar(
                        timeline = timeline,
                        playhead = playhead,
                        playing = playing,
                        speed = speed,
                        onScrub = { at ->
                            // Scrubbing pauses, the way dragging a video's scrubber does.
                            playing = false
                            playhead = at
                        },
                        onTogglePlay = { playing = !playing },
                        onCycleSpeed = { speed = if (speed >= 8) 1 else speed * 2 },
                        onLive = {
                            playing = false
                            playhead = null
                        },
                    )
                }

                PeopleBar(
                    group = group,
                    members = members,
                    locations = shownLocations,
                    selfUserId = selfUserId,
                    selectedUserId = selectedUserId,
                    selectedMember = selected,
                    selectedFix = selectedFix,
                    avatarFor = avatarFor,
                    onSelectUser = onSelectUser,
                    onOpenSettings = onOpenSettings,
                    onDeleteHistory = onDeleteHistory,
                )
            }
        }
    }

    // --- Keeping the map in step with the data ----------------------------------------------

    val avatars = rememberUpdatedState(avatarFor)

    // Pins. Keyed on the style too, because a theme change reloads the style from scratch and
    // takes every source and layer with it — without that this would silently stop updating the
    // moment someone switched to dark mode.
    LaunchedEffect(style, shownLocations, members, selectedUserId, playhead) {
        val loaded = style ?: return@LaunchedEffect
        // Read once here rather than kept as a value in the composable: as a LaunchedEffect key,
        // "now" changes on every recomposition, and this effect rebuilds an avatar bitmap per
        // member each time it runs.
        val asOf = playhead ?: System.currentTimeMillis()
        val features = shownLocations.mapNotNull { fix ->
            val member = membersById[fix.userId] ?: return@mapNotNull null
            if (member.isRemoved) return@mapNotNull null
            val imageId = "avatar-${member.userId}"
            loaded.addImage(
                imageId,
                avatarPin(
                    bytes = avatars.value(member),
                    name = member.displayName,
                    ringColor = if (fix.userId == selectedUserId) accent.toArgb() else Color.White.toArgb(),
                    fillColor = accent.toArgb(),
                    textColor = onAccent.toArgb(),
                ),
            )
            Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)).apply {
                addStringProperty(PROP_USER, member.userId)
                addStringProperty(PROP_ICON, imageId)
                addStringProperty(PROP_LABEL, member.displayName.ifBlank { "Someone" })
                // Stale positions are drawn faded rather than hidden: "where they were an hour
                // ago" is still the answer to the question, and removing the pin would suggest
                // the far stronger claim that we do not know at all.
                addNumberProperty(
                    PROP_OPACITY,
                    // Measured against the playhead while scrubbing, not the wall clock: every
                    // fix in a replay of last Tuesday is hours old, and fading all of them would
                    // say "we have lost them" about a map that is working perfectly.
                    if (asOf - fix.capturedAt > STALE_AFTER_MS) 0.45 else 1.0,
                )
            }
        }
        loaded.getSourceAs<GeoJsonSource>(SOURCE_PEOPLE)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Every member's path, as one FeatureCollection of coloured LineStrings.
    //
    // One source and one layer for the whole group rather than a layer per person: a layer per
    // person means adding and removing style layers as members come and go, which MapLibre does
    // on the GL thread and which shows as a stutter. Colour rides on each feature instead, and
    // the layer reads it with an expression.
    LaunchedEffect(style, paths, selectedUserId, playhead) {
        val loaded = style ?: return@LaunchedEffect
        val lines = paths.mapNotNull { (userId, allFixes) ->
            // Clipped to the playhead so the line draws itself as the replay runs, instead of
            // the whole route sitting there from the first frame with a marker sliding along it.
            val fixes = playhead?.let { at -> allFixes.filter { it.capturedAt <= at } } ?: allFixes
            // A single point is a position, not a path. Drawing it as a zero-length line gives
            // MapLibre a degenerate geometry and the user a dot they cannot tell from a pin.
            if (fixes.size < 2) return@mapNotNull null
            Feature.fromGeometry(
                LineString.fromLngLats(fixes.map { Point.fromLngLat(it.lon, it.lat) }),
            ).apply {
                addStringProperty(PROP_USER, userId)
                addStringProperty(PROP_COLOR, pathColor(userId))
                // With everyone drawn at once, selecting someone has to mean something visually.
                // Theirs stays solid and the others drop back far enough to read as context,
                // but not so far that you lose where they went.
                addNumberProperty(
                    PROP_OPACITY,
                    when (selectedUserId) {
                        null -> 0.75
                        userId -> 0.95
                        else -> 0.25
                    },
                )
                addNumberProperty(PROP_WIDTH, if (userId == selectedUserId) 5.0 else 3.0)
            }
        }
        loaded.getSourceAs<GeoJsonSource>(SOURCE_TRAIL)
            ?.setGeoJson(FeatureCollection.fromFeatures(lines))
    }

    // First frame only — see `framed`.
    LaunchedEffect(map, locations) {
        val ready = map ?: return@LaunchedEffect
        if (framed || locations.isEmpty()) return@LaunchedEffect
        frameAll(ready, locations, animate = false)
        framed = true
    }

    // Tapping a person in the strip flies to them, which is the half of selection the map has to
    // do; tapping their pin only selects, since the camera is already looking at it.
    LaunchedEffect(selectedUserId) {
        val ready = map ?: return@LaunchedEffect
        val fix = shownLocations.firstOrNull { it.userId == selectedUserId } ?: return@LaunchedEffect
        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), CLOSE_ZOOM))
    }
}

/**
 * The MapView itself, bridged into Compose.
 *
 * MapView is a lifecycle-aware Android view with nine callbacks it genuinely needs — skipping
 * onStop and onDestroy leaks a GL surface and a native map object per visit to this screen — so
 * the DisposableEffect forwards them all rather than the two that look sufficient.
 */
@Composable
private fun MapCanvas(
    layer: MapLayer,
    darkTheme: Boolean,
    onMapReady: (MapLibreMap, Style) -> Unit,
    onFeatureTapped: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val tapped = rememberUpdatedState(onFeatureTapped)
    val ready = rememberUpdatedState(onMapReady)

    // Unit, not lifecycleOwner: the MapView is remembered without keys, so re-keying this would
    // let onDestroy run and then hand the same dead view back to AndroidView.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.apply {
                    isRotateGesturesEnabled = true
                    isTiltGesturesEnabled = true
                    isCompassEnabled = true
                    // MapLibre's own attribution and logo views are drawn by the Android view
                    // underneath Compose and cannot be positioned against this screen's own
                    // overlays. Turned off here and redrawn as Compose text above, which keeps
                    // the OSM attribution requirement met rather than dropped.
                    isAttributionEnabled = false
                    isLogoEnabled = false
                }
                installStyle(mapView, map, layer, darkTheme) { style -> ready.value(map, style) }

                map.addOnMapClickListener { point ->
                    val screenPoint = map.projection.toScreenLocation(point)
                    val hits = map.queryRenderedFeatures(
                        PointF(screenPoint.x, screenPoint.y),
                        LAYER_PEOPLE,
                    )
                    // A tap on empty map clears the selection, which is how every map app
                    // dismisses a detail card and so the thing people will try first.
                    tapped.value(hits.firstOrNull()?.getStringProperty(PROP_USER))
                    true
                }
            }
            mapView
        },
        modifier = Modifier.fillMaxSize(),
    )

    // A theme change — or a change of layer — has to reload the style, and a reloaded style comes back
    // empty — every source, layer and image belongs to the style object, not the map. So the
    // whole installation runs again and the caller is handed the new Style, which re-keys the
    // effects that repopulate it.
    LaunchedEffect(layer, darkTheme) {
        val map = mapView.awaitMap() ?: return@LaunchedEffect
        if (map.style == null) return@LaunchedEffect
        installStyle(mapView, map, layer, darkTheme) { style -> ready.value(map, style) }
    }
}

/** Loads the tile style and puts this app's own sources, layers and puck back on top of it. */
private fun installStyle(
    mapView: MapView,
    map: MapLibreMap,
    layer: MapLayer,
    darkTheme: Boolean,
    onLoaded: (Style) -> Unit,
) {
    map.setStyle(Style.Builder().fromJson(MapStyle.styleJson(layer, darkTheme))) { style ->
        style.addSource(GeoJsonSource(SOURCE_TRAIL))
        style.addSource(GeoJsonSource(SOURCE_PEOPLE))
        // The trail goes in first so pins draw over it rather than under.
        style.addLayer(
            LineLayer(LAYER_TRAIL, SOURCE_TRAIL).withProperties(
                PropertyFactory.lineColor(Expression.get(PROP_COLOR)),
                PropertyFactory.lineWidth(Expression.get(PROP_WIDTH)),
                PropertyFactory.lineOpacity(Expression.get(PROP_OPACITY)),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_PEOPLE, SOURCE_PEOPLE).withProperties(
                // Expression.get, not the "{icon}" token form: token substitution is a
                // legacy style-JSON convenience that MapLibre does not apply to properties set
                // programmatically, so the layer was asking for one image literally named
                // "{icon}", finding none, and drawing no pin at all.
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                // The bitmap is a circle with a tail at the bottom, so the tail — not the middle
                // of the face — is what should sit on the coordinate.
                PropertyFactory.iconAnchor("bottom"),
                // Family members stand next to each other constantly; letting
                // MapLibre hide "colliding" pins would drop exactly the people the
                // map is being opened to find.
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(Expression.get(PROP_OPACITY)),
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                // Named explicitly because a raster style declares no default font stack.
                PropertyFactory.textFont(MapStyle.PIN_FONT),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
                // Fixed colours rather than theme ones: the label sits on map tiles, not on the
                // app's surface, and a white halo round dark text is what stays readable over
                // both the daylight tiles and their dimmed night version.
                PropertyFactory.textColor(android.graphics.Color.parseColor("#FF16181C")),
                PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                PropertyFactory.textHaloWidth(1.2f),
            ),
        )
        activateOwnPuck(mapView, map, style)
        onLoaded(style)
    }
}

/** The map object, once MapLibre has built it. getMapAsync fires immediately when it already
 * exists, so this is only ever a real suspension on the very first call. */
private suspend fun MapView.awaitMap(): MapLibreMap? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        getMapAsync { map -> if (continuation.isActive) continuation.resumeWith(Result.success(map)) }
    }

/**
 * The blue "you are here" puck.
 *
 * Deliberately separate from this app's own location engine and fed by MapLibre's default
 * provider: the puck is a live cursor that should follow you at whatever rate the screen can
 * show, while the engine publishes on an interval an admin chose for battery reasons. Driving
 * the puck from the published positions would make your own dot lag minutes behind you on your
 * own screen, which reads as a bug every time.
 *
 * Checked up front rather than only wrapped in try/catch: activateLocationComponent() itself
 * validates the permission synchronously, but MapLibre's default location engine then polls
 * LocationManager.getLastKnownLocation() again later from its own Handler callback, off this call
 * stack entirely — a SecurityException thrown there crashes the process no matter what this
 * function catches. The one guard that actually works is never starting the engine at all
 * without the permission.
 */
private fun activateOwnPuck(mapView: MapView, map: MapLibreMap, style: Style) {
    if (!hasLocationPermission(mapView.context)) {
        // The puck appears the next time this screen is opened after sharing has been switched
        // on; the map is still perfectly usable without it.
        return
    }
    try {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions
                    .builder(mapView.context, style)
                    .locationEngine(LocationEngineDefault.getDefaultLocationEngine(mapView.context))
                    .build(),
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.COMPASS
        }
    } catch (_: SecurityException) {
        // Permission revoked between the check above and here, e.g. from Settings mid-session.
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** Frames every known position at once, with room around the edges so pins are not clipped by
 * the top bar or the people strip. Falls back to a plain centre when there is only one. */
private fun frameAll(map: MapLibreMap, locations: List<LocationEntity>, animate: Boolean) {
    if (locations.isEmpty()) return
    val update = if (locations.size == 1) {
        val only = locations.first()
        CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), CLOSE_ZOOM)
    } else {
        val bounds = LatLngBounds.Builder()
            .includes(locations.map { LatLng(it.lat, it.lon) })
            .build()
        CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX)
    }
    if (animate) map.animateCamera(update) else map.moveCamera(update)
}

/** Shown instead of the map when the admin has not turned location on for this group — a map
 * with no possibility of anything on it is worse than an explanation. */
@Composable
private fun LocationOffNotice(isAdmin: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            PinIcon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Location sharing is off for this group", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isAdmin) {
                "You are the admin. Turn it on and choose how often each phone takes and sends a " +
                    "position — everyone still decides for themselves whether to share."
            } else {
                "Only the group admin can turn it on."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isAdmin) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = onOpenSettings) { Text("Location settings") }
        }
    }
}

/**
 * The strip along the bottom: everyone who has a position, plus this phone's own share switch.
 *
 * The switch lives here rather than buried in settings because it is the one control on this
 * screen whose state a person should never have to go looking for. A map that might or might not
 * be showing you to your family is not a thing to be uncertain about.
 */
@Composable
private fun PeopleBar(
    group: GroupEntity,
    members: List<MemberEntity>,
    locations: List<LocationEntity>,
    selfUserId: String,
    selectedUserId: String?,
    selectedMember: MemberEntity?,
    selectedFix: LocationEntity?,
    avatarFor: (MemberEntity) -> ByteArray?,
    onSelectUser: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteHistory: (String) -> Unit,
) {
    var confirmingDelete by remember(selectedMember?.userId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        if (selectedMember != null && selectedFix != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(avatarFor(selectedMember), selectedMember.displayName, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            selectedMember.displayName.ifBlank { "Someone" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(relativeTime(selectedFix.capturedAt))
                                if (selectedFix.accuracy > 0f) {
                                    append(" · ±${selectedFix.accuracy.roundToInt()} m")
                                }
                                if (selectedFix.speed > 1f) {
                                    append(" · ${(selectedFix.speed * 3.6f).roundToInt()} km/h")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(TrashIcon, contentDescription = "Delete their history")
                    }
                    IconButton(onClick = { onSelectUser(null) }) {
                        Icon(CloseIcon, contentDescription = "Close")
                    }
                }
            }
        }

        if (confirmingDelete && selectedMember != null) {
            val who = selectedMember.displayName.ifBlank { "this person" }
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete $who's history?") },
                text = {
                    Text(
                        // No count, deliberately. The obvious number to quote is how many points
                        // are on screen, and that is the wrong one: it is whatever the current
                        // range holds, and nothing at all while history is switched off, while
                        // the delete takes everything ever stored.
                        //
                        // The rest is said plainly because the obvious reading is also wrong.
                        // Everyone in the group holds their own copy of everyone's history —
                        // that is what lets the map work without a server — so this erases it
                        // here and nowhere else, and does not stop them sharing from now on.
                        "Erases every position stored for $who on this phone, not just the ones " +
                            "in view. Everyone else in the group keeps their own copy, and $who " +
                            "carries on sharing unless they turn it off.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        onDeleteHistory(selectedMember.userId)
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                },
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            // The switch itself now lives on the group's Location screen, next to the rules it
            // obeys. What stays here is the answer to "am I on this map right now" — the one
            // thing nobody should have to open a second screen to find out — and a way through
            // to the switch, so moving it did not also hide it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group.shareLocation) "Sharing my location" else "Not sharing my location",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (group.shareLocation) {
                            "Every ${humanInterval(group.locPushSec)}, when you have moved " +
                                "${group.locMinDistanceM} m"
                        } else {
                            "You can see everyone else; they cannot see you."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val withPositions = members.filter { member ->
                !member.isRemoved && locations.any { it.userId == member.userId }
            }
            if (withPositions.isEmpty()) {
                Text(
                    text = "Nobody is sharing a position yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(withPositions, key = { it.userId }) { member ->
                        val fix = locations.first { it.userId == member.userId }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onSelectUser(member.userId.takeIf { it != selectedUserId })
                                }
                                .padding(4.dp)
                                .width(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Avatar(avatarFor(member), member.displayName, size = 40.dp)
                                if (member.userId == selectedUserId) {
                                    Box(
                                        Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                            ),
                                    )
                                }
                            }
                            // The same colour their path is drawn in. Without this the map is a
                            // tangle of coloured lines with no key: you can see that someone went
                            // that way, but not who.
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(android.graphics.Color.parseColor(pathColor(member.userId)))),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (member.userId == selfUserId) {
                                    "You"
                                } else {
                                    member.displayName.ifBlank { "?" }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = relativeTime(fix.capturedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The transport for replaying the loaded history: play/pause, a scrubber over the whole span,
 * and the time the playhead is sitting on.
 *
 * The slider is over *time*, not over fix count. Positions are not evenly spaced — a phone
 * parked at home all afternoon publishes a handful, a car journey publishes hundreds — so a
 * slider indexed by point would crawl through the drive and skip the afternoon, which is exactly
 * backwards from how the day felt.
 */
@Composable
private fun PlaybackBar(
    timeline: LongRange?,
    playhead: Long?,
    playing: Boolean,
    speed: Int,
    onScrub: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onLive: () -> Unit,
) {
    // Two fixes are the least that can be a path; below that there is nothing to play, and a
    // dead transport bar is worse than none.
    if (timeline == null || timeline.last <= timeline.first) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (playing) PauseIcon else PlayIcon,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(Modifier.weight(1f)) {
                // Offsets from the start of the span, never absolute epoch milliseconds: a
                // Float carries 24 bits of mantissa, so an epoch timestamp rounds to the nearest
                // ~2 minutes. On a "last hour" timeline that is a slider with about thirty
                // usable positions. A span, even a week of one, fits with milliseconds to spare.
                Slider(
                    value = ((playhead ?: timeline.last) - timeline.first).toFloat(),
                    onValueChange = { onScrub(timeline.first + it.toLong()) },
                    valueRange = 0f..(timeline.last - timeline.first).toFloat(),
                )
                Text(
                    text = playhead?.let(::formatMoment) ?: "Now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))
            // The speed control doubles as the way back to the present, because those are the
            // two things anyone wants from this row once the replay has ended somewhere in the
            // middle of last Tuesday.
            if (playhead != null) {
                TextButton(onClick = onLive) { Text("Now") }
            }
            TextButton(onClick = onCycleSpeed) { Text("${speed}x") }
        }
    }
}

/** "Tue 14:05" — the day is needed as soon as a range covers more than one, and the seconds
 * never are. */
private fun formatMoment(at: Long): String =
    android.text.format.DateFormat.format("EEE HH:mm", at).toString()

/**
 * What span of history the map is showing, and the way to change it.
 *
 * The presets are one tap; anything else opens a date-range picker. Presets exist because the
 * question is nearly always relative ("today", "last week") and making someone pick two dates to
 * ask it is a chore. The picker exists because "the Saturday we drove to Bandung" is not a
 * preset and never will be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeChip(
    range: HistoryRange,
    onRangeChange: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    RoundedCornerShape(20.dp),
                )
                .clickable { menuOpen = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HistoryIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(range.label, style = MaterialTheme.typography.labelLarge)
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            HistoryRange.PRESETS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { if (option == range) AccentDot() },
                    onClick = {
                        menuOpen = false
                        onRangeChange(option)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Pick dates…") },
                leadingIcon = { if (range is HistoryRange.Custom) AccentDot() },
                onClick = {
                    menuOpen = false
                    pickerOpen = true
                },
            )
        }
    }

    if (pickerOpen) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                val start = state.selectedStartDateMillis
                TextButton(
                    // A range needs a start; an end that is still empty means the one day they
                    // have tapped so far, which is a reasonable thing to ask for and no reason
                    // to keep the button dead.
                    enabled = start != null,
                    onClick = {
                        pickerOpen = false
                        if (start != null) {
                            onRangeChange(
                                HistoryRange.Custom(
                                    firstDay = start,
                                    lastDay = state.selectedEndDateMillis ?: start,
                                ),
                            )
                        }
                    },
                ) { Text("Show") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(state = state, title = null)
        }
    }
}

/**
 * A stable colour per person, so a path means the same one on every phone in the group and on
 * every reopening of the screen.
 *
 * Derived from the user id rather than assigned by position in a list: the list is ordered by
 * whatever the database returns, so an index would repaint everyone's history the moment a
 * member joined or left. Hue is spread by the golden angle, which keeps neighbouring hashes far
 * apart instead of clustering the way a plain modulo does; saturation and lightness are fixed
 * high enough to read over both daylight tiles and satellite imagery.
 */
private fun pathColor(userId: String): String {
    val hue = ((userId.hashCode().toLong() and 0xFFFFFFFFL) * 137.508 % 360.0).toFloat()
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.98f))
    // Locale.ROOT, not the default: String.format with an Arabic or Persian locale renders %X
    // in that locale's own digits, and "#٣٤٥٦٧٨" is not a colour MapLibre can parse — the paths
    // would simply not draw, on those phones only.
    return String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and argb)
}

/**
 * The layer switcher: one button, three choices, no submenu.
 *
 * A DropdownMenu rather than a bottom sheet because the choice is momentary — people flip to
 * satellite to see whether that is a school or a shopping centre, then flip back — and a sheet
 * makes a two-tap decision feel like a settings trip. The same preference is offered on the
 * Settings screen for whoever goes looking for it there instead.
 */
@Composable
private fun LayerButton(current: MapLayer, onPick: (MapLayer) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(
            onClick = { open = true },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(LayersIcon, contentDescription = "Map layer")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapLayer.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            Text(
                                option.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { if (option == current) AccentDot() },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/**
 * A map pin from a member's avatar: the round photo, a white ring so it reads against any tile
 * colour, and a point at the bottom so it marks a spot rather than hovering over one.
 *
 * Drawn rather than composed because a SymbolLayer takes a Bitmap — MapLibre renders icons on
 * the GL surface, where a Compose tree cannot go.
 */
private fun avatarPin(
    bytes: ByteArray?,
    name: String,
    ringColor: Int,
    fillColor: Int,
    textColor: Int,
): Bitmap {
    val size = PIN_SIZE_PX
    val tail = PIN_TAIL_PX
    val bitmap = Bitmap.createBitmap(size, size + tail, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The tail, drawn first and in the ring colour so it reads as part of the same shape.
    paint.color = ringColor
    canvas.drawPath(
        android.graphics.Path().apply {
            moveTo(radius - tail * 0.55f, size * 0.86f)
            lineTo(radius, (size + tail).toFloat())
            lineTo(radius + tail * 0.55f, size * 0.86f)
            close()
        },
        paint,
    )
    canvas.drawCircle(radius, radius, radius, paint)

    val inner = radius - RING_PX
    val photo = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    if (photo != null) {
        // Square-cropped first: a shader scaled from a non-square source would stretch a face.
        val side = minOf(photo.width, photo.height)
        val square = Bitmap.createBitmap(
            photo,
            (photo.width - side) / 2,
            (photo.height - side) / 2,
            side,
            side,
        )
        val scaled = square.scale((inner * 2).toInt().coerceAtLeast(1))
        paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.translate(radius - inner, radius - inner)
        canvas.drawCircle(inner, inner, inner, paint)
        canvas.restore()
        paint.shader = null
    } else {
        paint.color = fillColor
        canvas.drawCircle(radius, radius, inner, paint)
        paint.color = textColor
        paint.textSize = inner
        paint.textAlign = Paint.Align.CENTER
        val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
        val metrics = paint.fontMetrics
        canvas.drawText(initial, radius, radius - (metrics.ascent + metrics.descent) / 2f, paint)
    }
    return bitmap
}

private fun Bitmap.scale(side: Int): Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    .also { out ->
        Canvas(out).drawBitmap(
            this,
            null,
            RectF(0f, 0f, side.toFloat(), side.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

/** How long ago, in the coarse words people actually use. Seconds are noise on a map whose
 * fastest interval is measured in tens of them. */
private fun relativeTime(at: Long): String {
    val seconds = (System.currentTimeMillis() - at) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

/** "30 seconds" / "2 minutes" / "1 hour" — the same phrasing the settings sliders use, so the
 * strip and the settings screen never seem to describe two different things. */
internal fun humanInterval(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds < 3_600 -> {
        val minutes = seconds / 60
        if (minutes == 1) "minute" else "$minutes minutes"
    }
    else -> {
        val hours = seconds / 3_600
        if (hours == 1) "hour" else "$hours hours"
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

// Drawn from path data like the rest of the app's glyphs, rather than pulling in
// material-icons-extended for four of them — see Components.simpleIcon.
private val GearIcon by lazy {
    simpleIcon(
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
            "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
            "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
            "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
            "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
            "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
            "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
            "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
            "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
            "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6" +
            "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    )
}

private val PinIcon by lazy {
    simpleIcon(
        "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7z" +
            "M12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z",
    )
}

private val MyLocationIcon by lazy {
    simpleIcon(
        "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4z" +
            "M20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06" +
            "c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06z" +
            "M12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
}

/** Material's "layers" glyph: three stacked sheets seen edge-on. */
private val LayersIcon by lazy {
    simpleIcon(
        "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74z" +
            "M12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
    )
}

/** A clock with an arrow curling back into it — Material's "history". */
private val HistoryIcon by lazy {
    simpleIcon(
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7" +
            " -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21" +
            "c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
            "M12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8z",
    )
}

private val TrashIcon by lazy {
    simpleIcon(
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12z" +
            "M19,4h-3.5l-1,-1h-5l-1,1L5,4v2h14L19,4z",
    )
}

private val PlayIcon by lazy { simpleIcon("M8,5v14l11,-7z") }

private val PauseIcon by lazy { simpleIcon("M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }

private val CloseIcon by lazy {
    simpleIcon(
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59" +
            " 13.41,12z",
    )
}

private const val SOURCE_PEOPLE = "fg-people"
private const val SOURCE_TRAIL = "fg-trail"
private const val LAYER_PEOPLE = "fg-people-layer"
private const val LAYER_TRAIL = "fg-trail-layer"
private const val PROP_USER = "user"
private const val PROP_ICON = "icon"
private const val PROP_LABEL = "label"
private const val PROP_OPACITY = "opacity"
private const val PROP_COLOR = "color"

/** How long a whole timeline takes to replay at 1x, and how often the playhead moves while it
 * does. Twelve seconds is long enough to follow and short enough to watch twice; 60ms is a
 * step-per-frame that costs one GeoJSON rebuild each and stays smooth. */
private const val PLAYBACK_SECONDS = 12
private const val FRAME_MS = 60L
private const val PROP_WIDTH = "width"

private const val CLOSE_ZOOM = 15.5
private const val BOUNDS_PADDING_PX = 160
private const val PIN_SIZE_PX = 96
private const val PIN_TAIL_PX = 22
private const val RING_PX = 5f

/** Past this, a position is shown faded. Twenty minutes is long enough not to flicker at the
 * default five-minute interval, short enough that a phone which died is not still presented as
 * current. */
private const val STALE_AFTER_MS = 20 * 60 * 1000L
