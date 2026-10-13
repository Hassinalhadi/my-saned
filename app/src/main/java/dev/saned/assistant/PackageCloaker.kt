package dev.saned.assistant

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.IOException

/**
 * Matches Lu80 and Lvh1.o in SanedAssistant-98-7bac1ec.apk:
 * - Hooks ApplicationPackageManager.getInstallerPackageName -> "com.android.vending" (Lu80.C)
 * - Hooks ApplicationPackageManager.getInstallSourceInfo -> sets mOriginatingPackageName,
 *   mInstallingPackageName, mInitiatingPackageName to "com.android.vending" (Lu80.D)
 * - Hooks RootBeer & RootBeerNative if loaded (Lu80.G / H / I)
 * - Hooks Runtime.exec for su/which checks (Lvh1.o)
 */
object PackageCloaker {

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. ApplicationPackageManager install source hooks (matching Lu80.C & Lu80.D)
        try {
            val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader)

            for (m in appPkgMgrClass.declaredMethods) {
                if (m.name == "getInstallerPackageName") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (OrderInterceptor.isMasterRunning) {
                                return "com.android.vending"
                            }
                            return chain.proceed()
                        }
                    })
                }

                if (m.name == "getInstallSourceInfo") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            if (res != null && OrderInterceptor.isMasterRunning) {
                                for (fieldName in listOf("mOriginatingPackageName", "mInstallingPackageName", "mInitiatingPackageName")) {
                                    try {
                                        val f = res.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                                        f.set(res, "com.android.vending")
                                    } catch (_: Throwable) {}
                                }
                            }
                            return res
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 2. RootBeer & RootBeerNative hooks (matching Lu80.G, Lu80.H, Lu80.I)
        val rootBeerClasses = listOf(
            "com.scottyab.rootbeer.RootBeer",
            "com.scottyab.rootbeer.util.QLog"
        )
        for (clsName in rootBeerClasses) {
            try {
                val cls = Class.forName(clsName, false, classLoader)
                for (m in cls.declaredMethods) {
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterCount == 0) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                return if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. Runtime.exec root check filter (matching Lvh1.o)
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
                                throw IOException("No such file or directory")
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }
}
