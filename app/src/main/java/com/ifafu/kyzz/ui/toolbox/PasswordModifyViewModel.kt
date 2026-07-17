package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.api.UserApi
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class PasswordModifyViewModel @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userRepository: UserRepository,
    private val userApi: UserApi
) : ViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    init {
        _state.value = State.Idle
    }

    fun submitPassword(oldPwd: String, newPwd: String, confirmPwd: String) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }

        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val host = userRepository.host
                val token = user.token
                val account = user.account

                val pageUrl = "${host}/(${token})/mmxg.aspx?xh=${account}&rmm=true&gnmkdm=N121502"

                val pageResult = htmlClient.getStringWithViewState(pageUrl)

                val formBody = htmlClient.buildViewStateFormBody(pageResult.viewState)
                    .add("TextBox2", oldPwd)
                    .add("TextBox3", newPwd)
                    .add("TextBox4", confirmPwd)
                    .add("Button1", "修  改")
                    .build()

                // Do not use postWithFollow here: the school's normal success
                // response is a JavaScript alert("修改成功"), not an error.
                val html = htmlClient.postStringRaw(pageUrl, formBody)

                val alert = htmlClient.checkAlert(html)
                // 成功判断必须优先：ZF 修改成功后常把旧会话失效，响应里可能带登录表单特征，
                // 此时 isSessionExpired 会返回 true。若先判会话过期，就永远走不到 savePassword，
                // 导致服务器密码已改、App 还存旧密码 → 后续自动重登全失败，用户被锁死。
                val isSuccess = alert?.message?.contains("成功") == true ||
                    html.contains("修改成功") ||
                    html.contains("密码修改成功")
                if (isSuccess) {
                    // 教务系统修改成功后，旧会话可能立即失效。先保存新密码，
                    // 再主动建立新会话，避免用户返回其它页面后才突然报登录过期。
                    userRepository.savePassword(newPwd)
                    val relogin = withTimeoutOrNull(20_000L) { userApi.relogin() }
                    _state.value = if (relogin?.success == true) {
                        State.Success("密码修改成功，登录状态已同步")
                    } else {
                        State.Success("密码已修改，请返回登录页重新登录")
                    }
                } else if (userApi.isSessionExpired(html)) {
                    _state.value = State.Error("会话已过期，请重新登录")
                } else if (alert != null && !alert.message.contains("成功")) {
                    _state.value = State.Error(alert.message)
                } else {
                    _state.value = State.Error("教务系统未返回成功结果，请确认密码是否已修改")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "提交失败")
            }
        }
    }

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Success(val message: String) : State()
        data class Error(val message: String) : State()
    }
}
