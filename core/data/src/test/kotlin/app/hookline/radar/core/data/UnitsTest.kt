package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test fun defaults() {
        assertEquals(UnitSystem.METRIC, UnitSystem.forCountry("GR"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.forCountry("us"))
    }

    @Test fun formats() {
        assertEquals("2.5 km", Fmt.distance(2_500.0, UnitSystem.METRIC))
        assertEquals("12 km", Fmt.distance(12_400.0, UnitSystem.METRIC))
        assertEquals("6.2 mi", Fmt.distance(10_000.0, UnitSystem.IMPERIAL))
        assertEquals("2,350 m", Fmt.height(2_347.0, UnitSystem.METRIC))
        assertEquals("7,700 ft", Fmt.height(2_347.0, UnitSystem.IMPERIAL))
        assertEquals("36 km/h", Fmt.speed(10.0, UnitSystem.METRIC))
        assertEquals("22 mph", Fmt.speed(10.0, UnitSystem.IMPERIAL))
        assertEquals("063° ENE", Fmt.bearing(63.0))
        assertEquals("000° N", Fmt.bearing(359.8))
        assertEquals("17 min", Fmt.duration(1_020.0))
        assertEquals("1 h 05 min", Fmt.duration(3_900.0))
    }
}
