package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

object DiagnosticLogger {

    @Volatile var xposedModule: XposedModule? = null
    @Volatile var cachedContext: Context? = null

    // Per-Hook Group Toggles (Always TRUE)
    @Volatile var hookEarlyCloaker: Boolean = true
    @Volatile var hookSecCloaker: Boolean = true
    @Volatile var hookLocation: Boolean = true
    @Volatile var hookDeviceSpoofer: Boolean = true
    @Volatile var hookOrders: Boolean = true
    @Volatile var hookBootstrapLoop: Boolean = true

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
    }

    fun installCrashHandler(module: XposedModule? = null, phase: String = "init") {
        if (module != null) xposedModule = module
    }

    fun logStep(section: String, message: String) {
        // Log catching removed as requested
    }

    fun logWarn(section: String, message: String) {
        // Log catching removed as requested
    }

    fun logError(section: String, message: String, t: Throwable? = null) {
        // Log catching removed as requested
    }

    fun logCrash(threadName: String, t: Throwable) {
        // Log catching removed as requested
    }
}
