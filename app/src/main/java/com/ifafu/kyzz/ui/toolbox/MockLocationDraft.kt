package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import com.ifafu.kyzz.data.model.LatLng

/** Saves the complete editable draft, including unfinished text, without float precision loss. */
internal data class MockLocationDraft(
    val route: List<LatLng> = emptyList(),
    val latitude: String = "",
    val longitude: String = "",
    val speed: String = "1.2",
    val laps: String = "0",
    val trajectory: Boolean = false,
    val loop: Boolean = false
) {
    fun write(bundle: Bundle) {
        bundle.putDoubleArray("mock_draft_route", route.flatMap { listOf(it.lat, it.lng) }.toDoubleArray())
        bundle.putString("mock_draft_lat", latitude)
        bundle.putString("mock_draft_lng", longitude)
        bundle.putString("mock_draft_speed", speed)
        bundle.putString("mock_draft_laps", laps)
        bundle.putBoolean("mock_draft_trajectory", trajectory)
        bundle.putBoolean("mock_draft_loop", loop)
    }
    companion object {
        fun read(bundle: Bundle): MockLocationDraft {
            val coordinates = bundle.getDoubleArray("mock_draft_route") ?: doubleArrayOf()
            val route = (0 until coordinates.size / 2).map { LatLng(coordinates[it * 2], coordinates[it * 2 + 1]) }
            return MockLocationDraft(route, bundle.getString("mock_draft_lat") ?: "", bundle.getString("mock_draft_lng") ?: "",
                bundle.getString("mock_draft_speed") ?: "1.2", bundle.getString("mock_draft_laps") ?: "0",
                bundle.getBoolean("mock_draft_trajectory"), bundle.getBoolean("mock_draft_loop"))
        }
    }
}
