package dev.saned.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile var isShowToasts: Boolean = true

    private val isAccepting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remotePrefs: SharedPreferences? = null
    @Volatile var appContext: Context? = null
    @Volatile var currentActivity: Activity? = null
    private var isScreenWatcherActive = false
    private val loggedOrderIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastHandledOrderSignature = ""
    @Volatile private var lastHandledTimestamp = 0L

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "dev.saned.assistant.SETTINGS_UPDATE") {
                applySettingsFromIntent(intent)
            }
        }
    }

    fun initAppContext(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        // 1. Register high-speed settings receiver
        try {
            val filter = IntentFilter("dev.saned.assistant.SETTINGS_UPDATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(settingsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(settingsReceiver, filter)
            }
        } catch (_: Throwable) {}

        // 2. Request current settings from Saned Assistant
        try {
            app.sendBroadcast(Intent("dev.saned.assistant.REQUEST_SETTINGS"))
        } catch (_: Throwable) {}

        // 3. Fallback: Read shared config file if available
        loadFallbackConfigFile()

        // 4. Start active screen watcher
        startScreenWatcher()
    }

    fun applySettingsFromIntent(intent: Intent) {
        isMasterRunning = intent.getBooleanExtra("master_running", isMasterRunning)
        isAutoAccept = intent.getBooleanExtra("auto_accept", isAutoAccept)
        isAutoReject = intent.getBooleanExtra("auto_reject", isAutoReject)
        isDryRun = intent.getBooleanExtra("dry_run", isDryRun)
        minOrderPrice = intent.getDoubleExtra("min_price", minOrderPrice)
        maxDistToRestaurant = intent.getDoubleExtra("max_dist_rest", maxDistToRestaurant)
        maxDistCustomer = intent.getDoubleExtra("max_dist_cust", maxDistCustomer)
        isSoundEnabled = intent.getBooleanExtra("sound_enabled", isSoundEnabled)
        isShowToasts = intent.getBooleanExtra("show_toasts", isShowToasts)
    }

    private fun loadFallbackConfigFile() {
        try {
            val f = File("/data/local/tmp/saned_config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                isMasterRunning = json.optBoolean("master_running", isMasterRunning)
                isAutoAccept = json.optBoolean("auto_accept", isAutoAccept)
                isAutoReject = json.optBoolean("auto_reject", isAutoReject)
                isDryRun = json.optBoolean("dry_run", isDryRun)
                minOrderPrice = json.optDouble("min_price", minOrderPrice)
                maxDistToRestaurant = json.optDouble("max_dist_rest", maxDistToRestaurant)
                maxDistCustomer = json.optDouble("max_dist_cust", maxDistCustomer)
            }
        } catch (_: Throwable) {}
    }

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
        loadFallbackConfigFile()

        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isAutoAccept = p.getBoolean("auto_accept", isAutoAccept)
            isAutoReject = p.getBoolean("auto_reject", isAutoReject)
            isDryRun = p.getBoolean("dry_run", isDryRun)
            minOrderPrice = p.getString("min_price", p.getString("min_order_price", "0.0"))?.toDoubleOrNull() ?: minOrderPrice
            maxDistToRestaurant = p.getString("max_dist_rest", p.getString("max_dist_to_restaurant", "0.0"))?.toDoubleOrNull() ?: maxDistToRestaurant
            maxDistCustomer = p.getString("max_dist_cust", p.getString("max_dist_restaurant_to_customer", "0.0"))?.toDoubleOrNull() ?: maxDistCustomer
            isSoundEnabled = p.getBoolean("sound_enabled", isSoundEnabled)
            isShowToasts = p.getBoolean("show_toasts", isShowToasts)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook OkHttp Builder & Responses
        hookOkHttp(module, classLoader)

        // 2. Hook WebSocket
        hookWebSocket(module, classLoader)

        // 3. Hook UI Lifecycle & start Continuous Screen Watcher
        hookUI(module, classLoader)
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader) {
        // A. Hook okhttp3.OkHttpClient$Builder.build() to insert dynamic Interceptor
        try {
            val builderClass = Class.forName("okhttp3.OkHttpClient\$Builder", false, classLoader)
            val interceptorClass = Class.forName("okhttp3.Interceptor", false, classLoader)
            val mBuild = builderClass.getDeclaredMethod("build")

            module.hook(mBuild).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val builder = chain.thisObject
                    try {
                        val mInterceptors = builder.javaClass.getMethod("interceptors")
                        val list = mInterceptors.invoke(builder) as? MutableList<Any>
                        if (list != null) {
                            val interceptorProxy = Proxy.newProxyInstance(classLoader, arrayOf(interceptorClass)) { _, method, args ->
                                if (method.name == "intercept" && args != null && args.isNotEmpty()) {
                                    val chainObj = args[0]
                                    val requestMethod = chainObj.javaClass.getMethod("request")
                                    val request = requestMethod.invoke(chainObj)
                                    val proceedMethod = chainObj.javaClass.getMethod("proceed", request.javaClass)
                                    val response = proceedMethod.invoke(chainObj, request)
                                    if (response != null) {
                                        try {
                                            processHttpResponse(response)
                                        } catch (_: Throwable) {}
                                    }
                                    response
                                } else {
                                    null
                                }
                            }
                            if (!list.contains(interceptorProxy)) {
                                list.add(0, interceptorProxy)
                            }
                        }
                    } catch (_: Throwable) {}
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // B. Hook okhttp3.Response$Builder.build() as universal fallback
        try {
            val respBuilderClass = Class.forName("okhttp3.Response\$Builder", false, classLoader)
            val mBuild = respBuilderClass.getDeclaredMethod("build")
            module.hook(mBuild).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val response = chain.proceed()
                    if (response != null) {
                        try {
                            processHttpResponse(response)
                        } catch (_: Throwable) {}
                    }
                    return response
                }
            })
        } catch (_: Throwable) {}

        // C. Hook okhttp3.Call.execute()
        try {
            val callClass = Class.forName("okhttp3.Call", false, classLoader)
            for (m in callClass.methods) {
                if (m.name == "execute") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val response = chain.proceed()
                            if (response != null) {
                                try {
                                    processHttpResponse(response)
                                } catch (_: Throwable) {}
                            }
                            return response
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookWebSocket(module: XposedModule, classLoader: ClassLoader) {
        // A. Hook RealWebSocket
        try {
            val realWsClass = Class.forName("okhttp3.internal.ws.RealWebSocket", false, classLoader)
            for (m in realWsClass.declaredMethods) {
                if (m.name == "onReadMessage" || m.name == "onMessage") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            for (arg in chain.args) {
                                if (arg is String && arg.isNotEmpty()) {
                                    parseOrderJson(arg, "وارد عبر WebSocket ⚡")
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // B. Hook WebSocketListener
        try {
            val listenerClass = Class.forName("okhttp3.WebSocketListener", false, classLoader)
            for (m in listenerClass.methods) {
                if (m.name == "onMessage" && m.parameterTypes.any { it == String::class.java }) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            for (arg in chain.args) {
                                if (arg is String && arg.isNotEmpty()) {
                                    parseOrderJson(arg, "وارد عبر WebSocket ⚡")
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookUI(module: XposedModule, classLoader: ClassLoader) {
        // Track currently active Activity
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        currentActivity = act
                        initAppContext(act.applicationContext)
                        syncSettings(act.contentResolver)
                        startScreenWatcher()
                        scanAndProcessOrder(act)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}

        try {
            val mFocus = Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType)
            module.hook(mFocus).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val hasFocus = chain.args[0] as? Boolean ?: false
                    val act = chain.thisObject as? Activity
                    if (hasFocus && act != null && act.packageName == "net.jahez.fleets") {
                        currentActivity = act
                        initAppContext(act.applicationContext)
                        syncSettings(act.contentResolver)
                        startScreenWatcher()
                        scanAndProcessOrder(act)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}

        try {
            val mPause = Activity::class.java.getDeclaredMethod("onPause")
            module.hook(mPause).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val act = chain.thisObject as? Activity
                    if (currentActivity == act) {
                        currentActivity = null
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }

    fun startScreenWatcher() {
        if (isScreenWatcherActive) return
        isScreenWatcherActive = true

        mainHandler.post(object : Runnable {
            override fun run() {
                try {
                    val act = currentActivity
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        scanAndProcessOrder(act)
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 120)
            }
        })
    }

    private fun processHttpResponse(response: Any) {
        try {
            val requestMethod = response.javaClass.getMethod("request")
            val request = requestMethod.invoke(response) ?: return
            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request).toString()

            val peekBodyMethod = response.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType)
            val bodyCopy = peekBodyMethod.invoke(response, 512L * 1024L) ?: return
            val stringMethod = bodyCopy.javaClass.getMethod("string")
            val json = stringMethod.invoke(bodyCopy) as? String ?: return

            if (json.isNotEmpty() && (url.contains("order", ignoreCase = true) || 
                                     url.contains("dispatch", ignoreCase = true) || 
                                     url.contains("fleet", ignoreCase = true) || 
                                     url.contains("trip", ignoreCase = true) || 
                                     json.contains("order", ignoreCase = true) || 
                                     json.contains("price", ignoreCase = true))) {
                parseOrderJson(json, "وارد عبر HTTP 🌐")
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

                val dataObj = json.optJSONObject("data") ?: json.optJSONObject("result") ?: json.optJSONObject("order")
                if (dataObj != null) {
                    if (orderId.isEmpty()) orderId = dataObj.optString("order_number", dataObj.optString("order_id", dataObj.optString("id", "")))
                    if (price == 0.0) price = dataObj.optDouble("price", dataObj.optDouble("delivery_cost", dataObj.optDouble("total", 0.0)))
                    if (dist == 0.0) dist = dataObj.optDouble("distance", dataObj.optDouble("km", 0.0))
                    if (restaurant.isEmpty()) restaurant = dataObj.optString("store_name", dataObj.optString("restaurant_name", ""))
                }
            }

            if (orderId.isNotEmpty() || price > 0.0) {
                if (orderId.isEmpty()) orderId = "#" + (100000..999999).random()
                if (loggedOrderIds.add(orderId)) {
                    val statusText = if (isMasterRunning && isAutoAccept) " (مقبول تلقائياً ✅)" else channelSource
                    sendOrderToLog(orderId, price, dist, restaurant, statusText)
                    if (isSoundEnabled) playAlertSound()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun sendOrderToLog(orderId: String, price: Double, dist: Double, restaurant: String, status: String) {
        val ctx = appContext ?: currentActivity ?: return
        try {
            val logIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                setPackage("dev.jing.sanedhook")
                putExtra("order_id", orderId)
                putExtra("price", price)
                putExtra("distance", dist)
                putExtra("restaurant", restaurant)
                putExtra("status", status)
            }
            ctx.sendBroadcast(logIntent)
        } catch (_: Throwable) {}
    }

    private fun scanAndProcessOrder(activity: Activity) {
        val decorView = activity.window?.decorView ?: return
        val startTime = System.currentTimeMillis()

        try {
            findAndTriggerOrder(decorView, activity, startTime)
        } catch (_: Throwable) {}
    }

    private fun findAndTriggerOrder(root: View, activity: Activity, startTime: Long) {
        var acceptView: View? = null
        var rejectView: View? = null
        var orderPrice = 0.0
        var distToRestaurant = 0.0
        var distToCustomer = 0.0
        var storeName = ""
        var orderId = ""
        var isNewOrderScreen = false

        val allTexts = mutableListOf<String>()

        fun traverse(v: View) {
            val desc = v.contentDescription?.toString() ?: ""
            val text = (v as? TextView)?.text?.toString() ?: ""

            if (text.isNotEmpty()) allTexts.add(text)
            if (desc.isNotEmpty()) allTexts.add(desc)

            // Look for Accept button or slider (>> Accept or قبول)
            val combined = (text + " " + desc).trim()
            if (combined.contains("Accept", ignoreCase = true) || combined.contains("قبول") || combined.contains(">>")) {
                if (acceptView == null || combined.contains("Accept", ignoreCase = true)) {
                    acceptView = v
                }
            }

            // Look for Reject button (Reject or رفض)
            if (combined.equals("Reject", ignoreCase = true) || combined.equals("رفض", ignoreCase = true)) {
                rejectView = v
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverse(v.getChildAt(i))
                }
            }
        }

        traverse(root)

        // Parse extracted texts
        for (raw in allTexts) {
            val t = normalizeArabicNumerals(raw).trim()
            if (t.contains("New Order", ignoreCase = true) || t.contains("طلب جديد", ignoreCase = true)) {
                isNewOrderScreen = true
            }

            // Distance to Restaurant (From You)
            val mDistRest = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*You|كم\s*منك)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistRest != null && distToRestaurant == 0.0) {
                distToRestaurant = mDistRest.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            // Distance to Customer (From Pickup)
            val mDistCust = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*Pickup|كم\s*من\s*نقطة\s*الاستلام)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistCust != null && distToCustomer == 0.0) {
                distToCustomer = mDistCust.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            // Store Name (Pick-up from ...)
            val mStore = Regex("""Pick-up from\s*([^,\n]+)""", RegexOption.IGNORE_CASE).find(t)
            if (mStore != null && storeName.isEmpty()) {
                storeName = mStore.groupValues[1].trim()
            }

            // Order ID
            val mId = Regex("""#\s*(\d{5,})""").find(t)
            if (mId != null && orderId.isEmpty()) {
                orderId = "#" + mId.groupValues[1]
            }

            // Accurate Price extraction (7.001 SAR / ﷼ 7.001 / standalone decimal)
            if (!t.contains("Bonus", ignoreCase = true) && !t.contains("From You", ignoreCase = true) && 
                !t.contains("Pickup", ignoreCase = true) && !t.contains("طريق", ignoreCase = true) && orderPrice == 0.0) {
                
                val mPriceCurr = Regex("""(?:SAR|﷼|رس)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(t)
                    ?: Regex("""(\d+(?:\.\d+)?)\s*(?:SAR|﷼|رس)""", RegexOption.IGNORE_CASE).find(t)
                
                if (mPriceCurr != null) {
                    val p = mPriceCurr.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (p in 5.0..350.0) orderPrice = p
                } else if (t.matches(Regex("""^\d{1,3}\.\d{1,3}$"""))) {
                    val p = t.toDoubleOrNull() ?: 0.0
                    if (p in 5.0..350.0) orderPrice = p
                }
            }
        }

        if (orderId.isEmpty()) {
            orderId = if (storeName.isNotEmpty()) "#$storeName" else "#" + (100000..999999).random()
        }

        // If Accept View or New Order screen is present
        if (acceptView != null && (isNewOrderScreen || acceptView!!.isShown)) {
            val signature = "$orderId-$orderPrice-$distToRestaurant"
            val now = System.currentTimeMillis()
            if (signature == lastHandledOrderSignature && (now - lastHandledTimestamp) < 3500L) {
                return // Already handled recently
            }

            lastHandledOrderSignature = signature
            lastHandledTimestamp = now

            // Log order to Orders Log if new
            if (loggedOrderIds.add(orderId)) {
                sendOrderToLog(orderId, orderPrice, distToRestaurant, storeName, "شاشة جاهز اللحظية 📱")
            }

            evaluateAndProcessOrder(activity, acceptView!!, rejectView, orderPrice, distToRestaurant, distToCustomer, orderId, startTime)
        }
    }

    private fun evaluateAndProcessOrder(
        act: Activity,
        btnAccept: View,
        btnReject: View?,
        price: Double,
        distRest: Double,
        distCust: Double,
        orderId: String,
        startTime: Long
    ) {
        syncSettings(act.contentResolver)

        // 1. Check Filters for Auto-Reject
        var shouldReject = false
        var rejectReason = ""

        if (isAutoReject) {
            if (minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice) {
                shouldReject = true
                rejectReason = "السعر (${price} ر.س) أقل من الحد الأدنى (${minOrderPrice} ر.س)"
            } else if (maxDistToRestaurant > 0.0 && distRest > 0.0 && distRest > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "المسافة للمطعم (${distRest} كم) أبعد من الحد (${maxDistToRestaurant} كم)"
            } else if (maxDistCustomer > 0.0 && distCust > 0.0 && distCust > maxDistCustomer) {
                shouldReject = true
                rejectReason = "مسافة التوصيل (${distCust} كم) أبعد من الحد (${maxDistCustomer} كم)"
            }
        }

        if (shouldReject) {
            showToast(act, "❌ تم رفض الطلب $orderId: $rejectReason")
            sendOrderToLog(orderId, price, distRest, "", "مرفوض تلقائياً ❌ ($rejectReason)")
            if (btnReject != null) {
                triggerReject(btnReject, act)
            }
            return
        }

        // 2. Check Dry-Run
        if (isDryRun) {
            showToast(act, "🔍 [وضع التجربة]: كان سيتم قبول الطلب $orderId بنجاح (${price} ر.س)")
            sendOrderToLog(orderId, price, distRest, "", "وضع التجربة (كان سيُقبل) 🔍")
            return
        }

        // 3. Auto-Accept
        // Even if isMasterRunning flag was delayed in sync, if user is in Jahez and order meets criteria:
        if (isAutoAccept || isMasterRunning) {
            if (isAccepting.compareAndSet(false, true)) {
                val latency = System.currentTimeMillis() - startTime
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        simulateSwipe(btnAccept, act)
                        showToast(act, "⚡ تم قبول الطلب $orderId فورياً بنجاح (${latency}ms)!")
                        sendOrderToLog(orderId, price, distRest, "", "تم القبول بنجاح ✅ (${latency}ms)")
                        playAlertSound()
                    } finally {
                        delay(600)
                        isAccepting.set(false)
                    }
                }
            }
        }
    }

    private fun simulateSwipe(view: View, activity: Activity) {
        val targetView = findSliderRoot(view)

        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        val w = targetView.width.toFloat().coerceAtLeast(280f)
        val h = targetView.height.toFloat().coerceAtLeast(60f)

        val startScreenX = location[0].toFloat() + 60f
        val endScreenX = location[0].toFloat() + w - 50f
        val screenY = location[1].toFloat() + (h / 2f)

        val startLocalX = 60f
        val endLocalX = w - 50f
        val localY = h / 2f

        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        // 1. Dispatch local MotionEvents to targetView
        try {
            val downLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, startLocalX, localY, 0)
            targetView.dispatchTouchEvent(downLocal)
            downLocal.recycle()

            val steps = 16
            for (i in 1..steps) {
                eventTime += 10
                val currX = startLocalX + (endLocalX - startLocalX) * (i.toFloat() / steps.toFloat())
                val moveLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, currX, localY, 0)
                targetView.dispatchTouchEvent(moveLocal)
                moveLocal.recycle()
            }

            eventTime += 10
            val upLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, endLocalX, localY, 0)
            targetView.dispatchTouchEvent(upLocal)
            upLocal.recycle()
        } catch (_: Throwable) {}

        // 2. Dispatch window MotionEvents to decorView with screen coordinates
        try {
            val decor = activity.window.decorView
            val downScreen = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startScreenX, screenY, 0)
            decor.dispatchTouchEvent(downScreen)
            downScreen.recycle()

            var sTime = downTime
            val steps = 16
            for (i in 1..steps) {
                sTime += 10
                val currX = startScreenX + (endScreenX - startScreenX) * (i.toFloat() / steps.toFloat())
                val moveScreen = MotionEvent.obtain(downTime, sTime, MotionEvent.ACTION_MOVE, currX, screenY, 0)
                decor.dispatchTouchEvent(moveScreen)
                moveScreen.recycle()
            }

            sTime += 10
            val upScreen = MotionEvent.obtain(downTime, sTime, MotionEvent.ACTION_UP, endScreenX, screenY, 0)
            decor.dispatchTouchEvent(upScreen)
            upScreen.recycle()
        } catch (_: Throwable) {}

        // 3. Regular Clicks on view and parent chain
        try {
            view.performClick()
            view.callOnClick()
            targetView.performClick()
            targetView.callOnClick()
        } catch (_: Throwable) {}

        // 4. Invoke any slider completion methods reflectively
        var current: View? = targetView
        while (current != null) {
            try {
                for (m in current.javaClass.declaredMethods) {
                    val name = m.name.lowercase()
                    if (name.contains("complete") || name.contains("slide") || name.contains("swipe") || name.contains("accept") || name.contains("confirm")) {
                        m.isAccessible = true
                        if (m.parameterTypes.isEmpty()) {
                            m.invoke(current)
                        } else if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                            m.invoke(current, true)
                        }
                    }
                }
            } catch (_: Throwable) {}
            current = current.parent as? View
        }
    }

    private fun triggerReject(view: View, activity: Activity) {
        try {
            view.performClick()
            view.callOnClick()
        } catch (_: Throwable) {}

        try {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val cx = location[0].toFloat() + (view.width / 2f)
            val cy = location[1].toFloat() + (view.height / 2f)
            val downTime = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
            view.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, cx, cy, 0)
            view.dispatchTouchEvent(up)
            up.recycle()
        } catch (_: Throwable) {}
    }

    private fun findSliderRoot(v: View): View {
        var curr = v
        while (curr.parent is View) {
            val p = curr.parent as View
            if (p.width > 250 && p.height in 50..300) {
                return p
            }
            curr = p
        }
        return v
    }

    private fun normalizeArabicNumerals(str: String): String {
        return str.replace('٠', '0')
            .replace('١', '1')
            .replace('٢', '2')
            .replace('٣', '3')
            .replace('٤', '4')
            .replace('٥', '5')
            .replace('٦', '6')
            .replace('٧', '7')
            .replace('٨', '8')
            .replace('٩', '9')
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
