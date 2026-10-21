package dev.saned.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/**
 * High-speed System Notification builder matching Luc1;.c from SanedAssistant:
 * Channel: sanedhook_accepted
 * Category: CATEGORY_EVENT
 * Multi-lingual: ar, en, ur, bn
 * PendingIntent: Launches Jahez Fleets directly with one tap
 */
object NotificationHelper {

    const val CHANNEL_ACCEPTED = "sanedhook_accepted"

    fun showOrderNotification(context: Context, order: OrderModel, isAccepted: Boolean = true) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(CHANNEL_ACCEPTED)
                if (existing == null) {
                    val chan = NotificationChannel(
                        CHANNEL_ACCEPTED,
                        "Saned Accepted Orders",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Instant notifications for captured and accepted Jahez orders"
                        enableLights(true)
                        enableVibration(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val lang = Locale.getDefault().language.lowercase()
            val ref = if (order.reference.isNotEmpty()) order.reference else order.id.toString().ifEmpty { "Offer" }

            val title = if (isAccepted) {
                when (lang) {
                    "ar" -> "✅ تم قبول الطلب رقم #$ref"
                    "ur" -> "✅ آرڈر #$ref قبول کر لیا گیا"
                    "bn" -> "✅ অর্ডার #$ref গ্রহণ করা হয়েছে"
                    else -> "✅ Order #$ref Accepted"
                }
            } else {
                when (lang) {
                    "ar" -> "⚡ طلب جديد · #$ref"
                    "ur" -> "⚡ نیا آرڈر · #$ref"
                    "bn" -> "⚡ নতুন অর্ডার · #$ref"
                    else -> "⚡ New Offer · #$ref"
                }
            }

            val restStr = if (order.restaurant.isNotEmpty()) order.restaurant else "Jahez Fleet"
            val distStr = if (order.distanceKm > 0.0) String.format(Locale.US, "%.1f", order.distanceKm) + " km" else ""
            val priceStr = String.format(Locale.US, "%.1f", order.price) + " " + order.currency

            val summaryParts = mutableListOf<String>()
            summaryParts.add("#$ref")
            summaryParts.add(restStr)
            if (distStr.isNotEmpty()) summaryParts.add(distStr)
            summaryParts.add(priceStr)

            var bigText = summaryParts.joinToString(" · ")
            if (order.cashAmount > 0.0) {
                val cashMsg = when (lang) {
                    "ar" -> "\n💵 استلم نقداً: " + String.format(Locale.US, "%.1f", order.cashAmount) + " " + order.currency
                    "ur" -> "\n💵 نقد وصول کریں: " + String.format(Locale.US, "%.1f", order.cashAmount) + " " + order.currency
                    "bn" -> "\n💵 নগদ সংগ্রহ করুন: " + String.format(Locale.US, "%.1f", order.cashAmount) + " " + order.currency
                    else -> "\n💵 Collect Cash: " + String.format(Locale.US, "%.1f", order.cashAmount) + " " + order.currency
                }
                bigText += cashMsg
            }
            if (order.customerName.isNotEmpty()) {
                bigText += "\n👤 ${order.customerName}"
            }
            if (order.address.isNotEmpty()) {
                bigText += "\n📍 ${order.address}"
            }

            // Launch Jahez Fleets on click
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage("net.jahez.fleets")
                ?: pm.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            val pFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val notifId = if (order.id != 0) order.id else (ref.hashCode() and 0x7FFFFFFF)
            val pendingIntent = PendingIntent.getActivity(context, notifId, launchIntent, pFlags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ACCEPTED)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder.setContentTitle(title)
                .setContentText(summaryParts.joinToString(" · "))
                .setStyle(Notification.BigTextStyle().bigText(bigText))
                .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setSmallIcon(android.graphics.drawable.Icon.createWithResource("dev.jing.sanedhook", R.drawable.ic_notif_original))
                } else {
                    @Suppress("DEPRECATION")
                    setSmallIcon(R.drawable.ic_notif_original)
                }
            }
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setDefaults(Notification.DEFAULT_ALL)

            nm.notify(notifId, builder.build())
        } catch (_: Throwable) {}
    }

    fun formatPhoneForWhatsApp(phone: String): String {
        var clean = phone.replace(Regex("[^0-9+]"), "").trim()
        if (clean.startsWith("+")) {
            clean = clean.substring(1)
        }
        if (clean.startsWith("05") && clean.length == 10) {
            clean = "966" + clean.substring(1)
        } else if (clean.startsWith("5") && clean.length == 9) {
            clean = "966$clean"
        }
        return clean
    }
}
