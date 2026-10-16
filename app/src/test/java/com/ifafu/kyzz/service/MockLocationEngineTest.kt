package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationEngineTest {
    private val coordinates = MockLocationCoordinates
    private val start = LatLng(26.0, 119.0)

    @Test
    fun actualElapsedTimeProducesTheSameTravelWithIrregularTicks() {
        val points = route(500.0)
        val irregular = MockLocationEngine(points, 20.0, false, 0)
        val regular = MockLocationEngine(points, 20.0, false, 0)
        listOf(0.07, 0.13, 0.2, 0.05, 0.55).forEach { irregular.advance(it) }
        repeat(10) { regular.advance(0.1) }
        assertEquals(20.0, coordinates.distance(start, irregular.current().position), 1e-5)
        assertTrue(coordinates.distance(irregular.current().position, regular.current().position) < 1e-6)
        assertEquals(20.0, irregular.current().speed, 0.0)
        assertFalse(irregular.completed)
    }

    @Test
    fun longSuspensionPausesOneTickWithoutCompensatingOnTheNextTick() {
        val engine = MockLocationEngine(route(500.0), 10.0, false, 0)
        val before = engine.advance(0.1)
        assertEquals(before, engine.advance(5.0))
        assertEquals(before, engine.advance(0.0))
        val resumed = engine.advance(0.1)
        assertEquals(2.0, coordinates.distance(start, resumed.position), 1e-5)
        assertEquals(10.0, resumed.speed, 0.0)
    }

    @Test
    fun nonLoopFinishesAtTheFinalWaypointAndRemainsStopped() {
        val points = route(10.0)
        val engine = MockLocationEngine(points, 20.0, false, 0)
        val final = engine.advance(1.0)
        assertTrue(final.completed)
        assertTrue(engine.completed)
        assertEquals(points.last(), final.position)
        assertEquals(0.0, final.speed, 0.0)
        assertEquals(1, final.waypointIndex)
        assertEquals(final, engine.advance(1.0))
    }

    @Test
    fun finiteLoopStopsAtStartAfterTheRequestedLapsWithoutJumpingToTheEndpoint() {
        val engine = MockLocationEngine(route(10.0), 20.0, true, 2)
        val lapDuration = engine.totalDistance / 20.0
        val firstLap = engine.advance(lapDuration)
        assertEquals(1, firstLap.completedLaps)
        assertFalse(firstLap.completed)
        assertTrue(coordinates.distance(start, firstLap.position) < 1e-6)
        val final = engine.advance(lapDuration)
        assertTrue(final.completed)
        assertEquals(2, final.completedLaps)
        assertEquals(engine.points.first(), final.position)
        assertEquals(0.0, final.speed, 0.0)
        assertEquals(0, final.waypointIndex)
        assertEquals(final, engine.advance(1.0))
    }

    @Test
    fun infiniteLoopCountsSeveralLapsInOneTickAndKeepsTheRemainder() {
        val engine = MockLocationEngine(route(0.5), 100.0, true, 0)
        val delta = (engine.totalDistance * 3.0 + 0.2) / 100.0
        val current = engine.advance(delta)
        assertEquals(3, current.completedLaps)
        assertEquals(0.2, coordinates.distance(start, current.position), 1e-5)
        assertEquals(100.0, current.speed, 0.0)
        assertFalse(current.completed)
    }

    @Test
    fun finiteLoopCapsCompletedLapsWhenOneTickCouldCrossMoreLaps() {
        val engine = MockLocationEngine(route(0.5), 100.0, true, 3)
        val final = engine.advance(1.0)
        assertEquals(3, final.completedLaps)
        assertEquals(engine.points.first(), final.position)
        assertTrue(final.completed)
        assertEquals(0.0, final.speed, 0.0)
    }

    @Test
    fun closingSegmentReportsItsOwnWaypointAndHeading() {
        val east = coordinates.move(start, 10.0, 90.0)
        val north = coordinates.move(east, 10.0, 0.0)
        val engine = MockLocationEngine(listOf(start, east, north), 20.0, true, 0)
        val closingStart = coordinates.distance(start, east) + coordinates.distance(east, north)
        val current = engine.advance((closingStart + 0.5) / 20.0)
        assertEquals(2, current.waypointIndex)
        assertEquals(coordinates.bearing(current.position, start), current.bearing, 1e-4f)
        assertTrue(current.bearing in 180f..270f)
    }

    @Test
    fun duplicatePointsAndExplicitClosureAreNormalizedWithoutChangingTheRoute() {
        val end = coordinates.move(start, 10.0, 90.0)
        val nearStart = coordinates.move(start, 0.05, 90.0)
        val engine = MockLocationEngine(listOf(start, nearStart, end, end, start), 10.0, true, 1)
        assertEquals(listOf(start, end).size, engine.points.size)
        assertEquals(2.0 * coordinates.distance(start, end), engine.totalDistance, 1e-5)
    }

    @Test
    fun allNearDuplicateClosingPointsAreRemoved() {
        val end = coordinates.move(start, 10.0, 90.0)
        val nearEast = coordinates.move(start, 0.08, 90.0)
        val nearWest = coordinates.move(start, 0.08, 270.0)
        val engine = MockLocationEngine(listOf(start, end, nearEast, nearWest), 10.0, true, 1)
        assertEquals(2, engine.points.size)
        assertEquals(2.0 * coordinates.distance(start, end), engine.totalDistance, 1e-5)
    }

    @Test
    fun routeIsCopiedAndCannotBeMutatedAfterStart() {
        val original = route(100.0).toMutableList()
        val engine = MockLocationEngine(original, 10.0, false, 0)
        original.clear()
        assertEquals(2, engine.points.size)
        assertThrows(UnsupportedOperationException::class.java) { (engine.points as MutableList<LatLng>).clear() }
        assertTrue(coordinates.distance(start, engine.advance(1.0).position) > 9.99)
    }

    @Test
    fun zeroLengthRoutesAndTooManyWaypointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(emptyList(), 10.0, false, 0) }
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(listOf(start), 10.0, false, 0) }
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(listOf(start, start), 10.0, false, 0) }
        val near = coordinates.move(start, 0.05, 90.0)
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(listOf(start, near), 10.0, true, 1) }
        val tooMany = List(MockLocationEngine.MAX_WAYPOINTS + 1) { if (it % 2 == 0) start else route(1.0).last() }
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(tooMany, 10.0, false, 0) }
    }

    @Test
    fun invalidSpeedLapCoordinateAndTimeInputsAreRejected() {
        val points = route(100.0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 100.01).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(points, speed, false, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(points, 10.0, true, -1) }
        listOf(LatLng(Double.NaN, 0.0), LatLng(0.0, Double.NEGATIVE_INFINITY),
            LatLng(90.01, 0.0), LatLng(0.0, 180.01)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { MockLocationEngine(listOf(start, invalid), 10.0, false, 0) }
        }
        val engine = MockLocationEngine(points, 10.0, false, 0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1).forEach { delta ->
            assertThrows(IllegalArgumentException::class.java) { engine.advance(delta) }
        }
    }

    @Test
    fun antimeridianRouteDoesNotCrossTheMiddleOfTheWorld() {
        val points = listOf(LatLng(0.0, 179.9999), LatLng(0.0, -179.9999))
        val engine = MockLocationEngine(points, 10.0, false, 0)
        val midpoint = engine.advance(engine.totalDistance / 20.0)
        assertTrue(kotlin.math.abs(midpoint.position.lng) > 179.9999)
        assertEquals(engine.totalDistance / 2.0, coordinates.distance(points.first(), midpoint.position), 1e-5)
        assertEquals(90f, midpoint.bearing, 1e-4f)
        assertFalse(midpoint.completed)
    }

    private fun route(distance: Double): List<LatLng> = listOf(start, coordinates.move(start, distance, 90.0))
}
