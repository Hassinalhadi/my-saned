package dev.saned.assistant

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Matches Ldv1 in SanedAssistant-98-7bac1ec.apk:
 * - Extracts Jahez versionCode (Ldv1.c) and checks version support via Lvh1.n / Native.s(versionCode)
 * - Displays localized unsupported-version warning (Ldv1.g / Ldv1.h) when strict version check fails
 * - Initializes Lvh1, Ls90, Ls20, Ltz0, Lji0, Lfs0, Lly1, Lu80, Lkh, Lb51, Lb11, and Luc1
 */
class HookEntry : XposedModule() {

    private val isSystemHooked = AtomicBoolean(false)
    private val isAppHooked = AtomicBoolean(false)
    private val isUnsupportedWarningShown = AtomicBoolean(false)
    @Volatile private var detectedJahezVersionCode: Long = 0L

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "net.jahez.fleets") return
        if (!param.isFirstPackage) return

        val vCode = extractJahezVersionCode(param)
        detectedJahezVersionCode = vCode
        val isSupported = PackageCloaker.checkVersionSupported(vCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
            notifyUnsupportedJahezVersionAsync(vCode)
            return
        }

        hookSystemLifecycle()
        val cl = param.defaultClassLoader
        if (cl != null) {
            initAppHooks(cl)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != "net.jahez.fleets") return

        val vCode = if (detectedJahezVersionCode > 0L) detectedJahezVersionCode else extractJahezVersionCode(param)
        detectedJahezVersionCode = vCode
        val isSupported = PackageCloaker.checkVersionSupported(vCode)
        if (!isSupported && OrderInterceptor.isStrictVersionCheck) {
            notifyUnsupportedJahezVersionAsync(vCode)
            return
        }

        hookSystemLifecycle()
        val cl = param.classLoader
        if (cl != null) {
            initAppHooks(cl)
        }
    }

    /**
     * Matches Ldv1.c(PackageLoadedParam) in SanedAssistant-98-7bac1ec.apk:
     * Reads longVersionCode / versionCode from ApplicationInfo or PackageManager.getPackageInfo.
     */
    private fun extractJahezVersionCode(param: XposedModuleInterface.PackageLoadedParam): Long {
        try {
            val appInfo = param.applicationInfo
            if (appInfo != null) {
                for (fieldName in listOf("longVersionCode", "versionCode")) {
                    try {
                        val f = appInfo.javaClass.getField(fieldName).apply { isAccessible = true }
                        val v = (f.get(appInfo) as? Number)?.toLong() ?: 0L
                        if (v > 0L) return v
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        val contexts = listOfNotNull(OrderInterceptor.resolveApplicationContext(), getSystemContext())
        for (ctx in contexts) {
            try {
                val pInfo = ctx.packageManager.getPackageInfo("net.jahez.fleets", 0)
                val v = pInfo.longVersionCode
                if (v > 0L) return v
            } catch (_: Throwable) {}
        }
        return 0L
    }

    private fun getSystemContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            atClass.getMethod("getSystemContext").invoke(currentAt) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Matches Ldv1.g(long) & Ldv1.h(Ldv1, long) in SanedAssistant-98-7bac1ec.apk:
     * Displays: "⛔ إصدار سند <versionName> غير مدعوم بعد — يرجى تحديث أداة المساعد"
     */
    private fun notifyUnsupportedJahezVersionAsync(versionCode: Long) {
        if (!isUnsupportedWarningShown.compareAndSet(false, true)) return
        Thread {
            var ctx: Context? = null
            for (i in 0 until 100) {
                ctx = OrderInterceptor.resolveApplicationContext()
                if (ctx != null) break
                try { Thread.sleep(150) } catch (_: Throwable) {}
            }
            val appCtx = ctx?.applicationContext ?: ctx ?: return@Thread
            val versionLabel = try {
                appCtx.packageManager.getPackageInfo("net.jahez.fleets", 0)?.versionName
            } catch (_: Throwable) {
                null
            } ?: versionCode.toString()

            val lang = Locale.getDefault().language
            val msg = when (lang) {
                "ar" -> "⛔ إصدار سند $versionLabel غير مدعوم بعد — يرجى تحديث أداة المساعد"
                "ur" -> "⛔ سعنید $versionLabel ابھی سپورٹڈ نہیں — اسسٹنٹ ماڈیول اپ ڈیٹ کریں"
                "bn" -> "⛔ Saned $versionLabel এখনো সমর্থিত নয় — সহকারী মডিউল আপডেট করুন"
                else -> "⛔ Saned $versionLabel isn't supported yet — update the assistant module"
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun hookSystemLifecycle() {
        if (!isSystemHooked.compareAndSet(false, true)) return

        // 1. Hook Application.onCreate (initializes app context & settings BEFORE chain.proceed)
        try {
            val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(mAppCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val app = chain.thisObject as? Application
                    if (app != null && app.packageName == "net.jahez.fleets") {
                        try {
                            initAppHooks(app.classLoader)
                            OrderInterceptor.initAppContext(app)
                        } catch (_: Throwable) {}
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Activity.onCreate (fallback lifecycle tracking alongside Ls20.d IncomingDispatchActivity hook)
        try {
            val mOnCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(mOnCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
                        // Pre-proceed intent extraction matching Ls20$a.intercept
                        OrderInterceptor.onActivityPreCreated(act)
                    }
                    val res = chain.proceed()
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        OrderInterceptor.onActivityCreated(act)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Activity.onResume
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "net.jahez.fleets") {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        OrderInterceptor.onActivityResumed(act)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 4. Hook Activity.onWindowFocusChanged
        try {
            val mFocus = Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType)
            hook(mFocus).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val hasFocus = chain.args.getOrNull(0) as? Boolean ?: false
                    val act = chain.thisObject as? Activity
                    if (hasFocus && act != null && act.packageName == "net.jahez.fleets") {
                        OrderInterceptor.currentActivity = act
                        OrderInterceptor.onActivityResumed(act)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}
    }

    fun initAppHooks(classLoader: ClassLoader) {
        try {
            val remotePrefs = getRemotePreferences("sanedhook_settings")
            DeviceSpoofer.initRemotePrefs(remotePrefs)
            LocationEngine.initRemotePrefs(remotePrefs)
            OrderInterceptor.initRemotePrefs(remotePrefs)
        } catch (_: Throwable) {}

        // Initialize native bridge matching Ldv1.onPackageReady (0061..0071)
        try {
            Native.safeA(this, "net.jahez.fleets")
            Native.safeC(this, "net.jahez.fleets", "", "", 0L)
        } catch (_: Throwable) {}

        // Always ensure bootstrap thread (Lst1.j + Lji0.k + Lb11.g) is running
        OrderInterceptor.startBootstrapLoop(this, classLoader)

        if (!isAppHooked.compareAndSet(false, true)) return

        try {
            PackageCloaker.hook(this, classLoader)
            LocationEngine.hook(this, classLoader)
            DeviceSpoofer.hook(this, classLoader)
            OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
