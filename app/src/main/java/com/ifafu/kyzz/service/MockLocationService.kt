package com.ifafu.kyzz.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.LatLng
import com.ifafu.kyzz.ui.toolbox.MockLocationActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class MockLocationService : Service() {
    companion object {
        const val CHANNEL_ID = "mock_location"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.ifafu.kyzz.STOP_MOCK_LOCATION"
        const val ACTION_RECOVER = "com.ifafu.kyzz.RECOVER_REAL_LOCATION"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TRAJECTORY_SPEED = "trajectory_speed"
        const val EXTRA_TRAJECTORY_LOOP = "trajectory_loop"
        const val EXTRA_TRAJECTORY_LAPS = "trajectory_laps"
        const val EXTRA_TRAJECTORY_LATITUDES = "trajectory_latitudes"
        const val EXTRA_TRAJECTORY_LONGITUDES = "trajectory_longitudes"
        const val MODE_SINGLE = "single"
        const val MODE_TRAJECTORY = "trajectory"
        private const val TAG = "MockLocation"
        private val stateGate = Any()
        private val mutableState = MutableStateFlow(MockLocationSnapshot())
        val state: StateFlow<MockLocationSnapshot> = mutableState.asStateFlow()
        val isRunning: Boolean get() = state.value.isRunning

        /** Reserve startup across all pages before submitting an asynchronous service command. */
        fun prepareStart(): Boolean = synchronized(stateGate) {
            if (state.value.isBusy || state.value.cleanupPending) false else {
                mutableState.value = state.value.copy(status = MockLocationStatus.STARTING, message = null)
                true
            }
        }

        /** Reads ownership only. Application startup must never restart a location session. */
        fun refreshCleanupState(context: Context) {
            val pending = PreferencesMockLocationCleanupLedger(context).pendingProviders().isNotEmpty()
            synchronized(stateGate) {
                if (!state.value.isBusy) {
                    mutableState.value = state.value.copy(
                        cleanupPending = pending,
                        status = if (pending) MockLocationStatus.FAILED else state.value.status,
                        message = if (pending) "上次模拟定位尚未清理，请恢复真实定位后再启动" else state.value.message
                    )
                }
            }
        }

        /** Call from a visible Activity. Cleanup has no real-location permission or FGS requirement. */
        fun recoverRealLocation(context: Context, force: Boolean = false): Boolean {
            refreshCleanupState(context)
            synchronized(stateGate) {
                if (state.value.isBusy) return false
                if (force) {
                    // Explicit user recovery can repair legacy versions which lost the running
                    // flag. Automatic recovery only touches providers already in the ledger.
                    val authorized = try { MockLocationAuthorization.isAllowed(context) } catch (_: Exception) { false }
                    if (!authorized) {
                        mutableState.value = state.value.copy(status = MockLocationStatus.FAILED,
                            message = "请先在开发者选项中选择本应用为模拟位置应用")
                        return false
                    }
                    val ledger = PreferencesMockLocationCleanupLedger(context)
                    if (!ledger.markPending(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))) {
                        mutableState.value = state.value.copy(status = MockLocationStatus.FAILED,
                            cleanupPending = ledger.pendingProviders().isNotEmpty(), message = "无法保存恢复记录，请检查应用存储后重试")
                        return false
                    }
                    mutableState.value = state.value.copy(cleanupPending = true)
                }
                if (!state.value.cleanupPending) return false
                mutableState.value = state.value.copy(status = MockLocationStatus.RECOVERING, message = "正在恢复真实定位")
            }
            return try {
                context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_RECOVER))
                true
            } catch (e: Exception) {
                Log.w(TAG, "无法提交模拟定位清理", e)
                synchronized(stateGate) {
                    if (state.value.status == MockLocationStatus.RECOVERING) {
                        mutableState.value = state.value.copy(status = MockLocationStatus.FAILED,
                            message = "系统未允许后台清理，请回到应用后点击恢复真实定位", cleanupPending = true)
                    }
                }
                false
            }
        }

        fun reportStartFailure(message: String) = synchronized(stateGate) {
            if (state.value.status == MockLocationStatus.STARTING) {
                mutableState.value = state.value.copy(status = MockLocationStatus.FAILED, message = message)
            }
        }

        fun singleIntent(context: Context, lat: Double, lng: Double): Intent =
            Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lng)
            }

        /** The entire request travels together; no process-global pending route can be overwritten. */
        fun trajectoryIntent(context: Context, points: List<LatLng>, speed: Double, loop: Boolean, laps: Int): Intent {
            require(points.size <= MockLocationEngine.MAX_WAYPOINTS) { "路径点过多" }
            return Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_TRAJECTORY)
                putExtra(EXTRA_TRAJECTORY_LATITUDES, points.map { it.lat }.toDoubleArray())
                putExtra(EXTRA_TRAJECTORY_LONGITUDES, points.map { it.lng }.toDoubleArray())
                putExtra(EXTRA_TRAJECTORY_SPEED, speed)
                putExtra(EXTRA_TRAJECTORY_LOOP, loop)
                putExtra(EXTRA_TRAJECTORY_LAPS, laps)
            }
        }

        fun stopService(context: Context) {
            if (!state.value.isBusy) {
                recoverRealLocation(context)
                return
            }
            try {
                context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "无法发送停止指令，使用系统停止服务", e)
                context.stopService(Intent(context, MockLocationService::class.java))
            }
        }

        internal fun isValidPosition(lat: Double, lng: Double): Boolean =
            MockLocationCoordinates.isValidPosition(lat, lng)
    }

    // Every command, advancement, injection and cleanup uses the same lock. A completed stop
    // cannot race with an in-flight injection, even while Activity clients remain bound.
    private val sessionGate = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private lateinit var workerHandler: Handler
    internal var injectionBackend: MockLocationBackend? = null
    internal var cleanupLedger: MockLocationCleanupLedger? = null
    internal var automaticUpdates = true
    internal var monotonicClock: () -> Long = { SystemClock.elapsedRealtimeNanos() }
    private var sessionActive = false
    private var destroyed = false
    private var generation = 0L
    private var position = LatLng(0.0, 0.0)
    private var engine: MockLocationEngine? = null
    private var manualSpeed = 0.0
    private var manualBearing = 0f
    private var reportedSpeed = 0.0
    private var reportedBearing = 0f
    private var lastTickNanos = 0L
    private var lastSuccessfulNanos = 0L
    private var lastSetupAttemptNanos = 0L
    private var lastNotifyNanos = 0L
    private var lastStateNanos = 0L
    private val attemptedProviders = linkedSetOf<String>()
    private val readyProviders = linkedSetOf<String>()
    private val providerHealthy = mutableMapOf<String, Boolean>()
    private val providerRetryNanos = mutableMapOf<String, Long>()
    private var overlay: MockLocationOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewNanos = 0L
    val binder = MockLocationBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (injectionBackend == null) {
            injectionBackend = AndroidMockLocationBackend(this, getSystemService(LOCATION_SERVICE) as LocationManager)
        }
        if (cleanupLedger == null) cleanupLedger = PreferencesMockLocationCleanupLedger(this)
        workerThread = HandlerThread("MockLocationThread").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        createNotificationChannel()
        // Binding alone must never register a mock provider or create a foreground session.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(sessionGate) {
            if (destroyed) return START_NOT_STICKY
            if (intent?.action == ACTION_RECOVER) {
                // Ordinary startService from a visible Activity: cleanup must still work when
                // location permission was revoked, so do not enter a location foreground service.
                stopMockingLocked(MockLocationStatus.STOPPED, null)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (intent == null || intent.action == ACTION_STOP) {
                stopMockingLocked(MockLocationStatus.STOPPED, null)
                stopSelf()
                return START_NOT_STICKY
            }
            publish(state.value.copy(status = MockLocationStatus.STARTING, message = null))
            try {
                // Every startForegroundService command must fulfill its foreground obligation
                // even when stale ownership prevents this request from starting a new session.
                startForeground(NOTIFICATION_ID, buildNotification(state.value))
                if (!clearResourcesLocked(removeNotification = false)) {
                    failLocked("上次模拟定位清理失败，请先恢复真实定位；本次请求未启动")
                    return START_NOT_STICKY
                }
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE
                engine = when (mode) {
                    MODE_SINGLE -> null
                    MODE_TRAJECTORY -> {
                        val latitudes = intent.getDoubleArrayExtra(EXTRA_TRAJECTORY_LATITUDES)
                            ?: throw IllegalArgumentException("缺少轨迹数据，请重新选择路径")
                        val longitudes = intent.getDoubleArrayExtra(EXTRA_TRAJECTORY_LONGITUDES)
                            ?: throw IllegalArgumentException("缺少轨迹数据，请重新选择路径")
                        require(latitudes.size == longitudes.size && latitudes.size <= MockLocationEngine.MAX_WAYPOINTS) {
                            "轨迹数据不完整或路径点过多"
                        }
                        MockLocationEngine(
                            latitudes.indices.map { LatLng(latitudes[it], longitudes[it]) },
                            intent.getDoubleExtra(EXTRA_TRAJECTORY_SPEED, Double.NaN),
                            intent.getBooleanExtra(EXTRA_TRAJECTORY_LOOP, false),
                            intent.getIntExtra(EXTRA_TRAJECTORY_LAPS, 0)
                        )
                    }
                    else -> throw IllegalArgumentException("未知的定位模式")
                }
                position = engine?.current()?.position ?: LatLng(
                    intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                    intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
                )
                require(isValidPosition(position.lat, position.lng)) { "经纬度无效或超出范围" }
                val initial = engine?.current()
                reportedSpeed = initial?.speed ?: 0.0
                reportedBearing = initial?.bearing ?: 0f
                publish(MockLocationSnapshot(
                    status = MockLocationStatus.STARTING, position = position, mode = mode,
                    route = engine?.points ?: emptyList(),
                    configuredSpeed = intent.getDoubleExtra(EXTRA_TRAJECTORY_SPEED, 1.2),
                    loop = intent.getBooleanExtra(EXTRA_TRAJECTORY_LOOP, false),
                    targetLaps = intent.getIntExtra(EXTRA_TRAJECTORY_LAPS, 0), cleanupPending = false
                ))
                val now = monotonicClock()
                setupProvidersLocked(now)
                require(readyProviders.isNotEmpty()) { "无法启用模拟定位，请在开发者选项中选择本应用" }
                sessionActive = true
                lastTickNanos = now
                lastSuccessfulNanos = now
                if (!injectLocked(now)) throw IllegalStateException("首次定位注入失败，请检查模拟位置应用设置")
                refreshStateLocked(now, force = true)
                persistPositionLocked()
                holdWakeLockLocked(now)
                showOverlayLocked()
                scheduleTickLocked(generation)
            } catch (e: Exception) {
                Log.e(TAG, "模拟定位启动失败", e)
                failLocked(when (e) {
                    is SecurityException -> "定位授权被拒绝，请检查定位权限和开发者选项中的模拟位置应用"
                    is IllegalArgumentException -> e.message ?: "定位参数无效"
                    else -> "无法启动模拟定位，请检查定位开关、权限和模拟位置应用设置"
                })
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(sessionGate) {
            destroyed = true
            val previous = state.value
            stopMockingLocked(
                if (previous.status == MockLocationStatus.FAILED) MockLocationStatus.FAILED else MockLocationStatus.STOPPED,
                previous.message
            )
            workerThread?.quitSafely()
            workerThread = null
        }
        super.onDestroy()
    }

    fun stopMocking() = synchronized(sessionGate) {
        stopMockingLocked(MockLocationStatus.STOPPED, null)
        stopSelf()
    }

    private fun scheduleTickLocked(sessionGeneration: Long) {
        if (!automaticUpdates || !sessionActive) return
        workerHandler.postDelayed({
            synchronized(sessionGate) {
                if (!sessionActive || destroyed || generation != sessionGeneration) return@postDelayed
                runLocationTick(monotonicClock())
                if (sessionActive && generation == sessionGeneration) scheduleTickLocked(sessionGeneration)
            }
        }, 100L)
    }

    internal fun runLocationTick(nowNanos: Long) = synchronized(sessionGate) {
        if (!sessionActive || destroyed || nowNanos < lastTickNanos) return@synchronized
        try {
            advanceMovementLocked(nowNanos)
            if (nowNanos - lastSetupAttemptNanos >= 1_000_000_000L && readyProviders.size < 2) setupProvidersLocked(nowNanos)
            injectLocked(nowNanos)
            if (nowNanos - lastSuccessfulNanos >= 3_000_000_000L) {
                failLocked("定位注入连续失败，模拟定位已停止，请检查系统定位设置")
                return@synchronized
            }
            refreshStateLocked(nowNanos)
            holdWakeLockLocked(nowNanos)
        } catch (e: SecurityException) {
            Log.w(TAG, "模拟位置授权已撤回", e)
            failLocked("模拟位置授权已撤回，已停止注入；请重新选择本应用后点击恢复真实定位")
        } catch (e: Exception) {
            Log.e(TAG, "定位更新失败", e)
            failLocked("定位更新失败，模拟定位已停止")
        }
    }

    /** Settle the previous command at its own timestamp, shared by ticks and manual commands. */
    private fun advanceMovementLocked(now: Long) {
        if (now < lastTickNanos) return
        val delta = (now - lastTickNanos) / 1_000_000_000.0
        lastTickNanos = now
        if (delta > MockLocationEngine.MAX_DELTA_SECONDS) {
            reportedSpeed = 0.0
            return
        }
        val trajectory = engine
        if (trajectory != null) {
            val current = trajectory.advance(delta)
            position = current.position
            reportedSpeed = current.speed
            reportedBearing = current.bearing
        } else {
            if (manualSpeed > 0.0) position = MockLocationCoordinates.move(position, manualSpeed * delta, manualBearing.toDouble())
            reportedSpeed = manualSpeed
            reportedBearing = manualBearing
        }
    }

    private fun setupProvidersLocked(now: Long) {
        lastSetupAttemptNanos = now
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider in readyProviders || now < (providerRetryNanos[provider] ?: 0L)) continue
            check(cleanupLedger!!.markPending(provider)) { "无法持久保存模拟定位清理记录，已取消启动" }
            attemptedProviders.add(provider)
            try {
                injectionBackend!!.add(provider)
                readyProviders.add(provider)
                providerHealthy[provider] = false
                providerRetryNanos.remove(provider)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                providerRetryNanos[provider] = now + 5_000_000_000L
                Log.w(TAG, "模拟定位 provider 不可用: $provider", e)
            }
        }
    }

    private fun injectLocked(now: Long): Boolean {
        check(isValidPosition(position.lat, position.lng)) { "计算产生无效坐标" }
        check(reportedSpeed.isFinite() && reportedSpeed >= 0.0 && reportedBearing.isFinite()) { "计算产生无效运动数据" }
        var accepted = false
        val wallTime = System.currentTimeMillis()
        for (provider in readyProviders.toList()) {
            if (now < (providerRetryNanos[provider] ?: 0L)) continue
            val location = Location(provider).apply {
                latitude = position.lat
                longitude = position.lng
                accuracy = if (provider == LocationManager.GPS_PROVIDER) 8f else 25f
                speed = reportedSpeed.toFloat()
                bearing = reportedBearing
                time = wallTime
                elapsedRealtimeNanos = now.coerceAtLeast(1L)
            }
            try {
                injectionBackend!!.inject(provider, location)
                providerHealthy[provider] = true
                providerRetryNanos.remove(provider)
                accepted = true
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                if (providerHealthy[provider] != false) Log.w(TAG, "定位注入失败: $provider", e)
                providerHealthy[provider] = false
                // A provider can have been removed or disabled after registration. Re-add it
                // after bounded backoff instead of indefinitely writing to a missing provider.
                readyProviders.remove(provider)
                providerRetryNanos[provider] = now + 1_000_000_000L
            }
        }
        if (accepted) lastSuccessfulNanos = now
        return accepted
    }

    private fun refreshStateLocked(now: Long, force: Boolean = false) {
        val healthyCount = providerHealthy.values.count { it }
        val status = if (healthyCount == 2) MockLocationStatus.RUNNING else MockLocationStatus.DEGRADED
        val current = engine?.current()
        val message = when {
            healthyCount == 0 -> "定位更新暂时失败，正在重试"
            providerHealthy[LocationManager.GPS_PROVIDER] != true -> "GPS 模拟不可用，仅网络模拟可用"
            healthyCount < 2 -> "网络模拟不可用，仅 GPS 模拟可用"
            current?.completed == true -> "轨迹已完成，停留在终点；点击停止可恢复真实定位"
            else -> null
        }
        if (force || now - lastStateNanos >= 250_000_000L || state.value.status != status || state.value.message != message) {
            lastStateNanos = now
            publish(state.value.copy(
                status = status, position = position, speedMetersPerSecond = reportedSpeed,
                waypointIndex = current?.waypointIndex ?: 0, completedLaps = current?.completedLaps ?: 0,
                trajectoryCompleted = current?.completed ?: false, message = message,
                cleanupPending = cleanupLedger!!.pendingProviders().isNotEmpty()
            ))
            overlay?.updateCoords(position.lat, position.lng)
            overlay?.updateTrajectoryProgress(
                current?.let { "${it.waypointIndex + 1}/${state.value.route.size}" }, reportedSpeed,
                current != null && !current.completed, state.value.loop,
                current?.completedLaps ?: 0, state.value.targetLaps
            )
        }
        if (force || now - lastNotifyNanos >= 3_000_000_000L) {
            lastNotifyNanos = now
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(state.value))
            } catch (e: Exception) {
                Log.w(TAG, "无法更新运行通知", e)
            }
        }
    }

    private fun showOverlayLocked() {
        val overlayGeneration = generation
        val newOverlay = MockLocationOverlay(this)
        overlay = newOverlay
        newOverlay.setMovementListener { speed, bearing ->
            synchronized(sessionGate) {
                if (generation == overlayGeneration) updateManualMovement(speed, bearing)
            }
        }
        newOverlay.setStopListener {
            synchronized(sessionGate) {
                if (sessionActive && generation == overlayGeneration) stopMocking()
            }
        }
        newOverlay.show(position.lat, position.lng)
        newOverlay.setTrajectoryMode(engine != null)
    }

    internal fun updateManualMovement(speed: Double, bearing: Float) = synchronized(sessionGate) {
        if (sessionActive && engine == null && speed.isFinite() && speed in 0.0..100.0 && bearing.isFinite()) {
            advanceMovementLocked(monotonicClock())
            manualSpeed = speed
            manualBearing = ((bearing % 360f) + 360f) % 360f
            reportedSpeed = speed
            reportedBearing = manualBearing
        }
    }

    private fun holdWakeLockLocked(now: Long) {
        if (wakeLock?.isHeld == true && now < wakeLockRenewNanos) return
        try {
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mock-location")
                    .apply { setReferenceCounted(false) }
            }
            wakeLock!!.acquire(10 * 60 * 1000L)
            wakeLockRenewNanos = now + 9 * 60 * 1_000_000_000L
        } catch (e: Exception) {
            Log.w(TAG, "无法保持定位线程唤醒", e)
        }
    }

    private fun failLocked(message: String) {
        stopMockingLocked(MockLocationStatus.FAILED, message)
        stopSelf()
    }

    private fun stopMockingLocked(status: MockLocationStatus, message: String?) {
        if (sessionActive) persistPositionLocked()
        val cleanupSucceeded = clearResourcesLocked()
        val resultMessage = if (cleanupSucceeded) message else
            listOfNotNull(message, "已停止发送模拟位置，但系统清理失败，请检查模拟位置设置后重试").joinToString("；")
        publish(state.value.copy(status = if (cleanupSucceeded) status else MockLocationStatus.FAILED,
            speedMetersPerSecond = 0.0, message = resultMessage,
            cleanupPending = !cleanupSucceeded || cleanupLedger!!.pendingProviders().isNotEmpty()))
    }

    private fun clearResourcesLocked(removeNotification: Boolean = true): Boolean {
        sessionActive = false
        generation++
        if (::workerHandler.isInitialized) workerHandler.removeCallbacksAndMessages(null)
        manualSpeed = 0.0
        reportedSpeed = 0.0
        engine = null
        val oldOverlay = overlay
        overlay = null
        if (Looper.myLooper() == Looper.getMainLooper()) oldOverlay?.dismiss()
        else mainHandler.post { oldOverlay?.dismiss() }
        val failedRemovals = mutableSetOf<String>()
        attemptedProviders.addAll(cleanupLedger?.pendingProviders().orEmpty())
        for (provider in attemptedProviders.toList()) {
            try {
                injectionBackend?.remove(provider)
                check(cleanupLedger?.markRemoved(provider) == true) { "模拟定位已停止，但清理结果无法持久保存" }
            } catch (e: Exception) {
                failedRemovals.add(provider)
                Log.w(TAG, "清理模拟 provider 失败: $provider", e)
            }
        }
        // Keep failures for another cleanup attempt on destruction or the next explicit start.
        attemptedProviders.retainAll(failedRemovals)
        readyProviders.clear()
        providerHealthy.clear()
        providerRetryNanos.clear()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放定位唤醒锁失败", e)
        }
        wakeLock = null
        wakeLockRenewNanos = 0L
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
        lastNotifyNanos = 0L
        lastStateNanos = 0L
        return failedRemovals.isEmpty()
    }

    private fun persistPositionLocked() {
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
            .putString("mock_location_lat_double", position.lat.toString())
            .putString("mock_location_lng_double", position.lng.toString())
            .putString("mock_location_mode", state.value.mode).apply()
    }

    private fun publish(snapshot: MockLocationSnapshot) = synchronized(stateGate) {
        mutableState.value = snapshot
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "虚拟定位", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "虚拟定位运行状态"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(snapshot: MockLocationSnapshot): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MockLocationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val coordinates = snapshot.position?.let { String.format(Locale.US, "%.6f, %.6f", it.lat, it.lng) } ?: ""
        val text = snapshot.message ?: if (snapshot.mode == MODE_TRAJECTORY) {
            val laps = if (snapshot.loop) " | 已完成 ${snapshot.completedLaps}/${if (snapshot.targetLaps > 0) snapshot.targetLaps else "∞"} 圈" else ""
            "${String.format(Locale.US, "%.1f", snapshot.speedMetersPerSecond)} m/s$laps | $coordinates"
        } else coordinates
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_pin)
            .setContentTitle(if (snapshot.status == MockLocationStatus.STARTING) "模拟定位启动中" else "模拟定位运行中")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(R.drawable.ic_location_pin, "停止", stop).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    inner class MockLocationBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
        fun setPosition(lat: Double, lng: Double) = synchronized(sessionGate) {
            if (sessionActive && engine == null && isValidPosition(lat, lng)) {
                lastTickNanos = monotonicClock()
                position = LatLng(lat, lng)
                manualSpeed = 0.0
                reportedSpeed = 0.0
                refreshStateLocked(monotonicClock(), force = true)
            }
        }
    }
}
