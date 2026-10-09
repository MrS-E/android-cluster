package ch.stix.android_cluster.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the battery-optimization state of the app: whether it is currently
 * exempt ([PowerManager.isIgnoringBatteryOptimizations]) and how to request
 * the exemption via the system dialog. Also exposes device charging state, used
 * by the UI to hint that continuous VM operation is intended for a powered
 * device.
 */
@Singleton
class BatteryOptimizationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Doze & app standby introduced in API 23; nothing to exempt below.
        }
    }

    fun requestExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            activity.startActivity(intent)
        }
    }

    /**
     * Whether the device is currently charging (AC, USB, wireless or dock),
     * read from the sticky [Intent.ACTION_BATTERY_CHANGED] broadcast.
     */
    fun isCharging(): Boolean {
        val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
