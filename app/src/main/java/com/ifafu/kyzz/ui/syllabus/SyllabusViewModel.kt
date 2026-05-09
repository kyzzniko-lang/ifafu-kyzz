package com.ifafu.kyzz.ui.syllabus

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.SyllabusApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyllabusViewModel @Inject constructor(
    private val syllabusApi: SyllabusApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<Syllabus>>()
    val state: LiveData<UiState<Syllabus>> = _state

    private val _availableYears = MutableLiveData<List<String>>()
    val availableYears: LiveData<List<String>> = _availableYears

    private val _availableTerms = MutableLiveData<List<String>>()
    val availableTerms: LiveData<List<String>> = _availableTerms

    var selectedYear: String? = null
        private set
    var selectedTerm: String? = null
        private set
    var isCurrentTerm: Boolean = true
        private set

    // 记录初始 GET 加载的学年/学期，spinner 选择相同时直接复用缓存
    private var initialLoadedYear: String? = null
    private var initialLoadedTerm: String? = null

    init {
        _state.value = UiState.Idle
    }

    fun loadSyllabus(forceRefresh: Boolean = false) {
        loadSyllabus(null, null, forceRefresh)
    }

    fun loadSyllabus(year: String?, term: String?, forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        selectedYear = year
        selectedTerm = term

        // 判断是否与初始 GET 加载的学期相同
        val isInitialTerm = year != null && term != null &&
                year == initialLoadedYear && term == initialLoadedTerm
        isCurrentTerm = (year == null && term == null) || isInitialTerm

        // 与初始学期相同时使用空 key（初始 GET 的缓存），否则用复合 key
        val yearTermKey = if (year != null && term != null && !isInitialTerm) "${year}_$term" else ""

        if (!forceRefresh) {
            val cached = cacheManager.loadSyllabus(user.account, yearTermKey)
            if (cached != null && cached.courses.isNotEmpty()) {
                updateAvailableOptions(cached)
                _state.value = UiState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val syllabus = if (year != null && term != null && !isInitialTerm) {
                    syllabusApi.getSyllabusWithTerm(
                        userRepository.host, freshUser.token, freshUser.account, freshUser.name,
                        year, term
                    )
                } else {
                    syllabusApi.getSyllabus(
                        userRepository.host, freshUser.token, freshUser.account, freshUser.name
                    )
                }
                if (syllabus != null) {
                    cacheManager.saveSyllabus(freshUser.account, syllabus, yearTermKey)
                    updateAvailableOptions(syllabus)
                    _state.value = UiState.Success(syllabus)
                } else {
                    val cached = cacheManager.loadSyllabus(freshUser.account, yearTermKey)
                    if (cached != null && cached.courses.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取课表失败，请检查网络后重试")
                    }
                }
            } catch (e: Exception) {
                val cached = cacheManager.loadSyllabus(userRepository.getUser().account, yearTermKey)
                if (cached != null && cached.courses.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }

    private fun updateAvailableOptions(syllabus: Syllabus) {
        if (syllabus.searchYearOptions.isNotEmpty()) {
            val existing = _availableYears.value ?: emptyList()
            val merged = (existing + syllabus.searchYearOptions).distinct().sortedDescending()
            _availableYears.value = merged
        }
        if (syllabus.searchTermOptions.isNotEmpty()) {
            _availableTerms.value = syllabus.searchTermOptions
        }
        // 记录初始 GET 加载的学年/学期（仅首次设置）
        if (initialLoadedYear == null && syllabus.searchYearOptions.isNotEmpty()
            && syllabus.selectedYearOption < syllabus.searchYearOptions.size) {
            initialLoadedYear = syllabus.searchYearOptions[syllabus.selectedYearOption]
        }
        if (initialLoadedTerm == null && syllabus.searchTermOptions.isNotEmpty()
            && syllabus.selectedTermOption < syllabus.searchTermOptions.size) {
            initialLoadedTerm = syllabus.searchTermOptions[syllabus.selectedTermOption]
        }
    }
}
