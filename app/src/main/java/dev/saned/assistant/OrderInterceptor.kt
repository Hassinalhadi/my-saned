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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object OrderInterceptor {

    // Master & Filter Controls
    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isAutoAccept: Boolean = true
    @Volatile var isAutoReject: Boolean = false
    @Volatile var isDryRun: Boolean = false
    @Volatile var minOrderPrice: Double = 0.0
    @Volatile var maxDistToRestaurant: Double = 0.0
    @Volatile var maxDistCustomer: Double = 0.0
    @Volatile var isSoundEnabled: Boolean = false
    @Volatile var isShowToasts: Boolean = true

    // Active Server Polling & Parallel Acceptance
    @Volatile var isActivePolling: Boolean = true
    @Volatile var pollIntervalSec: Float = 0.8f
    @Volatile var parallelRequests: Int = 3
    @Volatile var lastOrdersListUrl: String = ""
    private val isPollingRunning = AtomicBoolean(false)

    // Network & Session Diagnostics
    val httpRequestsCount = AtomicInteger(0)
    val pollingHits = AtomicInteger(0)
    @Volatile var lastInterceptedUrl: String = "None yet"
    @Volatile var lastHttpResponseCode: Int = 0
    @Volatile var lastOrderEvent: String = "Listening for orders..."
    @Volatile var isOkHttpHooked: Boolean = true

    // Network & Session Cache
    @Volatile var cachedAuthToken: String = ""
    @Volatile var cachedApiHost: String = ""
    private val cachedHeaders = ConcurrentHashMap<String, String>()

    // Resilient SSL-Bypassing OkHttpClient for direct Jahez API calls
    private val httpClient: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    private val isAccepting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remotePrefs: SharedPreferences? = null
    @Volatile var appContext: Context? = null
    @Volatile var currentActivity: Activity? = null
    private var isScreenWatcherActive = false
    val loggedOrderIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastHandledOrderSignature = ""
    @Volatile private var lastHandledTimestamp = 0L

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                "dev.saned.assistant.SETTINGS_UPDATE" -> {
                    applySettingsFromIntent(intent)
                }
                "dev.saned.assistant.ACTION_PING" -> {
                    respondToPing(intent)
                }
                "dev.saned.assistant.ACTION_TEST_API" -> {
                    performLiveApiProbe()
                }
                "dev.saned.assistant.ACTION_SIMULATE_ORDER" -> {
                    simulateTestOrder()
                }
            }
        }
    }

    private fun respondToPing(intent: Intent) {
        val app = appContext ?: currentActivity ?: return
        val pingTimestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())

        if (cachedAuthToken.isEmpty()) {
            readPersistedToken()
            if (cachedAuthToken.isEmpty()) {
                scanAllSharedPreferencesForTokens(app)
            }
        }

        try {
            val pongIntent = Intent("dev.saned.assistant.ACTION_PONG").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                putExtra("ping_timestamp", pingTimestamp)
                putExtra("pong_timestamp", System.currentTimeMillis())
                putExtra("hook_active", true)
                putExtra("has_auth_token", cachedAuthToken.isNotEmpty())
                putExtra("orders_count", loggedOrderIds.size)
                putExtra("api_host", cachedApiHost)
                putExtra("http_requests_count", httpRequestsCount.get())
                putExtra("polling_hits", pollingHits.get())
                putExtra("last_http_url", lastInterceptedUrl)
                putExtra("last_http_code", lastHttpResponseCode)
                putExtra("last_order_event", lastOrderEvent)
                putExtra("okhttp_hooked", isOkHttpHooked)
            }
            app.sendBroadcast(pongIntent)
        } catch (_: Throwable) {}

        try {
            val gPong = Intent("dev.saned.assistant.ACTION_PONG").apply {
                putExtra("ping_timestamp", pingTimestamp)
                putExtra("pong_timestamp", System.currentTimeMillis())
                putExtra("hook_active", true)
                putExtra("has_auth_token", cachedAuthToken.isNotEmpty())
                putExtra("orders_count", loggedOrderIds.size)
                putExtra("api_host", cachedApiHost)
                putExtra("http_requests_count", httpRequestsCount.get())
                putExtra("polling_hits", pollingHits.get())
                putExtra("last_http_url", lastInterceptedUrl)
                putExtra("last_http_code", lastHttpResponseCode)
                putExtra("last_order_event", lastOrderEvent)
                putExtra("okhttp_hooked", isOkHttpHooked)
            }
            app.sendBroadcast(gPong)
        } catch (_: Throwable) {}
    }

    private fun performLiveApiProbe() {
        val host = if (cachedApiHost.isNotEmpty()) cachedApiHost else "https://fleets.jahez.net"
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        val app = appContext ?: currentActivity ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val startTime = System.currentTimeMillis()
            var code = -1
            var snippet = ""
            var targetUrl = if (lastOrdersListUrl.isNotEmpty()) lastOrdersListUrl else "$host/api/fleets/orders/available"

            try {
                val reqBuilder = Request.Builder().url(targetUrl).get()
                if (token.isNotEmpty()) reqBuilder.header("Authorization", token)
                for ((k, v) in cachedHeaders) {
                    if (k != "Authorization" && k != "Content-Length") reqBuilder.header(k, v)
                }
                val resp = httpClient.newCall(reqBuilder.build()).execute()
                code = resp.code
                val rawBody = resp.body?.string() ?: ""
                resp.close()
                snippet = if (rawBody.length > 200) rawBody.substring(0, 200) + "..." else rawBody
            } catch (e: Throwable) {
                snippet = "Error: " + (e.message ?: e.javaClass.simpleName)
            }

            val latency = System.currentTimeMillis() - startTime
            val resultIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                putExtra("status_code", code)
                putExtra("latency_ms", latency)
                putExtra("url", targetUrl)
                putExtra("body_snippet", snippet)
                putExtra("has_token", token.isNotEmpty())
            }
            app.sendBroadcast(resultIntent)

            try {
                val gIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                    putExtra("status_code", code)
                    putExtra("latency_ms", latency)
                    putExtra("url", targetUrl)
                    putExtra("body_snippet", snippet)
                    putExtra("has_token", token.isNotEmpty())
                }
                app.sendBroadcast(gIntent)
            } catch (_: Throwable) {}
        }
    }

    private fun simulateTestOrder() {
        val testId = "#TEST" + (100..999).random()
        val testPrice = 18.5
        val testDist = 1.2
        val testStore = "Al Romansiah Restaurant"

        lastOrderEvent = "Simulated Order $testId received"
        sendOrderToLog(testId, testPrice, testDist, testStore, "Test Order Simulation 🧪")
        playAlertSound()
        currentActivity?.let { showToast(it, "🧪 Simulated Order $testId: Processing auto-accept pipeline...") }

        handleOrderViaApi(testId, testPrice, testDist, testStore)
    }

    fun initAppContext(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        // 1. Register high-speed settings & ping receiver
        try {
            val filter = IntentFilter().apply {
                addAction("dev.saned.assistant.SETTINGS_UPDATE")
                addAction("dev.saned.assistant.ACTION_PING")
                addAction("dev.saned.assistant.ACTION_TEST_API")
                addAction("dev.saned.assistant.ACTION_SIMULATE_ORDER")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(settingsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(settingsReceiver, filter)
            }
        } catch (_: Throwable) {}

        requestSettingsFromAssistant()
        loadFallbackConfigFile()
        readPersistedToken()
        scanAllSharedPreferencesForTokens(app)
        startScreenWatcher()
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

    fun persistToken(token: String) {
        if (token.isEmpty()) return
        try {
            val f = File("/data/local/tmp/saned_auth_token.txt")
            f.writeText(token.trim())
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    fun readPersistedToken(): String {
        try {
            val f = File("/data/local/tmp/saned_auth_token.txt")
            if (f.exists()) {
                val t = f.readText().trim()
                if (t.isNotEmpty()) {
                    cachedAuthToken = t
                    cachedHeaders["Authorization"] = t
                    return t
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    fun scanAllSharedPreferencesForTokens(context: Context) {
        if (cachedAuthToken.isNotEmpty()) return
        readPersistedToken()
        if (cachedAuthToken.isNotEmpty()) return

        val dataDir = context.applicationInfo?.dataDir ?: return
        val prefsDir = File(dataDir, "shared_prefs")
        if (prefsDir.exists() && prefsDir.isDirectory) {
            val files = prefsDir.listFiles() ?: return
            for (file in files) {
                if (cachedAuthToken.isNotEmpty()) break
                val name = file.nameWithoutExtension
                try {
                    val content = if (file.canRead()) file.readText() else ""
                    if (content.contains("__androidx_security_crypto")) {
                        tryDecryptEncryptedSharedPreferences(context, name)
                    } else {
                        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        for ((k, v) in sp.all) {
                            checkAndSetToken(v?.toString())
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun tryDecryptEncryptedSharedPreferences(context: Context, fileName: String) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            for ((k, v) in encPrefs.all) {
                checkAndSetToken(v?.toString())
            }
        } catch (_: Throwable) {}
    }

    fun checkAndSetToken(value: String?) {
        if (value.isNullOrEmpty()) return
        val str = value.trim()
        if (str.startsWith("Bearer ", ignoreCase = true)) {
            cachedAuthToken = str
            cachedHeaders["Authorization"] = str
            persistToken(str)
        } else if (str.startsWith("ey", ignoreCase = false) && str.contains(".") && str.length > 30) {
            val t = "Bearer $str"
            cachedAuthToken = t
            cachedHeaders["Authorization"] = t
            persistToken(t)
        } else if (str.length > 32 && str.matches(Regex("^[a-zA-Z0-9_\\-\\.]+$"))) {
            if (cachedAuthToken.isEmpty()) {
                val t = "Bearer $str"
                cachedAuthToken = t
                cachedHeaders["Authorization"] = t
                persistToken(t)
            }
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        hookOkHttp(module, classLoader)
        hookWebSocket(module, classLoader)
        hookSharedPreferences(module, classLoader)
        hookFirebase(module, classLoader)
        hookSQLite(module)
        hookCipher(module)
        hookJsonConstructors(module)
        hookDialogShow(module)
        hookUI(module, classLoader)
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader) {
        // Hook OkHttpClient.newCall(Request)
        try {
            val clientClass = Class.forName("okhttp3.OkHttpClient", false, classLoader)
            for (m in clientClass.declaredMethods) {
                if (m.name == "newCall" && m.parameterTypes.size == 1) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val req = chain.args.getOrNull(0)
                            if (req != null) {
                                captureRequestMetadata(req)
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // Hook RealCall execution bottlenecks
        val realCallClasses = listOf(
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall"
        )
        for (clsName in realCallClasses) {
            try {
                val cls = Class.forName(clsName, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (m.name == "execute" && m.parameterTypes.isEmpty()) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val resp = chain.proceed()
                                if (resp != null) {
                                    try { processHttpResponse(resp) } catch (_: Throwable) {}
                                }
                                return resp
                            }
                        })
                    }
                    if (m.name.startsWith("getResponseWithInterceptorChain")) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val resp = chain.proceed()
                                if (resp != null) {
                                    try { processHttpResponse(resp) } catch (_: Throwable) {}
                                }
                                return resp
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // Hook RealInterceptorChain.proceed(Request)
        try {
            val chainClass = Class.forName("okhttp3.internal.http.RealInterceptorChain", false, classLoader)
            for (m in chainClass.declaredMethods) {
                if (m.name == "proceed" && m.parameterTypes.size == 1) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val req = chain.args.getOrNull(0)
                            if (req != null) captureRequestMetadata(req)
                            val resp = chain.proceed()
                            if (resp != null) {
                                try { processHttpResponse(resp) } catch (_: Throwable) {}
                            }
                            return resp
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // Hook OkHttpClient$Builder.build()
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
                                        try { processHttpResponse(response) } catch (_: Throwable) {}
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
    }

    private fun captureRequestMetadata(request: Any) {
        try {
            httpRequestsCount.incrementAndGet()
            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request).toString()
            lastInterceptedUrl = url

            if (url.startsWith("http")) {
                val uri = java.net.URI(url)
                cachedApiHost = "${uri.scheme}://${uri.host}"

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
                checkAndSetToken(auth)
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
            val listenerClass = Class.forName("okhttp3.WebSocketListener", false, classLoader)
            for (m in listenerClass.methods) {
                if (m.name == "onMessage") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            for (arg in chain.args) {
                                when (arg) {
                                    is String -> if (arg.isNotEmpty()) findAndParseEmbeddedJson(arg, "WebSocket ⚡")
                                    is ByteArray -> if (arg.isNotEmpty()) findAndParseEmbeddedJson(String(arg, Charsets.UTF_8), "WebSocket (Binary) ⚡")
                                    else -> {
                                        try {
                                            val utf8Method = arg?.javaClass?.getMethod("utf8")
                                            val text = utf8Method?.invoke(arg) as? String
                                            if (!text.isNullOrEmpty()) findAndParseEmbeddedJson(text, "WebSocket (ByteString) ⚡")
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        try {
            val realWsClass = Class.forName("okhttp3.internal.ws.RealWebSocket", false, classLoader)
            for (m in realWsClass.declaredMethods) {
                if (m.name == "onReadMessage") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            for (arg in chain.args) {
                                if (arg is String && arg.isNotEmpty()) {
                                    findAndParseEmbeddedJson(arg, "WebSocket ⚡")
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookSharedPreferences(module: XposedModule, classLoader: ClassLoader) {
        try {
            val editorClass = Class.forName("android.content.SharedPreferences\$Editor")
            for (m in editorClass.declaredMethods) {
                if (m.name == "putString") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val value = chain.args.getOrNull(1) as? String
                            checkAndSetToken(value)
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        try {
            val spImplClass = Class.forName("android.app.SharedPreferencesImpl")
            for (m in spImplClass.declaredMethods) {
                if (m.name == "getString" && m.parameterTypes.size == 2) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            val s = result as? String
                            checkAndSetToken(s)
                            return result
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookFirebase(module: XposedModule, classLoader: ClassLoader) {
        try {
            val fmsClass = Class.forName("com.google.firebase.messaging.FirebaseMessagingService", false, classLoader)
            for (m in fmsClass.declaredMethods) {
                if (m.name == "onMessageReceived") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            try {
                                val remoteMessage = chain.args.getOrNull(0)
                                if (remoteMessage != null) {
                                    val getDataMethod = remoteMessage.javaClass.getMethod("getData")
                                    val dataMap = getDataMethod.invoke(remoteMessage) as? Map<*, *>
                                    if (dataMap != null && dataMap.isNotEmpty()) {
                                        val json = JSONObject()
                                        for ((k, v) in dataMap) {
                                            json.put(k.toString(), v.toString())
                                        }
                                        findAndParseEmbeddedJson(json.toString(), "FCM Push 📲")
                                    }
                                }
                            } catch (_: Throwable) {}
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookSQLite(module: XposedModule) {
        try {
            val dbClass = android.database.sqlite.SQLiteDatabase::class.java
            for (m in dbClass.declaredMethods) {
                if (m.name in listOf("insert", "insertWithOnConflict", "insertOrThrow")) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            try {
                                val table = chain.args.getOrNull(0) as? String ?: ""
                                val values = chain.args.getOrNull(2) as? android.content.ContentValues
                                if (values != null && (table.contains("order", ignoreCase = true) || 
                                                       table.contains("trip", ignoreCase = true) || 
                                                       table.contains("dispatch", ignoreCase = true))) {
                                    val json = JSONObject()
                                    for (key in values.keySet()) {
                                        json.put(key, values.get(key))
                                    }
                                    extractAndProcessOrderJson(json, "SQLite ($table) 💾")
                                }
                            } catch (_: Throwable) {}
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookCipher(module: XposedModule) {
        try {
            for (m in Cipher::class.java.declaredMethods) {
                if (m.name == "doFinal" && m.returnType == ByteArray::class.java) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            val bytes = result as? ByteArray
                            if (bytes != null && bytes.isNotEmpty()) {
                                try {
                                    val text = String(bytes, Charsets.UTF_8)
                                    findAndParseEmbeddedJson(text, "Decrypted (Cipher) 🔐")
                                } catch (_: Throwable) {}
                            }
                            return result
                        }
                    })
                }
            }
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
                        if ((lower.contains("order") || lower.contains("trip") || lower.contains("dispatch") || lower.contains("delivery")) &&
                            (lower.contains("id") || lower.contains("price") || lower.contains("status"))) {
                            findAndParseEmbeddedJson(str, "Memory JSON 🧠")
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
                        scanAllSharedPreferencesForTokens(act.applicationContext)
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
                        scanAllSharedPreferencesForTokens(act.applicationContext)
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

        for (root in roots.reversed()) {
            try {
                if (root.isShown && root.visibility == View.VISIBLE) {
                    val handled = findAndTriggerOrder(root, act, startTime)
                    if (handled) return
                }
            } catch (_: Throwable) {}
        }

        for (root in roots) {
            try {
                if (root.isShown && root.visibility == View.VISIBLE) {
                    val clicked = scanHomeScreenOrderCards(root, act)
                    if (clicked) return
                }
            } catch (_: Throwable) {}
        }
    }

    fun startActiveServerPolling() {
        if (!isPollingRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    if (isMasterRunning && isActivePolling) {
                        if (cachedAuthToken.isEmpty()) {
                            readPersistedToken()
                        }
                        if (cachedAuthToken.isNotEmpty()) {
                            pollServerForAvailableOrders()
                        }
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
                pollingHits.incrementAndGet()
                val reqBuilder = Request.Builder().url(url).get()
                reqBuilder.header("Authorization", token)
                for ((k, v) in cachedHeaders) {
                    if (k != "Authorization" && k != "Content-Length") {
                        reqBuilder.header(k, v)
                    }
                }
                val resp = httpClient.newCall(reqBuilder.build()).execute()
                val code = resp.code
                lastHttpResponseCode = code
                val body = resp.body?.string() ?: ""
                resp.close()

                if (code in 200..299 && body.isNotEmpty()) {
                    if (body.contains("order", ignoreCase = true) || body.contains("trip", ignoreCase = true)) {
                        findAndParseEmbeddedJson(body, "Server Polling (POLL) ⚡")
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

            val codeMethod = response.javaClass.getMethod("code")
            val code = codeMethod.invoke(response) as? Int ?: 200
            lastHttpResponseCode = code

            val urlMethod = request.javaClass.getMethod("url")
            val url = urlMethod.invoke(request).toString()

            var json = ""
            try {
                val peekBodyMethod = response.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType)
                val bodyCopy = peekBodyMethod.invoke(response, 1024L * 1024L)
                if (bodyCopy != null) {
                    val stringMethod = bodyCopy.javaClass.getMethod("string")
                    json = stringMethod.invoke(bodyCopy) as? String ?: ""
                }
            } catch (_: Throwable) {}

            if (json.isNotEmpty() && (url.contains("order", ignoreCase = true) || 
                                     url.contains("dispatch", ignoreCase = true) || 
                                     url.contains("fleet", ignoreCase = true) || 
                                     url.contains("trip", ignoreCase = true) || 
                                     json.contains("order", ignoreCase = true) || 
                                     json.contains("price", ignoreCase = true))) {
                findAndParseEmbeddedJson(json, "HTTP (OkHttp) 🌐")
            }
        } catch (_: Throwable) {}
    }

    fun findAndParseEmbeddedJson(text: String, source: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseOrderJson(trimmed, source)
            return
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            parseOrderJson(trimmed.substring(firstBrace, lastBrace + 1), source)
        }

        val firstBracket = trimmed.indexOf('[')
        val lastBracket = trimmed.lastIndexOf(']')
        if (firstBracket != -1 && lastBracket > firstBracket) {
            parseOrderJson(trimmed.substring(firstBracket, lastBracket + 1), source)
        }
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
                    lastOrderEvent = "Order $orderId detected via $channelSource"
                    sendOrderToLog(orderId, cleanPrice, dist, restaurant, channelSource)
                    if (isSoundEnabled) playAlertSound()

                    handleOrderViaApi(orderId, cleanPrice, dist, restaurant)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun handleOrderViaApi(orderId: String, price: Double, dist: Double, restaurant: String) {
        val rawId = orderId.replace("#", "").trim()
        if (rawId.isEmpty()) return

        var shouldReject = false
        var rejectReason = ""

        if (isAutoReject) {
            if (minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice) {
                shouldReject = true
                rejectReason = "Price (${String.format(Locale.US, "%.1f", price)} SAR) below minimum"
            } else if (maxDistToRestaurant > 0.0 && dist > 0.0 && dist > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "Distance to restaurant (${dist} km) exceeds limit"
            }
        }

        if (shouldReject) {
            lastOrderEvent = "Order $orderId Auto-Rejected: $rejectReason"
            currentActivity?.let { act -> showToast(act, "❌ Auto-Rejected Order $orderId: $rejectReason") }
            sendOrderToLog(orderId, price, dist, restaurant, "Auto-Rejected ❌ ($rejectReason)")
            executeDirectApiCall(rawId, "REJECT")
            return
        }

        if (isDryRun) {
            lastOrderEvent = "Order $orderId evaluated (Dry-Run: would accept)"
            currentActivity?.let { act -> showToast(act, "🔍 [Dry-Run]: Would accept Order $orderId (API)") }
            sendOrderToLog(orderId, price, dist, restaurant, "Dry-Run (Would Accept) 🔍")
            return
        }

        if (isAutoAccept || isMasterRunning) {
            lastOrderEvent = "Auto-Accepting Order $orderId..."
            currentActivity?.let { act -> showToast(act, "⚡ Auto-Accepting Order $orderId via direct API...") }
            sendOrderToLog(orderId, price, dist, restaurant, "Auto-Accepted ✅ (API)")
            executeDirectApiCall(rawId, "ACCEPT")
            playAlertSound()
        }
    }

    private fun executeDirectApiCall(orderIdNum: String, action: String) {
        val host = if (cachedApiHost.isNotEmpty()) cachedApiHost else "https://fleets.jahez.net"
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        val burstCount = if (action == "ACCEPT") parallelRequests.coerceIn(1, 5) else 1

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoints = if (action == "ACCEPT") {
                    listOf(
                        "$host/api/fleets/orders/$orderIdNum/accept",
                        "$host/api/driver/orders/$orderIdNum/accept",
                        "$host/api/orders/$orderIdNum/accept",
                        "$host/api/v1/orders/$orderIdNum/accept",
                        "$host/api/v2/fleets/orders/$orderIdNum/accept"
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
                            for (method in listOf("POST", "PUT")) {
                                try {
                                    val reqBuilder = Request.Builder().url(url)
                                    if (method == "POST") reqBuilder.post(body) else reqBuilder.put(body)

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
                                        return@launch
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun sendOrderToLog(orderId: String, price: Double, dist: Double, restaurant: String, status: String) {
        val ctx = appContext ?: currentActivity

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

        try {
            val f = File("/data/local/tmp/saned_orders.json")
            val existing = if (f.exists()) f.readText() else "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

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
        val clickableCandidates = mutableListOf<View>()

        fun traverse(v: View) {
            val desc = v.contentDescription?.toString() ?: ""
            val text = (v as? TextView)?.text?.toString() ?: ""

            if (text.isNotEmpty()) allTexts.add(text)
            if (desc.isNotEmpty()) allTexts.add(desc)

            val combined = (text + " " + desc).trim().lowercase()

            if (v.isClickable || v.width > 180) {
                clickableCandidates.add(v)
            }

            if (combined.contains("accept") || combined.contains("قبول") || 
                combined.contains("تأكيد") || combined.contains("confirm") || 
                combined.contains("slide") || combined.contains("swipe") ||
                combined.contains(">>") || combined.contains("اسحب")) {
                if (acceptView == null || combined.contains("accept")) {
                    acceptView = v
                }
            }

            if (combined.equals("reject") || combined.equals("رفض") || combined.equals("dismiss") || combined.equals("تجاهل")) {
                rejectView = v
            }

            if (combined.equals("confirm") || combined.equals("yes") ||
                combined.equals("تأكيد") || combined.equals("نعم")) {
                confirmDialogBtn = v
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverse(v.getChildAt(i))
                }
            }
        }

        traverse(root)

        if (confirmDialogBtn != null && confirmDialogBtn!!.isShown) {
            triggerClick(confirmDialogBtn!!)
        }

        for (raw in allTexts) {
            val t = normalizeArabicNumerals(raw).trim()
            if (t.contains("New Order", ignoreCase = true) || t.contains("طلب جديد", ignoreCase = true) ||
                t.contains("New Offer", ignoreCase = true) || t.contains("عرض جديد", ignoreCase = true) ||
                t.contains("Pick-up", ignoreCase = true) || t.contains("استلام", ignoreCase = true)) {
                isNewOrderScreen = true
            }

            val mDistRest = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*You|كم\s*منك)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistRest != null && distToRestaurant == 0.0) {
                distToRestaurant = mDistRest.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            val mDistCust = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*Pickup|كم\s*من\s*نقطة\s*الاستلام)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistCust != null && distToCustomer == 0.0) {
                distToCustomer = mDistCust.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            val mStore = Regex("""(?:Pick-up from|استلام من)\s*([^,\n]+)""", RegexOption.IGNORE_CASE).find(t)
            if (mStore != null && storeName.isEmpty()) {
                storeName = mStore.groupValues[1].trim()
            }

            if (!t.contains(".") && !t.contains("Km", ignoreCase = true)) {
                val mId = Regex("""#?\s*(\d{5,12})""").find(t)
                if (mId != null && orderId.isEmpty()) {
                    orderId = "#" + mId.groupValues[1]
                }
            }

            if (!t.contains("From You", ignoreCase = true) && !t.contains("Pickup", ignoreCase = true) && 
                !t.contains("منك", ignoreCase = true) && orderPrice == 0.0) {
                
                val mPrice = Regex("""(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)?\s*(\d+(?:\.\d+)?)\s*(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)?""", RegexOption.IGNORE_CASE).find(t)
                if (mPrice != null) {
                    val p = mPrice.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (p in 1.0..500.0 && p != distToRestaurant && p != distToCustomer) {
                        orderPrice = p
                    }
                }
            }
        }

        // Fail-safe: If acceptView is null but an order popup is detected, pick the bottom-most clickable candidate
        if (acceptView == null && (isNewOrderScreen || orderPrice > 0.0) && clickableCandidates.isNotEmpty()) {
            acceptView = clickableCandidates.maxByOrNull {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                loc[1]
            }
        }

        if (orderId.isEmpty()) {
            orderId = if (storeName.isNotEmpty()) "#$storeName" else "#" + (100000..999999).random()
        }

        if (acceptView != null && (isNewOrderScreen || acceptView!!.isShown || orderPrice > 0.0)) {
            val signature = "$orderId-$orderPrice-$distToRestaurant"
            val now = System.currentTimeMillis()
            if (signature == lastHandledOrderSignature && (now - lastHandledTimestamp) < 3000L) {
                return true
            }

            lastHandledOrderSignature = signature
            lastHandledTimestamp = now

            if (loggedOrderIds.add(orderId)) {
                val cleanPrice = String.format(Locale.US, "%.2f", orderPrice).toDoubleOrNull() ?: orderPrice
                lastOrderEvent = "Order $orderId detected on screen ($cleanPrice SAR, $distToRestaurant km)"
                sendOrderToLog(orderId, cleanPrice, distToRestaurant, storeName, "Screen Popup 📱")
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

            if (combined.equals("New", ignoreCase = true) || combined.equals("جديد", ignoreCase = true) ||
                combined.contains("طلب متاح", ignoreCase = true)) {
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
                lastOrderEvent = "Order $foundOrderId available in orders list"
                sendOrderToLog(foundOrderId, 0.0, 0.0, foundStore, "Order List Card 📋")
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
        var shouldReject = false
        var rejectReason = ""

        if (isAutoReject) {
            if (minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice) {
                shouldReject = true
                rejectReason = "Price (${String.format(Locale.US, "%.1f", price)} SAR) below minimum (${minOrderPrice} SAR)"
            } else if (maxDistToRestaurant > 0.0 && distRest > 0.0 && distRest > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "Distance to restaurant (${distRest} km) exceeds limit (${maxDistToRestaurant} km)"
            } else if (maxDistCustomer > 0.0 && distCust > 0.0 && distCust > maxDistCustomer) {
                shouldReject = true
                rejectReason = "Customer distance (${distCust} km) exceeds limit (${maxDistCustomer} km)"
            }
        }

        if (shouldReject) {
            lastOrderEvent = "Order $orderId auto-rejected ($rejectReason)"
            showToast(act, "❌ Auto-Rejected Order $orderId: $rejectReason")
            sendOrderToLog(orderId, price, distRest, "", "Auto-Rejected ❌ ($rejectReason)")
            if (btnReject != null) {
                triggerClick(btnReject)
            }
            executeDirectApiCall(orderId.replace("#", ""), "REJECT")
            return
        }

        if (isDryRun) {
            lastOrderEvent = "Order $orderId dry-run (would accept)"
            showToast(act, "🔍 [Dry-Run]: Would accept Order $orderId")
            sendOrderToLog(orderId, price, distRest, "", "Dry-Run (Would Accept) 🔍")
            return
        }

        if (isAutoAccept || isMasterRunning) {
            if (isAccepting.compareAndSet(false, true)) {
                val latency = System.currentTimeMillis() - startTime
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        simulateSwipe(btnAccept, dialogRoot, act)
                        executeDirectApiCall(orderId.replace("#", ""), "ACCEPT")

                        lastOrderEvent = "Order $orderId accepted successfully in ${latency}ms"
                        showToast(act, "⚡ Accepted Order $orderId in ${latency}ms!")
                        sendOrderToLog(orderId, price, distRest, "", "Accepted Successfully ✅ (${latency}ms)")
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

        val displayMetrics = activity.resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val viewW = targetView.width.toFloat()
        val viewH = targetView.height.toFloat()

        val startScreenX: Float
        val endScreenX: Float
        val screenY: Float

        if (viewW > 200f && location[0] >= 0) {
            startScreenX = location[0].toFloat() + 60f
            endScreenX = location[0].toFloat() + viewW - 50f
            screenY = location[1].toFloat() + (viewH / 2f)
        } else {
            startScreenX = 140f
            endScreenX = screenW - 140f
            screenY = screenH * 0.88f
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val downTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startScreenX, screenY, 0)
                activity.dispatchTouchEvent(downEvent)
                downEvent.recycle()

                val steps = 25
                for (i in 1..steps) {
                    val eventTime = downTime + (i * 7L)
                    val curX = startScreenX + (endScreenX - startScreenX) * (i.toFloat() / steps.toFloat())
                    val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, curX, screenY, 0)
                    activity.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                }

                val upTime = downTime + (steps * 7L) + 15L
                val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endScreenX, screenY, 0)
                activity.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            } catch (_: Throwable) {}
        }

        triggerClick(view)
        triggerClick(targetView)

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

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build()
        } catch (_: Throwable) {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build()
        }
    }
}
