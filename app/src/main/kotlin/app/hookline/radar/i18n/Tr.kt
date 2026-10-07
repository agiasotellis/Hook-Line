package app.hookline.radar.i18n

import java.util.Locale

/** Translates a user-facing English text. Placeholders are %s (args are inserted in order). Unknown text falls back to English. */
fun tr(text: String, vararg args: Any?): String {
    val lang = Locale.getDefault().language
    val t = when (lang) { "el" -> EL[text]; "de" -> DE[text]; else -> null } ?: text
    return if (args.isEmpty()) t else String.format(Locale.getDefault(), t, *args)
}
