package com.ifafu.kyzz.ui.login

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.UserApi
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.ZFVerify
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userApi: UserApi,
    private val userRepository: UserRepository,
    private val zfVerify: ZFVerify
) : ViewModel() {

    private val _loginState = MutableLiveData<LoginState>()
    val loginState: LiveData<LoginState> = _loginState

    private val _captchaBitmap = MutableLiveData<Bitmap?>()
    val captchaBitmap: LiveData<Bitmap?> = _captchaBitmap

    private var autoCaptcha: String = ""
    private var isLoadingCaptcha = false
    private var pendingErrorMessage: String? = null

    fun getSavedProfiles(): List<UserRepository.AccountProfile> = userRepository.getAccountProfiles()

    fun removeAccount(account: String) = userRepository.removeAccount(account)

    init {
        _loginState.value = LoginState.Idle
        loadCaptcha()
    }

    fun loadCaptcha() {
        if (isLoadingCaptcha) return
        isLoadingCaptcha = true
        viewModelScope.launch {
            try {
                val bitmap = userApi.prepareLogin(userRepository.host)
                if (bitmap == null) {
                    _loginState.value = LoginState.Error("无法连接教务系统")
                    return@launch
                }
                _captchaBitmap.value = bitmap
                autoCaptcha = if (zfVerify.initialized) {
                    zfVerify.recognize(bitmap)
                } else {
                    ""
                }
                // 如果有等待显示的错误消息（登录失败后刷新验证码），重新显示错误
                val errorMsg = pendingErrorMessage
                pendingErrorMessage = null
                if (errorMsg != null) {
                    _loginState.value = LoginState.Error(errorMsg)
                } else {
                    _loginState.value = LoginState.CaptchaLoaded
                }
            } catch (e: Exception) {
                autoCaptcha = ""
                _loginState.value = LoginState.Error(e.message ?: "加载验证码失败")
            } finally {
                isLoadingCaptcha = false
            }
        }
    }

    fun login(account: String, password: String, manualCaptcha: String? = null) {
        if (account.isBlank() || password.isBlank()) {
            _loginState.value = LoginState.Error("请输入学号和密码")
            return
        }
        if (isLoadingCaptcha) {
            _loginState.value = LoginState.Error("验证码加载中，请稍候")
            return
        }
        val captcha = manualCaptcha ?: autoCaptcha
        if (captcha.isBlank()) {
            _loginState.value = LoginState.Error("验证码识别失败，请重试")
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            try {
                val user = User()
                var response = attemptLogin(account, password, captcha, user)

                // 仅当用户未手动输入验证码、且失败原因是验证码错时，自动刷新重试最多 2 次
                if (!response.success && manualCaptcha == null) {
                    var attempt = 0
                    while (!response.success && attempt < 2 && isCaptchaError(response.message)) {
                        attempt++
                        val freshCaptcha = refreshCaptchaForRetry() ?: break
                        response = attemptLogin(account, password, freshCaptcha, user)
                    }
                }

                if (response.success) {
                    userRepository.saveUser(user)
                    userRepository.savePassword(password)
                    _loginState.value = LoginState.Success(user)
                } else {
                    // 登录失败时保存错误消息，刷新验证码后重新显示
                    pendingErrorMessage = response.message
                    loadCaptcha()
                }
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(e.message ?: "登录失败")
            }
        }
    }

    /** 包一层 try/catch 把 HtmlClient 抛出的 AlertException 转成 Response，方便统一判定 */
    private suspend fun attemptLogin(
        account: String,
        password: String,
        captcha: String,
        user: User
    ): com.ifafu.kyzz.data.model.Response {
        return try {
            userApi.login(account, password, captcha, user)
        } catch (e: com.ifafu.kyzz.data.network.AlertException) {
            com.ifafu.kyzz.data.model.Response(false, -1, e.message ?: "登录失败")
        }
    }

    private fun isCaptchaError(message: String?): Boolean {
        if (message == null) return false
        return message.contains("验证码")
    }

    /** 刷新验证码并尝试本地识别，返回识别结果；若识别失败/网络失败返回 null。不更新 LiveData，避免 UI 闪烁 */
    private suspend fun refreshCaptchaForRetry(): String? {
        return try {
            val bitmap = userApi.prepareLogin(userRepository.host) ?: return null
            _captchaBitmap.value = bitmap
            val recognized = if (zfVerify.initialized) zfVerify.recognize(bitmap) else ""
            if (recognized.isBlank()) null else {
                autoCaptcha = recognized
                recognized
            }
        } catch (e: Exception) {
            null
        }
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object CaptchaLoaded : LoginState()
        data class Success(val user: User) : LoginState()
        data class Error(val message: String) : LoginState()
    }
}
