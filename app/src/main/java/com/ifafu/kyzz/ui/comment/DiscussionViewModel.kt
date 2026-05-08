package com.ifafu.kyzz.ui.comment

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.model.Comment
import com.ifafu.kyzz.data.repository.CommentRepository
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.ContentFilter
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiscussionViewModel @Inject constructor(
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    private val _state = MutableLiveData<DiscussionState>()
    val state: LiveData<DiscussionState> = _state

    private val _nicknameState = MutableLiveData<NicknameState>()
    val nicknameState: LiveData<NicknameState> = _nicknameState

    private val _postState = MutableLiveData<PostState>()
    val postState: LiveData<PostState> = _postState

    private val _deleteState = MutableLiveData<DeleteState>()
    val deleteState: LiveData<DeleteState> = _deleteState

    private val comments = mutableListOf<Comment>()
    private var currentPage = 1
    private var hasMore = true
    private var isLoading = false

    val userId: String
        get() {
            val account = userRepository.getUser().account
            return if (account.isNotEmpty()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(account.toByteArray())
                "u_${hash.take(8).joinToString("") { "%02x".format(it) }}"
            } else ""
        }

    private fun oldUserId(): String {
        val account = userRepository.getUser().account
        return if (account.isNotEmpty()) "u_${account.hashCode().toString(16)}" else ""
    }

    fun checkNickname() {
        val uid = userId
        if (uid.isEmpty()) {
            _nicknameState.value = NicknameState.NotSet
            return
        }
        viewModelScope.launch {
            try {
                val nickname = commentRepository.getNickname(uid)
                if (nickname.isNullOrEmpty()) {
                    // Try migrating from old hashCode-based userId
                    val oldId = oldUserId()
                    if (oldId != uid) {
                        val oldNickname = commentRepository.getNickname(oldId)
                        if (!oldNickname.isNullOrEmpty()) {
                            commentRepository.saveNickname(uid, oldNickname)
                            _nicknameState.value = NicknameState.Ready(oldNickname)
                            return@launch
                        }
                    }
                    _nicknameState.value = NicknameState.NotSet
                } else {
                    _nicknameState.value = NicknameState.Ready(nickname)
                }
            } catch (e: Exception) {
                _nicknameState.value = NicknameState.NotSet
            }
        }
    }

    fun saveNickname(nickname: String) {
        // 昵称长度检查
        if (nickname.length > 12) {
            _nicknameState.value = NicknameState.Error("昵称不能超过12个字符")
            return
        }

        // 昵称过滤
        val filterResult = ContentFilter.check(nickname)
        if (!filterResult.passed) {
            _nicknameState.value = NicknameState.Error(filterResult.reason)
            return
        }

        val uid = userId
        if (uid.isEmpty()) return
        viewModelScope.launch {
            try {
                val success = commentRepository.saveNickname(uid, nickname)
                if (success) {
                    _nicknameState.value = NicknameState.Ready(nickname)
                } else {
                    _nicknameState.value = NicknameState.Error("保存失败，请重试")
                }
            } catch (e: Exception) {
                _nicknameState.value = NicknameState.Error("网络异常")
            }
        }
    }

    fun loadComments(refresh: Boolean = false) {
        if (isLoading) return
        if (refresh) {
            currentPage = 1
            hasMore = true
            comments.clear()
        }
        if (!hasMore && !refresh) return

        isLoading = true
        _state.value = if (comments.isEmpty()) DiscussionState.Loading else DiscussionState.LoadingMore

        viewModelScope.launch {
            try {
                val newComments = commentRepository.getComments(page = currentPage)
                if (newComments.isEmpty()) {
                    hasMore = false
                } else {
                    comments.addAll(newComments)
                    currentPage++
                }
                _state.value = DiscussionState.Success(comments.toList(), userId)
            } catch (e: Exception) {
                _state.value = if (comments.isEmpty()) {
                    DiscussionState.Error("加载失败，请重试")
                } else {
                    DiscussionState.Success(comments.toList(), userId)
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun postComment(content: String) {
        // 内容过滤
        val filterResult = ContentFilter.check(content)
        if (!filterResult.passed) {
            _postState.value = PostState.Error(filterResult.reason)
            return
        }

        val nickname = (nicknameState.value as? NicknameState.Ready)?.nickname ?: return
        val uid = userId
        if (uid.isEmpty()) return

        _postState.value = PostState.Loading
        viewModelScope.launch {
            try {
                val comment = commentRepository.postComment(content, nickname, uid)
                if (comment != null) {
                    comments.add(0, comment)
                    _postState.value = PostState.Success
                    _state.value = DiscussionState.Success(comments.toList(), userId)
                } else {
                    _postState.value = PostState.Error("发送失败")
                }
            } catch (e: Exception) {
                _postState.value = PostState.Error("网络异常")
            }
        }
    }

    fun deleteComment(objectId: String) {
        val currentUserId = userId
        val comment = comments.find { it.objectId == objectId }
        if (comment == null || comment.authorId != currentUserId) {
            _deleteState.value = DeleteState.Error("只能删除自己的评论")
            return
        }
        _deleteState.value = DeleteState.Loading
        viewModelScope.launch {
            try {
                val success = commentRepository.deleteComment(objectId)
                if (success) {
                    comments.removeAll { it.objectId == objectId }
                    _deleteState.value = DeleteState.Success
                    _state.value = DiscussionState.Success(comments.toList(), userId)
                } else {
                    _deleteState.value = DeleteState.Error("删除失败")
                }
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Error("网络异常")
            }
        }
    }

    sealed class DiscussionState {
        object Loading : DiscussionState()
        object LoadingMore : DiscussionState()
        data class Success(val comments: List<Comment>, val currentUserId: String) : DiscussionState()
        data class Error(val message: String) : DiscussionState()
    }

    sealed class NicknameState {
        object NotSet : NicknameState()
        data class Ready(val nickname: String) : NicknameState()
        data class Error(val message: String) : NicknameState()
    }

    sealed class PostState {
        object Loading : PostState()
        object Success : PostState()
        data class Error(val message: String) : PostState()
    }

    sealed class DeleteState {
        object Loading : DeleteState()
        object Success : DeleteState()
        data class Error(val message: String) : DeleteState()
    }
}
