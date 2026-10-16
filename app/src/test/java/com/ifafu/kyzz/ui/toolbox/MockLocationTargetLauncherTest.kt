package com.ifafu.kyzz.ui.toolbox

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class, shadows = [LaunchPackageManagerShadow::class])
class MockLocationTargetLauncherTest {
    @Before fun reset() {
        LaunchPackageManagerShadow.senderCalls = 0
        LaunchPackageManagerShadow.queryCalls = 0
    }

    @Test fun launcherUsesSenderOnModernAndroidEvenWhenLegacyQueryCannotSeeThePackage() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(MockLocationTargetLauncher.launch(context, "installed.target"))
        assertEquals("installed.target", shadowOf(context).nextStartedActivity.component!!.packageName)
        assertEquals(if (Build.VERSION.SDK_INT >= 33) 1 else 0, LaunchPackageManagerShadow.senderCalls)
        assertEquals(if (Build.VERSION.SDK_INT >= 33) 0 else 1, LaunchPackageManagerShadow.queryCalls)
    }

    @Test fun nonexistentOrBlankPackageFailsWithoutCrashing() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(MockLocationTargetLauncher.launch(context, "missing.target"))
        assertFalse(MockLocationTargetLauncher.launch(context, " "))
        assertNull(shadowOf(context).nextStartedActivity)
    }
}

/** Simulates Android's visibility filtering independently of the launch-sender path. */
@Implements(className = "android.app.ApplicationPackageManager")
class LaunchPackageManagerShadow : ShadowApplicationPackageManager() {
    companion object { var senderCalls = 0; var queryCalls = 0 }

    @Implementation(minSdk = 33)
    fun getLaunchIntentSenderForPackage(packageName: String): IntentSender {
        senderCalls++
        if (packageName != "installed.target") throw IntentSender.SendIntentException("Target unavailable")
        return PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 91,
            targetIntent(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
    }

    @Implementation
    public override fun getLaunchIntentForPackage(packageName: String): Intent? {
        queryCalls++
        return if (Build.VERSION.SDK_INT < 33 && packageName == "installed.target") targetIntent(packageName) else null
    }

    private fun targetIntent(packageName: String) = Intent(Intent.ACTION_MAIN)
        .setClassName(packageName, "$packageName.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
