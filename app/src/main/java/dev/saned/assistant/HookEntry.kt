package dev.saned.assistant

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    companion object {
        @Volatile var appContext: Context? = null

        fun syncAllFromProvider(context: Context) {
            appContext = context.applicationContext
            try {
                val uri = Uri.parse("content://dev.jing.sanedhook.XposedService")
                val bundle = context.contentResolver.call(uri, "get", null, null)
                if (bundle != null) {
                    DeviceSpoofer.updateFromBundle(bundle)
                    LocationEngine.updateFromBundle(bundle)
                    OrderInterceptor.updateFromBundle(bundle)
                }
            } catch (_: Throwable) {}
        }
    }

    private val isInitialized = AtomicBoolean(false)

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets") return
        val cl = param.defaultClassLoader
        if (cl != null) {
            initAllHooks(cl)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets") return
        val cl = param.classLoader
        if (cl != null) {
            initAllHooks(cl)
        }
    }

    private fun initAllHooks(classLoader: ClassLoader) {
        if (!isInitialized.compareAndSet(false, true)) return

        // 1. Connect Remote Preferences via LibXposed IPC
        try {
            val remotePrefs = getRemotePreferences("sanedhook_settings")
            DeviceSpoofer.initRemotePrefs(remotePrefs)
            LocationEngine.initRemotePrefs(remotePrefs)
            OrderInterceptor.initRemotePrefs(remotePrefs)
        } catch (_: Throwable) {}

        // 2. Hook Activity.onCreate for notification toast and context caching
        try {
            val mOnCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(mOnCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        syncAllFromProvider(act)
                        DeviceSpoofer.syncSettings()
                        LocationEngine.syncLocationSettings()
                        if (DeviceSpoofer.isMasterRunning) {
                            try {
                                Toast.makeText(act, "⚡ مساعد سند مفعل ويعمل بنجاح!", Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {}
                        }
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Activity.onResume for refreshing settings
        try {
            val mOnResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(mOnResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        syncAllFromProvider(act)
                        DeviceSpoofer.syncSettings()
                        LocationEngine.syncLocationSettings()
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 4. Register All Feature Hooks
        try {
            PackageCloaker.hook(this, classLoader)
            LocationEngine.hook(this, classLoader)
            DeviceSpoofer.hook(this, classLoader)
            OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
