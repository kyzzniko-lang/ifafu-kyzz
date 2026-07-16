package com.ifafu.kyzz.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.location.provider.ProviderProperties
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.ui.toolbox.MockLocationActivity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "mock_location"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.ifafu.kyzz.STOP_MOCK_LOCATION"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TRAJECTORY_SPEED = "trajectory_speed"
        const val EXTRA_TRAJECTORY_LOOP = "trajectory_loop"
        const val MODE_SINGLE = "single"
        const val MODE_TRAJECTORY = "trajectory"

        @Volatile
        var isRunning = false
            private set

        // Pending trajectory data (set by activity before service starts)
        @Volatile
        var pendingTrajectory: List<LatLng>? = null

        @Volatile
        var pendingTrajectorySpeed: Double = 1.2

        @Volatile
        var pendingTrajectoryLoop: Boolean = false

        @Volatile
        var pendingTrajectoryLaps: Int = 0

        fun stopService(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var locationThread: HandlerThread
    private lateinit var locationHandler: Handler
    // @Volatile: 这些字段在定位后台线程(updateRunnable)读写，Activity binder 线程也会写，
    // 必须保证可见性，否则后台线程可能读到旧值。
    @Volatile private var latitude: Double = 0.0
    @Volatile private var longitude: Double = 0.0
    private var overlay: MockLocationOverlay? = null

    // Trajectory mode state
    private var isTrajectoryMode = false
    private var trajectoryWaypoints: List<LatLng> = emptyList()
    private var trajectorySpeed: Double = 1.2
    private var elapsedTrajectoryTime = 0.0
    private var totalTrajectoryDistance = 0.0
    private var currentWaypointIndex = 0
    private var isLoopMode = false
    private var loopCloseDistance = 0.0
    private var trajectoryTargetLaps = 0
    private var completedLaps = 0
    private var cumulativeTrajectoryDistance = 0.0
    private var currentActualSpeed = 0.0

    // Realism: drift noise, bearing, accuracy jitter
    private val random = Random()
    private var driftLat = 0.0
    private var driftLng = 0.0
    private var currentBearing = 0f
    private var currentAccuracy = 12f

    val binder = MockLocationBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 从持久化恢复上次坐标，作为初始兜底值。
        // 若系统因 START_NOT_STICKY 外的原因重建服务（极端情况），避免坐标塌缩到 (0,0)。
        try {
            val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
            val lastLat = prefs.getFloat("mock_location_lat", Float.NaN)
            val lastLng = prefs.getFloat("mock_location_lng", Float.NaN)
            if (!lastLat.isNaN() && !lastLng.isNaN()) {
                latitude = lastLat.toDouble()
                longitude = lastLng.toDouble()
            }
        } catch (_: Exception) {}

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        removeTestProviderNetwork()
        addTestProviderNetwork()
        removeTestProviderGPS()
        addTestProviderGPS()

        initLocationHandler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        if (mode == MODE_TRAJECTORY) {
            // Read pending trajectory set by activity
            val wp = pendingTrajectory
            if (wp != null && wp.size >= 2) {
                isTrajectoryMode = true
                trajectoryWaypoints = wp
                trajectorySpeed = intent?.getDoubleExtra(EXTRA_TRAJECTORY_SPEED, 1.2) ?: 1.2
                elapsedTrajectoryTime = 0.0
                currentWaypointIndex = 0
                isLoopMode = pendingTrajectoryLoop
                trajectoryTargetLaps = if (isLoopMode) pendingTrajectoryLaps else 0
                completedLaps = 0
                cumulativeTrajectoryDistance = 0.0
                currentActualSpeed = trajectorySpeed
                driftLat = 0.0; driftLng = 0.0
                currentBearing = 0f
                currentAccuracy = 8f + random.nextFloat() * 12f

                // Start at first waypoint
                val first = wp.first()
                latitude = first.lat
                longitude = first.lng
                val baseDist = calculateTotalDistance(wp)
                loopCloseDistance = if (isLoopMode) haversineDistance(wp.last(), wp.first()) else 0.0
                totalTrajectoryDistance = baseDist + loopCloseDistance

                // Clear pending so service restart doesn't re-use stale data
                pendingTrajectory = null
            } else {
                isTrajectoryMode = false
                latitude = intent?.getDoubleExtra(EXTRA_LATITUDE, latitude) ?: latitude
                longitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, longitude) ?: longitude
            }
        } else {
            isTrajectoryMode = false
            trajectoryWaypoints = emptyList()
            latitude = intent?.getDoubleExtra(EXTRA_LATITUDE, latitude) ?: latitude
            longitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, longitude) ?: longitude
        }

        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putFloat("mock_location_lat", latitude.toFloat())
            .putFloat("mock_location_lng", longitude.toFloat())
            .putString("mock_location_mode", mode)
            .apply()

        updateNotification(latitude, longitude)
        try {
            showOverlay()
        } catch (e: Exception) {
            android.util.Log.w("MockLocation", "Overlay failed, service continues without it", e)
        }
        isRunning = true

        // 模拟定位不应被系统自动重启：若返回 START_STICKY，系统重建时 intent 为 null，
        // 会用 onCreate 恢复的坐标或初始值继续注入定位，可能导致用户不知情下持续生效。
        // 改为 NOT_STICKY：服务被杀后不再重建，用户需要时手动重启。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isTrajectoryMode = false
        trajectoryWaypoints = emptyList()
        pendingTrajectory = null
        removeOverlay()
        if (::locationThread.isInitialized) {
            locationHandler.removeCallbacksAndMessages(null)
            locationThread.quitSafely()
        }

        removeTestProviderNetwork()
        removeTestProviderGPS()

        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putBoolean("mock_location_running", false)
            .apply()

        super.onDestroy()
    }

    fun stopTrajectory() {
        isTrajectoryMode = false
        trajectoryWaypoints = emptyList()
    }

    // ==================== Location Handler ====================

    private fun initLocationHandler() {
        locationThread = HandlerThread("MockLocationThread", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        locationHandler = Handler(locationThread.looper)
        val updateRunnable = object : Runnable {
            override fun run() {
                try {
                    if (isTrajectoryMode) {
                        advanceTrajectory(0.1) // 100ms nominal
                    }
                    setLocationGPS()
                    setLocationNetwork()
                } catch (_: Exception) {}
                // Randomize interval by ±30ms so it's not perfectly regular
                val jitter = random.nextInt(61) - 30 // -30 ~ +30 ms（nextInt 无负数符号问题）
                locationHandler.postDelayed(this, (100L + jitter).coerceAtLeast(50L))
            }
        }
        locationHandler.post(updateRunnable)
    }

    // ==================== Trajectory Following ====================

    private fun advanceTrajectory(deltaSeconds: Double) {
        if (trajectoryWaypoints.size < 2 || totalTrajectoryDistance <= 0) return

        elapsedTrajectoryTime += deltaSeconds
        currentActualSpeed = calculateFluctuatingSpeed()
        cumulativeTrajectoryDistance += currentActualSpeed * deltaSeconds

        if (isLoopMode) {
            val newCompleted = (cumulativeTrajectoryDistance / totalTrajectoryDistance).toInt()
            if (newCompleted > completedLaps) {
                completedLaps = newCompleted
                if (trajectoryTargetLaps > 0 && completedLaps >= trajectoryTargetLaps) {
                    val first = trajectoryWaypoints.first()
                    latitude = first.lat
                    longitude = first.lng
                    currentWaypointIndex = 0
                    isLoopMode = false
                    updateCoordsDisplay()
                    return
                }
            }
            val pos = interpolatePosition(cumulativeTrajectoryDistance % totalTrajectoryDistance)
            latitude = pos.first
            longitude = pos.second
        } else {
            val pos = interpolatePosition(cumulativeTrajectoryDistance.coerceAtMost(totalTrajectoryDistance))
            latitude = pos.first
            longitude = pos.second
        }
        updateCoordsDisplay()
    }

    /** Smooth sinusoidal speed fluctuation around base speed for natural feel. */
    private fun calculateFluctuatingSpeed(): Double {
        // Two sine waves at different frequencies for organic variation (±~12%)
        val wave = sin(elapsedTrajectoryTime * 1.7) * 0.08 +
                   sin(elapsedTrajectoryTime * 3.2) * 0.04
        return (trajectorySpeed * (1.0 + wave)).coerceAtLeast(trajectorySpeed * 0.5)
    }

    private fun interpolatePosition(distance: Double): Pair<Double, Double> {
        var accumulated = 0.0
        for (i in 0 until trajectoryWaypoints.size - 1) {
            val p0 = trajectoryWaypoints[i]
            val p1 = trajectoryWaypoints[i + 1]
            val segDist = haversineDistance(p0, p1)
            if (distance <= accumulated + segDist + 1e-10) {
                val segProgress = ((distance - accumulated) / segDist).coerceIn(0.0, 1.0)
                currentWaypointIndex = i
                return Pair(
                    p0.lat + (p1.lat - p0.lat) * segProgress,
                    p0.lng + (p1.lng - p0.lng) * segProgress
                )
            }
            accumulated += segDist
        }
        // Closing segment (loop back to start)
        if (isLoopMode && loopCloseDistance > 0) {
            val remaining = (distance - accumulated).coerceAtLeast(0.0)
            val segProgress = (remaining / loopCloseDistance).coerceIn(0.0, 1.0)
            val last = trajectoryWaypoints.last()
            val first = trajectoryWaypoints.first()
            currentWaypointIndex = trajectoryWaypoints.size - 1
            return Pair(
                last.lat + (first.lat - last.lat) * segProgress,
                last.lng + (first.lng - last.lng) * segProgress
            )
        }
        // End of trajectory — last waypoint
        val last = trajectoryWaypoints.last()
        currentWaypointIndex = trajectoryWaypoints.size - 1
        return Pair(last.lat, last.lng)
    }

    private fun updateCoordsDisplay() {
        val hasTrajectory = trajectoryWaypoints.size >= 2
        val progress = if (hasTrajectory && currentWaypointIndex < trajectoryWaypoints.size) {
            "${currentWaypointIndex + 1}/${trajectoryWaypoints.size}"
        } else null

        // Show noisy (realistic) position on overlay
        val displayLat = latitude + driftLat
        val displayLng = longitude + driftLng
        overlay?.updateCoords(displayLat, displayLng)
        overlay?.updateTrajectoryProgress(progress, currentActualSpeed, isTrajectoryMode, isLoopMode, completedLaps, trajectoryTargetLaps)
        updateNotification(displayLat, displayLng)
    }

    private fun haversineDistance(p1: LatLng, p2: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = sin(dLat / 2).pow2() +
                cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) * sin(dLng / 2).pow2()
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun calculateTotalDistance(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += haversineDistance(points[i], points[i + 1])
        }
        return total
    }

    private fun Double.pow2() = this * this

    // ==================== GPS Provider ====================

    @SuppressLint("wrongconstant")
    private fun addTestProviderGPS() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true,
                    ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE
                )
            } else {
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true,
                    Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
                )
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            }
            // Mark provider as available so apps see it as working GPS
            locationManager.setTestProviderStatus(
                LocationManager.GPS_PROVIDER, LocationProvider.AVAILABLE, null, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            android.util.Log.e("MockLocation", "addTestProviderGPS failed", e)
        }
    }

    private fun removeTestProviderGPS() {
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        } catch (_: Exception) {}
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
    }

    private fun setLocationGPS() {
        try {
            // Apply GPS random drift
            updateDrift()
            val reportLat = latitude + driftLat
            val reportLng = longitude + driftLng

            // Update bearing from trajectory forward direction (not noisy positions)
            if (isTrajectoryMode) {
                currentBearing = calculateTrajectoryBearing()
            }

            // Slowly fluctuate accuracy
            currentAccuracy += (random.nextFloat() - 0.5f) * 2f
            currentAccuracy = currentAccuracy.coerceIn(5f, 25f)

            val timeJitter = random.nextInt(21) - 10 // ±10ms
            val nanoJitter = timeJitter * 1_000_000L

            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = reportLat
                longitude = reportLng
                altitude = 20.0 + (random.nextDouble() - 0.5) * 5.0
                accuracy = currentAccuracy
                bearing = currentBearing
                speed = if (isTrajectoryMode) currentActualSpeed.toFloat() else 0f
                time = System.currentTimeMillis() + timeJitter
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + nanoJitter
                extras = Bundle().apply {
                    putInt("satellites", (5 + random.nextInt(4))) // 5-8 satellites
                }
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: Exception) {
            if (!gpsLocationFailed) {
                gpsLocationFailed = true
                android.util.Log.e("MockLocation", "setLocationGPS failed", e)
            }
        }
    }

    private var gpsLocationFailed = false

    private fun updateDrift() {
        // Random walk with spring-back for smooth natural jitter (~±8m radius)
        driftLat += (random.nextDouble() - 0.5) * 0.000025
        driftLng += (random.nextDouble() - 0.5) * 0.000025
        driftLat *= 0.97
        driftLng *= 0.97
        val maxDrift = 0.00008 // ~8m
        driftLat = driftLat.coerceIn(-maxDrift, maxDrift)
        driftLng = driftLng.coerceIn(-maxDrift, maxDrift)
    }

    /** Bearing from the current trajectory segment, with ±60° smooth noise. */
    private fun calculateTrajectoryBearing(): Float {
        if (trajectoryWaypoints.size < 2) return currentBearing
        val idx = currentWaypointIndex.coerceIn(0, trajectoryWaypoints.size - 2)
        val p0 = trajectoryWaypoints[idx]
        val p1 = trajectoryWaypoints[idx + 1]

        val dLng = Math.toRadians(p1.lng - p0.lng)
        val y = sin(dLng) * cos(Math.toRadians(p1.lat))
        val x = cos(Math.toRadians(p0.lat)) * sin(Math.toRadians(p1.lat)) -
                sin(Math.toRadians(p0.lat)) * cos(Math.toRadians(p1.lat)) * cos(dLng)
        val trueBearing = ((Math.toDegrees(atan2(y, x))).toFloat() + 360) % 360

        // Smooth variation within ±60° so arrow wobbles naturally but never points backward
        val variation = sin(elapsedTrajectoryTime * 0.7) * 60.0
        return ((trueBearing + variation).toFloat() + 360) % 360
    }

    /** Pure bearing between two points (used by calculateTrajectoryBearing, kept for reference). */
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        if (lat1 == lat2 && lng1 == lng2) return currentBearing
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x))).toFloat() + 360) % 360
    }

    // ==================== Network Provider ====================

    @SuppressLint("wrongconstant")
    private fun addTestProviderNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE
                )
            } else {
                locationManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_COARSE
                )
            }
            if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            }
            locationManager.setTestProviderStatus(
                LocationManager.NETWORK_PROVIDER, LocationProvider.AVAILABLE, null, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            android.util.Log.e("MockLocation", "addTestProviderNetwork failed", e)
        }
    }

    private fun removeTestProviderNetwork() {
        try {
            locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        } catch (_: Exception) {}
        try {
            locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {}
    }

    private fun setLocationNetwork() {
        try {
            // Network provider uses same position but worse/noisy accuracy
            val reportLat = latitude + driftLat
            val reportLng = longitude + driftLng
            val netAccuracy = currentAccuracy * (2f + random.nextFloat()) // 2-3x GPS error

            val timeJitter = random.nextInt(31) - 15 // ±15ms (more than GPS)
            val nanoJitter = timeJitter * 1_000_000L

            val loc = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = reportLat
                longitude = reportLng
                altitude = 20.0 + (random.nextDouble() - 0.5) * 10.0
                accuracy = netAccuracy
                bearing = currentBearing
                speed = if (isTrajectoryMode) currentActualSpeed.toFloat() else 0f
                time = System.currentTimeMillis() + timeJitter
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + nanoJitter
            }
            locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc)
        } catch (e: Exception) {
            if (!networkLocationFailed) {
                networkLocationFailed = true
                android.util.Log.e("MockLocation", "setLocationNetwork failed", e)
            }
        }
    }

    private var networkLocationFailed = false

    // ==================== Overlay ====================

    private fun showOverlay() {
        try {
            overlay = MockLocationOverlay(this)
            overlay?.show(latitude, longitude)
            overlay?.setPositionListener { lat, lng ->
                latitude = lat
                longitude = lng
                updateNotification(lat, lng)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        try {
            overlay?.dismiss()
            overlay = null
        } catch (_: Exception) {}
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟定位", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "虚拟定位运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(lat: Double, lng: Double) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(lat, lng))
        } catch (_: Exception) {}
    }

    private fun buildNotification(lat: Double = latitude, lng: Double = longitude): Notification {
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MockLocationActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedStr = "${"%.1f".format(currentActualSpeed)}m/s"
        val contentText = if (isTrajectoryMode && isLoopMode) {
            // coerceInSafe: 避免 trajectoryWaypoints 为空时 coerceIn(0, -1) 抛 IllegalArgumentException
            val maxIdx = (trajectoryWaypoints.size - 1).coerceAtLeast(0)
            val wps = "${currentWaypointIndex.coerceIn(0, maxIdx) + 1}/${trajectoryWaypoints.size}"
            val lapInfo = if (trajectoryTargetLaps > 0) "圈: ${completedLaps + 1}/${trajectoryTargetLaps}" else "圈: ${completedLaps + 1}/∞"
            "校园跑 $wps | $lapInfo | $speedStr"
        } else if (isTrajectoryMode) {
            val progress = if (trajectoryWaypoints.size >= 2) {
                val maxIdx = trajectoryWaypoints.size - 1
                "${currentWaypointIndex.coerceIn(0, maxIdx) + 1}/${trajectoryWaypoints.size}"
            } else ""
            "轨迹: $progress | $speedStr | ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}"
        } else {
            "纬度: ${String.format("%.6f", lat)}, 经度: ${String.format("%.6f", lng)}"
        }

        val title = if (isTrajectoryMode && isLoopMode) "校园跑模拟运行中"
            else if (isTrajectoryMode) "轨迹模拟运行中"
            else "虚拟定位运行中"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.mipmap.ic_launcher, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== Binder ====================

    inner class MockLocationBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
        fun setPosition(lat: Double, lng: Double) {
            latitude = lat
            longitude = lng
        }
    }
}
