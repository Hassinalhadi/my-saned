package dev.saned.assistant

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule(), IXposedHookLoadPackage {

    companion object {
        @Volatile var isModuleEnabled: Boolean = true
        @Volatile var isAutoAccept: Boolean = true
        @Volatile var isAutoReject: Boolean = false
        @Volatile var isDryRun: Boolean = false
        @Volatile var minOrderPrice: Double = 0.0
        @Volatile var maxDistToRestaurant: Double = 8.0
        @Volatile var maxDistCustomer: Double = 15.0
        @Volatile var isSoundEnabled: Boolean = true
        @Volatile var isShowToasts: Boolean = true

        private val isInitialized = AtomicBoolean(false)

        fun initAllHooks(classLoader: ClassLoader) {
            if (!isInitialized.compareAndSet(false, true)) return

            XposedBridge.log("==========================================")
            XposedBridge.log("SanedAssistant PRO: Hooks Active in net.jahez.fleets!")
            XposedBridge.log("==========================================")

            try {
                // Show a toast when Jahez opens so user knows 100% the hook is running!
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    "onCreate",
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as Activity
                            if (act.packageName == "net.jahez.fleets") {
                                Toast.makeText(act, "⚡ مساعد سند مفعل ويعمل بنجاح!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                PackageCloaker.hook(classLoader)
                LocationEngine.hook(classLoader)
                DeviceSpoofer.hook(classLoader)
                OrderInterceptor.hook(classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("SanedAssistant: Critical hook error: ${t.message}")
            }
        }
    }

    // 1. Called by Modern LibXposed
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "net.jahez.fleets") {
            initAllHooks(param.defaultClassLoader ?: param.classLoader)
        }
    }

    // 2. Called by Legacy Xposed
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "net.jahez.fleets") {
            initAllHooks(lpparam.classLoader)
        }
    }
}
