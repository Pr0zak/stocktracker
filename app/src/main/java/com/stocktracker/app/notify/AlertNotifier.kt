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

    private const val CHANNEL_ID = "price_alerts"
    private const val MARKET_CHANNEL_ID = "market_summary"
    private const val BRIEF_CHANNEL_ID = "ai_daily_brief"

    fun ensureChannel(context: Context) {
        ensureChannel(
            context, CHANNEL_ID, "Price alerts",
            "Alerts when a tracked price crosses your thresholds",
            NotificationManager.IMPORTANCE_HIGH,
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
    fun notify(context: Context, id: Int, title: String, text: String) =
        post(context, CHANNEL_ID, NotificationCompat.PRIORITY_HIGH, id, title, text)

    /** Post a market-summary notification (its own default-importance channel). */
    fun notifyMarket(context: Context, id: Int, title: String, text: String) =
        post(context, MARKET_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text)

    /** Post the AI morning brief (its own default-importance channel). */
    fun notifyBrief(context: Context, id: Int, title: String, text: String) =
        post(context, BRIEF_CHANNEL_ID, NotificationCompat.PRIORITY_DEFAULT, id, title, text)

    private fun post(
        context: Context,
        channelId: String,
        priority: Int,
        id: Int,
        title: String,
        text: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // no notification permission — silently skip
        }
        when (channelId) {
            MARKET_CHANNEL_ID -> ensureMarketChannel(context)
            BRIEF_CHANNEL_ID -> ensureBriefChannel(context)
            else -> ensureChannel(context)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
