package com.ifafu.kyzz.ui.elective

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ElectiveCourseApi
import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ElectiveViewModel @Inject constructor(
    private val electiveCourseApi: ElectiveCourseApi,
    private val userRepository: UserRepository,
    private val cacheManager: com.ifafu.kyzz.data.cache.CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<ElectiveState>()
    val state: LiveData<ElectiveState> = _state

    init {
        _state.value = ElectiveState.Idle
    }

    private val _courseList = MutableLiveData<ElectiveCourseList>().apply { value = ElectiveCourseList() }
    val courseList: LiveData<ElectiveCourseList> = _courseList

    fun loadCourses() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = ElectiveState.Error("未登录")
            return
        }
        viewModelScope.launch {
            try {
                _state.value = ElectiveState.Loading
                val freshUser = userRepository.getUser()
                val list = ElectiveCourseList()
                val response = electiveCourseApi.getElectiveCourseIndex(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name, list
                )
                if (response.success) {
                    _courseList.value = list
                    _state.value = ElectiveState.Success(response.message)
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ElectiveState.Error("加载失败：${e.message}")
            }
        }
    }

    fun searchCourses() {
        viewModelScope.launch {
            try {
                _state.value = ElectiveState.Loading
                val freshUser = userRepository.getUser()
                val response = electiveCourseApi.searchElectiveCourse(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name, _courseList.value ?: ElectiveCourseList()
                )
                if (response.success) {
                    _state.value = ElectiveState.Success(response.message)
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ElectiveState.Error("搜索失败：${e.message}")
            }
        }
    }

    fun selectCourse(courseIndex: String) {
        viewModelScope.launch {
            try {
                _state.value = ElectiveState.Loading
                val freshUser = userRepository.getUser()
                val response = electiveCourseApi.electiveCourse(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name,
                    _courseList.value ?: ElectiveCourseList(), courseIndex
                )
                if (response.success) {
                    // 选课结果要反映到课表：失效课表缓存时间戳，首页/课表页下次
                    // 进入会重新拉取（Widget 仍读旧缓存，不受影响）
                    cacheManager.invalidateSyllabus(freshUser.account)
                    var listRefreshed = false
                    try {
                        val list = ElectiveCourseList()
                        val listResponse = electiveCourseApi.getElectiveCourseIndex(
                            userRepository.host, freshUser.token, freshUser.account, freshUser.name, list
                        )
                        if (listResponse.success) {
                            _courseList.value = list
                            listRefreshed = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    // 列表刷新失败不能静默：旧列表里刚选上的课仍在、余量未减，
                    // 用户会以为没选上而再次提交
                    _state.value = if (listRefreshed) {
                        ElectiveState.Success(response.message)
                    } else {
                        ElectiveState.Success("${response.message}（列表刷新失败，请手动刷新查看最新余量）")
                    }
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ElectiveState.Error("选课失败：${e.message}")
            }
        }
    }

    sealed class ElectiveState {
        object Idle : ElectiveState()
        object Loading : ElectiveState()
        data class Success(val message: String) : ElectiveState()
        data class Error(val message: String) : ElectiveState()
    }
}
