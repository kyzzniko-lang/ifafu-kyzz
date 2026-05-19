package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.network.HtmlClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReloginHelper @Inject constructor(
    private val userApi: UserApi,
    private val htmlClient: HtmlClient
) {
    private val mutex = Mutex()

    suspend fun relogin(): com.ifafu.kyzz.data.model.Response {
        return mutex.withLock {
            htmlClient.clearCookies()
            userApi.relogin()
        }
    }
}
