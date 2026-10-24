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
import android.os.Bundle
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
    @Volatile var jdrvLicKey: String = ""
    @Volatile var jdrvHwDeviceId: String = ""
    @Volatile var jdrvLicTokenSig: String = ""
    @Volatile var jdrvTrialLedgerEntry: String = ""
    @Volatile var jdrvLicActive: Boolean = false
    @Volatile var jdrvLicExpiryMs: Long = 0L
    @Volatile var jdrvLicFeatureMask: Int = 0x0F
    @Volatile var jdrvLicEpoch: Int = 1
    @Volatile var jdrvEngineMagic: Int = 0
    @Volatile var jdrvRemoteAppEnabled: Boolean = true
    @Volatile var jdrvRemoteAutoAccept: Boolean = true
    @Volatile var jdrvRemoteDeliverOrder: Boolean = true
    @Volatile var jdrvRemoteFloatingMenu: Boolean = true
    @Volatile var jdrvRemoteAndroidId: Boolean = true
    @Volatile var jdrvRemoteMinEpoch: Int = 1
    @Volatile var jdrvRemoteBlockedDevsCsv: String = ""
    @Volatile var jdrvRemoteBlockedKeysCsv: String = ""
    @Volatile var jdrvRemoteMsgAr: String = ""
    @Volatile var jdrvRemoteMsgEn: String = ""
    @Volatile private var lastRemoteCtrlCheckRealtime: Long = 0L
    @Volatile private var lastWarnedExpiryMsInSaned: Long = 0L
    @Volatile private var cachedCryptoCacheKey: String = ""
    @Volatile private var cachedCryptoValid: Boolean = false
    @Volatile private var cachedApkSigVerified: Int = 0 // 0=unchecked, 1=valid, -1=tampered

    private const val ENGINE_LIC_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun reconstructEngineMasterKey(): ByteArray {
        val seeds = intArrayOf(
            0x4A445256, 0x53414E45, 0x44393850, 0x524F5345,
            0x43555245, 0x39363635, 0x30353831, 0x32313431
        )
        val raw = ByteArray(32)
        for (idx in seeds.indices) {
            val mixed = seeds[idx] xor (0x5A3C96A5 + idx * 0x13579BDF)
            val off = idx * 4
            raw[off] = ((mixed ushr 24) and 0xFF).toByte()
            raw[off + 1] = ((mixed ushr 16) and 0xFF).toByte()
            raw[off + 2] = ((mixed ushr 8) and 0xFF).toByte()
            raw[off + 3] = (mixed and 0xFF).toByte()
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw)
    }

    private fun reconstructExpectedCertSha256(): ByteArray {
        val certSeeds = intArrayOf(
            0x04EA4414.toInt(), 0x2DC949DA.toInt(), 0x14EDEAF9.toInt(), 0xACAE78B3.toInt(),
            0xA5CF0D92.toInt(), 0x064463D1.toInt(), 0x33C46013.toInt(), 0xBCF223A2.toInt()
        )
        val out = ByteArray(32)
        for (idx in certSeeds.indices) {
            val mask = 0x3C6EF372 + idx * 0x27D4EB2D
            val word = certSeeds[idx] xor mask
            val off = idx * 4
            out[off] = ((word ushr 24) and 0xFF).toByte()
            out[off + 1] = ((word ushr 16) and 0xFF).toByte()
            out[off + 2] = ((word ushr 8) and 0xFF).toByte()
            out[off + 3] = (word and 0xFF).toByte()
        }
        return out
    }

    private fun verifyOfficialModuleApkSignature(): Boolean {
        val cached = cachedApkSigVerified
        if (cached != 0) return cached == 1
        val ctx = appContext ?: currentActivity ?: return true
        try {
            @Suppress("DEPRECATION")
            val pkgInfo = ctx.packageManager.getPackageInfo("dev.jing.sanedhook", android.content.pm.PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = pkgInfo.signatures
            if (sigs != null && sigs.isNotEmpty() && sigs[0] != null) {
                val actualDigest = java.security.MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
                val expectedDigest = reconstructExpectedCertSha256()
                val ok = java.security.MessageDigest.isEqual(actualDigest, expectedDigest)
                cachedApkSigVerified = if (ok) 1 else -1
                return ok
            }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            // Module is embedded in non-root integrated mode without standalone package installed
            cachedApkSigVerified = 1
            return true
        } catch (_: Throwable) {}
        return true
    }

    private fun computeEngineDeviceHashInt(deviceId: String): Int {
        val clean = deviceId.trim().uppercase(Locale.US).replace("-", "").replace(" ", "")
        if (clean.isEmpty() || clean == "*" || clean == "ANY" || clean == "ALL" || clean == "JID00000000") {
            return 0
        }
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("JDRV_DEV:$clean").toByteArray(Charsets.UTF_8))
        val v = ((d[0].toInt() and 0xFF) shl 24) or
            ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or
            (d[3].toInt() and 0xFF)
        return if (v != 0) v else 1
    }

    private fun decodeEngineBase32Key(keyStr: String): ByteArray? {
        var clean = keyStr.trim().uppercase(Locale.US).replace(" ", "")
        if (clean.startsWith("JDRV-")) clean = clean.substring(5)
        clean = clean.replace("-", "")
        if (clean.length != 32) return null
        var bits = 0
        var value = 0
        val out = ByteArray(20)
        var outIdx = 0
        for (i in 0 until clean.length) {
            val idx = ENGINE_LIC_ALPHABET.indexOf(clean[i])
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                if (outIdx < 20) {
                    out[outIdx++] = ((value ushr (bits - 8)) and 0xFF).toByte()
                }
                bits -= 8
            }
        }
        return if (outIdx == 20) out else null
    }

    private fun verifyCryptographicKeyAndTokenInEngine(): Boolean {
        val rawKey = jdrvLicKey.trim().uppercase(Locale.US).replace(" ", "")
        if (rawKey.isEmpty()) return false
        val devId = jdrvHwDeviceId.trim().uppercase(Locale.US)
        val minEp = jdrvRemoteMinEpoch
        val blkDevs = jdrvRemoteBlockedDevsCsv
        val blkKeys = jdrvRemoteBlockedKeysCsv
        val sigTok = jdrvLicTokenSig
        val stateSnapshot = "$rawKey|$devId|$jdrvLicExpiryMs|$jdrvLicFeatureMask|$jdrvLicEpoch|$minEp|$blkDevs|$blkKeys|$sigTok"
        if (stateSnapshot == cachedCryptoCacheKey) {
            return cachedCryptoValid
        }

        val valid = try {
            if (blkDevs.isNotEmpty() && devId.isNotEmpty()) {
                val setDevs = blkDevs.split(",").map { it.trim().uppercase(Locale.US) }.toSet()
                if (setDevs.contains(devId) || setDevs.contains(devId.replace("-", ""))) {
                    cachedCryptoCacheKey = stateSnapshot
                    cachedCryptoValid = false
                    return false
                }
            }
            if (blkKeys.isNotEmpty()) {
                val setKeys = blkKeys.split(",").map { it.trim().uppercase(Locale.US) }.toSet()
                if (setKeys.contains(rawKey)) {
                    cachedCryptoCacheKey = stateSnapshot
                    cachedCryptoValid = false
                    return false
                }
            }

            val decoded = decodeEngineBase32Key(rawKey) ?: return false
            val mk = reconstructEngineMasterKey()
            val enc12 = decoded.copyOfRange(0, 12)
            val nonce2 = decoded.copyOfRange(12, 14)
            val tag6 = decoded.copyOfRange(14, 20)

            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(mk, "HmacSHA256"))
            mac.update(enc12)
            mac.update(nonce2)
            val expectedTag = mac.doFinal().copyOfRange(0, 6)
            if (!java.security.MessageDigest.isEqual(tag6, expectedTag)) {
                false
            } else {
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                sha.update(mk)
                sha.update(nonce2)
                val stream = sha.digest()
                val plain12 = ByteArray(12)
                for (i in 0 until 12) {
                    plain12[i] = (enc12[i].toInt() xor stream[i].toInt()).toByte()
                }
                val devHash = ((plain12[0].toInt() and 0xFF) shl 24) or
                    ((plain12[1].toInt() and 0xFF) shl 16) or
                    ((plain12[2].toInt() and 0xFF) shl 8) or
                    (plain12[3].toInt() and 0xFF)
                val keyEpoch = plain12[8].toInt() and 0xFF
                val featureMask = plain12[9].toInt() and 0xFF
                val magic = ((plain12[10].toInt() and 0xFF) shl 8) or (plain12[11].toInt() and 0xFF)

                if (magic != 0x4A44 || keyEpoch < minEp) {
                    false
                } else if (devHash != 0 && (devId.isEmpty() || devHash != computeEngineDeviceHashInt(devId))) {
                    false
                } else {
                    // Enforce authentic featureMask & epoch decrypted directly from key
                    jdrvLicFeatureMask = featureMask
                    jdrvLicEpoch = keyEpoch
                    if (sigTok.isNotEmpty()) {
                        val tokenCanonical = "$rawKey|$devId|$jdrvLicExpiryMs|$featureMask|$keyEpoch|19012"
                        val tokMac = javax.crypto.Mac.getInstance("HmacSHA256")
                        tokMac.init(javax.crypto.spec.SecretKeySpec(mk, "HmacSHA256"))
                        val expectedTokHex = tokMac.doFinal(tokenCanonical.toByteArray(Charsets.UTF_8))
                            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
                            .take(32)
                        expectedTokHex == sigTok.lowercase(Locale.US)
                    } else {
                        true
                    }
                }
            }
        } catch (_: Throwable) {
            false
        }

        cachedCryptoCacheKey = stateSnapshot
        cachedCryptoValid = valid
        return valid
    }

    private fun verifyRemoteControlSignatureInEngine(json: JSONObject): Boolean {
        val sig = json.optString("sig", "").trim().lowercase(Locale.US)
        if (sig.length != 32) return false
        val appEn = if (json.optBoolean("app_enabled", true)) 1 else 0
        val accEn = if (json.optBoolean("allow_auto_accept", true)) 1 else 0
        val delEn = if (json.optBoolean("allow_deliver_order", true)) 1 else 0
        val fltEn = if (json.optBoolean("allow_floating_menu", true)) 1 else 0
        val aidEn = if (json.optBoolean("allow_android_id", true)) 1 else 0
        val minEp = json.optInt("min_key_epoch", 1)

        val bDevs = mutableListOf<String>()
        val devArr = json.optJSONArray("blocked_devices")
        if (devArr != null) {
            for (i in 0 until devArr.length()) {
                val s = devArr.optString(i, "").trim().uppercase(Locale.US)
                if (s.isNotEmpty()) bDevs.add(s)
            }
        }
        bDevs.sort()

        val bKeys = mutableListOf<String>()
        val keyArr = json.optJSONArray("blocked_keys")
        if (keyArr != null) {
            for (i in 0 until keyArr.length()) {
                val s = keyArr.optString(i, "").trim().uppercase(Locale.US)
                if (s.isNotEmpty()) bKeys.add(s)
            }
        }
        bKeys.sort()

        val canonical = "$appEn|$accEn|$delEn|$fltEn|$aidEn|$minEp|${bDevs.joinToString(",")}|${bKeys.joinToString(",")}"
        val mk = reconstructEngineMasterKey()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(mk, "HmacSHA256"))
        val fullHex = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        return fullHex.take(32) == sig
    }

    fun isLicenseActiveInsideSaned(): Boolean {
        if (!jdrvRemoteAppEnabled) return false
        if (!jdrvLicActive || jdrvEngineMagic != 0x4A44 || jdrvLicKey.isBlank()) return false
        val nowMs = System.currentTimeMillis()
        if (jdrvLicExpiryMs > 0L && nowMs > jdrvLicExpiryMs) return false
        val ok = verifyCryptographicKeyAndTokenInEngine()
        if (ok && jdrvLicExpiryMs > 0L) {
            val remMs = jdrvLicExpiryMs - nowMs
            if (remMs in 1L..86_400_000L && lastWarnedExpiryMsInSaned != jdrvLicExpiryMs) {
                lastWarnedExpiryMsInSaned = jdrvLicExpiryMs
                val ctx = appContext ?: currentActivity
                if (ctx != null) {
                    NotificationHelper.showLicenseExpiryWarningNotification(ctx, jdrvLicExpiryMs, remMs)
                }
            }
        }
        return ok
    }

    fun isAutoAcceptFeatureAllowed(): Boolean =
        isLicenseActiveInsideSaned() && jdrvRemoteAutoAccept && (jdrvLicFeatureMask and 1) != 0

    fun isDeliverOrderFeatureAllowed(): Boolean =
        isLicenseActiveInsideSaned() && jdrvRemoteDeliverOrder && (jdrvLicFeatureMask and 2) != 0

    fun isFloatingMenuFeatureAllowed(): Boolean =
        isLicenseActiveInsideSaned() && jdrvRemoteFloatingMenu && (jdrvLicFeatureMask and 4) != 0

    fun isAndroidIdFeatureAllowed(): Boolean =
        isLicenseActiveInsideSaned() && jdrvRemoteAndroidId && (jdrvLicFeatureMask and 8) != 0

    private fun checkRemoteKillSwitchFromSaned() {
        val nowRt = SystemClock.elapsedRealtime()
        if (nowRt - lastRemoteCtrlCheckRealtime < 45_000L) return
        lastRemoteCtrlCheckRealtime = nowRt
        CoroutineScope(Dispatchers.IO).launch {
            val urls = listOf(
                "https://raw.githubusercontent.com/Hassinalhadi/my-saned/main/jdriver_control.json",
                "https://raw.githubusercontent.com/Hassinalhadi/my-saned/master/jdriver_control.json"
            )
            for (u in urls) {
                try {
                    val req = Request.Builder()
                        .url("$u?t=${System.currentTimeMillis()}")
                        .header("Cache-Control", "no-cache")
                        .get()
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val code = resp.code
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    if (code in 200..299 && body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        if (verifyRemoteControlSignatureInEngine(json)) {
                            val bDevs = mutableListOf<String>()
                            val devArr = json.optJSONArray("blocked_devices")
                            if (devArr != null) {
                                for (i in 0 until devArr.length()) {
                                    val s = devArr.optString(i, "").trim().uppercase(Locale.US)
                                    if (s.isNotEmpty()) bDevs.add(s)
                                }
                            }
                            val bKeys = mutableListOf<String>()
                            val keyArr = json.optJSONArray("blocked_keys")
                            if (keyArr != null) {
                                for (i in 0 until keyArr.length()) {
                                    val s = keyArr.optString(i, "").trim().uppercase(Locale.US)
                                    if (s.isNotEmpty()) bKeys.add(s)
                                }
                            }
                            jdrvRemoteAppEnabled = json.optBoolean("app_enabled", true)
                            jdrvRemoteAutoAccept = json.optBoolean("allow_auto_accept", true)
                            jdrvRemoteDeliverOrder = json.optBoolean("allow_deliver_order", true)
                            jdrvRemoteFloatingMenu = json.optBoolean("allow_floating_menu", true)
                            jdrvRemoteAndroidId = json.optBoolean("allow_android_id", true)
                            jdrvRemoteMinEpoch = json.optInt("min_key_epoch", 1)
                            jdrvRemoteBlockedDevsCsv = bDevs.joinToString(",")
                            jdrvRemoteBlockedKeysCsv = bKeys.joinToString(",")
                            jdrvRemoteMsgAr = json.optString("kill_message_ar", "")
                            jdrvRemoteMsgEn = json.optString("kill_message_en", "")
                            if (!isDeliverOrderFeatureAllowed()) {
                                LocationEngine.isFakeLocationEnabled = false
                                LocationEngine.isTripSimulationActive = false
                            }
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }
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
    @Volatile var isExtraOrderEnabled: Boolean = false
    @Volatile var maxOrdersLimit: Int = 2
    @Volatile var isSoundEnabled: Boolean = false
    @Volatile var isShowToasts: Boolean = false
    @Volatile var isFloatingOverlayEnabled: Boolean = true
    @Volatile var lastLocalOverlayModMs: Long = 0L
    @Volatile var appUiLanguage: String = "ar"

    // Active Server Polling & Parallel Acceptance
    @Volatile var isActivePolling: Boolean = true
    @Volatile var pollIntervalSec: Float = 0.0f
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

    // Resilient SSL-Bypassing OkHttpClient for direct Jahez API calls (pre-initialized with HTTP/2 keep-alive & 15s pingInterval)
    private val httpClient: OkHttpClient = createUnsafeOkHttpClient()
    private val EMPTY_REQUEST_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    private val CACHED_INTENT_KEYS: Array<String> = Native.safeK()
    private val CACHED_FCM_KEYS: Array<String> = Native.safeL()
    @Volatile private var cachedRemoteMsgGetDataMethod: java.lang.reflect.Method? = null
    @Volatile private var lastSocketTouchRealtime: Long = 0L

    // Dedicated pre-warmed real-time executor (Process.setThreadPriority(-19)) for 0.0ms API dispatch overhead
    private val fastApiExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({
            try { Process.setThreadPriority(-19) } catch (_: Throwable) {
                try { Process.setThreadPriority(-8) } catch (_: Throwable) {}
            }
            r.run()
        }, "Saned-ZeroMs-Api").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }.also { exec ->
        // Pre-start both core threads immediately so thread creation latency is 0.0ms
        exec.submit {}
        exec.submit {}
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
        return try {
  val intent = activity.intent
  if (intent != null) {
      val exactId = extractExactOrderIdFromIntent(intent)
      extractAndProcessOrderFromIntent(intent, activity)
      exactId
  } else 0
        } catch (t: Throwable) {
  DiagnosticLogger.logError("onActivityPreCreated", "Error inspecting activity intent", t)
  0
        }
    }

    fun onOrderActivityPostCreate(activity: Activity, orderIdInt: Int) {
        handlePostOrderActivityCreate(activity, orderIdInt)
    }

    private fun extractExactOrderIdFromIntent(intent: Intent): Int = try {
        val reqIdKey = CACHED_INTENT_KEYS.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "EXTRA_DISPATCH_REQUEST_ID"
        val idVal = intent.getIntExtra(reqIdKey, 0)
        if (idVal != 0) idVal else intent.getIntExtra("order_id", 0)
    } catch (_: Throwable) { 0 }

    val apiConfirmedAcceptIds = ConcurrentHashMap.newKeySet<Int>()

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
        // If decision is ACCEPT, only finish IncomingDispatchActivity if API accept already succeeded (200..299).
        // Otherwise, trigger UI auto-accept on IncomingDispatchActivity as backup so the order is never lost!
        if (entry.decision == "ACCEPT" && !apiConfirmedAcceptIds.contains(orderIdInt)) {
  DiagnosticLogger.logStep("IncomingDispatchActivity", "Order #$orderIdInt screen opened while API accept pending/failed -> triggering 0ms UI auto-accept backup")
  for (delayMs in listOf(0L, 45L, 140L, 320L)) {
      mainHandler.postDelayed({
          try {
              if (apiConfirmedAcceptIds.contains(orderIdInt)) {
                  closeDispatchActivitySafely(activity, entry.wasInBackground)
              } else if (!activity.isFinishing && !activity.isDestroyed) {
                  val decor = activity.window?.decorView
                  if (decor != null) {
                      findAndTriggerOrder(decor, activity, System.currentTimeMillis())
                  }
              }
          } catch (_: Throwable) {}
      }, delayMs)
  }
  return
        }
        closeDispatchActivitySafely(activity, entry.wasInBackground)
    }

    private fun closeDispatchActivitySafely(activity: Activity, wasInBackground: Boolean) {
        mainHandler.post {
  try {
      if (activity.isTaskRoot && !wasInBackground) {
          val mainCls = PackageCloaker.getMainActivityClassName()
          if (mainCls.isNotEmpty()) activity.startActivity(Intent().setClassName(activity.packageName, mainCls))
      }
      if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
  } catch (_: Throwable) {}
        }
    }

    fun onActivityCreated(activity: Activity) {
        currentActivity = activity
    }

    fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        attachFloatingOverlayToActivity(activity)
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

                        cacheOrderCoords(reqId.toString(), pLat, pLng, dLat, dLng)
                        DiagnosticLogger.logStep(
                            "NavLog",
                            "IncomingDispatchActivity #$reqId: pickup=($pLat,$pLng) -> customer=($dLat,$dLng) restDist=${String.format(Locale.US, "%.2f", calcRestDist)}km custDist=${String.format(Locale.US, "%.2f", calcCustDist)}km"
                        )
                        val totalOfferPrice = delivPrice + bonusAmt
                        val cleanPrice = String.format(Locale.US, "%.2f", totalOfferPrice).toDoubleOrNull() ?: totalOfferPrice
                        lastOrderEvent = "Order #$reqId detected via IncomingDispatchActivity (Ls20)"
                        handleOrderViaApi(
                            orderId = "#$reqId",
                            price = cleanPrice,
                            dist = calcRestDist,
                            restaurant = subName,
                            custDist = calcCustDist,
                            pickupLat = pLat,
                            pickupLng = pLng,
                            destLat = dLat,
                            destLng = dLng,
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
                "dev.saned.assistant.ACTION_CLEAR_ORDERS" -> {
                    synchronized(syncedOrdersCache) {
                        for ((k, v) in syncedOrdersCache) {
                            val ck = k.replace("#", "").trim()
                            if (ck.isNotEmpty()) deletedOrderIds.add(ck)
                            val eid = v.optString("id", "").replace("#", "").trim()
                            val eref = v.optString("reference", "").replace("#", "").trim()
                            if (eid.isNotEmpty() && eid != "0") deletedOrderIds.add(eid)
                            if (eref.isNotEmpty()) deletedOrderIds.add(eref)
                        }
                        syncedOrdersCache.clear()
                    }
                    deletedOrderIds.addAll(knownDbOrderIds)
                    liveActiveOrderMap.clear()
                }
                "dev.saned.assistant.ACTION_DELETE_ORDER" -> {
                    val oid = (intent.getStringExtra("order_id") ?: "").replace("#", "").trim()
                    val oref = (intent.getStringExtra("reference") ?: "").replace("#", "").trim()
                    if (oid.isNotEmpty() && oid != "0") deletedOrderIds.add(oid)
                    if (oref.isNotEmpty()) deletedOrderIds.add(oref)
                    synchronized(syncedOrdersCache) {
                        val toRem = mutableListOf<String>()
                        for ((k, v) in syncedOrdersCache) {
                            val eid = v.optString("id", "").replace("#", "").trim()
                            val eref = v.optString("reference", "").replace("#", "").trim()
                            if ((oid.isNotEmpty() && (k == oid || eid == oid || eref == oid)) ||
                                (oref.isNotEmpty() && (k == oref || eref == oref || eid == oref))) {
                                toRem.add(k)
                            }
                        }
                        for (k in toRem) syncedOrdersCache.remove(k)
                    }
                    if (oid.isNotEmpty()) removeOrderLiveActive(oid)
                    if (oref.isNotEmpty()) removeOrderLiveActive(oref)
                }
                "dev.saned.assistant.ACTION_REFRESH_ORDER_DETAILS" -> {
                    val oid = intent.getStringExtra("order_id") ?: ""
                    val oref = intent.getStringExtra("reference") ?: ""
                    val app = resolveApplicationContext() ?: currentActivity
                    CoroutineScope(Dispatchers.IO).launch {
                        if (app != null) {
                            try { scanJahezDatabases(app) } catch (_: Throwable) {}
                        }
                        if (oid.isNotEmpty()) fetchOrderCustomerDetailsAsync(oid, force = true)
                        if (oref.isNotEmpty() && oref != oid) fetchOrderCustomerDetailsAsync(oref, force = true)
                    }
                }
            }
        }
    }

    private val syncedOrdersCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())
    private val deletedOrderIds = ConcurrentHashMap.newKeySet<String>()

    fun getSyncedOrdersBatchJson(): String {
        val arr = JSONArray()
        val seenKeys = HashSet<String>()
        synchronized(syncedOrdersCache) {
            for (obj in syncedOrdersCache.values) {
                val r = obj.optString("reference", "").replace("#", "").trim()
                val id = obj.optString("id", "").replace("#", "").trim()
                if ((r.isNotEmpty() && seenKeys.contains(r)) || (id.isNotEmpty() && id != "0" && seenKeys.contains(id))) {
                    continue
                }
                if (r.isNotEmpty()) seenKeys.add(r)
                if (id.isNotEmpty() && id != "0") seenKeys.add(id)
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    private var lastPingDbScanRealtime: Long = 0L

    private fun respondToPing(intent: Intent) {
        val app = resolveApplicationContext() ?: currentActivity ?: return
        val pingTimestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())

        CoroutineScope(Dispatchers.IO).launch {
            try { syncSettings(app.contentResolver) } catch (_: Throwable) {}
            // Do not scan historical SQLite DB on every ping so old orders never flood or reorder the active list

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
    }

    private fun performLiveApiProbe() {
        val app = resolveApplicationContext() ?: currentActivity ?: return
        if (cachedAuthToken.isEmpty()) readExactJahezEncryptedPrefs(app)
        var token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        if (token.isEmpty()) {
  val scanned = scanJahezSharedPreferencesForToken(app)
  if (scanned.isNotEmpty()) token = scanned
        }

        CoroutineScope(Dispatchers.IO).launch {
  if (token.isEmpty()) {
      val hint = "Waiting for Live Session Token ⏳\n\n👉 Please open Saned (Jahez Fleets) app and log in. Saned Assistant automatically reads your session token from ENCRYPTED_SANED_PREFERENCES!"
      val noTokenIntent = Intent("dev.saned.assistant.ACTION_TEST_API_RESULT").apply {
          component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
          putExtra("status_code", 0)
          putExtra("latency_ms", 0L)
          putExtra("url", "https://gateway.saned.io/api/v1/dispatch/active")
          putExtra("body_snippet", hint)
          putExtra("has_token", false)
      }
      app.sendBroadcast(noTokenIntent)
      return@launch
  }
  val startTime = System.currentTimeMillis()
  var code = -1
  var snippet = ""
  var targetUrl = "https://gateway.saned.io/api/v1/dispatch/active"
  try {
      val rawTok = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
      val spec = Native.safeB(2, 0, rawTok, cachedLanguage, capturedDriverCipherB64)
      if (spec.size >= 2 && spec[1].startsWith("http")) {
          targetUrl = spec[1]
          val rb = Request.Builder().url(targetUrl).get()
          var idx = 2
          while (idx + 1 < spec.size) { rb.header(spec[idx], spec[idx + 1]); idx += 2 }
          val resp = httpClient.newCall(rb.build()).execute()
          code = resp.code
          val rawBody = resp.body?.string() ?: ""
          resp.close()
          cachedApiHost = "https://gateway.saned.io"
          snippet = if (rawBody.length > 250) rawBody.substring(0, 250) + "..." else rawBody.ifEmpty { "HTTP $code OK (gateway.saned.io)" }
      }
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

        val simPLat = 24.8105
        val simPLng = 46.6251
        val simDLat = 24.8264
        val simDLng = 46.6412
        val simCustDist = LocationEngine.calculateDistance(simPLat, simPLng, simDLat, simDLng)

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
            foodPrice = 65.00,
            paymentMethod = "CASH",
            currency = "SAR",
            reference = testRef,
            customerDistanceKm = simCustDist,
            pickupLat = simPLat,
            pickupLng = simPLng,
            destLat = simDLat,
            destLng = simDLng
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
    // Caches Price Food (سعر الطعام / قيمة الطلب) for all orders (both Prepaid and Cash on Delivery)
    private val foodPriceCache = ConcurrentHashMap<String, Double>()
    private val paymentMethodCache = ConcurrentHashMap<String, String>()
    private val fetchedDetailIds = ConcurrentHashMap.newKeySet<String>()
    private val lastDetailFetchTimeMap = ConcurrentHashMap<String, Long>()
    // Caches [pickupLat, pickupLng, destLat, destLng] per orderId / referenceId (matching Luf1 h,i,j,k)
    private val coordsCache = ConcurrentHashMap<String, DoubleArray>()

    fun cacheOrderPayment(idOrRef: String, foodPrice: Double, cashAmt: Double = 0.0, payMethod: String = "") {
        val key = idOrRef.replace("#", "").trim()
        if (key.isEmpty() || key == "0") return
        val effFood = if (!foodPrice.isNaN() && foodPrice > 0.0) foodPrice else if (!cashAmt.isNaN() && cashAmt > 0.0) cashAmt else 0.0
        if (effFood > 0.0) {
            val prev = foodPriceCache[key] ?: 0.0
            if (effFood >= prev) foodPriceCache[key] = effFood
        }
        if (payMethod.isNotEmpty() && payMethod != "null") {
            paymentMethodCache[key] = payMethod
        }
    }
    private val knownDbOrderIds = ConcurrentHashMap.newKeySet<String>()
    private val loggedNavOrderKeys = ConcurrentHashMap.newKeySet<String>()
    private val loggedLocationSchema = AtomicBoolean(false)

    fun cacheOrderCoords(idOrRef: String, pLat: Double, pLng: Double, dLat: Double, dLng: Double) {
        val key = idOrRef.replace("#", "").trim()
        if (key.isEmpty() || key == "0") return
        val cleanPLat = if (pLat.isNaN() || pLat.isInfinite()) 0.0 else pLat
        val cleanPLng = if (pLng.isNaN() || pLng.isInfinite()) 0.0 else pLng
        val cleanDLat = if (dLat.isNaN() || dLat.isInfinite()) 0.0 else dLat
        val cleanDLng = if (dLng.isNaN() || dLng.isInfinite()) 0.0 else dLng
        if (cleanPLat == 0.0 && cleanPLng == 0.0 && cleanDLat == 0.0 && cleanDLng == 0.0) return
        val prev = coordsCache[key]
        val merged = doubleArrayOf(
            if (cleanPLat != 0.0) cleanPLat else (prev?.getOrNull(0) ?: 0.0),
            if (cleanPLng != 0.0) cleanPLng else (prev?.getOrNull(1) ?: 0.0),
            if (cleanDLat != 0.0) cleanDLat else (prev?.getOrNull(2) ?: 0.0),
            if (cleanDLng != 0.0) cleanDLng else (prev?.getOrNull(3) ?: 0.0)
        )
        coordsCache[key] = merged
    }

    fun getCachedCoords(idOrRef: String): DoubleArray? {
        val key = idOrRef.replace("#", "").trim()
        if (key.isEmpty() || key == "0") return null
        return coordsCache[key]
    }

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
  DiagnosticLogger.logStep("BootstrapLoop", "Waiting for ActivityThread.currentApplication()...")
  var resolvedApp: Context? = null
  for (attempt in 0 until 200) {
      resolvedApp = resolveApplicationContext()
      if (resolvedApp != null) break
      try { Thread.sleep(150) } catch (_: Throwable) {}
  }
  if (resolvedApp != null) {
      DiagnosticLogger.logStep("BootstrapLoop", "Application context resolved: ${resolvedApp.javaClass.name}")
      try { initAppContext(resolvedApp) } catch (t: Throwable) {
          DiagnosticLogger.logError("BootstrapLoop", "Error in initAppContext", t)
      }
      val appObj = resolvedApp.applicationContext as? Application ?: (resolvedApp as? Application)
      if (appObj != null) {
          registerActivityCallbacksOnApp(appObj)
      }
  } else {
      DiagnosticLogger.logWarn("BootstrapLoop", "Timed out waiting for currentApplication()")
  }

  // Wait 3000ms matching Lb11$b delay(3000L) in SanedAssistant-98 so Jahez finishes initializing Tink EncryptedSharedPreferences & Room DB
  try { Thread.sleep(3000) } catch (_: Throwable) {}
  if (DiagnosticLogger.hookBootstrapLoop) {
      startActiveServerPolling()
  }

  // Continuous background SQLite + EncryptedSharedPreferences token sync loop matching Lb11$b in SanedAssistant-98
  while (true) {
      try {
          val ctx = resolveApplicationContext()
          if (ctx != null) {
              if (appContext == null) {
                  try { initAppContext(ctx) } catch (_: Throwable) {}
              }
              try { syncSettings(ctx.contentResolver) } catch (_: Throwable) {}
              try { checkRemoteKillSwitchFromSaned() } catch (_: Throwable) {}
              readExactJahezEncryptedPrefs(ctx)
              val nowRt = SystemClock.elapsedRealtime()
              if ((nowRt - lastSocketTouchRealtime) >= 18_000L) {
                  lastSocketTouchRealtime = nowRt
                  try {
                      val warmReq = Request.Builder().url("https://gateway.saned.io/").head()
                          .header("User-Agent", "okhttp/5.1.0")
                          .build()
                      httpClient.newCall(warmReq).execute().close()
                  } catch (_: Throwable) {}
              }
              // Do not scan historical SQLite DB in the 2.5s loop so old orders never shuffle active orders
          }
      } catch (t: Throwable) {
          DiagnosticLogger.logError("BootstrapLoop", "Error in background loop iteration", t)
      }
      try { Thread.sleep(2500) } catch (_: Throwable) { break }
  }
        }.apply {
  isDaemon = true
  name = "Saned-Bootstrap-Lb11"
  start()
        }
    }

    private val startedActivityCount = AtomicInteger(0)

    private fun registerActivityCallbacksOnApp(app: Application) {
        if (!isLifecycleCallbacksRegistered.compareAndSet(false, true)) return
        mainHandler.post {
  try {
      app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
          override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
              if (activity.packageName == "net.jahez.fleets") {
                  currentActivity = activity
              }
          }
          override fun onActivityStarted(activity: Activity) {
              if (activity.packageName == "net.jahez.fleets") {
                  currentActivity = activity
                  if (activity.javaClass.name != Native.safeE()) {
                      startedActivityCount.incrementAndGet()
                  }
              }
          }
          override fun onActivityResumed(activity: Activity) {
              if (activity.packageName == "net.jahez.fleets") {
                  currentActivity = activity
                  attachFloatingOverlayToActivity(activity)
              }
          }
          override fun onActivityPaused(activity: Activity) {}
          override fun onActivityStopped(activity: Activity) {
              if (activity.packageName == "net.jahez.fleets") {
                  if (activity.javaClass.name != Native.safeE()) {
                      startedActivityCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                  }
              }
          }
          override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
          override fun onActivityDestroyed(activity: Activity) {}
      })
      DiagnosticLogger.logStep("OrderInterceptor", "Registered Lji0 ActivityLifecycleCallbacks")
  } catch (t: Throwable) {
      DiagnosticLogger.logError("OrderInterceptor", "Failed registering ActivityLifecycleCallbacks", t)
  }
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
        DiagnosticLogger.cachedContext = app
        DiagnosticLogger.installCrashHandler(DiagnosticLogger.xposedModule, "initAppContext")
        DiagnosticLogger.logStep("OrderInterceptor", "initAppContext initialized (${app.javaClass.name})")

        try {
  val filter = IntentFilter().apply {
      addAction("dev.saned.assistant.SETTINGS_UPDATE")
      addAction("dev.saned.assistant.ACTION_PING")
      addAction("dev.saned.assistant.ACTION_TEST_API")
      addAction("dev.saned.assistant.ACTION_SIMULATE_ORDER")
      addAction("dev.saned.assistant.ACTION_CLEAR_TOKEN")
      addAction("dev.saned.assistant.ACTION_CLEAR_ORDERS")
      addAction("dev.saned.assistant.ACTION_DELETE_ORDER")
      addAction("dev.saned.assistant.ACTION_REFRESH_ORDER_DETAILS")
  }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      app.registerReceiver(settingsReceiver, filter, Context.RECEIVER_EXPORTED)
  } else {
      app.registerReceiver(settingsReceiver, filter)
  }
        } catch (t: Throwable) {
  DiagnosticLogger.logError("initAppContext", "Failed registering settingsReceiver", t)
        }

        syncSettings(app.contentResolver)
        LocationEngine.syncLocationSettings()
        DeviceSpoofer.syncSettings()
        LocationEngine.seedLocationManager(app)
        if (isMasterRunning) acquireWakeLock(app)
        (app as? Application)?.let { registerActivityCallbacksOnApp(it) }
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
        val intentMod = intent.getLongExtra("last_settings_mod_ms", System.currentTimeMillis())
        if (intentMod >= lastLocalOverlayModMs) lastLocalOverlayModMs = intentMod
        isMasterRunning = intent.getBooleanExtra("master_running", isMasterRunning)
        isAutoAccept = intent.getBooleanExtra("auto_accept", isAutoAccept)
        isAutoReject = intent.getBooleanExtra("auto_reject", isAutoReject)
        isDryRun = intent.getBooleanExtra("dry_run", isDryRun)
        isStayInBackground = intent.getBooleanExtra("stay_in_background_v2", isStayInBackground)
        isSilenceAlertTone = intent.getBooleanExtra("silence_alert_tone", isSilenceAlertTone)
        isStrictVersionCheck = intent.getBooleanExtra("strict_version_check", isStrictVersionCheck)
        isActivePolling = true
        pollIntervalSec = 0.0f
        parallelRequests = 3
        minOrderPrice = intent.getDoubleExtra("min_price", minOrderPrice)
        maxDistToRestaurant = intent.getDoubleExtra("max_dist_rest", maxDistToRestaurant)
        maxDistCustomer = intent.getDoubleExtra("max_dist_cust", maxDistCustomer)
        isExtraOrderEnabled = intent.getBooleanExtra("extra_order_enabled", isExtraOrderEnabled)
        maxOrdersLimit = if (isExtraOrderEnabled) 3 else intent.getIntExtra("max_orders_limit", 2).coerceIn(2, 3)
        isSoundEnabled = intent.getBooleanExtra("sound_enabled", isSoundEnabled)
        isShowToasts = false
        isFloatingOverlayEnabled = intent.getBooleanExtra("floating_overlay_enabled", isFloatingOverlayEnabled)
        intent.getStringExtra("app_ui_language")?.let { if (it.isNotBlank()) appUiLanguage = it }
        currentActivity?.let { attachFloatingOverlayToActivity(it) }
        LocationEngine.isMasterRunning = isMasterRunning
        LocationEngine.isFakeLocationEnabled = intent.getBooleanExtra("fake_location_enabled", LocationEngine.isFakeLocationEnabled)
        LocationEngine.isFixLocationUnknownEnabled = intent.getBooleanExtra("fix_location_unknown", LocationEngine.isFixLocationUnknownEnabled)
        val lat = intent.getDoubleExtra("fake_lat", LocationEngine.currentLat)
        val lng = intent.getDoubleExtra("fake_lng", LocationEngine.currentLng)
        val simAct = intent.getBooleanExtra("route_sim_active", LocationEngine.isTripSimulationActive)
        val simSpd = intent.getFloatExtra("route_sim_speed_kmh", LocationEngine.simulatedSpeedKmh)
        val simBrg = intent.getFloatExtra("route_sim_bearing", LocationEngine.currentBearing)
        LocationEngine.updateLiveRouteState(lat, lng, simSpd, simBrg, simAct)
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
                isActivePolling = true
                pollIntervalSec = 0.0f
                parallelRequests = 3
                minOrderPrice = json.optDouble("min_price", minOrderPrice)
                maxDistToRestaurant = json.optDouble("max_dist_rest", maxDistToRestaurant)
                maxDistCustomer = json.optDouble("max_dist_cust", maxDistCustomer)
                isExtraOrderEnabled = json.optBoolean("extra_order_enabled", isExtraOrderEnabled)
                maxOrdersLimit = if (isExtraOrderEnabled) 3 else json.optInt("max_orders_limit", 2).coerceIn(2, 3)
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

    private fun readDoubleFromPrefs(p: SharedPreferences, key1: String, key2: String, fallback: Double): Double {
        return try {
            val raw1 = p.all[key1]
            val raw2 = p.all[key2]
            val v = (raw1 ?: raw2)?.toString()?.trim()?.toDoubleOrNull()
            if (v != null && !v.isNaN() && v >= 0.0) v else fallback
        } catch (_: Throwable) { fallback }
    }

    private fun readDoubleFromBundle(b: android.os.Bundle, key1: String, key2: String, fallback: Double): Double {
        return try {
            val raw1 = b.get(key1)
            val raw2 = b.get(key2)
            val v = (raw1 ?: raw2)?.toString()?.trim()?.toDoubleOrNull()
            if (v != null && !v.isNaN() && v >= 0.0) v else fallback
        } catch (_: Throwable) { fallback }
    }

    private fun applyPrefsSnapshot(p: SharedPreferences) {
        isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
        isAutoAccept = p.getBoolean("auto_accept", isAutoAccept)
        isAutoReject = p.getBoolean("auto_reject", isAutoReject)
        isDryRun = false
        isStayInBackground = p.getBoolean("stay_in_background_v2", isStayInBackground)
        isSilenceAlertTone = p.getBoolean("silence_alert_tone", isSilenceAlertTone)
        isStrictVersionCheck = false
        licenseToken = p.getString("license_token_v1", licenseToken) ?: licenseToken
        licenseBoundId = p.getString("license_bound_id_v1", licenseBoundId) ?: licenseBoundId
        if (p.contains("jdrv_lic_key")) jdrvLicKey = p.getString("jdrv_lic_key", jdrvLicKey) ?: jdrvLicKey
        if (p.contains("jdrv_hw_device_id")) jdrvHwDeviceId = p.getString("jdrv_hw_device_id", jdrvHwDeviceId) ?: jdrvHwDeviceId
        if (p.contains("jdrv_lic_token_sig")) jdrvLicTokenSig = p.getString("jdrv_lic_token_sig", jdrvLicTokenSig) ?: jdrvLicTokenSig
        if (p.contains("jdrv_trial_ledger_entry")) jdrvTrialLedgerEntry = p.getString("jdrv_trial_ledger_entry", jdrvTrialLedgerEntry) ?: jdrvTrialLedgerEntry
        if (p.contains("jdrv_lic_active")) jdrvLicActive = p.getBoolean("jdrv_lic_active", jdrvLicActive)
        if (p.contains("jdrv_lic_expiry_ms")) jdrvLicExpiryMs = p.getLong("jdrv_lic_expiry_ms", jdrvLicExpiryMs)
        if (p.contains("jdrv_lic_feature_mask")) jdrvLicFeatureMask = p.getInt("jdrv_lic_feature_mask", jdrvLicFeatureMask)
        if (p.contains("jdrv_lic_epoch")) jdrvLicEpoch = p.getInt("jdrv_lic_epoch", jdrvLicEpoch)
        if (p.contains("jdrv_engine_magic")) jdrvEngineMagic = p.getInt("jdrv_engine_magic", jdrvEngineMagic)
        if (p.contains("jdrv_remote_min_epoch")) jdrvRemoteMinEpoch = p.getInt("jdrv_remote_min_epoch", jdrvRemoteMinEpoch)
        if (p.contains("jdrv_remote_blocked_devs_csv")) jdrvRemoteBlockedDevsCsv = p.getString("jdrv_remote_blocked_devs_csv", jdrvRemoteBlockedDevsCsv) ?: jdrvRemoteBlockedDevsCsv
        if (p.contains("jdrv_remote_blocked_keys_csv")) jdrvRemoteBlockedKeysCsv = p.getString("jdrv_remote_blocked_keys_csv", jdrvRemoteBlockedKeysCsv) ?: jdrvRemoteBlockedKeysCsv
        if (p.contains("jdrv_remote_app_enabled")) jdrvRemoteAppEnabled = p.getBoolean("jdrv_remote_app_enabled", jdrvRemoteAppEnabled)
        if (p.contains("jdrv_remote_auto_accept")) jdrvRemoteAutoAccept = p.getBoolean("jdrv_remote_auto_accept", jdrvRemoteAutoAccept)
        if (p.contains("jdrv_remote_deliver_order")) jdrvRemoteDeliverOrder = p.getBoolean("jdrv_remote_deliver_order", jdrvRemoteDeliverOrder)
        if (p.contains("jdrv_remote_floating_menu")) jdrvRemoteFloatingMenu = p.getBoolean("jdrv_remote_floating_menu", jdrvRemoteFloatingMenu)
        if (p.contains("jdrv_remote_android_id")) jdrvRemoteAndroidId = p.getBoolean("jdrv_remote_android_id", jdrvRemoteAndroidId)
        isActivePolling = true
        pollIntervalSec = 0.0f
        parallelRequests = 3
        minOrderPrice = readDoubleFromPrefs(p, "min_price", "min_order_price", minOrderPrice)
        maxDistToRestaurant = readDoubleFromPrefs(p, "max_dist_rest", "max_dist_to_restaurant", maxDistToRestaurant)
        maxDistCustomer = readDoubleFromPrefs(p, "max_dist_cust", "max_dist_restaurant_to_customer", maxDistCustomer)
        val pMax = p.all["max_orders_limit_str"]?.toString()?.toIntOrNull()
            ?: (p.all["max_orders_limit"] as? Int) ?: 0
        isExtraOrderEnabled = p.getBoolean("extra_order_enabled", pMax >= 3)
        maxOrdersLimit = if (isExtraOrderEnabled || pMax >= 3) 3 else 2
        isSoundEnabled = p.getBoolean("sound_enabled", isSoundEnabled)
        isShowToasts = false
        isFloatingOverlayEnabled = p.getBoolean("floating_overlay_enabled", isFloatingOverlayEnabled)
        p.getString("app_ui_language", null)?.let { if (it.isNotBlank()) appUiLanguage = it }
    }

    fun syncSettings(resolver: ContentResolver? = null) {
        // 1. Check remotePrefs FIRST, and only apply if not older than our local overlay modifications
        remotePrefs?.let { p ->
            val pMod = try { p.getLong("last_settings_mod_ms", 0L) } catch (_: Throwable) { 0L }
            if (lastLocalOverlayModMs == 0L || pMod >= lastLocalOverlayModMs) {
                applyPrefsSnapshot(p)
                if (pMod > lastLocalOverlayModMs) lastLocalOverlayModMs = pMod
            }
        }

        // 2. Check local SharedPreferences inside target app (authoritative in Non-Root --embed mode or after overlay edits)
        try {
            val ctx = appContext ?: currentActivity
            if (ctx != null) {
                val localSp = ctx.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
                val localMod = localSp.getLong("last_settings_mod_ms", 0L)
                if (localMod > 0L && localMod >= lastLocalOverlayModMs) {
                    applyPrefsSnapshot(localSp)
                    lastLocalOverlayModMs = localMod
                    LocationEngine.isMasterRunning = isMasterRunning
                    LocationEngine.isFakeLocationEnabled = localSp.getBoolean("fake_location_enabled", LocationEngine.isFakeLocationEnabled)
                    LocationEngine.isTripSimulationActive = localSp.getBoolean("route_sim_active", LocationEngine.isTripSimulationActive)
                }
            }
        } catch (_: Throwable) {}

        // 3. Check XposedProvider ContentProvider (authoritative when J-Driver MainActivity updates settings)
        val activeResolver = resolver ?: appContext?.contentResolver ?: currentActivity?.contentResolver
        if (activeResolver != null) {
            try {
                val uri = android.net.Uri.parse("content://dev.jing.sanedhook.XposedService")
                val bundle = activeResolver.call(uri, "getSettings", null, null)
                if (bundle != null && !bundle.isEmpty) {
                    val bMod = bundle.getLong("last_settings_mod_ms", 0L)
                    if (lastLocalOverlayModMs == 0L || bMod >= lastLocalOverlayModMs) {
                        if (bMod > lastLocalOverlayModMs) lastLocalOverlayModMs = bMod
                        isMasterRunning = bundle.getBoolean("master_running", bundle.getBoolean("enabled", isMasterRunning))
                        isAutoAccept = bundle.getBoolean("auto_accept", isAutoAccept)
                        isAutoReject = bundle.getBoolean("auto_reject", isAutoReject)
                        isDryRun = false
                        isStayInBackground = bundle.getBoolean("stay_in_background_v2", isStayInBackground)
                        isSilenceAlertTone = bundle.getBoolean("silence_alert_tone", isSilenceAlertTone)
                        isStrictVersionCheck = false
                        licenseToken = bundle.getString("license_token_v1", licenseToken) ?: licenseToken
                        licenseBoundId = bundle.getString("license_bound_id_v1", licenseBoundId) ?: licenseBoundId
                        if (bundle.containsKey("jdrv_lic_key")) jdrvLicKey = bundle.getString("jdrv_lic_key", jdrvLicKey) ?: jdrvLicKey
                        if (bundle.containsKey("jdrv_hw_device_id")) jdrvHwDeviceId = bundle.getString("jdrv_hw_device_id", jdrvHwDeviceId) ?: jdrvHwDeviceId
                        if (bundle.containsKey("jdrv_lic_token_sig")) jdrvLicTokenSig = bundle.getString("jdrv_lic_token_sig", jdrvLicTokenSig) ?: jdrvLicTokenSig
                        val bLedger = bundle.getString("jdrv_trial_ledger_entry", "") ?: ""
                        if (bLedger.isNotEmpty()) {
                            jdrvTrialLedgerEntry = bLedger
                            try {
                                (appContext ?: currentActivity)?.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
                                    ?.edit()?.putString("jdrv_trial_ledger_entry", bLedger)?.apply()
                            } catch (_: Throwable) {}
                        } else if (jdrvTrialLedgerEntry.isNotEmpty()) {
                            try {
                                val ex = android.os.Bundle().apply { putString("jdrv_trial_ledger_entry", jdrvTrialLedgerEntry) }
                                activeResolver.call(uri, "updateSettingsFromFloating", null, ex)
                            } catch (_: Throwable) {}
                        }
                        if (bundle.containsKey("jdrv_lic_active")) jdrvLicActive = bundle.getBoolean("jdrv_lic_active", jdrvLicActive)
                        if (bundle.containsKey("jdrv_lic_expiry_ms")) jdrvLicExpiryMs = bundle.getLong("jdrv_lic_expiry_ms", jdrvLicExpiryMs)
                        if (bundle.containsKey("jdrv_lic_feature_mask")) jdrvLicFeatureMask = bundle.getInt("jdrv_lic_feature_mask", jdrvLicFeatureMask)
                        if (bundle.containsKey("jdrv_lic_epoch")) jdrvLicEpoch = bundle.getInt("jdrv_lic_epoch", jdrvLicEpoch)
                        if (bundle.containsKey("jdrv_engine_magic")) jdrvEngineMagic = bundle.getInt("jdrv_engine_magic", jdrvEngineMagic)
                        if (bundle.containsKey("jdrv_remote_min_epoch")) jdrvRemoteMinEpoch = bundle.getInt("jdrv_remote_min_epoch", jdrvRemoteMinEpoch)
                        if (bundle.containsKey("jdrv_remote_blocked_devs_csv")) jdrvRemoteBlockedDevsCsv = bundle.getString("jdrv_remote_blocked_devs_csv", jdrvRemoteBlockedDevsCsv) ?: jdrvRemoteBlockedDevsCsv
                        if (bundle.containsKey("jdrv_remote_blocked_keys_csv")) jdrvRemoteBlockedKeysCsv = bundle.getString("jdrv_remote_blocked_keys_csv", jdrvRemoteBlockedKeysCsv) ?: jdrvRemoteBlockedKeysCsv
                        if (bundle.containsKey("jdrv_remote_app_enabled")) jdrvRemoteAppEnabled = bundle.getBoolean("jdrv_remote_app_enabled", jdrvRemoteAppEnabled)
                        if (bundle.containsKey("jdrv_remote_auto_accept")) jdrvRemoteAutoAccept = bundle.getBoolean("jdrv_remote_auto_accept", jdrvRemoteAutoAccept)
                        if (bundle.containsKey("jdrv_remote_deliver_order")) jdrvRemoteDeliverOrder = bundle.getBoolean("jdrv_remote_deliver_order", jdrvRemoteDeliverOrder)
                        if (bundle.containsKey("jdrv_remote_floating_menu")) jdrvRemoteFloatingMenu = bundle.getBoolean("jdrv_remote_floating_menu", jdrvRemoteFloatingMenu)
                        if (bundle.containsKey("jdrv_remote_android_id")) jdrvRemoteAndroidId = bundle.getBoolean("jdrv_remote_android_id", jdrvRemoteAndroidId)
                        if (bundle.containsKey("jdrv_remote_msg_ar")) jdrvRemoteMsgAr = bundle.getString("jdrv_remote_msg_ar", jdrvRemoteMsgAr) ?: jdrvRemoteMsgAr
                        if (bundle.containsKey("jdrv_remote_msg_en")) jdrvRemoteMsgEn = bundle.getString("jdrv_remote_msg_en", jdrvRemoteMsgEn) ?: jdrvRemoteMsgEn
                        isActivePolling = true
                        pollIntervalSec = 0.0f
                        parallelRequests = 3
                        minOrderPrice = readDoubleFromBundle(bundle, "min_price", "min_order_price", minOrderPrice)
                        maxDistToRestaurant = readDoubleFromBundle(bundle, "max_dist_rest", "max_dist_to_restaurant", maxDistToRestaurant)
                        maxDistCustomer = readDoubleFromBundle(bundle, "max_dist_cust", "max_dist_restaurant_to_customer", maxDistCustomer)
                        val bMax = bundle.get("max_orders_limit_str")?.toString()?.toIntOrNull() ?: bundle.getInt("max_orders_limit", 0)
                        isExtraOrderEnabled = bundle.getBoolean("extra_order_enabled", bMax >= 3)
                        maxOrdersLimit = if (isExtraOrderEnabled || bMax >= 3) 3 else 2
                        isSoundEnabled = bundle.getBoolean("sound_enabled", isSoundEnabled)
                        isShowToasts = false
                        isFloatingOverlayEnabled = bundle.getBoolean("floating_overlay_enabled", isFloatingOverlayEnabled)
                        bundle.getString("app_ui_language", null)?.let { if (it.isNotBlank()) appUiLanguage = it }
                        LocationEngine.applySettingsBundle(bundle)
                        DeviceSpoofer.applySettingsBundle(bundle)
                    } else {
                        // Our local overlay settings are newer than XposedProvider -> push them to XposedProvider!
                        (appContext ?: currentActivity)?.let { pushQuickSettingsUpdateToProvider(it) }
                    }
                    if (cachedAuthToken.isEmpty()) {
                        val savedTok = bundle.getString("cached_jahez_auth_token", "")?.trim() ?: ""
                        if (savedTok.isNotEmpty()) {
                            cachedAuthToken = savedTok
                            cachedHeaders["Authorization"] = savedTok
                        }
                    }
                    if (cachedApiHost.isEmpty()) {
                        val savedHost = bundle.getString("cached_jahez_api_host", "")?.trim() ?: ""
                        if (savedHost.isNotEmpty() && isValidJahezHost(savedHost)) {
                            cachedApiHost = savedHost
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (lastLocalOverlayModMs == 0L) {
            loadFallbackConfigFile()
        }
    }

    fun persistToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        try {
            val ctx = resolveApplicationContext() ?: appContext
            if (ctx != null) {
                File(ctx.filesDir, "saned_auth_token.txt").writeText(clean)
                val extras = android.os.Bundle().apply { putString("auth_token", clean) }
                ctx.contentResolver.call(
                    android.net.Uri.parse("content://dev.jing.sanedhook.XposedService"),
                    "saveAuthToken",
                    clean,
                    extras
                )
            }
        } catch (_: Throwable) {}
        try {
            val f = File("/data/local/tmp/saned_auth_token.txt")
            f.writeText(clean)
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    fun readPersistedToken(): String {
        try {
            val ctx = resolveApplicationContext() ?: appContext
            if (ctx != null) {
                val localFile = File(ctx.filesDir, "saned_auth_token.txt")
                if (localFile.exists()) {
                    val t = localFile.readText().trim()
                    if (t.isNotEmpty()) {
                        cachedAuthToken = t
                        cachedHeaders["Authorization"] = t
                        extractHostFromJwt(t)
                        return t
                    }
                }
                val b = ctx.contentResolver.call(
                    android.net.Uri.parse("content://dev.jing.sanedhook.XposedService"),
                    "getSettings",
                    null,
                    null
                )
                val pTok = b?.getString("cached_jahez_auth_token", "")?.trim() ?: ""
                if (pTok.isNotEmpty()) {
                    cachedAuthToken = pTok
                    cachedHeaders["Authorization"] = pTok
                    extractHostFromJwt(pTok)
                    return pTok
                }
            }
        } catch (_: Throwable) {}
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
            h == "api.jahez.net" || h.endsWith(".jahez.net") || h == "gateway.saned.io" || h.endsWith(".saned.io")
        } catch (_: Throwable) {
            false
        }
    }

    fun persistHost(host: String) {
        val clean = host.trim()
        if (!isValidJahezHost(clean)) return
        try {
            val ctx = resolveApplicationContext() ?: appContext
            if (ctx != null) {
                File(ctx.filesDir, "saned_api_host.txt").writeText(clean)
                val extras = android.os.Bundle().apply { putString("api_host", clean) }
                ctx.contentResolver.call(
                    android.net.Uri.parse("content://dev.jing.sanedhook.XposedService"),
                    "saveAuthToken",
                    null,
                    extras
                )
            }
        } catch (_: Throwable) {}
        try {
            val f = File("/data/local/tmp/saned_api_host.txt")
            f.writeText(clean)
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    fun readPersistedHost(): String {
        try {
            val ctx = resolveApplicationContext() ?: appContext
            if (ctx != null) {
                val localFile = File(ctx.filesDir, "saned_api_host.txt")
                if (localFile.exists()) {
                    val h = localFile.readText().trim()
                    if (isValidJahezHost(h)) {
                        cachedApiHost = h
                        return h
                    }
                }
            }
        } catch (_: Throwable) {}
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
    @Volatile private var cachedEncPrefs: SharedPreferences? = null

    fun readExactJahezEncryptedPrefs(context: Context) {
        try {
  val prefs = cachedEncPrefs ?: synchronized(this) {
      cachedEncPrefs ?: run {
          val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
          EncryptedSharedPreferences.create(
              context, Native.safeH(), masterKey,
              EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
              EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
          ).also { cachedEncPrefs = it }
      }
  }
  val tok = prefs.getString(Native.safeI(), "") ?: ""
  if (tok.isNotEmpty()) {
      val normNew = if (tok.startsWith("Bearer ", ignoreCase = true)) tok.trim() else "Bearer ${tok.trim()}"
      if (cachedAuthToken != normNew) {
          DiagnosticLogger.logStep("EncryptedPrefs", "Auth token read successfully (len=${tok.length})")
      }
      checkAndSetToken(tok, Native.safeI())
  }
  val lang = prefs.getString(Native.safeJ(), "en") ?: "en"
  if (lang.isNotEmpty()) cachedLanguage = lang
        } catch (t: Throwable) {
  DiagnosticLogger.logError("EncryptedPrefs", "Could not read ENCRYPTED_SANED_PREFERENCES yet", t)
        }
    }

    fun scanAllSharedPreferencesForTokens(context: Context) {
        readExactJahezEncryptedPrefs(context)
    }

    fun clearPersistedToken() {
        try {
            val ctx = resolveApplicationContext() ?: appContext
            if (ctx != null) {
                File(ctx.filesDir, "saned_auth_token.txt").delete()
            }
        } catch (_: Throwable) {}
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

    private fun hookIncomingDispatchActivity(module: XposedModule, classLoader: ClassLoader) {
        if (!isIncomingDispatchHooked.compareAndSet(false, true)) return
        try {
  val actClsName = Native.safeE()
  if (actClsName.isEmpty()) return
  val actCls = classLoader.loadClass(actClsName)
  val mOnCreate = actCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
  module.hook(mOnCreate).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val res = chain.proceed()
          val act = chain.thisObject as? Activity
          if (act != null) {
              try {
                  initAppContext(act.applicationContext)
                  currentActivity = act
                  val reqId = onActivityPreCreated(act)
                  if (reqId != 0) handlePostOrderActivityCreate(act, reqId)
              } catch (t: Throwable) {
                  DiagnosticLogger.logError("IncomingDispatchActivity", "Error in onCreate hook", t)
              }
          }
          return res
      }
  })
  DiagnosticLogger.logStep("OrderInterceptor", "Hooked $actClsName.onCreate")
        } catch (t: Throwable) {
  isIncomingDispatchHooked.set(false)
  DiagnosticLogger.logError("OrderInterceptor", "Failed hooking IncomingDispatchActivity", t)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // Matches SanedAssistant-98-7bac1ec.apk 100%: ONLY these 4 hooks (zero OkHttp/UI/SQLite/SharedPreferences hooks that crash Jahez)
        try { hookFirebase(module, classLoader) } catch (t: Throwable) { DiagnosticLogger.logError("hookFirebase", "Error", t) }
        try { hookIncomingDispatchActivity(module, classLoader) } catch (t: Throwable) { DiagnosticLogger.logError("hookIncomingDispatchActivity", "Error", t) }
        try { hookInstrumentation(module) } catch (t: Throwable) { DiagnosticLogger.logError("hookInstrumentation", "Error", t) }
        try { hookCipher(module) } catch (t: Throwable) { DiagnosticLogger.logError("hookCipher", "Error", t) }
        isOkHttpHooked = true
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
  var count = 0
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
                              val callerObj = chain.args.getOrNull(activityIdx)
                              val orderIdInt = extractExactOrderIdFromIntent(intent)
                              if (callerObj == null && orderIdInt != 0 && !isDryRun) {
                                  val entry = handledOrdersMap[orderIdInt]
                                  if (entry != null && (entry.decision == "REJECT" || (entry.decision == "ACCEPT" && apiConfirmedAcceptIds.contains(orderIdInt)))) {
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
              count++
          }
      }
  }
  DiagnosticLogger.logStep("OrderInterceptor", "Hooked Instrumentation.execStartActivity ($count overloads)")
        } catch (t: Throwable) {
  DiagnosticLogger.logError("hookInstrumentation", "Failed hooking Instrumentation", t)
        }
    }

    private val hookedFcmClasses = ConcurrentHashMap.newKeySet<String>()

    /**
     * Matches Ls90.f, Ls90.e, Ls90.g, Ls90.b, and Ls90$a in SanedAssistant-98-7bac1ec.apk:
     * Hooks SanedFCMNotificationHandler (Native.d()), extracts order fields via Ldy1 (Native.l()),
     * and suppresses chain.proceed() (returns null) when an order is auto-rejected (not dry-run).
     */
    private fun hookFirebase(module: XposedModule, classLoader: ClassLoader) {
        val clsName = Native.safeD()
        if (clsName.isEmpty() || !hookedFcmClasses.add(clsName)) return
        try {
  val fmsClass = classLoader.loadClass(clsName)
  var fcmCount = 0
  for (m in fmsClass.declaredMethods) {
      if (m.parameterCount == 1 && !java.lang.reflect.Modifier.isStatic(m.modifiers)) {
          val paramCls = m.parameterTypes[0]
          val hasMapGetter = paramCls.methods.any { it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType) }
          if (!hasMapGetter) continue
          module.hook(m).intercept(object : XposedInterface.Hooker {
              override fun intercept(chain: XposedInterface.Chain): Any? {
                  var decisionAction = ""
                  try {
                      val svc = chain.thisObject as? Context
                      if (svc != null) initAppContext(svc.applicationContext)
                      val remoteMessage = chain.args.getOrNull(0)
                      if (remoteMessage != null) {
                          val getDataMethod = cachedRemoteMsgGetDataMethod ?: remoteMessage.javaClass.methods.firstOrNull {
                              it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType)
                          }?.also { cachedRemoteMsgGetDataMethod = it }
                          val dataMap = getDataMethod?.invoke(remoteMessage) as? Map<*, *>
                          if (dataMap != null && dataMap.isNotEmpty()) {
                              decisionAction = processFcmMapWithLs90(dataMap)
                          }
                      }
                  } catch (t: Throwable) {
                      DiagnosticLogger.logError("FCM", "Error in FCM hook", t)
                  }
                  if (decisionAction == "REJECT" && !isDryRun) return null
                  return chain.proceed()
              }
          })
          fcmCount++
      }
  }
  DiagnosticLogger.logStep("OrderInterceptor", "Hooked $clsName ($fcmCount methods)")
        } catch (t: Throwable) {
  hookedFcmClasses.remove(clsName)
  DiagnosticLogger.logError("hookFirebase", "Failed hooking $clsName", t)
        }
    }

    private fun processFcmMapWithLs90(dataMap: Map<*, *>): String {
        val keys = CACHED_FCM_KEYS
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
        val fcmCashAmt = mapFirstStr("collectionAmount", "cashAmount", "cash_amount", "cod_amount").toDoubleOrNull() ?: 0.0
        val fcmFoodPrice = mapFirstStr("foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "subTotal", "collectionAmount").toDoubleOrNull() ?: fcmCashAmt
        val fcmPayMethod = mapFirstStr("paymentMethod", "payment_method", "paymentType", "type")
        val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
        val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
        val calcRestDist = if (pLat != 0.0 && pLng != 0.0 && drvLat != 0.0 && drvLng != 0.0) LocationEngine.calculateDistance(drvLat, drvLng, pLat, pLng) else distVal
        val calcCustDist = if (pLat != 0.0 && pLng != 0.0 && dLat != 0.0 && dLng != 0.0) LocationEngine.calculateDistance(pLat, pLng, dLat, dLng) else 0.0
        val cleanPrice = kotlin.math.round((delivPrice + bonusAmt) * 100.0) / 100.0

        // FIRE ACCEPT/REJECT AT 0.0ms FIRST before any logging or string formatting!
        val decision = handleOrderViaApi(
            orderId = "#$reqId",
            price = cleanPrice,
            dist = calcRestDist,
            restaurant = restName,
            custDist = calcCustDist,
            cashAmt = fcmCashAmt,
            foodPrice = fcmFoodPrice,
            paymentMethod = fcmPayMethod,
            pickupLat = pLat,
            pickupLng = pLng,
            destLat = dLat,
            destLng = dLng,
            sourceChannel = "PUSH"
        )
        DiagnosticLogger.logStep("FCM", "Order #$reqId received (price=$cleanPrice, restDist=$calcRestDist)")
        DiagnosticLogger.logStep(
            "NavLog",
            "FCM Push Order #$reqId: pickup=($pLat,$pLng) -> customer=($dLat,$dLng) restDist=${String.format(Locale.US, "%.2f", calcRestDist)}km custDist=${String.format(Locale.US, "%.2f", calcCustDist)}km"
        )
        return decision
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
            u.contains("ACCEPT") || u.contains("قبول") || u == "DISPATCHED" || u == "ASSIGNED" || u == "AUTO_DISPATCH" || u == "NEW_DISPATCH" -> "Accepted ✅"
            u.contains("CANCEL") -> "Cancelled"
            u.contains("REJECT") || u.contains("رفض") -> rawStatus
            u.contains("FAIL") || u.contains("MISSED") || u.contains("MANUAL") || u.contains("DRY") -> rawStatus
            rawStatus.isNotEmpty() -> rawStatus.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
            else -> "Accepted ✅"
        }
    }

    fun isHistoricalOrActiveStatus(rawStatus: String): Boolean {
        val u = rawStatus.trim().uppercase(Locale.US)
        if (u.isEmpty()) return false
        return u.contains("DELIVER") ||
               u.contains("COMPLET") ||
               u.contains("COLLECT") ||
               u.contains("PICKED") ||
               u.contains("DISPATCH") ||
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
        val syncedPrev = synchronized(syncedOrdersCache) {
            syncedOrdersCache[refStr] ?: syncedOrdersCache[idStr]
        }
        val prevSyncedCash = syncedPrev?.optDouble("cashAmount", 0.0)?.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
        val prevSyncedFood = syncedPrev?.optDouble("foodPrice", 0.0)?.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
        val finalCustName = posCustName ?: cachedDetail?.first?.takeIf { it.isNotEmpty() } ?: syncedPrev?.optString("customerName", "") ?: ""
        val finalCustPhone = posCustPhone ?: cachedDetail?.second?.takeIf { it.isNotEmpty() } ?: syncedPrev?.optString("customerPhone", "") ?: ""
        val finalCash = when {
            posCash > 0.0 -> posCash
            (cachedDetail?.third ?: 0.0) > 0.0 -> cachedDetail!!.third
            prevSyncedCash > 0.0 -> prevSyncedCash
            else -> 0.0
        }
        val cachedFood = foodPriceCache[idStr] ?: foodPriceCache[refStr] ?: 0.0
        val effectiveFoodPrice = when {
            posCash > 0.0 -> posCash
            cachedFood > 0.0 -> cachedFood
            prevSyncedFood > 0.0 -> prevSyncedFood
            finalCash > 0.0 -> finalCash
            else -> 0.0
        }
        val effectivePayMethod = posPayMethod.ifEmpty {
            paymentMethodCache[idStr] ?: paymentMethodCache[refStr] ?: syncedPrev?.optString("paymentMethod", "") ?: ""
        }
        // Only treat as Cash on Delivery if paymentMethod indicates CASH/COD or if posCash > 0 and paymentMethod is not explicitly PREPAID/ONLINE
        val isExplicitlyPrepaid = effectivePayMethod.contains("PREPAID", ignoreCase = true) ||
            effectivePayMethod.contains("ONLINE", ignoreCase = true) ||
            effectivePayMethod.contains("CARD", ignoreCase = true) ||
            effectivePayMethod.contains("APPLE", ignoreCase = true) ||
            effectivePayMethod.contains("MADA", ignoreCase = true) ||
            effectivePayMethod.contains("WALLET", ignoreCase = true) ||
            effectivePayMethod.contains("STC", ignoreCase = true)
        val effectiveCash = if (isExplicitlyPrepaid && posCash <= 0.0) 0.0 else finalCash

        if (effectiveFoodPrice > 0.0 || finalCustPhone.isNotEmpty() || finalCustName.isNotEmpty()) {
            customerDetailsCache[idStr] = Triple(finalCustName, finalCustPhone, effectiveFoodPrice)
            if (refStr.isNotEmpty()) customerDetailsCache[refStr] = Triple(finalCustName, finalCustPhone, effectiveFoodPrice)
        }
        cacheOrderPayment(idStr, effectiveFoodPrice, effectiveCash, effectivePayMethod)
        if (refStr.isNotEmpty()) cacheOrderPayment(refStr, effectiveFoodPrice, effectiveCash, effectivePayMethod)

        cacheOrderCoords(idStr, posPickupLat, posPickupLng, posDestLat, posDestLng)
        if (refStr.isNotEmpty()) cacheOrderCoords(refStr, posPickupLat, posPickupLng, posDestLat, posDestLng)
        val cachedC = getCachedCoords(idStr) ?: getCachedCoords(refStr)
        val effPickupLat = if (posPickupLat != 0.0) posPickupLat else (cachedC?.getOrNull(0) ?: 0.0)
        val effPickupLng = if (posPickupLng != 0.0) posPickupLng else (cachedC?.getOrNull(1) ?: 0.0)
        val effDestLat = if (posDestLat != 0.0) posDestLat else (cachedC?.getOrNull(2) ?: 0.0)
        val effDestLng = if (posDestLng != 0.0) posDestLng else (cachedC?.getOrNull(3) ?: 0.0)

        var calcDist = posDistKm
        if (calcDist == 0.0 && effPickupLat != 0.0 && effPickupLng != 0.0) {
            val drvLat = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLat != 0.0) LocationEngine.currentLat else LocationEngine.lastRealLat
            val drvLng = if (LocationEngine.isFakeLocationEnabled && LocationEngine.currentLng != 0.0) LocationEngine.currentLng else LocationEngine.lastRealLng
            if (drvLat != 0.0 && drvLng != 0.0) {
                calcDist = LocationEngine.calculateDistance(drvLat, drvLng, effPickupLat, effPickupLng)
            }
        }
        var calcCustDist = 0.0
        if (effPickupLat != 0.0 && effPickupLng != 0.0 && effDestLat != 0.0 && effDestLng != 0.0) {
            calcCustDist = LocationEngine.calculateDistance(effPickupLat, effPickupLng, effDestLat, effDestLng)
        }

        val navSig = "$idStr:$rawStatus:${String.format(Locale.US, "%.4f,%.4f->%.4f,%.4f", effPickupLat, effPickupLng, effDestLat, effDestLng)}:$posAddr"
        if (loggedNavOrderKeys.add(navSig)) {
            DiagnosticLogger.logStep(
                "NavLog",
                "SQLite dispatches_db #$idStr (ref=$refStr) status=[$rawStatus] foodPrice=$effectiveFoodPrice payMethod=$effectivePayMethod: pickup=($effPickupLat,$effPickupLng) -> customer=($effDestLat,$effDestLng) custDist=${String.format(Locale.US, "%.2f", calcCustDist)}km addr='${posAddr.ifEmpty { "Location on map" }}'"
            )
        }

        knownDbOrderIds.add(idStr)
        if (refStr.isNotEmpty()) knownDbOrderIds.add(refStr)
        val uSt = rawStatus.uppercase(Locale.US)
        val isCompleteOrOld = uSt.contains("DELIVER") || uSt.contains("COMPLET") || uSt.contains("CANCEL") || uSt.contains("REJECT") || uSt.contains("EXPIRED")
        val isUserDeleted = deletedOrderIds.contains(idStr) || (refStr.isNotEmpty() && deletedOrderIds.contains(refStr))
        if (isCompleteOrOld || isUserDeleted) {
            removeOrderLiveActive(idStr)
            removeOrderLiveActive(refStr)
            synchronized(syncedOrdersCache) {
                syncedOrdersCache.remove(idStr)
                syncedOrdersCache.remove(refStr)
            }
            return
        }

        // Only enrich/sync SQLite row if it is currently active in liveActiveOrderMap or syncedOrdersCache
        val isCurrentlyActive = liveActiveOrderMap.containsKey(idStr) || liveActiveOrderMap.containsKey(refStr) ||
            synchronized(syncedOrdersCache) { syncedOrdersCache.containsKey(idStr) || syncedOrdersCache.containsKey(refStr) }
        if (isCurrentlyActive) {
            loggedOrderIds.add("#$idStr")
            val hasCoordFlag = (effDestLat != 0.0 && effDestLng != 0.0)
            val normStatus = normalizeOrderStatusDisplay(rawStatus)
            val syncSignature = "$idStr:$refStr:$normStatus:${finalCustPhone.isNotEmpty()}:${effectiveCash.toInt()}:${effectiveFoodPrice.toInt()}:$effectivePayMethod:$hasCoordFlag"
            if (loggedOrderIds.add(syncSignature)) {
                sendOrderToLog(
                    orderId = "#$idStr",
                    price = posPrice,
                    dist = calcDist,
                    restaurant = restName,
                    status = normStatus,
                    customerName = finalCustName,
                    customerPhone = finalCustPhone,
                    address = posAddr.ifEmpty { if (effDestLat != 0.0) "Location on map" else "" },
                    cashAmount = effectiveCash,
                    foodPrice = effectiveFoodPrice,
                    paymentMethod = effectivePayMethod,
                    currency = posCurrency,
                    reference = refStr,
                    customerDistanceKm = calcCustDist,
                    pickupLat = effPickupLat,
                    pickupLng = effPickupLng,
                    destLat = effDestLat,
                    destLng = effDestLng,
                    notifyUser = false
                )
            }
        }
        if ((finalCustPhone.isEmpty() || effectiveFoodPrice <= 0.0) && !isCompleteOrOld) {
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
            val exactDbName = Native.safeF()
            val exactSql = Native.safeG()
            if (exactDbName.isEmpty() || exactSql.isEmpty()) return
            val dbFile = context.getDatabasePath(exactDbName)
            if (!dbFile.exists()) return
            var exactDb: SQLiteDatabase? = null
            var exactCursor: Cursor? = null
            try {
                val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                val openedDb = SQLiteDatabase.openDatabase(dbFile.path, null, flags)
                exactDb = openedDb
                // Pre-scan payment table for any food/order/collection amount & payment method columns so Accepted & Picked Up orders always show Price Food
                var payCursor: Cursor? = null
                try {
                    payCursor = openedDb.rawQuery("SELECT * FROM payment", null)
                    val colNames = payCursor.columnNames ?: emptyArray()
                    val idColIdx = colNames.indexOfFirst { it.equals("dispatchId", ignoreCase = true) || it.equals("id", ignoreCase = true) }
                    val payMethodIdx = colNames.indexOfFirst {
                        it.contains("method", ignoreCase = true) || it.contains("type", ignoreCase = true) || it.contains("mode", ignoreCase = true)
                    }
                    if (idColIdx >= 0) {
                        while (payCursor.moveToNext()) {
                            val dId = cursorStringOrNull(payCursor, idColIdx) ?: cursorDoubleOrNull(payCursor, idColIdx)?.toLong()?.toString() ?: continue
                            var bestFoodAmt = 0.0
                            val pMethod = if (payMethodIdx >= 0) (cursorStringOrNull(payCursor, payMethodIdx) ?: "") else ""
                            for (ci in colNames.indices) {
                                if (ci == idColIdx || ci == payMethodIdx) continue
                                val cn = colNames[ci].lowercase(Locale.US)
                                if (cn.contains("delivery") || cn.contains("driver") || cn.contains("bonus") || cn.contains("tip") || cn.contains("id")) continue
                                val dv = cursorDoubleOrNull(payCursor, ci) ?: cursorStringOrNull(payCursor, ci)?.toDoubleOrNull() ?: 0.0
                                if (dv > bestFoodAmt) bestFoodAmt = dv
                            }
                            if (bestFoodAmt > 0.0 || pMethod.isNotEmpty()) {
                                cacheOrderPayment(dId, bestFoodAmt, bestFoodAmt, pMethod)
                                if (bestFoodAmt > 0.0) {
                                    val prev = customerDetailsCache[dId]
                                    customerDetailsCache[dId] = Triple(prev?.first ?: "", prev?.second ?: "", bestFoodAmt)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { payCursor?.close() } catch (_: Throwable) {}
                }
                // Pre-scan location table in dispatches_db so pickup & customer coordinates are always cached & logged in [NavLog]
                var locCursor: Cursor? = null
                try {
                    locCursor = openedDb.rawQuery("SELECT * FROM location", null)
                    val lCols = locCursor.columnNames ?: emptyArray()
                    if (loggedLocationSchema.compareAndSet(false, true)) {
                        DiagnosticLogger.logStep("NavLog", "dispatches_db.location columns: [${lCols.joinToString(", ")}]")
                    }
                    val dispIdIdx = lCols.indexOfFirst { it.equals("dispatchId", ignoreCase = true) || it.equals("id", ignoreCase = true) }
                    val pLatIdx = lCols.indexOfFirst { it.contains("pickupLat", ignoreCase = true) }
                    val pLngIdx = lCols.indexOfFirst { it.contains("pickupLong", ignoreCase = true) || it.contains("pickupLng", ignoreCase = true) }
                    val dLatIdx = lCols.indexOfFirst { it.contains("destLat", ignoreCase = true) || it.contains("destinationLat", ignoreCase = true) || it.contains("dropoffLat", ignoreCase = true) }
                    val dLngIdx = lCols.indexOfFirst { it.contains("destLong", ignoreCase = true) || it.contains("destinationLong", ignoreCase = true) || it.contains("destLng", ignoreCase = true) || it.contains("dropoffLong", ignoreCase = true) }
                    if (dispIdIdx >= 0) {
                        while (locCursor.moveToNext()) {
                            val dId = cursorStringOrNull(locCursor, dispIdIdx) ?: cursorDoubleOrNull(locCursor, dispIdIdx)?.toLong()?.toString() ?: continue
                            val pl = if (pLatIdx >= 0) (cursorDoubleOrNull(locCursor, pLatIdx) ?: 0.0) else 0.0
                            val pg = if (pLngIdx >= 0) (cursorDoubleOrNull(locCursor, pLngIdx) ?: 0.0) else 0.0
                            val dl = if (dLatIdx >= 0) (cursorDoubleOrNull(locCursor, dLatIdx) ?: 0.0) else 0.0
                            val dg = if (dLngIdx >= 0) (cursorDoubleOrNull(locCursor, dLngIdx) ?: 0.0) else 0.0
                            cacheOrderCoords(dId, pl, pg, dl, dg)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { locCursor?.close() } catch (_: Throwable) {}
                }
                val c = openedDb.rawQuery(exactSql, null)
                exactCursor = c
                while (c.moveToNext()) {
                    parseCursorRowWithLnzW(c)
                }
            } catch (_: Throwable) {
            } finally {
                try { exactCursor?.close() } catch (_: Throwable) {}
                try { exactDb?.close() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun fetchOrderCustomerDetailsAsync(orderId: String, force: Boolean = false) {
        val cleanId = orderId.replace("#", "").trim()
        val orderIdInt = cleanId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
        if (cleanId.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val lastFetch = lastDetailFetchTimeMap[cleanId] ?: 0L
        val hasCachedFood = (foodPriceCache[cleanId] ?: 0.0) > 0.0
        if (!force && fetchedDetailIds.contains(cleanId) && (hasCachedFood || (now - lastFetch) < 6000L)) return
        if (!force && (now - lastFetch) < 2000L) return
        lastDetailFetchTimeMap[cleanId] = now
        fetchedDetailIds.add(cleanId)

        val ctx = resolveApplicationContext() ?: currentActivity
        if (cachedAuthToken.isEmpty() && ctx != null) readExactJahezEncryptedPrefs(ctx)
        val token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        if (token.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val client = capturedAppHttpClient ?: httpClient
            val serverKeys = Native.safeM()
            val kClientPhone = serverKeys.getOrNull(5) ?: "clientPhone"
            val kContactNum = serverKeys.getOrNull(6) ?: "contactNumber"
            val rawTok = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()

            var cPhone = customerDetailsCache[cleanId]?.second ?: ""
            var cName = customerDetailsCache[cleanId]?.first ?: ""
            var cCash = customerDetailsCache[cleanId]?.third ?: 0.0
            var cFoodPrice = foodPriceCache[cleanId] ?: cCash
            var cPayMethod = paymentMethodCache[cleanId] ?: ""
            var cRef = ""

            if (orderIdInt > 0) {
                // 1. Exact Ljw.a endpoint via Native.safeB(3, orderIdInt, ...): GET https://gateway.saned.io/api/v1/dispatch-location/contact-number/{id}
                try {
                    val spec = Native.safeB(3, orderIdInt, rawTok, cachedLanguage, capturedDriverCipherB64)
                    if (spec.size >= 2 && spec[1].startsWith("http")) {
                        val rb = Request.Builder().url(spec[1]).get()
                        var idx = 2
                        while (idx + 1 < spec.size) { rb.header(spec[idx], spec[idx + 1]); idx += 2 }
                        val resp = client.newCall(rb.build()).execute()
                        val code = resp.code
                        val body = resp.body?.string() ?: ""
                        resp.close()
                        if (code in 200..299 && body.trim().startsWith("{")) {
                            val json = JSONObject(body)
                            val dataObj = json.optJSONObject("data") ?: json
                            val p = extractFirstString(dataObj, kClientPhone, kContactNum, "phone", "mobile", "customer_phone")
                            val n = extractFirstString(dataObj, "subscriberName", "customer_name", "client_name", "name")
                            val ca = extractFirstDouble(dataObj, "collectionAmount", "cash_amount", "cashAmount")
                            val fp = extractFirstDouble(dataObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "subTotal", "collectionAmount")
                            val pm = extractFirstString(dataObj, "paymentMethod", "payment_method", "paymentType", "type")
                            if (p.isNotEmpty()) cPhone = p
                            if (n.isNotEmpty()) cName = n
                            if (ca > 0.0) cCash = ca
                            if (fp > 0.0) cFoodPrice = fp
                            if (pm.isNotEmpty()) cPayMethod = pm
                        }
                    }
                } catch (_: Throwable) {}

                // 2. Fetch read-only dispatch details GET https://gateway.saned.io/api/v1/dispatch/{id} (works immediately on Accepted orders!)
                try {
                    val rb = Request.Builder().url("https://gateway.saned.io/api/v1/dispatch/$orderIdInt").get()
                        .header("Authorization", "Bearer $rawTok")
                        .header("Accept-Language", cachedLanguage.ifEmpty { "en" })
                        .header("User-Agent", "okhttp/5.1.0")
                    if (capturedDriverCipherB64.isNotEmpty()) rb.header("x-unique-Identifier", capturedDriverCipherB64)
                    val resp = client.newCall(rb.build()).execute()
                    val code = resp.code
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    if (code in 200..299 && body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data") ?: json
                        val refVal = extractFirstString(dataObj, "referenceId", "reference", "order_number", "orderNumber")
                        if (refVal.isNotEmpty()) cRef = refVal
                        val payObj = dataObj.optJSONObject("payment") ?: json.optJSONObject("payment")
                        if (payObj != null) {
                            val ca = extractFirstDouble(payObj, "collectionAmount", "cash_amount", "cashAmount", "cod_amount")
                            val fp = extractFirstDouble(payObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "invoice_amount", "subTotal", "sub_total", "itemsAmount", "collectionAmount", "amount")
                            val pm = extractFirstString(payObj, "paymentMethod", "payment_method", "paymentType", "type", "mode")
                            if (ca > 0.0) cCash = ca
                            if (fp > 0.0) cFoodPrice = fp
                            if (pm.isNotEmpty()) cPayMethod = pm
                        }
                        if (cFoodPrice <= 0.0) {
                            cFoodPrice = extractFirstDouble(dataObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "subTotal", "collectionAmount", "cashAmount")
                        }
                        if (cCash <= 0.0) {
                            cCash = extractFirstDouble(dataObj, "collectionAmount", "cash_amount", "cashAmount")
                        }
                        if (cPayMethod.isEmpty()) {
                            cPayMethod = extractFirstString(dataObj, "paymentMethod", "payment_method", "paymentType")
                        }
                        val clObj = dataObj.optJSONObject("client") ?: dataObj.optJSONObject("customer")
                        if (cPhone.isEmpty()) {
                            cPhone = (if (clObj != null) extractFirstString(clObj, kClientPhone, kContactNum, "phone", "mobile") else "").ifEmpty {
                                extractFirstString(dataObj, kClientPhone, kContactNum, "phone", "mobile", "customer_phone")
                            }
                        }
                        if (cName.isEmpty()) {
                            cName = (if (clObj != null) extractFirstString(clObj, "subscriberName", "name", "customer_name") else "").ifEmpty {
                                extractFirstString(dataObj, "subscriberName", "customer_name", "client_name", "name")
                            }
                        }
                        val locObj = dataObj.optJSONObject("location") ?: json.optJSONObject("location")
                        val pickObj = dataObj.optJSONObject("pickup") ?: locObj?.optJSONObject("pickup")
                        val dstObj = dataObj.optJSONObject("destination") ?: dataObj.optJSONObject("dropoff") ?: locObj?.optJSONObject("destination")
                        var apiPLat = extractFirstDouble(locObj ?: dataObj, "pickupLatitude", "pickupLat", "dispatchPickupLat")
                        var apiPLng = extractFirstDouble(locObj ?: dataObj, "pickupLongitude", "pickupLng", "dispatchPickupLng")
                        var apiDLat = extractFirstDouble(locObj ?: dataObj, "destinationLatitude", "destLat", "dispatchDestLat")
                        var apiDLng = extractFirstDouble(locObj ?: dataObj, "destinationLongitude", "destLng", "dispatchDestLng")
                        if (apiPLat == 0.0 && pickObj != null) {
                            apiPLat = extractFirstDouble(pickObj, "lat", "latitude")
                            apiPLng = extractFirstDouble(pickObj, "lng", "lon", "longitude")
                            val pc = pickObj.optJSONArray("coordinates")
                            if (pc != null && pc.length() >= 2) {
                                val c0 = pc.optDouble(0, 0.0); val c1 = pc.optDouble(1, 0.0)
                                if (c1 in 15.0..35.0) { apiPLng = c0; apiPLat = c1 } else { apiPLat = c0; apiPLng = c1 }
                            }
                        }
                        if (apiDLat == 0.0 && dstObj != null) {
                            apiDLat = extractFirstDouble(dstObj, "lat", "latitude")
                            apiDLng = extractFirstDouble(dstObj, "lng", "lon", "longitude")
                            val dc = dstObj.optJSONArray("coordinates")
                            if (dc != null && dc.length() >= 2) {
                                val c0 = dc.optDouble(0, 0.0); val c1 = dc.optDouble(1, 0.0)
                                if (c1 in 15.0..35.0) { apiDLng = c0; apiDLat = c1 } else { apiDLat = c0; apiDLng = c1 }
                            }
                        }
                        if (apiPLat != 0.0 || apiDLat != 0.0) {
                            cacheOrderCoords(cleanId, apiPLat, apiPLng, apiDLat, apiDLng)
                            if (cRef.isNotEmpty()) cacheOrderCoords(cRef, apiPLat, apiPLng, apiDLat, apiDLng)
                            DiagnosticLogger.logStep("NavLog", "API /dispatch/$orderIdInt coords: pickup=($apiPLat,$apiPLng) -> customer=($apiDLat,$apiDLng)")
                        }
                    }
                } catch (_: Throwable) {}
            }

            // 3. Also check active dispatches endpoint (/api/v1/dispatch/active) if foodPrice or referenceId is still missing
            if (cFoodPrice <= 0.0 || cRef.isEmpty()) {
                try {
                    val spec2 = Native.safeB(2, 0, rawTok, cachedLanguage, capturedDriverCipherB64)
                    val activeUrl = if (spec2.size >= 2 && spec2[1].startsWith("http")) spec2[1] else "https://gateway.saned.io/api/v1/dispatch/active"
                    val rb2 = Request.Builder().url(activeUrl).get()
                    if (spec2.size >= 4) {
                        var idx = 2
                        while (idx + 1 < spec2.size) { rb2.header(spec2[idx], spec2[idx + 1]); idx += 2 }
                    } else {
                        rb2.header("Authorization", "Bearer $rawTok")
                           .header("Accept-Language", cachedLanguage.ifEmpty { "en" })
                           .header("User-Agent", "okhttp/5.1.0")
                        if (capturedDriverCipherB64.isNotEmpty()) rb2.header("x-unique-Identifier", capturedDriverCipherB64)
                    }
                    val resp2 = client.newCall(rb2.build()).execute()
                    val code2 = resp2.code
                    val body2 = resp2.body?.string() ?: ""
                    resp2.close()
                    if (code2 in 200..299 && body2.isNotEmpty()) {
                        findAndParseEmbeddedJson(body2, "Active Dispatches Sync 🔄")
                    }
                } catch (_: Throwable) {}
            }

            if (cFoodPrice <= 0.0 && cCash > 0.0) cFoodPrice = cCash
            if (cPhone.isNotEmpty() || cName.isNotEmpty() || cFoodPrice > 0.0 || cCash > 0.0 || cPayMethod.isNotEmpty() || cRef.isNotEmpty() || getCachedCoords(cleanId) != null) {
                if (cPhone.isNotEmpty() || cName.isNotEmpty() || cFoodPrice > 0.0) {
                    customerDetailsCache[cleanId] = Triple(cName, cPhone, if (cFoodPrice > 0.0) cFoodPrice else cCash)
                    if (cRef.isNotEmpty()) customerDetailsCache[cRef] = Triple(cName, cPhone, if (cFoodPrice > 0.0) cFoodPrice else cCash)
                }
                cacheOrderPayment(cleanId, cFoodPrice, cCash, cPayMethod)
                if (cRef.isNotEmpty()) cacheOrderPayment(cRef, cFoodPrice, cCash, cPayMethod)
                DiagnosticLogger.logStep("ContactFetch", "Fetched details for #$cleanId (ref=$cRef, phone=${cPhone.isNotEmpty()}, foodPrice=$cFoodPrice, cash=$cCash, method=$cPayMethod)")

                // Immediately push updated order to Orders tab so Accepted card shows Price Food without waiting!
                val existingJson = synchronized(syncedOrdersCache) {
                    syncedOrdersCache[cRef] ?: syncedOrdersCache[cleanId] ?: syncedOrdersCache[orderIdInt.toString()]
                }
                if (existingJson != null) {
                    val prevModel = OrderModel.fromJson(existingJson)
                    sendOrderToLog(
                        orderId = "#${if (prevModel.id != 0) prevModel.id else cleanId}",
                        price = prevModel.price,
                        dist = prevModel.distanceKm,
                        restaurant = prevModel.restaurant,
                        status = prevModel.status,
                        customerName = cName.ifEmpty { prevModel.customerName },
                        customerPhone = cPhone.ifEmpty { prevModel.customerPhone },
                        address = prevModel.address,
                        cashAmount = if (cCash > 0.0) cCash else prevModel.cashAmount,
                        foodPrice = if (cFoodPrice > 0.0) cFoodPrice else prevModel.foodPrice,
                        paymentMethod = cPayMethod.ifEmpty { prevModel.paymentMethod },
                        currency = prevModel.currency,
                        reference = cRef.ifEmpty { prevModel.reference },
                        pickupAddress = prevModel.pickupAddress,
                        customerDistanceKm = prevModel.customerDistanceKm,
                        pickupLat = prevModel.pickupLat,
                        pickupLng = prevModel.pickupLng,
                        destLat = prevModel.destLat,
                        destLng = prevModel.destLng,
                        decisionReason = prevModel.decisionReason,
                        notifyUser = false
                    )
                }
                if (ctx != null) scanJahezDatabases(ctx)
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
                      val newB64 = Base64.encodeToString(outputBytes, Base64.NO_WRAP)
                      if (newB64.isNotEmpty() && newB64 != capturedDriverCipherB64) {
                          capturedDriverCipherB64 = newB64
                          DiagnosticLogger.logStep("CipherHook", "Captured encrypted driverId header (${capturedDriverCipherB64.take(12)}...)")
                      }
                  } else if (outputBytes.size in 80..4096) {
                      val str = String(outputBytes, Charsets.UTF_8).trim()
                      val eyIdx = str.indexOf("eyJ")
                      if (eyIdx in 0..32) {
                          val candidate = str.substring(eyIdx).takeWhile { !it.isWhitespace() && it != '"' && it != '\'' && it.code in 33..126 }
                          if (candidate.count { it == '.' } >= 2 && candidate.length > 60) {
                              checkAndSetToken(candidate, "TinkDecryptedToken")
                          }
                      }
                  }
              } catch (_: Throwable) {}
          }
          return result
      }
  })
  DiagnosticLogger.logStep("OrderInterceptor", "Hooked Cipher.doFinal(byte[])")
        } catch (t: Throwable) {
  DiagnosticLogger.logError("hookCipher", "Failed hooking Cipher.doFinal", t)
        }
    }

    private val isInsideJsonHook = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }
    private val recentMemoryJsonHashes = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private var lastUiResumeSyncRealtime: Long = 0L
    private val cachedWmgInstance: Any? by lazy {
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            wmgClass.getMethod("getInstance").invoke(null)
        } catch (_: Throwable) { null }
    }
    private val cachedWmgViewsField: java.lang.reflect.Field? by lazy {
        try {
            Class.forName("android.view.WindowManagerGlobal").getDeclaredField("mViews").apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    private fun hookJsonConstructors(module: XposedModule) {
        try {
            val jsonConst = JSONObject::class.java.getConstructor(String::class.java)
            module.hook(jsonConst).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    if (isInsideJsonHook.get() == true) return res
                    val str = chain.args.getOrNull(0) as? String ?: return res
                    if (str.length in 25..65536) {
                        val hasOrderKeyword = str.contains("dispatch", ignoreCase = true) ||
                            str.contains("order", ignoreCase = true) ||
                            str.contains("pickup", ignoreCase = true) ||
                            str.contains("referenceId", ignoreCase = true)
                        if (hasOrderKeyword) {
                            val h = str.hashCode()
                            if (recentMemoryJsonHashes.size > 400) recentMemoryJsonHashes.clear()
                            if (recentMemoryJsonHashes.add(h)) {
                                CoroutineScope(Dispatchers.Default).launch {
                                    isInsideJsonHook.set(true)
                                    try {
                                        findAndParseEmbeddedJson(str, "Memory JSON 🧠")
                                    } catch (_: Throwable) {
                                    } finally {
                                        isInsideJsonHook.set(false)
                                    }
                                }
                            }
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

    private fun onTargetActivityActive(act: Activity) {
        currentActivity = act
        val appCtx = act.applicationContext ?: act
        initAppContext(appCtx)
        attachFloatingOverlayToActivity(act)
        startScreenWatcher()
        startActiveServerPolling()

        val actName = act.javaClass.name
        if (actName.contains("Dispatch", ignoreCase = true) || actName.contains("Order", ignoreCase = true)) {
            scanAllRoots()
        }

        val nowRt = SystemClock.elapsedRealtime()
        if (nowRt - lastUiResumeSyncRealtime > 6000L) {
            lastUiResumeSyncRealtime = nowRt
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (cachedAuthToken.isEmpty()) {
                        scanAllSharedPreferencesForTokens(appCtx)
                    }
                    requestSettingsFromAssistant()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookUI(module: XposedModule, classLoader: ClassLoader) {
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        onTargetActivityActive(act)
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
                        onTargetActivityActive(act)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}
    }

    fun getAllRootViews(): List<View> {
        val result = mutableListOf<View>()
        try {
            val wmg = cachedWmgInstance
            val fViews = cachedWmgViewsField
            if (wmg != null && fViews != null) {
                val list = fViews.get(wmg) as? List<*>
                if (list != null) {
                    synchronized(list) {
                        for (item in list) {
                            if (item is View) {
                                result.add(item)
                            }
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
                var nextDelayMs = 1200L
                try {
                    val act = currentActivity
                    if (isMasterRunning && act != null && !act.isFinishing && !act.isDestroyed) {
                        val actName = act.javaClass.name
                        val isOrderScreen = actName.contains("Dispatch", ignoreCase = true) || actName.contains("Order", ignoreCase = true)
                        if (isOrderScreen) {
                            nextDelayMs = 180L
                            scanAllRoots()
                        } else {
                            val roots = getAllRootViews()
                            if (roots.size > 1) {
                                nextDelayMs = 350L
                                scanAllRoots(roots)
                            }
                        }
                    }
                } catch (_: Throwable) {}
                mainHandler.postDelayed(this, nextDelayMs)
            }
        })
    }

    fun scanAllRoots(preFetchedRoots: List<View>? = null) {
        val act = currentActivity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val roots = preFetchedRoots ?: getAllRootViews()
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
                val delayMs = ((pollIntervalSec.coerceIn(2.0f, 10.0f)) * 1000).toLong()
                delay(delayMs)
            }
        }
    }

    private var failedPollingCount = 0

    private fun pollServerForAvailableOrders() {
        if (!DiagnosticLogger.hookBootstrapLoop) return
        val ctx = resolveApplicationContext() ?: currentActivity
        if (cachedAuthToken.isEmpty() && ctx != null) readExactJahezEncryptedPrefs(ctx)
        val token = cachedAuthToken
        if (token.isEmpty()) return
        val clientToUse = httpClient

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
          if (code == 401 || code == 403) {
              cachedAuthToken = ""
              cachedEncPrefs = null
              if (ctx != null) readExactJahezEncryptedPrefs(ctx)
          } else if (code in 200..299 && body.isNotEmpty()) {
              failedPollingCount = 0
              reconcileLiveActiveOrdersFromServer(body)
              findAndParseEmbeddedJson(body, "Server Polling (POLL) ⚡")
              return
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
            var customerPhone = extractFirstString(json, "clientPhone", "contactNumber", "customer_phone", "client_phone", "customerPhone", "phone", "mobile", "contact_number")
            var address = extractFirstString(json, "customer_address", "delivery_address", "dropoff_address", "address", "dropoffLocation", "destination_address", "location_address")
            var cashAmount = extractFirstDouble(json, "collectionAmount", "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
            var foodPrice = extractFirstDouble(json, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "invoice_amount", "subTotal", "sub_total", "itemsAmount", "items_amount", "collectionAmount", "cashAmount")
            var paymentMethod = extractFirstString(json, "paymentMethod", "payment_method", "paymentType", "payment_type", "paymentMode")
            var currency = extractFirstString(json, "currency", "currencyCode", "curr").ifEmpty { "SAR" }
            var status = extractFirstString(json, "dispatchType", "status", "order_status", "state").ifEmpty { "Accepted ✅" }

            val dataObj = json.optJSONObject("data") ?: json.optJSONObject("order") ?: json.optJSONObject("trip") ?: json.optJSONObject("payload")
            if (dataObj != null) {
                if (orderId.isEmpty()) orderId = extractFirstString(dataObj, "requestId", "dispatchId", "order_number", "order_id", "orderNumber", "orderId", "id", "code", "trip_id", "tripId")
                if (reference.isEmpty()) reference = extractFirstString(dataObj, "referenceId", "reference", "order_number", "orderNumber", "order_code", "code", "id")
                if (price == 0.0) price = extractFirstDouble(dataObj, "deliveryPrice", "price", "delivery_cost", "delivery_fee", "deliveryFee", "deliveryCost", "driver_fee", "driverFee", "earning", "amount", "total")
                if (dist == 0.0) dist = extractFirstDouble(dataObj, "distance", "km", "distance_to_pickup", "distance_in_km", "distanceInKm", "distanceKm")
                if (restaurant.isEmpty()) restaurant = extractFirstString(dataObj, "subscriberName", "store_name", "restaurant_name", "storeName", "restaurantName", "vendor_name", "vendorName", "shop_name", "shopName", "name", "restaurant")
                if (customerName.isEmpty()) customerName = extractFirstString(dataObj, "customer_name", "client_name", "customerName", "clientName", "recipient_name", "user_name", "customer")
                if (customerPhone.isEmpty()) customerPhone = extractFirstString(dataObj, "clientPhone", "contactNumber", "customer_phone", "client_phone", "customerPhone", "phone", "mobile", "contact_number")
                if (address.isEmpty()) address = extractFirstString(dataObj, "customer_address", "delivery_address", "dropoff_address", "address", "dropoffLocation", "destination_address", "location_address")
                if (cashAmount == 0.0) cashAmount = extractFirstDouble(dataObj, "collectionAmount", "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
                if (foodPrice == 0.0) foodPrice = extractFirstDouble(dataObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "invoice_amount", "subTotal", "sub_total", "itemsAmount", "items_amount", "collectionAmount")
                if (paymentMethod.isEmpty()) paymentMethod = extractFirstString(dataObj, "paymentMethod", "payment_method", "paymentType", "payment_type")
                val s = extractFirstString(dataObj, "status", "order_status", "state")
                if (s.isNotEmpty()) status = s
            }

            val custObj = json.optJSONObject("customer") ?: dataObj?.optJSONObject("customer") ?: json.optJSONObject("client") ?: dataObj?.optJSONObject("client")
            if (custObj != null) {
                if (customerName.isEmpty()) customerName = extractFirstString(custObj, "subscriberName", "name", "full_name", "customer_name")
                if (customerPhone.isEmpty()) customerPhone = extractFirstString(custObj, "clientPhone", "contactNumber", "phone", "mobile", "phone_number")
            }

            val dropoffObj = json.optJSONObject("dropoff") ?: dataObj?.optJSONObject("dropoff") ?: json.optJSONObject("delivery") ?: dataObj?.optJSONObject("delivery")
            if (dropoffObj != null) {
                if (address.isEmpty()) address = extractFirstString(dropoffObj, "address", "location_name", "formatted_address", "name")
            }

            val pickupObj = json.optJSONObject("pickup") ?: dataObj?.optJSONObject("pickup") ?: json.optJSONObject("store") ?: dataObj?.optJSONObject("store") ?: json.optJSONObject("branch") ?: dataObj?.optJSONObject("branch") ?: json.optJSONObject("vendor") ?: dataObj?.optJSONObject("vendor")
            if (pickupObj != null) {
                if (restaurant.isEmpty()) restaurant = extractFirstString(pickupObj, "vendorName", "name", "store_name", "restaurant_name")
            }

            val paymentObj = json.optJSONObject("payment") ?: dataObj?.optJSONObject("payment")
            if (paymentObj != null) {
                if (price == 0.0) price = extractFirstDouble(paymentObj, "deliveryPrice", "price", "delivery_cost", "delivery_fee", "deliveryFee", "deliveryCost", "driver_fee", "driverFee")
                if (cashAmount == 0.0) cashAmount = extractFirstDouble(paymentObj, "collectionAmount", "cash_amount", "cashAmount", "collect_cash", "cod_amount", "cod", "cash", "cash_to_collect")
                if (foodPrice == 0.0) foodPrice = extractFirstDouble(paymentObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "invoice_amount", "subTotal", "sub_total", "itemsAmount", "items_amount", "collectionAmount", "cashAmount", "amount")
                if (paymentMethod.isEmpty()) paymentMethod = extractFirstString(paymentObj, "paymentMethod", "payment_method", "paymentType", "type", "mode")
                val pCurr = extractFirstString(paymentObj, "currencyCode", "currency", "curr")
                if (pCurr.isNotEmpty()) currency = pCurr
            }
            if (foodPrice == 0.0 && cashAmount > 0.0) {
                foodPrice = cashAmount
            }

            // Coordinate-based Haversine distance calculation matching Lb11.e, Ls90.g, and Lqz.a
            var custDist = 0.0
            var pLat = extractFirstDouble(json, "dispatchPickupLat", "pickupLatitude", "pickup_lat", "pickupLat", "store_lat", "branch_lat", "restaurant_lat")
            var pLng = extractFirstDouble(json, "dispatchPickupLng", "pickupLongitude", "pickup_lng", "pickupLng", "pickup_lon", "store_lng", "branch_lng", "restaurant_lng")
            var dLat = extractFirstDouble(json, "dispatchDestLat", "destinationLatitude", "dest_lat", "destLat", "dropoff_lat", "customer_lat")
            var dLng = extractFirstDouble(json, "dispatchDestLng", "destinationLongitude", "dest_lng", "destLng", "dropoff_lng", "customer_lng")
            run {
                if (pLat == 0.0 && pickupObj != null) {
                    pLat = extractFirstDouble(pickupObj, "lat", "latitude")
                    pLng = extractFirstDouble(pickupObj, "lng", "lon", "longitude")
                    val coords = pickupObj.optJSONArray("coordinates") ?: pickupObj.optJSONArray("location")
                    if (coords != null && coords.length() >= 2) {
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

            val hasExplicitOrderIdKey = extractFirstString(json, "requestId", "dispatchId", "order_number", "order_id", "orderNumber", "orderId", "trip_id", "tripId", "reference", "referenceId").isNotEmpty() ||
                (dataObj != null && extractFirstString(dataObj, "requestId", "dispatchId", "order_number", "order_id", "orderNumber", "orderId", "trip_id", "tripId", "reference", "referenceId").isNotEmpty())
            val hasOrderAttributes = restaurant.isNotEmpty() || price > 0.0 || dist > 0.0 || isHistoricalOrActiveStatus(rawStatusFromJson)

            if ((hasExplicitOrderIdKey && (hasOrderAttributes || !isFromLocalDb)) || (orderId.isNotEmpty() && hasOrderAttributes) || (price > 0.0 && restaurant.isNotEmpty())) {
                if (orderId.isEmpty()) {
                    orderId = if (reference.isNotEmpty()) "#$reference" else if (restaurant.isNotEmpty()) "#$restaurant" else return
                }
                if (reference.isEmpty()) reference = orderId.replace("#", "")
                val cleanPrice = String.format(Locale.US, "%.2f", price).toDoubleOrNull() ?: price

                val rawOrderIdClean = orderId.replace("#", "").trim()
                cacheOrderCoords(rawOrderIdClean, pLat, pLng, dLat, dLng)
                if (reference.isNotEmpty()) cacheOrderCoords(reference, pLat, pLng, dLat, dLng)
                cacheOrderPayment(rawOrderIdClean, foodPrice, cashAmount, paymentMethod)
                if (reference.isNotEmpty()) cacheOrderPayment(reference, foodPrice, cashAmount, paymentMethod)

                val cachedCoords = getCachedCoords(rawOrderIdClean) ?: getCachedCoords(reference)
                if (pLat == 0.0) pLat = cachedCoords?.getOrNull(0) ?: 0.0
                if (pLng == 0.0) pLng = cachedCoords?.getOrNull(1) ?: 0.0
                if (dLat == 0.0) dLat = cachedCoords?.getOrNull(2) ?: 0.0
                if (dLng == 0.0) dLng = cachedCoords?.getOrNull(3) ?: 0.0
                if ((pLat != 0.0 || dLat != 0.0) && loggedNavOrderKeys.add("json:$rawOrderIdClean:$pLat:$dLat")) {
                    DiagnosticLogger.logStep(
                        "NavLog",
                        "JSON [$channelSource] #$rawOrderIdClean (ref=$reference) foodPrice=$foodPrice: pickup=($pLat,$pLng) -> customer=($dLat,$dLng) custDist=${String.format(Locale.US, "%.2f", custDist)}km"
                    )
                }
                if (cashAmount <= 0.0) {
                    val cachedC = customerDetailsCache[rawOrderIdClean]?.third ?: customerDetailsCache[reference]?.third ?: 0.0
                    val syncedC = synchronized(syncedOrdersCache) {
                        (syncedOrdersCache[reference] ?: syncedOrdersCache[rawOrderIdClean])?.optDouble("cashAmount", 0.0) ?: 0.0
                    }
                    cashAmount = if (cachedC > 0.0) cachedC else if (!syncedC.isNaN() && syncedC > 0.0) syncedC else 0.0
                }
                if (foodPrice <= 0.0) {
                    val cachedF = foodPriceCache[rawOrderIdClean] ?: foodPriceCache[reference] ?: 0.0
                    val syncedF = synchronized(syncedOrdersCache) {
                        (syncedOrdersCache[reference] ?: syncedOrdersCache[rawOrderIdClean])?.optDouble("foodPrice", 0.0) ?: 0.0
                    }
                    foodPrice = when {
                        cachedF > 0.0 -> cachedF
                        !syncedF.isNaN() && syncedF > 0.0 -> syncedF
                        cashAmount > 0.0 -> cashAmount
                        else -> 0.0
                    }
                }
                if (isFromLocalDb || isHistoricalOrActiveStatus(rawStatusFromJson) || knownDbOrderIds.contains(rawOrderIdClean) || channelSource.contains("Active Dispatches", ignoreCase = true)) {
                    val normStatus = normalizeOrderStatusDisplay(rawStatusFromJson.ifEmpty { if (isFromLocalDb) "Delivered" else status })
                    val hasCoords = (dLat != 0.0 && dLng != 0.0)
                    val dedupKey = "${orderId.replace("#", "")}:$reference:$normStatus:${customerPhone.isNotEmpty()}:${cashAmount.toInt()}:${foodPrice.toInt()}:$hasCoords"
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
                            foodPrice = foodPrice,
                            paymentMethod = paymentMethod,
                            currency = currency,
                            reference = reference,
                            customerDistanceKm = custDist,
                            pickupLat = pLat,
                            pickupLng = pLng,
                            destLat = dLat,
                            destLng = dLng,
                            notifyUser = false
                        )
                    }
                    return
                }

                val normOrderId = if (orderId.startsWith("#")) orderId else "#$orderId"
                orderId = normOrderId
                if (loggedOrderIds.add(normOrderId)) {
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
                        foodPrice = foodPrice,
                        paymentMethod = paymentMethod,
                        reference = reference,
                        pickupLat = pLat,
                        pickupLng = pLng,
                        destLat = dLat,
                        destLng = dLng,
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
    // Tracks only genuinely active live orders (orderId -> elapsedRealtimeMs when confirmed active),
    // automatically pruning completed/delivered orders and entries older than 12 minutes so stale cache entries NEVER block new orders!
    private val liveActiveOrderMap = ConcurrentHashMap<String, Long>()

    fun markOrderLiveActive(orderIdOrRef: String) {
        val clean = orderIdOrRef.replace("#", "").trim()
        if (clean.isEmpty() || clean == "0") return
        liveActiveOrderMap[clean] = SystemClock.elapsedRealtime()
    }

    fun removeOrderLiveActive(orderIdOrRef: String) {
        val clean = orderIdOrRef.replace("#", "").trim()
        if (clean.isEmpty()) return
        liveActiveOrderMap.remove(clean)
    }

    fun reconcileLiveActiveOrdersFromServer(rawJson: String) {
        try {
            val trimmed = rawJson.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data") ?: obj.optJSONArray("dispatches") ?: obj.optJSONArray("orders") ?: return
                }
                else -> return
            }
            val now = SystemClock.elapsedRealtime()
            val serverActiveIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val st = (item.optString("status", "") + " " + item.optString("dispatchStatus", "")).uppercase(Locale.US)
                val isAssignedActive = st.contains("ACCEPT") || st.contains("PICKED") || st.contains("ARRIV") || st.contains("PROGRESS") || st.contains("COLLECT")
                if (isAssignedActive) {
                    val idVal = item.optInt("requestId", item.optInt("dispatchId", item.optInt("id", 0)))
                    if (idVal > 0) {
                        val key = idVal.toString()
                        serverActiveIds.add(key)
                        liveActiveOrderMap[key] = now
                    }
                }
            }
            // Remove any entry in liveActiveOrderMap that was accepted > 20s ago if the server says it is no longer active
            val iter = liveActiveOrderMap.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (!serverActiveIds.contains(e.key) && (now - e.value) > 20_000L) {
                    iter.remove()
                }
            }
        } catch (_: Throwable) {}
    }

    fun countCurrentActiveOrders(): Int {
        val now = SystemClock.elapsedRealtime()
        val iter = liveActiveOrderMap.entries.iterator()
        var activeCount = 0
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > 12 * 60_000L) {
                iter.remove()
            } else {
                activeCount++
            }
        }
        return activeCount
    }

    fun evaluateOrderDecision(price: Double, distRest: Double, distCust: Double): Pair<String, String> {
        if (!isLicenseActiveInsideSaned() || !jdrvRemoteAutoAccept || (jdrvLicFeatureMask and 1) == 0) {
            return "MANUAL" to "License inactive or remote stop"
        }
        if (!isMasterRunning) {
            return "MANUAL" to "Master switch off"
        }

        val effectiveMaxOrders = if (isExtraOrderEnabled || maxOrdersLimit >= 3) 3 else 2
        val currentActive = countCurrentActiveOrders()
        if (currentActive >= effectiveMaxOrders) {
            return "MANUAL" to "Reached max orders limit ($currentActive / $effectiveMaxOrders)"
        }

        val failedReasons = mutableListOf<String>()
        if (minOrderPrice > 0.0) {
            if (price <= 0.0) {
                failedReasons.add("price unknown")
            } else if (price < minOrderPrice) {
                val fmtP = String.format(Locale.US, "%.2f", price)
                failedReasons.add("Price ($fmtP SAR) < min ($minOrderPrice SAR)")
            }
        }
        if (maxDistToRestaurant > 0.0) {
            if (distRest <= 0.0) {
                if (!LocationEngine.isFixLocationUnknownEnabled) {
                    failedReasons.add("location unknown")
                }
            } else if (distRest > maxDistToRestaurant) {
                val fmtD = String.format(Locale.US, "%.2f", distRest)
                failedReasons.add("Pickup distance ($fmtD km) > max ($maxDistToRestaurant km)")
            }
        }
        if (maxDistCustomer > 0.0) {
            if (distCust <= 0.0) {
                if (!LocationEngine.isFixLocationUnknownEnabled) {
                    if (!failedReasons.contains("location unknown")) failedReasons.add("location unknown")
                }
            } else if (distCust > maxDistCustomer) {
                val fmtC = String.format(Locale.US, "%.2f", distCust)
                failedReasons.add("Drop-off distance ($fmtC km) > max ($maxDistCustomer km)")
            }
        }

        if (failedReasons.isNotEmpty()) {
            val reasonStr = failedReasons.joinToString(", ")
            return if (isAutoReject) {
                if (isDryRun) "DRY_RUN" to "Dry-Run (Would Reject: $reasonStr)"
                else "REJECT" to reasonStr
            } else {
                "MANUAL" to reasonStr
            }
        }

        if (isDryRun) {
            return "DRY_RUN" to "All gates passed (Dry-Run)"
        }

        return if (isAutoAccept) {
            "ACCEPT" to "All gates passed"
        } else {
            "MANUAL" to "All gates passed; auto-accept off"
        }
    }

    private fun isAppInBackground(): Boolean {
        return startedActivityCount.get() <= 0
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
        foodPrice: Double = 0.0,
        paymentMethod: String = "",
        reference: String = "",
        pickupLat: Double = 0.0,
        pickupLng: Double = 0.0,
        destLat: Double = 0.0,
        destLng: Double = 0.0,
        sourceChannel: String = "PUSH"
    ): String {
        val startNs = System.nanoTime()
        val rawId = orderId.replace("#", "").trim()
        if (rawId.isEmpty()) return ""
        val normOrderId = "#$rawId"
        val orderIdInt = rawId.toIntOrNull() ?: rawId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
        val nowRealtime = SystemClock.elapsedRealtime()

        if (orderIdInt != 0) {
            val existing = handledOrdersMap[orderIdInt]
            if (existing != null && (nowRealtime - existing.timestampRealtime) < 60_000L) {
                return existing.decision
            }
        }

        val (action, reason) = evaluateOrderDecision(price, dist, custDist)

        if (orderIdInt != 0) {
            val wasInBg = isStayInBackground && !isDryRun && isAppInBackground()
            synchronized(handledOrdersMap) {
                val prev = handledOrdersMap[orderIdInt]
                if (prev != null && (nowRealtime - prev.timestampRealtime) < 60_000L) {
                    return prev.decision
                }
                handledOrdersMap[orderIdInt] = HandledOrderEntry(action, nowRealtime, wasInBg)
            }
        }

        val effFoodPrice = if (foodPrice > 0.0) foodPrice else cashAmt

        // 1. FIRE DIRECT API CALL AT 0.0ms IMMEDIATELY BEFORE ANY LOGGING OR IPC!
        if (action == "ACCEPT") {
            lastOrderEvent = "⚡ جاري قبول الطلب $normOrderId..."
            executeDirectApiCall(rawId, "ACCEPT", price, dist, restaurant, custDist, pickupAddr, custAddr, custName, custPhone, cashAmt, reason, pickupLat, pickupLng, destLat, destLng, effFoodPrice, paymentMethod, reference, startNs)
        } else if (action == "REJECT") {
            lastOrderEvent = "Order $normOrderId auto-rejected ($reason)"
            executeDirectApiCall(rawId, "REJECT", price, dist, restaurant, custDist, pickupAddr, custAddr, custName, custPhone, cashAmt, reason, pickupLat, pickupLng, destLat, destLng, effFoodPrice, paymentMethod, reference, startNs)
        }

        // 2. Cache coordinates & payment and log AFTER the API call is already in flight on the wire!
        cacheOrderCoords(rawId, pickupLat, pickupLng, destLat, destLng)
        if (reference.isNotEmpty()) cacheOrderCoords(reference, pickupLat, pickupLng, destLat, destLng)
        cacheOrderPayment(rawId, effFoodPrice, cashAmt, paymentMethod)
        if (reference.isNotEmpty()) cacheOrderPayment(reference, effFoodPrice, cashAmt, paymentMethod)
        DiagnosticLogger.logStep("OrderDecision", "Order $normOrderId [$sourceChannel] -> $action ($reason) [0.0ms Instant Fire]")

        when (action) {
            "REJECT" -> {
                currentActivity?.let { act -> showToast(act, "❌ Auto-Rejected $normOrderId: $reason") }
                sendOrderToLog(orderId = normOrderId, price = price, dist = dist, restaurant = restaurant, status = "Rejected ❌ · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, foodPrice = effFoodPrice, paymentMethod = paymentMethod, reference = reference, pickupAddress = pickupAddr, customerDistanceKm = custDist, pickupLat = pickupLat, pickupLng = pickupLng, destLat = destLat, destLng = destLng, decisionReason = reason, notifyUser = false)
            }
            "DRY_RUN" -> {
                lastOrderEvent = "Order $normOrderId dry-run ($reason)"
                currentActivity?.let { act -> showToast(act, "🔍 [Dry-Run]: $normOrderId ($reason)") }
                sendOrderToLog(orderId = normOrderId, price = price, dist = dist, restaurant = restaurant, status = "Dry-Run 🔍 · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, foodPrice = effFoodPrice, paymentMethod = paymentMethod, reference = reference, pickupAddress = pickupAddr, customerDistanceKm = custDist, pickupLat = pickupLat, pickupLng = pickupLng, destLat = destLat, destLng = destLng, decisionReason = reason, notifyUser = false)
            }
            "ACCEPT" -> {
                // Already fired at 0.0ms above
            }
            else -> {
                lastOrderEvent = "Order $normOrderId left for manual review ($reason)"
                currentActivity?.let { act -> showToast(act, "↩️ تُرك $normOrderId لك: $reason") }
                sendOrderToLog(orderId = normOrderId, price = price, dist = dist, restaurant = restaurant, status = "Missed / Manual · $reason", customerName = custName, customerPhone = custPhone, address = custAddr, cashAmount = cashAmt, foodPrice = effFoodPrice, paymentMethod = paymentMethod, reference = reference, pickupAddress = pickupAddr, customerDistanceKm = custDist, pickupLat = pickupLat, pickupLng = pickupLng, destLat = destLat, destLng = destLng, decisionReason = reason, notifyUser = false)
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
     * - Immediately parses accept response & triggers fetchOrderCustomerDetailsAsync so Accepted orders always display Price Food
     */
    @Volatile var verifiedAcceptMethod: String = ""
    @Volatile var verifiedAcceptUrlTemplate: String = ""

    private fun executeDirectApiCall(
        orderIdNum: String,
        action: String,
        price: Double = 0.0,
        dist: Double = 0.0,
        restaurant: String = "",
        custDist: Double = 0.0,
        pickupAddr: String = "",
        custAddr: String = "",
        custName: String = "",
        custPhone: String = "",
        cashAmt: Double = 0.0,
        decisionReason: String = "",
        pickupLat: Double = 0.0,
        pickupLng: Double = 0.0,
        destLat: Double = 0.0,
        destLng: Double = 0.0,
        foodPrice: Double = 0.0,
        paymentMethod: String = "",
        reference: String = "",
        orderStartNs: Long = System.nanoTime()
    ) {
        val ctx = appContext ?: currentActivity ?: resolveApplicationContext()
        // Use pre-cached RAM token immediately (0.0ms); only read EncryptedPrefs synchronously if RAM cache is empty!
        var token = cachedAuthToken
        if (token.isEmpty()) {
            if (ctx != null) readExactJahezEncryptedPrefs(ctx)
            token = if (cachedAuthToken.isNotEmpty()) cachedAuthToken else readPersistedToken()
        }
        val isAccept = (action == "ACCEPT")
        val orderIdInt = orderIdNum.toIntOrNull() ?: orderIdNum.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0

        if (token.isEmpty()) {
            DiagnosticLogger.logWarn("DirectApi", "Cannot $action #$orderIdNum: not logged in (token empty)")
            if (ctx != null) showToast(ctx, "⚠️ لم يتم تسجيل الدخول — لا يمكن قبول الطلب #$orderIdNum")
            return
        }

        val clientToUse = httpClient
        fastApiExecutor.execute {
            try { processThreadPriorityBoost() } catch (_: Throwable) {}
            try {
                fun buildFastRequest(urlStr: String, tok: String): Request {
                    val rawTok = if (tok.startsWith("Bearer ", ignoreCase = true)) tok.substring(7).trim() else tok.trim()
                    val rb = Request.Builder().url(urlStr).put(EMPTY_REQUEST_BODY)
                        .header("Authorization", "Bearer $rawTok")
                        .header("Accept-Language", cachedLanguage.ifEmpty { "en" })
                        .header("User-Agent", "okhttp/5.1.0")
                    val cipher = capturedDriverCipherB64
                    if (cipher.isNotEmpty()) {
                        rb.header("x-unique-Identifier", cipher)
                    }
                    return rb.build()
                }

                val targetUrl = if (isAccept) {
                    "https://gateway.saned.io/api/v1/dispatch/accept-dispatch/$orderIdInt"
                } else {
                    "https://gateway.saned.io/api/v1/dispatch/reject/$orderIdInt"
                }

                var winCode: Int? = null
                var winBodyStr = ""
                var lastErrCode = 0
                var lastErrBody = ""
                var retried401 = false
                var attempt = 0

                while (attempt < 2) {
                    attempt++
                    try {
                        httpRequestsCount.incrementAndGet()
                        lastInterceptedUrl = targetUrl
                        lastSocketTouchRealtime = SystemClock.elapsedRealtime()
                        val req = buildFastRequest(targetUrl, token)
                        val callStartNs = System.nanoTime()
                        val resp = clientToUse.newCall(req).execute()
                        val rttMs = (System.nanoTime() - callStartNs) / 1_000_000L
                        val code = resp.code
                        val allowHdr = resp.header("Allow") ?: resp.header("allow") ?: ""
                        val bodyStr = try { resp.body?.string() ?: "" } catch (_: Throwable) { "" }
                        resp.close()
                        lastHttpResponseCode = code

                        if (code in 200..299) {
                            cachedApiHost = "https://gateway.saned.io"
                            winCode = code
                            winBodyStr = bodyStr
                            if (isAccept && orderIdInt != 0) {
                                apiConfirmedAcceptIds.add(orderIdInt)
                    if (orderIdInt > 0) markOrderLiveActive(orderIdInt.toString())
                                verifiedAcceptMethod = "PUT"
                                verifiedAcceptUrlTemplate = "https://gateway.saned.io/api/v1/dispatch/accept-dispatch/{id}"
                            }
                            DiagnosticLogger.logStep("DirectApi", "$action #$orderIdNum succeeded via PUT $targetUrl (HTTP $code, 0.0ms local dispatch, RTT=${rttMs}ms)")
                            break
                        }

                        lastErrCode = code
                        lastErrBody = bodyStr
                        val cleanBody = bodyStr.replace("\n", " ").replace("\r", " ").take(100)
                        DiagnosticLogger.logWarn("DirectApi", "Attempt PUT $targetUrl -> HTTP $code allow=[$allowHdr] body=($cleanBody)")

                        // Handle 401 TokenExpired automatically by reloading ENCRYPTED_SANED_PREFERENCES and retrying once
                        if ((code == 401 || bodyStr.contains("TokenExpired", ignoreCase = true)) && !retried401 && ctx != null) {
                            retried401 = true
                            cachedAuthToken = ""
                            cachedEncPrefs = null
                            readExactJahezEncryptedPrefs(ctx)
                            if (cachedAuthToken.isNotEmpty()) {
                                token = cachedAuthToken
                                DiagnosticLogger.logStep("DirectApi", "Refreshed expired token from EncryptedPrefs, retrying PUT $targetUrl")
                                continue
                            }
                        }
                        break
                    } catch (t: Throwable) {
                        lastErrCode = -1
                        lastErrBody = t.message ?: t.javaClass.simpleName
                    }
                }

                if (winCode != null) {
                    if (isAccept) {
                        var parsedFood = if (foodPrice > 0.0) foodPrice else cashAmt
                        var parsedCash = cashAmt
                        var parsedPayMethod = paymentMethod
                        var parsedRef = reference
                        var parsedPhone = custPhone
                        var parsedName = custName
                        var parsedAddr = custAddr
                        if (winBodyStr.trim().startsWith("{")) {
                            try {
                                val j = JSONObject(winBodyStr)
                                val dObj = j.optJSONObject("data") ?: j
                                val pObj = dObj.optJSONObject("payment") ?: j.optJSONObject("payment")
                                val r = extractFirstString(dObj, "referenceId", "reference", "order_number", "orderNumber")
                                if (r.isNotEmpty()) parsedRef = r
                                val ca = if (pObj != null) extractFirstDouble(pObj, "collectionAmount", "cash_amount", "cashAmount") else extractFirstDouble(dObj, "collectionAmount", "cash_amount", "cashAmount")
                                val fp = if (pObj != null) {
                                    extractFirstDouble(pObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "subTotal", "collectionAmount", "amount")
                                } else {
                                    extractFirstDouble(dObj, "foodPrice", "food_price", "orderAmount", "order_amount", "totalAmount", "total_amount", "invoiceAmount", "subTotal", "collectionAmount")
                                }
                                val pm = if (pObj != null) extractFirstString(pObj, "paymentMethod", "payment_method", "paymentType", "type") else extractFirstString(dObj, "paymentMethod", "payment_method", "paymentType")
                                if (ca > 0.0) parsedCash = ca
                                if (fp > 0.0) parsedFood = fp
                                if (pm.isNotEmpty()) parsedPayMethod = pm
                                val ph = extractFirstString(dObj, "clientPhone", "contactNumber", "customer_phone", "phone")
                                if (ph.isNotEmpty()) parsedPhone = ph
                            } catch (_: Throwable) {}
                        }
                        if (parsedFood <= 0.0 && parsedCash > 0.0) parsedFood = parsedCash
                        cacheOrderPayment(orderIdNum, parsedFood, parsedCash, parsedPayMethod)
                        if (parsedRef.isNotEmpty()) cacheOrderPayment(parsedRef, parsedFood, parsedCash, parsedPayMethod)

                        lastOrderEvent = "✅ تم قبول الطلب #$orderIdNum (HTTP $winCode)"
                        if (ctx != null) showToast(ctx, "✅ تم قبول الطلب #$orderIdNum")
                        sendOrderToLog(
                            orderId = "#$orderIdNum",
                            price = price,
                            dist = dist,
                            restaurant = restaurant,
                            status = "Accepted ✅",
                            customerName = parsedName,
                            customerPhone = parsedPhone,
                            address = parsedAddr,
                            cashAmount = parsedCash,
                            foodPrice = parsedFood,
                            paymentMethod = parsedPayMethod,
                            reference = parsedRef,
                            pickupAddress = pickupAddr,
                            customerDistanceKm = custDist,
                            pickupLat = pickupLat,
                            pickupLng = pickupLng,
                            destLat = destLat,
                            destLng = destLng,
                            decisionReason = decisionReason.ifEmpty { "Accepted (HTTP $winCode)" },
                            notifyUser = true
                        )
                        playAlertSound()
                        // Immediately fetch Price Food & customer details from Saned API + SQLite DB so Accepted card always displays Price Food!
                        fetchOrderCustomerDetailsAsync(orderIdNum, force = true)
                        for (delayMs in listOf(450L, 1300L, 2800L, 5500L)) {
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(delayMs)
                                if (ctx != null) {
                                    try { scanJahezDatabases(ctx) } catch (_: Throwable) {}
                                }
                                fetchOrderCustomerDetailsAsync(orderIdNum, force = (delayMs <= 1500L))
                            }
                        }
                        currentActivity?.let { act ->
                            if (act.javaClass.name == Native.safeE()) {
                                handlePostOrderActivityCreate(act, orderIdInt)
                            }
                        }
                    }
                } else if (isAccept) {
                    var errMsg = ""
                    if (lastErrBody.trim().startsWith("{")) {
                        try {
                            val j = JSONObject(lastErrBody)
                            errMsg = extractFirstString(j, "message", "error", "title").trim()
                        } catch (_: Throwable) {}
                    }
                    if (errMsg.isEmpty()) errMsg = if (lastErrCode > 0) "HTTP $lastErrCode" else "Network timeout"
                    val isTakenByOtherDriver = errMsg.contains("already accepted", ignoreCase = true) ||
                        errMsg.contains("already assigned", ignoreCase = true) ||
                        errMsg.contains("not available", ignoreCase = true)

                    if (isTakenByOtherDriver) {
                        val takenToast = "⚠️ تم قبول الطلب #$orderIdNum من قبل سائق آخر"
                        val takenReason = "تم قبول الطلب من قبل سائق آخر ($errMsg)"
                        lastOrderEvent = takenToast
                        DiagnosticLogger.logWarn("DirectApi", "Accept #$orderIdNum failed: Order already accepted by another driver (HTTP $lastErrCode)")
                        if (ctx != null) showToast(ctx, takenToast)
                        sendOrderToLog(
                            orderId = "#$orderIdNum",
                            price = price,
                            dist = dist,
                            restaurant = restaurant,
                            status = "تم قبوله من سائق آخر ⚠️",
                            customerName = custName,
                            customerPhone = custPhone,
                            address = custAddr,
                            cashAmount = cashAmt,
                            foodPrice = if (foodPrice > 0.0) foodPrice else cashAmt,
                            paymentMethod = paymentMethod,
                            reference = reference,
                            pickupAddress = pickupAddr,
                            customerDistanceKm = custDist,
                            pickupLat = pickupLat,
                            pickupLng = pickupLng,
                            destLat = destLat,
                            destLng = destLng,
                            decisionReason = takenReason,
                            notifyUser = true
                        )
                        if (orderIdInt != 0) {
                            apiConfirmedAcceptIds.add(orderIdInt)
                            currentActivity?.let { act ->
                                if (act.javaClass.name == Native.safeE()) {
                                    handlePostOrderActivityCreate(act, orderIdInt)
                                }
                            }
                        }
                    } else {
                        if (orderIdInt != 0 && (lastErrCode <= 0 || lastErrCode == 401 || lastErrCode in 500..599)) {
                            handledOrdersMap.remove(orderIdInt)
                            loggedOrderIds.remove("#$orderIdNum")
                        }
                        val failToast = "❌ تعذر قبول الطلب #$orderIdNum ($errMsg)"
                        val failReason = if (lastErrCode > 0) "HTTP $lastErrCode: $errMsg" else errMsg
                        DiagnosticLogger.logWarn("DirectApi", "Accept #$orderIdNum failed: $errMsg (HTTP $lastErrCode) -> UI backup active if screen opens")
                        lastOrderEvent = failToast
                        if (ctx != null) showToast(ctx, failToast)
                        sendOrderToLog(
                            orderId = "#$orderIdNum",
                            price = price,
                            dist = dist,
                            restaurant = restaurant,
                            status = "فشل القبول ❌",
                            customerName = custName,
                            customerPhone = custPhone,
                            address = custAddr,
                            cashAmount = cashAmt,
                            foodPrice = if (foodPrice > 0.0) foodPrice else cashAmt,
                            paymentMethod = paymentMethod,
                            reference = reference,
                            pickupAddress = pickupAddr,
                            customerDistanceKm = custDist,
                            pickupLat = pickupLat,
                            pickupLng = pickupLng,
                            destLat = destLat,
                            destLng = destLng,
                            decisionReason = failReason,
                            notifyUser = true
                        )
                        // Also trigger UI auto-accept immediately if IncomingDispatchActivity is already open!
                        currentActivity?.let { act ->
                            if (!act.isFinishing && !act.isDestroyed) {
                                act.runOnUiThread {
                                    try {
                                        val decor = act.window?.decorView
                                        if (decor != null) findAndTriggerOrder(decor, act, System.currentTimeMillis())
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun processThreadPriorityBoost() {
        try { Process.setThreadPriority(-19) } catch (_: Throwable) {
            try { Process.setThreadPriority(-8) } catch (_: Throwable) {}
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
        foodPrice: Double = 0.0,
        paymentMethod: String = "",
        currency: String = "SAR",
        reference: String = "",
        pickupAddress: String = "",
        customerDistanceKm: Double = 0.0,
        pickupLat: Double = 0.0,
        pickupLng: Double = 0.0,
        destLat: Double = 0.0,
        destLng: Double = 0.0,
        decisionReason: String = "",
        notifyUser: Boolean = true
    ) {
        val ctx = resolveApplicationContext() ?: currentActivity
        val cleanOrderId = orderId.replace("#", "").trim()
        val rawRef = if (reference.isNotEmpty()) reference.replace("#", "").trim() else cleanOrderId
        val intId = cleanOrderId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
        val idKey = if (intId != 0) intId.toString() else cleanOrderId
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val prevJson = synchronized(syncedOrdersCache) {
            var found = syncedOrdersCache[rawRef] ?: syncedOrdersCache[idKey] ?: syncedOrdersCache[cleanOrderId]
            if (found == null) {
                for (entry in syncedOrdersCache.values) {
                    val eId = entry.optString("id", "").replace("#", "").trim()
                    val eRef = entry.optString("reference", "").replace("#", "").trim()
                    if ((idKey.isNotEmpty() && (eId == idKey || eRef == idKey)) ||
                        (rawRef.isNotEmpty() && (eRef == rawRef || eId == rawRef))) {
                        found = entry
                        break
                    }
                }
            }
            found
        }

        val prevRef = prevJson?.optString("reference", "")?.replace("#", "")?.trim() ?: ""
        // Prefer 10-digit referenceId (e.g. 6088542571) over 9-digit dispatchId (e.g. 518628148) when merging
        val ref = when {
            rawRef.length >= 10 && rawRef != idKey -> rawRef
            prevRef.length >= 10 && prevRef != idKey -> prevRef
            rawRef.isNotEmpty() && rawRef != idKey -> rawRef
            prevRef.isNotEmpty() && prevRef != idKey -> prevRef
            rawRef.isNotEmpty() -> rawRef
            prevRef.isNotEmpty() -> prevRef
            else -> cleanOrderId
        }
        val cacheKey = ref.ifEmpty { cleanOrderId }

        cacheOrderCoords(idKey, pickupLat, pickupLng, destLat, destLng)
        if (cacheKey != idKey) cacheOrderCoords(cacheKey, pickupLat, pickupLng, destLat, destLng)

        val cachedTriple = customerDetailsCache[idKey] ?: customerDetailsCache[cacheKey] ?: customerDetailsCache[cleanOrderId]
        val cachedCoords = getCachedCoords(idKey) ?: getCachedCoords(cacheKey) ?: getCachedCoords(cleanOrderId)

        val mergedRestaurant = restaurant.ifEmpty { prevJson?.optString("restaurant", "") ?: "" }
        val mergedPickupAddr = pickupAddress.ifEmpty { prevJson?.optString("pickupAddress", "") ?: "" }
        val mergedCustName = customerName.ifEmpty { cachedTriple?.first?.takeIf { it.isNotEmpty() } ?: prevJson?.optString("customerName", "") ?: "" }
        val mergedCustPhone = customerPhone.ifEmpty { cachedTriple?.second?.takeIf { it.isNotEmpty() } ?: prevJson?.optString("customerPhone", "") ?: "" }
        val prevAddr = prevJson?.optString("address", "") ?: ""
        val mergedAddress = when {
            address.isNotEmpty() && address != "Location on map" -> address
            prevAddr.isNotEmpty() && prevAddr != "Location on map" -> prevAddr
            address.isNotEmpty() -> address
            else -> prevAddr
        }
        val prevPrice = prevJson?.optDouble("price", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val mergedPrice = if (price > 0.0) price else prevPrice
        val prevCash = prevJson?.optDouble("cashAmount", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val cachedCash = cachedTriple?.third ?: 0.0
        val mergedCash = when {
            cashAmount > 0.0 -> cashAmount
            cachedCash > 0.0 -> cachedCash
            prevCash > 0.0 -> prevCash
            else -> 0.0
        }
        val prevFoodPrice = prevJson?.optDouble("foodPrice", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val cachedFoodPrice = foodPriceCache[idKey] ?: foodPriceCache[cacheKey] ?: foodPriceCache[cleanOrderId] ?: 0.0
        val mergedFoodPrice = when {
            foodPrice > 0.0 -> foodPrice
            cachedFoodPrice > 0.0 -> cachedFoodPrice
            prevFoodPrice > 0.0 -> prevFoodPrice
            mergedCash > 0.0 -> mergedCash
            else -> 0.0
        }
        val mergedPaymentMethod = paymentMethod.ifEmpty {
            paymentMethodCache[idKey] ?: paymentMethodCache[cacheKey] ?: prevJson?.optString("paymentMethod", "") ?: ""
        }
        cacheOrderPayment(idKey, mergedFoodPrice, mergedCash, mergedPaymentMethod)
        if (cacheKey != idKey) cacheOrderPayment(cacheKey, mergedFoodPrice, mergedCash, mergedPaymentMethod)

        val prevDist = prevJson?.optDouble("distanceKm", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val mergedDist = if (dist > 0.0) dist else prevDist
        val prevCustDist = prevJson?.optDouble("customerDistanceKm", 0.0)?.takeIf { !it.isNaN() } ?: 0.0

        val prevPLat = prevJson?.optDouble("pickupLat", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val prevPLng = prevJson?.optDouble("pickupLng", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val prevDLat = prevJson?.optDouble("destLat", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val prevDLng = prevJson?.optDouble("destLng", 0.0)?.takeIf { !it.isNaN() } ?: 0.0

        val mergedPLat = when {
            pickupLat != 0.0 -> pickupLat
            (cachedCoords?.getOrNull(0) ?: 0.0) != 0.0 -> cachedCoords!![0]
            else -> prevPLat
        }
        val mergedPLng = when {
            pickupLng != 0.0 -> pickupLng
            (cachedCoords?.getOrNull(1) ?: 0.0) != 0.0 -> cachedCoords!![1]
            else -> prevPLng
        }
        val mergedDLat = when {
            destLat != 0.0 -> destLat
            (cachedCoords?.getOrNull(2) ?: 0.0) != 0.0 -> cachedCoords!![2]
            else -> prevDLat
        }
        val mergedDLng = when {
            destLng != 0.0 -> destLng
            (cachedCoords?.getOrNull(3) ?: 0.0) != 0.0 -> cachedCoords!![3]
            else -> prevDLng
        }

        val calcFromMergedCoords = if (mergedPLat != 0.0 && mergedPLng != 0.0 && mergedDLat != 0.0 && mergedDLng != 0.0) {
            LocationEngine.calculateDistance(mergedPLat, mergedPLng, mergedDLat, mergedDLng)
        } else 0.0
        val mergedCustDist = when {
            customerDistanceKm > 0.0 -> customerDistanceKm
            prevCustDist > 0.0 -> prevCustDist
            else -> calcFromMergedCoords
        }

        val normalizedStatus = if (status.equals("Dispatched", ignoreCase = true) || status.equals("DISPATCHED", ignoreCase = true) || status.equals("Accepted", ignoreCase = true)) {
            "Accepted ✅"
        } else {
            status
        }

        val order = OrderModel(
            id = intId,
            reference = ref,
            status = normalizedStatus,
            restaurant = mergedRestaurant,
            pickupAddress = mergedPickupAddr,
            customerName = mergedCustName,
            customerPhone = mergedCustPhone,
            address = mergedAddress,
            price = mergedPrice,
            currency = currency,
            cashAmount = mergedCash,
            foodPrice = mergedFoodPrice,
            paymentMethod = mergedPaymentMethod,
            distanceKm = mergedDist,
            customerDistanceKm = mergedCustDist,
            pickupLat = mergedPLat,
            pickupLng = mergedPLng,
            destLat = mergedDLat,
            destLng = mergedDLng,
            decisionReason = decisionReason,
            time = timeStr
        )

        if ((idKey.isNotEmpty() && deletedOrderIds.contains(idKey)) ||
            (rawRef.isNotEmpty() && deletedOrderIds.contains(rawRef)) ||
            (cacheKey.isNotEmpty() && deletedOrderIds.contains(cacheKey))) {
            return
        }

        val lowNormSt = normalizedStatus.lowercase(Locale.US)
        val isDeadOrder = lowNormSt.contains("deliver") || lowNormSt.contains("توصيل") ||
            lowNormSt.contains("complet") || lowNormSt.contains("cancel") ||
            lowNormSt.contains("ملغ") || lowNormSt.contains("expired")
        if (isDeadOrder) {
            synchronized(syncedOrdersCache) {
                val keysToRem = mutableListOf<String>()
                for ((k, v) in syncedOrdersCache) {
                    val eId = v.optString("id", "").replace("#", "").trim()
                    val eRef = v.optString("reference", "").replace("#", "").trim()
                    if ((idKey.isNotEmpty() && (k == idKey || eId == idKey || eRef == idKey)) ||
                        (cacheKey.isNotEmpty() && (k == cacheKey || eRef == cacheKey || eId == cacheKey))) {
                        keysToRem.add(k)
                    }
                }
                for (k in keysToRem) syncedOrdersCache.remove(k)
            }
            return
        }

        synchronized(syncedOrdersCache) {
            val matchingKeys = mutableListOf<String>()
            for ((k, v) in syncedOrdersCache) {
                val eId = v.optString("id", "").replace("#", "").trim()
                val eRef = v.optString("reference", "").replace("#", "").trim()
                if ((idKey.isNotEmpty() && (k == idKey || eId == idKey || eRef == idKey)) ||
                    (cacheKey.isNotEmpty() && (k == cacheKey || eRef == cacheKey || eId == cacheKey))) {
                    matchingKeys.add(k)
                }
            }
            if (matchingKeys.isNotEmpty()) {
                // Update IN-PLACE at existing key so LinkedHashMap order never changes!
                val primaryExistingKey = matchingKeys[0]
                syncedOrdersCache[primaryExistingKey] = order.toJson()
                for (idx in 1 until matchingKeys.size) {
                    syncedOrdersCache.remove(matchingKeys[idx])
                }
            } else {
                val primaryKey = cacheKey.ifEmpty { idKey }
                if (primaryKey.isNotEmpty()) {
                    syncedOrdersCache[primaryKey] = order.toJson()
                }
            }
        }
        refreshFloatingAppOrdersIfVisible()

        val isUnchangedExisting = prevJson != null &&
            prevJson.optString("status", "") == order.status &&
            prevJson.optString("customerPhone", "") == order.customerPhone &&
            prevJson.optString("address", "") == order.address &&
            Math.abs(prevJson.optDouble("price", 0.0) - order.price) < 0.01 &&
            Math.abs(prevJson.optDouble("foodPrice", 0.0) - order.foodPrice) < 0.01 &&
            Math.abs(prevJson.optDouble("cashAmount", 0.0) - order.cashAmount) < 0.01 &&
            Math.abs(prevJson.optDouble("pickupLat", 0.0) - order.pickupLat) < 0.00001 &&
            Math.abs(prevJson.optDouble("destLat", 0.0) - order.destLat) < 0.00001

        if (ctx != null && (!isUnchangedExisting || notifyUser)) {
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
                    putExtra("price", order.price)
                    putExtra("distance", order.distanceKm)
                    putExtra("customer_distance", order.customerDistanceKm)
                    putExtra("pickup_lat", order.pickupLat)
                    putExtra("pickup_lng", order.pickupLng)
                    putExtra("dest_lat", order.destLat)
                    putExtra("dest_lng", order.destLng)
                    putExtra("restaurant", order.restaurant)
                    putExtra("pickup_address", order.pickupAddress)
                    putExtra("status", order.status)
                    putExtra("decision_reason", order.decisionReason)
                    putExtra("customer_name", order.customerName)
                    putExtra("customer_phone", order.customerPhone)
                    putExtra("address", order.address)
                    putExtra("cash_amount", order.cashAmount)
                    putExtra("food_price", order.foodPrice)
                    putExtra("payment_method", order.paymentMethod)
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
                        (order.status.contains("Accept", ignoreCase = true) || order.status.contains("قبول"))
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
            val normId = idKey.replace("#", "").trim()
            for (i in 0 until array.length()) {
                if (newArray.length() >= 50) break
                val obj = array.optJSONObject(i) ?: continue
                val oldRef = obj.optString("reference", "").replace("#", "").trim()
                val oldId = obj.optString("id", "").replace("#", "").trim()
                if ((normRef.isNotEmpty() && (oldRef == normRef || oldId == normRef)) ||
                    (normId.isNotEmpty() && normId != "0" && (oldId == normId || oldRef == normId))) {
                    continue
                }
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
                executeDirectApiCall(orderId.replace("#", ""), "REJECT")
                if (btnReject != null) {
                    triggerClick(btnReject)
                }
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
                executeDirectApiCall(orderId.replace("#", ""), "ACCEPT")
                if (isAccepting.compareAndSet(false, true)) {
                    val latency = 0L
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            simulateSwipe(btnAccept, dialogRoot, act)

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
                delay(2)

                // 2. Ultra-fast ACTION_MOVE (6 steps x 2ms = 12ms total swipe time)
                val steps = 6
                for (step in 1..steps) {
                    val progress = step.toFloat() / steps.toFloat()
                    val eventTime = downTime + (step * 4L)
                    dispatchEvent(MotionEvent.ACTION_MOVE, eventTime, progress)
                    delay(2)
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
        // In-app Toasts inside net.jahez.fleets are hidden as requested; top bar notifications handle alerts
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
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .writeTimeout(2500, TimeUnit.MILLISECONDS)
                .build()
        } catch (_: Throwable) {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .writeTimeout(2500, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    // =========================================================================
    // FLOATING OVERLAY ICON (EXACT 10.JPG) & COMPACT J-DRIVER QUICK MENU (12.JPG)
    // =========================================================================
    private const val FLOATING_BUBBLE_TAG = "JDRIVER_FLOATING_BUBBLE_V3"
    private const val LEGACY_BUBBLE_TAG_1 = "SANED_FLOATING_OVERLAY_BUBBLE"
    private const val LEGACY_BUBBLE_TAG_2 = "SANED_FLOATING_FULL_APP_WINDOW"
    private const val FLOATING_DOT_TAG = "JDRIVER_FLOATING_STATUS_DOT_V3"
    private const val FLOATING_ICON_B64 = "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAMAAAD04JH5AAABCGlDQ1BJQ0MgUHJvZmlsZQAAeJxjYGA8wQAELAYMDLl5JUVB7k4KEZFRCuwPGBiBEAwSk4sLGHADoKpv1yBqL+viUYcLcKakFicD6Q9ArFIEtBxopAiQLZIOYWuA2EkQtg2IXV5SUAJkB4DYRSFBzkB2CpCtkY7ETkJiJxcUgdT3ANk2uTmlyQh3M/Ck5oUGA2kOIJZhKGYIYnBncAL5H6IkfxEDg8VXBgbmCQixpJkMDNtbGRgkbiHEVBYwMPC3MDBsO48QQ4RJQWJRIliIBYiZ0tIYGD4tZ2DgjWRgEL7AwMAVDQsIHG5TALvNnSEfCNMZchhSgSKeDHkMyQx6QJYRgwGDIYMZAKbWPz9HbOBQAAAA/1BMVEURFh5bX2ggim7b3eEYGCUSFR0AAFUSFSAkfGZ0doEQGCcQuYIAAAD29/gRFyUEChgNuH4TwYkMJSkKEh0ONzUTV0kWZlMbpXsah2gODiEbl3Lo6u0htogZe2IRSkLGys4jrIKjp60PExsRFyIQFiIPRDwYc1plaXKRlZwPFBckl3ckKTU2O0dFSlTR1dk9Qk2ZnaS9wccio3yFiI8SFRxcYmkAADwQFB0brYC6vcLe4eUrMDtVWmR8gIaztrsQDxgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC4+W26AAAAQHRSTlOa////HcsDWP///v8A/8P//////v//////////////////MnWw/////xP/////////////Tf8Eaf////////8nAnqwWwAAB9lJREFUeNrNmwdz4joQgJ1cckUOCEvu2KYTWoC0S73k//+rp2bjjsECv71Jhst42E+r1e5aKyk3OTJ/UtpArrTHoyf61T/TupSM9uFoDE4jBOL7Zh/A06m0CxkPb27+FgMM2+DkMv4utMBcAecQ/FIAcI7hCyPkAvwCZ5TvNMDPmydwVhmmLXBm/TsCAXAFzi5XcYDv8+sH7XkMYNwAAFBYXGYACmhEnkILDJvRD/BcALQbAgAjkhaU5gxAZM4scN0cADGB0qQBQJta4KVBABIPlabWYDgHyrxJ/WQOlKdGAcBQUZoFeFHazQKMagA4mPxCiIRUXKM6O24GMEATw7Rd17Us17V909BRHoaB9nrhUQC6aVuqqmmqEPLJs2xDTz+H+r50AAfoU5fp7nTUhJC/uSaKm8EJVNWQDTCxVU0tFO3Vj5vBV9W+LhUgIIPvFOsnVtE8O1KJLLWjLpA0AKyT0Xc6ZQB0XjRvihyO65FnNR/LAjCTo9c0r28x8WL+SCE0i8+8z583pAA4urvTQH5e3akR6IiJHhi+mzCE5yM2A0xK3aAyQNBXO6GR1Vc/QAA7OzqIgW7YamxpLHQYeOFnVBsAG140x+rCRAUL1HwIETpqf+KH86JNcV0Aczf7CwMUfh1GBKETTpMVfuiUuEElAGx60bhM5JQ+iqaeml0nxW5QBYDoD7/I1vfmHTxxNeGoMa8tdIMqAIYmhq+aleYL2XH9NDKQVGHgYwEcFk6YHYOKaZearG8LB1TVB5smy6MtoIfr70GvnPax0Z9MNaLcWkyZbqeGE7piFg/Qz+qUhWubNFrUzIZ0AewPZ7mWQ5WA9wHooS8FNcquOgC2xtOLma8fUnFOCBAIB7BxnnLwcfd8e/t5t8TwVAC2iH9ZB4Tgbtbrtph07x9Xx9qhFMAJDZCZAAfc9loJ+bqDJ7CAreXHUbi6b2VksISyAXRhACNlXnjbypMfxxihFMAUCTg9/kehcb29vdtsLi4G78wTWq1nKBfAzfWAUP/XBV+FRFDoEYcTlAGIIPSK8uzffYYwvib+cYINlAeATV5SJd+unBVT1Et7HLxja3It0wI8CqpB8q9vTH82ycBVl/rBDEoDEFV1JzED8JnZf5kXGS+YbVaOLADhAm7yr71iX4MzuhK2UBaAIUrq5ExT/W8wlg/AzhnRO7OOJACH+6CWiEJkkEQuQpXwc9DrbaP4A9lSuIWSLCCKuklmBnaePuBr7zH0CLZCBnIBkpXQB11r4SzDQUukw38wxteT5QO8GLRQygW6oY3hZ6i/1fqAOzc8zAlKACzqA1oSgEXBda+3XvfIv10e6r317om8/WDRUBLAJXv3t9H+NJiSZDjWmaBjIiF/+Qf1AHT+WmfL2SPiAF8zJo9vsWJkO6Cy7WVioc6LWl8SgHBCLhuhvdt6DxPzlv4XZctaWQBgSf1+wJc9igoTEpqF0nVmGYp4akI5AJja+D0cIuZFwPun+HbMbJLIhzynZ6u6YwH4oKNgCz8I0ADBRKD+TAD4HCCQtVO6SlYd8I0GRhSPkz/ywpmnywKAX4nQC+5jsR+y1PCYjALWnn2yQwEclo9bUQKMAfCCJBWIxSKw5W1W82F2VzANwGJEt/Un6e58q04zJfYLlized0VNEAGIguwrt6xL5fR6ALwoInPNnJ8DYPjBS4P3ZWqrRs2m1LoAoi4l7v5vJVYB2Mx4Yo5mJgQQRU39ndIMAVfYm5HQ19uGefk9/Vqii/2tCZAKAOBmnZcFvzKvxzbdYexoLpAMQN7EZjHFwvx/Mo9NmAE6moFlA9D3oG03Pvr1Y87uwCWfgQcE5AMABy5vB3yPptubfQKY827J9WsmOAUAfytZrjabFQIwJ9k6en/vGqwJwA1RlOiRaOJoBjglQHHlIDpWxdXgQQBIDw7UP1XVSlu8e/eKMUAB7RT/RodsVocOqHomPhqAFFFoYvquxXq1xm/PgIeOv6Qa3g+gG1PXUsNGsUVdyqyoH/me6LG4Ndr3v1k3NNaoJZ9sVMEIWHcFtWbt3+MvBkCLqEu6E6vCrr0ZtliIAzo1AMKXqli7lvzy/FJfdMDkMnq2Uo+jbJvO2A3ftaKWdX+qY6doyei2FzUurUo9lrJliP1o/EYYV+kJgb6dn9+Rwdr7IXO1Hk9pHBBuQPOZo7teNBn0rMYEJYZOIpVvpdvn9bfrdT6fGm2YJHuyGlmYvhFM6Lv/xCDR4jV2soN2eKVsVouiUvSeceBq6VMrO56E2NWj5p5QzF/twooCma9aIjJ01PRSpY1ayzggaezLBcQNOtquZYWmr1rpERJNfTARkAjAokH83RqZVskpHtU1DlJfAQAbXrKkwWS1WeIkE50D8tMRHmFNJ+DQ/maFeuDyMvOlJEvaD1RnKKq3sMnKPKK7WgEA6bkxF+mGOfVt2/Z9dp4GHNc4bNc6T4jFCboazdtx8wcaGz/S2fCh1rly1egctG+UZi43RC7wfzjaPW90Bujx/gbn4Bc93t/8BYfmTPAi7pjMcTP6r6N7Ri9NLQEB8LOZSRixq5firlkD+seJy27DJkJA4rrfuQmu5+kbl+claM+zVz6/z5gWx1d5l17nyvn8v+De8dNZItL1sOTm9ej0sz8qv/o9H53UFdq/9tw9/8tuv7dPNfjhzd7L7/xm+nA0Grelynj0Mo++Pi7/Aeh661RPCdcgAAAAAElFTkSuQmCC"

    @Volatile private var cachedFloatingBitmap: android.graphics.Bitmap? = null
    @Volatile private var savedBubbleX: Int = -1
    @Volatile private var savedBubbleY: Int = -1
    @Volatile private var activeQuickDialog: Dialog? = null
    @Volatile private var lastQuickDialogOpenUptime: Long = 0L

    private fun refreshFloatingAppOrdersIfVisible() {
        // No-op: using compact single Quick Control Dialog
    }

    private fun getFloatingIconBitmap(): android.graphics.Bitmap? {
        cachedFloatingBitmap?.let { if (!it.isRecycled) return it }
        return try {
            val bytes = Base64.decode(FLOATING_ICON_B64, Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            cachedFloatingBitmap = bmp
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun purgeLegacyFloatingViews(decorView: android.widget.FrameLayout) {
        try {
            for (i in decorView.childCount - 1 downTo 0) {
                val child = decorView.getChildAt(i) ?: continue
                val t = child.tag?.toString() ?: ""
                if (t == LEGACY_BUBBLE_TAG_1 || t == LEGACY_BUBBLE_TAG_2 || t == "SANED_FLOATING_STATUS_DOT") {
                    child.setOnTouchListener(null)
                    child.setOnClickListener(null)
                    child.visibility = View.GONE
                    decorView.removeViewAt(i)
                }
            }
            // If multiple JDRIVER_FLOATING_BUBBLE_V3 somehow exist, keep only the last one
            val matching = mutableListOf<View>()
            for (i in 0 until decorView.childCount) {
                val child = decorView.getChildAt(i) ?: continue
                if (child.tag == FLOATING_BUBBLE_TAG) {
                    matching.add(child)
                }
            }
            if (matching.size > 1) {
                for (k in 0 until matching.size - 1) {
                    val dup = matching[k]
                    dup.setOnTouchListener(null)
                    dup.visibility = View.GONE
                    decorView.removeView(dup)
                }
            }
        } catch (_: Throwable) {}
    }

    fun attachFloatingOverlayToActivity(activity: Activity) {
        mainHandler.post {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val pkg = activity.packageName
                if (pkg == "dev.jing.sanedhook" || pkg == "dev.saned.assistant") return@post

                val decorView = activity.window?.decorView as? android.widget.FrameLayout ?: return@post

                // Always remove any legacy/duplicate floating bubble or window first
                purgeLegacyFloatingViews(decorView)

                val existing = decorView.findViewWithTag<View>(FLOATING_BUBBLE_TAG)

                if (!isFloatingOverlayEnabled) {
                    if (existing != null) decorView.removeView(existing)
                    return@post
                }

                if (existing != null) {
                    val dot = existing.findViewWithTag<View>(FLOATING_DOT_TAG)
                    if (dot != null) updateStatusDotBackground(dot)
                    existing.bringToFront()
                    return@post
                }

                val density = activity.resources.displayMetrics.density
                val screenW = activity.resources.displayMetrics.widthPixels
                val screenH = activity.resources.displayMetrics.heightPixels
                // Small-to-medium floating icon (50dp)
                val bubbleSizePx = (50f * density).toInt()

                if (savedBubbleX < 0 || savedBubbleY < 0) {
                    try {
                        val sp = activity.getSharedPreferences("saned_floating_overlay_pos", Context.MODE_PRIVATE)
                        savedBubbleX = sp.getInt("x", (screenW - bubbleSizePx - (12f * density).toInt()).coerceAtLeast(0))
                        savedBubbleY = sp.getInt("y", (screenH * 0.32f).toInt())
                    } catch (_: Throwable) {
                        savedBubbleX = (screenW - bubbleSizePx - (12f * density).toInt()).coerceAtLeast(0)
                        savedBubbleY = (screenH * 0.32f).toInt()
                    }
                }

                val bubbleContainer = android.widget.FrameLayout(activity).apply {
                    tag = FLOATING_BUBBLE_TAG
                    elevation = 32f * density
                    val lp = android.widget.FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    lp.leftMargin = savedBubbleX.coerceIn(0, (screenW - bubbleSizePx).coerceAtLeast(0))
                    lp.topMargin = savedBubbleY.coerceIn((36f * density).toInt(), (screenH - bubbleSizePx - (48f * density).toInt()).coerceAtLeast(0))
                    layoutParams = lp
                }

                val iconView = android.widget.ImageView(activity).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    val bmp = getFloatingIconBitmap()
                    if (bmp != null) {
                        setImageBitmap(bmp)
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                bubbleContainer.addView(iconView)

                val dotSize = (12f * density).toInt()
                val statusDot = View(activity).apply {
                    tag = FLOATING_DOT_TAG
                    val dotLp = android.widget.FrameLayout.LayoutParams(dotSize, dotSize)
                    dotLp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    dotLp.rightMargin = (2f * density).toInt()
                    dotLp.bottomMargin = (2f * density).toInt()
                    layoutParams = dotLp
                }
                updateStatusDotBackground(statusDot)
                bubbleContainer.addView(statusDot)

                var dX = 0f
                var dY = 0f
                var downRawX = 0f
                var downRawY = 0f
                var isDragging = false
                val touchSlop = 10f * density

                bubbleContainer.setOnTouchListener { v, event ->
                    val lp = v.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            dX = lp.leftMargin - event.rawX
                            dY = lp.topMargin - event.rawY
                            isDragging = false
                            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val moveX = kotlin.math.abs(event.rawX - downRawX)
                            val moveY = kotlin.math.abs(event.rawY - downRawY)
                            if (moveX > touchSlop || moveY > touchSlop) {
                                isDragging = true
                            }
                            if (isDragging) {
                                val newX = (event.rawX + dX).toInt().coerceIn(0, (screenW - bubbleSizePx).coerceAtLeast(0))
                                val newY = (event.rawY + dY).toInt().coerceIn((28f * density).toInt(), (screenH - bubbleSizePx - (28f * density).toInt()).coerceAtLeast(0))
                                lp.leftMargin = newX
                                lp.topMargin = newY
                                v.layoutParams = lp
                                savedBubbleX = newX
                                savedBubbleY = newY
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                            if (!isDragging) {
                                val nowUp = SystemClock.uptimeMillis()
                                if (nowUp - lastQuickDialogOpenUptime > 350L) {
                                    lastQuickDialogOpenUptime = nowUp
                                    showFloatingQuickControlDialog(activity, statusDot)
                                }
                            } else {
                                snapFloatingBubbleToEdge(activity, v, lp, screenW, bubbleSizePx, density)
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            // Do NOT open dialog on ACTION_CANCEL (prevents opening a second duplicate dialog!)
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                            if (isDragging) {
                                snapFloatingBubbleToEdge(activity, v, lp, screenW, bubbleSizePx, density)
                            }
                            true
                        }
                        else -> false
                    }
                }

                decorView.addView(bubbleContainer)
                bubbleContainer.bringToFront()

                // Also watch hierarchy briefly to purge any legacy bubble added after us
                decorView.postDelayed({ purgeLegacyFloatingViews(decorView) }, 250)
                decorView.postDelayed({ purgeLegacyFloatingViews(decorView) }, 900)
            } catch (_: Throwable) {}
        }
    }

    private fun snapFloatingBubbleToEdge(
        activity: Activity,
        v: View,
        lp: android.widget.FrameLayout.LayoutParams,
        screenW: Int,
        bubbleSizePx: Int,
        density: Float
    ) {
        val snapX = if (lp.leftMargin + bubbleSizePx / 2 < screenW / 2) {
            (8f * density).toInt()
        } else {
            (screenW - bubbleSizePx - (8f * density).toInt()).coerceAtLeast(0)
        }
        lp.leftMargin = snapX
        v.layoutParams = lp
        savedBubbleX = snapX
        try {
            activity.getSharedPreferences("saned_floating_overlay_pos", Context.MODE_PRIVATE)
                .edit()
                .putInt("x", savedBubbleX)
                .putInt("y", savedBubbleY)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun updateStatusDotBackground(dot: View) {
        try {
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
            gd.setColor(android.graphics.Color.parseColor(if (isMasterRunning) "#10B981" else "#EF4444"))
            gd.setStroke(3, android.graphics.Color.WHITE)
            dot.background = gd
        } catch (_: Throwable) {}
    }

    private fun showFloatingQuickControlDialog(activity: Activity, statusDot: View) {
        try {
            if (activity.isFinishing || activity.isDestroyed) return

            // Ensure only ONE Quick Control Dialog is ever open at a time
            try {
                activeQuickDialog?.let { oldDlg ->
                    if (oldDlg.isShowing) oldDlg.dismiss()
                }
            } catch (_: Throwable) {}
            activeQuickDialog = null

            syncSettings(activity.contentResolver)

            val density = activity.resources.displayMetrics.density
            val isAr = appUiLanguage != "en"
            fun dp(v: Float): Int = (v * density).toInt()

            val dialog = Dialog(activity)
            activeQuickDialog = dialog
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.setOnDismissListener {
                if (activeQuickDialog === dialog) activeQuickDialog = null
            }

            val rootScroll = android.widget.ScrollView(activity).apply {
                isFillViewport = true
            }

            // Small-to-Medium Compact Card Container
            val card = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 18f * density
                    setColor(android.graphics.Color.parseColor("#0F172A"))
                    setStroke(dp(1.2f), android.graphics.Color.parseColor("#334155"))
                }
            }

            // Header Row (Compact Icon + Title "J-Driver" + Language Toggle + Close Button)
            val headerRow = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8f))
            }

            val hdrIcon = android.widget.ImageView(activity).apply {
                getFloatingIconBitmap()?.let { setImageBitmap(it) }
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                    setMargins(0, 0, dp(8f), 0)
                }
            }
            headerRow.addView(hdrIcon)

            val titleCol = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(activity).apply {
                text = "J-Driver"
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
            }
            val tvSub = TextView(activity).apply {
                text = if (isAr) "نشط داخل ساند ⚡" else "Active Inside Saned ⚡"
                textSize = 10.5f
                setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            }
            titleCol.addView(tvTitle)
            titleCol.addView(tvSub)
            headerRow.addView(titleCol)

            val btnLang = TextView(activity).apply {
                text = if (isAr) "🌐 EN" else "🌐 عربي"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(android.graphics.Color.parseColor("#1E293B"))
                    setStroke(dp(1f), android.graphics.Color.parseColor("#475569"))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(4f), 0, dp(4f), 0)
                }
                setOnClickListener {
                    appUiLanguage = if (isAr) "en" else "ar"
                    SettingsStore.setAppLanguage(activity, appUiLanguage)
                    pushQuickSettingsUpdateToProvider(activity)
                    dialog.dismiss()
                    showFloatingQuickControlDialog(activity, statusDot)
                }
            }
            headerRow.addView(btnLang)

            val btnClose = TextView(activity).apply {
                text = "✖"
                textSize = 12.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(android.graphics.Color.parseColor("#1E293B"))
                }
                setOnClickListener { dialog.dismiss() }
            }
            headerRow.addView(btnClose)
            card.addView(headerRow)

            if (isLicenseActiveInsideSaned()) {
                val expBanner = TextView(activity).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    val nowMs = System.currentTimeMillis()
                    if (jdrvLicExpiryMs == 0L) {
                        text = "📅 License: Lifetime ♾️ (Never Expires)"
                        setTextColor(android.graphics.Color.parseColor("#6EE7B7"))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 8f * density
                            setColor(android.graphics.Color.parseColor("#064E3B"))
                            setStroke(dp(1f), android.graphics.Color.parseColor("#10B981"))
                        }
                    } else {
                        val remMs = (jdrvLicExpiryMs - nowMs).coerceAtLeast(0L)
                        val expDateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(jdrvLicExpiryMs))
                        val totalMins = remMs / 60_000L
                        val d = totalMins / 1440L
                        val h = (totalMins % 1440L) / 60L
                        val m = totalMins % 60L
                        val isOneDayLeft = remMs <= 86_400_000L
                        if (isOneDayLeft) {
                            text = "⚠️ ONLY 1 DAY LEFT! (${h}h ${m}m) • Exp: $expDateStr"
                            setTextColor(android.graphics.Color.parseColor("#FCA5A5"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = 8f * density
                                setColor(android.graphics.Color.parseColor("#7F1D1D"))
                                setStroke(dp(1f), android.graphics.Color.parseColor("#EF4444"))
                            }
                        } else {
                            text = "📅 Exp: $expDateStr • ⏳ ${d}d ${h}h ${m}m"
                            setTextColor(android.graphics.Color.parseColor("#93C5FD"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = 8f * density
                                setColor(android.graphics.Color.parseColor("#1E293B"))
                                setStroke(dp(1f), android.graphics.Color.parseColor("#3B82F6"))
                            }
                        }
                    }
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(8f))
                    }
                }
                card.addView(expBanner)
            }

            if (!isLicenseActiveInsideSaned() || !jdrvRemoteFloatingMenu || (jdrvLicFeatureMask and 4) == 0) {
                val lockNotice = TextView(activity).apply {
                    text = if (!jdrvRemoteAppEnabled && jdrvRemoteMsgEn.isNotBlank()) {
                        jdrvRemoteMsgEn
                    } else {
                        "🔒 J-Driver license is not active or has expired.\nPlease open J-Driver to activate your key or contact the developer to purchase a key."
                    }
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.parseColor("#FBBF24"))
                    setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f * density
                        setColor(android.graphics.Color.parseColor("#1E293B"))
                        setStroke(dp(1f), android.graphics.Color.parseColor("#475569"))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(8f))
                    }
                }
                card.addView(lockNotice)

                val btnOpenToActivate = TextView(activity).apply {
                    text = if (isAr) "🔑 فتح J-Driver لتفعيل المفتاح" else "🔑 Open J-Driver to Activate Key"
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f * density
                        setColor(android.graphics.Color.parseColor("#2563EB"))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(6f))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            val launchIntent = activity.packageManager.getLaunchIntentForPackage("dev.jing.sanedhook")
                                ?: Intent().apply {
                                    component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.ui.MainActivity")
                                }
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            activity.startActivity(launchIntent)
                        } catch (_: Throwable) {}
                    }
                }
                card.addView(btnOpenToActivate)

                val btnWaBuy = TextView(activity).apply {
                    text = if (isAr) "💬 شراء مفتاح واتساب (+966505812141)" else "💬 Buy Key via WhatsApp (+966505812141)"
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f * density
                        setColor(android.graphics.Color.parseColor("#16A34A"))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(6f))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=966505812141&text=" + android.net.Uri.encode("hi i need buy key"))
                            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Throwable) {}
                    }
                }
                card.addView(btnWaBuy)

                val btnTgLinks = TextView(activity).apply {
                    text = if (isAr) "📢 تليجرام: t.me/J_Driver  |  @J_Drive1" else "📢 Telegram: t.me/J_Driver  |  @J_Drive1"
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f * density
                        setColor(android.graphics.Color.parseColor("#0284C7"))
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/J_Driver")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Throwable) {}
                    }
                }
                card.addView(btnTgLinks)

                rootScroll.addView(card)
                dialog.setContentView(rootScroll)
                dialog.window?.let { win ->
                    val dm = activity.resources.displayMetrics
                    win.setLayout((dm.widthPixels * 0.82f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                }
                dialog.show()
                return
            }

            // Small-to-Medium Toggle Button Builder
            fun makeToggleBtn(
                labelProvider: () -> String,
                stateProvider: () -> Boolean,
                activeColor: String = "#059669",
                onToggle: () -> Unit
            ): TextView {
                return TextView(activity).apply {
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(11f), dp(8.5f), dp(11f), dp(8.5f))
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, dp(6f))
                    layoutParams = lp

                    fun refreshLook() {
                        val on = stateProvider()
                        text = labelProvider()
                        setTextColor(android.graphics.Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 10f * density
                            setColor(android.graphics.Color.parseColor(if (on) activeColor else "#1E293B"))
                            setStroke(dp(1f), android.graphics.Color.parseColor(if (on) "#34D399" else "#334155"))
                        }
                    }
                    refreshLook()
                    setOnClickListener {
                        onToggle()
                        refreshLook()
                        updateStatusDotBackground(statusDot)
                    }
                }
            }

            // 1. Master Switch
            card.addView(makeToggleBtn(
                labelProvider = {
                    if (isAr) {
                        "⚡ المفتاح الرئيسي : " + (if (isMasterRunning) "مفعل ✅" else "متوقف ❌")
                    } else {
                        "⚡ Master Switch : " + (if (isMasterRunning) "ON ✅" else "OFF ❌")
                    }
                },
                stateProvider = { isMasterRunning },
                activeColor = "#059669"
            ) {
                isMasterRunning = !isMasterRunning
                SettingsStore.setMasterRunning(activity, isMasterRunning)
                pushQuickSettingsUpdateToProvider(activity)
            })

            // 2. Auto-Accept (0.0ms)
            card.addView(makeToggleBtn(
                labelProvider = {
                    if (isAr) {
                        "🚀 قبول تلقائي فوري (0.0ms) : " + (if (isAutoAccept) "مفعل ✅" else "متوقف ❌")
                    } else {
                        "🚀 Instant Auto-Accept (0.0ms) : " + (if (isAutoAccept) "ON ✅" else "OFF ❌")
                    }
                },
                stateProvider = { isAutoAccept },
                activeColor = "#059669"
            ) {
                isAutoAccept = !isAutoAccept
                SettingsStore.setAutoAccept(activity, isAutoAccept)
                pushQuickSettingsUpdateToProvider(activity)
            })

            // 3. Auto-Reject
            card.addView(makeToggleBtn(
                labelProvider = {
                    if (isAr) {
                        "🛡️ رفض تلقائي لغير المطابق : " + (if (isAutoReject) "مفعل ✅" else "متوقف ❌")
                    } else {
                        "🛡️ Auto-Reject Unmatched : " + (if (isAutoReject) "ON ✅" else "OFF ❌")
                    }
                },
                stateProvider = { isAutoReject },
                activeColor = "#DC2626"
            ) {
                isAutoReject = !isAutoReject
                SettingsStore.setAutoReject(activity, isAutoReject)
                pushQuickSettingsUpdateToProvider(activity)
            })

            // 4. Extra Order (2 vs 3 Orders)
            card.addView(makeToggleBtn(
                labelProvider = {
                    val isThree = maxOrdersLimit >= 3
                    if (isAr) {
                        if (isThree) "➕ طلب إضافي: مفعل (الحد: 3 طلبات) 🔥" else "➕ طلب إضافي: متوقف (الافتراضي: طلبين 2)"
                    } else {
                        if (isThree) "➕ Extra Order: ON (Max: 3 Orders) 🔥" else "➕ Extra Order: OFF (Default: 2 Orders)"
                    }
                },
                stateProvider = { maxOrdersLimit >= 3 },
                activeColor = "#7C3AED"
            ) {
                maxOrdersLimit = if (maxOrdersLimit >= 3) 2 else 3
                SettingsStore.setMaxOrdersLimit(activity, maxOrdersLimit)
                pushQuickSettingsUpdateToProvider(activity)
            })

            // 5. DELIVER ORDER Section (Compact)
            val deliverBox = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(9f), dp(7f), dp(9f), dp(5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 11f * density
                    setColor(android.graphics.Color.parseColor("#172554"))
                    setStroke(dp(1f), android.graphics.Color.parseColor("#2563EB"))
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(1f), 0, dp(7f))
                layoutParams = lp
            }

            val tvDeliverHdr = TextView(activity).apply {
                text = if (isAr) "📦 DELIVER ORDER (توصيل الطلب)" else "📦 DELIVER ORDER"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#93C5FD"))
                setPadding(0, 0, 0, dp(4f))
            }
            deliverBox.addView(tvDeliverHdr)

            deliverBox.addView(makeToggleBtn(
                labelProvider = {
                    val mockOn = LocationEngine.isFakeLocationEnabled
                    if (isAr) {
                        "تفعيل DELIVER ORDER : " + (if (mockOn) "مفعل ✅" else "متوقف ❌")
                    } else {
                        "Enable DELIVER ORDER : " + (if (mockOn) "ON ✅" else "OFF ❌")
                    }
                },
                stateProvider = { LocationEngine.isFakeLocationEnabled },
                activeColor = "#2563EB"
            ) {
                val next = !LocationEngine.isFakeLocationEnabled
                LocationEngine.isFakeLocationEnabled = next
                SettingsStore.setFakeLocation(activity, next)
                pushQuickSettingsUpdateToProvider(activity)
            })

            deliverBox.addView(makeToggleBtn(
                labelProvider = {
                    val simOn = LocationEngine.isTripSimulationActive
                    if (isAr) {
                        "تشغيل حركة DELIVER ORDER : " + (if (simOn) "يعمل 🚗💨" else "متوقف ⏹️")
                    } else {
                        "Run DELIVER ORDER Movement : " + (if (simOn) "ON ✅" else "OFF ❌")
                    }
                },
                stateProvider = { LocationEngine.isTripSimulationActive },
                activeColor = "#0284C7"
            ) {
                val next = !LocationEngine.isTripSimulationActive
                if (next && !LocationEngine.isFakeLocationEnabled) {
                    LocationEngine.isFakeLocationEnabled = true
                    SettingsStore.setFakeLocation(activity, true)
                }
                LocationEngine.isTripSimulationActive = next
                SettingsStore.setRouteSimActive(activity, next)
                pushQuickSettingsUpdateToProvider(activity)
            })
            card.addView(deliverBox)

            // 6. Quick Filters Row (Compact)
            val filtersBox = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(9f), dp(7f), dp(9f), dp(8f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 11f * density
                    setColor(android.graphics.Color.parseColor("#1E293B"))
                    setStroke(dp(1f), android.graphics.Color.parseColor("#334155"))
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, dp(7f))
                layoutParams = lp
            }

            val tvFiltersHdr = TextView(activity).apply {
                text = if (isAr) "🎯 فلاتر السريعة (0 = بلا حد)" else "🎯 Quick Filters (0 = No Limit)"
                textSize = 10.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                setPadding(0, 0, 0, dp(5f))
            }
            filtersBox.addView(tvFiltersHdr)

            val inputsRow = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }

            fun makeFilterInput(hintText: String, currentVal: Double): android.widget.EditText {
                return android.widget.EditText(activity).apply {
                    hint = hintText
                    val formatted = if (currentVal <= 0.0) "0" else if (currentVal == currentVal.toInt().toDouble()) currentVal.toInt().toString() else currentVal.toString()
                    setText(formatted)
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.parseColor("#64748B"))
                    gravity = android.view.Gravity.CENTER
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setPadding(dp(5f), dp(6f), dp(5f), dp(6f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 7f * density
                        setColor(android.graphics.Color.parseColor("#0F172A"))
                        setStroke(dp(1f), android.graphics.Color.parseColor("#475569"))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(2.5f), 0, dp(2.5f), 0)
                    }
                }
            }

            val edtMinPrice = makeFilterInput(if (isAr) "السعر" else "Price", minOrderPrice)
            val edtMaxRest = makeFilterInput(if (isAr) "المطعم" else "Pickup", maxDistToRestaurant)
            val edtMaxCust = makeFilterInput(if (isAr) "العميل" else "Customer", maxDistCustomer)

            fun applyTypedFiltersImmediately() {
                minOrderPrice = edtMinPrice.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                maxDistToRestaurant = edtMaxRest.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                maxDistCustomer = edtMaxCust.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                pushQuickSettingsUpdateToProvider(activity)
            }

            val liveFilterWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    applyTypedFiltersImmediately()
                }
            }
            edtMinPrice.addTextChangedListener(liveFilterWatcher)
            edtMaxRest.addTextChangedListener(liveFilterWatcher)
            edtMaxCust.addTextChangedListener(liveFilterWatcher)

            inputsRow.addView(edtMinPrice)
            inputsRow.addView(edtMaxRest)
            inputsRow.addView(edtMaxCust)
            filtersBox.addView(inputsRow)

            val btnSaveFilters = TextView(activity).apply {
                text = if (isAr) "💾 حفظ الفلاتر الآن" else "💾 Save Filters Now"
                textSize = 11.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(8f), dp(7.5f), dp(8f), dp(7.5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(android.graphics.Color.parseColor("#2563EB"))
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(6f), 0, 0)
                layoutParams = lp
                setOnClickListener {
                    applyTypedFiltersImmediately()
                    Toast.makeText(
                        activity,
                        if (isAr) "✅ تم حفظ وتفعيل الفلاتر فوراً" else "✅ Filters saved & active immediately",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            filtersBox.addView(btnSaveFilters)
            card.addView(filtersBox)

            // 7. Single Bottom Emoji Button "Go to app" (opens J-Driver full app)
            val btnOpenFullApp = TextView(activity).apply {
                text = "Go to app"
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(10f), dp(8.5f), dp(10f), dp(8.5f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(android.graphics.Color.parseColor("#2563EB"))
                }
                setOnClickListener {
                    applyTypedFiltersImmediately()
                    dialog.dismiss()
                    try {
                        val launchIntent = activity.packageManager.getLaunchIntentForPackage("dev.jing.sanedhook")
                            ?: Intent().apply {
                                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.ui.MainActivity")
                            }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        activity.startActivity(launchIntent)
                    } catch (_: Throwable) {}
                }
            }
            card.addView(btnOpenFullApp)

            dialog.setOnDismissListener {
                applyTypedFiltersImmediately()
                if (activeQuickDialog === dialog) activeQuickDialog = null
            }

            rootScroll.addView(card)
            dialog.setContentView(rootScroll)

            val win = dialog.window
            if (win != null) {
                val dm = activity.resources.displayMetrics
                // Small-to-medium width (82% of screen width)
                win.setLayout((dm.widthPixels * 0.82f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }
            dialog.show()
        } catch (_: Throwable) {}
    }

    private fun pushQuickSettingsUpdateToProvider(context: Context) {
        val modNow = System.currentTimeMillis()
        lastLocalOverlayModMs = modNow
        isExtraOrderEnabled = maxOrdersLimit >= 3
        LocationEngine.isMasterRunning = isMasterRunning
        DeviceSpoofer.isMasterRunning = isMasterRunning

        // 1. Write synchronously to local target-app SharedPreferences (100% reliable in all modes including Non-Root --embed)
        try {
            val sp = context.getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
            sp.edit()
                .putLong("last_settings_mod_ms", modNow)
                .putBoolean("master_running", isMasterRunning)
                .putBoolean("enabled", isMasterRunning)
                .putBoolean("auto_accept", isAutoAccept)
                .putBoolean("auto_reject", isAutoReject)
                .putBoolean("dry_run", false)
                .putBoolean("show_food_price", true)
                .putBoolean("extra_order_enabled", isExtraOrderEnabled)
                .putInt("max_orders_limit", maxOrdersLimit)
                .putString("max_orders_limit_str", maxOrdersLimit.toString())
                .putString("min_price", minOrderPrice.toString())
                .putString("min_order_price", minOrderPrice.toString())
                .putBoolean("price_gate_enabled", minOrderPrice > 0.0)
                .putString("max_dist_rest", maxDistToRestaurant.toString())
                .putString("max_dist_to_restaurant", maxDistToRestaurant.toString())
                .putBoolean("restaurant_gate_enabled", maxDistToRestaurant > 0.0)
                .putString("max_dist_cust", maxDistCustomer.toString())
                .putString("max_dist_restaurant_to_customer", maxDistCustomer.toString())
                .putBoolean("customer_gate_enabled", maxDistCustomer > 0.0)
                .putBoolean("fake_location_enabled", LocationEngine.isFakeLocationEnabled)
                .putString("fake_lat", LocationEngine.currentLat.toString())
                .putString("fake_lng", LocationEngine.currentLng.toString())
                .putBoolean("route_sim_active", LocationEngine.isTripSimulationActive)
                .putFloat("route_sim_speed_kmh", LocationEngine.simulatedSpeedKmh)
                .putFloat("route_sim_bearing", LocationEngine.currentBearing)
                .putBoolean("spoof_android_id", DeviceSpoofer.isSpoofAndroidId)
                .putString("spoofed_android_id", DeviceSpoofer.spoofedAndroidId)
                .putString("app_ui_language", appUiLanguage)
                .putBoolean("floating_overlay_enabled", isFloatingOverlayEnabled)
                .commit()
        } catch (_: Throwable) {}

        // 2. Build Bundle with exact keys & String/Boolean/Int/Float types expected by XposedProvider and SettingsStore
        val extras = android.os.Bundle().apply {
            putLong("last_settings_mod_ms", modNow)
            putBoolean("master_running", isMasterRunning)
            putBoolean("enabled", isMasterRunning)
            putBoolean("auto_accept", isAutoAccept)
            putBoolean("auto_reject", isAutoReject)
            putBoolean("dry_run", false)
            putBoolean("show_food_price", true)
            putBoolean("extra_order_enabled", isExtraOrderEnabled)
            putInt("max_orders_limit", maxOrdersLimit)
            putString("max_orders_limit_str", maxOrdersLimit.toString())
            putString("min_price", minOrderPrice.toString())
            putString("min_order_price", minOrderPrice.toString())
            putBoolean("price_gate_enabled", minOrderPrice > 0.0)
            putString("max_dist_rest", maxDistToRestaurant.toString())
            putString("max_dist_to_restaurant", maxDistToRestaurant.toString())
            putBoolean("restaurant_gate_enabled", maxDistToRestaurant > 0.0)
            putString("max_dist_cust", maxDistCustomer.toString())
            putString("max_dist_restaurant_to_customer", maxDistCustomer.toString())
            putBoolean("customer_gate_enabled", maxDistCustomer > 0.0)
            putBoolean("active_polling", true)
            putFloat("poll_interval_sec", 0.0f)
            putInt("parallel_requests", 3)
            putBoolean("fake_location_enabled", LocationEngine.isFakeLocationEnabled)
            putString("fake_lat", LocationEngine.currentLat.toString())
            putString("fake_lng", LocationEngine.currentLng.toString())
            putBoolean("route_sim_active", LocationEngine.isTripSimulationActive)
            putFloat("route_sim_speed_kmh", LocationEngine.simulatedSpeedKmh)
            putFloat("route_sim_bearing", LocationEngine.currentBearing)
            putBoolean("spoof_android_id", DeviceSpoofer.isSpoofAndroidId)
            putString("spoofed_android_id", DeviceSpoofer.spoofedAndroidId)
            putString("app_ui_language", appUiLanguage)
            putBoolean("floating_overlay_enabled", isFloatingOverlayEnabled)
        }

        // 3. Push via ContentProvider ("updateSettingsFromFloating")
        try {
            context.contentResolver.call(
                android.net.Uri.parse("content://dev.jing.sanedhook.XposedService"),
                "updateSettingsFromFloating",
                null,
                extras
            )
        } catch (_: Throwable) {}

        // 4. Also send explicit component Broadcast to SanedReceiver as fallback
        try {
            val bIntent = Intent("dev.saned.assistant.ACTION_UPDATE_SETTINGS_FROM_OVERLAY").apply {
                component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtras(extras)
            }
            context.sendBroadcast(bIntent)
        } catch (_: Throwable) {}
    }
}
