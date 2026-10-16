package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class MockLocationCoordinatesTest {
    private val coordinates = MockLocationCoordinates

    @Test
    fun longitudeNormalizationHandlesRepeatedCrossingsAndRejectsNonFiniteValues() {
        assertEquals(-180.0, coordinates.normalizeLongitude(180.0), 0.0)
        assertEquals(-179.0, coordinates.normalizeLongitude(541.0), 0.0)
        assertEquals(179.0, coordinates.normalizeLongitude(-541.0), 0.0)
        assertEquals(1e-12, coordinates.normalizeLongitude(1e-12), 0.0)
        assertEquals(1.01, coordinates.normalizeLongitude(1.01), 0.0)
        assertTrue(coordinates.normalizeLongitude(Double.MAX_VALUE) in -180.0..180.0)
        assertThrows(IllegalArgumentException::class.java) { coordinates.normalizeLongitude(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { coordinates.normalizeLongitude(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun equatorialDistanceAndHeadingMatchGeometry() {
        val start = LatLng(0.0, 0.0)
        val end = LatLng(0.0, 1.0)
        assertEquals(PI * coordinates.EARTH_RADIUS_METERS / 180.0, coordinates.distance(start, end), 1e-6)
        assertEquals(90f, coordinates.bearing(start, end), 1e-4f)
        assertEquals(270f, coordinates.bearing(end, start), 1e-4f)
        assertEquals(0.0, coordinates.distance(start, start), 0.0)
        assertEquals(0f, coordinates.bearing(start, start), 0f)
    }

    @Test
    fun antimeridianInterpolationTakesTheShortArc() {
        val start = LatLng(10.0, 179.9)
        val end = LatLng(10.0, -179.9)
        val midpoint = coordinates.interpolate(start, end, 0.5)
        assertTrue(abs(midpoint.lng) > 179.99)
        assertTrue(coordinates.distance(start, end) < 25_000.0)
        assertEquals(coordinates.distance(start, end) / 2.0, coordinates.distance(start, midpoint), 1e-6)
        assertValid(midpoint)
    }

    @Test
    fun greatCircleInterpolationCanCrossThePoleWithoutInvalidLongitude() {
        val start = LatLng(60.0, -90.0)
        val end = LatLng(60.0, 90.0)
        val midpoint = coordinates.interpolate(start, end, 0.5)
        assertEquals(90.0, midpoint.lat, 1e-6)
        assertValid(midpoint)
        assertEquals(coordinates.distance(start, end) / 2.0, coordinates.distance(start, midpoint), 1e-6)
    }

    @Test
    fun antipodalAndNearAntipodalDistancesRemainFiniteAndAccurate() {
        val start = LatLng(0.0, 0.0)
        val antipode = LatLng(0.0, 180.0)
        assertEquals(PI * coordinates.EARTH_RADIUS_METERS, coordinates.distance(start, antipode), 1e-6)
        val nearAntipode = LatLng(0.0, 179.999999)
        assertEquals(Math.toRadians(179.999999) * coordinates.EARTH_RADIUS_METERS,
            coordinates.distance(start, nearAntipode), 1e-6)
        val first = coordinates.interpolate(start, antipode, 0.5)
        val second = coordinates.interpolate(start, antipode, 0.5)
        assertEquals(first, second)
        assertValid(first)
        assertEquals(PI * coordinates.EARTH_RADIUS_METERS / 2.0, coordinates.distance(start, first), 1e-6)
        assertTrue(coordinates.bearing(start, antipode).isFinite())
    }

    @Test
    fun sphericalMovementHandlesPolesAndGlobeCrossings() {
        listOf(LatLng(90.0, 179.0), LatLng(-90.0, -180.0), LatLng(89.999, 179.999)).forEach { origin ->
            listOf(0.0, 90.0, 180.0, 270.0).forEach { bearing ->
                val moved = coordinates.move(origin, 1000.0, bearing)
                assertValid(moved)
                assertEquals(1000.0, coordinates.distance(origin, moved), 1e-4)
            }
        }
        val cross = coordinates.move(LatLng(0.0, 179.999), 1000.0, 90.0)
        assertTrue(cross.lng < -179.9)
        assertValid(coordinates.move(LatLng(26.0, 119.0), Double.MAX_VALUE, Double.MAX_VALUE))
    }

    @Test
    fun gcjAndWgsRoundTripsPreserveCampusAndMajorCityCoordinates() {
        listOf(
            LatLng(26.0835, 119.238),
            LatLng(39.908823, 116.39747),
            LatLng(31.2304, 121.4737),
            LatLng(22.5431, 114.0579),
            LatLng(22.3193, 114.1694), // Hong Kong
            LatLng(22.1987, 113.5439), // Macao
            LatLng(19.0760, 109.5160), // Hainan
            LatLng(29.6520, 91.1720), // Tibet
            LatLng(43.8256, 87.6168), // Xinjiang
            LatLng(45.8038, 126.5349), // Heilongjiang
            LatLng(36.9, 100.1), // Inland lake: do not create an identity hole in the map
            LatLng(26.0, 105.0) // The offset function's sqrt(abs(lng-105)) cusp
        ).forEach { original ->
            val mapPosition = coordinates.wgsToGcj(original)
            assertTrue(coordinates.distance(original, mapPosition) in 100.0..1000.0)
            val restored = coordinates.gcjToWgs(mapPosition)
            assertTrue("Coordinate round trip changed $original to $restored",
                coordinates.distance(original, restored) < 0.01)
        }
    }

    @Test
    fun coordinatesOutsideGcjCoverageAreUnchanged() {
        listOf(LatLng(51.5074, -0.1278), LatLng(34.0522, -118.2437),
            LatLng(-33.8688, 151.2093), LatLng(90.0, 180.0), LatLng(0.0, 0.0),
            LatLng(34.6937, 135.5023), // Osaka: inside the obsolete rectangle
            LatLng(37.5665, 126.9780), // Seoul
            LatLng(25.0330, 121.5654), // Taipei
            LatLng(24.4321, 118.3171), // Kinmen
            LatLng(26.16, 119.951), // Matsu
            LatLng(43.1155, 131.8855), // Vladivostok
            LatLng(47.9186, 106.9177), // Ulaanbaatar
            LatLng(21.0285, 105.8542), // Hanoi
            LatLng(27.7172, 85.3240), // Kathmandu
            LatLng(27.4728, 89.6390), // Thimphu
            LatLng(55.8271, 100.0), LatLng(40.0, 137.8347) // Former rectangle boundary regression
        ).forEach { point ->
            assertEquals(point, coordinates.wgsToGcj(point))
            assertEquals(point, coordinates.gcjToWgs(point))
        }
    }

    @Test
    fun boundaryShiftIsInvertedEvenWhenDisplayedCoordinateLeavesCoverage() {
        listOf(
            LatLng(33.386258, 78.917694),
            LatLng(22.157904, 113.323090),
            LatLng(30.414944, 122.423106),
            LatLng(39.262193, 122.684418),
            LatLng(25.536322, 119.846528),
            LatLng(22.025784, 114.206716)
        ).forEach { original ->
            assertTrue("Fixture must be on the covered geographic boundary", MockLocationGcjCoverage.contains(original))
            val displayed = coordinates.wgsToGcj(original)
            assertFalse("Fixture must cross the boundary after conversion", MockLocationGcjCoverage.contains(displayed))
            val restored = coordinates.gcjToWgs(displayed)
            assertTrue("A forward shift across the boundary must remain invertible for $original",
                coordinates.distance(original, restored) < 0.01)
        }
    }

    @Test
    fun subMeterMovementAwayFromEachPoleAccumulatesOverRepeatedUpdates() {
        listOf(LatLng(90.0, 0.0) to 180.0, LatLng(-90.0, 0.0) to 0.0).forEach { (start, bearing) ->
            var position = start
            repeat(100) { position = coordinates.move(position, 0.12, bearing) }
            assertValid(position)
            assertEquals("Ten seconds of walking at 1.2 m/s must move 12 m", 12.0,
                coordinates.distance(start, position), 0.001)
        }
    }

    @Test
    fun millimeterMovementAndPolarInterpolationRetainHorizontalPrecision() {
        listOf(LatLng(90.0, 179.0), LatLng(-90.0, -180.0)).forEach { start ->
            listOf(0.0, 90.0, 180.0, 270.0).forEach { bearing ->
                val moved = coordinates.move(start, 0.001, bearing)
                assertEquals(0.001, coordinates.distance(start, moved), 1e-7)
                val midpoint = coordinates.interpolate(start, moved, 0.5)
                assertEquals(0.0005, coordinates.distance(start, midpoint), 1e-7)
            }
        }
    }

    @Test
    fun invalidCoordinatesAndMotionInputsAreRejectedBeforeCalculation() {
        val valid = LatLng(26.0, 119.0)
        listOf(LatLng(Double.NaN, 0.0), LatLng(0.0, Double.POSITIVE_INFINITY),
            LatLng(90.01, 0.0), LatLng(0.0, -180.01)).forEach { invalid ->
            assertFalse(coordinates.isValidPosition(invalid.lat, invalid.lng))
            assertThrows(IllegalArgumentException::class.java) { coordinates.distance(valid, invalid) }
            assertThrows(IllegalArgumentException::class.java) { coordinates.bearing(invalid, valid) }
            assertThrows(IllegalArgumentException::class.java) { coordinates.interpolate(valid, invalid, 0.5) }
            assertThrows(IllegalArgumentException::class.java) { coordinates.move(invalid, 1.0, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { coordinates.wgsToGcj(invalid) }
            assertThrows(IllegalArgumentException::class.java) { coordinates.gcjToWgs(invalid) }
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) { coordinates.move(valid, distance, 0.0) }
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { bearing ->
            assertThrows(IllegalArgumentException::class.java) { coordinates.move(valid, 1.0, bearing) }
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach { fraction ->
            assertThrows(IllegalArgumentException::class.java) { coordinates.interpolate(valid, valid, fraction) }
        }
        assertTrue(coordinates.isValidPosition(-90.0, 180.0))
    }

    private fun assertValid(point: LatLng) {
        assertTrue("Invalid coordinate: $point", coordinates.isValidPosition(point.lat, point.lng))
    }
}
