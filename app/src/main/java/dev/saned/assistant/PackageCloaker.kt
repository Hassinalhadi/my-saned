package dev.saned.assistant

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Matches Lvh1, Lu80, and Lb51 in SanedAssistant-98-7bac1ec.apk:
 * - Version check & profile selection (Lvh1.n, Lvh1.l, Lvh1.m)
 * - Runtime.exec & loadLibrary0 blocking (Lvh1.o, Lvh1.j)
 * - Native methods neutralization on obfuscated guard classes (Lvh1.q)
 * - Security activity redirect to MainActivity (Lvh1.e)
 * - ApplicationPackageManager install source & Maps API key spoofing (Lu80.C, Lu80.D, Lb51.b)
 * - SecurityCallBacksState, RootBeer, ShadowArmor & FeatureFlags hooks (Lu80.y, Lu80.G, Lu80.L)
 */
object PackageCloaker {

    data class VersionProfile(
        val index: Int,
        val nativeLibName: String,          // e.g. "jahez_fleets"
        val nativeClasses: List<String>,    // e.g. ["ggk.P", "ggk.ar"]
        val blockedActivity: String,        // e.g. "ggk.am"
        val mainActivity: String,           // e.g. "net.jahez.fleets.MainActivity"
        val loaderClass: String,            // e.g. "ggk.z"
        val loaderMethod: String,           // e.g. "b" or "a"
        val runtimeExecMethod: String       // e.g. "loadLibrary0"
    )

    data class ShadowArmorProfile(
        val rootCheckClass: String,
        val autoClickerClass: String,
        val installSourceClass: String,
        val certPinClass: String,
        val featureFlagsClass: String
    )

    @Volatile var detectedVersionIndex: Int = -1
    @Volatile var activeProfile: VersionProfile? = null
    @Volatile var lastCheckedVersionCode: Long = 0L

    private val isRuntimeHooked = AtomicBoolean(false)
    private val isLoaderHooked = AtomicBoolean(false)

    fun getAllVersionProfiles(): List<VersionProfile> {
        val raw = Native.safeP()
        val result = mutableListOf<VersionProfile>()
        val chunks = raw.toList().chunked(8)
        for ((idx, c) in chunks.withIndex()) {
            if (c.size == 8) {
                val nativeCls = listOf(c[2], c[3]).filter { it.isNotEmpty() }
                result.add(
                    VersionProfile(
                        index = idx,
                        nativeLibName = c[0],
                        nativeClasses = nativeCls,
                        blockedActivity = c[1],
                        mainActivity = c[4],
                        loaderClass = c[5],
                        loaderMethod = c[6],
                        runtimeExecMethod = c[7]
                    )
                )
            }
        }
        return result
    }

    fun getAllShadowArmorProfiles(): List<ShadowArmorProfile> {
        val raw = Native.safeT()
        val result = mutableListOf<ShadowArmorProfile>()
        val chunks = raw.toList().chunked(5)
        for (c in chunks) {
            if (c.size == 5) {
                result.add(
                    ShadowArmorProfile(
                        rootCheckClass = c[0],
                        autoClickerClass = c[1],
                        installSourceClass = c[2],
                        certPinClass = c[3],
                        featureFlagsClass = c[4]
                    )
                )
            }
        }
        return result
    }

    /**
     * Matches Lvh1.n(long versionCode) in SanedAssistant-98-7bac1ec.apk:
     * Checks versionCode via Native.s(versionCode) and sets detectedVersionIndex.
     */
    fun checkVersionSupported(versionCode: Long): Boolean {
        if (versionCode <= 0L) return false
        if (lastCheckedVersionCode == versionCode && detectedVersionIndex >= 0) {
            return true
        }
        lastCheckedVersionCode = versionCode
        val idx = try {
            Native.safeS(versionCode)
        } catch (_: Throwable) {
            -1
        }
        detectedVersionIndex = idx
        return idx >= 0
    }

    /**
     * Matches Lvh1.l(ClassLoader) & Lvh1.m(ClassLoader, Ley1):
     * Resolves the active VersionProfile for the given ClassLoader.
     */
    fun resolveProfile(classLoader: ClassLoader): VersionProfile? {
        activeProfile?.let { return it }
        val profiles = getAllVersionProfiles()
        if (profiles.isEmpty()) return null

        val preferred = profiles.getOrNull(detectedVersionIndex)
        if (preferred != null && isProfilePresent(classLoader, preferred)) {
            activeProfile = preferred
            return preferred
        }
        for (p in profiles) {
            if (isProfilePresent(classLoader, p)) {
                activeProfile = p
                if (detectedVersionIndex < 0) detectedVersionIndex = p.index
                return p
            }
        }
        return profiles.firstOrNull()
    }

    private fun isProfilePresent(classLoader: ClassLoader, profile: VersionProfile): Boolean {
        if (profile.loaderClass.isEmpty()) return false
        return try {
            Class.forName(profile.loaderClass, false, classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getMainActivityClassName(): String {
        val p = activeProfile ?: getAllVersionProfiles().firstOrNull()
        return p?.mainActivity?.takeIf { it.isNotEmpty() } ?: "net.jahez.fleets.MainActivity"
    }

    private fun defaultReturnValueForType(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> true
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> ' '
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        val profiles = getAllVersionProfiles()
        val resolved = resolveProfile(classLoader)

        // 1. Lvh1.o: Hook Runtime.loadLibrary0(ClassLoader, String) / Runtime methods to block "jahez_fleets" native guard lib
        if (isRuntimeHooked.compareAndSet(false, true)) {
            try {
                val targetMethodName = profiles.firstOrNull { it.runtimeExecMethod.isNotEmpty() }?.runtimeExecMethod ?: "loadLibrary0"
                val blockedLibName = profiles.firstOrNull { it.nativeLibName.isNotEmpty() }?.nativeLibName ?: "jahez_fleets"
                for (m in Runtime::class.java.declaredMethods) {
                    if (m.name == targetMethodName && m.parameterTypes.lastOrNull() == String::class.java) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val lastArg = chain.args.lastOrNull() as? String
                                if (lastArg != null && (lastArg == blockedLibName || lastArg.contains(blockedLibName))) {
                                    return null
                                }
                                return chain.proceed()
                            }
                        })
                    }
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

        // 2. Lvh1.q: Hook native methods on obfuscated guard classes (e.g. ggk.P, ggk.ar / oxxqbaxw.e, oxxqbaxw.ao / lrbppeks.b, lrbppeks.ak)
        try {
            val nativeClassNames = resolved?.nativeClasses ?: profiles.flatMap { it.nativeClasses }
            for (clsName in nativeClassNames) {
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    for (m in cls.declaredMethods) {
                        if (Modifier.isNative(m.modifiers)) {
                            val retType = m.returnType
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    return defaultReturnValueForType(retType)
                                }
                            })
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 3. Lvh1.j: Hook obfuscated library loader method (e.g. ggk.z.b(String), oxxqbaxw.b.a(String), lrbppeks.am.a(String))
        if (!isLoaderHooked.get() && resolved != null && resolved.loaderClass.isNotEmpty() && resolved.loaderMethod.isNotEmpty()) {
            try {
                val cls = Class.forName(resolved.loaderClass, false, classLoader)
                val m = cls.getDeclaredMethod(resolved.loaderMethod, String::class.java)
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return null
                    }
                })
                isLoaderHooked.set(true)
            } catch (_: Throwable) {}
        }

        // 4. Lvh1.e: Redirect blocked security Activities (ggk.am, oxxqbaxw.an, lrbppeks.U) onResume -> MainActivity
        try {
            val blockedActivities = profiles.mapNotNull { it.blockedActivity.takeIf { s -> s.isNotEmpty() } }.toSet()
            val mainActName = getMainActivityClassName()
            if (blockedActivities.isNotEmpty() && mainActName.isNotEmpty()) {
                val mResume = Activity::class.java.getDeclaredMethod("onResume")
                module.hook(mResume).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val res = chain.proceed()
                        val act = chain.thisObject as? Activity
                        if (act != null && blockedActivities.contains(act.javaClass.name) && !act.isFinishing) {
                            try {
                                val mainCls = classLoader.loadClass(mainActName)
                                act.startActivity(android.content.Intent(act, mainCls))
                                act.finish()
                            } catch (_: Throwable) {}
                        }
                        return res
                    }
                })
            }
        } catch (_: Throwable) {}

        // 5. Lu80.C & Lu80.D & Lb51.b: ApplicationPackageManager install source & Maps API key hooks
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
                if (m.name == "getApplicationInfo" || m.name == "getPackageInfo") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            patchGoogleMapsApiKeyIfNeeded(res)
                            return res
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 6. Lu80.y: SecurityCallBacksState boolean fields reset to false
        val secClasses = Native.safeN()
        val secStateClsName = secClasses.getOrNull(0) ?: ""
        if (secStateClsName.isNotEmpty()) {
            try {
                val secCls = classLoader.loadClass(secStateClsName)
                for (m in secCls.declaredMethods) {
                    if (Modifier.isStatic(m.modifiers) && m.parameterTypes.firstOrNull() == secCls) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                if (OrderInterceptor.isMasterRunning) {
                                    val stateObj = chain.args.getOrNull(0)
                                    if (stateObj != null) {
                                        for (f in stateObj.javaClass.declaredFields) {
                                            if (f.type == Boolean::class.javaPrimitiveType || f.type == java.lang.Boolean::class.java) {
                                                try {
                                                    f.isAccessible = true
                                                    f.setBoolean(stateObj, false)
                                                } catch (_: Throwable) {}
                                            }
                                        }
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // 7. Lu80.G, Lu80.H, Lu80.I: RootBeer & RootBeerNative hooks
        for (idx in 1..2) {
            val rbClsName = secClasses.getOrNull(idx) ?: continue
            try {
                val cls = classLoader.loadClass(rbClsName)
                for (m in cls.declaredMethods) {
                    if (m.returnType == Boolean::class.javaPrimitiveType && (idx == 2 || m.parameterCount == 0)) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                return if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                            }
                        })
                    } else if (idx == 2 && m.returnType == Int::class.javaPrimitiveType) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                return if (OrderInterceptor.isMasterRunning) 0 else chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // 8. Lu80.L: ShadowArmor RootCheck, AutoClickerCheck, InstallSourceCheck, CertPinCheck, and FeatureFlags
        try {
            val saProfiles = getAllShadowArmorProfiles()
            val saToApply = if (detectedVersionIndex in saProfiles.indices) listOf(saProfiles[detectedVersionIndex]) else saProfiles
            val featureFlags = Native.safeU().toSet()

            for (sa in saToApply) {
                // RootCheck: 0-arg boolean methods -> false
                try {
                    val rootCls = classLoader.loadClass(sa.rootCheckClass)
                    for (m in rootCls.declaredMethods) {
                        if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterCount == 0) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    return if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                                }
                            })
                        }
                    }
                } catch (_: Throwable) {}

                // AutoClickerCheck (false), InstallSourceCheck (true), CertPinCheck (true): (Context) -> Boolean
                for ((clsName, retVal) in listOf(
                    sa.autoClickerClass to false,
                    sa.installSourceClass to true,
                    sa.certPinClass to true
                )) {
                    try {
                        val c = classLoader.loadClass(clsName)
                        val m = c.declaredMethods.firstOrNull {
                            it.returnType == Boolean::class.javaPrimitiveType &&
                                it.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java))
                        }
                        if (m != null) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    return if (OrderInterceptor.isMasterRunning) retVal else chain.proceed()
                                }
                            })
                        }
                    } catch (_: Throwable) {}
                }

                // FeatureFlags: (String) or (String, boolean) -> Boolean returns false for rooted_device_check, auto_clicker_check, installation_source_check
                try {
                    val ffCls = classLoader.loadClass(sa.featureFlagsClass)
                    for (m in ffCls.declaredMethods) {
                        val pTypes = m.parameterTypes
                        if (m.returnType == Boolean::class.javaPrimitiveType &&
                            pTypes.isNotEmpty() && pTypes[0] == String::class.java &&
                            (pTypes.size == 1 || (pTypes.size == 2 && pTypes[1] == Boolean::class.javaPrimitiveType))) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val key = chain.args.getOrNull(0) as? String
                                    if (OrderInterceptor.isMasterRunning && key != null && featureFlags.contains(key)) {
                                        return false
                                    }
                                    return chain.proceed()
                                }
                            })
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun patchGoogleMapsApiKeyIfNeeded(infoObj: Any?) {
        try {
            val appInfo: ApplicationInfo = when (infoObj) {
                is ApplicationInfo -> infoObj
                is PackageInfo -> infoObj.applicationInfo ?: return
                else -> return
            }
            if (appInfo.packageName != "net.jahez.fleets") return
            val metaData: Bundle = appInfo.metaData ?: return
            if (!metaData.containsKey("com.google.android.geo.API_KEY")) return
            val replacementKey = Native.safeQ()
            if (replacementKey.isNotEmpty()) {
                metaData.putString("com.google.android.geo.API_KEY", replacementKey)
            }
        } catch (_: Throwable) {}
    }
}
