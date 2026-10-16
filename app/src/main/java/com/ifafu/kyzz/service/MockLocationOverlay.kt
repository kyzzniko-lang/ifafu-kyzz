package com.ifafu.kyzz.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Displays state and sends movement commands; the service owns position and timing. */
@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
class MockLocationOverlay(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateToken = Any()
    private val generation = AtomicLong()

    // All view/window/movement fields below are accessed on the main thread only.
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rootView: LinearLayout? = null
    private var expandedView: View? = null
    private var compactView: View? = null
    private var manualControls: View? = null
    private var trajectoryHint: TextView? = null
    private var tvCoords: TextView? = null
    private var tvProgress: TextView? = null
    private var isShowing = false
    private var isCollapsed = false
    private var isTrajectoryMode = false
    private var configurationReceiverRegistered = false
    private var movementListener: ((Double, Float) -> Unit)? = null
    private var stopListener: (() -> Unit)? = null
    private var moveSpeed = WALK_SPEED
    private var moveBearing = 0f
    private var activeDirection: View? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var appliedX = Int.MIN_VALUE
    private var appliedY = Int.MIN_VALUE
    private var appliedWidth = Int.MIN_VALUE

    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) updateWindowPosition()
        }
    }

    private companion object {
        private const val WALK_SPEED = 1.2
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    fun show(lat: Double, lng: Double) = onMain {
        if (!lat.isFinite() || !lng.isFinite()) return@onMain
        if (isShowing) {
            tvCoords?.text = formatCoords(lat, lng)
            updateWindowPosition()
            return@onMain
        }
        if (!Settings.canDrawOverlays(context)) return@onMain
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@onMain
        windowManager = manager
        layoutParams = WindowManager.LayoutParams(
            dp(180), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            BASE_FLAGS, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(20)
            y = dp(200)
        }
        val view = createFloatingView(lat, lng)
        rootView = view
        try {
            manager.addView(view, layoutParams)
            isShowing = true
            registerConfigurationReceiver()
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (isShowing) updateWindowPosition()
            }
            updateModeViews()
            updateWindowPosition()
        } catch (_: RuntimeException) {
            // Permission can be revoked between checking it and adding the window.
            cleanupWindow()
        }
    }

    fun setMovementListener(listener: (Double, Float) -> Unit) = onMain {
        movementListener = listener
    }

    fun setStopListener(listener: () -> Unit) = onMain {
        stopListener = listener
    }

    fun setTrajectoryMode(isTrajectory: Boolean) = onMain {
        if (isTrajectoryMode != isTrajectory) {
            // The service has already changed mode. A late zero-speed command must not
            // overwrite the new trajectory's movement state.
            stopMoving(notify = false)
            isTrajectoryMode = isTrajectory
        }
        updateModeViews()
    }

    fun updateCoords(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        postDisplayUpdate { tvCoords?.text = formatCoords(lat, lng) }
    }

    fun updateTrajectoryProgress(
        progress: String?, speed: Double, isTrajectory: Boolean,
        isLoop: Boolean = false, completedLaps: Int = 0, targetLaps: Int = 0
    ) {
        postDisplayUpdate {
            // Completion hides progress but the run remains a trajectory until it
            // stops. Only setTrajectoryMode may enable manual movement controls.
            trajectoryHint?.text = if (isTrajectoryMode && !isTrajectory) {
                "轨迹已完成，点击停止恢复真实定位"
            } else "轨迹按设定自动运行"
            tvProgress?.apply {
                visibility = if (isTrajectory && !progress.isNullOrBlank()) View.VISIBLE else View.GONE
                text = if (isTrajectory && !progress.isNullOrBlank()) {
                    val safeSpeed = if (speed.isFinite() && speed >= 0) speed else 0.0
                    val speedText = String.format(Locale.ROOT, "%.1f m/s", safeSpeed)
                    if (isLoop) {
                        val lap = (completedLaps.coerceAtLeast(0).toLong() + 1)
                        val lapInfo = if (targetLaps > 0) "$lap/$targetLaps" else "$lap/∞"
                        "校园跑: $progress  圈: $lapInfo\n$speedText"
                    } else "路径: $progress\n$speedText"
                } else ""
            }
        }
    }

    fun dismiss() {
        // Invalidates already queued updates immediately, even when called by a worker.
        generation.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanupWindow()
        } else {
            // Control operations remain FIFO; a subsequent show can create a new window.
            mainHandler.post { cleanupWindow() }
        }
    }

    private fun onMain(action: () -> Unit) {
        val expectedGeneration = generation.get()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post {
                if (generation.get() == expectedGeneration) action()
            }
        }
    }

    private fun postDisplayUpdate(action: () -> Unit) {
        val expectedGeneration = generation.get()
        mainHandler.postAtTime({
            if (generation.get() == expectedGeneration && isShowing) action()
        }, updateToken, SystemClock.uptimeMillis())
    }

    private fun cleanupWindow() {
        mainHandler.removeCallbacksAndMessages(updateToken)
        // Permission loss or dismissal during a held direction must stop movement.
        // The service guards callbacks from obsolete overlay instances/run generations.
        stopMoving()
        // Clear callbacks before removing the view: detach may dispatch ACTION_CANCEL.
        movementListener = null
        stopListener = null
        isShowing = false
        rootView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: RuntimeException) {
                // Safe when the system already removed the window after permission loss.
            }
        }
        if (configurationReceiverRegistered) {
            try {
                context.unregisterReceiver(configurationReceiver)
            } catch (_: IllegalArgumentException) {
                // Registration may have been lost during context teardown.
            }
            configurationReceiverRegistered = false
        }
        rootView = null
        expandedView = null
        compactView = null
        manualControls = null
        trajectoryHint = null
        tvCoords = null
        tvProgress = null
        layoutParams = null
        windowManager = null
        isCollapsed = false
        isTrajectoryMode = false
        moveSpeed = WALK_SPEED
        appliedX = Int.MIN_VALUE
        appliedY = Int.MIN_VALUE
        appliedWidth = Int.MIN_VALUE
    }

    private fun registerConfigurationReceiver() {
        if (configurationReceiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(configurationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(configurationReceiver, filter)
            }
            configurationReceiverRegistered = true
        } catch (_: RuntimeException) {
            // Layout changes still clamp the window if a receiver cannot be registered.
        }
    }

    private fun createFloatingView(lat: Double, lng: Double): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background(Color.parseColor("#E6333333"), 8)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val expanded = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        expandedView = expanded
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "● 虚拟定位"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "拖动虚拟定位悬浮窗"
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        setupDrag(title)
        titleRow.addView(title)
        titleRow.addView(button("收起", Color.parseColor("#44555555")).apply {
            contentDescription = "收起悬浮窗，保留展开和停止入口"
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(40))
            setOnClickListener { setCollapsed(true) }
        })
        expanded.addView(titleRow)
        tvCoords = TextView(context).apply {
            text = formatCoords(lat, lng)
            setTextColor(Color.parseColor("#DDFFFFFF"))
            textSize = 10f
            setPadding(0, dp(2), 0, dp(2))
        }
        expanded.addView(tvCoords)
        tvProgress = TextView(context).apply {
            setTextColor(Color.parseColor("#FFD4724A"))
            textSize = 10f
            visibility = View.GONE
        }
        expanded.addView(tvProgress)
        trajectoryHint = TextView(context).apply {
            text = "轨迹按设定自动运行"
            textSize = 11f
            setTextColor(Color.parseColor("#DDFFFFFF"))
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        expanded.addView(trajectoryHint)
        val manual = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        manualControls = manual
        manual.addView(createSpeedRow())
        manual.addView(createDirectionPad())
        expanded.addView(manual)
        expanded.addView(button("停止定位", Color.parseColor("#E53935")).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
            ).apply { topMargin = dp(4) }
            setOnClickListener { requestStop() }
        })
        container.addView(expanded)

        // A compact, touchable window leaves the rest of the screen usable. Keeping
        // recovery and stop controls touchable avoids the former passthrough deadlock.
        val compact = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        compactView = compact
        compact.addView(TextView(context).apply {
            text = "定位"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "拖动收起的定位悬浮窗"
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setupDrag(this)
        })
        compact.addView(button("展开", Color.parseColor("#66555555")).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(44)).apply { marginEnd = dp(4) }
            setOnClickListener { setCollapsed(false) }
        })
        compact.addView(button("停止", Color.parseColor("#E53935")).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(44))
            setOnClickListener { requestStop() }
        })
        container.addView(compact)
        return container
    }

    private fun createSpeedRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val labels = arrayOf("步行", "跑步", "骑车")
        val speeds = doubleArrayOf(WALK_SPEED, 3.6, 10.0)
        val buttons = labels.map { label ->
            button(label, Color.parseColor("#44555555")).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
            }
        }
        buttons.forEachIndexed { index, view ->
            view.isSelected = speeds[index] == moveSpeed
            view.setOnClickListener {
                if (isTrajectoryMode || !isShowing) return@setOnClickListener
                moveSpeed = speeds[index]
                buttons.forEach { it.isSelected = it === view }
                if (activeDirection != null) movementListener?.invoke(moveSpeed, moveBearing)
            }
            row.addView(view)
        }
        return row
    }

    private fun createDirectionPad(): View {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val bearings = arrayOf(
            floatArrayOf(315f, 0f, 45f),
            floatArrayOf(270f, -1f, 90f),
            floatArrayOf(225f, 180f, 135f)
        )
        val labels = arrayOf(
            arrayOf("↖", "↑", "↗"),
            arrayOf("←", "■", "→"),
            arrayOf("↙", "↓", "↘")
        )
        val descriptions = arrayOf(
            arrayOf("向西北移动", "向北移动", "向东北移动"),
            arrayOf("向西移动", "停止移动", "向东移动"),
            arrayOf("向西南移动", "向南移动", "向东南移动")
        )
        for (rowIndex in 0..2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (column in 0..2) {
                val bearing = bearings[rowIndex][column]
                val view = button(labels[rowIndex][column], Color.parseColor("#44555555")).apply {
                    contentDescription = descriptions[rowIndex][column]
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                }
                if (bearing < 0) {
                    view.setOnClickListener {
                        if (!isTrajectoryMode && isShowing) stopMoving(forceNotify = true)
                    }
                } else {
                    view.setOnTouchListener { _, event -> handleDirectionTouch(view, bearing, event) }
                }
                row.addView(view)
            }
            grid.addView(row)
        }
        return grid
    }

    private fun handleDirectionTouch(view: View, bearing: Float, event: MotionEvent): Boolean {
        if (!isShowing || isTrajectoryMode || isCollapsed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMoving(notify = false)
                activeDirection = view
                activePointerId = event.getPointerId(event.actionIndex)
                moveBearing = bearing
                view.isPressed = true
                movementListener?.invoke(moveSpeed, bearing)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeDirection === view) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index < 0 || event.getX(index) !in 0f..view.width.toFloat() ||
                        event.getY(index) !in 0f..view.height.toFloat()) stopMoving()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (activeDirection === view && event.getPointerId(event.actionIndex) == activePointerId) {
                    stopMoving()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeDirection === view) stopMoving()
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            }
        }
        return true
    }

    private fun stopMoving(notify: Boolean = true, forceNotify: Boolean = false) {
        val wasMoving = activeDirection != null
        activeDirection?.isPressed = false
        activeDirection = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (notify && isShowing && !isTrajectoryMode && (wasMoving || forceNotify)) {
            movementListener?.invoke(0.0, moveBearing)
        }
    }

    private fun requestStop() {
        if (!isShowing) return
        // The listener directly invokes the existing service's stop operation.
        stopMoving(notify = false)
        stopListener?.invoke()
    }

    private fun setCollapsed(collapsed: Boolean) {
        if (!isShowing || isCollapsed == collapsed) return
        stopMoving()
        isCollapsed = collapsed
        expandedView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        compactView?.visibility = if (collapsed) View.VISIBLE else View.GONE
        updateWindowPosition()
    }

    private fun updateModeViews() {
        manualControls?.visibility = if (isTrajectoryMode) View.GONE else View.VISIBLE
        trajectoryHint?.visibility = if (isTrajectoryMode) View.VISIBLE else View.GONE
        trajectoryHint?.text = "轨迹按设定自动运行"
        if (!isTrajectoryMode) tvProgress?.visibility = View.GONE
        updateWindowPosition()
    }

    private fun button(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), background(Color.parseColor("#FFD4724A")))
            addState(intArrayOf(android.R.attr.state_selected), background(Color.parseColor("#FF975238")))
            addState(intArrayOf(), background(color))
        }
    }

    private fun background(color: Int, radius: Int = 4) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun setupDrag(handle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        handle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            if (!isShowing) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    updateWindowPosition()
                }
                MotionEvent.ACTION_UP -> handle.performClick()
            }
            true
        }
    }

    private fun updateWindowPosition() {
        if (!isShowing) return
        val view = rootView ?: return
        val params = layoutParams ?: return
        val manager = windowManager ?: return
        try {
            val available = screenBounds(manager)
            // On short landscape displays the full controls may no longer fit. The
            // compact window always keeps restore and stop reachable.
            if (!isCollapsed && view.height > available.height()) {
                setCollapsed(true)
                return
            }
            val width = dp(180).coerceAtMost(available.width().coerceAtLeast(1))
            val x = params.x.coerceIn(available.left, (available.right - width).coerceAtLeast(available.left))
            val height = view.height.coerceAtLeast(dp(52)).coerceAtMost(available.height().coerceAtLeast(1))
            val y = params.y.coerceIn(available.top, (available.bottom - height).coerceAtLeast(available.top))
            params.width = width
            params.x = x
            params.y = y
            if (appliedX != x || appliedY != y || appliedWidth != width) {
                manager.updateViewLayout(view, params)
                appliedX = x
                appliedY = y
                appliedWidth = width
            }
        } catch (_: RuntimeException) {
            // Losing overlay permission must neither crash nor leave a held direction running.
            stopMoving()
            cleanupWindow()
        }
    }

    private fun screenBounds(manager: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(metrics.bounds).apply {
                left += insets.left
                top += insets.top
                right -= insets.right
                bottom -= insets.bottom
            }
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        @SuppressLint("InternalInsetResource")
        val statusId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        @SuppressLint("InternalInsetResource")
        val navigationId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (statusId > 0) context.resources.getDimensionPixelSize(statusId) else 0
        val bottom = if (navigationId > 0) context.resources.getDimensionPixelSize(navigationId) else 0
        return Rect(0, top, metrics.widthPixels, (metrics.heightPixels - bottom).coerceAtLeast(top + 1))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun formatCoords(lat: Double, lng: Double) = String.format(Locale.ROOT, "%.5f, %.5f", lat, lng)
}
