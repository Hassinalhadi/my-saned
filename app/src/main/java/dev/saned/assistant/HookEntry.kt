package dev.saned.assistant

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    companion object {
        // In-memory synced settings for zero-latency lookups
        @Volatile var isModuleEnabled: Boolean = true
        @Volatile var isAutoAccept: Boolean = true
        @Volatile var isAutoReject: Boolean = false
        @Volatile var isDryRun: Boolean = false
        @Volatile var minOrderPrice: Double = 0.0
        @Volatile var maxDistToRestaurant: Double = 8.0
        @Volatile var maxDistCustomer: Double = 15.0
        @Volatile var spoofedAndroidId: String = "d41d8cd98f00b204"
        @Volatile var spoofedImei: String = "869402041234567"
        @Volatile var isSoundEnabled: Boolean = true
        @Volatile var isShowToasts: Boolean = true
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "net.jahez.fleets") return

        XposedBridge.log("==========================================")
        XposedBridge.log("SanedAssistant PRO: Hooking net.jahez.fleets")
        XposedBridge.log("==========================================")

        try {
            // 0. Cloak module from package scans (Bypass Harmful Apps Detected)
            PackageCloaker.hook(lpparam)

            // 1. Hook GPS & Location (Bypass 'location unknown' & Inject Fake GPS)
            LocationEngine.hook(lpparam)

            // 2. Hook Device Identity (Spoof Android ID, IMEI, Hardware)
            DeviceSpoofer.hook(lpparam)

            // 3. Hook Orders & Auto-Accept (Instant 0ms parallel accept)
            OrderInterceptor.hook(lpparam)

            XposedBridge.log("SanedAssistant PRO: All hooks active with zero license restrictions!")
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant PRO: Critical hook error: ${t.message}")
        }
    }
}
