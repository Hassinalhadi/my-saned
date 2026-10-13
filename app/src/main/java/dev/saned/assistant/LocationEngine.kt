package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/**
 * Matches Ltz0 and Lp90 in SanedAssistant-98-7bac1ec.apk:
 * - Hooks only the 7 methods in Ltz0.m:
 *   Location.getLatitude, getLongitude, getSpeed, hasSpeed, getBearing, hasBearing,
 *   and LocationManager.getLastKnownLocation(String).
 * - Uses a ThreadLocal<Int> re-entrancy guard (matching Ltz0$e) when reading real locations.
 * - Actively registers passive/active listeners on LocationManager (matching Ltz0.y / Ltz0.j / Ltz0.w)
 *   so the process continuously receives location fixes.
 */
object LocationEngine {

    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isFakeLocationEnabled: Boolean = false
    @Volatile var isFixLocationUnknownEnabled: Boolean = false
    @Volatile var isTripSimulationActive: Boolean = false
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0
    @Volatile var lastRealLat: Double = 0.0
    @Volatile var lastRealLng: Double = 0.0

    private var remotePrefs: SharedPreferences? = null
    private val reentrancyDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private var isSeeded = false

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
        try {
            val f = File("/data/local/tmp/saned_config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                isMasterRunning = json.optBoolean("master_running", json.optBoolean("enabled", isMasterRunning))
                isFakeLocationEnabled = json.optBoolean("fake_location_enabled", isFakeLocationEnabled)
                isFixLocationUnknownEnabled = json.optBoolean("fix_location_unknown", isFixLocationUnknownEnabled)
                val lat = json.optDouble("fake_lat", currentLat)
                val lng = json.optDouble("fake_lng", currentLng)
                if (!lat.isNaN() && lat != 0.0) currentLat = lat
                if (!lng.isNaN() && lng != 0.0) currentLng = lng
            }
        } catch (_: Throwable) {}

        remotePrefs?.let { p ->
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isFakeLocationEnabled = p.getBoolean("fake_location_enabled", isFakeLocationEnabled)
            isFixLocationUnknownEnabled = p.getBoolean("fix_location_unknown", p.getBoolean("high_accuracy_location", isFixLocationUnknownEnabled))
            val lat = p.getString("fake_lat", null)?.toDoubleOrNull()
            val lng = p.getString("fake_lng", null)?.toDoubleOrNull()
            if (lat != null && lat != 0.0) currentLat = lat
            if (lng != null && lng != 0.0) currentLng = lng
        }
    }

    private fun recordRealLocation(loc: Location?) {
        if (loc == null) return
        val depth = reentrancyDepth.get() ?: 0
        reentrancyDepth.set(depth + 1)
        try {
            val lat = loc.latitude
            val lng = loc.longitude
            if (lat != 0.0 && lng != 0.0) {
                lastRealLat = lat
                lastRealLng = lng
            }
        } catch (_: Throwable) {
        } finally {
            reentrancyDepth.set(depth)
        }
    }

    /**
     * Matches Ltz0.y(Context): Seeds LocationManager from fused/gps/network/passive providers
     */
    fun seedLocationManager(context: Context) {
        if (isSeeded) return
        isSeeded = true
        Handler(Looper.getMainLooper()).post {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@post
                val providers = listOf("fused", "gps", "network")
                val enabledProviders = providers.filter {
                    try { lm.isProviderEnabled(it) } catch (_: Throwable) { false }
                }

                val depth = reentrancyDepth.get() ?: 0
                reentrancyDepth.set(depth + 1)
                try {
                    var bestLoc: Location? = null
                    for (prov in providers) {
                        val l = try { lm.getLastKnownLocation(prov) } catch (_: Throwable) { null }
                        if (l != null && (bestLoc == null || l.time > bestLoc.time)) {
                            bestLoc = l
                        }
                    }
                    if (bestLoc != null) {
                        recordRealLocation(bestLoc)
                    }
                } finally {
                    reentrancyDepth.set(depth)
                }

                val passiveListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        recordRealLocation(location)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    lm.requestLocationUpdates("passive", 1000L, 0f, passiveListener, Looper.getMainLooper())
                } catch (_: Throwable) {}

                if (isMasterRunning && isFixLocationUnknownEnabled && enabledProviders.isNotEmpty()) {
                    val activeListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            recordRealLocation(location)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    for (prov in enabledProviders) {
                        try {
                            lm.requestLocationUpdates(prov, 2000L, 0f, activeListener, Looper.getMainLooper())
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
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
        // 1. Location.getLatitude() matching Ltz0.n
        try {
            val mLat = Location::class.java.getDeclaredMethod("getLatitude")
            module.hook(mLat).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    syncLocationSettings()
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0) {
                        return currentLat
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 2. Location.getLongitude() matching Ltz0.o
        try {
            val mLng = Location::class.java.getDeclaredMethod("getLongitude")
            module.hook(mLng).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    syncLocationSettings()
                    if (isMasterRunning && isFakeLocationEnabled && currentLng != 0.0) {
                        return currentLng
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 3. Location.getSpeed() matching Ltz0.p
        try {
            val mSpeed = Location::class.java.getDeclaredMethod("getSpeed")
            module.hook(mSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled && isTripSimulationActive) {
                        return 4.5f
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 4. Location.hasSpeed() matching Ltz0.q
        try {
            val mHasSpeed = Location::class.java.getDeclaredMethod("hasSpeed")
            module.hook(mHasSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled && isTripSimulationActive) {
                        return true
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 5. Location.getBearing() matching Ltz0.r
        try {
            val mBearing = Location::class.java.getDeclaredMethod("getBearing")
            module.hook(mBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled && isTripSimulationActive) {
                        return 120.0f
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 6. Location.hasBearing() matching Ltz0.s
        try {
            val mHasBearing = Location::class.java.getDeclaredMethod("hasBearing")
            module.hook(mHasBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled && isTripSimulationActive) {
                        return true
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 7. LocationManager.getLastKnownLocation(String) matching Ltz0.t
        try {
            val mLastLoc = LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java)
            module.hook(mLastLoc).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val orig = chain.proceed() as? Location
                    if ((reentrancyDepth.get() ?: 0) > 0) return orig
                    syncLocationSettings()
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                        val prov = (chain.args.getOrNull(0) as? String) ?: "gps"
                        val loc = if (orig != null) Location(orig) else Location(prov)
                        loc.latitude = currentLat
                        loc.longitude = currentLng
                        loc.time = System.currentTimeMillis()
                        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        loc.accuracy = 8.0f
                        if (isTripSimulationActive) {
                            loc.speed = 4.5f
                            loc.bearing = 120.0f
                        }
                        return loc
                    }
                    return orig
                }
            })
        } catch (_: Throwable) {}
    }
}
