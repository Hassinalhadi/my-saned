package io.github.libxposed.service

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import dev.saned.assistant.NotificationHelper
import dev.saned.assistant.OrderModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class XposedProvider : ContentProvider() {

    companion object {
        private const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
        private const val PULL_DESCRIPTOR_LSPATCH = "org.lsposed.lspatch.IXposedServicePull"
        private const val PULL_DESCRIPTOR_NPATCH = "top.nkbe.npatch.IXposedServicePull"
        private const val NPATCH_REMOTE_URI = "content://top.nkbe.npatch.remote"
        private const val TRANSACTION_REQUEST_REMOTE_PREFS = 21
        private const val TRANSACTION_UPDATE_REMOTE_PREFS = 22
        private const val PREF_GROUP = "sanedhook_settings"
        private const val DIAG_PREF_NAME = "sanedhook_diag_logs"
        private const val MAX_DIAG_LINES = 300

        private val pullExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Saned-NPatch-Pull").apply { isDaemon = true }
        }
        private val isPullInProgress = AtomicBoolean(false)

        @Volatile
        var serviceBinder: IBinder? = null
            private set

        @Volatile
        private var cachedDiagLogText: String? = null
        @Volatile
        private var lastDiagBroadcastRealtime: Long = 0L
        @Volatile
        private var lastSavedOrdersJson: String? = null
        private val deletedOrderKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var deletedKeysLoaded: Boolean = false

        private fun ensureDeletedKeysLoaded(ctx: Context) {
            if (deletedKeysLoaded) return
            synchronized(deletedOrderKeys) {
                if (deletedKeysLoaded) return
                try {
                    val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                    val saved = prefs.getStringSet("deleted_order_keys", emptySet()) ?: emptySet()
                    deletedOrderKeys.addAll(saved)
                } catch (_: Throwable) {}
                deletedKeysLoaded = true
            }
        }

        private fun isOrderDeleted(ctx: Context, normId: String, normRef: String): Boolean {
            ensureDeletedKeysLoaded(ctx)
            val cId = normId.replace("#", "").trim()
            val cRef = normRef.replace("#", "").trim()
            if (cId.isNotEmpty() && cId != "0" && deletedOrderKeys.contains(cId)) return true
            if (cRef.isNotEmpty() && deletedOrderKeys.contains(cRef)) return true
            return false
        }

        private fun isCompletedOrDeadStatus(status: String): Boolean {
            val low = status.lowercase(java.util.Locale.US)
            return low.contains("deliver") || low.contains("توصيل") ||
                low.contains("complet") || low.contains("مكتمل") ||
                low.contains("cancel") || low.contains("ملغ") ||
                low.contains("expired") || low.contains("انته")
        }

        private fun attachServiceBinder(binder: IBinder, ctx: Context?) {
            if (serviceBinder === binder) return
            serviceBinder = binder
            try {
                binder.linkToDeath({
                    if (serviceBinder === binder) {
                        serviceBinder = null
                    }
                }, 0)
            } catch (_: Throwable) {}
            if (ctx != null) {
                pushAllPrefsToBinder(ctx, binder)
            }
        }

        /**
         * Matches Lz21.b / Lz21.d / Lx21.run in SanedAssistant-98-7bac1ec.apk + NPatch v1.0.9 Remote Store:
         * Connects to Non-Root NPatch (top.nkbe.npatch) or Vector / LSPatch Manager to pull/request IXposedService binder.
         */
        fun ensureServiceBinderConnected(context: Context) {
            val current = serviceBinder
            if (current != null && current.isBinderAlive) return
            val appCtx = context.applicationContext ?: context
            if (!isPullInProgress.compareAndSet(false, true)) return
            pullExecutor.execute {
                try {
                    // 1. Try NPatch v1.0.7–v1.0.9 Remote Store ContentProvider (content://top.nkbe.npatch.remote)
                    tryConnectNPatchRemoteProvider(appCtx)
                    if (serviceBinder?.isBinderAlive == true) return@execute

                    // 2. Try REQUEST_PUSH service binding (Lz21.d + Lx21.run) for NPatch / Vector / LSPatch
                    for (attempt in 0 until 4) {
                        requestPushFromNonRootManager(appCtx)
                        if (serviceBinder?.isBinderAlive == true) break
                        try { Thread.sleep(350L) } catch (_: Throwable) { break }
                        tryConnectNPatchRemoteProvider(appCtx)
                        if (serviceBinder?.isBinderAlive == true) break
                    }
                } catch (_: Throwable) {
                } finally {
                    isPullInProgress.set(false)
                }
            }
        }

        private fun tryConnectNPatchRemoteProvider(ctx: Context) {
            val uri = Uri.parse(NPATCH_REMOTE_URI)
            val pkg = ctx.packageName
            val extras = Bundle().apply {
                putString("packageName", pkg)
                putString("modulePackage", pkg)
                putString("package", pkg)
            }
            for (methodName in listOf("connect", "getService", "acquireService", "getBinder", "requestBinder")) {
                try {
                    val reply = ctx.contentResolver.call(uri, methodName, pkg, extras) ?: continue
                    for (key in listOf("binder", "service", "client", "xposed_service")) {
                        val b = reply.getBinder(key)
                        if (b != null && b.isBinderAlive) {
                            attachServiceBinder(b, ctx)
                            return
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        private fun requestPushFromNonRootManager(ctx: Context) {
            val pm = ctx.packageManager ?: return
            val candidateActions = listOf(
                "org.lsposed.lspatch.action.REQUEST_PUSH",
                "top.nkbe.npatch.action.REQUEST_PUSH"
            )
            val discoveredComponents = LinkedHashSet<ComponentName>()
            for (action in candidateActions) {
                try {
                    val list = pm.queryIntentServices(Intent(action), 0)
                    if (list != null) {
                        for (ri in list) {
                            val si = ri.serviceInfo ?: continue
                            if (!si.packageName.isNullOrEmpty() && !si.name.isNullOrEmpty()) {
                                discoveredComponents.add(ComponentName(si.packageName, si.name))
                            }
                        }
                    }
                } catch (_: Throwable) {}
                for (mgrPkg in listOf("top.nkbe.npatch", "org.lsposed.lspatch")) {
                    try {
                        val list = pm.queryIntentServices(Intent(action).setPackage(mgrPkg), 0)
                        if (list != null) {
                            for (ri in list) {
                                val si = ri.serviceInfo ?: continue
                                if (!si.packageName.isNullOrEmpty() && !si.name.isNullOrEmpty()) {
                                    discoveredComponents.add(ComponentName(si.packageName, si.name))
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }

            for (comp in discoveredComponents) {
                if (serviceBinder?.isBinderAlive == true) break
                try {
                    val bindIntent = Intent("org.lsposed.lspatch.action.REQUEST_PUSH").apply {
                        component = comp
                        putExtra("packageName", ctx.packageName)
                    }
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val connectionRef = this
                            pullExecutor.execute {
                                try {
                                    if (binder != null) {
                                        for (desc in listOf(PULL_DESCRIPTOR_LSPATCH, PULL_DESCRIPTOR_NPATCH)) {
                                            val data = Parcel.obtain()
                                            val reply = Parcel.obtain()
                                            try {
                                                data.writeInterfaceToken(desc)
                                                val ok = binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                                                if (ok) {
                                                    reply.readException()
                                                    break
                                                }
                                            } catch (_: Throwable) {
                                            } finally {
                                                reply.recycle()
                                                data.recycle()
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    try { ctx.unbindService(connectionRef) } catch (_: Throwable) {}
                                }
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {}
                        override fun onNullBinding(name: ComponentName?) {
                            try { ctx.unbindService(this) } catch (_: Throwable) {}
                        }
                    }
                    ctx.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)
                } catch (_: Throwable) {}
            }
        }

        @Synchronized
        fun appendDiagLog(ctx: Context, logText: String) {
            if (logText.isBlank()) return
            try {
                val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
                val existing = cachedDiagLogText ?: (prefs.getString("diag_log_text", "") ?: "")
                val combined = if (existing.isEmpty()) logText.trimEnd() else "$existing\n${logText.trimEnd()}"
                val lines = combined.lines()
                val trimmed = if (lines.size > MAX_DIAG_LINES) {
                    lines.takeLast(MAX_DIAG_LINES).joinToString("\n")
                } else {
                    combined
                }
                cachedDiagLogText = trimmed
                prefs.edit().putString("diag_log_text", trimmed).apply()
                val now = SystemClock.elapsedRealtime()
                if (now - lastDiagBroadcastRealtime >= 600L) {
                    lastDiagBroadcastRealtime = now
                    ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_LOGS"))
                }
            } catch (_: Throwable) {}
        }

        @Synchronized
        fun getDiagLogs(ctx: Context): String {
            cachedDiagLogText?.let { return it }
            return try {
                val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
                (prefs.getString("diag_log_text", "") ?: "").also { cachedDiagLogText = it }
            } catch (_: Throwable) { "" }
        }

        @Synchronized
        fun clearCachedOrders(ctx: Context) {
            try {
                ensureDeletedKeysLoaded(ctx)
                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existing = lastSavedOrdersJson ?: (prefs.getString("orders_log_json", "[]") ?: "[]")
                val arr = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val oldId = obj.optString("id", "").replace("#", "").trim()
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    if (oldId.isNotEmpty() && oldId != "0") deletedOrderKeys.add(oldId)
                    if (oldRef.isNotEmpty()) deletedOrderKeys.add(oldRef)
                }
                lastSavedOrdersJson = "[]"
                prefs.edit()
                    .putString("orders_log_json", "[]")
                    .putStringSet("deleted_order_keys", HashSet(deletedOrderKeys))
                    .commit()
                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
            } catch (_: Throwable) {}
        }

        @Synchronized
        fun deleteSingleOrder(ctx: Context, orderId: String, orderRef: String) {
            try {
                ensureDeletedKeysLoaded(ctx)
                val cleanId = orderId.replace("#", "").trim()
                val cleanRef = orderRef.replace("#", "").trim()
                if (cleanId.isNotEmpty() && cleanId != "0") deletedOrderKeys.add(cleanId)
                if (cleanRef.isNotEmpty()) deletedOrderKeys.add(cleanRef)

                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existing = lastSavedOrdersJson ?: (prefs.getString("orders_log_json", "[]") ?: "[]")
                val arr = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
                val newArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val oldId = obj.optString("id", "").replace("#", "").trim()
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    if (isSameOrderEntry(cleanId, cleanRef, oldId, oldRef)) {
                        if (oldId.isNotEmpty() && oldId != "0") deletedOrderKeys.add(oldId)
                        if (oldRef.isNotEmpty()) deletedOrderKeys.add(oldRef)
                        continue
                    }
                    newArr.put(obj)
                }
                val newJsonStr = newArr.toString()
                lastSavedOrdersJson = newJsonStr
                prefs.edit()
                    .putString("orders_log_json", newJsonStr)
                    .putStringSet("deleted_order_keys", HashSet(deletedOrderKeys))
                    .commit()
                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
            } catch (_: Throwable) {}
        }

        @Synchronized
        fun clearDiagLogs(ctx: Context) {
            try {
                cachedDiagLogText = ""
                val prefs = ctx.getSharedPreferences(DIAG_PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString("diag_log_text", "").apply()
                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_LOGS"))
            } catch (_: Throwable) {}
        }

        fun syncToLSPosedRemotePrefs(context: Context) {
            val binder = serviceBinder
            if (binder == null || !binder.isBinderAlive) {
                ensureServiceBinderConnected(context)
                return
            }
            pushAllPrefsToBinder(context, binder)
        }

        private fun pushAllPrefsToBinder(context: Context, binder: IBinder) {
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

        private fun mergeWithExistingModel(incoming: OrderModel, oldModel: OrderModel): OrderModel {
            val normId = if (incoming.id != 0) incoming.id.toString() else ""
            val bestAddr = when {
                incoming.address.isNotEmpty() && incoming.address != "Location on map" -> incoming.address
                oldModel.address.isNotEmpty() && oldModel.address != "Location on map" -> oldModel.address
                incoming.address.isNotEmpty() -> incoming.address
                else -> oldModel.address
            }
            val bestId = if (incoming.id != 0) incoming.id else oldModel.id
            val bestRef = pickBestReference(
                if (bestId != 0) bestId.toString() else normId,
                incoming.reference,
                oldModel.reference
            )
            val bestCash = if (incoming.cashAmount > 0.0) incoming.cashAmount else oldModel.cashAmount
            val bestFood = when {
                incoming.foodPrice > 0.0 -> incoming.foodPrice
                oldModel.foodPrice > 0.0 -> oldModel.foodPrice
                bestCash > 0.0 -> bestCash
                else -> 0.0
            }
            val bestStatus = when {
                incoming.status.equals("Accepted", ignoreCase = true) && oldModel.status.contains("✅") -> oldModel.status
                else -> incoming.status.ifEmpty { oldModel.status }
            }
            return incoming.copy(
                id = bestId,
                reference = bestRef,
                status = bestStatus,
                customerPhone = incoming.customerPhone.ifEmpty { oldModel.customerPhone },
                customerName = incoming.customerName.ifEmpty { oldModel.customerName },
                address = bestAddr,
                pickupAddress = incoming.pickupAddress.ifEmpty { oldModel.pickupAddress },
                restaurant = incoming.restaurant.ifEmpty { oldModel.restaurant },
                price = if (incoming.price > 0.0) incoming.price else oldModel.price,
                cashAmount = bestCash,
                foodPrice = bestFood,
                paymentMethod = incoming.paymentMethod.ifEmpty { oldModel.paymentMethod },
                distanceKm = if (incoming.distanceKm > 0.0) incoming.distanceKm else oldModel.distanceKm,
                customerDistanceKm = if (incoming.customerDistanceKm > 0.0) incoming.customerDistanceKm else oldModel.customerDistanceKm,
                pickupLat = if (incoming.pickupLat != 0.0) incoming.pickupLat else oldModel.pickupLat,
                pickupLng = if (incoming.pickupLng != 0.0) incoming.pickupLng else oldModel.pickupLng,
                destLat = if (incoming.destLat != 0.0) incoming.destLat else oldModel.destLat,
                destLng = if (incoming.destLng != 0.0) incoming.destLng else oldModel.destLng
            )
        }

        @Synchronized
        fun saveOrderAndNotify(ctx: Context, order: OrderModel, notifyUser: Boolean = true) {
            try {
                val normRef = order.reference.replace("#", "").trim()
                val normId = if (order.id != 0) order.id.toString() else ""
                if (isOrderDeleted(ctx, normId, normRef)) return

                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existing = lastSavedOrdersJson ?: (prefs.getString("orders_log_json", "[]") ?: "[]")
                val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

                val normalizedStatus = if (order.status.equals("Dispatched", ignoreCase = true) || order.status.equals("DISPATCHED", ignoreCase = true)) {
                    "Accepted ✅"
                } else {
                    order.status
                }
                var mergedOrder = order.copy(
                    status = normalizedStatus,
                    foodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount
                )

                var matchIdx = -1
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val oldRef = obj.optString("reference", "").replace("#", "").trim()
                    val oldId = obj.optString("id", "").replace("#", "").trim()
                    if (isSameOrderEntry(normId, normRef, oldId, oldRef)) {
                        if (matchIdx == -1) matchIdx = i
                        mergedOrder = mergeWithExistingModel(mergedOrder, OrderModel.fromJson(obj))
                    }
                }

                val finalRef = mergedOrder.reference.replace("#", "").trim()
                val finalId = if (mergedOrder.id != 0) mergedOrder.id.toString() else normId
                if (isOrderDeleted(ctx, finalId, finalRef)) return

                if (isCompletedOrDeadStatus(mergedOrder.status)) {
                    if (matchIdx >= 0) {
                        val cleanedArray = JSONArray()
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i) ?: continue
                            val oldRef = obj.optString("reference", "").replace("#", "").trim()
                            val oldId = obj.optString("id", "").replace("#", "").trim()
                            if (isSameOrderEntry(finalId, finalRef, oldId, oldRef) || isSameOrderEntry(normId, normRef, oldId, oldRef)) {
                                continue
                            }
                            cleanedArray.put(obj)
                        }
                        val cleanedStr = cleanedArray.toString()
                        lastSavedOrdersJson = cleanedStr
                        prefs.edit().putString("orders_log_json", cleanedStr).apply()
                        ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
                    }
                    return
                }

                val newArray = JSONArray()
                if (matchIdx >= 0) {
                    // Update IN-PLACE at matchIdx so orders never swap positions!
                    var updatedPlaced = false
                    for (i in 0 until array.length()) {
                        if (newArray.length() >= 60) break
                        val obj = array.optJSONObject(i) ?: continue
                        val oldRef = obj.optString("reference", "").replace("#", "").trim()
                        val oldId = obj.optString("id", "").replace("#", "").trim()
                        if (isSameOrderEntry(finalId, finalRef, oldId, oldRef) || isSameOrderEntry(normId, normRef, oldId, oldRef)) {
                            if (!updatedPlaced) {
                                newArray.put(mergedOrder.toJson())
                                updatedPlaced = true
                            }
                            continue
                        }
                        newArray.put(obj)
                    }
                } else {
                    newArray.put(mergedOrder.toJson())
                    for (i in 0 until array.length()) {
                        if (newArray.length() >= 60) break
                        val obj = array.optJSONObject(i) ?: continue
                        newArray.put(obj)
                    }
                }

                val newJsonStr = newArray.toString()
                if (newJsonStr != existing) {
                    lastSavedOrdersJson = newJsonStr
                    prefs.edit().putString("orders_log_json", newJsonStr).apply()
                    ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
                }

                if (notifyUser) {
                    val isAccepted = !mergedOrder.isMissedOrRejected &&
                        (mergedOrder.status.contains("Accept", ignoreCase = true) ||
                         mergedOrder.status.contains("قبول") ||
                         mergedOrder.status.contains("Deliver", ignoreCase = true))
                    NotificationHelper.showOrderNotification(ctx, mergedOrder, isAccepted = isAccepted)
                }
            } catch (_: Throwable) {}
        }

        fun saveOrderAndNotify(ctx: Context, orderJsonStr: String, showNotif: Boolean = true) {
            try {
                val order = OrderModel.fromJson(JSONObject(orderJsonStr))
                saveOrderAndNotify(ctx, order, notifyUser = showNotif)
            } catch (_: Throwable) {}
        }

        /**
         * Single-pass O(N) batch merge with strict IN-PLACE updates so order positions never jump!
         */
        @Synchronized
        fun saveOrdersBatch(ctx: Context, ordersJsonArrayStr: String) {
            try {
                if (ordersJsonArrayStr.isBlank() || ordersJsonArrayStr == "[]") return
                val incoming = JSONArray(ordersJsonArrayStr)
                if (incoming.length() == 0) return

                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val existingStr = lastSavedOrdersJson ?: (prefs.getString("orders_log_json", "[]") ?: "[]")
                val existingArr = try { JSONArray(existingStr) } catch (_: Throwable) { JSONArray() }

                val workingList = ArrayList<OrderModel>(existingArr.length() + incoming.length())
                for (i in 0 until existingArr.length()) {
                    val obj = existingArr.optJSONObject(i) ?: continue
                    val m = OrderModel.fromJson(obj)
                    val mRef = m.reference.replace("#", "").trim()
                    val mId = if (m.id != 0) m.id.toString() else ""
                    if (!isOrderDeleted(ctx, mId, mRef) && !isCompletedOrDeadStatus(m.status)) {
                        workingList.add(m)
                    }
                }

                for (i in incoming.length() - 1 downTo 0) {
                    val obj = incoming.optJSONObject(i) ?: continue
                    val rawOrder = OrderModel.fromJson(obj)
                    val normalizedStatus = if (rawOrder.status.equals("Dispatched", ignoreCase = true)) "Accepted ✅" else rawOrder.status
                    val incomingModel = rawOrder.copy(
                        status = normalizedStatus,
                        foodPrice = if (rawOrder.foodPrice > 0.0) rawOrder.foodPrice else rawOrder.cashAmount
                    )
                    val normRef = incomingModel.reference.replace("#", "").trim()
                    val normId = if (incomingModel.id != 0) incomingModel.id.toString() else ""
                    if (isOrderDeleted(ctx, normId, normRef)) continue

                    val matchIdx = workingList.indexOfFirst { old ->
                        val oldRef = old.reference.replace("#", "").trim()
                        val oldId = if (old.id != 0) old.id.toString() else ""
                        isSameOrderEntry(normId, normRef, oldId, oldRef)
                    }
                    if (isCompletedOrDeadStatus(incomingModel.status)) {
                        if (matchIdx >= 0) workingList.removeAt(matchIdx)
                        continue
                    }
                    if (matchIdx >= 0) {
                        val merged = mergeWithExistingModel(incomingModel, workingList[matchIdx])
                        workingList[matchIdx] = merged
                    } else {
                        workingList.add(0, incomingModel)
                    }
                }

                val outArr = JSONArray()
                for (m in workingList) {
                    if (outArr.length() >= 60) break
                    outArr.put(m.toJson())
                }
                val newJsonStr = outArr.toString()
                if (newJsonStr != existingStr) {
                    lastSavedOrdersJson = newJsonStr
                    prefs.edit().putString("orders_log_json", newJsonStr).apply()
                    ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(): Boolean {
        context?.let { ensureServiceBinderConnected(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val bundle = Bundle()
        if (method == "SendBinder") {
            val binder = extras?.getBinder("binder")
            if (binder != null) {
                attachServiceBinder(binder, context)
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

        if ((method == "updateSettingsFromFloating" || method == "push_settings") && extras != null) {
            try {
                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val ed = prefs.edit()
                val stringKeys = setOf(
                    "min_price", "min_order_price",
                    "max_dist_rest", "max_dist_to_restaurant",
                    "max_dist_cust", "max_dist_restaurant_to_customer",
                    "fake_lat", "fake_lng",
                    "max_orders_limit_str", "spoofed_android_id", "app_ui_language",
                    "jdrv_lic_key", "jdrv_hw_device_id", "jdrv_trial_ledger_entry", "jdrv_lic_token_sig", "jdrv_remote_blocked_devs_csv", "jdrv_remote_blocked_keys_csv", "jdrv_remote_msg_ar", "jdrv_remote_msg_en"
                )
                for (key in extras.keySet()) {
                    val v = extras.get(key) ?: continue
                    if (key in stringKeys) {
                        ed.putString(key, v.toString())
                    } else {
                        when (v) {
                            is Boolean -> ed.putBoolean(key, v)
                            is Int -> ed.putInt(key, v)
                            is Long -> ed.putLong(key, v)
                            is Float -> ed.putFloat(key, v)
                            is Double -> ed.putString(key, v.toString())
                            is String -> ed.putString(key, v)
                        }
                    }
                }
                if (!extras.containsKey("last_settings_mod_ms")) {
                    ed.putLong("last_settings_mod_ms", System.currentTimeMillis())
                }
                ed.commit()
                syncToLSPosedRemotePrefs(ctx)
                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_REFRESH_ORDERS"))
                ctx.sendBroadcast(Intent("dev.saned.assistant.UI_SETTINGS_UPDATED"))
                bundle.putBoolean("ok", true)
            } catch (_: Throwable) {}
            return bundle
        }

        if (method == "saveAuthToken") {
            try {
                val tok = extras?.getString("auth_token") ?: arg ?: ""
                val host = extras?.getString("api_host") ?: ""
                val prefs = ctx.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                val ed = prefs.edit()
                if (tok.isNotEmpty()) ed.putString("cached_jahez_auth_token", tok)
                if (host.isNotEmpty()) ed.putString("cached_jahez_api_host", host)
                ed.apply()
                bundle.putBoolean("ok", true)
            } catch (_: Throwable) {}
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

        if (method == "clearOrders") {
            clearCachedOrders(ctx)
            bundle.putBoolean("ok", true)
            return bundle
        }

        if (method == "deleteOrder" && extras != null) {
            val oid = extras.getString("order_id") ?: ""
            val oref = extras.getString("reference") ?: ""
            deleteSingleOrder(ctx, oid, oref)
            bundle.putBoolean("ok", true)
            return bundle
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
