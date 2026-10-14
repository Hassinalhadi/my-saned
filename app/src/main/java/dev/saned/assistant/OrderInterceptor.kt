package dev.saned.assistant

import android.app.Activity
import android.app.Application
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
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Process
import dev.jing.sanedhook.util.Native
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
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
    @Volatile var isStayInBackground: Boolean = false
    @Volatile var isSilenceAlertTone: Boolean = true
    @Volatile var isStrictVersionCheck: Boolean = false
    @Volatile var licenseToken: String = ""
    @Volatile var licenseBoundId: String = ""
    @Volatile var detectedJahezVersionCode: Long = 0L
    @Volatile var isJahezVersionOfficiallySupported: Boolean = true
    private val unsupportedVersionNotified = AtomicBoolean(false)

    fun checkJahezVersionSupport(versionCode: Long): Boolean {
        if (versionCode <= 0L) return true
        detectedJahezVersionCode = versionCode
        val supported = PackageCloaker.checkVersionSupported(versionCode)
        isJahezVersionOfficiallySupported = supported
        return supported
    }

    fun notifyUnsupportedVersion(versionCode: Long) {
        if (!unsupportedVersionNotified.compareAndSet(false, true)) return
        val msg = "⛔ إصدار سند $versionCode غير مدعوم بعد — يرجى تحديث أداة المساعد"
        lastOrderEvent = msg
        mainHandler.post {
  try {
      val ctx = resolveApplicationContext() ?: currentActivity
      if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
  } catch (_: Throwable) {}
        }
    }

    data class HandledOrderEntry(val decision: String, val timestampRealtime: Long, val wasInBackground: Boolean)
    val handledOrdersMap = ConcurrentHashMap<Int, HandledOrderEntry>()

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

    fun onActivityPreCreated(activity: Activity): Int {
        currentActivity = activity
        syncSettings(activity.contentResolver)
        return try {
  val intent = activity.intent
  if (intent != null) {
      val exactId = extractExactOrderIdFromIntent(intent)
      extractAndProcessOrderFromIntent(intent, activity)
      exactId
  } else 0
        } catch (_: Throwable) { 0 }
    }

    fun onOrderActivityPostCreate(activity: Activity, orderIdInt: Int) {
        handlePostOrderActivityCreate(activity, orderIdInt)
    }

    private fun extractExactOrderIdFromIntent(intent: Intent): Int = try {
        val keys = Native.safeK()
        val reqIdKey = keys.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "EXTRA_DISPATCH_REQUEST_ID"
        val idVal = intent.getIntExtra(reqIdKey, 0)
        if (idVal != 0) idVal else intent.getIntExtra("order_id", 0)
    } catch (_: Throwable) { 0 }

    fun handlePostOrderActivityCreate(activity: Activity, orderIdInt: Int) {
        if (orderIdInt == 0) return
        val entry = handledOrdersMap[orderIdInt] ?: return
        if ((entry.decision != "ACCEPT" && entry.decision != "REJECT") || isDryRun) return
        if (isSilenceAlertTone) {
  try {
      for (field in activity.javaClass.declaredFields) {
          if (MediaPlayer::class.java.isAssignableFrom(field.type)) {
              field.isAccessible = true
              (field.get(activity) as? MediaPlayer)?.setVolume(0f, 0f)
          }
      }
  } catch (_: Throwable) {}
        }
        mainHandler.post {
  try {
      if (activity.isTaskRoot && !entry.wasInBackground) {
          val mainCls = PackageCloaker.getMainActivityClassName()
          if (mainCls.isNotEmpty()) activity.startActivity(Intent().setClassName(activity.packageName, mainCls))
      }
      if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
  } catch (_: Throwable) {}
        }
    }

    fun onActivityCreated(activity: Activity) {
        currentActivity = activity
        syncSettings(activity.contentResolver)
        try {
  val intent = activity.intent
  if (intent != null) {
      extractAndProcessOrderFromIntent(intent, activity)
  }
        } catch (_: Throwable) {}
    }

    fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        syncSettings(activity.contentResolver)
        startScreenWatcher()
        startActiveServerPolling()
        for (delayMs in listOf(0L, 80L, 220L, 450L, 800L)) {
            mainHandler.postDelayed({
                try {
                    scanAllRoots()
                } catch (_: Throwable) {}
            }, delayMs)
        }
    }

    private fun extractOrderIdFromActivity(activity: Activity): String {
        try {
            val extras = activity.intent?.extras
            if (extras != null) {
                for (k in extras.keySet()) {
                    val lk = k.lowercase()
                    val v = extras.get(k) ?: continue
                    val strVal = v.toString().trim()
                    if (lk.contains("order") || lk.contains("id") || lk.contains("offer") || lk.contains("number") || lk.contains("code") || lk.contains("trip")) {
                        val digits = strVal.filter { it.isDigit() }
                        if (digits.length in 5..12 && digits != "13524") {
                            return "#$digits"
                        }
                    }
                    if (v is Int && v in 10000..2147483647 && v != 13524) {
                        return "#$v"
                    }
                    if (v is Long && v in 10000L..999999999999L) {
                        return "#$v"
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            var cls: Class<*>? = activity.javaClass
            while (cls != null && cls != Activity::class.java && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    val fname = f.name.lowercase()
                    val fv = f.get(activity) ?: continue
                    if (fname.contains("order") || fname.contains("offer") || fname.contains("id")) {
                        val digits = fv.toString().filter { it.isDigit() }
                        if (digits.length in 6..12) return "#$digits"
                    }
                    if (fv is Int && fv in 100000..2147483647) return "#$fv"
                    if (fv is Long && fv in 100000L..999999999999L) return "#$fv"
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return ""
    }

    private fun extractAndProcessOrderFromIntent(intent: Intent, activity: Activity) {
        try {
            val extras = intent.extras ?: return

            // 0. Exact SanedAssistant-98 Ls20.e & Ls20.c extraction via Lby1 (Native.k)
            try {
                val k = Native.safeK()
                if (k.size >= 12) {
                    val reqId = intent.getIntExtra(k[0], 0)
                    if (reqId != 0) {
                        val pLat = intent.getDoubleExtra(k[1], 0.0)
                        val pLng = intent.getDoubleExtra(k[2], 0.0)
                        val dLat = intent.getDoubleExtra(k[3], 0.0)
                        val dLng = intent.getDoubleExtra(k[4], 0.0)
                        val delivPrice = intent.getDoubleExtra(k[5], 0.0)
                        val dispType = intent.getStringExtra(k[6]) ?: ""
                        val distExtra = intent.getDoubleExtra(k[7], 0.0)
                        val counterMs = intent.getLongExtra(k[8], 0L)
                        val subName = intent.getStringExtra(k[9]) ?: ""
                        val bonusAmt = intent.getDoubleExtra(k[10], 0.0)
                        val curr = intent.getStringExtra(k[11]) ?: "SAR"

                        val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
                        val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
                        val calcRestDist = if (pLat != 0.0 && pLng != 0.0 && drvLat != 0.0 && drvLng != 0.0) {
                            LocationEngine.calculateDistance(drvLat, drvLng, pLat, pLng)
                        } else {
                            distExtra
                        }
                        val calcCustDist = if (pLat != 0.0 && pLng != 0.0 && dLat != 0.0 && dLng != 0.0) {
                            LocationEngine.calculateDistance(pLat, pLng, dLat, dLng)
                        } else {
                            0.0
                        }

                        val totalOfferPrice = delivPrice + bonusAmt
                        val cleanPrice = String.format(Locale.US, "%.2f", totalOfferPrice).toDoubleOrNull() ?: totalOfferPrice
                        lastOrderEvent = "Order #$reqId detected via IncomingDispatchActivity (Ls20)"
                        handleOrderViaApi(
                            orderId = "#$reqId",
                            price = cleanPrice,
                            dist = calcRestDist,
                            restaurant = subName,
                            custDist = calcCustDist,
                            sourceChannel = "SCREEN"
                        )
                        handlePostOrderActivityCreate(activity, reqId)
                        return
                    }
                }
            } catch (_: Throwable) {}

            // 1. Check for embedded JSON strings in any extra
            for (k in extras.keySet()) {
                val v = extras.get(k) ?: continue
                val str = v.toString().trim()
                if (str.startsWith("{") && str.endsWith("}")) {
                    try {
                        val json = JSONObject(str)
                        extractAndProcessOrderJson(json, "Activity Intent ($k)")
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
                    if (digits.length in 5..12) foundId = "#$digits"
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

            // Compute Haversine distances from coordinates if direct distance extra is 0.0 (matching Lqz.a & Ls20.e)
            if (foundDist == 0.0) {
                var pLat = 0.0
                var pLng = 0.0
                var dLat = 0.0
                var dLng = 0.0
                for (k in extras.keySet()) {
                    val lk = k.lowercase()
                    val v = extras.get(k) ?: continue
                    val d = (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: continue
                    if (d in 15.0..35.0) {
                        if (lk.contains("pickup") || lk.contains("store") || lk.contains("branch") || lk.contains("rest") || pLat == 0.0) {
                            if (pLat == 0.0) pLat = d else if (dLat == 0.0) dLat = d
                        } else if (dLat == 0.0) {
                            dLat = d
                        }
                    } else if (d in 34.0..56.0) {
                        if (lk.contains("pickup") || lk.contains("store") || lk.contains("branch") || lk.contains("rest") || pLng == 0.0) {
                            if (pLng == 0.0) pLng = d else if (dLng == 0.0) dLng = d
                        } else if (dLng == 0.0) {
                            dLng = d
                        }
                    }
                }
                val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
                val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
                if (pLat != 0.0 && pLng != 0.0 && drvLat != 0.0 && drvLng != 0.0) {
                    foundDist = LocationEngine.calculateDistance(drvLat, drvLng, pLat, pLng)
                }
                if (foundCustDist == 0.0 && pLat != 0.0 && pLng != 0.0 && dLat != 0.0 && dLng != 0.0) {
                    foundCustDist = LocationEngine.calculateDistance(pLat, pLng, dLat, dLng)
                }
            }

            if (foundId.isNotEmpty() && (foundPrice > 0.0 || foundDist > 0.0 || foundStore.isNotEmpty())) {
                val cleanPrice = String.format(Locale.US, "%.2f", foundPrice).toDoubleOrNull() ?: foundPrice
                lastOrderEvent = "Order $foundId detected via Activity Intent"
                handleOrderViaApi(
                    orderId = foundId,
                    price = cleanPrice,
                    dist = foundDist,
                    restaurant = foundStore,
                    custDist = foundCustDist,
                    custAddr = foundAddress,
                    custName = foundCustomer,
                    custPhone = foundPhone,
                    cashAmt = foundCash
                )
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

    private val syncedOrdersCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())

    fun getSyncedOrdersBatchJson(): String {
        val arr = JSONArray()
        synchronized(syncedOrdersCache) {
            for (obj in syncedOrdersCache.values) {
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    private fun respondToPing(intent: Intent) {
        val app = resolveApplicationContext() ?: currentActivity ?: return
        val pingTimestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())

        CoroutineScope(Dispatchers.IO).launch {
            try { scanJahezDatabases(app) } catch (_: Throwable) {}
        }

        if (cachedAuthToken.isEmpty()) {
            readPersistedToken()
            if (cachedAuthToken.isEmpty()) {
                scanAllSharedPreferencesForTokens(app)
            }
        }
        if (cachedApiHost.isEmpty()) {
            readPersistedHost()
        }

        val batchStr = getSyncedOrdersBatchJson()
        val totalOrders = synchronized(syncedOrdersCache) { syncedOrdersCache.size }.coerceAtLeast(loggedOrderIds.size)

        try {
            val pongIntent = Intent("dev.saned.assistant.ACTION_PONG").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("ping_timestamp", pingTimestamp)
                putExtra("pong_timestamp", System.currentTimeMillis())
                putExtra("hook_active", true)
                putExtra("has_auth_token", cachedAuthToken.isNotEmpty())
                putExtra("orders_count", totalOrders)
                putExtra("orders_batch_json", batchStr)
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
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("ping_timestamp", pingTimestamp)
                putExtra("pong_timestamp", System.currentTimeMillis())
                putExtra("hook_active", true)
                putExtra("has_auth_token", cachedAuthToken.isNotEmpty())
                putExtra("orders_count", totalOrders)
                putExtra("orders_batch_json", batchStr)
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
        currentActivity?.let { showToast(it, "🧪 Simulated Order $testId: Processing auto-accept pipeline...") }

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

    private val isBootstrapRunning = AtomicBoolean(false)
    private val isLifecycleCallbacksRegistered = AtomicBoolean(false)
    // Matches Ljw.a in SanedAssistant-98: caches (customerName, customerPhone, cashAmount) per orderId
    private val customerDetailsCache = ConcurrentHashMap<String, Triple<String, String, Double>>()
    private val fetchedDetailIds = ConcurrentHashMap.newKeySet<String>()

    fun resolveApplicationContext(): Context? {
        appContext?.let { return it }
        currentActivity?.applicationContext?.let {
            appContext = it
            return it
        }
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null) as? Application
            if (app != null && app.packageName == "net.jahez.fleets") {
                return app
            }
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            if (currentAt != null) {
                val mGetApp = atClass.getMethod("getApplication")
                val app2 = mGetApp.invoke(currentAt) as? Application
                if (app2 != null && app2.packageName == "net.jahez.fleets") {
                    return app2
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Matches Lst1.j() + Lji0.k() + Lb11.g() in SanedAssistant-98-7bac1ec.apk:
     * Immediately starts a background daemon loop from onPackageLoaded / onPackageReady,
     * resolves ActivityThread.currentApplication() without waiting for BootClassLoader Activity hooks,
     * registers Application.ActivityLifecycleCallbacks, and continuously syncs Jahez SQLite orders every 2s.
     */
    fun startBootstrapLoop(module: XposedModule, classLoader: ClassLoader) {
        if (!isBootstrapRunning.compareAndSet(false, true)) return
        Thread {
            var resolvedApp: Context? = null
            for (attempt in 0 until 200) {
                resolvedApp = resolveApplicationContext()
                if (resolvedApp != null) break
                try { Thread.sleep(150) } catch (_: Throwable) {}
            }
            if (resolvedApp != null) {
                try { initAppContext(resolvedApp) } catch (_: Throwable) {}
                val appObj = resolvedApp.applicationContext as? Application ?: (resolvedApp as? Application)
                if (appObj != null) {
                    registerActivityCallbacksOnApp(appObj)
                }
                scanExistingActivitiesInActivityThread()
            }

            // Continuous background SQLite + Order sync loop matching Lb11$b in SanedAssistant-98
            while (true) {
                try {
                    val ctx = resolveApplicationContext()
                    if (ctx != null) {
                        if (appContext == null) {
                            try { initAppContext(ctx) } catch (_: Throwable) {}
                        }
                        scanJahezDatabases(ctx)
                        if (cachedAuthToken.isEmpty()) {
                            scanJahezSharedPreferencesForToken(ctx)
                            scanAllSharedPreferencesForTokens(ctx)
                        }
                    }
                    scanExistingActivitiesInActivityThread()
                } catch (_: Throwable) {}
                try { Thread.sleep(2000) } catch (_: Throwable) { break }
            }
        }.apply {
            isDaemon = true
            name = "Saned-Bootstrap-Lb11"
            start()
        }
    }

    private fun registerActivityCallbacksOnApp(app: Application) {
        if (!isLifecycleCallbacksRegistered.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                        if (activity.packageName == "net.jahez.fleets") {
                            currentActivity = activity
                            this@OrderInterceptor.onActivityCreated(activity)
                        }
                    }
                    override fun onActivityStarted(activity: Activity) {
                        if (activity.packageName == "net.jahez.fleets") {
                            currentActivity = activity
                            this@OrderInterceptor.onActivityResumed(activity)
                        }
                    }
                    override fun onActivityResumed(activity: Activity) {
                        if (activity.packageName == "net.jahez.fleets") {
                            currentActivity = activity
                            this@OrderInterceptor.onActivityResumed(activity)
                        }
                    }
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                })
            } catch (_: Throwable) {}
        }
    }

    private fun scanExistingActivitiesInActivityThread() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null) ?: return
            val mActivitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = mActivitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val pausedField = record.javaClass.getDeclaredField("paused").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                val isPaused = pausedField.getBoolean(record)
                if (!isPaused && act.packageName == "net.jahez.fleets") {
                    if (currentActivity !== act) {
                        currentActivity = act
                        mainHandler.post { onActivityResumed(act) }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun initAppContext(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
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

        syncSettings(app.contentResolver)
        LocationEngine.syncLocationSettings()
        DeviceSpoofer.syncSettings(app.contentResolver)
        requestSettingsFromAssistant()
        loadFallbackConfigFile()
        readPersistedToken()
        readPersistedHost()
        scanAllSharedPreferencesForTokens(app)
        LocationEngine.seedLocationManager(app)
        acquireWakeLock(app)
         (app as? Application)?.let { registerActivityCallbacksOnApp(it) }
        startScreenWatcher()
        startActiveServerPolling()
        CoroutineScope(Dispatchers.IO).launch {
            try { scanJahezDatabases(app) } catch (_: Throwable) {}
        }
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
        isStayInBackground = intent.getBooleanExtra("stay_in_background_v2", isStayInBackground)
        isSilenceAlertTone = intent.getBooleanExtra("silence_alert_tone", isSilenceAlertTone)
        isStrictVersionCheck = intent.getBooleanExtra("strict_version_check", isStrictVersionCheck)
        isActivePolling = intent.getBooleanExtra("active_polling", isActivePolling)
        pollIntervalSec = intent.getFloatExtra("poll_interval_sec", pollIntervalSec)
        parallelRequests = intent.getIntExtra("parallel_requests", parallelRequests)
        minOrderPrice = intent.getDoubleExtra("min_price", minOrderPrice)
        maxDistToRestaurant = intent.getDoubleExtra("max_dist_rest", maxDistToRestaurant)
        maxDistCustomer = intent.getDoubleExtra("max_dist_cust", maxDistCustomer)
        isSoundEnabled = intent.getBooleanExtra("sound_enabled", isSoundEnabled)
        isShowToasts = intent.getBooleanExtra("show_toasts", isShowToasts)
        LocationEngine.isMasterRunning = isMasterRunning
        LocationEngine.isFakeLocationEnabled = intent.getBooleanExtra("fake_location_enabled", LocationEngine.isFakeLocationEnabled)
        LocationEngine.isFixLocationUnknownEnabled = intent.getBooleanExtra("fix_location_unknown", LocationEngine.isFixLocationUnknownEnabled)
        val lat = intent.getDoubleExtra("fake_lat", LocationEngine.currentLat)
        val lng = intent.getDoubleExtra("fake_lng", LocationEngine.currentLng)
        if (lat != 0.0) LocationEngine.currentLat = lat
        if (lng != 0.0) LocationEngine.currentLng = lng
        DeviceSpoofer.isMasterRunning = isMasterRunning
        DeviceSpoofer.isSpoofAndroidId = intent.getBooleanExtra("spoof_android_id", DeviceSpoofer.isSpoofAndroidId)
        val sId = intent.getStringExtra("spoofed_android_id")
        if (!sId.isNullOrEmpty()) DeviceSpoofer.spoofedAndroidId = sId.trim()
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
                isStayInBackground = json.optBoolean("stay_in_background_v2", isStayInBackground)
                isSilenceAlertTone = json.optBoolean("silence_alert_tone", isSilenceAlertTone)
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
        val activeResolver = resolver ?: appContext?.contentResolver ?: currentActivity?.contentResolver
        if (activeResolver != null) {
            try {
                val uri = android.net.Uri.parse("content://dev.jing.sanedhook.XposedService")
                val bundle = activeResolver.call(uri, "getSettings", null, null)
                if (bundle != null && !bundle.isEmpty) {
                    isMasterRunning = bundle.getBoolean("master_running", bundle.getBoolean("enabled", isMasterRunning))
                    isAutoAccept = bundle.getBoolean("auto_accept", isAutoAccept)
                    isAutoReject = bundle.getBoolean("auto_reject", isAutoReject)
                    isDryRun = bundle.getBoolean("dry_run", isDryRun)
                    isStayInBackground = bundle.getBoolean("stay_in_background_v2", isStayInBackground)
                    isSilenceAlertTone = bundle.getBoolean("silence_alert_tone", isSilenceAlertTone)
                    isStrictVersionCheck = bundle.getBoolean("strict_version_check", isStrictVersionCheck)
                    isActivePolling = bundle.getBoolean("active_polling", isActivePolling)
                    pollIntervalSec = bundle.getFloat("poll_interval_sec", pollIntervalSec)
                    parallelRequests = bundle.getInt("parallel_requests", parallelRequests)
                    minOrderPrice = bundle.getString("min_price", bundle.getString("min_order_price", minOrderPrice.toString()))?.toDoubleOrNull() ?: minOrderPrice
                    maxDistToRestaurant = bundle.getString("max_dist_rest", bundle.getString("max_dist_to_restaurant", maxDistToRestaurant.toString()))?.toDoubleOrNull() ?: maxDistToRestaurant
                    maxDistCustomer = bundle.getString("max_dist_cust", bundle.getString("max_dist_restaurant_to_customer", maxDistCustomer.toString()))?.toDoubleOrNull() ?: maxDistCustomer
                    isSoundEnabled = bundle.getBoolean("sound_enabled", isSoundEnabled)
                    isShowToasts = bundle.getBoolean("show_toasts", isShowToasts)
                }
            } catch (_: Throwable) {}
        }
        loadFallbackConfigFile()

        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isAutoAccept = p.getBoolean("auto_accept", isAutoAccept)
            isAutoReject = p.getBoolean("auto_reject", isAutoReject)
            isDryRun = p.getBoolean("dry_run", isDryRun)
            isStayInBackground = p.getBoolean("stay_in_background_v2", isStayInBackground)
            isSilenceAlertTone = p.getBoolean("silence_alert_tone", isSilenceAlertTone)
            isStrictVersionCheck = p.getBoolean("strict_version_check", isStrictVersionCheck)
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

    @Volatile var cachedLanguage: String = "en"

    /**
     * Matches Ljh.e(Context) & Ljh.a(Context, Ljh$a, int) in SanedAssistant-98-7bac1ec.apk:
     * Directly opens ENCRYPTED_SANED_PREFERENCES (Native.h) and reads token (Native.i) and language (Native.j).
     */
    fun readExactJahezEncryptedPrefs(context: Context) {
        try {
            val encPrefName = Native.safeH()
            val tokenKey = Native.safeI()
            val langKey = Native.safeJ()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = EncryptedSharedPreferences.create(
                context,
                encPrefName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val tok = encPrefs.getString(tokenKey, "") ?: ""
            if (tok.isNotEmpty()) {
                checkAndSetToken(tok, tokenKey)
            }
            val lang = encPrefs.getString(langKey, "en") ?: "en"
            if (lang.isNotEmpty()) {
                cachedLanguage = lang
            }
        } catch (_: Throwable) {}
    }

    fun scanAllSharedPreferencesForTokens(context: Context) {
        readPersistedToken()
        readPersistedHost()
        readExactJahezEncryptedPrefs(context)

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

    private val isIncomingDispatchHooked = AtomicBoolean(false)

    /**
     * Matches Ls20.d(XposedModule, ClassLoader) in SanedAssistant-98-7bac1ec.apk:
     * Directly hooks IncomingDispatchActivity.onCreate(Bundle) via Native.e().
     */
    private fun hookIncomingDispatchActivity(module: XposedModule, classLoader: ClassLoader) {
        if (!isIncomingDispatchHooked.compareAndSet(false, true)) return
        try {
            val actClsName = Native.safeE()
            if (actClsName.isEmpty()) return
            val actCls = classLoader.loadClass(actClsName)
            val mOnCreate = actCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            module.hook(mOnCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val act = chain.thisObject as? Activity
                    if (act != null) {
                        initAppContext(act.applicationContext)
                        currentActivity = act
                        onActivityPreCreated(act)
                    }
                    val res = chain.proceed()
                    if (act != null) {
                        val reqId = act.intent?.let { extractExactOrderIdFromIntent(it) } ?: 0
                        if (reqId != 0) {
                            handlePostOrderActivityCreate(act, reqId)
                        }
                    }
                    return res
                }
            })
        } catch (_: Throwable) {
            isIncomingDispatchHooked.set(false)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 0. IncomingDispatchActivity.onCreate (matching Ls20.d)
        hookIncomingDispatchActivity(module, classLoader)

        // 1. Firebase Cloud Messaging (matching Ls90.f)
        hookFirebase(module, classLoader)

        // 2. Instrumentation.execStartActivity (matching Lfs0.d)
        hookInstrumentation(module)

        // 3. javax.crypto.Cipher.doFinal for "driverId : " (matching Lkh.b)
        hookCipher(module)

        // 4. UI / Dialog / WebSocket / SQLite / SharedPreferences hooks for complete order capture
        hookUI(module, classLoader)
        hookDialogShow(module)
        hookWebSocket(module, classLoader)
        hookSQLite(module)
        hookSharedPreferences(module, classLoader)

        // 5. OkHttp network interception
        val hookCount = hookOkHttp(module, classLoader)
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
                                        val proxyCb = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                                            when (method.name) {
                                                "equals" -> proxy === args?.getOrNull(0)
                                                "hashCode" -> System.identityHashCode(proxy)
                                                "toString" -> originalCb.toString()
                                                else -> {
                                                    if (method.name == "onResponse" && args != null && args.size >= 2) {
                                                        val resp = args[1]
                                                        if (resp != null) {
                                                            try { processHttpResponse(resp) } catch (_: Throwable) {}
                                                        }
                                                    }
                                                    method.invoke(originalCb, *(args ?: emptyArray()))
                                                }
                                            }
                                        }
                                        return chain.proceed(arrayOf(proxyCb))
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
                            val interceptorProxy = Proxy.newProxyInstance(classLoader, arrayOf(interceptorClass)) { proxy, method, args ->
                                when (method.name) {
                                    "equals" -> proxy === args?.getOrNull(0)
                                    "hashCode" -> System.identityHashCode(proxy)
                                    "toString" -> "SanedOkHttpInterceptor"
                                    "intercept" -> {
                                        if (args != null && args.isNotEmpty()) {
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
                                    else -> null
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

    private val isInstrumentationHooked = AtomicBoolean(false)

    /**
     * Matches Lfs0.d, Lfs0.c, Lfs0.e, and Lfs0$b in SanedAssistant-98-7bac1ec.apk:
     * Intercepts Instrumentation.execStartActivity to:
     * 1. BLOCK opening IncomingDispatchActivity if the order was already handled in background and stay_in_background_v2 is enabled.
     * 2. Strip Intent.FLAG_ACTIVITY_CLEAR_TASK (0x8000) via Lfs0.e so MainActivity stays alive in the task stack.
     */
    private fun hookInstrumentation(module: XposedModule) {
        if (!isInstrumentationHooked.compareAndSet(false, true)) return
        try {
  val instClass = Class.forName("android.app.Instrumentation")
  for (m in instClass.declaredMethods) {
      if (m.name == "execStartActivity") {
          val paramTypes = m.parameterTypes
          val intentIdx = paramTypes.indexOfFirst { it == Intent::class.java }
          val activityIdx = paramTypes.indexOfFirst { it.name == "android.app.Activity" }
          if (intentIdx >= 0 && activityIdx >= 0) {
              module.hook(m).intercept(object : XposedInterface.Hooker {
                  override fun intercept(chain: XposedInterface.Chain): Any? {
                      try {
                          val intent = chain.args.getOrNull(intentIdx) as? Intent
                          if (intent != null) {
                              currentActivity?.let { extractAndProcessOrderFromIntent(intent, it) }
                              val callerObj = chain.args.getOrNull(activityIdx)
                              val orderIdInt = extractExactOrderIdFromIntent(intent)
                              if (callerObj == null && orderIdInt != 0 && !isDryRun) {
                                  val entry = handledOrdersMap[orderIdInt]
                                  if (entry != null && (entry.decision == "ACCEPT" || entry.decision == "REJECT")) {
                                      if ((SystemClock.elapsedRealtime() - entry.timestampRealtime) <= 60_000L) {
                                          if (isStayInBackground && entry.wasInBackground) return null
                                          val flags = intent.flags
                                          if ((flags and 0x8000) != 0) intent.flags = flags and (-32769)
                                      }
                                  }
                              }
                          }
                      } catch (_: Throwable) {}
                      return chain.proceed()
                  }
              })
          }
      }
  }
        } catch (_: Throwable) {}
    }

    private val hookedFcmClasses = ConcurrentHashMap.newKeySet<String>()

    /**
     * Matches Ls90.f, Ls90.e, Ls90.g, Ls90.b, and Ls90$a in SanedAssistant-98-7bac1ec.apk:
     * Hooks SanedFCMNotificationHandler (Native.d()), extracts order fields via Ldy1 (Native.l()),
     * and suppresses chain.proceed() (returns null) when an order is auto-rejected (not dry-run).
     */
    private fun hookFirebase(module: XposedModule, classLoader: ClassLoader) {
        val candidateClasses = mutableSetOf(Native.safeD(), "com.google.firebase.messaging.FirebaseMessagingService").filter { it.isNotEmpty() }.toMutableSet()
        try {
  val ctx = appContext ?: currentActivity
  if (ctx != null) {
      val intent = Intent("com.google.firebase.messaging.MESSAGING_EVENT").apply { setPackage("net.jahez.fleets") }
      ctx.packageManager.queryIntentServices(intent, 0)?.forEach { it.serviceInfo?.name?.let { n -> candidateClasses.add(n) } }
  }
        } catch (_: Throwable) {}

        for (clsName in candidateClasses) {
  if (!hookedFcmClasses.add(clsName)) continue
  try {
      val fmsClass = Class.forName(clsName, false, classLoader)
      for (m in fmsClass.declaredMethods) {
          if (m.parameterCount == 1 && !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
              val paramCls = m.parameterTypes[0]
              val hasMapGetter = paramCls.methods.any { it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType) }
              if (!hasMapGetter && clsName != Native.safeD()) continue
              module.hook(m).intercept(object : XposedInterface.Hooker {
                  override fun intercept(chain: XposedInterface.Chain): Any? {
                      var decisionAction = ""
                      try {
                          val remoteMessage = chain.args.getOrNull(0)
                          if (remoteMessage != null) {
                              val getDataMethod = try { remoteMessage.javaClass.getMethod("getData") } catch (_: Throwable) {
                                  remoteMessage.javaClass.methods.firstOrNull { it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType) }
                              }
                              val dataMap = getDataMethod?.invoke(remoteMessage) as? Map<*, *>
                              if (dataMap != null && dataMap.isNotEmpty()) {
                                  decisionAction = processFcmMapWithLs90(dataMap)
                                  if (decisionAction.isEmpty()) {
                                      val json = JSONObject()
                                      for ((k, v) in dataMap) if (k != null && v != null) json.put(k.toString(), v.toString())
                                      extractAndProcessOrderJson(json, "FCM Push 📲")
                                  }
                              }
                          }
                      } catch (_: Throwable) {}
                      if (decisionAction == "REJECT" && !isDryRun) return null
                      return chain.proceed()
                  }
              })
          }
      }
  } catch (_: Throwable) { hookedFcmClasses.remove(clsName) }
        }
    }

    private fun processFcmMapWithLs90(dataMap: Map<*, *>): String {
        val keys = Native.safeL()
        if (keys.size < 18) return ""
        val reqId = (dataMap[keys[0]] as? String)?.trim()?.toIntOrNull() ?: return ""
        if (reqId == 0) return ""
        fun mapDouble(k: String): Double = (dataMap[k] as? String)?.trim()?.toDoubleOrNull() ?: 0.0
        fun mapFirstStr(vararg ks: String): String {
  for (k in ks) { val v = (dataMap[k] as? String)?.trim(); if (!v.isNullOrEmpty()) return v }
  return ""
        }
        val pLat = mapDouble(keys[1]); val pLng = mapDouble(keys[2])
        val dLat = mapDouble(keys[3]); val dLng = mapDouble(keys[4])
        val delivPrice = mapDouble(keys[5]); val distVal = mapDouble(keys[7])
        val restName = mapFirstStr(keys[9], keys[10], keys[11], keys[12])
        val bonusAmt = mapFirstStr(keys[13], keys[14], keys[15]).toDoubleOrNull() ?: 0.0
        val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
        val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
        val calcRestDist = if (pLat != 0.0 && pLng != 0.0 && drvLat != 0.0 && drvLng != 0.0) LocationEngine.calculateDistance(drvLat, drvLng, pLat, pLng) else distVal
        val calcCustDist = if (pLat != 0.0 && pLng != 0.0 && dLat != 0.0 && dLng != 0.0) LocationEngine.calculateDistance(pLat, pLng, dLat, dLng) else 0.0
        val cleanPrice = String.format(Locale.US, "%.2f", delivPrice + bonusAmt).toDoubleOrNull() ?: (delivPrice + bonusAmt)
        lastOrderEvent = "Order #$reqId detected via FCM Push (Ls90)"
        return handleOrderViaApi(orderId = "#$reqId", price = cleanPrice, dist = calcRestDist, restaurant = restName, custDist = calcCustDist, sourceChannel = "PUSH")
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

    /**
     * Proactive direct SQLite database scanner matching original app Lb11.f
     * Directly opens Jahez local SQLite/Room databases via SQLiteDatabase.openDatabase
     */
    // Matches Lnz.x(Cursor, int) in SanedAssistant-98-7bac1ec.apk
    private fun cursorDoubleOrNull(cursor: Cursor, index: Int): Double? {
        if (index < 0 || index >= cursor.columnCount) return null
        return try {
            if (cursor.isNull(index)) null else cursor.getDouble(index)
        } catch (_: Throwable) { null }
    }

    // Matches Lnz.y(Cursor, int) in SanedAssistant-98-7bac1ec.apk
    private fun cursorStringOrNull(cursor: Cursor, index: Int): String? {
        if (index < 0 || index >= cursor.columnCount) return null
        return try {
            if (cursor.isNull(index)) return null
            val s = cursor.getString(index)?.trim()
            if (s.isNullOrEmpty() || s == "null") null else s
        } catch (_: Throwable) { null }
    }

    fun normalizeOrderStatusDisplay(rawStatus: String): String {
        val u = rawStatus.trim().uppercase(Locale.US)
        return when {
            u.contains("DELIVERED") || u == "DELIVER" -> "Delivered"
            u.contains("COMPLETED") || u == "COMPLETE" -> "Completed"
            u.contains("PICKED") || u.contains("COLLECTED") -> "Picked Up"
            u.contains("ARRIVED") -> "Arrived"
            u.contains("WAY") || u.contains("PROGRESS") -> "In Progress"
            u.contains("ACCEPT") || u.contains("قبول") -> "Accepted"
            u.contains("CANCEL") -> "Cancelled"
            u.contains("REJECT") || u.contains("رفض") -> rawStatus
            u.contains("FAIL") || u.contains("MISSED") || u.contains("MANUAL") || u.contains("DRY") -> rawStatus
            rawStatus.isNotEmpty() -> rawStatus.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
            else -> "Accepted"
        }
    }

    fun isHistoricalOrActiveStatus(rawStatus: String): Boolean {
        val u = rawStatus.trim().uppercase(Locale.US)
        if (u.isEmpty()) return false
        return u.contains("DELIVER") ||
               u.contains("COMPLET") ||
               u.contains("COLLECT") ||
               u.contains("PICKED") ||
               u.contains("ACCEPT") ||
               u.contains("ARRIV") ||
               u.contains("PROGRESS") ||
               u.contains("WAY") ||
               u.contains("CANCEL") ||
               u.contains("STARTED")
    }

    /**
     * Proactive direct SQLite database scanner matching original app Lb11.f & Lnz.w(Cursor):
     * Opens Jahez local SQLite/Room databases via SQLiteDatabase.openDatabase and reads both
     * active orders and old/delivered orders on login so they immediately appear in the Orders tab.
     */
    private fun parseCursorRowWithLnzW(cursor: Cursor) {
        val colCount = cursor.columnCount
        if (colCount < 13) return
        val posIdDouble = cursorDoubleOrNull(cursor, 0)
        val posIdStr = cursorStringOrNull(cursor, 0)
        val posRef = cursorStringOrNull(cursor, 1)
        val posStatus = cursorStringOrNull(cursor, 2)
        val posRestAr = cursorStringOrNull(cursor, 3)
        val posRestEn = cursorStringOrNull(cursor, 4)
        val posCustName = cursorStringOrNull(cursor, 5)
        val posCustPhone = cursorStringOrNull(cursor, 6)
        val posPickupLat = cursorDoubleOrNull(cursor, 7) ?: 0.0
        val posPickupLng = cursorDoubleOrNull(cursor, 8) ?: 0.0
        val posDestLat = cursorDoubleOrNull(cursor, 9) ?: 0.0
        val posDestLng = cursorDoubleOrNull(cursor, 10) ?: 0.0
        val posDistKm = cursorDoubleOrNull(cursor, 11) ?: 0.0
        val posPrice = cursorDoubleOrNull(cursor, 12) ?: 0.0
        val posCash = cursorDoubleOrNull(cursor, 13) ?: 0.0
        val posCurrency = cursorStringOrNull(cursor, 14) ?: "SAR"
        val posPayMethod = cursorStringOrNull(cursor, 15) ?: ""

        val addrParts = mutableListOf<String>()
        for (cIdx in 16..minOf(27, colCount - 1)) {
            val part = cursorStringOrNull(cursor, cIdx)
            if (!part.isNullOrEmpty() && !part.startsWith("http")) {
                addrParts.add(part)
            }
        }
        val posAddr = addrParts.joinToString(", ")

        val idStr = when {
            posIdDouble != null && posIdDouble > 100.0 -> posIdDouble.toLong().toString()
            !posIdStr.isNullOrEmpty() && posIdStr.all { it.isDigit() } && posIdStr.length >= 3 -> posIdStr
            !posRef.isNullOrEmpty() && posRef.any { it.isDigit() } -> posRef
            else -> return
        }
        val restName = when {
            !posRestAr.isNullOrEmpty() && !posRestEn.isNullOrEmpty() && posRestAr != posRestEn -> "$posRestEn - $posRestAr"
            !posRestAr.isNullOrEmpty() -> posRestAr
            !posRestEn.isNullOrEmpty() -> posRestEn
            else -> ""
        }
        val refStr = if (!posRef.isNullOrEmpty()) posRef else idStr
        val rawStatus = posStatus ?: "Delivered"
        val cachedDetail = customerDetailsCache[idStr] ?: customerDetailsCache[refStr]
        val finalCustName = posCustName ?: cachedDetail?.first ?: ""
        val finalCustPhone = posCustPhone ?: cachedDetail?.second ?: ""
        val finalCash = if (posCash > 0.0) posCash else (cachedDetail?.third ?: 0.0)
        val effectiveCash = if (posPayMethod.contains("PREPAID", ignoreCase = true) || posPayMethod.contains("ONLINE", ignoreCase = true)) 0.0 else finalCash

        var calcDist = posDistKm
        if (calcDist == 0.0 && posPickupLat != 0.0 && posPickupLng != 0.0) {
            val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
            val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
            if (drvLat != 0.0 && drvLng != 0.0) {
                calcDist = LocationEngine.calculateDistance(drvLat, drvLng, posPickupLat, posPickupLng)
            }
        }
        var calcCustDist = 0.0
        if (posPickupLat != 0.0 && posPickupLng != 0.0 && posDestLat != 0.0 && posDestLng != 0.0) {
            calcCustDist = LocationEngine.calculateDistance(posPickupLat, posPickupLng, posDestLat, posDestLng)
        }

        val syncSignature = "$idStr:$rawStatus:${finalCustPhone.isNotEmpty()}"
        if (loggedOrderIds.add(syncSignature)) {
            val normStatus = normalizeOrderStatusDisplay(rawStatus)
            sendOrderToLog(
                orderId = "#$idStr",
                price = posPrice,
                dist = calcDist,
                restaurant = restName,
                status = normStatus,
                customerName = finalCustName,
                customerPhone = finalCustPhone,
                address = posAddr.ifEmpty { if (posDestLat != 0.0) "Location on map" else "" },
                cashAmount = effectiveCash,
                currency = posCurrency,
                reference = refStr,
                customerDistanceKm = calcCustDist,
                notifyUser = false
            )
        }

        val uSt = rawStatus.uppercase(Locale.US)
        val isCompleteOrOld = uSt.contains("DELIVER") || uSt.contains("COMPLET") || uSt.contains("CANCEL") || uSt.contains("REJECT")
        if (finalCustPhone.isEmpty() && !isCompleteOrOld) {
            fetchOrderCustomerDetailsAsync(idStr)
        }
    }

    private fun invokeNativeInit(context: Context) {
        try { Native.safeA(context, "dev.jing.sanedhook") } catch (_: Throwable) {}
    }

    private fun invokeNativeString(methodName: String): String = when (methodName) {
        "d" -> Native.safeD()
        "e" -> Native.safeE()
        "f" -> Native.safeF()
        "g" -> Native.safeG()
        "h" -> Native.safeH()
        "i" -> Native.safeI()
        "j" -> Native.safeJ()
        "q" -> Native.safeQ()
        else -> ""
    }

    fun scanJahezDatabases(context: Context) {
        try {
            // 1. Exact SanedAssistant-98 Lb11.f SQLite query via Ljy1.c (Native.f) and Ljy1.d (Native.g)
            try {
                invokeNativeInit(context)
                val exactDbName = invokeNativeString("f")
                val exactSql = invokeNativeString("g")
                if (exactDbName.isNotEmpty() && exactSql.isNotEmpty()) {
                    val dbFile = context.getDatabasePath(exactDbName)
                    if (dbFile.exists()) {
                        var exactDb: SQLiteDatabase? = null
                        var exactCursor: Cursor? = null
                        try {
                            val openedExactDb: SQLiteDatabase = try {
                                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                            } catch (_: Throwable) {
                                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                            }
                            exactDb = openedExactDb
                            val c = openedExactDb.rawQuery(exactSql, null)
                            exactCursor = c
                            while (c.moveToNext()) {
                                parseCursorRowWithLnzW(c)
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try { exactCursor?.close() } catch (_: Throwable) {}
                            try { exactDb?.close() } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {}

            val seenFiles = HashSet<String>()
            val dbDir = File(context.applicationInfo.dataDir, "databases")
            if (dbDir.exists() && dbDir.isDirectory) {
                val files = dbDir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.isFile && !f.name.endsWith("-wal") && !f.name.endsWith("-shm") && !f.name.endsWith("-journal")) {
                        if (seenFiles.add(f.absolutePath)) {
                            scanDatabaseFile(f)
                        }
                    }
                }
            }

            val dbList = try { context.databaseList() } catch (_: Throwable) { null } ?: emptyArray()
            for (dbName in dbList) {
                val dbFile = context.getDatabasePath(dbName)
                if (dbFile.exists() && !dbFile.name.endsWith("-wal") && !dbFile.name.endsWith("-shm") && !dbFile.name.endsWith("-journal")) {
                    if (seenFiles.add(dbFile.absolutePath)) {
                        scanDatabaseFile(dbFile)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scanDatabaseFile(file: File) {
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        try {
            // Matches Lb11.f instruction 033 (0x20000000 = ENABLE_WRITE_AHEAD_LOGGING) so Room -wal orders are visible immediately after login
            val activeDb: SQLiteDatabase = try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
            } catch (_: Throwable) {
                try {
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                } catch (_: Throwable) {
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                }
            }
            db = activeDb
            val tableCursor = activeDb.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                null
            )
            val allTables = mutableListOf<String>()
            while (tableCursor.moveToNext()) {
                val name = tableCursor.getString(0) ?: continue
                allTables.add(name)
            }
            tableCursor.close()

            // Prioritize order/trip/delivery tables first, but inspect all user tables so obfuscated table names are never missed
            val sortedTables = allTables.sortedByDescending { name ->
                val l = name.lowercase(Locale.US)
                if (l.contains("order") || l.contains("trip") || l.contains("deliver") || l.contains("dispatch") || l.contains("shipment") || l.contains("task")) 1 else 0
            }

            for (table in sortedTables) {
                try {
                    val rowCursor = activeDb.rawQuery("SELECT * FROM \"$table\" ORDER BY rowid DESC LIMIT 25", null)
                    cursor = rowCursor
                    val colCount = rowCursor.columnCount
                    val colNames = rowCursor.columnNames
                    while (rowCursor.moveToNext()) {
                        val cursor = rowCursor
                        // 1. Check if row matches Lnz.w(Cursor) 28-column positional schema (colCount >= 13)
                        if (colCount >= 13) {
                            val posIdDouble = cursorDoubleOrNull(cursor, 0)
                            val posIdStr = cursorStringOrNull(cursor, 0)
                            val posRef = cursorStringOrNull(cursor, 1)
                            val posStatus = cursorStringOrNull(cursor, 2)
                            val posRestAr = cursorStringOrNull(cursor, 3)
                            val posRestEn = cursorStringOrNull(cursor, 4)
                            val posCustName = cursorStringOrNull(cursor, 5)
                            val posCustPhone = cursorStringOrNull(cursor, 6)
                            val posPickupLat = cursorDoubleOrNull(cursor, 7) ?: 0.0
                            val posPickupLng = cursorDoubleOrNull(cursor, 8) ?: 0.0
                            val posDestLat = cursorDoubleOrNull(cursor, 9) ?: 0.0
                            val posDestLng = cursorDoubleOrNull(cursor, 10) ?: 0.0
                            val posDistKm = cursorDoubleOrNull(cursor, 11) ?: 0.0
                            val posPrice = cursorDoubleOrNull(cursor, 12) ?: 0.0
                            val posCash = cursorDoubleOrNull(cursor, 13) ?: 0.0
                            val posCurrency = cursorStringOrNull(cursor, 14) ?: "SAR"
                            val posPayMethod = cursorStringOrNull(cursor, 15) ?: ""

                            val addrParts = mutableListOf<String>()
                            for (cIdx in 16..minOf(27, colCount - 1)) {
                                val part = cursorStringOrNull(cursor, cIdx)
                                if (!part.isNullOrEmpty() && !part.startsWith("http")) {
                                    addrParts.add(part)
                                }
                            }
                            val posAddr = addrParts.joinToString(", ")

                            val idStr = when {
                                posIdDouble != null && posIdDouble > 100.0 -> posIdDouble.toLong().toString()
                                !posIdStr.isNullOrEmpty() && posIdStr.all { it.isDigit() } && posIdStr.length >= 3 -> posIdStr
                                !posRef.isNullOrEmpty() && posRef.any { it.isDigit() } -> posRef
                                else -> ""
                            }
                            val restName = when {
                                !posRestAr.isNullOrEmpty() && !posRestEn.isNullOrEmpty() && posRestAr != posRestEn -> "$posRestEn - $posRestAr"
                                !posRestAr.isNullOrEmpty() -> posRestAr
                                !posRestEn.isNullOrEmpty() -> posRestEn
                                else -> ""
                            }

                            val isKnownPosStatus = !posStatus.isNullOrEmpty() && (
                                isHistoricalOrActiveStatus(posStatus) ||
                                posStatus.equals("NEW", true) ||
                                posStatus.equals("PENDING", true) ||
                                posStatus.equals("ASSIGNED", true) ||
                                posStatus.equals("REJECTED", true)
                            )
                            if (idStr.isNotEmpty() && isKnownPosStatus && restName.isNotEmpty()) {
                                val refStr = if (!posRef.isNullOrEmpty()) posRef else idStr
                                val rawStatus = posStatus ?: "Delivered"
                                val cachedDetail = customerDetailsCache[idStr] ?: customerDetailsCache[refStr]
                                val finalCustName = posCustName ?: cachedDetail?.first ?: ""
                                val finalCustPhone = posCustPhone ?: cachedDetail?.second ?: ""
                                val finalCash = if (posCash > 0.0) posCash else (cachedDetail?.third ?: 0.0)
                                val effectiveCash = if (posPayMethod.contains("PREPAID", ignoreCase = true) || posPayMethod.contains("ONLINE", ignoreCase = true)) 0.0 else finalCash

                                var calcDist = posDistKm
                                if (calcDist == 0.0 && posPickupLat != 0.0 && posPickupLng != 0.0) {
                                    val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
                                    val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
                                    if (drvLat != 0.0 && drvLng != 0.0) {
                                        calcDist = LocationEngine.calculateDistance(drvLat, drvLng, posPickupLat, posPickupLng)
                                    }
                                }
                                var calcCustDist = 0.0
                                if (posPickupLat != 0.0 && posPickupLng != 0.0 && posDestLat != 0.0 && posDestLng != 0.0) {
                                    calcCustDist = LocationEngine.calculateDistance(posPickupLat, posPickupLng, posDestLat, posDestLng)
                                }

                                val syncSignature = "$idStr:$rawStatus:${finalCustPhone.isNotEmpty()}"
                                if (loggedOrderIds.add(syncSignature)) {
                                    val normStatus = normalizeOrderStatusDisplay(rawStatus)
                                    sendOrderToLog(
                                        orderId = "#$idStr",
                                        price = posPrice,
                                        dist = calcDist,
                                        restaurant = restName,
                                        status = normStatus,
                                        customerName = finalCustName,
                                        customerPhone = finalCustPhone,
                                        address = posAddr.ifEmpty { if (posDestLat != 0.0) "Location on map" else "" },
                                        cashAmount = effectiveCash,
                                        currency = posCurrency,
                                        reference = refStr,
                                        customerDistanceKm = calcCustDist,
                                        notifyUser = false
                                    )
                                }

                                // Matches Lb11.c -> Ljw.a: fetch customer phone for active orders missing phone
                                val uSt = rawStatus.uppercase(Locale.US)
                                val isCompleteOrOld = uSt.contains("DELIVER") || uSt.contains("COMPLET") || uSt.contains("CANCEL") || uSt.contains("REJECT")
                                if (finalCustPhone.isEmpty() && !isCompleteOrOld) {
                                    fetchOrderCustomerDetailsAsync(idStr)
                                }
                                continue
                            }
                        }

                        // 2. Column-name & JSON column extraction for any SQLite table schema
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
                                            extractAndProcessOrderJson(JSONObject(strVal), "DB Row JSON ($table.${colNames[i]}) 💾", isFromLocalDb = true)
                                        } catch (_: Throwable) {}
                                    }
                                }
                                Cursor.FIELD_TYPE_BLOB -> {
                                    val bytes = cursor.getBlob(i)
                                    if (bytes != null) {
                                        val s = String(bytes, Charsets.UTF_8).trim()
                                        if (s.startsWith("{") && s.endsWith("}")) {
                                            try {
                                                extractAndProcessOrderJson(JSONObject(s), "DB Blob JSON ($table.${colNames[i]}) 💾", isFromLocalDb = true)
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            }
                        }
                        extractAndProcessOrderJson(json, "DB Table ($table) 💾", isFromLocalDb = true)
                    }
                    cursor.close()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {} finally {
            try { cursor?.close() } catch (_: Throwable) {}
            try { db?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Matches Ljw.a(context, orderId) in SanedAssistant-98-7bac1ec.apk:
     * Queries Jahez Order Details API for an active orderId to populate customerName & customerPhone.
     */
    private fun fetchOrderCustomerDetailsAsync(orderId: String) {
        val cleanId = orderId.replace("#", "").trim()
        if (cleanId.isEmpty() || !fetchedDetailIds.add(cleanId)) return
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        if (token.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val client = capturedAppHttpClient ?: httpClient
            val host = if (cachedApiHost.isNotEmpty()) cachedApiHost else "https://api.jahez.net"
            val detailPaths = listOf(
                "/api/fleets/orders/$cleanId",
                "/api/orders/$cleanId",
                "/api/v1/driver/orders/$cleanId",
                "/v2/fleet/orders/$cleanId"
            )
            for (path in detailPaths) {
                try {
                    val reqBuilder = Request.Builder().url(buildCandidateUrl(host, path)).get()
                    applyJahezHeaders(reqBuilder, token)
                    val resp = client.newCall(reqBuilder.build()).execute()
                    val code = resp.code
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    if (code in 200..299 && body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data") ?: json.optJSONObject("order") ?: json
                        val custObj = dataObj.optJSONObject("customer") ?: dataObj.optJSONObject("client")
                        val cName = extractFirstString(dataObj, "customer_name", "client_name", "customerName", "clientName")
                            .ifEmpty { if (custObj != null) extractFirstString(custObj, "name", "full_name", "customer_name") else "" }
                        val serverKeys = Native.safeM()
                        val kClientPhone = serverKeys.getOrNull(5) ?: "clientPhone"
                        val kContactNum = serverKeys.getOrNull(6) ?: "contactNumber"
                        val cPhone = extractFirstString(dataObj, kClientPhone, kContactNum, "customer_phone", "client_phone", "customerPhone", "phone", "mobile")
                            .ifEmpty { if (custObj != null) extractFirstString(custObj, kClientPhone, kContactNum, "phone", "mobile", "phone_number") else "" }
                        val cCash = extractFirstDouble(dataObj, "cash_amount", "cashAmount", "cod_amount", "collect_cash")
                        if (cPhone.isNotEmpty() || cName.isNotEmpty()) {
                            customerDetailsCache[cleanId] = Triple(cName, cPhone, cCash)
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    @Volatile var capturedDriverCipherB64: String = ""

    private val DRIVER_ID_PREFIX = "driverId : ".toByteArray(Charsets.UTF_8)

    private fun startsWithDriverIdPrefix(bytes: ByteArray): Boolean {
        if (bytes.size < DRIVER_ID_PREFIX.size) return false
        for (i in DRIVER_ID_PREFIX.indices) if (bytes[i] != DRIVER_ID_PREFIX[i]) return false
        return true
    }

    private fun hookCipher(module: XposedModule) {
        try {
  val mDoFinal = Cipher::class.java.getDeclaredMethod("doFinal", ByteArray::class.java)
  module.hook(mDoFinal).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val result = chain.proceed()
          val inputBytes = chain.args.getOrNull(0) as? ByteArray
          val outputBytes = result as? ByteArray
          if (inputBytes != null && outputBytes != null) {
              try {
                  if (startsWithDriverIdPrefix(inputBytes)) {
                      capturedDriverCipherB64 = Base64.encodeToString(outputBytes, Base64.NO_WRAP)
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
                    if (isMasterRunning) {
                        scanAllRoots()
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, 250)
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
        val ctx = resolveApplicationContext() ?: currentActivity
        if (cachedAuthToken.isEmpty() && ctx != null) readExactJahezEncryptedPrefs(ctx)
        val token = cachedAuthToken
        if (token.isEmpty()) return
        val clientToUse = capturedAppHttpClient ?: httpClient

        if (Native.isLoaded) {
  try {
      if (ctx != null) Native.safeA(ctx, "dev.jing.sanedhook")
      val rawTok = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
      val spec = Native.safeB(2, 0, rawTok, cachedLanguage, capturedDriverCipherB64)
      if (spec.size >= 2 && spec[1].startsWith("http")) {
          pollingHits.incrementAndGet()
          val rb = Request.Builder().url(spec[1]).get()
          var idx = 2
          while (idx + 1 < spec.size) { rb.header(spec[idx], spec[idx + 1]); idx += 2 }
          val resp = clientToUse.newCall(rb.build()).execute()
          val code = resp.code
          lastHttpResponseCode = code
          val body = resp.body?.string() ?: ""
          resp.close()
          if (code in 200..299 && body.isNotEmpty()) {
              failedPollingCount = 0
              findAndParseEmbeddedJson(body, "Server Polling (POLL) ⚡")
              return
          }
      }
  } catch (_: Throwable) {}
        }

        if (lastOrdersListUrl.isEmpty()) return
        val candidateEndpoints = listOf("/api/fleets/orders/available", "/api/v1/fleets/orders/available", "/api/fleets/orders")
        val probeHosts = mutableListOf<String>()
        if (lastOrdersListUrl.isNotEmpty()) probeHosts.add(lastOrdersListUrl)
        if (cachedApiHost.isNotEmpty()) probeHosts.add(cachedApiHost)
        probeHosts.addAll(candidateApiHosts)

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

            val dispatchesKey = Native.safeM().getOrNull(0) ?: "dispatches"
            val dataArray = json.optJSONArray(dispatchesKey) ?: json.optJSONArray("data") ?: json.optJSONArray("orders") ?: json.optJSONArray("result")
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
            val v = json.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        // Case-insensitive & normalized underscore fallback for SQLite/Room columns
        val keyMap = HashMap<String, String>()
        val it = json.keys()
        while (it.hasNext()) {
            val actualKey = it.next()
            keyMap[actualKey.lowercase(Locale.US)] = actualKey
            keyMap[actualKey.lowercase(Locale.US).replace("_", "")] = actualKey
        }
        for (k in keys) {
            val lk = k.lowercase(Locale.US)
            val matchedKey = keyMap[lk] ?: keyMap[lk.replace("_", "")]
            if (matchedKey != null) {
                val v = json.optString(matchedKey, "").trim()
                if (v.isNotEmpty() && v != "null") return v
            }
        }
        return ""
    }

    private fun extractFirstDouble(json: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (json.has(k)) {
                val d = json.optDouble(k, 0.0)
                if (!d.isNaN() && d > 0.0) return d
            }
        }
        val keyMap = HashMap<String, String>()
        val it = json.keys()
        while (it.hasNext()) {
            val actualKey = it.next()
            keyMap[actualKey.lowercase(Locale.US)] = actualKey
            keyMap[actualKey.lowercase(Locale.US).replace("_", "")] = actualKey
        }
        for (k in keys) {
            val lk = k.lowercase(Locale.US)
            val matchedKey = keyMap[lk] ?: keyMap[lk.replace("_", "")]
            if (matchedKey != null) {
                val d = json.optDouble(matchedKey, 0.0)
                if (!d.isNaN() && d > 0.0) return d
            }
        }
        return 0.0
    }

    private fun extractAndProcessOrderJson(json: JSONObject, channelSource: String, isFromLocalDb: Boolean = false) {
        try {
            var orderId = extractFirstString(json, "requestId", "dispatchId", "order_number", "order_id", "orderNumber", "orderId", "id", "referenceId", "code", "trip_id", "tripId")
            var reference = extractFirstString(json, "referenceId", "reference", "requestId", "order_number", "orderNumber", "order_code", "code", "id")
            var price = extractFirstDouble(json, "deliveryPrice", "price", "delivery_cost", "delivery_fee", "deliveryFee", "deliveryCost", "driver_fee", "driverFee", "earning", "amount", "total")
            val bonusVal = extractFirstDouble(json, "bonus", "bonusAmount", "extraBonus")
            if (bonusVal > 0.0) price += bonusVal
            var dist = extractFirstDouble(json, "distance", "km", "distance_to_pickup", "distance_in_km", "distanceInKm", "distanceKm")
            var restaurant = extractFirstString(json, "subscriberName", "vendorName", "restaurantName", "storeName", "store_name", "restaurant_name", "store_name_ar", "store_name_en", "restaurant_name_ar", "restaurant_name_en", "branch_name", "branchName", "vendor_name", "merchant_name", "merchantName", "shop_name", "shopName", "pickup_name", "pickupName", "restaurant", "store")
            var customerName = extractFirstString(json, "customer_name", "client_name", "customerName", "clientName", "recipient_name", "user_name", "customer")
            var customerPhone = extractFirstString(json, "clientPhone", "contactNumber", "customer_phone", "client_phone", "customerPhone", " phone", "mobile", "contact_number")
            var address = extractFirstString(json, "customer_address", "delivery_address", "dropoff_address", "address", "dropoffLocation", "destination_address", "location_address")
            var cashAmount = extractFirstDouble(json, "collectionAmount", "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
            var currency = extractFirstString(json, "currency", "currencyCode", "curr").ifEmpty { "SAR" }
            var status = extractFirstString(json, "dispatchType", "status", "order_status", "state").ifEmpty { "Accepted ✅" }

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

            val pickupObj = json.optJSONObject("pickup") ?: dataObj?.optJSONObject("pickup") ?: json.optJSONObject("store") ?: dataObj?.optJSONObject("store") ?: json.optJSONObject("branch") ?: dataObj?.optJSONObject("branch")
            if (pickupObj != null) {
                if (restaurant.isEmpty()) restaurant = extractFirstString(pickupObj, "name", "store_name", "restaurant_name")
            }

            // Coordinate-based Haversine distance calculation matching Lb11.e, Ls90.g, and Lqz.a
            var custDist = 0.0
            run {
                var pLat = extractFirstDouble(json, "dispatchPickupLat", "pickupLatitude", "pickup_lat", "pickupLat", "store_lat", "branch_lat", "restaurant_lat")
                var pLng = extractFirstDouble(json, "dispatchPickupLng", "pickupLongitude", "pickup_lng", "pickupLng", "pickup_lon", "store_lng", "branch_lng", "restaurant_lng")
                var dLat = extractFirstDouble(json, "dispatchDestLat", "destinationLatitude", "dest_lat", "destLat", "dropoff_lat", "customer_lat")
                var dLng = extractFirstDouble(json, "dispatchDestLng", "destinationLongitude", "dest_lng", "destLng", "dropoff_lng", "customer_lng")

                if (pLat == 0.0 && pickupObj != null) {
                    pLat = extractFirstDouble(pickupObj, "lat", "latitude")
                    pLng = extractFirstDouble(pickupObj, "lng", "lon", "longitude")
                    val coords = pickupObj.optJSONArray("coordinates") ?: pickupObj.optJSONArray("location")
                    if (coords != null && coords.length() >= 2) {
                        // In Lb11.e (008f..0096), GeoJSON coordinates are [lng, lat]
                        val c0 = coords.optDouble(0, 0.0)
                        val c1 = coords.optDouble(1, 0.0)
                        if (c1 in 15.0..35.0 && c0 in 34.0..56.0) { pLng = c0; pLat = c1 }
                        else if (c0 in 15.0..35.0) { pLat = c0; pLng = c1 }
                        else { pLng = c0; pLat = c1 }
                    }
                }
                val destObj = json.optJSONObject("destination") ?: dataObj?.optJSONObject("destination") ?: dropoffObj
                if (dLat == 0.0 && destObj != null) {
                    dLat = extractFirstDouble(destObj, "lat", "latitude")
                    dLng = extractFirstDouble(destObj, "lng", "lon", "longitude")
                    val dCoords = destObj.optJSONArray("coordinates") ?: destObj.optJSONArray("location")
                    if (dCoords != null && dCoords.length() >= 2) {
                        val c0 = dCoords.optDouble(0, 0.0)
                        val c1 = dCoords.optDouble(1, 0.0)
                        if (c1 in 15.0..35.0 && c0 in 34.0..56.0) { dLng = c0; dLat = c1 }
                        else if (c0 in 15.0..35.0) { dLat = c0; dLng = c1 }
                        else { dLng = c0; dLat = c1 }
                    }
                }

                val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
                val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
                if (dist == 0.0 && pLat != 0.0 && pLng != 0.0 && drvLat != 0.0 && drvLng != 0.0) {
                    dist = LocationEngine.calculateDistance(drvLat, drvLng, pLat, pLng)
                }
                if (pLat != 0.0 && pLng != 0.0 && dLat != 0.0 && dLng != 0.0) {
                    custDist = LocationEngine.calculateDistance(pLat, pLng, dLat, dLng)
                }
            }

            val rawStatusFromJson = extractFirstString(json, "status", "order_status", "state", "orderStatus")
                .ifEmpty { if (dataObj != null) extractFirstString(dataObj, "status", "order_status", "state", "orderStatus") else "" }

            val hasExplicitOrderIdKey = extractFirstString(json, "order_number", "order_id", "orderNumber", "orderId", "trip_id", "tripId", "reference").isNotEmpty() ||
                (dataObj != null && extractFirstString(dataObj, "order_number", "order_id", "orderNumber", "orderId", "trip_id", "tripId", "reference").isNotEmpty())
            val hasOrderAttributes = restaurant.isNotEmpty() || price > 0.0 || dist > 0.0 || isHistoricalOrActiveStatus(rawStatusFromJson)

            if ((hasExplicitOrderIdKey && (hasOrderAttributes || !isFromLocalDb)) || (orderId.isNotEmpty() && hasOrderAttributes) || (price > 0.0 && restaurant.isNotEmpty())) {
                if (orderId.isEmpty()) {
                    orderId = if (reference.isNotEmpty()) "#$reference" else if (restaurant.isNotEmpty()) "#$restaurant" else return
                }
                if (reference.isEmpty()) reference = orderId.replace("#", "")
                val cleanPrice = String.format(Locale.US, "%.2f", price).toDoubleOrNull() ?: price

                // If order is from SQLite DB or already has a historical/active status (Delivered, Completed, Accepted, Picked Up, Cancelled),
                // sync it directly to the Orders tab with its real status without running new-offer Auto-Accept/Reject gates!
                if (isFromLocalDb || isHistoricalOrActiveStatus(rawStatusFromJson)) {
                    val normStatus = normalizeOrderStatusDisplay(rawStatusFromJson.ifEmpty { if (isFromLocalDb) "Delivered" else status })
                    val dedupKey = "${orderId.replace("#", "")}:$normStatus:${customerPhone.isNotEmpty()}"
                    if (loggedOrderIds.add(dedupKey)) {
                        lastOrderEvent = "Order $orderId synced ($normStatus) via $channelSource"
                        sendOrderToLog(
                            orderId = orderId,
                            price = cleanPrice,
                            dist = dist,
                            restaurant = restaurant,
                            status = normStatus,
                            customerName = customerName,
                            customerPhone = customerPhone,
                            address = address,
                            cashAmount = cashAmount,
                            currency = currency,
                            reference = reference,
                            notifyUser = false
                        )
                    }
                    return
                }

                if (loggedOrderIds.add(orderId)) {
                    lastOrderEvent = "Order $orderId detected via $channelSource"
                    handleOrderViaApi(
                        orderId = orderId,
                        price = cleanPrice,
                        dist = dist,
                        restaurant = restaurant,
                        custDist = custDist,
                        custAddr = address,
                        custName = customerName,
                        custPhone = customerPhone,
                        cashAmt = cashAmount,
                        sourceChannel = if (channelSource.contains("POLL", ignoreCase = true)) "POLL" else "PUSH"
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Exact gate evaluation matching Lqz.a in SanedAssistant-98-7bac1ec.apk:
     * Evaluates price, restaurant distance, and customer distance gates FIRST,
     * then returns ("ACCEPT", "ok"), ("REJECT", reason), ("DRY_RUN", reason), or ("MANUAL", reason).
     */
    fun evaluateOrderDecision(price: Double, restDistKm: Double, custDistKm: Double): Pair<String, String> {
        if (!isMasterRunning) return "MANUAL" to "Master switch is OFF"
        if (!isAutoAcceptEnabled) return "MANUAL" to "Auto-Accept is OFF"

        val failsPrice = minOrderPrice > 0.0 && price > 0.0 && price < minOrderPrice
        val failsRestDist = maxOrderDistance < 999.0 && restDistKm > 0.0 && restDistKm > maxOrderDistance
        val failsMinCustDist = minCustomerDistance > 0.0 && custDistKm > 0.0 && custDistKm < minCustomerDistance
        val failsMaxCustDist = maxCustomerDistance < 999.0 && custDistKm > 0.0 && custDistKm > maxCustomerDistance

        if (failsPrice || failsRestDist || failsMinCustDist || failsMaxCustDist) {
  val reasons = mutableListOf<String>()
  if (failsPrice) reasons.add("Price $price < $minOrderPrice")
  if (failsRestDist) reasons.add("Pickup ${String.format(Locale.US, "%.1f", restDistKm)}km > $maxOrderDistance")
  if (failsMinCustDist) reasons.add("Dropoff ${String.format(Locale.US, "%.1f", custDistKm)}km < $minCustomerDistance")
  if (failsMaxCustDist) reasons.add("Dropoff ${String.format(Locale.US, "%.1f", custDistKm)}km > $maxCustomerDistance")
  val reasonStr = reasons.joinToString(", ")

  return if (filterAction.equals("REJECT", ignoreCase = true)) {
      if (isDryRun) "DRY_RUN" to "Dry-Run (Would Reject: $reasonStr)"
      else "REJECT" to reasonStr
  } else {
      "IGNORE" to reasonStr
  }
        }

        if (isDryRun) {
  return "DRY_RUN" to "Dry-Run Mode (Matches all criteria)"
        }
        return "ACCEPT" to "Matches all criteria"
    }

    private fun isAppInBackground(): Boolean {
        val act = currentActivity
        return act == null || act.isFinishing || act.isDestroyed || !act.hasWindowFocus()
    }

    private fun handleOrderViaApi(
        orderId: String,
        price: Double,
        dist: Double,
        restaurant: String,
        custDist: Double = 0.0,
        pickupAddr: String = "",
        custAddr: String = "",
        custName: String = "",
        custPhone: String = "",
        cashAmt: Double = 0.0,
        sourceChannel: String = "PUSH"
    ): String {
        val rawId = orderId.replace("#", "").trim()
        if (rawId.isEmpty()) return ""
        val orderIdInt = rawId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0

        if (orderIdInt != 0) {
  val existing = handledOrdersMap[orderIdInt]
  if (existing != null && (SystemClock.elapsedRealtime() - existing.timestampRealtime) < 60_000L) {
      return existing.decision
  }
        }

        val (action, reason) = evaluateOrderDecision(price, dist, custDist)

        if (orderIdInt != 0) {
  val wasInBg = isStayInBackground && !isDryRun && isAppInBackground()
  val prev = handledOrdersMap.putIfAbsent(orderIdInt, HandledOrderEntry(action, SystemClock.elapsedRealtime(), wasInBg))
  if (prev != null && (SystemClock.elapsedRealtime() - prev.timestampRealtime) < 60_000L) {
      return prev.decision
  }
        }

        when (action) {
  "REJECT" -> {
      lastOrderEvent = "Order $orderId auto-rejected ($reason)"
      currentActivity?.let { act -> showToast(act, "❌ Auto-Rejected $orderId: $reason") }
      sendOrderToLog(orderId = orderId, price = price, dist = dist, restaurant = restaurant, status = "Rejected ❌ · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, pickupAddress = pickupAddr, customerDistanceKm = custDist, decisionReason = reason)
      executeDirectApiCall(rawId, "REJECT")
  }
  "DRY_RUN" -> {
      lastOrderEvent = "Order $orderId dry-run ($reason)"
      currentActivity?.let { act -> showToast(act, "🔍 [Dry-Run]: Would accept $orderId") }
      sendOrderToLog(orderId = orderId, price = price, dist = dist, restaurant = restaurant, status = "Dry-Run 🔍 · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, pickupAddress = pickupAddr, customerDistanceKm = custDist, decisionReason = reason)
  }
  "ACCEPT" -> {
      lastOrderEvent = "Auto-Accepting Order $orderId..."
      currentActivity?.let { act ->
          showToast(act, "⚡ Auto-Accepting Order $orderId...")
          act.runOnUiThread { try { act.window?.decorView?.let { decor -> findAndTriggerOrder(decor, act, System.currentTimeMillis()) } } catch (_: Throwable) {} }
      }
      sendOrderToLog(orderId = orderId, price = price, dist = dist, restaurant = restaurant, status = "Accepted ✅", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, pickupAddress = pickupAddr, customerDistanceKm = custDist, decisionReason = reason)
      executeDirectApiCall(rawId, "ACCEPT")
      playAlertSound()
  }
  else -> {
      lastOrderEvent = "Order $orderId left for manual review ($reason)"
      currentActivity?.let { act -> showToast(act, "↩️ Left $orderId for you: $reason") }
      sendOrderToLog(orderId = orderId, price = price, dist = dist, restaurant = restaurant, status = "Missed / Manual · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, pickupAddress = pickupAddr, customerDistanceKm = custDist, decisionReason = reason)
  }
        }
        return action
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

    /**
     * Matches Ljx.h, Ljx$c.s, Ljh.a, and Ly0 in SanedAssistant-98-7bac1ec.apk:
     * - Boosts thread priority to -8 (THREAD_PRIORITY_URGENT_DISPLAY) on ACCEPT
     * - Queries Native.b(kind, orderIdInt, token, lang, driverCipher) if available
     * - Executes parallelRequests (1..8) concurrent HTTP calls synchronized with CountDownLatch(1)
     */
    private fun executeDirectApiCall(orderIdNum: String, action: String) {
        val ctx = resolveApplicationContext() ?: currentActivity
        if (cachedAuthToken.isEmpty() && ctx != null) readExactJahezEncryptedPrefs(ctx)
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        val isAccept = (action == "ACCEPT")
        val burstCount = if (isAccept) parallelRequests.coerceIn(1, 8) else 1
        val orderIdInt = orderIdNum.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0

        val probeHosts = mutableListOf<String>()
        if (cachedApiHost.isNotEmpty()) probeHosts.add(cachedApiHost)
        probeHosts.addAll(candidateApiHosts)
        val clientToUse = capturedAppHttpClient ?: httpClient

        CoroutineScope(Dispatchers.IO).launch {
  val tid = Process.myTid()
  val origPriority = try { Process.getThreadPriority(tid) } catch (_: Throwable) { 0 }
  if (isAccept) try { Process.setThreadPriority(-8) } catch (_: Throwable) {}
  try {
      var nativeMethod = ""
      var nativeUrl = ""
      val nativeHeaders = LinkedHashMap<String, String>()
      if (Native.isLoaded && orderIdInt > 0) {
          try {
              if (ctx != null) Native.safeA(ctx, "dev.jing.sanedhook")
              val rawTok = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
              val spec = Native.safeB(if (isAccept) 0 else 1, orderIdInt, rawTok, cachedLanguage, capturedDriverCipherB64)
              if (spec.size >= 2 && spec[1].startsWith("http")) {
                  nativeMethod = spec[0]; nativeUrl = spec[1]
                  var idx = 2
                  while (idx + 1 < spec.size) { nativeHeaders[spec[idx]] = spec[idx + 1]; idx += 2 }
              }
          } catch (_: Throwable) {}
      }

      val emptyPutBody = "".toRequestBody(null)
      val jsonBody = JSONObject().apply {
          put("order_id", orderIdNum)
          if (orderIdInt > 0) put("requestId", orderIdInt)
      }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

      val latch = CountDownLatch(1)
      val winnerCode = AtomicReference<Int>(null)

      for (burst in 0 until burstCount) {
          launch {
              if (nativeUrl.isNotEmpty() && winnerCode.get() == null) {
                  try {
                      val rb = Request.Builder().url(nativeUrl)
                      if (nativeMethod.equals("PUT", ignoreCase = true)) rb.put(emptyPutBody) else rb.get()
                      for ((hk, hv) in nativeHeaders) rb.header(hk, hv)
                      val resp = clientToUse.newCall(rb.build()).execute()
                      val code = resp.code
                      resp.close()
                      if (code in 200..299) {
                          if (winnerCode.compareAndSet(null, code)) latch.countDown()
                          return@launch
                      }
                  } catch (_: Throwable) {}
              }
              for (host in probeHosts.distinct()) {
                  if (winnerCode.get() != null) return@launch
                  val endpoints = if (isAccept) listOf(
                      buildCandidateUrl(host, "/api/fleets/orders/$orderIdNum/accept"),
                      buildCandidateUrl(host, "/api/driver/orders/$orderIdNum/accept"),
                      buildCandidateUrl(host, "/api/orders/$orderIdNum/accept")
                  ) else listOf(
                      buildCandidateUrl(host, "/api/fleets/orders/$orderIdNum/reject"),
                      buildCandidateUrl(host, "/api/driver/orders/$orderIdNum/reject"),
                      buildCandidateUrl(host, "/api/orders/$orderIdNum/reject")
                  )
                  for (url in endpoints) {
                      if (winnerCode.get() != null) return@launch
                      for (method in listOf("PUT", "POST")) {
                          try {
                              val reqBuilder = Request.Builder().url(url)
                              if (method == "PUT") reqBuilder.put(emptyPutBody) else reqBuilder.post(jsonBody)
                              applyJahezHeaders(reqBuilder, token)
                              if (capturedDriverCipherB64.isNotEmpty()) reqBuilder.header("driverId", capturedDriverCipherB64)
                              val resp = clientToUse.newCall(reqBuilder.build()).execute()
                              val code = resp.code
                              resp.close()
                              if (code in 200..299) {
                                  cachedApiHost = host; persistHost(host)
                                  if (winnerCode.compareAndSet(null, code)) latch.countDown()
                                  return@launch
                              }
                          } catch (_: Throwable) {}
                      }
                  }
              }
          }
      }
      try { latch.await(12, TimeUnit.SECONDS) } catch (_: Throwable) {}
  } catch (_: Throwable) {
  } finally {
      if (isAccept) try { Process.setThreadPriority(origPriority) } catch (_: Throwable) {}
  }
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
        reference: String = "",
        pickupAddress: String = "",
        customerDistanceKm: Double = 0.0,
        decisionReason: String = "",
        notifyUser: Boolean = true
    ) {
        val ctx = resolveApplicationContext() ?: currentActivity
        val ref = if (reference.isNotEmpty()) reference else orderId.replace("#", "")
        val intId = orderId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val order = OrderModel(
            id = intId,
            reference = ref,
            status = status,
            restaurant = restaurant,
            pickupAddress = pickupAddress,
            customerName = customerName,
            customerPhone = customerPhone,
            address = address,
            price = price,
            currency = currency,
            cashAmount = cashAmount,
            distanceKm = dist,
            customerDistanceKm = customerDistanceKm,
            decisionReason = decisionReason,
            time = timeStr
        )

        val cacheKey = ref.ifEmpty { orderId.replace("#", "").trim() }
        if (cacheKey.isNotEmpty()) {
            syncedOrdersCache[cacheKey] = order.toJson()
        }

        if (ctx != null) {
            // 1. Direct synchronous ContentProvider IPC to XposedProvider (never blocked by background broadcast rules)
            try {
                val uri = android.net.Uri.parse("content://dev.jing.sanedhook.XposedService")
                val extras = android.os.Bundle().apply {
                    putString("order_json", order.toJson().toString())
                    putBoolean("notify_user", notifyUser)
                }
                ctx.contentResolver.call(uri, "logOrder", null, extras)
            } catch (_: Throwable) {}

            // 2. Explicit broadcast with FLAG_INCLUDE_STOPPED_PACKAGES and FLAG_RECEIVER_FOREGROUND
            try {
                val logIntent = Intent("dev.saned.assistant.ACTION_LOG_ORDER").apply {
                    component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra("order_id", orderId)
                    putExtra("reference", ref)
                    putExtra("price", price)
                    putExtra("distance", dist)
                    putExtra("customer_distance", customerDistanceKm)
                    putExtra("restaurant", restaurant)
                    putExtra("pickup_address", pickupAddress)
                    putExtra("status", status)
                    putExtra("decision_reason", decisionReason)
                    putExtra("customer_name", customerName)
                    putExtra("customer_phone", customerPhone)
                    putExtra("address", address)
                    putExtra("cash_amount", cashAmount)
                    putExtra("currency", currency)
                    putExtra("notify_user", notifyUser)
                }
                ctx.sendBroadcast(logIntent)
            } catch (_: Throwable) {}

            // 3. Also send original SanedAssistant Lvf1.run() broadcast ("dev.jing.sanedhook.b")
            try {
                val arr = JSONArray().apply { put(order.toJson()) }
                val bIntent = Intent("dev.jing.sanedhook.b").apply {
                    setPackage("dev.jing.sanedhook")
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra("o", arr.toString())
                    putExtra("k", isMasterRunning)
                }
                ctx.sendBroadcast(bIntent)
            } catch (_: Throwable) {}

            // 4. Instant System Notification from host context when notifyUser == true
            if (notifyUser) {
                try {
                    val isAccepted = !order.isMissedOrRejected &&
                        (status.contains("Accept", ignoreCase = true) || status.contains("قبول"))
                    NotificationHelper.showOrderNotification(ctx, order, isAccepted = isAccepted)
                } catch (_: Throwable) {}
            }
        }

        try {
            val f = File("/data/local/tmp/saned_orders.json")
            val existing = if (f.exists()) f.readText() else "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }

            val newArray = JSONArray()
            newArray.put(order.toJson())
            val normRef = ref.replace("#", "").trim()
            for (i in 0 until array.length()) {
                if (newArray.length() >= 50) break
                val obj = array.optJSONObject(i) ?: continue
                val oldRef = obj.optString("reference", "").replace("#", "").trim()
                if (normRef.isNotEmpty() && oldRef == normRef) continue
                newArray.put(obj)
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
        var pickupAddress = ""
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
                if (acceptView == null || combined.contains("accept") || combined.contains("قبول")) {
                    acceptView = v
                }
            }

            if (combined.contains("reject") || combined.contains("رفض") || combined.contains("dismiss") || combined.contains("تجاهل") || combined.contains("إلغاء")) {
                if (rejectView == null || combined.equals("reject") || combined.equals("رفض")) {
                    rejectView = v
                }
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
        var brandTitle = ""
        val decimalCandidates = mutableListOf<Double>()
        val integerPriceCandidates = mutableListOf<Double>()

        for (raw in allTexts) {
            val t = normalizeArabicNumerals(raw).trim()
            if (t.isEmpty()) continue

            if (t.contains("New Order", ignoreCase = true) || t.contains("طلب جديد", ignoreCase = true) ||
                t.contains("New Offer", ignoreCase = true) || t.contains("عرض جديد", ignoreCase = true) ||
                t.contains("Pick-up from", ignoreCase = true) || t.contains("Pickup from", ignoreCase = true) ||
                t.contains("Drop off at", ignoreCase = true) || t.contains("Drop-off at", ignoreCase = true) ||
                t.contains("Km From You", ignoreCase = true) || t.contains("Km From Pickup", ignoreCase = true) ||
                t.contains("كم منك", ignoreCase = true) || t.contains("نقطة الاستلام", ignoreCase = true)) {
                isNewOrderScreen = true
            }

            val mDistRest = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*You|كم\s*منك|كم\s*عنك)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistRest != null && distToRestaurant == 0.0) {
                distToRestaurant = mDistRest.groupValues[1].toDoubleOrNull() ?: 0.0
                continue
            }

            val mDistCust = Regex("""(\d+(?:\.\d+)?)\s*(?:Km\s*From\s*Pickup|Km\s*From\s*Store|كم\s*من\s*نقطة\s*الاستلام|كم\s*من\s*المتجر|كم\s*من\s*المطعم)""", RegexOption.IGNORE_CASE).find(t)
            if (mDistCust != null && distToCustomer == 0.0) {
                distToCustomer = mDistCust.groupValues[1].toDoubleOrNull() ?: 0.0
                continue
            }

            val mPickup = Regex("""(?:Pick-?up\s*from|استلام\s*من|الاستلام\s*من)\s*(.+)""", RegexOption.IGNORE_CASE).find(t)
            if (mPickup != null && pickupAddress.isEmpty()) {
                pickupAddress = mPickup.groupValues[1].trim()
                continue
            }

            val mDrop = Regex("""(?:Drop-?off\s*at|Deliver\s*to|توصيل\s*إلى|التوصيل\s*إلى|تسليم\s*في|نقطة\s*التسليم)\s*(.+)""", RegexOption.IGNORE_CASE).find(t)
            if (mDrop != null && customerAddress.isEmpty()) {
                customerAddress = mDrop.groupValues[1].trim()
                continue
            }

            val mPhone = Regex("""(?:\+?966|0)?5\d{8}""").find(t)
            if (mPhone != null && customerPhone.isEmpty()) {
                customerPhone = mPhone.value
            }

            val mCash = Regex("""(?:استلم نقداً|استلام نقدي|كاش|نقدا|نقداً|Collect\s*Cash|COD)[\s:]*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(t)
            if (mCash != null && cashAmount == 0.0) {
                cashAmount = mCash.groupValues[1].toDoubleOrNull() ?: 0.0
            }

            val isAddressOrMetaLine = t.contains("Pick-up", ignoreCase = true) ||
                t.contains("Pickup", ignoreCase = true) ||
                t.contains("Drop off", ignoreCase = true) ||
                t.contains("Drop-off", ignoreCase = true) ||
                t.contains("From You", ignoreCase = true) ||
                t.contains("From Pickup", ignoreCase = true) ||
                t.contains("Km", ignoreCase = true) ||
                t.contains("كم", ignoreCase = true) ||
                t.contains("Riyadh", ignoreCase = true) ||
                t.contains("Saudi", ignoreCase = true) ||
                t.contains("Prince", ignoreCase = true) ||
                t.contains("+")

            if (!isAddressOrMetaLine && !t.contains(".")) {
                val mId = Regex("""#?\s*(\d{6,12})""").find(t)
                if (mId != null && orderId.isEmpty()) {
                    orderId = "#" + mId.groupValues[1]
                }
            }

            if (!isAddressOrMetaLine &&
                !t.equals("New Order", ignoreCase = true) &&
                !t.equals("Reject", ignoreCase = true) &&
                !t.equals("Accept", ignoreCase = true) &&
                !t.equals(">>", ignoreCase = true) &&
                !t.equals("Google", ignoreCase = true)) {

                // Check for decimal price first (e.g. "10.002" in 68.jpg)
                val mDec = Regex("""(\d+\.\d+)""").find(t)
                if (mDec != null) {
                    val p = mDec.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (p in 1.0..1000.0 && p != distToRestaurant && p != distToCustomer) {
                        decimalCandidates.add(p)
                    }
                } else {
                    val mCurr = Regex("""(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*(?:[#﷼\$€£]|SAR|رس|ر\.س|ريال)""", RegexOption.IGNORE_CASE).find(t)
                    if (mCurr != null) {
                        val numStr = mCurr.groupValues[1].ifEmpty { mCurr.groupValues[2] }
                        val p = numStr.toDoubleOrNull() ?: 0.0
                        if (p in 1.0..1000.0) decimalCandidates.add(p)
                    } else if (t.any { it.isLetter() } && brandTitle.isEmpty() && t.length in 2..40) {
                        brandTitle = t
                    }
                }
            }
        }

        if (orderPrice == 0.0) {
            if (decimalCandidates.isNotEmpty()) {
                orderPrice = decimalCandidates.first()
            } else if (integerPriceCandidates.isNotEmpty()) {
                orderPrice = integerPriceCandidates.first()
            }
        }

        if (storeName.isEmpty()) {
            storeName = when {
                brandTitle.isNotEmpty() && pickupAddress.isNotEmpty() -> "$brandTitle — $pickupAddress"
                pickupAddress.isNotEmpty() -> pickupAddress
                brandTitle.isNotEmpty() -> brandTitle
                else -> ""
            }
        }

        // Try extracting real numeric Order ID from the Activity / Intent if not shown as text on screen
        if (orderId.isEmpty()) {
            orderId = extractOrderIdFromActivity(activity)
        }

        // Geometry-based slider detection (for Canvas / Custom Slider views)
        if (acceptView == null && isNewOrderScreen) {
            val dm = activity.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val sliderList = mutableListOf<View>()
            fun findSliderCandidates(v: View) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (v.width > (screenW * 0.45) && v.height in 35..220 && loc[1] > (screenH * 0.65)) {
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

        val hasOrderDetails = isNewOrderScreen || (orderPrice > 0.0 && (distToRestaurant > 0.0 || storeName.isNotEmpty()))
        if (!hasOrderDetails) {
            return false
        }

        if (orderId.isEmpty()) {
            // Deterministic ID for NewOrderScreen when numeric ID is not displayed in TextViews (as in 68.jpg)
            val pTag = (orderPrice * 1000).toInt()
            val rTag = (distToRestaurant * 10).toInt()
            val cTag = (distToCustomer * 10).toInt()
            orderId = "#JH-$pTag-$rTag-$cTag"
        }

        if (acceptView != null && hasOrderDetails) {
            val signature = "$orderId-$orderPrice-$distToRestaurant-$distToCustomer"
            val now = System.currentTimeMillis()
            if (signature == lastHandledOrderSignature && (now - lastHandledTimestamp) < 4000L) {
                return true
            }

            lastHandledOrderSignature = signature
            lastHandledTimestamp = now

            evaluateAndProcessOrder(
                act = activity,
                dialogRoot = root,
                btnAccept = acceptView!!,
                btnReject = rejectView,
                price = orderPrice,
                distRest = distToRestaurant,
                distCust = distToCustomer,
                orderId = orderId,
                storeName = storeName,
                pickupAddr = pickupAddress,
                custAddr = customerAddress,
                custName = customerName,
                custPhone = customerPhone,
                cashAmt = cashAmount,
                startTime = startTime
            )
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

            if (isAutoAccept && isMasterRunning) {
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
        storeName: String,
        pickupAddr: String,
        custAddr: String,
        custName: String,
        custPhone: String,
        cashAmt: Double,
        startTime: Long
    ) {
        val cleanPrice = String.format(Locale.US, "%.2f", price).toDoubleOrNull() ?: price
        val (decision, reason) = evaluateOrderDecision(cleanPrice, distRest, distCust)

        when (decision) {
            "REJECT" -> {
                lastOrderEvent = "Order $orderId auto-rejected ($reason)"
                showToast(act, "❌ Auto-Rejected $orderId: $reason")
                sendOrderToLog(
                    orderId = orderId,
                    price = cleanPrice,
                    dist = distRest,
                    restaurant = storeName,
                    status = "Rejected ❌ · $reason",
                    customerName = custName,
                    customerPhone = custPhone,
                    address = custAddr,
                    cashAmount = cashAmt,
                    pickupAddress = pickupAddr,
                    customerDistanceKm = distCust,
                    decisionReason = reason
                )
                if (btnReject != null) {
                    triggerClick(btnReject)
                }
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
            }

            "DRY_RUN" -> {
                lastOrderEvent = "Order $orderId dry-run ($reason)"
                showToast(act, "🔍 [Dry-Run]: Would accept $orderId ($cleanPrice SAR, $distRest km)")
                sendOrderToLog(
                    orderId = orderId,
                    price = cleanPrice,
                    dist = distRest,
                    restaurant = storeName,
                    status = "Dry-Run 🔍 · Would Accept",
                    customerName = custName,
                    customerPhone = custPhone,
                    address = custAddr,
                    cashAmount = cashAmt,
                    pickupAddress = pickupAddr,
                    customerDistanceKm = distCust,
                    decisionReason = reason
                )
            }

            "ACCEPT" -> {
                if (isAccepting.compareAndSet(false, true)) {
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            simulateSwipe(btnAccept, dialogRoot, act)
                            executeDirectApiCall(orderId.replace("#", ""), "ACCEPT")

                            lastOrderEvent = "Order $orderId accepted in ${latency}ms"
                            showToast(act, "✅ Accepted $orderId ($cleanPrice SAR · $distRest km) in ${latency}ms!")
                            sendOrderToLog(
                                orderId = orderId,
                                price = cleanPrice,
                                dist = distRest,
                                restaurant = storeName,
                                status = "Accepted ✅",
                                customerName = custName,
                                customerPhone = custPhone,
                                address = custAddr,
                                cashAmount = cashAmt,
                                pickupAddress = pickupAddr,
                                customerDistanceKm = distCust,
                                decisionReason = "Accepted in ${latency}ms"
                            )
                            playAlertSound()
                        } finally {
                            delay(600)
                            isAccepting.set(false)
                        }
                    }
                }
            }

            else -> {
                // MANUAL: Log the order to Orders list with full details even when auto-accept/reject is off or skipped!
                lastOrderEvent = "Order $orderId logged ($reason)"
                showToast(act, "↩️ Order $orderId: $reason")
                sendOrderToLog(
                    orderId = orderId,
                    price = cleanPrice,
                    dist = distRest,
                    restaurant = storeName,
                    status = "Missed / Manual · $reason",
                    customerName = custName,
                    customerPhone = custPhone,
                    address = custAddr,
                    cashAmount = cashAmt,
                    pickupAddress = pickupAddr,
                    customerDistanceKm = distCust,
                    decisionReason = reason
                )
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
