package dev.saned.assistant

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val isSystemHooked = AtomicBoolean(false)
    private val isAppHooked = AtomicBoolean(false)

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "com.muzahid.gps") {
            hookMuzahidLifecycle()
            return
        }
        if (!param.packageName.contains("jahez") && param.packageName != "net.jahez.fleets") return
        hookSystemLifecycle()
        // Do NOT call initAppHooks here with defaultClassLoader as it only contains boot classes
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == "com.muzahid.gps") {
            param.classLoader?.let { hookMuzahidGps(it) }
            return
        }
        if (!param.packageName.contains("jahez") && param.packageName != "net.jahez.fleets") return
        val cl = param.classLoader
        if (cl != null) {
            initAppHooks(cl)
        }
    }

    private fun hookMuzahidLifecycle() {
        // 1. Hook Activity.onResume for MUZAHID GPS connection feedback
        try {
            val mResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(mResume).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && act.packageName == "com.muzahid.gps") {
                        try {
                            val sp = act.getSharedPreferences("gps", Context.MODE_PRIVATE)
                            sp.edit().putString("get", "VIP_ACTIVE").putBoolean("first_login_shown", true).apply()
                        } catch (_: Throwable) {}
                        try {
                            Toast.makeText(act, "⚡ MUZAHID GPS VIP Activated via Saned!", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 2. Suppress the missing Xposed alert dialog in MUZAHID GPS
        try {
            val mShow = android.app.Dialog::class.java.getDeclaredMethod("show")
            hook(mShow).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val dialog = chain.thisObject as? android.app.Dialog
                    if (dialog != null) {
                        try {
                            val pkg = dialog.context.packageName
                            if (pkg == "com.muzahid.gps" || dialog.ownerActivity?.packageName == "com.muzahid.gps") {
                                dialog.setOnShowListener { d ->
                                    try {
                                        d.dismiss()
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookMuzahidGps(classLoader: ClassLoader) {
        // 1. Hook MainViewModel to make isXposed always emit TRUE and dismiss warning
        try {
            val vmClass = classLoader.loadClass("com.muzahid.gps.ui.viewmodel.MainViewModel")
            for (m in vmClass.declaredMethods) {
                if (m.name == "updateXposedState") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val res = chain.proceed()
                            val vm = chain.thisObject ?: return res
                            try {
                                for (f in vm.javaClass.declaredFields) {
                                    f.isAccessible = true
                                    val v = f.get(vm) ?: continue
                                    for (sm in v.javaClass.methods) {
                                        if (sm.name == "postValue" || sm.name == "setValue") {
                                            sm.isAccessible = true
                                            sm.invoke(v, java.lang.Boolean.TRUE)
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            return res
                        }
                    })
                } else if (m.name == "isXposed") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val liveData = chain.proceed()
                            if (liveData != null) {
                                try {
                                    for (sm in liveData.javaClass.methods) {
                                        if (sm.name == "postValue" || sm.name == "setValue") {
                                            sm.isAccessible = true
                                            sm.invoke(liveData, java.lang.Boolean.TRUE)
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                            return liveData
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 2. Hook Xshare.isHookedSystem to return true
        try {
            val xsClass = classLoader.loadClass("com.muzahid.gps.xposed.Xshare")
            for (m in xsClass.declaredMethods) {
                if (m.name == "isHookedSystem") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? = true
                    })
                }
            }
        } catch (_: Throwable) {}

        // 3. Hook MapActivity.B() dialog getter to auto-dismiss
        try {
            val mapActClass = classLoader.loadClass("com.muzahid.gps.ui.MapActivity")
            for (m in mapActClass.declaredMethods) {
                if (m.name == "B") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val dialog = chain.proceed() as? android.app.Dialog
                            try {
                                dialog?.dismiss()
                            } catch (_: Throwable) {}
                            return dialog
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 4. Hook androidx.core.location.location_providor to eliminate Key / License dialog
        try {
            val lpClass = classLoader.loadClass("androidx.core.location.location_providor")
            for (m in lpClass.declaredMethods) {
                when (m.name) {
                    "l" -> {
                        // Completely suppress the "Enter your license key" dialog
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = null
                        })
                    }
                    "geoapi" -> {
                        // Suppress license check & popup flow
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = null
                        })
                    }
                    "i" -> {
                        // Key validation check -> Always TRUE (VIP active)
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = true
                        })
                    }
                    "h" -> {
                        // Date / expiration check -> Always TRUE
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = true
                        })
                    }
                    "g" -> {
                        // Automatic time check -> Always TRUE
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = true
                        })
                    }
                }
            }
        } catch (_: Throwable) {}

        // 5. Hook access_providor signature & integrity check
        try {
            val apClass = classLoader.loadClass("androidx.core.location.access_providor")
            for (m in apClass.declaredMethods) {
                when (m.name) {
                    "g", "x" -> {
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = null
                        })
                    }
                    "sig", "pkg", "blk", "zip", "certs" -> {
                        hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = true
                        })
                    }
                }
            }
        } catch (_: Throwable) {}

        // 6. Hook google.get & google.O0O0o0oo00oo
        try {
            val gClass = classLoader.loadClass("androidx.core.location.google")
            for (m in gClass.declaredMethods) {
                if (m.name == "get" || m.name == "O0O0o0oo00oo") {
                    hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? = null
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookSystemLifecycle() {
        if (!isSystemHooked.compareAndSet(false, true)) return

        // 1. Hook Application.onCreate
        try {
            val mAppCreate = Application::class.java.getDeclaredMethod("onCreate")
            hook(mAppCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && (app.packageName == "net.jahez.fleets" || app.packageName.contains("jahez"))) {
                        try {
                            OrderInterceptor.initAppContext(app)
                            initAppHooks(app.classLoader)
                        } catch (_: Throwable) {}
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Activity.onCreate
        try {
            val mOnCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            hook(mOnCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val act = chain.thisObject as? Activity
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
                        try {
                            Toast.makeText(act, "⚡ Saned Assistant Connected!", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
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
                    if (act != null && (act.packageName == "net.jahez.fleets" || act.packageName.contains("jahez"))) {
                        OrderInterceptor.initAppContext(act.applicationContext)
                        OrderInterceptor.currentActivity = act
                        initAppHooks(act.classLoader)
                        OrderInterceptor.onActivityCreated(act)
                    }
                    return res
                }
            })
        } catch (_: Throwable) {}

        // 4. Hook Dialog.show for instant event-driven order popup detection
        try {
            val mDialogShow = android.app.Dialog::class.java.getDeclaredMethod("show")
            hook(mDialogShow).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val res = chain.proceed()
                    val dialog = chain.thisObject as? android.app.Dialog
                    if (dialog != null) {
                        OrderInterceptor.onDialogShown(dialog)
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

        if (!isAppHooked.compareAndSet(false, true)) {
            // Re-invoke OrderInterceptor hooks with new ClassLoader if not yet hooked
            OrderInterceptor.hook(this, classLoader)
            return
        }

        try {
            PackageCloaker.hook(this, classLoader)
            LocationEngine.hook(this, classLoader)
            DeviceSpoofer.hook(this, classLoader)
            OrderInterceptor.hook(this, classLoader)
        } catch (_: Throwable) {}
    }
}
