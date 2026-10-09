package dev.saned.assistant

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import de.robv.android.xposed.XposedBridge

class HookEntry : XposedModule() {

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

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val targetPkg = param.packageName
        if (targetPkg != "net.jahez.fleets") return

        XposedBridge.log("==========================================")
        XposedBridge.log("SanedAssistant PRO: Hooking net.jahez.fleets (Modern LibXposed)")
        XposedBridge.log("==========================================")

        val classLoader = param.defaultClassLoader ?: param.classLoader

        try {
            // 0. Cloak module from package scans
            PackageCloaker.hook(classLoader)

            // 1. Hook GPS & Location
            LocationEngine.hook(classLoader)

            // 2. Hook Device Identity (Android ID, IMEI, Hardware, TextView)
            DeviceSpoofer.hook(classLoader)

            // 3. Hook Orders & Auto-Accept
            OrderInterceptor.hook(classLoader)

            XposedBridge.log("SanedAssistant PRO: All hooks active via LibXposed!")
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant PRO: Hook error: ${t.message}")
        }
    }
}
