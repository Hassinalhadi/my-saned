package dev.saned.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object SettingsStore {
    const val PREFS_NAME = "sanedhook_settings"
    const val ACTION_SETTINGS_UPDATED = "dev.saned.assistant.SETTINGS_UPDATED"
    const val ACTION_ORDER_LOGGED = "dev.saned.assistant.ORDER_LOGGED"
    const val ACTION_DIAGNOSTICS_REPORT = "dev.saned.assistant.DIAGNOSTICS_REPORT"
    const val ACTION_REQUEST_DIAGNOSTICS = "dev.saned.assistant.REQUEST_DIAGNOSTICS"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMasterRunning(context: Context): Boolean = getPrefs(context).getBoolean("master_running", true)
    fun setMasterRunning(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("master_running", value).apply()
        broadcastSettings(context)
    }

    fun isFakeLocationEnabled(context: Context): Boolean = getPrefs(context).getBoolean("fake_location_enabled", false)
    fun setFakeLocationEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("fake_location_enabled", value).apply()
        broadcastSettings(context)
    }

    fun getLat(context: Context): Double =
        getPrefs(context).getString("fake_lat", "24.7136")?.toDoubleOrNull() ?: 24.7136

    fun getLng(context: Context): Double =
        getPrefs(context).getString("fake_lng", "46.6753")?.toDoubleOrNull() ?: 46.6753

    fun setLocation(context: Context, lat: Double, lng: Double) {
        getPrefs(context).edit()
  .putString("fake_lat", lat.toString())
  .putString("fake_lng", lng.toString())
  .apply()
        broadcastSettings(context)
    }

    fun isAutoAcceptEnabled(context: Context): Boolean = getPrefs(context).getBoolean("auto_accept_enabled", false)
    fun setAutoAcceptEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("auto_accept_enabled", value).apply()
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

    fun isActivePolling(context: Context): Boolean = getPrefs(context).getBoolean("active_polling", true)
    fun setActivePolling(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("active_polling", value).apply()
        broadcastSettings(context)
    }

    fun isSoundEnabled(context: Context): Boolean = getPrefs(context).getBoolean("sound_enabled", true)
    fun setSoundEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("sound_enabled", value).apply()
        broadcastSettings(context)
    }

    fun getMinPrice(context: Context): Double =
        getPrefs(context).getString("min_order_price", "0.0")?.toDoubleOrNull() ?: 0.0

    fun getMaxDistance(context: Context): Double =
        getPrefs(context).getString("max_order_distance", "999.0")?.toDoubleOrNull() ?: 999.0

    fun getMinCustomerDistance(context: Context): Double =
        getPrefs(context).getString("min_customer_distance", "0.0")?.toDoubleOrNull() ?: 0.0

    fun getMaxCustomerDistance(context: Context): Double =
        getPrefs(context).getString("max_customer_distance", "999.0")?.toDoubleOrNull() ?: 999.0

    fun getFilterAction(context: Context): String =
        getPrefs(context).getString("filter_action", "IGNORE") ?: "IGNORE"

    fun getParallelRequests(context: Context): Int =
        getPrefs(context).getInt("parallel_requests", 4).coerceIn(1, 8)

    fun setFilterRules(
        context: Context,
        minPrice: Double,
        maxDistance: Double,
        minCustDist: Double,
        maxCustDist: Double,
        filterAction: String,
        parallelReqs: Int,
        dryRun: Boolean
    ) {
        getPrefs(context).edit()
  .putString("min_order_price", minPrice.toString())
  .putString("max_order_distance", maxDistance.toString())
  .putString("min_customer_distance", minCustDist.toString())
  .putString("max_customer_distance", maxCustDist.toString())
  .putString("filter_action", filterAction)
  .putInt("parallel_requests", parallelReqs.coerceIn(1, 8))
  .putBoolean("dry_run", dryRun)
  .apply()
        broadcastSettings(context)
    }

    fun broadcastSettings(context: Context) {
        try {
  val master = isMasterRunning(context)
  val fakeLoc = isFakeLocationEnabled(context)
  val lat = getLat(context)
  val lng = getLng(context)
  val autoAcc = isAutoAcceptEnabled(context)
  val dryRun = isDryRun(context)
  val stayBg = isStayInBackground(context)
  val silenceTone = isSilenceAlertTone(context)
  val strictVer = isStrictVersionCheck(context)
  val minP = getMinPrice(context)
  val maxD = getMaxDistance(context)
  val minCD = getMinCustomerDistance(context)
  val maxCD = getMaxCustomerDistance(context)
  val fAct = getFilterAction(context)
  val pReqs = getParallelRequests(context)
  val poll = isActivePolling(context)
  val sound = isSoundEnabled(context)

  val intent = Intent(ACTION_SETTINGS_UPDATED).apply {
      putExtra("master_running", master)
      putExtra("fake_location_enabled", fakeLoc)
      putExtra("fake_lat", lat)
      putExtra("fake_lng", lng)
      putExtra("auto_accept_enabled", autoAcc)
      putExtra("dry_run", dryRun)
      putExtra("stay_in_background_v2", stayBg)
      putExtra("silence_alert_tone", silenceTone)
      putExtra("strict_version_check", strictVer)
      putExtra("min_order_price", minP)
      putExtra("max_order_distance", maxD)
      putExtra("min_customer_distance", minCD)
      putExtra("max_customer_distance", maxCD)
      putExtra("filter_action", fAct)
      putExtra("parallel_requests", pReqs)
      putExtra("active_polling", poll)
      putExtra("sound_enabled", sound)
  }
  context.sendBroadcast(intent)

  val json = JSONObject().apply {
      put("master_running", master)
      put("fake_location_enabled", fakeLoc)
      put("fake_lat", lat)
      put("fake_lng", lng)
      put("auto_accept_enabled", autoAcc)
      put("dry_run", dryRun)
      put("stay_in_background_v2", stayBg)
      put("silence_alert_tone", silenceTone)
      put("strict_version_check", strictVer)
      put("min_order_price", minP)
      put("max_order_distance", maxD)
      put("min_customer_distance", minCD)
      put("max_customer_distance", maxCD)
      put("filter_action", fAct)
      put("parallel_requests", pReqs)
      put("active_polling", poll)
      put("sound_enabled", sound)
  }
  File("/data/local/tmp/saned_config.json").apply {
      writeText(json.toString())
      setReadable(true, false)
  }
        } catch (_: Throwable) {}
    }

    data class SavedPlace(val name: String, val lat: Double, val lng: Double)

    fun getSavedPlaces(context: Context): List<SavedPlace> {
        val raw = getPrefs(context).getString("saved_places_json", "[]") ?: "[]"
        val list = mutableListOf<SavedPlace>()
        try {
  val arr = JSONArray(raw)
  for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      list.add(SavedPlace(o.optString("name", "Place"), o.optDouble("lat", 24.7136), o.optDouble("lng", 46.6753)))
  }
        } catch (_: Throwable) {}
        return list
    }

    fun addSavedPlace(context: Context, name: String, lat: Double, lng: Double) {
        val current = getSavedPlaces(context).toMutableList()
        current.removeAll { it.name.equals(name, ignoreCase = true) }
        current.add(0, SavedPlace(name, lat, lng))
        savePlacesList(context, current)
    }

    fun removeSavedPlace(context: Context, index: Int) {
        val current = getSavedPlaces(context).toMutableList()
        if (index in current.indices) {
  current.removeAt(index)
  savePlacesList(context, current)
        }
    }

    private fun savePlacesList(context: Context, list: List<SavedPlace>) {
        val arr = JSONArray()
        for (p in list) {
  arr.put(JSONObject().apply {
      put("name", p.name)
      put("lat", p.lat)
      put("lng", p.lng)
  })
        }
        getPrefs(context).edit().putString("saved_places_json", arr.toString()).apply()
    }

    data class OrderEntry(
        val id: String,
        val price: String,
        val distance: String,
        val restaurant: String,
        val status: String,
        val time: String,
        val customerName: String = "",
        val customerPhone: String = "",
        val address: String = "",
        val cashAmount: String = "",
        val pickupAddress: String = "",
        val customerDistance: String = "",
        val decisionReason: String = ""
    )

    fun getOrderLogs(context: Context): List<OrderEntry> {
        val raw = getPrefs(context).getString("order_logs_json", "[]") ?: "[]"
        val list = mutableListOf<OrderEntry>()
        try {
  val arr = JSONArray(raw)
  for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      list.add(
          OrderEntry(
              id = o.optString("id", "#???"),
              price = o.optString("price", "0.0"),
              distance = o.optString("distance", "0.0"),
              restaurant = o.optString("restaurant", "Jahez Order"),
              status = o.optString("status", "Detected"),
              time = o.optString("time", ""),
              customerName = o.optString("customerName", ""),
              customerPhone = o.optString("customerPhone", ""),
              address = o.optString("address", ""),
              cashAmount = o.optString("cashAmount", ""),
              pickupAddress = o.optString("pickupAddress", ""),
              customerDistance = o.optString("customerDistance", ""),
              decisionReason = o.optString("decisionReason", "")
          )
      )
  }
        } catch (_: Throwable) {}
        return list
    }

    fun appendOrderLog(context: Context, entry: OrderEntry) {
        val current = getOrderLogs(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.id == entry.id && entry.id != "#???" }
        if (existingIdx >= 0) {
  current[existingIdx] = entry
        } else {
  current.add(0, entry)
        }
        val trimmed = current.take(50)
        val arr = JSONArray()
        for (e in trimmed) {
  arr.put(JSONObject().apply {
      put("id", e.id)
      put("price", e.price)
      put("distance", e.distance)
      put("restaurant", e.restaurant)
      put("status", e.status)
      put("time", e.time)
      put("customerName", e.customerName)
      put("customerPhone", e.customerPhone)
      put("address", e.address)
      put("cashAmount", e.cashAmount)
      put("pickupAddress", e.pickupAddress)
      put("customerDistance", e.customerDistance)
      put("decisionReason", e.decisionReason)
  })
        }
        getPrefs(context).edit().putString("order_logs_json", arr.toString()).apply()
    }

    fun clearOrderLogs(context: Context) {
        getPrefs(context).edit().putString("order_logs_json", "[]").apply()
    }
}
