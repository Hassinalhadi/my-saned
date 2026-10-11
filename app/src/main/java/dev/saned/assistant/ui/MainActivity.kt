package dev.saned.assistant.ui

import android.net.Uri
import dev.saned.assistant.OrderModel
import dev.saned.assistant.NotificationHelper
import java.util.Locale
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
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
import dev.saned.assistant.SettingsStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

class MainActivity : AppCompatActivity() {

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
    private lateinit var edtMinPrice: EditText
    private lateinit var edtMaxDistRest: EditText
    private lateinit var edtMaxDistCust: EditText

    // Active Server Polling & Parallel Acceptance
    private lateinit var chkActivePolling: CheckBox
    private lateinit var edtPollInterval: EditText
    private lateinit var edtParallelRequests: EditText

    private lateinit var ordersLogContainer: LinearLayout

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPing()
            checkPingTimeout()
            mainHandler.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 60)
        }
        scroll.addView(mainLayout)
        setContentView(scroll)

        // ================= HEADER =================
        val header = TextView(this).apply {
            text = "⚡ Saned Assistant Pro"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(header)

        val subHeader = TextView(this).apply {
            text = "Automated High-Speed Order Interceptor for Jahez Fleets"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mainLayout.addView(subHeader)

        // ================= SECTION 0: LIVE PING & CONNECTION STATUS =================
        mainLayout.addView(createConnectionPingCard())

        // ================= SECTION 0.5: OKHTTP & NETWORK DIAGNOSTICS =================
        mainLayout.addView(createDiagnosticsCard())

        // ================= SECTION 1: MASTER SWITCH CARD =================
        mainLayout.addView(createMasterSwitchCard())

        // ================= SECTION 2: ANDROID ID SPOOFER =================
        mainLayout.addView(createAndroidIdCard())

        // ================= SECTION 3: INTERACTIVE MAP & GPS =================
        mainLayout.addView(createLocationMapCard())

        // ================= SECTION 4: AUTO ACCEPT & SMART FILTERS =================
        mainLayout.addView(createFiltersCard())

        // ================= SECTION 5: ACTIVE SERVER POLLING =================
        mainLayout.addView(createPollingCard())

        // ================= SECTION 6: ORDERS LIVE LOG =================
        mainLayout.addView(createOrdersLogCard())

        // ================= SAVE BUTTON =================
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
    }

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
            text = "⚡ Simulate Test Order"
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
        Toast.makeText(this, "Simulating incoming test order...", Toast.LENGTH_SHORT).show()
        val intent = Intent("dev.saned.assistant.ACTION_SIMULATE_ORDER").apply {
            setPackage("net.jahez.fleets")
        }
        sendBroadcast(intent)
        try {
            sendBroadcast(Intent("dev.saned.assistant.ACTION_SIMULATE_ORDER"))
        } catch (_: Throwable) {}

        // Immediate direct simulation locally to verify notifications & UI
        mainHandler.postDelayed({
            val testNum = (100000..999999).random()
            val simOrder = OrderModel(
                id = testNum,
                reference = "JH-",
                status = "Accepted (Auto-Accept ⚡)",
                restaurant = "شاورما كلاسك (Al Classic Shawarma)",
                customerName = "عبدالله الشمري (Abdullah)",
                customerPhone = "0551234567",
                address = "حي الياسمين، شارع أنس بن مالك، الرياض",
                price = 24.50,
                currency = "SAR",
                cashAmount = 55.00,
                distanceKm = 2.8,
                time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            )

            val prefs = getSharedPreferences("sanedhook_settings", Context.MODE_PRIVATE)
            val existing = prefs.getString("orders_log_json", "[]") ?: "[]"
            val array = try { JSONArray(existing) } catch (_: Throwable) { JSONArray() }
            val newArray = JSONArray()
            newArray.put(simOrder.toJson())
            for (i in 0 until minOf(39, array.length())) {
                newArray.put(array.getJSONObject(i))
            }
            prefs.edit().putString("orders_log_json", newArray.toString()).apply()

            NotificationHelper.showOrderNotification(this@MainActivity, simOrder, isAccepted = true)
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
            tvPingStatus.text = "🔴 Disconnected (Jahez App Closed or Inactive)"
            tvPingStatus.setTextColor(Color.parseColor("#DC2626"))
            tvPingDetails.text = "Open Jahez Fleet app to establish real-time link"
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
            tvMasterStatus.text = "Status: 🔴 Stopped (All Functions Disabled)"
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
                val roundLat = String.format("%.6f", lat)
                val roundLng = String.format("%.6f", lng)
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
            text = "⚡ Auto-Accept & Smart Filters"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkAutoAccept = CheckBox(this).apply {
            text = "Enable Instant Auto-Accept"
            isChecked = SettingsStore.isAutoAccept(this@MainActivity)
        }
        layout.addView(chkAutoAccept)

        chkAutoReject = CheckBox(this).apply {
            text = "Enable Auto-Reject for Non-Matching Orders"
            isChecked = SettingsStore.isAutoReject(this@MainActivity)
        }
        layout.addView(chkAutoReject)

        chkDryRun = CheckBox(this).apply {
            text = "Dry-Run Mode (Evaluate orders without accepting)"
            isChecked = SettingsStore.isDryRun(this@MainActivity)
        }
        layout.addView(chkDryRun)

        layout.addView(createLabel("Minimum Order Price (SAR) - Set 0 to disable limit:"))
        edtMinPrice = createInput(SettingsStore.getMinPrice(this).toString())
        layout.addView(edtMinPrice)

        layout.addView(createLabel("Max Distance to Restaurant (km) - Set 0 to disable limit:"))
        edtMaxDistRest = createInput(SettingsStore.getMaxDistRest(this).toString())
        layout.addView(edtMaxDistRest)

        layout.addView(createLabel("Max Distance to Customer (km) - Set 0 to disable limit:"))
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
            text = "Original Assistant Feature: Repeatedly queries Jahez backend via OkHttp to grab unassigned orders before they appear on other drivers' screens."
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
        SettingsStore.setMinPrice(this, minPrice)
        SettingsStore.setMaxDistRest(this, maxRest)
        SettingsStore.setMaxDistCust(this, maxCust)

        SettingsStore.setActivePolling(this, activePolling)
        SettingsStore.setPollInterval(this, pollInterval)
        SettingsStore.setParallelRequests(this, parallelReqs)

        Toast.makeText(
            this,
            "✅ Settings saved & applied successfully!\nServer Polling active (${pollInterval}s) with ${parallelReqs} parallel burst attempts.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun createOrdersLogCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "📋 Live Orders Log (HTTP / WebSocket / Push)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = "Real-time feed of intercepted orders across all network, database, and screen channels:"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(desc)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        val btnRefresh = Button(this).apply {
            text = "🔄 Refresh Log"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { refreshOrdersLogUI() }
        }
        btnRow.addView(btnRefresh)

        val btnClear = Button(this).apply {
            text = "🗑️ Clear Log"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                SettingsStore.clearOrdersLog(this@MainActivity)
                refreshOrdersLogUI()
                Toast.makeText(this@MainActivity, "Orders log cleared", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(btnClear)
        layout.addView(btnRow)

        ordersLogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(ordersLogContainer)

        refreshOrdersLogUI()

        card.addView(layout)
        return card
    }

    private fun refreshOrdersLogUI() {
        if (!::ordersLogContainer.isInitialized) return
        ordersLogContainer.removeAllViews()

        val jsonStr = SettingsStore.getOrdersLog(this)
        val array = try { JSONArray(jsonStr) } catch (_: Throwable) { JSONArray() }

        try {
            val f = File("/data/local/tmp/saned_orders.json")
            if (f.exists()) {
                val fArray = JSONArray(f.readText())
                val seenIds = mutableSetOf<String>()
                for (idx in 0 until array.length()) {
                    val itm = array.optJSONObject(idx)
                    if (itm != null) seenIds.add(itm.optString("id"))
                }
                for (jdx in 0 until fArray.length()) {
                    val fItm = fArray.optJSONObject(jdx)
                    if (fItm != null) {
                        val fId = fItm.optString("id")
                        if (fId.isNotEmpty() && !seenIds.contains(fId)) {
                            array.put(fItm)
                            seenIds.add(fId)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (array.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "No orders captured yet. (Make sure Jahez Fleet app is running and orders are dispatched)."
                textSize = 12.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            ordersLogContainer.addView(emptyTv)
            return
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val order = OrderModel.fromJson(item)
            val idStr = if (order.reference.isNotEmpty()) order.reference else order.id.toString().ifEmpty { "#--" }
            val displayId = if (idStr.startsWith("#")) idStr else "#$idStr"
            val timeStr = order.time

            val itemCard = CardView(this).apply {
                radius = 16f
                cardElevation = 3f
                setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 18, 20, 18)
            }

            // Top Header: Order ID + Status Badge + Time
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val idTv = TextView(this).apply {
                text = displayId
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusBadge = TextView(this).apply {
                val isRejected = order.status.contains("Reject", ignoreCase = true) || order.status.contains("رفض")
                val isDeliver = order.status.contains("DELIVER", ignoreCase = true) || order.status.contains("PICKED", ignoreCase = true) || order.status.contains("WAY", ignoreCase = true)
                text = if (isRejected) "❌ ${order.status}" else if (isDeliver) "🚗 ${order.status}" else "✅ ${order.status}"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                if (isRejected) {
                    setTextColor(Color.parseColor("#DC2626"))
                    setBackgroundColor(Color.parseColor("#FEE2E2"))
                } else if (isDeliver) {
                    setTextColor(Color.parseColor("#2563EB"))
                    setBackgroundColor(Color.parseColor("#DBEAFE"))
                } else {
                    setTextColor(Color.parseColor("#059669"))
                    setBackgroundColor(Color.parseColor("#DCFCE7"))
                }
                setPadding(12, 4, 12, 4)
            }
            val timeTv = TextView(this).apply {
                text = " $timeStr"
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(8, 0, 0, 0)
            }
            topRow.addView(idTv)
            topRow.addView(statusBadge)
            topRow.addView(timeTv)
            cardInner.addView(topRow)

            // Divider
            val div = View(this).apply {
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, 12, 0, 12)
                }
            }
            cardInner.addView(div)

            // Route Section (Matching Lg8;.t - Pickup ➔ Dropoff)
            val routeLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F8FAFC"))
                setPadding(14, 12, 14, 12)
            }

            // Pickup: Restaurant
            val pickupRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pickupIcon = TextView(this).apply {
                text = "🏪 "
                textSize = 13f
            }
            val pickupTv = TextView(this).apply {
                val restName = if (order.restaurant.isNotEmpty()) order.restaurant else "Restaurant / المتجر"
                text = restName
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1E293B"))
            }
            pickupRow.addView(pickupIcon)
            pickupRow.addView(pickupTv)
            routeLayout.addView(pickupRow)

            // Dropoff: Customer Name & Delivery Address
            val dropoffRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            val dropoffIcon = TextView(this).apply {
                text = "📍 "
                textSize = 13f
            }
            val dropoffText = StringBuilder()
            if (order.customerName.isNotEmpty()) {
                dropoffText.append(order.customerName)
            }
            if (order.address.isNotEmpty()) {
                if (dropoffText.isNotEmpty()) dropoffText.append(" — ")
                dropoffText.append(order.address)
            }
            if (dropoffText.isEmpty()) {
                dropoffText.append("Customer Dropoff / موقع التسليم")
            }
            val dropoffTv = TextView(this).apply {
                text = dropoffText.toString()
                textSize = 12.5f
                setTextColor(Color.parseColor("#475569"))
            }
            dropoffRow.addView(dropoffIcon)
            dropoffRow.addView(dropoffTv)
            routeLayout.addView(dropoffRow)

            cardInner.addView(routeLayout)

            // Price & Distance (Matching Lg8;.l)
            val priceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 4)
            }
            val priceTv = TextView(this).apply {
                val pStr = if (order.bonus > 0.0) {
                    "${String.format(Locale.US, \"%.1f\", order.price)} + ${String.format(Locale.US, \"%.1f\", order.bonus)} (Bonus) ${order.currency}"
                } else if (order.price > 0.0) {
                    "${String.format(Locale.US, \"%.1f\", order.price)} ${order.currency}"
                } else "--"
                text = "💰 Delivery Fee: $pStr"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            priceRow.addView(priceTv)

            if (order.distanceKm > 0.0) {
                val distTv = TextView(this).apply {
                    text = "📏 ${String.format(Locale.US, "%.1f", order.distanceKm)} km"
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                }
                priceRow.addView(distTv)
            }
            cardInner.addView(priceRow)

            // Cash Alert Banner (Matching Lg8;.e) - If cashAmount > 0
            if (order.cashAmount > 0.0) {
                val cashBanner = TextView(this).apply {
                    text = "💵 استلم نقداً / Collect cash: ${String.format(Locale.US, "%.1f", order.cashAmount)} ${order.currency}"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#B91C1C"))
                    setBackgroundColor(Color.parseColor("#FEF2F2"))
                    setPadding(14, 10, 14, 10)
                    val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    p.setMargins(0, 8, 0, 8)
                    layoutParams = p
                }
                cardInner.addView(cashBanner)
            }

            // Customer Contact Actions (Matching Lg8;.a) - WhatsApp & Direct Dial buttons
            if (order.customerPhone.isNotEmpty()) {
                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                }

                val cleanPhone = NotificationHelper.formatPhoneForWhatsApp(order.customerPhone)

                // WhatsApp Button (Lg8;.a)
                val btnWa = Button(this).apply {
                    text = "💬 WhatsApp"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#25D366"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, 6, 0)
                    }
                    setOnClickListener {
                        try {
                            val waUrl = "https://api.whatsapp.com/send?phone=$cleanPhone"
                            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                            startActivity(waIntent)
                        } catch (e: Throwable) {
                            Toast.makeText(this@MainActivity, "WhatsApp not available: $cleanPhone", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                actionsRow.addView(btnWa)

                // Direct Call Button
                val btnCall = Button(this).apply {
                    text = "📞 Call"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#2563EB"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(6, 0, 0, 0)
                    }
                    setOnClickListener {
                        try {
                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone"))
                            startActivity(dialIntent)
                        } catch (e: Throwable) {
                            Toast.makeText(this@MainActivity, "Cannot dial: $cleanPhone", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                actionsRow.addView(btnCall)

                cardInner.addView(actionsRow)
            }

            itemCard.addView(cardInner)
            ordersLogContainer.addView(itemCard)
        }
    }

    private val ordersUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                "dev.saned.assistant.UI_REFRESH_ORDERS" -> {
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
            tvPingStatus.text = "Connected to Jahez Fleets (Ping: ${latency} ms) 🟢"
            tvPingStatus.setTextColor(Color.parseColor("#059669"))
            tvPingDetails.text = "Hook: Active  •  Auth: ${if (hasToken) "Token Cached ✅" else "Waiting for Login ⏳"}  •  Orders Logged: $ordersCount"

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
            val statusColor = if (code in 200..299) "#059669" else "#DC2626"
            val message = """
                Status Code: HTTP $code (${latency}ms)
                Auth Token: ${if (hasToken) "Attached ✅" else "Missing ❌"}
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
        SettingsStore.broadcastSettings(this)

        try {
            val filter = IntentFilter().apply {
                addAction("dev.saned.assistant.UI_REFRESH_ORDERS")
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
