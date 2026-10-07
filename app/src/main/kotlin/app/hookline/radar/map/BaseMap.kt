package app.hookline.radar.map

import android.content.Context
import app.hookline.radar.core.data.ImagerySource
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.core.radar.Viewport
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** User-adjustable map layer strengths, 0..1. Never forced on anyone by an update. */
data class MapIntensity(val roads: Float = 0.7f, val labels: Float = 0.8f, val borders: Float = 0.6f)

/**
 * The vector base map, drawn ABOVE the radar on a transparent surface so
 * roads, borders and place names stay readable over heavy echoes (the way
 * pros expect). Land fills are hidden; water is a faint tint and outline.
 *
 * We load OpenFreeMap's dark style and restyle it at runtime by data layer
 * (OpenMapTiles schema) rather than by layer id, so upstream style changes
 * don't break us, and the chrome stays neutral: the radar is the only colour.
 */
class BaseMap(context: Context, interactive: Boolean = true) {

    val mapView: MapView
    var map: MapLibreMap? = null
        private set

    /** Fires on every camera frame (UI thread). */
    var onCameraMove: (() -> Unit)? = null
    /** Fires when the camera settles: centre, zoom and visible bounds. */
    var onCameraIdle: ((LatLng, Double, LatLngBounds?) -> Unit)? = null
    var onLongPress: ((LatLng) -> Unit)? = null
    /** Single tap on the map; return true to consume it. */
    var onTap: ((LatLng) -> Boolean)? = null
    /** Follow-me ended because the user moved the map. */
    var onFollowDismissed: (() -> Unit)? = null
    /** Fires once the map style has loaded (or immediately if it already has). */
    var onStyleLoaded: (() -> Unit)? = null
        set(v) { field = v; if (styleLoaded) v?.invoke() }
    var styleLoaded = false
        private set

    private val roadLayers = mutableListOf<String>()
    private val labelLayers = mutableListOf<String>()
    private val borderLayers = mutableListOf<String>()
    private var intensity = MapIntensity()
    private var ink = INK
    private var ink2 = INK_2
    private var voidColor = MAP_VOID
    private var pendingCamera: Pair<LatLng, Double>? = null
    private var imagery: List<Pair<ImagerySource, Float>> = emptyList()
    private val shownImagery = mutableListOf<String>()

    init {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .translucentTextureSurface(true)
            .attributionEnabled(false) // shown in our own footer, always visible
            .logoEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false) // north-up keeps radar geometry honest
            .scrollGesturesEnabled(interactive)
            .zoomGesturesEnabled(interactive)
            .doubleTapGesturesEnabled(interactive)
            .tiltGesturesEnabled(false)
            .minZoomPreference(2.0)
            .maxZoomPreference(14.0)
        mapView = MapView(context, options)
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                restyle(style)
                styleLoaded = true
                onStyleLoaded?.invoke()
            }
            m.addOnCameraMoveListener { onCameraMove?.invoke() }
            m.addOnCameraIdleListener {
                onCameraIdle?.invoke(m.cameraPosition.target ?: LatLng(0.0, 0.0), m.cameraPosition.zoom, visibleBounds())
                onCameraMove?.invoke()
            }
            m.addOnMapLongClickListener { p -> onLongPress?.invoke(p); true }
            m.addOnMapClickListener { p -> onTap?.invoke(p) ?: false }
            pendingCamera?.let { (t, z) -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(t, z)) }
            pendingCamera = null
            onCameraMove?.invoke()
        }
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double = 7.0) {
        val m = map
        if (m == null) pendingCamera = LatLng(lat, lon) to zoom
        else m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
    }

    fun cameraTarget(): Pair<LatLng, Double>? = map?.cameraPosition?.let { cp -> cp.target?.let { it to cp.zoom } }

    fun visibleBounds(): LatLngBounds? = map?.projection?.visibleRegion?.latLngBounds

    val zoom: Double get() = map?.cameraPosition?.zoom ?: 7.0

    /**
     * Radar viewport matching the map camera: centre and scale in Web Mercator
     * metres relative to the session's render [origin], measured from the
     * map's own projection around the camera target so the two layers can't
     * drift apart anywhere on Earth.
     */
    fun radarViewport(origin: MercatorProjector, widthPx: Int, heightPx: Int): Viewport? {
        val m = map ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val proj = m.projection
        val target = m.cameraPosition.target ?: return null
        val s0 = proj.toScreenLocation(target)
        val dLon = 0.5
        val s1 = proj.toScreenLocation(LatLng(target.latitude, target.longitude + dLon))
        val px = (s1.x - s0.x).toDouble()
        if (px <= 0.0) return null
        val mpp = Math.toRadians(dLon) * MercatorProjector.R / px
        val (tx, ty) = origin.fromLatLon(target.latitude, target.longitude)
        return Viewport(
            widthPx, heightPx,
            centerEastM = tx - (s0.x - widthPx / 2.0) * mpp,
            centerNorthM = ty + (s0.y - heightPx / 2.0) * mpp,
            metersPerPixel = mpp,
        )
    }

    /** Night vision: roads, borders and names in dim red. */
    fun setRedNight(on: Boolean) {
        val (a, b, c) = if (on) Triple("#B8352E", "#6E211C", "#0A0505") else Triple(INK, INK_2, MAP_VOID)
        if (a == ink) return
        ink = a; ink2 = b; voidColor = c
        map?.style?.let { st -> if (st.isFullyLoaded) runCatching { restyle(st, fresh = false) } }
    }

    /** Shows the device position (a dot with heading). Needs location permission. */
    @android.annotation.SuppressLint("MissingPermission")
    fun showLocation(context: Context): Boolean {
        val m = map ?: return false
        val st = m.style ?: return false
        if (!DeviceLocation.hasPermission(context)) return false
        return runCatching {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(
                    org.maplibre.android.location.LocationComponentActivationOptions.builder(context, st)
                        .useDefaultLocationEngine(true)
                        .build(),
                )
                lc.addOnCameraTrackingChangedListener(object : org.maplibre.android.location.OnCameraTrackingChangedListener {
                    override fun onCameraTrackingDismissed() { onFollowDismissed?.invoke() }
                    override fun onCameraTrackingChanged(currentMode: Int) = Unit
                })
            }
            lc.isLocationComponentEnabled = true
            lc.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
            true
        }.getOrDefault(false)
    }

    /** Keeps the camera on the device position (north stays up). */
    fun setFollow(on: Boolean) {
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) return
        runCatching {
            lc.cameraMode = if (on) org.maplibre.android.location.modes.CameraMode.TRACKING
            else org.maplibre.android.location.modes.CameraMode.NONE
        }
    }

    /** The base map (lines and names) as a bitmap, transparent elsewhere. */
    suspend fun snapshot(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val m = map
        if (m == null) cont.resumeWith(Result.success(null))
        else m.snapshot { bmp -> if (cont.isActive) cont.resumeWith(Result.success(bmp)) }
    }

    fun setIntensity(i: MapIntensity) {
        intensity = i
        map?.style?.let { applyIntensity(it) }
    }

    /**
     * Raster imagery (satellite, the US mosaic) with an opacity each, drawn
     * below roads and names. Sources are replaced when their URL changes.
     */
    fun setImagery(list: List<Pair<ImagerySource, Float>>) {
        if (list == imagery) return
        imagery = list
        map?.style?.let { if (it.isFullyLoaded) runCatching { applyImagery(it) } }
    }

    private fun applyImagery(style: Style) {
        for (id in shownImagery) {
            style.removeLayer("img-$id")
            style.removeSource("img-$id")
        }
        shownImagery.clear()
        // Directly above the (invisible) background, so lines and labels stay on top.
        val firstVisible = style.layers.firstOrNull { it !is BackgroundLayer }?.id
        for ((src, opacity) in imagery) {
            val key = src.id + "-" + src.url.hashCode().toUInt().toString(16)
            val tiles = TileSet("2.2.0", src.url).apply {
                minZoom = 0f
                maxZoom = src.maxZoom.toFloat()
                setBounds(src.west.toFloat(), src.south.toFloat(), src.east.toFloat(), src.north.toFloat())
            }
            style.addSource(RasterSource("img-$key", tiles, 256))
            val layer = org.maplibre.android.style.layers.RasterLayer("img-$key", "img-$key").withProperties(
                PropertyFactory.rasterOpacity(opacity),
                PropertyFactory.rasterFadeDuration(0f),
            )
            if (firstVisible != null) style.addLayerBelow(layer, firstVisible) else style.addLayer(layer)
            shownImagery += key
        }
    }

    private fun restyle(style: Style, fresh: Boolean = true) {
        roadLayers.clear(); labelLayers.clear(); borderLayers.clear()
        for (layer in style.layers) restyleLayer(layer)
        applyIntensity(style)
        if (fresh) {
            shownImagery.clear()
            runCatching { applyImagery(style) }
        }
    }

    private fun restyleLayer(layer: Layer) {
        // Our own imagery and the location dot are styled where they're added.
        if (layer.id.startsWith("img-") || layer.id.contains("location")) return
        val hide = PropertyFactory.visibility(Property.NONE)
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundOpacity(0f))
            is FillExtrusionLayer -> layer.setProperties(hide)
            is FillLayer -> if (layer.sourceLayer == "water") {
                layer.setProperties(PropertyFactory.fillColor(ink2), PropertyFactory.fillOpacity(0.10f))
            } else {
                layer.setProperties(hide) // land, landuse, buildings would hide the radar
            }
            is LineLayer -> when (layer.sourceLayer) {
                "transportation" -> { layer.setProperties(PropertyFactory.lineColor(ink)); roadLayers += layer.id }
                "boundary" -> { layer.setProperties(PropertyFactory.lineColor(ink)); borderLayers += layer.id }
                "water", "waterway" -> layer.setProperties(PropertyFactory.lineColor(ink2), PropertyFactory.lineOpacity(0.35f))
                else -> layer.setProperties(hide)
            }
            is SymbolLayer -> when (layer.sourceLayer) {
                "place", "transportation_name" -> {
                    layer.setProperties(
                        PropertyFactory.textColor(ink),
                        PropertyFactory.textHaloColor(voidColor),
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.iconOpacity(0f),
                    )
                    labelLayers += layer.id
                }
                else -> layer.setProperties(hide) // POIs, house numbers, peaks: clutter on a radar map
            }
            else -> layer.setProperties(hide) // raster, hillshade, heatmap, circles
        }
    }

    private fun applyIntensity(style: Style) {
        fun lines(ids: List<String>, v: Float, max: Float) = ids.forEach { id ->
            style.getLayer(id)?.setProperties(
                PropertyFactory.visibility(if (v <= 0.01f) Property.NONE else Property.VISIBLE),
                PropertyFactory.lineOpacity(v * max),
            )
        }
        lines(roadLayers, intensity.roads, 0.85f)
        lines(borderLayers, intensity.borders, 0.9f)
        labelLayers.forEach { id ->
            style.getLayer(id)?.setProperties(
                PropertyFactory.visibility(if (intensity.labels <= 0.01f) Property.NONE else Property.VISIBLE),
                PropertyFactory.textOpacity(intensity.labels),
            )
        }
    }

    // Lifecycle passthrough
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()

    companion object {
        /** OpenFreeMap: free, keyless OpenStreetMap vector tiles. Attribution is shown in the map footer. */
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        const val ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
        private const val INK = "#ECE8DF"
        private const val INK_2 = "#9AA5AE"
        private const val MAP_VOID = "#141B22"
    }
}
