package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class PasswordModifyViewModel @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userRepository: UserRepository
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

                val pageUrl = "${host}/(${token})/mmxg.aspx?xh=${account}&gnmkdm=N121502"

                val pageResult = htmlClient.getStringWithViewState(pageUrl)

                val formBody = htmlClient.buildViewStateFormBody(pageResult.viewState)
                    .add("TextBox2", oldPwd)
                    .add("TextBox3", newPwd)
                    .add("Textbox4", confirmPwd)
                    .add("Button1", "修  改")
                    .build()

                val result = htmlClient.postWithFollow(pageUrl, formBody)
                val html = result.html

                val alert = htmlClient.checkAlert(html)
                if (alert != null && !alert.message.contains("修改成功")) {
                    _state.value = State.Error(alert.message)
                } else {
                    userRepository.savePassword(newPwd)
                    _state.value = State.Success
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
        object Success : State()
        data class Error(val message: String) : State()
    }
}
