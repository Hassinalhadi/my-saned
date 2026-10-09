package io.github.libxposed.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XposedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val bundle = Bundle()
        try {
            val prefs = ctx.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)

            if (method == "logOrder" && extras != null) {
                val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
                val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val newObj = JSONObject().apply {
                    put("id", extras.getString("order_id", "#--"))
                    put("price", extras.getDouble("price", 0.0))
                    put("distance", extras.getDouble("distance", 0.0))
                    put("restaurant", extras.getString("restaurant", ""))
                    put("status", extras.getString("status", "وارد عبر الشبكة 🌐"))
                    put("time", timeStr)
                }
                val newArray = JSONArray()
                newArray.put(newObj)
                for (i in 0 until minOf(29, array.length())) {
                    newArray.put(array.getJSONObject(i))
                }
                prefs.edit().putString("orders_log_json", newArray.toString()).apply()
                return bundle
            }

            if (method == "clearOrdersLog") {
                prefs.edit().putString("orders_log_json", "[]").apply()
                return bundle
            }

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
