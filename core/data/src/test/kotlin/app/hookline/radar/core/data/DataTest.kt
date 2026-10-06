package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {

    private val sample = """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "id": "https://api.weather.gov/alerts/urn:oid:1",
          "type": "Feature",
          "geometry": {
            "type": "Polygon",
            "coordinates": [[[-97.60, 35.30], [-97.40, 35.30], [-97.40, 35.45], [-97.60, 35.45], [-97.60, 35.30]]]
          },
          "properties": {
            "id": "urn:oid:1",
            "event": "Tornado Warning",
            "headline": "Tornado Warning issued for Cleveland County",
            "severity": "Extreme",
            "expires": "2026-05-06T00:42:00-05:00",
            "description": "AT 712 PM CDT, A SEVERE THUNDERSTORM CAPABLE OF PRODUCING A TORNADO...",
            "instruction": "TAKE COVER NOW!",
            "parameters": {
              "tornadoDetection": ["RADAR INDICATED"],
              "maxHailSize": ["1.75"],
              "maxWindGust": ["60 MPH"]
            }
          }
        },
        {
          "id": "https://api.weather.gov/alerts/urn:oid:2",
          "type": "Feature",
          "geometry": null,
          "properties": { "id": "urn:oid:2", "event": "Tornado Watch", "severity": "Severe", "expires": null }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesWarningWithImpactTagsAndWatchWithoutGeometry() {
        val alerts = NwsAlerts.parse(sample)
        assertEquals(2, alerts.size)
        val tor = alerts[0]
        assertEquals(Hazard.TORNADO_WARNING, tor.hazard)
        assertEquals("RADAR INDICATED", tor.tornadoDetection)
        assertEquals(1.75, tor.maxHailInches!!, 1e-9)
        assertEquals(60, tor.maxWindMph)
        assertEquals(1, tor.polygons.size)
        assertTrue(tor.expires != null)

        val watch = alerts[1]
        assertEquals(Hazard.TORNADO_WATCH, watch.hazard)
        assertTrue(watch.polygons.isEmpty())
        assertNull(watch.expires)
    }

    @Test
    fun polygonContainment() {
        val tor = NwsAlerts.parse(sample)[0]
        assertTrue(tor.covers(LatLon(35.34, -97.49))) // Moore
        assertFalse(tor.covers(LatLon(35.47, -97.52))) // north of polygon
    }

    @Test
    fun parsesRealtimeChunkKeys() {
        val c = NexradSource.parseChunkKey("KTLX/412/20261006-193012-005-I")!!
        assertEquals(412, c.volume)
        assertEquals(5, c.sequence)
        assertEquals('I', c.kind)
        assertNull(NexradSource.parseChunkKey("KTLX/412/garbage"))
    }

    @Test
    fun readsS3ListingKeysAndToken() {
        val xml = """<ListBucketResult><Contents><Key>KTLX/1/20261006-193012-001-S</Key></Contents>
            <Contents><Key>KTLX/1/20261006-193012-002-I</Key></Contents>
            <NextContinuationToken>abc&amp;def</NextContinuationToken></ListBucketResult>"""
        assertEquals(2, S3Listing.keys(xml).size)
        assertEquals("abc&def", S3Listing.continuationToken(xml))
    }
}
