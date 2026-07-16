package com.ifafu.kyzz.ui.main

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.NavOptions
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityMainBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.login.LoginActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var navController: NavController
    private var firstClickBack: Long = 0
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun createBinding(): ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃恢复检测：若本次启动由崩溃触发且有待反馈的崩溃记录，弹恢复对话框
        if (intent?.getBooleanExtra(com.ifafu.kyzz.MainApplication.EXTRA_CRASH_RECOVERY, false) == true) {
            maybeShowCrashRecoveryDialog()
        }

        val simpleMode = getSharedPreferences("ifafu_user", MODE_PRIVATE).getBoolean("simple_mode", false)
        if (simpleMode) {
            binding.bottomNav.menu.clear()
            binding.bottomNav.inflateMenu(R.menu.bottom_nav_menu_simple)
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        val fadeOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build()

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.syllabusFragment) {
                startActivity(Intent(this, com.ifafu.kyzz.ui.syllabus.GridSyllabusActivity::class.java))
                return@setOnItemSelectedListener true
            }
            if (navController.currentDestination?.id != item.itemId) {
                navController.navigate(item.itemId, null, fadeOptions)
            }
            // Animate the selected icon
            animateNavIcon(item.itemId)
            true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.menu.findItem(destination.id)?.isChecked = true
        }

        scheduleCourseReminder()
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun animateNavIcon(itemId: Int) {
        // Find the icon view inside the selected BottomNavigationItemView
        val menuView = binding.bottomNav.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i)
            val item = binding.bottomNav.menu.getItem(i)
            val icon = itemView.findViewById<ImageView>(com.google.android.material.R.id.icon) ?: continue
            if (item.itemId == itemId) {
                // Bounce scale animation on selected tab
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.3f, 1f).setDuration(300).start()
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.3f, 1f).setDuration(300).start()
            } else {
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
        }
    }

    /**
     * 崩溃恢复对话框：检测到上次崩溃（落盘 pending）时弹出，
     * 引导用户反馈或忽略。只在有 pending 记录时弹一次。
     */
    private fun maybeShowCrashRecoveryDialog() {
        val crashInfo = com.ifafu.kyzz.core.crash.CrashReporter.getPendingCrashReport()
            ?: return
        if (isFinishing || isDestroyed) return
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("应用已恢复")
                .setMessage(
                    "应用上次异常退出了（${crashInfo.message}）。\n" +
                    "是否把这次的崩溃信息反馈给开发者，帮助我们修复？"
                )
                .setPositiveButton("反馈问题") { _, _ ->
                    startActivity(
                        android.content.Intent(this, com.ifafu.kyzz.ui.feedback.CrashFeedbackActivity::class.java)
                    )
                }
                .setNegativeButton("忽略") { dialog, _ ->
                    com.ifafu.kyzz.core.crash.CrashReporter.clearPendingCrash()
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            com.ifafu.kyzz.util.Logger.e("MainActivity", "show crash recovery dialog failed", e)
            com.ifafu.kyzz.core.crash.CrashReporter.clearPendingCrash()
        }
    }

    private fun scheduleCourseReminder() {
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager

        val courseIntent = android.content.Intent(this, com.ifafu.kyzz.service.CourseReminderReceiver::class.java)
        val coursePending = android.app.PendingIntent.getBroadcast(
            this, 0, courseIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val courseCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 30); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        try {
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(courseCal.timeInMillis, null), coursePending)
        } catch (_: SecurityException) {
            am.set(android.app.AlarmManager.RTC_WAKEUP, courseCal.timeInMillis, coursePending)
        }

        val scoreIntent = android.content.Intent(this, com.ifafu.kyzz.service.ScoreCheckReceiver::class.java)
        val scorePending = android.app.PendingIntent.getBroadcast(
            this, 1, scoreIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val scoreCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, scoreCal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, scorePending)
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // Restore bottom nav highlight to current nav destination
        navController.currentDestination?.id?.let { destId ->
            binding.bottomNav.menu.findItem(destId)?.isChecked = true
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (navController.currentDestination?.id != R.id.homeFragment) {
                navController.popBackStack(R.id.homeFragment, false)
                binding.bottomNav.selectedItemId = R.id.homeFragment
                return true
            }
            if (System.currentTimeMillis() - firstClickBack > 2000) {
                Toast.makeText(this, "再次按下返回键退出程序", Toast.LENGTH_SHORT).show()
                firstClickBack = System.currentTimeMillis()
            } else {
                finishAffinity()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
