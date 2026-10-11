package dev.saned.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SanedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            "dev.saned.assistant.ACTION_LOG_ORDER" -> {
                val orderId = intent.getStringExtra("order_id") ?: "#--"
                val reference = intent.getStringExtra("reference") ?: orderId
                val price = intent.getDoubleExtra("price", 0.0)
                val dist = intent.getDoubleExtra("distance", 0.0)
                val rest = intent.getStringExtra("restaurant") ?: ""
                val status = intent.getStringExtra("status") ?: "Accepted ✅"
                val customerName = intent.getStringExtra("customer_name") ?: ""
                val customerPhone = intent.getStringExtra("customer_phone") ?: ""
                val address = intent.getStringExtra("address") ?: ""
                val cashAmount = intent.getDoubleExtra("cash_amount", 0.0)
                val currency = intent.getStringExtra("currency") ?: "SAR"

                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

                val order = OrderModel(
                    id = orderId.filter { it.isDigit() }.toIntOrNull() ?: 0,
                    reference = reference,
                    status = status,
                    restaurant = rest,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    address = address,
                    price = price,
                    currency = currency,
                    cashAmount = cashAmount,
                    distanceKm = dist,
                    time = timeStr
                )

                val prefs = context.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
                val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
                val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

                val newArray = JSONArray()
                newArray.put(order.toJson())
                for (i in 0 until minOf(39, array.length())) {
                    newArray.put(array.getJSONObject(i))
                }
                prefs.edit().putString("orders_log_json", newArray.toString()).apply()

                // Trigger Instant System Notification (Luc1;.c)
                NotificationHelper.showOrderNotification(
                    context,
                    order,
                    isAccepted = status.contains("Accept", ignoreCase = true) || status.contains("قبول")
                )

                try {
                    context.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
                } catch (_: Throwable) {}
            }

            "dev.saned.assistant.REQUEST_SETTINGS" -> {
                SettingsStore.broadcastSettings(context)
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
