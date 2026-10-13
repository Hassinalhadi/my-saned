package io.github.libxposed.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

class XposedProvider : ContentProvider() {

    companion object {
        private const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
        private const val TRANSACTION_REQUEST_REMOTE_PREFS = 21
        private const val TRANSACTION_UPDATE_REMOTE_PREFS = 22
        private const val PREF_GROUP = "sanedhook_settings"

        @Volatile
        var serviceBinder: IBinder? = null
            private set

        /**
         * Exact match to SanedAssistant-98-7bac1ec.apk (Lxr1.a & Lek0.a):
         * Pushes all local SharedPreferences("sanedhook_settings") into LSPosed daemon
         * via IXposedService Binder transactions 21 (requestRemotePreferences) and 22 (updateRemotePreferences).
         */
        fun syncToLSPosedRemotePrefs(context: Context) {
            val binder = serviceBinder ?: return
            try {
                val prefs = context.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val all = prefs.all
                if (all.isEmpty()) return

                // 1. Ensure remote preferences group is initialized in LSPosed daemon (Transaction 21 - Lxr1.a)
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

                // 2. Push key-value map to LSPosed daemon (Transaction 22 - Lek0.a & Lwr1.a)
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
                // Immediately sync all local settings to LSPosed RemotePreferences on bind (matching SanedApp.a)
                context?.let { syncToLSPosedRemotePrefs(it) }
            }
            return bundle
        }

        val ctx = context ?: return bundle
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
