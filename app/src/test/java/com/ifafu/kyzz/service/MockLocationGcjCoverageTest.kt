package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import java.io.DataInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationGcjCoverageTest {
    @Test
    fun bundledCoverageIsPinnedAndReadableWithoutAndroidContext() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/gcj_coverage.bin")).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("9c98bd2707b3a6627d66c700c215d09e24d845ef845a7bb68c5461069a2b7147", digest)
        assertTrue(MockLocationGcjCoverage.contains(LatLng(26.0835, 119.238)))
    }

    @Test
    fun forwardInverseRoundTripPreservesBoundarySamplesAcrossAllPolygons() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/gcj_coverage.bin"))
        DataInputStream(stream).use { input ->
            input.readInt()
            input.readInt()
            val polygonCount = input.readInt()
            repeat(polygonCount) {
                repeat(input.readInt()) {
                    val count = input.readInt()
                    repeat(count) { index ->
                        val lng = input.readInt() / 1_000_000.0
                        val lat = input.readInt() / 1_000_000.0
                        // At least eight samples per ring, including small islands,
                        // coastlines and all mainland-border directions.
                        if (index % (count / 8).coerceAtLeast(1) == 0) {
                            val original = LatLng(lat, lng)
                            val restored = MockLocationCoordinates.gcjToWgs(MockLocationCoordinates.wgsToGcj(original))
                            assertTrue("Boundary round trip moved $original to $restored",
                                MockLocationCoordinates.distance(original, restored) < 0.01)
                        }
                    }
                }
            }
        }
    }
}
