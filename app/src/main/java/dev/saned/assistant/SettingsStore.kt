package dev.saned.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.service.XposedProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SettingsStore {

    private const val PREF_NAME = "sanedhook_settings"
    private val bgSyncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Saned-SettingsSync").apply { isDaemon = true }
    }
    private val syncPending = AtomicBoolean(false)

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

    fun isStayInBackground(context: Context): Boolean = getPrefs(context).getBoolean("stay_in_background_v2", false)
    fun setStayInBackground(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("stay_in_background_v2", value).apply()
        broadcastSettings(context)
    }

    fun isSilenceAlertTone(context: Context): Boolean = getPrefs(context).getBoolean("silence_alert_tone", true)
    fun setSilenceAlertTone(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("silence_alert_tone", value).apply()
        broadcastSettings(context)
    }

    fun isStrictVersionCheck(context: Context): Boolean = getPrefs(context).getBoolean("strict_version_check", false)
    fun setStrictVersionCheck(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("strict_version_check", value).apply()
        broadcastSettings(context)
    }

    // Extra Order Toggle (طلب إضافي / Extra Order): OFF = 2 orders (default), ON = 3 orders
    fun isExtraOrderEnabled(context: Context): Boolean = getPrefs(context).getBoolean("extra_order_enabled", false)
    fun setExtraOrderEnabled(context: Context, value: Boolean) {
        val ordersCount = if (value) 3 else 2
        getPrefs(context).edit()
            .putBoolean("extra_order_enabled", value)
            .putInt("max_orders_limit", ordersCount)
            .putString("max_orders_limit_str", ordersCount.toString())
            .apply()
        broadcastSettings(context)
    }

    fun getMaxOrdersLimit(context: Context): Int = if (isExtraOrderEnabled(context)) 3 else 2
    fun setMaxOrdersLimit(context: Context, value: Int) {
        val isExtra = value >= 3
        setExtraOrderEnabled(context, isExtra)
    }

    // Hook Group Isolation Toggles (Default TRUE)
    fun isHookEarlyCloaker(context: Context): Boolean = getPrefs(context).getBoolean("hook_early_cloaker", true)
    fun setHookEarlyCloaker(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_early_cloaker", value).apply()
        broadcastSettings(context)
    }

    fun isHookSecCloaker(context: Context): Boolean = getPrefs(context).getBoolean("hook_sec_cloaker", true)
    fun setHookSecCloaker(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_sec_cloaker", value).apply()
        broadcastSettings(context)
    }

    fun isHookLocation(context: Context): Boolean = getPrefs(context).getBoolean("hook_location", true)
    fun setHookLocation(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_location", value).apply()
        broadcastSettings(context)
    }

    fun isHookDeviceSpoofer(context: Context): Boolean = getPrefs(context).getBoolean("hook_device_spoofer", true)
    fun setHookDeviceSpoofer(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_device_spoofer", value).apply()
        broadcastSettings(context)
    }

    fun isHookOrders(context: Context): Boolean = getPrefs(context).getBoolean("hook_orders", true)
    fun setHookOrders(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_orders", value).apply()
        broadcastSettings(context)
    }

    fun isHookBootstrapLoop(context: Context): Boolean = getPrefs(context).getBoolean("hook_bootstrap_loop", true)
    fun setHookBootstrapLoop(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("hook_bootstrap_loop", value).apply()
        broadcastSettings(context)
    }

    // Diagnostic Logs
    fun getDiagLogs(context: Context): String {
        val spLogs = XposedProvider.getDiagLogs(context)
        if (spLogs.isNotEmpty()) return spLogs
        return try {
            val f = File("/data/local/tmp/saned_diag.log")
            if (f.exists()) f.readText() else ""
        } catch (_: Throwable) { "" }
    }

    fun clearDiagLogs(context: Context) {
        XposedProvider.clearDiagLogs(context)
        bgSyncExecutor.execute {
            try {
                val f = File("/data/local/tmp/saned_diag.log")
                if (f.exists()) f.writeText("")
            } catch (_: Throwable) {}
        }
    }

    // Active Server Polling & Parallel Acceptance (Always ON by default with Interval = 0 and Parallel = 3, hidden from UI)
    fun isActivePolling(context: Context): Boolean = true
    fun setActivePolling(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("active_polling", true).apply()
        broadcastSettings(context)
    }

    fun getPollInterval(context: Context): Float = 0.0f
    fun setPollInterval(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("poll_interval_sec", 0.0f).apply()
        broadcastSettings(context)
    }

    fun getParallelRequests(context: Context): Int = 3
    fun setParallelRequests(context: Context, value: Int) {
        getPrefs(context).edit().putInt("parallel_requests", 3).apply()
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

    // Route Simulator & Google Maps API Key
    fun isRouteSimActive(context: Context): Boolean = getPrefs(context).getBoolean("route_sim_active", false)
    fun setRouteSimActive(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("route_sim_active", value).apply()
        broadcastSettings(context)
    }

    fun getRouteSimSpeedKmh(context: Context): Float = getPrefs(context).getFloat("route_sim_speed_kmh", 45.0f)
    fun setRouteSimSpeedKmh(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("route_sim_speed_kmh", value.coerceIn(1.0f, 220.0f)).apply()
        broadcastSettings(context)
    }

    fun getRouteSimBearing(context: Context): Float = getPrefs(context).getFloat("route_sim_bearing", 0.0f)
    fun setRouteSimBearing(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("route_sim_bearing", value).apply()
    }

    fun getGoogleMapsApiKey(context: Context): String = getPrefs(context).getString("google_maps_api_key", "") ?: ""
    fun setGoogleMapsApiKey(context: Context, value: String) {
        getPrefs(context).edit().putString("google_maps_api_key", value.trim()).apply()
    }

    // App UI Language ("ar" = Pure Arabic, "en" = Pure English)
    fun getAppLanguage(context: Context): String = getPrefs(context).getString("app_ui_language", "ar") ?: "ar"
    fun setAppLanguage(context: Context, lang: String) {
        val clean = if (lang.lowercase().startsWith("en")) "en" else "ar"
        getPrefs(context).edit().putString("app_ui_language", clean).apply()
        broadcastSettings(context)
    }

    // Floating Icon Over Saned Screen (Default TRUE)
    fun isFloatingOverlayEnabled(context: Context): Boolean = getPrefs(context).getBoolean("floating_overlay_enabled", true)
    fun setFloatingOverlayEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("floating_overlay_enabled", value).apply()
        broadcastSettings(context)
    }

    // 2-Point Route Simulator (Point 1 -> Point 2) & Variable Speeds
    fun getPoint1Lat(context: Context): Double = getPrefs(context).getString("point1_lat", null)?.toDoubleOrNull() ?: getFakeLat(context)
    fun getPoint1Lng(context: Context): Double = getPrefs(context).getString("point1_lng", null)?.toDoubleOrNull() ?: getFakeLng(context)
    fun setPoint1(context: Context, lat: Double, lng: Double) {
        getPrefs(context).edit()
            .putString("point1_lat", lat.toString())
            .putString("point1_lng", lng.toString())
            .apply()
    }

    fun getPoint2Lat(context: Context): Double = getPrefs(context).getString("point2_lat", "0.0")?.toDoubleOrNull() ?: 0.0
    fun getPoint2Lng(context: Context): Double = getPrefs(context).getString("point2_lng", "0.0")?.toDoubleOrNull() ?: 0.0
    fun setPoint2(context: Context, lat: Double, lng: Double) {
        getPrefs(context).edit()
            .putString("point2_lat", lat.toString())
            .putString("point2_lng", lng.toString())
            .apply()
    }

    fun isVariableSpeedEnabled(context: Context): Boolean = getPrefs(context).getBoolean("route_sim_var_speed", true)
    fun setVariableSpeedEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("route_sim_var_speed", value).apply()
    }

    fun getRouteSimMinSpeedKmh(context: Context): Float = getPrefs(context).getFloat("route_sim_min_speed_kmh", 30.0f)
    fun setRouteSimMinSpeedKmh(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("route_sim_min_speed_kmh", value.coerceIn(1.0f, 220.0f)).apply()
    }

    fun getRouteSimMaxSpeedKmh(context: Context): Float = getPrefs(context).getFloat("route_sim_max_speed_kmh", 70.0f)
    fun setRouteSimMaxSpeedKmh(context: Context, value: Float) {
        getPrefs(context).edit().putFloat("route_sim_max_speed_kmh", value.coerceIn(1.0f, 220.0f)).apply()
    }

    fun pushLiveLocationStep(
        context: Context,
        lat: Double,
        lng: Double,
        speedKmh: Float,
        bearing: Float,
        simActive: Boolean
    ) {
        getPrefs(context).edit()
            .putBoolean("fake_location_enabled", true)
            .putString("fake_lat", lat.toString())
            .putString("fake_lng", lng.toString())
            .putBoolean("route_sim_active", simActive)
            .putFloat("route_sim_speed_kmh", speedKmh.coerceIn(1.0f, 220.0f))
            .putFloat("route_sim_bearing", bearing)
            .apply()
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

    // In-app Toasts inside Saned are permanently hidden (system status bar notifications remain active)
    fun isShowToasts(context: Context): Boolean = false
    fun setShowToasts(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("show_toasts", false).apply()
        broadcastSettings(context)
    }

    // Food Price is ALWAYS enabled by default and hidden from Settings UI
    fun isShowFoodPrice(context: Context): Boolean = true
    fun setShowFoodPrice(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("show_food_price", true).apply()
        broadcastSettings(context)
    }

    // Orders Log
    fun getOrdersLog(context: Context): String = getPrefs(context).getString("orders_log_json", "[]") ?: "[]"
    fun clearOrdersLog(context: Context) {
        val appCtx = context.applicationContext ?: context
        try {
            io.github.libxposed.service.XposedProvider.clearCachedOrders(appCtx)
        } catch (_: Throwable) {}
        getPrefs(context).edit().putString("orders_log_json", "[]").commit()
        bgSyncExecutor.execute {
            try {
                val f = File("/data/local/tmp/saned_orders.json")
                if (f.exists()) f.writeText("[]")
            } catch (_: Throwable) {}
            try {
                appCtx.sendBroadcast(Intent("dev.saned.assistant.ACTION_CLEAR_ORDERS").apply {
                    setPackage("net.jahez.fleets")
                })
                appCtx.sendBroadcast(Intent("dev.saned.assistant.ACTION_CLEAR_ORDERS"))
            } catch (_: Throwable) {}
        }
    }

    fun deleteSingleOrder(context: Context, orderId: Int, reference: String) {
        val appCtx = context.applicationContext ?: context
        val idStr = if (orderId != 0) orderId.toString() else ""
        val refStr = reference.replace("#", "").trim()
        try {
            io.github.libxposed.service.XposedProvider.deleteSingleOrder(appCtx, idStr, refStr)
        } catch (_: Throwable) {}
        bgSyncExecutor.execute {
            try {
                appCtx.sendBroadcast(Intent("dev.saned.assistant.ACTION_DELETE_ORDER").apply {
                    setPackage("net.jahez.fleets")
                    putExtra("order_id", idStr)
                    putExtra("reference", refStr)
                })
                appCtx.sendBroadcast(Intent("dev.saned.assistant.ACTION_DELETE_ORDER").apply {
                    putExtra("order_id", idStr)
                    putExtra("reference", refStr)
                })
            } catch (_: Throwable) {}
        }
    }

    /**
     * Non-blocking coalesced background settings broadcast:
     * Executes Binder IPC, Broadcasts, and JSON file sync on bgSyncExecutor so the Main UI Thread never stutters!
     */
    fun broadcastSettings(context: Context) {
        val appCtx = context.applicationContext ?: context
        val modNow = System.currentTimeMillis()
        try { getPrefs(appCtx).edit().putLong("last_settings_mod_ms", modNow).commit() } catch (_: Throwable) {}
        if (!syncPending.compareAndSet(false, true)) return
        bgSyncExecutor.execute {
            syncPending.set(false)
            try { XposedProvider.syncToLSPosedRemotePrefs(appCtx) } catch (_: Throwable) {}
            val master = isMasterRunning(appCtx)
            val autoAccept = isAutoAccept(appCtx)
            val autoReject = isAutoReject(appCtx)
            val dryRun = isDryRun(appCtx)
            val stayBg = isStayInBackground(appCtx)
            val silenceTone = isSilenceAlertTone(appCtx)
            val strictVer = isStrictVersionCheck(appCtx)
            val activePolling = isActivePolling(appCtx)
            val pollInterval = getPollInterval(appCtx)
            val parallelReqs = getParallelRequests(appCtx)
            val extraOrder = isExtraOrderEnabled(appCtx)
            val maxOrdersLim = getMaxOrdersLimit(appCtx)
            val minPrice = getMinPrice(appCtx)
            val maxRest = getMaxDistRest(appCtx)
            val maxCust = getMaxDistCust(appCtx)
            val hEarly = isHookEarlyCloaker(appCtx)
            val hSec = isHookSecCloaker(appCtx)
            val hLoc = isHookLocation(appCtx)
            val hDev = isHookDeviceSpoofer(appCtx)
            val hOrd = isHookOrders(appCtx)
            val hBoot = isHookBootstrapLoop(appCtx)
            val spoofId = isSpoofAndroidId(appCtx)
            val spoofedIdStr = getSpoofedAndroidId(appCtx)
            val fixLoc = isFixLocation(appCtx)
            val fakeLoc = isFakeLocation(appCtx)
            val fLat = getFakeLat(appCtx)
            val fLng = getFakeLng(appCtx)
            val rSimAct = isRouteSimActive(appCtx)
            val rSimSpd = getRouteSimSpeedKmh(appCtx)
            val rSimBrg = getRouteSimBearing(appCtx)
            val snd = isSoundEnabled(appCtx)
            val toasts = isShowToasts(appCtx)

            try {
                val intent = Intent("dev.saned.assistant.SETTINGS_UPDATE").apply {
                    setPackage("net.jahez.fleets")
                    putExtra("master_running", master)
                    putExtra("auto_accept", autoAccept)
                    putExtra("auto_reject", autoReject)
                    putExtra("dry_run", dryRun)
                    putExtra("stay_in_background_v2", stayBg)
                    putExtra("silence_alert_tone", silenceTone)
                    putExtra("strict_version_check", strictVer)
                    putExtra("active_polling", activePolling)
                    putExtra("poll_interval_sec", pollInterval)
                    putExtra("parallel_requests", parallelReqs)
                    putExtra("extra_order_enabled", extraOrder)
                    putExtra("max_orders_limit", maxOrdersLim)
                    putExtra("min_price", minPrice)
                    putExtra("max_dist_rest", maxRest)
                    putExtra("max_dist_cust", maxCust)
                    putExtra("spoof_android_id", spoofId)
                    putExtra("spoofed_android_id", spoofedIdStr)
                    putExtra("fix_location_unknown", fixLoc)
                    putExtra("fake_location_enabled", fakeLoc)
                    putExtra("fake_lat", fLat)
                    putExtra("fake_lng", fLng)
                    putExtra("route_sim_active", rSimAct)
                    putExtra("route_sim_speed_kmh", rSimSpd)
                    putExtra("route_sim_bearing", rSimBrg)
                    putExtra("sound_enabled", snd)
                    putExtra("show_toasts", toasts)
                    putExtra("show_food_price", true)
                    putExtra("floating_overlay_enabled", isFloatingOverlayEnabled(appCtx))
                    putExtra("app_ui_language", getAppLanguage(appCtx))
                    putExtra("last_settings_mod_ms", getPrefs(appCtx).getLong("last_settings_mod_ms", System.currentTimeMillis()))
                    putExtra("hook_early_cloaker", hEarly)
                    putExtra("hook_sec_cloaker", hSec)
                    putExtra("hook_location", hLoc)
                    putExtra("hook_device_spoofer", hDev)
                    putExtra("hook_orders", hOrd)
                    putExtra("hook_bootstrap_loop", hBoot)
                }
                appCtx.sendBroadcast(intent)
            } catch (_: Throwable) {}

            try {
                val json = JSONObject().apply {
                    put("master_running", master)
                    put("enabled", master)
                    put("auto_accept", autoAccept)
                    put("auto_reject", autoReject)
                    put("dry_run", dryRun)
                    put("stay_in_background_v2", stayBg)
                    put("silence_alert_tone", silenceTone)
                    put("strict_version_check", strictVer)
                    put("active_polling", activePolling)
                    put("poll_interval_sec", pollInterval.toDouble())
                    put("parallel_requests", parallelReqs)
                    put("extra_order_enabled", extraOrder)
                    put("max_orders_limit", maxOrdersLim)
                    put("min_price", minPrice)
                    put("max_dist_rest", maxRest)
                    put("max_dist_cust", maxCust)
                    put("spoof_android_id", spoofId)
                    put("spoofed_android_id", spoofedIdStr)
                    put("fix_location_unknown", fixLoc)
                    put("fake_location_enabled", fakeLoc)
                    put("fake_lat", fLat)
                    put("fake_lng", fLng)
                    put("route_sim_active", rSimAct)
                    put("route_sim_speed_kmh", rSimSpd.toDouble())
                    put("route_sim_bearing", rSimBrg.toDouble())
                    put("hook_early_cloaker", hEarly)
                    put("hook_sec_cloaker", hSec)
                    put("hook_location", hLoc)
                    put("hook_device_spoofer", hDev)
                    put("hook_orders", hOrd)
                    put("hook_bootstrap_loop", hBoot)
                    put("show_food_price", true)
                    put("floating_overlay_enabled", isFloatingOverlayEnabled(appCtx))
                    put("app_ui_language", getAppLanguage(appCtx))
                }
                val file = File("/data/local/tmp/saned_config.json")
                file.writeText(json.toString())
                file.setReadable(true, false)
                file.setWritable(true, false)
            } catch (_: Throwable) {}
        }
    }
}
