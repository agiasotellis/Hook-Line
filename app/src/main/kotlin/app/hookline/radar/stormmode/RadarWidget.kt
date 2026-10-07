package app.hookline.radar.stormmode

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hookline.radar.MainActivity
import app.hookline.radar.R
import app.hookline.radar.core.data.LocalGrid
import app.hookline.radar.core.data.NowcastResult
import app.hookline.radar.core.data.RainSource
import app.hookline.radar.core.radar.Palette
import app.hookline.radar.map.BaseMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Home-screen widget: radar around your first place, its rain outlook and
 * any warning. Refreshed by the Storm Mode check (every 15 min, or every
 * minute in Active mode); tapping it opens the app.
 */
class RadarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        show(context, manager, ids)
        // Fill a fresh widget soon instead of waiting for the next scheduled check.
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<StormModeWorker>().build())
    }

    companion object {
        private const val SIZE = 480

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun imageFile(context: Context) = File(context.filesDir, "widget.png")
        private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** Renders and pushes a new widget image and text. */
        suspend fun publish(context: Context, place: Place, grid: LocalGrid, kind: RainSource.Kind, r: NowcastResult, warning: String?) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RadarWidget::class.java))
            if (ids.isEmpty()) return
            val bmp = render(context, grid, kind)
            withContext(Dispatchers.IO) { imageFile(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(grid.timeMillis).atZone(ZoneId.systemDefault()))
            prefs(context).edit()
                .putString("title", "${place.name} · $time")
                .putString("outlook", r.summary)
                .putString("warning", warning ?: "")
                .apply()
            show(context, AppWidgetManager.getInstance(context), ids)
        }

        fun show(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val p = prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_radar)
            val file = imageFile(context)
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { views.setImageViewBitmap(R.id.widget_image, it) }
            views.setTextViewText(R.id.widget_title, p.getString("title", null) ?: "Hookline")
            views.setTextViewText(R.id.widget_outlook, p.getString("outlook", null) ?: "Open Hookline once to set up your places")
            val warning = p.getString("warning", "").orEmpty()
            views.setTextViewText(R.id.widget_warning, warning)
            views.setViewVisibility(R.id.widget_warning, if (warning.isEmpty()) View.GONE else View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            manager.updateAppWidget(ids, views)
        }

        /** Base map snapshot (if it loads in time) with the radar drawn over it, and the place marked. */
        private suspend fun render(context: Context, grid: LocalGrid, kind: RainSource.Kind): Bitmap {
            val spanKm = grid.size * grid.cellKm
            val snap = withTimeoutOrNull(12_000) { snapshot(context, grid, spanKm) }
            return withContext(Dispatchers.Default) {
                val out = snap?.first?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF141B22.toInt()) }
                val c = Canvas(out)
                val lut = Palette.NWS_REFLECTIVITY.toLut(-10f, 80f, 256)
                val paint = Paint()
                // Cell corners in pixels: from the snapshot's projection, or a plain square layout.
                val px = snap?.second
                val cell = out.width.toFloat() / grid.size
                for (row in 0 until grid.size) for (col in 0 until grid.size) {
                    val v = grid[col, row]
                    if (v.isNaN()) continue
                    val dbz = if (kind == RainSource.Kind.OPERA_RATE) {
                        if (v < 0.1f) continue
                        // Marshall–Palmer, Z = 200 R^1.6
                        (10 * log10(200 * v.toDouble().pow(1.6))).toFloat()
                    } else v
                    if (dbz < 15f) continue
                    val color = lut[(((dbz + 10f) / 90f) * 255).toInt().coerceIn(0, 255)]
                    if ((color ushr 24) == 0) continue
                    paint.color = (color and 0x00FFFFFF) or (0xC8 shl 24)
                    val (x, y) = px?.invoke(col, row) ?: PointF(col * cell, row * cell)
                    c.drawRect(x - cell / 2, y - cell / 2, x + cell / 2 + 0.5f, y + cell / 2 + 0.5f, paint)
                }
                // The place.
                val mid = out.width / 2f
                paint.color = 0xFFE8E4DA.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.isAntiAlias = true
                c.drawCircle(mid, mid, 9f, paint)
                paint.style = Paint.Style.FILL; paint.color = 0xFFE4472F.toInt()
                c.drawCircle(mid, mid, 4.5f, paint)
                out
            }
        }

        private operator fun PointF.component1() = x
        private operator fun PointF.component2() = y

        /** Snapshot of the base map centred on the grid, plus a cell → pixel mapping. */
        private suspend fun snapshot(context: Context, grid: LocalGrid, spanKm: Double): Pair<Bitmap, (Int, Int) -> PointF>? =
            withContext(Dispatchers.Main) {
                MapLibre.getInstance(context.applicationContext)
                // Zoom so the grid's width fills the image.
                val mpp = spanKm * 1000 / SIZE
                val zoom = ln(156_543.03 * cos(Math.toRadians(grid.centerLat)) / mpp) / ln(2.0)
                val options = MapSnapshotter.Options(SIZE, SIZE)
                    .withStyleBuilder(Style.Builder().fromUri(BaseMap.STYLE_URL))
                    .withCameraPosition(CameraPosition.Builder().target(LatLng(grid.centerLat, grid.centerLon)).zoom(zoom).build())
                    .withLogo(false)
                val snapshotter = MapSnapshotter(context.applicationContext, options)
                try {
                    suspendCancellableCoroutine<Pair<Bitmap, (Int, Int) -> PointF>?> { cont ->
                        snapshotter.start(
                            { s ->
                                // Project every cell centre now, on the main thread, while the snapshot is fresh.
                                val n = grid.size
                                val xs = FloatArray(n * n); val ys = FloatArray(n * n)
                                for (row in 0 until n) for (col in 0 until n) {
                                    val ll = grid.latLon(col, row)
                                    val pt = s.pixelForLatLng(LatLng(ll.lat, ll.lon))
                                    xs[row * n + col] = pt.x; ys[row * n + col] = pt.y
                                }
                                val f: (Int, Int) -> PointF = { col, row -> PointF(xs[row * n + col], ys[row * n + col]) }
                                if (cont.isActive) cont.resumeWith(Result.success(s.bitmap to f))
                            },
                            { _ -> if (cont.isActive) cont.resumeWith(Result.success(null)) },
                        )
                        cont.invokeOnCancellation { snapshotter.cancel() }
                    }
                } catch (e: Exception) {
                    null
                }
            }
    }
}
