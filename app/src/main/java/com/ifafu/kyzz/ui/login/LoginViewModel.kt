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
                _loginState.value = LoginState.CaptchaLoaded
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
        val captcha = manualCaptcha ?: autoCaptcha
        if (captcha.isBlank()) {
            loadCaptcha()
            _loginState.value = LoginState.Error("验证码识别失败，请重试")
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            try {
                val user = User()
                val response = userApi.login(account, password, captcha, user)
                if (response.success) {
                    userRepository.saveUser(user)
                    userRepository.savePassword(password)
                    _loginState.value = LoginState.Success(user)
                } else {
                    // 登录失败时清空识别结果并重新加载验证码，确保下次点击时重新识别
                    autoCaptcha = ""
                    loadCaptcha()
                    _loginState.value = LoginState.Error(response.message)
                }
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(e.message ?: "登录失败")
            }
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
