package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure coordinate operations. Internal positions use WGS-84; the map uses GCJ-02. */
object MockLocationCoordinates {
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun isValidPosition(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    fun isValidPosition(point: LatLng): Boolean = isValidPosition(point.lat, point.lng)

    /** Returns a finite longitude in [-180, 180), including for repeated globe crossings. */
    fun normalizeLongitude(longitude: Double): Double {
        require(longitude.isFinite()) { "经度必须为有限数值" }
        // Keep already-valid input exact; adding a large offset loses precision near zero.
        if (longitude >= -180.0 && longitude < 180.0) return longitude
        val wrapped = longitude % 360.0
        return when {
            wrapped < -180.0 -> wrapped + 360.0
            wrapped >= 180.0 -> wrapped - 360.0
            else -> wrapped
        }
    }

    fun distance(a: LatLng, b: LatLng): Double {
        requirePosition(a)
        requirePosition(b)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(normalizeLongitude(b.lng - a.lng))
        val h = (sin(dLat / 2).squared() + cos(lat1) * cos(lat2) * sin(dLng / 2).squared())
            .coerceIn(0.0, 1.0)
        // Haversine preserves precision for short segments; vector atan2 avoids cancellation
        // of 1-h at near-antipodal endpoints. Both branches remain finite at the extremes.
        val angle = if (h < 0.5) 2.0 * atan2(sqrt(h), sqrt(1.0 - h)) else {
            val start = vector(a)
            val end = vector(b)
            atan2(start.cross(end).length(), start.dot(end).coerceIn(-1.0, 1.0))
        }
        return EARTH_RADIUS_METERS * angle
    }

    /** Initial bearing in [0, 360). Coincident points use zero; antipodes use a stable path. */
    fun bearing(a: LatLng, b: LatLng): Float {
        requirePosition(a)
        requirePosition(b)
        val start = vector(a)
        val end = vector(b)
        if ((end - start).length() < VECTOR_EPSILON) return 0f
        val tangent = tangentTowards(start, end)
        val lat = Math.toRadians(a.lat)
        val lng = Math.toRadians(a.lng)
        val north = Vector(-sin(lat) * cos(lng), -sin(lat) * sin(lng), cos(lat))
        val east = Vector(-sin(lng), cos(lng), 0.0)
        val degrees = Math.toDegrees(atan2(tangent.dot(east), tangent.dot(north)))
        // Float rounding can turn 359.999999 into 360, so normalize after conversion too.
        return ((degrees + 360.0) % 360.0).toFloat() % 360f
    }

    /** Great-circle movement, with a defined local tangent even when the origin is a pole. */
    fun move(origin: LatLng, distanceMeters: Double, bearingDegrees: Double): LatLng {
        requirePosition(origin)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) { "移动距离必须为有限非负数" }
        require(bearingDegrees.isFinite()) { "方向必须为有限数值" }
        if (distanceMeters == 0.0) return normalized(origin)
        val lat = Math.toRadians(origin.lat)
        val lng = Math.toRadians(origin.lng)
        val direction = Math.toRadians(bearingDegrees % 360.0)
        val north = Vector(-sin(lat) * cos(lng), -sin(lat) * sin(lng), cos(lat))
        val east = Vector(-sin(lng), cos(lng), 0.0)
        val tangent = north * cos(direction) + east * sin(direction)
        val angularDistance = (distanceMeters % (2.0 * PI * EARTH_RADIUS_METERS)) / EARTH_RADIUS_METERS
        return position(vector(origin) * cos(angularDistance) + tangent * sin(angularDistance))
    }

    /** Shortest spherical arc, with a deterministic choice for the ambiguous antipodal arc. */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng {
        requirePosition(a)
        requirePosition(b)
        require(fraction.isFinite() && fraction in 0.0..1.0) { "插值进度必须在 0 到 1 之间" }
        if (fraction == 0.0) return normalized(a)
        if (fraction == 1.0) return normalized(b)
        val start = vector(a)
        val end = vector(b)
        val dot = start.dot(end).coerceIn(-1.0, 1.0)
        val crossLength = start.cross(end).length()
        if (crossLength < VECTOR_EPSILON && dot > 0.0) {
            return position(start * (1.0 - fraction) + end * fraction)
        }
        val angle = atan2(crossLength, dot)
        return position(start * cos(angle * fraction) + tangentTowards(start, end) * sin(angle * fraction))
    }

    fun wgsToGcj(point: LatLng): LatLng = wgsToGcj(point.lat, point.lng)

    fun wgsToGcj(lat: Double, lng: Double): LatLng {
        val point = LatLng(lat, lng)
        requirePosition(point)
        if (!MockLocationGcjCoverage.contains(point)) return point
        val offset = gcjOffset(point)
        return LatLng(lat + offset.lat, lng + offset.lng)
    }

    fun gcjToWgs(point: LatLng): LatLng = gcjToWgs(point.lat, point.lng)

    /**
     * Invert a covered GCJ-02 point; return distant overseas WGS-84 points unchanged.
     *
     * Test the inverse candidate, not the displayed coordinate: a valid forward
     * shift can cross the coverage boundary. Numeric GCJ and unchanged WGS values
     * can overlap near a border; without source provenance this API gives a valid
     * covered GCJ inverse priority. See the bundled coverage README for limits.
     */
    fun gcjToWgs(lat: Double, lng: Double): LatLng {
        val point = LatLng(lat, lng)
        requirePosition(point)
        if (!MockLocationGcjCoverage.mayContainShiftedPoint(point)) return point
        // Invert the smooth offset unconditionally before testing geographic
        // coverage. Calling the conditional forward transform during iteration
        // would bounce across borders and lose hundreds of meters.
        var result = point
        repeat(30) {
            val offset = gcjOffset(result)
            val errorLat = result.lat + offset.lat - lat
            val errorLng = result.lng + offset.lng - lng
            result = LatLng(result.lat - errorLat, result.lng - errorLng)
            if (abs(errorLat) < 1e-10 && abs(errorLng) < 1e-10) {
                return if (MockLocationGcjCoverage.contains(result)) result else point
            }
        }
        // A nonconverged candidate must not silently apply an arbitrary offset.
        return point
    }

    private fun gcjOffset(point: LatLng): LatLng {
        val x = point.lng - 105.0
        val y = point.lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var dLng = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLng += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLng += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        dLng += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val latitudeRadians = Math.toRadians(point.lat)
        val magic = 1.0 - GCJ_ECCENTRICITY_SQUARED * sin(latitudeRadians).squared()
        val rootMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((GCJ_SEMI_MAJOR_AXIS * (1.0 - GCJ_ECCENTRICITY_SQUARED)) / (magic * rootMagic) * PI)
        dLng = dLng * 180.0 / (GCJ_SEMI_MAJOR_AXIS / rootMagic * cos(latitudeRadians) * PI)
        return LatLng(dLat, dLng)
    }

    private fun requirePosition(point: LatLng) {
        require(isValidPosition(point.lat, point.lng)) { "坐标无效" }
    }

    private fun normalized(point: LatLng) = LatLng(point.lat, normalizeLongitude(point.lng))

    private fun vector(point: LatLng): Vector {
        val lat = Math.toRadians(point.lat)
        val lng = Math.toRadians(point.lng)
        return Vector(cos(lat) * cos(lng), cos(lat) * sin(lng), sin(lat))
    }

    private fun position(vector: Vector): LatLng {
        val unit = vector.normalized()
        return LatLng(
            // asin(z) rounds sub-meter movement at a pole back to +/-90 degrees.
            // atan2 retains the small horizontal component of the unit vector.
            Math.toDegrees(atan2(unit.z, hypot(unit.x, unit.y))),
            normalizeLongitude(Math.toDegrees(atan2(unit.y, unit.x)))
        )
    }

    private fun tangentTowards(start: Vector, end: Vector): Vector {
        val cross = start.cross(end)
        if (cross.length() >= VECTOR_EPSILON) return cross.cross(start).normalized()
        // There is no unique shortest arc between antipodes. Choose the least-aligned axis
        // so the tangent stays well-conditioned and repeated ticks follow the same arc.
        val axis = when {
            abs(start.x) <= abs(start.y) && abs(start.x) <= abs(start.z) -> Vector(1.0, 0.0, 0.0)
            abs(start.y) <= abs(start.z) -> Vector(0.0, 1.0, 0.0)
            else -> Vector(0.0, 0.0, 1.0)
        }
        return (axis - start * axis.dot(start)).normalized()
    }

    private data class Vector(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector) = Vector(x - other.x, y - other.y, z - other.z)
        operator fun times(value: Double) = Vector(x * value, y * value, z * value)
        fun dot(other: Vector) = x * other.x + y * other.y + z * other.z
        fun cross(other: Vector) = Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
        fun length() = sqrt(dot(this).coerceAtLeast(0.0))
        fun normalized(): Vector {
            val length = length()
            require(length.isFinite() && length > 0.0) { "无法计算球面坐标" }
            return this * (1.0 / length)
        }
    }

    private fun Double.squared() = this * this
    private const val VECTOR_EPSILON = 1e-12
    private const val GCJ_SEMI_MAJOR_AXIS = 6_378_245.0
    private const val GCJ_ECCENTRICITY_SQUARED = 0.00669342162296594323
}
