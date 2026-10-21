package io.github.libxposed.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import dev.saned.assistant.NotificationHelper
import dev.saned.assistant.OrderModel
import org.json.JSONArray
import org.json.JSONObject

class XposedProvider : ContentProvider() {

    companion object {
        private const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
        private const val TRANSACTION_REQUEST_REMOTE_PREFS = 21
        private const val TRANSACTION_UPDATE_REMOTE_PREFS = 22
        private const val PREF_GROUP = "sanedhook_settings"
        private const val DIAG_PREF_NAME = "sanedhook_diag_logs"
        private const val MAX_DIAG_LINES = 350

        @Volatile
        var serviceBinder: IBinder? = null
  private set

        @Synchronized
        fun appendDiagLog(ctx: Context, logText: String) {
  if (logText.isBlank()) return
  try {
      val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
      val existing = prefs.getString("diag_log_text", "") ?: ""
      val combined = if (existing.isEmpty()) logText.trimEnd() else "$existing\n${logText.trimEnd()}"
      val lines = combined.lines()
      val trimmed = if (lines.size > MAX_DIAG_LINES) {
          lines.takeLast(MAX_DIAG_LINES).joinToString("\n")
      } else {
          combined
      }
      prefs.edit().putString("diag_log_text", trimmed).apply()
      ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_LOGS"))
  } catch (_: Throwable) {}
        }

        @Synchronized
        fun getDiagLogs(ctx: Context): String {
  return try {
      val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
      prefs.getString("diag_log_text", "") ?: ""
  } catch (_: Throwable) { "" }
        }

        @Synchronized
        fun clearDiagLogs(ctx: Context) {
  try {
      val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
      prefs.edit().putString("diag_log_text", "").apply()
      ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_LOGS"))
  } catch (_: Throwable) {}
        }

        fun syncToLSPosedRemotePrefs(context: Context) {
  val binder = serviceBinder ?: return
  try {
      val prefs = context.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
      val all = prefs.all
      if (all.isEmpty()) return

      val reqData = Parcel.obtain()
      val reqReply = Parcel.obtain()
      try {
          reqData.writeInterfaceToken(DESCRIPTOR)
          reqData.writeString(PREF_GROUP)
          binder.transact(TRANSACTION_REQUEST_REMOTE_PREFS, reqData, reqReply, 0)
          reqReply.readException()
      } catch (_: Throwable) {
      } finally {
          reqReply.recycle()
          reqData.recycle()
      }

      val putMap = HashMap<String, Any?>()
      for ((k, v) in all) {
          if (v != null) putMap[k] = v
      }
      val diffBundle = Bundle().apply {
          putBoolean("clear", false)
          putSerializable("delete", HashSet<String>())
          putSerializable("put", putMap)
      }

      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      try {
          data.writeInterfaceToken(DESCRIPTOR)
          data.writeString(PREF_GROUP)
          data.writeInt(1)
          diffBundle.writeToParcel(data, 0)
          binder.transact(TRANSACTION_UPDATE_REMOTE_PREFS, data, reply, 0)
          reply.readException()
      } finally {
          reply.recycle()
          data.recycle()
      }
  } catch (_: Throwable) {}
        }

        private fun isSameOrderEntry(normId: String, normRef: String, oldId: String, oldRef: String): Boolean {
            if (normRef.isNotEmpty() && (oldRef == normRef || oldId == normRef)) return true
            if (normId.isNotEmpty() && normId != "0" && (oldId == normId || oldRef == normId)) return true
            return false
        }

        private fun pickBestReference(idStr: String, refA: String, refB: String): String {
            val cleanA = refA.replace("#", "").trim()
            val cleanB = refB.replace("#", "").trim()
            val cleanId = idStr.replace("#", "").trim()
            return when {
                cleanA.length >= 10 && cleanA != cleanId -> cleanA
                cleanB.length >= 10 && cleanB != cleanId -> cleanB
                cleanA.isNotEmpty() && cleanA != cleanId -> cleanA
                cleanB.isNotEmpty() && cleanB != cleanId -> cleanB
                cleanA.isNotEmpty() -> cleanA
                else -> cleanB
            }
        }

        fun saveOrderAndNotify(ctx: Context, order: OrderModel, notifyUser: Boolean = true) {
            try {
                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
                val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

                val normRef = order.reference.replace("#", "").trim()
                val normId = if (order.id != 0) order.id.toString() else ""

                val normalizedStatus = if (order.status.equals("Dispatched", ignoreCase = true) || order.status.equals("DISPATCHED", ignoreCase = true)) {
                    "Accepted ✅"
                } else {
                    order.status
                }
                var mergedOrder = order.copy(
                    status = normalizedStatus,
                    foodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount
                )

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    val oldId = obj.optString("id", "").replace("#", "").trim()
                    if (isSameOrderEntry(normId, normRef, oldId, oldRef)) {
                        val oldModel = OrderModel.fromJson(obj)
                        val bestAddr = when {
                            mergedOrder.address.isNotEmpty() && mergedOrder.address != "Location on map" -> mergedOrder.address
                            oldModel.address.isNotEmpty() && oldModel.address != "Location on map" -> oldModel.address
                            mergedOrder.address.isNotEmpty() -> mergedOrder.address
                            else -> oldModel.address
                        }
                        val bestId = if (mergedOrder.id != 0) mergedOrder.id else oldModel.id
                        val bestRef = pickBestReference(
                            if (bestId != 0) bestId.toString() else normId,
                            mergedOrder.reference,
                            oldModel.reference
                        )
                        val bestCash = if (mergedOrder.cashAmount > 0.0) mergedOrder.cashAmount else oldModel.cashAmount
                        val bestFood = when {
                            mergedOrder.foodPrice > 0.0 -> mergedOrder.foodPrice
                            oldModel.foodPrice > 0.0 -> oldModel.foodPrice
                            bestCash > 0.0 -> bestCash
                            else -> 0.0
                        }
                        val bestStatus = when {
                            mergedOrder.status.equals("Accepted", ignoreCase = true) && oldModel.status.contains("✅") -> oldModel.status
                            else -> mergedOrder.status.ifEmpty { oldModel.status }
                        }
                        mergedOrder = mergedOrder.copy(
                            id = bestId,
                            reference = bestRef,
                            status = bestStatus,
                            customerPhone = mergedOrder.customerPhone.ifEmpty { oldModel.customerPhone },
                            customerName = mergedOrder.customerName.ifEmpty { oldModel.customerName },
                            address = bestAddr,
                            pickupAddress = mergedOrder.pickupAddress.ifEmpty { oldModel.pickupAddress },
                            restaurant = mergedOrder.restaurant.ifEmpty { oldModel.restaurant },
                            price = if (mergedOrder.price > 0.0) mergedOrder.price else oldModel.price,
                            cashAmount = bestCash,
                            foodPrice = bestFood,
                            paymentMethod = mergedOrder.paymentMethod.ifEmpty { oldModel.paymentMethod },
                            distanceKm = if (mergedOrder.distanceKm > 0.0) mergedOrder.distanceKm else oldModel.distanceKm,
                            customerDistanceKm = if (mergedOrder.customerDistanceKm > 0.0) mergedOrder.customerDistanceKm else oldModel.customerDistanceKm,
                            pickupLat = if (mergedOrder.pickupLat != 0.0) mergedOrder.pickupLat else oldModel.pickupLat,
                            pickupLng = if (mergedOrder.pickupLng != 0.0) mergedOrder.pickupLng else oldModel.pickupLng,
                            destLat = if (mergedOrder.destLat != 0.0) mergedOrder.destLat else oldModel.destLat,
                            destLng = if (mergedOrder.destLng != 0.0) mergedOrder.destLng else oldModel.destLng
                        )
                    }
                }

                val finalRef = mergedOrder.reference.replace("#", "").trim()
                val finalId = if (mergedOrder.id != 0) mergedOrder.id.toString() else normId

                val newArray = JSONArray()
                newArray.put(mergedOrder.toJson())
                for (i in 0 until array.length()) {
                    if (newArray.length() >= 60) break
                    val obj = array.optJSONObject(i) ?: continue
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    val oldId = obj.optString("id", "").replace("#", "").trim()
                    if (isSameOrderEntry(finalId, finalRef, oldId, oldRef) || isSameOrderEntry(normId, normRef, oldId, oldRef)) {
                        continue
                    }
                    newArray.put(obj)
                }
                prefs.edit().putString("orders_log_json", newArray.toString()).apply()

                if (notifyUser) {
                    val isAccepted = !mergedOrder.isMissedOrRejected &&
                        (mergedOrder.status.contains("Accept", ignoreCase = true) ||
                         mergedOrder.status.contains("قبول") ||
                         mergedOrder.status.contains("Deliver", ignoreCase = true))
                    NotificationHelper.showOrderNotification(ctx, mergedOrder, isAccepted = isAccepted)
                }

                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
            } catch (_: Throwable) {}
        }

        fun saveOrderAndNotify(ctx: Context, orderJsonStr: String, showNotif: Boolean = true) {
  try {
      val order = OrderModel.fromJson(JSONObject(orderJsonStr))
      saveOrderAndNotify(ctx, order, notifyUser = showNotif)
  } catch (_: Throwable) {}
        }

        fun saveOrdersBatch(ctx: Context, ordersJsonArrayStr: String) {
  try {
      val incoming = JSONArray(ordersJsonArrayStr)
      if (incoming.length() == 0) return
      for (i in incoming.length() - 1 downTo 0) {
          val obj = incoming.optJSONObject(i) ?: continue
          val order = OrderModel.fromJson(obj)
          saveOrderAndNotify(ctx, order, notifyUser = false)
      }
  } catch (_: Throwable) {}
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val bundle = Bundle()
        if (method == "SendBinder") {
  val binder = extras?.getBinder("binder")
  if (binder != null) {
      serviceBinder = binder
      try {
          binder.linkToDeath({
              serviceBinder = null
          }, 0)
      } catch (_: Throwable) {}
      context?.let { syncToLSPosedRemotePrefs(it) }
  }
  return bundle
        }

        val ctx = context ?: return bundle

        if (method == "appendDiagLog") {
  val line = extras?.getString("log_line") ?: arg ?: ""
  if (line.isNotEmpty()) {
      appendDiagLog(ctx, line)
  }
  bundle.putBoolean("ok", true)
  return bundle
        }

        if (method == "clearDiagLogs") {
  clearDiagLogs(ctx)
  bundle.putBoolean("ok", true)
  return bundle
        }

        if (method == "getDiagLogs") {
  bundle.putString("diag_log_text", getDiagLogs(ctx))
  bundle.putBoolean("ok", true)
  return bundle
        }

        if (method == "logOrder" && extras != null) {
  try {
      val orderJsonStr = extras.getString("order_json")
      val notifyUser = extras.getBoolean("notify_user", true)
      if (!orderJsonStr.isNullOrEmpty()) {
          val order = OrderModel.fromJson(JSONObject(orderJsonStr))
          saveOrderAndNotify(ctx, order, notifyUser = notifyUser)
          bundle.putBoolean("ok", true)
          return bundle
      }
  } catch (_: Throwable) {}
        }

        if (method == "syncOrdersBatch" && extras != null) {
  try {
      val batchJson = extras.getString("orders_json")
      if (!batchJson.isNullOrEmpty()) {
          saveOrdersBatch(ctx, batchJson)
          bundle.putBoolean("ok", true)
          return bundle
      }
  } catch (_: Throwable) {}
        }

        try {
  val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
  val all = prefs.all
  for ((key, value) in all) {
      when (value) {
          is Boolean -> bundle.putBoolean(key, value)
          is Int -> bundle.putInt(key, value)
          is Long -> bundle.putLong(key, value)
          is Float -> bundle.putFloat(key, value)
          is String -> bundle.putString(key, value)
      }
  }
        } catch (_: Throwable) {}
        return bundle
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
