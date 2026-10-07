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
class BaseMap(context: Context) {

    val mapView: MapView
    var map: MapLibreMap? = null
        private set

    /** Fires on every camera frame (UI thread). */
    var onCameraMove: (() -> Unit)? = null
    /** Fires when the camera settles: centre, zoom and visible bounds. */
    var onCameraIdle: ((LatLng, Double, LatLngBounds?) -> Unit)? = null
    var onLongPress: ((LatLng) -> Unit)? = null

    private val roadLayers = mutableListOf<String>()
    private val labelLayers = mutableListOf<String>()
    private val borderLayers = mutableListOf<String>()
    private var intensity = MapIntensity()
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
            .tiltGesturesEnabled(false)
            .minZoomPreference(2.0)
            .maxZoomPreference(14.0)
        mapView = MapView(context, options)
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style -> restyle(style) }
            m.addOnCameraMoveListener { onCameraMove?.invoke() }
            m.addOnCameraIdleListener {
                onCameraIdle?.invoke(m.cameraPosition.target ?: LatLng(0.0, 0.0), m.cameraPosition.zoom, visibleBounds())
                onCameraMove?.invoke()
            }
            m.addOnMapLongClickListener { p -> onLongPress?.invoke(p); true }
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

    private fun restyle(style: Style) {
        roadLayers.clear(); labelLayers.clear(); borderLayers.clear()
        for (layer in style.layers) restyleLayer(layer)
        applyIntensity(style)
        shownImagery.clear()
        runCatching { applyImagery(style) }
    }

    private fun restyleLayer(layer: Layer) {
        val hide = PropertyFactory.visibility(Property.NONE)
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundOpacity(0f))
            is FillExtrusionLayer -> layer.setProperties(hide)
            is FillLayer -> if (layer.sourceLayer == "water") {
                layer.setProperties(PropertyFactory.fillColor(INK_2), PropertyFactory.fillOpacity(0.10f))
            } else {
                layer.setProperties(hide) // land, landuse, buildings would hide the radar
            }
            is LineLayer -> when (layer.sourceLayer) {
                "transportation" -> { layer.setProperties(PropertyFactory.lineColor(INK)); roadLayers += layer.id }
                "boundary" -> { layer.setProperties(PropertyFactory.lineColor(INK)); borderLayers += layer.id }
                "water", "waterway" -> layer.setProperties(PropertyFactory.lineColor(INK_2), PropertyFactory.lineOpacity(0.35f))
                else -> layer.setProperties(hide)
            }
            is SymbolLayer -> when (layer.sourceLayer) {
                "place", "transportation_name" -> {
                    layer.setProperties(
                        PropertyFactory.textColor(INK),
                        PropertyFactory.textHaloColor(MAP_VOID),
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
