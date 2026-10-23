package dev.saned.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.libxposed.service.XposedProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SanedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
  "dev.saned.assistant.ACTION_DIAG_LOG" -> {
      val logLine = intent.getStringExtra("log_line") ?: ""
      if (logLine.isNotEmpty()) {
          XposedProvider.appendDiagLog(context, logLine)
      }
  }

  "dev.saned.assistant.ACTION_LOG_ORDER" -> {
      val orderId = intent.getStringExtra("order_id") ?: "#--"
      val reference = intent.getStringExtra("reference") ?: orderId.replace("#", "")
      val price = intent.getDoubleExtra("price", 0.0)
      val dist = intent.getDoubleExtra("distance", 0.0)
      val custDist = intent.getDoubleExtra("customer_distance", 0.0)
      val rest = intent.getStringExtra("restaurant") ?: ""
      val pickupAddr = intent.getStringExtra("pickup_address") ?: ""
      val status = intent.getStringExtra("status") ?: "Accepted"
      val reason = intent.getStringExtra("decision_reason") ?: ""
      val customerName = intent.getStringExtra("customer_name") ?: ""
      val customerPhone = intent.getStringExtra("customer_phone") ?: ""
      val address = intent.getStringExtra("address") ?: ""
      val cashAmount = intent.getDoubleExtra("cash_amount", 0.0)
      val currency = intent.getStringExtra("currency") ?: "SAR"
      val notifyUser = intent.getBooleanExtra("notify_user", true)

      val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

      val order = OrderModel(
          id = orderId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0,
          reference = reference,
          status = status,
          restaurant = rest,
          pickupAddress = pickupAddr,
          customerName = customerName,
          customerPhone = customerPhone,
          address = address,
          price = price,
          currency = currency,
          cashAmount = cashAmount,
          distanceKm = dist,
          customerDistanceKm = custDist,
          decisionReason = reason,
          time = timeStr
      )

      XposedProvider.saveOrderAndNotify(context, order, notifyUser = notifyUser)
  }

  "dev.jing.sanedhook.b" -> {
      val ordersJson = intent.getStringExtra("o")
      if (!ordersJson.isNullOrEmpty()) {
          XposedProvider.saveOrdersBatch(context, ordersJson)
      }
  }

  "dev.saned.assistant.REQUEST_SETTINGS" -> {
      SettingsStore.broadcastSettings(context)
  }

  "dev.saned.assistant.ACTION_UPDATE_SETTINGS_FROM_OVERLAY" -> {
      try {
          val extras = intent.extras
          if (extras != null) {
              context.contentResolver.call(
                  android.net.Uri.parse("content://dev.jing.sanedhook.XposedService"),
                  "updateSettingsFromFloating",
                  null,
                  extras
              )
          }
      } catch (_: Throwable) {}
  }

  "dev.saned.assistant.ACTION_PONG" -> {
      try {
          val relay = Intent("dev.saned.assistant.UI_PONG_RECEIVED").apply {
              putExtras(intent)
          }
          context.sendBroadcast(relay)
      } catch (_: Throwable) {}
  }

  "dev.saned.assistant.ACTION_TEST_API_RESULT" -> {
      try {
          val relay = Intent("dev.saned.assistant.UI_TEST_API_RESULT").apply {
              putExtras(intent)
          }
          context.sendBroadcast(relay)
      } catch (_: Throwable) {}
  }
        }
    }
}
