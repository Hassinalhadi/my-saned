package dev.saned.assistant

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object PackageCloaker {

    private val blacklist = listOf(
        "saned",
        "assistant",
        "lsposed",
        "xposed",
        "magisk",
        "driverservice"
    )

    fun hook(classLoader: ClassLoader) {
        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

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

            XposedBridge.log("SanedAssistant: PackageCloaker armed!")
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
