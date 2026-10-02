package com.ifafu.kyzz.core.crash

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CrashReporterTest {
    @Test
    fun pendingCrashSurvivesAnrRotationAndSameMillisecondReportsDoNotOverwrite() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ifafu_crash", Context.MODE_PRIVATE).edit().clear().commit()
        CrashReporter.init(context)
        CrashReporter.reportCrash("main", IllegalStateException("original crash"))
        val original = CrashReporter.getPendingCrashReport()
        assertNotNull(original)
        repeat(15) { CrashReporter.reportAnr("main", 12_000L) }
        val pending = CrashReporter.getPendingCrashReport()
        assertEquals("original crash", pending?.message)
        assertTrue(File(original!!.filePath!!).exists())
        assertTrue(File(context.filesDir, "crash").listFiles()!!.size <= 10)
        CrashReporter.reportCrash("main", IllegalStateException("next crash"))
        val next = CrashReporter.getPendingCrashReport()
        assertEquals("next crash", next?.message)
        assertNotEquals(original.filePath, next?.filePath)
        assertTrue(next!!.time > 0L)
    }
}
