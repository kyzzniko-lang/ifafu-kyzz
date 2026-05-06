package com.ifafu.kyzz.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityMainBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.elective.ElectiveCourseActivity
import com.ifafu.kyzz.ui.exam.ExamActivity
import com.ifafu.kyzz.ui.login.LoginActivity
import com.ifafu.kyzz.ui.score.ScoreActivity
import com.ifafu.kyzz.ui.syllabus.GridSyllabusActivity
import com.ifafu.kyzz.ui.syllabus.SyllabusActivity
import com.ifafu.kyzz.ui.toolbox.KyzzToolboxActivity
import com.ifafu.kyzz.ui.toolbox.ToolboxActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val viewModel: MainViewModel by viewModels()
    private var firstClickBack: Long = 0

    override fun createBinding(): ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupViews()
        observeUser()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUser()
        if (!viewModel.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun setupViews() {
        binding.cardSyllabus.setOnClickListener {
            startActivity(Intent(this, SyllabusActivity::class.java))
        }
        binding.cardGridSyllabus.setOnClickListener {
            startActivity(Intent(this, GridSyllabusActivity::class.java))
        }
        binding.cardScore.setOnClickListener {
            startActivity(Intent(this, ScoreActivity::class.java))
        }
        binding.cardExam.setOnClickListener {
            startActivity(Intent(this, ExamActivity::class.java))
        }
        binding.cardElective.setOnClickListener {
            startActivity(Intent(this, ElectiveCourseActivity::class.java))
        }
        binding.cardToolbox.setOnClickListener {
            startActivity(Intent(this, ToolboxActivity::class.java))
        }
        binding.cardKyzzToolbox.setOnClickListener {
            startActivity(Intent(this, KyzzToolboxActivity::class.java))
        }
        binding.cardLogout.setOnClickListener { showLogoutConfirm() }
    }

    private fun observeUser() {
        viewModel.user.observe(this) { user ->
            if (user.isLogin) {
                binding.tvWelcome.text = getString(R.string.main_welcome, user.name)
            }
        }
    }

    private fun showLogoutConfirm() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_logout)
            .setPositiveButton(R.string.nav_logout) { _, _ ->
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (System.currentTimeMillis() - firstClickBack > 2000) {
                Toast.makeText(this, "再次按下返回键退出程序", Toast.LENGTH_SHORT).show()
                firstClickBack = System.currentTimeMillis()
            } else {
                finish()
                System.exit(0)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
