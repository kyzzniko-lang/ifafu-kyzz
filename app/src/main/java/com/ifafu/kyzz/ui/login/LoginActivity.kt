package com.ifafu.kyzz.ui.login

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.databinding.ActivityLoginBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private val viewModel: LoginViewModel by viewModels()

    private var accountAdapter: AccountAdapter? = null

    private var glowAnimator1: ValueAnimator? = null
    private var glowAnimator2: ValueAnimator? = null

    override fun createBinding(): ActivityLoginBinding = ActivityLoginBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupAmbientGlow()
        setupInputAnimations()
        setupAccountSelector()
        loadSavedCredentials()
        playEntryAnimation()

        binding.btnLogin.setOnClickListener { submitLogin() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin()
                true
            } else {
                false
            }
        }

        viewModel.loginState.observe(this) { state ->
            when (state) {
                is LoginViewModel.LoginState.Idle -> {
                    showLoading(false)
                    hideError()
                }
                is LoginViewModel.LoginState.Loading -> {
                    showLoading(true)
                    hideError()
                }
                is LoginViewModel.LoginState.CaptchaLoaded -> {
                    hideError()
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
                    showError(state.message)
                }
            }
        }
    }

    private fun submitLogin() {
        val account = binding.etAccount.text.toString().trim()
        // 学号可去除误输入的空格，密码必须原样提交。
        val password = binding.etPassword.text.toString()
        viewModel.login(account, password)
    }

    private fun setupAmbientGlow() {
        binding.ambientGlow?.let { glow ->
            glowAnimator1 = ValueAnimator.ofFloat(0.3f, 0.5f, 0.3f).apply {
                duration = 4000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { glow.alpha = it.animatedValue as Float }
                start()
            }
        }
        binding.ambientGlow2?.let { glow ->
            glowAnimator2 = ValueAnimator.ofFloat(0.2f, 0.35f, 0.2f).apply {
                duration = 5000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { glow.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun setupInputAnimations() {
        binding.etAccount.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            animateInputLayout(binding.tilAccount, hasFocus)
        }
        binding.etPassword.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            animateInputLayout(binding.tilPassword, hasFocus)
        }
    }

    private fun animateInputLayout(view: View, hasFocus: Boolean) {
        val alpha = if (hasFocus) 1f else 0.9f
        view.animate()
            // Keep the field inside its card bounds. Scaling a focused TextInputLayout
            // makes the parent MaterialCardView clip its left and right outline.
            .scaleX(1f).scaleY(1f)
            .alpha(alpha)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun setupAccountSelector() {
        val profiles = viewModel.getSavedProfiles()
        if (profiles.isEmpty()) {
            binding.accountSelectorLayout.visibility = View.GONE
            return
        }

        binding.accountSelectorLayout.visibility = View.VISIBLE
        accountAdapter = AccountAdapter(profiles) { profile ->
            binding.etAccount.setText(profile.account)
            binding.etPassword.setText(profile.password)
            binding.accountSelectorLayout.animate()
                .alpha(0f).translationY(-10f)
                .setDuration(200)
                .withEndAction { binding.accountSelectorLayout.visibility = View.GONE }
                .start()
        }

        binding.rvAccounts.apply {
            layoutManager = LinearLayoutManager(this@LoginActivity)
            adapter = accountAdapter
        }
    }

    private fun loadSavedCredentials() {
        val profiles = viewModel.getSavedProfiles()
        if (profiles.size == 1) {
            val profile = profiles.first()
            binding.etAccount.setText(profile.account)
            binding.etPassword.setText(profile.password)
        }
    }

    private fun playEntryAnimation() {
        val views = listOf(
            binding.ivLogo,
            binding.tvTitle,
            binding.tvSubtitle,
            binding.tilAccount,
            binding.tilPassword,
            binding.btnLogin.parent as View
        )

        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(100L + index * 80)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.alpha = 0f
        binding.tvError.translationY = -10f
        binding.tvError.animate()
            .alpha(1f).translationY(0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    private fun hideError() {
        if (binding.tvError.visibility == View.VISIBLE) {
            binding.tvError.animate()
                .alpha(0f).translationY(-10f)
                .setDuration(200)
                .withEndAction { binding.tvError.visibility = View.GONE }
                .start()
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.btnLogin.text = if (loading) "" else getString(R.string.btn_login)
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showWelcomeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<MaterialButton>(R.id.btnDismiss)
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
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    inner class AccountAdapter(
        private val accounts: List<UserRepository.AccountProfile>,
        private val onClick: (UserRepository.AccountProfile) -> Unit
    ) : RecyclerView.Adapter<AccountAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAccount: TextView = view.findViewById(R.id.tvAccount)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_account, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = accounts[position]
            holder.tvAccount.text = account.account
            holder.tvName.text = account.name.ifEmpty { "未命名" }
            holder.itemView.setOnClickListener { onClick(account) }
            holder.btnRemove.setOnClickListener {
                viewModel.removeAccount(account.account)
                val newList = viewModel.getSavedProfiles()
                if (newList.isEmpty()) {
                    binding.accountSelectorLayout.visibility = View.GONE
                } else {
                    // Rebuild adapter to avoid stale position references
                    accountAdapter = AccountAdapter(newList, onClick)
                    binding.rvAccounts.adapter = accountAdapter
                }
            }
        }

        override fun getItemCount() = accounts.size
    }

    override fun onDestroy() {
        super.onDestroy()
        glowAnimator1?.cancel()
        glowAnimator2?.cancel()
    }
}
