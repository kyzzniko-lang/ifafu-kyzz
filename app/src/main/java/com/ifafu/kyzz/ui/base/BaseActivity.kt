package com.ifafu.kyzz.ui.base

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.observe
import androidx.viewbinding.ViewBinding
import com.ifafu.kyzz.MainApplication
import com.ifafu.kyzz.ui.event.GlobalEvent
import com.ifafu.kyzz.ui.event.GlobalEventBus
import com.ifafu.kyzz.ui.login.LoginActivity

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    abstract fun createBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        // On some devices (e.g. iQOO), restored fragments conflict with Hilt injection
        // and cause alternating crash patterns (1st OK, 2nd crash, 3rd OK...). Activity
        // initialization is not retryable: calling super.onCreate twice after a partial
        // failure corrupts FragmentManager state and can crash again.
        // 故仅放行"进程内配置变更重建"（旋转屏幕等，注入状态完好）；进程死亡后的
        // 恢复路径（冷启动）仍禁用恢复，让各页面重载缓存数据。
        val restore = savedInstanceState != null && MainApplication.processWarmed
        MainApplication.processWarmed = true
        super.onCreate(if (restore) savedInstanceState else null)
        binding = createBinding()
        setContentView(binding.root)

        // 订阅全局事件总线（会话过期统一跳登录、全局 Toast 兜底）
        observeGlobalEvents()
    }

    /**
     * 子类可覆盖：会话过期时的额外处理（默认行为是跳转到登录页并 finish 当前页）。
     * 例如某些页面想先保存草稿再跳转，可覆盖此方法。
     */
    protected open fun onSessionExpired() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /**
     * 子类可覆盖：全局 Toast 事件到达时的处理（默认直接 Toast）。
     */
    protected open fun onGlobalToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun observeGlobalEvents() {
        GlobalEventBus.events.observe(this) { eventWrapper ->
            val event = eventWrapper.getContentIfNotHandled() ?: return@observe
            when (event) {
                is GlobalEvent.SessionExpired -> onSessionExpired()
                is GlobalEvent.Toast -> onGlobalToast(event.message)
            }
        }
    }
}
