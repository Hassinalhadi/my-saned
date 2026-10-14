package dev.saned.assistant

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val isSystemHooked = AtomicBoolean(false)
    private val isAppHooked = AtomicBoolean(false)
    @Volatile private var detectedVersionCode: Long = 0L

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets" || !param.isFirstPackage) return
        val vCode = resolveJahezVersionCode(param.applicationInfo)
        if (vCode > 0L) detectedVersionCode = vCode
        val isSupported = OrderInterceptor.checkJahezVersionSupport(detectedVersionCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) return
        hookSystemLifecycle()
        param.defaultClassLoader?.let { initAppHooks(it) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets") return
        if (detectedVersionCode <= 0L) detectedVersionCode = resolveJahezVersionCode(param.applicationInfo)
        val isSupported = OrderInterceptor.checkJahezVersionSupport(detectedVersionCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
  OrderInterceptor.notifyUnsupportedVersion(detectedVersionCode)
  return
        }
        hookSystemLifecycle()
        param.classLoader?.let { initAppHooks(it) }
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

    private fun hookSystemLifecycle() {
        if (!isSystemHooked.compareAndSet(false, true)) return
        try {
  val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
  hook(mAppCreate).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val app = chain.thisObject as? Application
          if (app != null && app.packageName == "net.jahez.fleets") {
              try { initAppHooks(app.classLoader); OrderInterceptor.initAppContext(app) } catch (_: Throwable) {}
          }
          return chain.proceed()
      }
  })
        } catch (_: Throwable) {}

        try {
  val mOnCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
  hook(mOnCreate).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val act = chain.thisObject as? Activity
          val isJahez = act != null && act.packageName == "net.jahez.fleets"
          var detectedOrderId = 0
          if (isJahez && act != null) {
              OrderInterceptor.initAppContext(act.applicationContext)
              OrderInterceptor.currentActivity = act
              initAppHooks(act.classLoader)
              detectedOrderId = OrderInterceptor.onActivityPreCreated(act)
          }
          val res = chain.proceed()
          if (isJahez && act != null) {
              OrderInterceptor.onActivityCreated(act)
              if (detectedOrderId != 0) OrderInterceptor.onOrderActivityPostCreate(act, detectedOrderId)
          }
          return res
      }
  })
        } catch (_: Throwable) {}

        try {
  val mResume = Activity::class.java.getDeclaredMethod("onResume")
  hook(mResume).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val res = chain.proceed()
          val act = chain.thisObject as? Activity
          if (act != null && act.packageName == "net.jahez.fleets") {
              OrderInterceptor.initAppContext(act.applicationContext)
              OrderInterceptor.currentActivity = act
              OrderInterceptor.onActivityResumed(act)
          }
          return res
      }
  })
        } catch (_: Throwable) {}

        try {
  val mFocus = Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType)
  hook(mFocus).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val res = chain.proceed()
          val hasFocus = chain.args.getOrNull(0) as? Boolean ?: false
          val act = chain.thisObject as? Activity
          if (hasFocus && act != null && act.packageName == "net.jahez.fleets") {
              OrderInterceptor.currentActivity = act
              OrderInterceptor.onActivityResumed(act)
          }
          return res
      }
  })
        } catch (_: Throwable) {}
    }

    fun initAppHooks(classLoader: ClassLoader) {
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
        OrderInterceptor.startBootstrapLoop(this, classLoader)
        if (!isAppHooked.compareAndSet(false, true)) return
        try {
  PackageCloaker.hook(this, classLoader)
  LocationEngine.hook(this, classLoader)
  DeviceSpoofer.hook(this, classLoader)
  OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
