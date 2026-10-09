package dev.saned.assistant

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
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

        // 1. Hook Application.onCreate to get Application context
        try {
            val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(mAppCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null) {
                        try {
                            OrderInterceptor.initAppContext(app)
                            initAppHooks(app.classLoader)
                        } catch (_: Throwable) {}
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Activity.onCreate
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
                        if (OrderInterceptor.isMasterRunning) {
                            try {
                                Toast.makeText(act, "⚡ مساعد سند مفعل ويعمل بنجاح!", Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {}
                        }
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}
    }

    private fun initAppHooks(classLoader: ClassLoader) {
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
