package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences

object SettingsStore {

    private const val PREF_NAME = "sanedhook_settings"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // Master Switch - Default FALSE
    fun isMasterRunning(context: Context): Boolean = getPrefs(context).getBoolean("master_running", false)
    fun setMasterRunning(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("master_running", value).apply()

    // Auto Accept & Filters - Defaults FALSE / 0.0
    fun isAutoAccept(context: Context): Boolean = getPrefs(context).getBoolean("auto_accept", false)
    fun setAutoAccept(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("auto_accept", value).apply()

    fun isAutoReject(context: Context): Boolean = getPrefs(context).getBoolean("auto_reject", false)
    fun setAutoReject(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("auto_reject", value).apply()

    fun isDryRun(context: Context): Boolean = getPrefs(context).getBoolean("dry_run", false)
    fun setDryRun(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("dry_run", value).apply()

    fun getMinPrice(context: Context): Double = getPrefs(context).getString("min_price", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setMinPrice(context: Context, value: Double) = getPrefs(context).edit().putString("min_price", value.toString()).apply()

    fun getMaxDistRest(context: Context): Double = getPrefs(context).getString("max_dist_rest", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setMaxDistRest(context: Context, value: Double) = getPrefs(context).edit().putString("max_dist_rest", value.toString()).apply()

    fun getMaxDistCust(context: Context): Double = getPrefs(context).getString("max_dist_cust", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setMaxDistCust(context: Context, value: Double) = getPrefs(context).edit().putString("max_dist_cust", value.toString()).apply()

    // Location & GPS - Defaults FALSE / 0.0
    fun isFixLocation(context: Context): Boolean = getPrefs(context).getBoolean("fix_location_unknown", false)
    fun setFixLocation(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("fix_location_unknown", value).apply()

    fun isFakeLocation(context: Context): Boolean = getPrefs(context).getBoolean("fake_location_enabled", false)
    fun setFakeLocation(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("fake_location_enabled", value).apply()

    fun getFakeLat(context: Context): Double = getPrefs(context).getString("fake_lat", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setFakeLat(context: Context, value: Double) = getPrefs(context).edit().putString("fake_lat", value.toString()).apply()

    fun getFakeLng(context: Context): Double = getPrefs(context).getString("fake_lng", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setFakeLng(context: Context, value: Double) = getPrefs(context).edit().putString("fake_lng", value.toString()).apply()

    // Android ID (IMEI removed) - Defaults FALSE / ""
    fun isSpoofAndroidId(context: Context): Boolean = getPrefs(context).getBoolean("spoof_android_id", false)
    fun setSpoofAndroidId(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("spoof_android_id", value).apply()

    fun getSpoofedAndroidId(context: Context): String = getPrefs(context).getString("spoofed_android_id", "") ?: ""
    fun setSpoofedAndroidId(context: Context, value: String) = getPrefs(context).edit().putString("spoofed_android_id", value).apply()

    // Feedback
    fun isSoundEnabled(context: Context): Boolean = getPrefs(context).getBoolean("sound_enabled", false)
    fun setSoundEnabled(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("sound_enabled", value).apply()

    fun isShowToasts(context: Context): Boolean = getPrefs(context).getBoolean("show_toasts", false)
    fun setShowToasts(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean("show_toasts", value).apply()
    // Orders Log
    fun getOrdersLog(context: Context): String = getPrefs(context).getString("orders_log_json", "[]") ?: "[]"
    fun clearOrdersLog(context: Context) = getPrefs(context).edit().putString("orders_log_json", "[]").apply()
}
