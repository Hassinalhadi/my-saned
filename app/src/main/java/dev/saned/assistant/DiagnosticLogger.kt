package dev.saned.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticLogger {

    @Volatile var xposedModule: XposedModule? = null
    @Volatile var cachedContext: Context? = null

    // Per-Hook Group Toggles (Always TRUE by default)
    @Volatile var hookEarlyCloaker: Boolean = true
    @Volatile var hookSecCloaker: Boolean = true
    @Volatile var hookLocation: Boolean = true
    @Volatile var hookDeviceSpoofer: Boolean = true
    @Volatile var hookOrders: Boolean = true
    @Volatile var hookBootstrapLoop: Boolean = true

    private val crashHandlerInstalled = AtomicBoolean(false)
    private val providerUri: Uri = Uri.parse("content://dev.jing.sanedhook.XposedService")
    private val logExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Saned-DiagLogger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 2
        }
    }

    fun resolveAnyContext(): Context? {
        cachedContext?.let { return it }
        OrderInterceptor.resolveApplicationContext()?.let {
            cachedContext = it
            return it
        }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val sysCtx = atClass.getMethod("getSystemContext").invoke(currentAt) as? Context
            if (sysCtx != null) cachedContext = sysCtx
            sysCtx
        } catch (_: Throwable) { null }
    }

    fun syncHookToggles(module: XposedModule? = null, prefs: SharedPreferences? = null) {
        if (module != null) xposedModule = module
        if (prefs != null) {
            try {
                hookEarlyCloaker = prefs.getBoolean("hook_early_cloaker", true)
                hookSecCloaker = prefs.getBoolean("hook_sec_cloaker", true)
                hookLocation = prefs.getBoolean("hook_location", true)
                hookDeviceSpoofer = prefs.getBoolean("hook_device_spoofer", true)
                hookOrders = prefs.getBoolean("hook_orders", true)
                hookBootstrapLoop = prefs.getBoolean("hook_bootstrap_loop", true)
            } catch (_: Throwable) {}
        }
    }

    fun installCrashHandler(module: XposedModule? = null, phase: String = "init") {
        if (module != null) xposedModule = module
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        try {
            val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    deliverLogLineSync("CRASH", "Thread:${thread.name}", "Uncaught exception ($phase)", throwable, maxTraceLines = 20)
                } catch (_: Throwable) {}
                prevHandler?.uncaughtException(thread, throwable)
            }
            logStep("CRASH-GUARD", "UncaughtExceptionHandler active ($phase) pid=${android.os.Process.myPid()}")
        } catch (_: Throwable) {}
    }

    private fun deliverLogLineSync(level: String, section: String, message: String, t: Throwable? = null, maxTraceLines: Int = 8) {
        try {
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val errSuffix = if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                val trace = sw.toString().lines().take(maxTraceLines).joinToString("\n  ")
                "\n  ↳ ${t.javaClass.name}: ${t.message ?: ""}\n  $trace"
            } else {
                ""
            }
            val line = "[$timeStr] [$level] [$section] $message$errSuffix"
            try {
                Log.d("SanedDiag", line)
            } catch (_: Throwable) {}

            val ctx = resolveAnyContext() ?: return
            var delivered = false
            try {
                val extras = Bundle().apply {
                    putString("log_line", line)
                }
                val res = ctx.contentResolver.call(providerUri, "appendDiagLog", line, extras)
                if (res != null && res.getBoolean("ok", false)) {
                    delivered = true
                }
            } catch (_: Throwable) {}

            if (!delivered) {
                try {
                    val intent = Intent("dev.saned.assistant.ACTION_DIAG_LOG").apply {
                        component = ComponentName("dev.jing.sanedhook", "dev.saned.assistant.SanedReceiver")
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                        putExtra("log_line", line)
                    }
                    ctx.sendBroadcast(intent)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun dispatchLogAsync(level: String, section: String, message: String, t: Throwable? = null) {
        logExecutor.execute {
            deliverLogLineSync(level, section, message, t, maxTraceLines = 8)
        }
    }

    fun logStep(section: String, message: String) {
        dispatchLogAsync("STEP", section, message, null)
    }

    fun logWarn(section: String, message: String) {
        dispatchLogAsync("WARN", section, message, null)
    }

    fun logError(section: String, message: String, t: Throwable? = null) {
        dispatchLogAsync("ERR", section, message, t)
    }

    fun logCrash(threadName: String, t: Throwable) {
        deliverLogLineSync("CRASH", "Thread:$threadName", "Uncaught exception", t, maxTraceLines = 20)
    }
}
