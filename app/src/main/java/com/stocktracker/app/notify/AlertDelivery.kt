package com.stocktracker.app.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * NOTIF-1: why a price-alert notification might never reach the user — a dimension [BackgroundRunStatus]
 * (in Settings) previously had no concept of at all. That composable only ever asked "did the 15-minute
 * worker run recently, with no reported failures", and answered "Running normally" from that alone —
 * true even on a device where every single [AlertNotifier.post] call it makes is being silently
 * swallowed by one of the four gates below. The worker running is necessary but not sufficient.
 */
enum class AlertDeliveryStatus {
    /** Nothing found blocking delivery, as far as this device's settings say. */
    OK,

    /** POST_NOTIFICATIONS not granted (Android 13+ runtime permission). */
    PERMISSION_DENIED,

    /** The user switched StockTracker's notifications off entirely (the per-app toggle). */
    APP_BLOCKED,

    /** The "Price alerts" channel itself is muted (importance NONE), independent of the app toggle —
     *  the user can mute one channel without touching the others. */
    CHANNEL_MUTED,

    /** StockTracker is not exempt from battery optimisation, so Android may defer or skip the 15-minute
     *  worker before it ever gets a chance to hit the other three gates. */
    BATTERY_RESTRICTED,
}

/**
 * PURE decision logic ([evaluate], [canDeliver]) plus thin Android-facing readers ([current]) that
 * gather the inputs it needs. Split this way so the one thing worth getting right — which state wins
 * when more than one gate is closed, and whether a post() should count as delivered — is unit-tested
 * without an Android runtime (no Robolectric in this project's test setup).
 */
object AlertDelivery {

    /**
     * Full breakdown for the Settings status banner. Order matters: permission trumps everything else
     * because it is the single most common first-run cause and makes the other three moot; app-level
     * block is checked before the one channel because a user who disabled notifications entirely did
     * not "mute a channel", they turned StockTracker off; battery restriction is reported last because
     * it is a lower-confidence signal (many OEMs defer background work even when the app IS whitelisted)
     * and because it speaks to whether the WORKER runs at all, not whether a run that happened could post.
     *
     * @param channelImportance null means the "Price alerts" channel has never been created (the
     *   worker has never posted through it yet) — treated as open, not muted, so a fresh install does
     *   not read as blocked before it has had a chance to run.
     */
    fun evaluate(
        permissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
        channelImportance: Int?,
        ignoringBatteryOptimizations: Boolean,
    ): AlertDeliveryStatus = when {
        !permissionGranted -> AlertDeliveryStatus.PERMISSION_DENIED
        !appNotificationsEnabled -> AlertDeliveryStatus.APP_BLOCKED
        channelImportance == NotificationManager.IMPORTANCE_NONE -> AlertDeliveryStatus.CHANNEL_MUTED
        !ignoringBatteryOptimizations -> AlertDeliveryStatus.BATTERY_RESTRICTED
        else -> AlertDeliveryStatus.OK
    }

    /**
     * The two gates [AlertNotifier.post] itself needs to check on every single post — the POST_NOTIFICATIONS
     * permission is checked separately (it's a hard precondition even calling NotificationManagerCompat
     * is meaningless without), and battery-optimisation status is deliberately excluded: it affects
     * whether the worker runs at all, not whether one already-running post() call succeeds, so folding
     * it in here would make a healthy, running worker's successful post look like a failed one.
     */
    fun canDeliver(appNotificationsEnabled: Boolean, channelImportance: Int?): Boolean =
        appNotificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

    /**
     * Reads the current state of all four gates for the price-alerts channel. Not unit-tested itself —
     * it's a thin Android reader with no branching of its own; [evaluate] is what carries the logic and
     * is what's tested.
     */
    fun current(context: Context): AlertDeliveryStatus {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelImportance = AlertNotifier.priceAlertChannelImportance(context)
        val ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        return evaluate(permissionGranted, appNotificationsEnabled, channelImportance, ignoringBatteryOptimizations)
    }
}
