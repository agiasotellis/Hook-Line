package app.hookline.radar.stormmode

import android.content.Context
import app.hookline.radar.core.data.RainIntensity
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

/**
 * The user's alert choices, stored with Storm Mode's settings:
 * how hard rain must be to notify, quiet hours, and when the background
 * check last ran (to spot Android delaying it).
 */
class AlertPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("storm_mode", Context.MODE_PRIVATE)

    /** Rain alerts only when the next hour reaches at least this intensity. */
    var rainMin: RainIntensity
        get() = runCatching { RainIntensity.valueOf(prefs.getString("rain_min", null) ?: "LIGHT") }.getOrDefault(RainIntensity.LIGHT)
        set(v) = prefs.edit().putString("rain_min", v.name).apply()

    var quietOn: Boolean
        get() = prefs.getBoolean("quiet_on", false)
        set(v) = prefs.edit().putBoolean("quiet_on", v).apply()

    /** Hours of day, 0..23. */
    var quietFrom: Int
        get() = prefs.getInt("quiet_from", 22)
        set(v) = prefs.edit().putInt("quiet_from", v.mod(24)).apply()

    var quietTo: Int
        get() = prefs.getInt("quiet_to", 7)
        set(v) = prefs.edit().putInt("quiet_to", v.mod(24)).apply()

    /** Red warnings and tornado warnings still come through in quiet hours. */
    var quietLetSevere: Boolean
        get() = prefs.getBoolean("quiet_severe", true)
        set(v) = prefs.edit().putBoolean("quiet_severe", v).apply()

    /** Last time the background check finished. */
    var lastCheckMillis: Long
        get() = prefs.getLong("last_check", 0L)
        set(v) = prefs.edit().putLong("last_check", v).apply()

    fun inQuietHours(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!quietOn) return false
        val h = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        return inWindow(h, quietFrom, quietTo)
    }

    companion object {
        /** True if [t] is in [from, to), wrapping past midnight when from > to. */
        fun inWindow(t: LocalTime, from: Int, to: Int): Boolean {
            val h = t.hour
            return when {
                from == to -> false
                from < to -> h in from until to
                else -> h >= from || h < to
            }
        }
    }
}
