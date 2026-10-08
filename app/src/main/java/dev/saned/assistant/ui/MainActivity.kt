package dev.saned.assistant.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import dev.saned.assistant.R
import dev.saned.assistant.SettingsStore

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)

        setContentView(createLayout())
    }

    private fun createLayout(): android.view.View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 50, 40, 50)
            setBackgroundColor(android.graphics.Color.parseColor("#F8F9FA"))
        }

        // شارة الحالة (مفتوح بالكامل وبدون قيود)
        val statusCard = TextView(this).apply {
            text = "✅ " + getString(R.string.status_unlocked)
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
            setPadding(30, 20, 30, 20)
        }
        layout.addView(statusCard)

        // عنوان الإعدادات
        val title = TextView(this).apply {
            text = getString(R.string.title_settings)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 40, 0, 20)
        }
        layout.addView(title)

        // مفتاح تفعيل القبول التلقائي
        val switchAutoAccept = SwitchCompat(this).apply {
            text = getString(R.string.pref_auto_accept)
            isChecked = settings.isAutoAcceptEnabled
            textSize = 16f
            setOnCheckedChangeListener { _, isChecked ->
                settings.isAutoAcceptEnabled = isChecked
            }
        }
        layout.addView(switchAutoAccept)

        // مفتاح تجاوز مشكلة location unknown
        val switchIgnoreLoc = SwitchCompat(this).apply {
            text = getString(R.string.pref_ignore_unknown_loc)
            isChecked = settings.acceptWhenLocationUnknown
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
            setOnCheckedChangeListener { _, isChecked ->
                settings.acceptWhenLocationUnknown = isChecked
            }
        }
        layout.addView(switchIgnoreLoc)

        // حقول المسافات والأسعار
        val editPrice = createInputField("أدنى سعر للطلب (SAR):", settings.minPrice.toString())
        layout.addView(editPrice.first)
        layout.addView(editPrice.second)

        val editRestDist = createInputField("أقصى مسافة للمطعم (كم):", settings.maxRestaurantDistKm.toString())
        layout.addView(editRestDist.first)
        layout.addView(editRestDist.second)

        // زر الحفظ
        val btnSave = Button(this).apply {
            text = getString(R.string.btn_save)
            setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                settings.minPrice = editPrice.second.text.toString().toDoubleOrNull() ?: 10.0
                settings.maxRestaurantDistKm = editRestDist.second.text.toString().toDoubleOrNull() ?: 5.0
                Toast.makeText(this@MainActivity, "تم حفظ الإعدادات بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnSave)

        return layout
    }

    private fun createInputField(label: String, initialValue: String): Pair<TextView, EditText> {
        val tv = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, 20, 0, 5)
        }
        val et = EditText(this).apply {
            setText(initialValue)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        return tv to et
    }
}
