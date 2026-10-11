package app.hookline.radar.stormmode

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hookline.radar.R
import app.hookline.radar.core.data.RainForecast
import app.hookline.radar.core.data.RainSource
import app.hookline.radar.i18n.ForecastText
import app.hookline.radar.i18n.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln

/**
 * Home-screen widget without a map: the next hour of rain at your place as
 * a row of bars, one per 5 minutes, from the same radar extrapolation as the
 * rain alerts. Filled by the Storm Mode check.
 */
class RainBarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        show(context, manager, ids)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<StormModeWorker>().build())
    }

    companion object {
        private const val W = 720
        private const val H = 150
        private const val INK = 0xFFE8E4DA.toInt()
        private const val HAIR = 0xFF3A4652.toInt()
        private const val LIGHT = 0xFF6FB6F0.toInt()
        private const val STRONG = 0xFF2A63D6.toInt()
        private const val HEAVY = 0xFFE4472F.toInt()

        private fun prefs(context: Context) = context.getSharedPreferences("widget_rain", Context.MODE_PRIVATE)
        private fun imageFile(context: Context) = java.io.File(context.filesDir, "widget_rain.png")

        fun publish(context: Context, place: Place, f: RainForecast) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RainBarWidget::class.java))
            val kind = f.kind; val series = f.series
            prefs(context).edit()
                .putString("place", place.name)
                .putString("kind", kind.name)
                .putLong("time", f.observedMillis)
                .putString("series", series.joinToString(",") { if (it.isNaN()) "" else it.toString() })
                .putString("summary", ForecastText.headline(f, System.currentTimeMillis()))
                .putString("confidence", ForecastText.confidence(f.confidence))
                .apply()
            // Always render, so a widget added later shows the latest outlook straight away.
            imageFile(context).outputStream().use { render(kind, series).compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (ids.isNotEmpty()) show(context, manager, ids)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RainBarWidget::class.java))
            if (ids.isNotEmpty()) show(context, manager, ids)
        }

        /** 0 at the rain threshold, 1 at very heavy rain; the scale is logarithmic in rain rate. */
        private fun level(kind: RainSource.Kind, v: Float): Float = when (kind) {
            RainSource.Kind.MRMS_DBZ -> (v - 18f) / (55f - 18f)
            RainSource.Kind.OPERA_RATE -> (ln(v / 0.2f) / ln(60f / 0.2f))
        }.coerceIn(0f, 1f)

        private fun mix(a: Int, b: Int, k: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        private fun render(kind: RainSource.Kind, series: FloatArray): Bitmap {
            val (thr, heavy) = RainSource.thresholdsFor(kind)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val n = series.size
            val gap = 10f
            val bw = (W - gap * (n - 1)) / n
            val base = H - 6f
            // Baseline and the half-hour tick.
            p.color = HAIR; p.strokeWidth = 2f
            c.drawLine(0f, base + 4f, W.toFloat(), base + 4f, p)
            for (i in 0 until n) {
                val x = i * (bw + gap)
                val v = series[i]
                val r = RectF(x, 0f, x + bw, base)
                when {
                    v.isNaN() -> { // unknown (no motion estimate): a faint stub
                        p.color = HAIR; r.top = base - 8f; c.drawRoundRect(r, 3f, 3f, p)
                    }
                    v < thr -> { // dry: a dot on the baseline
                        p.color = HAIR; c.drawCircle(x + bw / 2, base - 3f, 3.5f, p)
                    }
                    else -> {
                        val k = level(kind, v)
                        r.top = base - (18f + k * (base - 18f))
                        p.color = if (v >= heavy) HEAVY else mix(LIGHT, STRONG, k)
                        c.drawRoundRect(r, 5f, 5f, p)
                    }
                }
            }
            // "Now" marker.
            p.color = INK; c.drawRect(0f, base + 2f, bw, base + 6f, p)
            return bmp
        }

        fun show(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(context.packageName, R.layout.widget_rain)
            v.setOnClickPendingIntent(R.id.wr_root, RadarWidget.openAppIntent(context))
            v.setTextViewText(R.id.wr_now, tr("Now"))
            v.setTextViewText(R.id.wr_mid, tr("30 min"))
            v.setTextViewText(R.id.wr_end, tr("60 min"))

            val pr = prefs(context)
            val time = pr.getLong("time", 0L)
            val enabled = AlertChecker.isEnabled(context)
            val age = System.currentTimeMillis() - time
            val file = imageFile(context)
            when {
                time == 0L -> {
                    v.setTextViewText(R.id.wr_summary, if (enabled) tr("Next hour of rain") else tr("Storm Mode is off"))
                    v.setTextViewText(R.id.wr_place, if (enabled) tr("Open Hookline once to set up your places") else tr("Turn it on in Hookline to keep this updated"))
                    v.setImageViewResource(R.id.wr_bars, R.drawable.widget_rain_empty)
                }
                age > 75 * 60_000L -> {
                    // The hour it described is over: don't show it as a forecast.
                    v.setTextViewText(R.id.wr_summary, tr("No recent rain outlook"))
                    v.setTextViewText(R.id.wr_place, tr("%s · last radar %s", pr.getString("place", "") ?: "", hm(time)))
                    v.setImageViewResource(R.id.wr_bars, R.drawable.widget_rain_empty)
                }
                else -> {
                    v.setTextViewText(R.id.wr_summary, pr.getString("summary", "") ?: "")
                    v.setTextViewText(R.id.wr_place, tr("%s · radar %s", pr.getString("place", "") ?: "", hm(time)) + (pr.getString("confidence", null)?.let { " · $it" } ?: ""))
                    android.graphics.BitmapFactory.decodeFile(file.path)?.let { v.setImageViewBitmap(R.id.wr_bars, it) }
                        ?: v.setImageViewResource(R.id.wr_bars, R.drawable.widget_rain_empty)
                }
            }
            manager.updateAppWidget(ids, v)
        }

        private fun hm(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}
