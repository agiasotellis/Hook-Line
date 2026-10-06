package app.hookline.radar.core.radar

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder for NEXRAD Archive II (Level II) data, ICD 2620010 / 2620002.
 *
 * Handles both complete volume files (24-byte "AR2V" header followed by LDM
 * records) and the real-time chunk files published to the
 * `unidata-nexrad-level2-chunks` bucket (the first "S" chunk has the volume
 * header; "I" and "E" chunks are bare LDM records).
 *
 * Only Message 31 (generic digital radar data, RDA Build 10+, ~2008 onward) is
 * decoded. Legacy Message 1 support is tracked in docs/04-ARCHITECTURE.md.
 */
object Level2Decoder {

    private const val VOLUME_HEADER_SIZE = 24
    private const val CTM_HEADER_SIZE = 12
    private const val MESSAGE_HEADER_SIZE = 16
    private const val LEGACY_FRAME_SIZE = 2432
    private const val MESSAGE_31 = 31
    private const val MILLIS_PER_DAY = 86_400_000L

    /** Decode any Archive II file or chunk into radials, in file order. */
    fun decodeRadials(bytes: ByteArray): List<Radial> {
        val out = ArrayList<Radial>(720)
        var site: SiteLocation? = null
        for (record in ldmRecords(bytes)) {
            for (radial in messages31(record)) {
                site = radial.site ?: site
                out += if (radial.site == null && site != null) radial.copy(site = site) else radial
            }
        }
        return out
    }

    /** Decode a full volume and group radials into sweeps by elevation number. */
    fun decodeVolume(bytes: ByteArray): Volume {
        val radials = decodeRadials(bytes)
        require(radials.isNotEmpty()) { "No Message 31 radials found" }
        val sweeps = radials
            .groupBy { it.elevationNumber }
            .toSortedMap()
            .map { (n, rs) -> Sweep(n, rs.map { it.elevationDeg }.average().toFloat(), rs.sortedBy { it.azimuthDeg }) }
        return Volume(radials.first().radarId, radials.firstNotNullOfOrNull { it.site }, sweeps)
    }

    fun hasVolumeHeader(bytes: ByteArray): Boolean =
        bytes.size >= VOLUME_HEADER_SIZE && bytes[0] == 'A'.code.toByte() && bytes[1] == 'R'.code.toByte() &&
            bytes[2] == '2'.code.toByte() && bytes[3] == 'V'.code.toByte()

    /** ICAO id from the volume header, e.g. "KTLX", or null for bare chunks. */
    fun volumeHeaderIcao(bytes: ByteArray): String? =
        if (hasVolumeHeader(bytes)) String(bytes, 20, 4, Charsets.US_ASCII) else null

    /**
     * Split the payload into decompressed LDM records. A record is a signed
     * big-endian Int32 control word (absolute value = compressed length)
     * followed by a bzip2 stream. Uncompressed payloads are returned whole.
     */
    internal fun ldmRecords(bytes: ByteArray): Sequence<ByteArray> = sequence {
        var pos = if (hasVolumeHeader(bytes)) VOLUME_HEADER_SIZE else 0
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        while (pos + 4 <= bytes.size) {
            val size = Math.abs(buf.getInt(pos))
            val start = pos + 4
            val isBzip = start + 3 <= bytes.size && bytes[start] == 'B'.code.toByte() &&
                bytes[start + 1] == 'Z'.code.toByte() && bytes[start + 2] == 'h'.code.toByte()
            if (!isBzip || size == 0 || start + size > bytes.size) {
                // Uncompressed (older archives) or trailing data: hand back the rest as-is.
                if (pos < bytes.size) yield(bytes.copyOfRange(pos, bytes.size))
                break
            }
            BZip2CompressorInputStream(ByteArrayInputStream(bytes, start, size), true).use { yield(it.readBytes()) }
            pos = start + size
        }
    }

    /** Walk the message stream of one decompressed record, decoding Message 31s. */
    internal fun messages31(record: ByteArray): List<Radial> {
        val out = ArrayList<Radial>()
        val buf = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)
        var pos = 0
        while (pos + CTM_HEADER_SIZE + MESSAGE_HEADER_SIZE <= record.size) {
            val h = pos + CTM_HEADER_SIZE
            val sizeHalfWords = buf.getShort(h).toInt() and 0xFFFF
            val type = record[h + 3].toInt() and 0xFF
            if (type == MESSAGE_31) {
                if (sizeHalfWords == 0) break
                val bodyStart = h + MESSAGE_HEADER_SIZE
                val next = pos + CTM_HEADER_SIZE + sizeHalfWords * 2
                if (next > record.size) break
                out += parseMessage31(buf, bodyStart)
                pos = next
            } else {
                pos += LEGACY_FRAME_SIZE
            }
        }
        return out
    }

    internal fun parseMessage31(buf: ByteBuffer, base: Int): Radial {
        val radarId = ascii(buf, base, 4)
        val collectionMs = buf.getInt(base + 4).toLong() and 0xFFFFFFFFL
        val julian = buf.getShort(base + 8).toInt() and 0xFFFF
        val azNumber = buf.getShort(base + 10).toInt() and 0xFFFF
        val azimuth = buf.getFloat(base + 12)
        val azSpacingCode = buf.get(base + 20).toInt() and 0xFF
        val status = buf.get(base + 21).toInt() and 0xFF
        val elevNumber = buf.get(base + 22).toInt() and 0xFF
        val elevation = buf.getFloat(base + 24)
        val blockCount = buf.getShort(base + 30).toInt() and 0xFFFF

        val moments = LinkedHashMap<Moment, MomentData>()
        var site: SiteLocation? = null
        for (i in 0 until minOf(blockCount, 10)) {
            val ptr = buf.getInt(base + 32 + i * 4)
            if (ptr <= 0) continue
            val b = base + ptr
            if (b + 4 > buf.limit()) continue
            val kind = buf.get(b).toInt().toChar()
            val name = ascii(buf, b + 1, 3)
            when {
                kind == 'R' && name == "VOL" -> site = SiteLocation(
                    latitude = buf.getFloat(b + 8),
                    longitude = buf.getFloat(b + 12),
                    heightMeters = buf.getShort(b + 16).toInt(),
                )
                kind == 'D' -> Moment.fromCode(name)?.let { m -> moments[m] = parseMoment(buf, b, m) }
            }
        }

        return Radial(
            radarId = radarId,
            timeMillis = (julian - 1) * MILLIS_PER_DAY + collectionMs,
            azimuthNumber = azNumber,
            azimuthDeg = azimuth,
            azimuthSpacingDeg = if (azSpacingCode == 1) 0.5f else 1.0f,
            elevationNumber = elevNumber,
            elevationDeg = elevation,
            radialStatus = status,
            moments = moments,
            site = site,
        )
    }

    private fun parseMoment(buf: ByteBuffer, b: Int, moment: Moment): MomentData {
        val gateCount = buf.getShort(b + 8).toInt() and 0xFFFF
        val firstGate = buf.getShort(b + 10).toInt()
        val spacing = buf.getShort(b + 12).toInt() and 0xFFFF
        val wordSize = buf.get(b + 19).toInt() and 0xFF
        val scale = buf.getFloat(b + 20)
        val offset = buf.getFloat(b + 24)
        val data = b + 28
        val gates = FloatArray(gateCount) { i ->
            val raw = if (wordSize == 16) buf.getShort(data + i * 2).toInt() and 0xFFFF
            else buf.get(data + i).toInt() and 0xFF
            when (raw) {
                0 -> Gate.BELOW_THRESHOLD
                1 -> Gate.RANGE_FOLDED
                else -> if (scale == 0f) raw.toFloat() else (raw - offset) / scale
            }
        }
        return MomentData(moment, firstGate, spacing, gates)
    }

    private fun ascii(buf: ByteBuffer, at: Int, len: Int): String {
        val b = ByteArray(len) { buf.get(at + it) }
        return String(b, Charsets.US_ASCII)
    }
}
