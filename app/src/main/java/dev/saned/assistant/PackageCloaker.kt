package dev.saned.assistant

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.BaseBundle
import android.os.Build
import android.os.Bundle
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object PackageCloaker {

    data class VersionProfile(
        val index: Int,
        val nativeLibName: String,
        val nativeClasses: List<String>,
        val blockedActivity: String,
        val mainActivity: String,
        val loaderClass: String,
        val loaderMethod: String,
        val runtimeExecMethod: String
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
    @Volatile private var mapsCertCheckResult: Int = 0 // 0=unchecked, 1=official Play Store cert, 2=re-signed/non-root patched

    private val HIDDEN_PATCH_PACKAGES = setOf(
        "top.nkbe.npatch",
        "org.lsposed.lspatch",
        "org.lsposed.manager",
        "dev.jing.sanedhook"
    )

    private val isRuntimeHooked = AtomicBoolean(false)
    private val isNativeStubbed = AtomicBoolean(false)
    private val stubbedNativeClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val isLoaderHooked = AtomicBoolean(false)
    private val isBlockedActHooked = AtomicBoolean(false)
    private val isSecurityHooked = AtomicBoolean(false)
    private val isMapsAndPkgHooked = AtomicBoolean(false)
    private val isBundleMapsKeyHooked = AtomicBoolean(false)
    private val isFvfeiucoiGuardHooked = AtomicBoolean(false)

    fun isNativeStubbedFlag(): Boolean = isNativeStubbed.get()

    fun isNonRootFramework(module: XposedModule?): Boolean {
        val fw = try { module?.frameworkName ?: "" } catch (_: Throwable) { "" }
        if (fw.contains("NPatch", ignoreCase = true) ||
            fw.contains("Vector", ignoreCase = true) ||
            fw.contains("LSPatch", ignoreCase = true)
        ) {
            return true
        }
        return mapsCertCheckResult == 2
    }

    fun shouldReplaceMapsApiKey(module: XposedModule? = DiagnosticLogger.xposedModule): Boolean {
        if (mapsCertCheckResult == 2) return true
        if (mapsCertCheckResult == 1) return false
        if (isNonRootFramework(module)) {
            mapsCertCheckResult = 2
            return true
        }
        try {
            val ctx = OrderInterceptor.resolveApplicationContext() ?: OrderInterceptor.currentActivity
            if (ctx != null) {
                val srcDir = ctx.applicationInfo?.sourceDir
                if (!srcDir.isNullOrEmpty()) {
                    val isOfficial = Native.safeR(srcDir)
                    if (!isOfficial) {
                        mapsCertCheckResult = 2
                        return true
                    }
                }
                @Suppress("DEPRECATION")
                val pInfo = ctx.packageManager.getPackageInfo("net.jahez.fleets", PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val sigs = pInfo?.signatures
                if (sigs != null && sigs.isNotEmpty() && sigs[0] != null) {
                    val sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(sigs[0].toByteArray())
                        .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
                    val isOfficialCert = sha256.equals(Native.OFFICIAL_JAHEZ_CERT_SHA256, ignoreCase = true)
                    mapsCertCheckResult = if (isOfficialCert) 1 else 2
                    return !isOfficialCert
                }
            }
        } catch (_: Throwable) {}
        // Default to true until verified official so early MapsInitializer calls on re-signed APKs never fail
        return true
    }

    fun getAllVersionProfiles(): List<VersionProfile> {
        val raw = Native.safeP()
        val result = mutableListOf<VersionProfile>()
        for ((idx, c) in raw.toList().chunked(8).withIndex()) {
            if (c.size == 8) {
                result.add(VersionProfile(idx, c[0], listOf(c[1], c[2]).filter { it.isNotEmpty() }, c[3], c[4], c[5], c[6], c[7]))
            }
        }
        return result
    }

    fun getAllShadowArmorProfiles(): List<ShadowArmorProfile> {
        val raw = Native.safeT()
        val result = mutableListOf<ShadowArmorProfile>()
        for (c in raw.toList().chunked(5)) {
            if (c.size == 5) result.add(ShadowArmorProfile(c[0], c[1], c[2], c[3], c[4]))
        }
        return result
    }

    fun checkVersionSupported(versionCode: Long): Boolean {
        if (versionCode <= 0L) return true
        if (lastCheckedVersionCode == versionCode && detectedVersionIndex >= 0) return true
        lastCheckedVersionCode = versionCode
        detectedVersionIndex = try { Native.safeS(versionCode) } catch (_: Throwable) { -1 }
        return detectedVersionIndex >= 0
    }

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
        return null
    }

    private fun isProfilePresent(classLoader: ClassLoader, profile: VersionProfile): Boolean =
        profile.loaderClass.isNotEmpty() && try { Class.forName(profile.loaderClass, false, classLoader); true } catch (_: Throwable) { false }

    fun getMainActivityClassName(): String =
        (activeProfile ?: getAllVersionProfiles().firstOrNull())?.mainActivity?.takeIf { it.isNotEmpty() } ?: "net.jahez.fleets.MainActivity"

    private fun defaultReturnValueForType(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> java.lang.Boolean.TRUE
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> ' '
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    /**
     * Installs Universal Google Maps API Key hooks & Package Manager cloaking at the earliest stage (hookEarly):
     * 1. Hooks ApplicationPackageManager.getApplicationInfo, getApplicationInfoAsUser, getPackageInfo, and getPackageInfoAsUser
     *    (fixes ColorOS/Realme/OxygenOS/HyperOS/OneUI Android 13-16 where getApplicationInfo is AOT-inlined to getApplicationInfoAsUser!).
     * 2. Hooks BaseBundle.getString / Bundle.getString for "com.google.android.geo.API_KEY" so Google Maps Dynamite module
     *    always receives the unrestricted Maps API Key even if PackageManager calls were AOT-inlined in boot.oat!
     */
    private fun installMapsAndPackageCloakerHooks(module: XposedModule) {
        if (isMapsAndPkgHooked.compareAndSet(false, true)) {
            try {
                val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")
                val targetNames = setOf(
                    "getApplicationInfo",
                    "getApplicationInfoAsUser",
                    "getPackageInfo",
                    "getPackageInfoAsUser"
                )
                for (m in appPkgMgrClass.declaredMethods) {
                    if (m.name in targetNames) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val pkgArg = chain.args.firstOrNull() as? String
                                if (OrderInterceptor.isMasterRunning && pkgArg != null && HIDDEN_PATCH_PACKAGES.contains(pkgArg)) {
                                    throw PackageManager.NameNotFoundException(pkgArg)
                                }
                                val res = chain.proceed()
                                patchGoogleMapsApiKeyIfNeeded(res, module)
                                return res
                            }
                        })
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "Hooked ApplicationPackageManager (getApplicationInfo/AsUser + getPackageInfo/AsUser)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:MapsPkg", "Error hooking ApplicationPackageManager", t)
            }
        }

        if (isBundleMapsKeyHooked.compareAndSet(false, true)) {
            for (bundleCls in listOf(BaseBundle::class.java, Bundle::class.java)) {
                try {
                    for (m in bundleCls.declaredMethods) {
                        if (m.name == "getString" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val key = chain.args.firstOrNull() as? String
                                    if (key == "com.google.android.geo.API_KEY") {
                                        if (shouldReplaceMapsApiKey(module)) {
                                            val replacementKey = Native.safeQ()
                                            if (replacementKey.isNotEmpty()) {
                                                return replacementKey
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
            DiagnosticLogger.logStep("PackageCloaker", "Hooked BaseBundle/Bundle.getString for com.google.android.geo.API_KEY")
        }
    }

    /**
     * Matches Lvh1.o, Lvh1.q, Lvh1.j, Lvh1.e in SanedAssistant-98-7bac1ec.apk.
     * Note: Stubs JNI native methods FIRST, and only suppresses Runtime.loadLibrary0("jahez_fleets")
     * when isNativeStubbed is true, preventing UnsatisfiedLinkError if Jahez is updated to a new obfuscated build (such as 40104).
     */
    fun hookEarly(module: XposedModule, classLoader: ClassLoader) {
        // 0. Install Universal Google Maps API Key & PackageManager hooks immediately in hookEarly!
        installMapsAndPackageCloakerHooks(module)

        // 0B. On Saned 6.2.0 (40104), allow libjahez_fleets.so to load normally (required for 40104 on both LSPosed & NPatch)
        // and ONLY catch the NPatch signature-check exception `fvfeiucoi.r: 01` thrown by `fvfeiucoi.I.a` / `fvfeiucoi.I.b`.
        if (isFvfeiucoiGuardHooked.compareAndSet(false, true)) {
            try {
                val clsI = Class.forName("fvfeiucoi.I", false, classLoader)
                for (m in clsI.declaredMethods) {
                    if (m.name == "a" || m.name == "b") {
                        val retType = m.returnType
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                return try {
                                    chain.proceed()
                                } catch (t: Throwable) {
                                    val inner = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                                    if (inner.javaClass.name == "fvfeiucoi.r") {
                                        DiagnosticLogger.logStep("PackageCloaker", "Suppressed fvfeiucoi.r (${inner.message}) in fvfeiucoi.I.${m.name}")
                                        defaultReturnValueForType(retType)
                                    } else {
                                        throw inner
                                    }
                                }
                            }
                        })
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "Installed pass-through fvfeiucoi.r guard on fvfeiucoi.I for 40104")
            } catch (_: Throwable) {}
        }

        val profiles = getAllVersionProfiles()
        val resolved = resolveProfile(classLoader)

        // 1. Lvh1.q: Stub native methods on resolved profile's nativeClasses FIRST (c[1] and c[2], e.g. oxxqbaxw.an and oxxqbaxw.e)
        try {
            val nativeClassNames = resolved?.nativeClasses ?: profiles.flatMap { it.nativeClasses }
            var newlyHookedCount = 0
            for (clsName in nativeClassNames) {
                if (stubbedNativeClasses.contains(clsName)) continue
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    var clsHooked = 0
                    for (m in cls.declaredMethods) {
                        if (Modifier.isNative(m.modifiers)) {
                            val retType = m.returnType
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? = defaultReturnValueForType(retType)
                            })
                            clsHooked++
                            newlyHookedCount++
                        }
                    }
                    if (clsHooked > 0) {
                        stubbedNativeClasses.add(clsName)
                    }
                } catch (_: Throwable) {}
            }
            val allRequiredStubbed = if (resolved != null && resolved.nativeClasses.isNotEmpty()) {
                stubbedNativeClasses.containsAll(resolved.nativeClasses)
            } else {
                stubbedNativeClasses.isNotEmpty()
            }
            if (allRequiredStubbed) {
                isNativeStubbed.set(true)
            }
            if (newlyHookedCount > 0) {
                DiagnosticLogger.logStep("PackageCloaker", "Stubbed $newlyHookedCount JNI methods on $stubbedNativeClasses (allReady=$allRequiredStubbed)")
            } else if (!isNativeStubbed.get()) {
                DiagnosticLogger.logWarn(
                    "PackageCloaker",
                    "No matching native classes found for vCode=$lastCheckedVersionCode (expected=${resolved?.nativeClasses ?: nativeClassNames})."
                )
            }
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:NativeStub", "Error stubbing native classes", t)
        }

        // 2. Lvh1.j: Hook loaderClass.loaderMethod(String)
        if (!isLoaderHooked.get() && resolved != null && resolved.loaderClass.isNotEmpty() && resolved.loaderMethod.isNotEmpty()) {
            try {
                val cls = Class.forName(resolved.loaderClass, false, classLoader)
                val m = cls.getDeclaredMethod(resolved.loaderMethod, String::class.java)
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = null
                })
                isLoaderHooked.set(true)
                DiagnosticLogger.logStep("PackageCloaker", "Hooked loader ${resolved.loaderClass}.${resolved.loaderMethod}(String)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:Loader", "Error hooking loader ${resolved.loaderClass}.${resolved.loaderMethod}", t)
            }
        }

        // 3. Lvh1.o: Hook Runtime.loadLibrary0 (only suppress blockedLibName if its JNI methods were stubbed!)
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
                                    if (isNativeStubbed.get()) {
                                        DiagnosticLogger.logStep("Runtime.loadLibrary0", "Blocked loading native lib '$lastArg' (JNI methods stubbed)")
                                        return null
                                    } else {
                                        DiagnosticLogger.logWarn(
                                            "Runtime.loadLibrary0",
                                            "Allowing '$lastArg' to load because JNI methods were NOT stubbed for vCode=$lastCheckedVersionCode (prevents UnsatisfiedLinkError)"
                                        )
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                    }
                }
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:Runtime", "Error hooking Runtime.loadLibrary0", t)
            }
        }

        // 4. Lvh1.e: Hook Activity.onResume for blockedActivities redirect
        if (isBlockedActHooked.compareAndSet(false, true)) {
            try {
                val blockedActivities = profiles.mapNotNull { it.blockedActivity.takeIf { s -> s.isNotEmpty() } }.toSet()
                val mainActName = getMainActivityClassName()
                if (blockedActivities.isNotEmpty() && mainActName.isNotEmpty()) {
                    val mResume = Activity::class.java.getDeclaredMethod("onResume")
                    module.hook(mResume).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            val act = chain.thisObject as? Activity
                            if (act != null) {
                                val actClsName = act.javaClass.name
                                DiagnosticLogger.logStep("Activity.onResume", "Resumed: $actClsName")
                                if (blockedActivities.contains(actClsName) && !act.isFinishing) {
                                    try {
                                        DiagnosticLogger.logWarn("BlockedActivity", "Intercepted blocked activity $actClsName -> redirecting to $mainActName")
                                        val mainCls = classLoader.loadClass(mainActName)
                                        act.startActivity(android.content.Intent(act, mainCls))
                                        act.finish()
                                    } catch (t: Throwable) {
                                        DiagnosticLogger.logError("BlockedActivity", "Failed redirecting $actClsName", t)
                                    }
                                }
                            }
                            return res
                        }
                    })
                }
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:BlockedAct", "Error hooking Activity.onResume", t)
            }
        }
    }

    /**
     * Matches Lu80.p and Lb51.b in SanedAssistant-98-7bac1ec.apk (called in onPackageReady / initAppHooks),
     * extended to support both Root (LSPosed) and Non-Root (NPatch v1.0.9 / Vector / LSPatch).
     */
    fun hook(module: XposedModule, classLoader: ClassLoader) {
        installMapsAndPackageCloakerHooks(module)
        if (DiagnosticLogger.hookEarlyCloaker) {
            hookEarly(module, classLoader)
        }
        if (!isSecurityHooked.compareAndSet(false, true)) return

        // 1. Lu80.C: ApplicationPackageManager.getInstallerPackageName & getInstallSourceInfo
        try {
            val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")
            try {
                val mInstaller = appPkgMgrClass.getDeclaredMethod("getInstallerPackageName", String::class.java)
                module.hook(mInstaller).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        if (OrderInterceptor.isMasterRunning) "com.android.vending" else chain.proceed()
                })
            } catch (_: Throwable) {}
            try {
                val mSourceInfo = appPkgMgrClass.getDeclaredMethod("getInstallSourceInfo", String::class.java)
                module.hook(mSourceInfo).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val res = chain.proceed()
                        if (res != null && OrderInterceptor.isMasterRunning) {
                            for (fieldName in listOf("mOriginatingPackageName", "mInstallingPackageName", "mInitiatingPackageName")) {
                                try { res.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.set(res, "com.android.vending") } catch (_: Throwable) {}
                            }
                        }
                        return res
                    }
                })
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:Installer", "Error hooking ApplicationPackageManager", t)
        }

        // 2. Lu80.y: SecurityCallBacksState
        val secClasses = Native.safeN()
        val secStateClsName = secClasses.getOrNull(0) ?: ""
        if (secStateClsName.isNotEmpty()) {
            try {
                val secCls = classLoader.loadClass(secStateClsName)
                var secHooked = 0
                for (m in secCls.declaredMethods) {
                    if (Modifier.isStatic(m.modifiers) && m.parameterTypes.firstOrNull() == secCls) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                if (OrderInterceptor.isMasterRunning) {
                                    val stateObj = chain.args.getOrNull(0)
                                    if (stateObj != null) {
                                        for (f in stateObj.javaClass.declaredFields) {
                                            if (f.type == Boolean::class.javaPrimitiveType || f.type == java.lang.Boolean::class.java) {
                                                try { f.isAccessible = true; f.setBoolean(stateObj, false) } catch (_: Throwable) {}
                                            }
                                        }
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                        secHooked++
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "SecurityCallBacksState hooked ($secHooked methods)")
            } catch (_: Throwable) {}
        }

        // 3. Lu80.G: RootBeer & RootBeerNative
        var rbHooked = 0
        for (idx in 1..2) {
            val rbClsName = secClasses.getOrNull(idx) ?: continue
            try {
                val cls = classLoader.loadClass(rbClsName)
                for (m in cls.declaredMethods) {
                    if (m.returnType == Boolean::class.javaPrimitiveType && (idx == 2 || m.parameterCount == 0)) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                        })
                        rbHooked++
                    } else if (idx == 2 && m.returnType == Int::class.javaPrimitiveType) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) 0 else chain.proceed()
                        })
                        rbHooked++
                    }
                }
            } catch (_: Throwable) {}
        }
        DiagnosticLogger.logStep("PackageCloaker", "RootBeer/RootBeerNative hooked ($rbHooked methods)")

        // 4. Lu80.L: ShadowArmor profiles (only for detectedVersionIndex if >= 0, matching Lu80.L!)
        try {
            val saProfiles = getAllShadowArmorProfiles()
            val sa = saProfiles.getOrNull(detectedVersionIndex)
            val featureFlags = Native.safeU().toList()
            if (sa != null) {
                var saCount = 0
                try {
                    val rootCls = classLoader.loadClass(sa.rootCheckClass)
                    for (m in rootCls.declaredMethods) {
                        if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterCount == 0) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                            })
                            saCount++
                        }
                    }
                } catch (_: Throwable) {}
                for ((clsName, retVal) in listOf(sa.autoClickerClass to false, sa.installSourceClass to true, sa.certPinClass to true)) {
                    try {
                        val c = classLoader.loadClass(clsName)
                        for (m in c.declaredMethods) {
                            if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java))) {
                                module.hook(m).intercept(object : XposedInterface.Hooker {
                                    override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) retVal else chain.proceed()
                                })
                                saCount++
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (featureFlags.isNotEmpty()) {
                    try {
                        val ffCls = classLoader.loadClass(sa.featureFlagsClass)
                        for (m in ffCls.declaredMethods) {
                            val pTypes = m.parameterTypes
                            if (m.returnType == Boolean::class.javaPrimitiveType && pTypes.isNotEmpty() && pTypes[0] == String::class.java) {
                                module.hook(m).intercept(object : XposedInterface.Hooker {
                                    override fun intercept(chain: XposedInterface.Chain): Any? {
                                        val key = chain.args.getOrNull(0) as? String
                                        if (OrderInterceptor.isMasterRunning && key != null && featureFlags.contains(key)) return false
                                        return chain.proceed()
                                    }
                                })
                                saCount++
                            }
                        }
                    } catch (_: Throwable) {}
                }
                DiagnosticLogger.logStep("PackageCloaker", "ShadowArmor profile[$detectedVersionIndex] hooked ($saCount methods)")
            }
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:ShadowArmor", "Error hooking ShadowArmor", t)
        }
    }

    fun patchGoogleMapsApiKeyIfNeeded(infoObj: Any?, module: XposedModule? = null) {
        try {
            val appInfo: ApplicationInfo = when (infoObj) {
                is ApplicationInfo -> infoObj
                is PackageInfo -> {
                    // Check PackageInfo signatures if present to accurately classify official vs re-signed APK
                    if (infoObj.packageName == "net.jahez.fleets" && mapsCertCheckResult == 0) {
                        try {
                            @Suppress("DEPRECATION")
                            val sigs = infoObj.signatures
                            if (sigs != null && sigs.isNotEmpty() && sigs[0] != null) {
                                val sha256 = MessageDigest.getInstance("SHA-256")
                                    .digest(sigs[0].toByteArray())
                                    .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
                                val isOfficialCert = sha256.equals(Native.OFFICIAL_JAHEZ_CERT_SHA256, ignoreCase = true)
                                mapsCertCheckResult = if (isOfficialCert && !isNonRootFramework(module)) 1 else 2
                            }
                        } catch (_: Throwable) {}
                    }
                    infoObj.applicationInfo ?: return
                }
                else -> return
            }
            if (appInfo.packageName != "net.jahez.fleets") return

            if (mapsCertCheckResult == 0) {
                val srcDir = appInfo.sourceDir
                if (!srcDir.isNullOrEmpty()) {
                    val isOfficial = try { Native.safeR(srcDir) } catch (_: Throwable) { false }
                    mapsCertCheckResult = if (isOfficial && !isNonRootFramework(module)) 1 else 2
                } else if (isNonRootFramework(module)) {
                    mapsCertCheckResult = 2
                }
            }
            if (!shouldReplaceMapsApiKey(module)) return
            val metaData: Bundle = appInfo.metaData ?: return
            val replacementKey = Native.safeQ()
            if (replacementKey.isNotEmpty()) {
                metaData.putString("com.google.android.geo.API_KEY", replacementKey)
            }
        } catch (_: Throwable) {}
    }
}
