package dev.saned.assistant

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object OrderInterceptor {

    @Volatile var isMasterRunning: Boolean = false
    @Volatile var isAutoAccept: Boolean = false
    @Volatile var isAutoReject: Boolean = false
    @Volatile var isDryRun: Boolean = false
    @Volatile var minOrderPrice: Double = 0.0
    @Volatile var maxDistToRestaurant: Double = 0.0
    @Volatile var maxDistCustomer: Double = 0.0
    @Volatile var isSoundEnabled: Boolean = false
    @Volatile var isShowToasts: Boolean = false

    private val isAccepting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remotePrefs: SharedPreferences? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings() {
        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", false)
            isAutoAccept = p.getBoolean("auto_accept", false)
            isAutoReject = p.getBoolean("auto_reject", false)
            isDryRun = p.getBoolean("dry_run", false)
            minOrderPrice = p.getString("min_price", "0.0")?.toDoubleOrNull() ?: 0.0
            maxDistToRestaurant = p.getString("max_dist_rest", "0.0")?.toDoubleOrNull() ?: 0.0
            maxDistCustomer = p.getString("max_dist_cust", "0.0")?.toDoubleOrNull() ?: 0.0
            isSoundEnabled = p.getBoolean("sound_enabled", false)
            isShowToasts = p.getBoolean("show_toasts", false)
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == "net.jahez.fleets") {
                        syncSettings()
                        scanAndProcessOrder(activity)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}

        try {
            val mAttached = View::class.java.getDeclaredMethod("onAttachedToWindow")
            module.hook(mAttached).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    val context = view?.context
                    if (context is Activity && context.packageName == "net.jahez.fleets") {
                        syncSettings()
                        scanAndProcessOrder(context)
                    }
                    return result
                }
            })
        } catch (_: Throwable) {}
    }

    private fun scanAndProcessOrder(activity: Activity) {
        if (!isMasterRunning || !isAutoAccept) return

        val decorView = activity.window?.decorView ?: return
        val startTime = System.currentTimeMillis()

        mainHandler.postDelayed({
            try {
                findAndTriggerOrder(decorView, activity, startTime)
            } catch (_: Throwable) {}
        }, 50)
    }

    private fun findAndTriggerOrder(root: View, activity: Activity, startTime: Long) {
        var acceptButton: View? = null
        var rejectButton: View? = null
        var orderPrice = 0.0
        var distToRestaurant = 0.0
        var orderId = ""

        fun traverse(v: View) {
            if (v is TextView) {
                val text = v.text.toString()
                if (text.contains("SAR") || text.contains("﷼") || text.matches(Regex(".*\\d+\\.\\d+.*"))) {
                    val priceMatch = Regex("(\\d+(?:\\.\\d+)?)").find(text)
                    if (priceMatch != null && orderPrice == 0.0) {
                        orderPrice = priceMatch.value.toDoubleOrNull() ?: 0.0
                    }
                }
                if (text.contains("From You", ignoreCase = true) || text.contains("منك")) {
                    val distMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*Km").find(text)
                    if (distMatch != null) {
                        distToRestaurant = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
                }
                if (text.contains("#") || text.matches(Regex(".*\\d{7,}.*"))) {
                    val idMatch = Regex("(#?\\d{7,})").find(text)
                    if (idMatch != null) {
                        orderId = idMatch.value
                    }
                }
            }

            val desc = v.contentDescription?.toString() ?: ""
            val text = (v as? TextView)?.text?.toString() ?: ""
            if (text.equals("Accept", ignoreCase = true) || text.contains("قبول") ||
                desc.equals("Accept", ignoreCase = true) || desc.contains("قبول")) {
                acceptButton = v
            }

            if (text.equals("Reject", ignoreCase = true) || text.contains("رفض") ||
                desc.equals("Reject", ignoreCase = true) || desc.contains("رفض")) {
                rejectButton = v
            }

            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    traverse(v.getChildAt(i))
                }
            }
        }

        traverse(root)

        if (acceptButton != null && acceptButton!!.isShown && acceptButton!!.isEnabled) {
            evaluateAndAccept(activity, acceptButton!!, rejectButton, orderPrice, distToRestaurant, orderId, startTime)
        }
    }

    private fun evaluateAndAccept(
        ctx: Context,
        btnAccept: View,
        btnReject: View?,
        price: Double,
        distRest: Double,
        orderId: String,
        startTime: Long
    ) {
        syncSettings()
        if (minOrderPrice > 0 && price > 0 && price < minOrderPrice) {
            showToast(ctx, "❌ تم رفض الطلب $orderId: السعر ($price) أقل من الحد الأدنى ($minOrderPrice)")
            if (isAutoReject && btnReject != null) btnReject.performClick()
            return
        }

        if (maxDistToRestaurant > 0 && distRest > 0 && distRest > maxDistToRestaurant) {
            showToast(ctx, "❌ تم رفض الطلب $orderId: المسافة ($distRest كم) أبعد من الحد ($maxDistToRestaurant كم)")
            if (isAutoReject && btnReject != null) btnReject.performClick()
            return
        }

        if (isDryRun) {
            showToast(ctx, "🔍 [وضع التجربة]: كان سيتم قبول الطلب $orderId بنجاح")
            return
        }

        if (isAccepting.compareAndSet(false, true)) {
            val latency = System.currentTimeMillis() - startTime
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    btnAccept.performClick()
                    btnAccept.callOnClick()
                    showToast(ctx, "✅ تم قبول الطلب $orderId فورياً (${latency}ms)!")
                    playAlertSound()
                } finally {
                    delay(800)
                    isAccepting.set(false)
                }
            }
        }
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
}
