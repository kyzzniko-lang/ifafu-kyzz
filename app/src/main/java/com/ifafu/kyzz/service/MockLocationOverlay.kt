package com.ifafu.kyzz.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class MockLocationOverlay(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var tvCoords: TextView? = null
    private var isShowing = false

    // Movement
    private var moveAngle = 0.0
    private var moveSpeed = 1.2 // m/s (walk)
    private var isMoving = false
    private val moveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var moveRunnable: Runnable? = null
    private var positionListener: ((Double, Double) -> Unit)? = null
    private var currentLat = 0.0
    private var currentLng = 0.0

    fun show(lat: Double, lng: Double) {
        if (isShowing) {
            updateCoords(lat, lng)
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        currentLat = lat
        currentLng = lng
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        rootView = createFloatingView(lat, lng)

        try {
            windowManager?.addView(rootView, layoutParams)
            isShowing = true
            setupDrag(rootView!!, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateCoords(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        tvCoords?.text = "%.5f, %.5f".format(lat, lng)
    }

    fun setPositionListener(listener: (Double, Double) -> Unit) {
        positionListener = listener
    }

    fun dismiss() {
        stopMoving()
        if (!isShowing) return
        try {
            windowManager?.removeView(rootView)
        } catch (_: Exception) {}
        rootView = null
        tvCoords = null
        isShowing = false
    }

    private fun createFloatingView(lat: Double, lng: Double): View {
        val dp = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createBackground(dp)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }

        // Title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
            }
            layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply {
                marginEnd = (6 * dp).toInt()
            }
        }
        titleRow.addView(dot)
        titleRow.addView(TextView(context).apply {
            text = "虚拟定位"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        container.addView(titleRow)

        // Coordinates
        tvCoords = TextView(context).apply {
            text = "%.5f, %.5f".format(lat, lng)
            setTextColor(Color.parseColor("#DDFFFFFF"))
            textSize = 11f
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        container.addView(tvCoords)

        // Speed row
        val speedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val tvWalk = createSpeedBtn(dp, "步行", true)
        val tvRun = createSpeedBtn(dp, "跑步", false)
        val tvBike = createSpeedBtn(dp, "骑车", false)
        val speedBtns = mutableListOf(tvWalk, tvRun, tvBike)
        val speeds = doubleArrayOf(1.2, 3.6, 10.0)
        speedBtns.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                moveSpeed = speeds[index]
                speedBtns.forEach { b ->
                    (b as TextView).setTextColor(Color.parseColor("#88FFFFFF"))
                    (b.background as? GradientDrawable)?.setStroke(
                        (1 * dp).toInt(), Color.parseColor("#44FFFFFF")
                    )
                }
                btn.setTextColor(Color.WHITE)
                (btn.background as? GradientDrawable)?.setStroke(
                    (1 * dp).toInt(), Color.parseColor("#D4724A")
                )
            }
        }
        speedRow.addView(tvWalk)
        speedRow.addView(tvRun)
        speedRow.addView(tvBike)
        container.addView(speedRow)

        // Direction pad (3x3 grid)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val directions = arrayOf(
            doubleArrayOf(135.0, 90.0, 45.0),   // NW, N, NE
            doubleArrayOf(180.0, -1.0, 0.0),      // W, stop, E
            doubleArrayOf(225.0, 270.0, 315.0)    // SW, S, SE
        )
        val labels = arrayOf(
            arrayOf("↖", "↑", "↗"),
            arrayOf("←", "·", "→"),
            arrayOf("↙", "↓", "↘")
        )
        for (row in 0..2) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0..2) {
                val angle = directions[row][col]
                val label = labels[row][col]
                val btn = TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (36 * dp).toInt(), (36 * dp).toInt()
                    ).apply {
                        setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * dp
                        setColor(Color.parseColor("#44FFFFFF"))
                    }
                }
                if (angle < 0) {
                    // Center button: stop
                    btn.setOnClickListener { stopMoving() }
                    btn.text = "■"
                    btn.textSize = 14f
                } else {
                    val a = angle
                    btn.setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startMoving(a)
                                btn.background = GradientDrawable().apply {
                                    shape = GradientDrawable.RECTANGLE
                                    cornerRadius = 6 * dp
                                    setColor(Color.parseColor("#66D4724A"))
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                stopMoving()
                                btn.background = GradientDrawable().apply {
                                    shape = GradientDrawable.RECTANGLE
                                    cornerRadius = 6 * dp
                                    setColor(Color.parseColor("#44FFFFFF"))
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            grid.addView(rowLayout)
        }
        container.addView(grid)

        // Stop service button
        val btnStop = TextView(context).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * dp
                setColor(Color.parseColor("#E53935"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (28 * dp).toInt()
            ).apply {
                topMargin = (8 * dp).toInt()
            }
            setOnClickListener {
                MockLocationService.stopService(context)
            }
        }
        container.addView(btnStop)

        return container
    }

    private fun createSpeedBtn(dp: Float, text: String, selected: Boolean): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#88FFFFFF"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4 * dp
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(
                    (1 * dp).toInt(),
                    if (selected) Color.parseColor("#D4724A") else Color.parseColor("#44FFFFFF")
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                0, (26 * dp).toInt(), 1f
            ).apply {
                marginEnd = (4 * dp).toInt()
            }
        }
    }

    private fun startMoving(angle: Double) {
        moveAngle = angle
        isMoving = true
        stopMoving()
        moveRunnable = object : Runnable {
            override fun run() {
                if (!isMoving) return
                // 1 degree latitude ≈ 110.574 km
                // 1 degree longitude ≈ 111.320 * cos(lat) km
                val intervalSec = 0.1 // 100ms
                val distM = moveSpeed * intervalSec
                val distLat = distM * sin(Math.toRadians(angle)) / 110574.0
                val distLng = distM * cos(Math.toRadians(angle)) / (111320.0 * cos(Math.toRadians(currentLat)))
                currentLat += distLat
                currentLng += distLng
                updateCoords(currentLat, currentLng)
                positionListener?.invoke(currentLat, currentLng)
                moveHandler.postDelayed(this, 100)
            }
        }
        moveHandler.post(moveRunnable!!)
    }

    private fun stopMoving() {
        isMoving = false
        moveRunnable?.let { moveHandler.removeCallbacks(it) }
    }

    private fun createBackground(dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(Color.parseColor("#CC333333"))
            setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // Don't consume, let children handle
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }
    }
}
