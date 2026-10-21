package dev.saned.assistant.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import dev.saned.assistant.NotificationHelper
import dev.saned.assistant.OrderModel
import dev.saned.assistant.SettingsStore
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private var pendingRestoreTab: Int = -1
    }

    // Strict Single-Language State ("ar" = 100% pure Arabic, "en" = 100% pure English)
    private var isAr: Boolean = true
    private fun tr(ar: String, en: String): String = if (isAr) ar else en

    // Tabs
    private lateinit var tabOrdersView: ScrollView
    private lateinit var tabMapView: ScrollView
    private lateinit var tabSettingsView: ScrollView
    private lateinit var tabLogsView: ScrollView
    private lateinit var navMapBtn: LinearLayout
    private lateinit var navMapIconBg: TextView
    private lateinit var navMapLabel: TextView
    private lateinit var navLogsBtn: LinearLayout
    private lateinit var navLogsIconBg: TextView
    private lateinit var navLogsLabel: TextView
    private lateinit var tvDiagLogConsole: TextView
    private lateinit var tvDiagLogSummary: TextView
    private var filterErrorsOnly: Boolean = false
    private lateinit var chkHookEarlyCloaker: CheckBox
    private lateinit var chkHookSecCloaker: CheckBox
    private lateinit var chkHookLocation: CheckBox
    private lateinit var chkHookDeviceSpoofer: CheckBox
    private lateinit var chkHookOrders: CheckBox
    private lateinit var chkHookBootstrapLoop: CheckBox
    private lateinit var navOrdersBtn: LinearLayout
    private lateinit var navSettingsBtn: LinearLayout
    private lateinit var navOrdersIconBg: TextView
    private lateinit var navSettingsIconBg: TextView
    private lateinit var navOrdersLabel: TextView
    private lateinit var navSettingsLabel: TextView
    private var currentTab = 0 // 0 = Orders, 1 = Settings, 2 = Logs, 3 = Map

    // Orders Tab Containers
    private lateinit var activeOrdersContainer: LinearLayout
    private lateinit var missedSectionHeader: LinearLayout
    private lateinit var tvMissedCountBadge: TextView
    private lateinit var missedOrdersContainer: LinearLayout
    private lateinit var tvOrdersEmptyState: TextView
    private lateinit var tvOrdersSubStatus: TextView

    // Connection & Ping Status
    private lateinit var tvPingStatus: TextView
    private lateinit var tvPingDetails: TextView
    private var lastPongTimestamp: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiBgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "Saned-UI-Worker").apply { isDaemon = true }
    }
    private var lastRenderedOrdersSignature: String = ""
    private var lastRenderedLogsSignature: String = ""
    private var lastReceivedBatchHash: Int = 0

    // OkHttp & Diagnostics UI
    private lateinit var tvOkHttpHookStatus: TextView
    private lateinit var tvHttpRequestsCount: TextView
    private lateinit var tvPollingHits: TextView
    private lateinit var tvLastHttpUrl: TextView
    private lateinit var tvLastOrderEvent: TextView

    // Master Switch
    private lateinit var swMaster: Switch
    private lateinit var tvMasterStatus: TextView

    // Android ID
    private lateinit var chkAndroidId: CheckBox
    private lateinit var edtAndroidId: EditText

    // Location & 2-Point Route Simulator
    private lateinit var chkFakeLoc: CheckBox
    private lateinit var chkFixLoc: CheckBox
    private lateinit var edtLat: EditText
    private lateinit var edtLng: EditText
    private lateinit var mapWebView: WebView
    private lateinit var tvRouteSimStatus: TextView
    private lateinit var tvRouteMetricsBadge: TextView
    private lateinit var tvTwoPointSummary: TextView
    private lateinit var btnStartRouteSim: Button
    private lateinit var btnPauseRouteSim: Button
    private lateinit var btnStopRouteSim: Button
    private lateinit var btnModeTeleport: Button
    private lateinit var btnModePoint1: Button
    private lateinit var btnModePoint2: Button
    private lateinit var edtMapSearch: EditText

    // 2-Point Route Simulator Engine State (Point 1 -> Point 2 with Variable Speeds & Custom Speed)
    private val routeWaypoints = mutableListOf<Pair<Double, Double>>()
    private var routeCumulativeMeters = DoubleArray(0)
    private var routeTotalMeters: Double = 0.0
    private var routeTraveledMeters: Double = 0.0
    private var routeSimSpeedKmh: Float = 45.0f
    private var isVariableSpeedMode: Boolean = true
    private var routeSimMinSpeedKmh: Float = 30.0f
    private var routeSimMaxSpeedKmh: Float = 75.0f
    private var routeSimCurrentDynamicSpeedKmh: Float = 45.0f
    private var routeTickCounter: Int = 0
    private var isRouteSimRunning: Boolean = false
    private var isRouteSimPaused: Boolean = false
    private var isRouteSimLooping: Boolean = false
    private var isRouteSimForward: Boolean = true
    private var mapTapMode: String = "point2" // "teleport", "point1", "point2"
    private var point1Lat: Double = 24.803508
    private var point1Lng: Double = 46.604578
    private var point2Lat: Double = 24.812500
    private var point2Lng: Double = 46.615200

    // Auto Accept & Filters
    private lateinit var chkAutoAccept: CheckBox
    private lateinit var chkAutoReject: CheckBox
    private lateinit var chkDryRun: CheckBox
    private lateinit var chkStayInBackground: CheckBox
    private lateinit var chkSilenceAlertTone: CheckBox
    private lateinit var chkSoundAlert: CheckBox
    private lateinit var chkStrictVersionCheck: CheckBox
    private lateinit var edtMinPrice: EditText
    private lateinit var edtMaxDistRest: EditText
    private lateinit var edtMaxDistCust: EditText
    private lateinit var btnExtraOrder: Button
    private var isExtraOrderEnabled: Boolean = false

    // Active Server Polling & Parallel Acceptance
    private lateinit var chkActivePolling: CheckBox
    private lateinit var edtPollInterval: EditText
    private lateinit var edtParallelRequests: EditText

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPing()
            checkPingTimeout()
            mainHandler.postDelayed(this, 2500)
        }
    }

    private fun roundedBg(colorHex: String, cornerRadiusDp: Float, strokeColorHex: String? = null, strokeWidthPx: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * resources.displayMetrics.density
            setColor(Color.parseColor(colorHex))
            if (strokeColorHex != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, Color.parseColor(strokeColorHex))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAr = SettingsStore.getAppLanguage(this) != "en"
        window.decorView.layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        routeSimSpeedKmh = SettingsStore.getRouteSimSpeedKmh(this)
        isVariableSpeedMode = SettingsStore.isVariableSpeedEnabled(this)
        routeSimMinSpeedKmh = SettingsStore.getRouteSimMinSpeedKmh(this)
        routeSimMaxSpeedKmh = SettingsStore.getRouteSimMaxSpeedKmh(this)
        routeSimCurrentDynamicSpeedKmh = routeSimSpeedKmh
        point1Lat = SettingsStore.getPoint1Lat(this).takeIf { it != 0.0 } ?: SettingsStore.getFakeLat(this).takeIf { it != 0.0 } ?: 24.803508
        point1Lng = SettingsStore.getPoint1Lng(this).takeIf { it != 0.0 } ?: SettingsStore.getFakeLng(this).takeIf { it != 0.0 } ?: 46.604578
        point2Lat = SettingsStore.getPoint2Lat(this).takeIf { it != 0.0 } ?: 24.812500
        point2Lng = SettingsStore.getPoint2Lng(this).takeIf { it != 0.0 } ?: 46.615200

        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (isAr) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Color.parseColor("#F8F7FC"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ================= TOP BAR =================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(36, 36, 36, 28)
            elevation = 4f
        }

        val appTitle = TextView(this).apply {
            text = tr("مساعد ساند", "Saned Assistant")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(appTitle)

        val btnTopLogs = TextView(this).apply {
            text = "📋"
            textSize = 18f
            setPadding(20, 10, 20, 10)
            setOnClickListener { switchTab(2) }
        }
        topBar.addView(btnTopLogs)

        val btnTopSim = TextView(this).apply {
            text = "⚡"
            textSize = 18f
            setPadding(20, 10, 20, 10)
            setOnClickListener { sendSimulateOrder() }
        }
        topBar.addView(btnTopSim)

        val btnClearTop = TextView(this).apply {
            text = "🗑️"
            textSize = 18f
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                SettingsStore.clearOrdersLog(this@MainActivity)
                lastRenderedOrdersSignature = ""
                refreshOrdersLogUI(forceRebuild = true)
                Toast.makeText(
                    this@MainActivity,
                    tr("تم مسح قائمة الطلبات", "Orders list cleared"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        topBar.addView(btnClearTop)

        val btnRefreshTop = TextView(this).apply {
            text = "🌐"
            textSize = 18f
            setPadding(20, 10, 10, 10)
            setOnClickListener {
                sendPing()
                lastRenderedOrdersSignature = ""
                refreshOrdersLogUI(forceRebuild = true)
                Toast.makeText(
                    this@MainActivity,
                    tr("تم تحديث الطلبات وحالة الاتصال", "Orders & connection refreshed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        topBar.addView(btnRefreshTop)

        rootContainer.addView(topBar)

        // ================= CONTENT FRAME =================
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tabOrdersView = buildOrdersTabView()
        tabMapView = buildMapTabView()
        tabSettingsView = buildSettingsTabView()
        tabLogsView = buildLogsTabView()

        contentFrame.addView(tabOrdersView)
        contentFrame.addView(tabMapView)
        contentFrame.addView(tabSettingsView)
        contentFrame.addView(tabLogsView)
        rootContainer.addView(contentFrame)

        // ================= BOTTOM NAVIGATION BAR =================
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 20)
            elevation = 16f
        }

        navOrdersBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(0) }
        }
        navOrdersIconBg = TextView(this).apply {
            text = "🏠"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(42, 10, 42, 10)
        }
        navOrdersLabel = TextView(this).apply {
            text = tr("الطلبات", "Orders")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 0)
        }
        navOrdersBtn.addView(navOrdersIconBg)
        navOrdersBtn.addView(navOrdersLabel)

        navSettingsBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(1) }
        }
        navSettingsIconBg = TextView(this).apply {
            text = "⚙️"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(42, 10, 42, 10)
        }
        navSettingsLabel = TextView(this).apply {
            text = tr("الإعدادات", "Settings")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 0)
        }
        navSettingsBtn.addView(navSettingsIconBg)
        navSettingsBtn.addView(navSettingsLabel)

        navLogsBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(2) }
        }
        navLogsIconBg = TextView(this).apply {
            text = "📋"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(42, 10, 42, 10)
        }
        navLogsLabel = TextView(this).apply {
            text = tr("السجلات", "Logs")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 0)
        }
        navLogsBtn.addView(navLogsIconBg)
        navLogsBtn.addView(navLogsLabel)

        navMapBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(3) }
        }
        navMapIconBg = TextView(this).apply {
            text = "🗺️"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(36, 10, 36, 10)
        }
        navMapLabel = TextView(this).apply {
            text = tr("الخريطة والمسار", "Map & Route")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 0)
        }
        navMapBtn.addView(navMapIconBg)
        navMapBtn.addView(navMapLabel)

        bottomNav.addView(navOrdersBtn)
        bottomNav.addView(navMapBtn)
        bottomNav.addView(navSettingsBtn)
        bottomNav.addView(navLogsBtn)
        rootContainer.addView(bottomNav)

        setContentView(rootContainer)

        val initialTab = if (pendingRestoreTab in 0..3) {
            val t = pendingRestoreTab
            pendingRestoreTab = -1
            t
        } else {
            0
        }
        switchTab(initialTab)
        refreshOrdersLogUI()
    }

    private fun switchLanguage(newLang: String) {
        val current = SettingsStore.getAppLanguage(this)
        if (current == newLang) return
        SettingsStore.setAppLanguage(this, newLang)
        pendingRestoreTab = currentTab
        recreate()
    }

    private fun switchTab(tabIndex: Int) {
        currentTab = tabIndex
        tabOrdersView.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        tabSettingsView.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        tabLogsView.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        tabMapView.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        navOrdersIconBg.background = if (tabIndex == 0) roundedBg("#EDE9FE", 20f) else null
        navSettingsIconBg.background = if (tabIndex == 1) roundedBg("#EDE9FE", 20f) else null
        navLogsIconBg.background = if (tabIndex == 2) roundedBg("#EDE9FE", 20f) else null
        navMapIconBg.background = if (tabIndex == 3) roundedBg("#EDE9FE", 20f) else null

        navOrdersLabel.setTextColor(Color.parseColor(if (tabIndex == 0) "#4C1D95" else "#64748B"))
        navSettingsLabel.setTextColor(Color.parseColor(if (tabIndex == 1) "#4C1D95" else "#64748B"))
        navLogsLabel.setTextColor(Color.parseColor(if (tabIndex == 2) "#4C1D95" else "#64748B"))
        navMapLabel.setTextColor(Color.parseColor(if (tabIndex == 3) "#4C1D95" else "#64748B"))

        if (tabIndex == 0) refreshOrdersLogUI()
        if (tabIndex == 2) refreshDiagLogsUI(force = true)
        if (tabIndex == 3 && ::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.invalidateMapSize) window.invalidateMapSize();", null)
        }
    }

    // =========================================================================
    // TAB 2: DIAGNOSTIC LOGS TAB (100% Pure Selected Language)
    // =========================================================================
    private fun buildLogsTabView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 60)
        }

        val logCard = createStyledCard()
        val logLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = tr("📋 نافذة السجل التشخيصي ورصد الأعطال", "📋 Live Diagnostic & Crash Logs")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        logLayout.addView(title)

        tvDiagLogSummary = TextView(this).apply {
            text = tr(
                "جاهز لالتقاط جميع الأحداث والأخطاء فور فتح تطبيق ساند...",
                "Ready to capture all events and errors as soon as the target app opens..."
            )
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, 0, 0, 12)
        }
        logLayout.addView(tvDiagLogSummary)

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }

        val btnFilterToggle = Button(this).apply {
            text = tr("🔎 الكل / الأخطاء فقط", "🔎 All / Errors Only")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                filterErrorsOnly = !filterErrorsOnly
                text = if (filterErrorsOnly) {
                    tr("🚨 الأخطاء والأعطال فقط", "🚨 Errors & Crashes Only")
                } else {
                    tr("🔎 عرض كل السجلات", "🔎 Show All Logs")
                }
                refreshDiagLogsUI(force = true)
            }
        }
        btnRow1.addView(btnFilterToggle)

        val btnCopyLogs = Button(this).apply {
            text = tr("📋 نسخ السجل", "📋 Copy Logs")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val fullLogs = SettingsStore.getDiagLogs(this@MainActivity)
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(
                    ClipData.newPlainText(
                        tr("سجل التشخيص", "Diagnostic Logs"),
                        fullLogs.ifEmpty { tr("لا توجد سجلات بعد", "No logs yet") }
                    )
                )
                Toast.makeText(
                    this@MainActivity,
                    tr("✅ تم نسخ السجل التشخيصي بالكامل إلى الحافظة", "✅ Diagnostic logs copied to clipboard"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnRow1.addView(btnCopyLogs)

        val btnClearLogs = Button(this).apply {
            text = tr("🗑️ مسح", "🗑️ Clear")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply {
                setMargins(8, 0, 0, 0)
            }
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                SettingsStore.clearDiagLogs(this@MainActivity)
                refreshDiagLogsUI()
                Toast.makeText(
                    this@MainActivity,
                    tr("تم مسح السجل", "Logs cleared"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnRow1.addView(btnClearLogs)
        logLayout.addView(btnRow1)

        val hScroll = HorizontalScrollView(this).apply {
            background = roundedBg("#0F172A", 14f, "#334155", 2)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        tvDiagLogConsole = TextView(this).apply {
            text = tr(
                "لا توجد سجلات بعد.\nافتح تطبيق ساند الآن وسيظهر هنا كل حدث يتم تفعيله مع تفاصيل الطلبات والأخطاء بالكامل.",
                "No logs recorded yet.\nOpen the target app now and every active hook, version detail, and stacktrace will appear here."
            )
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextIsSelectable(true)
            setLineSpacing(6f, 1.0f)
        }
        hScroll.addView(tvDiagLogConsole)
        logLayout.addView(hScroll)
        logCard.addView(logLayout)
        container.addView(logCard)

        // Card 2: Per-Hook Group Isolation Toggles (Pure Arabic / Pure English)
        val isoCard = createStyledCard()
        val isoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val isoTitle = TextView(this).apply {
            text = tr("🛠️ التحكم في وحدات النظام فردياً", "🛠️ Individual Module Isolation")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        isoLayout.addView(isoTitle)

        val isoSub = TextView(this).apply {
            text = tr(
                "يمكنك إيقاف أو تشغيل أي وحدة نظام على حدة ثم إعادة فتح التطبيق لتجربة كل ميزة بشكل مستقل:",
                "You can enable or disable any module individually and restart the target app to test each feature independently:"
            )
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        isoLayout.addView(isoSub)

        chkHookEarlyCloaker = CheckBox(this).apply {
            text = tr("1. حماية المكتبات الأصلية المبكرة", "1. Early Native Library Cloaker")
            isChecked = SettingsStore.isHookEarlyCloaker(this@MainActivity)
        }
        isoLayout.addView(chkHookEarlyCloaker)

        chkHookSecCloaker = CheckBox(this).apply {
            text = tr("2. إخفاء الروت وأدوات الفحص الأمني", "2. Root & Security Detection Cloaker")
            isChecked = SettingsStore.isHookSecCloaker(this@MainActivity)
        }
        isoLayout.addView(chkHookSecCloaker)

        chkHookLocation = CheckBox(this).apply {
            text = tr("3. محرك الموقع المزيف ومحاكاة المسار", "3. Fake Location & Route Simulator Module")
            isChecked = SettingsStore.isHookLocation(this@MainActivity)
        }
        isoLayout.addView(chkHookLocation)

        chkHookDeviceSpoofer = CheckBox(this).apply {
            text = tr("4. تغيير بصمة ومعرف الجهاز", "4. Device Identifier Spoofer Module")
            isChecked = SettingsStore.isHookDeviceSpoofer(this@MainActivity)
        }
        isoLayout.addView(chkHookDeviceSpoofer)

        chkHookOrders = CheckBox(this).apply {
            text = tr("5. اعتراض وقبول الطلبات الفوري", "5. Instant Order Interceptor Module")
            isChecked = SettingsStore.isHookOrders(this@MainActivity)
        }
        isoLayout.addView(chkHookOrders)

        chkHookBootstrapLoop = CheckBox(this).apply {
            text = tr("6. فحص السيرفر وقاعدة البيانات في الخلفية", "6. Background Database & Server Polling Module")
            isChecked = SettingsStore.isHookBootstrapLoop(this@MainActivity)
        }
        isoLayout.addView(chkHookBootstrapLoop)

        val btnSaveIso = Button(this).apply {
            text = tr("💾 حفظ إعدادات الوحدات وتطبيقها", "💾 Save & Apply Module Settings")
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                saveAllSettings()
                Toast.makeText(
                    this@MainActivity,
                    tr("✅ تم حفظ إعدادات الوحدات بنجاح", "✅ Module settings saved and applied"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        isoLayout.addView(btnSaveIso)

        isoCard.addView(isoLayout)
        container.addView(isoCard)

        scroll.addView(container)
        return scroll
    }

    private fun refreshDiagLogsUI(force: Boolean = false) {
        if (!::tvDiagLogConsole.isInitialized) return
        if (!force && currentTab != 2) return
        val rawLogs = SettingsStore.getDiagLogs(this)
        val logSig = "${rawLogs.hashCode()}:${rawLogs.length}:$filterErrorsOnly:$isAr"
        if (!force && logSig == lastRenderedLogsSignature) return
        lastRenderedLogsSignature = logSig
        if (rawLogs.isBlank()) {
            tvDiagLogSummary.text = tr(
                "🟢 لا توجد أخطاء مسجلة — افتح تطبيق ساند لبدء التسجيل المباشر",
                "🟢 No errors recorded — Open the target app to start live logging"
            )
            tvDiagLogSummary.setTextColor(Color.parseColor("#059669"))
            tvDiagLogConsole.text = tr(
                "لا توجد سجلات بعد.\nافتح تطبيق ساند الآن وسيظهر هنا كل حدث يتم تفعيله مع تفاصيل الطلبات والأخطاء بالكامل.",
                "No logs recorded yet.\nOpen the target app now and every active hook, version detail, and stacktrace will appear here."
            )
            tvDiagLogConsole.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        val lines = rawLogs.lines()
        val crashCount = lines.count { it.contains("[CRASH]") }
        val errCount = lines.count { it.contains("[ERR]") }
        val warnCount = lines.count { it.contains("[WARN]") }

        if (crashCount > 0 || errCount > 0) {
            tvDiagLogSummary.text = tr(
                "🚨 تم رصد $crashCount انهيار و $errCount خطأ و $warnCount تنبيه (إجمالي ${lines.size} سطر)",
                "🚨 Detected $crashCount crashes, $errCount errors, and $warnCount warnings (Total ${lines.size} lines)"
            )
            tvDiagLogSummary.setTextColor(Color.parseColor("#DC2626"))
        } else {
            tvDiagLogSummary.text = tr(
                "✅ السجل يعمل بنجاح (${lines.size} سطر | $warnCount تنبيه | 0 أعطال)",
                "✅ Logger running cleanly (${lines.size} lines | $warnCount warnings | 0 crashes)"
            )
            tvDiagLogSummary.setTextColor(Color.parseColor("#059669"))
        }

        val displayText = if (filterErrorsOnly) {
            val filtered = mutableListOf<String>()
            var includeContinuation = false
            for (line in lines) {
                if (line.startsWith("[")) {
                    includeContinuation = line.contains("[CRASH]") || line.contains("[ERR]") || line.contains("[WARN]")
                }
                if (includeContinuation) filtered.add(line)
            }
            if (filtered.isEmpty()) {
                tr("لا توجد أخطاء أو أعطال في السجل الحالي ✅", "No errors or crashes in current log ✅")
            } else {
                filtered.joinToString("\n")
            }
        } else {
            rawLogs
        }

        tvDiagLogConsole.text = displayText
        tvDiagLogConsole.setTextColor(
            if (crashCount > 0) Color.parseColor("#FCA5A5") else Color.parseColor("#38BDF8")
        )
    }

    // =========================================================================
    // TAB 0: ORDERS TAB (100% Pure Selected Language)
    // =========================================================================
    private fun buildOrdersTabView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8F7FC"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 48)
        }

        val statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#FFFFFF", 14f, "#E2E8F0", 2)
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        tvOrdersSubStatus = TextView(this).apply {
            val isRun = SettingsStore.isMasterRunning(this@MainActivity)
            val isAcc = SettingsStore.isAutoAccept(this@MainActivity)
            val isRej = SettingsStore.isAutoReject(this@MainActivity)
            text = if (isRun) {
                tr(
                    "🟢 النظام نشط  •  القبول التلقائي: ${if (isAcc) "مفعل" else "متوقف"}  •  الرفض التلقائي: ${if (isRej) "مفعل" else "متوقف"}",
                    "🟢 Interceptor Active  •  Auto-Accept: ${if (isAcc) "ON" else "OFF"}  •  Auto-Reject: ${if (isRej) "ON" else "OFF"}"
                )
            } else {
                tr(
                    "🔴 المفتاح الرئيسي متوقف (يستمر تسجيل وعرض الطلبات بالأسفل)",
                    "🔴 Master Switch OFF (Orders are still logged below)"
                )
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isRun) Color.parseColor("#059669") else Color.parseColor("#D97706"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusStrip.addView(tvOrdersSubStatus)
        container.addView(statusStrip)

        tvOrdersEmptyState = TextView(this).apply {
            text = tr(
                "لم يتم استلام أي طلبات بعد.\n\nكل طلب يظهر في التطبيق (سواء تم قبوله أو رفضه حسب المسافة والسعر أو يدوياً) سيظهر هنا فوراً بكامل تفاصيله.",
                "No orders received yet.\n\nEvery order that appears in the app (whether Accepted, Rejected by distance/price, or Manual) will immediately appear here with full details."
            )
            textSize = 13.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(32, 80, 32, 80)
            visibility = View.GONE
        }
        container.addView(tvOrdersEmptyState)

        activeOrdersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(activeOrdersContainer)

        missedSectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 24, 8, 16)
            visibility = View.GONE
        }
        val tvMissedTitle = TextView(this).apply {
            text = tr("الطلبات الفائتة والمرفوضة", "MISSED & REJECTED")
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
            letterSpacing = 0.04f
        }
        tvMissedCountBadge = TextView(this).apply {
            text = "0"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4C1D95"))
            background = roundedBg("#EDE9FE", 12f)
            setPadding(18, 4, 18, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 16, 0)
            }
        }
        missedSectionHeader.addView(tvMissedTitle)
        missedSectionHeader.addView(tvMissedCountBadge)
        container.addView(missedSectionHeader)

        missedOrdersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(missedOrdersContainer)

        scroll.addView(container)
        return scroll
    }

    // =========================================================================
    // TAB 1: SETTINGS TAB (Language Card + All Configuration Cards)
    // =========================================================================
    private fun buildSettingsTabView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 60)
        }

        mainLayout.addView(createLanguageCard())
        mainLayout.addView(createConnectionPingCard())
        mainLayout.addView(createDiagnosticsCard())
        mainLayout.addView(createMasterSwitchCard())
        mainLayout.addView(createFiltersCard())
        mainLayout.addView(createPollingCard())
        mainLayout.addView(createAndroidIdCard())
        mainLayout.addView(createLocationShortcutCard())

        val btnSave = Button(this).apply {
            text = tr("💾 حفظ وتطبيق جميع الإعدادات", "💾 Save & Apply All Settings")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setPadding(24, 28, 24, 28)
            setOnClickListener { saveAllSettings() }
        }
        mainLayout.addView(btnSave)

        scroll.addView(mainLayout)
        return scroll
    }

    private fun createLanguageCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = tr("🌐 لغة التطبيق", "🌐 Application Language")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = tr(
                "اختر لغة واجهة التطبيق بالكامل (عربي خالص أو إنجليزي خالص بدون دمج بين اللغتين):",
                "Select the application interface language (pure Arabic or pure English without mixing languages):"
            )
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(desc)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnArabic = Button(this).apply {
            text = if (isAr) "✅ العربية" else "العربية"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor(if (isAr) "#4F46E5" else "#E2E8F0"))
            setTextColor(if (isAr) Color.WHITE else Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                switchLanguage("ar")
            }
        }
        btnRow.addView(btnArabic)

        val btnEnglish = Button(this).apply {
            text = if (!isAr) "✅ English" else "English"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor(if (!isAr) "#4F46E5" else "#E2E8F0"))
            setTextColor(if (!isAr) Color.WHITE else Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                switchLanguage("en")
            }
        }
        btnRow.addView(btnEnglish)

        layout.addView(btnRow)
        card.addView(layout)
        return card
    }

    // =========================================================================
    // RENDER ORDERS IN ORDERS TAB
    // =========================================================================
    private fun isSameOrderUi(a: OrderModel, b: OrderModel): Boolean {
        val idA = if (a.id != 0) a.id.toString() else ""
        val idB = if (b.id != 0) b.id.toString() else ""
        val refA = a.reference.replace("#", "").trim()
        val refB = b.reference.replace("#", "").trim()
        if (refA.isNotEmpty() && (refA == refB || refA == idB)) return true
        if (idA.isNotEmpty() && (idA == idB || idA == refB)) return true
        return false
    }

    private fun mergeOrderModelsUi(primary: OrderModel, secondary: OrderModel): OrderModel {
        val idStr = if (primary.id != 0) primary.id.toString() else if (secondary.id != 0) secondary.id.toString() else ""
        val refA = primary.reference.replace("#", "").trim()
        val refB = secondary.reference.replace("#", "").trim()
        val bestRef = when {
            refA.length >= 10 && refA != idStr -> refA
            refB.length >= 10 && refB != idStr -> refB
            refA.isNotEmpty() && refA != idStr -> refA
            refB.isNotEmpty() && refB != idStr -> refB
            refA.isNotEmpty() -> refA
            else -> refB
        }
        val bestAddr = when {
            primary.address.isNotEmpty() && primary.address != "Location on map" -> primary.address
            secondary.address.isNotEmpty() && secondary.address != "Location on map" -> secondary.address
            primary.address.isNotEmpty() -> primary.address
            else -> secondary.address
        }
        val bestCash = if (primary.cashAmount > 0.0) primary.cashAmount else secondary.cashAmount
        val bestFood = when {
            primary.foodPrice > 0.0 -> primary.foodPrice
            secondary.foodPrice > 0.0 -> secondary.foodPrice
            bestCash > 0.0 -> bestCash
            else -> 0.0
        }
        val bestStatus = when {
            primary.status.equals("Dispatched", ignoreCase = true) -> "Accepted ✅"
            secondary.status.equals("Dispatched", ignoreCase = true) && primary.status.isEmpty() -> "Accepted ✅"
            primary.status.equals("Accepted", ignoreCase = true) && secondary.status.contains("✅") -> secondary.status
            else -> primary.status.ifEmpty { secondary.status }
        }
        return primary.copy(
            id = if (primary.id != 0) primary.id else secondary.id,
            reference = bestRef,
            status = bestStatus,
            restaurant = primary.restaurant.ifEmpty { secondary.restaurant },
            pickupAddress = primary.pickupAddress.ifEmpty { secondary.pickupAddress },
            customerName = primary.customerName.ifEmpty { secondary.customerName },
            customerPhone = primary.customerPhone.ifEmpty { secondary.customerPhone },
            address = bestAddr,
            price = if (primary.price > 0.0) primary.price else secondary.price,
            cashAmount = bestCash,
            foodPrice = bestFood,
            paymentMethod = primary.paymentMethod.ifEmpty { secondary.paymentMethod },
            distanceKm = if (primary.distanceKm > 0.0) primary.distanceKm else secondary.distanceKm,
            customerDistanceKm = if (primary.customerDistanceKm > 0.0) primary.customerDistanceKm else secondary.customerDistanceKm,
            pickupLat = if (primary.pickupLat != 0.0) primary.pickupLat else secondary.pickupLat,
            pickupLng = if (primary.pickupLng != 0.0) primary.pickupLng else secondary.pickupLng,
            destLat = if (primary.destLat != 0.0) primary.destLat else secondary.destLat,
            destLng = if (primary.destLng != 0.0) primary.destLng else secondary.destLng
        )
    }

    private fun requestOrderDetailsRefresh(order: OrderModel) {
        val rawId = if (order.id != 0) order.id.toString() else order.reference.replace("#", "").trim()
        val rawRef = order.reference.replace("#", "").trim()
        try {
            val intent = Intent("dev.saned.assistant.ACTION_REFRESH_ORDER_DETAILS").apply {
                setPackage("net.jahez.fleets")
                putExtra("order_id", rawId)
                putExtra("reference", rawRef)
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}
        try {
            val gIntent = Intent("dev.saned.assistant.ACTION_REFRESH_ORDER_DETAILS").apply {
                putExtra("order_id", rawId)
                putExtra("reference", rawRef)
            }
            sendBroadcast(gIntent)
        } catch (_: Throwable) {}
        sendPing()
    }

    private fun localizeStatusText(rawStatus: String): String {
        val s = rawStatus.trim()
        if (!isAr) {
            return if (s.equals("Dispatched", ignoreCase = true) || s.isEmpty()) "Accepted ✅" else s
        }
        val low = s.lowercase(Locale.US)
        return when {
            low.contains("picked") || low.contains("استلام") -> "تم الاستلام 📦"
            low.contains("deliver") || low.contains("توصيل") -> "تم التوصيل 🏁"
            low.contains("accept") || low.contains("dispatched") || s.isEmpty() -> "مقبول ✅"
            low.contains("reject") || low.contains("رفض") -> "مرفوض ❌"
            low.contains("dry") -> "وضع التجربة 🧪"
            else -> s
        }
    }

    private fun refreshOrdersLogUI(forceRebuild: Boolean = false) {
        if (!::activeOrdersContainer.isInitialized) return

        if (::tvOrdersSubStatus.isInitialized) {
            val isRun = SettingsStore.isMasterRunning(this)
            val isAcc = SettingsStore.isAutoAccept(this)
            val isRej = SettingsStore.isAutoReject(this)
            val maxRest = SettingsStore.getMaxDistRest(this)
            val maxCust = SettingsStore.getMaxDistCust(this)
            val minP = SettingsStore.getMinPrice(this)
            val maxOrdCount = SettingsStore.getMaxOrdersLimit(this)
            tvOrdersSubStatus.text = if (isRun) {
                tr(
                    "🟢 نشط • القبول: ${if (isAcc) "مفعل" else "متوقف"} • الرفض: ${if (isRej) "مفعل" else "متوقف"} • الطلبات: $maxOrdCount • المطعم≤${maxRest}كم • العميل≤${maxCust}كم • الأدنى≥${minP}",
                    "🟢 Active • Auto-Accept: ${if (isAcc) "ON" else "OFF"} • Auto-Reject: ${if (isRej) "ON" else "OFF"} • Orders: $maxOrdCount • Rest≤${maxRest}km • Cust≤${maxCust}km • Min≥${minP}"
                )
            } else {
                tr(
                    "🔴 المفتاح الرئيسي متوقف (يستمر التقاط وعرض الطلبات الواردة بالأسفل)",
                    "🔴 Master Switch OFF (Incoming orders are still captured & shown below)"
                )
            }
            tvOrdersSubStatus.setTextColor(if (isRun) Color.parseColor("#059669") else Color.parseColor("#D97706"))
        }

        val jsonStr = SettingsStore.getOrdersLog(this)
        val currentSig = "$isAr:${jsonStr.hashCode()}:${jsonStr.length}"
        if (!forceRebuild && currentSig == lastRenderedOrdersSignature) {
            return
        }
        lastRenderedOrdersSignature = currentSig

        activeOrdersContainer.removeAllViews()
        missedOrdersContainer.removeAllViews()

        val array = try { JSONArray(jsonStr) } catch (_: Throwable) { JSONArray() }

        if (array.length() == 0) {
            tvOrdersEmptyState.visibility = View.VISIBLE
            missedSectionHeader.visibility = View.GONE
            return
        }

        val deduplicatedOrders = mutableListOf<OrderModel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val order = OrderModel.fromJson(item)
            val existingIdx = deduplicatedOrders.indexOfFirst { isSameOrderUi(it, order) }
            if (existingIdx >= 0) {
                deduplicatedOrders[existingIdx] = mergeOrderModelsUi(deduplicatedOrders[existingIdx], order)
            } else {
                deduplicatedOrders.add(order)
            }
        }

        if (deduplicatedOrders.isEmpty()) {
            tvOrdersEmptyState.visibility = View.VISIBLE
            missedSectionHeader.visibility = View.GONE
            return
        }

        tvOrdersEmptyState.visibility = View.GONE

        val activeList = mutableListOf<OrderModel>()
        val missedList = mutableListOf<OrderModel>()

        for (order in deduplicatedOrders) {
            val st = order.status.lowercase(Locale.US)
            val isMissed = order.isMissedOrRejected ||
                st.contains("reject") ||
                st.contains("رفض") ||
                st.contains("fail") ||
                st.contains("missed") ||
                st.contains("manual") ||
                st.contains("dry-run") ||
                st.contains("dry run")

            if (isMissed) {
                missedList.add(order)
            } else {
                activeList.add(order)
            }
        }

        for (order in activeList) {
            activeOrdersContainer.addView(buildActiveOrderCard(order))
        }

        if (missedList.isNotEmpty()) {
            missedSectionHeader.visibility = View.VISIBLE
            tvMissedCountBadge.text = missedList.size.toString()
            for (order in missedList) {
                missedOrdersContainer.addView(buildMissedOrderCard(order))
            }
        } else {
            missedSectionHeader.visibility = View.GONE
        }
    }

    private fun openGoogleMapsNavigation(
        targetLat: Double,
        targetLng: Double,
        originLat: Double = 0.0,
        originLng: Double = 0.0,
        fallbackQuery: String = "",
        label: String = ""
    ) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
        if (targetLat != 0.0 && targetLng != 0.0) {
            val url = if (originLat != 0.0 && originLng != 0.0) {
                "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$targetLat,$targetLng&travelmode=driving"
            } else {
                "https://www.google.com/maps/dir/?api=1&destination=$targetLat,$targetLng&travelmode=driving"
            }
            io.github.libxposed.service.XposedProvider.appendDiagLog(
                this,
                "[$ts] [STEP] [NavLog] UI Navigate clicked [$label]: target=($targetLat,$targetLng) origin=($originLat,$originLng) url=$url"
            )
            try {
                val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(mapsIntent)
                return
            } catch (_: Throwable) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(browserIntent)
                    return
                } catch (_: Throwable) {}
            }
        }
        if (fallbackQuery.isNotEmpty() && fallbackQuery != "Location on map") {
            io.github.libxposed.service.XposedProvider.appendDiagLog(
                this,
                "[$ts] [STEP] [NavLog] UI Navigate fallback [$label]: query=$fallbackQuery"
            )
            try {
                val mapUri = Uri.parse("geo:0,0?q=${Uri.encode(fallbackQuery)}")
                startActivity(Intent(Intent.ACTION_VIEW, mapUri))
                return
            } catch (_: Throwable) {}
        }
        Toast.makeText(
            this,
            tr("⚠️ إحداثيات الموقع غير متوفرة بعد لهذا الطلب", "⚠️ Location coordinates are not available for this order yet"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun buildActiveOrderCard(order: OrderModel): CardView {
        val rawId = if (order.reference.isNotEmpty()) order.reference else order.id.toString()
        val displayId = if (rawId.startsWith("#")) rawId else "#$rawId"
        val showFoodPriceOpt = SettingsStore.isShowFoodPrice(this)
        val effectiveFoodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount
        val currLabel = if (isAr && order.currency.equals("SAR", ignoreCase = true)) "ر.س" else order.currency

        val card = CardView(this).apply {
            radius = 28f
            cardElevation = 3f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 28)
            }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }

        val statusBadge = TextView(this).apply {
            text = localizeStatusText(order.status)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            background = roundedBg("#EDE9FE", 16f)
            setPadding(24, 10, 24, 10)
        }
        headerRow.addView(statusBadge)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        headerRow.addView(spacer)

        val idCopyTv = TextView(this).apply {
            text = "$displayId 📋"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#334155"))
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Order ID", rawId.removePrefix("#")))
                Toast.makeText(
                    this@MainActivity,
                    tr("تم نسخ الرقم $displayId", "Copied $displayId"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        headerRow.addView(idCopyTv)
        inner.addView(headerRow)

        // 2. Pickup Box
        val pickupBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#F1F0F7", 18f)
            setPadding(24, 22, 24, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener {
                openGoogleMapsNavigation(
                    targetLat = order.pickupLat,
                    targetLng = order.pickupLng,
                    fallbackQuery = order.pickupAddress.ifEmpty { order.restaurant },
                    label = "Pickup ($displayId)"
                )
            }
        }

        val pickupIcon = TextView(this).apply {
            text = "🏪"
            textSize = 20f
            setPadding(0, 0, 24, 0)
        }
        pickupBox.addView(pickupIcon)

        val pickupTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickupLabel = TextView(this).apply {
            val distRestStr = if (order.distanceKm > 0.0) {
                if (isAr) {
                    String.format(Locale.US, " • يبعد %.1f كم عنك", order.distanceKm)
                } else {
                    String.format(Locale.US, " • %.1f km from you", order.distanceKm)
                }
            } else ""
            text = tr("الاستلام من المطعم$distRestStr", "Pickup$distRestStr")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
        }
        val pickupNameTv = TextView(this).apply {
            val restTitle = order.restaurant.ifEmpty { tr("المطعم", "Restaurant") }
            val pickAddr = if (order.pickupAddress.isNotEmpty() && order.pickupAddress != restTitle) "\n${order.pickupAddress}" else ""
            val pickCoordStr = if (order.pickupLat != 0.0 && order.pickupLng != 0.0) {
                String.format(Locale.US, "\n📍 %.5f, %.5f", order.pickupLat, order.pickupLng)
            } else ""
            text = "$restTitle$pickAddr$pickCoordStr"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            setPadding(0, 4, 0, 0)
        }
        pickupTextCol.addView(pickupLabel)
        pickupTextCol.addView(pickupNameTv)
        pickupBox.addView(pickupTextCol)

        val pickupNavBtn = TextView(this).apply {
            text = tr("اتجاهات ↗", "Navigate ↗")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#334155"))
            background = roundedBg("#E2E8F0", 12f)
            setPadding(18, 10, 18, 10)
        }
        pickupBox.addView(pickupNavBtn)
        inner.addView(pickupBox)

        // 3. Drop-off Box
        val dropoffBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#EFE9FE", 18f)
            setPadding(24, 22, 24, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener {
                openGoogleMapsNavigation(
                    targetLat = order.destLat,
                    targetLng = order.destLng,
                    originLat = order.pickupLat,
                    originLng = order.pickupLng,
                    fallbackQuery = order.address.takeIf { it != "Location on map" } ?: order.customerName,
                    label = "Customer Drop-off ($displayId)"
                )
            }
        }

        val dropoffIcon = TextView(this).apply {
            text = "📍"
            textSize = 18f
            gravity = Gravity.CENTER
            background = roundedBg("#5B3FD3", 24f)
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 24, 0)
            }
        }
        dropoffBox.addView(dropoffIcon)

        val dropoffTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dropoffHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dropoffLabel = TextView(this).apply {
            text = tr("التوصيل للعميل  ", "Drop-off  ")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
        }
        val nextBadge = TextView(this).apply {
            text = tr("التالي", "NEXT")
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#5B4378", 10f)
            setPadding(14, 3, 14, 3)
        }
        dropoffHeaderRow.addView(dropoffLabel)
        dropoffHeaderRow.addView(nextBadge)
        if (order.customerDistanceKm > 0.0) {
            val custDistTv = TextView(this).apply {
                text = if (isAr) {
                    String.format(Locale.US, "  •  %.1f كم من المطعم", order.customerDistanceKm)
                } else {
                    String.format(Locale.US, "  •  %.1f km from pickup", order.customerDistanceKm)
                }
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#4C1D95"))
            }
            dropoffHeaderRow.addView(custDistTv)
        }
        dropoffTextCol.addView(dropoffHeaderRow)

        val dropoffDetailTv = TextView(this).apply {
            val parts = mutableListOf<String>()
            if (order.customerName.isNotEmpty() && order.customerName != "Customer") parts.add(order.customerName)
            if (order.address.isNotEmpty() && order.address != "Location on map") parts.add(order.address)
            val hasDestCoords = order.destLat != 0.0 && order.destLng != 0.0
            val coordLine = if (hasDestCoords) {
                if (isAr) {
                    String.format(Locale.US, "📍 موقع العميل: %.5f, %.5f", order.destLat, order.destLng)
                } else {
                    String.format(Locale.US, "📍 Customer Location: %.5f, %.5f", order.destLat, order.destLng)
                }
            } else ""
            text = when {
                parts.isNotEmpty() && coordLine.isNotEmpty() -> "${parts.joinToString(" — ")}\n$coordLine"
                parts.isNotEmpty() -> parts.joinToString(" — ")
                coordLine.isNotEmpty() -> coordLine + "\n" + tr("اضغط لفتح موقع العميل في الخريطة", "Tap to open customer location in Maps")
                else -> tr("الموقع على الخريطة", "Location on map")
            }
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            setPadding(0, 6, 0, 0)
        }
        dropoffTextCol.addView(dropoffDetailTv)
        dropoffBox.addView(dropoffTextCol)

        val dropoffNavBtn = TextView(this).apply {
            text = tr("اتجاهات ↗", "Navigate ↗")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#5B3FD3", 12f)
            setPadding(18, 10, 18, 10)
        }
        dropoffBox.addView(dropoffNavBtn)
        inner.addView(dropoffBox)

        // 4. Payment & Food Price Box (100% pure Arabic or 100% pure English)
        val isCash = order.cashAmount > 0.0
        val paymentBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(if (isCash) "#FEF2F2" else "#F1F0F7", 18f)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 18)
            }
            setOnClickListener {
                requestOrderDetailsRefresh(order)
                Toast.makeText(
                    this@MainActivity,
                    tr("🔄 جاري تحديث سعر الطعام وتفاصيل الطلب $displayId...", "🔄 Refreshing food price & order details for $displayId..."),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val payIcon = TextView(this).apply {
            text = if (isCash) "💵" else "✔️"
            textSize = 16f
            setPadding(0, 0, 24, 0)
        }
        paymentBox.addView(payIcon)

        val payCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val payTitle = TextView(this).apply {
            text = if (isCash) {
                if (isAr) {
                    String.format(Locale.US, "الدفع عند الاستلام: %.2f %s", order.cashAmount, currLabel)
                } else {
                    String.format(Locale.US, "Cash on Delivery: %.2f %s", order.cashAmount, currLabel)
                }
            } else {
                tr("مدفوع مسبقاً", "Prepaid")
            }
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isCash) Color.parseColor("#B91C1C") else Color.parseColor("#1E1B2E"))
        }
        val paySub = TextView(this).apply {
            text = if (isCash) {
                if (isAr) {
                    String.format(Locale.US, "استلم %.2f %s نقداً من العميل", order.cashAmount, currLabel)
                } else {
                    String.format(Locale.US, "Collect %.2f %s from customer", order.cashAmount, currLabel)
                }
            } else {
                tr("لا يوجد مبلغ مطلوب تحصيله من العميل", "Nothing to collect from customer")
            }
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
        }
        payCol.addView(payTitle)
        payCol.addView(paySub)

        if (showFoodPriceOpt) {
            val foodPriceTv = TextView(this).apply {
                text = if (effectiveFoodPrice > 0.0) {
                    if (isAr) {
                        String.format(Locale.US, "🍔 سعر الطعام: %.2f %s", effectiveFoodPrice, currLabel)
                    } else {
                        String.format(Locale.US, "🍔 Food Price: %.2f %s", effectiveFoodPrice, currLabel)
                    }
                } else {
                    if (isAr) {
                        String.format(Locale.US, "🍔 سعر الطعام: 0.00 %s (مدفوع)", currLabel)
                    } else {
                        String.format(Locale.US, "🍔 Food Price: 0.00 %s (Prepaid)", currLabel)
                    }
                }
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (effectiveFoodPrice > 0.0) Color.parseColor("#B45309") else Color.parseColor("#0F766E"))
                setPadding(0, 6, 0, 0)
            }
            payCol.addView(foodPriceTv)
        }

        paymentBox.addView(payCol)

        val rightPricesCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        if (order.price > 0.0) {
            val priceBadge = TextView(this).apply {
                text = String.format(Locale.US, "🚗 %.2f %s", order.price, currLabel)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#059669"))
                background = roundedBg("#ECFDF5", 10f)
                setPadding(16, 6, 16, 6)
            }
            rightPricesCol.addView(priceBadge)
        }

        if (showFoodPriceOpt) {
            val foodBadge = TextView(this).apply {
                text = String.format(Locale.US, "🍔 %.2f %s", effectiveFoodPrice, currLabel)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (effectiveFoodPrice > 0.0) Color.parseColor("#B45309") else Color.parseColor("#475569"))
                background = roundedBg(if (effectiveFoodPrice > 0.0) "#FEF3C7" else "#E2E8F0", 10f)
                setPadding(16, 6, 16, 6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 0)
                }
            }
            rightPricesCol.addView(foodBadge)
        }

        paymentBox.addView(rightPricesCol)
        inner.addView(paymentBox)

        // 5. Call & WhatsApp Buttons Row
        val cleanPhone = NotificationHelper.formatPhoneForWhatsApp(order.customerPhone)
        if (cleanPhone.isNotEmpty()) {
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnCall = TextView(this).apply {
                text = tr("📞   اتصال", "📞   Call")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E1B2E"))
                background = roundedBg("#EDE9FE", 20f)
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 12, 0)
                }
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")))
                    } catch (_: Throwable) {
                        Toast.makeText(this@MainActivity, cleanPhone, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionsRow.addView(btnCall)

            val btnWhatsApp = TextView(this).apply {
                text = tr("💬   واتساب", "💬   WhatsApp")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E1B2E"))
                background = roundedBg("#EDE9FE", 20f)
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(12, 0, 0, 0)
                }
                setOnClickListener {
                    try {
                        val waUrl = "https://api.whatsapp.com/send?phone=$cleanPhone"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)))
                    } catch (_: Throwable) {
                        Toast.makeText(this@MainActivity, cleanPhone, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionsRow.addView(btnWhatsApp)

            inner.addView(actionsRow)
        }

        // 6. Quick 2-Point Route Simulator Button for this Order
        if ((order.pickupLat != 0.0 && order.pickupLng != 0.0) || (order.destLat != 0.0 && order.destLng != 0.0)) {
            val btnSimOrderRoute = TextView(this).apply {
                text = tr("🚗  محاكاة مسار الطريق لهذا الطلب من نقطة 1 إلى نقطة 2", "🚗  Simulate 2-Point Street Route for This Order")
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedBg("#2563EB", 18f)
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 14, 0, 0)
                }
                setOnClickListener {
                    showOrderRouteQuickPickerDialog(order)
                }
            }
            inner.addView(btnSimOrderRoute)
        }

        card.addView(inner)
        return card
    }

    private fun buildMissedOrderCard(order: OrderModel): CardView {
        val showFoodPriceOpt = SettingsStore.isShowFoodPrice(this)
        val effectiveFoodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount
        val currLabel = if (isAr && order.currency.equals("SAR", ignoreCase = true)) "ر.س" else order.currency

        val card = CardView(this).apply {
            radius = 22f
            cardElevation = 2f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 18)
            }
        }

        val outerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val storeIcon = TextView(this).apply {
            text = "🏪"
            textSize = 18f
            gravity = Gravity.CENTER
            background = roundedBg("#F1F0F7", 24f)
            setPadding(20, 18, 20, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 20, 0)
            }
        }
        row.addView(storeIcon)

        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val restNameTv = TextView(this).apply {
            text = order.restaurant.ifEmpty { tr("مطعم", "Restaurant") }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
        }
        centerCol.addView(restNameTv)

        val statusLineTv = TextView(this).apply {
            val reasonText = if (order.decisionReason.isNotEmpty()) order.decisionReason else localizeStatusText(order.status)
            text = "⚠️ $reasonText  •  ${order.time.ifEmpty { tr("الآن", "just now") }}"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B91C1C"))
            setPadding(0, 4, 0, 2)
        }
        centerCol.addView(statusLineTv)

        val pkgLineTv = TextView(this).apply {
            val pickInfo = order.pickupAddress.ifEmpty { tr("موقع المطعم", "Restaurant Location") }
            val dropInfo = if (order.address.isNotEmpty()) " ➔ ${order.address}" else ""
            text = "$pickInfo$dropInfo"
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 2, 0, 2)
        }
        centerCol.addView(pkgLineTv)

        val totalDist = order.distanceKm + order.customerDistanceKm
        val distToUse = if (totalDist > 0.0) totalDist else order.distanceKm
        val ratePerKm = if (distToUse > 0.0 && order.price > 0.0) order.price / distToUse else 0.0

        val metricsTv = TextView(this).apply {
            val distStr = if (order.customerDistanceKm > 0.0) {
                if (isAr) {
                    String.format(Locale.US, "المطعم %.1f كم • العميل %.1f كم", order.distanceKm, order.customerDistanceKm)
                } else {
                    String.format(Locale.US, "Pickup %.1f km • Drop-off %.1f km", order.distanceKm, order.customerDistanceKm)
                }
            } else {
                if (isAr) {
                    String.format(Locale.US, "المسافة ≈ %.2f كم", distToUse)
                } else {
                    String.format(Locale.US, "Distance ≈ %.2f km", distToUse)
                }
            }
            val rateStr = if (ratePerKm > 0.0) {
                if (isAr) {
                    String.format(Locale.US, "  •  %.2f %s/كم", ratePerKm, currLabel)
                } else {
                    String.format(Locale.US, "  •  %.2f %s/km", ratePerKm, currLabel)
                }
            } else ""
            val foodStr = if (showFoodPriceOpt) {
                if (isAr) {
                    String.format(Locale.US, "  •  🍔 الطعام: %.2f %s", effectiveFoodPrice, currLabel)
                } else {
                    String.format(Locale.US, "  •  🍔 Food: %.2f %s", effectiveFoodPrice, currLabel)
                }
            } else ""
            text = "$distStr$rateStr$foodStr"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 2, 0, 0)
        }
        centerCol.addView(metricsTv)

        row.addView(centerCol)

        val priceRightTv = TextView(this).apply {
            val priceFormatted = if (order.price % 1.0 == 0.0) {
                String.format(Locale.US, "%.0f %s", order.price, currLabel)
            } else {
                String.format(Locale.US, "%.2f %s", order.price, currLabel)
            }
            text = priceFormatted
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            setPadding(16, 0, 0, 0)
        }
        row.addView(priceRightTv)

        outerCol.addView(row)
        if (order.destLat != 0.0 && order.destLng != 0.0) {
            card.setOnClickListener {
                openGoogleMapsNavigation(
                    targetLat = order.destLat,
                    targetLng = order.destLng,
                    originLat = order.pickupLat,
                    originLng = order.pickupLng,
                    label = "Missed Order (${order.reference})"
                )
            }
        }
        card.addView(outerCol)
        return card
    }

    // =========================================================================
    // SETTINGS TAB CARDS (100% Pure Selected Language)
    // =========================================================================
    private fun createConnectionPingCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = tr("📡 حالة الاتصال المباشر بالتطبيق", "📡 Live App Connection Status")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        tvPingStatus = TextView(this).apply {
            text = tr("جاري فحص الاتصال بتطبيق ساند... ⏳", "Checking connection to target app... ⏳")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D97706"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvPingStatus)

        tvPingDetails = TextView(this).apply {
            text = tr(
                "النظام: قيد التهيئة  •  المصادقة: بانتظار تسجيل الدخول  •  الطلبات: 0",
                "Hook: Initializing  •  Auth: Waiting for login  •  Orders: 0"
            )
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvPingDetails)

        val btnPing = Button(this).apply {
            text = tr("⚡ فحص الاتصال الآن", "⚡ Test Connection Now")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sendPing()
                Toast.makeText(
                    this@MainActivity,
                    tr("تم إرسال نبضة فحص الاتصال", "Connection ping request sent"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(btnPing)

        card.addView(layout)
        return card
    }

    private fun createDiagnosticsCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = tr("🌐 تشخيص الشبكة والاتصال المباشر بالسيرفر", "🌐 Network & Direct Server Diagnostics")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(title)

        val sub = TextView(this).apply {
            text = tr(
                "مراقبة حية لحركة الشبكة، وعدد عمليات السحب في الخلفية، واختبار الاتصال الفوري:",
                "Live inspection of network traffic, background polling hits, and direct server probe:"
            )
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(sub)

        tvOkHttpHookStatus = TextView(this).apply {
            text = tr("حالة معترض الشبكة: نشط ويعترض الطلبات 🟢", "Network Interceptor: Active & Intercepting 🟢")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvOkHttpHookStatus)

        tvHttpRequestsCount = TextView(this).apply {
            text = tr("إجمالي طلبات الشبكة الملتقطة: 0", "Total Captured Network Requests: 0")
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvHttpRequestsCount)

        tvPollingHits = TextView(this).apply {
            text = tr("عمليات فحص السيرفر في الخلفية: 0", "Background Server Polling Hits: 0")
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvPollingHits)

        tvLastHttpUrl = TextView(this).apply {
            text = tr("آخر طلب شبكة: بانتظار حركة البيانات...", "Last Request: Waiting for network traffic...")
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvLastHttpUrl)

        tvLastOrderEvent = TextView(this).apply {
            text = tr("آخر حدث: جاهز لاستقبال الطلبات", "Last Event: Ready for orders")
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvLastOrderEvent)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTestApi = Button(this).apply {
            text = tr("🧪 فحص اتصال السيرفر", "🧪 Test Server API")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setBackgroundColor(Color.parseColor("#0D9488"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sendTestApiProbe()
            }
        }
        btnRow.addView(btnTestApi)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 1)
        }
        btnRow.addView(spacer)

        val btnSimOrder = Button(this).apply {
            text = tr("⚡ محاكاة طلب تجريبي", "⚡ Simulate Test Order")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sendSimulateOrder()
            }
        }
        btnRow.addView(btnSimOrder)

        layout.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        val btnClearToken = Button(this).apply {
            text = tr("🗑️ مسح رمز الجلسة المخزن", "🗑️ Clear Cached Session Token")
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sendClearToken()
            }
        }
        btnRow2.addView(btnClearToken)
        layout.addView(btnRow2)

        card.addView(layout)
        return card
    }

    private fun sendTestApiProbe() {
        Toast.makeText(
            this,
            tr("جاري اختبار الاتصال المباشر بالسيرفر...", "Testing direct server connection..."),
            Toast.LENGTH_SHORT
        ).show()
        val intent = Intent("dev.saned.assistant.ACTION_TEST_API").apply {
            setPackage("net.jahez.fleets")
        }
        sendBroadcast(intent)
        try {
            sendBroadcast(Intent("dev.saned.assistant.ACTION_TEST_API"))
        } catch (_: Throwable) {}
    }

    private fun sendSimulateOrder() {
        Toast.makeText(
            this,
            tr("جاري إنشاء طلب تجريبي...", "Simulating test order..."),
            Toast.LENGTH_SHORT
        ).show()
        val intent = Intent("dev.saned.assistant.ACTION_SIMULATE_ORDER").apply {
            setPackage("net.jahez.fleets")
        }
        sendBroadcast(intent)
        try {
            sendBroadcast(Intent("dev.saned.assistant.ACTION_SIMULATE_ORDER"))
        } catch (_: Throwable) {}

        mainHandler.postDelayed({
            val testNum = (100000000..999999999).random()
            val simOrder = OrderModel(
                id = testNum,
                reference = "#$testNum",
                status = if (isAr) "مقبول ✅" else "Accepted ✅",
                restaurant = if (isAr) "ماكدونالدز - فرع الملقا" else "McDonald's - Al Malqa Branch",
                pickupAddress = if (isAr) "حي الملقا، الرياض 13524" else "Al Malqa District, Riyadh 13524",
                customerName = if (isAr) "عبدالله الشمري" else "Abdullah Al-Shammari",
                customerPhone = "0551234567",
                address = if (isAr) "حي حطين، الرياض" else "Hittin District, Riyadh",
                pickupLat = 24.803508,
                pickupLng = 46.604578,
                destLat = 24.774265,
                destLng = 46.618340,
                price = 7.00,
                currency = "SAR",
                cashAmount = 0.0,
                foodPrice = 65.00,
                paymentMethod = if (isAr) "مدفوع مسبقاً" else "Prepaid",
                distanceKm = 0.5,
                customerDistanceKm = 2.8,
                decisionReason = if (isAr) "تم القبول (0.5 كم / 2.8 كم)" else "Accepted (0.5 km / 2.8 km)",
                isMissedOrRejected = false,
                time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            )

            io.github.libxposed.service.XposedProvider.saveOrderAndNotify(
                this@MainActivity,
                simOrder
            )
            switchTab(0)
            refreshOrdersLogUI()
        }, 150)
    }

    private fun sendClearToken() {
        val intent = Intent("dev.saned.assistant.ACTION_CLEAR_TOKEN").apply {
            setPackage("net.jahez.fleets")
        }
        sendBroadcast(intent)
        try {
            sendBroadcast(Intent("dev.saned.assistant.ACTION_CLEAR_TOKEN"))
        } catch (_: Throwable) {}
        Toast.makeText(
            this,
            tr("تم مسح رمز الجلسة المخزن! افتح التطبيق لتحديث الجلسة.", "Session token cache cleared! Open the target app to refresh."),
            Toast.LENGTH_LONG
        ).show()
        sendPing()
    }

    private fun sendPing() {
        val pingTime = System.currentTimeMillis()
        try {
            val intent = Intent("dev.saned.assistant.ACTION_PING").apply {
                setPackage("net.jahez.fleets")
                putExtra("timestamp", pingTime)
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}

        try {
            val gIntent = Intent("dev.saned.assistant.ACTION_PING").apply {
                putExtra("timestamp", pingTime)
            }
            sendBroadcast(gIntent)
        } catch (_: Throwable) {}
    }

    private fun checkPingTimeout() {
        val now = System.currentTimeMillis()
        if (lastPongTimestamp > 0 && (now - lastPongTimestamp) > 4000L) {
            if (::tvPingStatus.isInitialized) {
                tvPingStatus.text = tr("🔴 غير متصل (التطبيق مغلق أو غير نشط)", "🔴 Disconnected (Target App Closed or Inactive)")
                tvPingStatus.setTextColor(Color.parseColor("#DC2626"))
                tvPingDetails.text = tr("افتح تطبيق ساند لتفعيل الربط المباشر", "Open the target app to establish real-time link")
            }
        }
    }

    private fun createMasterSwitchCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = tr("🔘 مفتاح التشغيل الرئيسي", "🔘 Master Control Switch")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        tvMasterStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }

        swMaster = Switch(this).apply {
            text = tr("تفعيل جميع ميزات المساعد (تشغيل / إيقاف شامل)", "Enable All Assistant Functions (Master ON / OFF)")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = SettingsStore.isMasterRunning(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                updateMasterStatusUI(isChecked)
                SettingsStore.setMasterRunning(this@MainActivity, isChecked)
            }
        }
        layout.addView(swMaster)
        layout.addView(tvMasterStatus)

        updateMasterStatusUI(swMaster.isChecked)
        card.addView(layout)
        return card
    }

    private fun updateMasterStatusUI(isRunning: Boolean) {
        if (isRunning) {
            tvMasterStatus.text = tr("الحالة: 🟢 نشط وجاهز لالتقاط الطلبات فوراً", "Status: 🟢 Active & Ready for Orders")
            tvMasterStatus.setTextColor(Color.parseColor("#059669"))
        } else {
            tvMasterStatus.text = tr("الحالة: 🔴 متوقف (يستمر عرض الطلبات في تبويب الطلبات)", "Status: 🔴 Stopped (Orders are still logged in Orders tab)")
            tvMasterStatus.setTextColor(Color.parseColor("#DC2626"))
        }
    }

    private fun createAndroidIdCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = tr("📱 معرف وبصمة الجهاز", "📱 Device Identifier")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        val note = TextView(this).apply {
            text = tr(
                "ملاحظة: يستخدم التطبيق معرف الجهاز عند تسجيل الدخول. يتيح لك هذا الخيار تخصيص أو توليد معرف جهاز جديد.",
                "Note: The target app uses the device identifier on the login screen. Modifying this changes the reported device ID."
            )
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(note)

        chkAndroidId = CheckBox(this).apply {
            text = tr("تفعيل تغيير معرف الجهاز", "Enable Device Identifier Spoofing")
            isChecked = SettingsStore.isSpoofAndroidId(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setSpoofAndroidId(this@MainActivity, isChecked)
            }
        }
        layout.addView(chkAndroidId)

        edtAndroidId = EditText(this).apply {
            hint = tr("أدخل 16 رمزاً سداسياً عشرياً", "Enter 16 hexadecimal characters")
            setText(SettingsStore.getSpoofedAndroidId(this@MainActivity))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
        }
        layout.addView(edtAndroidId)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        val btnGen = Button(this).apply {
            text = tr("🎲 توليد عشوائي", "🎲 Generate Random ID")
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                edtAndroidId.setText(generateRandomAndroidId())
                chkAndroidId.isChecked = true
            }
        }
        btnRow.addView(btnGen)

        val btnCopy = Button(this).apply {
            text = tr("📋 نسخ المعرف", "📋 Copy ID")
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Device ID", edtAndroidId.text.toString().trim()))
                Toast.makeText(
                    this@MainActivity,
                    tr("تم نسخ معرف الجهاز إلى الحافظة", "Device ID copied to clipboard"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnRow.addView(btnCopy)

        layout.addView(btnRow)
        card.addView(layout)
        return card
    }

    private fun createLocationShortcutCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = tr("🗺️ محاكي الموقع والمسار بين نقطتين", "🗺️ 2-Point Location Spoofer & Route Simulator")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(title)
        val sub = TextView(this).apply {
            text = tr(
                "انتقل إلى تبويب الخريطة والمسار لتحديد النقطة 1 والنقطة 2 ومحاكاة المشي في الطريق بسرعات مختلفة أو بسرعة مخصصة.",
                "Open the Map & Route tab to set Point 1 and Point 2 and simulate driving along the road with variable speeds or a custom speed."
            )
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(sub)
        val btnOpenMap = Button(this).apply {
            text = tr("🗺️ فتح الخريطة ومحاكي النقطتين الآن", "🗺️ Open Map & 2-Point Simulator Now")
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener { switchTab(3) }
        }
        layout.addView(btnOpenMap)
        card.addView(layout)
        return card
    }

    // =========================================================================
    // TAB 3: MAP & 2-POINT ROUTE SIMULATOR TAB (Point 1 -> Point 2 + Variable/Custom Speeds)
    // =========================================================================
    private fun buildMapTabView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 48)
        }
        container.addView(createLocationMapCard(scroll))
        scroll.addView(container)
        return scroll
    }

    private fun createLocationMapCard(parentScroll: ScrollView? = null): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 22)
        }

        // Header Row: Title + API Key Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val title = TextView(this).apply {
            text = tr("🗺️ الموقع المزيف ومحاكي المسار (نقطتين 1 ➔ 2)", "🗺️ Location Spoofer & 2-Point Route Simulator")
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)

        val btnApiKey = TextView(this).apply {
            text = tr("🔑 مفتاح الخريطة", "🔑 Map Key")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E40AF"))
            background = roundedBg("#DBEAFE", 12f)
            setPadding(18, 10, 18, 10)
            setOnClickListener { showGoogleMapsApiKeyDialog() }
        }
        headerRow.addView(btnApiKey)
        layout.addView(headerRow)

        // Live Status Banner
        tvRouteSimStatus = TextView(this).apply {
            val isFake = SettingsStore.isFakeLocation(this@MainActivity)
            text = if (isFake) {
                tr(
                    "🟢 الموقع المزيف مفعل • حدد النقطة 1 والنقطة 2 على الخريطة لبدء محاكاة الطريق",
                    "🟢 Fake Location Enabled • Set Point 1 and Point 2 on the map to start route simulation"
                )
            } else {
                tr(
                    "⚪ اضغط على الخريطة لتعيين النقطة 1 والنقطة 2 أو تثبيت موقعك",
                    "⚪ Tap on the map to set Point 1 and Point 2 or pin your location"
                )
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (isFake) "#059669" else "#475569"))
            background = roundedBg("#F8FAFC", 12f, "#E2E8F0", 2)
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 10)
            }
        }
        layout.addView(tvRouteSimStatus)

        // Toggles Row (Pure Arabic or Pure English — NO mixed text!)
        chkFakeLoc = CheckBox(this).apply {
            text = tr("تفعيل الموقع المزيف والحماية من الكشف", "Enable Fake Location & Anti-Detection")
            isChecked = SettingsStore.isFakeLocation(this@MainActivity)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setFakeLocation(this@MainActivity, isChecked)
                if (!isChecked && isRouteSimRunning) {
                    stopRouteSimulation()
                }
                updateRouteSimBannerUI()
            }
        }
        layout.addView(chkFakeLoc)

        chkFixLoc = CheckBox(this).apply {
            text = tr("إصلاح خطأ الموقع غير معروف", "Fix Location Unknown Error")
            isChecked = SettingsStore.isFixLocation(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setFixLocation(this@MainActivity, isChecked)
            }
        }
        layout.addView(chkFixLoc)

        // Search Row
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 8)
        }
        edtMapSearch = EditText(this).apply {
            hint = tr("🔍 ابحث عن حي أو مطعم أو شارع (مثال: حي الملقا الرياض)...", "🔍 Search district, restaurant, or street (e.g. Al Malqa Riyadh)...")
            textSize = 13f
            setSingleLine(true)
            background = roundedBg("#F1F5F9", 12f, "#CBD5E1", 2)
            setPadding(22, 16, 22, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 10, 0)
            }
        }
        searchRow.addView(edtMapSearch)

        val btnSearchPlace = Button(this).apply {
            text = tr("بحث 🔎", "Search 🔎")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val q = edtMapSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    val safeQ = q.replace("'", "")
                    mapWebView.evaluateJavascript("if(window.searchPlaceOnGoogleMap) window.searchPlaceOnGoogleMap('$safeQ');", null)
                }
            }
        }
        searchRow.addView(btnSearchPlace)
        layout.addView(searchRow)

        // 3-Button Mode Switcher Row: [📍 موقع واحد] [1️⃣ النقطة 1 (انطلاق)] [2️⃣ النقطة 2 (وصول)]
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        btnModeTeleport = Button(this).apply {
            text = tr("📍 موقع واحد", "📍 Single Pin")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener { setMapTapSelectionMode("teleport") }
        }
        btnModePoint1 = Button(this).apply {
            text = tr("1️⃣ النقطة 1 (انطلاق)", "1️⃣ Point 1 (Start)")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { setMapTapSelectionMode("point1") }
        }
        btnModePoint2 = Button(this).apply {
            text = tr("2️⃣ النقطة 2 (وصول)", "2️⃣ Point 2 (End)")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener { setMapTapSelectionMode("point2") }
        }
        modeRow.addView(btnModeTeleport)
        modeRow.addView(btnModePoint1)
        modeRow.addView(btnModePoint2)
        layout.addView(modeRow)

        // 2-Point Coordinates Summary & Quick Actions Box
        val twoPtBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#F0FDF4", 12f, "#86EFAC", 2)
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        tvTwoPointSummary = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#065F46"))
            setPadding(0, 0, 0, 10)
        }
        twoPtBox.addView(tvTwoPointSummary)

        val twoPtBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnEditTwoPts = TextView(this).apply {
            text = tr("✏️ تعيين النقطتين 1 و 2", "✏️ Set Points 1 & 2")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#059669", 10f)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener { showEditTwoPointsDialog() }
        }
        val btnSwapTwoPts = TextView(this).apply {
            text = tr("🔄 عكس (1 ⇄ 2)", "🔄 Swap (1 ⇄ 2)")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#0284C7", 10f)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f).apply {
                setMargins(3, 0, 3, 0)
            }
            setOnClickListener { swapPoint1AndPoint2() }
        }
        val btnBuildTwoPtRoute = TextView(this).apply {
            text = tr("🛣️ رسم مسار 1 ➔ 2", "🛣️ Route 1 ➔ 2")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 10f)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                setMargins(6, 0, 0, 0)
            }
            setOnClickListener {
                if (::mapWebView.isInitialized) {
                    mapWebView.evaluateJavascript(
                        "if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, true);",
                        null
                    )
                }
            }
        }
        twoPtBtnRow.addView(btnEditTwoPts)
        twoPtBtnRow.addView(btnSwapTwoPts)
        twoPtBtnRow.addView(btnBuildTwoPtRoute)
        twoPtBox.addView(twoPtBtnRow)
        layout.addView(twoPtBox)

        // Layer Switcher + Active Order Route Loader Row
        val layerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val btnLayerRoad = TextView(this).apply {
            text = tr("🗺️ خريطة الشوارع", "🗺️ Street Map")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1E293B"))
            background = roundedBg("#E2E8F0", 10f)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener {
                mapWebView.evaluateJavascript("if(window.setGoogleMapLayer) window.setGoogleMapLayer('roadmap');", null)
            }
        }
        val btnLayerSat = TextView(this).apply {
            text = tr("🛰️ قمر صناعي", "🛰️ Satellite")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1E293B"))
            background = roundedBg("#E2E8F0", 10f)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(3, 0, 3, 0)
            }
            setOnClickListener {
                mapWebView.evaluateJavascript("if(window.setGoogleMapLayer) window.setGoogleMapLayer('hybrid');", null)
            }
        }
        val btnLayerTraffic = TextView(this).apply {
            text = tr("🚦 الزحام", "🚦 Traffic")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1E293B"))
            background = roundedBg("#E2E8F0", 10f)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                setMargins(3, 0, 6, 0)
            }
            setOnClickListener {
                mapWebView.evaluateJavascript("if(window.setGoogleMapLayer) window.setGoogleMapLayer('traffic');", null)
            }
        }
        val btnLoadOrderRoute = TextView(this).apply {
            text = tr("📦 مسار طلب", "📦 Order Route")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#7C3AED", 10f)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f)
            setOnClickListener { showSelectOrderForRouteDialog() }
        }
        layerRow.addView(btnLayerRoad)
        layerRow.addView(btnLayerSat)
        layerRow.addView(btnLayerTraffic)
        layerRow.addView(btnLoadOrderRoute)
        layout.addView(layerRow)

        // Interactive Google Map Frame
        val mapFrame = FrameLayout(this).apply {
            background = roundedBg("#E2E8F0", 16f, "#CBD5E1", 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                920
            ).apply {
                setMargins(0, 4, 0, 10)
            }
        }

        mapWebView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        parentScroll?.requestDisallowInterceptTouchEvent(true)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parentScroll?.requestDisallowInterceptTouchEvent(false)
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
        mapFrame.addView(mapWebView)
        layout.addView(mapFrame)

        // Route Metrics & Speed Mode Badge
        tvRouteMetricsBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E3A8A"))
            background = roundedBg("#EFF6FF", 12f, "#BFDBFE", 2)
            setPadding(18, 14, 18, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            setOnClickListener {
                showRouteSpeedCustomizationDialog(startImmediately = false)
            }
        }
        layout.addView(tvRouteMetricsBadge)

        // Route Simulator Control Buttons Row
        val simCtrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        btnStartRouteSim = Button(this).apply {
            text = tr("🚗 بدء محاكاة المسار (1 ➔ 2)", "🚗 Start Route Sim (1 ➔ 2)")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.55f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener {
                showRouteSpeedCustomizationDialog(startImmediately = true)
            }
        }
        simCtrlRow.addView(btnStartRouteSim)

        val btnSpeedDialog = Button(this).apply {
            text = tr("⚡ السرعات", "⚡ Speeds")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener {
                showRouteSpeedCustomizationDialog(startImmediately = false)
            }
        }
        simCtrlRow.addView(btnSpeedDialog)

        btnPauseRouteSim = Button(this).apply {
            text = "⏸️"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#D97706"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener {
                togglePauseRouteSimulation()
            }
        }
        simCtrlRow.addView(btnPauseRouteSim)

        btnStopRouteSim = Button(this).apply {
            text = tr("⏹️ إيقاف", "⏹️ Stop")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f).apply {
                setMargins(6, 0, 0, 0)
            }
            setOnClickListener {
                stopRouteSimulation()
                mapWebView.evaluateJavascript("if(window.clearSimulatedRoute) window.clearSimulatedRoute();", null)
                Toast.makeText(
                    this@MainActivity,
                    tr("⏹️ تم إيقاف محاكاة المسار وتثبيت الموقع الحالي", "⏹️ Route simulation stopped and current location pinned"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        simCtrlRow.addView(btnStopRouteSim)
        layout.addView(simCtrlRow)

        // Current Live Coordinates Row
        val coordsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        edtLat = EditText(this).apply {
            hint = tr("خط العرض", "Latitude")
            val l = SettingsStore.getFakeLat(this@MainActivity)
            setText(if (l != 0.0) String.format(Locale.US, "%.6f", l) else String.format(Locale.US, "%.6f", point1Lat))
            textSize = 12.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLat)

        edtLng = EditText(this).apply {
            hint = tr("خط الطول", "Longitude")
            val g = SettingsStore.getFakeLng(this@MainActivity)
            setText(if (g != 0.0) String.format(Locale.US, "%.6f", g) else String.format(Locale.US, "%.6f", point1Lng))
            textSize = 12.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLng)

        val btnApplyCoords = Button(this).apply {
            text = tr("📍 تثبيت", "📍 Pin")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val lt = edtLat.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                val lg = edtLng.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                point1Lat = lt
                point1Lng = lg
                SettingsStore.setPoint1(this@MainActivity, lt, lg)
                updateTwoPointSummaryUI()
                applyImmediateSpoofLocation(lt, lg, panMap = true)
                Toast.makeText(
                    this@MainActivity,
                    tr("✅ تم تحديث وتثبيت الموقع فوراً", "✅ Spoofed location updated and pinned immediately"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        coordsRow.addView(btnApplyCoords)
        layout.addView(coordsRow)

        updateTwoPointSummaryUI()
        updateRouteMetricsUI()
        setMapTapSelectionMode("point2")
        setupGoogleMapWebView()

        card.addView(layout)
        return card
    }

    private fun updateTwoPointSummaryUI() {
        if (!::tvTwoPointSummary.isInitialized) return
        val straightDistKm = computeDistanceMeters(point1Lat, point1Lng, point2Lat, point2Lng) / 1000.0
        tvTwoPointSummary.text = if (isAr) {
            String.format(
                Locale.US,
                "1️⃣ النقطة 1 (انطلاق): %.5f, %.5f\n2️⃣ النقطة 2 (وصول):   %.5f, %.5f   •   البعد المباشر: %.2f كم",
                point1Lat, point1Lng, point2Lat, point2Lng, straightDistKm
            )
        } else {
            String.format(
                Locale.US,
                "1️⃣ Point 1 (Start): %.5f, %.5f\n2️⃣ Point 2 (End):   %.5f, %.5f   •   Direct Distance: %.2f km",
                point1Lat, point1Lng, point2Lat, point2Lng, straightDistKm
            )
        }
    }

    private fun setMapTapSelectionMode(mode: String) {
        mapTapMode = mode
        if (::btnModeTeleport.isInitialized && ::btnModePoint1.isInitialized && ::btnModePoint2.isInitialized) {
            val isTel = mode == "teleport"
            val isP1 = mode == "point1"
            val isP2 = mode == "point2"

            btnModeTeleport.setBackgroundColor(Color.parseColor(if (isTel) "#4F46E5" else "#CBD5E1"))
            btnModeTeleport.setTextColor(if (isTel) Color.WHITE else Color.parseColor("#1E293B"))

            btnModePoint1.setBackgroundColor(Color.parseColor(if (isP1) "#059669" else "#CBD5E1"))
            btnModePoint1.setTextColor(if (isP1) Color.WHITE else Color.parseColor("#1E293B"))

            btnModePoint2.setBackgroundColor(Color.parseColor(if (isP2) "#DC2626" else "#CBD5E1"))
            btnModePoint2.setTextColor(if (isP2) Color.WHITE else Color.parseColor("#1E293B"))
        }
        if (::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.setClickMode) window.setClickMode('$mode');", null)
        }
    }

    private fun swapPoint1AndPoint2() {
        val tmpLat = point1Lat
        val tmpLng = point1Lng
        point1Lat = point2Lat
        point1Lng = point2Lng
        point2Lat = tmpLat
        point2Lng = tmpLng
        SettingsStore.setPoint1(this, point1Lat, point1Lng)
        SettingsStore.setPoint2(this, point2Lat, point2Lng)
        updateTwoPointSummaryUI()
        applyImmediateSpoofLocation(point1Lat, point1Lng, panMap = false)
        if (::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript(
                "if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, false);",
                null
            )
        }
        Toast.makeText(
            this,
            tr("🔄 تم عكس النقطتين (1 ⇄ 2) وإعادة رسم المسار", "🔄 Swapped Point 1 ⇄ Point 2 and rebuilt route"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showEditTwoPointsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
        }

        val info = TextView(this).apply {
            text = tr(
                "حدد إحداثيات النقطة 1 (نقطة الانطلاق) والنقطة 2 (نقطة الوصول) لمحاكاة الطريق بينهما:",
                "Specify coordinates for Point 1 (Start) and Point 2 (Destination) to simulate the street route between them:"
            )
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(info)

        val lblP1 = TextView(this).apply {
            text = tr("1️⃣ النقطة 1 — نقطة الانطلاق (خط العرض , خط الطول):", "1️⃣ Point 1 — Start Location (Lat , Lng):")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#059669"))
        }
        layout.addView(lblP1)

        val rowP1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 12)
        }
        val edtP1Lat = EditText(this).apply {
            setText(String.format(Locale.US, "%.6f", point1Lat))
            hint = tr("عرض 1", "Lat 1")
            textSize = 13f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val edtP1Lng = EditText(this).apply {
            setText(String.format(Locale.US, "%.6f", point1Lng))
            hint = tr("طول 1", "Lng 1")
            textSize = 13f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowP1.addView(edtP1Lat)
        rowP1.addView(edtP1Lng)
        layout.addView(rowP1)

        val lblP2 = TextView(this).apply {
            text = tr("2️⃣ النقطة 2 — نقطة الوصول (خط العرض , خط الطول):", "2️⃣ Point 2 — Destination Location (Lat , Lng):")
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#DC2626"))
        }
        layout.addView(lblP2)

        val rowP2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 12)
        }
        val edtP2Lat = EditText(this).apply {
            setText(String.format(Locale.US, "%.6f", point2Lat))
            hint = tr("عرض 2", "Lat 2")
            textSize = 13f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val edtP2Lng = EditText(this).apply {
            setText(String.format(Locale.US, "%.6f", point2Lng))
            hint = tr("طول 2", "Lng 2")
            textSize = 13f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowP2.addView(edtP2Lat)
        rowP2.addView(edtP2Lng)
        layout.addView(rowP2)

        AlertDialog.Builder(this)
            .setTitle(tr("1️⃣➔2️⃣ تعيين النقطتين 1 و 2 للمسار", "1️⃣➔2️⃣ Set Route Points 1 & 2"))
            .setView(layout)
            .setPositiveButton(tr("🛣️ حفظ ورسم المسار", "🛣️ Save & Build Route")) { _, _ ->
                val p1Lt = edtP1Lat.text.toString().trim().toDoubleOrNull() ?: point1Lat
                val p1Lg = edtP1Lng.text.toString().trim().toDoubleOrNull() ?: point1Lng
                val p2Lt = edtP2Lat.text.toString().trim().toDoubleOrNull() ?: point2Lat
                val p2Lg = edtP2Lng.text.toString().trim().toDoubleOrNull() ?: point2Lng
                point1Lat = p1Lt
                point1Lng = p1Lg
                point2Lat = p2Lt
                point2Lng = p2Lg
                SettingsStore.setPoint1(this, point1Lat, point1Lng)
                SettingsStore.setPoint2(this, point2Lat, point2Lng)
                updateTwoPointSummaryUI()
                applyImmediateSpoofLocation(point1Lat, point1Lng, panMap = false)
                if (::mapWebView.isInitialized) {
                    mapWebView.evaluateJavascript(
                        "if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, true);",
                        null
                    )
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun applyImmediateSpoofLocation(lat: Double, lng: Double, panMap: Boolean = false) {
        val roundLat = String.format(Locale.US, "%.6f", lat)
        val roundLng = String.format(Locale.US, "%.6f", lng)
        if (::edtLat.isInitialized) edtLat.setText(roundLat)
        if (::edtLng.isInitialized) edtLng.setText(roundLng)
        if (::chkFakeLoc.isInitialized && !chkFakeLoc.isChecked) {
            chkFakeLoc.isChecked = true
        }
        SettingsStore.pushLiveLocationStep(
            this,
            lat = lat,
            lng = lng,
            speedKmh = if (isRouteSimRunning) routeSimCurrentDynamicSpeedKmh else 0.0f,
            bearing = SettingsStore.getRouteSimBearing(this),
            simActive = isRouteSimRunning && !isRouteSimPaused
        )
        if (panMap && ::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.setDriverPin) window.setDriverPin($lat, $lng, 0, true);", null)
        }
        updateRouteSimBannerUI()
    }

    /**
     * Dialog supporting BOTH:
     * 1) Variable Multi-Speed Mode (بسرعات مختلفة بين حد أدنى وحد أقصى على طول الطريق من نقطة 1 إلى نقطة 2)
     * 2) Custom Speed Mode (سرعة مخصصة يحددها المستخدم بالرقم أو بشريط التمرير)
     */
    private fun showRouteSpeedCustomizationDialog(startImmediately: Boolean) {
        val scrollWrap = ScrollView(this)
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 28, 44, 20)
        }
        scrollWrap.addView(dialogLayout)

        val distKm = routeTotalMeters / 1000.0
        val infoTv = TextView(this).apply {
            text = if (distKm > 0.01) {
                if (isAr) {
                    String.format(Locale.US, "🛣️ مسار الطريق من النقطة 1 إلى النقطة 2: %.2f كم\nاختر نظام السرعة لمحاكاة المشي في الطريق:", distKm)
                } else {
                    String.format(Locale.US, "🛣️ Street route from Point 1 to Point 2: %.2f km\nChoose your speed mode for the route simulation:", distKm)
                }
            } else {
                tr(
                    "اختر نظام السرعة لمحاكاة الطريق من النقطة 1 إلى النقطة 2 (سرعات مختلفة واقعية أو سرعة مخصصة ثابتة):",
                    "Select speed mode for simulating the route from Point 1 to Point 2 (Variable realistic speeds or Custom fixed speed):"
                )
            }
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 14)
        }
        dialogLayout.addView(infoTv)

        var selectedVarMode = isVariableSpeedMode

        val modeSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 14)
        }
        val btnSelectVariable = Button(this).apply {
            text = tr("🌊 بسرعات مختلفة", "🌊 Variable Speeds")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
        }
        val btnSelectCustom = Button(this).apply {
            text = tr("🎯 سرعة مخصصة", "🎯 Custom Speed")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 0, 0)
            }
        }
        modeSwitchRow.addView(btnSelectVariable)
        modeSwitchRow.addView(btnSelectCustom)
        dialogLayout.addView(modeSwitchRow)

        // Container A: Variable Speeds Mode (Min Speed -> Max Speed)
        val variableContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#F0FDF4", 12f, "#86EFAC", 2)
            setPadding(24, 20, 24, 20)
        }

        val varDesc = TextView(this).apply {
            text = tr(
                "يتحرك الحساب من النقطة 1 إلى النقطة 2 بسرعات متغيرة تلقائياً بين الحد الأدنى والحد الأقصى (تهدئة واقعية عند المنعطفات وتسارع في الشوارع المستقيمة):",
                "Moves from Point 1 to Point 2 at realistic varying speeds between Min and Max (smoothly slowing down on turns and accelerating on straight roads):"
            )
            textSize = 11.5f
            setTextColor(Color.parseColor("#065F46"))
            setPadding(0, 0, 0, 12)
        }
        variableContainer.addView(varDesc)

        val minMaxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
        }
        val lblMin = TextView(this).apply {
            text = tr("الحد الأدنى (كم/س)", "Min Speed (km/h)")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#047857"))
        }
        val edtMinSpd = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(routeSimMinSpeedKmh.toInt().toString())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg("#FFFFFF", 10f, "#6EE7B7", 2)
            setPadding(16, 16, 16, 16)
        }
        minCol.addView(lblMin)
        minCol.addView(edtMinSpd)

        val maxCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
        }
        val lblMax = TextView(this).apply {
            text = tr("الحد الأقصى (كم/س)", "Max Speed (km/h)")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#047857"))
        }
        val edtMaxSpd = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(routeSimMaxSpeedKmh.toInt().toString())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg("#FFFFFF", 10f, "#6EE7B7", 2)
            setPadding(16, 16, 16, 16)
        }
        maxCol.addView(lblMax)
        maxCol.addView(edtMaxSpd)

        minMaxRow.addView(minCol)
        minMaxRow.addView(maxCol)
        variableContainer.addView(minMaxRow)

        val varPresetsLbl = TextView(this).apply {
            text = tr("نطاقات سرعات مختلفة جاهزة:", "Quick Variable Speed Ranges:")
            textSize = 11.5f
            setTextColor(Color.parseColor("#047857"))
            setPadding(0, 12, 0, 6)
        }
        variableContainer.addView(varPresetsLbl)

        val varPresetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val varRanges = listOf(
            "🛵 20-45" to (20 to 45),
            "🚗 30-75" to (30 to 75),
            "🛣️ 45-95" to (45 to 95),
            "⚡ 65-120" to (65 to 120)
        )
        for ((lbl, pair) in varRanges) {
            val chip = TextView(this).apply {
                text = lbl
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#065F46"))
                background = roundedBg("#D1FAE5", 10f)
                setPadding(10, 12, 10, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    edtMinSpd.setText(pair.first.toString())
                    edtMaxSpd.setText(pair.second.toString())
                }
            }
            varPresetsRow.addView(chip)
        }
        variableContainer.addView(varPresetsRow)
        dialogLayout.addView(variableContainer)

        // Container B: Custom Fixed Speed Mode
        val customContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#EFF6FF", 12f, "#93C5FD", 2)
            setPadding(24, 20, 24, 20)
        }

        val custDesc = TextView(this).apply {
            text = tr(
                "أدخل أي سرعة مخصصة تريدها (كم/س) أو اسحب المؤشر لتثبيت سرعة محددة طوال المسار:",
                "Enter any custom speed (km/h) or drag the slider to lock a specific speed along the route:"
            )
            textSize = 11.5f
            setTextColor(Color.parseColor("#1E3A8A"))
            setPadding(0, 0, 0, 10)
        }
        customContainer.addView(custDesc)

        val edtSpeed = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(routeSimSpeedKmh.toInt().toString())
            hint = tr("السرعة المخصصة (كم/س) مثل 50", "Custom Speed (km/h) e.g. 50")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg("#FFFFFF", 12f, "#93C5FD", 2)
            setPadding(24, 18, 24, 18)
        }
        customContainer.addView(edtSpeed)

        val tvLiveEta = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1D4ED8"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }
        customContainer.addView(tvLiveEta)

        fun updateDialogEtaPreview(spdKmh: Float) {
            val cleanSpd = spdKmh.coerceIn(2f, 240f)
            val mps = cleanSpd / 3.6f
            if (distKm > 0.01) {
                val totalSec = (routeTotalMeters / mps).toInt().coerceAtLeast(1)
                val mins = totalSec / 60
                val secs = totalSec % 60
                tvLiveEta.text = if (isAr) {
                    String.format(Locale.US, "⚡ السرعة المخصصة: %.0f كم/س (%.1f م/ث) • الوصول خلال: %d د و %d ث", cleanSpd, mps, mins, secs)
                } else {
                    String.format(Locale.US, "⚡ Custom Speed: %.0f km/h (%.1f m/s) • ETA: %d min %d sec", cleanSpd, mps, mins, secs)
                }
            } else {
                tvLiveEta.text = if (isAr) {
                    String.format(Locale.US, "⚡ السرعة المخصصة: %.0f كم/س (%.1f متر/ثانية)", cleanSpd, mps)
                } else {
                    String.format(Locale.US, "⚡ Custom Speed: %.0f km/h (%.1f m/s)", cleanSpd, mps)
                }
            }
        }
        updateDialogEtaPreview(routeSimSpeedKmh)

        val seekBar = SeekBar(this).apply {
            max = 175 // 5 km/h .. 180 km/h
            progress = (routeSimSpeedKmh.toInt() - 5).coerceIn(0, 175)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val spd = (progress + 5).toFloat()
                    if (fromUser) {
                        edtSpeed.setText(spd.toInt().toString())
                    }
                    updateDialogEtaPreview(spd)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        customContainer.addView(seekBar)

        val presetsLabel = TextView(this).apply {
            text = tr("سرعات مخصصة سريعة:", "Quick Custom Speeds:")
            textSize = 11.5f
            setTextColor(Color.parseColor("#1E3A8A"))
            setPadding(0, 12, 0, 6)
        }
        customContainer.addView(presetsLabel)

        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val presets = listOf(
            "🚶 12" to 12,
            "🛵 35" to 35,
            "🚗 55" to 55,
            "🛣️ 80" to 80,
            "⚡ 110" to 110
        )
        for ((lbl, valSpd) in presets) {
            val chip = TextView(this).apply {
                text = lbl
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E40AF"))
                background = roundedBg("#DBEAFE", 10f)
                setPadding(10, 12, 10, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    edtSpeed.setText(valSpd.toString())
                    seekBar.progress = (valSpd - 5).coerceIn(0, 175)
                    updateDialogEtaPreview(valSpd.toFloat())
                }
            }
            presetsRow.addView(chip)
        }
        customContainer.addView(presetsRow)
        dialogLayout.addView(customContainer)

        fun refreshModeTabsUI() {
            variableContainer.visibility = if (selectedVarMode) View.VISIBLE else View.GONE
            customContainer.visibility = if (!selectedVarMode) View.VISIBLE else View.GONE

            btnSelectVariable.setBackgroundColor(Color.parseColor(if (selectedVarMode) "#059669" else "#E2E8F0"))
            btnSelectVariable.setTextColor(if (selectedVarMode) Color.WHITE else Color.parseColor("#1E293B"))

            btnSelectCustom.setBackgroundColor(Color.parseColor(if (!selectedVarMode) "#2563EB" else "#E2E8F0"))
            btnSelectCustom.setTextColor(if (!selectedVarMode) Color.WHITE else Color.parseColor("#1E293B"))
        }

        btnSelectVariable.setOnClickListener {
            selectedVarMode = true
            refreshModeTabsUI()
        }
        btnSelectCustom.setOnClickListener {
            selectedVarMode = false
            refreshModeTabsUI()
        }
        refreshModeTabsUI()

        val chkLoop = CheckBox(this).apply {
            text = tr(
                "🔄 تكرار المسار ذهاباً وإياباً بين النقطة 1 والنقطة 2 تلقائياً",
                "🔄 Automatically loop route back and forth between Point 1 and Point 2"
            )
            isChecked = isRouteSimLooping
            setPadding(0, 14, 0, 0)
        }
        dialogLayout.addView(chkLoop)

        AlertDialog.Builder(this)
            .setTitle(tr("🚗 إعدادات السرعة لمحاكي المسار (1 ➔ 2)", "🚗 Route Simulator Speed Settings (1 ➔ 2)"))
            .setView(scrollWrap)
            .setPositiveButton(
                if (startImmediately) {
                    tr("🚀 بدء محاكاة المسار الآن", "🚀 Start Route Simulation Now")
                } else {
                    tr("💾 حفظ إعدادات السرعة", "💾 Save Speed Settings")
                }
            ) { _, _ ->
                isVariableSpeedMode = selectedVarMode
                isRouteSimLooping = chkLoop.isChecked

                val minV = edtMinSpd.text.toString().trim().toFloatOrNull()?.coerceIn(2.0f, 220.0f) ?: 30.0f
                val maxV = edtMaxSpd.text.toString().trim().toFloatOrNull()?.coerceIn(minV, 240.0f) ?: 75.0f
                routeSimMinSpeedKmh = minV
                routeSimMaxSpeedKmh = maxV

                val chosenCustomSpd = edtSpeed.text.toString().trim().toFloatOrNull()?.coerceIn(2.0f, 240.0f) ?: 45.0f
                routeSimSpeedKmh = chosenCustomSpd
                routeSimCurrentDynamicSpeedKmh = if (isVariableSpeedMode) ((minV + maxV) / 2.0f) else chosenCustomSpd

                SettingsStore.setVariableSpeedEnabled(this, isVariableSpeedMode)
                SettingsStore.setRouteSimMinSpeedKmh(this, routeSimMinSpeedKmh)
                SettingsStore.setRouteSimMaxSpeedKmh(this, routeSimMaxSpeedKmh)
                SettingsStore.setRouteSimSpeedKmh(this, routeSimSpeedKmh)

                updateRouteMetricsUI()
                if (startImmediately || isRouteSimRunning) {
                    startOrResumeRouteSimulation()
                } else {
                    val msg = if (isVariableSpeedMode) {
                        tr(
                            "✅ تم تفعيل السرعات المختلفة (${minV.toInt()} - ${maxV.toInt()} كم/س)",
                            "✅ Variable speeds enabled (${minV.toInt()} - ${maxV.toInt()} km/h)"
                        )
                    } else {
                        tr(
                            "✅ تم ضبط السرعة المخصصة على ${chosenCustomSpd.toInt()} كم/س",
                            "✅ Custom speed set to ${chosenCustomSpd.toInt()} km/h"
                        )
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun showGoogleMapsApiKeyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
        }
        val tv = TextView(this).apply {
            text = tr(
                "تعمل الخرائط المدمجة ورسم الشوارع بين النقطة 1 والنقطة 2 تلقائياً بدون مفتاح.\nوإذا كان لديك مفتاح خاص بك (اختياري)، يمكنك إدخاله هنا:",
                "Built-in street maps and Point 1 to Point 2 routing work automatically without a key.\nIf you have your own API key (optional), you can enter it here:"
            )
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(tv)

        val edtKey = EditText(this).apply {
            hint = tr("اختياري — اتركه فارغاً للوضع التلقائي", "Optional — leave blank for automatic routing")
            setText(SettingsStore.getGoogleMapsApiKey(this@MainActivity))
            textSize = 13.5f
            setSingleLine(true)
        }
        layout.addView(edtKey)

        AlertDialog.Builder(this)
            .setTitle(tr("🔑 إعداد مفتاح الخريطة", "🔑 Map API Key Settings"))
            .setView(layout)
            .setPositiveButton(tr("حفظ وإعادة تحميل الخريطة", "Save & Reload Map")) { _, _ ->
                val key = edtKey.text.toString().trim()
                SettingsStore.setGoogleMapsApiKey(this, key)
                setupGoogleMapWebView()
                Toast.makeText(
                    this,
                    tr("✅ تم تحديث إعدادات الخريطة", "✅ Map settings updated"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(tr("إغلاق", "Close"), null)
            .show()
    }

    private fun showOrderRouteQuickPickerDialog(order: OrderModel) {
        val curLat = edtLat.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLat(this).takeIf { it != 0.0 } ?: order.pickupLat
        val curLng = edtLng.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLng(this).takeIf { it != 0.0 } ?: order.pickupLng
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (order.pickupLat != 0.0 && order.pickupLng != 0.0) {
            options.add(
                tr(
                    "🏪 مسار (1 ➔ 2) من موقعي الحالي إلى المطعم (${order.restaurant.ifEmpty { "المطعم" }})",
                    "🏪 Route (1 ➔ 2) from Current Location to Restaurant (${order.restaurant.ifEmpty { "Pickup" }})"
                )
            )
            actions.add {
                switchTab(3)
                point1Lat = if (curLat != 0.0) curLat else order.pickupLat - 0.008
                point1Lng = if (curLng != 0.0) curLng else order.pickupLng - 0.008
                point2Lat = order.pickupLat
                point2Lng = order.pickupLng
                SettingsStore.setPoint1(this, point1Lat, point1Lng)
                SettingsStore.setPoint2(this, point2Lat, point2Lng)
                updateTwoPointSummaryUI()
                mapWebView.evaluateJavascript("if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, true);", null)
            }
        }
        if (order.destLat != 0.0 && order.destLng != 0.0) {
            val fromLat = if (order.pickupLat != 0.0) order.pickupLat else curLat
            val fromLng = if (order.pickupLng != 0.0) order.pickupLng else curLng
            options.add(
                tr(
                    "📍 مسار (1 ➔ 2) من المطعم إلى موقع العميل",
                    "📍 Route (1 ➔ 2) from Restaurant to Customer Drop-off"
                )
            )
            actions.add {
                switchTab(3)
                point1Lat = fromLat
                point1Lng = fromLng
                point2Lat = order.destLat
                point2Lng = order.destLng
                SettingsStore.setPoint1(this, point1Lat, point1Lng)
                SettingsStore.setPoint2(this, point2Lat, point2Lng)
                updateTwoPointSummaryUI()
                mapWebView.evaluateJavascript("if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, true);", null)
            }
        }
        if (order.pickupLat != 0.0 && order.pickupLng != 0.0) {
            options.add(
                tr(
                    "⚡ نقل موقعي فوراً إلى عند المطعم",
                    "⚡ Teleport Immediately to Restaurant"
                )
            )
            actions.add {
                applyImmediateSpoofLocation(order.pickupLat, order.pickupLng, panMap = true)
                Toast.makeText(
                    this,
                    tr("📍 تم نقل موقعك فوراً إلى المطعم", "📍 Teleported immediately to restaurant"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (order.destLat != 0.0 && order.destLng != 0.0) {
            options.add(
                tr(
                    "⚡ نقل موقعي فوراً إلى موقع العميل",
                    "⚡ Teleport Immediately to Customer Location"
                )
            )
            actions.add {
                applyImmediateSpoofLocation(order.destLat, order.destLng, panMap = true)
                Toast.makeText(
                    this,
                    tr("📍 تم نقل موقعك فوراً إلى موقع العميل", "📍 Teleported immediately to customer location"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(
                this,
                tr("⚠️ لا تتوفر إحداثيات لهذا الطلب بعد", "⚠️ No coordinates available for this order yet"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                tr(
                    "🚗 محاكاة المسار للطلب #${order.reference.ifEmpty { order.id.toString() }}",
                    "🚗 Route Simulator for Order #${order.reference.ifEmpty { order.id.toString() }}"
                )
            )
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton(tr("إغلاق", "Close"), null)
            .show()
    }

    private fun showSelectOrderForRouteDialog() {
        val jsonStr = SettingsStore.getOrdersLog(this)
        val array = try { JSONArray(jsonStr) } catch (_: Throwable) { JSONArray() }
        val ordersWithCoords = mutableListOf<OrderModel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val m = OrderModel.fromJson(item)
            if ((m.pickupLat != 0.0 && m.pickupLng != 0.0) || (m.destLat != 0.0 && m.destLng != 0.0)) {
                if (ordersWithCoords.none { isSameOrderUi(it, m) }) {
                    ordersWithCoords.add(m)
                }
            }
        }
        if (ordersWithCoords.isEmpty()) {
            Toast.makeText(
                this,
                tr(
                    "اضغط مباشرة على الخريطة لتحديد النقطة 1 والنقطة 2 أو انتظر وصول طلب",
                    "Tap directly on the map to set Point 1 and Point 2, or wait for an order"
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = ordersWithCoords.map { o ->
            val idStr = if (o.reference.isNotEmpty()) "#${o.reference.removePrefix("#")}" else "#${o.id}"
            "$idStr — ${o.restaurant.ifEmpty { tr("طلب", "Order") }} (${localizeStatusText(o.status)})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(tr("📦 اختر طلباً لتحميل مساره (نقطة 1 ➔ نقطة 2)", "📦 Select Order to Load 2-Point Route"))
            .setItems(labels) { _, which ->
                showOrderRouteQuickPickerDialog(ordersWithCoords[which])
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    // =========================================================================
    // REAL-TIME 500ms 2-POINT STREET ROUTE SIMULATOR ENGINE (Variable Speeds + Custom Speed)
    // =========================================================================
    private fun computeInstantSimulationSpeedKmh(segIdx: Int): Float {
        if (!isVariableSpeedMode) {
            return routeSimSpeedKmh.coerceIn(2.0f, 240.0f)
        }
        val minS = routeSimMinSpeedKmh.coerceIn(2.0f, 220.0f)
        val maxS = routeSimMaxSpeedKmh.coerceIn(minS, 240.0f)
        if (maxS - minS < 1.0f) return minS

        // Check upcoming street turn angle to decelerate realistically at corners
        var turnAngleDeg = 0.0f
        if (segIdx + 2 < routeWaypoints.size) {
            val a = routeWaypoints[segIdx]
            val b = routeWaypoints[segIdx + 1]
            val c = routeWaypoints[segIdx + 2]
            val brg1 = computeCompassBearing(a.first, a.second, b.first, b.second)
            val brg2 = computeCompassBearing(b.first, b.second, c.first, c.second)
            var diff = kotlin.math.abs(brg2 - brg1)
            if (diff > 180.0f) diff = 360.0f - diff
            turnAngleDeg = diff
        }

        // Smooth organic oscillation + turn slowdown
        val wave = (kotlin.math.sin(routeTickCounter * 0.18) + 1.0) * 0.5 // 0.0 .. 1.0
        val jitter = ((System.nanoTime() % 1000L) / 1000.0 - 0.5) * 0.08
        var factor = (wave + jitter).coerceIn(0.0, 1.0)
        if (turnAngleDeg > 30.0f) {
            val turnPenalty = ((turnAngleDeg - 30.0f) / 90.0f).coerceIn(0.0f, 0.85f)
            factor *= (1.0 - turnPenalty)
        }
        val targetSpd = (minS + (maxS - minS) * factor).toFloat()
        // Smooth transition from previous tick speed (realistic vehicle inertia)
        routeSimCurrentDynamicSpeedKmh = (routeSimCurrentDynamicSpeedKmh * 0.65f + targetSpd * 0.35f).coerceIn(minS, maxS)
        return routeSimCurrentDynamicSpeedKmh
    }

    private val routeSimTickRunnable = object : Runnable {
        override fun run() {
            if (!isRouteSimRunning || isRouteSimPaused || routeWaypoints.size < 2 || routeTotalMeters <= 0.5) {
                return
            }
            routeTickCounter++
            val tickSec = 0.5 // 500ms tick (2 updates per second)

            // Find current segment index first to evaluate upcoming road curvature
            var preSegIdx = 0
            while (preSegIdx < routeCumulativeMeters.size - 2 && routeCumulativeMeters[preSegIdx + 1] < routeTraveledMeters) {
                preSegIdx++
            }
            val currentSpdKmh = computeInstantSimulationSpeedKmh(preSegIdx)
            val speedMps = (currentSpdKmh / 3.6)
            val stepMeters = speedMps * tickSec

            if (isRouteSimForward) {
                routeTraveledMeters += stepMeters
                if (routeTraveledMeters >= routeTotalMeters) {
                    if (isRouteSimLooping) {
                        routeTraveledMeters = routeTotalMeters
                        isRouteSimForward = false
                    } else {
                        routeTraveledMeters = routeTotalMeters
                        val lastPt = routeWaypoints.last()
                        val prevPt = routeWaypoints[routeWaypoints.size - 2]
                        val finalBrg = computeCompassBearing(prevPt.first, prevPt.second, lastPt.first, lastPt.second)
                        SettingsStore.pushLiveLocationStep(
                            this@MainActivity,
                            lat = lastPt.first,
                            lng = lastPt.second,
                            speedKmh = 0.0f,
                            bearing = finalBrg,
                            simActive = false
                        )
                        isRouteSimRunning = false
                        isRouteSimPaused = false
                        if (::btnPauseRouteSim.isInitialized) btnPauseRouteSim.visibility = View.GONE
                        if (::btnStartRouteSim.isInitialized) {
                            btnStartRouteSim.text = tr("🚗 بدء محاكاة المسار (1 ➔ 2)", "🚗 Start Route Sim (1 ➔ 2)")
                        }
                        updateSimCoordinateInputs(lastPt.first, lastPt.second, finalBrg)
                        updateRouteSimBannerUI()
                        updateRouteMetricsUI()
                        Toast.makeText(
                            this@MainActivity,
                            tr("🏁 وصلت إلى النقطة 2 (نهاية المسار المحدد)!", "🏁 Reached Point 2 (Destination)!"),
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }
                }
            } else {
                routeTraveledMeters -= stepMeters
                if (routeTraveledMeters <= 0.0) {
                    routeTraveledMeters = 0.0
                    isRouteSimForward = true
                }
            }

            // Interpolate exact (lat, lng) and street compass bearing at routeTraveledMeters
            var segIdx = 0
            while (segIdx < routeCumulativeMeters.size - 2 && routeCumulativeMeters[segIdx + 1] < routeTraveledMeters) {
                segIdx++
            }
            val segStartM = routeCumulativeMeters[segIdx]
            val segEndM = routeCumulativeMeters[segIdx + 1]
            val segLen = (segEndM - segStartM).coerceAtLeast(0.001)
            val frac = ((routeTraveledMeters - segStartM) / segLen).coerceIn(0.0, 1.0)

            val p1 = routeWaypoints[segIdx]
            val p2 = routeWaypoints[segIdx + 1]
            val curLat = p1.first + (p2.first - p1.first) * frac
            val curLng = p1.second + (p2.second - p1.second) * frac
            val segBearing = if (isRouteSimForward) {
                computeCompassBearing(p1.first, p1.second, p2.first, p2.second)
            } else {
                computeCompassBearing(p2.first, p2.second, p1.first, p1.second)
            }

            SettingsStore.pushLiveLocationStep(
                this@MainActivity,
                lat = curLat,
                lng = curLng,
                speedKmh = currentSpdKmh,
                bearing = segBearing,
                simActive = true
            )
            if (::btnStartRouteSim.isInitialized) {
                btnStartRouteSim.text = if (isAr) {
                    "🚗 يتحرك (${currentSpdKmh.toInt()} كم/س)"
                } else {
                    "🚗 Moving (${currentSpdKmh.toInt()} km/h)"
                }
            }
            updateSimCoordinateInputs(curLat, curLng, segBearing)
            updateRouteSimBannerUI()
            updateRouteMetricsUI()

            if (isRouteSimRunning && !isRouteSimPaused) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private fun updateSimCoordinateInputs(lat: Double, lng: Double, bearing: Float) {
        val rLat = String.format(Locale.US, "%.6f", lat)
        val rLng = String.format(Locale.US, "%.6f", lng)
        if (::edtLat.isInitialized) edtLat.setText(rLat)
        if (::edtLng.isInitialized) edtLng.setText(rLng)
        if (::mapWebView.isInitialized) {
            val pct = if (routeTotalMeters > 0) ((routeTraveledMeters / routeTotalMeters) * 100.0).coerceIn(0.0, 100.0) else 0.0
            mapWebView.evaluateJavascript("if(window.setDriverPin) window.setDriverPin($lat, $lng, $bearing, false, $pct);", null)
        }
    }

    private fun startOrResumeRouteSimulation() {
        if (routeWaypoints.size < 2 || routeTotalMeters <= 0.5) {
            if (::mapWebView.isInitialized) {
                mapWebView.evaluateJavascript(
                    "if(window.buildStreetRoute) window.buildStreetRoute($point1Lat, $point1Lng, $point2Lat, $point2Lng, false);",
                    null
                )
            }
            Toast.makeText(
                this,
                tr(
                    "🛣️ جاري رسم مسار الشوارع من النقطة 1 إلى النقطة 2...",
                    "🛣️ Building street route from Point 1 to Point 2..."
                ),
                Toast.LENGTH_SHORT
            ).show()
            mainHandler.postDelayed({
                if (routeWaypoints.size >= 2 && routeTotalMeters > 0.5) {
                    startOrResumeRouteSimulation()
                }
            }, 900L)
            return
        }
        if (::chkFakeLoc.isInitialized && !chkFakeLoc.isChecked) {
            chkFakeLoc.isChecked = true
        }
        if (routeTraveledMeters >= routeTotalMeters - 1.0) {
            routeTraveledMeters = 0.0
            isRouteSimForward = true
        }
        routeSimCurrentDynamicSpeedKmh = if (isVariableSpeedMode) {
            (routeSimMinSpeedKmh + routeSimMaxSpeedKmh) / 2.0f
        } else {
            routeSimSpeedKmh
        }
        isRouteSimRunning = true
        isRouteSimPaused = false
        if (::btnPauseRouteSim.isInitialized) {
            btnPauseRouteSim.visibility = View.VISIBLE
            btnPauseRouteSim.text = "⏸️"
        }
        mainHandler.removeCallbacks(routeSimTickRunnable)
        mainHandler.post(routeSimTickRunnable)
        val toastMsg = if (isVariableSpeedMode) {
            tr(
                "🚗 بدأت محاكاة المسار (1 ➔ 2) بسرعات مختلفة (${routeSimMinSpeedKmh.toInt()}-${routeSimMaxSpeedKmh.toInt()} كم/س)",
                "🚗 Started 2-Point route simulation with variable speeds (${routeSimMinSpeedKmh.toInt()}-${routeSimMaxSpeedKmh.toInt()} km/h)"
            )
        } else {
            tr(
                "🚗 بدأت محاكاة المسار (1 ➔ 2) بسرعة مخصصة ${routeSimSpeedKmh.toInt()} كم/س",
                "🚗 Started 2-Point route simulation at custom speed ${routeSimSpeedKmh.toInt()} km/h"
            )
        }
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun togglePauseRouteSimulation() {
        if (!isRouteSimRunning) return
        isRouteSimPaused = !isRouteSimPaused
        if (isRouteSimPaused) {
            mainHandler.removeCallbacks(routeSimTickRunnable)
            val lat = edtLat.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLat(this)
            val lng = edtLng.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLng(this)
            SettingsStore.pushLiveLocationStep(this, lat, lng, 0.0f, SettingsStore.getRouteSimBearing(this), false)
            if (::btnPauseRouteSim.isInitialized) btnPauseRouteSim.text = "▶️"
            Toast.makeText(
                this,
                tr("⏸️ تم إيقاف حركة السيارة مؤقتاً في نفس النقطة", "⏸️ Vehicle movement paused at current point"),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            if (::btnPauseRouteSim.isInitialized) btnPauseRouteSim.text = "⏸️"
            mainHandler.post(routeSimTickRunnable)
            Toast.makeText(
                this,
                tr("▶️ تم استئناف محاكاة المسار", "▶️ Route simulation resumed"),
                Toast.LENGTH_SHORT
            ).show()
        }
        updateRouteSimBannerUI()
    }

    private fun stopRouteSimulation() {
        isRouteSimRunning = false
        isRouteSimPaused = false
        routeTraveledMeters = 0.0
        mainHandler.removeCallbacks(routeSimTickRunnable)
        val lat = if (::edtLat.isInitialized) (edtLat.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLat(this)) else SettingsStore.getFakeLat(this)
        val lng = if (::edtLng.isInitialized) (edtLng.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLng(this)) else SettingsStore.getFakeLng(this)
        SettingsStore.setRouteSimActive(this, false)
        if (lat != 0.0 && lng != 0.0 && SettingsStore.isFakeLocation(this)) {
            SettingsStore.pushLiveLocationStep(this, lat, lng, 0.0f, 0.0f, false)
        }
        if (::btnPauseRouteSim.isInitialized) btnPauseRouteSim.visibility = View.GONE
        if (::btnStartRouteSim.isInitialized) {
            btnStartRouteSim.text = tr("🚗 بدء محاكاة المسار (1 ➔ 2)", "🚗 Start Route Sim (1 ➔ 2)")
        }
        updateRouteSimBannerUI()
        updateRouteMetricsUI()
    }

    private fun updateRouteSimBannerUI() {
        if (!::tvRouteSimStatus.isInitialized) return
        val lat = if (::edtLat.isInitialized) edtLat.text.toString() else ""
        val lng = if (::edtLng.isInitialized) edtLng.text.toString() else ""
        when {
            isRouteSimRunning && !isRouteSimPaused -> {
                val remMeters = (routeTotalMeters - routeTraveledMeters).coerceAtLeast(0.0)
                val remKm = remMeters / 1000.0
                val activeSpd = if (isVariableSpeedMode) routeSimCurrentDynamicSpeedKmh else routeSimSpeedKmh
                val spdMps = (activeSpd / 3.6).coerceAtLeast(0.5)
                val remSec = (remMeters / spdMps).toInt()
                tvRouteSimStatus.text = if (isAr) {
                    String.format(
                        Locale.US,
                        "🚗 يتحرك من نقطة 1 إلى 2 • السرعة الآن: %.0f كم/س • المتبقي: %.2f كم (%d:%02d د) • 📍 %s, %s",
                        activeSpd, remKm, remSec / 60, remSec % 60, lat, lng
                    )
                } else {
                    String.format(
                        Locale.US,
                        "🚗 Moving Point 1 ➔ 2 • Current Speed: %.0f km/h • Remaining: %.2f km (%d:%02d) • 📍 %s, %s",
                        activeSpd, remKm, remSec / 60, remSec % 60, lat, lng
                    )
                }
                tvRouteSimStatus.setTextColor(Color.parseColor("#047857"))
                tvRouteSimStatus.background = roundedBg("#ECFDF5", 12f, "#6EE7B7", 2)
            }
            isRouteSimRunning && isRouteSimPaused -> {
                tvRouteSimStatus.text = tr(
                    "⏸️ محاكاة المسار متوقفة مؤقتاً عند 📍 $lat, $lng (اضغط ▶️ للاستئناف)",
                    "⏸️ Route simulation paused at 📍 $lat, $lng (Tap ▶️ to resume)"
                )
                tvRouteSimStatus.setTextColor(Color.parseColor("#B45309"))
                tvRouteSimStatus.background = roundedBg("#FFFBEB", 12f, "#FCD34D", 2)
            }
            SettingsStore.isFakeLocation(this) -> {
                tvRouteSimStatus.text = tr(
                    "🟢 الموقع المزيف نشط وثابت عند 📍 $lat, $lng",
                    "🟢 Fake location active and pinned at 📍 $lat, $lng"
                )
                tvRouteSimStatus.setTextColor(Color.parseColor("#059669"))
                tvRouteSimStatus.background = roundedBg("#F8FAFC", 12f, "#E2E8F0", 2)
            }
            else -> {
                tvRouteSimStatus.text = tr(
                    "⚪ اضغط على الخريطة لتعيين النقطة 1 والنقطة 2 أو تثبيت موقعك",
                    "⚪ Tap on the map to set Point 1 and Point 2 or pin your location"
                )
                tvRouteSimStatus.setTextColor(Color.parseColor("#475569"))
                tvRouteSimStatus.background = roundedBg("#F8FAFC", 12f, "#E2E8F0", 2)
            }
        }
    }

    private fun updateRouteMetricsUI() {
        if (!::tvRouteMetricsBadge.isInitialized) return
        val speedSummary = if (isVariableSpeedMode) {
            if (isAr) {
                "بسرعات مختلفة (${routeSimMinSpeedKmh.toInt()}-${routeSimMaxSpeedKmh.toInt()} كم/س)"
            } else {
                "Variable Speeds (${routeSimMinSpeedKmh.toInt()}-${routeSimMaxSpeedKmh.toInt()} km/h)"
            }
        } else {
            if (isAr) {
                "سرعة مخصصة (${routeSimSpeedKmh.toInt()} كم/س)"
            } else {
                "Custom Speed (${routeSimSpeedKmh.toInt()} km/h)"
            }
        }
        if (routeTotalMeters > 1.0 && routeWaypoints.size >= 2) {
            val totalKm = routeTotalMeters / 1000.0
            val remKm = ((routeTotalMeters - routeTraveledMeters).coerceAtLeast(0.0)) / 1000.0
            val refSpd = if (isVariableSpeedMode) ((routeSimMinSpeedKmh + routeSimMaxSpeedKmh) / 2.0f) else routeSimSpeedKmh
            val mps = (refSpd / 3.6).coerceAtLeast(0.5)
            val etaSec = ((routeTotalMeters - routeTraveledMeters).coerceAtLeast(0.0) / mps).toInt()
            tvRouteMetricsBadge.text = if (isAr) {
                String.format(
                    Locale.US,
                    "🛣️ مسار (1 ➔ 2): %.2f كم (المتبقي %.2f كم) • %s • الوصول ≈ %d د %d ث (اضغط لتعديل السرعة ⚡)",
                    totalKm, remKm, speedSummary, etaSec / 60, etaSec % 60
                )
            } else {
                String.format(
                    Locale.US,
                    "🛣️ Route (1 ➔ 2): %.2f km (Rem %.2f km) • %s • ETA ≈ %d m %d s (Tap to edit speed ⚡)",
                    totalKm, remKm, speedSummary, etaSec / 60, etaSec % 60
                )
            }
        } else {
            tvRouteMetricsBadge.text = tr(
                "🛣️ المسار (1 ➔ 2): حدد النقطة 1 والنقطة 2 على الخريطة • الوضع الحالي: $speedSummary",
                "🛣️ Route (1 ➔ 2): Set Point 1 & Point 2 on the map • Current Mode: $speedSummary"
            )
        }
    }

    private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun computeCompassBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon)
        val brg = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        return if (brg.isNaN()) 0.0f else brg.toFloat()
    }

    private fun setupGoogleMapWebView() {
        val gMapsKey = SettingsStore.getGoogleMapsApiKey(this).replace("'", "")
        val mapLang = if (isAr) "ar" else "en"
        val mapDir = if (isAr) "rtl" else "ltr"
        val hudDefault = tr("خريطة المسار • اضغط لتعيين النقطة 2 ورسم الطريق 🛣️", "Route Map • Tap to set Point 2 & build route 🛣️")
        val hudPoint1 = tr("خريطة المسار • اضغط لتعيين النقطة 1 (الانطلاق) 1️⃣", "Route Map • Tap to set Point 1 (Start) 1️⃣")
        val hudPoint2 = tr("خريطة المسار • اضغط لتعيين النقطة 2 (الوصول) 2️⃣", "Route Map • Tap to set Point 2 (Destination) 2️⃣")
        val hudTeleport = tr("خريطة المسار • اضغط لتثبيت الموقع الفوري 📍", "Route Map • Tap for instant location pin 📍")
        val hudMovingPrefix = tr("🚗 يتحرك من النقطة 1 إلى 2 (", "🚗 Moving Point 1 ➔ 2 (")

        val html = """
            <!DOCTYPE html>
            <html dir="$mapDir" lang="$mapLang">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; font-family: sans-serif; background: #e5e3df; }
                    .gmap-badge {
                        position: absolute; bottom: 8px; left: 8px; z-index: 999;
                        background: rgba(255,255,255,0.94); color: #1a73e8;
                        padding: 5px 12px; border-radius: 8px; font-size: 11px; font-weight: bold;
                        box-shadow: 0 1px 4px rgba(0,0,0,0.25); pointer-events: none;
                    }
                    .car-marker-icon {
                        display: flex; align-items: center; justify-content: center;
                        width: 36px; height: 36px; border-radius: 50%;
                        background: #1a73e8; color: #fff; font-size: 19px;
                        border: 3px solid #ffffff; box-shadow: 0 2px 8px rgba(0,0,0,0.45);
                    }
                    .pt1-marker-icon {
                        display: flex; align-items: center; justify-content: center;
                        width: 30px; height: 30px; border-radius: 50%;
                        background: #059669; color: #fff; font-size: 14px; font-weight: bold;
                        border: 2px solid #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,0.4);
                    }
                    .pt2-marker-icon {
                        display: flex; align-items: center; justify-content: center;
                        width: 30px; height: 30px; border-radius: 50%;
                        background: #dc2626; color: #fff; font-size: 14px; font-weight: bold;
                        border: 2px solid #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,0.4);
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <div class="gmap-badge" id="hudBadge">$hudDefault</div>
                <script>
                    var pt1Lat = $point1Lat;
                    var pt1Lng = $point1Lng;
                    var pt2Lat = $point2Lat;
                    var pt2Lng = $point2Lng;
                    var curLat = pt1Lat;
                    var curLng = pt1Lng;
                    var clickMode = 'point2';
                    var googleApiKey = '$gMapsKey';

                    var map = L.map('map', { zoomControl: true }).setView([pt1Lat, pt1Lng], 14);

                    var googleRoadmap = L.tileLayer('https://mt1.google.com/vt/lyrs=m&hl=$mapLang&x={x}&y={y}&z={z}', {
                        maxZoom: 21,
                        attribution: 'Google Maps'
                    });
                    var googleHybrid = L.tileLayer('https://mt1.google.com/vt/lyrs=y&hl=$mapLang&x={x}&y={y}&z={z}', {
                        maxZoom: 21,
                        attribution: 'Google Satellite'
                    });
                    var googleTraffic = L.tileLayer('https://mt1.google.com/vt/lyrs=m,traffic&hl=$mapLang&x={x}&y={y}&z={z}', {
                        maxZoom: 21,
                        attribution: 'Google Traffic'
                    });
                    googleRoadmap.addTo(map);
                    var currentLayer = googleRoadmap;

                    window.setGoogleMapLayer = function(mode) {
                        map.removeLayer(currentLayer);
                        if (mode === 'hybrid') currentLayer = googleHybrid;
                        else if (mode === 'traffic') currentLayer = googleTraffic;
                        else currentLayer = googleRoadmap;
                        currentLayer.addTo(map);
                    };

                    window.invalidateMapSize = function() {
                        setTimeout(function() { map.invalidateSize(); }, 120);
                    };

                    var driverIcon = L.divIcon({
                        className: '',
                        html: '<div class="car-marker-icon" id="driverCarBox">🚗</div>',
                        iconSize: [36, 36],
                        iconAnchor: [18, 18]
                    });
                    var pt1Icon = L.divIcon({
                        className: '',
                        html: '<div class="pt1-marker-icon">1</div>',
                        iconSize: [30, 30],
                        iconAnchor: [15, 15]
                    });
                    var pt2Icon = L.divIcon({
                        className: '',
                        html: '<div class="pt2-marker-icon">2</div>',
                        iconSize: [30, 30],
                        iconAnchor: [15, 15]
                    });

                    var point1Marker = L.marker([pt1Lat, pt1Lng], { icon: pt1Icon, draggable: true }).addTo(map);
                    var point2Marker = L.marker([pt2Lat, pt2Lng], { icon: pt2Icon, draggable: true }).addTo(map);
                    var driverMarker = L.marker([curLat, curLng], { icon: driverIcon, draggable: true, zIndexOffset: 500 }).addTo(map);
                    var routePolyline = null;
                    var routeShadowLine = null;

                    window.setClickMode = function(m) {
                        clickMode = m;
                        var badge = document.getElementById('hudBadge');
                        if (badge) {
                            if (m === 'point1') badge.innerText = '$hudPoint1';
                            else if (m === 'point2') badge.innerText = '$hudPoint2';
                            else badge.innerText = '$hudTeleport';
                        }
                    };

                    point1Marker.on('dragend', function() {
                        var pos = point1Marker.getLatLng();
                        pt1Lat = pos.lat;
                        pt1Lng = pos.lng;
                        if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                            window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                        }
                        window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                    });

                    point2Marker.on('dragend', function() {
                        var pos = point2Marker.getLatLng();
                        pt2Lat = pos.lat;
                        pt2Lng = pos.lng;
                        if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                            window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                        }
                        window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                    });

                    driverMarker.on('dragend', function() {
                        var pos = driverMarker.getLatLng();
                        curLat = pos.lat;
                        curLng = pos.lng;
                        if (window.AndroidBridge && window.AndroidBridge.onLocationMoved) {
                            window.AndroidBridge.onLocationMoved(curLat, curLng);
                        }
                    });

                    map.on('click', function(e) {
                        var targetLat = e.latlng.lat;
                        var targetLng = e.latlng.lng;
                        if (clickMode === 'teleport') {
                            curLat = targetLat;
                            curLng = targetLng;
                            driverMarker.setLatLng([curLat, curLng]);
                            if (window.AndroidBridge && window.AndroidBridge.onLocationMoved) {
                                window.AndroidBridge.onLocationMoved(curLat, curLng);
                            }
                        } else if (clickMode === 'point1') {
                            pt1Lat = targetLat;
                            pt1Lng = targetLng;
                            curLat = targetLat;
                            curLng = targetLng;
                            point1Marker.setLatLng([pt1Lat, pt1Lng]);
                            driverMarker.setLatLng([pt1Lat, pt1Lng]);
                            if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                                window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, true);
                            }
                            window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                        } else {
                            pt2Lat = targetLat;
                            pt2Lng = targetLng;
                            point2Marker.setLatLng([pt2Lat, pt2Lng]);
                            if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                                window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                            }
                            window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, true);
                        }
                    });

                    window.setDriverPin = function(newLat, newLng, bearing, shouldPan, pct) {
                        curLat = newLat;
                        curLng = newLng;
                        driverMarker.setLatLng([newLat, newLng]);
                        if (shouldPan) {
                            map.panTo([newLat, newLng]);
                        }
                        var badge = document.getElementById('hudBadge');
                        if (badge && pct !== undefined && pct > 0) {
                            badge.innerText = '$hudMovingPrefix' + pct.toFixed(0) + '%)';
                        }
                    };

                    window.clearSimulatedRoute = function() {
                        if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
                        if (routeShadowLine) { map.removeLayer(routeShadowLine); routeShadowLine = null; }
                    };

                    function drawRouteCoordinates(coordsLatLng, promptSpeedDialog) {
                        window.clearSimulatedRoute();
                        if (!coordsLatLng || coordsLatLng.length < 2) return;
                        var startPt = coordsLatLng[0];
                        var endPt = coordsLatLng[coordsLatLng.length - 1];
                        point1Marker.setLatLng(startPt);
                        point2Marker.setLatLng(endPt);
                        routeShadowLine = L.polyline(coordsLatLng, { color: '#1e3a8a', weight: 9, opacity: 0.35 }).addTo(map);
                        routePolyline = L.polyline(coordsLatLng, { color: '#1a73e8', weight: 6, opacity: 0.95 }).addTo(map);
                        map.fitBounds(routePolyline.getBounds(), { padding: [35, 35] });

                        if (window.AndroidBridge && window.AndroidBridge.onRoutePathReady) {
                            window.AndroidBridge.onRoutePathReady(JSON.stringify(coordsLatLng), !!promptSpeedDialog);
                        }
                    }

                    function decodeGooglePolyline(encoded) {
                        var points = [];
                        var index = 0, len = encoded.length;
                        var lat = 0, lng = 0;
                        while (index < len) {
                            var b, shift = 0, result = 0;
                            do {
                                b = encoded.charCodeAt(index++) - 63;
                                result |= (b & 0x1f) << shift;
                                shift += 5;
                            } while (b >= 0x20);
                            var dlat = ((result & 1) !== 0 ? ~(result >> 1) : (result >> 1));
                            lat += dlat;
                            shift = 0;
                            result = 0;
                            do {
                                b = encoded.charCodeAt(index++) - 63;
                                result |= (b & 0x1f) << shift;
                                shift += 5;
                            } while (b >= 0x20);
                            var dlng = ((result & 1) !== 0 ? ~(result >> 1) : (result >> 1));
                            lng += dlng;
                            points.push([lat / 1E5, lng / 1E5]);
                        }
                        return points;
                    }

                    window.buildStreetRoute = function(startLat, startLng, endLat, endLng, promptSpeedDialog) {
                        pt1Lat = startLat;
                        pt1Lng = startLng;
                        pt2Lat = endLat;
                        pt2Lng = endLng;
                        curLat = startLat;
                        curLng = startLng;
                        point1Marker.setLatLng([startLat, startLng]);
                        point2Marker.setLatLng([endLat, endLng]);
                        driverMarker.setLatLng([startLat, startLng]);

                        if (googleApiKey && googleApiKey.length > 10) {
                            var gUrl = 'https://maps.googleapis.com/maps/api/directions/json?origin='
                                + startLat + ',' + startLng + '&destination=' + endLat + ',' + endLng
                                + '&mode=driving&key=' + encodeURIComponent(googleApiKey);
                            fetch(gUrl)
                                .then(function(r) { return r.json(); })
                                .then(function(gData) {
                                    if (gData && gData.routes && gData.routes.length > 0 && gData.routes[0].overview_polyline && gData.routes[0].overview_polyline.points) {
                                        var decoded = decodeGooglePolyline(gData.routes[0].overview_polyline.points);
                                        if (decoded.length >= 2) {
                                            drawRouteCoordinates(decoded, promptSpeedDialog);
                                            return;
                                        }
                                    }
                                    throw new Error('Fallback to OSRM');
                                })
                                .catch(function() {
                                    fetchOsrmStreetRoute(startLat, startLng, endLat, endLng, promptSpeedDialog);
                                });
                            return;
                        }
                        fetchOsrmStreetRoute(startLat, startLng, endLat, endLng, promptSpeedDialog);
                    };

                    function fetchOsrmStreetRoute(startLat, startLng, endLat, endLng, promptSpeedDialog) {
                        var osrmUrl = 'https://router.project-osrm.org/route/v1/driving/'
                            + startLng + ',' + startLat + ';' + endLng + ',' + endLat
                            + '?overview=full&geometries=geojson&steps=true';

                        fetch(osrmUrl)
                            .then(function(r) { return r.json(); })
                            .then(function(data) {
                                if (data && data.routes && data.routes.length > 0 && data.routes[0].geometry && data.routes[0].geometry.coordinates) {
                                    var rawCoords = data.routes[0].geometry.coordinates;
                                    var latLngs = [];
                                    for (var i = 0; i < rawCoords.length; i++) {
                                        latLngs.push([rawCoords[i][1], rawCoords[i][0]]);
                                    }
                                    drawRouteCoordinates(latLngs, promptSpeedDialog);
                                } else {
                                    throw new Error('Fallback to segmented route');
                                }
                            })
                            .catch(function() {
                                var pts = [];
                                var steps = 40;
                                for (var s = 0; s <= steps; s++) {
                                    var f = s / steps;
                                    pts.push([
                                        startLat + (endLat - startLat) * f,
                                        startLng + (endLng - startLng) * f
                                    ]);
                                }
                                drawRouteCoordinates(pts, promptSpeedDialog);
                            });
                    }

                    window.searchPlaceOnGoogleMap = function(query) {
                        var url = 'https://nominatim.openstreetmap.org/search?format=json&accept-language=$mapLang&limit=1&q=' + encodeURIComponent(query);
                        fetch(url)
                            .then(function(r) { return r.json(); })
                            .then(function(arr) {
                                if (arr && arr.length > 0) {
                                    var lt = parseFloat(arr[0].lat);
                                    var lg = parseFloat(arr[0].lon);
                                    map.setView([lt, lg], 16);
                                    if (clickMode === 'teleport') {
                                        curLat = lt; curLng = lg;
                                        driverMarker.setLatLng([lt, lg]);
                                        if (window.AndroidBridge && window.AndroidBridge.onLocationMoved) {
                                            window.AndroidBridge.onLocationMoved(lt, lg);
                                        }
                                    } else if (clickMode === 'point1') {
                                        pt1Lat = lt; pt1Lng = lg;
                                        point1Marker.setLatLng([lt, lg]);
                                        if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                                            window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, true);
                                        }
                                        window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                                    } else {
                                        pt2Lat = lt; pt2Lng = lg;
                                        point2Marker.setLatLng([lt, lg]);
                                        if (window.AndroidBridge && window.AndroidBridge.onTwoPointsUpdated) {
                                            window.AndroidBridge.onTwoPointsUpdated(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                                        }
                                        window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, true);
                                    }
                                }
                            })
                            .catch(function() {});
                    };

                    // Automatically draw the initial 2-Point route on load
                    setTimeout(function() {
                        window.buildStreetRoute(pt1Lat, pt1Lng, pt2Lat, pt2Lng, false);
                    }, 350);
                </script>
            </body>
            </html>
        """.trimIndent()

        mapWebView.loadDataWithBaseURL("https://maps.google.com", html, "text/html", "UTF-8", null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onLocationMoved(lat: Double, lng: Double) {
            runOnUiThread {
                stopRouteSimulation()
                applyImmediateSpoofLocation(lat, lng, panMap = false)
            }
        }

        @JavascriptInterface
        fun onTwoPointsUpdated(p1Lat: Double, p1Lng: Double, p2Lat: Double, p2Lng: Double, autoSwitchToPoint2: Boolean) {
            runOnUiThread {
                point1Lat = p1Lat
                point1Lng = p1Lng
                point2Lat = p2Lat
                point2Lng = p2Lng
                SettingsStore.setPoint1(this@MainActivity, point1Lat, point1Lng)
                SettingsStore.setPoint2(this@MainActivity, point2Lat, point2Lng)
                updateTwoPointSummaryUI()
                applyImmediateSpoofLocation(point1Lat, point1Lng, panMap = false)
                if (autoSwitchToPoint2) {
                    setMapTapSelectionMode("point2")
                    Toast.makeText(
                        this@MainActivity,
                        tr("1️⃣ تم تحديد النقطة 1 • اضغط الآن على النقطة 2 (الوصول)", "1️⃣ Point 1 set • Now tap Point 2 (Destination)"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun onRoutePathReady(coordsJson: String, promptSpeedDialog: Boolean) {
            runOnUiThread {
                try {
                    val arr = JSONArray(coordsJson)
                    if (arr.length() < 2) return@runOnUiThread
                    routeWaypoints.clear()
                    for (i in 0 until arr.length()) {
                        val pt = arr.optJSONArray(i) ?: continue
                        val lt = pt.optDouble(0, 0.0)
                        val lg = pt.optDouble(1, 0.0)
                        if (lt != 0.0 && lg != 0.0) {
                            routeWaypoints.add(lt to lg)
                        }
                    }
                    if (routeWaypoints.size < 2) return@runOnUiThread
                    val cum = DoubleArray(routeWaypoints.size)
                    var totalM = 0.0
                    cum[0] = 0.0
                    for (i in 1 until routeWaypoints.size) {
                        val prev = routeWaypoints[i - 1]
                        val curr = routeWaypoints[i]
                        totalM += computeDistanceMeters(prev.first, prev.second, curr.first, curr.second)
                        cum[i] = totalM
                    }
                    routeCumulativeMeters = cum
                    routeTotalMeters = totalM
                    routeTraveledMeters = 0.0
                    isRouteSimForward = true
                    val startPt = routeWaypoints.first()
                    val endPt = routeWaypoints.last()
                    point1Lat = startPt.first
                    point1Lng = startPt.second
                    point2Lat = endPt.first
                    point2Lng = endPt.second
                    SettingsStore.setPoint1(this@MainActivity, point1Lat, point1Lng)
                    SettingsStore.setPoint2(this@MainActivity, point2Lat, point2Lng)
                    updateTwoPointSummaryUI()
                    updateRouteMetricsUI()
                    if (promptSpeedDialog) {
                        showRouteSpeedCustomizationDialog(startImmediately = true)
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun createFiltersCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = tr("⚡ القبول التلقائي وفلاتر المسافة والسعر", "⚡ Auto-Accept & Distance / Price Filters")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkAutoAccept = CheckBox(this).apply {
            text = tr("تفعيل القبول التلقائي الفوري للطلبات المطابقة للشروط", "Enable Instant Auto-Accept for Matching Orders")
            isChecked = SettingsStore.isAutoAccept(this@MainActivity)
        }
        layout.addView(chkAutoAccept)

        chkAutoReject = CheckBox(this).apply {
            text = tr("تفعيل الرفض التلقائي الفوري للطلبات المخالفة للمسافة أو السعر", "Enable Instant Auto-Reject for Orders Outside Limits")
            isChecked = SettingsStore.isAutoReject(this@MainActivity)
        }
        layout.addView(chkAutoReject)

        chkDryRun = CheckBox(this).apply {
            text = tr("وضع التجربة فقط (تسجيل وإشعار بالطلبات بدون قبول أو رفض فعلي)", "Dry-Run Mode (Log & notify orders without accepting or rejecting)")
            isChecked = SettingsStore.isDryRun(this@MainActivity)
        }
        layout.addView(chkDryRun)

        chkStayInBackground = CheckBox(this).apply {
            text = tr("عدم فتح شاشة التطبيق عند معالجة الطلب تلقائياً (البقاء في الخلفية)", "Keep Target App in Background When Orders Are Handled Automatically")
            isChecked = SettingsStore.isStayInBackground(this@MainActivity)
        }
        layout.addView(chkStayInBackground)

        chkSilenceAlertTone = CheckBox(this).apply {
            text = tr("كتم صوت تنبيه الطلب المزعج عند معالجته تلقائياً", "Silence Order Alert Tone When Handled Automatically")
            isChecked = SettingsStore.isSilenceAlertTone(this@MainActivity)
        }
        layout.addView(chkSilenceAlertTone)

        chkSoundAlert = CheckBox(this).apply {
            text = tr("تشغيل نغمة تنبيه خفيفة عند قبول الطلب تلقائياً", "Play Short Notification Beep When an Order Is Auto-Accepted")
            isChecked = SettingsStore.isSoundEnabled(this@MainActivity)
        }
        layout.addView(chkSoundAlert)

        chkStrictVersionCheck = CheckBox(this).apply {
            text = tr("التحقق الصارم من توافق إصدار التطبيق قبل التشغيل", "Strict Target App Version Verification")
            isChecked = SettingsStore.isStrictVersionCheck(this@MainActivity)
        }
        layout.addView(chkStrictVersionCheck)

        layout.addView(
            createLabel(
                tr(
                    "الحد الأدنى لسعر التوصيل (ضع 0 لإلغاء الشرط):",
                    "Minimum Delivery Price (Set 0 to disable limit):"
                )
            )
        )
        edtMinPrice = createInput(SettingsStore.getMinPrice(this).toString())
        layout.addView(edtMinPrice)

        layout.addView(
            createLabel(
                tr(
                    "الحد الأقصى لبعد المطعم عنك بالكيلومتر (ضع 0 لإلغاء الشرط):",
                    "Max Distance to Restaurant in km (Set 0 to disable limit):"
                )
            )
        )
        edtMaxDistRest = createInput(SettingsStore.getMaxDistRest(this).toString())
        layout.addView(edtMaxDistRest)

        layout.addView(
            createLabel(
                tr(
                    "الحد الأقصى لبعد العميل عن المطعم بالكيلومتر (ضع 0 لإلغاء الشرط):",
                    "Max Distance to Customer in km (Set 0 to disable limit):"
                )
            )
        )
        edtMaxDistCust = createInput(SettingsStore.getMaxDistCust(this).toString())
        layout.addView(edtMaxDistCust)

        isExtraOrderEnabled = SettingsStore.isExtraOrderEnabled(this)
        layout.addView(
            createLabel(
                tr(
                    "عدد الطلبات المسموح بها (مغلق = 2 طلبات • مفعل = 3 طلبات):",
                    "Allowed Orders Count (OFF = 2 Orders • ON = 3 Orders):"
                )
            )
        )
        btnExtraOrder = Button(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(24, 22, 24, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 6, 0, 4)
            }
            setOnClickListener {
                isExtraOrderEnabled = !isExtraOrderEnabled
                SettingsStore.setExtraOrderEnabled(this@MainActivity, isExtraOrderEnabled)
                updateExtraOrderButtonUI()
                refreshOrdersLogUI()
                val msg = if (isExtraOrderEnabled) {
                    tr("✅ تم تفعيل طلب إضافي (عدد الطلبات الآن: 3)", "✅ Extra Order enabled (Orders count is now: 3)")
                } else {
                    tr("⚪ تم إغلاق طلب إضافي (العدد الافتراضي للطلبات: 2)", "⚪ Extra Order disabled (Default orders count: 2)")
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        updateExtraOrderButtonUI()
        layout.addView(btnExtraOrder)

        card.addView(layout)
        return card
    }

    private fun updateExtraOrderButtonUI() {
        if (!::btnExtraOrder.isInitialized) return
        if (isExtraOrderEnabled) {
            btnExtraOrder.text = tr("➕ طلب إضافي: مفعل ✅ (عدد الطلبات: 3)", "➕ Extra Order: ON ✅ (Orders: 3)")
            btnExtraOrder.setBackgroundColor(Color.parseColor("#059669"))
            btnExtraOrder.setTextColor(Color.WHITE)
        } else {
            btnExtraOrder.text = tr("➕ طلب إضافي: مغلق (عدد الطلبات: 2)", "➕ Extra Order: OFF (Orders: 2)")
            btnExtraOrder.setBackgroundColor(Color.parseColor("#475569"))
            btnExtraOrder.setTextColor(Color.WHITE)
        }
    }

    private fun createPollingCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = tr("🌐 السحب النشط للطلبات من السيرفر في الخلفية", "🌐 Active Background Server Polling")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = tr(
                "يفحص السيرفر بشكل متكرر لالتقاط الطلبات المتاحة فوراً قبل وصولها لبقية السائقين.",
                "Repeatedly checks the backend server to capture available orders immediately."
            )
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(desc)

        chkActivePolling = CheckBox(this).apply {
            text = tr("تفعيل السحب النشط من السيرفر", "Enable Active Server Polling")
            isChecked = SettingsStore.isActivePolling(this@MainActivity)
            textSize = 14f
        }
        layout.addView(chkActivePolling)

        layout.addView(createLabel(tr("الفاصل الزمني للفحص بالثواني (مثال 0.8):", "Polling Interval in Seconds (e.g. 0.8):")))
        edtPollInterval = createInput(SettingsStore.getPollInterval(this).toString())
        layout.addView(edtPollInterval)

        layout.addView(createLabel(tr("عدد محاولات القبول المتوازية الفورية:", "Parallel Instant Acceptance Attempts:")))
        edtParallelRequests = createInput(SettingsStore.getParallelRequests(this).toString())
        layout.addView(edtParallelRequests)

        card.addView(layout)
        return card
    }

    private fun createStyledCard(): CardView {
        return CardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 12, 0, 4)
        }
    }

    private fun createInput(initialValue: String): EditText {
        return EditText(this).apply {
            setText(if (initialValue == "0.0") "0" else initialValue)
            textSize = 14f
            setSingleLine(true)
        }
    }

    private fun generateRandomAndroidId(): String {
        val chars = "0123456789abcdef"
        val rnd = Random()
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(chars[rnd.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun saveAllSettings() {
        val master = swMaster.isChecked

        val spoofId = chkAndroidId.isChecked
        val customId = edtAndroidId.text.toString().trim()

        val fixLoc = chkFixLoc.isChecked
        val fakeLoc = chkFakeLoc.isChecked
        val lat = edtLat.text.toString().toDoubleOrNull() ?: 0.0
        val lng = edtLng.text.toString().toDoubleOrNull() ?: 0.0

        val autoAccept = chkAutoAccept.isChecked
        val autoReject = chkAutoReject.isChecked
        val dryRun = chkDryRun.isChecked
        val minPrice = edtMinPrice.text.toString().toDoubleOrNull() ?: 0.0
        val maxRest = edtMaxDistRest.text.toString().toDoubleOrNull() ?: 0.0
        val maxCust = edtMaxDistCust.text.toString().toDoubleOrNull() ?: 0.0

        val activePolling = chkActivePolling.isChecked
        val pollInterval = edtPollInterval.text.toString().toFloatOrNull() ?: 0.8f
        val parallelReqs = edtParallelRequests.text.toString().toIntOrNull() ?: 3

        SettingsStore.setMasterRunning(this, master)
        SettingsStore.setSpoofAndroidId(this, spoofId)
        SettingsStore.setSpoofedAndroidId(this, customId)

        SettingsStore.setFixLocation(this, fixLoc)
        SettingsStore.setFakeLocation(this, fakeLoc)
        SettingsStore.setFakeLat(this, lat)
        SettingsStore.setFakeLng(this, lng)
        SettingsStore.setPoint1(this, point1Lat, point1Lng)
        SettingsStore.setPoint2(this, point2Lat, point2Lng)
        SettingsStore.setVariableSpeedEnabled(this, isVariableSpeedMode)
        SettingsStore.setRouteSimMinSpeedKmh(this, routeSimMinSpeedKmh)
        SettingsStore.setRouteSimMaxSpeedKmh(this, routeSimMaxSpeedKmh)

        SettingsStore.setAutoAccept(this, autoAccept)
        SettingsStore.setAutoReject(this, autoReject)
        SettingsStore.setDryRun(this, dryRun)
        SettingsStore.setStayInBackground(this, chkStayInBackground.isChecked)
        SettingsStore.setSilenceAlertTone(this, chkSilenceAlertTone.isChecked)
        SettingsStore.setSoundEnabled(this, chkSoundAlert.isChecked)
        SettingsStore.setStrictVersionCheck(this, chkStrictVersionCheck.isChecked)
        SettingsStore.setShowFoodPrice(this, true)
        SettingsStore.setExtraOrderEnabled(this, isExtraOrderEnabled)
        SettingsStore.setMinPrice(this, minPrice)
        SettingsStore.setMaxDistRest(this, maxRest)
        SettingsStore.setMaxDistCust(this, maxCust)

        SettingsStore.setActivePolling(this, activePolling)
        SettingsStore.setPollInterval(this, pollInterval)
        SettingsStore.setParallelRequests(this, parallelReqs)

        if (::chkHookEarlyCloaker.isInitialized) {
            SettingsStore.setHookEarlyCloaker(this, chkHookEarlyCloaker.isChecked)
            SettingsStore.setHookSecCloaker(this, chkHookSecCloaker.isChecked)
            SettingsStore.setHookLocation(this, chkHookLocation.isChecked)
            SettingsStore.setHookDeviceSpoofer(this, chkHookDeviceSpoofer.isChecked)
            SettingsStore.setHookOrders(this, chkHookOrders.isChecked)
            SettingsStore.setHookBootstrapLoop(this, chkHookBootstrapLoop.isChecked)
        }

        refreshOrdersLogUI()
        Toast.makeText(
            this,
            tr("✅ تم حفظ وتطبيق جميع الإعدادات فوراً", "✅ All settings saved & applied immediately"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private val ordersUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                "dev.saned.assistant.UI_REFRESH_ORDERS" -> {
                    refreshOrdersLogUI()
                }
                "dev.saned.assistant.UI_REFRESH_LOGS", "dev.saned.assistant.ACTION_DIAG_LOG" -> {
                    refreshDiagLogsUI()
                }
                "dev.jing.sanedhook.b" -> {
                    val ordersJson = intent.getStringExtra("o")
                    if (!ordersJson.isNullOrEmpty() && context != null) {
                        val appCtx = context.applicationContext ?: context
                        uiBgExecutor.execute {
                            io.github.libxposed.service.XposedProvider.saveOrdersBatch(appCtx, ordersJson)
                        }
                    } else {
                        refreshOrdersLogUI()
                    }
                }
                "dev.saned.assistant.UI_PONG_RECEIVED", "dev.saned.assistant.ACTION_PONG" -> {
                    handlePongResponse(intent)
                }
                "dev.saned.assistant.UI_TEST_API_RESULT" -> {
                    handleTestApiResult(intent)
                }
            }
        }
    }

    private fun handlePongResponse(intent: Intent) {
        val batchJson = intent.getStringExtra("orders_batch_json")
        if (!batchJson.isNullOrEmpty() && batchJson != "[]") {
            val bHash = batchJson.hashCode()
            if (bHash != lastReceivedBatchHash) {
                lastReceivedBatchHash = bHash
                val appCtx = applicationContext ?: this
                uiBgExecutor.execute {
                    io.github.libxposed.service.XposedProvider.saveOrdersBatch(appCtx, batchJson)
                }
            }
        }
        val pingTime = intent.getLongExtra("ping_timestamp", 0L)
        val now = System.currentTimeMillis()
        val latency = if (pingTime > 0) (now - pingTime).coerceAtLeast(0L) else 0L
        lastPongTimestamp = now

        val hasToken = intent.getBooleanExtra("has_auth_token", false)
        val ordersCount = intent.getIntExtra("orders_count", 0)
        val httpCount = intent.getIntExtra("http_requests_count", 0)
        val pollCount = intent.getIntExtra("polling_hits", 0)
        val lastUrl = intent.getStringExtra("last_http_url") ?: tr("لا يوجد بعد", "None yet")
        val lastCode = intent.getIntExtra("last_http_code", 0)
        val lastEvent = intent.getStringExtra("last_order_event") ?: tr("جاهز", "Ready")

        runOnUiThread {
            if (::tvPingStatus.isInitialized) {
                tvPingStatus.text = tr(
                    "متصل بالتطبيق مباشرة (الاستجابة: ${latency} ملي ثانية) 🟢",
                    "Connected to Target App (Ping: ${latency} ms) 🟢"
                )
                tvPingStatus.setTextColor(Color.parseColor("#059669"))
                val tokenLabel = if (hasToken) {
                    tr("الجلسة متصلة ✅", "Token Cached ✅")
                } else {
                    tr("بانتظار تسجيل الدخول ⏳", "Waiting for Login ⏳")
                }
                tvPingDetails.text = tr(
                    "النظام: نشط  •  المصادقة: $tokenLabel  •  الطلبات المسجلة: $ordersCount",
                    "Hook: Active  •  Auth: $tokenLabel  •  Orders Logged: $ordersCount"
                )
            }

            if (::tvHttpRequestsCount.isInitialized) {
                tvHttpRequestsCount.text = tr(
                    "إجمالي طلبات الشبكة الملتقطة: $httpCount",
                    "Total Captured Network Requests: $httpCount"
                )
                tvPollingHits.text = tr(
                    "عمليات فحص السيرفر في الخلفية: $pollCount",
                    "Background Server Polling Hits: $pollCount"
                )
                tvLastHttpUrl.text = tr("آخر طلب: $lastUrl", "Last Request: $lastUrl") + (if (lastCode > 0) " (HTTP $lastCode)" else "")
                tvLastOrderEvent.text = tr("آخر حدث: $lastEvent", "Last Event: $lastEvent")
            }
        }
    }

    private fun handleTestApiResult(intent: Intent) {
        val code = intent.getIntExtra("status_code", -1)
        val latency = intent.getLongExtra("latency_ms", 0L)
        val url = intent.getStringExtra("url") ?: ""
        val body = intent.getStringExtra("body_snippet") ?: ""
        val hasToken = intent.getBooleanExtra("has_token", false)

        runOnUiThread {
            val tokenStr = if (hasToken) {
                tr("موجود ومرفق ✅", "Attached ✅")
            } else {
                tr("غير متوفر ❌", "Missing ❌")
            }
            val message = if (isAr) {
                """
                رمز الاستجابة: HTTP $code (${latency} ملي ثانية)
                رمز الجلسة: $tokenStr
                الرابط: $url
                
                معاينة الرد:
                $body
                """.trimIndent()
            } else {
                """
                Status Code: HTTP $code (${latency}ms)
                Auth Token: $tokenStr
                Target URL: $url
                
                Response Preview:
                $body
                """.trimIndent()
            }

            AlertDialog.Builder(this)
                .setTitle(tr("🧪 نتيجة فحص اتصال السيرفر", "🧪 Server API Probe Result"))
                .setMessage(message)
                .setPositiveButton(tr("حسناً", "OK"), null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOrdersLogUI()
        refreshDiagLogsUI()
        SettingsStore.broadcastSettings(this)

        try {
            val filter = IntentFilter().apply {
                addAction("dev.saned.assistant.UI_REFRESH_ORDERS")
                addAction("dev.saned.assistant.UI_REFRESH_LOGS")
                addAction("dev.saned.assistant.ACTION_DIAG_LOG")
                addAction("dev.jing.sanedhook.b")
                addAction("dev.saned.assistant.UI_PONG_RECEIVED")
                addAction("dev.saned.assistant.ACTION_PONG")
                addAction("dev.saned.assistant.UI_TEST_API_RESULT")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ordersUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(ordersUpdateReceiver, filter)
            }
        } catch (_: Throwable) {}

        mainHandler.post(pingRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pingRunnable)
        try {
            unregisterReceiver(ordersUpdateReceiver)
        } catch (_: Throwable) {}
        saveAllSettings()
    }
}
