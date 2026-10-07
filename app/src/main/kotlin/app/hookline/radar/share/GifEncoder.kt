package app.hookline.radar.share

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Minimal animated GIF89a encoder: one shared 256-colour palette for all
 * frames (built by popularity on 15-bit colour), LZW compression, looping
 * forever. Good enough for radar loops, which have few distinct colours.
 */
class GifEncoder(private val width: Int, private val height: Int) {

    /** [frames] are ARGB pixels (width*height each); [delaysCs] in hundredths of a second. */
    fun encode(frames: List<IntArray>, delaysCs: List<Int>, out: OutputStream) {
        val palette = buildPalette(frames)
        val lookup = buildLookup(palette)
        val w = ByteArrayOutputStream()
        w.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(w, width); short(w, height)
        w.write(0xF7); w.write(0); w.write(0) // global table, 8 bits, 256 entries
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            w.write((c shr 16) and 0xFF); w.write((c shr 8) and 0xFF); w.write(c and 0xFF)
        }
        // Loop forever.
        w.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        w.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        w.write(byteArrayOf(3, 1, 0, 0, 0))
        val indices = ByteArray(width * height)
        frames.forEachIndexed { f, px ->
            for (i in px.indices) {
                val c = px[i]
                indices[i] = lookup[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]
            }
            w.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
            short(w, delaysCs.getOrElse(f) { 30 })
            w.write(0); w.write(0)
            w.write(0x2C); short(w, 0); short(w, 0); short(w, width); short(w, height); w.write(0)
            w.write(8)
            lzw(indices, w)
            w.write(0)
        }
        w.write(0x3B)
        out.write(w.toByteArray())
    }

    private fun short(o: OutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    private fun key15(c: Int) = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)

    private fun buildPalette(frames: List<IntArray>): IntArray {
        val counts = IntArray(32768)
        for (px in frames) {
            var i = 0
            while (i < px.size) { counts[key15(px[i])]++; i += 3 }
        }
        val top = (0 until 32768).filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(256)
        return IntArray(top.size) { k ->
            val b = top[k]
            val r = (b shr 10) and 31; val g = (b shr 5) and 31; val bl = b and 31
            ((r shl 3) or (r shr 2) shl 16) or ((g shl 3) or (g shr 2) shl 8) or ((bl shl 3) or (bl shr 2))
        }
    }

    private fun buildLookup(palette: IntArray): ByteArray {
        val out = ByteArray(32768)
        for (b in 0 until 32768) {
            val r = ((b shr 10) and 31) shl 3; val g = ((b shr 5) and 31) shl 3; val bl = (b and 31) shl 3
            var best = 0; var bestD = Int.MAX_VALUE
            for (i in palette.indices) {
                val c = palette[i]
                val dr = ((c shr 16) and 0xFF) - r; val dg = ((c shr 8) and 0xFF) - g; val db = (c and 0xFF) - bl
                val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (d < bestD) { bestD = d; best = i; if (d == 0) break }
            }
            out[b] = best.toByte()
        }
        return out
    }

    /** LZW with variable code size, 8-bit initial code size, output as GIF sub-blocks. */
    private fun lzw(data: ByteArray, o: OutputStream) {
        val clear = 256; val eoi = 257
        val dict = HashMap<Int, Int>(8192)
        var nBits = 9; var maxCode = 511; var freeEnt = 258; var clearFlag = false
        val block = ByteArray(255); var blockLen = 0
        var acc = 0L; var accBits = 0

        fun byteOut(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) { o.write(255); o.write(block, 0, 255); blockLen = 0 }
        }
        fun output(code: Int) {
            acc = acc or (code.toLong() shl accBits); accBits += nBits
            while (accBits >= 8) { byteOut((acc and 0xFF).toInt()); acc = acc ushr 8; accBits -= 8 }
            if (clearFlag) {
                nBits = 9; maxCode = 511; clearFlag = false
            } else if (freeEnt > maxCode) {
                nBits++
                maxCode = if (nBits == 12) 4096 else (1 shl nBits) - 1
            }
        }

        output(clear)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val hit = dict[key]
            if (hit != null) { prefix = hit; continue }
            output(prefix)
            prefix = k
            if (freeEnt < 4096) {
                dict[key] = freeEnt++
            } else {
                dict.clear(); freeEnt = 258; clearFlag = true
                output(clear)
            }
        }
        output(prefix)
        output(eoi)
        if (accBits > 0) byteOut((acc and 0xFF).toInt())
        if (blockLen > 0) { o.write(blockLen); o.write(block, 0, blockLen) }
    }
}
