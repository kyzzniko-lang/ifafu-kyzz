package com.ifafu.kyzz.ui.toolbox

import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.service.MockLocationCoordinates
import com.ifafu.kyzz.service.MockLocationEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MockLocationMapProjectionTest {
    @Test fun shortAntimeridianRouteAndItsClosureStayWithinOneLocalMapView() {
        val route = MockLocationMapProjection.projectRoute(listOf(LatLng(10.0, 179.9), LatLng(10.0, -179.9)))
        assertEquals(179.9, route.first().lng, 1e-10)
        assertEquals(180.1, route.last().lng, 1e-10)
        assertEquals(0.2, route.last().lng - route.first().lng, 1e-10)
        assertEquals(180.0, route.map { it.lng }.average(), 1e-10)
        assertEquals(179.9, MockLocationMapProjection.closingPoint(route)!!.lng, 1e-10)
    }

    @Test fun repeatedWorldCrossingsAndLoopClosureAlwaysChooseAdjacentCopies() {
        val input = listOf(LatLng(0.0, -170.0), LatLng(0.0, 170.0), LatLng(0.0, 0.0), LatLng(0.0, -150.0))
        val route = MockLocationMapProjection.projectRoute(input)
        route.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.lng - b.lng) <= 180.0) }
        val closure = MockLocationMapProjection.closingPoint(route)!!
        assertTrue(abs(route.last().lng - closure.lng) <= 180.0)
        assertEquals(input.first().lng, ((closure.lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0, 1e-10)
        assertEquals(listOf(-170.0, 170.0, 0.0, -150.0), input.map { it.lng })
    }

    @Test fun projectionUsesGcjForChinaAndLeavesNonChinaCoordinatesUntouched() {
        val campus = LatLng(26.0835123456, 119.2389123456)
        val projected = MockLocationMapProjection.projectPoint(campus)
        assertTrue(abs(projected.lat - campus.lat) > 0.0001)
        assertTrue(abs(projected.lng - campus.lng) > 0.0001)
        assertEquals(MockLocationMapPoint(51.5074, -0.1278), MockLocationMapProjection.projectPoint(LatLng(51.5074, -0.1278)))
        assertTrue(MockLocationMapProjection.projectRoute(emptyList()).isEmpty())
        assertNull(MockLocationMapProjection.closingPoint(emptyList()))
    }

    @Test fun longArcDrawsTheEnginesMidpointAndIncludesLatitudeExtremumInBounds() {
        val a = LatLng(30.0, -100.0)
        val b = LatLng(55.0, 30.0)
        val geometry = MockLocationMapProjection.geometry(listOf(a, b), false)
        val midpoint = MockLocationMapProjection.projectPoint(MockLocationCoordinates.interpolate(a, b, 0.5))
        assertTrue(geometry.path.any { abs(it.lat - midpoint.lat) < 1e-9 && abs(it.lng - midpoint.lng) < 1e-9 })
        val observedPeak = (0..10_000).maxOf { MockLocationCoordinates.interpolate(a, b, it / 10_000.0).lat }
        assertTrue(geometry.bounds.maxOf { it.lat } >= observedPeak - 1e-8)
        assertEquals(listOf(MockLocationMapProjection.projectPoint(a), MockLocationMapProjection.projectPoint(b)), geometry.waypoints)
        assertTrue(geometry.path.size > geometry.waypoints.size)
        assertTrue(geometry.closingPath.isEmpty())
    }

    @Test fun maximumGlobalRouteHasBoundedDataAndOnlyOriginalWaypointMarkers() {
        val route = (0 until MockLocationEngine.MAX_WAYPOINTS).map { index ->
            LatLng(if (index % 2 == 0) 60.0 else -55.0, if (index % 2 == 0) -140.0 else 30.0)
        }
        val geometry = MockLocationMapProjection.geometry(route, true)
        assertEquals(route.size, geometry.waypoints.size)
        assertTrue(geometry.path.size + geometry.closingPath.size <= MockLocationMapProjection.MAX_RENDER_POINTS)
        geometry.waypoints.zip(route).forEach { (marker, point) ->
            assertEquals(point.lat, marker.lat, 1e-8)
            assertEquals(point.lng, MockLocationCoordinates.normalizeLongitude(marker.lng), 1e-8)
        }
        geometry.path.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.lng - b.lng) <= 180) }
        geometry.closingPath.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.lng - b.lng) <= 180) }
        assertEquals(geometry.path.last(), geometry.closingPath.first())
    }

    @Test fun loopClosureUsesTheSameSphericalArcAndRemainsLocalAtDateline() {
        val route = listOf(LatLng(60.0, 179.0), LatLng(61.0, -179.0), LatLng(55.0, -177.0))
        val geometry = MockLocationMapProjection.geometry(route, true)
        val midpoint = MockLocationMapProjection.projectPoint(MockLocationCoordinates.interpolate(route.last(), route.first(), 0.5))
        assertTrue(geometry.closingPath.any { abs(it.lat - midpoint.lat) < 1e-8 })
        assertTrue(geometry.bounds.maxOf { it.lng } - geometry.bounds.minOf { it.lng } < 10)
        assertEquals(route.size, geometry.waypoints.size)
    }
}
