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

        @Volatile
        var serviceBinder: IBinder? = null
            private set

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

        fun saveOrderAndNotify(ctx: Context, order: OrderModel) {
            try {
                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
                val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

                val newArray = JSONArray()
                newArray.put(order.toJson())
                val normRef = order.reference.replace("#", "").trim()
                for (i in 0 until array.length()) {
                    if (newArray.length() >= 50) break
                    val obj = array.optJSONObject(i) ?: continue
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    if (normRef.isNotEmpty() && oldRef == normRef) {
                        continue // Replace older entry of the same order with updated status
                    }
                    newArray.put(obj)
                }
                prefs.edit().putString("orders_log_json", newArray.toString()).apply()

                val isAccepted = !order.isMissedOrRejected &&
                    (order.status.contains("Accept", ignoreCase = true) ||
                     order.status.contains("قبول") ||
                     order.status.contains("Deliver", ignoreCase = true))
                NotificationHelper.showOrderNotification(ctx, order, isAccepted = isAccepted)

                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
            } catch (_: Throwable) {}
        }

        fun saveOrderAndNotify(ctx: Context, orderJsonStr: String, showNotif: Boolean = true) {
            try {
                val order = OrderModel.fromJson(JSONObject(orderJsonStr))
                saveOrderAndNotify(ctx, order)
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

        if (method == "logOrder" && extras != null) {
            try {
                val orderJsonStr = extras.getString("order_json")
                if (!orderJsonStr.isNullOrEmpty()) {
                    val order = OrderModel.fromJson(JSONObject(orderJsonStr))
                    saveOrderAndNotify(ctx, order)
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
