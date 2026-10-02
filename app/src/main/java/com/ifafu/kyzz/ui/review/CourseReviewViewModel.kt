package com.ifafu.kyzz.ui.review

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.CourseReviewApi
import com.ifafu.kyzz.data.model.CourseReview
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.AuthorId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CourseReviewViewModel @Inject constructor(
    private val reviewApi: CourseReviewApi,
    private val userRepository: UserRepository,
    private val app: Application
) : ViewModel() {

    private val prefs by lazy { app.getSharedPreferences("review_prefs", android.content.Context.MODE_PRIVATE) }

    private val _reviews = MutableLiveData<List<CourseReview>>()
    val reviews: LiveData<List<CourseReview>> = _reviews

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _postState = MutableLiveData<PostState>(PostState.Idle)
    val postState: LiveData<PostState> = _postState

    private val _deleteState = MutableLiveData<DeleteState>(DeleteState.Idle)
    val deleteState: LiveData<DeleteState> = _deleteState

    private var currentPage = 1
    private var hasMore = true
    private var allReviews = mutableListOf<CourseReview>()
    // 最近一次搜索词：网络刷新完成后要重新套用，否则搜索框里有关键词、
    // 列表却显示全量，用户以为筛选失效。
    private var lastQuery: String = ""
    // 在途的分页/刷新请求：refresh 时取消在途分页，避免慢的旧分页请求晚到
    // 把陈旧整页 append 进已重置的列表并错误递增 currentPage。
    private var loadJob: Job? = null

    sealed class State {
        object Idle : State()
        object Loading : State()
        object LoadingMore : State()
        data class Success(val fromCache: Boolean = false) : State()
        data class Error(val message: String) : State()
    }

    sealed class PostState {
        object Idle : PostState()
        object Loading : PostState()
        object Success : PostState()
        data class Error(val message: String) : PostState()
    }

    sealed class DeleteState {
        object Idle : DeleteState()
        object Loading : DeleteState()
        object Success : DeleteState()
        data class Error(val message: String) : DeleteState()
    }

    // 加盐哈希：无盐 SHA-256(学号) 可被枚举反查，详见 AuthorId
    private val userId: String by lazy {
        AuthorId.discussionUserId(app, userRepository.getUser().account)
    }

    fun getNickname(): String {
        return prefs.getString("nickname", "") ?: ""
    }

    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname.trim()).apply()
    }

    fun isMyReview(review: CourseReview): Boolean = review.authorId == userId

    /** 全量（未按搜索词过滤）评价，供"我的评价"删除列表使用。 */
    fun getAllReviews(): List<CourseReview> = allReviews.toList()

    fun loadReviews(refresh: Boolean = true) {
        if (!refresh && !hasMore) return
        // 加载更多时的在途守卫：滚动监听可能在加载过程中反复触发，同一页
        // 并发拉两次会重复追加。refresh 路径不加守卫（发布/重试后的强制刷新
        // 不应被偶发的 LoadingMore 挡掉）。
        if (!refresh) {
            val state = _state.value
            if (state is State.Loading || state is State.LoadingMore) return
            _state.value = State.LoadingMore
        } else {
            // 取消在途的分页请求（getReviews 挂起点会抛 CancellationException
            // 结束旧协程，append/currentPage++ 不再执行），防止晚到的旧整页
            // 污染刚重置的列表。
            loadJob?.cancel()
            currentPage = 1
            allReviews.clear()
            hasMore = true
            _state.value = State.Loading
        }

        loadJob = viewModelScope.launch {
            try {
                val result = reviewApi.getReviews(currentPage)
                if (result == null) {
                    // 拉取失败（断网/GitHub 限流）：明确报错而不是伪装成"暂无评价"。
                    // 加载更多失败时保留已有列表，仅提示，不清空用户正在看的内容。
                    _state.value = State.Error(
                        if (refresh) "加载失败，请检查网络后重试" else "加载更多失败"
                    )
                    return@launch
                }
                if (refresh) {
                    allReviews = result.toMutableList()
                } else {
                    // 按 commentId 去重：页间数据偏移会让同一评价在相邻两页重复出现，
                    // 直接 addAll 会把同一条评价追加两份（同 DiscussionViewModel 的做法）。
                    allReviews.addAll(
                        result.filter { fresh -> allReviews.none { it.commentId == fresh.commentId } }
                    )
                }
                hasMore = result.size >= 20
                currentPage++
                filterReviews(lastQuery)
                _state.value = State.Success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    fun filterReviews(query: String) {
        lastQuery = query
        if (query.isBlank()) {
            _reviews.value = allReviews.toList()
        } else {
            val q = query.lowercase()
            _reviews.value = allReviews.filter {
                it.courseName.lowercase().contains(q) || it.teacher.lowercase().contains(q)
            }
        }
    }

    fun postReview(
        courseName: String, teacher: String,
        difficulty: Int, grading: Int, attendance: Int,
        comment: String, nickname: String
    ) {
        val savedNickname = nickname.ifBlank { getNickname().ifBlank { "匿名用户" } }
        if (nickname.isNotBlank()) saveNickname(nickname)

        _postState.value = PostState.Loading
        viewModelScope.launch {
            try {
                val review = CourseReview(
                    courseName = courseName,
                    teacher = teacher,
                    difficulty = difficulty,
                    grading = grading,
                    attendance = attendance,
                    comment = comment,
                    nickname = savedNickname,
                    authorId = userId
                )
                val success = reviewApi.postReview(review)
                if (success) {
                    _postState.value = PostState.Success
                    delay(500)
                    loadReviews(refresh = true)
                } else {
                    _postState.value = PostState.Error("发布失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _postState.value = PostState.Error(e.message ?: "发布失败")
            }
        }
    }

    fun deleteReview(review: CourseReview) {
        if (review.commentId.isEmpty()) return
        _deleteState.value = DeleteState.Loading
        viewModelScope.launch {
            try {
                val success = reviewApi.deleteReview(review.commentId, userId)
                if (success) {
                    _deleteState.value = DeleteState.Success
                    delay(300)
                    allReviews.removeAll { it.commentId == review.commentId }
                    filterReviews(lastQuery)
                } else {
                    _deleteState.value = DeleteState.Error("删除失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Error(e.message ?: "删除失败")
            }
        }
    }

}
