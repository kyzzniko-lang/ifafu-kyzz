package com.ifafu.kyzz.ui.comment

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.CommentTeacherApi
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommentViewModel @Inject constructor(
    private val commentTeacherApi: CommentTeacherApi,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    private val _state = MutableLiveData<CommentState>()
    val state: LiveData<CommentState> = _state

    init {
        _state.value = CommentState.Idle
    }

    fun startComment() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = CommentState.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = CommentState.Loading
            val freshUser = userRepository.getUser()
            val response = commentTeacherApi.commentAllTeachers(
                userRepository.host, freshUser.token, freshUser.account, freshUser.name
            )
            if (response.success) {
                _state.value = CommentState.Success(response.message)
            } else {
                _state.value = CommentState.Error(response.message)
            }
        }
    }

    sealed class CommentState {
        object Idle : CommentState()
        object Loading : CommentState()
        data class Success(val message: String) : CommentState()
        data class Error(val message: String) : CommentState()
    }
}
