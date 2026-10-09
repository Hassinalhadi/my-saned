package dev.saned.assistant

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Environment
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import kotlin.math.*

object LocationEngine {

    @Volatile var currentLat: Double = 24.774265 // Default Riyadh (Al Malqa)
    @Volatile var currentLng: Double = 46.638527
    @Volatile var isFakeLocationEnabled: Boolean = false
    @Volatile var isFixLocationUnknownEnabled: Boolean = true

    fun syncLocationSettings() {
        // Priority 1: Direct file sync from Download folder
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "saned_location.txt")
            if (file.exists()) {
                val parts = file.readText().trim().split(",")
                if (parts.size >= 3) {
                    isFakeLocationEnabled = parts[0].toBoolean()
                    val lat = parts[1].toDoubleOrNull()
                    val lng = parts[2].toDoubleOrNull()
                    if (lat != null && lng != null && lat != 0.0) {
                        currentLat = lat
                        currentLng = lng
                    }
                }
            }
        } catch (_: Throwable) {}

        // Priority 2: XSharedPreferences
        try {
            val pref = XSharedPreferences("dev.jing.sanedhook", "sanedhook_settings")
            if (pref.hasFileChanged()) pref.reload()
            isFakeLocationEnabled = pref.getBoolean("fake_location_enabled", isFakeLocationEnabled)
            val lat = pref.getString("fake_lat", "")?.toDoubleOrNull()
            val lng = pref.getString("fake_lng", "")?.toDoubleOrNull()
            if (lat != null && lng != null && lat != 0.0) {
                currentLat = lat
                currentLng = lng
            }
        } catch (_: Throwable) {}
    }

    fun createAccurateLocation(provider: String = "gps"): Location {
        syncLocationSettings()
        return Location(provider).apply {
            latitude = currentLat
            longitude = currentLng
            altitude = 612.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 3.0f
            speed = 0.0f
            bearing = 0.0f
        }
    }

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun hook(classLoader: ClassLoader) {
        // 1. UNIVERSAL HOOK: Hook Location.getLatitude() & Location.getLongitude()
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncLocationSettings()
                        if (isFakeLocationEnabled) {
                            param.result = currentLat
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncLocationSettings()
                        if (isFakeLocationEnabled) {
                            param.result = currentLng
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getAccuracy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isFakeLocationEnabled || isFixLocationUnknownEnabled) {
                            param.result = 3.0f
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking Location getters: ${t.message}")
        }

        // 2. Hook LocationManager.getLastKnownLocation
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncLocationSettings()
                        val provider = param.args[0] as? String ?: "gps"
                        if (isFakeLocationEnabled || (isFixLocationUnknownEnabled && param.result == null)) {
                            param.result = createAccurateLocation(provider)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // 3. Hook LocationManager.requestLocationUpdates
        try {
            val listenerHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    syncLocationSettings()
                    for (arg in param.args) {
                        if (arg is LocationListener) {
                            if (isFakeLocationEnabled || isFixLocationUnknownEnabled) {
                                try {
                                    arg.onLocationChanged(createAccurateLocation("gps"))
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            }

            for (m in LocationManager::class.java.declaredMethods) {
                if (m.name == "requestLocationUpdates") {
                    XposedBridge.hookMethod(m, listenerHook)
                }
            }
        } catch (_: Throwable) {}
    }
}
