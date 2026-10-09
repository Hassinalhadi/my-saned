package dev.saned.assistant

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

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
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "net.jahez.fleets") return

        XposedBridge.log("==========================================")
        XposedBridge.log("SanedAssistant PRO: Hooking net.jahez.fleets")
        XposedBridge.log("==========================================")

        try {
            // Hook Application.onCreate to immediately sync settings
            try {
                XposedHelpers.findAndHookMethod(
                    Application::class.java,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.thisObject as Application
                            SettingsSync.syncFromProvider(app.contentResolver)
                        }
                    }
                )
            } catch (_: Throwable) {}

            // 0. Cloak module from package scans
            PackageCloaker.hook(lpparam)

            // 1. Hook GPS & Location
            LocationEngine.hook(lpparam)

            // 2. Hook Device Identity (Android ID, IMEI, Hardware)
            DeviceSpoofer.hook(lpparam)

            // 3. Hook Orders & Auto-Accept
            OrderInterceptor.hook(lpparam)

            XposedBridge.log("SanedAssistant PRO: All hooks active with dynamic IPC settings sync!")
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant PRO: Critical hook error: ${t.message}")
        }
    }
}
