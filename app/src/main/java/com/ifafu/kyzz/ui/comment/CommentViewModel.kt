package com.ifafu.kyzz.ui.comment

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.CommentTeacherApi
import com.ifafu.kyzz.data.api.CommentTeacherApi.EvalItem
import com.ifafu.kyzz.data.api.CommentTeacherApi.EvalMode
import com.ifafu.kyzz.data.api.CommentTeacherApi.ScoreLevel
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommentViewModel @Inject constructor(
    private val commentTeacherApi: CommentTeacherApi,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    private val _state = MutableLiveData<CommentState>()
    val state: LiveData<CommentState> = _state

    // ── 模式选择 ──
    private val _evalMode = MutableLiveData<EvalMode>(EvalMode.FullAuto)
    val evalMode: LiveData<EvalMode> = _evalMode

    private val _semiMin = MutableLiveData(ScoreLevel.GOOD)
    val semiMin: LiveData<ScoreLevel> = _semiMin

    private val _semiMax = MutableLiveData(ScoreLevel.MEDIUM)
    val semiMax: LiveData<ScoreLevel> = _semiMax

    // ── 手动模式数据 ──
    private val _manualItems = MutableLiveData<List<EvalItem>>(emptyList())
    val manualItems: LiveData<List<EvalItem>> = _manualItems

    private val _manualSelections = MutableLiveData<MutableMap<String, ScoreLevel>>(mutableMapOf())
    val manualSelections: LiveData<MutableMap<String, ScoreLevel>> = _manualSelections

    private val _currentCourseName = MutableLiveData("")
    val currentCourseName: LiveData<String> = _currentCourseName

    private val _currentTeacherName = MutableLiveData("")
    val currentTeacherName: LiveData<String> = _currentTeacherName

    private val _courseLabels = MutableLiveData<List<String>>(emptyList())
    val courseLabels: LiveData<List<String>> = _courseLabels

    // ── 教师白名单/黑名单（内存存储） ──
    private val _teacherWhitelist = mutableSetOf<String>()
    private val _teacherBlacklist = mutableSetOf<String>()
    val isWhitelisted: Boolean get() = _currentTeacherName.value in _teacherWhitelist
    val isBlacklisted: Boolean get() = _currentTeacherName.value in _teacherBlacklist

    fun toggleWhitelist(teacherName: String) {
        if (teacherName in _teacherWhitelist) _teacherWhitelist.remove(teacherName)
        else { _teacherWhitelist.add(teacherName); _teacherBlacklist.remove(teacherName) }
        if (coursePaths.isNotEmpty() && manualCoursesDone < coursePaths.size) {
            loadNextManualCourse()
        }
    }

    fun toggleBlacklist(teacherName: String) {
        if (teacherName in _teacherBlacklist) _teacherBlacklist.remove(teacherName)
        else { _teacherBlacklist.add(teacherName); _teacherWhitelist.remove(teacherName) }
        if (coursePaths.isNotEmpty() && manualCoursesDone < coursePaths.size) {
            loadNextManualCourse()
        }
    }

    private val _currentCourseIndex = MutableLiveData(0)
    val currentCourseIndex: LiveData<Int> = _currentCourseIndex

    private val _totalCourses = MutableLiveData(0)
    val totalCourses: LiveData<Int> = _totalCourses

    private var coursePaths: List<Pair<String, String>> = emptyList() // path, label
    private var manualCoursesDone = 0
    private var isSubmitting = false
    private val _savedIndices = mutableSetOf<Int>() // 已保存的课程索引

    private fun refreshCourseLabels() {
        _courseLabels.value = coursePaths.mapIndexed { index, (_, label) ->
            val prefix = if (index in _savedIndices) "✓ " else "  "
            "$prefix${label.ifEmpty { "课程 ${index + 1}" }}"
        }
    }

    init {
        _state.value = CommentState.Idle
    }

    fun setEvalMode(mode: EvalMode) { _evalMode.value = mode }

    fun setSemiMin(level: ScoreLevel) {
        _semiMin.value = level
        // 确保 min <= max
        if (level.ordinal > (_semiMax.value?.ordinal ?: 0)) {
            _semiMax.value = level
        }
    }

    fun setSemiMax(level: ScoreLevel) {
        _semiMax.value = level
        if (level.ordinal < (_semiMin.value?.ordinal ?: 0)) {
            _semiMin.value = level
        }
    }

    fun setManualSelection(fieldKey: String, level: ScoreLevel) {
        val map = _manualSelections.value ?: mutableMapOf()
        map[fieldKey] = level
        _manualSelections.value = map
    }

    /** 初始化所有评价项为"良好" */
    fun initManualSelections(items: List<EvalItem>) {
        val map = mutableMapOf<String, ScoreLevel>()
        items.forEach { map[it.fieldKey] = ScoreLevel.GOOD }
        _manualSelections.value = map
    }

    var currentCommentText: String = ""

    fun startComment() {
        val user = userRepository.getUser()
        if (!user.isLogin) { _state.value = CommentState.Error("未登录"); return }
        // 确保 SemiAuto 模式的 min/max 与当前下拉框一致
        val currentMode = _evalMode.value
        if (currentMode is EvalMode.SemiAuto) {
            val min = _semiMin.value ?: ScoreLevel.GOOD
            val max = _semiMax.value ?: ScoreLevel.MEDIUM
            _evalMode.value = EvalMode.SemiAuto(min, max)
        }
        startManualComment()
    }

    // ── 加载课程列表 ──

    private fun startManualComment() {
        viewModelScope.launch {
            _state.value = CommentState.Loading("正在获取评教列表…")
            try {
                val freshUser = userRepository.getUser()
                val resp = commentTeacherApi.fetchCourseLinks(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (!resp.success && (resp.message.contains("所有评价已完成") || resp.message.contains("可以提交"))) {
                    _state.value = CommentState.ManualInput("所有课程已评价完成，请点击「提交总表」")
                    return@launch
                }
                if (!resp.success) { _state.value = CommentState.Error(resp.message); return@launch }

                val links = parseCourseLinksFromJson(resp.message)
                coursePaths = links
                _savedIndices.clear()
                refreshCourseLabels()
                _totalCourses.value = links.size
                manualCoursesDone = 0
                isSubmitting = false

                // 加载第一个课程
                loadNextManualCourse()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CommentState.Error(e.message ?: "评教失败")
            }
        }
    }

    private fun loadNextManualCourse() {
        if (manualCoursesDone >= coursePaths.size) {
            _state.value = CommentState.ManualInput("已保存全部 ${coursePaths.size} 门课程，请点击下方「提交总表」")
            return
        }
        val (path, label) = coursePaths[manualCoursesDone]
        _currentCourseIndex.value = manualCoursesDone
        _currentCourseName.value = label.ifEmpty { "课程 ${manualCoursesDone + 1}" }

        viewModelScope.launch {
            _state.value = CommentState.Loading(
                "正在加载 (${manualCoursesDone + 1}/${coursePaths.size})：${_currentCourseName.value}"
            )
            try {
                val freshUser = userRepository.getUser()
                val result = commentTeacherApi.fetchCourseItems(
                    userRepository.host, freshUser.token, path, freshUser.account, freshUser.name
                )
                if (result.items.isEmpty()) {
                    _state.value = CommentState.Error("无法解析评价项")
                    return@launch
                }
                // 更新教师名（切换课程后重新提取）
                if (result.teacherName.isNotEmpty()) {
                    _currentTeacherName.value = result.teacherName
                }
                val items = result.items
                initManualSelections(items) // 先初始化选择，设为默认 良好
                // 预填评分（白名单优先于黑名单）
                val mode = _evalMode.value ?: EvalMode.FullAuto
                val teacherName = _currentTeacherName.value ?: ""
                val isGood = teacherName in _teacherWhitelist
                val isBad = teacherName in _teacherBlacklist
                val random = java.util.Random()
                val map = _manualSelections.value ?: mutableMapOf()
                if (isGood) {
                    // 白名单：大部分优秀，混入少量良好避免"所有选项一致"
                    for (item in items) {
                        val idx = listOf(0,0,0,0,0, 0,1,0,1,0)[random.nextInt(10)]
                        map[item.fieldKey] = ScoreLevel.fromIndex(idx)
                    }
                } else if (isBad) {
                    // 黑名单：大部分不及格，混入少量低分
                    for (item in items) {
                        val idx = listOf(4,4,4,4,4, 4,3,4,3,2)[random.nextInt(10)]
                        map[item.fieldKey] = ScoreLevel.fromIndex(idx)
                    }
                } else if (mode is EvalMode.FullAuto || mode is EvalMode.SemiAuto) {
                    for (item in items) {
                        map[item.fieldKey] = when (mode) {
                            is EvalMode.FullAuto -> {
                                ScoreLevel.fromIndex(random.nextInt(3))
                            }
                            is EvalMode.SemiAuto -> {
                                val range = mode.max.ordinal - mode.min.ordinal + 1
                                ScoreLevel.fromIndex(mode.min.ordinal + random.nextInt(range))
                            }
                            else -> ScoreLevel.GOOD
                        }
                    }
                    _manualSelections.value = map
                }
                _manualItems.value = items
                _state.value = CommentState.ManualInput()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CommentState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun switchToCourse(index: Int) {
        if (index < 0 || index >= coursePaths.size || index == manualCoursesDone) return
        manualCoursesDone = index
        isSubmitting = false
        loadNextManualCourse()
    }

    fun submitCurrentCourse(commentText: String = "") {
        if (isSubmitting) return
        val selections = _manualSelections.value ?: return
        if (coursePaths.isEmpty()) return
        val (path, _) = coursePaths[manualCoursesDone]

        viewModelScope.launch {
            isSubmitting = true
            _state.value = CommentState.Loading(
                "正在保存 (${manualCoursesDone + 1}/${coursePaths.size})：${_currentCourseName.value}"
            )
            try {
                val freshUser = userRepository.getUser()
                val resp = commentTeacherApi.submitManualCourse(
                    userRepository.host, freshUser.token, path, freshUser.account, freshUser.name, selections, commentText
                )
                if (!resp.success) {
                    _state.value = CommentState.Error(resp.message)
                    return@launch
                }
                _savedIndices.add(manualCoursesDone)
                refreshCourseLabels()
                manualCoursesDone++
                _state.value = CommentState.Loading(
                    "保存成功 (${manualCoursesDone}/${coursePaths.size})"
                )
                kotlinx.coroutines.delay(300)
                loadNextManualCourse()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CommentState.Error(e.message ?: "保存失败")
            } finally {
                isSubmitting = false
            }
        }
    }

    fun submitFinal() {
        submitFinalAndFinish()
    }

    private fun submitFinalAndFinish() {
        viewModelScope.launch {
            _state.value = CommentState.Loading("正在提交总表…")
            try {
                val freshUser = userRepository.getUser()
                val resp = commentTeacherApi.submitFinalTable(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                _state.value = if (resp.success) {
                    CommentState.Success("全部评教完成，共 ${coursePaths.size} 门课程")
                } else {
                    CommentState.Success("已保存 ${coursePaths.size} 门课程（总表提交：${resp.message}）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = CommentState.Success("已保存 ${coursePaths.size} 门课程")
            }
        }
    }

    /** 解析 JSON 格式的课程链接列表 */
    private fun parseCourseLinksFromJson(json: String): List<Pair<String, String>> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val pair = arr.getJSONArray(i)
                pair.getString(0) to pair.getString(1)
            }
        } catch (e: Exception) {
            // 兜底：尝试旧格式解析
            json.split("|").map {
                val parts = it.split("::")
                parts[0] to (parts.getOrElse(1) { "" })
            }
        }
    }

    sealed class CommentState {
        object Idle : CommentState()
        data class ManualInput(val message: String = "请为以下评价项打分") : CommentState()
        data class Loading(val message: String = "正在评教中…") : CommentState()
        data class Success(val message: String) : CommentState()
        data class Error(val message: String) : CommentState()
    }
}
