package dev.saned.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val isAppHooked = AtomicBoolean(false)
    @Volatile private var detectedVersionCode: Long = 0L

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        DiagnosticLogger.xposedModule = this
        DiagnosticLogger.logStep("HookEntry", "onModuleLoaded process=${param.processName} framework=$frameworkName")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        DiagnosticLogger.installCrashHandler(this, "onPackageLoaded")
        DiagnosticLogger.syncHookToggles(this)

        val vCode = resolveJahezVersionCode(param.applicationInfo)
        if (vCode > 0L) detectedVersionCode = vCode
        val isSupported = PackageCloaker.checkVersionSupported(detectedVersionCode)
        DiagnosticLogger.logStep(
  "onPackageLoaded",
  "pkg=${param.packageName} vCode=$detectedVersionCode supported=$isSupported strict=${OrderInterceptor.isStrictVersionCheck} earlyToggle=${DiagnosticLogger.hookEarlyCloaker}"
        )
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
  DiagnosticLogger.logWarn("onPackageLoaded", "Stopping early hook because StrictVersionCheck is enabled and vCode=$detectedVersionCode is not in [40089, 40093, 40099]")
  return
        }
        if (DiagnosticLogger.hookEarlyCloaker) {
  param.defaultClassLoader?.let { cl ->
      PackageCloaker.hookEarly(this, cl)
  }
        } else {
  DiagnosticLogger.logWarn("onPackageLoaded", "Skipped PackageCloaker.hookEarly (disabled by user toggle)")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        DiagnosticLogger.installCrashHandler(this, "onPackageReady")
        DiagnosticLogger.syncHookToggles(this)

        if (detectedVersionCode <= 0L) {
  detectedVersionCode = resolveJahezVersionCode(param.applicationInfo)
        }
        val isSupported = OrderInterceptor.checkJahezVersionSupport(detectedVersionCode)
        DiagnosticLogger.logStep(
  "onPackageReady",
  "pkg=${param.packageName} vCode=$detectedVersionCode supported=$isSupported cl=${param.classLoader?.javaClass?.name}"
        )
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
  OrderInterceptor.notifyUnsupportedVersion(detectedVersionCode)
  return
        }
        val cl = param.classLoader ?: return
        if (DiagnosticLogger.hookEarlyCloaker) {
  PackageCloaker.hookEarly(this, cl)
        }
        initAppHooks(cl)
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
