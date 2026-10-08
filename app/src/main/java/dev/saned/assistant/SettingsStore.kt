package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("saned_prefs", Context.MODE_PRIVATE)

    var isAutoAcceptEnabled: Boolean
        get() = prefs.getBoolean("auto_accept", true)
        set(value) = prefs.edit().putBoolean("auto_accept", value).apply()

    var minPrice: Double
        get() = prefs.getFloat("min_price", 10.0f).toDouble()
        set(value) = prefs.edit().putFloat("min_price", value.toFloat()).apply()

    var maxRestaurantDistKm: Double
        get() = prefs.getFloat("max_rest_dist", 5.0f).toDouble()
        set(value) = prefs.edit().putFloat("max_rest_dist", value.toFloat()).apply()

    var maxCustomerDistKm: Double
        get() = prefs.getFloat("max_cust_dist", 15.0f).toDouble()
        set(value) = prefs.edit().putFloat("max_cust_dist", value.toFloat()).apply()

    // ميزة حصرية لحل مشكلة location unknown
    var acceptWhenLocationUnknown: Boolean
        get() = prefs.getBoolean("accept_when_loc_unknown", true)
        set(value) = prefs.edit().putBoolean("accept_when_loc_unknown", value).apply()
}
