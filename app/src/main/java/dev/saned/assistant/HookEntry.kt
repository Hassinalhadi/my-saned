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
        if (!param.packageName.contains("jahez") && param.packageName != "net.jahez.fleets") return
        hookSystemLifecycle()
        // Do NOT call initAppHooks here with defaultClassLoader as it only contains boot classes
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.packageName.contains("jahez") && param.packageName != "net.jahez.fleets") return
        val cl = param.classLoader
        if (cl != null) {
            initAppHooks(cl)
        }
    }

    private fun hookSystemLifecycle() {
        if (!isSystemHooked.compareAndSet(false, true)) return

        // 1. Hook Application.onCreate
        try {
            val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(mAppCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && (app.packageName == "net.jahez.fleets" || app.packageName.contains("jahez"))) {
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
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
                        try {
                            Toast.makeText(act, "⚡ Saned Assistant Connected!", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Activity.onResume
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
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

        if (!isAppHooked.compareAndSet(false, true)) {
            // Re-invoke OrderInterceptor hooks with new ClassLoader if not yet hooked
            OrderInterceptor.hook(this, classLoader)
            return
        }

        try {
            PackageCloaker.hook(this, classLoader)
            LocationEngine.hook(this, classLoader)
            DeviceSpoofer.hook(this, classLoader)
            OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
