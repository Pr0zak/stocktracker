package com.stocktracker.app.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOTIF-1: [AlertDelivery.evaluate] is the whole reason the Settings status banner can now say
 * something other than "Running normally" when alerts cannot actually reach the user, and
 * [AlertDelivery.canDeliver] is the whole reason [AlertNotifier.post] can now tell the difference
 * between "handed to the system" and "silently dropped by a muted channel".
 */
class AlertDeliveryTest {

    private val high = NotificationManager.IMPORTANCE_HIGH
    private val none = NotificationManager.IMPORTANCE_NONE

    // --- evaluate(): priority order among simultaneous gate failures ---

    @Test fun `all four gates open reports OK`() {
        assertEquals(
            AlertDeliveryStatus.OK,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = true,
                channelImportance = high, ignoringBatteryOptimizations = true,
            ),
        )
    }

    @Test fun `missing permission wins over every other closed gate`() {
        assertEquals(
            AlertDeliveryStatus.PERMISSION_DENIED,
            AlertDelivery.evaluate(
                permissionGranted = false, appNotificationsEnabled = false,
                channelImportance = none, ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test fun `app blocked wins over a muted channel and battery restriction`() {
        assertEquals(
            AlertDeliveryStatus.APP_BLOCKED,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = false,
                channelImportance = none, ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test fun `muted channel wins over battery restriction`() {
        assertEquals(
            AlertDeliveryStatus.CHANNEL_MUTED,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = true,
                channelImportance = none, ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test fun `battery restriction is reported only once the first three gates are open`() {
        assertEquals(
            AlertDeliveryStatus.BATTERY_RESTRICTED,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = true,
                channelImportance = high, ignoringBatteryOptimizations = false,
            ),
        )
    }

    @Test fun `a channel that has never been created is treated as open, not muted`() {
        // null importance = the worker has never posted through the channel yet (fresh install). A
        // fresh install must not read as "blocked" before it has had a chance to run.
        assertEquals(
            AlertDeliveryStatus.OK,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = true,
                channelImportance = null, ignoringBatteryOptimizations = true,
            ),
        )
    }

    @Test fun `importance below NONE but above it is still deliverable, e_g_ LOW`() {
        assertEquals(
            AlertDeliveryStatus.OK,
            AlertDelivery.evaluate(
                permissionGranted = true, appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_LOW, ignoringBatteryOptimizations = true,
            ),
        )
    }

    // --- canDeliver(): what AlertNotifier.post() actually gates on ---

    @Test fun `canDeliver is true when the app allows notifications and the channel isn't muted`() {
        assertTrue(AlertDelivery.canDeliver(appNotificationsEnabled = true, channelImportance = high))
    }

    @Test fun `canDeliver is false when the app has notifications disabled`() {
        assertFalse(AlertDelivery.canDeliver(appNotificationsEnabled = false, channelImportance = high))
    }

    @Test fun `canDeliver is false when the channel importance is NONE`() {
        assertFalse(AlertDelivery.canDeliver(appNotificationsEnabled = true, channelImportance = none))
    }

    @Test fun `canDeliver treats a not-yet-created channel (null importance) as deliverable`() {
        assertTrue(AlertDelivery.canDeliver(appNotificationsEnabled = true, channelImportance = null))
    }

    @Test fun `canDeliver ignores battery optimisation entirely — that's a worker-running concern, not a post() outcome`() {
        // canDeliver has no battery parameter at all; this test exists to document why, so nobody
        // "fixes" that by adding one and folding it into a single post()'s success/failure.
        assertTrue(AlertDelivery.canDeliver(appNotificationsEnabled = true, channelImportance = high))
    }
}
