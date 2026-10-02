package com.ifafu.kyzz.ui.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateDownloadRecoveryTest {
    @Test
    fun unavailableStorageDoesNotCrashOrPermanentlyBlockRetry() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val release = UpdateChecker.ReleaseInfo("v99.0.0", "test", listOf(
            UpdateChecker.ReleaseInfo.Asset("app.apk", "https://example.com/app.apk", 100L)
        ))
        UpdateChecker.downloadAndInstall(context, release)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("更新失败，请稍后重试", ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        UpdateChecker.downloadAndInstall(context, release)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("更新失败，请稍后重试", ShadowToast.getTextOfLatestToast())
    }
}
