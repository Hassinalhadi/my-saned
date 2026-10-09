package dev.saned.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import dev.saned.assistant.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

class MainActivity : AppCompatActivity() {

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
    private lateinit var ordersLogContainer: LinearLayout

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
            text = "⚡ مساعد جاهز الذكي - Saned Assistant"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(header)

        val subHeader = TextView(this).apply {
            text = "النسخة البرمجية المطابقة للمساعد الأصلي - مفتوحة المصدر وبدون قيود"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mainLayout.addView(subHeader)

        // ================= MASTER SWITCH CARD =================
        mainLayout.addView(createMasterSwitchCard())

        // ================= SECTION 1: ANDROID ID SPOOFER =================
        mainLayout.addView(createAndroidIdCard())

        // ================= SECTION 2: INTERACTIVE MAP & GPS =================
        mainLayout.addView(createLocationMapCard())

        // ================= SECTION 3: AUTO ACCEPT & SMART FILTERS =================
        mainLayout.addView(createFiltersCard())

        // ================= SECTION 4: ORDERS LIVE LOG =================
        mainLayout.addView(createOrdersLogCard())

        // ================= SAVE BUTTON =================
        val btnSave = Button(this).apply {
            text = "💾 حفظ جميع الإعدادات وتطبيقها فوراً"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setPadding(24, 28, 24, 28)
            setOnClickListener { saveAllSettings() }
        }
        mainLayout.addView(btnSave)
    }

    private fun createMasterSwitchCard(): CardView {
        val card = createStyledCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val title = TextView(this).apply {
            text = "🔘 زر التشغيل والتحكم الرئيسي"
            textSize = 17f
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
            text = "تشغيل المساعد بالكامل (Master ON / OFF)"
            textSize = 15f
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
            tvMasterStatus.text = "الحالة: 🟢 المساعد قيد التشغيل والجاهزية للعمل مع جاهز"
            tvMasterStatus.setTextColor(Color.parseColor("#059669"))
        } else {
            tvMasterStatus.text = "الحالة: 🔴 المساعد متوقف بالكامل (كل الوظائف معطلة حالياً)"
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
            text = "📱 معرف الجهاز (Android ID)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        val note = TextView(this).apply {
            text = "ملاحظة: تطبيق جاهز يعتمد على Android ID ويسميه في واجهة الدخول IMEI. تعديل هذا المعرف يغير رقم الجهاز الظاهر في جاهز."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(note)

        chkAndroidId = CheckBox(this).apply {
            text = "تفعيل تغيير معرف الجهاز (Android ID Spoofing)"
            isChecked = SettingsStore.isSpoofAndroidId(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setSpoofAndroidId(this@MainActivity, isChecked)
            }
        }
        layout.addView(chkAndroidId)

        edtAndroidId = EditText(this).apply {
            hint = "أدخل 16 خانة سداسية عشرية (مثال: a1b2c3d4e5f67890)"
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
            text = "🎲 توليد عشوائي (16 خانة)"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                edtAndroidId.setText(generateRandomAndroidId())
                chkAndroidId.isChecked = true
            }
        }
        btnRow.addView(btnGen)

        val btnCopy = Button(this).apply {
            text = "📋 نسخ المعرف"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Android ID", edtAndroidId.text.toString().trim()))
                Toast.makeText(this@MainActivity, "تم نسخ المعرف إلى الحافظة", Toast.LENGTH_SHORT).show()
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
            text = "🗺️ الخريطة التفاعلية ونظام تحديد المواقع (GPS)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkFixLoc = CheckBox(this).apply {
            text = "حل مشكلة location unknown (حقن موقع دقيق دائماً)"
            isChecked = SettingsStore.isFixLocation(this@MainActivity)
        }
        layout.addView(chkFixLoc)

        chkFakeLoc = CheckBox(this).apply {
            text = "تفعيل الموقع المخصص المحدد على الخريطة (Fake GPS)"
            isChecked = SettingsStore.isFakeLocation(this@MainActivity)
        }
        layout.addView(chkFakeLoc)

        val coordsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }

        edtLat = EditText(this).apply {
            hint = "خط العرض (Lat)"
            val savedLat = SettingsStore.getFakeLat(this@MainActivity)
            setText(if (savedLat != 0.0) savedLat.toString() else "")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLat)

        edtLng = EditText(this).apply {
            hint = "خط الطول (Lng)"
            val savedLng = SettingsStore.getFakeLng(this@MainActivity)
            setText(if (savedLng != 0.0) savedLng.toString() else "")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordsRow.addView(edtLng)
        layout.addView(coordsRow)

        // Hotspots Buttons (Riyadh)
        val hotspotsLabel = TextView(this).apply {
            text = "⚡ مواقع سريعة (شمال الرياض):"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 4, 0, 4)
        }
        layout.addView(hotspotsLabel)

        val hotspotsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hotspotsRow1.addView(createHotspotBtn("الملقا", 24.7925, 46.6189))
        hotspotsRow1.addView(createHotspotBtn("حطين", 24.7648, 46.6022))
        layout.addView(hotspotsRow1)

        val hotspotsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 12)
        }
        hotspotsRow2.addView(createHotspotBtn("الياسمين", 24.8193, 46.6437))
        hotspotsRow2.addView(createHotspotBtn("العليا", 24.6987, 46.6842))
        layout.addView(hotspotsRow2)

        // Leaflet Interactive Map WebView
        mapWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                700
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        }
        layout.addView(mapWebView)

        loadMapContent()
        card.addView(layout)
        return card
    }

    private fun createHotspotBtn(name: String, lat: Double, lng: Double): Button {
        return Button(this).apply {
            text = name
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                edtLat.setText(lat.toString())
                edtLng.setText(lng.toString())
                chkFakeLoc.isChecked = true
                mapWebView.evaluateJavascript("setPin($lat, $lng);", null)
            }
        }
    }

    private fun loadMapContent() {
        val initialLat = edtLat.text.toString().toDoubleOrNull() ?: 24.7925
        val initialLng = edtLng.text.toString().toDoubleOrNull() ?: 46.6189

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
                    L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
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
            text = "⚡ إعدادات القبول والفلاتر الذكية"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        chkAutoAccept = CheckBox(this).apply {
            text = "تفعيل القبول التلقائي الفوري للطلبات"
            isChecked = SettingsStore.isAutoAccept(this@MainActivity)
        }
        layout.addView(chkAutoAccept)

        chkAutoReject = CheckBox(this).apply {
            text = "تفعيل الرفض التلقائي للطلبات غير المطابقة"
            isChecked = SettingsStore.isAutoReject(this@MainActivity)
        }
        layout.addView(chkAutoReject)

        chkDryRun = CheckBox(this).apply {
            text = "وضع التجربة (Dry-Run: فحص الطلب دون قبوله فعلياً)"
            isChecked = SettingsStore.isDryRun(this@MainActivity)
        }
        layout.addView(chkDryRun)

        layout.addView(createLabel("الحد الأدنى لسعر الطلب (SAR) - اتركه 0 لإلغاء القيد:"))
        edtMinPrice = createInput(SettingsStore.getMinPrice(this).toString())
        layout.addView(edtMinPrice)

        layout.addView(createLabel("أقصى مسافة للمطعم (كم) - اتركه 0 لإلغاء القيد:"))
        edtMaxDistRest = createInput(SettingsStore.getMaxDistRest(this).toString())
        layout.addView(edtMaxDistRest)

        layout.addView(createLabel("أقصى مسافة توصيل للعميل (كم) - اتركه 0 لإلغاء القيد:"))
        edtMaxDistCust = createInput(SettingsStore.getMaxDistCust(this).toString())
        layout.addView(edtMaxDistCust)

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

        // Android ID
        val spoofId = chkAndroidId.isChecked
        val customId = edtAndroidId.text.toString().trim()

        // Location
        val fixLoc = chkFixLoc.isChecked
        val fakeLoc = chkFakeLoc.isChecked
        val lat = edtLat.text.toString().toDoubleOrNull() ?: 0.0
        val lng = edtLng.text.toString().toDoubleOrNull() ?: 0.0

        // Filters
        val autoAccept = chkAutoAccept.isChecked
        val autoReject = chkAutoReject.isChecked
        val dryRun = chkDryRun.isChecked
        val minPrice = edtMinPrice.text.toString().toDoubleOrNull() ?: 0.0
        val maxRest = edtMaxDistRest.text.toString().toDoubleOrNull() ?: 0.0
        val maxCust = edtMaxDistCust.text.toString().toDoubleOrNull() ?: 0.0

        // Persist to SettingsStore & SharedPreferences
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

        Toast.makeText(
            this,
            "✅ تم حفظ وتطبيق الإعدادات بنجاح!\nقم بعمل إيقاف إجباري لتطبيق جاهز وأعد فتحه لتطبيق التغييرات.",
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
            text = "📋 سجل الطلبات اللحظي (HTTP / WebSocket)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        layout.addView(title)

        val desc = TextView(this).apply {
            text = "يتم رصد الطلبات الملتقطة فوراً عبر شبكة جاهز HTTP / WebSocket وعرضها هنا:"
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
            text = "🔄 تحديث السجل"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { refreshOrdersLogUI() }
        }
        btnRow.addView(btnRefresh)

        val btnClear = Button(this).apply {
            text = "🗑️ مسح السجل"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                SettingsStore.clearOrdersLog(this@MainActivity)
                refreshOrdersLogUI()
                Toast.makeText(this@MainActivity, "تم مسح سجل الطلبات", Toast.LENGTH_SHORT).show()
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

        if (array.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "لا توجد طلبات ملتقطة حتى الآن.
(تأكد من تشغيل المساعد واستقبال طلبات في تطبيق جاهز)."
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
            val id = item.optString("id", "#--")
            val price = item.optDouble("price", 0.0)
            val dist = item.optDouble("distance", 0.0)
            val rest = item.optString("restaurant", "")
            val status = item.optString("status", "")
            val time = item.optString("time", "")

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#F8FAFC" else "#FFFFFF"))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 8)
                layoutParams = params
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val idTv = TextView(this).apply {
                text = "طلب: "
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeTv = TextView(this).apply {
                text = time
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
            }
            topRow.addView(idTv)
            topRow.addView(timeTv)
            itemCard.addView(topRow)

            val detailsText = StringBuilder()
            if (price > 0.0) detailsText.append("السعر: ").append(price).append(" ر.س  ")
            if (dist > 0.0) detailsText.append("المسافة: ").append(dist).append(" كم  ")
            if (rest.isNotEmpty()) detailsText.append("(").append(rest).append(")")

            if (detailsText.isNotEmpty()) {
                val detailsTv = TextView(this).apply {
                    text = detailsText.toString()
                    textSize = 12f
                    setTextColor(Color.parseColor("#334155"))
                    setPadding(0, 4, 0, 2)
                }
                itemCard.addView(detailsTv)
            }

            val statusTv = TextView(this).apply {
                text = status
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (status.contains("رفض")) Color.parseColor("#DC2626") else Color.parseColor("#059669"))
                setPadding(0, 2, 0, 0)
            }
            itemCard.addView(statusTv)

            ordersLogContainer.addView(itemCard)
        }
    }

}
