package dev.saned.assistant

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

object DeviceSpoofer {

    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isSpoofAndroidId: Boolean = false
    @Volatile var spoofedAndroidId: String = ""

    private var remotePrefs: SharedPreferences? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncFromPrefs()
        try {
  prefs.registerOnSharedPreferenceChangeListener { _, _ ->
      syncFromPrefs()
  }
        } catch (_: Throwable) {}
    }

    private fun syncFromPrefs() {
        remotePrefs?.let { p ->
  isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
  isSpoofAndroidId = p.getBoolean("spoof_android_id", isSpoofAndroidId)
  val sId = p.getString("spoofed_android_id", "")?.trim() ?: ""
  if (sId.isNotEmpty()) spoofedAndroidId = sId
        }
    }

    fun syncSettings(resolver: ContentResolver? = null) {
        syncFromPrefs()
        try {
  val f = File("/data/local/tmp/saned_config.json")
  if (f.exists()) {
      val json = JSONObject(f.readText())
      isMasterRunning = json.optBoolean("master_running", json.optBoolean("enabled", isMasterRunning))
      isSpoofAndroidId = json.optBoolean("spoof_android_id", isSpoofAndroidId)
      val sId = json.optString("spoofed_android_id", "").trim()
      if (sId.isNotEmpty()) spoofedAndroidId = sId
  }
        } catch (_: Throwable) {}
    }

    fun getEffectiveAndroidId(resolver: ContentResolver? = null): String {
        val clean = spoofedAndroidId.trim()
        return if (clean.isNotEmpty()) clean else "7a8b9c0d1e2f3456"
    }

    /**
     * Matches Lly1.b in SanedAssistant-98-7bac1ec.apk:
     * Hooks ONLY Settings.Secure.getString(ContentResolver, String) and returns in-memory spoofedAndroidId.
     */
    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
  val mGetString = Settings.Secure::class.java.getDeclaredMethod(
      "getString",
      ContentResolver::class.java,
      String::class.java
  )
  module.hook(mGetString).intercept(object : XposedInterface.Hooker {
      override fun intercept(chain: XposedInterface.Chain): Any? {
          val key = chain.args.getOrNull(1) as? String
          if ("android_id" == key && isMasterRunning && isSpoofAndroidId && spoofedAndroidId.isNotEmpty()) {
              return spoofedAndroidId
          }
          return chain.proceed()
      }
  })
        } catch (_: Throwable) {}
    }
}
