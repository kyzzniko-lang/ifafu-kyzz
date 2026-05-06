package com.ifafu.kyzz.ui.main

import androidx.lifecycle.ViewModel
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user

    init {
        _user.value = userRepository.getUser()
    }

    val isLoggedIn: Boolean get() = _user.value?.isLogin == true

    fun refreshUser() {
        _user.value = userRepository.getUser()
    }

    fun logout() {
        userRepository.clearUser()
        _user.value = User()
    }
}
