package com.ifafu.kyzz.ui.about

import androidx.lifecycle.ViewModel
import com.ifafu.kyzz.util.AppUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor() : ViewModel() {

    private val _versionName = MutableLiveData<String>()
    val versionName: LiveData<String> = _versionName

    fun loadVersion(context: android.content.Context) {
        _versionName.value = AppUtil.getLocalVersionName(context)
    }
}
