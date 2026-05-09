package com.ifafu.kyzz.ui.main

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.viewModels
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

    override fun createBinding(): ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, courseCal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, coursePending)

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
        viewModel.refreshUser()
        if (!viewModel.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
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
