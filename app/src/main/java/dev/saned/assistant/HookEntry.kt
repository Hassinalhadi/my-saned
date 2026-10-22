package dev.saned.assistant

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val isAppHooked = AtomicBoolean(false)
    private val isAttachFallbackHooked = AtomicBoolean(false)
    @Volatile private var detectedVersionCode: Long = 0L

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        DiagnosticLogger.xposedModule = this
        val fw = try { frameworkName } catch (_: Throwable) { "Unknown" }
        DiagnosticLogger.logStep("HookEntry", "onModuleLoaded process=${param.processName} framework=$fw")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        DiagnosticLogger.installCrashHandler(this, "onPackageLoaded")
        DiagnosticLogger.syncHookToggles(this)

        val appInfo = resolveParamAppInfo(param)
        if (appInfo != null) {
            PackageCloaker.patchGoogleMapsApiKeyIfNeeded(appInfo, this)
        }
        val vCode = resolveJahezVersionCode(appInfo)
        if (vCode > 0L) detectedVersionCode = vCode
        val isSupported = PackageCloaker.checkVersionSupported(detectedVersionCode)
        val cl = resolveParamClassLoader(param)
        val fw = try { frameworkName } catch (_: Throwable) { "Unknown" }
        DiagnosticLogger.logStep(
            "onPackageLoaded",
            "pkg=${param.packageName} fw=$fw vCode=$detectedVersionCode supported=$isSupported strict=${OrderInterceptor.isStrictVersionCheck} earlyToggle=${DiagnosticLogger.hookEarlyCloaker}"
        )
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
            DiagnosticLogger.logWarn("onPackageLoaded", "Stopping early hook because StrictVersionCheck is enabled and vCode=$detectedVersionCode is not in [40089, 40093, 40099]")
            return
        }
        if (cl != null) {
            if (DiagnosticLogger.hookEarlyCloaker) {
                PackageCloaker.hookEarly(this, cl)
            } else {
                DiagnosticLogger.logWarn("onPackageLoaded", "Skipped PackageCloaker.hookEarly (disabled by user toggle)")
            }
            // Ensure compatibility with Root LSPosed API 100 (where onPackageReady may not exist)
            // as well as Non-Root NPatch / Vector API 101/102:
            if (!supportsPackageReadyCallback()) {
                DiagnosticLogger.logStep("onPackageLoaded", "API 100 framework detected — running initAppHooks directly from onPackageLoaded")
                initAppHooks(cl)
            } else {
                installApplicationAttachFallback(cl)
            }
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        DiagnosticLogger.installCrashHandler(this, "onPackageReady")
        DiagnosticLogger.syncHookToggles(this)

        val appInfo = resolveParamAppInfo(param)
        if (appInfo != null) {
            PackageCloaker.patchGoogleMapsApiKeyIfNeeded(appInfo, this)
        }
        if (detectedVersionCode <= 0L) {
            detectedVersionCode = resolveJahezVersionCode(appInfo)
        }
        val isSupported = OrderInterceptor.checkJahezVersionSupport(detectedVersionCode)
        val cl = resolveParamClassLoader(param)
        DiagnosticLogger.logStep(
            "onPackageReady",
            "pkg=${param.packageName} vCode=$detectedVersionCode supported=$isSupported cl=${cl?.javaClass?.name}"
        )
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
            OrderInterceptor.notifyUnsupportedVersion(detectedVersionCode)
            return
        }
        if (cl == null) return
        if (DiagnosticLogger.hookEarlyCloaker) {
            PackageCloaker.hookEarly(this, cl)
        }
        initAppHooks(cl)
    }

    private fun supportsPackageReadyCallback(): Boolean = try {
        Class.forName(
            "io.github.libxposed.api.XposedModuleInterface\$PackageReadyParam",
            false,
            XposedModule::class.java.classLoader
        )
        true
    } catch (_: Throwable) {
        false
    }

    private fun installApplicationAttachFallback(fallbackClassLoader: ClassLoader) {
        if (!isAttachFallbackHooked.compareAndSet(false, true)) return
        try {
            val mAttach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            val moduleRef = this
            hook(mAttach).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val ctx = chain.args.firstOrNull() as? Context
                    val activeCl = ctx?.classLoader ?: fallbackClassLoader
                    if (!isAppHooked.get()) {
                        DiagnosticLogger.logStep("AttachFallback", "Initializing app hooks via Application.attach fallback")
                        moduleRef.initAppHooks(activeCl)
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }

    private fun resolveParamClassLoader(param: Any): ClassLoader? {
        for (methodName in listOf("getClassLoader", "getDefaultClassLoader")) {
            try {
                val m = param.javaClass.getMethod(methodName)
                val cl = m.invoke(param) as? ClassLoader
                if (cl != null) return cl
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun resolveParamAppInfo(param: Any): ApplicationInfo? = try {
        param.javaClass.getMethod("getApplicationInfo").invoke(param) as? ApplicationInfo
    } catch (_: Throwable) {
        null
    }

    private fun resolveJahezVersionCode(appInfo: ApplicationInfo?): Long {
        if (appInfo != null) {
            for (fieldName in listOf("longVersionCode", "versionCode")) {
                try {
                    val f = appInfo.javaClass.getField(fieldName).apply { isAccessible = true }
                    val v = (f.get(appInfo) as? Number)?.toLong() ?: 0L
                    if (v > 0L) return v
                } catch (_: Throwable) {}
            }
        }
        for (ctx in listOfNotNull(OrderInterceptor.resolveApplicationContext(), resolveSystemContext())) {
            try {
                val pInfo = ctx.packageManager.getPackageInfo("net.jahez.fleets", 0)
                val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                if (v > 0L) return v
            } catch (_: Throwable) {}
        }
        return 0L
    }

    private fun resolveSystemContext(): Context? = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
        atClass.getMethod("getSystemContext").invoke(currentAt) as? Context
    } catch (_: Throwable) { null }

    fun initAppHooks(classLoader: ClassLoader) {
        if (!isAppHooked.compareAndSet(false, true)) return
        try {
            val remotePrefs = getRemotePreferences("sanedhook_settings")
            DiagnosticLogger.syncHookToggles(this, remotePrefs)
            DeviceSpoofer.initRemotePrefs(remotePrefs)
            LocationEngine.initRemotePrefs(remotePrefs)
            OrderInterceptor.initRemotePrefs(remotePrefs)
            DiagnosticLogger.logStep("initAppHooks", "RemotePreferences initialized successfully")
        } catch (t: Throwable) {
            DiagnosticLogger.logError("initAppHooks:RemotePrefs", "Failed to read RemotePreferences (using ContentProvider/file fallback)", t)
        }
        try {
            Native.safeA(this, "net.jahez.fleets")
            Native.safeC(this, "net.jahez.fleets", OrderInterceptor.licenseToken, OrderInterceptor.licenseBoundId, 0L)
        } catch (_: Throwable) {}

        if (DiagnosticLogger.hookSecCloaker) {
            try {
                PackageCloaker.hook(this, classLoader)
                DiagnosticLogger.logStep("initAppHooks", "PackageCloaker.hook installed")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker.hook", "Exception in PackageCloaker.hook", t)
            }
        } else {
            DiagnosticLogger.logWarn("initAppHooks", "Skipped PackageCloaker.hook (disabled by user toggle)")
        }

        if (DiagnosticLogger.hookLocation) {
            try {
                LocationEngine.hook(this, classLoader)
                DiagnosticLogger.logStep("initAppHooks", "LocationEngine.hook installed")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("LocationEngine.hook", "Exception in LocationEngine.hook", t)
            }
        } else {
            DiagnosticLogger.logWarn("initAppHooks", "Skipped LocationEngine.hook (disabled by user toggle)")
        }

        if (DiagnosticLogger.hookDeviceSpoofer) {
            try {
                DeviceSpoofer.hook(this, classLoader)
                DiagnosticLogger.logStep("initAppHooks", "DeviceSpoofer.hook installed")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("DeviceSpoofer.hook", "Exception in DeviceSpoofer.hook", t)
            }
        } else {
            DiagnosticLogger.logWarn("initAppHooks", "Skipped DeviceSpoofer.hook (disabled by user toggle)")
        }

        if (DiagnosticLogger.hookOrders) {
            try {
                OrderInterceptor.hook(this, classLoader)
                DiagnosticLogger.logStep("initAppHooks", "OrderInterceptor.hook installed")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("OrderInterceptor.hook", "Exception in OrderInterceptor.hook", t)
            }
        } else {
            DiagnosticLogger.logWarn("initAppHooks", "Skipped OrderInterceptor.hook (disabled by user toggle)")
        }

        if (DiagnosticLogger.hookBootstrapLoop) {
            try {
                OrderInterceptor.startBootstrapLoop(this, classLoader)
                DiagnosticLogger.logStep("initAppHooks", "OrderInterceptor.startBootstrapLoop started")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("OrderInterceptor.startBootstrapLoop", "Exception starting bootstrap loop", t)
            }
        } else {
            DiagnosticLogger.logWarn("initAppHooks", "Skipped OrderInterceptor.startBootstrapLoop (disabled by user toggle)")
        }
    }
}
