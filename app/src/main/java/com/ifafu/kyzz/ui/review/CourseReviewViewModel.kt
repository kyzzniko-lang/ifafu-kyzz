package com.ifafu.kyzz.ui.review

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.CourseReviewApi
import com.ifafu.kyzz.data.model.CourseReview
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
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

    private val userId: String by lazy {
        val account = userRepository.getUser().account
        "u_" + hashSha256(account).take(8)
    }

    fun getNickname(): String {
        return prefs.getString("nickname", "") ?: ""
    }

    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname.trim()).apply()
    }

    fun isMyReview(review: CourseReview): Boolean = review.authorId == userId

    fun loadReviews(refresh: Boolean = true) {
        if (!refresh && !hasMore) return
        if (refresh) {
            currentPage = 1
            allReviews.clear()
            hasMore = true
            _state.value = State.Loading
        } else {
            _state.value = State.LoadingMore
        }

        viewModelScope.launch {
            try {
                val result = reviewApi.getReviews(currentPage)
                if (refresh) {
                    allReviews = result.toMutableList()
                } else {
                    allReviews.addAll(result)
                }
                hasMore = result.size >= 20
                currentPage++
                _reviews.value = allReviews.toList()
                _state.value = State.Success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    fun filterReviews(query: String) {
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
                val success = reviewApi.deleteReview(review.commentId)
                if (success) {
                    _deleteState.value = DeleteState.Success
                    delay(300)
                    allReviews.removeAll { it.commentId == review.commentId }
                    _reviews.value = allReviews.toList()
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

    private fun hashSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
