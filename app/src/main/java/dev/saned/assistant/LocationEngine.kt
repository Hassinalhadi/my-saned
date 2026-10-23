package dev.saned.assistant

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

object LocationEngine {

    @Volatile var isMasterRunning: Boolean = true
    @Volatile var isFakeLocationEnabled: Boolean = false
    @Volatile var isFixLocationUnknownEnabled: Boolean = false
    @Volatile var isTripSimulationActive: Boolean = false
    @Volatile var simulatedSpeedKmh: Float = 45.0f
    @Volatile var currentBearing: Float = 0.0f
    @Volatile var currentAccuracy: Float = 4.2f
    @Volatile var currentLat: Double = 0.0
    @Volatile var currentLng: Double = 0.0
    @Volatile var lastRealLat: Double = 0.0
    @Volatile var lastRealLng: Double = 0.0

    private var remotePrefs: SharedPreferences? = null
    private val reentrancyDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    @Volatile private var isSeeded = false
    private val isActivePusherRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cached reflection fields & methods (resolved ONCE to eliminate reflection overhead & GC pauses on the UI thread)
    private val cachedMockField1: Field? by lazy {
        try { Location::class.java.getDeclaredField("mIsFromMockProvider").apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val cachedMockField2: Field? by lazy {
        try { Location::class.java.getDeclaredField("mMock").apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val cachedFieldsMaskField: Field? by lazy {
        try { Location::class.java.getDeclaredField("mFieldsMask").apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val gmsListenerMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val gmsCallbackMethodCache = ConcurrentHashMap<Class<*>, Method>()

    // Captured Android LocationListeners & Google Play Services FusedLocation callbacks inside net.jahez.fleets
    private val capturedLocationListeners = CopyOnWriteArraySet<LocationListener>()
    private val capturedGmsLocationCallbacks = CopyOnWriteArraySet<Any>()
    private val capturedGmsLocationListeners = CopyOnWriteArraySet<Any>()
    @Volatile private var gmsLocationResultCreateMethod: Method? = null

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
            val pMod = p.getLong("last_settings_mod_ms", 0L)
            if (OrderInterceptor.lastLocalOverlayModMs > 0L && pMod < OrderInterceptor.lastLocalOverlayModMs) {
                return
            }
            isMasterRunning = p.getBoolean("master_running", p.getBoolean("enabled", isMasterRunning))
            isFakeLocationEnabled = p.getBoolean("fake_location_enabled", isFakeLocationEnabled)
            isFixLocationUnknownEnabled = p.getBoolean("fix_location_unknown", p.getBoolean("high_accuracy_location", isFixLocationUnknownEnabled))
            isTripSimulationActive = p.getBoolean("route_sim_active", isTripSimulationActive)
            simulatedSpeedKmh = p.getFloat("route_sim_speed_kmh", simulatedSpeedKmh).coerceIn(1.0f, 220.0f)
            currentBearing = p.getFloat("route_sim_bearing", currentBearing)
            val lat = p.getString("fake_lat", null)?.toDoubleOrNull()
            val lng = p.getString("fake_lng", null)?.toDoubleOrNull()
            if (lat != null && !lat.isNaN() && lat != 0.0) {
                if (currentLat != 0.0 && currentLng != 0.0 && (lat != currentLat || (lng != null && lng != currentLng))) {
                    val computedBrg = calculateBearing(currentLat, currentLng, lat, lng ?: currentLng)
                    if (computedBrg > 0f) currentBearing = computedBrg
                }
                currentLat = lat
            }
            if (lng != null && !lng.isNaN() && lng != 0.0) currentLng = lng
        }
    }

    fun applySettingsBundle(b: Bundle) {
        val bMod = b.getLong("last_settings_mod_ms", 0L)
        if (OrderInterceptor.lastLocalOverlayModMs > 0L && bMod < OrderInterceptor.lastLocalOverlayModMs) {
            return
        }
        isMasterRunning = b.getBoolean("master_running", b.getBoolean("enabled", isMasterRunning))
        isFakeLocationEnabled = b.getBoolean("fake_location_enabled", isFakeLocationEnabled)
        isFixLocationUnknownEnabled = b.getBoolean("fix_location_unknown", isFixLocationUnknownEnabled)
        isTripSimulationActive = b.getBoolean("route_sim_active", isTripSimulationActive)
        val spd = b.getFloat("route_sim_speed_kmh", simulatedSpeedKmh)
        if (!spd.isNaN() && spd > 0f) simulatedSpeedKmh = spd.coerceIn(1.0f, 220.0f)
        val brg = b.getFloat("route_sim_bearing", currentBearing)
        if (!brg.isNaN()) currentBearing = brg
        val lat = b.getString("fake_lat", null)?.toDoubleOrNull()
        val lng = b.getString("fake_lng", null)?.toDoubleOrNull()
        if (lat != null && !lat.isNaN() && lat != 0.0) {
            if (currentLat != 0.0 && currentLng != 0.0 && (lat != currentLat || (lng != null && lng != currentLng))) {
                val computedBrg = calculateBearing(currentLat, currentLng, lat, lng ?: currentLng)
                if (computedBrg > 0f) currentBearing = computedBrg
            }
            currentLat = lat
        }
        if (lng != null && !lng.isNaN() && lng != 0.0) currentLng = lng
    }

    fun syncLocationSettings() {
        syncFromPrefs()
        try {
            val ctx = DiagnosticLogger.resolveAnyContext()
            if (ctx != null) {
                val b = ctx.contentResolver.call(Uri.parse("content://dev.jing.sanedhook.XposedService"), "getSettings", null, null)
                if (b != null && !b.isEmpty) {
                    applySettingsBundle(b)
                }
            }
        } catch (_: Throwable) {}
        try {
            val f = File("/data/local/tmp/saned_config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                isMasterRunning = json.optBoolean("master_running", json.optBoolean("enabled", isMasterRunning))
                isFakeLocationEnabled = json.optBoolean("fake_location_enabled", isFakeLocationEnabled)
                isFixLocationUnknownEnabled = json.optBoolean("fix_location_unknown", isFixLocationUnknownEnabled)
                isTripSimulationActive = json.optBoolean("route_sim_active", isTripSimulationActive)
                val spd = json.optDouble("route_sim_speed_kmh", simulatedSpeedKmh.toDouble())
                if (!spd.isNaN() && spd > 0.0) simulatedSpeedKmh = spd.toFloat().coerceIn(1.0f, 220.0f)
                val brg = json.optDouble("route_sim_bearing", currentBearing.toDouble())
                if (!brg.isNaN()) currentBearing = brg.toFloat()
                val lat = json.optDouble("fake_lat", currentLat)
                val lng = json.optDouble("fake_lng", currentLng)
                if (!lat.isNaN() && lat != 0.0) currentLat = lat
                if (!lng.isNaN() && lng != 0.0) currentLng = lng
            }
        } catch (_: Throwable) {}
    }

    fun updateLiveRouteState(lat: Double, lng: Double, speedKmh: Float, bearing: Float, simActive: Boolean) {
        if (!lat.isNaN() && lat != 0.0 && !lng.isNaN() && lng != 0.0) {
            if (currentLat != 0.0 && currentLng != 0.0 && bearing == 0.0f && (lat != currentLat || lng != currentLng)) {
                currentBearing = calculateBearing(currentLat, currentLng, lat, lng)
            } else if (!bearing.isNaN() && bearing >= 0.0f) {
                currentBearing = bearing
            }
            currentLat = lat
            currentLng = lng
        }
        if (!speedKmh.isNaN() && speedKmh > 0.0f) {
            simulatedSpeedKmh = speedKmh.coerceIn(1.0f, 220.0f)
        }
        isTripSimulationActive = simActive
        if (simActive) isFakeLocationEnabled = true
        pushSpoofedLocationToAllCallbacks()
    }

    private fun getEffectiveSpeedMps(): Float {
        if (!isTripSimulationActive) return 0.0f
        val baseMps = (simulatedSpeedKmh / 3.6f).coerceAtLeast(0.5f)
        return try {
            val jitter = ThreadLocalRandom.current().nextDouble(0.97, 1.03).toFloat()
            baseMps * jitter
        } catch (_: Throwable) {
            baseMps
        }
    }

    private fun getRealisticAccuracy(): Float {
        return try {
            ThreadLocalRandom.current().nextDouble(3.4, 5.6).toFloat()
        } catch (_: Throwable) {
            4.2f
        }
    }

    private fun getMicroJitteredLat(): Double {
        if (currentLat == 0.0) return 0.0
        return try {
            if (isTripSimulationActive) currentLat
            else currentLat + ThreadLocalRandom.current().nextDouble(-0.0000025, 0.0000025)
        } catch (_: Throwable) {
            currentLat
        }
    }

    private fun getMicroJitteredLng(): Double {
        if (currentLng == 0.0) return 0.0
        return try {
            if (isTripSimulationActive) currentLng
            else currentLng + ThreadLocalRandom.current().nextDouble(-0.0000025, 0.0000025)
        } catch (_: Throwable) {
            currentLng
        }
    }

    fun buildSpoofedLocation(provider: String = "gps", template: Location? = null): Location {
        val loc = if (template != null) Location(template) else Location(provider)
        val depth = reentrancyDepth.get() ?: 0
        reentrancyDepth.set(depth + 1)
        try {
            loc.provider = provider
            loc.latitude = getMicroJitteredLat()
            loc.longitude = getMicroJitteredLng()
            loc.altitude = 612.0
            loc.accuracy = getRealisticAccuracy()
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.verticalAccuracyMeters = 3.5f
                loc.speedAccuracyMetersPerSecond = 0.4f
                loc.bearingAccuracyDegrees = 5.0f
            }
            if (isTripSimulationActive) {
                loc.speed = getEffectiveSpeedMps()
                loc.bearing = currentBearing
            } else {
                loc.speed = 0.0f
                loc.bearing = currentBearing
            }
            stripMockFlagOnLocation(loc)
        } catch (_: Throwable) {
        } finally {
            reentrancyDepth.set(depth)
        }
        return loc
    }

    private fun stripMockFlagOnLocation(loc: Location) {
        try {
            cachedMockField1?.setBoolean(loc, false)
            cachedMockField2?.setBoolean(loc, false)
            cachedFieldsMaskField?.let { fFields ->
                val mask = fFields.getInt(loc)
                fFields.setInt(loc, mask and 0xFFFFFFEF.toInt())
            }
        } catch (_: Throwable) {}
    }

    private fun recordRealLocation(loc: Location?) {
        if (loc == null) return
        val depth = reentrancyDepth.get() ?: 0
        reentrancyDepth.set(depth + 1)
        try {
            val lat = loc.latitude
            val lng = loc.longitude
            if (lat != 0.0 && lng != 0.0 && (lat != currentLat || lng != currentLng)) {
                lastRealLat = lat
                lastRealLng = lng
            }
        } catch (_: Throwable) {
        } finally {
            reentrancyDepth.set(depth)
        }
    }

    fun startActiveLocationPusher() {
        if (!isActivePusherRunning.compareAndSet(false, true)) return
        val tickRunnable = object : Runnable {
            override fun run() {
                try {
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                        pushSpoofedLocationToAllCallbacks()
                    }
                } catch (_: Throwable) {}
                val intervalMs = if (isTripSimulationActive) 500L else 2500L
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        mainHandler.post(tickRunnable)
    }

    fun pushSpoofedLocationToAllCallbacks() {
        if (!isMasterRunning || !isFakeLocationEnabled || currentLat == 0.0 || currentLng == 0.0) return
        if (capturedLocationListeners.isEmpty() && capturedGmsLocationListeners.isEmpty() && capturedGmsLocationCallbacks.isEmpty()) return
        mainHandler.post {
            try {
                val gpsLoc = buildSpoofedLocation("gps")
                val fusedLoc = buildSpoofedLocation("fused")

                // 1. Push to all captured Android LocationListeners
                for (listener in capturedLocationListeners) {
                    try {
                        listener.onLocationChanged(gpsLoc)
                    } catch (_: Throwable) {}
                }

                // 2. Push to all captured Google Play Services FusedLocation Listeners (using cached Method)
                for (gmsListener in capturedGmsLocationListeners) {
                    try {
                        val cls = gmsListener.javaClass
                        val m = gmsListenerMethodCache[cls] ?: cls.getMethod("onLocationChanged", Location::class.java).also {
                            gmsListenerMethodCache[cls] = it
                        }
                        m.invoke(gmsListener, fusedLoc)
                    } catch (_: Throwable) {}
                }

                // 3. Push to all captured Google Play Services FusedLocation LocationCallbacks (using cached Method)
                if (capturedGmsLocationCallbacks.isNotEmpty()) {
                    for (cb in capturedGmsLocationCallbacks) {
                        try {
                            val createMethod = gmsLocationResultCreateMethod ?: run {
                                val lrCls = Class.forName("com.google.android.gms.location.LocationResult", false, cb.javaClass.classLoader)
                                lrCls.getMethod("create", List::class.java).also { gmsLocationResultCreateMethod = it }
                            }
                            val locResult = createMethod.invoke(null, listOf(fusedLoc))
                            if (locResult != null) {
                                val cbCls = cb.javaClass
                                val mOnRes = gmsCallbackMethodCache[cbCls] ?: cbCls.getMethod("onLocationResult", locResult.javaClass).also {
                                    gmsCallbackMethodCache[cbCls] = it
                                }
                                mOnRes.invoke(cb, locResult)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun seedLocationManager(context: Context) {
        startActiveLocationPusher()
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
                        if (!isFakeLocationEnabled) recordRealLocation(location)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    lm.requestLocationUpdates("passive", 2000L, 0f, passiveListener, Looper.getMainLooper())
                } catch (_: Throwable) {}

                if (isMasterRunning && isFixLocationUnknownEnabled && enabledProviders.isNotEmpty()) {
                    val activeListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!isFakeLocationEnabled) recordRealLocation(location)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    for (prov in enabledProviders) {
                        try {
                            lm.requestLocationUpdates(prov, 3000L, 0f, activeListener, Looper.getMainLooper())
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

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val brg = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return if (brg.isNaN()) 0.0f else brg.toFloat()
    }

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        startActiveLocationPusher()

        // 1. Anti-Mock Detection Cloaking: Location.isFromMockProvider() & Location.isMock() -> ALWAYS false
        for (mockMethodName in listOf("isFromMockProvider", "isMock")) {
            try {
                val mMock = Location::class.java.getDeclaredMethod(mockMethodName)
                module.hook(mMock).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return false
                    }
                })
            } catch (_: Throwable) {}
        }

        // 2. Hook Location.getLatitude() & getLongitude()
        try {
            val mLat = Location::class.java.getDeclaredMethod("getLatitude")
            module.hook(mLat).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0) {
                        return getMicroJitteredLat()
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        try {
            val mLng = Location::class.java.getDeclaredMethod("getLongitude")
            module.hook(mLng).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    if (isMasterRunning && isFakeLocationEnabled && currentLng != 0.0) {
                        return getMicroJitteredLng()
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook Location.getSpeed(), hasSpeed(), getBearing(), hasBearing(), getAccuracy()
        try {
            val mSpeed = Location::class.java.getDeclaredMethod("getSpeed")
            module.hook(mSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    if (isMasterRunning && isFakeLocationEnabled) {
                        return getEffectiveSpeedMps()
                    }
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        try {
            val mHasSpeed = Location::class.java.getDeclaredMethod("hasSpeed")
            module.hook(mHasSpeed).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled) return true
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        try {
            val mBearing = Location::class.java.getDeclaredMethod("getBearing")
            module.hook(mBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    if (isMasterRunning && isFakeLocationEnabled) return currentBearing
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        try {
            val mHasBearing = Location::class.java.getDeclaredMethod("hasBearing")
            module.hook(mHasBearing).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (isMasterRunning && isFakeLocationEnabled) return true
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        try {
            val mAcc = Location::class.java.getDeclaredMethod("getAccuracy")
            module.hook(mAcc).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if ((reentrancyDepth.get() ?: 0) > 0) return chain.proceed()
                    if (isMasterRunning && isFakeLocationEnabled) return getRealisticAccuracy()
                    return chain.proceed()
                }
            })
        } catch (_: Throwable) {}

        // 4. Hook LocationManager.getLastKnownLocation & requestLocationUpdates
        try {
            val mLastLoc = LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java)
            module.hook(mLastLoc).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val orig = chain.proceed() as? Location
                    if ((reentrancyDepth.get() ?: 0) > 0) return orig
                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                        val prov = (chain.args.getOrNull(0) as? String) ?: "gps"
                        return buildSpoofedLocation(prov, orig)
                    }
                    return orig
                }
            })
        } catch (_: Throwable) {}

        try {
            for (m in LocationManager::class.java.declaredMethods) {
                if (m.name == "requestLocationUpdates") {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            for (arg in chain.args) {
                                if (arg is LocationListener) {
                                    capturedLocationListeners.add(arg)
                                    if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                                        mainHandler.post {
                                            try { arg.onLocationChanged(buildSpoofedLocation("gps")) } catch (_: Throwable) {}
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

        // 5. Hook Google Play Services FusedLocationProviderClient & LocationResult
        try {
            val lrCls = Class.forName("com.google.android.gms.location.LocationResult", false, classLoader)
            for (m in lrCls.declaredMethods) {
                if (m.name == "getLastLocation" && m.parameterCount == 0) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val orig = chain.proceed() as? Location
                            if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                                return buildSpoofedLocation("fused", orig)
                            }
                            return orig
                        }
                    })
                } else if (m.name == "getLocations" && m.parameterCount == 0) {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                                return listOf(buildSpoofedLocation("fused"))
                            }
                            return chain.proceed()
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        for (fusedClsName in listOf(
            "com.google.android.gms.location.FusedLocationProviderClient",
            "com.google.android.gms.internal.location.zzbp",
            "com.google.android.gms.internal.location.zzau"
        )) {
            try {
                val fCls = Class.forName(fusedClsName, false, classLoader)
                for (m in fCls.declaredMethods) {
                    if (m.name == "requestLocationUpdates") {
                        module.hook(m).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                for (arg in chain.args) {
                                    if (arg == null) continue
                                    val clsName = arg.javaClass.name
                                    val superName = arg.javaClass.superclass?.name ?: ""
                                    if (superName.contains("LocationCallback") || clsName.contains("LocationCallback")) {
                                        capturedGmsLocationCallbacks.add(arg)
                                    } else if (arg.javaClass.interfaces.any { it.name.contains("LocationListener") }) {
                                        capturedGmsLocationListeners.add(arg)
                                    }
                                }
                                val res = chain.proceed()
                                if (isMasterRunning && isFakeLocationEnabled && currentLat != 0.0 && currentLng != 0.0) {
                                    pushSpoofedLocationToAllCallbacks()
                                }
                                return res
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
