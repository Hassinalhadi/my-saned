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
    private var currentTab = 0 // 0 = Orders, 1 = Settings

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

    // Location & Route Simulator
    private lateinit var chkFakeLoc: CheckBox
    private lateinit var chkFixLoc: CheckBox
    private lateinit var edtLat: EditText
    private lateinit var edtLng: EditText
    private lateinit var mapWebView: WebView
    private lateinit var tvRouteSimStatus: TextView
    private lateinit var tvRouteMetricsBadge: TextView
    private lateinit var btnStartRouteSim: Button
    private lateinit var btnPauseRouteSim: Button
    private lateinit var btnStopRouteSim: Button
    private lateinit var btnModeTeleport: Button
    private lateinit var btnModeRoute: Button
    private lateinit var edtMapSearch: EditText

    // Route Simulator Engine State (runs at 500ms ticks along real street polyline)
    private val routeWaypoints = mutableListOf<Pair<Double, Double>>()
    private var routeCumulativeMeters = DoubleArray(0)
    private var routeTotalMeters: Double = 0.0
    private var routeTraveledMeters: Double = 0.0
    private var routeSimSpeedKmh: Float = 45.0f
    private var isRouteSimRunning: Boolean = false
    private var isRouteSimPaused: Boolean = false
    private var isRouteSimLooping: Boolean = false
    private var isRouteSimForward: Boolean = true
    private var isMapRouteClickMode: Boolean = true // Default true so clicking sets route A -> B
    private var routeDestLat: Double = 0.0
    private var routeDestLng: Double = 0.0

    // Auto Accept & Filters
    private lateinit var chkAutoAccept: CheckBox
    private lateinit var chkAutoReject: CheckBox
    private lateinit var chkDryRun: CheckBox
    private lateinit var chkStayInBackground: CheckBox
    private lateinit var chkSilenceAlertTone: CheckBox
    private lateinit var chkSoundAlert: CheckBox
    private lateinit var chkStrictVersionCheck: CheckBox
    private lateinit var chkShowFoodPrice: CheckBox
    private lateinit var edtMinPrice: EditText
    private lateinit var edtMaxDistRest: EditText
    private lateinit var edtMaxDistCust: EditText

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

        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8F7FC"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ================= TOP BAR (Matching Original 34.jpg) =================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(36, 36, 36, 28)
            elevation = 4f
        }

        val appTitle = TextView(this).apply {
            text = "Saned Assistant"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(appTitle)

        // Simulate Test Order quick button in top bar
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

        // Clear orders icon button (matching trash icon in 34.jpg)
        val btnClearTop = TextView(this).apply {
            text = "🗑️"
            textSize = 18f
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                SettingsStore.clearOrdersLog(this@MainActivity)
                try { File("/data/local/tmp/saned_orders.json").delete() } catch (_: Throwable) {}
                refreshOrdersLogUI()
                Toast.makeText(this@MainActivity, "Orders list cleared", Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(btnClearTop)

        // Refresh / Ping icon button
        val btnRefreshTop = TextView(this).apply {
            text = "🌐"
            textSize = 18f
            setPadding(20, 10, 10, 10)
            setOnClickListener {
                sendPing()
                refreshOrdersLogUI()
                Toast.makeText(this@MainActivity, "Orders & Connection Refreshed", Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(btnRefreshTop)

        rootContainer.addView(topBar)

        // ================= CONTENT FRAME (Holds Orders Tab & Settings Tab) =================
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        routeSimSpeedKmh = SettingsStore.getRouteSimSpeedKmh(this)
        tabOrdersView = buildOrdersTabView()
        tabMapView = buildMapTabView()
        tabSettingsView = buildSettingsTabView()
        tabLogsView = buildLogsTabView()

        contentFrame.addView(tabOrdersView)
        contentFrame.addView(tabMapView)
        contentFrame.addView(tabSettingsView)
        contentFrame.addView(tabLogsView)
        rootContainer.addView(contentFrame)

        // ================= BOTTOM NAVIGATION BAR (Orders | Settings matching 34.jpg) =================
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
            text = "Orders"
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
            text = "Settings"
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
  text = "Logs"
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
            text = "Map & Route"
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

        // Default to Orders tab (matching 34.jpg)
        switchTab(0)
        refreshOrdersLogUI()
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
        if (tabIndex == 2) refreshDiagLogsUI()
        if (tabIndex == 3 && ::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.invalidateMapSize) window.invalidateMapSize();", null)
        }
    }

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

        // Card 1: Live Crash & Hook Diagnostic Window
        val logCard = createStyledCard()
        val logLayout = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL
  setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
  text = "📋 نافذة السجل التشخيصي وصيد الكراش (Live Crash & Hook Logs)"
  textSize = 15.5f
  typeface = Typeface.DEFAULT_BOLD
  setTextColor(Color.parseColor("#0F172A"))
  setPadding(0, 0, 0, 6)
        }
        logLayout.addView(title)

        tvDiagLogSummary = TextView(this).apply {
  text = "جاهز لالتقاط أي خطأ أو Stacktrace فور فتح تطبيق net.jahez.fleets..."
  textSize = 12f
  typeface = Typeface.DEFAULT_BOLD
  setTextColor(Color.parseColor("#059669"))
  setPadding(0, 0, 0, 12)
        }
        logLayout.addView(tvDiagLogSummary)

        // Filter & Action Buttons Row
        val btnRow1 = LinearLayout(this).apply {
  orientation = LinearLayout.HORIZONTAL
  setPadding(0, 0, 0, 10)
        }

        val btnFilterToggle = Button(this).apply {
  text = "🔎 الكل / الأخطاء فقط"
  textSize = 11.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
      setMargins(0, 0, 8, 0)
  }
  setBackgroundColor(Color.parseColor("#4F46E5"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      filterErrorsOnly = !filterErrorsOnly
      text = if (filterErrorsOnly) "🚨 الأخطاء والكراش فقط" else "🔎 عرض كل السجلات"
      refreshDiagLogsUI()
  }
        }
        btnRow1.addView(btnFilterToggle)

        val btnCopyLogs = Button(this).apply {
  text = "📋 نسخ السجل"
  textSize = 11.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
      setMargins(4, 0, 4, 0)
  }
  setBackgroundColor(Color.parseColor("#059669"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      val fullLogs = SettingsStore.getDiagLogs(this@MainActivity)
      val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clip.setPrimaryClip(ClipData.newPlainText("Saned Diagnostic Logs", fullLogs.ifEmpty { "No logs yet" }))
      Toast.makeText(this@MainActivity, "✅ تم نسخ السجل التشخيصي بالكامل للحافظة", Toast.LENGTH_SHORT).show()
  }
        }
        btnRow1.addView(btnCopyLogs)

        val btnClearLogs = Button(this).apply {
  text = "🗑️ مسح"
  textSize = 11.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply {
      setMargins(8, 0, 0, 0)
  }
  setBackgroundColor(Color.parseColor("#DC2626"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      SettingsStore.clearDiagLogs(this@MainActivity)
      refreshDiagLogsUI()
      Toast.makeText(this@MainActivity, "تم مسح السجل", Toast.LENGTH_SHORT).show()
  }
        }
        btnRow1.addView(btnClearLogs)
        logLayout.addView(btnRow1)

        // Dark Terminal Console Box
        val hScroll = HorizontalScrollView(this).apply {
  background = roundedBg("#0F172A", 14f, "#334155", 2)
  setPadding(20, 20, 20, 20)
  layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT
  )
        }

        tvDiagLogConsole = TextView(this).apply {
  text = "لا توجد سجلات بعد.\nافتح تطبيق net.jahez.fleets الآن وسيظهر هنا كل خطاف (Hook) يتم تفعيله، ورقم إصدار جاهز، وأي استثناء أو Crash بالتفصيل الكامل (Stacktrace)."
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

        // Card 2: Per-Hook Group Isolation Toggles
        val isoCard = createStyledCard()
        val isoLayout = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL
  setPadding(24, 24, 24, 24)
        }

        val isoTitle = TextView(this).apply {
  text = "🛠️ عزل وتجربة الـ Hooks فردياً (Hook Isolation)"
  textSize = 15.5f
  typeface = Typeface.DEFAULT_BOLD
  setTextColor(Color.parseColor("#0F172A"))
  setPadding(0, 0, 0, 4)
        }
        isoLayout.addView(isoTitle)

        val isoSub = TextView(this).apply {
  text = "يمكنك إيقاف أو تشغيل أي مجموعة Hooks على حدة ثم إغلاق تطبيق سند وفتحه لمعرفة المسبب لأي سلوك بدقة:"
  textSize = 12f
  setTextColor(Color.parseColor("#64748B"))
  setPadding(0, 0, 0, 12)
        }
        isoLayout.addView(isoSub)

        chkHookEarlyCloaker = CheckBox(this).apply {
  text = "1. حماية المكتبات المبكرة (Early Native Cloaker - Lvh1)"
  isChecked = SettingsStore.isHookEarlyCloaker(this@MainActivity)
        }
        isoLayout.addView(chkHookEarlyCloaker)

        chkHookSecCloaker = CheckBox(this).apply {
  text = "2. إخفاء الروت والحماية (RootBeer & ShadowArmor - Lu80)"
  isChecked = SettingsStore.isHookSecCloaker(this@MainActivity)
        }
        isoLayout.addView(chkHookSecCloaker)

        chkHookLocation = CheckBox(this).apply {
  text = "3. خطاف الموقع (Location Hook - Ltz0)"
  isChecked = SettingsStore.isHookLocation(this@MainActivity)
        }
        isoLayout.addView(chkHookLocation)

        chkHookDeviceSpoofer = CheckBox(this).apply {
  text = "4. خطاف بصمة الجهاز (Android ID Hook - Lly1)"
  isChecked = SettingsStore.isHookDeviceSpoofer(this@MainActivity)
        }
        isoLayout.addView(chkHookDeviceSpoofer)

        chkHookOrders = CheckBox(this).apply {
  text = "5. خطافات الطلبات (FCM / DispatchActivity / Cipher)"
  isChecked = SettingsStore.isHookOrders(this@MainActivity)
        }
        isoLayout.addView(chkHookOrders)

        chkHookBootstrapLoop = CheckBox(this).apply {
  text = "6. فحص قاعدة البيانات والسيرفر بالخلفية (DB & Polling Loop)"
  isChecked = SettingsStore.isHookBootstrapLoop(this@MainActivity)
        }
        isoLayout.addView(chkHookBootstrapLoop)

        val btnSaveIso = Button(this).apply {
  text = "💾 حفظ إعدادات العزل وتطبيقها"
  textSize = 13.5f
  typeface = Typeface.DEFAULT_BOLD
  setBackgroundColor(Color.parseColor("#059669"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      saveAllSettings()
      Toast.makeText(this@MainActivity, "✅ تم حفظ إعدادات العزل! أعد تشغيل تطبيق net.jahez.fleets للتجربة", Toast.LENGTH_LONG).show()
  }
        }
        isoLayout.addView(btnSaveIso)

        isoCard.addView(isoLayout)
        container.addView(isoCard)

        scroll.addView(container)
        return scroll
    }

    private fun refreshDiagLogsUI() {
        if (!::tvDiagLogConsole.isInitialized) return
        val rawLogs = SettingsStore.getDiagLogs(this)
        if (rawLogs.isBlank()) {
  tvDiagLogSummary.text = "🟢 لا توجد أخطاء مسجلة — افتح تطبيق net.jahez.fleets لبدء التسجيل المباشر"
  tvDiagLogSummary.setTextColor(Color.parseColor("#059669"))
  tvDiagLogConsole.text = "لا توجد سجلات بعد.\nافتح تطبيق net.jahez.fleets الآن وسيظهر هنا كل خطاف (Hook) يتم تفعيله، ورقم إصدار جاهز، وأي استثناء أو Crash بالتفصيل الكامل (Stacktrace)."
  tvDiagLogConsole.setTextColor(Color.parseColor("#94A3B8"))
  return
        }

        val lines = rawLogs.lines()
        val crashCount = lines.count { it.contains("[CRASH]") }
        val errCount = lines.count { it.contains("[ERR]") }
        val warnCount = lines.count { it.contains("[WARN]") }

        if (crashCount > 0 || errCount > 0) {
  tvDiagLogSummary.text = "🚨 تم رصد $crashCount كراش و $errCount خطأ و $warnCount تنبيه (إجمالي ${lines.size} سطر)"
  tvDiagLogSummary.setTextColor(Color.parseColor("#DC2626"))
        } else {
  tvDiagLogSummary.text = "✅ السجل يعمل بنجاح (${lines.size} سطر | $warnCount تنبيه | 0 كراش)"
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
  if (filtered.isEmpty()) "لا توجد أخطاء أو كراش في السجل الحالي ✅" else filtered.joinToString("\n")
        } else {
  rawLogs
        }

        tvDiagLogConsole.text = displayText
        tvDiagLogConsole.setTextColor(
  if (crashCount > 0) Color.parseColor("#FCA5A5") else Color.parseColor("#38BDF8")
        )
    }

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

        // Quick status strip at top of Orders tab
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
                "🟢 Interceptor Active  •  Auto-Accept: ${if (isAcc) "ON" else "OFF"}  •  Auto-Reject: ${if (isRej) "ON" else "OFF"}"
            } else {
                "🔴 Master Switch OFF (Orders are still logged below)"
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isRun) Color.parseColor("#059669") else Color.parseColor("#D97706"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusStrip.addView(tvOrdersSubStatus)
        container.addView(statusStrip)

        // Empty state
        tvOrdersEmptyState = TextView(this).apply {
            text = "No orders received yet.\n\nEvery order that appears in net.jahez.fleets (whether Accepted, Rejected by distance/price, or Manual) will immediately appear here with full details."
            textSize = 13.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(32, 80, 32, 80)
            visibility = View.GONE
        }
        container.addView(tvOrdersEmptyState)

        // 1. Active / Accepted / Delivered Orders Container
        activeOrdersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(activeOrdersContainer)

        // 2. MISSED Section Header (Matches "MISSED  1" in 34.jpg)
        missedSectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 24, 8, 16)
            visibility = View.GONE
        }
        val tvMissedTitle = TextView(this).apply {
            text = "MISSED"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
            letterSpacing = 0.08f
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
                setMargins(16, 0, 0, 0)
            }
        }
        missedSectionHeader.addView(tvMissedTitle)
        missedSectionHeader.addView(tvMissedCountBadge)
        container.addView(missedSectionHeader)

        // 3. Missed / Rejected / Failed Orders Container
        missedOrdersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(missedOrdersContainer)

        scroll.addView(container)
        return scroll
    }

    // =========================================================================
    // TAB 1: SETTINGS TAB (Contains all configuration cards)
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

        mainLayout.addView(createConnectionPingCard())
        mainLayout.addView(createDiagnosticsCard())
        mainLayout.addView(createMasterSwitchCard())
        mainLayout.addView(createFiltersCard())
        mainLayout.addView(createPollingCard())
        mainLayout.addView(createAndroidIdCard())
        mainLayout.addView(createLocationShortcutCard())

        val btnSave = Button(this).apply {
            text = "💾 Save & Apply All Settings"
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

    // =========================================================================
    // RENDER ORDERS IN ORDERS TAB (Matching 34.jpg)
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

    private fun refreshOrdersLogUI() {
        if (!::activeOrdersContainer.isInitialized) return

        // Update status strip
        if (::tvOrdersSubStatus.isInitialized) {
            val isRun = SettingsStore.isMasterRunning(this)
            val isAcc = SettingsStore.isAutoAccept(this)
            val isRej = SettingsStore.isAutoReject(this)
            val maxRest = SettingsStore.getMaxDistRest(this)
            val maxCust = SettingsStore.getMaxDistCust(this)
            val minP = SettingsStore.getMinPrice(this)
            val showFood = SettingsStore.isShowFoodPrice(this)
            tvOrdersSubStatus.text = if (isRun) {
                "🟢 Active • Auto-Accept: ${if (isAcc) "ON" else "OFF"} • Auto-Reject: ${if (isRej) "ON" else "OFF"} • Rest≤${maxRest}km • Cust≤${maxCust}km • Min≥${minP} • Price Food: ${if (showFood) "ON" else "OFF"}"
            } else {
                "🔴 Master Switch OFF (Incoming orders are still captured & shown below)"
            }
            tvOrdersSubStatus.setTextColor(if (isRun) Color.parseColor("#059669") else Color.parseColor("#D97706"))
        }

        activeOrdersContainer.removeAllViews()
        missedOrdersContainer.removeAllViews()

        val jsonStr = SettingsStore.getOrdersLog(this)
        val array = try { JSONArray(jsonStr) } catch (_: Throwable) { JSONArray() }

        // Merge with fallback file if any orders were written directly from hook process (cross-matching both id and reference!)
        try {
            val f = File("/data/local/tmp/saned_orders.json")
            if (f.exists()) {
                val fArray = JSONArray(f.readText())
                for (jdx in 0 until fArray.length()) {
                    val fItm = fArray.optJSONObject(jdx)
                    if (fItm != null) {
                        array.put(fItm)
                    }
                }
            }
        } catch (_: Throwable) {}

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

    /**
     * Exact implementation of Lg8.U & Lg8.W in SanedAssistant-98-7bac1ec.apk:
     * Opens Google Maps navigation to (targetLat, targetLng), optionally starting from (originLat, originLng),
     * so the driver can view & navigate to the customer's home BEFORE confirming pickup in Saned!
     */
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
        Toast.makeText(this, "⚠️ إحداثيات الموقع غير متوفرة بعد لهذا الطلب", Toast.LENGTH_SHORT).show()
    }

    private fun buildActiveOrderCard(order: OrderModel): CardView {
        val rawId = if (order.reference.isNotEmpty()) order.reference else order.id.toString()
        val displayId = if (rawId.startsWith("#")) rawId else "#$rawId"
        val showFoodPriceOpt = SettingsStore.isShowFoodPrice(this)
        val effectiveFoodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount

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

        // 1. Header Row: Status Pill ("Accepted ✅" / "Picked Up" / "Delivered") + Order ID with Copy button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }

        val statusBadge = TextView(this).apply {
            val stText = if (order.status.equals("Dispatched", ignoreCase = true)) "Accepted ✅" else order.status.ifEmpty { "Accepted ✅" }
            text = stText
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
                Toast.makeText(this@MainActivity, "Copied $displayId", Toast.LENGTH_SHORT).show()
            }
        }
        headerRow.addView(idCopyTv)
        inner.addView(headerRow)

        // 2. Pickup Box (Matches 34.jpg & Lg8.U case 0: tapping opens Google Maps directions to restaurant)
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
            val distRestStr = if (order.distanceKm > 0.0) String.format(Locale.US, " • %.1f km from you", order.distanceKm) else ""
            text = "Pickup$distRestStr"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
        }
        val pickupNameTv = TextView(this).apply {
            val restTitle = order.restaurant.ifEmpty { "JAHEZ Restaurant" }
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
            text = "Navigate ↗"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#334155"))
            background = roundedBg("#E2E8F0", 12f)
            setPadding(18, 10, 18, 10)
        }
        pickupBox.addView(pickupNavBtn)
        inner.addView(pickupBox)

        // 3. Drop-off Box (Matches 34.jpg & Lg8.U case 1: opens customer home location in Google Maps BEFORE or AFTER pickup!)
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
            text = "Drop-off  "
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
        }
        val nextBadge = TextView(this).apply {
            text = "NEXT"
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
                text = String.format(Locale.US, "  •  %.1f km from pickup", order.customerDistanceKm)
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
                String.format(Locale.US, "📍 منزل العميل: %.5f, %.5f", order.destLat, order.destLng)
            } else ""
            text = when {
                parts.isNotEmpty() && coordLine.isNotEmpty() -> "${parts.joinToString(" — ")}\n$coordLine"
                parts.isNotEmpty() -> parts.joinToString(" — ")
                coordLine.isNotEmpty() -> "$coordLine\nاضغط لفتح موقع العميل في الخريطة (قبل الاستلام)"
                else -> "Location on map"
            }
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
            setPadding(0, 6, 0, 0)
        }
        dropoffTextCol.addView(dropoffDetailTv)
        dropoffBox.addView(dropoffTextCol)

        val dropoffNavBtn = TextView(this).apply {
            text = "Navigate ↗"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#5B3FD3", 12f)
            setPadding(18, 10, 18, 10)
        }
        dropoffBox.addView(dropoffNavBtn)
        inner.addView(dropoffBox)

        // 4. Dedicated Price Food (سعر الطعام) + Payment Box (Shown on ALL orders in Accepted / Picked Up / Delivered — whether Prepaid or Cash on Delivery!)
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
                Toast.makeText(this@MainActivity, "🔄 جاري تحديث سعر الطعام وتفاصيل الطلب $displayId...", Toast.LENGTH_SHORT).show()
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
            val methodSuffix = if (order.paymentMethod.isNotEmpty()) " (${order.paymentMethod})" else ""
            text = if (isCash) {
                String.format(Locale.US, "Cash on Delivery: %.2f %s", order.cashAmount, order.currency)
            } else {
                "Prepaid$methodSuffix"
            }
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isCash) Color.parseColor("#B91C1C") else Color.parseColor("#1E1B2E"))
        }
        val paySub = TextView(this).apply {
            text = if (isCash) {
                String.format(Locale.US, "Collect %.2f %s from customer", order.cashAmount, order.currency)
            } else {
                "Nothing to collect (مدفوع مسبقاً)"
            }
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
        }
        payCol.addView(payTitle)
        payCol.addView(paySub)

        if (showFoodPriceOpt) {
            val foodPriceTv = TextView(this).apply {
                text = if (effectiveFoodPrice > 0.0) {
                    String.format(Locale.US, "🍔 Price Food (سعر الطعام): %.2f %s", effectiveFoodPrice, order.currency)
                } else {
                    String.format(Locale.US, "🍔 Price Food (سعر الطعام): 0.00 %s (Prepaid)", order.currency)
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
                text = String.format(Locale.US, "🚗 %.2f %s", order.price, order.currency)
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
                text = String.format(Locale.US, "🍔 %.2f %s", effectiveFoodPrice, order.currency)
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

        // 5. Call & WhatsApp Buttons Row (Matches Lg8.a in SanedAssistant: shown when customerPhone is present, hidden for old SQLite orders without customer phone)
        val cleanPhone = NotificationHelper.formatPhoneForWhatsApp(order.customerPhone)
        if (cleanPhone.isNotEmpty()) {
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnCall = TextView(this).apply {
                text = "📞   Call"
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
                        Toast.makeText(this@MainActivity, "Phone: $cleanPhone", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionsRow.addView(btnCall)

            val btnWhatsApp = TextView(this).apply {
                text = "💬   WhatsApp"
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
                        Toast.makeText(this@MainActivity, "WhatsApp phone: $cleanPhone", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionsRow.addView(btnWhatsApp)

            inner.addView(actionsRow)
        }
        // 6. Quick Route Simulator Button for this Order (Restaurant or Customer)
        if ((order.pickupLat != 0.0 && order.pickupLng != 0.0) || (order.destLat != 0.0 && order.destLng != 0.0)) {
            val btnSimOrderRoute = TextView(this).apply {
                text = "🚗  محاكاة المشي في الطريق لهذا الطلب (Route Simulator)"
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

    /**
     * Builds a Missed / Rejected / Manual Order Card matching the "MISSED" card at the bottom of 34.jpg,
     * plus shows Price Food & full pickup/dropoff details when tapped.
     */
    private fun buildMissedOrderCard(order: OrderModel): CardView {
        val showFoodPriceOpt = SettingsStore.isShowFoodPrice(this)
        val effectiveFoodPrice = if (order.foodPrice > 0.0) order.foodPrice else order.cashAmount

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
            text = order.restaurant.ifEmpty { "Restaurant" }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E1B2E"))
        }
        centerCol.addView(restNameTv)

        val statusLineTv = TextView(this).apply {
            val reasonText = if (order.decisionReason.isNotEmpty()) order.decisionReason else order.status
            text = "⚠️ $reasonText  •  ${order.time.ifEmpty { "just now" }}"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B91C1C"))
            setPadding(0, 4, 0, 2)
        }
        centerCol.addView(statusLineTv)

        val pkgLineTv = TextView(this).apply {
            val pickInfo = order.pickupAddress.ifEmpty { "net.jahez.fleets" }
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
                String.format(Locale.US, "Pickup %.1f km • Drop-off %.1f km", order.distanceKm, order.customerDistanceKm)
            } else {
                String.format(Locale.US, "Delivery ≈ %.2f km", distToUse)
            }
            val rateStr = if (ratePerKm > 0.0) String.format(Locale.US, "  •  %.2f SAR/km", ratePerKm) else ""
            val foodStr = if (showFoodPriceOpt) String.format(Locale.US, "  •  🍔 Food: %.2f %s", effectiveFoodPrice, order.currency) else ""
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
                String.format(Locale.US, "%.0f %s", order.price, order.currency)
            } else {
                String.format(Locale.US, "%.2f %s", order.price, order.currency)
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
    // SETTINGS TAB CARDS
    // =========================================================================
    private fun createConnectionPingCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = "📡 Connection & Live Ping Status"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        tvPingStatus = TextView(this).apply {
            text = "Checking connection to Jahez Fleets... ⏳"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D97706"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvPingStatus)

        tvPingDetails = TextView(this).apply {
            text = "Hook: Initializing  •  Auth: Waiting for login  •  Orders: 0"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvPingDetails)

        val btnPing = Button(this).apply {
            text = "⚡ TEST PING NOW"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sendPing()
                Toast.makeText(this@MainActivity, "Ping request sent to Jahez Fleets", Toast.LENGTH_SHORT).show()
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
            text = "🌐 OkHttp & Network Diagnostics"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(title)

        val sub = TextView(this).apply {
            text = "Live inspection of OkHttp traffic, background polling hits, and direct API probe:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(sub)

        tvOkHttpHookStatus = TextView(this).apply {
            text = "OkHttp Hook: Active & Intercepting 🟢"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvOkHttpHookStatus)

        tvHttpRequestsCount = TextView(this).apply {
            text = "Total Captured HTTP Requests: 0"
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvHttpRequestsCount)

        tvPollingHits = TextView(this).apply {
            text = "Active Server Polling Hits: 0"
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvPollingHits)

        tvLastHttpUrl = TextView(this).apply {
            text = "Last Request: Waiting for network traffic..."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(tvLastHttpUrl)

        tvLastOrderEvent = TextView(this).apply {
            text = "Last Event: Ready for orders"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(tvLastOrderEvent)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTestApi = Button(this).apply {
            text = "🧪 Test Live Jahez API"
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
            text = "⚡ Simulate Order (68.jpg)"
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
            text = "🗑️ Clear Cached Token"
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
        Toast.makeText(this, "Testing direct OkHttp call to Jahez API...", Toast.LENGTH_SHORT).show()
        val intent = Intent("dev.saned.assistant.ACTION_TEST_API").apply {
            setPackage("net.jahez.fleets")
        }
        sendBroadcast(intent)
        try {
            sendBroadcast(Intent("dev.saned.assistant.ACTION_TEST_API"))
        } catch (_: Throwable) {}
    }

    private fun sendSimulateOrder() {
        Toast.makeText(this, "Simulating Jahez Order...", Toast.LENGTH_SHORT).show()
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
                status = "Accepted ✅",
                restaurant = "McDonald's KSA 🇸🇦 - ماكدونالدز وجبة_فيلم_سبايدرمان",
                pickupAddress = "Pick-up from RJ43+H42, Al Malqa, Riyadh 13524",
                customerName = "عبدالله الشمري (Abdullah)",
                customerPhone = "0551234567",
                address = "Location on map",
                price = 7.00,
                currency = "SAR",
                cashAmount = 0.0,
                foodPrice = 65.00,
                paymentMethod = "Prepaid",
                distanceKm = 0.5,
                customerDistanceKm = 2.8,
                decisionReason = "Accepted (0.5km / 2.8km)",
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
        Toast.makeText(this, "Token cache cleared! Open Jahez Fleets app to refresh token.", Toast.LENGTH_LONG).show()
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
                tvPingStatus.text = "🔴 Disconnected (Jahez App Closed or Inactive)"
                tvPingStatus.setTextColor(Color.parseColor("#DC2626"))
                tvPingDetails.text = "Open Jahez Fleet app to establish real-time link"
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
            text = "🔘 Master Control Switch"
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
            text = "Enable All Assistant Functions (Master ON / OFF)"
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
            tvMasterStatus.text = "Status: 🟢 Active & Ready for Jahez Orders"
            tvMasterStatus.setTextColor(Color.parseColor("#059669"))
        } else {
            tvMasterStatus.text = "Status: 🔴 Stopped (Orders are still logged in Orders tab)"
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
            text = "📱 Device Identifier (Android ID / IMEI)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        val note = TextView(this).apply {
            text = "Note: Jahez Fleets uses Android ID as device IMEI on the login screen. Modifying this changes the reported device ID."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(note)

        chkAndroidId = CheckBox(this).apply {
            text = "Enable Device ID Spoofing"
            isChecked = SettingsStore.isSpoofAndroidId(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setSpoofAndroidId(this@MainActivity, isChecked)
            }
        }
        layout.addView(chkAndroidId)

        edtAndroidId = EditText(this).apply {
            hint = "Enter 16-hex characters (e.g. a1b2c3d4e5f67890)"
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
            text = "🎲 Randomize (16-char)"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                edtAndroidId.setText(generateRandomAndroidId())
                chkAndroidId.isChecked = true
            }
        }
        btnRow.addView(btnGen)

        val btnCopy = Button(this).apply {
            text = "📋 Copy ID"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Android ID", edtAndroidId.text.toString().trim()))
                Toast.makeText(this@MainActivity, "Device ID copied to clipboard", Toast.LENGTH_SHORT).show()
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
            text = "🗺️ Google Maps Spoofer & Route Simulator (محاكي الطريق)"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(title)
        val sub = TextView(this).apply {
            text = "تم نقل خريطة Google Maps التفاعلية ومحاكي المشي في الطريق (Route Simulator بتخصيص السرعة) إلى تبويب مستقل بالأسفل (🗺️ Map & Route) لسهولة التحكم الكامل."
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(sub)
        val btnOpenMap = Button(this).apply {
            text = "🗺️ فتح خريطة Google Maps ومحاكي الطريق الآن"
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

        // Header Row: Title + Google Maps API Key Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val title = TextView(this).apply {
            text = "🗺️ Google Maps GPS Spoof & Route Simulator"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)

        val btnApiKey = TextView(this).apply {
            text = "🔑 API Key"
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
                "🟢 تغيير الموقع مفعل (GPS Spoof ON) • حدد وجهة على خريطة Google لبدء محاكاة الطريق"
            } else {
                "⚪ اضغط على أي مكان في خريطة Google لتثبيت موقعك أو رسم مسار المشي في الطريق"
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

        // Toggles Row
        chkFakeLoc = CheckBox(this).apply {
            text = "تفعيل تغيير الموقع والمحاكي الذكي (Enable GPS Spoof & Anti-Mock Protection)"
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
            text = "إصلاح خطأ 'الموقع غير معروف' (Fix Location Unknown)"
            isChecked = SettingsStore.isFixLocation(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setFixLocation(this@MainActivity, isChecked)
            }
        }
        layout.addView(chkFixLoc)

        // Search Row for Google Maps
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 8)
        }
        edtMapSearch = EditText(this).apply {
            hint = "🔍 ابحث عن حي أو مطعم أو شارع (مثال: حي الملقا الرياض)..."
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
            text = "بحث 🔎"
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

        // Mode Switcher Row: Teleport Mode vs Route Simulator Mode
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        btnModeTeleport = Button(this).apply {
            text = "📍 نقل فوري (Teleport)"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener { setMapClickMode(false) }
        }
        btnModeRoute = Button(this).apply {
            text = "🛣️ رسم مسار (Route Sim)"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 0, 0)
            }
            setOnClickListener { setMapClickMode(true) }
        }
        modeRow.addView(btnModeTeleport)
        modeRow.addView(btnModeRoute)
        layout.addView(modeRow)

        // Google Maps Layer Switcher + Active Order Route Loader Row
        val layerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val btnLayerRoad = TextView(this).apply {
            text = "🗺️ خريطة جوجل"
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
            text = "🛰️ قمر صناعي"
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
            text = "🚦 الزحام"
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
            text = "📦 مسار طلب"
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

        // Interactive Google Map Frame (920px height + touch intercept lock so dragging map never scrolls parent ScrollView)
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

        // Route Metrics & Speed Badge
        tvRouteMetricsBadge = TextView(this).apply {
            text = "🛣️ المسار: اضغط على خريطة Google لتحديد وجهة الطريق (أو اختر 📦 مسار طلب) • السرعة المخصصة: ${routeSimSpeedKmh.toInt()} كم/س"
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

        // Route Simulator Control Buttons Row: [▶️ بدء محاكاة الطريق (مع تخصيص السرعة)] [⚡ السرعة] [⏸️ إيقاف مؤقت] [⏹️ إنهاء]
        val simCtrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        btnStartRouteSim = Button(this).apply {
            text = "🚗 بدء محاكاة الطريق (تخصيص السرعة)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f).apply {
                setMargins(0, 0, 6, 0)
            }
            setOnClickListener {
                showRouteSpeedCustomizationDialog(startImmediately = true)
            }
        }
        simCtrlRow.addView(btnStartRouteSim)

        val btnSpeedDialog = Button(this).apply {
            text = "⚡ السرعة"
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
            text = "⏹️ إيقاف"
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
                Toast.makeText(this@MainActivity, "⏹️ تم إيقاف محاكاة الطريق وتثبيت الموقع الحالي", Toast.LENGTH_SHORT).show()
            }
        }
        simCtrlRow.addView(btnStopRouteSim)
        layout.addView(simCtrlRow)

        // Coordinates Row (Manual Lat / Lng + Apply Pin button)
        val coordsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        edtLat = EditText(this).apply {
            hint = "Latitude (Lat)"
            val l = SettingsStore.getFakeLat(this@MainActivity)
            setText(if (l != 0.0) String.format(Locale.US, "%.6f", l) else "24.803508")
            textSize = 12.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLat)

        edtLng = EditText(this).apply {
            hint = "Longitude (Lng)"
            val g = SettingsStore.getFakeLng(this@MainActivity)
            setText(if (g != 0.0) String.format(Locale.US, "%.6f", g) else "46.604578")
            textSize = 12.5f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLng)

        val btnApplyCoords = Button(this).apply {
            text = "📍 تثبيت"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val lt = edtLat.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                val lg = edtLng.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                applyImmediateSpoofLocation(lt, lg, panMap = true)
                Toast.makeText(this@MainActivity, "✅ تم تحديث الموقع الوهمي فوراً!", Toast.LENGTH_SHORT).show()
            }
        }
        coordsRow.addView(btnApplyCoords)
        layout.addView(coordsRow)

        setMapClickMode(true)
        setupGoogleMapWebView()

        card.addView(layout)
        return card
    }

    private fun setMapClickMode(routeMode: Boolean) {
        isMapRouteClickMode = routeMode
        if (::btnModeTeleport.isInitialized && ::btnModeRoute.isInitialized) {
            btnModeTeleport.setBackgroundColor(Color.parseColor(if (!routeMode) "#4F46E5" else "#CBD5E1"))
            btnModeTeleport.setTextColor(if (!routeMode) Color.WHITE else Color.parseColor("#1E293B"))
            btnModeRoute.setBackgroundColor(Color.parseColor(if (routeMode) "#2563EB" else "#CBD5E1"))
            btnModeRoute.setTextColor(if (routeMode) Color.WHITE else Color.parseColor("#1E293B"))
        }
        if (::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.setClickMode) window.setClickMode('${if (routeMode) "route" else "teleport"}');", null)
        }
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
            speedKmh = if (isRouteSimRunning) routeSimSpeedKmh else 0.0f,
            bearing = SettingsStore.getRouteSimBearing(this),
            simActive = isRouteSimRunning && !isRouteSimPaused
        )
        if (panMap && ::mapWebView.isInitialized) {
            mapWebView.evaluateJavascript("if(window.setDriverPin) window.setDriverPin($lat, $lng, 0, true);", null)
        }
        updateRouteSimBannerUI()
    }

    /**
     * Dialog requested by user:
     * "توفير مربع حوار يطلب تخصيص سرعة في ميزة route simulator حيث يظهر كما لو انني امشي في طريق في سرعة"
     */
    private fun showRouteSpeedCustomizationDialog(startImmediately: Boolean) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 24)
        }

        val infoTv = TextView(this).apply {
            val distKm = routeTotalMeters / 1000.0
            text = if (distKm > 0.01) {
                String.format(Locale.US, "🛣️ طول المسار المحدد على الشوارع: %.2f كم\nحدد السرعة التي تريد أن يظهر بها حسابك وكأنك تمشي في الطريق:", distKm)
            } else {
                "حدد السرعة بالكيلومتر/ساعة (km/h) لمحاكاة المشي في الطريق:"
            }
            textSize = 13f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 16)
        }
        dialogLayout.addView(infoTv)

        val edtSpeed = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(routeSimSpeedKmh.toInt().toString())
            hint = "السرعة (كم/س) مثل 45"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg("#F1F5F9", 12f, "#94A3B8", 2)
            setPadding(24, 20, 24, 20)
        }
        dialogLayout.addView(edtSpeed)

        val tvLiveEta = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#059669"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        dialogLayout.addView(tvLiveEta)

        fun updateDialogEtaPreview(spdKmh: Float) {
            val cleanSpd = spdKmh.coerceIn(2f, 200f)
            val mps = cleanSpd / 3.6f
            val distKm = routeTotalMeters / 1000.0
            if (distKm > 0.01) {
                val totalSec = (routeTotalMeters / mps).toInt().coerceAtLeast(1)
                val mins = totalSec / 60
                val secs = totalSec % 60
                tvLiveEta.text = String.format(Locale.US, "⚡ السرعة: %.0f كم/س (%.1f م/ث) • زمن الوصول المتوقع: %d د و %d ث", cleanSpd, mps, mins, secs)
            } else {
                tvLiveEta.text = String.format(Locale.US, "⚡ السرعة المختارة: %.0f كم/س (%.1f متر/ثانية)", cleanSpd, mps)
            }
        }
        updateDialogEtaPreview(routeSimSpeedKmh)

        val seekBar = SeekBar(this).apply {
            max = 155 // 5 km/h .. 160 km/h
            progress = (routeSimSpeedKmh.toInt() - 5).coerceIn(0, 155)
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
        dialogLayout.addView(seekBar)

        // Quick Speed Presets Row
        val presetsLabel = TextView(this).apply {
            text = "سرعات جاهزة بضغطة واحدة:"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 14, 0, 8)
        }
        dialogLayout.addView(presetsLabel)

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
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E40AF"))
                background = roundedBg("#DBEAFE", 10f)
                setPadding(12, 14, 12, 14)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener {
                    edtSpeed.setText(valSpd.toString())
                    seekBar.progress = (valSpd - 5).coerceIn(0, 155)
                    updateDialogEtaPreview(valSpd.toFloat())
                }
            }
            presetsRow.addView(chip)
        }
        dialogLayout.addView(presetsRow)

        val chkLoop = CheckBox(this).apply {
            text = "🔄 إعادة المسار تلقائياً ذهاباً وإياباً عند الوصول للوجهة (Loop Route)"
            isChecked = isRouteSimLooping
            setPadding(0, 16, 0, 0)
        }
        dialogLayout.addView(chkLoop)

        AlertDialog.Builder(this)
            .setTitle("🚗 تخصيص سرعة محاكي الطريق (Route Simulator)")
            .setView(dialogLayout)
            .setPositiveButton(if (startImmediately) "🚀 ابدأ المشي في الطريق الآن" else "💾 حفظ السرعة") { _, _ ->
                val chosenSpd = edtSpeed.text.toString().trim().toFloatOrNull()?.coerceIn(2.0f, 220.0f) ?: 45.0f
                routeSimSpeedKmh = chosenSpd
                isRouteSimLooping = chkLoop.isChecked
                SettingsStore.setRouteSimSpeedKmh(this, chosenSpd)
                updateRouteMetricsUI()
                if (startImmediately || isRouteSimRunning) {
                    startOrResumeRouteSimulation()
                } else {
                    Toast.makeText(this, "✅ تم ضبط سرعة محاكي الطريق على ${chosenSpd.toInt()} كم/س", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showGoogleMapsApiKeyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 16)
        }
        val tv = TextView(this).apply {
            text = "تعمل خرائط Google Maps المدمجة وتوجيه الشوارع تلقائياً بدون مفتاح.\nوإذا كان لديك مفتاح Google Maps API خاص بك (اختياري)، يمكنك إدخاله هنا لتفعيل Google Directions API الرسمي:"
            textSize = 12.5f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 14)
        }
        layout.addView(tv)

        val edtKey = EditText(this).apply {
            hint = "AIzaSy... (اختياري — اتركه فارغاً للوضع التلقائي)"
            setText(SettingsStore.getGoogleMapsApiKey(this@MainActivity))
            textSize = 13.5f
            setSingleLine(true)
        }
        layout.addView(edtKey)

        AlertDialog.Builder(this)
            .setTitle("🔑 إعداد مفتاح Google Maps API")
            .setView(layout)
            .setPositiveButton("حفظ وإعادة تحميل الخريطة") { _, _ ->
                val key = edtKey.text.toString().trim()
                SettingsStore.setGoogleMapsApiKey(this, key)
                setupGoogleMapWebView()
                Toast.makeText(this, "✅ تم تحديث إعدادات خريطة Google Maps", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showOrderRouteQuickPickerDialog(order: OrderModel) {
        val curLat = edtLat.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLat(this).takeIf { it != 0.0 } ?: order.pickupLat
        val curLng = edtLng.text.toString().toDoubleOrNull() ?: SettingsStore.getFakeLng(this).takeIf { it != 0.0 } ?: order.pickupLng
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (order.pickupLat != 0.0 && order.pickupLng != 0.0) {
            options.add("🏪 المشي من موقعي الحالي ➔ المطعم (${order.restaurant.ifEmpty { "Pickup" }})")
            actions.add {
                switchTab(3)
                setMapClickMode(true)
                val sLat = if (curLat != 0.0) curLat else order.pickupLat - 0.008
                val sLng = if (curLng != 0.0) curLng else order.pickupLng - 0.008
                mapWebView.evaluateJavascript("if(window.buildStreetRoute) window.buildStreetRoute($sLat, $sLng, ${order.pickupLat}, ${order.pickupLng}, true);", null)
            }
        }
        if (order.destLat != 0.0 && order.destLng != 0.0) {
            val fromLat = if (order.pickupLat != 0.0) order.pickupLat else curLat
            val fromLng = if (order.pickupLng != 0.0) order.pickupLng else curLng
            options.add("📍 المشي من المطعم ➔ منزل العميل (Drop-off)")
            actions.add {
                switchTab(3)
                setMapClickMode(true)
                mapWebView.evaluateJavascript("if(window.buildStreetRoute) window.buildStreetRoute($fromLat, $fromLng, ${order.destLat}, ${order.destLng}, true);", null)
            }
        }
        if (order.pickupLat != 0.0 && order.pickupLng != 0.0) {
            options.add("⚡ نقل موقعي فوراً إلى عند المطعم (Teleport to Pickup)")
            actions.add {
                applyImmediateSpoofLocation(order.pickupLat, order.pickupLng, panMap = true)
                Toast.makeText(this, "📍 تم نقل موقعك فوراً إلى المطعم!", Toast.LENGTH_SHORT).show()
            }
        }
        if (order.destLat != 0.0 && order.destLng != 0.0) {
            options.add("⚡ نقل موقعي فوراً إلى منزل العميل (Teleport to Customer)")
            actions.add {
                applyImmediateSpoofLocation(order.destLat, order.destLng, panMap = true)
                Toast.makeText(this, "📍 تم نقل موقعك فوراً إلى منزل العميل!", Toast.LENGTH_SHORT).show()
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(this, "⚠️ لا تتوفر إحداثيات لهذا الطلب بعد", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("🚗 محاكاة الطريق للطلب #${order.reference.ifEmpty { order.id.toString() }}")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("إغلاق", null)
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
            Toast.makeText(this, "اضغط مباشرة على خريطة Google لتحديد نقطة الوصول أو انتظر وصول طلب", Toast.LENGTH_LONG).show()
            return
        }
        val labels = ordersWithCoords.map { o ->
            val idStr = if (o.reference.isNotEmpty()) "#${o.reference.removePrefix("#")}" else "#${o.id}"
            "$idStr — ${o.restaurant.ifEmpty { "طلب ساند" }} (${o.status})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📦 اختر طلباً لتحميل مساره على خريطة Google")
            .setItems(labels) { _, which ->
                showOrderRouteQuickPickerDialog(ordersWithCoords[which])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // =========================================================================
    // REAL-TIME 500ms STREET ROUTE SIMULATOR ENGINE
    // =========================================================================
    private val routeSimTickRunnable = object : Runnable {
        override fun run() {
            if (!isRouteSimRunning || isRouteSimPaused || routeWaypoints.size < 2 || routeTotalMeters <= 0.5) {
                return
            }
            val tickSec = 0.5 // 500ms tick (2 updates per second)
            val speedMps = (routeSimSpeedKmh.coerceIn(1.0f, 220.0f) / 3.6)
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
                        updateSimCoordinateInputs(lastPt.first, lastPt.second, finalBrg)
                        updateRouteSimBannerUI()
                        updateRouteMetricsUI()
                        Toast.makeText(this@MainActivity, "🏁 وصلت إلى نهاية المسار المحدد!", Toast.LENGTH_LONG).show()
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
                speedKmh = routeSimSpeedKmh,
                bearing = segBearing,
                simActive = true
            )
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
            Toast.makeText(
                this,
                "⚠️ اضغط أولاً على خريطة Google لتحديد وجهة الطريق (أو اختر 📦 مسار طلب) ليتم رسم الشوارع!",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (::chkFakeLoc.isInitialized && !chkFakeLoc.isChecked) {
            chkFakeLoc.isChecked = true
        }
        if (routeTraveledMeters >= routeTotalMeters - 1.0) {
            routeTraveledMeters = 0.0
            isRouteSimForward = true
        }
        isRouteSimRunning = true
        isRouteSimPaused = false
        if (::btnPauseRouteSim.isInitialized) {
            btnPauseRouteSim.visibility = View.VISIBLE
            btnPauseRouteSim.text = "⏸️"
        }
        if (::btnStartRouteSim.isInitialized) {
            btnStartRouteSim.text = "🚗 يتحرك (${routeSimSpeedKmh.toInt()} كم/س)"
        }
        mainHandler.removeCallbacks(routeSimTickRunnable)
        mainHandler.post(routeSimTickRunnable)
        Toast.makeText(
            this,
            "🚗 بدأت محاكاة المشي في الطريق بسرعة ${routeSimSpeedKmh.toInt()} كم/س!",
            Toast.LENGTH_SHORT
        ).show()
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
            Toast.makeText(this, "⏸️ تم إيقاف حركة السيارة مؤقتاً في نفس النقطة", Toast.LENGTH_SHORT).show()
        } else {
            if (::btnPauseRouteSim.isInitialized) btnPauseRouteSim.text = "⏸️"
            mainHandler.post(routeSimTickRunnable)
            Toast.makeText(this, "▶️ استئناف المشي في الطريق بسرعة ${routeSimSpeedKmh.toInt()} كم/س", Toast.LENGTH_SHORT).show()
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
        if (::btnStartRouteSim.isInitialized) btnStartRouteSim.text = "🚗 بدء محاكاة الطريق (تخصيص السرعة)"
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
                val spdMps = (routeSimSpeedKmh / 3.6).coerceAtLeast(0.5)
                val remSec = (remMeters / spdMps).toInt()
                tvRouteSimStatus.text = String.format(
                    Locale.US,
                    "🚗 يمشي في الطريق الآن بسرعة %.0f كم/س • المتبقي: %.2f كم (%d:%02d د) • 📍 %s, %s",
                    routeSimSpeedKmh, remKm, remSec / 60, remSec % 60, lat, lng
                )
                tvRouteSimStatus.setTextColor(Color.parseColor("#047857"))
                tvRouteSimStatus.background = roundedBg("#ECFDF5", 12f, "#6EE7B7", 2)
            }
            isRouteSimRunning && isRouteSimPaused -> {
                tvRouteSimStatus.text = "⏸️ محاكاة الطريق متوقفة مؤقتاً عند 📍 $lat, $lng (اضغط ▶️ للاستئناف)"
                tvRouteSimStatus.setTextColor(Color.parseColor("#B45309"))
                tvRouteSimStatus.background = roundedBg("#FFFBEB", 12f, "#FCD34D", 2)
            }
            SettingsStore.isFakeLocation(this) -> {
                tvRouteSimStatus.text = "🟢 الموقع الوهمي نشط وثابت عند 📍 $lat, $lng"
                tvRouteSimStatus.setTextColor(Color.parseColor("#059669"))
                tvRouteSimStatus.background = roundedBg("#F8FAFC", 12f, "#E2E8F0", 2)
            }
            else -> {
                tvRouteSimStatus.text = "⚪ اضغط على خريطة Google لتفعيل الموقع أو رسم مسار المشي في الطريق"
                tvRouteSimStatus.setTextColor(Color.parseColor("#475569"))
                tvRouteSimStatus.background = roundedBg("#F8FAFC", 12f, "#E2E8F0", 2)
            }
        }
    }

    private fun updateRouteMetricsUI() {
        if (!::tvRouteMetricsBadge.isInitialized) return
        if (routeTotalMeters > 1.0 && routeWaypoints.size >= 2) {
            val totalKm = routeTotalMeters / 1000.0
            val remKm = ((routeTotalMeters - routeTraveledMeters).coerceAtLeast(0.0)) / 1000.0
            val mps = (routeSimSpeedKmh / 3.6).coerceAtLeast(0.5)
            val etaSec = ((routeTotalMeters - routeTraveledMeters).coerceAtLeast(0.0) / mps).toInt()
            tvRouteMetricsBadge.text = String.format(
                Locale.US,
                "🛣️ مسافة الطريق: %.2f كم (المتبقي %.2f كم) • السرعة: %.0f كم/س • الوصول: %d د %d ث (اضغط لتغيير السرعة ⚡)",
                totalKm, remKm, routeSimSpeedKmh, etaSec / 60, etaSec % 60
            )
        } else {
            tvRouteMetricsBadge.text = "🛣️ المسار: اضغط على خريطة Google لتحديد وجهة الطريق (أو اختر 📦 مسار طلب) • السرعة: ${routeSimSpeedKmh.toInt()} كم/س"
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
        val initialLat = edtLat.text.toString().toDoubleOrNull()?.takeIf { it != 0.0 } ?: 24.803508
        val initialLng = edtLng.text.toString().toDoubleOrNull()?.takeIf { it != 0.0 } ?: 46.604578
        val gMapsKey = SettingsStore.getGoogleMapsApiKey(this).replace("'", "")

        val html = """
            <!DOCTYPE html>
            <html dir="rtl" lang="ar">
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
                        padding: 4px 10px; border-radius: 8px; font-size: 11px; font-weight: bold;
                        box-shadow: 0 1px 4px rgba(0,0,0,0.25); pointer-events: none;
                    }
                    .car-marker-icon {
                        display: flex; align-items: center; justify-content: center;
                        width: 36px; height: 36px; border-radius: 50%;
                        background: #1a73e8; color: #fff; font-size: 19px;
                        border: 3px solid #ffffff; box-shadow: 0 2px 8px rgba(0,0,0,0.45);
                        transition: transform 0.25s linear;
                    }
                    .dest-marker-icon {
                        display: flex; align-items: center; justify-content: center;
                        width: 32px; height: 32px; border-radius: 50%;
                        background: #dc2626; color: #fff; font-size: 16px;
                        border: 2px solid #ffffff; box-shadow: 0 2px 6px rgba(0,0,0,0.4);
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <div class="gmap-badge" id="hudBadge">Google Maps • وضع رسم المسار 🛣️</div>
                <script>
                    var curLat = $initialLat;
                    var curLng = $initialLng;
                    var clickMode = 'route';
                    var googleApiKey = '$gMapsKey';

                    var map = L.map('map', { zoomControl: true }).setView([curLat, curLng], 15);

                    // Official Google Maps Tile Servers (Roadmap, Satellite Hybrid, and Live Traffic)
                    var googleRoadmap = L.tileLayer('https://mt1.google.com/vt/lyrs=m&hl=ar&x={x}&y={y}&z={z}', {
                        maxZoom: 21,
                        attribution: 'Google Maps'
                    });
                    var googleHybrid = L.tileLayer('https://mt1.google.com/vt/lyrs=y&hl=ar&x={x}&y={y}&z={z}', {
                        maxZoom: 21,
                        attribution: 'Google Satellite'
                    });
                    var googleTraffic = L.tileLayer('https://mt1.google.com/vt/lyrs=m,traffic&hl=ar&x={x}&y={y}&z={z}', {
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
                    var destIcon = L.divIcon({
                        className: '',
                        html: '<div class="dest-marker-icon">🏁</div>',
                        iconSize: [32, 32],
                        iconAnchor: [16, 16]
                    });

                    var driverMarker = L.marker([curLat, curLng], { icon: driverIcon, draggable: true }).addTo(map);
                    var destMarker = null;
                    var routePolyline = null;
                    var routeShadowLine = null;

                    window.setClickMode = function(m) {
                        clickMode = m;
                        var badge = document.getElementById('hudBadge');
                        if (badge) {
                            badge.innerText = (m === 'route')
                                ? 'Google Maps • اضغط على أي شارع لرسم المسار والبدء 🛣️'
                                : 'Google Maps • اضغط على أي مكان للنقل الفوري 📍';
                        }
                    };

                    driverMarker.on('dragend', function(e) {
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
                        } else {
                            window.buildStreetRoute(curLat, curLng, targetLat, targetLng, true);
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
                            badge.innerText = '🚗 جاري المشي في الطريق (' + pct.toFixed(0) + '%)';
                        }
                    };

                    window.clearSimulatedRoute = function() {
                        if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
                        if (routeShadowLine) { map.removeLayer(routeShadowLine); routeShadowLine = null; }
                        if (destMarker) { map.removeLayer(destMarker); destMarker = null; }
                    };

                    function drawRouteCoordinates(coordsLatLng, promptSpeedDialog) {
                        window.clearSimulatedRoute();
                        if (!coordsLatLng || coordsLatLng.length < 2) return;
                        var endPt = coordsLatLng[coordsLatLng.length - 1];
                        destMarker = L.marker(endPt, { icon: destIcon }).addTo(map);
                        routeShadowLine = L.polyline(coordsLatLng, { color: '#1e3a8a', weight: 9, opacity: 0.35 }).addTo(map);
                        routePolyline = L.polyline(coordsLatLng, { color: '#1a73e8', weight: 6, opacity: 0.95 }).addTo(map);
                        map.fitBounds(routePolyline.getBounds(), { padding: [35, 35] });

                        if (window.AndroidBridge && window.AndroidBridge.onRoutePathReady) {
                            window.AndroidBridge.onRoutePathReady(JSON.stringify(coordsLatLng), !!promptSpeedDialog);
                        }
                    }

                    // Decode Google Polyline algorithm (for Google Directions API)
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
                        curLat = startLat;
                        curLng = startLng;
                        driverMarker.setLatLng([startLat, startLng]);

                        // 1. If user configured a Google Maps API Key, try Google Directions API first, then fallback to OSRM Real Street Driving Router
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
                                // Smooth 40-segment path fallback if offline
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
                    };

                    window.searchPlaceOnGoogleMap = function(query) {
                        var url = 'https://nominatim.openstreetmap.org/search?format=json&accept-language=ar&limit=1&q=' + encodeURIComponent(query);
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
                                    } else {
                                        window.buildStreetRoute(curLat, curLng, lt, lg, true);
                                    }
                                }
                            })
                            .catch(function() {});
                    };
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
                    val dest = routeWaypoints.last()
                    routeDestLat = dest.first
                    routeDestLng = dest.second
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
            text = "⚡ Auto-Accept & Distance / Price Filters"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkAutoAccept = CheckBox(this).apply {
            text = "Enable Instant Auto-Accept (When order passes filters)"
            isChecked = SettingsStore.isAutoAccept(this@MainActivity)
        }
        layout.addView(chkAutoAccept)

        chkAutoReject = CheckBox(this).apply {
            text = "Enable Auto-Reject (Automatically reject orders exceeding distance/price)"
            isChecked = SettingsStore.isAutoReject(this@MainActivity)
        }
        layout.addView(chkAutoReject)

        chkDryRun = CheckBox(this).apply {
            text = "Dry-Run Mode (Log & notify orders without clicking Accept/Reject)"
            isChecked = SettingsStore.isDryRun(this@MainActivity)
        }
        layout.addView(chkDryRun)

        chkStayInBackground = CheckBox(this).apply {
            text = "Don't open Saned for orders handled for me (Stay in Background)"
            isChecked = SettingsStore.isStayInBackground(this@MainActivity)
        }
        layout.addView(chkStayInBackground)

        chkSilenceAlertTone = CheckBox(this).apply {
            text = "Silence Jahez order alert tone when handled automatically"
            isChecked = SettingsStore.isSilenceAlertTone(this@MainActivity)
        }
        layout.addView(chkSilenceAlertTone)

        chkSoundAlert = CheckBox(this).apply {
            text = "Play beep sound when an order is auto-accepted"
            isChecked = SettingsStore.isSoundEnabled(this@MainActivity)
        }
        layout.addView(chkSoundAlert)

        chkStrictVersionCheck = CheckBox(this).apply {
            text = "Strict Jahez Version Check (Stop if Jahez version is not 40089/40093/40099)"
            isChecked = SettingsStore.isStrictVersionCheck(this@MainActivity)
        }
        layout.addView(chkStrictVersionCheck)

        chkShowFoodPrice = CheckBox(this).apply {
            text = "🍔 Price Food (إظهار سعر الطعام في جميع الطلبات المقبولة والمسبقة الدفع والكاش)"
            isChecked = SettingsStore.isShowFoodPrice(this@MainActivity)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B45309"))
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setShowFoodPrice(this@MainActivity, isChecked)
                refreshOrdersLogUI()
            }
        }
        layout.addView(chkShowFoodPrice)

        layout.addView(createLabel("Minimum Order Price (SAR) - Set 0 to disable limit:"))
        edtMinPrice = createInput(SettingsStore.getMinPrice(this).toString())
        layout.addView(edtMinPrice)

        layout.addView(createLabel("Max Distance to Restaurant / Pickup (km) - Set 0 to disable limit:"))
        edtMaxDistRest = createInput(SettingsStore.getMaxDistRest(this).toString())
        layout.addView(edtMaxDistRest)

        layout.addView(createLabel("Max Distance to Customer / Drop-off (km) - Set 0 to disable limit:"))
        edtMaxDistCust = createInput(SettingsStore.getMaxDistCust(this).toString())
        layout.addView(edtMaxDistCust)

        card.addView(layout)
        return card
    }

    private fun createPollingCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "🌐 Active Server Polling (Background Puller)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 6)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = "Repeatedly queries Jahez backend via OkHttp to grab unassigned orders before they appear on other drivers' screens."
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(desc)

        chkActivePolling = CheckBox(this).apply {
            text = "Enable Active Server Polling"
            isChecked = SettingsStore.isActivePolling(this@MainActivity)
            textSize = 14f
        }
        layout.addView(chkActivePolling)

        layout.addView(createLabel("Poll Interval in seconds (e.g. 0.8s):"))
        edtPollInterval = createInput(SettingsStore.getPollInterval(this).toString())
        layout.addView(edtPollInterval)

        layout.addView(createLabel("Parallel Acceptance Attempts (Burst requests to win order):"))
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

        SettingsStore.setAutoAccept(this, autoAccept)
        SettingsStore.setAutoReject(this, autoReject)
        SettingsStore.setDryRun(this, dryRun)
        SettingsStore.setStayInBackground(this, chkStayInBackground.isChecked)
        SettingsStore.setSilenceAlertTone(this, chkSilenceAlertTone.isChecked)
        SettingsStore.setSoundEnabled(this, chkSoundAlert.isChecked)
        SettingsStore.setStrictVersionCheck(this, chkStrictVersionCheck.isChecked)
        if (::chkShowFoodPrice.isInitialized) {
            SettingsStore.setShowFoodPrice(this, chkShowFoodPrice.isChecked)
        }
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
            "✅ Settings saved & applied immediately!",
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
                        io.github.libxposed.service.XposedProvider.saveOrdersBatch(context, ordersJson)
                    }
                    refreshOrdersLogUI()
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
            io.github.libxposed.service.XposedProvider.saveOrdersBatch(this, batchJson)
            runOnUiThread { refreshOrdersLogUI() }
        }
        val pingTime = intent.getLongExtra("ping_timestamp", 0L)
        val now = System.currentTimeMillis()
        val latency = if (pingTime > 0) (now - pingTime).coerceAtLeast(0L) else 0L
        lastPongTimestamp = now

        val hasToken = intent.getBooleanExtra("has_auth_token", false)
        val ordersCount = intent.getIntExtra("orders_count", 0)
        val httpCount = intent.getIntExtra("http_requests_count", 0)
        val pollCount = intent.getIntExtra("polling_hits", 0)
        val lastUrl = intent.getStringExtra("last_http_url") ?: "None yet"
        val lastCode = intent.getIntExtra("last_http_code", 0)
        val lastEvent = intent.getStringExtra("last_order_event") ?: "Ready"

        runOnUiThread {
            if (::tvPingStatus.isInitialized) {
                tvPingStatus.text = "Connected to Jahez Fleets (Ping: ${latency} ms) 🟢"
                tvPingStatus.setTextColor(Color.parseColor("#059669"))
                val tokenLabel = if (hasToken) "Token Cached ✅" else "Waiting for Login ⏳"
                tvPingDetails.text = "Hook: Active  •  Auth: $tokenLabel  •  Orders Logged: $ordersCount"
            }

            if (::tvHttpRequestsCount.isInitialized) {
                tvHttpRequestsCount.text = "Total Captured HTTP Requests: $httpCount"
                tvPollingHits.text = "Active Server Polling Hits: $pollCount"
                tvLastHttpUrl.text = "Last Request: $lastUrl" + (if (lastCode > 0) " (HTTP $lastCode)" else "")
                tvLastOrderEvent.text = "Last Event: $lastEvent"
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
            val tokenStr = if (hasToken) "Attached ✅" else "Missing ❌"
            val message = """
                Status Code: HTTP $code (${latency}ms)
                Auth Token: $tokenStr
                Target URL: $url
                
                Response Preview:
                $body
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("🧪 Jahez API Probe Result")
                .setMessage(message)
                .setPositiveButton("OK", null)
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
