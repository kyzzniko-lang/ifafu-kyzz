package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng
import java.io.DataInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline geographic approximation of the map's GCJ-02 conversion area.
 *
 * Natural Earth v5.1.1 CHN/HKG/MAC polygons, public domain. Source, limitations
 * and the reproducible generator are documented in gcj_coverage_README.txt.
 * This boundary belongs only to the coordinate policy; it is not a displayed
 * boundary, nor an assertion that the map vendor uses this exact coastline.
 */
internal object MockLocationGcjCoverage {
    private val polygons: List<Polygon> by lazy { loadPolygons() }

    fun contains(point: LatLng): Boolean = polygons.any { it.contains(point.lng, point.lat) }

    /** Only a cost/finite-arithmetic guard; actual applicability always uses polygons. */
    fun mayContainShiftedPoint(point: LatLng): Boolean = polygons.any {
        it.outer.boundsContain(point.lng * SCALE, point.lat * SCALE, INVERSE_SEARCH_MARGIN)
    }

    private fun loadPolygons(): List<Polygon> {
        // Java resources are packaged at the APK root by the Android Gradle plugin.
        // ClassLoader access also keeps coordinate tests independent of Android Context.
        val stream = checkNotNull(javaClass.getResourceAsStream("/gcj_coverage.bin")) {
            "Bundled GCJ geographic coverage is missing"
        }
        return DataInputStream(stream.buffered()).use { input ->
            check(input.readInt() == 0x4B47434A && input.readInt() == 1) { "Invalid GCJ coverage format" }
            val polygonCount = input.readInt()
            check(polygonCount in 1..MAX_POLYGONS) { "Invalid GCJ coverage polygon count" }
            var vertices = 0
            val result = List(polygonCount) {
                val ringCount = input.readInt()
                check(ringCount in 1..MAX_RINGS_PER_POLYGON) { "Invalid GCJ coverage ring count" }
                val rings = List(ringCount) {
                    val count = input.readInt()
                    check(count >= 3 && count <= MAX_VERTICES - vertices) { "Invalid GCJ coverage vertex count" }
                    vertices += count
                    val points = IntArray(count * 2) { index ->
                        input.readInt().also { coordinate ->
                            val limit = if (index % 2 == 0) 180_000_000 else 90_000_000
                            check(coordinate in -limit..limit) { "Invalid GCJ coverage coordinate" }
                        }
                    }
                    Ring(points)
                }
                Polygon(rings.first(), rings.drop(1))
            }
            check(input.read() == -1) { "Unexpected GCJ coverage trailing data" }
            result
        }
    }

    private class Polygon(val outer: Ring, private val holes: List<Ring>) {
        fun contains(lng: Double, lat: Double): Boolean =
            outer.contains(lng * SCALE, lat * SCALE) && holes.none { it.contains(lng * SCALE, lat * SCALE) }
    }

    private class Ring(private val coordinates: IntArray) {
        private val count = coordinates.size / 2
        private val minLng = (0 until count).minOf { coordinates[it * 2] }
        private val maxLng = (0 until count).maxOf { coordinates[it * 2] }
        private val minLat = (0 until count).minOf { coordinates[it * 2 + 1] }
        private val maxLat = (0 until count).maxOf { coordinates[it * 2 + 1] }

        // Ray casting needs only edges whose latitude interval meets this row.
        // A sampled long route therefore does not scan 14,000 edges per point.
        private val latitudeRows: Array<IntArray> = run {
            val rows = Array((maxLat - minLat) / ROW_HEIGHT + 1) { mutableListOf<Int>() }
            for (edge in 0 until count) {
                val next = (edge + 1) % count
                // Keep neighboring rows too: an inverse candidate may differ from
                // an exact row-boundary vertex by a few floating point ulps.
                val low = ((min(coordinates[edge * 2 + 1], coordinates[next * 2 + 1]) - minLat) / ROW_HEIGHT - 1)
                    .coerceAtLeast(0)
                val high = ((max(coordinates[edge * 2 + 1], coordinates[next * 2 + 1]) - minLat) / ROW_HEIGHT + 1)
                    .coerceAtMost(rows.lastIndex)
                for (row in low..high) rows[row].add(edge)
            }
            Array(rows.size) { rows[it].toIntArray() }
        }

        fun boundsContain(x: Double, y: Double, margin: Double = EDGE_TOLERANCE): Boolean =
            x >= minLng - margin && x <= maxLng + margin && y >= minLat - margin && y <= maxLat + margin

        fun contains(x: Double, y: Double): Boolean {
            if (!boundsContain(x, y)) return false
            val row = ((y - minLat) / ROW_HEIGHT).toInt().coerceIn(latitudeRows.indices)
            var inside = false
            for (edge in latitudeRows[row]) {
                val next = (edge + 1) % count
                val ax = coordinates[edge * 2].toDouble()
                val ay = coordinates[edge * 2 + 1].toDouble()
                val bx = coordinates[next * 2].toDouble()
                val by = coordinates[next * 2 + 1].toDouble()
                // Include the boundary. The tiny tolerance covers inverse floating
                // point roundoff; it does not expand the geographic area by meters.
                if (x >= min(ax, bx) - EDGE_TOLERANCE && x <= max(ax, bx) + EDGE_TOLERANCE &&
                    y >= min(ay, by) - EDGE_TOLERANCE && y <= max(ay, by) + EDGE_TOLERANCE &&
                    abs((x - ax) * (by - ay) - (y - ay) * (bx - ax)) <= EDGE_TOLERANCE * hypot(bx - ax, by - ay)
                ) return true
                if ((ay > y) != (by > y) && x < ax + (y - ay) * (bx - ax) / (by - ay)) inside = !inside
            }
            return inside
        }
    }

    private const val SCALE = 1_000_000.0
    private const val ROW_HEIGHT = 500_000
    private const val EDGE_TOLERANCE = 0.001 // 1e-9 degrees, about 0.1 millimeter.
    private const val INVERSE_SEARCH_MARGIN = 100_000.0 // 0.1 degree, well beyond GCJ offsets.
    private const val MAX_POLYGONS = 1_000
    private const val MAX_RINGS_PER_POLYGON = 100
    private const val MAX_VERTICES = 200_000
}
