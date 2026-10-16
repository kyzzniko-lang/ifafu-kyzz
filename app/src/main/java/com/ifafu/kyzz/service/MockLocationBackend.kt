package com.ifafu.kyzz.service

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import androidx.core.location.LocationManagerCompat

/** Framework boundary: failures must reach the service instead of being treated as success. */
internal interface MockLocationBackend {
    fun add(provider: String)
    fun inject(provider: String, location: Location)
    fun remove(provider: String)
}

/** The UI preflight, cold recovery and framework writes use the same AppOp decision. */
internal object MockLocationAuthorization {
    @Suppress("DEPRECATION")
    fun isAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

internal class AndroidMockLocationBackend(private val context: Context, private val manager: LocationManager) : MockLocationBackend {
    private fun requireMockAuthorization() {
        // Several Android releases silently discard unauthorized test-provider operations.
        // Checking only for exceptions would falsely report successful injection.
        if (!MockLocationAuthorization.isAllowed(context)) {
            throw SecurityException("未授权本应用模拟位置")
        }
    }

    private fun requireInjectionAuthorization() {
        requireMockAuthorization()
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            throw SecurityException("系统定位开关已关闭")
        }
    }

    private fun requireEnabledProvider(provider: String) {
        check(manager.isProviderEnabled(provider)) { "定位通道 $provider 已被禁用，正在尝试恢复" }
    }

    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    override fun add(provider: String) {
        requireInjectionAuthorization()
        // Android 7-10 can reject duplicate test providers left by a killed process.
        // Remove a previous mock before replacing it; this does not remove a real provider.
        remove(provider)
        val gps = provider == LocationManager.GPS_PROVIDER
        manager.addTestProvider(
            provider, !gps, gps, !gps, false, true, true, true,
            if (gps) Criteria.POWER_HIGH else Criteria.POWER_LOW,
            if (gps) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
        )
        manager.setTestProviderEnabled(provider, true)
        requireMockAuthorization()
        requireEnabledProvider(provider)
    }

    override fun inject(provider: String, location: Location) {
        requireInjectionAuthorization()
        // A disabled test provider accepts writes on some Android versions but does
        // not deliver them to clients. Treat that as recoverable registration failure.
        requireEnabledProvider(provider)
        manager.setTestProviderLocation(provider, location)
        requireMockAuthorization()
        requireEnabledProvider(provider)
    }

    override fun remove(provider: String) {
        // Removal must not depend on real-location permission or the global location
        // switch. It does require the mock AppOp: unauthorized removals can be ignored
        // silently, and the persistent cleanup journal must then remain pending.
        requireMockAuthorization()
        try {
            manager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
            // Android 7-10 throws when it was already removed or add never succeeded.
        }
        requireMockAuthorization()
    }
}
