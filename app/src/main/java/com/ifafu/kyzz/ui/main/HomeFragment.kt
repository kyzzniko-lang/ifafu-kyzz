package com.ifafu.kyzz.ui.main

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.airbnb.lottie.LottieAnimationView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.CountdownEvent
import com.ifafu.kyzz.data.model.Pet
import com.ifafu.kyzz.data.repository.CommentRepository
import javax.inject.Inject
import com.ifafu.kyzz.data.model.PetState
import com.ifafu.kyzz.databinding.FragmentHomeBinding
import com.ifafu.kyzz.ui.pet.PetViewModel
import com.ifafu.kyzz.ui.countdown.CountdownActivity
import com.ifafu.kyzz.ui.syllabus.GridSyllabusActivity
import com.ifafu.kyzz.ui.toolbox.KyzzToolboxActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private val petViewModel: PetViewModel by viewModels()

    @Inject
    lateinit var cacheManager: CacheManager

    @Inject
    lateinit var commentRepository: CommentRepository

    @Inject
    lateinit var weatherApi: com.ifafu.kyzz.data.api.WeatherApi

    private var dailyWeather: com.ifafu.kyzz.data.model.DailyWeather? = null
    private var weatherJob: kotlinx.coroutines.Job? = null

    private val bubbleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bubbleRunnable: Runnable? = null
    private var bubbleAnimator: ValueAnimator? = null
    private var pulseAnimator: AnimatorSet? = null
    private val resumeRunnable = Runnable {
        if (isAdded && _binding != null) {
            val courses = viewModel.todayCourses.value ?: emptyList()
            val exam = viewModel.nextExam.value
            val examInfo = exam?.let { it.exam.name to it.daysLeft }
            petViewModel.triggerRandomBubble(courses.size, examInfo, getCountdownPairs())
        }
    }

    // 每节45分钟; 上午5min-20min-5min-5min, 下午5min-15min-5min, 晚上5min-5min
    private val sectionTimeMap = mapOf(
        1 to Pair("08:00", "08:45"),
        2 to Pair("08:50", "09:35"),
        3 to Pair("09:55", "10:40"),
        4 to Pair("10:45", "11:30"),
        5 to Pair("11:35", "12:20"),
        6 to Pair("14:00", "14:45"),
        7 to Pair("14:50", "15:35"),
        8 to Pair("15:50", "16:35"),
        9 to Pair("16:40", "17:25"),
        10 to Pair("18:25", "19:10"),
        11 to Pair("19:15", "20:00"),
        12 to Pair("20:05", "20:50")
    )

    private val sectionStartMinutes = mapOf(
        1 to 480, 2 to 530, 3 to 595, 4 to 645, 5 to 695,
        6 to 840, 7 to 890, 8 to 950, 9 to 1000,
        10 to 1105, 11 to 1155, 12 to 1205
    )

    private val sectionEndMinutes = mapOf(
        1 to 525, 2 to 575, 3 to 640, 4 to 690, 5 to 740,
        6 to 885, 7 to 935, 8 to 995, 9 to 1045,
        10 to 1150, 11 to 1200, 12 to 1250
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        simpleMode = requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
            .getBoolean("simple_mode", false)
        setupViews()
        observeUser()
        setupPet()
        loadData()
    }

    private var hasLoadedData = false
    private var simpleMode = false
    private var cachedCountdownEvents: List<CountdownEvent> = emptyList()

    private fun loadData() {
        if (hasLoadedData) return
        hasLoadedData = true
        viewModel.refreshUser()
        if (!simpleMode) {
            loadHotDiscussion()
            loadCountdownEvents()
            loadWeather()
        }
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        updateGreeting()
        if (!simpleMode) {
            petViewModel.reloadPet()
        }
        // Show cached update result if available
        showCachedUpdateIfNeeded()
        // Auto bubble on resume
        if (!simpleMode) {
            bubbleHandler.removeCallbacks(resumeRunnable)
            bubbleHandler.postDelayed(resumeRunnable, 2000)
        }
    }

    private fun showCachedUpdateIfNeeded() {
        val ctx = context ?: return
        val cached = com.ifafu.kyzz.ui.settings.UpdateChecker.loadCachedResult(ctx)
        if (cached != null && !com.ifafu.kyzz.ui.settings.UpdateChecker.isDismissed(ctx, cached.versionName)) {
            if (com.ifafu.kyzz.ui.settings.UpdateChecker.isNewerThanCurrent(ctx, cached)) {
                showUpdateCard(cached)
            } else {
                com.ifafu.kyzz.ui.settings.UpdateChecker.clearCachedResult(ctx)
            }
        }
    }

    private fun setupViews() {
        if (simpleMode) {
            binding.btnSettings.setOnClickListener {
                startActivity(Intent(requireContext(), com.ifafu.kyzz.ui.settings.SettingsActivity::class.java))
            }
            binding.tvQuickEntry.visibility = View.GONE
            binding.gridContainer.visibility = View.GONE
            binding.cardCountdown.visibility = View.GONE
            binding.cardHotDiscussion.visibility = View.GONE
            binding.cardWeather.visibility = View.GONE
            binding.courseProgressContainer.visibility = View.GONE
        } else {
            binding.btnSettings.visibility = View.GONE

            binding.gridSyllabus.setOnClickListener {
                startActivity(Intent(requireContext(), GridSyllabusActivity::class.java))
            }
            binding.gridElective.setOnClickListener {
                startActivity(Intent(requireContext(), com.ifafu.kyzz.ui.elective.SimpleElectiveActivity::class.java))
            }
            binding.gridToolbox.setOnClickListener {
                startActivity(Intent(requireContext(), KyzzToolboxActivity::class.java))
            }
            // Grid entry press feedback: scale on touch with haptics
            listOf(binding.gridSyllabus, binding.gridElective, binding.gridToolbox).forEach { entry ->
                entry.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(250)
                                .setInterpolator(OvershootInterpolator(2.5f)).start()
                        }
                    }
                    false
                }
            }

            // Card press feedback: scale on touch with haptics
            listOf(binding.cardCountdown, binding.cardExamCountdown, binding.cardHotDiscussion).forEach { card ->
                card.isClickable = true // Ensure view consumes touches and receives ACTION_UP/CANCEL
                card.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(250)
                                .setInterpolator(OvershootInterpolator(2.5f)).start()
                        }
                    }
                    false
                }
            }
        }

        // Pull-to-refresh
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            requireContext().getColor(R.color.claude_bg_elevated)
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshUser(force = true)
            if (!simpleMode) {
                loadHotDiscussion()
                loadCountdownEvents()
                loadWeather()
            }
            checkForUpdate()
            if (!simpleMode) petViewModel.onPetClicked()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(800)
                if (isAdded) binding.swipeRefresh.isRefreshing = false
            }
        }

        // Entrance animation for chips
        binding.chipsContainer.alpha = 0f
        binding.chipsContainer.translationY = 20f
        binding.chipsContainer.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(200).start()

        setupHomeAmbientGlow()
    }

    private var homeGlowAnimator1: ValueAnimator? = null
    private var homeGlowAnimator2: ValueAnimator? = null

    private fun setupHomeAmbientGlow() {
        binding.ambientGlowTop?.let { glow ->
            homeGlowAnimator1 = ValueAnimator.ofFloat(0.25f, 0.4f, 0.25f).apply {
                duration = 4500
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { glow.alpha = it.animatedValue as Float }
                start()
            }
        }
        binding.ambientGlowBottom?.let { glow ->
            homeGlowAnimator2 = ValueAnimator.ofFloat(0.15f, 0.28f, 0.15f).apply {
                duration = 5500
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { glow.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun loadHotDiscussion() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val comments = withContext(Dispatchers.IO) {
                    commentRepository.getComments(page = 1, perPage = 50)
                }
                val hot = comments.filter { it.likes.isNotEmpty() }
                    .sortedByDescending { it.likes.size }
                    .take(1)
                if (hot.isNotEmpty() && _binding != null) {
                    val card = binding.cardHotDiscussion
                    val container = binding.hotDiscussionContainer
                    container.removeAllViews()
                    card.visibility = View.VISIBLE

                    val comment = hot[0]
                    val dp = resources.displayMetrics.density

                    container.addView(TextView(requireContext()).apply {
                        text = comment.content.take(80) + if (comment.content.length > 80) "..." else ""
                        setTextAppearance(R.style.ClaudeBody)
                        setTextColor(resources.getColor(R.color.claude_text_primary, null))
                        typeface = resources.getFont(R.font.claude_serif)
                        maxLines = 2
                    })
                    container.addView(TextView(requireContext()).apply {
                        text = "${comment.nickname} · ♥${comment.likes.size}"
                        textSize = 11f
                        setTextColor(resources.getColor(R.color.claude_text_hint, null))
                        typeface = resources.getFont(R.font.claude_serif)
                        setPadding(0, (6 * dp).toInt(), 0, 0)
                    })

                    card.setOnClickListener {
                        startActivity(Intent(requireContext(), com.ifafu.kyzz.ui.comment.DiscussionActivity::class.java))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun loadWeather() {
        weatherJob?.cancel()
        weatherJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val weather = withContext(Dispatchers.IO) {
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val campusKey = prefs.getString("campus_weather", "jinshan") ?: "jinshan"
                    val campus = com.ifafu.kyzz.data.model.CampusWeather.fromKey(campusKey)

                    // 30-min cache per campus
                    val gson = com.google.gson.Gson()
                    val cached = cacheManager.loadWeather(campusKey)
                    if (cached != null) {
                        gson.fromJson(cached, com.ifafu.kyzz.data.model.DailyWeather::class.java)
                    } else {
                        val fresh = weatherApi.fetchWeather(campus.lat, campus.lng)
                        if (fresh != null) {
                            cacheManager.saveWeather(campusKey, gson.toJson(fresh))
                            fresh
                        } else {
                            // 网络失败时降级到过期缓存
                            val fallback = cacheManager.loadWeatherFallback(campusKey)
                            if (fallback != null) {
                                gson.fromJson(fallback, com.ifafu.kyzz.data.model.DailyWeather::class.java)
                            } else null
                        }
                    }
                }
                if (weather != null && _binding != null) {
                    dailyWeather = weather
                    showWeather(weather)
                    // 刷新课程卡片上的天气信息
                    val courses = viewModel.todayCourses.value
                    if (courses != null) showTodayCourses(courses)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun showWeather(weather: com.ifafu.kyzz.data.model.DailyWeather) {
        val card = binding.cardWeather
        val container = binding.weatherPeriodContainer
        container.removeAllViews()

        binding.tvWeatherSummary.text = weather.summary
        card.visibility = View.VISIBLE

        // Build per-period weather chips: group by class periods
        // Period → approx hour mapping
        data class PeriodWeather(val label: String, val weather: com.ifafu.kyzz.data.model.HourlyWeather?)
        val periods = listOf(
            "1-2节" to 8, "3-4节" to 10, "5节" to 11,
            "6-7节" to 14, "8-9节" to 16, "10-12节" to 19
        ).map { (label, hour) ->
            PeriodWeather(label, weather.getWeatherForHour(hour))
        }

        val ctx = requireContext()
        for (pw in periods) {
            val chip = TextView(ctx).apply {
                text = buildString {
                    append(pw.label)
                    if (pw.weather != null) {
                        append(" · ")
                        append("${pw.weather.temp}°")
                    }
                }
                setTextAppearance(com.ifafu.kyzz.R.style.ClaudeCaption)
                setTextColor(ctx.resources.getColor(com.ifafu.kyzz.R.color.claude_text_secondary, null))
                typeface = ctx.resources.getFont(com.ifafu.kyzz.R.font.claude_serif)
                background = ctx.resources.getDrawable(com.ifafu.kyzz.R.drawable.bg_info_chip, null)
                gravity = android.view.Gravity.CENTER
                setPadding(24, 10, 24, 10)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 10
                layoutParams = lp
            }
            container.addView(chip)
        }

        // Fade in
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(400).start()
    }

    private fun loadCountdownEvents() {
        val prefs = requireContext().getSharedPreferences("countdown_prefs", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("events", null)
        cachedCountdownEvents = if (json != null) {
            try {
                Gson().fromJson(json, object : TypeToken<List<CountdownEvent>>() {}.type)
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        val events = cachedCountdownEvents

        if (events.isEmpty()) {
            binding.cardCountdown.visibility = View.GONE
            return
        }

        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        val upcoming = events.mapNotNull { event ->
            try {
                val target = sdf.parse(event.date) ?: return@mapNotNull null
                val days = ((target.time - now.time.time) / (24 * 60 * 60 * 1000L)).toInt()
                event to days
            } catch (_: Exception) { null }
        }.sortedBy { it.second }.take(3)

        if (upcoming.isEmpty()) {
            binding.cardCountdown.visibility = View.GONE
            return
        }

        val container = binding.countdownContainer
        container.removeAllViews()
        binding.cardCountdown.visibility = View.VISIBLE
        // A1: Card entrance animation
        binding.cardCountdown.alpha = 0f
        binding.cardCountdown.translationY = 20f
        binding.cardCountdown.animate()
            .alpha(1f).translationY(0f)
            .setDuration(350).setStartDelay(50L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()

        for ((idx, pair) in upcoming.withIndex()) {
            val (event, days) = pair
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
                alpha = 0f
                translationX = -20f
            }
            row.addView(TextView(requireContext()).apply {
                text = event.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val daysText = when {
                days < 0 -> "已过${-days}天"
                days == 0 -> "就是今天！"
                else -> "${days}天后"
            }
            row.addView(TextView(requireContext()).apply {
                text = daysText
                textSize = 14f
                setTextColor(resources.getColor(
                    if (days in 0..7) R.color.claude_terracotta else R.color.claude_text_hint, null
                ))
                typeface = resources.getFont(R.font.claude_serif)
            })
            container.addView(row)
            // A1: staggered slide-in for each row
            row.animate()
                .alpha(1f).translationX(0f)
                .setDuration(300)
                .setStartDelay(80L + idx * 60L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }

        binding.cardCountdown.setOnClickListener {
            startActivity(Intent(requireContext(), CountdownActivity::class.java))
        }
    }

    private fun getCountdownPairs(): List<Pair<String, Int>> {
        val events = cachedCountdownEvents

        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        return events.mapNotNull { event ->
            try {
                val target = sdf.parse(event.date) ?: return@mapNotNull null
                val days = ((target.time - now.time.time) / (24 * 60 * 60 * 1000L)).toInt()
                event.name to days
            } catch (_: Exception) { null }
        }
    }

    private fun setupPet() {
        val prefs = requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
        if (simpleMode || !prefs.getBoolean("show_pet", true)) {
            binding.petWidget.root.visibility = View.GONE
            return
        }
        binding.petWidget.root.visibility = View.VISIBLE

        val petView = binding.petWidget
        val parent = petView.root.parent as? ViewGroup ?: return

        // Position pet at saved location or default bottom-right, clamped to screen
        parent.post {
            val savedX = prefs.getFloat("pet_pos_x", -1f)
            val savedY = prefs.getFloat("pet_pos_y", -1f)
            val petW = petView.root.width.toFloat().coerceAtLeast(1f)
            val petH = petView.root.height.toFloat().coerceAtLeast(1f)
            val maxX = (parent.width - petW).coerceAtLeast(0f)
            val maxY = (parent.height - petH).coerceAtLeast(0f)
            if (savedX in 0f..maxX && savedY in 0f..maxY) {
                petView.root.x = savedX
                petView.root.y = savedY
            } else if (savedX >= 0f && savedY >= 0f) {
                // Saved position out of bounds, clamp it
                petView.root.x = savedX.coerceIn(0f, maxX)
                petView.root.y = savedY.coerceIn(0f, maxY)
                savePetPosition(petView.root.x, petView.root.y)
            } else {
                // Default: bottom-right
                petView.root.x = (maxX - 20f).coerceAtLeast(0f)
                petView.root.y = (maxY - 48f).coerceAtLeast(0f)
            }
        }

        // Double-tap -> interact with pet, single tap -> show feed dialog
        val gestureDetector = GestureDetectorCompat(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    showPetChatDialog()
                    bouncePet()
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    showPetDialog()
                    bouncePet()
                    return true
                }
            })

        // Long press to drag, short tap/single/double handled by gesture detector
        var dX = 0f
        var dY = 0f
        var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
        var lastX = 0f
        var lastMoveTime = 0L
        var downTime = 0L
        var downX = 0f
        var downY = 0f
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

        // Touch listener on root (the whole pet widget) so it can move freely
        petView.root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    isDragging = false
                    lastX = event.rawX
                    lastMoveTime = System.currentTimeMillis()
                    downTime = System.currentTimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    gestureDetector.onTouchEvent(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    val moveDist = Math.sqrt(
                        ((event.rawX - downX) * (event.rawX - downX) +
                         (event.rawY - downY) * (event.rawY - downY)).toDouble()
                    ).toFloat()
                    // Start dragging immediately after exceeding touch slop
                    if (!isDragging && moveDist > touchSlop) {
                        isDragging = true
                        // Prevent all ancestors (CoordinatorLayout/AppBarLayout/SwipeRefreshLayout) from intercepting touch
                        var p = v.parent as? View
                        while (p != null) {
                            p.parent?.requestDisallowInterceptTouchEvent(true)
                            p = p.parent as? View
                        }
                    }
                    if (isDragging) {
                        val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
                        val newX = (event.rawX + dX).coerceIn(0f, maxX)
                        val newY = (event.rawY + dY).coerceIn(0f, maxY)
                        v.x = newX
                        v.y = newY
                        lastX = event.rawX
                        lastMoveTime = System.currentTimeMillis()
                        spawnDragTrail(petView.root)
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val elapsed = System.currentTimeMillis() - lastMoveTime
                        val velocityX = if (elapsed > 0 && elapsed < 200) {
                            val dx = event.rawX - lastX
                            dx / elapsed * 1000
                        } else 0f
                        val petW = v.width.toFloat()
                        val petH = v.height.toFloat()
                        val maxX = (parent.width - petW).coerceAtLeast(0f)
                        val maxY = (parent.height - petH).coerceAtLeast(0f)
                        val snapX = when {
                            velocityX > 800f -> maxX
                            velocityX < -800f -> 0f
                            else -> {
                                val centerX = v.x + petW / 2
                                if (centerX < parent.width / 2) 0f else maxX
                            }
                        }
                        val snapY = v.y.coerceIn(0f, maxY)
                        v.animate()
                            .x(snapX).y(snapY).rotation(0f)
                            .setDuration(250)
                            .setInterpolator(OvershootInterpolator(1.2f))
                            .withEndAction {
                                savePetPosition(snapX, snapY)
                                // Wobble after snap completes
                                v.animate().rotation(3f).setDuration(80).withEndAction {
                                    v.animate().rotation(-3f).setDuration(80).withEndAction {
                                        v.animate().rotation(0f).setDuration(60).start()
                                    }.start()
                                }.start()
                            }
                            .start()
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Clamp position on cancel as well
                    val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
                    val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
                    if (v.x < 0f || v.x > maxX || v.y < 0f || v.y > maxY) {
                        v.x = v.x.coerceIn(0f, maxX)
                        v.y = v.y.coerceIn(0f, maxY)
                        savePetPosition(v.x, v.y)
                    }
                    gestureDetector.onTouchEvent(event)
                    true
                }
                else -> {
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }
        }

        // Observe pet state
        petViewModel.pet.observe(viewLifecycleOwner) { pet ->
            updatePetImage(pet.state, pet.petType)
            // Update info bar
            updatePetInfoBar(pet)
            // Apply level color to Lottie
            applyPetColorTier(pet)
        }

        // Observe bubble text with typewriter effect
        petViewModel.bubbleText.observe(viewLifecycleOwner) { text ->
            if (text != null) {
                petView.tvBubbleText.text = ""
                petView.petBubble.visibility = View.VISIBLE
                petView.petBubble.alpha = 0f
                petView.petBubble.translationY = 12f
                petView.petBubble.scaleX = 0.85f
                petView.petBubble.scaleY = 0.85f
                petView.petBubble.animate()
                    .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
                // Typewriter: reveal one char at a time
                bubbleAnimator?.cancel()
                val animator = ValueAnimator.ofInt(0, text.length)
                animator.duration = (text.length * 38L).coerceAtMost(1100L)
                animator.interpolator = AccelerateDecelerateInterpolator()
                animator.addUpdateListener { a ->
                    val idx = a.animatedValue as Int
                    petView.tvBubbleText.text = text.substring(0, idx)
                }
                bubbleAnimator = animator
                animator.start()
            } else {
                // C4: Bubble flies upward and fades out
                petView.petBubble.animate()
                    .alpha(0f).translationY(-16f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(220)
                    .withEndAction {
                        petView.petBubble.visibility = View.GONE
                        petView.petBubble.translationY = 0f
                        petView.petBubble.scaleX = 1f
                        petView.petBubble.scaleY = 1f
                    }.start()
            }
        }

        // Observe level up
        petViewModel.showLevelUp.observe(viewLifecycleOwner) { level ->
            if (level != null) {
                showLevelUpDialog(level)
                petViewModel.dismissLevelUp()
            }
        }

        // Check-in click
        petView.tvCheckIn.setOnClickListener {
            petViewModel.checkIn()
        }

        // Observe check-in result
        petViewModel.checkInResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                if (result.alreadyChecked) {
                    petView.tvCheckIn.text = "已签到"
                } else {
                    val msg = "签到成功！+${result.points}积分 连续${result.streak}天"
                    petViewModel.dismissCheckInResult()
                    // Show bubble with check-in result
                    petView.tvCheckIn.text = "已签到 ${result.streak}天"
                }
            }
        }

        // Periodic random bubble every 45-90 seconds
        bubbleRunnable = object : Runnable {
            override fun run() {
                if (isAdded) {
                    val courses = viewModel.todayCourses.value ?: emptyList()
                    val exam = viewModel.nextExam.value
                    val examInfo = exam?.let { it.exam.name to it.daysLeft }
                    petViewModel.triggerRandomBubble(courses.size, examInfo, getCountdownPairs())
                    bubbleHandler.postDelayed(this, (45_000 + (Math.random() * 45_000).toLong()).toLong())
                }
            }
        }
        bubbleRunnable?.let { bubbleHandler.postDelayed(it, 60_000) }
    }

    private fun updatePetImage(state: PetState, petType: String = "cat") {
        val pet = petViewModel.pet.value ?: return
        com.ifafu.kyzz.ui.pet.PetLottieManager.applyAnimation(
            requireContext(), binding.petWidget.ivPet, state, petType
        )

        // Show status label
        binding.petWidget.tvPetStatus.text = state.label
        binding.petWidget.tvPetStatus.visibility = View.VISIBLE

        // GIF pet type: skip Lottie speed, only adjust alpha/scale
        if (petType in com.ifafu.kyzz.ui.pet.PetLottieManager.gifPetTypes) {
            when (state) {
                PetState.SLEEPING -> {
                    binding.petWidget.ivPet.alpha = 0.6f
                    binding.petWidget.ivPet.scaleX = 0.9f
                    binding.petWidget.ivPet.scaleY = 0.9f
                }
                else -> {
                    binding.petWidget.ivPet.alpha = 1f
                    binding.petWidget.ivPet.scaleX = 1f
                    binding.petWidget.ivPet.scaleY = 1f
                }
            }
            return
        }

        // Adjust animation by state (Lottie pets)
        when (state) {
            PetState.SLEEPING -> {
                binding.petWidget.ivPet.speed = 0.3f
                binding.petWidget.ivPet.alpha = 0.6f
                binding.petWidget.ivPet.scaleX = 0.9f
                binding.petWidget.ivPet.scaleY = 0.9f
            }
            PetState.EXCITED, PetState.HAPPY -> {
                binding.petWidget.ivPet.speed = 1.5f
                binding.petWidget.ivPet.alpha = 1f
                binding.petWidget.ivPet.scaleX = 1f
                binding.petWidget.ivPet.scaleY = 1f
            }
            PetState.STUDYING -> {
                binding.petWidget.ivPet.speed = 0.8f
                binding.petWidget.ivPet.alpha = 1f
                binding.petWidget.ivPet.scaleX = 1f
                binding.petWidget.ivPet.scaleY = 1f
            }
            else -> {
                binding.petWidget.ivPet.speed = 1f
                binding.petWidget.ivPet.alpha = 1f
                binding.petWidget.ivPet.scaleX = 1f
                binding.petWidget.ivPet.scaleY = 1f
            }
        }
    }

    @Suppress("SetTextI18n")
    private fun updatePetInfoBar(pet: Pet) {
        val petView = binding.petWidget
        petView.tvPetLevel.text = "Lv.${pet.level} ${pet.levelTitle}"

        // Color by tier: 0=white, 1=yellow, 2=terracotta
        val levelColor = when (pet.colorTier) {
            2 -> resources.getColor(R.color.claude_terracotta, null)
            1 -> resources.getColor(R.color.claude_warning, null)
            else -> resources.getColor(R.color.claude_text_secondary, null)
        }
        petView.tvPetLevel.setTextColor(levelColor)

        petView.miniMoodBar.progress = pet.mood
        petView.miniHungerBar.progress = pet.hunger

        // Check-in indicator
        if (pet.isCheckedInToday()) {
            petView.tvCheckIn.text = "已签到 ${pet.checkInStreak}天"
            petView.tvCheckIn.setTextColor(resources.getColor(R.color.claude_success, null))
            petView.tvCheckIn.visibility = View.VISIBLE
        } else {
            petView.tvCheckIn.text = "点击签到"
            petView.tvCheckIn.setTextColor(resources.getColor(R.color.claude_terracotta, null))
            petView.tvCheckIn.visibility = View.VISIBLE
        }
    }

    private fun applyPetColorTier(pet: Pet) {
        val lottie = binding.petWidget.ivPet
        val color = when (pet.colorTier) {
            2 -> resources.getColor(R.color.claude_terracotta, null)
            1 -> resources.getColor(R.color.claude_warning, null)
            else -> null
        }
        if (color != null) {
            lottie.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
        } else {
            lottie.colorFilter = null
        }
    }

    private fun savePetPosition(x: Float, y: Float) {
        requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
            .edit()
            .putFloat("pet_pos_x", x)
            .putFloat("pet_pos_y", y)
            .apply()
    }

    private fun spawnDragTrail(petView: View) {
        val parent = petView.parent as? ViewGroup ?: return
        val trailSize = (petView.width * 0.4f).toInt()
        val trail = View(requireContext()).apply {
            x = petView.x + (petView.width - trailSize) / 2f
            y = petView.y + (petView.height - trailSize) / 2f
            layoutParams = ViewGroup.LayoutParams(trailSize, trailSize)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(resources.getColor(R.color.claude_terracotta, null))
            }
            alpha = 0.25f
        }
        parent.addView(trail)
        trail.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).setDuration(350).withEndAction {
            parent.removeView(trail)
        }.start()
    }

    private fun bouncePet() {
        val pet = binding.petWidget.ivPet
        val petRoot = binding.petWidget.root
        val parent = petRoot.parent as View
        val isOnRightSide = petRoot.x + petRoot.width / 2f > parent.width / 2f
        val offsetX = if (isOnRightSide) 15f else -15f

        val bounce = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pet, "translationY", 0f, -20f, 0f).setDuration(300),
                ObjectAnimator.ofFloat(pet, "translationX", 0f, offsetX, 0f).setDuration(300),
                ObjectAnimator.ofFloat(pet, "scaleX", 1f, 1.1f, 1f).setDuration(300),
                ObjectAnimator.ofFloat(pet, "scaleY", 1f, 1.1f, 1f).setDuration(300)
            )
        }
        bounce.start()
    }

    @Suppress("SetTextI18n")
    private fun showPetChatDialog() {
        val petData = petViewModel.pet.value ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pet_chat, null)

        val rvMessages = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvChatMessages)
        val etInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etChatInput)
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSend)
        val tvTyping = dialogView.findViewById<TextView>(R.id.tvTyping)
        val tvPetName = dialogView.findViewById<TextView>(R.id.tvChatPetName)
        val tvPetStatus = dialogView.findViewById<TextView>(R.id.tvChatPetStatus)
        val ivAvatar = dialogView.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivPetChatAvatar)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseChat)

        // 头像用静态 drawable，避免在 dialog 关闭/打开竞态中 Glide/Lottie 加载冲突导致 native crash
        try {
            ivAvatar.setImageResource(R.drawable.pet_cat_idle)
        } catch (_: Exception) {}
        tvPetName.text = "${petData.name}  Lv.${petData.level}"
        tvPetStatus.text = petData.state.label

        // 构建用户上下文
        val user = viewModel.user.value
        val todayCourses = viewModel.todayCourses.value ?: emptyList()
        val nextExam = viewModel.nextExam.value
        val userContext = buildUserContext(user, todayCourses, nextExam)

        val chatApi = com.ifafu.kyzz.data.api.PetChatApi()
        val chipGroupModel = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupModel)
        val messages = mutableListOf<com.ifafu.kyzz.data.api.PetChatApi.ChatMessage>()

        // 加载历史聊天记录
        val account = user?.account ?: ""
        if (account.isNotEmpty()) {
            messages.addAll(cacheManager.loadChatHistory(account))
        }

        // 如果没有历史记录，添加开场白
        if (messages.isEmpty()) {
            val nextExamName = nextExam?.let { it.exam.name }
            val greeting = petViewModel.getChatGreeting(todayCourses.size, nextExamName)
            messages.add(com.ifafu.kyzz.data.api.PetChatApi.ChatMessage(greeting, false))
        }

        val adapter = ChatMessageAdapter(messages)
        rvMessages.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        rvMessages.adapter = adapter
        rvMessages.post { rvMessages.smoothScrollToPosition(messages.size - 1) }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun sendMessage() {
            val text = etInput.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) return
            etInput.text?.clear()

            messages.add(com.ifafu.kyzz.data.api.PetChatApi.ChatMessage(text, true))
            adapter.notifyItemInserted(messages.size - 1)
            rvMessages.smoothScrollToPosition(messages.size - 1)
            if (account.isNotEmpty()) cacheManager.saveChatHistory(account, messages)

            tvTyping.visibility = View.VISIBLE
            btnSend.isEnabled = false

            val currentPet = petViewModel.pet.value ?: petData
            val selectedModel = when (chipGroupModel.checkedChipId) {
                R.id.chipQwen -> com.ifafu.kyzz.data.api.PetChatApi.AiModel.QWEN
                else -> com.ifafu.kyzz.data.api.PetChatApi.AiModel.GLM
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val reply = withContext(Dispatchers.IO) {
                        chatApi.chat(text, currentPet, messages, userContext, selectedModel)
                    }
                    tvTyping.visibility = View.GONE
                    btnSend.isEnabled = true

                    messages.add(com.ifafu.kyzz.data.api.PetChatApi.ChatMessage(reply, false))
                    adapter.notifyItemInserted(messages.size - 1)
                    rvMessages.smoothScrollToPosition(messages.size - 1)
                    if (account.isNotEmpty()) cacheManager.saveChatHistory(account, messages)

                    petViewModel.onPetClicked()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tvTyping.visibility = View.GONE
                    btnSend.isEnabled = true
                }
            }
        }

        btnSend.setOnClickListener { sendMessage() }
        etInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }

        dialog.show()
    }

    private fun buildUserContext(
        user: com.ifafu.kyzz.data.model.User?,
        todayCourses: List<MainViewModel.TodayCourse>,
        nextExam: MainViewModel.ExamCountdown?
    ): com.ifafu.kyzz.data.api.PetChatApi.UserContext {
        val courseDescs = todayCourses.map {
            val time = sectionTimeMap[it.begin]?.let { (s, e) -> "$s-$e" } ?: "第${it.begin}-${it.end}节"
            "${it.name}(${time},${it.address})"
        }

        val examDesc = nextExam?.let {
            val days = if (it.daysLeft == 0) "今天" else if (it.daysLeft == 1) "明天" else "${it.daysLeft}天后"
            "${it.exam.name}($days,${it.exam.address})"
        } ?: ""

        val scores = user?.let { cacheManager.loadScores(it.account) }
        val recentScores = scores?.takeLast(5)?.map {
            "${it.courseName} ${it.score}分"
        } ?: emptyList()

        val gpaSummary = if (!scores.isNullOrEmpty()) {
            val avg = scores.map { it.score }.average()
            val avgGpa = scores.map { it.scorePoint }.average()
            "平均分${"%.1f".format(avg)}，平均绩点${"%.2f".format(avgGpa)}，共${scores.size}门课"
        } else ""

        val countdownDescs = getCountdownPairs()
            .filter { it.second >= 0 }
            .sortedBy { it.second }
            .map { (name, days) -> "距离${name}还有${days}天" }

        val prefs = requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
        val petSeeNotes = prefs.getBoolean("pet_see_notes", true)
        val noteDescs = if (petSeeNotes) {
            val noteRepo = com.ifafu.kyzz.data.repository.NoteRepository(requireContext())
            noteRepo.getPetVisibleNotes()
                .sortedByDescending { it.updatedAt }
                .take(5)
                .map { n ->
                    val preview = n.content.take(50).replace('\n', ' ')
                    "「${n.title}」$preview"
                }
        } else emptyList()

        return com.ifafu.kyzz.data.api.PetChatApi.UserContext(
            userName = user?.name ?: "",
            studentId = user?.account ?: "",
            institute = user?.institute ?: "",
            major = "",
            className = user?.clas ?: "",
            todayCourses = courseDescs,
            nextExam = examDesc,
            recentScores = recentScores,
            gpaSummary = gpaSummary,
            countdownEvents = countdownDescs,
            recentNotes = noteDescs
        )
    }

    private inner class ChatMessageAdapter(
        private val messages: List<com.ifafu.kyzz.data.api.PetChatApi.ChatMessage>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_USER = 0
        private val TYPE_PET = 1

        override fun getItemViewType(position: Int) = if (messages[position].isUser) TYPE_USER else TYPE_PET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val dp = resources.displayMetrics.density
            val isUser = viewType == TYPE_USER

            val container = FrameLayout(parent.context).apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }

            val textView = TextView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
                    if (isUser) {
                        marginStart = (48 * dp).toInt()
                    } else {
                        marginEnd = (48 * dp).toInt()
                    }
                    topMargin = (10 * dp).toInt()
                    bottomMargin = (10 * dp).toInt()
                }
                maxWidth = (280 * dp).toInt()
                textSize = 15f
                setLineSpacing(0f, 1.55f)
                typeface = android.graphics.Typeface.SANS_SERIF
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                setPadding(0, 0, 0, 0)
            }
            container.addView(textView)
            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(container) {}
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            val container = holder.itemView as FrameLayout
            val textView = container.getChildAt(0) as TextView
            textView.text = messages[position].content
        }

        override fun getItemCount() = messages.size
    }

    private fun showPetDialog() {
        val petData = petViewModel.pet.value ?: return
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pet, null)

        val ivPet = dialogView.findViewById<LottieAnimationView>(R.id.ivPetDialog)
        val tvName = dialogView.findViewById<TextView>(R.id.tvPetName)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPetTitle)
        val progressMood = dialogView.findViewById<ProgressBar>(R.id.progressMood)
        val progressHunger = dialogView.findViewById<ProgressBar>(R.id.progressHunger)
        val progressExp = dialogView.findViewById<ProgressBar>(R.id.progressExp)
        val tvMood = dialogView.findViewById<TextView>(R.id.tvMoodValue)
        val tvHunger = dialogView.findViewById<TextView>(R.id.tvHungerValue)
        val tvExp = dialogView.findViewById<TextView>(R.id.tvExpValue)
        val btnFeed = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFeed)
        val btnPlay = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPlay)

        // Set pet image based on state
        com.ifafu.kyzz.ui.pet.PetLottieManager.applyAnimation(
            requireContext(), ivPet, petData.state, petData.petType
        )

        tvName.text = "${petData.name}  Lv.${petData.level}"
        tvTitle.text = petData.levelTitle

        progressMood.progress = petData.mood
        tvMood.text = "${petData.mood}%"
        progressHunger.progress = petData.hunger
        tvHunger.text = "${petData.hunger}%"
        val expPercent = if (petData.expToNextLevel > 0) (petData.exp * 100 / petData.expToNextLevel) else 0
        progressExp.progress = expPercent
        tvExp.text = "${petData.exp}/${petData.expToNextLevel}"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        fun refreshStats() {
            val updated = petViewModel.pet.value ?: return
            progressMood.progress = updated.mood
            tvMood.text = "${updated.mood}%"
            progressHunger.progress = updated.hunger
            tvHunger.text = "${updated.hunger}%"
            val newExpPercent = if (updated.expToNextLevel > 0) (updated.exp * 100 / updated.expToNextLevel) else 0
            progressExp.progress = newExpPercent
            tvExp.text = "${updated.exp}/${updated.expToNextLevel}"
            btnFeed.text = "喂食 (${updated.remainingFeeds()}/${Pet.MAX_CHARGE})"
            btnPlay.text = "玩耍 (${updated.remainingPlays()}/${Pet.MAX_CHARGE})"
            btnFeed.isEnabled = updated.canFeed()
            btnPlay.isEnabled = updated.canPlay()
            com.ifafu.kyzz.ui.pet.PetLottieManager.applyAnimation(
                requireContext(), ivPet, updated.state, updated.petType
            )
        }

        btnFeed.setOnClickListener {
            petViewModel.feed()
            refreshStats()
        }

        btnPlay.setOnClickListener {
            petViewModel.play()
            refreshStats()
        }

        // Add chat button
        val chatButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "聊天"
            textSize = 13f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
            strokeWidth = 1
            strokeColor = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.claude_border, null))
            backgroundTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.claude_bg_elevated, null))
            cornerRadius = 20
            setOnClickListener {
                dialog.dismiss()
                showPetChatDialog()
            }
        }
        (btnFeed.parent as? LinearLayout)?.addView(chatButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 24 })

        dialog.show()
    }

    private fun showLevelUpDialog(level: Int) {
        val pet = petViewModel.pet.value
        val name = pet?.name ?: "宠物"
        val msg = when (pet?.petType) {
            "crab" -> "${name}升到了 Lv.$level！代码能力又变强了"
            "cloudling" -> "${name}升到了 Lv.$level！变得更闪亮了"
            else -> {
                val emoji = when (pet?.petType) {
                    "dog" -> "汪~"
                    "dragon" -> "吼~"
                    else -> "喵~"
                }
                "${name}升到了 Lv.$level！继续加油$emoji"
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("升级啦！")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    private fun observeUser() {
        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user.isLogin) {
                updateGreeting()
            }
            setDateInfo()
        }
        viewModel.currentWeek.observe(viewLifecycleOwner) { week ->
            if (week > 0) {
                // B1: Animated counter for chip week number
                animateChipText(binding.chipWeek, getString(R.string.chip_week, week))
                binding.chipWeek.visibility = View.VISIBLE
            } else {
                binding.chipWeek.visibility = View.GONE
            }
        }
        viewModel.todayCourses.observe(viewLifecycleOwner) { courses ->
            showTodayCourses(courses)
            val newText = if (courses.isNotEmpty()) {
                getString(R.string.chip_courses_today, courses.size)
            } else {
                getString(R.string.chip_no_courses_today)
            }
            // B1: Animated counter for courses chip
            animateChipText(binding.chipCoursesCount, newText)
        }
        viewModel.nextExam.observe(viewLifecycleOwner) { countdown ->
            showExamCountdown(countdown)
            val newExamText = if (countdown != null && countdown.daysLeft >= 0) {
                getString(R.string.chip_exam_days, countdown.daysLeft)
            } else {
                getString(R.string.chip_no_exam)
            }
            // B1: Animated counter for exam chip
            animateChipText(binding.chipExamCountdown, newExamText)
            // Pet exam reminder
            if (countdown != null && countdown.daysLeft in 0..7) {
                petViewModel.onExamComing(countdown.exam.name, countdown.daysLeft)
            }
        }
    }

    /** B1: Chip text flip animation — short scale Y collapse/expand to swap text */
    private fun animateChipText(chip: android.widget.TextView, newText: String) {
        if (chip.text == newText) return
        chip.animate().scaleY(0f).setDuration(100).withEndAction {
            chip.text = newText
            chip.animate().scaleY(1f).setDuration(150)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun updateGreeting() {
        val user = viewModel.user.value ?: return
        if (!user.isLogin) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 6 -> getString(R.string.greeting_evening, user.name)
            hour < 12 -> getString(R.string.greeting_morning, user.name)
            hour < 18 -> getString(R.string.greeting_afternoon, user.name)
            else -> getString(R.string.greeting_evening, user.name)
        }
        // C3: Typewriter effect — reveal one character at a time
        binding.tvWelcome.text = ""
        binding.tvWelcome.alpha = 1f
        val typeAnim = ValueAnimator.ofInt(0, greeting.length)
        typeAnim.duration = (greeting.length * 45L).coerceIn(400L, 1200L)
        typeAnim.interpolator = AccelerateDecelerateInterpolator()
        typeAnim.addUpdateListener { a ->
            val idx = a.animatedValue as Int
            if (_binding != null) binding.tvWelcome.text = greeting.substring(0, idx)
        }
        typeAnim.start()
        // A2: Time-aware header gradient
        updateHeaderGradient(hour)
    }

    /** A2: Solid color header with breathing animation */
    private var breathingAnimator: android.animation.ValueAnimator? = null

    private fun updateHeaderGradient(hour: Int) {
        val color = when {
            hour in 6..10  -> 0xFFD4724A.toInt()  // Morning: warm terracotta
            hour in 11..14 -> 0xFFC05E38.toInt()  // Noon: standard terracotta
            hour in 18..23 -> 0xFF2C2C2E.toInt()  // Evening: dark
            else            -> 0xFFB85A38.toInt()  // Afternoon: deeper terracotta
        }
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
        }
        val prevBackground = binding.headerBackground.background
        if (prevBackground != null) {
            val transition = android.graphics.drawable.TransitionDrawable(
                arrayOf(prevBackground, shape)
            )
            binding.headerBackground.background = transition
            transition.startTransition(800)
        } else {
            binding.headerBackground.background = shape
        }
        startBreathing()
        drawBottomArc(color)
    }

    private fun drawBottomArc(color: Int) {
        if (_binding == null) return
        binding.headerBottomArc.background = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = android.graphics.Paint.Style.FILL
            }
            private val path = android.graphics.Path()
            override fun draw(canvas: android.graphics.Canvas) {
                path.reset()
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.quadTo(w / 2f, h * 1.8f, 0f, 0f)
                path.close()
                canvas.drawPath(path, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun startBreathing() {
        breathingAnimator?.cancel()
        breathingAnimator = android.animation.ValueAnimator.ofFloat(1f, 0.82f).apply {
            duration = 2400
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                if (_binding != null) {
                    val alpha = anim.animatedValue as Float
                    binding.headerBackground.alpha = alpha
                    binding.headerBottomArc.alpha = alpha
                }
            }
            start()
        }
    }

    private fun setDateInfo() {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> getString(R.string.monday)
            Calendar.TUESDAY -> getString(R.string.tuesday)
            Calendar.WEDNESDAY -> getString(R.string.wednesday)
            Calendar.THURSDAY -> getString(R.string.thursday)
            Calendar.FRIDAY -> getString(R.string.friday)
            Calendar.SATURDAY -> getString(R.string.saturday)
            Calendar.SUNDAY -> getString(R.string.sunday)
            else -> ""
        }
        binding.tvDateInfo.text = getString(R.string.date_format, month, day, dayOfWeek)
    }

    private fun showExamCountdown(countdown: MainViewModel.ExamCountdown?) {
        if (countdown == null) {
            binding.cardExamCountdown.visibility = View.GONE
            return
        }
        binding.cardExamCountdown.visibility = View.VISIBLE
        binding.cardExamCountdown.alpha = 0f
        binding.cardExamCountdown.translationY = 16f
        binding.cardExamCountdown.animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(100).start()

        val container = binding.examCountdownContainer
        container.removeAllViews()

        val exam = countdown.exam
        val countdownText = when {
            countdown.daysLeft < 0 -> "已结束"
            countdown.daysLeft == 0 -> "今天"
            countdown.daysLeft == 1 -> "明天"
            else -> "${countdown.daysLeft}天后"
        }

        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val name = TextView(requireContext()).apply {
            text = exam.name
            setTextAppearance(R.style.ClaudeBody)
            setTextColor(resources.getColor(R.color.claude_text_primary, null))
            typeface = resources.getFont(R.font.claude_serif)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val countdownContainer = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.BOTTOM // Align to bottom baseline
        }
        
        val numberStr = countdown.daysLeft.toString()
        val textStr = when {
            countdown.daysLeft < 0 -> "已结束"
            countdown.daysLeft == 0 -> "今天"
            countdown.daysLeft == 1 -> "明天"
            else -> "天后"
        }

        if (countdown.daysLeft > 1) {
            countdownContainer.addView(TextView(requireContext()).apply {
                text = numberStr
                textSize = 20f // Larger number for premium feel
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 0, 2, 0)
            })
        }
        countdownContainer.addView(TextView(requireContext()).apply {
            text = textStr
            textSize = 13f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
            setPadding(0, 0, 0, 2) // Slight tweak to perfectly match baseline optically
        })

        row.addView(name)
        row.addView(countdownContainer)
        container.addView(row)

        val detail = TextView(requireContext()).apply {
            text = "${exam.datetime}  ${exam.address}"
            setTextAppearance(R.style.ClaudeCaption)
            typeface = resources.getFont(R.font.claude_serif)
            setPadding(0, 4, 0, 0)
        }
        container.addView(detail)
    }

    private fun showTodayCourses(courses: List<MainViewModel.TodayCourse>) {
        if (courses.isEmpty()) {
            binding.timelineContainer.visibility = View.GONE
            binding.tvTodaySection.visibility = View.GONE
            binding.courseProgressContainer.visibility = View.GONE
            binding.emptyCoursesState.visibility = View.VISIBLE
            binding.emptyCoursesState.alpha = 0f
            binding.emptyCoursesState.animate().alpha(1f).setDuration(400).start()
            // Load pet's idle Lottie for empty state
            val emptyLottie = binding.emptyLottie
            val petType = petViewModel.pet.value?.petType ?: "cat"
            if (petType in com.ifafu.kyzz.ui.pet.PetLottieManager.gifPetTypes) {
                com.ifafu.kyzz.ui.pet.PetLottieManager.applyAnimation(
                    requireContext(), emptyLottie, PetState.IDLE, petType
                )
            } else {
                val lottieFile = when (petType) {
                    "dog" -> "lottie/lottie_dog_idle.json"
                    "dragon" -> "lottie/lottie_dragon_idle.json"
                    else -> "lottie/lottie_cat_idle.json"
                }
                try {
                    emptyLottie.setAnimation(lottieFile)
                    emptyLottie.playAnimation()
                } catch (_: Exception) { }
            }
            return
        }
        binding.emptyCoursesState.visibility = View.GONE
        binding.tvTodaySection.visibility = View.VISIBLE
        binding.timelineContainer.visibility = View.VISIBLE

        // Course progress bar
        val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val totalCourses = courses.size
        val finishedCount = courses.count { nowMin > (sectionEndMinutes[it.end] ?: 0) }
        val progressPercent = if (totalCourses > 0) finishedCount * 100 / totalCourses else 0
        binding.courseProgressContainer.visibility = View.VISIBLE
        binding.courseProgressBar.max = 100
        // Animate progress bar
        val progressBar = binding.courseProgressBar
        val anim = ValueAnimator.ofInt(0, progressPercent)
        anim.duration = 600
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener { progressBar.progress = it.animatedValue as Int }
        anim.start()
        binding.courseProgressText.text = "已完成 $finishedCount/$totalCourses 节课"

        val container = binding.timelineContainer
        container.removeAllViews()

        val nowMinutes = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

        for ((i, course) in courses.withIndex()) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_timeline_course, container, false)

            val tvTimeStart = itemView.findViewById<TextView>(R.id.tvTimeStart)
            val tvTimeEnd = itemView.findViewById<TextView>(R.id.tvTimeEnd)
            val timelineDot = itemView.findViewById<View>(R.id.timelineDot)
            val timelineLine = itemView.findViewById<View>(R.id.timelineLine)
            val tvCourseName = itemView.findViewById<TextView>(R.id.tvCourseName)
            val tvCourseLocation = itemView.findViewById<TextView>(R.id.tvCourseLocation)
            val tvCourseTeacher = itemView.findViewById<TextView>(R.id.tvCourseTeacher)
            val tvCourseStatus = itemView.findViewById<TextView>(R.id.tvCourseStatus)
            // A3: Status stripe view
            val courseStatusStripe = itemView.findViewById<View>(R.id.courseStatusStripe)
            val cardCourse = itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardCourse)

            val startTime = sectionTimeMap[course.begin]?.first ?: "--:--"
            val endTime = sectionTimeMap[course.end]?.second ?: "--:--"
            tvTimeStart.text = startTime
            tvTimeEnd.text = endTime

            tvCourseName.text = course.name
            tvCourseLocation.text = course.address.ifEmpty { "-" }
            tvCourseTeacher.text = course.teacher.ifEmpty { "-" }

            // Weather info
            val tvWeather = itemView.findViewById<TextView>(R.id.tvCourseWeather)
            val courseStartHour = (sectionStartMinutes[course.begin] ?: 480) / 60
            val weather = dailyWeather?.getWeatherForHour(courseStartHour)
            if (weather != null) {
                tvWeather.text = "${com.ifafu.kyzz.data.model.DailyWeather.weatherDesc(weather.weatherCode)} · ${weather.temp}°C"
                tvWeather.visibility = View.VISIBLE
            } else {
                tvWeather.visibility = View.GONE
            }

            val courseStartMin = sectionStartMinutes[course.begin] ?: 0
            val courseEndMin = sectionEndMinutes[course.end] ?: 0

            when {
                nowMinutes > courseEndMin -> {
                    // Finished: dim it, grey dot, no stripe
                    tvCourseStatus.text = "已结束"
                    tvCourseStatus.setTextColor(resources.getColor(R.color.claude_text_hint, null))
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot_inactive)
                    courseStatusStripe.visibility = View.GONE
                    itemView.alpha = 0.65f
                    tvCourseName.alpha = 0.6f
                }
                nowMinutes in courseStartMin..courseEndMin -> {
                    // A3: In progress — show orange stripe + warm card stroke + pulse dot
                    tvCourseStatus.text = "进行中"
                    tvCourseStatus.setTextColor(resources.getColor(R.color.claude_success, null))
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot_current)
                    courseStatusStripe.visibility = View.VISIBLE
                    // Warm card border to highlight active course
                    cardCourse.strokeColor = resources.getColor(R.color.claude_terracotta_300, null)
                    cardCourse.cardElevation = 2f * resources.displayMetrics.density
                    itemView.alpha = 1f
                    // Pulsing dot animation
                    pulseAnimator?.cancel()
                    val pulseAnim = ObjectAnimator.ofFloat(timelineDot, "scaleX", 1f, 1.5f, 1f).apply {
                        duration = 1000; repeatCount = ObjectAnimator.INFINITE
                    }
                    val pulseAnimY = ObjectAnimator.ofFloat(timelineDot, "scaleY", 1f, 1.5f, 1f).apply {
                        duration = 1000; repeatCount = ObjectAnimator.INFINITE
                    }
                    pulseAnimator = AnimatorSet().apply { playTogether(pulseAnim, pulseAnimY); start() }
                }
                else -> {
                    val minutesUntil = courseStartMin - nowMinutes
                    tvCourseStatus.text = when {
                        minutesUntil <= 30 -> "还有${minutesUntil}分钟"
                        minutesUntil <= 60 -> "还有${minutesUntil}分钟"
                        else -> "还有${minutesUntil / 60}h${minutesUntil % 60}m"
                    }
                    // Upcoming soon: show subtle stripe
                    if (minutesUntil <= 30) {
                        courseStatusStripe.background = resources.getDrawable(
                            R.drawable.bg_section_accent, null)
                        courseStatusStripe.visibility = View.VISIBLE
                        tvCourseStatus.setTextColor(resources.getColor(R.color.claude_warning, null))
                    } else {
                        courseStatusStripe.visibility = View.GONE
                        tvCourseStatus.setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    }
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot)
                    itemView.alpha = 1f
                }
            }

            if (i == courses.size - 1) {
                timelineLine.visibility = View.INVISIBLE
            }

            // Click to show course detail
            itemView.setOnClickListener {
                showCourseDetail(course, startTime, endTime)
            }

            // A1: Staggered entrance animation — each card slides up with delay
            itemView.alpha = 0f
            itemView.translationY = 24f
            itemView.scaleX = 0.96f
            itemView.animate()
                .alpha(if (nowMinutes > courseEndMin) 0.65f else 1f)
                .translationY(0f)
                .scaleX(1f)
                .setDuration(380)
                .setStartDelay((i * 90).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()

            container.addView(itemView)
        }
    }

    private fun showCourseDetail(course: MainViewModel.TodayCourse, startTime: String, endTime: String) {
        val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val statusText = when {
            nowMin > (sectionEndMinutes[course.end] ?: 0) -> "已结束"
            nowMin >= (sectionStartMinutes[course.begin] ?: 0) -> "进行中"
            else -> "未开始"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(course.name)
            .setMessage(
                "时间: $startTime - $endTime（第${course.begin}-${course.end}节）\n" +
                "地点: ${course.address.ifEmpty { "未指定" }}\n" +
                "教师: ${course.teacher.ifEmpty { "未指定" }}\n" +
                "状态: $statusText"
            )
            .setPositiveButton("确定", null)
            .show()
    }

    // --- Auto update check ---

    private var cachedUpdateRelease: com.ifafu.kyzz.ui.settings.UpdateChecker.ReleaseInfo? = null

    private fun checkForUpdate() {
        val ctx = context ?: return
        if (!com.ifafu.kyzz.ui.settings.UpdateChecker.shouldCheck(ctx)) return
        android.util.Log.i("HomeFragment", "checkForUpdate called")
        com.ifafu.kyzz.ui.settings.UpdateChecker.checkForUpdate(ctx) { release ->
            android.util.Log.i("HomeFragment", "checkForUpdate callback: release=${release?.versionName}, binding=${_binding != null}")
            if (release != null) {
                com.ifafu.kyzz.ui.settings.UpdateChecker.saveCheckResult(ctx, release)
                val dismissed = com.ifafu.kyzz.ui.settings.UpdateChecker.isDismissed(ctx, release.versionName)
                android.util.Log.i("HomeFragment", "isDismissed=$dismissed for ${release.versionName}")
                if (!dismissed) {
                    showUpdateCard(release)
                }
            } else {
                com.ifafu.kyzz.ui.settings.UpdateChecker.clearCachedResult(ctx)
                if (_binding != null) {
                    binding.cardUpdate.visibility = View.GONE
                }
            }
        }
    }

    private fun showUpdateCard(release: com.ifafu.kyzz.ui.settings.UpdateChecker.ReleaseInfo) {
        if (_binding == null) return
        cachedUpdateRelease = release
        binding.cardUpdate.visibility = View.VISIBLE
        binding.tvUpdateVersion.text = "v${release.versionName}"
        binding.tvUpdateBody.text = release.body?.take(200) ?: ""
        val size = release.apkAsset?.size ?: 0L
        binding.tvUpdateSize.text = com.ifafu.kyzz.ui.settings.UpdateChecker.formatSize(size)
        binding.cardUpdate.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val sizeText = release.apkAsset?.let { com.ifafu.kyzz.ui.settings.UpdateChecker.formatSize(it.size) } ?: ""
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("发现新版本 v${release.versionName}")
                .setMessage("${release.body ?: "修复已知问题并优化体验"}\n\n大小: $sizeText")
                .setPositiveButton("下载更新") { _, _ ->
                    com.ifafu.kyzz.ui.settings.UpdateChecker.downloadAndInstall(ctx, release)
                }
                .setNegativeButton("稍后再说") { dialog, _ ->
                    com.ifafu.kyzz.ui.settings.UpdateChecker.dismissVersion(ctx, release.versionName)
                    if (_binding != null) {
                        binding.cardUpdate.visibility = View.GONE
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        breathingAnimator?.cancel()
        breathingAnimator = null
        bubbleRunnable?.let { bubbleHandler.removeCallbacks(it) }
        bubbleRunnable = null
        bubbleHandler.removeCallbacks(resumeRunnable)
        bubbleAnimator?.cancel()
        bubbleAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        homeGlowAnimator1?.cancel()
        homeGlowAnimator1 = null
        homeGlowAnimator2?.cancel()
        homeGlowAnimator2 = null
        _binding = null
    }
}
