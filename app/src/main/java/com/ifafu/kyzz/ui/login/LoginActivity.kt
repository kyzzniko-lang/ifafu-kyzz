package com.ifafu.kyzz.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityLoginBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private val viewModel: LoginViewModel by viewModels()

    override fun createBinding(): ActivityLoginBinding = ActivityLoginBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            viewModel.login(account, password)
        }

        viewModel.loginState.observe(this) { state ->
            when (state) {
                is LoginViewModel.LoginState.Idle -> {
                    showLoading(false)
                    binding.tvError.visibility = View.GONE
                }
                is LoginViewModel.LoginState.Loading -> {
                    showLoading(true)
                    binding.tvError.visibility = View.GONE
                }
                is LoginViewModel.LoginState.CaptchaLoaded -> {
                    binding.tvError.visibility = View.GONE
                }
                is LoginViewModel.LoginState.Success -> {
                    showLoading(false)
                    val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
                    if (!prefs.getBoolean("has_seen_welcome", false)) {
                        prefs.edit().putBoolean("has_seen_welcome", true).apply()
                        showWelcomeDialog()
                    } else {
                        goToMain()
                    }
                }
                is LoginViewModel.LoginState.Error -> {
                    showLoading(false)
                    binding.tvError.text = state.message
                    binding.tvError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showWelcomeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDismiss)
            .setOnClickListener {
                dialog.dismiss()
                goToMain()
            }
        dialog.show()
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun showLoading(loading: Boolean) {
        binding.btnLogin.text = if (loading) getString(R.string.btn_logining) else getString(R.string.btn_login)
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
