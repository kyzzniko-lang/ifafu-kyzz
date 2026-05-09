package com.ifafu.kyzz.ui.review

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
    private val userRepository: UserRepository
) : ViewModel() {

    private val _reviews = MutableLiveData<List<CourseReview>>()
    val reviews: LiveData<List<CourseReview>> = _reviews

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _postState = MutableLiveData<PostState>(PostState.Idle)
    val postState: LiveData<PostState> = _postState

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
        comment: String
    ) {
        val user = userRepository.getUser()
        val userId = "u_" + hashSha256(user.account).take(8)

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
                    nickname = user.name.ifEmpty { "匿名用户" },
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

    private fun hashSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
