package dev.saned.assistant

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.BaseBundle
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    private val BLOCKED_FEATURE_FLAG_KEYS = setOf(
        "rooted_device_check",
        "auto_clicker_check",
        "installation_source_check"
    )

    private const val PLAY_STORE_PKG = "com.android.vending"

    private val isRuntimeHooked = AtomicBoolean(false)
    private val isNativeStubbed = AtomicBoolean(false)
    private val stubbedNativeClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hookedPromonGuardClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dynamicHookedInstallSourceClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dynamicBlockedActivities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scannedPromonPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        add("ggk")
        add("oxxqbaxw")
        add("lrbppeks")
        add("fvfeiucoi")
    }
    @Volatile private var cachedAppClassLoader: ClassLoader? = null
    private val isLoaderHooked = AtomicBoolean(false)
    private val isBlockedActHooked = AtomicBoolean(false)
    private val isSecurityHooked = AtomicBoolean(false)
    private val isMapsAndPkgHooked = AtomicBoolean(false)
    private val isBundleMapsKeyHooked = AtomicBoolean(false)
    private val isInstallSourceInfoHooked = AtomicBoolean(false)
    private val isFeatureFlagsSourceHooked = AtomicBoolean(false)
    private val isDialogTraceHooked = AtomicBoolean(false)
    private val installSourceLogCount = AtomicInteger(0)

    fun isNativeStubbedFlag(): Boolean = isNativeStubbed.get()

    private fun isInternalHookFrame(clsName: String?): Boolean {
        if (clsName.isNullOrEmpty()) return true
        return clsName.startsWith("android.") ||
            clsName.startsWith("androidx.") ||
            clsName.startsWith("java.") ||
            clsName.startsWith("javax.") ||
            clsName.startsWith("kotlin.") ||
            clsName.startsWith("kotlinx.") ||
            clsName.startsWith("org.matrix.") ||
            clsName.startsWith("top.nkbe.") ||
            clsName.startsWith("org.lsposed.") ||
            clsName.startsWith("io.github.") ||
            clsName.startsWith("dev.saned.") ||
            clsName.startsWith("dev.jing.") ||
            clsName.startsWith("LSPHooker") ||
            clsName.startsWith("o.") ||
            clsName.contains("喵")
    }

    private fun formatAppCallStack(maxFrames: Int = 8): String {
        return try {
            Throwable().stackTrace
                .filter { !isInternalHookFrame(it.className) }
                .take(maxFrames)
                .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        } catch (_: Throwable) { "" }
    }

    private fun safeNoOpReturnValue(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
        Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
        String::class.java, CharSequence::class.java -> ""
        else -> if (type == Any::class.java || type.name == "kotlin.Unit") Unit else null
    }

    private fun describeBooleanFields(obj: Any): String {
        return try {
            obj.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && (it.type == Boolean::class.javaPrimitiveType || it.type == java.lang.Boolean::class.java) }
                .joinToString(",") { f ->
                    f.isAccessible = true
                    "${f.name}=${f.getBoolean(obj)}"
                }
        } catch (_: Throwable) { "" }
    }

    private fun normalizeFailedSecurityResult(obj: Any) {
        try {
            val boolFields = obj.javaClass.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) &&
                    (it.type == Boolean::class.javaPrimitiveType || it.type == java.lang.Boolean::class.java)
            }
            if (boolFields.isEmpty()) return
            for (f in boolFields) f.isAccessible = true
            val dField = boolFields.firstOrNull { it.name == "d" } ?: boolFields.last()
            val failedDVal = dField.getBoolean(obj)
            val cleanVal = !failedDVal
            for (f in boolFields) {
                try {
                    f.setBoolean(obj, cleanVal)
                } catch (_: Throwable) {
                    try { f.set(obj, java.lang.Boolean.valueOf(cleanVal)) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun createFallbackSecurityResult(retType: Class<*>): Any? {
        return try {
            val ctor = retType.declaredConstructors.firstOrNull() ?: return null
            ctor.isAccessible = true
            val args = ctor.parameterTypes.map { pType ->
                when (pType) {
                    Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                    Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
                    String::class.java -> ""
                    else -> null
                }
            }.toTypedArray()
            ctor.newInstance(*args)
        } catch (_: Throwable) { null }
    }

    fun isPromonClassName(clsName: String?): Boolean {
        if (clsName.isNullOrEmpty()) return false
        val pkg = clsName.substringBeforeLast('.', "")
        if (pkg.isNotEmpty() && scannedPromonPackages.contains(pkg)) return true
        if (clsName.contains("guardzilla", ignoreCase = true)) return true
        return false
    }

    fun isPromonThrowable(t: Throwable?): Boolean {
        if (t == null) return false
        if (isPromonClassName(t.javaClass.name)) return true
        val trace = try { t.stackTrace } catch (_: Throwable) { null }
        if (trace != null) {
            for (frame in trace) {
                if (isPromonClassName(frame.className)) return true
            }
        }
        val cause = try { t.cause } catch (_: Throwable) { null }
        if (cause != null && cause !== t && isPromonThrowable(cause)) return true
        return false
    }

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

    private val jniCallLogCounter = AtomicInteger(0)
    private val isProcessKillHooked = AtomicBoolean(false)

    private fun defaultReturnValueForType(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> java.lang.Boolean.TRUE
        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
        Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
        Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
        Char::class.javaPrimitiveType, java.lang.Character::class.java -> ' '
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
        String::class.java, CharSequence::class.java -> ""
        ByteArray::class.java -> ByteArray(0)
        IntArray::class.java -> IntArray(0)
        LongArray::class.java -> LongArray(0)
        BooleanArray::class.java -> BooleanArray(0)
        else -> if (type.isArray) {
            try { java.lang.reflect.Array.newInstance(type.componentType ?: Any::class.java, 0) } catch (_: Throwable) { null }
        } else {
            null
        }
    }

    private fun createPlayStoreInstallSourceInfo(orig: Any?): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return orig
        return try {
            val parcel = Parcel.obtain()
            try {
                parcel.writeString(PLAY_STORE_PKG) // 1. mInitiatingPackageName
                parcel.writeInt(0)                 // 2. mInitiatingPackageSigningInfo = null (writeTypedObject null marker is 0!)
                parcel.writeString(null)           // 3. mOriginatingPackageName (null for normal Play Store installs)
                parcel.writeString(PLAY_STORE_PKG) // 4. mInstallingPackageName
                parcel.writeInt(2)                 // 5. mPackageSource = PACKAGE_SOURCE_STORE (API 33+)
                parcel.writeString(PLAY_STORE_PKG) // 6. mUpdateOwnerPackageName (API 34+)
                parcel.setDataPosition(0)
                InstallSourceInfo.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        } catch (_: Throwable) {
            orig
        }
    }

    private fun installMapsAndPackageCloakerHooks(module: XposedModule) {
        if (isMapsAndPkgHooked.compareAndSet(false, true)) {
            try {
                val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")
                val infoTargetNames = setOf(
                    "getApplicationInfo",
                    "getApplicationInfoAsUser",
                    "getPackageInfo",
                    "getPackageInfoAsUser"
                )
                val installSourceTargetNames = setOf(
                    "getInstallSourceInfo",
                    "getInstallSourceInfoAsUser"
                )
                for (m in appPkgMgrClass.declaredMethods) {
                    when (m.name) {
                        in infoTargetNames -> {
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
                        "getInstallerPackageName" -> {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    if (OrderInterceptor.isMasterRunning) {
                                        if (installSourceLogCount.incrementAndGet() <= 12) {
                                            DiagnosticLogger.logStep("PackageCloaker", "getInstallerPackageName -> $PLAY_STORE_PKG")
                                        }
                                        return PLAY_STORE_PKG
                                    }
                                    return chain.proceed()
                                }
                            })
                        }
                        in installSourceTargetNames -> {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val orig = try { chain.proceed() } catch (_: Throwable) { null }
                                    if (!OrderInterceptor.isMasterRunning) return orig
                                    val spoofed = createPlayStoreInstallSourceInfo(orig)
                                    if (installSourceLogCount.incrementAndGet() <= 12) {
                                        DiagnosticLogger.logStep("PackageCloaker", "${m.name} -> PlayStore InstallSourceInfo")
                                    }
                                    return spoofed
                                }
                            })
                        }
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "Hooked ApplicationPackageManager (AppInfo/PkgInfo + InstallSourceInfo/InstallerPkg)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:MapsPkg", "Error hooking ApplicationPackageManager", t)
            }
        }

        // Hook public getters on android.content.pm.InstallSourceInfo directly (Android 11+ / API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isInstallSourceInfoHooked.compareAndSet(false, true)) {
            try {
                val isiClass = InstallSourceInfo::class.java
                val stringGetters = setOf(
                    "getInstallingPackageName",
                    "getInitiatingPackageName",
                    "getUpdateOwnerPackageName"
                )
                var isiHookCount = 0
                for (m in isiClass.declaredMethods) {
                    if (m.name in stringGetters && m.parameterCount == 0 && m.returnType == String::class.java) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                if (OrderInterceptor.isMasterRunning) {
                                    return PLAY_STORE_PKG
                                }
                                return chain.proceed()
                            }
                        })
                        isiHookCount++
                    } else if (m.name == "getOriginatingPackageName" && m.parameterCount == 0 && m.returnType == String::class.java) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? =
                                if (OrderInterceptor.isMasterRunning) null else chain.proceed()
                        })
                        isiHookCount++
                    } else if (m.name == "getPackageSource" && m.parameterCount == 0 && m.returnType == Int::class.javaPrimitiveType) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? =
                                if (OrderInterceptor.isMasterRunning) 2 else chain.proceed()
                        })
                        isiHookCount++
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "Hooked InstallSourceInfo getters ($isiHookCount methods -> $PLAY_STORE_PKG)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:ISI", "Error hooking InstallSourceInfo", t)
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
                        } else if (m.name == "getBoolean" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val key = chain.args.firstOrNull() as? String
                                    if (OrderInterceptor.isMasterRunning && key != null && BLOCKED_FEATURE_FLAG_KEYS.contains(key)) {
                                        return java.lang.Boolean.FALSE
                                    }
                                    return chain.proceed()
                                }
                            })
                        }
                    }
                } catch (_: Throwable) {}
            }
            DiagnosticLogger.logStep("PackageCloaker", "Hooked BaseBundle/Bundle (Maps API_KEY + FeatureFlag booleans)")
        }

        if (isFeatureFlagsSourceHooked.compareAndSet(false, true)) {
            try {
                val spImplCls = Class.forName("android.app.SharedPreferencesImpl")
                for (m in spImplCls.declaredMethods) {
                    if (m.name == "getBoolean" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val key = chain.args.firstOrNull() as? String
                                if (OrderInterceptor.isMasterRunning && key != null && BLOCKED_FEATURE_FLAG_KEYS.contains(key)) {
                                    DiagnosticLogger.logStep("PackageCloaker", "FeatureFlag '$key' -> false (SharedPreferences)")
                                    return java.lang.Boolean.FALSE
                                }
                                return chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
            try {
                for (m in JSONObject::class.java.declaredMethods) {
                    if ((m.name == "optBoolean" || m.name == "getBoolean") &&
                        m.parameterTypes.isNotEmpty() &&
                        m.parameterTypes[0] == String::class.java
                    ) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val key = chain.args.firstOrNull() as? String
                                if (OrderInterceptor.isMasterRunning && key != null && BLOCKED_FEATURE_FLAG_KEYS.contains(key)) {
                                    DiagnosticLogger.logStep("PackageCloaker", "FeatureFlag '$key' -> false (JSONObject)")
                                    return java.lang.Boolean.FALSE
                                }
                                return chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // Guard Dialog.show() & MainActivity.finish() for any future version
        if (isDialogTraceHooked.compareAndSet(false, true)) {
            try {
                val mShow = Dialog::class.java.getDeclaredMethod("show")
                module.hook(mShow).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val dlg = chain.thisObject as? Dialog
                        if (OrderInterceptor.isMasterRunning && dlg != null) {
                            try {
                                val decor = dlg.window?.decorView
                                if (decor != null && containsSecurityAlertText(decor)) {
                                    DiagnosticLogger.logWarn(
                                        "PackageCloaker",
                                        "Blocked security Dialog.show()! App stack: ${formatAppCallStack(12)}"
                                    )
                                    return null
                                }
                            } catch (_: Throwable) {}
                        }
                        return chain.proceed()
                    }
                })
                for (mFinName in listOf("finish", "finishAffinity", "finishAndRemoveTask")) {
                    try {
                        val mFin = Activity::class.java.getDeclaredMethod(mFinName)
                        module.hook(mFin).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val act = chain.thisObject as? Activity
                                if (OrderInterceptor.isMasterRunning && act != null && act.javaClass.name == getMainActivityClassName()) {
                                    val trace = Throwable().stackTrace
                                    val fromDialogOrGuard = trace.any { f ->
                                        val cn = f.className ?: ""
                                        cn.contains("Dialog", ignoreCase = true) ||
                                        cn.contains("AlertController", ignoreCase = true) ||
                                        cn.contains("guardzilla", ignoreCase = true) ||
                                        cn.contains("Security", ignoreCase = true) ||
                                        cn == "Evb" || cn == "kg3" ||
                                        isPromonClassName(cn)
                                    }
                                    if (fromDialogOrGuard) {
                                        DiagnosticLogger.logWarn(
                                            "PackageCloaker",
                                            "Blocked $mFinName() on MainActivity! App stack: ${formatAppCallStack(12)}"
                                        )
                                        return null
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private fun containsSecurityAlertText(view: View): Boolean {
        if (view is TextView) {
            val txt = view.text?.toString() ?: ""
            if (txt.contains("Not installed from Play Store", ignoreCase = true) ||
                txt.contains("Rooted device", ignoreCase = true) ||
                txt.contains("Auto Clicker", ignoreCase = true)
            ) {
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                if (containsSecurityAlertText(child)) return true
            }
        }
        return false
    }

    private fun stubPromonPackage(module: XposedModule, classLoader: ClassLoader, pkgPrefix: String): Int {
        val cleanPkg = pkgPrefix.trim().removeSuffix(".")
        if (cleanPkg.isEmpty() ||
            cleanPkg.startsWith("java.") ||
            cleanPkg.startsWith("javax.") ||
            cleanPkg.startsWith("android.") ||
            cleanPkg.startsWith("androidx.") ||
            cleanPkg.startsWith("kotlin.") ||
            cleanPkg.startsWith("kotlinx.") ||
            cleanPkg.startsWith("org.matrix.") ||
            cleanPkg.startsWith("top.nkbe.") ||
            cleanPkg.startsWith("org.lsposed.") ||
            cleanPkg.startsWith("io.github.") ||
            cleanPkg.startsWith("dev.saned.") ||
            cleanPkg.startsWith("dev.jing.")
        ) {
            return 0
        }
        scannedPromonPackages.add(cleanPkg)

        val candidates = ArrayList<String>(104)
        for (c in 'a'..'z') candidates.add(c.toString())
        for (c in 'A'..'Z') candidates.add(c.toString())
        for (c in 'a'..'z') candidates.add("a$c")
        for (c in 'a'..'z') candidates.add("b$c")

        var newlyHookedMethods = 0
        var watchdogHooks = 0
        for (shortName in candidates) {
            val clsName = "$cleanPkg.$shortName"
            try {
                val cls = Class.forName(clsName, false, classLoader)
                if (Activity::class.java.isAssignableFrom(cls)) {
                    dynamicBlockedActivities.add(clsName)
                    continue
                }
                if (Throwable::class.java.isAssignableFrom(cls) || cls.isInterface || cls.isAnnotation || cls.isEnum) {
                    continue
                }

                val allMethods = cls.declaredMethods
                val nativeMethods = allMethods.filter { Modifier.isNative(it.modifiers) }
                if (nativeMethods.isNotEmpty() && !stubbedNativeClasses.contains(clsName)) {
                    for (m in nativeMethods) {
                        val retType = m.returnType
                        val mName = m.name
                        val pSig = m.parameterTypes.joinToString(",") { it.simpleName }
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val retVal = defaultReturnValueForType(retType)
                                val count = jniCallLogCounter.incrementAndGet()
                                if (count <= 40) {
                                    DiagnosticLogger.logStep("JNI-Call", "$clsName.$mName($pSig):${retType.simpleName} -> $retVal")
                                }
                                return retVal
                            }
                        })
                        newlyHookedMethods++
                    }
                    stubbedNativeClasses.add(clsName)
                }

                if (hookedPromonGuardClasses.add(clsName)) {
                    val isRunnableOrThread = Runnable::class.java.isAssignableFrom(cls) || Thread::class.java.isAssignableFrom(cls)
                    for (m in allMethods) {
                        if (Modifier.isNative(m.modifiers) || Modifier.isAbstract(m.modifiers)) continue
                        val retType = m.returnType
                        val mName = m.name
                        if (isRunnableOrThread && mName == "run" && m.parameterCount == 0) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    DiagnosticLogger.logStep("PackageCloaker", "Skipped Promon watchdog $clsName.run() on ${Thread.currentThread().name}")
                                    return null
                                }
                            })
                            watchdogHooks++
                        } else if (nativeMethods.isEmpty()) {
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    return try {
                                        chain.proceed()
                                    } catch (t: Throwable) {
                                        if (isPromonThrowable(t)) {
                                            DiagnosticLogger.logWarn(
                                                "PackageCloaker",
                                                "Suppressed ${t.javaClass.name} (${t.message}) in $clsName.$mName"
                                            )
                                            defaultReturnValueForType(retType)
                                        } else {
                                            throw t
                                        }
                                    }
                                }
                            })
                            watchdogHooks++
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (stubbedNativeClasses.isNotEmpty()) {
            isNativeStubbed.set(true)
        }
        if (newlyHookedMethods > 0 || watchdogHooks > 0) {
            DiagnosticLogger.logStep(
                "PackageCloaker",
                "Stubbed $newlyHookedMethods JNI methods & $watchdogHooks guard/watchdog methods in '$cleanPkg' -> blockedAct=$dynamicBlockedActivities"
            )
        }
        return newlyHookedMethods
    }

    private fun discoverAndStubCallerPromonPackage(module: XposedModule, chain: XposedInterface.Chain): Boolean {
        if (isNativeStubbed.get()) return true
        var effCl = cachedAppClassLoader
        val candidatePackages = LinkedHashSet<String>()

        try {
            for (arg in chain.args) {
                when (arg) {
                    is ClassLoader -> if (effCl == null) effCl = arg
                    is Class<*> -> {
                        if (effCl == null && arg.classLoader != null) effCl = arg.classLoader
                        val cname = arg.name
                        if (cname.contains('.')) {
                            val pkg = cname.substringBeforeLast('.')
                            if (!pkg.contains('.')) {
                                candidatePackages.add(pkg)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val trace = Throwable().stackTrace
            for (i in trace.indices) {
                val frame = trace[i]
                val cname = frame.className ?: continue
                if (!cname.contains('.')) continue
                val pkg = cname.substringBeforeLast('.')
                if (!pkg.contains('.') && pkg.length in 2..16 && pkg != "o" && pkg != "kotlin") {
                    candidatePackages.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        val cl = effCl ?: return isNativeStubbed.get()
        for (pkg in candidatePackages) {
            stubPromonPackage(module, cl, pkg)
        }
        return isNativeStubbed.get()
    }

    fun hookEarly(module: XposedModule, classLoader: ClassLoader) {
        cachedAppClassLoader = classLoader
        installMapsAndPackageCloakerHooks(module)

        val profiles = getAllVersionProfiles()
        val resolved = resolveProfile(classLoader)

        // 1. Lvh1.q: Stub native methods on resolved profile's nativeClasses FIRST (e.g. fvfeiucoi.ag & fvfeiucoi.c)
        try {
            val nativeClassNames = if (resolved != null && resolved.nativeClasses.isNotEmpty()) {
                resolved.nativeClasses
            } else {
                profiles.flatMap { it.nativeClasses }.filter { it.isNotEmpty() }
            }
            var newlyHookedCount = 0
            for (clsName in nativeClassNames) {
                if (clsName.isEmpty() || stubbedNativeClasses.contains(clsName)) continue
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    var clsHooked = 0
                    for (m in cls.declaredMethods) {
                        if (Modifier.isNative(m.modifiers)) {
                            val retType = m.returnType
                            val mName = m.name
                            val pSig = m.parameterTypes.joinToString(",") { it.simpleName }
                            module.hook(m).intercept(object : XposedInterface.Hooker {
                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                    val retVal = defaultReturnValueForType(retType)
                                    val count = jniCallLogCounter.incrementAndGet()
                                    if (count <= 40) {
                                        DiagnosticLogger.logStep("JNI-Call", "$clsName.$mName($pSig):${retType.simpleName} -> $retVal")
                                    }
                                    return retVal
                                }
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

            if (resolved != null && resolved.loaderClass.contains('.')) {
                val pkgPrefix = resolved.loaderClass.substringBeforeLast('.')
                newlyHookedCount += stubPromonPackage(module, classLoader, pkgPrefix)
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
                DiagnosticLogger.logStep("PackageCloaker", "Stubbed $newlyHookedCount JNI methods on ${resolved?.nativeClasses ?: stubbedNativeClasses} (allReady=$allRequiredStubbed)")
            } else if (!isNativeStubbed.get()) {
                DiagnosticLogger.logStep(
                    "PackageCloaker",
                    "Deferring JNI stubbing for vCode=$lastCheckedVersionCode to dynamic Runtime.loadLibrary0 caller discovery"
                )
            }
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:NativeStub", "Error stubbing native classes", t)
        }

        // 2. Lvh1.j: Hook loaderClass (e.g. ggk.z.b, oxxqbaxw.b.a, lrbppeks.am.a, fvfeiucoi.I.a & fvfeiucoi.I.b) to return null
        if (!isLoaderHooked.get() && resolved != null && resolved.loaderClass.isNotEmpty() && resolved.loaderMethod.isNotEmpty()) {
            try {
                val cls = Class.forName(resolved.loaderClass, false, classLoader)
                var hookedCount = 0
                for (m in cls.declaredMethods) {
                    if (!Modifier.isNative(m.modifiers) && !Modifier.isAbstract(m.modifiers)) {
                        val retType = m.returnType
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = defaultReturnValueForType(retType)
                        })
                        hookedCount++
                    }
                }
                isLoaderHooked.set(true)
                DiagnosticLogger.logStep("PackageCloaker", "Hooked ${resolved.loaderClass}.${resolved.loaderMethod} ($hookedCount methods stubbed)")
            } catch (t: Throwable) {
                DiagnosticLogger.logError("PackageCloaker:Loader", "Error hooking ${resolved.loaderClass}.${resolved.loaderMethod}", t)
            }
        }

        // 2b. Guard Process.killProcess & Runtime.exit against Promon delayed self-termination
        if (isProcessKillHooked.compareAndSet(false, true)) {
            try {
                val mKill = android.os.Process::class.java.getDeclaredMethod("killProcess", Int::class.javaPrimitiveType)
                module.hook(mKill).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val trace = Throwable().stackTrace
                        val promonFrame = trace.firstOrNull { f -> isPromonClassName(f.className) }
                        if (promonFrame != null) {
                            DiagnosticLogger.logWarn("PackageCloaker", "Blocked Process.killProcess triggered by ${promonFrame.className}.${promonFrame.methodName}:${promonFrame.lineNumber}")
                            return null
                        }
                        return chain.proceed()
                    }
                })
                for (mExit in Runtime::class.java.declaredMethods) {
                    if ((mExit.name == "exit" || mExit.name == "halt") && mExit.parameterTypes.size == 1 && mExit.parameterTypes[0] == Int::class.javaPrimitiveType) {
                        module.hook(mExit).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val trace = Throwable().stackTrace
                                val promonFrame = trace.firstOrNull { f -> isPromonClassName(f.className) }
                                if (promonFrame != null) {
                                    DiagnosticLogger.logWarn("PackageCloaker", "Blocked Runtime.${mExit.name} triggered by ${promonFrame.className}.${promonFrame.methodName}:${promonFrame.lineNumber}")
                                    return null
                                }
                                return chain.proceed()
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. Lvh1.o: Hook Runtime.loadLibrary0
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
                                    if (!isNativeStubbed.get()) {
                                        discoverAndStubCallerPromonPackage(module, chain)
                                    }
                                    if (isNativeStubbed.get()) {
                                        DiagnosticLogger.logStep("Runtime.loadLibrary0", "Blocked loading native lib '$lastArg' (JNI methods stubbed)")
                                        return null
                                    } else {
                                        DiagnosticLogger.logWarn(
                                            "Runtime.loadLibrary0",
                                            "Allowing '$lastArg' to load because JNI methods were NOT stubbed for vCode=$lastCheckedVersionCode"
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
                if ((blockedActivities.isNotEmpty() || dynamicBlockedActivities.isNotEmpty()) && mainActName.isNotEmpty()) {
                    val mResume = Activity::class.java.getDeclaredMethod("onResume")
                    module.hook(mResume).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            val act = chain.thisObject as? Activity
                            if (act != null) {
                                val actClsName = act.javaClass.name
                                if ((blockedActivities.contains(actClsName) || dynamicBlockedActivities.contains(actClsName)) && !act.isFinishing) {
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

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        cachedAppClassLoader = classLoader
        installMapsAndPackageCloakerHooks(module)
        if (DiagnosticLogger.hookEarlyCloaker) {
            hookEarly(module, classLoader)
        }
        if (!isSecurityHooked.compareAndSet(false, true)) return

        // 1. Lu80.y: SecurityCallBacksState (Clear all boolean fields on construction AND on copy$default)
        val secClasses = Native.safeN()
        val secStateClsName = secClasses.getOrNull(0) ?: ""
        if (secStateClsName.isNotEmpty()) {
            try {
                val secCls = classLoader.loadClass(secStateClsName)
                var secHooked = 0
                fun clearSecurityStateBooleans(obj: Any?) {
                    if (obj == null) return
                    for (f in obj.javaClass.declaredFields) {
                        if (f.type == Boolean::class.javaPrimitiveType || f.type == java.lang.Boolean::class.java) {
                            try {
                                f.isAccessible = true
                                f.setBoolean(obj, false)
                            } catch (_: Throwable) {
                                try { f.set(obj, java.lang.Boolean.FALSE) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                for (c in secCls.declaredConstructors) {
                    module.hook(c).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            if (OrderInterceptor.isMasterRunning) {
                                clearSecurityStateBooleans(chain.thisObject)
                            }
                            return res
                        }
                    })
                    secHooked++
                }
                for (m in secCls.declaredMethods) {
                    if (Modifier.isStatic(m.modifiers) && m.parameterTypes.firstOrNull() == secCls) {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                if (OrderInterceptor.isMasterRunning) {
                                    clearSecurityStateBooleans(chain.args.getOrNull(0))
                                }
                                val res = chain.proceed()
                                if (OrderInterceptor.isMasterRunning) {
                                    clearSecurityStateBooleans(res)
                                }
                                return res
                            }
                        })
                        secHooked++
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "SecurityCallBacksState hooked ($secHooked constructors/copy methods)")
            } catch (_: Throwable) {}
        }

        // 2. Lu80.G: RootBeer & RootBeerNative
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

        // 3. Lu80.L: ShadowArmor profile for current detectedVersionIndex
        try {
            val saProfiles = getAllShadowArmorProfiles()
            val sa = saProfiles.getOrNull(detectedVersionIndex)
            val featureFlags = Native.safeU().toList()
            if (sa != null) {
                var saCount = 0
                if (sa.rootCheckClass.isNotEmpty()) {
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
                }
                for ((clsName, retVal) in listOf(sa.autoClickerClass to false, sa.installSourceClass to true, sa.certPinClass to true)) {
                    if (clsName.isEmpty()) continue
                    try {
                        val c = classLoader.loadClass(clsName)
                        for (m in c.declaredMethods) {
                            if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.contentEquals(arrayOf(Context::class.java))) {
                                module.hook(m).intercept(object : XposedInterface.Hooker {
                                    override fun intercept(chain: XposedInterface.Chain): Any? =
                                        if (OrderInterceptor.isMasterRunning) retVal else chain.proceed()
                                })
                                dynamicHookedInstallSourceClasses.add(clsName)
                                saCount++
                            }
                        }
                    } catch (_: Throwable) {}
                }
                if (featureFlags.isNotEmpty() && sa.featureFlagsClass.isNotEmpty()) {
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

            // 4. Hook vCode=40104 discovered security coordinator (Evb), dialog builder (kg3), and state model (cx1):
            //    - Stack in 26.txt: kg3.c:106 <- Evb.a:132 <- net.jahez.fleets.MainActivity.s0:39
            //                       d06.a(Context) <- Evb.c:4 <- Evb.a:35 <- net.jahez.fleets.MainActivity.s0:39
            //    - Stack in 29.txt: MainActivity.s0:43 reads 'boolean cx1.d' on the cx1 instance returned by Evb.a!
            val wasKg3CalledInEvbA = AtomicBoolean(false)
            val invertEvbC = AtomicBoolean(true)

            try {
                val kg3Cls = classLoader.loadClass("kg3")
                var kg3Hooked = 0
                for (m in kg3Cls.declaredMethods) {
                    if (m.name == "c") {
                        val retType = m.returnType
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                if (!OrderInterceptor.isMasterRunning) return chain.proceed()
                                wasKg3CalledInEvbA.set(true)
                                invertEvbC.set(!invertEvbC.get())
                                DiagnosticLogger.logStep("PackageCloaker", "Blocked kg3.c security dialog builder at Evb.a:132")
                                return safeNoOpReturnValue(retType)
                            }
                        })
                        kg3Hooked++
                    }
                }
                if (kg3Hooked > 0) {
                    DiagnosticLogger.logStep("PackageCloaker", "Hooked kg3.c security dialog ($kg3Hooked methods)")
                }
            } catch (_: Throwable) {}

            try {
                val evbCls = classLoader.loadClass("Evb")
                var evbHooked = 0
                for (m in evbCls.declaredMethods) {
                    val mName = m.name
                    val retType = m.returnType
                    if (mName == "c" && retType == Boolean::class.javaPrimitiveType) {
                        // Evb.c:4 is the Play Store (d06.a) + CertPin check called at Evb.a:35.
                        // On NPatch APK, chain.proceed() returns the failed boolean (due to NPatch cert), so !realRes is the passing boolean.
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val realRes = (chain.proceed() as? Boolean) ?: false
                                if (!OrderInterceptor.isMasterRunning) return realRes
                                val out = if (invertEvbC.get()) !realRes else realRes
                                DiagnosticLogger.logStep("PackageCloaker", "Evb.c realRes=$realRes -> returning $out")
                                return out
                            }
                        })
                        evbHooked++
                    } else if (mName == "a") {
                        // Evb.a is called by MainActivity.s0:39 and MUST execute chain.proceed() so it returns a non-null cx1 instance for MainActivity.s0:43!
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                wasKg3CalledInEvbA.set(false)
                                var res = chain.proceed()
                                if (!OrderInterceptor.isMasterRunning) return res
                                val dialogTriggered = wasKg3CalledInEvbA.getAndSet(false)
                                if (res == null && retType != Void.TYPE && retType.name != "kotlin.Unit") {
                                    res = createFallbackSecurityResult(retType)
                                }
                                if (res != null) {
                                    val beforeFields = describeBooleanFields(res)
                                    if (dialogTriggered) {
                                        normalizeFailedSecurityResult(res)
                                    }
                                    val afterFields = describeBooleanFields(res)
                                    DiagnosticLogger.logStep(
                                        "PackageCloaker",
                                        "Evb.a completed (dialogTriggered=$dialogTriggered, ret=${res.javaClass.name}, before=[$beforeFields], after=[$afterFields])"
                                    )
                                }
                                return res
                            }
                        })
                        evbHooked++
                    }
                }
                DiagnosticLogger.logStep("PackageCloaker", "Hooked Evb security coordinator ($evbHooked methods)")
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            DiagnosticLogger.logError("PackageCloaker:ShadowArmor", "Error hooking ShadowArmor", t)
        }
    }

    fun patchGoogleMapsApiKeyIfNeeded(infoObj: Any?, module: XposedModule? = null) {
        try {
            val appInfo: ApplicationInfo = when (infoObj) {
                is ApplicationInfo -> infoObj
                is PackageInfo -> {
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
