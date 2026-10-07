package app.hookline.radar.core.radar

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.pow

/**
 * Just enough GRIB2 for NOAA MRMS products: one message, a regular lat/lon
 * grid (template 3.0) and PNG-packed values (template 5.41), or simple
 * packing (5.0). Values are streamed row by row so a 7000×3500 grid never
 * has to sit in memory at once.
 */
object Grib2 {

    class Grid(
        val ni: Int, val nj: Int,
        /** First point (row 0, column 0) and increments, degrees; rows run south when [dLat] < 0. */
        val lat1: Double, val lon1: Double, val dLat: Double, val dLon: Double,
    ) {
        fun latOf(row: Int) = lat1 + row * dLat
        fun lonOf(col: Int) = lon1 + col * dLon
    }

    class Header(val grid: Grid, val discipline: Int, val category: Int, val parameter: Int, val refTimeMillis: Long)

    /**
     * Decodes a (possibly gzipped) message, calling [row] with each grid row of
     * physical values (reused array; copy what you keep). Returns the header.
     */
    fun decode(bytes: ByteArray, onGrid: (Grid) -> Unit = {}, row: (Int, FloatArray) -> Unit): Header {
        val data = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        } else bytes
        if (data.size < 16 || String(data, 0, 4, Charsets.US_ASCII) != "GRIB") throw IOException("Not GRIB")
        if (data[7].toInt() != 2) throw IOException("Not GRIB edition 2")
        val discipline = data[6].toInt() and 0xFF
        var p = 16
        var grid: Grid? = null
        var category = -1; var parameter = -1
        var template5 = -1
        var ref = 0f; var e = 0; var d = 0; var bits = 0
        var refTime = 0L
        while (p + 5 <= data.size) {
            if (String(data, p, 4, Charsets.US_ASCII) == "7777") break
            val len = u32(data, p).toInt()
            val sec = data[p + 4].toInt()
            when (sec) {
                1 -> {
                    val y = u16(data, p + 12); val mo = data[p + 14].toInt(); val dd = data[p + 15].toInt()
                    val h = data[p + 16].toInt(); val mi = data[p + 17].toInt(); val s = data[p + 18].toInt()
                    refTime = java.time.LocalDateTime.of(y, mo, dd, h, mi, s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                }
                3 -> {
                    val tmpl = u16(data, p + 12)
                    if (tmpl != 0) throw IOException("Unsupported grid template 3.$tmpl")
                    val ni = u32(data, p + 30).toInt(); val nj = u32(data, p + 34).toInt()
                    val la1 = s32(data, p + 46) / 1e6; var lo1 = s32(data, p + 50) / 1e6
                    val la2 = s32(data, p + 55) / 1e6
                    val di = u32(data, p + 63) / 1e6; val dj = u32(data, p + 67) / 1e6
                    val scan = data[p + 71].toInt() and 0xFF
                    if (lo1 > 180) lo1 -= 360
                    val south = (scan and 0x40) == 0 // bit 2 clear: rows scan north to south
                    val dLat = if (south || la2 < la1) -dj else dj
                    val dLon = if ((scan and 0x80) != 0) -di else di
                    grid = Grid(ni, nj, la1, lo1, dLat, dLon)
                }
                4 -> {
                    category = data[p + 9].toInt() and 0xFF
                    parameter = data[p + 10].toInt() and 0xFF
                }
                5 -> {
                    template5 = u16(data, p + 9)
                    ref = java.lang.Float.intBitsToFloat(u32(data, p + 11).toInt())
                    e = signMag16(data, p + 15)
                    d = signMag16(data, p + 17)
                    bits = data[p + 19].toInt() and 0xFF
                }
                6 -> if ((data[p + 5].toInt() and 0xFF) != 255) throw IOException("Bitmaps not supported")
                7 -> {
                    val g = grid ?: throw IOException("Data before grid")
                    onGrid(g)
                    val scaleE = 2.0.pow(e); val scaleD = 10.0.pow(-d)
                    val out = FloatArray(g.ni)
                    val toValue = { x: Long -> ((ref + x * scaleE) * scaleD).toFloat() }
                    when (template5) {
                        41 -> png(data, p + 5, len - 5, g.ni, g.nj) { r, raw -> for (i in 0 until g.ni) out[i] = toValue(raw[i]); row(r, out) }
                        0 -> simple(data, p + 5, bits, g.ni, g.nj) { r, raw -> for (i in 0 until g.ni) out[i] = toValue(raw[i]); row(r, out) }
                        else -> throw IOException("Unsupported packing 5.$template5")
                    }
                    return Header(g, discipline, category, parameter, refTime)
                }
            }
            p += len
        }
        throw IOException("No data section")
    }

    /** Streams PNG rows as raw unsigned integers (big-endian per pixel). */
    private fun png(data: ByteArray, off: Int, len: Int, w: Int, h: Int, row: (Int, LongArray) -> Unit) {
        if (len < 8 || data[off + 1] != 'P'.code.toByte()) throw IOException("Not PNG data")
        var p = off + 8
        val end = off + len
        var bitDepth = 8; var channels = 1
        val idat = java.io.ByteArrayOutputStream()
        while (p + 8 <= end) {
            val clen = u32(data, p).toInt()
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IHDR" -> {
                    bitDepth = data[p + 16].toInt() and 0xFF
                    channels = when (data[p + 17].toInt() and 0xFF) { 0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> throw IOException("PNG colour type") }
                }
                "IDAT" -> idat.write(data, p + 8, clen)
                "IEND" -> break
            }
            p += 12 + clen
        }
        val bitsPerPixel = bitDepth * channels
        val bpp = maxOf(1, bitsPerPixel / 8)
        val stride = (w * bitsPerPixel + 7) / 8
        val input: InputStream = InflaterInputStream(ByteArrayInputStream(idat.toByteArray()))
        val din = DataInputStream(input)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        val values = LongArray(w)
        for (r in 0 until h) {
            val filter = din.readUnsignedByte()
            din.readFully(cur)
            unfilter(filter, cur, prev, bpp)
            if (bitsPerPixel >= 8) {
                val bytesPer = bitsPerPixel / 8
                for (i in 0 until w) {
                    var v = 0L
                    val o = i * bytesPer
                    for (k in 0 until bytesPer) v = (v shl 8) or (cur[o + k].toLong() and 0xFF)
                    values[i] = v
                }
            } else {
                for (i in 0 until w) {
                    val bit = i * bitsPerPixel
                    val b = cur[bit / 8].toInt() and 0xFF
                    values[i] = ((b shr (8 - bitsPerPixel - bit % 8)) and ((1 shl bitsPerPixel) - 1)).toLong()
                }
            }
            row(r, values)
            val t = prev; prev = cur; cur = t
        }
    }

    private fun unfilter(f: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        when (f) {
            0 -> Unit
            1 -> for (i in bpp until cur.size) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            2 -> for (i in cur.indices) cur[i] = (cur[i] + prev[i]).toByte()
            3 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + ((a + (prev[i].toInt() and 0xFF)) shr 1)).toByte()
            }
            4 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val pp = a + b - c
                val pa = kotlin.math.abs(pp - a); val pb = kotlin.math.abs(pp - b); val pc = kotlin.math.abs(pp - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                cur[i] = (cur[i] + pred).toByte()
            }
            else -> throw IOException("PNG filter $f")
        }
    }

    private fun simple(data: ByteArray, off: Int, bits: Int, w: Int, h: Int, row: (Int, LongArray) -> Unit) {
        val values = LongArray(w)
        var bitPos = off.toLong() * 8
        for (r in 0 until h) {
            for (i in 0 until w) {
                var v = 0L
                for (k in 0 until bits) {
                    val byte = data[(bitPos ushr 3).toInt()].toInt()
                    v = (v shl 1) or ((byte shr (7 - (bitPos and 7).toInt())) and 1).toLong()
                    bitPos++
                }
                values[i] = v
            }
            row(r, values)
        }
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    /** GRIB2 signed integers are sign-magnitude, not two's complement. */
    private fun s32(b: ByteArray, p: Int): Long { val v = u32(b, p); return if (v and 0x80000000L != 0L) -(v and 0x7FFFFFFFL) else v }
    private fun signMag16(b: ByteArray, p: Int): Int { val v = u16(b, p); return if (v and 0x8000 != 0) -(v and 0x7FFF) else v }
}
