package com.ifafu.kyzz.ui.toolbox

import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.service.MockLocationCoordinates
import com.ifafu.kyzz.service.MockLocationEngine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Display-only coordinates may exceed ±180 to keep a route in adjacent Leaflet world copies. */
internal data class MockLocationMapPoint(val lat: Double, val lng: Double)

/** Markers are original waypoints; line samples must never become editable waypoint indices. */
internal data class MockLocationMapGeometry(
    val waypoints: List<MockLocationMapPoint>,
    val path: List<MockLocationMapPoint>,
    val closingPath: List<MockLocationMapPoint>
) {
    val bounds: List<MockLocationMapPoint> get() = path + closingPath
}

internal object MockLocationMapProjection {
    // At most 6,000 intervals plus two extrema per segment and two path start points.
    const val MAX_RENDER_POINTS = 8_192
    private const val MAX_BASE_INTERVALS = 6_000
    private const val TARGET_ANGLE_DEGREES = 1.0
    fun projectPoint(wgsPoint: LatLng): MockLocationMapPoint {
        val gcj = MockLocationCoordinates.wgsToGcj(wgsPoint)
        return MockLocationMapPoint(gcj.lat, gcj.lng)
    }

    fun projectRoute(wgsPoints: List<LatLng>): List<MockLocationMapPoint> {
        var previousLongitude: Double? = null
        return wgsPoints.map { point ->
            val gcj = projectPoint(point)
            val longitude = previousLongitude?.let { closestLongitude(gcj.lng, it) } ?: gcj.lng
            previousLongitude = longitude
            MockLocationMapPoint(gcj.lat, longitude)
        }
    }

    fun closingPoint(route: List<MockLocationMapPoint>): MockLocationMapPoint? {
        if (route.size < 2) return null
        val first = route.first()
        return MockLocationMapPoint(first.lat, closestLongitude(first.lng, route.last().lng))
    }

    /** Uses the engine's exact spherical interpolation; work and bridge payload are bounded. */
    fun geometry(wgsPoints: List<LatLng>, loop: Boolean): MockLocationMapGeometry {
        require(wgsPoints.size <= MockLocationEngine.MAX_WAYPOINTS) { "地图路径点过多" }
        require(wgsPoints.all { MockLocationCoordinates.isValidPosition(it) }) { "地图坐标无效" }
        if (wgsPoints.size < 2) {
            val markers = projectRoute(wgsPoints)
            return MockLocationMapGeometry(markers, markers, emptyList())
        }
        val segments = wgsPoints.zipWithNext() + if (loop) listOf(wgsPoints.last() to wgsPoints.first()) else emptyList()
        val angles = segments.map { (a, b) -> MockLocationCoordinates.distance(a, b) / MockLocationCoordinates.EARTH_RADIUS_METERS }
        // Even interval counts preserve each segment's midpoint after budget reduction.
        val desired = angles.map { maxOf(2, ceil(Math.toDegrees(it) / TARGET_ANGLE_DEGREES / 2).toInt() * 2) }
        val extra = desired.sumOf { it - 2 }
        val available = MAX_BASE_INTERVALS - segments.size * 2
        val scale = if (extra == 0) 1.0 else minOf(1.0, available.toDouble() / extra)
        val intervals = desired.map { 2 + floor((it - 2) * scale / 2).toInt() * 2 }
        val raw = mutableListOf(projectPoint(wgsPoints.first()))
        val markerIndices = mutableListOf(0)
        var rawClosing: List<MockLocationMapPoint> = emptyList()
        segments.forEachIndexed { index, (a, b) ->
            val fractions = (1..intervals[index]).map { it.toDouble() / intervals[index] }.toMutableList()
            fractions.addAll(latitudeExtrema(a, b, angles[index]))
            val samples = fractions.distinct().sorted().map { projectPoint(MockLocationCoordinates.interpolate(a, b, it)) }
            if (index == wgsPoints.lastIndex) {
                rawClosing = listOf(raw.last()) + samples
            } else {
                raw.addAll(samples)
                markerIndices.add(raw.lastIndex)
            }
        }
        val path = unwrap(raw)
        val closing = if (rawClosing.isEmpty()) emptyList() else unwrap(rawClosing, path.last().lng)
        check(path.size + closing.size <= MAX_RENDER_POINTS)
        return MockLocationMapGeometry(markerIndices.map { path[it] }, path, closing)
    }

    /** z(t)=startZ*cos(t)+tangentZ*sin(t); include internal stationary points in bounds. */
    private fun latitudeExtrema(a: LatLng, b: LatLng, angle: Double): List<Double> {
        if (angle < 1e-8) return emptyList()
        val startZ = sin(Math.toRadians(a.lat))
        val midpointZ = sin(Math.toRadians(MockLocationCoordinates.interpolate(a, b, 0.5).lat))
        val tangentZ = (midpointZ - startZ * cos(angle / 2)) / sin(angle / 2)
        val phase = atan2(tangentZ, startZ)
        return (-1..2).map { phase + it * PI }.filter { it > 1e-10 && it < angle - 1e-10 }.map { it / angle }
    }

    private fun unwrap(points: List<MockLocationMapPoint>, reference: Double? = null): List<MockLocationMapPoint> {
        var previous = reference
        return points.map { point ->
            val longitude = previous?.let { closestLongitude(point.lng, it) } ?: point.lng
            previous = longitude
            MockLocationMapPoint(point.lat, longitude)
        }
    }

    private fun closestLongitude(longitude: Double, reference: Double): Double =
        reference + MockLocationCoordinates.normalizeLongitude(longitude - reference)
}
