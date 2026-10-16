package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import java.util.Collections
import kotlin.math.floor

data class EnginePosition(
    val position: LatLng,
    val speed: Double,
    val bearing: Float,
    val waypointIndex: Int,
    val completedLaps: Int,
    val completed: Boolean
)

/** A deterministic trajectory; Android scheduling and provider injection stay in the service. */
class MockLocationEngine(
    points: List<LatLng>,
    private val speedMetersPerSecond: Double,
    private val loop: Boolean,
    private val targetLaps: Int
) {
    companion object {
        const val MAX_WAYPOINTS = 1000
        const val MIN_DISTANCE_METERS = 0.1
        const val MAX_SPEED_METERS_PER_SECOND = 100.0
        const val MAX_DELTA_SECONDS = 2.0
    }

    /** Defensive immutable copy, without adjacent near-duplicate points or redundant closure. */
    val points: List<LatLng>
    val totalDistance: Double

    private data class Segment(val start: LatLng, val end: LatLng, val startIndex: Int, val distance: Double)
    private val segments: List<Segment>
    private val segmentEnds: DoubleArray
    private val boundaryTolerance: Double
    private var distanceInRoute = 0.0
    private var laps = 0L
    @Volatile private var finished = false
    val completed: Boolean get() = finished

    init {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond > 0.0 &&
            speedMetersPerSecond <= MAX_SPEED_METERS_PER_SECOND) { "轨迹速度必须大于 0 且不超过 100 米/秒" }
        require(targetLaps >= 0) { "圈数不能为负数" }
        require(points.size in 2..MAX_WAYPOINTS) { "路线需要 2 到 $MAX_WAYPOINTS 个航点" }
        val normalized = ArrayList<LatLng>(points.size)
        points.forEach { point ->
            require(MockLocationCoordinates.isValidPosition(point.lat, point.lng)) { "路线包含无效坐标" }
            val value = LatLng(point.lat, MockLocationCoordinates.normalizeLongitude(point.lng))
            if (normalized.isEmpty() || MockLocationCoordinates.distance(normalized.last(), value) >= MIN_DISTANCE_METERS) {
                normalized.add(value)
            }
        }
        while (loop && normalized.size > 1 &&
            MockLocationCoordinates.distance(normalized.last(), normalized.first()) < MIN_DISTANCE_METERS) {
            normalized.removeAt(normalized.lastIndex)
        }
        require(normalized.size >= 2) { "路线需要至少两个相距 0.1 米以上的有效航点" }
        this.points = Collections.unmodifiableList(normalized)
        val routeSegments = ArrayList<Segment>(normalized.size)
        for (index in 0 until normalized.lastIndex) {
            val start = normalized[index]
            val end = normalized[index + 1]
            routeSegments.add(Segment(start, end, index, MockLocationCoordinates.distance(start, end)))
        }
        if (loop) {
            routeSegments.add(Segment(normalized.last(), normalized.first(), normalized.lastIndex,
                MockLocationCoordinates.distance(normalized.last(), normalized.first())))
        }
        segments = routeSegments
        segmentEnds = DoubleArray(segments.size)
        var distance = 0.0
        segments.forEachIndexed { index, segment ->
            require(segment.distance.isFinite() && segment.distance >= MIN_DISTANCE_METERS) { "路线包含无效路段" }
            distance += segment.distance
            segmentEnds[index] = distance
        }
        require(distance.isFinite() && distance >= MIN_DISTANCE_METERS) { "路线总距离无效" }
        totalDistance = distance
        boundaryTolerance = maxOf(1e-9, Math.ulp(totalDistance) * 8.0)
    }

    /**
     * Advance by measured monotonic time. A gap over two seconds pauses the route for that
     * tick rather than teleporting after background suspension; the caller must reset its clock.
     */
    @Synchronized
    fun advance(deltaSeconds: Double): EnginePosition {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0.0) { "时间间隔必须为有限非负数" }
        if (finished || deltaSeconds == 0.0 || deltaSeconds > MAX_DELTA_SECONDS) return current()
        val nextDistance = distanceInRoute + speedMetersPerSecond * deltaSeconds
        if (!loop) {
            if (nextDistance >= totalDistance - boundaryTolerance) {
                distanceInRoute = totalDistance
                finished = true
            } else {
                distanceInRoute = nextDistance
            }
        } else {
            val remainingLaps = targetLaps.toLong() - laps
            if (targetLaps > 0 && nextDistance >= remainingLaps * totalDistance - boundaryTolerance) {
                laps = targetLaps.toLong()
                distanceInRoute = 0.0
                finished = true
            } else {
                val crossedLaps = floor((nextDistance + boundaryTolerance) / totalDistance).toLong()
                distanceInRoute = (nextDistance - crossedLaps * totalDistance).coerceAtLeast(0.0)
                laps = if (Long.MAX_VALUE - laps < crossedLaps) Long.MAX_VALUE else laps + crossedLaps
            }
        }
        return current()
    }

    @Synchronized
    fun current(): EnginePosition {
        if (finished) {
            val endpoint = if (loop) points.first() else points.last()
            val lastSegment = segments.last()
            return EnginePosition(endpoint, 0.0,
                MockLocationCoordinates.bearing(lastSegment.start, lastSegment.end),
                if (loop) 0 else points.lastIndex, visibleLaps(), true)
        }
        // First segment whose endpoint is after the traveled distance. At an exact waypoint
        // the next segment supplies the heading, including the last-to-first closing segment.
        var low = 0
        var high = segmentEnds.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (segmentEnds[mid] <= distanceInRoute) low = mid + 1 else high = mid
        }
        val segment = segments[low]
        val startDistance = if (low == 0) 0.0 else segmentEnds[low - 1]
        val fraction = ((distanceInRoute - startDistance) / segment.distance).coerceIn(0.0, 1.0)
        val position = MockLocationCoordinates.interpolate(segment.start, segment.end, fraction)
        return EnginePosition(position, speedMetersPerSecond,
            MockLocationCoordinates.bearing(position, segment.end), segment.startIndex, visibleLaps(), false)
    }

    private fun visibleLaps(): Int = laps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
