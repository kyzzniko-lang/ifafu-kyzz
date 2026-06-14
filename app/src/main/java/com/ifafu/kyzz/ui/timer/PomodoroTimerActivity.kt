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

    companion object {
        private const val FOCUS_DURATION = 25 * 60 * 1000L
        private const val BREAK_DURATION = 5 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Restore timer state after rotation
        if (savedInstanceState != null) {
            isRunning = savedInstanceState.getBoolean("isRunning", false)
            isBreak = savedInstanceState.getBoolean("isBreak", false)
            remainingMillis = savedInstanceState.getLong("remainingMillis", FOCUS_DURATION)
        }

        updateTodayCount()
        updateDisplay()

        // If timer was running before rotation, restart it
        if (isRunning) {
            start()
        }

        binding.btnStart.setOnClickListener {
            if (isRunning) pause() else start()
        }
        binding.btnReset.setOnClickListener { reset() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isRunning", isRunning)
        outState.putBoolean("isBreak", isBreak)
        outState.putLong("remainingMillis", remainingMillis)
    }

    private fun start() {
        isRunning = true
        binding.btnStart.text = "暂停"
        binding.tvStatus.text = if (isBreak) "休息中" else "专注中"
        if (isBreak) binding.btnReset.text = "跳过休息"

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
    }

    private fun reset() {
        timer?.cancel()
        isRunning = false
        isBreak = false
        remainingMillis = FOCUS_DURATION
        binding.btnStart.text = "开始专注"
        binding.tvStatus.text = "准备开始"
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
        binding.tvTime.text = String.format("%02d:%02d", minutes, seconds)
        binding.progressBar.progress = (remainingMillis * 100 / totalDuration).toInt()
    }

    private fun updateTodayCount() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        binding.tvTodayCount.text = prefs.getInt("pomodoros_$today", 0).toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
