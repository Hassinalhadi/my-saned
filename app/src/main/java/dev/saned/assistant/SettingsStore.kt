package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object SettingsStore {
    const val PREF_NAME = "sanedhook_settings"

    // Master
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_ACCEPT = "auto_accept"
    const val KEY_AUTO_REJECT = "auto_reject"
    const val KEY_PARALLEL_ACCEPT = "parallel_requests"
    const val KEY_DRY_RUN = "dry_run"

    // Gates / Filters
    const val KEY_PRICE_GATE = "price_gate_enabled"
    const val KEY_MIN_PRICE = "min_order_price"
    const val KEY_REST_GATE = "restaurant_gate_enabled"
    const val KEY_MAX_DIST_REST = "max_dist_to_restaurant"
    const val KEY_CUST_GATE = "customer_gate_enabled"
    const val KEY_MAX_DIST_CUST = "max_dist_restaurant_to_customer"

    // Location & GPS
    const val KEY_FIX_LOCATION = "high_accuracy_location"
    const val KEY_FAKE_LOCATION = "fake_location_enabled"
    const val KEY_FAKE_LAT = "fake_lat"
    const val KEY_FAKE_LNG = "fake_lng"
    const val KEY_FAKE_TRIP = "fake_trip"
    const val KEY_FAKE_LABEL = "fake_label"

    // Device Spoofing (IMEI & Android ID)
    const val KEY_SPOOF_ANDROID_ID = "spoof_android_id"
    const val KEY_SPOOFED_ANDROID_ID = "spoofed_android_id"
    const val KEY_SPOOF_IMEI = "spoof_imei"
    const val KEY_SPOOFED_IMEI = "spoofed_imei"

    // UI & System
    const val KEY_SHOW_OVERLAY = "systemOverlays"
    const val KEY_SOUND = "sound"
    const val KEY_SHOW_TOASTS = "show_toasts"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(ctx: Context) = getPrefs(ctx).getBoolean(KEY_ENABLED, true)
    fun isAutoAccept(ctx: Context) = getPrefs(ctx).getBoolean(KEY_AUTO_ACCEPT, true)
    fun isAutoReject(ctx: Context) = getPrefs(ctx).getBoolean(KEY_AUTO_REJECT, false)
    fun isParallelAccept(ctx: Context) = getPrefs(ctx).getBoolean(KEY_PARALLEL_ACCEPT, true)
    fun isDryRun(ctx: Context) = getPrefs(ctx).getBoolean(KEY_DRY_RUN, false)

    fun isPriceGateEnabled(ctx: Context) = getPrefs(ctx).getBoolean(KEY_PRICE_GATE, true)
    fun getMinPrice(ctx: Context) = getPrefs(ctx).getString(KEY_MIN_PRICE, "0.0")?.toDoubleOrNull() ?: 0.0

    fun isRestGateEnabled(ctx: Context) = getPrefs(ctx).getBoolean(KEY_REST_GATE, true)
    fun getMaxDistRest(ctx: Context) = getPrefs(ctx).getString(KEY_MAX_DIST_REST, "8.0")?.toDoubleOrNull() ?: 8.0

    fun isCustGateEnabled(ctx: Context) = getPrefs(ctx).getBoolean(KEY_CUST_GATE, false)
    fun getMaxDistCust(ctx: Context) = getPrefs(ctx).getString(KEY_MAX_DIST_CUST, "15.0")?.toDoubleOrNull() ?: 15.0

    fun isFixLocation(ctx: Context) = getPrefs(ctx).getBoolean(KEY_FIX_LOCATION, true)
    fun isFakeLocation(ctx: Context) = getPrefs(ctx).getBoolean(KEY_FAKE_LOCATION, false)
    fun getFakeLat(ctx: Context) = getPrefs(ctx).getString(KEY_FAKE_LAT, "24.774265")?.toDoubleOrNull() ?: 24.774265
    fun getFakeLng(ctx: Context) = getPrefs(ctx).getString(KEY_FAKE_LNG, "46.638527")?.toDoubleOrNull() ?: 46.638527
    fun isFakeTrip(ctx: Context) = getPrefs(ctx).getBoolean(KEY_FAKE_TRIP, false)

    fun isSpoofAndroidId(ctx: Context) = getPrefs(ctx).getBoolean(KEY_SPOOF_ANDROID_ID, true)
    fun getSpoofedAndroidId(ctx: Context): String {
        var id = getPrefs(ctx).getString(KEY_SPOOFED_ANDROID_ID, "") ?: ""
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            getPrefs(ctx).edit().putString(KEY_SPOOFED_ANDROID_ID, id).apply()
        }
        return id
    }

    fun isSpoofImei(ctx: Context) = getPrefs(ctx).getBoolean(KEY_SPOOF_IMEI, true)
    fun getSpoofedImei(ctx: Context): String {
        var imei = getPrefs(ctx).getString(KEY_SPOOFED_IMEI, "") ?: ""
        if (imei.isEmpty()) {
            imei = "86" + (1000000000000L + (Math.random() * 8999999999999L).toLong()).toString()
            getPrefs(ctx).edit().putString(KEY_SPOOFED_IMEI, imei).apply()
        }
        return imei
    }

    fun isShowOverlay(ctx: Context) = getPrefs(ctx).getBoolean(KEY_SHOW_OVERLAY, true)
    fun isSoundEnabled(ctx: Context) = getPrefs(ctx).getBoolean(KEY_SOUND, true)
    fun isShowToasts(ctx: Context) = getPrefs(ctx).getBoolean(KEY_SHOW_TOASTS, true)
}
