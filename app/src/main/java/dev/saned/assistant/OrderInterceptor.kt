package dev.saned.assistant

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object OrderInterceptor {

    @Volatile var isMasterRunning: Boolean = false
    @Volatile var isAutoAccept: Boolean = false
    @Volatile var isAutoReject: Boolean = false
    @Volatile var isDryRun: Boolean = false
    @Volatile var minOrderPrice: Double = 0.0
    @Volatile var maxDistToRestaurant: Double = 0.0
    @Volatile var maxDistCustomer: Double = 0.0
    @Volatile var isSoundEnabled: Boolean = false
    @Volatile var isShowToasts: Boolean = false

    private val isAccepting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remotePrefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings(resolver: ContentResolver? = null) {
        if (resolver != null) {
            try {
                val uri = Uri.parse("content://dev.jing.sanedhook.XposedService")
                val bundle = resolver.call(uri, "getSettings", null, null)
                if (bundle != null && !bundle.isEmpty) {
                    isMasterRunning = bundle.getBoolean("master_running", isMasterRunning)
                    isAutoAccept = bundle.getBoolean("auto_accept", isAutoAccept)
                    isAutoReject = bundle.getBoolean("auto_reject", isAutoReject)
                    isDryRun = bundle.getBoolean("dry_run", isDryRun)
                    minOrderPrice = bundle.getString("min_price", "0.0")?.toDoubleOrNull() ?: minOrderPrice
                    maxDistToRestaurant = bundle.getString("max_dist_rest", "0.0")?.toDoubleOrNull() ?: maxDistToRestaurant
                    maxDistCustomer = bundle.getString("max_dist_cust", "0.0")?.toDoubleOrNull() ?: maxDistCustomer
                    isSoundEnabled = bundle.getBoolean("sound_enabled", isSoundEnabled)
                    isShowToasts = bundle.getBoolean("show_toasts", isShowToasts)
                    return
                }
            } catch (_: Throwable) {}
        }
        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", isMasterRunning)
            isAutoAccept = p.getBoolean("auto_accept", isAutoAccept)
            isAutoReject = p.getBoolean("auto_reject", isAutoReject)
            isDryRun = p.getBoolean("dry_run", isDryRun)
            minOrderPrice = p.getString("min_price", "0.0")?.toDoubleOrNull() ?: 0.0
            maxDistToRestaurant = p.getString("max_dist_rest", "0.0")?.toDoubleOrNull() ?: 0.0
            maxDistCustomer = p.getString("max_dist_cust", "0.0")?.toDoubleOrNull() ?: 0.0
            isSoundEnabled = p.getBoolean("sound_enabled", isSoundEnabled)
            isShowToasts = p.getBoolean("show_toasts", isShowToasts)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook OkHttp RealCall to intercept HTTP network responses
        hookOkHttp(module, classLoader)

        // 2. Hook WebSocket to intercept live push orders
        hookWebSocket(module, classLoader)

        // 3. Hook UI Activities and Views (Fallback & Visual Acceptance)
        hookUI(module, classLoader)
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader) {
        try {
            val realCallClass = Class.forName("okhttp3.RealCall", false, classLoader)
            for (m in realCallClass.declaredMethods) {
                if (m.name == "getResponseWithInterceptorChain" || m.name == "getResponseWithInterceptorChain$okhttp") {
                    m.isAccessible = true
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val response = chain.proceed() ?: return null
                            try {
                                processHttpResponse(response)
                            } catch (_: Throwable) {}
                            return response
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookWebSocket(module: XposedModule, classLoader: ClassLoader) {
        try {
            val realWsClass = Class.forName("okhttp3.internal.ws.RealWebSocket", false, classLoader)
            for (m in realWsClass.declaredMethods) {
                if (m.name == "onReadMessage" && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    m.isAccessible = true
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val text = chain.args[0] as? String
                            if (!text.isNullOrEmpty()) {
                                try {
                                    parseOrderJson(text, "وارد عبر WebSocket ⚡")
                                } catch (_: Throwable) {}
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookUI(module: XposedModule, classLoader: ClassLoader) {
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == "net.jahez.fleets") {
                        appContext = activity.applicationContext
                        syncSettings(activity.contentResolver)
                        scanAndProcessOrder(activity)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}

        try {
            val mAttached = View::class.java.getDeclaredMethod("onAttachedToWindow")
            module.hook(mAttached).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    val context = view?.context
                    if (context is Activity && context.packageName == "net.jahez.fleets") {
                        appContext = context.applicationContext
                        syncSettings(context.contentResolver)
                        scanAndProcessOrder(context)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}
    }

    private fun processHttpResponse(response: Any) {
        try {
            val requestMethod = response.javaClass.getMethod("request")
            val request = requestMethod.invoke(response)
            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request).toString().lowercase()

            // Check if URL relates to orders or dispatch
            if (url.contains("order") || url.contains("dispatch") || url.contains("fleet") || url.contains("trip")) {
                val peekBodyMethod = response.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType)
                val bodyCopy = peekBodyMethod.invoke(response, 512L * 1024L)
                val stringMethod = bodyCopy.javaClass.getMethod("string")
                val json = stringMethod.invoke(bodyCopy) as? String
                if (!json.isNullOrEmpty() && (json.contains("order") || json.contains("price") || json.contains("cost") || json.contains("id"))) {
                    parseOrderJson(json, "وارد عبر HTTP 🌐")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseOrderJson(rawJson: String, channelSource: String) {
        try {
            val trimmed = rawJson.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return

            var orderId = ""
            var price = 0.0
            var dist = 0.0
            var restaurant = ""

            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                orderId = json.optString("order_number", json.optString("order_id", json.optString("id", "")))
                price = json.optDouble("price", json.optDouble("delivery_cost", json.optDouble("total", 0.0)))
                dist = json.optDouble("distance", json.optDouble("km", 0.0))
                restaurant = json.optString("store_name", json.optString("restaurant_name", ""))

                // Also check inner "data" or "order" objects
                val dataObj = json.optJSONObject("data") ?: json.optJSONObject("order")
                if (dataObj != null) {
                    if (orderId.isEmpty()) orderId = dataObj.optString("order_number", dataObj.optString("order_id", dataObj.optString("id", "")))
                    if (price == 0.0) price = dataObj.optDouble("price", dataObj.optDouble("delivery_cost", dataObj.optDouble("total", 0.0)))
                    if (dist == 0.0) dist = dataObj.optDouble("distance", dataObj.optDouble("km", 0.0))
                    if (restaurant.isEmpty()) restaurant = dataObj.optString("store_name", dataObj.optString("restaurant_name", ""))
                }
            }

            if (orderId.isNotEmpty() || price > 0.0) {
                if (orderId.isEmpty()) orderId = "#" + (100000..999999).random()
                val statusText = if (isMasterRunning && isAutoAccept) " (مقبول تلقائياً ✅)" else channelSource
                sendOrderToLog(orderId, price, dist, restaurant, statusText)
                if (isSoundEnabled) playAlertSound()
            }
        } catch (_: Throwable) {}
    }

    private fun sendOrderToLog(orderId: String, price: Double, dist: Double, restaurant: String, status: String) {
        val ctx = appContext ?: return
        try {
            val uri = Uri.parse("content://dev.jing.sanedhook.XposedService")
            val extras = Bundle().apply {
                putString("order_id", orderId)
                putDouble("price", price)
                putDouble("distance", dist)
                putString("restaurant", restaurant)
                putString("status", status)
            }
            ctx.contentResolver.call(uri, "logOrder", null, extras)
        } catch (_: Throwable) {}
    }

    private fun scanAndProcessOrder(activity: Activity) {
        if (!isMasterRunning || !isAutoAccept) return

        val decorView = activity.window?.decorView ?: return
        val startTime = System.currentTimeMillis()

        mainHandler.postDelayed({
            try {
                findAndTriggerOrder(decorView, activity, startTime)
            } catch (_: Throwable) {}
        }, 50)
    }

    private fun findAndTriggerOrder(root: View, activity: Activity, startTime: Long) {
        var acceptButton: View? = null
        var rejectButton: View? = null
        var orderPrice = 0.0
        var distToRestaurant = 0.0
        var orderId = ""

        fun traverse(v: View) {
            if (v is TextView) {
                val text = v.text.toString()
                if (text.contains("SAR") || text.contains("﷼") || text.matches(Regex(".*\d+\.\d+.*"))) {
                    val priceMatch = Regex("(\d+(?:\.\d+)?)").find(text)
                    if (priceMatch != null && orderPrice == 0.0) {
                        orderPrice = priceMatch.value.toDoubleOrNull() ?: 0.0
                    }
                }
                if (text.contains("From You", ignoreCase = true) || text.contains("منك")) {
                    val distMatch = Regex("(\d+(?:\.\d+)?)\s*Km").find(text)
                    if (distMatch != null) {
                        distToRestaurant = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
                }
                if (text.contains("#") || text.matches(Regex(".*\d{7,}.*"))) {
                    val idMatch = Regex("(#?\d{7,})").find(text)
                    if (idMatch != null) {
                        orderId = idMatch.value
                    }
                }
            }

            val desc = v.contentDescription?.toString() ?: ""
            val text = (v as? TextView)?.text?.toString() ?: ""
            if (text.equals("Accept", ignoreCase = true) || text.contains("قبول") ||
                desc.equals("Accept", ignoreCase = true) || desc.contains("قبول")) {
                acceptButton = v
            }

            if (text.equals("Reject", ignoreCase = true) || text.contains("رفض") ||
                desc.equals("Reject", ignoreCase = true) || desc.contains("رفض")) {
                rejectButton = v
            }

            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverse(v.getChildAt(i))
                }
            }
        }

        traverse(root)

        if (acceptButton != null && acceptButton!!.isShown && acceptButton!!.isEnabled) {
            evaluateAndAccept(activity, acceptButton!!, rejectButton, orderPrice, distToRestaurant, orderId, startTime)
        }
    }

    private fun evaluateAndAccept(
        ctx: Context,
        btnAccept: View,
        btnReject: View?,
        price: Double,
        distRest: Double,
        orderId: String,
        startTime: Long
    ) {
        syncSettings(ctx.contentResolver)
        if (minOrderPrice > 0 && price > 0 && price < minOrderPrice) {
            showToast(ctx, "❌ تم رفض الطلب : السعر () أقل من الحد الأدنى ()")
            sendOrderToLog(orderId, price, distRest, "", "مرفوض بسبب السعر ❌")
            if (isAutoReject && btnReject != null) btnReject.performClick()
            return
        }

        if (maxDistToRestaurant > 0 && distRest > 0 && distRest > maxDistToRestaurant) {
            showToast(ctx, "❌ تم رفض الطلب : المسافة ( كم) أبعد من الحد ( كم)")
            sendOrderToLog(orderId, price, distRest, "", "مرفوض بسبب المسافة ❌")
            if (isAutoReject && btnReject != null) btnReject.performClick()
            return
        }

        if (isDryRun) {
            showToast(ctx, "🔍 [وضع التجربة]: كان سيتم قبول الطلب  بنجاح")
            sendOrderToLog(orderId, price, distRest, "", "وضع التجربة (كان سيُقبل) 🔍")
            return
        }

        if (isAccepting.compareAndSet(false, true)) {
            val latency = System.currentTimeMillis() - startTime
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    btnAccept.performClick()
                    btnAccept.callOnClick()
                    showToast(ctx, "✅ تم قبول الطلب  فورياً (ms)!")
                    sendOrderToLog(orderId, price, distRest, "", "تم القبول بنجاح ✅ (ms)")
                    playAlertSound()
                } finally {
                    delay(800)
                    isAccepting.set(false)
                }
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        if (!isShowToasts) return
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun playAlertSound() {
        if (!isSoundEnabled) return
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        } catch (_: Throwable) {}
    }
}
