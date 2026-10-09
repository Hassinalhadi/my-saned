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

        if (action == "dev.saned.assistant.ACTION_LOG_ORDER") {
            val orderId = intent.getStringExtra("order_id") ?: "#--"
            val price = intent.getDoubleExtra("price", 0.0)
            val dist = intent.getDoubleExtra("distance", 0.0)
            val rest = intent.getStringExtra("restaurant") ?: ""
            val status = intent.getStringExtra("status") ?: "وارد عبر الشبكة 🌐"

            val prefs = context.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
            val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val newObj = JSONObject().apply {
                put("id", orderId)
                put("price", price)
                put("distance", dist)
                put("restaurant", rest)
                put("status", status)
                put("time", timeStr)
            }
            val newArray = JSONArray()
            newArray.put(newObj)
            for (i in 0 until minOf(39, array.length())) {
                newArray.put(array.getJSONObject(i))
            }
            prefs.edit().putString("orders_log_json", newArray.toString()).apply()

            // Notify UI
            try {
                context.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
            } catch (_: Throwable) {}
        }

        if (action == "dev.saned.assistant.REQUEST_SETTINGS") {
            SettingsStore.broadcastSettings(context)
        }
    }
}
