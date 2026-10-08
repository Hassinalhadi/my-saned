package dev.saned.assistant

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PackageCloaker {

    private val blacklist = listOf(
        "saned",
        "assistant",
        "lsposed",
        "xposed",
        "magisk",
        "driverservice"
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            // 1. Hook getInstalledPackages to remove blacklisted companion apps
            val getInstalledPackagesHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val filtered = list.filterNot { item ->
                        val pi = item as? PackageInfo ?: return@filterNot false
                        isHarmfulPackage(pi.packageName)
                    }
                    param.result = filtered
                }
            }

            for (m in pmClass.declaredMethods) {
                if (m.name == "getInstalledPackages") {
                    XposedBridge.hookMethod(m, getInstalledPackagesHook)
                }
            }

            // 2. Hook getInstalledApplications
            val getInstalledAppsHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val filtered = list.filterNot { item ->
                        val ai = item as? ApplicationInfo ?: return@filterNot false
                        isHarmfulPackage(ai.packageName)
                    }
                    param.result = filtered
                }
            }

            for (m in pmClass.declaredMethods) {
                if (m.name == "getInstalledApplications") {
                    XposedBridge.hookMethod(m, getInstalledAppsHook)
                }
            }

            // 3. Hook getPackageInfo
            val getPackageInfoHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    if (isHarmfulPackage(pkg)) {
                        param.throwable = PackageManager.NameNotFoundException("Package $pkg not found")
                    }
                }
            }

            for (m in pmClass.declaredMethods) {
                if (m.name == "getPackageInfo") {
                    XposedBridge.hookMethod(m, getPackageInfoHook)
                }
            }

            // 4. Hook getApplicationInfo
            val getAppInfoHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    if (isHarmfulPackage(pkg)) {
                        param.throwable = PackageManager.NameNotFoundException("Application $pkg not found")
                    }
                }
            }

            for (m in pmClass.declaredMethods) {
                if (m.name == "getApplicationInfo") {
                    XposedBridge.hookMethod(m, getAppInfoHook)
                }
            }

            XposedBridge.log("SanedAssistant: PackageCloaker armed - All harmful package detections bypassed!")
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: PackageCloaker error: ${t.message}")
        }
    }

    private fun isHarmfulPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val lower = pkg.lowercase()
        return blacklist.any { lower.contains(it) }
    }
}
