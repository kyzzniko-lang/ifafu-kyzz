package com.ifafu.kyzz.ui.main

import androidx.lifecycle.ViewModel
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user

    init {
        _user.value = userRepository.getUser()
        fetchTermFirstDay()
    }

    val isLoggedIn: Boolean get() = _user.value?.isLogin == true

    fun refreshUser() {
        _user.value = userRepository.getUser()
    }

    fun logout() {
        userRepository.clearUser()
        _user.value = User()
    }

    private fun fetchTermFirstDay() {
        val existing = userRepository.termFirstDay
        android.util.Log.d("MainViewModel", "fetchTermFirstDay: existing='$existing'")
        if (existing.isNotEmpty() && isValidDateFormat(existing)) return
        setDefaultTermFirstDay()
    }

    private fun isValidDateFormat(date: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(date)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setDefaultTermFirstDay() {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val year = calendar.get(Calendar.YEAR)

        val termStart = if (month in 1..6) {
            Calendar.getInstance().apply {
                set(year, Calendar.MARCH, 2, 0, 0, 0)
            }
        } else {
            Calendar.getInstance().apply {
                set(year, Calendar.SEPTEMBER, 1, 0, 0, 0)
            }
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val defaultDate = sdf.format(termStart.time)
        android.util.Log.d("MainViewModel", "setDefaultTermFirstDay: $defaultDate (month=$month, year=$year)")
        userRepository.termFirstDay = defaultDate
    }
}
