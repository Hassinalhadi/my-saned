package dev.saned.assistant

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import dev.jing.sanedhook.util.Native
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
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
    @Volatile private var mapsCertCheckResult: Int = 0

    private val isRuntimeHooked = AtomicBoolean(false)
    private val isNativeStubbed = AtomicBoolean(false)
    private val isLoaderHooked = AtomicBoolean(false)
    private val isBlockedActHooked = AtomicBoolean(false)
    private val isSecurityHooked = AtomicBoolean(false)

    fun getAllVersionProfiles(): List<VersionProfile> {
        val raw = Native.safeP()
        val result = mutableListOf<VersionProfile>()
        for ((idx, c) in raw.toList().chunked(8).withIndex()) {
  if (c.size == 8) {
      result.add(VersionProfile(idx, c[0], listOf(c[2], c[3]).filter { it.isNotEmpty() }, c[1], c[4], c[5], c[6], c[7]))
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
     * Matches Lvh1.o, Lvh1.q, Lvh1.j, Lvh1.e in SanedAssistant-98-7bac1ec.apk
     */
    fun hookEarly(module: XposedModule, classLoader: ClassLoader) {
        val profiles = getAllVersionProfiles()
        val resolved = resolveProfile(classLoader)

        // 1. Lvh1.o: Hook Runtime.loadLibrary0 ONLY (do NOT hook Runtime.exec!)
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
      }
  } catch (_: Throwable) {}
        }

        // 2. Lvh1.q: Stub native methods on resolved profile's nativeClasses
        if (!isNativeStubbed.get()) {
  try {
      val nativeClassNames = resolved?.nativeClasses ?: profiles.flatMap { it.nativeClasses }
      var hookedCount = 0
      for (clsName in nativeClassNames) {
          try {
              val cls = Class.forName(clsName, false, classLoader)
              for (m in cls.declaredMethods) {
                  if (Modifier.isNative(m.modifiers)) {
                      val retType = m.returnType
                      module.hook(m).intercept(object : XposedInterface.Hooker {
                          override fun intercept(chain: XposedInterface.Chain): Any? = defaultReturnValueForType(retType)
                      })
                      hookedCount++
                  }
              }
          } catch (_: Throwable) {}
      }
      if (hookedCount > 0) isNativeStubbed.set(true)
  } catch (_: Throwable) {}
        }

        // 3. Lvh1.j: Hook loaderClass.loaderMethod(String)
        if (!isLoaderHooked.get() && resolved != null && resolved.loaderClass.isNotEmpty() && resolved.loaderMethod.isNotEmpty()) {
  try {
      val cls = Class.forName(resolved.loaderClass, false, classLoader)
      val m = cls.getDeclaredMethod(resolved.loaderMethod, String::class.java)
      module.hook(m).intercept(object : XposedInterface.Hooker {
          override fun intercept(chain: XposedInterface.Chain): Any? = null
      })
      isLoaderHooked.set(true)
  } catch (_: Throwable) {}
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
        }
    }

    /**
     * Matches Lu80.p and Lb51.b in SanedAssistant-98-7bac1ec.apk (called in onPackageReady)
     */
    fun hook(module: XposedModule, classLoader: ClassLoader) {
        hookEarly(module, classLoader)
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
        } catch (_: Throwable) {}

        // 2. Lu80.y: SecurityCallBacksState
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
                                      try { f.isAccessible = true; f.setBoolean(stateObj, false) } catch (_: Throwable) {}
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

        // 3. Lu80.G: RootBeer & RootBeerNative
        for (idx in 1..2) {
  val rbClsName = secClasses.getOrNull(idx) ?: continue
  try {
      val cls = classLoader.loadClass(rbClsName)
      for (m in cls.declaredMethods) {
          if (m.returnType == Boolean::class.javaPrimitiveType && (idx == 2 || m.parameterCount == 0)) {
              module.hook(m).intercept(object : XposedInterface.Hooker {
                  override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) false else chain.proceed()
              })
          } else if (idx == 2 && m.returnType == Int::class.javaPrimitiveType) {
              module.hook(m).intercept(object : XposedInterface.Hooker {
                  override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) 0 else chain.proceed()
              })
          }
      }
  } catch (_: Throwable) {}
        }

        // 4. Lu80.L: ShadowArmor profiles (only for detectedVersionIndex if >= 0, matching Lu80.L!)
        try {
  val saProfiles = getAllShadowArmorProfiles()
  val sa = saProfiles.getOrNull(detectedVersionIndex)
  val featureFlags = Native.safeU().toList()
  if (sa != null) {
      try {
          val rootCls = classLoader.loadClass(sa.rootCheckClass)
          for (m in rootCls.declaredMethods) {
              if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterCount == 0) {
                  module.hook(m).intercept(object : XposedInterface.Hooker {
                      override fun intercept(chain: XposedInterface.Chain): Any? = if (OrderInterceptor.isMasterRunning) false else chain.proceed()
                  })
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
                  }
              }
          } catch (_: Throwable) {}
      }
  }
        } catch (_: Throwable) {}

        // 5. Lb51.b: ONLY hook getApplicationInfo/getPackageInfo if frameworkName == "Vector" (LSPatch non-root)
        try {
  val fwName = try { module.frameworkName } catch (_: Throwable) { "" }
  if (fwName.equals("Vector", ignoreCase = true)) {
      val appPkgMgrClass = Class.forName("android.app.ApplicationPackageManager")
      for (m in appPkgMgrClass.declaredMethods) {
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
  if (mapsCertCheckResult == 0) {
      val srcDir = appInfo.sourceDir
      if (srcDir.isNullOrEmpty()) return
      val isOfficial = try { Native.safeR(srcDir) } catch (_: Throwable) { false }
      mapsCertCheckResult = if (isOfficial) 1 else 2
  }
  if (mapsCertCheckResult != 2) return
  val metaData: Bundle = appInfo.metaData ?: return
  if (!metaData.containsKey("com.google.android.geo.API_KEY")) return
  val replacementKey = Native.safeQ()
  if (replacementKey.isNotEmpty()) metaData.putString("com.google.android.geo.API_KEY", replacementKey)
        } catch (_: Throwable) {}
    }
}
