package dev.saned.assistant

import android.content.Context
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

    fun createAccurateLocation(provider: String = "gps"): Location {
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

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 1. UNIVERSAL HOOK: Hook Location.getLatitude() & Location.getLongitude()
        // This guarantees that ANY library (Google Maps SDK, FusedLocationProviderClient, Mapbox, or Jahez internal classes)
        // reading coordinates will receive the exact spoofed coordinates!
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
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
                            val acc = param.result as? Float ?: 0.0f
                            if (acc <= 0.0f || acc > 15.0f) {
                                param.result = 3.0f
                            }
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
                        val provider = param.args[0] as? String ?: "gps"
                        if (isFakeLocationEnabled || (isFixLocationUnknownEnabled && param.result == null)) {
                            param.result = createAccurateLocation(provider)
                            XposedBridge.log("SanedAssistant: Injected getLastKnownLocation -> Lat: $currentLat, Lng: $currentLng")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking getLastKnownLocation: ${t.message}")
        }

        // 3. Hook LocationManager.requestLocationUpdates
        try {
            val listenerHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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
        } catch (t: Throwable) {
            XposedBridge.log("SanedAssistant: Error hooking requestLocationUpdates: ${t.message}")
        }
    }
}
