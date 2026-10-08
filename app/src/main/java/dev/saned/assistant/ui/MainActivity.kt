package dev.saned.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import dev.saned.assistant.OverlayService
import dev.saned.assistant.SettingsStore
import java.util.UUID

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 48)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        scroll.addView(mainLayout)

        // Header Title
        val header = TextView(this).apply {
            text = "🚀 Saned Assistant PRO"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFD32F2F.toInt())
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(header)

        val subHeader = TextView(this).apply {
            text = "نسخة مفتوحة المصدر فائقة الأداء - بدون قيود أو قفل ترخيص"
            textSize = 13f
            setTextColor(0xFF616161.toInt())
            setPadding(0, 0, 0, 28)
        }
        mainLayout.addView(subHeader)

        // ================= SECTION 1: AUTO ACCEPT & FILTERS =================
        val cardFilters = createCard("⚡ إعدادات القبول والفلاتر الذكية")
        val filterLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val chkAutoAccept = CheckBox(this).apply {
            text = "تفعيل القبول التلقائي الفوري (0ms Instant)"
            isChecked = SettingsStore.isAutoAccept(this@MainActivity)
        }
        filterLayout.addView(chkAutoAccept)

        val chkAutoReject = CheckBox(this).apply {
            text = "تفعيل الرفض التلقائي للطلبات غير المطابقة (لتفريغ الشاشة فوراً)"
            isChecked = SettingsStore.isAutoReject(this@MainActivity)
        }
        filterLayout.addView(chkAutoReject)

        val chkDryRun = CheckBox(this).apply {
            text = "وضع التجربة (Dry-Run: فحص الطلب وإظهار النتيجة دون قبوله فعلياً)"
            isChecked = SettingsStore.isDryRun(this@MainActivity)
        }
        filterLayout.addView(chkDryRun)

        filterLayout.addView(createLabel("الحد الأدنى لسعر الطلب (SAR):"))
        val edtMinPrice = createInput(SettingsStore.getMinPrice(this).toString())
        filterLayout.addView(edtMinPrice)

        filterLayout.addView(createLabel("أقصى مسافة للمطعم من موقعك (كم):"))
        val edtMaxDistRest = createInput(SettingsStore.getMaxDistRest(this).toString())
        filterLayout.addView(edtMaxDistRest)

        filterLayout.addView(createLabel("أقصى مسافة توصيل للعميل (كم):"))
        val edtMaxDistCust = createInput(SettingsStore.getMaxDistCust(this).toString())
        filterLayout.addView(edtMaxDistCust)

        cardFilters.addView(filterLayout)
        mainLayout.addView(cardFilters)

        // ================= SECTION 2: LOCATION & GPS =================
        val cardLocation = createCard("📍 نظام الموقع وتجاوز مشكلة location unknown")
        val locLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val chkFixLoc = CheckBox(this).apply {
            text = "حل مشكلة location unknown (حقن موقع دقيق دائماً)"
            isChecked = SettingsStore.isFixLocation(this@MainActivity)
        }
        locLayout.addView(chkFixLoc)

        val chkFakeLoc = CheckBox(this).apply {
            text = "تفعيل تزييف الموقع المباشر (Fake GPS)"
            isChecked = SettingsStore.isFakeLocation(this@MainActivity)
        }
        locLayout.addView(chkFakeLoc)

        locLayout.addView(createLabel("خط العرض المخصص (Latitude):"))
        val edtLat = createInput(SettingsStore.getFakeLat(this).toString())
        locLayout.addView(edtLat)

        locLayout.addView(createLabel("خط الطول المخصص (Longitude):"))
        val edtLng = createInput(SettingsStore.getFakeLng(this).toString())
        locLayout.addView(edtLng)

        // Presets Button
        val btnPresets = Button(this).apply {
            text = "📍 اختيار موقع سريع (الملقا / حطين / الياسمين)"
            setOnClickListener {
                // Set to Al Malqa Riyadh
                edtLat.setText("24.774265")
                edtLng.setText("46.638527")
                Toast.makeText(this@MainActivity, "تم تعيين الموقع: حي الملقا، الرياض", Toast.LENGTH_SHORT).show()
            }
        }
        locLayout.addView(btnPresets)

        cardLocation.addView(locLayout)
        mainLayout.addView(cardLocation)

        // ================= SECTION 3: DEVICE IDENTITY & IMEI SPOOFING =================
        val cardDevice = createCard("🛡️ تزييف هوية الجهاز (IMEI & Android ID)")
        val devLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val chkSpoofId = CheckBox(this).apply {
            text = "تفعيل تزييف معرّف الجهاز (Android ID)"
            isChecked = SettingsStore.isSpoofAndroidId(this@MainActivity)
        }
        devLayout.addView(chkSpoofId)

        devLayout.addView(createLabel("معرّف الجهاز المخصص (Android ID):"))
        val edtAndroidId = createInput(SettingsStore.getSpoofedAndroidId(this))
        devLayout.addView(edtAndroidId)

        val chkSpoofImei = CheckBox(this).apply {
            text = "تفعيل تزييف رقم الـ IMEI"
            isChecked = SettingsStore.isSpoofImei(this@MainActivity)
        }
        devLayout.addView(chkSpoofImei)

        devLayout.addView(createLabel("رقم الـ IMEI المخصص:"))
        val edtImei = createInput(SettingsStore.getSpoofedImei(this))
        devLayout.addView(edtImei)

        val btnGenRandom = Button(this).apply {
            text = "🎲 توليد هوية جديدة بضغطة زر (New Identity)"
            setOnClickListener {
                val newId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                val newImei = "86" + (1000000000000L + (Math.random() * 8999999999999L).toLong()).toString()
                edtAndroidId.setText(newId)
                edtImei.setText(newImei)
                Toast.makeText(this@MainActivity, "تم توليد معرّفات جديدة للجهاز بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }
        devLayout.addView(btnGenRandom)

        cardDevice.addView(devLayout)
        mainLayout.addView(cardDevice)

        // ================= SECTION 4: FLOATING HUD & AUDIO =================
        val cardHud = createCard("🖥️ الشاشة العائمة والتنبيهات")
        val hudLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val chkOverlay = CheckBox(this).apply {
            text = "إظهار شاشة المساعد العائمة فوق تطبيق جاهز (Floating HUD)"
            isChecked = SettingsStore.isShowOverlay(this@MainActivity)
        }
        hudLayout.addView(chkOverlay)

        val chkSound = CheckBox(this).apply {
            text = "تشغيل نغمة تنبيه خاصة عند قبول الطلب تلقائياً"
            isChecked = SettingsStore.isSoundEnabled(this@MainActivity)
        }
        hudLayout.addView(chkSound)

        val chkToasts = CheckBox(this).apply {
            text = "إظهار رسائل سريعة (Toasts) بتفاصيل وسرعة كل طلب"
            isChecked = SettingsStore.isShowToasts(this@MainActivity)
        }
        hudLayout.addView(chkToasts)

        cardHud.addView(hudLayout)
        mainLayout.addView(cardHud)

        // Save Button
        val btnSave = Button(this).apply {
            text = "💾 حفظ جميع الإعدادات وتطبيقها فوراً"
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 28, 0, 28)
            setOnClickListener {
                val prefs = SettingsStore.getPrefs(this@MainActivity).edit()
                prefs.putBoolean(SettingsStore.KEY_AUTO_ACCEPT, chkAutoAccept.isChecked)
                prefs.putBoolean(SettingsStore.KEY_AUTO_REJECT, chkAutoReject.isChecked)
                prefs.putBoolean(SettingsStore.KEY_DRY_RUN, chkDryRun.isChecked)
                prefs.putString(SettingsStore.KEY_MIN_PRICE, edtMinPrice.text.toString())
                prefs.putString(SettingsStore.KEY_MAX_DIST_REST, edtMaxDistRest.text.toString())
                prefs.putString(SettingsStore.KEY_MAX_DIST_CUST, edtMaxDistCust.text.toString())

                prefs.putBoolean(SettingsStore.KEY_FIX_LOCATION, chkFixLoc.isChecked)
                prefs.putBoolean(SettingsStore.KEY_FAKE_LOCATION, chkFakeLoc.isChecked)
                prefs.putString(SettingsStore.KEY_FAKE_LAT, edtLat.text.toString())
                prefs.putString(SettingsStore.KEY_FAKE_LNG, edtLng.text.toString())

                prefs.putBoolean(SettingsStore.KEY_SPOOF_ANDROID_ID, chkSpoofId.isChecked)
                prefs.putString(SettingsStore.KEY_SPOOFED_ANDROID_ID, edtAndroidId.text.toString())
                prefs.putBoolean(SettingsStore.KEY_SPOOF_IMEI, chkSpoofImei.isChecked)
                prefs.putString(SettingsStore.KEY_SPOOFED_IMEI, edtImei.text.toString())

                prefs.putBoolean(SettingsStore.KEY_SHOW_OVERLAY, chkOverlay.isChecked)
                prefs.putBoolean(SettingsStore.KEY_SOUND, chkSound.isChecked)
                prefs.putBoolean(SettingsStore.KEY_SHOW_TOASTS, chkToasts.isChecked)
                prefs.apply()

                // Check overlay permission
                if (chkOverlay.isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                        startActivity(intent)
                    } else {
                        startService(Intent(this@MainActivity, OverlayService::class.java))
                    }
                }

                Toast.makeText(this@MainActivity, "✅ تم حفظ وتحديث الإعدادات بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }
        mainLayout.addView(btnSave)

        setContentView(scroll)
    }

    private fun createCard(titleText: String): CardView {
        val card = CardView(this).apply {
            radius = 16f
            cardElevation = 6f
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 32)
            layoutParams = lp
            setContentPadding(28, 24, 28, 28)
        }
        val title = TextView(this).apply {
            text = titleText
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFD32F2F.toInt())
            setPadding(0, 0, 0, 16)
        }
        card.addView(title)
        return card
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 16, 0, 4)
        }
    }

    private fun createInput(defaultVal: String): EditText {
        return EditText(this).apply {
            setText(defaultVal)
            textSize = 14f
            setBackgroundResource(android.R.drawable.edit_text)
        }
    }
}
