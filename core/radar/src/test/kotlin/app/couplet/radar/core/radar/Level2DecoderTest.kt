package app.couplet.radar.core.radar

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Level2DecoderTest {

    /** Build one Message 31 with a VOL block and an 8-bit REF block. */
    private fun message31(
        azimuth: Float,
        elevationNumber: Int,
        elevation: Float,
        rawGates: IntArray,
        julian: Int = 20_000,
        ms: Int = 3_600_000,
    ): ByteArray {
        val headerLen = 68
        val volLen = 44
        val refLen = 28 + rawGates.size
        val bodyLen = headerLen + volLen + refLen
        val total = 12 + 16 + bodyLen
        val b = ByteBuffer.allocate(total + (total % 2)).order(ByteOrder.BIG_ENDIAN)
        // CTM header: 12 bytes of zeros
        b.position(12)
        // Message header
        b.putShort(((16 + bodyLen + 1) / 2).toShort()) // size in halfwords
        b.put(0) // channel
        b.put(31) // type
        b.putShort(1); b.putShort(julian.toShort()); b.putInt(ms); b.putShort(1); b.putShort(1)
        val base = b.position()
        b.put("KTLX".toByteArray(Charsets.US_ASCII))
        b.putInt(ms)
        b.putShort(julian.toShort())
        b.putShort(1) // azimuth number
        b.putFloat(azimuth)
        b.put(0); b.put(0) // compression, spare
        b.putShort(bodyLen.toShort())
        b.put(1) // 0.5 deg spacing
        b.put(RadialStatus.INTERMEDIATE.toByte())
        b.put(elevationNumber.toByte())
        b.put(0) // cut sector
        b.putFloat(elevation)
        b.put(0); b.put(0) // spot blanking, indexing
        b.putShort(2) // block count
        b.putInt(headerLen) // VOL pointer
        b.putInt(headerLen + volLen) // REF pointer
        repeat(7) { b.putInt(0) }
        // VOL block
        b.position(base + headerLen)
        b.put('R'.code.toByte()); b.put("VOL".toByteArray(Charsets.US_ASCII))
        b.putShort(volLen.toShort()); b.put(1); b.put(0)
        b.putFloat(35.333f); b.putFloat(-97.278f); b.putShort(370)
        // REF block
        b.position(base + headerLen + volLen)
        b.put('D'.code.toByte()); b.put("REF".toByteArray(Charsets.US_ASCII))
        b.putInt(0)
        b.putShort(rawGates.size.toShort())
        b.putShort(2125) // first gate (m)
        b.putShort(250) // spacing (m)
        b.putShort(0); b.putShort(0); b.put(0)
        b.put(8) // word size
        b.putFloat(2f) // scale
        b.putFloat(66f) // offset
        rawGates.forEach { b.put(it.toByte()) }
        return b.array()
    }

    private fun bzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        BZip2CompressorOutputStream(o).use { it.write(bytes) }
    }.toByteArray()

    private fun ldmRecord(payload: ByteArray, last: Boolean = false): ByteArray {
        val c = bzip(payload)
        return ByteBuffer.allocate(4 + c.size).putInt(if (last) -c.size else c.size).put(c).array()
    }

    private fun volumeHeader(): ByteArray {
        val h = ByteArray(24)
        "AR2V0006.001".toByteArray(Charsets.US_ASCII).copyInto(h)
        "KTLX".toByteArray(Charsets.US_ASCII).copyInto(h, 20)
        return h
    }

    @Test
    fun decodesScaledReflectivityAndSentinels() {
        // raw -> (raw - 66) / 2 : 0=below, 1=folded, 66=0 dBZ, 166=50 dBZ
        val msg = message31(azimuth = 45.25f, elevationNumber = 1, elevation = 0.48f, rawGates = intArrayOf(0, 1, 66, 166))
        val radials = Level2Decoder.messages31(msg)
        assertEquals(1, radials.size)
        val r = radials[0]
        assertEquals("KTLX", r.radarId)
        assertEquals(45.25f, r.azimuthDeg, 1e-4f)
        assertEquals(0.5f, r.azimuthSpacingDeg)
        val ref = r.moments.getValue(Moment.REFLECTIVITY)
        assertEquals(2125, ref.firstGateMeters)
        assertEquals(250, ref.gateSpacingMeters)
        assertEquals(Gate.BELOW_THRESHOLD, ref.gates[0])
        assertEquals(Gate.RANGE_FOLDED, ref.gates[1])
        assertEquals(0f, ref.gates[2], 1e-4f)
        assertEquals(50f, ref.gates[3], 1e-4f)
        assertNotNull(r.site)
        assertEquals(35.333f, r.site!!.latitude, 1e-3f)
    }

    @Test
    fun convertsJulianDateToEpochMillis() {
        // Julian day 1 = 1970-01-01
        val msg = message31(0f, 1, 0.5f, intArrayOf(100), julian = 2, ms = 1_000)
        val r = Level2Decoder.messages31(msg).single()
        assertEquals(86_400_000L + 1_000L, r.timeMillis)
    }

    @Test
    fun decodesCompressedVolumeIntoSweeps() {
        val sweep1 = (0 until 4).map { message31(it * 90f, 1, 0.5f, intArrayOf(120, 130)) }
        val sweep2 = (0 until 4).map { message31(it * 90f + 1f, 2, 0.9f, intArrayOf(140)) }
        val rec1 = ldmRecord(sweep1.reduce { a, b -> a + b })
        val rec2 = ldmRecord(sweep2.reduce { a, b -> a + b }, last = true)
        val file = volumeHeader() + rec1 + rec2

        assertTrue(Level2Decoder.hasVolumeHeader(file))
        assertEquals("KTLX", Level2Decoder.volumeHeaderIcao(file))
        val vol = Level2Decoder.decodeVolume(file)
        assertEquals(2, vol.sweeps.size)
        assertEquals(4, vol.sweeps[0].radials.size)
        assertEquals(0.9f, vol.sweeps[1].elevationDeg, 1e-4f)
        assertTrue(vol.sweeps[0].has(Moment.REFLECTIVITY))
    }

    @Test
    fun decodesBareRealtimeChunkWithoutVolumeHeader() {
        val chunk = ldmRecord(message31(10f, 3, 1.3f, intArrayOf(150)))
        val radials = Level2Decoder.decodeRadials(chunk)
        assertEquals(1, radials.size)
        assertEquals(3, radials[0].elevationNumber)
    }

    @Test
    fun skipsLegacyFixedSizeFrames() {
        // A 2432-byte metadata frame (type 2) before a Message 31.
        val legacy = ByteArray(2432).also { it[12 + 3] = 2; it[12 + 1] = 0x10 }
        val radials = Level2Decoder.messages31(legacy + message31(5f, 1, 0.5f, intArrayOf(100)))
        assertEquals(1, radials.size)
    }
}
