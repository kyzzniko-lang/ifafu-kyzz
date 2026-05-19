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
import com.ifafu.kyzz.ui.toolbox.MockLocationActivity

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "mock_location"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.ifafu.kyzz.STOP_MOCK_LOCATION"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"

        @Volatile
        var isRunning = false
            private set

        fun stopService(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }

    private lateinit var locationManager: LocationManager
    private lateinit var locationThread: HandlerThread
    private lateinit var locationHandler: Handler
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var overlay: MockLocationOverlay? = null

    private val binder = MockLocationBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

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

        latitude = intent?.getDoubleExtra(EXTRA_LATITUDE, latitude) ?: latitude
        longitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, longitude) ?: longitude

        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putFloat("mock_location_lat", latitude.toFloat())
            .putFloat("mock_location_lng", longitude.toFloat())
            .apply()

        updateNotification(latitude, longitude)
        showOverlay()
        isRunning = true
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putBoolean("mock_location_running", true)
            .apply()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        removeOverlay()
        locationHandler.removeCallbacksAndMessages(null)
        locationThread.quitSafely()

        removeTestProviderNetwork()
        removeTestProviderGPS()

        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putBoolean("mock_location_running", false)
            .apply()

        super.onDestroy()
    }

    // ==================== Location Handler ====================

    private fun initLocationHandler() {
        locationThread = HandlerThread("MockLocationThread", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        locationHandler = Handler(locationThread.looper)
        val updateRunnable = object : Runnable {
            override fun run() {
                try {
                    setLocationGPS()
                    setLocationNetwork()
                } catch (_: Exception) {}
                locationHandler.postDelayed(this, 100)
            }
        }
        locationHandler.post(updateRunnable)
    }

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeTestProviderGPS() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        } catch (_: Exception) {}
    }

    private fun setLocationGPS() {
        try {
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = this@MockLocationService.latitude
                longitude = this@MockLocationService.longitude
                altitude = 20.0
                accuracy = Criteria.ACCURACY_FINE.toFloat()
                bearing = 0f
                speed = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = Bundle().apply { putInt("satellites", 7) }
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }

    private fun removeTestProviderNetwork() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {}
    }

    private fun setLocationNetwork() {
        try {
            val loc = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = this@MockLocationService.latitude
                longitude = this@MockLocationService.longitude
                altitude = 20.0
                accuracy = Criteria.ACCURACY_COARSE.toFloat()
                bearing = 0f
                speed = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc)
        } catch (_: Exception) {}
    }

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("虚拟定位运行中")
            .setContentText("纬度: ${String.format("%.6f", lat)}, 经度: ${String.format("%.6f", lng)}")
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
