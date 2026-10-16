package com.ifafu.kyzz.ui.toolbox

import android.content.Context
import android.os.Build

/** Android 13's sender resolves packages without depending on package-visibility queries. */
internal object MockLocationTargetLauncher {
    fun launch(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getLaunchIntentSenderForPackage(packageName)
                    .sendIntent(context, 0, null, null, null)
            } else {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
                context.startActivity(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
