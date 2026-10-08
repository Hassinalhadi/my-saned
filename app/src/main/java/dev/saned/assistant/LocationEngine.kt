package dev.saned.assistant

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.*

object LocationEngine {

    @Volatile var currentLat: Double = 24.774265 // Default Riyadh (Al Malqa)
    @Volatile var currentLng: Double = 46.638527
    @Volatile var isFakeLocationEnabled: Boolean = false
    @Volatile var isFixLocationUnknownEnabled: Boolean = true

    fun setLocation(lat: Double, lng: Double) {
        if (lat != 0.0 && lng != 0.0) {
            currentLat = lat
            currentLng = lng
        }
    }

    fun createAccurateLocation(provider: String = "gps"): Location {
        return Location(provider).apply {
            latitude = currentLat
            longitude = currentLng
            altitude = 612.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 3.5f
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

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val lmClass = LocationManager::class.java

        // 1. Hook getLastKnownLocation to completely resolve "location unknown"
        try {
            XposedHelpers.findAndHookMethod(
                lmClass,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val provider = param.args[0] as? String ?: "gps"
                        if (isFakeLocationEnabled || (isFixLocationUnknownEnabled && param.result == null)) {
                            param.result = createAccurateLocation(provider)
                            XposedBridge.log("SanedAssistant: Injected valid Location (Lat: $currentLat, Lng: $currentLng)")
                        } else if (param.result != null) {
                            val loc = param.result as Location
                            if (!isFakeLocationEnabled) {
                                currentLat = loc.latitude
                                currentLng = loc.longitude
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking getLastKnownLocation: ${t.message}")
        }

        // 2. Hook requestLocationUpdates to deliver continuous mock/safe locations
        try {
            XposedHelpers.findAndHookMethod(
                lmClass,
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[3] as? LocationListener ?: return
                        if (isFakeLocationEnabled || isFixLocationUnknownEnabled) {
                            try {
                                listener.onLocationChanged(createAccurateLocation(param.args[0] as? String ?: "gps"))
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking requestLocationUpdates: ${t.message}")
        }
    }
}
