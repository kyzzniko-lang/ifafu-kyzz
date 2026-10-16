package com.ifafu.kyzz.service

import com.ifafu.kyzz.data.model.LatLng

enum class MockLocationStatus { STOPPED, STARTING, RECOVERING, RUNNING, DEGRADED, FAILED }

/** Immutable state shared by the service, notification and every open control page. */
data class MockLocationSnapshot(
    val status: MockLocationStatus = MockLocationStatus.STOPPED,
    val position: LatLng? = null,
    val mode: String = "single",
    val route: List<LatLng> = emptyList(),
    val configuredSpeed: Double = 1.2,
    val speedMetersPerSecond: Double = 0.0,
    val loop: Boolean = false,
    val targetLaps: Int = 0,
    val completedLaps: Int = 0,
    val waypointIndex: Int = 0,
    val trajectoryCompleted: Boolean = false,
    val message: String? = null,
    /** A durable cleanup obligation, including when there is no live Service or process session. */
    val cleanupPending: Boolean = false
) {
    val isRunning: Boolean
        get() = status == MockLocationStatus.RUNNING || status == MockLocationStatus.DEGRADED
    val isBusy: Boolean
        get() = status == MockLocationStatus.STARTING || status == MockLocationStatus.RECOVERING || isRunning
}
