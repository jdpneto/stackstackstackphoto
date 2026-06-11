package com.jdpneto.stackstackstack

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/**
 * System conditions consulted at shutter press (spec 2026-06-11 §2). Closures so tests inject
 * states the simulator can't produce (thermal, battery, full disk). Mirrors iOS [CaptureEnvironment].
 *
 * Thermal mapping (pinned in the plan):
 * - [PowerManager.THERMAL_STATUS_SEVERE] → serious-equivalent (warn, don't block).
 * - [PowerManager.THERMAL_STATUS_CRITICAL] and above → critical (block capture).
 */
data class CaptureEnvironment(
    /** Current thermal status as an Android [PowerManager.THERMAL_STATUS_*] int. */
    val thermalStatus: () -> Int,
    /** Battery level 0.0–1.0; -1.0 = unknown. */
    val batteryLevel: () -> Float,
    /** Whether the battery is currently charging or full. */
    val batteryCharging: () -> Boolean,
    /** Free bytes available for writing. */
    val freeDiskBytes: () -> Long
) {
    companion object {
        /** Free space below which capture is blocked (a 20-frame stack + result can need ~150 MB). */
        const val MINIMUM_FREE_BYTES: Long = 200_000_000L

        /** Battery fraction below which the UI warns (capture is never blocked on battery). */
        const val LOW_BATTERY_THRESHOLD: Float = 0.10f

        /**
         * Real system probes. Mirrors `CaptureEnvironment.live()` on iOS.
         *
         * @param context Application context (used to access system services).
         * @param filesDir The app's files directory (for free-disk-space query).
         */
        fun live(context: Context, filesDir: File): CaptureEnvironment {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return CaptureEnvironment(
                thermalStatus = {
                    powerManager.currentThermalStatus
                },
                batteryLevel = {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val intent = context.registerReceiver(null, filter)
                    if (intent == null) -1f
                    else {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level < 0 || scale <= 0) -1f else level.toFloat() / scale.toFloat()
                    }
                },
                batteryCharging = {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val intent = context.registerReceiver(null, filter)
                    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                },
                freeDiskBytes = {
                    // A failed probe must never wrongly block the shutter — report "plenty".
                    runCatching {
                        val stat = StatFs(filesDir.absolutePath)
                        stat.availableBlocksLong * stat.blockSizeLong
                    }.getOrDefault(Long.MAX_VALUE)
                }
            )
        }
    }
}

/**
 * Semantic thermal level, independent of the platform int values.
 * Mirrors the iOS [ProcessInfo.ThermalState] mapping in the coordinator.
 */
enum class ThermalLevel {
    NOMINAL,
    FAIR,
    /** Corresponds to [PowerManager.THERMAL_STATUS_SEVERE]: warn in the UI. */
    SERIOUS,
    /** Corresponds to [PowerManager.THERMAL_STATUS_CRITICAL] and above: block capture. */
    CRITICAL;

    companion object {
        /**
         * Map an Android [PowerManager.THERMAL_STATUS_*] int to a [ThermalLevel].
         * Pinned mapping from the plan: SEVERE → SERIOUS, ≥ CRITICAL → CRITICAL.
         */
        fun from(androidStatus: Int): ThermalLevel = when {
            androidStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL
            androidStatus >= PowerManager.THERMAL_STATUS_SEVERE   -> SERIOUS
            androidStatus >= PowerManager.THERMAL_STATUS_MODERATE -> FAIR
            else                                                   -> NOMINAL
        }
    }
}
