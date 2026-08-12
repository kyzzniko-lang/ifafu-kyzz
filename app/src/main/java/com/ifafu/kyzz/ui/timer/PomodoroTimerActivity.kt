package com.ifafu.kyzz.ui.timer

import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.repository.PetRepository
import com.ifafu.kyzz.databinding.ActivityPomodoroTimerBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PomodoroTimerActivity : BaseActivity<ActivityPomodoroTimerBinding>() {

    override fun createBinding() = ActivityPomodoroTimerBinding.inflate(layoutInflater)

    private val petRepository by lazy { PetRepository(applicationContext) }

    private val prefs by lazy { getSharedPreferences("pomodoro_prefs", MODE_PRIVATE) }

    private var timer: CountDownTimer? = null
    private var isRunning = false
    private var isBreak = false
    private var remainingMillis = FOCUS_DURATION
    /**
     * 当前这一轮的预计结束时刻（epoch ms）。仅在 [isRunning] 时有效。
     * 用于在 Activity 被销毁（按 Home、低内存）后重新进入时按真实流逝时间校正进度，
     * 避免旧实现里 onDestroy 直接 cancel 导致「专注中切后台 → 回来进度全丢、宠物不得经验」。
     */
    private var endAt = 0L

    companion object {
        private const val FOCUS_DURATION = 25 * 60 * 1000L
        private const val BREAK_DURATION = 5 * 60 * 1000L

        private const val KEY_RUNNING = "running"
        private const val KEY_IS_BREAK = "is_break"
        private const val KEY_END_AT = "end_at"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        updateTodayCount()

        // 恢复后台期间持久化的计时状态（按真实时间校正，不再依赖 savedInstanceState）
        restorePersistedState()

        updateDisplay()

        binding.btnStart.setOnClickListener {
            if (isRunning) pause() else start()
        }
        binding.btnReset.setOnClickListener { reset() }
    }

    /**
     * 从 prefs 恢复计时状态。
     * - 若上一轮尚未结束：按 endAt 与 now 的差值校正 remainingMillis，继续计时。
     * - 若在后台期间已到点：补发完成回调（专注轮补发经验 + 番茄数，休息轮只推进状态）。
     */
    private fun restorePersistedState() {
        val wasRunning = prefs.getBoolean(KEY_RUNNING, false)
        if (!wasRunning) return
        val wasBreak = prefs.getBoolean(KEY_IS_BREAK, false)
        val savedEndAt = prefs.getLong(KEY_END_AT, 0L)
        if (savedEndAt <= 0L) return

        val now = System.currentTimeMillis()
        if (savedEndAt > now) {
            // 后台期间未结束，按真实时间续上
            isBreak = wasBreak
            remainingMillis = savedEndAt - now
            start()
        } else {
            // 后台期间已结束。校正到「结束后」的 UI 状态。
            // 对专注轮：补发经验 + 番茄数；对休息轮：不补发，仅把状态切回专注待开始。
            isBreak = wasBreak
            if (wasBreak) {
                // 休息轮在后台结束 → 回到专注待开始
                isBreak = false
                remainingMillis = FOCUS_DURATION
                isRunning = false
                binding.tvStatus.text = "休息已结束，继续专注吧"
                binding.btnStart.text = "开始专注"
                binding.btnReset.text = "重置"
            } else {
                // 专注轮在后台结束 → 补发完成，然后切到休息待开始
                remainingMillis = FOCUS_DURATION
                onComplete()
                isBreak = true
                remainingMillis = BREAK_DURATION
                isRunning = false
                binding.tvStatus.text = "专注完成！休息一下吧"
                binding.btnStart.text = "开始休息"
                binding.btnReset.text = "重置"
            }
            clearPersistedState()
            updateDisplay()
        }
    }

    private fun persistState() {
        if (!isRunning) {
            clearPersistedState()
            return
        }
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putBoolean(KEY_IS_BREAK, isBreak)
            .putLong(KEY_END_AT, endAt)
            .apply()
    }

    private fun clearPersistedState() {
        prefs.edit().apply {
            remove(KEY_RUNNING)
            remove(KEY_IS_BREAK)
            remove(KEY_END_AT)
        }.apply()
    }

    private fun start() {
        isRunning = true
        binding.btnStart.text = "暂停"
        binding.tvStatus.text = if (isBreak) "休息中" else "专注中"
        if (isBreak) binding.btnReset.text = "跳过休息"
        endAt = System.currentTimeMillis() + remainingMillis
        persistState()

        timer = object : CountDownTimer(remainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                updateDisplay()
            }

            override fun onFinish() {
                if (isBreak) {
                    isBreak = false
                    remainingMillis = FOCUS_DURATION
                    binding.tvStatus.text = "休息结束，继续专注吧"
                } else {
                    onComplete()
                    isBreak = true
                    remainingMillis = BREAK_DURATION
                    binding.tvStatus.text = "专注完成！休息一下吧"
                }
                isRunning = false
                binding.btnStart.text = if (isBreak) "开始休息" else "开始专注"
                binding.btnReset.text = "重置"
                clearPersistedState()
                updateDisplay()
            }
        }.start()
    }

    private fun pause() {
        timer?.cancel()
        isRunning = false
        binding.btnStart.text = "继续"
        binding.btnReset.text = "重置"
        binding.tvStatus.text = "已暂停"
        clearPersistedState()
    }

    private fun reset() {
        timer?.cancel()
        isRunning = false
        isBreak = false
        remainingMillis = FOCUS_DURATION
        binding.btnStart.text = "开始专注"
        binding.tvStatus.text = "准备开始"
        clearPersistedState()
        updateDisplay()
    }

    private fun onComplete() {
        // 番茄数 +1
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val count = prefs.getInt("pomodoros_$today", 0) + 1
        prefs.edit().putInt("pomodoros_$today", count).apply()
        updateTodayCount()

        // 宠物 +10 经验
        val pet = petRepository.loadPet()
        pet.addExp(10)
        petRepository.savePet(pet)

        Toast.makeText(this, "完成一个番茄！宠物+10经验", Toast.LENGTH_SHORT).show()
    }

    private fun updateDisplay() {
        val totalDuration = if (isBreak) BREAK_DURATION else FOCUS_DURATION
        val minutes = (remainingMillis / 1000 / 60).toInt()
        val seconds = (remainingMillis / 1000 % 60).toInt()
        binding.tvTime.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
        binding.progressBar.progress = (remainingMillis * 100 / totalDuration).toInt()
    }

    private fun updateTodayCount() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        binding.tvTodayCount.text = prefs.getInt("pomodoros_$today", 0).toString()
    }

    override fun onPause() {
        super.onPause()
        // 切后台时持久化当前轮的结束时刻，保证回来能按真实时间校正
        persistState()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
