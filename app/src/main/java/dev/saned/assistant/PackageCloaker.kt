package dev.saned.assistant

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object PackageCloaker {

    private val BLACKLIST = setOf(
        "org.lsposed.manager",
        "io.github.lsposed",
        "com.topjohnwu.magisk",
        "de.robv.android.xposed.installer"
    )

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            for (m in PackageManager::class.java.declaredMethods) {
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
            }
        } catch (_: Throwable) {}
    }
}
