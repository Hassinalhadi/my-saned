package dev.saned.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val isSystemHooked = AtomicBoolean(false)
    private val isAppHooked = AtomicBoolean(false)

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets") return
        hookSystemLifecycle()
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets") return
        val cl = param.classLoader
        if (cl != null) {
            initAppHooks(cl)
        }
    }

    private fun hookSystemLifecycle() {
        if (!isSystemHooked.compareAndSet(false, true)) return

        // 1. Hook Application.onCreate (initializes app context & settings BEFORE chain.proceed)
        try {
            val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(mAppCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val app = chain.thisObject as? Application
                    if (app != null && app.packageName == "net.jahez.fleets") {
                        try {
                            initAppHooks(app.classLoader)
                            OrderInterceptor.initAppContext(app)
                        } catch (_: Throwable) {}
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Activity.onCreate matching Ls20.d
        try {
            val mOnCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(mOnCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
                        OrderInterceptor.onActivityCreated(act)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Activity.onResume (starts Screen Watcher & scans NewOrderActivity layout)
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

        // 4. Hook Activity.onWindowFocusChanged (catches NewOrderActivity when window gains focus)
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

        if (!isAppHooked.compareAndSet(false, true)) return

        try {
            PackageCloaker.hook(this, classLoader)
            LocationEngine.hook(this, classLoader)
            DeviceSpoofer.hook(this, classLoader)
            OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
