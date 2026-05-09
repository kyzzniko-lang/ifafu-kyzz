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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.airbnb.lottie.LottieAnimationView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Pet
import javax.inject.Inject
import com.ifafu.kyzz.data.model.PetState
import com.ifafu.kyzz.databinding.FragmentHomeBinding
import com.ifafu.kyzz.ui.pet.PetViewModel
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

    private val bubbleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bubbleRunnable: Runnable? = null

    private val sectionTimeMap = mapOf(
        1 to Pair("08:00", "08:45"),
        2 to Pair("08:55", "09:40"),
        3 to Pair("10:10", "10:55"),
        4 to Pair("11:05", "11:50"),
        5 to Pair("12:00", "12:45"),
        6 to Pair("14:00", "14:45"),
        7 to Pair("14:55", "15:40"),
        8 to Pair("16:00", "16:45"),
        9 to Pair("16:55", "17:40"),
        10 to Pair("19:00", "19:45"),
        11 to Pair("19:55", "20:40"),
        12 to Pair("20:50", "21:35")
    )

    private val sectionStartMinutes = mapOf(
        1 to 480, 2 to 535, 3 to 610, 4 to 665, 5 to 720,
        6 to 840, 7 to 895, 8 to 960, 9 to 1015,
        10 to 1140, 11 to 1195, 12 to 1250
    )

    private val sectionEndMinutes = mapOf(
        1 to 525, 2 to 580, 3 to 655, 4 to 710, 5 to 765,
        6 to 885, 7 to 940, 8 to 1005, 9 to 1060,
        10 to 1185, 11 to 1240, 12 to 1295
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeUser()
        setupPet()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUser()
        updateGreeting()
        petViewModel.reloadPet()
        petViewModel.updateState()
        // Auto bubble on resume
        val courses = viewModel.todayCourses.value ?: emptyList()
        val exam = viewModel.nextExam.value
        val examInfo = exam?.let { it.exam.name to it.daysLeft }
        view?.postDelayed({
            petViewModel.triggerRandomBubble(courses.size, examInfo)
        }, 2000)
    }

    private fun setupViews() {
        binding.gridSyllabus.setOnClickListener {
            startActivity(Intent(requireContext(), GridSyllabusActivity::class.java))
        }
        binding.gridElective.setOnClickListener {
            startActivity(Intent(requireContext(), com.ifafu.kyzz.ui.elective.SimpleElectiveActivity::class.java))
        }
        binding.gridToolbox.setOnClickListener {
            startActivity(Intent(requireContext(), KyzzToolboxActivity::class.java))
        }
        // Grid entry press feedback: scale on touch
        listOf(binding.gridSyllabus, binding.gridElective, binding.gridToolbox).forEach { entry ->
            entry.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(100).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                    }
                }
                false
            }
        }

        // Pull-to-refresh
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshUser()
            petViewModel.onPetClicked() // pet reacts to refresh
            binding.swipeRefresh.postDelayed({ binding.swipeRefresh.isRefreshing = false }, 800)
        }

        // Entrance animation for chips
        binding.chipsContainer.alpha = 0f
        binding.chipsContainer.translationY = 20f
        binding.chipsContainer.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(200).start()
    }

    private fun setupPet() {
        val prefs = requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("show_pet", true)) {
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
                    // Only start dragging after long press timeout + sufficient movement
                    if (!isDragging && elapsed >= longPressTimeout && moveDist > touchSlop) {
                        isDragging = true
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
                            .x(snapX).y(snapY)
                            .setDuration(250)
                            .setInterpolator(OvershootInterpolator(1.2f))
                            .withEndAction { savePetPosition(snapX, snapY) }
                            .start()
                        // Slight wobble
                        v.animate().rotation(3f).setDuration(80).withEndAction {
                            v.animate().rotation(-3f).setDuration(80).withEndAction {
                                v.animate().rotation(0f).setDuration(60).start()
                            }.start()
                        }.start()
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
                petView.petBubble.translationY = 10f
                petView.petBubble.animate().alpha(1f).translationY(0f).setDuration(200).start()
                // Typewriter: reveal one char at a time
                val animator = ValueAnimator.ofInt(0, text.length)
                animator.duration = (text.length * 40L).coerceAtMost(1200L)
                animator.interpolator = AccelerateDecelerateInterpolator()
                animator.addUpdateListener { a ->
                    val idx = a.animatedValue as Int
                    petView.tvBubbleText.text = text.substring(0, idx)
                }
                animator.start()
            } else {
                petView.petBubble.animate().alpha(0f).setDuration(200).withEndAction {
                    petView.petBubble.visibility = View.GONE
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
                    petViewModel.triggerRandomBubble(courses.size, examInfo)
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

        // Adjust animation by state
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
            2 -> android.graphics.Color.parseColor("#C75B39")
            1 -> android.graphics.Color.parseColor("#D4A017")
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
        val bounce = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pet, "translationY", 0f, -20f, 0f).setDuration(300),
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
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCloseChat)

        com.ifafu.kyzz.ui.pet.PetLottieManager.applyAnimation(
            requireContext(), ivAvatar, petData.state, petData.petType
        )
        tvPetName.text = "${petData.name}  Lv.${petData.level}"
        tvPetStatus.text = petData.state.label

        // 构建用户上下文
        val user = viewModel.user.value
        val todayCourses = viewModel.todayCourses.value ?: emptyList()
        val nextExam = viewModel.nextExam.value
        val userContext = buildUserContext(user, todayCourses, nextExam)

        val chatApi = com.ifafu.kyzz.data.api.PetChatApi()
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
            lifecycleScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    chatApi.chat(text, currentPet, messages, userContext)
                }
                tvTyping.visibility = View.GONE
                btnSend.isEnabled = true

                messages.add(com.ifafu.kyzz.data.api.PetChatApi.ChatMessage(reply, false))
                adapter.notifyItemInserted(messages.size - 1)
                rvMessages.smoothScrollToPosition(messages.size - 1)
                if (account.isNotEmpty()) cacheManager.saveChatHistory(account, messages)

                petViewModel.onPetClicked()
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

        return com.ifafu.kyzz.data.api.PetChatApi.UserContext(
            userName = user?.name ?: "",
            studentId = user?.account ?: "",
            institute = user?.institute ?: "",
            major = "",
            className = user?.clas ?: "",
            todayCourses = courseDescs,
            nextExam = examDesc,
            recentScores = recentScores,
            gpaSummary = gpaSummary
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
            val margin = (8 * dp).toInt()

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
                    if (viewType == TYPE_USER) {
                        gravity = android.view.Gravity.END
                        marginStart = margin * 4
                    } else {
                        gravity = android.view.Gravity.START
                        marginEnd = margin * 4
                    }
                    topMargin = margin / 2
                    bottomMargin = margin / 2
                }
                maxWidth = (260 * dp).toInt()
                setPadding(margin, margin / 2, margin, margin / 2)
                textSize = 14f
                typeface = resources.getFont(R.font.claude_serif)
                background = if (viewType == TYPE_USER) {
                    resources.getDrawable(R.drawable.bg_chat_msg_user, null)
                } else {
                    resources.getDrawable(R.drawable.bg_chat_msg_pet, null)
                }
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
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
            btnFeed.text = "喂食 (${updated.remainingFeeds()}/${Pet.MAX_DAILY_FEED})"
            btnPlay.text = "玩耍 (${updated.remainingPlays()}/${Pet.MAX_DAILY_PLAY})"
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
        val emoji = when (pet?.petType) {
            "dog" -> "汪~"
            "dragon" -> "吼~"
            else -> "喵~"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("升级啦！")
            .setMessage("${name}升到了 Lv.$level！继续加油$emoji")
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
                binding.chipWeek.text = getString(R.string.chip_week, week)
                binding.chipWeek.visibility = View.VISIBLE
            } else {
                binding.chipWeek.visibility = View.GONE
            }
        }
        viewModel.todayCourses.observe(viewLifecycleOwner) { courses ->
            showTodayCourses(courses)
            binding.chipCoursesCount.text = if (courses.isNotEmpty()) {
                getString(R.string.chip_courses_today, courses.size)
            } else {
                getString(R.string.chip_no_courses_today)
            }
        }
        viewModel.nextExam.observe(viewLifecycleOwner) { countdown ->
            showExamCountdown(countdown)
            binding.chipExamCountdown.text = if (countdown != null && countdown.daysLeft >= 0) {
                getString(R.string.chip_exam_days, countdown.daysLeft)
            } else {
                getString(R.string.chip_no_exam)
            }
            // Pet exam reminder
            if (countdown != null && countdown.daysLeft in 0..7) {
                petViewModel.onExamComing(countdown.exam.name, countdown.daysLeft)
            }
        }
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
        binding.tvWelcome.text = greeting
        binding.tvWelcome.alpha = 0f
        binding.tvWelcome.animate().alpha(1f).setDuration(600).start()
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
        binding.cardExamCountdown.animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(100).start()
        binding.cardExamCountdown.translationY = 16f

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
        val countdownTv = TextView(requireContext()).apply {
            text = countdownText
            textSize = 16f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
        }
        row.addView(name)
        row.addView(countdownTv)
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
            val lottieFile = when (petType) {
                "dog" -> "lottie/lottie_dog_idle.json"
                "dragon" -> "lottie/lottie_dragon_idle.json"
                else -> "lottie/lottie_cat_idle.json"
            }
            try {
                emptyLottie.setAnimation(lottieFile)
                emptyLottie.playAnimation()
            } catch (_: Exception) { }
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
        val anim = ValueAnimator.ofInt(0, progressPercent)
        anim.duration = 600
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener { binding.courseProgressBar.progress = it.animatedValue as Int }
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

            val startTime = sectionTimeMap[course.begin]?.first ?: "--:--"
            val endTime = sectionTimeMap[course.end]?.second ?: "--:--"
            tvTimeStart.text = startTime
            tvTimeEnd.text = endTime

            tvCourseName.text = course.name
            tvCourseLocation.text = course.address.ifEmpty { "-" }
            tvCourseTeacher.text = course.teacher.ifEmpty { "-" }

            val courseStartMin = sectionStartMinutes[course.begin] ?: 0
            val courseEndMin = sectionEndMinutes[course.end] ?: 0

            when {
                nowMinutes > courseEndMin -> {
                    tvCourseStatus.text = "已结束"
                    tvCourseStatus.setTextColor(resources.getColor(R.color.claude_text_hint, null))
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot_inactive)
                    itemView.alpha = 0.5f
                }
                nowMinutes in courseStartMin..courseEndMin -> {
                    tvCourseStatus.text = "进行中"
                    tvCourseStatus.setTextColor(resources.getColor(R.color.claude_success, null))
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot_current)
                    itemView.alpha = 1f
                }
                else -> {
                    val minutesUntil = courseStartMin - nowMinutes
                    tvCourseStatus.text = when {
                        minutesUntil <= 60 -> "还有${minutesUntil}分钟"
                        else -> "还有${minutesUntil / 60}小时${minutesUntil % 60}分钟"
                    }
                    tvCourseStatus.setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    timelineDot.setBackgroundResource(R.drawable.timeline_dot)
                    itemView.alpha = 1f
                }
            }

            if (i == courses.size - 1) {
                timelineLine.visibility = View.INVISIBLE
            }

            // Click to show course detail dialog
            itemView.setOnClickListener {
                showCourseDetail(course, startTime, endTime)
            }

            itemView.alpha = 0f
            itemView.translationY = 16f
            itemView.animate()
                .alpha(if (nowMinutes > courseEndMin) 0.5f else 1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay((i * 80).toLong())
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

    override fun onDestroyView() {
        super.onDestroyView()
        bubbleRunnable?.let { bubbleHandler.removeCallbacks(it) }
        bubbleRunnable = null
        _binding = null
    }
}
