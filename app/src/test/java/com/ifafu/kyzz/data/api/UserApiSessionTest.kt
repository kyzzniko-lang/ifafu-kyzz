package com.ifafu.kyzz.data.api

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.ZFVerify
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserApiSessionTest {
    private lateinit var repository: UserRepository
    private lateinit var api: UserApi
    private var submissions = 0
    private var submittedForm: FormBody? = null
    private var beforeResponse: (() -> Unit)? = null
    private val host = "http://example.com"

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ifafu_secure", Context.MODE_PRIVATE).edit().clear().commit()
        repository = UserRepository(context, CacheManager(context))
        repository.host = host
        val image = ByteArrayOutputStream().also {
            Bitmap.createBitmap(72, 27, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().protocol(Protocol.HTTP_1_1).code(200).message("OK")
            when {
                request.method == "POST" -> {
                    submissions += 1
                    submittedForm = request.body as FormBody
                    beforeResponse?.invoke()
                    response.request(request.newBuilder().url("$host/(new-token)/xs_main.aspx?xh=A").build())
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body("""<span class="student" id="xhxm">张&nbsp;三 同学</span>""".toResponseBody())
                }
                request.url.encodedPath.endsWith("CheckCode.aspx") -> response.request(request).body(image.toResponseBody())
                else -> response.request(request.newBuilder().url("$host/(old-token)/default2.aspx").build())
                    .body("""<input name="__VIEWSTATE" value="page-state"><input name="__VIEWSTATEGENERATOR" value="generator">""".toResponseBody())
            }
            response.build()
        }.build()
        api = UserApi(HtmlClient(http), repository, ZFVerify(context))
    }

    @Test
    fun loginUsesChallengeViewStateAndFinalRedirectToken() = runBlocking {
        val challenge = requireNotNull(api.prepareLogin(host))
        val user = User()
        assertTrue(api.login("A", "password", "1234", user, challenge).success)
        assertEquals("new-token", user.token)
        assertEquals("张 三", user.name)
        val fields = (0 until submittedForm!!.size).associate { submittedForm!!.name(it) to submittedForm!!.value(it) }
        assertEquals("page-state", fields["__VIEWSTATE"])
        assertEquals("generator", fields["__VIEWSTATEGENERATOR"])
        assertTrue(repository.saveAuthenticatedUserIfUnchanged(user, "password", challenge.authSnapshot))
    }

    @Test
    fun refreshedCaptchaRejectsOlderChallengeWithoutSubmitting() = runBlocking {
        val old = requireNotNull(api.prepareLogin(host))
        assertNotNull(api.prepareLogin(host))
        assertFalse(api.login("A", "password", "1234", User(), old).success)
        assertEquals(0, submissions)
    }

    @Test
    fun switchedAccountRejectsChallengeBeforeSubmission() = runBlocking {
        val old = requireNotNull(api.prepareLogin(host))
        repository.switchAccount(UserRepository.AccountProfile("B", "B", "other-password"))
        assertFalse(api.login("A", "password", "1234", User(), old).success)
        assertEquals(0, submissions)
    }

    @Test
    fun accountChangeDuringSubmissionRejectsSuccessfulOldResponse() = runBlocking {
        val old = requireNotNull(api.prepareLogin(host))
        beforeResponse = { repository.switchAccount(UserRepository.AccountProfile("B", "B", "other-password")) }
        val user = User()
        assertFalse(api.login("A", "password", "1234", user, old).success)
        assertFalse(user.isLogin)
        assertEquals("", user.token)
        assertEquals("B", repository.getUser().account)
    }
}
