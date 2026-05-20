package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.databinding.ActivityPasswordModifyBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PasswordModifyActivity : BaseActivity<ActivityPasswordModifyBinding>() {

    private val viewModel: PasswordModifyViewModel by viewModels()

    override fun createBinding() = ActivityPasswordModifyBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSubmit.setOnClickListener {
            val oldPwd = binding.etOldPassword.text.toString()
            val newPwd = binding.etNewPassword.text.toString()
            val confirmPwd = binding.etConfirmPassword.text.toString()

            if (oldPwd.isEmpty()) {
                binding.tilOldPassword.error = "请输入原密码"
                return@setOnClickListener
            }
            if (newPwd.isEmpty()) {
                binding.tilNewPassword.error = "请输入新密码"
                return@setOnClickListener
            }
            if (confirmPwd.isEmpty()) {
                binding.tilConfirmPassword.error = "请输入确认密码"
                return@setOnClickListener
            }
            if (newPwd != confirmPwd) {
                binding.tilConfirmPassword.error = "两次密码不一致"
                return@setOnClickListener
            }

            viewModel.submitPassword(oldPwd, newPwd, confirmPwd)
        }

        viewModel.state.observe(this) { state ->
            when (state) {
                is PasswordModifyViewModel.State.Idle -> {}
                is PasswordModifyViewModel.State.Loading -> {
                    binding.btnSubmit.isEnabled = false
                    binding.btnSubmit.text = "提交中…"
                }
                is PasswordModifyViewModel.State.Success -> {
                    binding.btnSubmit.isEnabled = true
                    binding.btnSubmit.text = "修改密码"
                    binding.tvResult.visibility = View.VISIBLE
                    binding.tvResult.text = "密码修改成功"
                    Toast.makeText(this, "密码修改成功", Toast.LENGTH_SHORT).show()
                }
                is PasswordModifyViewModel.State.Error -> {
                    binding.btnSubmit.isEnabled = true
                    binding.btnSubmit.text = "修改密码"
                    binding.tvResult.visibility = View.VISIBLE
                    binding.tvResult.text = state.message
                }
            }
        }
    }
}
