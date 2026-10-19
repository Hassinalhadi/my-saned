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
    private lateinit var tabSettingsView: ScrollView
    private lateinit var tabLogsView: ScrollView
    private lateinit var navLogsBtn: LinearLayout
    private lateinit var navLogsIconBg: TextView
    private lateinit var navLogsLabel: TextView
    private lateinit var tvDiagLogConsole: TextView
    private lateinit var tvDiagLogSummary: TextView
    private var filterErrorsOnly: Boolean = false
    private var filterNavLogsOnly: Boolean = false
    private var filterSpeedLogsOnly: Boolean = false
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

    // Location
    private lateinit var chkFakeLoc: CheckBox
    private lateinit var chkFixLoc: CheckBox
    private lateinit var edtLat: EditText
    private lateinit var edtLng: EditText
    private lateinit var mapWebView: WebView

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
    private lateinit var edtMaxOrders: EditText

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

        tabOrdersView = buildOrdersTabView()
        tabSettingsView = buildSettingsTabView()
        tabLogsView = buildLogsTabView()

        contentFrame.addView(tabOrdersView)
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

        bottomNav.addView(navOrdersBtn)
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

        navOrdersIconBg.background = if (tabIndex == 0) roundedBg("#EDE9FE", 20f) else null
        navSettingsIconBg.background = if (tabIndex == 1) roundedBg("#EDE9FE", 20f) else null
        navLogsIconBg.background = if (tabIndex == 2) roundedBg("#EDE9FE", 20f) else null

        navOrdersLabel.setTextColor(Color.parseColor(if (tabIndex == 0) "#4C1D95" else "#64748B"))
        navSettingsLabel.setTextColor(Color.parseColor(if (tabIndex == 1) "#4C1D95" else "#64748B"))
        navLogsLabel.setTextColor(Color.parseColor(if (tabIndex == 2) "#4C1D95" else "#64748B"))

        if (tabIndex == 0) refreshOrdersLogUI()
        if (tabIndex == 2) refreshDiagLogsUI()
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
  text = "🔎 الكل / الأخطاء"
  textSize = 10.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
      setMargins(0, 0, 4, 0)
  }
  setBackgroundColor(Color.parseColor("#4F46E5"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      filterNavLogsOnly = false
      filterSpeedLogsOnly = false
      filterErrorsOnly = !filterErrorsOnly
      text = if (filterErrorsOnly) "🚨 الأخطاء ✓" else "🔎 الكل / الأخطاء"
      refreshDiagLogsUI()
  }
        }
        btnRow1.addView(btnFilterToggle)

        val btnSpeedLogFilter = Button(this).apply {
  text = "⚡ سجل السرعة"
  textSize = 10.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f).apply {
      setMargins(3, 0, 4, 0)
  }
  setBackgroundColor(Color.parseColor("#7C3AED"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      filterErrorsOnly = false
      filterNavLogsOnly = false
      filterSpeedLogsOnly = !filterSpeedLogsOnly
      text = if (filterSpeedLogsOnly) "⚡ السرعة فقط ✓" else "⚡ سجل السرعة"
      refreshDiagLogsUI()
  }
        }
        btnRow1.addView(btnSpeedLogFilter)

        val btnNavLogFilter = Button(this).apply {
  text = "🧭 الملاحة"
  textSize = 10.5f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
      setMargins(3, 0, 4, 0)
  }
  setBackgroundColor(Color.parseColor("#0284C7"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      filterErrorsOnly = false
      filterSpeedLogsOnly = false
      filterNavLogsOnly = !filterNavLogsOnly
      text = if (filterNavLogsOnly) "🧭 الملاحة ✓" else "🧭 الملاحة"
      refreshDiagLogsUI()
  }
        }
        btnRow1.addView(btnNavLogFilter)

        val btnCopyLogs = Button(this).apply {
  text = "📋 نسخ"
  textSize = 11f
  layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
      setMargins(3, 0, 4, 0)
  }
  setBackgroundColor(Color.parseColor("#059669"))
  setTextColor(Color.WHITE)
  setOnClickListener {
      val shownText = tvDiagLogConsole.text?.toString() ?: ""
      val fullLogs = SettingsStore.getDiagLogs(this@MainActivity)
      val toCopy = if ((filterSpeedLogsOnly || filterNavLogsOnly || filterErrorsOnly) && shownText.isNotBlank()) shownText else fullLogs
      val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clip.setPrimaryClip(ClipData.newPlainText("Saned Diagnostic Logs", toCopy.ifEmpty { "No logs yet" }))
      val modeMsg = if (filterSpeedLogsOnly) "✅ تم نسخ سجل سرعة ومعالجة الطلبات [SpeedLog] للحافظة" else "✅ تم نسخ السجل التشخيصي للحافظة"
      Toast.makeText(this@MainActivity, modeMsg, Toast.LENGTH_SHORT).show()
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
        val navCount = lines.count { it.contains("[NavLog]") }
        val speedCount = lines.count { it.contains("[SpeedLog]") }

        if (crashCount > 0 || errCount > 0) {
  tvDiagLogSummary.text = "🚨 $crashCount كراش و $errCount خطأ | ⚡ $speedCount سرعة قبول | 🧭 $navCount ملاحة (${lines.size} سطر)"
  tvDiagLogSummary.setTextColor(Color.parseColor("#DC2626"))
        } else {
  tvDiagLogSummary.text = "✅ السجل يعمل (${lines.size} سطر | ⚡ $speedCount سرعة قبول | 🧭 $navCount ملاحة | $warnCount تنبيه)"
  tvDiagLogSummary.setTextColor(Color.parseColor("#059669"))
        }

        val displayText = when {
  filterSpeedLogsOnly -> {
      val speedLines = lines.filter {
          it.contains("[SpeedLog]") ||
          it.contains("[OrderDecision]") ||
          it.contains("[EarlyFCM]") ||
          it.contains("[FCM]") ||
          it.contains("[DirectApi]") ||
          it.contains("[IncomingDispatchActivity]") ||
          it.contains("[CipherHook]") ||
          it.contains("[EncryptedPrefs]")
      }
      if (speedLines.isEmpty()) "لا توجد سجلات سرعة ومعالجة طلبات [SpeedLog] بعد — انتظر وصول طلب أو اضغط محاكاة طلب في الإعدادات" else speedLines.joinToString("\n")
  }
  filterNavLogsOnly -> {
      val navLines = lines.filter { it.contains("[NavLog]") }
      if (navLines.isEmpty()) "لا توجد سجلات ملاحة [NavLog] بعد — افتح طلب في تطبيق سند أو اضغط ⚡ لتجربة التوجيه للعميل" else navLines.joinToString("\n")
  }
  filterErrorsOnly -> {
      val filtered = mutableListOf<String>()
      var includeContinuation = false
      for (line in lines) {
          if (line.startsWith("[")) {
              includeContinuation = line.contains("[CRASH]") || line.contains("[ERR]") || line.contains("[WARN]")
          }
          if (includeContinuation) filtered.add(line)
      }
      if (filtered.isEmpty()) "لا توجد أخطاء أو كراش في السجل الحالي ✅" else filtered.joinToString("\n")
  }
  else -> rawLogs
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
        mainLayout.addView(createLocationMapCard())

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
            val maxOrd = SettingsStore.getMaxOrdersLimit(this)
            val maxOrdStr = if (maxOrd <= 0) "∞ Unlimited" else maxOrd.toString()
            val showFood = SettingsStore.isShowFoodPrice(this)
            tvOrdersSubStatus.text = if (isRun) {
                "🟢 Active • Auto-Accept: ${if (isAcc) "ON" else "OFF"} • Orders Limit: $maxOrdStr • Rest≤${maxRest}km • Cust≤${maxCust}km • Min≥${minP} • Price Food: ${if (showFood) "ON" else "OFF"}"
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
                pickupLat = 24.7685,
                pickupLng = 46.6182,
                destLat = 24.7912,
                destLng = 46.6345,
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

    private fun createLocationMapCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "📍 GPS Location & Interactive Map"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkFixLoc = CheckBox(this).apply {
            text = "Fix 'Location Unknown' Error"
            isChecked = SettingsStore.isFixLocation(this@MainActivity)
        }
        layout.addView(chkFixLoc)

        chkFakeLoc = CheckBox(this).apply {
            text = "Enable Mock GPS Location"
            isChecked = SettingsStore.isFakeLocation(this@MainActivity)
        }
        layout.addView(chkFakeLoc)

        val coordsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        edtLat = EditText(this).apply {
            hint = "Latitude (Lat)"
            val l = SettingsStore.getFakeLat(this@MainActivity)
            setText(if (l != 0.0) l.toString() else "24.7136")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLat)

        edtLng = EditText(this).apply {
            hint = "Longitude (Lng)"
            val g = SettingsStore.getFakeLng(this@MainActivity)
            setText(if (g != 0.0) g.toString() else "46.6753")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLng)
        layout.addView(coordsRow)

        val mapFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                550
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }

        mapWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        }
        mapFrame.addView(mapWebView)
        layout.addView(mapFrame)

        setupLeafletMap()

        card.addView(layout)
        return card
    }

    private fun setupLeafletMap() {
        val initialLat = edtLat.text.toString().toDoubleOrNull() ?: 24.7136
        val initialLng = edtLng.text.toString().toDoubleOrNull() ?: 46.6753

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var lat = $initialLat;
                    var lng = $initialLng;
                    var map = L.map('map').setView([lat, lng], 14);
                    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', {
                        maxZoom: 19
                    }).addTo(map);

                    var marker = L.marker([lat, lng], { draggable: true }).addTo(map);

                    function notifyAndroid(lt, lg) {
                        if (window.AndroidBridge && window.AndroidBridge.onLocationMoved) {
                            window.AndroidBridge.onLocationMoved(lt, lg);
                        }
                    }

                    marker.on('dragend', function (e) {
                        var pos = marker.getLatLng();
                        notifyAndroid(pos.lat, pos.lng);
                    });

                    map.on('click', function(e) {
                        marker.setLatLng(e.latlng);
                        notifyAndroid(e.latlng.lat, e.latlng.lng);
                    });

                    function setPin(newLat, newLng) {
                        marker.setLatLng([newLat, newLng]);
                        map.panTo([newLat, newLng]);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        mapWebView.loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "UTF-8", null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onLocationMoved(lat: Double, lng: Double) {
            runOnUiThread {
                val roundLat = String.format(Locale.US, "%.6f", lat)
                val roundLng = String.format(Locale.US, "%.6f", lng)
                edtLat.setText(roundLat)
                edtLng.setText(roundLng)
                chkFakeLoc.isChecked = true
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

        layout.addView(createLabel("How many order you want (عدد الطلبات المسموح بقبولها - Set 0 for Unlimited / عدد لا متناهي):"))
        edtMaxOrders = createInput(SettingsStore.getMaxOrdersLimit(this).toString())
        layout.addView(edtMaxOrders)

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

        layout.addView(createLabel("Poll Interval in seconds (Set 0 for 0.0ms Instant Pre-Warmed Mode):"))
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
        val maxOrders = if (::edtMaxOrders.isInitialized) (edtMaxOrders.text.toString().trim().toIntOrNull() ?: 0) else 0

        val activePolling = chkActivePolling.isChecked
        val pollInterval = edtPollInterval.text.toString().toFloatOrNull() ?: 0.0f
        val parallelReqs = edtParallelRequests.text.toString().toIntOrNull() ?: 4

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
        SettingsStore.setMaxOrdersLimit(this, maxOrders)

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
