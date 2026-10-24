package dev.jing.jdriver.keygen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KeyGenActivity : Activity() {

    private val LIC_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var isAr: Boolean = true
    private var currentTab: Int = 0
    private var selectedDurationMins: Int = 43200 // Default 30 Days
    private var isUniversalMode: Boolean = false
    private var remoteAppEnabled: Boolean = true

    private lateinit var tabKeyGenView: ScrollView
    private lateinit var tabRemoteView: ScrollView
    private lateinit var tabVerifyView: ScrollView
    private lateinit var btnTab0: TextView
    private lateinit var btnTab1: TextView
    private lateinit var btnTab2: TextView
    private lateinit var historyContainer: LinearLayout

    private fun tr(ar: String, en: String): String = if (isAr) ar else en

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, cornerDp: Float, strokeHex: String? = null, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = cornerDp * resources.displayMetrics.density
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null && strokeDp > 0f) {
                setStroke(dp(strokeDp), Color.parseColor(strokeHex))
            }
        }
    }

    private val ADMIN_PIN_SALTED_SHA256 = "bd76ba93ef2f6959025cfbd2e75f2d584ef90d1f6e958b7013b5a0c590ae2c7e"

    private fun reconstructOfficialCertSha256(): ByteArray {
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

    private fun isKeyGenEnvironmentTampered(): Boolean {
        try {
            if (android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()) return true
            if (packageName != "dev.jing.jdriver.keygen") return true
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true
            @Suppress("DEPRECATION")
            val pkgInfo = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = pkgInfo.signatures
            if (sigs != null && sigs.isNotEmpty() && sigs[0] != null) {
                val actual = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
                val expected = reconstructOfficialCertSha256()
                if (!MessageDigest.isEqual(actual, expected)) return true
            } else {
                return true
            }
        } catch (_: Throwable) {
            return true
        }
        return false
    }

    private fun verifyAdminPinHash(pinInput: String): Boolean {
        val clean = pinInput.trim()
        if (clean.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("JDRV_ADMIN_SALT_2026:$clean").toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
        return digest == ADMIN_PIN_SALTED_SHA256
    }

    private fun showPinLockScreen() {
        val lockRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#1E293B", 24f, "#10B981", 2f)
            setPadding(dp(24f), dp(28f), dp(24f), dp(28f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(dp(22f), dp(22f), dp(22f), dp(22f))
            }
        }
        val icon = TextView(this).apply {
            text = "🛡️"
            textSize = 38f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        }
        card.addView(icon)

        val title = TextView(this).apply {
            text = tr("حماية تطبيق المطور (J-Driver KeyGen)", "J-Driver KeyGen Developer Lock")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        card.addView(title)

        val sub = TextView(this).apply {
            text = tr("أدخل الرقم السري الخاص بالمطور لفتح لوحة توليد المفاتيح والتحكم عن بُعد", "Enter Developer Master PIN to unlock KeyGen & Remote Control")
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6f), 0, dp(18f))
        }
        card.addView(sub)

        val edtPin = EditText(this).apply {
            hint = tr("الرقم السري للمطور (PIN)", "Developer Master PIN")
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 16f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = roundedBg("#0F172A", 14f, "#334155", 1.5f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(edtPin)

        val chkRemember = CheckBox(this).apply {
            text = tr("تذكر دخولي على هذا الهاتف", "Remember login on this phone")
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            isChecked = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(10f), 0, dp(14f))
            }
        }
        card.addView(chkRemember)

        val btnUnlock = TextView(this).apply {
            text = tr("🔓 فتح لوحة المطور الآن", "🔓 Unlock Developer Console")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#10B981", 14f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (isKeyGenEnvironmentTampered()) {
                    Toast.makeText(this@KeyGenActivity, "⛔ Tampered environment detected", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val entered = edtPin.text.toString().trim()
                if (verifyAdminPinHash(entered)) {
                    if (chkRemember.isChecked) {
                        getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("saved_pin_hash", ADMIN_PIN_SALTED_SHA256)
                            .commit()
                    }
                    buildMainKeyGenUI()
                } else {
                    Toast.makeText(
                        this@KeyGenActivity,
                        tr("❌ الرقم السري غير صحيح!", "❌ Invalid Developer PIN!"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        card.addView(btnUnlock)
        lockRoot.addView(card)
        setContentView(lockRoot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)
        isAr = prefs.getString("ui_lang", "ar") != "en"
        window.decorView.layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        window.statusBarColor = Color.parseColor("#090D16")
        if (isKeyGenEnvironmentTampered()) {
            Toast.makeText(this, "⛔ Security integrity check failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val savedHash = prefs.getString("saved_pin_hash", "") ?: ""
        if (savedHash == ADMIN_PIN_SALTED_SHA256) {
            buildMainKeyGenUI()
        } else {
            showPinLockScreen()
        }
    }

    private fun buildMainKeyGenUI() {
        val prefs = getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#1E293B", 0f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }

        val badgeIcon = TextView(this).apply {
            text = "🔐"
            textSize = 22f
            gravity = Gravity.CENTER
            background = roundedBg("#0F172A", 14f, "#10B981", 1.5f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        topBar.addView(badgeIcon)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12f), 0, dp(12f), 0)
            }
        }
        val tvTitle = TextView(this).apply {
            text = tr("مولد مفاتيح وتحكم J-Driver", "J-Driver KeyGen & Control")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val tvSub = TextView(this).apply {
            text = tr("نسخة المطور الرسمية • تشفير وإيقاف عن بُعد", "Official Developer Admin APK • Remote Kill-Switch")
            textSize = 11.5f
            setTextColor(Color.parseColor("#10B981"))
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)
        topBar.addView(titleCol)

        val btnLang = TextView(this).apply {
            text = if (isAr) "🌐 EN" else "🌐 عربي"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#334155", 10f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                prefs.edit().putString("ui_lang", if (isAr) "en" else "ar").commit()
                recreate()
            }
        }
        topBar.addView(btnLang)
        root.addView(topBar)

        // Navigation Tabs Row
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#0F172A", 16f, "#334155", 1f)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(14f), dp(12f), dp(14f), dp(8f))
            }
        }

        btnTab0 = createNavTabButton(tr("🔑 توليد مفتاح", "🔑 KeyGen"), 0)
        btnTab1 = createNavTabButton(tr("🛡️ إيقاف وتحكم", "🛡️ Kill-Switch"), 1)
        btnTab2 = createNavTabButton(tr("🔍 فحص وسجل", "🔍 Verify & Log"), 2)
        navRow.addView(btnTab0)
        navRow.addView(btnTab1)
        navRow.addView(btnTab2)
        root.addView(navRow)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tabKeyGenView = buildKeyGenTab()
        tabRemoteView = buildRemoteControlTab()
        tabVerifyView = buildVerifyAndHistoryTab()

        contentFrame.addView(tabKeyGenView)
        contentFrame.addView(tabRemoteView)
        contentFrame.addView(tabVerifyView)
        root.addView(contentFrame)

        setContentView(root)
        switchTab(0)
    }

    private fun createNavTabButton(title: String, index: Int): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(index) }
        }
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        tabKeyGenView.visibility = if (idx == 0) View.VISIBLE else View.GONE
        tabRemoteView.visibility = if (idx == 1) View.VISIBLE else View.GONE
        tabVerifyView.visibility = if (idx == 2) View.VISIBLE else View.GONE

        val tabs = listOf(btnTab0, btnTab1, btnTab2)
        for (i in tabs.indices) {
            if (i == idx) {
                tabs[i].background = roundedBg("#10B981", 12f)
                tabs[i].setTextColor(Color.WHITE)
            } else {
                tabs[i].background = null
                tabs[i].setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        if (idx == 2) refreshHistoryUI()
    }

    // =========================================================================
    // CRYPTOGRAPHIC ENGINE (100% IDENTICAL TO J-DRIVER CLIENT)
    // =========================================================================
    private fun reconstructMasterKey(): ByteArray {
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
        return MessageDigest.getInstance("SHA-256").digest(raw)
    }

    private fun computeDeviceHashInt(deviceId: String): Int {
        val clean = deviceId.trim().uppercase(Locale.US).replace("-", "").replace(" ", "")
        if (clean.isEmpty() || clean == "*" || clean == "ANY" || clean == "ALL" || clean == "JID00000000") {
            return 0
        }
        val d = MessageDigest.getInstance("SHA-256")
            .digest(("JDRV_DEV:$clean").toByteArray(Charsets.UTF_8))
        val v = ((d[0].toInt() and 0xFF) shl 24) or
            ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or
            (d[3].toInt() and 0xFF)
        return if (v != 0) v else 1
    }

    private fun encodeBase32Bytes20(data: ByteArray): String {
        var bits = 0
        var value = 0
        val sb = StringBuilder()
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(LIC_ALPHABET[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(LIC_ALPHABET[(value shl (5 - bits)) and 31])
        }
        val raw = sb.toString()
        val groups = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            val end = (i + 4).coerceAtMost(raw.length)
            groups.add(raw.substring(i, end))
            i += 4
        }
        return "JDRV-" + groups.joinToString("-")
    }

    private fun decodeBase32Key(keyStr: String): ByteArray? {
        var clean = keyStr.trim().uppercase(Locale.US).replace(" ", "")
        if (clean.startsWith("JDRV-")) clean = clean.substring(5)
        clean = clean.replace("-", "")
        if (clean.length != 32) return null
        var bits = 0
        var value = 0
        val out = ByteArray(20)
        var outIdx = 0
        for (i in 0 until clean.length) {
            val idx = LIC_ALPHABET.indexOf(clean[i])
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

    private fun generateLicenseKey(
        targetDeviceId: String,
        durationMinutes: Int,
        keyEpoch: Int,
        featureMask: Int
    ): String {
        val mk = reconstructMasterKey()
        val devHash = computeDeviceHashInt(targetDeviceId)
        val expCode: Int = if (durationMinutes <= 0) {
            -1 // 0xFFFFFFFF = Lifetime
        } else {
            (-0x80000000) or (durationMinutes and 0x7FFFFFFF)
        }
        val plain12 = ByteArray(12)
        plain12[0] = ((devHash ushr 24) and 0xFF).toByte()
        plain12[1] = ((devHash ushr 16) and 0xFF).toByte()
        plain12[2] = ((devHash ushr 8) and 0xFF).toByte()
        plain12[3] = (devHash and 0xFF).toByte()
        plain12[4] = ((expCode ushr 24) and 0xFF).toByte()
        plain12[5] = ((expCode ushr 16) and 0xFF).toByte()
        plain12[6] = ((expCode ushr 8) and 0xFF).toByte()
        plain12[7] = (expCode and 0xFF).toByte()
        plain12[8] = (keyEpoch and 0xFF).toByte()
        plain12[9] = (featureMask and 0xFF).toByte()
        plain12[10] = 0x4A.toByte()
        plain12[11] = 0x44.toByte()

        val rnd = SecureRandom().nextInt(0x10000)
        val nonce2 = byteArrayOf(((rnd ushr 8) and 0xFF).toByte(), (rnd and 0xFF).toByte())

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(mk)
        sha.update(nonce2)
        val stream = sha.digest()

        val enc12 = ByteArray(12)
        for (idx in 0 until 12) {
            enc12[idx] = (plain12[idx].toInt() xor stream[idx].toInt()).toByte()
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mk, "HmacSHA256"))
        mac.update(enc12)
        mac.update(nonce2)
        val tag6 = mac.doFinal().copyOfRange(0, 6)

        val full20 = ByteArray(20)
        System.arraycopy(enc12, 0, full20, 0, 12)
        System.arraycopy(nonce2, 0, full20, 12, 2)
        System.arraycopy(tag6, 0, full20, 14, 6)
        return encodeBase32Bytes20(full20)
    }

    private fun buildSignedRemoteControlJson(
        appEnabled: Boolean,
        allowAccept: Boolean,
        allowDeliver: Boolean,
        allowFloating: Boolean,
        allowAndroidId: Boolean,
        minEpoch: Int,
        blockedDevicesCsv: String,
        killMsgAr: String
    ): String {
        val bDevs = blockedDevicesCsv.split(",")
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .sorted()
        val canonical = "${if (appEnabled) 1 else 0}|${if (allowAccept) 1 else 0}|${if (allowDeliver) 1 else 0}|${if (allowFloating) 1 else 0}|${if (allowAndroidId) 1 else 0}|$minEpoch|${bDevs.joinToString(",")}|"
        val mk = reconstructMasterKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mk, "HmacSHA256"))
        val sigHex = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
            .take(32)

        val json = JSONObject().apply {
            put("app_enabled", appEnabled)
            put("allow_auto_accept", allowAccept)
            put("allow_deliver_order", allowDeliver)
            put("allow_floating_menu", allowFloating)
            put("allow_android_id", allowAndroidId)
            put("min_key_epoch", minEpoch)
            put("blocked_devices", JSONArray(bDevs))
            put("blocked_keys", JSONArray())
            put("kill_message_ar", killMsgAr)
            put("kill_message_en", "⚠️ This copy of J-Driver has been disabled by the developer. Contact WhatsApp +966505812141 or Telegram @J_Drive1")
            put("sig", sigHex)
        }
        return json.toString(2)
    }

    // =========================================================================
    // TAB 0: KEY GENERATOR UI
    // =========================================================================
    private fun buildKeyGenTab(): ScrollView {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(32f))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 20f, "#334155", 1.5f)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }

        val lblDev = TextView(this).apply {
            text = tr("1. معرّف جهاز العميل (JID-XXXX-XXXX):", "1. Customer Device ID (JID-XXXX-XXXX):")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        card.addView(lblDev)

        val edtDeviceId = EditText(this).apply {
            hint = "JID-8F2A-41C9"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
            textSize = 15f
            background = roundedBg("#0F172A", 14f, "#475569", 1.5f)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8f), 0, dp(8f)) }
        }
        card.addView(edtDeviceId)

        val devBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14f)) }
        }

        val btnPasteDev = TextView(this).apply {
            text = tr("📋 لصق المعرّف", "📋 Paste ID")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#334155", 12f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6f), 0)
            }
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = clip.primaryClip?.getItemAt(0)?.coerceToText(this@KeyGenActivity)?.toString()?.trim() ?: ""
                if (txt.isNotEmpty()) {
                    isUniversalMode = false
                    edtDeviceId.isEnabled = true
                    edtDeviceId.setText(txt)
                }
            }
        }

        val btnUniversal = TextView(this).apply {
            text = tr("🌍 مفتاح عام (أي جهاز)", "🌍 Universal Key (*)")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#4F46E5", 12f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            }
            setOnClickListener {
                isUniversalMode = !isUniversalMode
                if (isUniversalMode) {
                    edtDeviceId.setText("*")
                    edtDeviceId.isEnabled = false
                    text = tr("🔒 العودة لربط جهاز محدد", "🔒 Bind Specific Device")
                } else {
                    edtDeviceId.setText("")
                    edtDeviceId.isEnabled = true
                    text = tr("🌍 مفتاح عام (أي جهاز)", "🌍 Universal Key (*)")
                }
            }
        }
        devBtnsRow.addView(btnPasteDev)
        devBtnsRow.addView(btnUniversal)
        card.addView(devBtnsRow)

        val lblDur = TextView(this).apply {
            text = tr("2. اختر مدة صلاحية المفتاح (تبدأ من أول تفعيل):", "2. Select License Duration (starts on 1st activation):")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8f))
        }
        card.addView(lblDur)

        val durations = listOf(
            60 to tr("⏳ ساعة (1H)", "⏳ 1 Hour"),
            1440 to tr("📅 يوم (1D)", "📅 1 Day"),
            10080 to tr("📅 أسبوع (7D)", "📅 7 Days"),
            43200 to tr("📅 شهر (30D)", "📅 30 Days"),
            129600 to tr("📅 3 أشهر (90D)", "📅 90 Days"),
            259200 to tr("📅 6 أشهر (180D)", "📅 180 Days"),
            525600 to tr("📅 سنة (1Y)", "📅 1 Year"),
            0 to tr("♾️ مدى الحياة", "♾️ Lifetime")
        )

        val durButtons = mutableListOf<Pair<Int, TextView>>()
        val edtCustomDays = EditText(this).apply {
            hint = tr("أو اكتب عدد أيام مخصص (مثلاً: 15)", "Or custom days (e.g. 15)")
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13.5f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6f), 0, dp(14f)) }
        }

        fun updateDurationButtonsUI() {
            for ((mins, btn) in durButtons) {
                if (mins == selectedDurationMins) {
                    btn.background = roundedBg("#059669", 12f, "#34D399", 2f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.background = roundedBg("#0F172A", 12f, "#334155", 1f)
                    btn.setTextColor(Color.parseColor("#CBD5E1"))
                }
            }
        }

        for (rowIdx in 0 until 2) {
            val rLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8f)) }
            }
            for (colIdx in 0 until 4) {
                val (mins, title) = durations[rowIdx * 4 + colIdx]
                val b = TextView(this).apply {
                    text = title
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(6f), dp(12f), dp(6f), dp(12f))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3f), 0, dp(3f), 0)
                    }
                    setOnClickListener {
                        selectedDurationMins = mins
                        edtCustomDays.setText("")
                        updateDurationButtonsUI()
                    }
                }
                durButtons.add(mins to b)
                rLayout.addView(b)
            }
            card.addView(rLayout)
        }
        updateDurationButtonsUI()
        card.addView(edtCustomDays)

        val lblFeats = TextView(this).apply {
            text = tr("3. الوظائف المسموحة داخل هذا المفتاح:", "3. Functions Bound to This Key:")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6f))
        }
        card.addView(lblFeats)

        val chkAcc = CheckBox(this).apply {
            text = tr("⚡ القبول التلقائي الفوري والفلاتر", "⚡ Instant Auto-Accept & Filters")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkDel = CheckBox(this).apply {
            text = tr("🚗 توصيل الطلبات والخريطة (DELIVER ORDER)", "🚗 DELIVER ORDER & Map")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkFlt = CheckBox(this).apply {
            text = tr("🎯 القائمة العائمة السريعة فوق ساند", "🎯 Floating Quick Menu Over Saned")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkAid = CheckBox(this).apply {
            text = tr("🆔 ميزة تغيير معرّف الأندرويد (Android ID)", "🆔 Change Android ID Feature")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        card.addView(chkAcc)
        card.addView(chkDel)
        card.addView(chkFlt)
        card.addView(chkAid)

        val tvGeneratedKey = TextView(this).apply {
            text = generateLicenseKey("*", 43200, 1, 15)
            textSize = 14.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FBBF24"))
            background = roundedBg("#090D16", 14f, "#10B981", 2f)
            setPadding(dp(14f), dp(18f), dp(14f), dp(18f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(14f), 0, dp(12f)) }
        }

        val btnGenerate = TextView(this).apply {
            text = tr("⚡ توليد مفتاح التفعيل المشفر الآن", "⚡ Generate Encrypted License Key Now")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#10B981", 16f)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12f), 0, 0) }
            setOnClickListener {
                val targetDev = if (isUniversalMode) "*" else edtDeviceId.text.toString().trim()
                if (!isUniversalMode && targetDev.isEmpty()) {
                    Toast.makeText(
                        this@KeyGenActivity,
                        tr("⚠️ أدخل معرّف جهاز العميل (JID-...) أو اختر (مفتاح عام)", "⚠️ Enter Customer Device ID or tap Universal Key"),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val customDays = edtCustomDays.text.toString().trim().toIntOrNull() ?: 0
                val effMins = if (customDays > 0) customDays * 1440 else selectedDurationMins
                var mask = 0
                if (chkAcc.isChecked) mask = mask or 1
                if (chkDel.isChecked) mask = mask or 2
                if (chkFlt.isChecked) mask = mask or 4
                if (chkAid.isChecked) mask = mask or 8
                if (mask == 0) mask = 15

                val newKey = generateLicenseKey(targetDev, effMins, 1, mask)
                tvGeneratedKey.text = newKey
                val durLabel = when {
                    effMins == 0 -> tr("مدى الحياة", "Lifetime")
                    effMins >= 1440 -> "${effMins / 1440} " + tr("يوم", "Days")
                    else -> "$effMins " + tr("دقيقة", "Mins")
                }
                saveKeyToHistory(newKey, targetDev, durLabel)
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("J-Driver Key", newKey))
                Toast.makeText(
                    this@KeyGenActivity,
                    tr("✅ تم توليد المفتاح ونسخه للحافظة تلقائياً!", "✅ Key generated & copied to clipboard!"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        card.addView(btnGenerate)
        card.addView(tvGeneratedKey)

        val outActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCopyKey = TextView(this).apply {
            text = tr("📋 نسخ المفتاح", "📋 Copy Key")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 14f)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6f), 0)
            }
            setOnClickListener {
                val k = tvGeneratedKey.text.toString().trim()
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("J-Driver Key", k))
                Toast.makeText(this@KeyGenActivity, tr("✅ تم نسخ المفتاح", "✅ Key copied"), Toast.LENGTH_SHORT).show()
            }
        }

        val btnSendWhatsApp = TextView(this).apply {
            text = tr("💬 إرسال عبر واتساب", "💬 Send on WhatsApp")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#16A34A", 14f)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            }
            setOnClickListener {
                val k = tvGeneratedKey.text.toString().trim()
                val msg = "🔑 مفتاح تفعيل J-Driver الرسمي الخاص بك:\n\n$k\n\n📢 القناة الرسمية: https://t.me/J_Driver\n👤 المطور: @J_Drive1"
                try {
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?text=" + Uri.encode(msg)))
                    startActivity(waIntent)
                } catch (_: Throwable) {}
            }
        }
        outActionsRow.addView(btnCopyKey)
        outActionsRow.addView(btnSendWhatsApp)
        card.addView(outActionsRow)

        col.addView(card)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 1: REMOTE KILL-SWITCH & FUNCTION CONTROL UI
    // =========================================================================
    private fun buildRemoteControlTab(): ScrollView {
        val prefs = getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(32f))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 20f, "#334155", 1.5f)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }

        val hdr = TextView(this).apply {
            text = tr("🛡️ إيقاف التطبيق والتحكم بالوظائف عن بُعد (Kill-Switch)", "🛡️ Remote App Kill-Switch & Function Control")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6f))
        }
        card.addView(hdr)

        val sub = TextView(this).apply {
            text = tr(
                "إذا تم تكريك التطبيق أو تسريب المفاتيح، اضغط على زر الإيقاف بالأسفل لتعطيل التطبيق أو إيقاف وظائفه عن بُعد لدى جميع الهواتف فوراً.",
                "If the app is cracked or keys are leaked, tap the Kill-Switch below to remotely disable the app or its functions across all phones immediately."
            )
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(14f))
        }
        card.addView(sub)

        val btnMasterKill = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16f), dp(18f), dp(16f), dp(18f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14f)) }
        }

        fun updateMasterKillBtnUI() {
            if (remoteAppEnabled) {
                btnMasterKill.text = tr("🟢 حالة التطبيق: مفعّل ويعمل (اضغط هنا لإيقاف التطبيق بالكامل)", "🟢 App Status: ENABLED (Tap to Kill/Stop App)")
                btnMasterKill.background = roundedBg("#059669", 16f)
            } else {
                btnMasterKill.text = tr("🔴 حالة التطبيق: متوقف بالكامل! (اضغط هنا لإعادة التشغيل)", "🔴 App Status: KILLED / STOPPED! (Tap to Re-Enable)")
                btnMasterKill.background = roundedBg("#DC2626", 16f)
            }
        }
        updateMasterKillBtnUI()
        btnMasterKill.setOnClickListener {
            remoteAppEnabled = !remoteAppEnabled
            updateMasterKillBtnUI()
        }
        card.addView(btnMasterKill)

        val chkRemAcc = CheckBox(this).apply {
            text = tr("⚡ السماح بوظيفة القبول التلقائي عن بُعد", "⚡ Allow Auto-Accept Function Remotely")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkRemDel = CheckBox(this).apply {
            text = tr("🚗 السماح بوظيفة توصيل الطلبات والخريطة عن بُعد", "🚗 Allow DELIVER ORDER & Map Remotely")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkRemFlt = CheckBox(this).apply {
            text = tr("🎯 السماح بوظيفة القائمة العائمة فوق ساند عن بُعد", "🎯 Allow Floating Menu Over Saned Remotely")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        val chkRemAid = CheckBox(this).apply {
            text = tr("🆔 السماح بوظيفة تغيير معرّف الأندرويد عن بُعد", "🆔 Allow Change Android ID Remotely")
            setTextColor(Color.WHITE)
            isChecked = true
        }
        card.addView(chkRemAcc)
        card.addView(chkRemDel)
        card.addView(chkRemFlt)
        card.addView(chkRemAid)

        val edtMinEpoch = EditText(this).apply {
            hint = tr("الحد الأدنى لإصدار المفاتيح (ارفعه إلى 2 لإبطال كل المفاتيح القديمة)", "Min Key Epoch (set to 2 to revoke all epoch 1 keys)")
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10f), 0, dp(10f)) }
        }
        card.addView(edtMinEpoch)

        val edtBlockedDevs = EditText(this).apply {
            hint = tr("حظر أجهزة معينة مفصولة بفاصلة: JID-XXXX-XXXX", "Blocked Device IDs (comma-separated): JID-XXXX-XXXX")
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10f)) }
        }
        card.addView(edtBlockedDevs)

        val edtKillMsg = EditText(this).apply {
            setText("⚠️ تم إيقاف هذه النسخة من التطبيق عن بُعد من قِبل المطور. للتواصل وشراء مفتاح رسمي: واتساب +966505812141 أو تليجرام @J_Drive1")
            setTextColor(Color.WHITE)
            textSize = 12.5f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12f)) }
        }
        card.addView(edtKillMsg)

        val edtGhRepo = EditText(this).apply {
            setText(prefs.getString("gh_repo", "Hassinalhadi/my-saned") ?: "Hassinalhadi/my-saned")
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8f)) }
        }
        card.addView(edtGhRepo)

        val edtGhToken = EditText(this).apply {
            hint = tr("رمز GitHub Personal Access Token (يُحفظ تلقائياً)", "GitHub Personal Access Token (saved automatically)")
            setText(prefs.getString("gh_token", "") ?: "")
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14f)) }
        }
        card.addView(edtGhToken)

        val btnPushGithub = TextView(this).apply {
            text = tr("🚀 إرسال وتطبيق أمر الإيقاف/التحكم فوراً على جميع الهواتف", "🚀 Push & Apply Remote Kill-Switch / Control Now")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#E11D48", 16f)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            setOnClickListener {
                val repo = edtGhRepo.text.toString().trim().ifEmpty { "Hassinalhadi/my-saned" }
                val token = edtGhToken.text.toString().trim()
                prefs.edit().putString("gh_repo", repo).putString("gh_token", token).commit()

                val minEp = edtMinEpoch.text.toString().trim().toIntOrNull() ?: 1
                val signedJson = buildSignedRemoteControlJson(
                    appEnabled = remoteAppEnabled,
                    allowAccept = chkRemAcc.isChecked,
                    allowDeliver = chkRemDel.isChecked,
                    allowFloating = chkRemFlt.isChecked,
                    allowAndroidId = chkRemAid.isChecked,
                    minEpoch = minEp,
                    blockedDevicesCsv = edtBlockedDevs.text.toString(),
                    killMsgAr = edtKillMsg.text.toString().trim()
                )

                if (token.isEmpty()) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("jdriver_control.json", signedJson))
                    Toast.makeText(
                        this@KeyGenActivity,
                        tr("📋 تم نسخ ملف التحكم الموقّع (أدخل رمز GitHub Token للإرسال المباشر بضغطة زر)", "📋 Copied signed jdriver_control.json (enter GitHub Token for direct push)"),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                Toast.makeText(this@KeyGenActivity, tr("⏳ جاري تطبيق الأمر عن بُعد...", "⏳ Pushing remote control..."), Toast.LENGTH_SHORT).show()
                bgExecutor.execute {
                    try {
                        val apiUrl = "https://api.github.com/repos/$repo/contents/jdriver_control.json"
                        var existingSha = ""
                        val getConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Accept", "application/vnd.github+json")
                        }
                        if (getConn.responseCode in 200..299) {
                            val getBody = getConn.inputStream.bufferedReader().use { it.readText() }
                            existingSha = JSONObject(getBody).optString("sha", "")
                        }
                        getConn.disconnect()

                        val b64 = Base64.encodeToString(signedJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val putBody = JSONObject().apply {
                            put("message", if (remoteAppEnabled) "chore: update remote control [skip ci]" else "chore: activate remote kill-switch [skip ci]")
                            put("content", b64)
                            if (existingSha.isNotEmpty()) put("sha", existingSha)
                        }
                        val putConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "PUT"
                            doOutput = true
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Accept", "application/vnd.github+json")
                            setRequestProperty("Content-Type", "application/json")
                        }
                        putConn.outputStream.use { it.write(putBody.toString().toByteArray(Charsets.UTF_8)) }
                        val respCode = putConn.responseCode
                        putConn.disconnect()
                        runOnUiThread {
                            if (respCode in 200..299) {
                                Toast.makeText(
                                    this@KeyGenActivity,
                                    if (remoteAppEnabled) tr("✅ تم تفعيل وتشغيل التطبيق عن بُعد بنجاح!", "✅ App enabled remotely!")
                                    else tr("🔴 تم إيقاف التطبيق عن بُعد لدى جميع المستخدمين فوراً!", "🔴 App stopped remotely across all devices!"),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(this@KeyGenActivity, "GitHub HTTP $respCode", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            Toast.makeText(this@KeyGenActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        card.addView(btnPushGithub)

        col.addView(card)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: KEY VERIFIER & GENERATED HISTORY UI
    // =========================================================================
    private fun buildVerifyAndHistoryTab(): ScrollView {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(32f))
        }

        val verifyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 20f, "#334155", 1.5f)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14f)) }
        }

        val vTitle = TextView(this).apply {
            text = tr("🔍 فحص وكشف بيانات أي مفتاح ترخيص", "🔍 Verify & Inspect Any License Key")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8f))
        }
        verifyCard.addView(vTitle)

        val edtCheckKey = EditText(this).apply {
            hint = "JDRV-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            background = roundedBg("#0F172A", 12f, "#475569", 1f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        verifyCard.addView(edtCheckKey)

        val tvVerifyResult = TextView(this).apply {
            text = tr("أدخل المفتاح أعلاه واضغط على فحص.", "Enter a key above and tap Verify.")
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(10f), 0, dp(10f))
        }

        val btnVerify = TextView(this).apply {
            text = tr("🔍 فحص المفتاح الآن", "🔍 Verify Key Now")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#4F46E5", 14f)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10f), 0, 0) }
            setOnClickListener {
                val raw = decodeBase32Key(edtCheckKey.text.toString())
                if (raw == null) {
                    tvVerifyResult.text = tr("❌ صيغة المفتاح غير صحيحة", "❌ Invalid key format")
                    tvVerifyResult.setTextColor(Color.parseColor("#F87171"))
                    return@setOnClickListener
                }
                val mk = reconstructMasterKey()
                val enc12 = raw.copyOfRange(0, 12)
                val nonce2 = raw.copyOfRange(12, 14)
                val tag6 = raw.copyOfRange(14, 20)
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(mk, "HmacSHA256"))
                mac.update(enc12)
                mac.update(nonce2)
                val expTag = mac.doFinal().copyOfRange(0, 6)
                if (!MessageDigest.isEqual(tag6, expTag)) {
                    tvVerifyResult.text = tr("❌ التوقيع الرقمي للمفتاح غير صالح أو مزوّر!", "❌ Forged or invalid signature!")
                    tvVerifyResult.setTextColor(Color.parseColor("#F87171"))
                    return@setOnClickListener
                }
                val sha = MessageDigest.getInstance("SHA-256")
                sha.update(mk)
                sha.update(nonce2)
                val stream = sha.digest()
                val plain12 = ByteArray(12)
                for (i in 0 until 12) plain12[i] = (enc12[i].toInt() xor stream[i].toInt()).toByte()
                val devHash = ((plain12[0].toInt() and 0xFF) shl 24) or ((plain12[1].toInt() and 0xFF) shl 16) or ((plain12[2].toInt() and 0xFF) shl 8) or (plain12[3].toInt() and 0xFF)
                val expCode = ((plain12[4].toInt() and 0xFF) shl 24) or ((plain12[5].toInt() and 0xFF) shl 16) or ((plain12[6].toInt() and 0xFF) shl 8) or (plain12[7].toInt() and 0xFF)
                val epoch = plain12[8].toInt() and 0xFF
                val mask = plain12[9].toInt() and 0xFF
                val durStr = if (expCode == -1) tr("مدى الحياة ♾️", "Lifetime ♾️") else "${(expCode and 0x7FFFFFFF) / 1440} " + tr("يوم", "Days")
                val devStr = if (devHash == 0) tr("عام لأي جهاز (*)", "Universal (*)") else "0x" + Integer.toHexString(devHash).uppercase(Locale.US)
                tvVerifyResult.text = tr(
                    "✅ مفتاح أصلي سليم\n• ربط الجهاز: $devStr\n• المدة: $durStr\n• الإصدار (Epoch): $epoch\n• قناع الوظائف: $mask",
                    "✅ Valid Authentic Key\n• Device Bind: $devStr\n• Duration: $durStr\n• Epoch: $epoch\n• Feature Mask: $mask"
                )
                tvVerifyResult.setTextColor(Color.parseColor("#34D399"))
            }
        }
        verifyCard.addView(btnVerify)
        verifyCard.addView(tvVerifyResult)
        col.addView(verifyCard)

        val histCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 20f, "#334155", 1.5f)
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        val histTitle = TextView(this).apply {
            text = tr("📜 سجل آخر المفاتيح الصادرة:", "📜 Generated Keys History:")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10f))
        }
        histCard.addView(histTitle)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        histCard.addView(historyContainer)
        col.addView(histCard)

        scroll.addView(col)
        return scroll
    }

    private fun saveKeyToHistory(key: String, dev: String, dur: String) {
        val prefs = getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("history_json", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val newArr = JSONArray()
        val item = JSONObject().apply {
            put("key", key)
            put("dev", dev)
            put("dur", dur)
            put("time", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()))
        }
        newArr.put(item)
        for (i in 0 until arr.length()) {
            if (newArr.length() >= 30) break
            newArr.put(arr.optJSONObject(i))
        }
        prefs.edit().putString("history_json", newArr.toString()).commit()
    }

    private fun refreshHistoryUI() {
        if (!::historyContainer.isInitialized) return
        historyContainer.removeAllViews()
        val prefs = getSharedPreferences("jdrv_keygen_admin_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("history_json", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        if (arr.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = tr("لا توجد مفاتيح مسجلة بعد.", "No keys generated yet.")
                textSize = 12.5f
                setTextColor(Color.parseColor("#64748B"))
            }
            historyContainer.addView(emptyTv)
            return
        }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val k = obj.optString("key", "")
            val d = obj.optString("dev", "*")
            val dur = obj.optString("dur", "")
            val tm = obj.optString("time", "")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#0F172A", 12f, "#334155", 1f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8f)) }
                setOnClickListener {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("J-Driver Key", k))
                    Toast.makeText(this@KeyGenActivity, tr("✅ تم نسخ المفتاح", "✅ Copied key"), Toast.LENGTH_SHORT).show()
                }
            }
            val tvK = TextView(this).apply {
                text = k
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#FBBF24"))
            }
            val tvMeta = TextView(this).apply {
                text = "$d  •  $dur  •  $tm"
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            row.addView(tvK)
            row.addView(tvMeta)
            historyContainer.addView(row)
        }
    }
}