package dev.saned.assistant

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

object OrderInterceptor {

    // Default to active state
    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isAutoAccept: Boolean = true
    @Volatile var isAutoReject: Boolean = false
    @Volatile var isDryRun: Boolean = false
    @Volatile var minOrderPrice: Double = 0.0
    @Volatile var maxDistToRestaurant: Double = 0.0
    @Volatile var maxDistCustomer: Double = 0.0
    @Volatile var isSoundEnabled: Boolean = false
    @Volatile var isShowToasts: Boolean = true

    // Active Server Polling & Parallel Acceptance (Original Assistant Mechanics)
    @Volatile var isActivePolling: Boolean = true
    @Volatile var pollIntervalSec: Float = 0.8f
    @Volatile var parallelRequests: Int = 3
    @Volatile var lastOrdersListUrl: String = ""
    private val isPollingRunning = AtomicBoolean(false)

    // Network & Session Cache
    @Volatile var cachedAuthToken: String = ""
    @Volatile var cachedApiHost: String = ""
    private val cachedHeaders = ConcurrentHashMap<String, String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

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

        // 2. Request current settings using EXPLICIT intent (bypasses package visibility)
        requestSettingsFromAssistant()

        // 3. Fallback: Read shared config file if available
        loadFallbackConfigFile()

        // 4. Proactive: Scan Jahez SharedPreferences for auth tokens
        scanSharedPreferencesForAuth(app)

        // 5. Start active screen watcher
        startScreenWatcher()

        // 6. Start active server polling loop (matching original assistant)
        startActiveServerPolling()
    }

    fun requestSettingsFromAssistant() {
        val app = appContext ?: return
        try {
            val reqIntent = Intent("dev.saned.assistant.REQUEST_SETTINGS").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
            }
            app.sendBroadcast(reqIntent)
        } catch (_: Throwable) {}

        try {
            val gIntent = Intent("dev.saned.assistant.REQUEST_SETTINGS")
            app.sendBroadcast(gIntent)
        } catch (_: Throwable) {}
    }

    fun applySettingsFromIntent(intent: Intent) {
        isMasterRunning = intent.getBooleanExtra("master_running", isMasterRunning)
        isAutoAccept = intent.getBooleanExtra("auto_accept", isAutoAccept)
        isAutoReject = intent.getBooleanExtra("auto_reject", isAutoReject)
        isDryRun = intent.getBooleanExtra("dry_run", isDryRun)
        isActivePolling = intent.getBooleanExtra("active_polling", isActivePolling)
        pollIntervalSec = intent.getFloatExtra("poll_interval_sec", pollIntervalSec)
        parallelRequests = intent.getIntExtra("parallel_requests", parallelRequests)
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
                isActivePolling = json.optBoolean("active_polling", isActivePolling)
                pollIntervalSec = json.optDouble("poll_interval_sec", pollIntervalSec.toDouble()).toFloat()
                parallelRequests = json.optInt("parallel_requests", parallelRequests)
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
            isActivePolling = p.getBoolean("active_polling", isActivePolling)
            pollIntervalSec = p.getFloat("poll_interval_sec", pollIntervalSec)
            parallelRequests = p.getInt("parallel_requests", parallelRequests)
            minOrderPrice = p.getString("min_price", p.getString("min_order_price", minOrderPrice.toString()))?.toDoubleOrNull() ?: minOrderPrice
            maxDistToRestaurant = p.getString("max_dist_rest", p.getString("max_dist_to_restaurant", maxDistToRestaurant.toString()))?.toDoubleOrNull() ?: maxDistToRestaurant
            maxDistCustomer = p.getString("max_dist_cust", p.getString("max_dist_restaurant_to_customer", maxDistCustomer.toString()))?.toDoubleOrNull() ?: maxDistCustomer
            isSoundEnabled = p.getBoolean("sound_enabled", isSoundEnabled)
            isShowToasts = p.getBoolean("show_toasts", isShowToasts)
        }
    }

    fun scanSharedPreferencesForAuth(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    val prefName = file.nameWithoutExtension
                    try {
                        val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                        for ((k, v) in sp.all) {
                            val strVal = v?.toString() ?: ""
                            if (strVal.startsWith("Bearer ", ignoreCase = true) || 
                                (k.contains("token", ignoreCase = true) && strVal.length > 20)) {
                                val token = if (strVal.startsWith("Bearer ", ignoreCase = true)) strVal else "Bearer $strVal"
                                cachedAuthToken = token
                                cachedHeaders["Authorization"] = token
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook JSON Object & Array constructors (Universal network/push parser)
        hookJsonConstructors(module)

        // 2. Hook javax.crypto.Cipher.doFinal (Intercept decrypted AES payloads as in original app)
        hookCipher(module)

        // 3. Hook Dialog.show() to immediately detect bottom-sheets / popups
        hookDialogShow(module)

        // 4. Hook OkHttp Builder, Responses & WebSockets
        hookOkHttp(module, classLoader)
        hookWebSocket(module, classLoader)

        // 5. Hook UI Lifecycle & start Continuous Multi-Window Screen Watcher
        hookUI(module, classLoader)
    }

    private fun hookCipher(module: XposedModule) {
        try {
            val doFinalMethod = Cipher::class.java.getDeclaredMethod("doFinal", ByteArray::class.java)
            module.hook(doFinalMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val bytes = result as? ByteArray
                    if (bytes != null && bytes.isNotEmpty()) {
                        try {
                            val text = String(bytes, Charsets.UTF_8).trim()
                            if ((text.startsWith("{") || text.startsWith("[")) && 
                                (text.contains("order", ignoreCase = true) || 
                                 text.contains("trip", ignoreCase = true) || 
                                 text.contains("dispatch", ignoreCase = true))) {
                                parseOrderJson(text, "وارد عبر التشفير (Cipher) 🔐")
                            }
                        } catch (_: Throwable) {}
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookJsonConstructors(module: XposedModule) {
        try {
            val jsonConst = JSONObject::class.java.getConstructor(String::class.java)
            module.hook(jsonConst).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val str = chain.args.getOrNull(0) as? String
                    if (!str.isNullOrEmpty()) {
                        val lower = str.lowercase()
                        if (lower.contains("order") || lower.contains("trip") || lower.contains("dispatch") || lower.contains("delivery")) {
                            parseOrderJson(str, "وارد عبر الشبكة (JSON) 🌐")
                        }
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        try {
            val arrConst = JSONArray::class.java.getConstructor(String::class.java)
            module.hook(arrConst).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val str = chain.args.getOrNull(0) as? String
                    if (!str.isNullOrEmpty()) {
                        val lower = str.lowercase()
                        if (lower.contains("order") || lower.contains("trip") || lower.contains("dispatch")) {
                            parseOrderJson(str, "وارد عبر الشبكة (Array) 🌐")
                        }
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookDialogShow(module: XposedModule) {
        try {
            val mShow = Dialog::class.java.getDeclaredMethod("show")
            module.hook(mShow).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val dialog = chain.thisObject as? Dialog
                    if (dialog != null) {
                        mainHandler.postDelayed({
                            try {
                                val dView = dialog.window?.decorView
                                val act = currentActivity
                                if (dView != null && act != null) {
                                    findAndTriggerOrder(dView, act, System.currentTimeMillis())
                                }
                            } catch (_: Throwable) {}
                        }, 50)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader) {
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
                                    captureRequestMetadata(request)

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
    }

    private fun captureRequestMetadata(request: Any) {
        try {
            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request).toString()
            if (url.startsWith("http")) {
                val uri = java.net.URI(url)
                cachedApiHost = "${uri.scheme}://${uri.host}"

                // If URL fetches orders, save it for active polling
                val lowerUrl = url.lowercase()
                if (lowerUrl.contains("order") || lowerUrl.contains("fleet") || lowerUrl.contains("dispatch")) {
                    if (!lowerUrl.contains("/accept") && !lowerUrl.contains("/reject")) {
                        lastOrdersListUrl = url
                    }
                }
            }

            val headersMethod = request.javaClass.getMethod("headers")
            val headersObj = headersMethod.invoke(request)
            val getMethod = headersObj.javaClass.getMethod("get", String::class.java)

            val auth = getMethod.invoke(headersObj, "Authorization") as? String
            if (!auth.isNullOrEmpty()) {
                cachedAuthToken = auth
                cachedHeaders["Authorization"] = auth
            }

            val cookie = getMethod.invoke(headersObj, "Cookie") as? String
            if (!cookie.isNullOrEmpty()) cachedHeaders["Cookie"] = cookie

            val userAgent = getMethod.invoke(headersObj, "User-Agent") as? String
            if (!userAgent.isNullOrEmpty()) cachedHeaders["User-Agent"] = userAgent

            val acceptLang = getMethod.invoke(headersObj, "accept-language") as? String
            if (!acceptLang.isNullOrEmpty()) cachedHeaders["accept-language"] = acceptLang
        } catch (_: Throwable) {}
    }

    private fun hookWebSocket(module: XposedModule, classLoader: ClassLoader) {
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
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        currentActivity = act
                        initAppContext(act.applicationContext)
                        requestSettingsFromAssistant()
                        startScreenWatcher()
                        startActiveServerPolling()
                        scanAllRoots()
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
                    if (hasFocus && act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        currentActivity = act
                        initAppContext(act.applicationContext)
                        requestSettingsFromAssistant()
                        startScreenWatcher()
                        startActiveServerPolling()
                        scanAllRoots()
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}
    }

    fun getAllRootViews(): List<View> {
        val result = mutableListOf<View>()
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstance = wmgClass.getMethod("getInstance")
            val wmg = getInstance.invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true
            val list = mViewsField.get(wmg) as? List<*>
            if (list != null) {
                synchronized(list) {
                    for (item in list) {
                        if (item is View) {
                            result.add(item)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (result.isEmpty()) {
            currentActivity?.window?.decorView?.let { result.add(it) }
        }
        return result
    }

    fun startScreenWatcher() {
        if (isScreenWatcherActive) return
        isScreenWatcherActive = true

        mainHandler.post(object : Runnable {
            override fun run() {
                try {
                    scanAllRoots()
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 100)
            }
        })
    }

    fun scanAllRoots() {
        val act = currentActivity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val roots = getAllRootViews()
        val startTime = System.currentTimeMillis()

        // 1. Scan in reverse order: Dialog/BottomSheet windows are always on top!
        for (root in roots.reversed()) {
            try {
                if (root.isShown && root.visibility == View.VISIBLE) {
                    val handled = findAndTriggerOrder(root, act, startTime)
                    if (handled) return
                }
            } catch (_: Throwable) {}
        }

        // 2. If no popup found, scan Home screen order list cards
        for (root in roots) {
            try {
                if (root.isShown && root.visibility == View.VISIBLE) {
                    val clicked = scanHomeScreenOrderCards(root, act)
                    if (clicked) return
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Active Server Polling: Query Jahez backend repeatedly to grab available orders
     * before they are displayed or claimed by other drivers.
     */
    fun startActiveServerPolling() {
        if (!isPollingRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    if (isMasterRunning && isActivePolling) {
                        pollServerForAvailableOrders()
                    }
                } catch (_: Throwable) {}
                val delayMs = ((pollIntervalSec.coerceIn(0.2f, 10.0f)) * 1000).toLong()
                delay(delayMs)
            }
        }
    }

    private fun pollServerForAvailableOrders() {
        val host = if (cachedApiHost.isNotEmpty()) cachedApiHost else "https://fleets.jahez.net"
        val token = cachedAuthToken
        if (token.isEmpty()) return

        val targetUrls = mutableListOf<String>()
        if (lastOrdersListUrl.isNotEmpty()) {
            targetUrls.add(lastOrdersListUrl)
        }
        targetUrls.add("$host/api/fleets/orders/available")
        targetUrls.add("$host/api/fleets/orders")
        targetUrls.add("$host/api/driver/orders/available")
        targetUrls.add("$host/api/driver/orders")
        targetUrls.add("$host/api/fleets/orders/unassigned")

        for (url in targetUrls) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                reqBuilder.header("Authorization", token)
                for ((k, v) in cachedHeaders) {
                    if (k != "Authorization" && k != "Content-Length") {
                        reqBuilder.header(k, v)
                    }
                }
                val resp = httpClient.newCall(reqBuilder.build()).execute()
                val code = resp.code
                val body = resp.body?.string() ?: ""
                resp.close()

                if (code in 200..299 && body.isNotEmpty()) {
                    if (body.contains("order", ignoreCase = true) || body.contains("trip", ignoreCase = true)) {
                        parseOrderJson(body, "استعلام الخادم (POLL) ⚡")
                        break
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun processHttpResponse(response: Any) {
        try {
            val requestMethod = response.javaClass.getMethod("request")
            val request = requestMethod.invoke(response) ?: return
            captureRequestMetadata(request)

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

            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        extractAndProcessOrderJson(item, channelSource)
                    }
                }
                return
            }

            val json = JSONObject(trimmed)
            extractAndProcessOrderJson(json, channelSource)

            val dataArray = json.optJSONArray("data") ?: json.optJSONArray("orders") ?: json.optJSONArray("result")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i)
                    if (item != null) {
                        extractAndProcessOrderJson(item, channelSource)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractAndProcessOrderJson(json: JSONObject, channelSource: String) {
        try {
            var orderId = json.optString("order_number", json.optString("order_id", json.optString("id", "")))
            var price = json.optDouble("price", json.optDouble("delivery_cost", json.optDouble("total", 0.0)))
            var dist = json.optDouble("distance", json.optDouble("km", 0.0))
            var restaurant = json.optString("store_name", json.optString("restaurant_name", ""))

            val dataObj = json.optJSONObject("data") ?: json.optJSONObject("order") ?: json.optJSONObject("trip")
            if (dataObj != null) {
                if (orderId.isEmpty()) orderId = dataObj.optString("order_number", dataObj.optString("order_id", dataObj.optString("id", "")))
                if (price == 0.0) price = dataObj.optDouble("price", dataObj.optDouble("delivery_cost", dataObj.optDouble("total", 0.0)))
                if (dist == 0.0) dist = dataObj.optDouble("distance", dataObj.optDouble("km", 0.0))
                if (restaurant.isEmpty()) restaurant = dataObj.optString("store_name", dataObj.optString("restaurant_name", ""))
            }

            if (orderId.isNotEmpty() || price > 0.0 || restaurant.isNotEmpty()) {
                if (orderId.isEmpty()) orderId = "#" + (100000..999999).random()
                if (loggedOrderIds.add(orderId)) {
                    val cleanPrice = String.format(Locale.US, "%.2f", price).toDoubleOrNull() ?: price
                    sendOrderToLog(orderId, cleanPrice, dist, restaurant, channelSource)
                    if (isSoundEnabled) playAlertSound()

                    // If order arrived via network / polling, trigger direct HTTP API Accept/Reject action!
                    handleOrderViaApi(orderId, cleanPrice, dist, restaurant)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Executes direct API calls to Jahez endpoints using OkHttp, matching SanedAssistant-98
     */
    private fun handleOrderViaApi(orderId: String, price: Double, dist: Double, restaurant: String) {
        val rawId = orderId.replace("#", "").trim()
        if (rawId.isEmpty()) return

        var shouldReject = false
        var rejectReason = ""

        if (isAutoReject) {
            if (minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice) {
                shouldReject = true
                rejectReason = "السعر (${String.format(Locale.US, "%.1f", price)} ر.س) أقل من الحد الأدنى"
            } else if (maxDistToRestaurant > 0.0 && dist > 0.0 && dist > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "المسافة للمطعم (${dist} كم) أبعد من الحد"
            }
        }

        if (shouldReject) {
            currentActivity?.let { act -> showToast(act, "❌ تم رفض الطلب $orderId برمجياً: $rejectReason") }
            sendOrderToLog(orderId, price, dist, restaurant, "مرفوض تلقائياً (API) ❌")
            executeDirectApiCall(rawId, "REJECT")
            return
        }

        if (isDryRun) {
            currentActivity?.let { act -> showToast(act, "🔍 [وضع التجربة]: كان سيتم قبول الطلب $orderId (API)") }
            sendOrderToLog(orderId, price, dist, restaurant, "وضع التجربة (كان سيُقبل) 🔍")
            return
        }

        if (isAutoAccept || isMasterRunning) {
            currentActivity?.let { act -> showToast(act, "⚡ جاري قبول الطلب $orderId عبر الشبكة مباشرة...") }
            sendOrderToLog(orderId, price, dist, restaurant, "مقبول تلقائياً (API) ✅")
            executeDirectApiCall(rawId, "ACCEPT")
            playAlertSound()
        }
    }

    /**
     * Sends direct API requests using parallel bursts to win race against competing drivers
     */
    private fun executeDirectApiCall(orderIdNum: String, action: String) {
        val host = if (cachedApiHost.isNotEmpty()) cachedApiHost else "https://fleets.jahez.net"
        val token = cachedAuthToken
        val burstCount = if (action == "ACCEPT") parallelRequests.coerceIn(1, 5) else 1

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoints = if (action == "ACCEPT") {
                    listOf(
                        "$host/api/fleets/orders/$orderIdNum/accept",
                        "$host/api/driver/orders/$orderIdNum/accept",
                        "$host/api/orders/$orderIdNum/accept"
                    )
                } else {
                    listOf(
                        "$host/api/fleets/orders/$orderIdNum/reject",
                        "$host/api/driver/orders/$orderIdNum/reject",
                        "$host/api/orders/$orderIdNum/reject"
                    )
                }

                val body = "{\"order_id\":\"$orderIdNum\"}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                for (burst in 0 until burstCount) {
                    launch {
                        for (url in endpoints) {
                            try {
                                val reqBuilder = Request.Builder()
                                    .url(url)
                                    .post(body)

                                if (token.isNotEmpty()) {
                                    reqBuilder.header("Authorization", token)
                                }
                                for ((k, v) in cachedHeaders) {
                                    if (k != "Authorization" && k != "Content-Length") {
                                        reqBuilder.header(k, v)
                                    }
                                }

                                val resp = httpClient.newCall(reqBuilder.build()).execute()
                                val code = resp.code
                                resp.close()
                                if (code in 200..299) {
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun sendOrderToLog(orderId: String, price: Double, dist: Double, restaurant: String, status: String) {
        val ctx = appContext ?: currentActivity

        // 1. Explicit Broadcast directly to SanedReceiver
        if (ctx != null) {
            try {
                val logIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                    component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                    putExtra("order_id", orderId)
                    putExtra("price", price)
                    putExtra("distance", dist)
                    putExtra("restaurant", restaurant)
                    putExtra("status", status)
                }
                ctx.sendBroadcast(logIntent)
            } catch (_: Throwable) {}

            try {
                val gIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                    putExtra("order_id", orderId)
                    putExtra("price", price)
                    putExtra("distance", dist)
                    putExtra("restaurant", restaurant)
                    putExtra("status", status)
                }
                ctx.sendBroadcast(gIntent)
            } catch (_: Throwable) {}
        }

        // 2. Fallback: Write shared JSON file in /data/local/tmp/
        try {
            val f = File("/data/local/tmp/saned_orders.json")
            val existing = if (f.exists()) f.readText() else "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val newObj = JSONObject().apply {
                put("id", orderId)
                put("price", price)
                put("distance", dist)
                put("restaurant", restaurant)
                put("status", status)
                put("time", timeStr)
            }
            val newArray = JSONArray()
            newArray.put(newObj)
            for (i in 0 until minOf(39, array.length())) {
                newArray.put(array.getJSONObject(i))
            }
            f.writeText(newArray.toString())
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    /**
     * Scans a root view (Activity, Dialog, BottomSheet) for Order Accept slider/button & Reject button
     */
    private fun findAndTriggerOrder(root: View, activity: Activity, startTime: Long): Boolean {
        var acceptView: View? = null
        var rejectView: View? = null
        var confirmDialogBtn: View? = null
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

            val combined = (text + " " + desc).trim()

            // Look for Accept slider or button
            if (combined.contains("Accept", ignoreCase = true) || combined.contains("قبول") || combined.contains(">>")) {
                if (acceptView == null || combined.contains("Accept", ignoreCase = true)) {
                    acceptView = v
                }
            }

            // Look for Reject button
            if (combined.equals("Reject", ignoreCase = true) || combined.equals("رفض", ignoreCase = true)) {
                rejectView = v
            }

            // Look for confirmation popup buttons (e.g. Yes, Confirm, تأكيد, نعم)
            if (combined.equals("Confirm", ignoreCase = true) || combined.equals("Yes", ignoreCase = true) ||
                combined.equals("تأكيد", ignoreCase = true) || combined.equals("نعم", ignoreCase = true)) {
                confirmDialogBtn = v
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverse(v.getChildAt(i))
                }
            }
        }

        traverse(root)

        // Handle possible confirmation dialog for reject
        if (confirmDialogBtn != null && confirmDialogBtn!!.isShown) {
            triggerClick(confirmDialogBtn!!)
        }

        // Parse extracted texts
        for (raw in allTexts) {
            val t = normalizeArabicNumerals(raw).trim()
            if (t.contains("New Order", ignoreCase = true) || t.contains("طلب جديد", ignoreCase = true)) {
                isNewOrderScreen = true
            }

            // Distance to Restaurant (e.g., "12.1 Km From You" / "12.1 كم منك")
            val mDistRest = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*You|كم\s*منك)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistRest != null && distToRestaurant == 0.0) {
                distToRestaurant = mDistRest.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            // Distance to Customer (e.g., "1.8 Km From Pickup" / "1.8 كم من نقطة الاستلام")
            val mDistCust = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*Pickup|كم\s*من\s*نقطة\s*الاستلام)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistCust != null && distToCustomer == 0.0) {
                distToCustomer = mDistCust.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            // Store Name (Pick-up from ...)
            val mStore = Regex("""Pick-up from\s*([^,\n]+)""", RegexOption.IGNORE_CASE).find(t)
            if (mStore != null && storeName.isEmpty()) {
                storeName = mStore.groupValues[1].trim()
            }

            // Order ID: only match whole numbers with at least 5 digits (no decimal points)
            if (!t.contains(".") && !t.contains("Km", ignoreCase = true)) {
                val mId = Regex("""#?\s*(\d{5,12})""").find(t)
                if (mId != null && orderId.isEmpty()) {
                    orderId = "#" + mId.groupValues[1]
                }
            }

            // Price extraction: Match numbers with currency symbol (including official riyal ligature ﷼)
            if (!t.contains("From You", ignoreCase = true) && !t.contains("Pickup", ignoreCase = true) && 
                !t.contains("منك", ignoreCase = true) && orderPrice == 0.0) {
                
                val mPrice = Regex("""(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)?\s*(\d+(?:\.\d+)?)\s*(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)?""", RegexOption.IGNORE_CASE).find(t)
                if (mPrice != null) {
                    val p = mPrice.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (p in 2.0..500.0 && p != distToRestaurant && p != distToCustomer) {
                        orderPrice = p
                    }
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
            if (signature == lastHandledOrderSignature && (now - lastHandledTimestamp) < 3000L) {
                return true // Already handled recently
            }

            lastHandledOrderSignature = signature
            lastHandledTimestamp = now

            // Log order to Orders Log immediately
            if (loggedOrderIds.add(orderId)) {
                val cleanPrice = String.format(Locale.US, "%.2f", orderPrice).toDoubleOrNull() ?: orderPrice
                sendOrderToLog(orderId, cleanPrice, distToRestaurant, storeName, "شاشة الطلب اللحظية 📱")
            }

            evaluateAndProcessOrder(activity, root, acceptView!!, rejectView, orderPrice, distToRestaurant, distToCustomer, orderId, startTime)
            return true
        }

        return false
    }

    private fun scanHomeScreenOrderCards(root: View, activity: Activity): Boolean {
        var newOrderCardView: View? = null
        var foundOrderId = ""
        var foundStore = ""

        fun traverseList(v: View) {
            val text = (v as? TextView)?.text?.toString() ?: ""
            val desc = v.contentDescription?.toString() ?: ""
            val combined = "$text $desc".trim()

            if (combined.equals("New", ignoreCase = true) || combined.equals("جديد", ignoreCase = true)) {
                var parent = v.parent as? View
                while (parent != null) {
                    if (parent.width > 300 && parent.height in 80..600) {
                        newOrderCardView = parent
                        break
                    }
                    parent = parent.parent as? View
                }
                if (newOrderCardView == null) newOrderCardView = v
            }

            val mId = Regex("""\b(\d{7,10})\b""").find(combined)
            if (mId != null && foundOrderId.isEmpty()) {
                foundOrderId = "#" + mId.groupValues[1]
            }

            if (combined.contains("Pho", ignoreCase = true) || combined.contains("Gatherin", ignoreCase = true)) {
                foundStore = combined
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverseList(v.getChildAt(i))
                }
            }
        }

        traverseList(root)

        if (newOrderCardView != null && foundOrderId.isNotEmpty()) {
            val signature = "home-$foundOrderId"
            val now = System.currentTimeMillis()
            if (signature == lastHandledOrderSignature && (now - lastHandledTimestamp) < 3000L) {
                return false
            }

            lastHandledOrderSignature = signature
            lastHandledTimestamp = now

            if (loggedOrderIds.add(foundOrderId)) {
                sendOrderToLog(foundOrderId, 0.0, 0.0, foundStore, "شاشة الطلبات الرئيسية 📋 (طلب متاح)")
            }

            if (isAutoAccept || isMasterRunning) {
                mainHandler.post {
                    triggerClick(newOrderCardView!!)
                }
                return true
            }
        }

        return false
    }

    private fun evaluateAndProcessOrder(
        act: Activity,
        dialogRoot: View,
        btnAccept: View,
        btnReject: View?,
        price: Double,
        distRest: Double,
        distCust: Double,
        orderId: String,
        startTime: Long
    ) {
        // 1. Check Filters for Auto-Reject
        var shouldReject = false
        var rejectReason = ""

        if (isAutoReject) {
            if (minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice) {
                shouldReject = true
                rejectReason = "السعر (${String.format(Locale.US, "%.1f", price)} ر.س) أقل من الحد الأدنى (${minOrderPrice} ر.س)"
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
                triggerClick(btnReject)
            }
            executeDirectApiCall(orderId.replace("#", ""), "REJECT")
            return
        }

        // 2. Check Dry-Run
        if (isDryRun) {
            showToast(act, "🔍 [وضع التجربة]: كان سيتم قبول الطلب $orderId بنجاح")
            sendOrderToLog(orderId, price, distRest, "", "وضع التجربة (كان سيُقبل) 🔍")
            return
        }

        // 3. Auto-Accept: Trigger instant dual execution (API Call + Screen Gesture)
        if (isAutoAccept || isMasterRunning) {
            if (isAccepting.compareAndSet(false, true)) {
                val latency = System.currentTimeMillis() - startTime
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        // A. Trigger screen swipe & click
                        simulateSwipe(btnAccept, dialogRoot, act)
                        // B. Trigger direct OkHttp Accept API
                        executeDirectApiCall(orderId.replace("#", ""), "ACCEPT")

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

    private fun simulateSwipe(view: View, dialogRoot: View, activity: Activity) {
        val targetView = findSliderRoot(view)

        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        val w = targetView.width.toFloat().coerceAtLeast(320f)
        val h = targetView.height.toFloat().coerceAtLeast(65f)

        val startScreenX = location[0].toFloat() + 70f
        val endScreenX = location[0].toFloat() + w - 40f
        val screenY = location[1].toFloat() + (h / 2f)

        val startLocalX = 70f
        val endLocalX = w - 40f
        val localY = h / 2f

        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        // A. Clicks on view and parent chain
        triggerClick(view)
        triggerClick(targetView)

        // B. Dispatch local MotionEvents to targetView
        try {
            val downLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, startLocalX, localY, 0)
            targetView.dispatchTouchEvent(downLocal)
            downLocal.recycle()

            val steps = 25
            for (i in 1..steps) {
                eventTime += 6
                val currX = startLocalX + (endLocalX - startLocalX) * (i.toFloat() / steps.toFloat())
                val moveLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, currX, localY, 0)
                targetView.dispatchTouchEvent(moveLocal)
                moveLocal.recycle()
            }

            eventTime += 6
            val upLocal = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, endLocalX, localY, 0)
            targetView.dispatchTouchEvent(upLocal)
            upLocal.recycle()
        } catch (_: Throwable) {}

        // C. Dispatch window MotionEvents to Dialog/Activity DecorView with screen coordinates
        try {
            var sTime = downTime
            val downScreen = MotionEvent.obtain(downTime, sTime, MotionEvent.ACTION_DOWN, startScreenX, screenY, 0)
            dialogRoot.dispatchTouchEvent(downScreen)
            downScreen.recycle()

            val steps = 25
            for (i in 1..steps) {
                sTime += 6
                val currX = startScreenX + (endScreenX - startScreenX) * (i.toFloat() / steps.toFloat())
                val moveScreen = MotionEvent.obtain(downTime, sTime, MotionEvent.ACTION_MOVE, currX, screenY, 0)
                dialogRoot.dispatchTouchEvent(moveScreen)
                moveScreen.recycle()
            }

            sTime += 6
            val upScreen = MotionEvent.obtain(downTime, sTime, MotionEvent.ACTION_UP, endScreenX, screenY, 0)
            dialogRoot.dispatchTouchEvent(upScreen)
            upScreen.recycle()
        } catch (_: Throwable) {}

        // D. Also dispatch on activity window decorView if distinct
        try {
            val decor = activity.window.decorView
            if (decor != dialogRoot) {
                val downScreen = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startScreenX, screenY, 0)
                decor.dispatchTouchEvent(downScreen)
                downScreen.recycle()

                val upScreen = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, endScreenX, screenY, 0)
                decor.dispatchTouchEvent(upScreen)
                upScreen.recycle()
            }
        } catch (_: Throwable) {}

        // E. Invoke reflective methods
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

    private fun triggerClick(view: View) {
        try {
            view.performClick()
            view.callOnClick()
        } catch (_: Throwable) {}

        try {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val cx = location[0].toFloat() + (view.width / 2f).coerceAtLeast(20f)
            val cy = location[1].toFloat() + (view.height / 2f).coerceAtLeast(20f)
            val downTime = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cx, cy, 0)
            view.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, cx, cy, 0)
            view.dispatchTouchEvent(up)
            up.recycle()
        } catch (_: Throwable) {}
    }

    private fun findSliderRoot(v: View): View {
        var curr = v
        while (curr.parent is View) {
            val p = curr.parent as View
            if (p.width > 260 && p.height in 50..300) {
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
