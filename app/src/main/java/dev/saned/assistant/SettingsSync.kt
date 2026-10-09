package dev.saned.assistant

import android.content.ContentResolver
import android.net.Uri
import de.robv.android.xposed.XposedBridge

object SettingsSync {

    private val PROVIDER_URI = Uri.parse("content://dev.jing.sanedhook.XposedService")
    private var lastSyncTime = 0L

    fun syncFromProvider(resolver: ContentResolver?) {
        if (resolver == null) return
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 2000) return
        lastSyncTime = now

        try {
            val bundle = resolver.call(PROVIDER_URI, "get_settings", null, null) ?: return

            LocationEngine.isFakeLocationEnabled = bundle.getBoolean(SettingsStore.KEY_FAKE_LOCATION, false)
            LocationEngine.isFixLocationUnknownEnabled = bundle.getBoolean(SettingsStore.KEY_FIX_LOCATION, true)
            val latStr = bundle.getString(SettingsStore.KEY_FAKE_LAT, "24.774265")
            val lngStr = bundle.getString(SettingsStore.KEY_FAKE_LNG, "46.638527")
            LocationEngine.currentLat = latStr?.toDoubleOrNull() ?: 24.774265
            LocationEngine.currentLng = lngStr?.toDoubleOrNull() ?: 46.638527

            DeviceSpoofer.isSpoofAndroidId = bundle.getBoolean(SettingsStore.KEY_SPOOF_ANDROID_ID, true)
            val aid = bundle.getString(SettingsStore.KEY_SPOOFED_ANDROID_ID, "") ?: ""
            if (aid.isNotEmpty()) DeviceSpoofer.spoofedAndroidId = aid

            DeviceSpoofer.isSpoofImei = bundle.getBoolean(SettingsStore.KEY_SPOOF_IMEI, true)
            val imei = bundle.getString(SettingsStore.KEY_SPOOFED_IMEI, "") ?: ""
            if (imei.isNotEmpty()) DeviceSpoofer.spoofedImei = imei

            HookEntry.isAutoAccept = bundle.getBoolean(SettingsStore.KEY_AUTO_ACCEPT, true)
            HookEntry.isAutoReject = bundle.getBoolean(SettingsStore.KEY_AUTO_REJECT, false)
            HookEntry.isDryRun = bundle.getBoolean(SettingsStore.KEY_DRY_RUN, false)
            HookEntry.minOrderPrice = bundle.getString(SettingsStore.KEY_MIN_PRICE, "0.0")?.toDoubleOrNull() ?: 0.0
            HookEntry.maxDistToRestaurant = bundle.getString(SettingsStore.KEY_MAX_DIST_REST, "8.0")?.toDoubleOrNull() ?: 8.0
            HookEntry.maxDistCustomer = bundle.getString(SettingsStore.KEY_MAX_DIST_CUST, "15.0")?.toDoubleOrNull() ?: 15.0
            HookEntry.isSoundEnabled = bundle.getBoolean(SettingsStore.KEY_SOUND, true)
            HookEntry.isShowToasts = bundle.getBoolean(SettingsStore.KEY_SHOW_TOASTS, true)

            XposedBridge.log("SanedAssistant: Synced! FakeLoc=" + LocationEngine.isFakeLocationEnabled + " (" + LocationEngine.currentLat + "," + LocationEngine.currentLng + ") AndroidID=" + DeviceSpoofer.spoofedAndroidId + " IMEI=" + DeviceSpoofer.spoofedImei)
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Sync error: ${t.message}")
        }
    }
}
