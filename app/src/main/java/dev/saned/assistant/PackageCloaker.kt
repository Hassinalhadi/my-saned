package dev.saned.assistant

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.IOException

object PackageCloaker {

    private val BLACKLIST = setOf(
        "org.lsposed.manager",
        "io.github.lsposed",
        "com.topjohnwu.magisk",
        "de.robv.android.xposed.installer",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.lexa.fakegps",
        "com.incorporateapps.fakegps",
        "com.incorporateapps.fakegps_route",
        "com.rosteam.gpsemulator",
        "com.gsmartstudio.fakegps",
        "com.location.gps.fakelocation",
        "com.fakegps.mock",
        "com.mockloc",
        "com.app.fakegps"
    )

    private val ROOT_PATHS = setOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/system/app/Superuser.apk",
        "/system/app/Magisk.apk"
    )

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook the real runtime implementation: android.app.ApplicationPackageManager
        try {
            val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader)

            for (m in appPkgMgrClass.declaredMethods) {
                // Hook getInstalledPackages
                if (m.name == "getInstalledPackages") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val list = chain.proceed() as? List<*> ?: return chain.proceed()
                            return list.filter {
                                val pkgName = (it as? PackageInfo)?.packageName ?: ""
                                !BLACKLIST.contains(pkgName)
                            }
                        }
                    })
                }

                // Hook getInstalledApplications
                if (m.name == "getInstalledApplications") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val list = chain.proceed() as? List<*> ?: return chain.proceed()
                            return list.filter {
                                val pkgName = (it as? ApplicationInfo)?.packageName ?: ""
                                !BLACKLIST.contains(pkgName)
                            }
                        }
                    })
                }

                // Hook getPackageInfo(String, ...)
                if (m.name == "getPackageInfo" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val pkg = chain.args.getOrNull(0) as? String
                            if (pkg != null && BLACKLIST.contains(pkg)) {
                                throw PackageManager.NameNotFoundException(pkg)
                            }
                            return chain.proceed()
                        }
                    })
                }

                // Hook getApplicationInfo(String, ...)
                if (m.name == "getApplicationInfo" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val pkg = chain.args.getOrNull(0) as? String
                            if (pkg != null && BLACKLIST.contains(pkg)) {
                                throw PackageManager.NameNotFoundException(pkg)
                            }
                            return chain.proceed()
                        }
                    })
                }

                // Hook getInstallerPackageName(String) -> Always return Google Play Store
                if (m.name == "getInstallerPackageName") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val pkg = chain.args.getOrNull(0) as? String
                            if (pkg == "net.jahez.fleets" || pkg?.contains("jahez") == true) {
                                return "com.android.vending"
                            }
                            return chain.proceed()
                        }
                    })
                }

                // Hook getInstallSourceInfo(String) on Android 11+
                if (m.name == "getInstallSourceInfo") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            if (res != null) {
                                try {
                                    val fInit = res.javaClass.getDeclaredField("mInitiatingPackageName").apply { isAccessible = true }
                                    fInit.set(res, "com.android.vending")
                                    val fInst = res.javaClass.getDeclaredField("mInstallingPackageName").apply { isAccessible = true }
                                    fInst.set(res, "com.android.vending")
                                } catch (_: Throwable) {}
                            }
                            return res
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 2. Anti-Root: Hook Runtime.getRuntime().exec
        try {
            for (m in Runtime::class.java.declaredMethods) {
                if (m.name == "exec") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val cmd = when (val first = chain.args.getOrNull(0)) {
                                is String -> first
                                is Array<*> -> first.joinToString(" ") { it?.toString() ?: "" }
                                else -> ""
                            }
                            if (cmd.contains("su") || cmd.contains("which") || cmd.contains("magisk") || cmd.contains("busybox")) {
                                throw IOException("No such file or directory: $cmd")
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 3. Anti-Root: Hook File.exists()
        try {
            val mExists = File::class.java.getDeclaredMethod("exists")
            module.hook(mExists).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val f = chain.thisObject as? File
                    if (f != null && ROOT_PATHS.contains(f.absolutePath)) {
                        return false
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }
}
