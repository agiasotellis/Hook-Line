package app.hookline.radar.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

data class SavedRegion(val id: Long, val name: String, val sizeMb: Double?, val complete: Boolean)

data class OfflineUi(
    val regions: List<SavedRegion> = emptyList(),
    /** 0..1 while a download runs. */
    val progress: Float? = null,
    val message: String? = null,
)

/**
 * Downloadable base-map regions, for chasing where there's no signal.
 * Radar data still needs a connection; the map under it doesn't.
 * All calls on the main thread (MapLibre's offline API requires it).
 */
class OfflineMaps(context: Context) {
    private val manager: OfflineManager
    private val handles = mutableMapOf<Long, OfflineRegion>()
    private val _ui = MutableStateFlow(OfflineUi())
    val ui: StateFlow<OfflineUi> = _ui.asStateFlow()

    init {
        MapLibre.getInstance(context.applicationContext)
        manager = OfflineManager.getInstance(context.applicationContext)
        refresh()
    }

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                handles.clear()
                list.forEach { handles[it.id] = it }
                _ui.update { u -> u.copy(regions = list.map { SavedRegion(it.id, nameOf(it), null, true) }) }
                list.forEach { r -> r.getStatus(statusCallback(r)) }
            }
            override fun onError(error: String) {
                _ui.update { it.copy(message = "Couldn't read saved maps: $error") }
            }
        })
    }

    private fun statusCallback(r: OfflineRegion) = object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) {
            status ?: return
            _ui.update { u ->
                u.copy(regions = u.regions.map {
                    if (it.id == r.id) it.copy(sizeMb = status.completedResourceSize / 1_048_576.0, complete = status.isComplete) else it
                })
            }
        }
        override fun onError(error: String?) = Unit
    }

    /**
     * Save the visible area from country level down to street-grid level
     * (zoom 3–10: roads, towns and county lines), typically 20–80 MB.
     */
    fun download(bounds: LatLngBounds, name: String, pixelRatio: Float) {
        if (_ui.value.progress != null) return
        val definition = OfflineTilePyramidRegionDefinition(BaseMap.STYLE_URL, bounds, 3.0, 10.0, pixelRatio)
        val metadata = JSONObject().put("name", name).toString().toByteArray()
        _ui.update { it.copy(progress = 0f, message = "Saving $name…") }
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val p = if (status.requiredResourceCount > 0) {
                            status.completedResourceCount.toFloat() / status.requiredResourceCount
                        } else 0f
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            _ui.update { it.copy(progress = null, message = "Saved $name for offline use") }
                            refresh()
                        } else {
                            _ui.update { it.copy(progress = p) }
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        _ui.update { it.copy(message = "Download problem: ${error.message}. Retrying.") }
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        _ui.update { it.copy(progress = null, message = "That area is too large. Zoom in and try again.") }
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) {
                _ui.update { it.copy(progress = null, message = "Couldn't start the download: $error") }
            }
        })
    }

    fun delete(id: Long) {
        val r = handles[id] ?: return
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { refresh() }
            override fun onError(error: String) {
                _ui.update { it.copy(message = "Couldn't delete: $error") }
            }
        })
    }

    private fun nameOf(r: OfflineRegion): String =
        runCatching { JSONObject(String(r.metadata)).getString("name") }.getOrDefault("Saved area")
}
