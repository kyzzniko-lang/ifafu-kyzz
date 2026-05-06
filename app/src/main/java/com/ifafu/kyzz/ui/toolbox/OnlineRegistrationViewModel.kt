package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

@HiltViewModel
class OnlineRegistrationViewModel @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    fun load() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val url = "${userRepository.host}/(${user.token})/bmxmb2.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121303"
                val html = htmlClient.getString(url)
                if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx")) {
                    _state.value = State.Error("获取失败，请重新登录")
                } else {
                    _state.value = State.Success(html)
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    sealed class State {
        object Loading : State()
        data class Success(val html: String) : State()
        data class Error(val message: String) : State()
    }
}
