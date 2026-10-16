package com.ifafu.kyzz.service

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Process
import androidx.core.location.LocationManagerCompat
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = Application::class)
class MockLocationBackendTest {
    @Before fun selectMockApplication() {
        val context = RuntimeEnvironment.getApplication()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    @Test fun cleanupOfAlreadyAbsentProvidersIsIdempotent() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val backend = AndroidMockLocationBackend(context, manager)
        repeat(2) {
            backend.remove(LocationManager.GPS_PROVIDER)
            backend.remove(LocationManager.NETWORK_PROVIDER)
        }
    }

    @Test fun deniedAppOpFailsExplicitlyEvenIfFrameworkWouldSilentlyIgnoreIt() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName, AppOpsManager.MODE_ERRORED)
        val backend = AndroidMockLocationBackend(context, manager)
        assertDenied { backend.add(LocationManager.GPS_PROVIDER) }
        assertDenied { backend.inject(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER)) }
        assertDenied { backend.remove(LocationManager.GPS_PROVIDER) }
    }

    @Test fun cleanupDoesNotRequireTheGlobalLocationSwitch() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(false)
        assertFalse(LocationManagerCompat.isLocationEnabled(manager))
        AndroidMockLocationBackend(context, manager).remove(LocationManager.GPS_PROVIDER)
    }

    @Test fun disabledProviderCannotBeReportedAsAcceptedInjection() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val backend = AndroidMockLocationBackend(context, manager)
        backend.add(LocationManager.GPS_PROVIDER)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        assertFalse(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        assertTrue(LocationManagerCompat.isLocationEnabled(manager))
        try {
            backend.inject(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 26.0
                longitude = 119.0
                accuracy = 8f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = 1_000_000_000L
            })
            fail("Disabled providers must trigger registration recovery")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("禁用"))
        }
    }

    private fun assertDenied(action: () -> Unit) {
        try {
            action()
            fail("Unauthorized mock operation must fail explicitly")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("模拟位置"))
        }
    }
}
