package com.ifafu.kyzz.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
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
