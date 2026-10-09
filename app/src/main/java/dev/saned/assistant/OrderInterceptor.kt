package dev.saned.assistant

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object OrderInterceptor {

    private val isAccepting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hook(classLoader: ClassLoader) {
        // classLoader passed via parameter

        // Hook Activity lifecycle to detect order views and accept button
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        scanAndProcessOrder(activity)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking Activity onResume: ${t.message}")
        }

        // Hook View layout to instantly catch offer popups (0ms reaction)
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        val context = view.context
                        if (context is Activity && context.packageName == "net.jahez.fleets") {
                            scanAndProcessOrder(context)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking View onAttachedToWindow: ${t.message}")
        }
    }

    private fun scanAndProcessOrder(activity: Activity) {
        val decorView = activity.window?.decorView ?: return
        val startTime = System.currentTimeMillis()

        mainHandler.postDelayed({
            try {
                findAndTriggerOrder(decorView, activity, startTime)
            } catch (t: Throwable) {
                XposedBridge.log("SanedAssistant: Scan error: ${t.message}")
            }
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
                // Order price check
                if (text.contains("SAR") || text.contains("﷼") || text.matches(Regex(".*\\d+\\.\\d+.*"))) {
                    val priceMatch = Regex("(\\d+(?:\\.\\d+)?)").find(text)
                    if (priceMatch != null && orderPrice == 0.0) {
                        orderPrice = priceMatch.value.toDoubleOrNull() ?: 0.0
                    }
                }
                // Distance to restaurant
                if (text.contains("From You", ignoreCase = true) || text.contains("منك")) {
                    val distMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*Km").find(text)
                    if (distMatch != null) {
                        distToRestaurant = distMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
                }
                // Order ID
                if (text.contains("#") || text.matches(Regex(".*\\d{7,}.*"))) {
                    val idMatch = Regex("(#?\\d{7,})").find(text)
                    if (idMatch != null) {
                        orderId = idMatch.value
                    }
                }
            }

            // Accept button detection
            val desc = v.contentDescription?.toString() ?: ""
            val text = (v as? TextView)?.text?.toString() ?: ""
            if (text.equals("Accept", ignoreCase = true) || text.contains("قبول") ||
                desc.equals("Accept", ignoreCase = true) || desc.contains("قبول")) {
                acceptButton = v
            }

            // Reject button detection
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
        val minPrice = HookEntry.minOrderPrice
        val maxDist = HookEntry.maxDistToRestaurant
        val autoAccept = HookEntry.isAutoAccept
        val autoReject = HookEntry.isAutoReject
        val dryRun = HookEntry.isDryRun

        // 1. Check Filters
        if (minPrice > 0 && price > 0 && price < minPrice) {
            val msg = "❌ تم رفض الطلب $orderId: السعر ($price) أقل من الحد الأدنى ($minPrice)"
            showToast(ctx, msg)
            if (autoReject && btnReject != null) {
                btnReject.performClick()
            }
            return
        }

        if (maxDist > 0 && distRest > 0 && distRest > maxDist) {
            val msg = "❌ تم رفض الطلب $orderId: مسافة المطعم ($distRest كم) أبعد من الحد ($maxDist كم)"
            showToast(ctx, msg)
            if (autoReject && btnReject != null) {
                btnReject.performClick()
            }
            return
        }

        // 2. All gates passed
        if (!autoAccept) {
            showToast(ctx, "⚡ جميع الشروط مطابقة ولكن القبول التلقائي متوقف")
            return
        }

        if (dryRun) {
            showToast(ctx, "🔍 [وضع التجربة]: كان سيتم قبول الطلب $orderId بنجاح")
            return
        }

        // 3. Instant Parallel Accept Execution
        if (isAccepting.compareAndSet(false, true)) {
            val latency = System.currentTimeMillis() - startTime
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    btnAccept.performClick()
                    btnAccept.callOnClick()
                    val successMsg = "✅ تم قبول الطلب $orderId فورياً خلال ${latency}ms!"
                    showToast(ctx, successMsg)
                    playAlertSound()
                } finally {
                    delay(800)
                    isAccepting.set(false)
                }
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        if (!HookEntry.isShowToasts) return
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun playAlertSound() {
        if (!HookEntry.isSoundEnabled) return
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        } catch (_: Throwable) {}
    }
}
