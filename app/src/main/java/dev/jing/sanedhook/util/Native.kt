package dev.jing.sanedhook.util

object Native {
    @JvmField
    val a: Native = this

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("shnative")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun a(ctx: Any, pkg: String): Boolean
    external fun b(kind: Int, orderId: Int, token: String, lang: String, driverCipher: String): Array<String>
    external fun c(ctx: Any, pkg: String, s1: String, s2: String, v: Long): Int
    external fun d(): String
    external fun e(): String
    external fun f(): String
    external fun g(): String
    external fun h(): String
    external fun i(): String
    external fun j(): String
    external fun k(): Array<String>
    external fun l(): Array<String>
    external fun m(): Array<String>
    external fun n(): Array<String>
    external fun o(s: String): String
    external fun p(): Array<String>
    external fun q(): String
    external fun r(path: String): Boolean
    external fun s(versionCode: Long): Int
    external fun t(): Array<String>
    external fun u(): Array<String>

    // =========================================================================
    // Exact Decrypted Constants from libshnative.so (SanedAssistant-98-7bac1ec)
    // Used automatically if JNI call throws or returns empty
    // =========================================================================

    private val SUPPORTED_VERSIONS = longArrayOf(40093L, 40099L, 40089L)

    private const val FALLBACK_FCM_CLASS = "net.saned.core.SanedFCMNotificationHandler"
    private const val FALLBACK_INCOMING_DISPATCH_ACTIVITY = "net.jahez.fleets.dispatch.accept.IncomingDispatchActivity"
    private const val FALLBACK_DISPATCHES_DB = "dispatches_db"
    private const val FALLBACK_DISPATCHES_SQL =
        "SELECT di.dispatchId, di.referenceId, di.status, v.vendorName, c.subscriberName, NULL, c.clientPhone, " +
        "l.pickupLatitude, l.pickupLongitude, l.destinationLatitude, l.destinationLongitude, l.distance, " +
        "p.deliveryPrice, p.collectionAmount, p.currencyCode, p.paymentMethod, " +
        "a.buildingName, a.buildingNumber, a.houseNumber, a.streetName, a.roadNumber, a.blockNumber, " +
        "a.apartmentNumber, a.floor, a.lane, a.officeName, a.region, a.additionalInformation " +
        "FROM dispatch_info di " +
        "LEFT JOIN vendor v ON v.dispatchId = di.dispatchId " +
        "LEFT JOIN client c ON c.dispatchId = di.dispatchId " +
        "LEFT JOIN location l ON l.dispatchId = di.dispatchId " +
        "LEFT JOIN payment p ON p.dispatchId = di.dispatchId " +
        "LEFT JOIN address a ON a.dispatchId = di.dispatchId"
    private const val FALLBACK_ENCRYPTED_PREFS_NAME = "ENCRYPTED_SANED_PREFERENCES"
    private const val FALLBACK_TOKEN_KEY = "token"
    private const val FALLBACK_LANGUAGE_KEY = "language"
    private const val FALLBACK_MAPS_API_KEY = "AIzaSyDngGva2vOywyZGjSvoGQqnIr-IC3v4FVE"

    // Lby1 (Native.k): Intent Extra keys for IncomingDispatchActivity (Ls20.e & Ls20.c & Lfs0.c)
    private val FALLBACK_INTENT_KEYS = arrayOf(
        "EXTRA_DISPATCH_REQUEST_ID",       // 0
        "EXTRA_DISPATCH_PICKUP_LATITUDE",  // 1
        "EXTRA_DISPATCH_PICKUP_LONGITUDE", // 2
        "EXTRA_DISPATCH_DEST_LATITUDE",    // 3
        "EXTRA_DISPATCH_DEST_LONGITUDE",   // 4
        "EXTRA_DELIVERY_PRICE",            // 5
        "EXTRA_DISPATCH_TYPE",             // 6
        "EXTRA_DISTANCE",                  // 7
        "EXTRA_COUNTER",                   // 8
        "EXTRA_SUBSCRIBER_NAME",           // 9
        "EXTRA_BONUS_AMOUNT",              // 10
        "EXTRA_CURRENCY"                   // 11
    )

    // Ldy1 (Native.l): Firebase FCM data map & JSON keys (Ls90.g, Ls90.b, Lb11.e)
    private val FALLBACK_FCM_KEYS = arrayOf(
        "requestId",         // 0
        "dispatchPickupLat", // 1
        "dispatchPickupLng", // 2
        "dispatchDestLat",   // 3
        "dispatchDestLng",   // 4
        "deliveryPrice",     // 5
        "dispatchType",      // 6
        "distance",          // 7
        "counter",           // 8
        "subscriberName",    // 9
        "vendorName",        // 10
        "restaurantName",    // 11
        "storeName",         // 12
        "bonus",             // 13
        "bonusAmount",       // 14
        "extraBonus",        // 15
        "currency",          // 16
        "currencyCode"       // 17
    )

    // Lgy1 (Native.m): Server polling & Contact API JSON keys (Lb11.d, Lb11.e, Ljw.a)
    private val FALLBACK_SERVER_JSON_KEYS = arrayOf(
        "dispatches",    // 0
        "referenceId",   // 1
        "pickup",        // 2
        "coordinates",   // 3
        "destination",   // 4
        "clientPhone",   // 5
        "contactNumber"  // 6
    )

    // Lcy1 (Native.n): Security & RootBeer classes (Lu80.y & Lu80.G)
    private val FALLBACK_SECURITY_CLASSES = arrayOf(
        "net.jahez.guardzilla.logging.domain.model.SecurityCallBacksState", // 0
        "com.scottyab.rootbeer.RootBeer",                                   // 1
        "com.scottyab.rootbeer.RootBeerNative"                              // 2
    )

    // Lfy1 (Native.p): 3 version profiles x 8 strings (Ley1)
    private val FALLBACK_VERSION_PROFILES = arrayOf(
        // Profile 0 (versionCode 40093)
        "jahez_fleets", "ggk.am", "ggk.P", "ggk.ar", "net.jahez.fleets.MainActivity", "ggk.z", "b", "loadLibrary0",
        // Profile 1 (versionCode 40099)
        "jahez_fleets", "oxxqbaxw.an", "oxxqbaxw.e", "oxxqbaxw.ao", "net.jahez.fleets.MainActivity", "oxxqbaxw.b", "a", "loadLibrary0",
        // Profile 2 (versionCode 40089)
        "jahez_fleets", "lrbppeks.U", "lrbppeks.b", "lrbppeks.ak", "net.jahez.fleets.MainActivity", "lrbppeks.am", "a", "loadLibrary0"
    )

    // Liy1.a (Native.t): 3 version profiles x 5 strings (Lhy1)
    private val FALLBACK_SHADOWARMOR_CLASSES = arrayOf(
        // Profile 0 (40093)
        "S86", "yM", "ta3", "xD", "cj6",
        // Profile 1 (40099)
        "tEa", "Ke0", "MV5", "XS", "wRa",
        // Profile 2 (40089)
        "A76", "gM", "B93", "fD", "Jh6"
    )

    // Liy1.b (Native.u): Feature flag keys (Lu80.v)
    private val FALLBACK_FEATURE_FLAGS = arrayOf(
        "rooted_device_check",
        "auto_clicker_check",
        "installation_source_check"
    )

    fun safeA(ctx: Any, pkg: String): Boolean {
        if (isLoaded) {
            try { return a(ctx, pkg) } catch (_: Throwable) {}
        }
        return true
    }

    fun safeC(ctx: Any, pkg: String, s1: String, s2: String, v: Long): Int {
        if (isLoaded) {
            try { return c(ctx, pkg, s1, s2, v) } catch (_: Throwable) {}
        }
        return 0
    }

    fun safeD(): String {
        if (isLoaded) {
            try {
                val res = d()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_FCM_CLASS
    }

    fun safeE(): String {
        if (isLoaded) {
            try {
                val res = e()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_INCOMING_DISPATCH_ACTIVITY
    }

    fun safeF(): String {
        if (isLoaded) {
            try {
                val res = f()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_DISPATCHES_DB
    }

    fun safeG(): String {
        if (isLoaded) {
            try {
                val res = g()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_DISPATCHES_SQL
    }

    fun safeH(): String {
        if (isLoaded) {
            try {
                val res = h()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_ENCRYPTED_PREFS_NAME
    }

    fun safeI(): String {
        if (isLoaded) {
            try {
                val res = i()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_TOKEN_KEY
    }

    fun safeJ(): String {
        if (isLoaded) {
            try {
                val res = j()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_LANGUAGE_KEY
    }

    fun safeK(): Array<String> {
        if (isLoaded) {
            try {
                val res = k()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_INTENT_KEYS
    }

    fun safeL(): Array<String> {
        if (isLoaded) {
            try {
                val res = l()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_FCM_KEYS
    }

    fun safeM(): Array<String> {
        if (isLoaded) {
            try {
                val res = m()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_SERVER_JSON_KEYS
    }

    fun safeN(): Array<String> {
        if (isLoaded) {
            try {
                val res = n()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_SECURITY_CLASSES
    }

    fun safeP(): Array<String> {
        if (isLoaded) {
            try {
                val res = p()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_VERSION_PROFILES
    }

    fun safeQ(): String {
        if (isLoaded) {
            try {
                val res = q()
                if (!res.isNullOrEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_MAPS_API_KEY
    }

    fun safeS(versionCode: Long): Int {
        if (isLoaded) {
            try {
                val res = s(versionCode)
                if (res >= 0) return res
            } catch (_: Throwable) {}
        }
        return SUPPORTED_VERSIONS.indexOfFirst { it == versionCode }
    }

    fun safeT(): Array<String> {
        if (isLoaded) {
            try {
                val res = t()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_SHADOWARMOR_CLASSES
    }

    fun safeU(): Array<String> {
        if (isLoaded) {
            try {
                val res = u()
                if (res.isNotEmpty()) return res
            } catch (_: Throwable) {}
        }
        return FALLBACK_FEATURE_FLAGS
    }
}
