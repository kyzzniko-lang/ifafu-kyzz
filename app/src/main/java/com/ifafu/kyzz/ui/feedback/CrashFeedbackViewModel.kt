package com.ifafu.kyzz.ui.feedback

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.core.crash.CrashInfo
import com.ifafu.kyzz.core.crash.CrashReporter
import com.ifafu.kyzz.data.api.CrashReportApi
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CrashFeedbackViewModel @Inject constructor(
    private val crashReportApi: CrashReportApi
) : ReloginViewModel() {

    private val _crashInfo = MutableLiveData<CrashInfo?>()
    val crashInfo: LiveData<CrashInfo?> = _crashInfo

    private val _submitState = MutableLiveData<SubmitState>(SubmitState.Idle)
    val submitState: LiveData<SubmitState> = _submitState

    sealed class SubmitState {
        object Idle : SubmitState()
        object Submitting : SubmitState()
        object Success : SubmitState()
        data class Error(val message: String) : SubmitState()
    }

    fun loadCrashInfo() {
        _crashInfo.value = CrashReporter.getPendingCrashReport()
    }

    fun submit(description: String) {
        val info = _crashInfo.value ?: run {
            _submitState.value = SubmitState.Error("没有可反馈的崩溃记录")
            return
        }
        _submitState.value = SubmitState.Submitting
        viewModelScope.launch {
            try {
                val ok = crashReportApi.reportCrash(info, description)
                if (ok) {
                    CrashReporter.clearPendingCrash()
                    _submitState.value = SubmitState.Success
                } else {
                    _submitState.value = SubmitState.Error("反馈提交失败，可能是网络问题，请尝试复制日志后重试")
                }
            } catch (e: Exception) {
                Logger.e("CrashFeedbackVM", "submit failed", e)
                _submitState.value = SubmitState.Error("反馈提交失败：${e.message ?: "未知错误"}")
            }
        }
    }
}
