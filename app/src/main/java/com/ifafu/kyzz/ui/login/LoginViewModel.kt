package com.ifafu.kyzz.ui.login

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.UserApi
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userApi: UserApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _loginState = MutableLiveData<LoginState>()
    val loginState: LiveData<LoginState> = _loginState

    private val _captchaBitmap = MutableLiveData<Bitmap?>()
    val captchaBitmap: LiveData<Bitmap?> = _captchaBitmap

    init {
        _loginState.value = LoginState.Idle
        loadCaptcha()
    }

    fun loadCaptcha() {
        viewModelScope.launch {
            try {
                val bitmap = userApi.prepareLogin(userRepository.host)
                if (bitmap == null) {
                    _loginState.value = LoginState.Error("无法连接教务系统")
                    return@launch
                }
                _captchaBitmap.value = bitmap
                _loginState.value = LoginState.CaptchaLoaded
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(e.message ?: "加载验证码失败")
            }
        }
    }

    fun login(account: String, password: String, captcha: String) {
        if (account.isBlank() || password.isBlank()) {
            _loginState.value = LoginState.Error("请输入学号和密码")
            return
        }
        if (captcha.isBlank()) {
            _loginState.value = LoginState.Error("请输入验证码")
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
                    loadCaptcha()
                    _loginState.value = LoginState.Error(response.message, needCaptchaRefresh = true)
                }
            } catch (e: Exception) {
                loadCaptcha()
                _loginState.value = LoginState.Error(e.message ?: "登录失败", needCaptchaRefresh = true)
            }
        }
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object CaptchaLoaded : LoginState()
        data class Success(val user: User) : LoginState()
        data class Error(val message: String, val needCaptchaRefresh: Boolean = false) : LoginState()
    }
}
