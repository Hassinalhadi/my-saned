package dev.saned.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

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
  val combined = "${order.status} ${order.decisionReason}".lowercase(Locale.US)
  val isTakenByOther = combined.contains("already accepted") ||
      combined.contains("already assigned") ||
      combined.contains("سائق آخر") ||
      combined.contains("taken")
  val isAcceptFailed = !isAccepted && (isTakenByOther || combined.contains("fail") || combined.contains("فشل") || combined.contains("تعذر"))

  val title = when {
      isAccepted -> when (lang) {
          "ar" -> "✅ تم قبول الطلب رقم #$ref"
          "ur" -> "✅ آرڈر #$ref قبول کر لیا گیا"
          "bn" -> "✅ অর্ডার #$ref গ্রহণ করা হয়েছে"
          else -> "✅ Order #$ref Accepted"
      }
      isTakenByOther -> when (lang) {
          "ar" -> "⚠️ تم قبول الطلب #$ref من قبل سائق آخر"
          "ur" -> "⚠️ آرڈر #$ref کسی دوسرے ڈرائیور نے قبول کر لیا"
          "bn" -> "⚠️ অর্ডার #$ref অন্য ড্রাইভার গ্রহণ করেছে"
          else -> "⚠️ Order #$ref Accepted by Another Driver"
      }
      isAcceptFailed -> when (lang) {
          "ar" -> "❌ تعذر قبول الطلب رقم #$ref"
          "ur" -> "❌ آرڈر #$ref قبول نہ ہو سکا"
          "bn" -> "❌ অর্ডার #$ref গ্রহণ ব্যর্থ"
          else -> "❌ Order #$ref Accept Failed"
      }
      else -> when (lang) {
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
  if (isTakenByOther) {
      summaryParts.add(if (lang == "ar") "تم قبوله من سائق آخر" else "Taken by another driver")
  } else if (isAcceptFailed && order.decisionReason.isNotEmpty()) {
      summaryParts.add(order.decisionReason)
  }
  summaryParts.add("#$ref")
  summaryParts.add(restStr)
  if (distStr.isNotEmpty()) summaryParts.add(distStr)
  summaryParts.add(priceStr)

  var bigText = summaryParts.joinToString(" · ")
  if (isTakenByOther) {
      val takenNote = when (lang) {
          "ar" -> "\n⚠️ سبقك سائق آخر بقبول هذا الطلب (Order is already accepted)"
          "ur" -> "\n⚠️ یہ آرڈر پہلے ہی کسی دوسرے ڈرائیور نے قبول کر لیا ہے"
          "bn" -> "\n⚠️ এই অর্ডারটি ইতিমধ্যে অন্য একজন ড্রাইভার গ্রহণ করেছে"
          else -> "\n⚠️ Another driver accepted this order first (Order is already accepted)"
      }
      bigText += takenNote
  } else if (isAcceptFailed && order.decisionReason.isNotEmpty()) {
      bigText += "\n❌ ${order.decisionReason}"
  }
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
