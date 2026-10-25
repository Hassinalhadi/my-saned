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
    private val scannedPromonPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dynamicBlockedActivities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hookedExceptionGuardMethods = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastKnownClassLoader: ClassLoader? = null
    private val isLoaderHooked = AtomicBoolean(false)
    private val isBlockedActHooked = AtomicBoolean(false)
    private val isSecurityHooked = AtomicBoolean(false)
    private val isMapsAndPkgHooked = AtomicBoolean(false)
    private val isBundleMapsKeyHooked = AtomicBoolean(false)

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
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> java.lang.Boolean.TRUE
        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
        Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
        Char::class.javaPrimitiveType, java.lang.Character::class.java -> ' '
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
        String::class.java -> ""
        ByteArray::class.java -> ByteArray(0)
        IntArray::class.java -> IntArray(0)
        BooleanArray::class.java -> BooleanArray(0)
        else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType ?: Any::class.java, 0) else null
    }

    private fun isSystemOrFrameworkPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return pkg.startsWith("java.") ||
            pkg.startsWith("javax.") ||
            pkg.startsWith("android.") ||
            pkg.startsWith("androidx.") ||
            pkg.startsWith("kotlin") ||
            pkg.startsWith("kotlinx.") ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("dalvik.") ||
            pkg.startsWith("org.matrix.") ||
            pkg.startsWith("top.nkbe.") ||
            pkg.startsWith("org.lsposed.") ||
            pkg.startsWith("io.github.libxposed") ||
            pkg.startsWith("dev.saned.") ||
            pkg.startsWith("dev.jing.") ||
            pkg.startsWith("net.jahez.") ||
            pkg.startsWith("net.saned.") ||
            pkg == "o"
    }

    /**
     * Dynamically scans an obfuscated Promon Shield package (such as `fvfeiucoi` in Saned 6.2.0 / 40104,
     * `oxxqbaxw` in 40099, `ggk` in 40093, `lrbppeks` in 40089, or any future obfuscated package)
     * WITHOUT initializing classes (`Class.forName(name, false, classLoader)`):
     * 1. Stubs all `native` JNI methods on every class in the package so blocking `libjahez_fleets.so` never causes `UnsatisfiedLinkError`.
     * 2. Hooks loader methods (e.g. `fvfeiucoi.ag.b(String)`) to prevent `System.loadLibrary("jahez_fleets")`.
     * 3. Wraps Promon Shield tamper-check methods (such as `fvfeiucoi.I.a` / `fvfeiucoi.I.b`) to suppress `fvfeiucoi.r` exceptions.
     * 4. Detects any `Activity` subclass in the package and registers it in `dynamicBlockedActivities`.
     */
    private fun scanAndStubPromonPackage(module: XposedModule, classLoader: ClassLoader, pkgPrefix: String) {
        if (isSystemOrFrameworkPackage(pkgPrefix)) return
        if (!scannedPromonPackages.add(pkgPrefix)) return

        val shortNames = ArrayList<String>(160)
        for (c in 'a'..'z') shortNames.add(c.toString())
        for (c in 'A'..'Z') shortNames.add(c.toString())
        for (c1 in listOf('a', 'b', 'c')) {
            for (c2 in 'a'..'z') shortNames.add("$c1$c2")
            for (c2 in 'A'..'Z') shortNames.add("$c1$c2")
        }

        var totalNativeStubbed = 0
        for (shortName in shortNames) {
            val clsName = "$pkgPrefix.$shortName"
            val cls = try {
                Class.forName(clsName, false, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue

            try {
                if (Activity::class.java.isAssignableFrom(cls)) {
                    dynamicBlockedActivities.add(clsName)
                }
            } catch (_: Throwable) {}

            try {
                var clsNativeCount = 0
                for (m in cls.declaredMethods) {
                    val mod = m.modifiers
                    if (Modifier.isNative(mod)) {
                        if (!stubbedNativeClasses.contains("$clsName#${m.name}/${m.parameterTypes.size}")) {
                            val retType = m.returnType
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? = defaultReturnValueForType(retType)
                            })
                            stubbedNativeClasses.add("$clsName#${m.name}/${m.parameterTypes.size}")
                            clsNativeCount++
                            totalNativeStubbed++
                        }
                    } else if (shortName == "I" || shortName == "ag" || m.exceptionTypes.any { it.name.startsWith("$pkgPrefix.") }) {
                        val mKey = "$clsName#${m.name}/${m.parameterTypes.size}"
                        if (hookedExceptionGuardMethods.add(mKey)) {
                            val isStringLoader = (shortName == "ag") &&
                                m.parameterTypes.size == 1 &&
                                m.parameterTypes[0] == String::class.java &&
                                (m.returnType == Void.TYPE || m.returnType == Boolean::class.javaPrimitiveType)
                            val retType = m.returnType
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    if (isStringLoader) {
                                        val arg0 = chain.args.firstOrNull() as? String
                                        if (arg0 == null || arg0.contains("jahez_fleets") || arg0.isNotEmpty()) {
                                            return defaultReturnValueForType(retType)
                                        }
                                    }
                                    return try {
                                        chain.proceed()
                                    } catch (t: Throwable) {
                                        val inner = if (t is java.lang.reflect.InvocationTargetException) (t.targetException ?: t) else t
                                        if (inner.javaClass.name.startsWith("$pkgPrefix.")) {
                                            DiagnosticLogger.logStep("PackageCloaker", "Suppressed Promon tamper exception ${inner.javaClass.name}: ${inner.message}")
                                            defaultReturnValueForType(retType)
                                        } else {
                                            throw inner
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
                if (clsNativeCount > 0) {
                    stubbedNativeClasses.add(clsName)
                }
            } catch (_: Throwable) {}
        }

        if (totalNativeStubbed > 0) {
            isNativeStubbed.set(true)
        }
        DiagnosticLogger.logStep(
            "PackageCloaker",
            "Scanned Promon package '$pkgPrefix': stubbed $totalNativeStubbed JNI methods, blockedActivities=$dynamicBlockedActivities"
        )
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
     * when isNativeStubbed is true, preventing UnsatisfiedLinkError if Jahez is updated to a new obfuscated build.
     */
    fun hookEarly(module: XposedModule, classLoader: ClassLoader) {
        lastKnownClassLoader = classLoader

        // 0. Install Universal Google Maps API Key & PackageManager hooks immediately in hookEarly!
        installMapsAndPackageCloakerHooks(module)

        val profiles = getAllVersionProfiles()
        val resolved = resolveProfile(classLoader)

        // 1. Hook Runtime.loadLibrary0 / load0 FIRST so any attempt to load libjahez_fleets.so is intercepted!
        if (isRuntimeHooked.compareAndSet(false, true)) {
            try {
                val blockedLibName = profiles.firstOrNull { it.nativeLibName.isNotEmpty() }?.nativeLibName ?: "jahez_fleets"
                val runtimeHookMethods = setOf("loadLibrary0", "loadLibrary", "load0")
                for (m in Runtime::class.java.declaredMethods) {
                    if (m.name in runtimeHookMethods && m.parameterTypes.any { it == String::class.java }) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val libArg = chain.args.firstOrNull { it is String && (it as String).contains(blockedLibName) } as? String
                                if (libArg != null) {
                                    // Dynamically discover caller package (e.g. fvfeiucoi in Saned 6.2.0 / 40104 or any future version)
                                    val callerCls = chain.args.firstOrNull { it is Class<*> } as? Class<*>
                                    val activeCl = (chain.args.firstOrNull { it is ClassLoader } as? ClassLoader)
                                        ?: callerCls?.classLoader
                                        ?: lastKnownClassLoader
                                        ?: classLoader
                                    if (callerCls != null) {
                                        val callerPkg = callerCls.name.substringBeforeLast('.', "")
                                        scanAndStubPromonPackage(module, activeCl, callerPkg)
                                    }
                                    try {
                                        for (frame in Thread.currentThread().stackTrace) {
                                            val cn = frame.className ?: continue
                                            val pkg = cn.substringBeforeLast('.', "")
                                            if (!isSystemOrFrameworkPackage(pkg)) {
                                                scanAndStubPromonPackage(module, activeCl, pkg)
                                                break
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                    DiagnosticLogger.logStep("Runtime.${m.name}", "Blocked loading Promon Shield native lib '$libArg'")
                                    return null
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

        // 2. Scan and stub all classes in the active Promon Shield package (fvfeiucoi, oxxqbaxw, ggk, lrbppeks)
        try {
            val candidatePackages = linkedSetOf<String>()
            if (resolved != null && resolved.loaderClass.contains('.')) {
                candidatePackages.add(resolved.loaderClass.substringBeforeLast('.'))
            }
            for (p in profiles) {
                if (p.loaderClass.contains('.')) {
                    val pkg = p.loaderClass.substringBeforeLast('.')
                    try {
                        Class.forName(p.loaderClass, false, classLoader)
                        candidatePackages.add(pkg)
                    } catch (_: Throwable) {}
                }
            }
            for (pkg in candidatePackages) {
                scanAndStubPromonPackage(module, classLoader, pkg)
            }
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:PromonScan", "Error scanning Promon package", t)
        }

        // 3. Lvh1.q: Also explicitly stub nativeClasses from profiles
        try {
            val nativeClassNames = resolved?.nativeClasses ?: profiles.flatMap { it.nativeClasses }
            for (clsName in nativeClassNames) {
                if (stubbedNativeClasses.contains(clsName)) continue
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    var clsHooked = 0
                    for (m in cls.declaredMethods) {
                        if (Modifier.isNative(m.modifiers)) {
                            val mKey = "$clsName#${m.name}/${m.parameterTypes.size}"
                            if (!stubbedNativeClasses.contains(mKey)) {
                                val retType = m.returnType
                                module.hook(m).intercept(object : XposedInterface.Hooker {
                                    override fun intercept(chain: XposedInterface.Chain): Any? = defaultReturnValueForType(retType)
                                })
                                stubbedNativeClasses.add(mKey)
                                clsHooked++
                            }
                        }
                    }
                    if (clsHooked > 0) {
                        stubbedNativeClasses.add(clsName)
                        isNativeStubbed.set(true)
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:NativeStub", "Error stubbing native classes", t)
        }

        // 4. Lvh1.j: Hook loaderClass.loaderMethod(String)
        if (!isLoaderHooked.get() && resolved != null && resolved.loaderClass.isNotEmpty() && resolved.loaderMethod.isNotEmpty()) {
            try {
                val cls = Class.forName(resolved.loaderClass, false, classLoader)
                val m = cls.getDeclaredMethod(resolved.loaderMethod, String::class.java)
                val mKey = "${resolved.loaderClass}#${m.name}/1"
                if (hookedExceptionGuardMethods.add(mKey)) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? = null
                    })
                }
                isLoaderHooked.set(true)
                DiagnosticLogger.logStep("PackageCloaker", "Hooked loader ${resolved.loaderClass}.${resolved.loaderMethod}(String)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:Loader", "Error hooking loader ${resolved.loaderClass}.${resolved.loaderMethod}", t)
            }
        }

        // 5. Lvh1.e: Hook Activity.onResume for blockedActivities redirect
        if (isBlockedActHooked.compareAndSet(false, true)) {
            try {
                val blockedActivities = profiles.mapNotNull { it.blockedActivity.takeIf { s -> s.isNotEmpty() } }.toSet()
                val mainActName = getMainActivityClassName()
                if (mainActName.isNotEmpty()) {
                    val mResume = Activity::class.java.getDeclaredMethod("onResume")
                    module.hook(mResume).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            val act = chain.thisObject as? Activity
                            if (act != null) {
                                val actClsName = act.javaClass.name
                                val actPkg = actClsName.substringBeforeLast('.', "")
                                DiagnosticLogger.logStep("Activity.onResume", "Resumed: $actClsName")
                                val isBlocked = blockedActivities.contains(actClsName) ||
                                    dynamicBlockedActivities.contains(actClsName) ||
                                    scannedPromonPackages.contains(actPkg)
                                if (isBlocked && !act.isFinishing) {
                                    try {
                                        DiagnosticLogger.logWarn("BlockedActivity", "Intercepted blocked activity $actClsName -> redirecting to $mainActName")
                                        val activeCl = lastKnownClassLoader ?: classLoader
                                        val mainCls = activeCl.loadClass(mainActName)
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

            // Cloak NPatch / LSPatch metaloader appComponentFactory & debuggable flag from Jahez security inspection
            if (OrderInterceptor.isMasterRunning) {
                appInfo.flags = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val factory = appInfo.appComponentFactory ?: ""
                    if (factory.contains("npatch", ignoreCase = true) ||
                        factory.contains("lspatch", ignoreCase = true) ||
                        factory.contains("vector", ignoreCase = true) ||
                        factory.contains("metaloader", ignoreCase = true)
                    ) {
                        appInfo.appComponentFactory = "androidx.core.app.CoreComponentFactory"
                    }
                }
            }

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
