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
 * Strictly respects the selected app language ("ar" = 100% Arabic, "en" = 100% English) without mixing languages.
 */
object NotificationHelper {

    const val CHANNEL_ACCEPTED = "sanedhook_accepted"

    fun showOrderNotification(context: Context, order: OrderModel, isAccepted: Boolean = true) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val isAr = SettingsStore.getAppLanguage(context) != "en"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(CHANNEL_ACCEPTED)
                if (existing == null) {
                    val chan = NotificationChannel(
                        CHANNEL_ACCEPTED,
                        if (isAr) "إشعارات الطلبات المقبولة" else "Accepted Orders Notifications",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = if (isAr) "إشعارات فورية للطلبات الملتقطة والمقبولة" else "Instant notifications for captured and accepted orders"
                        enableLights(true)
                        enableVibration(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val ref = if (order.reference.isNotEmpty()) order.reference else order.id.toString().ifEmpty { if (isAr) "طلب" else "Offer" }

            val title = if (isAccepted) {
                if (isAr) "✅ تم قبول الطلب رقم #$ref" else "✅ Order #$ref Accepted"
            } else {
                if (isAr) "⚡ طلب جديد · #$ref" else "⚡ New Offer · #$ref"
            }

            val restStr = if (order.restaurant.isNotEmpty()) order.restaurant else if (isAr) "مطعم" else "Restaurant"
            val unitKm = if (isAr) "كم" else "km"
            val currLabel = if (isAr && order.currency.equals("SAR", ignoreCase = true)) "ر.س" else order.currency
            val distStr = if (order.distanceKm > 0.0) String.format(Locale.US, "%.1f %s", order.distanceKm, unitKm) else ""
            val priceStr = String.format(Locale.US, "%.1f %s", order.price, currLabel)

            val summaryParts = mutableListOf<String>()
            summaryParts.add("#$ref")
            summaryParts.add(restStr)
            if (distStr.isNotEmpty()) summaryParts.add(distStr)
            summaryParts.add(priceStr)

            var bigText = summaryParts.joinToString(" · ")
            if (order.cashAmount > 0.0) {
                val cashMsg = if (isAr) {
                    "\n💵 استلم نقداً: " + String.format(Locale.US, "%.1f %s", order.cashAmount, currLabel)
                } else {
                    "\n💵 Collect Cash: " + String.format(Locale.US, "%.1f %s", order.cashAmount, currLabel)
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
