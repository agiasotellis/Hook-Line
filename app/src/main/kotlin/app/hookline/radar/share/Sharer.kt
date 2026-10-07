package app.hookline.radar.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.hookline.radar.R
import java.io.File
import java.io.OutputStream
import app.hookline.radar.i18n.tr

/** Builds shareable pictures of the map and hands them to Android's share sheet. */
object Sharer {

    /**
     * Radar + base map with a footer: the mark, the name, what's shown and the
     * data credit (attribution travels with the picture, as the licences ask).
     */
    fun compose(context: Context, radar: Bitmap, map: Bitmap?, title: String, credit: String, bg: Int): Bitmap {
        val w = radar.width; val h = radar.height
        val footer = (w * 0.17f).toInt().coerceIn(120, 280)
        val out = Bitmap.createBitmap(w, h + footer, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(bg)
        c.drawBitmap(radar, 0f, 0f, null)
        map?.let { c.drawBitmap(it, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG)) }

        val pad = footer * 0.16f
        val mark = (footer * 0.42f).toInt()
        ContextCompat.getDrawable(context, R.drawable.ic_hookline_mark)?.apply {
            setBounds(pad.toInt(), (h + pad).toInt(), pad.toInt() + mark, (h + pad).toInt() + mark)
            draw(c)
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8E4DA.toInt(); textSize = footer * 0.2f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textX = pad * 1.6f + mark
        c.drawText("Hookline", textX, h + pad + mark * 0.42f, ink)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9C4BA.toInt(); textSize = footer * 0.14f }
        c.drawText(fit(title, sub, w - textX - pad), textX, h + pad + mark * 0.92f, sub)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C98A3.toInt(); textSize = footer * 0.1f }
        c.drawText(fit(credit, small, w - 2 * pad), pad, h + footer - pad * 0.7f, small)
        return out
    }

    private fun fit(text: String, p: Paint, max: Float): String {
        if (p.measureText(text) <= max) return text
        var t = text
        while (t.length > 4 && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    fun scaleToWidth(b: Bitmap, maxW: Int): Bitmap =
        if (b.width <= maxW) b else Bitmap.createScaledBitmap(b, maxW, (b.height.toLong() * maxW / b.width).toInt(), true)

    fun write(context: Context, name: String, body: (OutputStream) -> Unit): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != name) it.delete() } // keep the cache small
        return File(dir, name).also { f -> f.outputStream().use(body) }
    }

    fun share(context: Context, file: File, mime: String, text: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Share radar")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
}
