package app.hookline.radar.stormmode

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.hookline.radar.R
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget: the official warnings active for your places, most
 * severe first, each with its colour, place and end time. Filled by the
 * Storm Mode check, like the radar widget.
 */
class WarningsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        show(context, manager, ids)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<StormModeWorker>().build())
    }

    /** One warning for one place, as stored for the widget. */
    data class Entry(val placeId: Long, val place: String, val event: String, val argb: Int, val level: Int, val expires: Long?) {
        fun json() = JSONObject().put("pid", placeId).put("place", place).put("event", event).put("argb", argb).put("level", level).put("exp", expires ?: 0L)

        companion object {
            fun of(placeId: Long, place: String, w: WarningItem) =
                Entry(placeId, place, w.event, w.argb, w.level.ordinal, w.expires?.toEpochMilli())

            fun parse(o: JSONObject) = Entry(
                o.getLong("pid"), o.getString("place"), o.getString("event"), o.getInt("argb"), o.getInt("level"),
                o.getLong("exp").takeIf { it > 0 },
            )
        }
    }

    companion object {
        private const val ROWS = 3
        private val rowIds = intArrayOf(R.id.ww_row0, R.id.ww_row1, R.id.ww_row2)
        private val stripeIds = intArrayOf(R.id.ww_stripe0, R.id.ww_stripe1, R.id.ww_stripe2)
        private val eventIds = intArrayOf(R.id.ww_event0, R.id.ww_event1, R.id.ww_event2)
        private val detailIds = intArrayOf(R.id.ww_detail0, R.id.ww_detail1, R.id.ww_detail2)

        private fun prefs(context: Context) = context.getSharedPreferences("widget_warnings", Context.MODE_PRIVATE)

        fun load(context: Context): List<Entry> = runCatching {
            val a = JSONArray(prefs(context).getString("entries", "[]"))
            (0 until a.length()).map { Entry.parse(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        /**
         * Saves the latest check. Places whose check failed keep their previous
         * warnings, so a network hiccup doesn't make the widget say "all clear".
         */
        fun publish(context: Context, entries: List<Entry>, failedPlaceIds: Set<Long>) {
            val kept = load(context).filter { it.placeId in failedPlaceIds }
            val all = (entries + kept).distinctBy { it.placeId to it.event }
            prefs(context).edit()
                .putString("entries", JSONArray(all.map { it.json() }).toString())
                .putLong("checked", System.currentTimeMillis())
                .apply()
            refresh(context)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WarningsWidget::class.java))
            if (ids.isNotEmpty()) show(context, manager, ids)
        }

        private fun hm(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        private fun until(ms: Long): String {
            val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            val today = java.time.LocalDate.now(ZoneId.systemDefault())
            return if (z.toLocalDate() == today) DateTimeFormatter.ofPattern("HH:mm").format(z)
            else DateTimeFormatter.ofPattern("EEE HH:mm").format(z)
        }

        fun show(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(context.packageName, R.layout.widget_warnings)
            v.setOnClickPendingIntent(R.id.ww_root, RadarWidget.openAppIntent(context))
            v.setTextViewText(R.id.ww_label, tr("Warnings"))

            val enabled = AlertChecker.isEnabled(context)
            val checked = prefs(context).getLong("checked", 0L)
            val now = System.currentTimeMillis()
            val active = load(context)
                .filter { it.expires == null || it.expires > now }
                .sortedWith(compareByDescending<Entry> { it.level }.thenBy { it.expires ?: Long.MAX_VALUE })
            // Older than ~2 checks: say so rather than imply the list is current.
            val stale = checked == 0L || now - checked > 2 * 60 * 60_000L

            v.setTextViewText(
                R.id.ww_status,
                when {
                    !enabled -> ""
                    checked == 0L -> ""
                    stale -> tr("Not updated since %s", hm(checked))
                    else -> hm(checked)
                },
            )

            for (i in 0 until ROWS) {
                val e = active.getOrNull(i)
                v.setViewVisibility(rowIds[i], if (e == null) View.GONE else View.VISIBLE)
                if (e == null) continue
                v.setInt(stripeIds[i], "setColorFilter", e.argb)
                v.setTextViewText(eventIds[i], e.event)
                v.setTextViewText(detailIds[i], if (e.expires != null) tr("%s · until %s", e.place, until(e.expires)) else e.place)
            }
            val more = active.size - ROWS
            v.setViewVisibility(R.id.ww_more, if (more > 0) View.VISIBLE else View.GONE)
            if (more > 0) v.setTextViewText(R.id.ww_more, tr("+%s more", more))

            val empty = active.isEmpty()
            v.setViewVisibility(R.id.ww_empty, if (empty) View.VISIBLE else View.GONE)
            if (empty) {
                v.setTextViewText(
                    R.id.ww_empty_title,
                    when {
                        !enabled -> tr("Storm Mode is off")
                        checked == 0L -> tr("Checking your places…")
                        stale -> tr("No recent check")
                        else -> tr("No warnings for your places")
                    },
                )
                v.setTextViewText(
                    R.id.ww_empty_sub,
                    when {
                        !enabled -> tr("Turn it on in Hookline to keep this updated")
                        checked == 0L -> tr("Open Hookline once to set up your places")
                        else -> tr("Official NWS and MeteoAlarm warnings")
                    },
                )
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
