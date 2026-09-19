package com.stocktracker.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.stocktracker.app.MainActivity
import com.stocktracker.app.R

object AlertNotifier {

    /**
     * Carries the nav route a notification wants opened. Read once by MainActivity.
     *
     * The value is a route string built by [com.stocktracker.app.ui.Routes] — the same strings the
     * nav graph registers and every in-app tap navigates to. Deliberately not a parallel scheme of
     * deep-link URIs: a second table is a table that can disagree with the first one.
     */
    const val EXTRA_ROUTE = "com.stocktracker.app.NOTIFICATION_ROUTE"


    // Not private: Settings (AlertDelivery.current) needs it to read the channel's live importance
    // and to open the system channel-settings screen for it.
    const val CHANNEL_ID = "price_alerts"
    private const val MARKET_CHANNEL_ID = "market_summary"
    private const val BRIEF_CHANNEL_ID = "ai_daily_brief"
    private const val SANDBOX_CHANNEL_ID = "sandbox_trades"
    private const val SCAN_CHANNEL_ID = "signal_scan"

    /** Groups every scan-family post (see [notifyScan]) so they collapse together in the shade instead
     *  of listing separately from a channel a user may not have even opened Settings to name yet. */
    private const val SCAN_GROUP_KEY = "com.stocktracker.app.SCAN_GROUP"
    private val SCAN_SUMMARY_ID = "scan_group_summary".hashCode()

    fun ensureChannel(context: Context) {
        ensureChannel(
            context, CHANNEL_ID, "Price alerts",
            "Alerts when a tracked price crosses your thresholds",
            NotificationManager.IMPORTANCE_HIGH,
        )
    }

    /**
     * NOTIF-2: home for everything that used to ride the HIGH-importance price-alerts channel without
     * actually being a price alert — overnight signal flips, 200-week-line crosses, dip alerts, the
     * catalyst calendar, the weekly digest, and call-exit warnings. A user muting "Market dates to
     * watch" was muting the same channel as "NVDA fell below $120"; now they're independent.
     *
     * This is a NEW channel id rather than a repurposed [CHANNEL_ID]: a channel's importance (and most
     * other properties) is frozen the instant it is first created on a device — calling
     * createNotificationChannel again with the same id is a no-op for anyone who already has it, it
     * does not retroactively lower its importance. So there is no way to "migrate" price_alerts down to
     * DEFAULT for existing installs from code; the only correct move is to leave price_alerts exactly as
     * it is (still HIGH, still exclusively real price alerts, unaffected for every existing user) and
     * mint a distinct id that every install — new or upgrading — gets created fresh at DEFAULT the first
     * time this fires.
     */
    fun ensureScanChannel(context: Context) {
        ensureChannel(
            context, SCAN_CHANNEL_ID, "Signal & scan alerts",
            "Overnight signal flips, 200-week-line crosses, dip alerts, key dates, the weekly digest, " +
                "and call exit warnings",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** Separate, lower-key channel for the daily close / after-hours movers recap, so users can mute
     *  it independently of price alerts. */
    fun ensureMarketChannel(context: Context) {
        ensureChannel(
            context, MARKET_CHANNEL_ID, "Market summary",
            "A recap of your watchlist's top movers at the close and after hours",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** The AI morning-brief channel — its own default-importance channel so it can be muted apart from
     *  price alerts and the movers recap. */
    fun ensureBriefChannel(context: Context) {
        ensureChannel(
            context, BRIEF_CHANNEL_ID, "AI morning brief",
            "A once-a-morning AI read of the tape, your watchlist, and today's catalysts",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** The AI sandbox's paper-trade channel — its own default-importance channel so trade pings can be
     *  muted separately from price alerts and the daily brief. */
    fun ensureSandboxChannel(context: Context) {
        ensureChannel(
            context, SANDBOX_CHANNEL_ID, "Sandbox trades",
            "When the AI paper trader buys or sells in the sandbox",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    private fun ensureChannel(
        context: Context,
        id: String,
        name: String,
        desc: String,
        importance: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(id, name, importance).apply { description = desc },
                )
            }
        }
    }

    /** Post a price-alert notification (high-importance channel). */
    fun notify(context: Context, id: Int, title: String, text: String, route: String?): Boolean =
        post(context, CHANNEL_ID, NotificationCompat.PRIORITY_HIGH, id, title, text, route)

    /** Post a scan-family notification (its own default-importance channel; grouped — see [ensureScanChannel]). */
    fun notifyScan(context: Context, id: Int, title: String, text: String, route: String?): Boolean =
        post(context, SCAN_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text, route, group = SCAN_GROUP_KEY)

    /** Post a market-summary notification (its own default-importance channel). */
    fun notifyMarket(context: Context, id: Int, title: String, text: String, route: String?): Boolean =
        post(context, MARKET_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text, route)

    /** Post the AI morning brief (its own default-importance channel). */
    fun notifyBrief(context: Context, id: Int, title: String, text: String, route: String?): Boolean =
        post(context, BRIEF_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text, route)

    /** Post a sandbox paper-trade notification (its own default-importance channel). */
    fun notifySandbox(context: Context, id: Int, title: String, text: String, route: String?): Boolean =
        post(context, SANDBOX_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text, route)

    /**
     * Post one notification. Returns TRUE only when it was actually handed to the system.
     *
     * It used to return Unit and skip silently when POST_NOTIFICATIONS was denied, so callers that
     * advance a watermark after "notifying" (the sandbox trade notifier, the scan notifier) recorded
     * alerts as delivered that the user never saw — and, having moved the watermark past them, could
     * never send them again. Permission is not requested anywhere except the ticker-alert sheet, so
     * for the market recap (on by default), the AI brief and call tracking this is the normal path,
     * not an edge case.
     *
     * NOTIF-1: permission was the ONLY gate checked here, but it is not the only way a post silently
     * disappears — the user can also block the app's notifications outright, or mute one channel
     * without touching the app toggle, and [NotificationManagerCompat.notify] "succeeds" (no exception)
     * in both cases anyway. See [AlertDelivery] for the shared, unit-tested decision.
     */
    private fun post(
        context: Context,
        channelId: String,
        priority: Int,
        id: Int,
        title: String,
        text: String,
        /** Where the tap lands, from [com.stocktracker.app.ui.Routes]. Null opens wherever the app was. */
        route: String?,
        /** Non-null bundles this post with every other post sharing the same key so they collapse
         *  together in the shade (see [notifyScan]); also triggers a group-summary post. */
        group: String? = null,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false // no notification permission — the caller must NOT record this as sent
        }
        when (channelId) {
            MARKET_CHANNEL_ID -> ensureMarketChannel(context)
            BRIEF_CHANNEL_ID -> ensureBriefChannel(context)
            SANDBOX_CHANNEL_ID -> ensureSandboxChannel(context)
            SCAN_CHANNEL_ID -> ensureScanChannel(context)
            else -> ensureChannel(context)
        }

        val channelImportance = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId)?.importance
        if (!AlertDelivery.canDeliver(NotificationManagerCompat.from(context).areNotificationsEnabled(), channelImportance)) {
            return false // app blocked, or this channel muted — the caller must NOT record this as sent
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // The whole point of the deep link. Without it every notification in this app — a
            // price alert naming a ticker, a sandbox trade, a dip list — opened on whatever screen
            // the app was last left on, and the user had to go find the thing they had just been
            // told about.
            route?.let { putExtra(EXTRA_ROUTE, it) }
        }
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply { if (group != null) setGroup(group) }
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            if (group != null) postGroupSummary(context, channelId, group)
            true
        } catch (e: SecurityException) {
            // Permission revoked between the check above and the post.
            false
        }
    }

    /** The system only visually bundles grouped notifications once a summary post exists for the
     *  group — post/refresh it alongside every real post rather than tracking a count, since re-notifying
     *  the same id is just an update. Losing this is cosmetic only, so failures here are swallowed. */
    private fun postGroupSummary(context: Context, channelId: String, group: String) {
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Signal & scan alerts")
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SCAN_SUMMARY_ID, summary) }
    }

    /**
     * Current importance of the price-alerts channel, or null if it has never been created (the
     * background worker has never posted through it). Read by Settings ([AlertDelivery.current]) to
     * explain why alerts might not arrive even when the worker itself looks healthy.
     */
    fun priceAlertChannelImportance(context: Context): Int? =
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)?.importance
}
