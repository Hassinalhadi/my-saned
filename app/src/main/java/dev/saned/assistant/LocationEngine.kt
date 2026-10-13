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

        // 6. Anti-Shadowban: Hook Location.isFromMockProvider() -> always false
        try {
            val mMock = Location::class.java.getDeclaredMethod("isFromMockProvider")
            module.hook(mMock).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return false
                }
            })
        } catch (_: Throwable) {}

        // 7. Anti-Shadowban: Hook Location.isMock() on Android 12/13/14+ -> always false
        try {
            val mMock31 = Location::class.java.getDeclaredMethod("isMock")
            module.hook(mMock31).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return false
                }
            })
        } catch (_: Throwable) {}

        // 8. Anti-Shadowban: Clean mock extras from Location.getExtras()
        try {
            val mExtras = Location::class.java.getDeclaredMethod("getExtras")
            module.hook(mExtras).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val bundle = chain.proceed() as? android.os.Bundle
                    bundle?.remove("mockLocation")
                    return bundle
                }
            })
        } catch (_: Throwable) {}

        // 9. Ensure GPS is always reported as enabled
        try {
            val mEnabled = LocationManager::class.java.getDeclaredMethod("isProviderEnabled", String::class.java)
            module.hook(mEnabled).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val prov = chain.args.getOrNull(0) as? String
                    if (prov == "gps" || prov == "network") return true
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 10. Realistic Movement matching Ltz0: Hook getSpeed & hasSpeed
        try {
            val mSpeed = Location::class.java.getDeclaredMethod("getSpeed")
            module.hook(mSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                        return 4.5f // ~16 km/h realistic inner city driving speed
                    }
                    return chain.proceed()
                }
            })
            val mHasSpeed = Location::class.java.getDeclaredMethod("hasSpeed")
            module.hook(mHasSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                        return true
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 11. Realistic Movement matching Ltz0: Hook getBearing & hasBearing
        try {
            val mBearing = Location::class.java.getDeclaredMethod("getBearing")
            module.hook(mBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    syncLocationSettings()
                    if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                        return 120.0f
                    }
                    return chain.proceed()
                }
            })
            val mHasBearing = Location::class.java.getDeclaredMethod("hasBearing")
            module.hook(mHasBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && (isFakeLocationEnabled || isFixLocationUnknownEnabled)) {
                        return true
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}
    }
}
