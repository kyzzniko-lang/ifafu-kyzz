package com.ifafu.kyzz.ui.exam

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ExamApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examApi: ExamApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    companion object {
        private const val TAG = "ExamViewModel"
    }

    private val _state = MutableLiveData<UiState<ExamTable>>()
    val state: LiveData<UiState<ExamTable>> = _state

    init {
        _state.value = UiState.Idle
    }

    fun getUser() = userRepository.getUser()

    fun loadExams(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val isStale = cacheManager.isCacheStale(user.account, "exams", 12 * 60 * 60 * 1000L) // 12 hours
            if (!isStale) {
                val cached = cacheManager.loadExamTable(user.account)
                if (cached != null && cached.exams.isNotEmpty()) {
                    // 检测缓存中的考试是否都已过期（属于旧学期）
                    if (!isAllExamsExpired(cached.exams)) {
                        _state.value = UiState.Success(cached)
                        return
                    }
                    // 缓存中的考试全部过期，强制刷新
                }
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val examTable = examApi.getExamTable(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (examTable != null) {
                    cacheManager.saveExamTable(freshUser.account, examTable)
                    _state.value = UiState.Success(examTable)
                } else {
                    val cached = cacheManager.loadExamTable(freshUser.account)
                    if (cached != null && cached.exams.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取考试信息失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取考试信息失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load exams", e)
                val cached = cacheManager.loadExamTable(userRepository.getUser().account)
                if (cached != null && cached.exams.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }

    /**
     * 检测考试列表中的所有考试是否都已过期（考试日期都在3天前或更早）。
     * 用于判断缓存是否属于旧学期。
     */
    private fun isAllExamsExpired(exams: List<com.ifafu.kyzz.data.model.Exam>): Boolean {
        if (exams.isEmpty()) return true
        val datePatterns = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy年MM月dd日", java.util.Locale.getDefault())
        )
        val now = java.util.Calendar.getInstance()
        for (exam in exams) {
            if (exam.datetime.isEmpty()) continue
            try {
                val raw = exam.datetime
                    .replace("（", "(").replace("）", ")")
                    .replace("～", "~").replace("至", "~")
                val datePart = raw.split("(", "~", " ").first().trim()
                for (fmt in datePatterns) {
                    try {
                        val parsed = fmt.parse(datePart) ?: continue
                        val examCal = java.util.Calendar.getInstance().apply { time = parsed }
                        val diffMs = now.timeInMillis - examCal.timeInMillis
                        val diffDays = diffMs / (24 * 60 * 60 * 1000L)
                        // 只要有一场考试在3天内或未来，就不算全部过期
                        if (diffDays < 3) return false
                        break
                    } catch (_: Exception) { continue }
                }
            } catch (_: Exception) { continue }
        }
        return true
    }
}
