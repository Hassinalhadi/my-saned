package dev.saned.assistant

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlin.math.*

object LocationEngine {

    @Volatile var isMasterRunning: Boolean = false
    @Volatile var isFakeLocationEnabled: Boolean = false
    @Volatile var isFixLocationUnknownEnabled: Boolean = false
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0

    private var remotePrefs: SharedPreferences? = null

    fun initRemotePrefs(prefs: SharedPreferences) {
        remotePrefs = prefs
        syncLocationSettings()
        try {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                syncLocationSettings()
            }
        } catch (_: Throwable) {}
    }

    fun syncLocationSettings() {
        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", false)
            isFakeLocationEnabled = p.getBoolean("fake_location_enabled", false)
            isFixLocationUnknownEnabled = p.getBoolean("fix_location_unknown", false)
            currentLat = p.getString("fake_lat", "0.0")?.toDoubleOrNull() ?: 0.0
            currentLng = p.getString("fake_lng", "0.0")?.toDoubleOrNull() ?: 0.0
        }
    }

    fun createAccurateLocation(provider: String = "gps"): Location {
        syncLocationSettings()
        val lat = if (currentLat != 0.0) currentLat else 24.7925
        val lng = if (currentLng != 0.0) currentLng else 46.6189

        return Location(provider).apply {
            latitude = lat
            longitude = lng
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

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        // 1. Hook Location.getLatitude()
        try {
            val mLat = Location::class.java.getDeclaredMethod("getLatitude")
            module.hook(mLat).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0) {
                        return currentLat
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 2. Hook Location.getLongitude()
        try {
            val mLng = Location::class.java.getDeclaredMethod("getLongitude")
            module.hook(mLng).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    if (isMasterRunning && isFakeLocationEnabled && currentLng != 0.0) {
                        return currentLng
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Location.getAccuracy()
        try {
            val mAcc = Location::class.java.getDeclaredMethod("getAccuracy")
            module.hook(mAcc).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                        return 3.0f
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 4. Hook LocationManager.getLastKnownLocation(String)
        try {
            val mLastLoc = LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java)
            module.hook(mLastLoc).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    val orig = chain.proceed() as? Location
                    if (isMasterRunning && (isFakeLocationEnabled || (isFixLocationUnknownEnabled && orig == null))) {
                        val provider = chain.args[0] as? String ?: "gps"
                        return createAccurateLocation(provider)
                    }
                    return orig
                }
            })
        } catch (_: Throwable) {}

        // 5. Hook LocationManager.requestLocationUpdates
        try {
            for (m in LocationManager::class.java.declaredMethods) {
                if (m.name == "requestLocationUpdates") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            syncLocationSettings()
                            if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                                for (arg in chain.args) {
                                    if (arg is LocationListener) {
                                        try {
                                            arg.onLocationChanged(createAccurateLocation("gps"))
                                        } catch (_: Throwable) {}
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
}
