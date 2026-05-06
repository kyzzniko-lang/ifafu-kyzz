package com.ifafu.kyzz.ui.login

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityLoginBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private val viewModel: LoginViewModel by viewModels()

    override fun createBinding(): ActivityLoginBinding = ActivityLoginBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.ivCaptcha.setOnClickListener {
            viewModel.loadCaptcha()
        }

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val captcha = binding.etCaptcha.text.toString().trim()
            viewModel.login(account, password, captcha)
        }

        viewModel.captchaBitmap.observe(this) { bitmap ->
            if (bitmap != null) {
                binding.ivCaptcha.setImageBitmap(bitmap)
            }
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
                    binding.etCaptcha.text?.clear()
                    binding.tvError.visibility = View.GONE
                }
                is LoginViewModel.LoginState.Success -> {
                    showLoading(false)
                    finish()
                }
                is LoginViewModel.LoginState.Error -> {
                    showLoading(false)
                    binding.tvError.text = state.message
                    binding.tvError.visibility = View.VISIBLE
                    if (state.needCaptchaRefresh) {
                        binding.etCaptcha.text?.clear()
                    }
                }
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.btnLogin.text = if (loading) getString(R.string.btn_logining) else getString(R.string.btn_login)
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
