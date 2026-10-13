package dev.saned.assistant

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

/**
 * Matches Lly1 in SanedAssistant-98-7bac1ec.apk:
 * Hooks ONLY Settings.Secure.getString(ContentResolver, String) for "android_id".
 * Removes unnecessary TextView.setText, AlertDialog, and ClipboardManager hooks.
 */
object DeviceSpoofer {

    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isSpoofAndroidId: Boolean = false
    @Volatile var spoofedAndroidId: String = ""

    private var remotePrefs: SharedPreferences? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings() {
        try {
            val f = File("/data/local/tmp/saned_config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                isMasterRunning = json.optBoolean("master_running", json.optBoolean("enabled", isMasterRunning))
                isSpoofAndroidId = json.optBoolean("spoof_android_id", isSpoofAndroidId)
                val sId = json.optString("spoofed_android_id", "")
                if (sId.isNotEmpty()) spoofedAndroidId = sId
            }
        } catch (_: Throwable) {}

        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isSpoofAndroidId = p.getBoolean("spoof_android_id", isSpoofAndroidId)
            val sId = p.getString("spoofed_android_id", "")
            if (!sId.isNullOrEmpty()) {
                spoofedAndroidId = sId
            }
        }
    }

    fun getEffectiveAndroidId(): String {
        syncSettings()
        return spoofedAndroidId.trim()
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val mGetString = Settings.Secure::class.java.getDeclaredMethod(
                "getString",
                ContentResolver::class.java,
                String::class.java
            )
            module.hook(mGetString).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val name = chain.args.getOrNull(1) as? String
                    if (name == Settings.Secure.ANDROID_ID) {
                        syncSettings()
                        val customId = getEffectiveAndroidId()
                        if (isMasterRunning && isSpoofAndroidId && customId.isNotEmpty()) {
                            return customId
                        }
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }
}
