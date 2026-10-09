package dev.saned.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

object SettingsStore {

    private const val PREF_NAME = "sanedhook_settings"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // Master Switch - Default FALSE
    fun isMasterRunning(context: Context): Boolean = 
        getPrefs(context).getBoolean("master_running", getPrefs(context).getBoolean("enabled", false))
    
    fun setMasterRunning(context: Context, value: Boolean) {
        getPrefs(context).edit()
            .putBoolean("master_running", value)
            .putBoolean("enabled", value)
            .apply()
        broadcastSettings(context)
    }

    // Auto Accept & Filters - Defaults FALSE / 0.0
    fun isAutoAccept(context: Context): Boolean = getPrefs(context).getBoolean("auto_accept", false)
    fun setAutoAccept(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("auto_accept", value).apply()
        broadcastSettings(context)
    }

    fun isAutoReject(context: Context): Boolean = getPrefs(context).getBoolean("auto_reject", false)
    fun setAutoReject(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("auto_reject", value).apply()
        broadcastSettings(context)
    }

    fun isDryRun(context: Context): Boolean = getPrefs(context).getBoolean("dry_run", false)
    fun setDryRun(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("dry_run", value).apply()
        broadcastSettings(context)
    }

    fun getMinPrice(context: Context): Double = 
        getPrefs(context).getString("min_price", getPrefs(context).getString("min_order_price", "0.0"))?.toDoubleOrNull() ?: 0.0
    
    fun setMinPrice(context: Context, value: Double) {
        getPrefs(context).edit()
            .putString("min_price", value.toString())
            .putString("min_order_price", value.toString())
            .putBoolean("price_gate_enabled", value > 0.0)
            .apply()
        broadcastSettings(context)
    }

    fun getMaxDistRest(context: Context): Double = 
        getPrefs(context).getString("max_dist_rest", getPrefs(context).getString("max_dist_to_restaurant", "0.0"))?.toDoubleOrNull() ?: 0.0
    
    fun setMaxDistRest(context: Context, value: Double) {
        getPrefs(context).edit()
            .putString("max_dist_rest", value.toString())
            .putString("max_dist_to_restaurant", value.toString())
            .putBoolean("restaurant_gate_enabled", value > 0.0)
            .apply()
        broadcastSettings(context)
    }

    fun getMaxDistCust(context: Context): Double = 
        getPrefs(context).getString("max_dist_cust", getPrefs(context).getString("max_dist_restaurant_to_customer", "0.0"))?.toDoubleOrNull() ?: 0.0
    
    fun setMaxDistCust(context: Context, value: Double) {
        getPrefs(context).edit()
            .putString("max_dist_cust", value.toString())
            .putString("max_dist_restaurant_to_customer", value.toString())
            .putBoolean("customer_gate_enabled", value > 0.0)
            .apply()
        broadcastSettings(context)
    }

    // Location & GPS - Defaults FALSE / 0.0
    fun isFixLocation(context: Context): Boolean = getPrefs(context).getBoolean("fix_location_unknown", false)
    fun setFixLocation(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("fix_location_unknown", value).apply()
        broadcastSettings(context)
    }

    fun isFakeLocation(context: Context): Boolean = getPrefs(context).getBoolean("fake_location_enabled", false)
    fun setFakeLocation(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("fake_location_enabled", value).apply()
        broadcastSettings(context)
    }

    fun getFakeLat(context: Context): Double = getPrefs(context).getString("fake_lat", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setFakeLat(context: Context, value: Double) {
        getPrefs(context).edit().putString("fake_lat", value.toString()).apply()
        broadcastSettings(context)
    }

    fun getFakeLng(context: Context): Double = getPrefs(context).getString("fake_lng", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setFakeLng(context: Context, value: Double) {
        getPrefs(context).edit().putString("fake_lng", value.toString()).apply()
        broadcastSettings(context)
    }

    // Android ID
    fun isSpoofAndroidId(context: Context): Boolean = getPrefs(context).getBoolean("spoof_android_id", false)
    fun setSpoofAndroidId(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("spoof_android_id", value).apply()
        broadcastSettings(context)
    }

    fun getSpoofedAndroidId(context: Context): String = getPrefs(context).getString("spoofed_android_id", "") ?: ""
    fun setSpoofedAndroidId(context: Context, value: String) {
        getPrefs(context).edit().putString("spoofed_android_id", value).apply()
        broadcastSettings(context)
    }

    // Feedback
    fun isSoundEnabled(context: Context): Boolean = getPrefs(context).getBoolean("sound_enabled", false)
    fun setSoundEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("sound_enabled", value).apply()
        broadcastSettings(context)
    }

    fun isShowToasts(context: Context): Boolean = getPrefs(context).getBoolean("show_toasts", true)
    fun setShowToasts(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("show_toasts", value).apply()
        broadcastSettings(context)
    }

    // Orders Log
    fun getOrdersLog(context: Context): String = getPrefs(context).getString("orders_log_json", "[]") ?: "[]"
    fun clearOrdersLog(context: Context) {
        getPrefs(context).edit().putString("orders_log_json", "[]").apply()
    }

    fun broadcastSettings(context: Context) {
        try {
            val intent = Intent("dev.saned.assistant.SETTINGS_UPDATE").apply {
                putExtra("master_running", isMasterRunning(context))
                putExtra("auto_accept", isAutoAccept(context))
                putExtra("auto_reject", isAutoReject(context))
                putExtra("dry_run", isDryRun(context))
                putExtra("min_price", getMinPrice(context))
                putExtra("max_dist_rest", getMaxDistRest(context))
                putExtra("max_dist_cust", getMaxDistCust(context))
                putExtra("fix_location_unknown", isFixLocation(context))
                putExtra("fake_location_enabled", isFakeLocation(context))
                putExtra("fake_lat", getFakeLat(context))
                putExtra("fake_lng", getFakeLng(context))
                putExtra("spoof_android_id", isSpoofAndroidId(context))
                putExtra("spoofed_android_id", getSpoofedAndroidId(context))
                putExtra("sound_enabled", isSoundEnabled(context))
                putExtra("show_toasts", isShowToasts(context))
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}

        // Fallback root config file
        try {
            val json = JSONObject().apply {
                put("master_running", isMasterRunning(context))
                put("auto_accept", isAutoAccept(context))
                put("auto_reject", isAutoReject(context))
                put("dry_run", isDryRun(context))
                put("min_price", getMinPrice(context))
                put("max_dist_rest", getMaxDistRest(context))
                put("max_dist_cust", getMaxDistCust(context))
                put("spoof_android_id", isSpoofAndroidId(context))
                put("spoofed_android_id", getSpoofedAndroidId(context))
            }
            val file = File("/data/local/tmp/saned_config.json")
            file.writeText(json.toString())
            file.setReadable(true, false)
        } catch (_: Throwable) {}
    }
}
