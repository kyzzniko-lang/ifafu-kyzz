package com.ifafu.kyzz.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.google.gson.Gson
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun incompleteAndUnsafeReleaseAssetsAreNotDownloadCandidates() {
        val malformed = Gson().fromJson("""{"tag_name":"v3.0.0","assets":[{"size":100}]}""",
            UpdateChecker.ReleaseInfo::class.java)
        assertNull(malformed.apkAsset)
        val release = UpdateChecker.ReleaseInfo("v3.0.0", null, listOf(
            UpdateChecker.ReleaseInfo.Asset("empty.apk", "https://example.com/empty.apk", 0),
            UpdateChecker.ReleaseInfo.Asset("unsafe.apk", "http://example.com/unsafe.apk", 1000),
            UpdateChecker.ReleaseInfo.Asset("valid.apk", "https://example.com/valid.apk", 500)
        ))
        assertEquals("valid.apk", release.apkAsset?.name)
    }
    @Test
    fun olderPublishedReleaseIsNotReportedAsAnUpdate() {
        assertFalse(UpdateChecker.isNewerVersion("2.5.9", "2.6.0"))
    }

    @Test
    fun newerPublishedReleaseIsReportedAsAnUpdate() {
        assertTrue(UpdateChecker.isNewerVersion("2.6.1", "2.6.0"))
        assertTrue(UpdateChecker.isNewerVersion("3.0.0", "2.99.99"))
    }

    @Test
    fun versionPrefixSuffixAndMissingSegmentsAreHandled() {
        assertFalse(UpdateChecker.isNewerVersion("v2.6.0", "2.6"))
        assertFalse(UpdateChecker.isNewerVersion("2.6.0", "V2.6.0-debug"))
        assertTrue(UpdateChecker.isNewerVersion("v2.6.0.1", "2.6.0"))
    }
}
