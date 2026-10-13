package dev.saned.assistant

import android.app.Activity
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
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
    @Volatile var isOkHttpHooked: Boolean = false

    // Network & Session Cache
    @Volatile var cachedAuthToken: String = ""
    @Volatile var cachedApiHost: String = ""
    private val cachedHeaders = ConcurrentHashMap<String, String>()

    // Candidate hosts verified for Jahez platform
    val candidateApiHosts = listOf(
        "https://api.jahez.net"
    )

    @Volatile var capturedAppHttpClient: OkHttpClient? = null

    fun buildCandidateUrl(baseOrFull: String, endpointPath: String): String {
        val clean = baseOrFull.trim().removeSuffix("/")
        if (clean.contains("/api/fleets/") || clean.contains("/api/orders/") || clean.contains("/api/driver/")) {
            return clean
        }
        val uri = try { java.net.URI(clean) } catch (_: Throwable) { null }
        val base = if (uri != null && !uri.host.isNullOrEmpty()) {
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portPart"
        } else {
            clean
        }
        val cleanPath = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
        return "$base$cleanPath"
    }

    fun applyJahezHeaders(reqBuilder: Request.Builder, token: String) {
        if (token.isNotEmpty()) {
            val authVal = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
            reqBuilder.header("Authorization", authVal)
        }
        val osRel = Build.VERSION.RELEASE ?: "14"
        val devMod = Build.MODEL ?: "Device"
        val bldId = Build.ID ?: "BUILD"
        val defaultUa = "Jahez/6.1.0 (Linux; U; Android " + osRel + "; " + devMod + "; Build/" + bldId + ") okhttp/4.12.0"
        val ua = cachedHeaders["User-Agent"] ?: defaultUa
        reqBuilder.header("User-Agent", ua)
        reqBuilder.header("Accept", "application/json, text/plain, */*")
        reqBuilder.header("Accept-Language", cachedHeaders["accept-language"] ?: "ar-SA,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        reqBuilder.header("Connection", "keep-alive")
        reqBuilder.header("x-app-version", "6.1.0")
        reqBuilder.header("x-platform", "android")
        reqBuilder.header("x-device-type", "mobile")

        for ((k, v) in cachedHeaders) {
            val lower = k.lowercase()
            if (lower != "authorization" && lower != "content-length" && lower != "user-agent" && lower != "host") {
                reqBuilder.header(k, v)
            }
        }
    }

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

    fun onActivityCreated(activity: Activity) {
        currentActivity = activity
        try {
            val intent = activity.intent
            if (intent != null) {
                extractAndProcessOrderFromIntent(intent, activity)
            }
        } catch (_: Throwable) {}

        CoroutineScope(Dispatchers.IO).launch {
            try {
                scanJahezDatabases(activity)
            } catch (_: Throwable) {}
        }

        activity.runOnUiThread {
            try {
                scanAllRoots()
            } catch (_: Throwable) {}
        }
    }

    private fun extractAndProcessOrderFromIntent(intent: Intent, activity: Activity) {
        try {
            val extras = intent.extras ?: return

            // 1. Check for embedded JSON strings in any extra
            for (k in extras.keySet()) {
                val v = extras.get(k) ?: continue
                val str = v.toString().trim()
                if (str.startsWith("{") && str.endsWith("}")) {
                    try {
                        val json = JSONObject(str)
                        extractAndProcessOrderJson(json, "Activity Intent ()")
                    } catch (_: Throwable) {}
                }
            }

            // 2. Direct bundle field extraction matching Ls20 / Jahez order intent
            var foundId = ""
            var foundPrice = 0.0
            var foundDist = 0.0
            var foundCustDist = 0.0
            var foundStore = ""
            var foundCustomer = ""
            var foundPhone = ""
            var foundAddress = ""
            var foundCash = 0.0

            for (k in extras.keySet()) {
                val lk = k.lowercase()
                val v = extras.get(k) ?: continue
                val strVal = v.toString().trim()

                if (foundId.isEmpty() && (lk == "order_id" || lk == "orderid" || lk == "id" || lk == "code" || lk == "trip_id" || lk == "tripid" || lk == "ordernumber" || lk == "order_number")) {
                    val digits = strVal.filter { it.isDigit() }
                    if (digits.length in 5..12) foundId = "#"
                }

                if (foundPrice == 0.0 && (lk.contains("price") || lk.contains("fee") || lk.contains("cost") || lk.contains("earning") || lk.contains("amount") || lk == "total")) {
                    val p = when (v) {
                        is Number -> v.toDouble()
                        else -> strVal.toDoubleOrNull() ?: 0.0
                    }
                    if (p in 1.0..500.0) foundPrice = p
                }

                if (foundDist == 0.0 && (lk.contains("distance") || lk.contains("km") || lk.contains("dist"))) {
                    val d = when (v) {
                        is Number -> v.toDouble()
                        else -> strVal.toDoubleOrNull() ?: 0.0
                    }
                    if (d in 0.1..100.0) {
                        if (lk.contains("customer") || lk.contains("dropoff")) foundCustDist = d else foundDist = d
                    }
                }

                if (foundStore.isEmpty() && (lk.contains("store") || lk.contains("restaurant") || lk.contains("shop") || lk.contains("vendor"))) {
                    if (strVal.length > 1 && !strVal.startsWith("{") && !strVal.startsWith("[")) {
                        foundStore = strVal
                    }
                }

                if (foundCustomer.isEmpty() && (lk.contains("customer") || lk.contains("client") || lk.contains("recipient")) && !lk.contains("phone") && !lk.contains("address")) {
                    if (strVal.length > 1 && !strVal.startsWith("{")) foundCustomer = strVal
                }

                if (foundPhone.isEmpty() && (lk.contains("phone") || lk.contains("mobile"))) {
                    val digits = strVal.filter { it.isDigit() }
                    if (digits.length in 9..14) foundPhone = digits
                }

                if (foundAddress.isEmpty() && (lk.contains("address") || lk.contains("location") || lk.contains("dropoff"))) {
                    if (strVal.length > 3 && !strVal.startsWith("{")) foundAddress = strVal
                }

                if (foundCash == 0.0 && (lk.contains("cash") || lk.contains("cod"))) {
                    val c = when (v) {
                        is Number -> v.toDouble()
                        else -> strVal.toDoubleOrNull() ?: 0.0
                    }
                    if (c > 0.0) foundCash = c
                }
            }

            if (foundId.isNotEmpty() || (foundPrice > 0.0 && foundStore.isNotEmpty())) {
                if (foundId.isEmpty()) foundId = if (foundStore.isNotEmpty()) "#" else return
                if (loggedOrderIds.add(foundId)) {
                    val cleanPrice = String.format(Locale.US, "%.2f", foundPrice).toDoubleOrNull() ?: foundPrice
                    lastOrderEvent = "Order  detected via Activity Intent"
                    sendOrderToLog(
                        orderId = foundId,
                        price = cleanPrice,
                        dist = foundDist,
                        restaurant = foundStore,
                        status = "Activity Intent (Accepted ✅)",
                        customerName = foundCustomer,
                        customerPhone = foundPhone,
                        address = foundAddress,
                        cashAmount = foundCash,
                        currency = "SAR",
                        reference = foundId.replace("#", "")
                    )
                    if (isSoundEnabled) playAlertSound()
                    handleOrderViaApi(foundId, cleanPrice, foundDist, foundStore)
                }
            }
        } catch (_: Throwable) {}
    }

    fun onDialogShown(dialog: Dialog) {
        val act = currentActivity ?: dialog.ownerActivity
        if (act != null) {
            act.runOnUiThread {
                try {
                    scanAllRoots()
                } catch (_: Throwable) {}
            }
        } else {
            mainHandler.post {
                try {
                    scanAllRoots()
                } catch (_: Throwable) {}
            }
        }
        if (failedPollingCount < 100) failedPollingCount++
    }

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
                "dev.saned.assistant.ACTION_CLEAR_TOKEN" -> {
                    clearPersistedToken()
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
        if (cachedApiHost.isEmpty()) {
            readPersistedHost()
        }

        try {
            val pongIntent = Intent("dev.saned.assistant.ACTION_PONG").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                putExtra("ping_timestamp", pingTimestamp)
                putExtra("pong_timestamp", System.currentTimeMillis())
                putExtra("hook_active", true)
                putExtra("has_auth_token", cachedAuthToken.isNotEmpty())
                putExtra("orders_count", loggedOrderIds.size)
                putExtra("api_host", if (cachedApiHost.isNotEmpty()) cachedApiHost else "Auto-Detecting")
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
                putExtra("api_host", if (cachedApiHost.isNotEmpty()) cachedApiHost else "Auto-Detecting")
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
        val app = appContext ?: currentActivity ?: return
        var token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        if (token.isEmpty() && appContext != null) {
            val scanned = scanJahezSharedPreferencesForToken(appContext!!)
            if (scanned.isNotEmpty()) token = scanned
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (token.isEmpty()) {
                val hint = "Waiting for Live Session Token ⏳\n\n👉 Please open Jahez Fleets app, go to Orders tab and pull down to refresh. Saned Assistant will automatically capture your active session token from live network traffic!"
                val noTokenIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                    component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                    putExtra("status_code", 0)
                    putExtra("latency_ms", 0L)
                    putExtra("url", "https://api.jahez.net/api/fleets/orders/available")
                    putExtra("body_snippet", hint)
                    putExtra("has_token", false)
                }
                app.sendBroadcast(noTokenIntent)
                try {
                    val gIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                        putExtra("status_code", 0)
                        putExtra("latency_ms", 0L)
                        putExtra("url", "https://api.jahez.net/api/fleets/orders/available")
                        putExtra("body_snippet", hint)
                        putExtra("has_token", false)
                    }
                    app.sendBroadcast(gIntent)
                } catch (_: Throwable) {}
                return@launch
            }
            val startTime = System.currentTimeMillis()
            var code = -1
            var snippet = ""
            var resolvedHost = ""
            var targetUrl = ""

            val candidateEndpoints = listOf(
                "/api/fleets/orders/available",
                "/api/v1/fleets/orders/available",
                "/api/fleets/driver/profile",
                "/api/fleets/profile",
                "/api/fleets/orders"
            )

            // Build list of hosts to probe (strictly validated for jahez.net)
            val probeHosts = mutableListOf<String>()
            if (lastOrdersListUrl.isNotEmpty() && isValidJahezHost(lastOrdersListUrl)) probeHosts.add(lastOrdersListUrl)
            if (cachedApiHost.isNotEmpty() && isValidJahezHost(cachedApiHost)) probeHosts.add(cachedApiHost)
            probeHosts.add("https://api.jahez.net")

            val clientToUse = capturedAppHttpClient ?: httpClient
            var successFound = false

            for (h in probeHosts.distinct()) {
                if (successFound) break
                for (endpoint in candidateEndpoints) {
                    val candidateUrl = buildCandidateUrl(h, endpoint)
                    targetUrl = candidateUrl
                    try {
                        val reqBuilder = Request.Builder().url(candidateUrl).get()
                        applyJahezHeaders(reqBuilder, token)

                        val resp = clientToUse.newCall(reqBuilder.build()).execute()
                        val currentCode = resp.code
                        val rawBody = resp.body?.string() ?: ""
                        resp.close()

                        val isCloudflareBlock = (currentCode == 403 || currentCode == 503) &&
                            (rawBody.contains("Just a moment", ignoreCase = true) || rawBody.contains("cloudflare", ignoreCase = true))

                        if (currentCode in 200..299 || (currentCode in 400..499 && !isCloudflareBlock && (rawBody.trim().startsWith("{") || rawBody.trim().startsWith("[")))) {
                            code = currentCode
                            targetUrl = candidateUrl
                            val uri = java.net.URI(candidateUrl)
                            resolvedHost = "${uri.scheme}://${uri.host}"
                            cachedApiHost = resolvedHost
                            persistHost(resolvedHost)
                            lastOrdersListUrl = candidateUrl
                            if (currentCode == 401 && (rawBody.contains("invalid_token") || rawBody.contains("unauthorized", ignoreCase = true))) {
                                clearPersistedToken()
                                snippet = "Invalid/Expired Token: " + rawBody + " -- Stale token cleared! Open Jahez Fleets app & pull to refresh to capture active token."
                            } else {
                                snippet = if (rawBody.length > 250) rawBody.substring(0, 250) + "..." else rawBody
                            }
                            successFound = true
                            break
                        } else if (!isCloudflareBlock && currentCode > 0) {
                            if (code <= 0 || code == 403 || currentCode == 404) {
                                code = currentCode
                                targetUrl = candidateUrl
                                if (currentCode == 404 && rawBody.contains("Tomcat", ignoreCase = true)) {
                                    snippet = "Connected to Jahez Server (Apache Tomcat) 🟢\n\n• Server Response: HTTP 404 (Test path not routed)\n• Network Hook: Active & Ready\n\n👉 Next Step: Open Jahez Fleets app and pull down to refresh on Orders tab. Saned Assistant will automatically capture the exact production order endpoint!"
                                } else {
                                    snippet = if (rawBody.length > 250) rawBody.substring(0, 250) + "..." else rawBody
                                }
                            }
                        } else if (isCloudflareBlock) {
                            if (snippet.isEmpty() || snippet.startsWith("Error:")) {
                                code = currentCode
                                targetUrl = candidateUrl
                                snippet = "Cloudflare WAF (HTTP 403) detected on " + candidateUrl + ". Note: In-App order interceptor & Screen watcher operate natively inside Jahez and bypass Cloudflare."
                            }
                        }
                    } catch (e: Throwable) {
                        if (snippet.isEmpty()) {
                            snippet = "Error: " + (e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }

            val latency = System.currentTimeMillis() - startTime
            val resultIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                putExtra("status_code", code)
                putExtra("latency_ms", latency)
                putExtra("url", if (targetUrl.isNotEmpty()) targetUrl else resolvedHost)
                putExtra("body_snippet", snippet)
                putExtra("has_token", token.isNotEmpty())
            }
            app.sendBroadcast(resultIntent)

            try {
                val gIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
                    putExtra("status_code", code)
                    putExtra("latency_ms", latency)
                    putExtra("url", if (targetUrl.isNotEmpty()) targetUrl else resolvedHost)
                    putExtra("body_snippet", snippet)
                    putExtra("has_token", token.isNotEmpty())
                }
                app.sendBroadcast(gIntent)
            } catch (_: Throwable) {}
        }
    }

    fun simulateTestOrder() {
        val testNum = (100000..999999).random()
        val testRef = "JH-$testNum"
        val testId = "#$testNum"
        val testPrice = 24.50
        val testDist = 2.8
        val testStore = "شاورما كلاسك (Al Classic Shawarma)"
        val testCustomer = "محمد العتيبي (Mohammed)"
        val testPhone = "0501234567"
        val testAddress = "حي الياسمين، شارع أنس بن مالك، الرياض"
        val testCash = 55.00
        val testStatus = "Accepted (Auto-Accept ⚡)"

        lastOrderEvent = "Simulated Order $testId received"
        sendOrderToLog(
            orderId = testId,
            price = testPrice,
            dist = testDist,
            restaurant = testStore,
            status = testStatus,
            customerName = testCustomer,
            customerPhone = testPhone,
            address = testAddress,
            cashAmount = testCash,
            currency = "SAR",
            reference = testRef
        )
        playAlertSound()
        currentActivity?.let { showToast(it, "🧪 Simulated Order : Processing auto-accept pipeline...") }

        handleOrderViaApi(testId, testPrice, testDist, testStore)
    }

    
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Acquire WakeLock matching Luc1.a from SanedAssistant:
     * Tag: sanedhook:keepalive
     * Keeps CPU and Jahez socket permanently active without Doze Mode timeout
     */
    fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sanedhook:keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
    }

    fun initAppContext(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        scanJahezSharedPreferencesForToken(app)

        try {
            val filter = IntentFilter().apply {
                addAction("dev.saned.assistant.SETTINGS_UPDATE")
                addAction("dev.saned.assistant.ACTION_PING")
                addAction("dev.saned.assistant.ACTION_TEST_API")
                addAction("dev.saned.assistant.ACTION_SIMULATE_ORDER")
                addAction("dev.saned.assistant.ACTION_CLEAR_TOKEN")
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
        readPersistedHost()
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
                    extractHostFromJwt(t)
                    return t
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    fun isValidJahezHost(host: String): Boolean {
        return try {
            val uri = java.net.URI(host)
            val h = uri.host?.lowercase() ?: ""
            h == "api.jahez.net" || h.endsWith(".jahez.net")
        } catch (_: Throwable) {
            false
        }
    }

    fun persistHost(host: String) {
        if (!isValidJahezHost(host)) return
        try {
            val f = File("/data/local/tmp/saned_api_host.txt")
            f.writeText(host.trim())
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    fun readPersistedHost(): String {
        try {
            val f = File("/data/local/tmp/saned_api_host.txt")
            if (f.exists()) {
                val h = f.readText().trim()
                if (isValidJahezHost(h)) {
                    cachedApiHost = h
                    return h
                } else {
                    f.delete()
                }
            }
        } catch (_: Throwable) {}
        cachedApiHost = "https://api.jahez.net"
        return cachedApiHost
    }

    private fun extractHostFromJwt(token: String) {
        try {
            val clean = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
            val parts = clean.split(".")
            if (parts.size >= 2) {
                val payloadB64 = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
                val jsonStr = String(Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                val json = JSONObject(jsonStr)

                val iss = json.optString("iss", "")
                val aud = json.optString("aud", "")
                for (candidate in listOf(iss, aud)) {
                    if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                        val uri = java.net.URI(candidate)
                        cachedApiHost = "${uri.scheme}://${uri.host}"
                        persistHost(cachedApiHost)
                        break
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun scanAllSharedPreferencesForTokens(context: Context) {
        readPersistedToken()
        readPersistedHost()

        val dataDir = context.applicationInfo?.dataDir ?: return
        val prefsDir = File(dataDir, "shared_prefs")
        if (prefsDir.exists() && prefsDir.isDirectory) {
            val files = prefsDir.listFiles() ?: return
            for (file in files) {
                val name = file.nameWithoutExtension
                try {
                    val content = if (file.canRead()) file.readText() else ""
                    if (content.contains("__androidx_security_crypto")) {
                        tryDecryptEncryptedSharedPreferences(context, name)
                    } else {
                        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        for ((k, v) in sp.all) {
                            val strVal = v?.toString() ?: ""
                            checkAndSetToken(strVal, k)
                            if (strVal.startsWith("http://") || strVal.startsWith("https://")) {
                                if (strVal.contains("jahez.net")) {
                                    val uri = java.net.URI(strVal)
                                    val h = "${uri.scheme}://${uri.host}"
                                    if (isValidJahezHost(h)) {
                                        cachedApiHost = h
                                        persistHost(cachedApiHost)
                                    }
                                }
                            }
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
                val strVal = v?.toString() ?: ""
                checkAndSetToken(strVal, k)
                if (strVal.startsWith("http://") || strVal.startsWith("https://")) {
                    if (strVal.contains("jahez") || strVal.contains("api") || strVal.contains("fleet")) {
                        val uri = java.net.URI(strVal)
                        cachedApiHost = "${uri.scheme}://${uri.host}"
                        persistHost(cachedApiHost)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun clearPersistedToken() {
        try {
            File("/data/local/tmp/saned_auth_token.txt").delete()
        } catch (_: Throwable) {}
        cachedAuthToken = ""
        cachedHeaders.remove("Authorization")
    }

    fun checkAndSetToken(value: String?, key: String? = null) {
        if (value.isNullOrEmpty()) return
        val str = value.trim()

        // 1. Direct Bearer header (highest priority, verified from live network call)
        if (str.startsWith("Bearer ", ignoreCase = true)) {
            val tokenPart = str.substring(7).trim()
            if (tokenPart.length > 20) {
                cachedAuthToken = str
                cachedHeaders["Authorization"] = str
                persistToken(str)
                extractHostFromJwt(str)
                return
            }
        }

        // 2. JWT token format (starts with ey, contains dots, length > 40)
        if (str.startsWith("ey", ignoreCase = false) && str.contains(".") && str.length > 40) {
            val t = "Bearer $str"
            cachedAuthToken = t
            cachedHeaders["Authorization"] = t
            persistToken(t)
            extractHostFromJwt(str)
            return
        }

        // 3. Key-based token filtering for SharedPreferences
        if (key != null) {
            val lowerKey = key.lowercase()
            val isExcluded = lowerKey.contains("device") || lowerKey.contains("install") ||
                    lowerKey.contains("fcm") || lowerKey.contains("firebase") ||
                    lowerKey.contains("admob") || lowerKey.contains("analytics") ||
                    lowerKey.contains("fingerprint") || lowerKey.contains("signature") ||
                    lowerKey.contains("push") || lowerKey.contains("notification") ||
                    lowerKey.contains("hash") || lowerKey.contains("id")

            val isTokenKey = lowerKey.contains("token") || lowerKey.contains("auth") ||
                    lowerKey.contains("access") || lowerKey.contains("jwt") ||
                    lowerKey.contains("bearer") || lowerKey.contains("session")

            if (isTokenKey && !isExcluded && str.length in 20..2048 && str.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                if (cachedAuthToken.isEmpty() || !cachedAuthToken.contains("ey")) {
                    val t = "Bearer $str"
                    cachedAuthToken = t
                    cachedHeaders["Authorization"] = t
                    persistToken(t)
                }
            }
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        var hookCount = 0

        // 1. Hook OkHttpClient across all possible class names
        hookCount += hookOkHttp(module, classLoader)

        // 2. WebSocket Listeners & Readers
        hookWebSocket(module, classLoader)

        // 3. SharedPreferences Editor & getString hooks
        hookSharedPreferences(module, classLoader)

        // 4. Firebase Cloud Messaging (FCM Push Notifications)
        hookFirebase(module, classLoader)

        // 5. SQLite Database (Room & raw SQLite order inserts)
        hookSQLite(module)

        // 6. Hook javax.crypto.Cipher.doFinal
        hookCipher(module)

        // 7. Universal JSON constructors
        hookJsonConstructors(module)

        // 8. Dialog show & UI Lifecycle
        hookDialogShow(module)
        hookUI(module, classLoader)

        isOkHttpHooked = (hookCount > 0)
    }

    private fun hookOkHttp(module: XposedModule, classLoader: ClassLoader): Int {
        var hooksInstalled = 0

        // Hook OkHttpClient.newCall(Request)
        try {
            val clientClass = Class.forName("okhttp3.OkHttpClient", false, classLoader)
            for (m in clientClass.declaredMethods) {
                if (m.name == "newCall" && m.parameterTypes.size == 1) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val client = chain.thisObject as? OkHttpClient
                            if (client != null) {
                                capturedAppHttpClient = client
                            }
                            val req = chain.args.getOrNull(0)
                            if (req != null) {
                                captureRequestMetadata(req)
                            }
                            return chain.proceed()
                        }
                    })
                    hooksInstalled++
                }
            }
        } catch (_: Throwable) {}

        // Hook RealCall execution bottlenecks & async enqueue
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
                        hooksInstalled++
                    }
                    if (m.name == "enqueue" && m.parameterTypes.size == 1) {
                        try {
                            val callbackClass = Class.forName("okhttp3.Callback", false, classLoader)
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    try {
                                        val callObj = chain.thisObject
                                        val mReq = callObj.javaClass.getMethod("request")
                                        val req = mReq.invoke(callObj)
                                        if (req != null) captureRequestMetadata(req)
                                    } catch (_: Throwable) {}

                                    val originalCb = chain.args.getOrNull(0)
                                    if (originalCb != null) {
                                        val proxyCb = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { _, method, args ->
                                            if (method.name == "onResponse" && args != null && args.size >= 2) {
                                                val resp = args[1]
                                                if (resp != null) {
                                                    try { processHttpResponse(resp) } catch (_: Throwable) {}
                                                }
                                            }
                                            method.invoke(originalCb, *(args ?: emptyArray()))
                                        }
                                        chain.args[0] = proxyCb
                                    }
                                    return chain.proceed()
                                }
                            })
                            hooksInstalled++
                        } catch (_: Throwable) {}
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
                        hooksInstalled++
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
                    hooksInstalled++
                }
            }
        } catch (_: Throwable) {}

        // Hook OkHttpClient.build()
        try {
            val builderClass = Class.forName("okhttp3.OkHttpClient" + '$' + "Builder", false, classLoader)
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
            hooksInstalled++
        } catch (_: Throwable) {}

        return hooksInstalled
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
                persistHost(cachedApiHost)

                val lowerUrl = url.lowercase()
                if (lowerUrl.contains("order") || lowerUrl.contains("fleet") || lowerUrl.contains("dispatch")) {
                    if (!lowerUrl.contains("/accept") && !lowerUrl.contains("/reject")) {
                        lastOrdersListUrl = url
                    }
                }
            }

            val headersMethod = request.javaClass.getMethod("headers")
            val headersObj = headersMethod.invoke(request)
            try {
                val sizeMethod = headersObj.javaClass.getMethod("size")
                val nameMethod = headersObj.javaClass.getMethod("name", Int::class.javaPrimitiveType)
                val valMethod = headersObj.javaClass.getMethod("value", Int::class.javaPrimitiveType)
                val count = sizeMethod.invoke(headersObj) as Int
                for (i in 0 until count) {
                    val hName = nameMethod.invoke(headersObj, i) as String
                    val hVal = valMethod.invoke(headersObj, i) as String
                    cachedHeaders[hName] = hVal
                    if (hName.equals("Authorization", ignoreCase = true) || hName.contains("token", ignoreCase = true)) {
                        checkAndSetToken(hVal, hName)
                    }
                }
            } catch (_: Throwable) {
                val getMethod = headersObj.javaClass.getMethod("get", String::class.java)
                val auth = getMethod.invoke(headersObj, "Authorization") as? String
                if (!auth.isNullOrEmpty()) {
                    checkAndSetToken(auth, "Authorization")
                }
                val cookie = getMethod.invoke(headersObj, "Cookie") as? String
                if (!cookie.isNullOrEmpty()) cachedHeaders["Cookie"] = cookie
                val userAgent = getMethod.invoke(headersObj, "User-Agent") as? String
                if (!userAgent.isNullOrEmpty()) cachedHeaders["User-Agent"] = userAgent
            }
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
                            checkAndSetToken(value, chain.args.getOrNull(0) as? String)
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
                            checkAndSetToken(s, chain.args.getOrNull(0) as? String)
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
            val dbClass = SQLiteDatabase::class.java
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
                                    extractAndProcessOrderJson(json, "SQLite () 💾")
                                }
                            } catch (_: Throwable) {}
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Proactive direct SQLite database scanner matching original app Lb11.f
     * Directly opens Jahez local SQLite/Room databases via SQLiteDatabase.openDatabase
     */
    fun scanJahezDatabases(context: Context) {
        try {
            val dbDir = File(context.applicationInfo.dataDir, "databases")
            if (dbDir.exists() && dbDir.isDirectory) {
                val files = dbDir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.isFile && !f.name.endsWith("-wal") && !f.name.endsWith("-shm") && !f.name.endsWith("-journal")) {
                        scanDatabaseFile(f)
                    }
                }
            }

            val dbList = try { context.databaseList() } catch (_: Throwable) { null } ?: emptyArray()
            for (dbName in dbList) {
                val dbFile = context.getDatabasePath(dbName)
                if (dbFile.exists() && !dbFile.name.endsWith("-wal") && !dbFile.name.endsWith("-shm") && !dbFile.name.endsWith("-journal")) {
                    scanDatabaseFile(dbFile)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scanDatabaseFile(file: File) {
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        try {
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tableCursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                null
            )
            val tableNames = mutableListOf<String>()
            while (tableCursor.moveToNext()) {
                val name = tableCursor.getString(0)
                if (name.contains("order", ignoreCase = true) || 
                    name.contains("trip", ignoreCase = true) || 
                    name.contains("dispatch", ignoreCase = true) ||
                    name.contains("request", ignoreCase = true) ||
                    name.contains("task", ignoreCase = true)) {
                    tableNames.add(name)
                }
            }
            tableCursor.close()

            for (table in tableNames) {
                try {
                    cursor = db.rawQuery("SELECT * FROM  ORDER BY rowid DESC LIMIT 15", null)
                    val colCount = cursor.columnCount
                    val colNames = cursor.columnNames
                    while (cursor.moveToNext()) {
                        val json = JSONObject()
                        for (i in 0 until colCount) {
                            when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> json.put(colNames[i], cursor.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> json.put(colNames[i], cursor.getDouble(i))
                                Cursor.FIELD_TYPE_STRING -> {
                                    val strVal = cursor.getString(i)
                                    json.put(colNames[i], strVal)
                                    if (strVal != null && strVal.startsWith("{") && strVal.endsWith("}")) {
                                        try {
                                            extractAndProcessOrderJson(JSONObject(strVal), "DB Row JSON (.) 💾")
                                        } catch (_: Throwable) {}
                                    }
                                }
                                Cursor.FIELD_TYPE_BLOB -> {
                                    val bytes = cursor.getBlob(i)
                                    if (bytes != null) {
                                        val s = String(bytes, Charsets.UTF_8).trim()
                                        if (s.startsWith("{") && s.endsWith("}")) {
                                            try {
                                                extractAndProcessOrderJson(JSONObject(s), "DB Blob JSON (.) 💾")
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            }
                        }
                        extractAndProcessOrderJson(json, "DB Table () 💾")
                    }
                    cursor.close()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {} finally {
            try { cursor?.close() } catch (_: Throwable) {}
            try { db?.close() } catch (_: Throwable) {}
        }
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
                    if (isMasterRunning && (isAutoAccept || isAutoReject || isDryRun)) {
                        scanAllRoots()
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 350)
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

    private var failedPollingCount = 0

    private fun pollServerForAvailableOrders() {
        val token = cachedAuthToken
        if (token.isEmpty()) return

        // Backoff if endpoints return 404 to avoid WAF rate-limiting / shadowban
        if (lastOrdersListUrl.isEmpty() && failedPollingCount > 6) {
            if (failedPollingCount % 12 != 0) {
                failedPollingCount++
                return
            }
        }

        val candidateEndpoints = listOf(
            "/api/fleets/orders/available",
            "/api/v1/fleets/orders/available",
            "/api/fleets/orders"
        )

        val probeHosts = mutableListOf<String>()
        if (lastOrdersListUrl.isNotEmpty()) probeHosts.add(lastOrdersListUrl)
        if (cachedApiHost.isNotEmpty()) probeHosts.add(cachedApiHost)
        probeHosts.addAll(candidateApiHosts)

        val clientToUse = capturedAppHttpClient ?: httpClient

        for (h in probeHosts.distinct()) {
            for (endpoint in candidateEndpoints) {
                val url = buildCandidateUrl(h, endpoint)
                try {
                    pollingHits.incrementAndGet()
                    val reqBuilder = Request.Builder().url(url).get()
                    applyJahezHeaders(reqBuilder, token)

                    val resp = clientToUse.newCall(reqBuilder.build()).execute()
                    val code = resp.code
                    lastHttpResponseCode = code
                    val body = resp.body?.string() ?: ""
                    resp.close()

                    if (code in 200..299 && body.isNotEmpty()) {
                        failedPollingCount = 0
                        if (cachedApiHost.isEmpty()) {
                            val uri = java.net.URI(url)
                            cachedApiHost = "${uri.scheme}://${uri.host}"
                            persistHost(cachedApiHost)
                        }
                        if (body.contains("order", ignoreCase = true) || body.contains("trip", ignoreCase = true)) {
                            findAndParseEmbeddedJson(body, "Server Polling (POLL) ⚡")
                            return
                        }
                    }
                } catch (_: Throwable) {}
            }
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

    private fun extractFirstString(json: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = json.optString(k, "")
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }

    private fun extractFirstDouble(json: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (json.has(k)) {
                val d = json.optDouble(k, 0.0)
                if (d > 0.0) return d
            }
        }
        return 0.0
    }

    private fun extractAndProcessOrderJson(json: JSONObject, channelSource: String) {
        try {
            var orderId = extractFirstString(json, "order_number", "order_id", "orderNumber", "orderId", "id", "code", "trip_id", "tripId")
            var reference = extractFirstString(json, "reference", "order_number", "orderNumber", "order_code", "code", "id")
            var price = extractFirstDouble(json, "price", "delivery_cost", "delivery_fee", "deliveryFee", "deliveryCost", "driver_fee", "driverFee", "earning", "amount", "total")
            var dist = extractFirstDouble(json, "distance", "km", "distance_to_pickup", "distance_in_km", "distanceInKm", "distanceKm")
            var restaurant = extractFirstString(json, "store_name", "restaurant_name", "storeName", "restaurantName", "vendor_name", "vendorName", "shop_name", "shopName", "name", "restaurant")
            var customerName = extractFirstString(json, "customer_name", "client_name", "customerName", "clientName", "recipient_name", "user_name", "customer")
            var customerPhone = extractFirstString(json, "customer_phone", "client_phone", "customerPhone", "clientPhone", "phone", "mobile", "contact_number")
            var address = extractFirstString(json, "customer_address", "delivery_address", "dropoff_address", "address", "dropoffLocation", "destination_address", "location_address")
            var cashAmount = extractFirstDouble(json, "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
            var currency = extractFirstString(json, "currency", "curr").ifEmpty { "SAR" }
            var status = extractFirstString(json, "status", "order_status", "state").ifEmpty { "Accepted ✅" }

            val dataObj = json.optJSONObject("data") ?: json.optJSONObject("order") ?: json.optJSONObject("trip") ?: json.optJSONObject("payload")
            if (dataObj != null) {
                if (orderId.isEmpty()) orderId = extractFirstString(dataObj, "order_number", "order_id", "orderNumber", "orderId", "id", "code", "trip_id", "tripId")
                if (reference.isEmpty()) reference = extractFirstString(dataObj, "reference", "order_number", "orderNumber", "order_code", "code", "id")
                if (price == 0.0) price = extractFirstDouble(dataObj, "price", "delivery_cost", "delivery_fee", "deliveryFee", "deliveryCost", "driver_fee", "driverFee", "earning", "amount", "total")
                if (dist == 0.0) dist = extractFirstDouble(dataObj, "distance", "km", "distance_to_pickup", "distance_in_km", "distanceInKm", "distanceKm")
                if (restaurant.isEmpty()) restaurant = extractFirstString(dataObj, "store_name", "restaurant_name", "storeName", "restaurantName", "vendor_name", "vendorName", "shop_name", "shopName", "name", "restaurant")
                if (customerName.isEmpty()) customerName = extractFirstString(dataObj, "customer_name", "client_name", "customerName", "clientName", "recipient_name", "user_name", "customer")
                if (customerPhone.isEmpty()) customerPhone = extractFirstString(dataObj, "customer_phone", "client_phone", "customerPhone", "clientPhone", "phone", "mobile", "contact_number")
                if (address.isEmpty()) address = extractFirstString(dataObj, "customer_address", "delivery_address", "dropoff_address", "address", "dropoffLocation", "destination_address", "location_address")
                if (cashAmount == 0.0) cashAmount = extractFirstDouble(dataObj, "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
                val s = extractFirstString(dataObj, "status", "order_status", "state")
                if (s.isNotEmpty()) status = s
            }

            val custObj = json.optJSONObject("customer") ?: dataObj?.optJSONObject("customer") ?: json.optJSONObject("client") ?: dataObj?.optJSONObject("client")
            if (custObj != null) {
                if (customerName.isEmpty()) customerName = extractFirstString(custObj, "name", "full_name", "customer_name")
                if (customerPhone.isEmpty()) customerPhone = extractFirstString(custObj, "phone", "mobile", "phone_number")
            }

            val dropoffObj = json.optJSONObject("dropoff") ?: dataObj?.optJSONObject("dropoff") ?: json.optJSONObject("delivery") ?: dataObj?.optJSONObject("delivery")
            if (dropoffObj != null) {
                if (address.isEmpty()) address = extractFirstString(dropoffObj, "address", "location_name", "formatted_address", "name")
            }

            val pickupObj = json.optJSONObject("pickup") ?: dataObj?.optJSONObject("pickup") ?: json.optJSONObject("store") ?: dataObj?.optJSONObject("store")
            if (pickupObj != null) {
                if (restaurant.isEmpty()) restaurant = extractFirstString(pickupObj, "name", "store_name", "restaurant_name")
            }

            if (orderId.isNotEmpty() || (price > 0.0 && restaurant.isNotEmpty())) {
                if (orderId.isEmpty()) {
                    orderId = if (reference.isNotEmpty()) "#$reference" else if (restaurant.isNotEmpty()) "#$restaurant" else return
                }
                if (reference.isEmpty()) reference = orderId.replace("#", "")
                if (loggedOrderIds.add(orderId)) {
                    val cleanPrice = String.format(Locale.US, "%.2f", price).toDoubleOrNull() ?: price
                    lastOrderEvent = "Order $orderId detected via $channelSource"
                    sendOrderToLog(
                        orderId = orderId,
                        price = cleanPrice,
                        dist = dist,
                        restaurant = restaurant,
                        status = "$channelSource (Accepted ✅)",
                        customerName = customerName,
                        customerPhone = customerPhone,
                        address = address,
                        cashAmount = cashAmount,
                        currency = currency,
                        reference = reference
                    )
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
                val fmtP = String.format(Locale.US, "%.1f", price)
                rejectReason = "Price (" + fmtP + " SAR) below minimum"
            } else if (maxDistToRestaurant > 0.0 && dist > 0.0 && dist > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "Distance to restaurant (${dist} km) exceeds limit"
            }
        }

        if (shouldReject) {
            lastOrderEvent = "Order $orderId auto-rejected ($rejectReason)"
            currentActivity?.let { act -> showToast(act, "❌ Auto-Rejected Order $orderId: $rejectReason") }
            sendOrderToLog(orderId, price, dist, restaurant, "Auto-Rejected ❌ ($rejectReason)")
            executeDirectApiCall(rawId, "REJECT")
            return
        }

        if (isDryRun) {
            lastOrderEvent = "Order $orderId dry-run (would accept)"
            currentActivity?.let { act -> showToast(act, "🔍 [Dry-Run]: Would accept Order $orderId (API)") }
            sendOrderToLog(orderId, price, dist, restaurant, "Dry-Run (Would Accept) 🔍")
            return
        }

        if (isAutoAccept || isMasterRunning) {
            lastOrderEvent = "Auto-Accepting Order $orderId..."
            currentActivity?.let { act ->
                showToast(act, "⚡ Auto-Accepting Order $orderId (In-Process)...")
                act.runOnUiThread {
                    try {
                        act.window?.decorView?.let { decor ->
                            findAndTriggerOrder(decor, act, System.currentTimeMillis())
                        }
                    } catch (_: Throwable) {}
                }
            }
            sendOrderToLog(orderId, price, dist, restaurant, "Auto-Accepted ✅ (In-Process / API)")
            executeDirectApiCall(rawId, "ACCEPT")
            playAlertSound()
        }
    }

    fun scanJahezSharedPreferencesForToken(context: Context): String {
        try {
            val dataDir = context.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                val xmlFiles = prefsDir.listFiles { _, name -> name.endsWith(".xml") } ?: emptyArray()
                for (xmlFile in xmlFiles) {
                    val prefName = xmlFile.nameWithoutExtension
                    val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    for ((key, value) in sp.all) {
                        val strVal = value?.toString()?.trim() ?: continue
                        if (strVal.length > 20 && (strVal.startsWith("ey") || strVal.startsWith("Bearer "))) {
                            checkAndSetToken(strVal, key)
                            if (cachedAuthToken.isNotEmpty()) return cachedAuthToken
                        } else if (key.contains("token", ignoreCase = true) || key.contains("auth", ignoreCase = true) || key.contains("session", ignoreCase = true)) {
                            checkAndSetToken(strVal, key)
                            if (cachedAuthToken.isNotEmpty()) return cachedAuthToken
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    private fun executeDirectApiCall(orderIdNum: String, action: String) {
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        val burstCount = if (action == "ACCEPT") parallelRequests.coerceIn(1, 5) else 1

        val probeHosts = mutableListOf<String>()
        if (cachedApiHost.isNotEmpty()) probeHosts.add(cachedApiHost)
        probeHosts.addAll(candidateApiHosts)

        val clientToUse = capturedAppHttpClient ?: httpClient

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = org.json.JSONObject().apply { put("order_id", orderIdNum) }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                for (burst in 0 until burstCount) {
                    launch {
                        for (host in probeHosts.distinct()) {
                            val endpoints = if (action == "ACCEPT") {
                                listOf(
                                    buildCandidateUrl(host, "/api/fleets/orders/" + orderIdNum + "/accept"),
                                    buildCandidateUrl(host, "/api/driver/orders/" + orderIdNum + "/accept"),
                                    buildCandidateUrl(host, "/api/orders/" + orderIdNum + "/accept"),
                                    buildCandidateUrl(host, "/api/v1/orders/" + orderIdNum + "/accept")
                                )
                            } else {
                                listOf(
                                    buildCandidateUrl(host, "/api/fleets/orders/" + orderIdNum + "/reject"),
                                    buildCandidateUrl(host, "/api/driver/orders/" + orderIdNum + "/reject"),
                                    buildCandidateUrl(host, "/api/orders/" + orderIdNum + "/reject")
                                )
                            }

                            for (url in endpoints) {
                                for (method in listOf("POST", "PUT")) {
                                    try {
                                        val reqBuilder = Request.Builder().url(url)
                                        if (method == "POST") reqBuilder.post(body) else reqBuilder.put(body)

                                        applyJahezHeaders(reqBuilder, token)

                                        val resp = clientToUse.newCall(reqBuilder.build()).execute()
                                        val code = resp.code
                                        resp.close()
                                        if (code in 200..299) {
                                            cachedApiHost = host
                                            persistHost(host)
                                            return@launch
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun sendOrderToLog(
        orderId: String,
        price: Double,
        dist: Double,
        restaurant: String,
        status: String,
        customerName: String = "",
        customerPhone: String = "",
        address: String = "",
        cashAmount: Double = 0.0,
        currency: String = "SAR",
        reference: String = ""
    ) {
        val ctx = appContext ?: currentActivity
        val ref = if (reference.isNotEmpty()) reference else orderId.replace("#", "")
        val intId = orderId.filter { it.isDigit() }.toIntOrNull() ?: 0
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val order = OrderModel(
            id = intId,
            reference = ref,
            status = status,
            restaurant = restaurant,
            customerName = customerName,
            customerPhone = customerPhone,
            address = address,
            price = price,
            currency = currency,
            cashAmount = cashAmount,
            distanceKm = dist,
            time = timeStr
        )

        if (ctx != null) {
            try {
                val logIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                    component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                    putExtra("order_id", orderId)
                    putExtra("reference", ref)
                    putExtra("price", price)
                    putExtra("distance", dist)
                    putExtra("restaurant", restaurant)
                    putExtra("status", status)
                    putExtra("customer_name", customerName)
                    putExtra("customer_phone", customerPhone)
                    putExtra("address", address)
                    putExtra("cash_amount", cashAmount)
                    putExtra("currency", currency)
                }
                ctx.sendBroadcast(logIntent)
            } catch (_: Throwable) {}

            try {
                val gIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                    putExtra("order_id", orderId)
                    putExtra("reference", ref)
                    putExtra("price", price)
                    putExtra("distance", dist)
                    putExtra("restaurant", restaurant)
                    putExtra("status", status)
                    putExtra("customer_name", customerName)
                    putExtra("customer_phone", customerPhone)
                    putExtra("address", address)
                    putExtra("cash_amount", cashAmount)
                    putExtra("currency", currency)
                }
                ctx.sendBroadcast(gIntent)
            } catch (_: Throwable) {}

            // Instant System Notification matching Luc1;.c
            try {
                NotificationHelper.showOrderNotification(
                    ctx,
                    order,
                    isAccepted = status.contains("Accept", ignoreCase = true) || status.contains("قبول")
                )
            } catch (_: Throwable) {}
        }

        try {
            val f = File("/data/local/tmp/saned_orders.json")
            val existing = if (f.exists()) f.readText() else "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

            val newArray = JSONArray()
            newArray.put(order.toJson())
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

        var customerPhone = ""
        var customerName = ""
        var customerAddress = ""
        var cashAmount = 0.0

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

            val mPhone = Regex("""(?:\+?966|0)?5\d{8}""").find(t)
            if (mPhone != null && customerPhone.isEmpty()) {
                customerPhone = mPhone.value
            }

            val mCash = Regex("""(?:استلم نقداً|استلام نقدي|كاش|نقدا|نقداً|Collect\s*Cash|COD)[\s:]*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(t)
            if (mCash != null && cashAmount == 0.0) {
                cashAmount = mCash.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            val mDrop = Regex("""(?:Deliver to|توصيل إلى|التوصيل إلى)\s*([^,\n]+)""", RegexOption.IGNORE_CASE).find(t)
            if (mDrop != null && customerAddress.isEmpty()) {
                customerAddress = mDrop.groupValues[1].trim()
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

        // Geometry-based slider detection (for Canvas / Custom Slider views without text)
        if (acceptView == null) {
            val dm = activity.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val sliderList = mutableListOf<View>()
            fun findSliderCandidates(v: View) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (v.width > (screenW * 0.45) && v.height in 35..180 && loc[1] > (screenH * 0.35)) {
                    sliderList.add(v)
                }
                if (v is ViewGroup) {
                    for (idx in 0 until v.childCount) {
                        findSliderCandidates(v.getChildAt(idx))
                    }
                }
            }
            findSliderCandidates(root)

            acceptView = sliderList.maxByOrNull {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                loc[1]
            } ?: clickableCandidates.maxByOrNull {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                loc[1]
            }
        }

        // 1. إذا لم يوجد اسم مطعم، ولا سعر، ولا معرّف طلب حقيقي، ولا علامة على شاشة استلام:
        val hasOrderDetails = isNewOrderScreen || (orderPrice > 0.0 && storeName.isNotEmpty())
        if (!hasOrderDetails && orderId.isEmpty() && storeName.isEmpty() && orderPrice == 0.0) {
            return false // شاشة عادية (ليست طلباً) - لا تفعل شيئاً
        }

        // 2. استخدام معرّف حقيقي فقط، وعدم استخدام random() نهائياً:
        if (orderId.isEmpty()) {
            orderId = if (storeName.isNotEmpty()) "#$storeName" else return false
        }

        // 3. التأكد أن هناك زراً بالفعل، وأن الشاشة هي شاشة طلب جديد حقيقية تحتوي على تفاصيل:
        if (acceptView != null && hasOrderDetails && (orderPrice > 0.0 || storeName.isNotEmpty() || orderId.isNotEmpty())) {
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
                sendOrderToLog(
                    orderId = orderId,
                    price = cleanPrice,
                    dist = distToRestaurant,
                    restaurant = storeName,
                    status = "Screen Popup 📱 (Accepted ✅)",
                    customerName = customerName,
                    customerPhone = customerPhone,
                    address = customerAddress,
                    cashAmount = cashAmount,
                    currency = "SAR",
                    reference = orderId.replace("#", "")
                )
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
                val fmtP = String.format(Locale.US, "%.1f", price)
                rejectReason = "Price (" + fmtP + " SAR) below minimum (" + minOrderPrice + " SAR)"
            } else if (maxDistToRestaurant > 0.0 && distRest > 0.0 && distRest > maxDistToRestaurant) {
                shouldReject = true
                rejectReason = "Distance to restaurant ( km) exceeds limit ( km)"
            } else if (maxDistCustomer > 0.0 && distCust > 0.0 && distCust > maxDistCustomer) {
                shouldReject = true
                rejectReason = "Customer distance ( km) exceeds limit ( km)"
            }
        }

        if (shouldReject) {
            lastOrderEvent = "Order  auto-rejected ()"
            showToast(act, "❌ Auto-Rejected Order : ")
            sendOrderToLog(orderId, price, distRest, "", "Auto-Rejected ❌ ()")
            if (btnReject != null) {
                triggerClick(btnReject)
            }
            // Dismiss dialog / finish activity matching original app Ljx.e
            act.runOnUiThread {
                try {
                    if (dialogRoot is ViewGroup && dialogRoot !== act.window?.decorView) {
                        dialogRoot.visibility = View.GONE
                    }
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.finish()
                    }
                } catch (_: Throwable) {}
            }
            executeDirectApiCall(orderId.replace("#", ""), "REJECT")
            return
        }

        if (isDryRun) {
            lastOrderEvent = "Order  dry-run (would accept)"
            showToast(act, "🔍 [Dry-Run]: Would accept Order ")
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

                        lastOrderEvent = "Order  accepted successfully in ms"
                        showToast(act, "⚡ Accepted Order  in ms!")
                        sendOrderToLog(orderId, price, distRest, "", "Accepted Successfully ✅ (ms)")
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

        val targetLoc = IntArray(2)
        targetView.getLocationOnScreen(targetLoc)

        val rootLoc = IntArray(2)
        dialogRoot.getLocationOnScreen(rootLoc)

        val decorLoc = IntArray(2)
        activity.window?.decorView?.getLocationOnScreen(decorLoc)

        val viewLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)

        val viewW = targetView.width.toFloat().coerceAtLeast(200f)
        val viewH = targetView.height.toFloat().coerceAtLeast(50f)

        val isRtl = (activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)

        // 1. Local coordinates relative to targetView
        val localY = (viewH / 2f).coerceAtLeast(15f)
        val localStartX = if (isRtl) (viewW - 40f).coerceAtLeast(20f) else 40f.coerceAtMost(viewW - 20f)
        val localEndX = if (isRtl) 20f.coerceAtMost(viewW - 20f) else (viewW - 40f).coerceAtLeast(20f)

        // 2. Relative coordinates for dialogRoot (bounds-safe)
        val rootOffsetX = targetLoc[0] - rootLoc[0]
        val rootOffsetY = targetLoc[1] - rootLoc[1]
        val rootY = rootOffsetY + localY
        val rootStartX = rootOffsetX + localStartX
        val rootEndX = rootOffsetX + localEndX

        // 3. Relative coordinates for decorView
        val decorOffsetX = targetLoc[0] - decorLoc[0]
        val decorOffsetY = targetLoc[1] - decorLoc[1]
        val decorY = decorOffsetY + localY
        val decorStartX = decorOffsetX + localStartX
        val decorEndX = decorOffsetX + localEndX

        // Immediate direct clicks and reflection callback triggers
        triggerClick(view)
        triggerClick(targetView)

        var curr: View? = targetView
        while (curr != null) {
            try {
                for (m in curr.javaClass.declaredMethods) {
                    val name = m.name.lowercase()
                    if (name.contains("complete") || name.contains("slide") || name.contains("swipe") || name.contains("accept") || name.contains("confirm")) {
                        m.isAccessible = true
                        if (m.parameterTypes.isEmpty()) {
                            m.invoke(curr)
                        } else if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                            m.invoke(curr, true)
                        }
                    }
                }
            } catch (_: Throwable) {}
            curr = curr.parent as? View
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val downTime = SystemClock.uptimeMillis()

                fun dispatchEvent(action: Int, eventTime: Long, progress: Float) {
                    val curLocalX = localStartX + (localEndX - localStartX) * progress
                    val curRootX = rootStartX + (rootEndX - rootStartX) * progress
                    val curDecorX = decorStartX + (decorEndX - decorStartX) * progress

                    // A) targetView dispatch (in targetView local coordinates)
                    try {
                        val ev = MotionEvent.obtain(downTime, eventTime, action, curLocalX, localY, 0)
                        targetView.dispatchTouchEvent(ev)
                        ev.recycle()
                    } catch (_: Throwable) {}

                    // B) dialogRoot dispatch (in dialogRoot relative coordinates)
                    try {
                        val ev = MotionEvent.obtain(downTime, eventTime, action, curRootX, rootY, 0)
                        dialogRoot.dispatchTouchEvent(ev)
                        ev.recycle()
                    } catch (_: Throwable) {}

                    // C) view dispatch (if separate child)
                    if (view !== targetView) {
                        try {
                            val vLocalX = (targetLoc[0] - viewLoc[0]) + curLocalX
                            val vLocalY = (targetLoc[1] - viewLoc[1]) + localY
                            val ev = MotionEvent.obtain(downTime, eventTime, action, vLocalX, vLocalY, 0)
                            view.dispatchTouchEvent(ev)
                            ev.recycle()
                        } catch (_: Throwable) {}
                    }

                    // D) decorView dispatch (in decorView relative coordinates)
                    try {
                        val decor = activity.window?.decorView
                        if (decor != null && decor !== dialogRoot) {
                            val ev = MotionEvent.obtain(downTime, eventTime, action, curDecorX, decorY, 0)
                            decor.dispatchTouchEvent(ev)
                            ev.recycle()
                        }
                    } catch (_: Throwable) {}
                }

                // 1. ACTION_DOWN
                dispatchEvent(MotionEvent.ACTION_DOWN, downTime, 0f)
                delay(12)

                // 2. Smooth ACTION_MOVE with delays for velocity & gesture detection
                val steps = 24
                for (step in 1..steps) {
                    val progress = step.toFloat() / steps.toFloat()
                    val eventTime = downTime + (step * 8L)
                    dispatchEvent(MotionEvent.ACTION_MOVE, eventTime, progress)
                    delay(7)
                }

                // 3. ACTION_UP
                val upTime = SystemClock.uptimeMillis()
                dispatchEvent(MotionEvent.ACTION_UP, upTime, 1.0f)

                delay(30)
                // Post-swipe clicks
                try { targetView.performClick() } catch (_: Throwable) {}
                try { view.performClick() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    private fun triggerClick(view: View) {
        try {
            view.performClick()
            view.callOnClick()
        } catch (_: Throwable) {}

        try {
            val cx = (view.width / 2f).coerceAtLeast(10f)
            val cy = (view.height / 2f).coerceAtLeast(10f)
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
