package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.ViewModel
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ToolboxViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    val termFirstDay: String get() = userRepository.termFirstDay

    fun saveTermFirstDay(date: String) {
        userRepository.termFirstDay = date
        userRepository.termFirstDayManual = true
    }

    fun getAccountProfiles() = userRepository.getAccountProfiles()

    fun switchAccount(profile: UserRepository.AccountProfile) {
        userRepository.switchAccount(profile)
    }

    fun removeAccount(account: String) = userRepository.removeAccount(account)

    fun getCurrentAccount() = userRepository.getUser().account
}
