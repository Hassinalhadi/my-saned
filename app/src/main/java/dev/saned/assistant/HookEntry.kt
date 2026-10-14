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
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        val vCode = resolveJahezVersionCode(param.applicationInfo)
        if (vCode > 0L) detectedVersionCode = vCode
        val isSupported = PackageCloaker.checkVersionSupported(detectedVersionCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) return
        // Matches Ldv1.onPackageLoaded: ONLY run Lvh1 (o, q, j, e) during onPackageLoaded!
        param.defaultClassLoader?.let { cl ->
  PackageCloaker.hookEarly(this, cl)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets") return
        if (detectedVersionCode <= 0L) {
  detectedVersionCode = resolveJahezVersionCode(param.applicationInfo)
        }
        val isSupported = OrderInterceptor.checkJahezVersionSupport(detectedVersionCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
  OrderInterceptor.notifyUnsupportedVersion(detectedVersionCode)
  return
        }
        val cl = param.classLoader ?: return
        PackageCloaker.hookEarly(this, cl)
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
  DeviceSpoofer.initRemotePrefs(remotePrefs)
  LocationEngine.initRemotePrefs(remotePrefs)
  OrderInterceptor.initRemotePrefs(remotePrefs)
        } catch (_: Throwable) {}
        try {
  Native.safeA(this, "net.jahez.fleets")
  Native.safeC(this, "net.jahez.fleets", OrderInterceptor.licenseToken, OrderInterceptor.licenseBoundId, 0L)
        } catch (_: Throwable) {}
        try { PackageCloaker.hook(this, classLoader) } catch (_: Throwable) {}
        try { LocationEngine.hook(this, classLoader) } catch (_: Throwable) {}
        try { DeviceSpoofer.hook(this, classLoader) } catch (_: Throwable) {}
        try { OrderInterceptor.hook(this, classLoader) } catch (_: Throwable) {}
        try { OrderInterceptor.startBootstrapLoop(this, classLoader) } catch (_: Throwable) {}
    }
}
