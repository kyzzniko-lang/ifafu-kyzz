package com.ifafu.kyzz.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationValidationTest {
    @Test
    fun nonFiniteAndOutOfRangeCoordinatesAreRejected() {
        assertFalse(MockLocationService.isValidPosition(Double.NaN, 119.0))
        assertFalse(MockLocationService.isValidPosition(26.0, Double.POSITIVE_INFINITY))
        assertFalse(MockLocationService.isValidPosition(90.01, 119.0))
        assertFalse(MockLocationService.isValidPosition(26.0, -180.01))
        assertTrue(MockLocationService.isValidPosition(26.0, 119.0))
        assertTrue(MockLocationService.isValidPosition(-90.0, 180.0))
    }
}
