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

    // Master Switch - Default TRUE (Active)
    fun isMasterRunning(context: Context): Boolean = 
        getPrefs(context).getBoolean("master_running", getPrefs(context).getBoolean("enabled", true))
    
    fun setMasterRunning(context: Context, value: Boolean) {
        getPrefs(context).edit()
            .putBoolean("master_running", value)
            .putBoolean("enabled", value)
            .apply()
        broadcastSettings(context)
    }

    // Auto Accept & Filters - Default TRUE for auto_accept
    fun isAutoAccept(context: Context): Boolean = getPrefs(context).getBoolean("auto_accept", true)
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

    // Active Server Polling & Parallel Acceptance (Original Assistant Features)
    fun isActivePolling(context: Context): Boolean = getPrefs(context).getBoolean("active_polling", true)
    fun setActivePolling(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("active_polling", value).apply()
        broadcastSettings(context)
    }

    fun getPollInterval(context: Context): Float = getPrefs(context).getFloat("poll_interval_sec", 0.8f)
    fun setPollInterval(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("poll_interval_sec", value).apply()
        broadcastSettings(context)
    }

    fun getParallelRequests(context: Context): Int = getPrefs(context).getInt("parallel_requests", 3)
    fun setParallelRequests(context: Context, value: Int) {
        getPrefs(context).edit().putInt("parallel_requests", value).apply()
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

    // Location & GPS
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
        try {
            val f = File("/data/local/tmp/saned_orders.json")
            if (f.exists()) f.writeText("[]")
        } catch (_: Throwable) {}
    }

    fun broadcastSettings(context: Context) {
        val master = isMasterRunning(context)
        val autoAccept = isAutoAccept(context)
        val autoReject = isAutoReject(context)
        val dryRun = isDryRun(context)
        val activePolling = isActivePolling(context)
        val pollInterval = getPollInterval(context)
        val parallelReqs = getParallelRequests(context)
        val minPrice = getMinPrice(context)
        val maxRest = getMaxDistRest(context)
        val maxCust = getMaxDistCust(context)

        // 1. Explicit broadcast to Jahez app
        try {
            val intent = Intent("dev.saned.assistant.SETTINGS_UPDATE").apply {
                setPackage("net.jahez.fleets")
                putExtra("master_running", master)
                putExtra("auto_accept", autoAccept)
                putExtra("auto_reject", autoReject)
                putExtra("dry_run", dryRun)
                putExtra("active_polling", activePolling)
                putExtra("poll_interval_sec", pollInterval)
                putExtra("parallel_requests", parallelReqs)
                putExtra("min_price", minPrice)
                putExtra("max_dist_rest", maxRest)
                putExtra("max_dist_cust", maxCust)
                putExtra("spoof_android_id", isSpoofAndroidId(context))
                putExtra("spoofed_android_id", getSpoofedAndroidId(context))
                putExtra("sound_enabled", isSoundEnabled(context))
                putExtra("show_toasts", isShowToasts(context))
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}

        // 2. Global broadcast for dynamic receivers
        try {
            val gIntent = Intent("dev.saned.assistant.SETTINGS_UPDATE").apply {
                putExtra("master_running", master)
                putExtra("auto_accept", autoAccept)
                putExtra("auto_reject", autoReject)
                putExtra("dry_run", dryRun)
                putExtra("active_polling", activePolling)
                putExtra("poll_interval_sec", pollInterval)
                putExtra("parallel_requests", parallelReqs)
                putExtra("min_price", minPrice)
                putExtra("max_dist_rest", maxRest)
                putExtra("max_dist_cust", maxCust)
            }
            context.sendBroadcast(gIntent)
        } catch (_: Throwable) {}

        // 3. Write world-readable shared config in /data/local/tmp/
        try {
            val json = JSONObject().apply {
                put("master_running", master)
                put("auto_accept", autoAccept)
                put("auto_reject", autoReject)
                put("dry_run", dryRun)
                put("active_polling", activePolling)
                put("poll_interval_sec", pollInterval.toDouble())
                put("parallel_requests", parallelReqs)
                put("min_price", minPrice)
                put("max_dist_rest", maxRest)
                put("max_dist_cust", maxCust)
                put("spoof_android_id", isSpoofAndroidId(context))
                put("spoofed_android_id", getSpoofedAndroidId(context))
            }
            val file = File("/data/local/tmp/saned_config.json")
            file.writeText(json.toString())
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (_: Throwable) {}
    }
}
