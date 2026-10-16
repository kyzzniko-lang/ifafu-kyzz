package com.ifafu.kyzz.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MockLocationCleanupLedgerTest {
    private lateinit var preferences: SharedPreferences

    @Before fun setup() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("cleanup_ledger_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test fun committedOwnershipSurvivesAnotherLedgerInstanceAndOnlyTracksOwnedProviders() {
        val ledger = PreferencesMockLocationCleanupLedger(preferences)
        assertTrue(ledger.markPending("gps"))
        assertEquals(setOf("gps"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
        assertTrue(ledger.markPending("network"))
        assertTrue(ledger.markRemoved("gps"))
        assertEquals(setOf("network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
        assertTrue(ledger.markRemoved("network"))
        assertTrue(PreferencesMockLocationCleanupLedger(preferences).pendingProviders().isEmpty())
    }

    @Test fun legacyRunningMarkerMigratesBothProvidersWithoutReappearingDuringPartialCleanup() {
        preferences.edit().putBoolean("mock_location_running", true).commit()
        val ledger = PreferencesMockLocationCleanupLedger(preferences)
        assertEquals(setOf("gps", "network"), ledger.pendingProviders())
        assertTrue(ledger.markRemoved("gps"))
        assertEquals(setOf("network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
        assertFalse(preferences.getBoolean("mock_location_running", true))
        assertTrue(ledger.markRemoved("network"))
        assertTrue(ledger.pendingProviders().isEmpty())
    }

    @Test fun legacyFalseMarkerDoesNotInventAnAutomaticCleanupObligation() {
        preferences.edit().putBoolean("mock_location_running", false).commit()
        assertTrue(PreferencesMockLocationCleanupLedger(preferences).pendingProviders().isEmpty())
    }

    @Test fun failedCleanupCommitRestoresPendingInMemoryForANewService() {
        val ledger = PreferencesMockLocationCleanupLedger(preferences)
        assertTrue(ledger.markPending(setOf("gps", "network")))
        val failing = PreferencesMockLocationCleanupLedger(FailingCommits(preferences))
        assertFalse(failing.markRemoved("gps"))
        assertEquals(setOf("gps", "network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
    }

    @Test fun failedForceRegistrationKeepsBothProvidersInOneConservativeRecord() {
        val ledger = PreferencesMockLocationCleanupLedger(FailingCommits(preferences))
        assertFalse(ledger.markPending(setOf("gps", "network")))
        assertEquals(setOf("gps", "network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
    }

    @Test fun damagedExistingRecordMustNotBeReportedAsClean() {
        preferences.edit().putString(PreferencesMockLocationCleanupLedger.KEY_PENDING, "broken").commit()
        assertEquals(setOf("gps", "network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
        preferences.edit().putStringSet(PreferencesMockLocationCleanupLedger.KEY_PENDING, setOf("unexpected")).commit()
        assertEquals(setOf("gps", "network"), PreferencesMockLocationCleanupLedger(preferences).pendingProviders())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unrelatedProvidersCannotBeRecordedForCleanup() {
        PreferencesMockLocationCleanupLedger(preferences).markPending("passive")
    }

    /** Reproduce Android's important commit(false) contract: its memory image already changed. */
    private class FailingCommits(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                    editor.putStringSet(key, values)
                    return this
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                    editor.putInt(key, value)
                    return this
                }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    editor.commit()
                    return false
                }
            }
        }
    }
}
