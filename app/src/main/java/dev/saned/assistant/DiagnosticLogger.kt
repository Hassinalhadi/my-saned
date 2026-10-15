package dev.saned.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticLogger {

    private const val TAG = "SanedDiag"
    private const val PROVIDER_URI = "content://dev.jing.sanedhook.XposedService"
    private const val ASSISTANT_PKG = "dev.jing.sanedhook"
    private const val RECEIVER_CLS = "dev.saned.assistant.SanedReceiver"

    @Volatile var xposedModule: XposedModule? = null
    @Volatile var cachedContext: Context? = null

    // Per-Hook Group Isolation Toggles (Default TRUE)
    @Volatile var hookEarlyCloaker: Boolean = true
    @Volatile var hookSecCloaker: Boolean = true
    @Volatile var hookLocation: Boolean = true
    @Volatile var hookDeviceSpoofer: Boolean = true
    @Volatile var hookOrders: Boolean = true
    @Volatile var hookBootstrapLoop: Boolean = true

    private val isCrashHandlerInstalled = AtomicBoolean(false)
    private val isUncaughtSetterHooked = AtomicBoolean(false)
    private val isFlusherStarted = AtomicBoolean(false)
    private val logQueue = ConcurrentLinkedQueue<String>()

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

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
        try {
  val p = prefs ?: module?.getRemotePreferences("sanedhook_settings")
  if (p != null) {
      hookEarlyCloaker = p.getBoolean("hook_early_cloaker", hookEarlyCloaker)
      hookSecCloaker = p.getBoolean("hook_sec_cloaker", hookSecCloaker)
      hookLocation = p.getBoolean("hook_location", hookLocation)
      hookDeviceSpoofer = p.getBoolean("hook_device_spoofer", hookDeviceSpoofer)
      hookOrders = p.getBoolean("hook_orders", hookOrders)
      hookBootstrapLoop = p.getBoolean("hook_bootstrap_loop", hookBootstrapLoop)
  }
        } catch (_: Throwable) {}

        try {
  val f = File("/data/local/tmp/saned_config.json")
  if (f.exists()) {
      val j = JSONObject(f.readText())
      hookEarlyCloaker = j.optBoolean("hook_early_cloaker", hookEarlyCloaker)
      hookSecCloaker = j.optBoolean("hook_sec_cloaker", hookSecCloaker)
      hookLocation = j.optBoolean("hook_location", hookLocation)
      hookDeviceSpoofer = j.optBoolean("hook_device_spoofer", hookDeviceSpoofer)
      hookOrders = j.optBoolean("hook_orders", hookOrders)
      hookBootstrapLoop = j.optBoolean("hook_bootstrap_loop", hookBootstrapLoop)
  }
        } catch (_: Throwable) {}

        try {
  val ctx = resolveAnyContext()
  if (ctx != null) {
      val b = ctx.contentResolver.call(Uri.parse(PROVIDER_URI), "getSettings", null, null)
      if (b != null) {
          hookEarlyCloaker = b.getBoolean("hook_early_cloaker", hookEarlyCloaker)
          hookSecCloaker = b.getBoolean("hook_sec_cloaker", hookSecCloaker)
          hookLocation = b.getBoolean("hook_location", hookLocation)
          hookDeviceSpoofer = b.getBoolean("hook_device_spoofer", hookDeviceSpoofer)
          hookOrders = b.getBoolean("hook_orders", hookOrders)
          hookBootstrapLoop = b.getBoolean("hook_bootstrap_loop", hookBootstrapLoop)
      }
  }
        } catch (_: Throwable) {}
    }

    fun installCrashHandler(module: XposedModule? = null, phase: String = "init") {
        if (module != null) xposedModule = module
        startAsyncFlusher()

        try {
  val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
  if (currentHandler !is SanedUncaughtHandler) {
      Thread.setDefaultUncaughtExceptionHandler(SanedUncaughtHandler(currentHandler))
  }
        } catch (_: Throwable) {}

        // Also hook Thread.setDefaultUncaughtExceptionHandler so Crashlytics/Sentry in Jahez cannot bypass our crash logger
        if (module != null && isUncaughtSetterHooked.compareAndSet(false, true)) {
  try {
      val mSet = Thread::class.java.getDeclaredMethod(
          "setDefaultUncaughtExceptionHandler",
          Thread.UncaughtExceptionHandler::class.java
      )
      module.hook(mSet).intercept(object : XposedInterface.Hooker {
          override fun intercept(chain: XposedInterface.Chain): Any? {
              val newHandler = chain.args.getOrNull(0) as? Thread.UncaughtExceptionHandler
              if (newHandler != null && newHandler !is SanedUncaughtHandler) {
                  val wrapped = SanedUncaughtHandler(newHandler)
                  return chain.proceed(arrayOf(wrapped))
              }
              return chain.proceed()
          }
      })
  } catch (_: Throwable) {}
        }

        if (isCrashHandlerInstalled.compareAndSet(false, true)) {
  logStep("CRASH-GUARD", "UncaughtExceptionHandler active ($phase) pid=${Process.myPid()}")
        }
    }

    private class SanedUncaughtHandler(
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
  try {
      logCrash(t.name, e)
  } catch (_: Throwable) {}
  delegate?.uncaughtException(t, e)
        }
    }

    fun logStep(section: String, message: String) {
        enqueueLine("[${nowTime()}] [STEP] [$section] $message")
    }

    fun logWarn(section: String, message: String) {
        enqueueLine("[${nowTime()}] [WARN] [$section] ⚠️ $message")
    }

    fun logError(section: String, message: String, t: Throwable? = null) {
        val trace = if (t != null) {
  val lines = Log.getStackTraceString(t).lineSequence().take(8).joinToString("\n   ")
  "\n   $lines"
        } else ""
        enqueueLine("[${nowTime()}] [ERR] [$section] ❌ $message$trace")
    }

    fun logCrash(threadName: String, t: Throwable) {
        val vCode = PackageCloaker.lastCheckedVersionCode
        val profIdx = PackageCloaker.detectedVersionIndex
        val stubbed = PackageCloaker.isNativeStubbedFlag()
        val actName = OrderInterceptor.currentActivity?.javaClass?.name ?: "none"
        val fullStack = Log.getStackTraceString(t)
        val crashBlock = buildString {
  append("[${nowTime()}] [CRASH] 🚨 FATAL UNCAUGHT EXCEPTION in net.jahez.fleets!\n")
  append("   PID: ${Process.myPid()} | Thread: $threadName\n")
  append("   Jahez VersionCode: $vCode | ProfileIdx: $profIdx | NativeStubbed: $stubbed | Activity: $actName\n")
  append("   Exception: ${t.javaClass.name}: ${t.message}\n")
  append(fullStack.trimEnd())
        }
        try { Log.e(TAG, crashBlock) } catch (_: Throwable) {}
        try { xposedModule?.log(Log.ERROR, TAG, crashBlock, t) } catch (_: Throwable) {}

        // Flush queued logs + crash block synchronously on a helper thread with bounded timeout before process dies
        val flushThread = Thread {
  try {
      val batch = StringBuilder()
      while (true) {
          val item = logQueue.poll() ?: break
          batch.append(item).append("\n")
      }
      batch.append(crashBlock)
      deliverToAssistantSync(batch.toString())
  } catch (_: Throwable) {}
        }
        flushThread.isDaemon = true
        flushThread.start()
        try { flushThread.join(900) } catch (_: Throwable) {}
    }

    private fun enqueueLine(line: String) {
        try { Log.i(TAG, line) } catch (_: Throwable) {}
        try { xposedModule?.log(Log.INFO, TAG, line) } catch (_: Throwable) {}
        logQueue.offer(line)
        startAsyncFlusher()
    }

    private fun startAsyncFlusher() {
        if (!isFlusherStarted.compareAndSet(false, true)) return
        Thread {
  while (true) {
      try {
          val sb = StringBuilder()
          var count = 0
          while (count < 25) {
              val item = logQueue.poll() ?: break
              if (sb.isNotEmpty()) sb.append("\n")
              sb.append(item)
              count++
          }
          if (sb.isNotEmpty()) {
              deliverToAssistantSync(sb.toString())
          }
      } catch (_: Throwable) {}
      try { Thread.sleep(300) } catch (_: Throwable) { break }
  }
        }.apply {
  isDaemon = true
  name = "Saned-Diag-Flusher"
  start()
        }
    }

    private fun deliverToAssistantSync(textBlock: String) {
        if (textBlock.isEmpty()) return
        // 1. Write to fallback file if accessible
        try {
  val f = File("/data/local/tmp/saned_diag.log")
  f.appendText(textBlock + "\n")
  f.setReadable(true, false)
  f.setWritable(true, false)
        } catch (_: Throwable) {}

        val ctx = resolveAnyContext() ?: return

        // 2. Deliver via ContentProvider call (works even before Application.onCreate using systemContext!)
        var deliveredViaProvider = false
        try {
  val extras = Bundle().apply { putString("log_line", textBlock) }
  val res = ctx.contentResolver.call(Uri.parse(PROVIDER_URI), "appendDiagLog", textBlock, extras)
  if (res?.getBoolean("ok", false) == true) {
      deliveredViaProvider = true
  }
        } catch (_: Throwable) {}

        // 3. Deliver via explicit component Broadcast to SanedReceiver if provider call didn't succeed
        if (!deliveredViaProvider) {
  try {
      val intent = Intent("dev.saned.assistant.ACTION_DIAG_LOG").apply {
          setClassName(ASSISTANT_PKG, RECEIVER_CLS)
          addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
          putExtra("log_line", textBlock)
      }
      ctx.sendBroadcast(intent)
  } catch (_: Throwable) {}
        }
    }
}
