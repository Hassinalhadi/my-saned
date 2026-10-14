package dev.jing.sanedhook.util

object Native {
    @JvmField val a: Native = this
    @Volatile var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try { System.loadLibrary("shnative"); true } catch (_: Throwable) { false }
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

    private const val FALLBACK_FCM_CLASS = "net.saned.core.SanedFCMNotificationHandler"
    private const val FALLBACK_ORDER_ACTIVITY = "net.jahez.fleets.dispatch.accept.IncomingDispatchActivity"
    private const val FALLBACK_DB_NAME = "dispatches_db"
    private const val FALLBACK_SQL_QUERY =
        "SELECT di.dispatchId, di.referenceId, di.status, v.vendorName, c.subscriberName, NULL, c.clientPhone, " +
        "l.pickupLatitude, l.pickupLongitude, l.destinationLatitude, l.destinationLongitude, l.distance, " +
        "p.deliveryPrice, p.collectionAmount, p.currencyCode, p.paymentMethod, a.buildingName, a.buildingNumber, " +
        "a.houseNumber, a.streetName, a.roadNumber, a.blockNumber, a.apartmentNumber, a.floor, a.lane, " +
        "a.officeName, a.region, a.additionalInformation FROM dispatch_info di " +
        "LEFT JOIN vendor v ON v.dispatchId = di.dispatchId LEFT JOIN client c ON c.dispatchId = di.dispatchId " +
        "LEFT JOIN location l ON l.dispatchId = di.dispatchId LEFT JOIN payment p ON p.dispatchId = di.dispatchId " +
        "LEFT JOIN address a ON a.dispatchId = di.dispatchId"
    private const val FALLBACK_ENCRYPTED_PREFS = "ENCRYPTED_SANED_PREFERENCES"
    private const val FALLBACK_TOKEN_KEY = "token"
    private const val FALLBACK_LANG_KEY = "language"

    private val FALLBACK_INTENT_KEYS = arrayOf(
        "EXTRA_DISPATCH_REQUEST_ID", "EXTRA_DISPATCH_PICKUP_LATITUDE", "EXTRA_DISPATCH_PICKUP_LONGITUDE",
        "EXTRA_DISPATCH_DEST_LATITUDE", "EXTRA_DISPATCH_DEST_LONGITUDE", "EXTRA_DELIVERY_PRICE",
        "EXTRA_DISPATCH_TYPE", "EXTRA_DISTANCE", "EXTRA_COUNTER", "EXTRA_SUBSCRIBER_NAME",
        "EXTRA_BONUS_AMOUNT", "EXTRA_CURRENCY"
    )
    private val FALLBACK_FCM_KEYS = arrayOf(
        "requestId", "dispatchPickupLat", "dispatchPickupLng", "dispatchDestLat", "dispatchDestLng",
        "deliveryPrice", "dispatchType", "distance", "counter", "subscriberName", "vendorName",
        "restaurantName", "storeName", "bonus", "bonusAmount", "extraBonus", "currency", "currencyCode"
    )
    private val FALLBACK_POLL_KEYS = arrayOf(
        "dispatches", "referenceId", "pickup", "coordinates", "destination", "clientPhone", "contactNumber"
    )
    private val FALLBACK_SECURITY_CLASSES = arrayOf(
        "net.jahez.guardzilla.logging.domain.model.SecurityCallBacksState",
        "com.scottyab.rootbeer.RootBeer", "com.scottyab.rootbeer.RootBeerNative"
    )
    private val FALLBACK_VERSION_PROFILES = arrayOf(
        "jahez_fleets", "ggk.am", "ggk.P", "ggk.ar", "net.jahez.fleets.MainActivity", "ggk.z", "b", "loadLibrary0",
        "jahez_fleets", "oxxqbaxw.an", "oxxqbaxw.e", "oxxqbaxw.ao", "net.jahez.fleets.MainActivity", "oxxqbaxw.b", "a", "loadLibrary0",
        "jahez_fleets", "lrbppeks.U", "lrbppeks.b", "lrbppeks.ak", "net.jahez.fleets.MainActivity", "lrbppeks.am", "a", "loadLibrary0"
    )
    private val FALLBACK_GUARD_CLASSES = arrayOf(
        "S86", "yM", "ta3", "xD", "cj6", "tEa", "Ke0", "MV5", "XS", "wRa", "A76", "gM", "B93", "fD", "Jh6"
    )
    private val FALLBACK_FEATURE_FLAGS = arrayOf(
        "rooted_device_check", "auto_clicker_check", "installation_source_check"
    )

    fun safeA(ctx: Any, pkg: String = "dev.jing.sanedhook"): Boolean = try { if (isLoaded) a(ctx, pkg) else false } catch (_: Throwable) { false }
    fun safeB(kind: Int, orderId: Int, token: String, lang: String, driverCipher: String): Array<String> = try { if (isLoaded) b(kind, orderId, token, lang, driverCipher) else emptyArray() } catch (_: Throwable) { emptyArray() }
    fun safeC(ctx: Any, pkg: String, s1: String, s2: String, v: Long): Int = try { if (isLoaded) c(ctx, pkg, s1, s2, v) else -1 } catch (_: Throwable) { -1 }
    fun safeD(): String = try { if (isLoaded) d().ifEmpty { FALLBACK_FCM_CLASS } else FALLBACK_FCM_CLASS } catch (_: Throwable) { FALLBACK_FCM_CLASS }
    fun safeE(): String = try { if (isLoaded) e().ifEmpty { FALLBACK_ORDER_ACTIVITY } else FALLBACK_ORDER_ACTIVITY } catch (_: Throwable) { FALLBACK_ORDER_ACTIVITY }
    fun safeF(): String = try { if (isLoaded) f().ifEmpty { FALLBACK_DB_NAME } else FALLBACK_DB_NAME } catch (_: Throwable) { FALLBACK_DB_NAME }
    fun safeG(): String = try { if (isLoaded) g().ifEmpty { FALLBACK_SQL_QUERY } else FALLBACK_SQL_QUERY } catch (_: Throwable) { FALLBACK_SQL_QUERY }
    fun safeH(): String = try { if (isLoaded) h().ifEmpty { FALLBACK_ENCRYPTED_PREFS } else FALLBACK_ENCRYPTED_PREFS } catch (_: Throwable) { FALLBACK_ENCRYPTED_PREFS }
    fun safeI(): String = try { if (isLoaded) i().ifEmpty { FALLBACK_TOKEN_KEY } else FALLBACK_TOKEN_KEY } catch (_: Throwable) { FALLBACK_TOKEN_KEY }
    fun safeJ(): String = try { if (isLoaded) j().ifEmpty { FALLBACK_LANG_KEY } else FALLBACK_LANG_KEY } catch (_: Throwable) { FALLBACK_LANG_KEY }
    fun safeK(): Array<String> = try { if (isLoaded) k().takeIf { it.isNotEmpty() } ?: FALLBACK_INTENT_KEYS else FALLBACK_INTENT_KEYS } catch (_: Throwable) { FALLBACK_INTENT_KEYS }
    fun safeL(): Array<String> = try { if (isLoaded) l().takeIf { it.isNotEmpty() } ?: FALLBACK_FCM_KEYS else FALLBACK_FCM_KEYS } catch (_: Throwable) { FALLBACK_FCM_KEYS }
    fun safeM(): Array<String> = try { if (isLoaded) m().takeIf { it.isNotEmpty() } ?: FALLBACK_POLL_KEYS else FALLBACK_POLL_KEYS } catch (_: Throwable) { FALLBACK_POLL_KEYS }
    fun safeN(): Array<String> = try { if (isLoaded) n().takeIf { it.isNotEmpty() } ?: FALLBACK_SECURITY_CLASSES else FALLBACK_SECURITY_CLASSES } catch (_: Throwable) { FALLBACK_SECURITY_CLASSES }
    fun safeO(s: String): String = try { if (isLoaded) o(s) else "" } catch (_: Throwable) { "" }
    fun safeP(): Array<String> = try { if (isLoaded) p().takeIf { it.isNotEmpty() } ?: FALLBACK_VERSION_PROFILES else FALLBACK_VERSION_PROFILES } catch (_: Throwable) { FALLBACK_VERSION_PROFILES }
    fun safeQ(): String = try { if (isLoaded) q() else "" } catch (_: Throwable) { "" }
    fun safeR(path: String): Boolean = try { if (isLoaded) r(path) else true } catch (_: Throwable) { true }
    fun safeS(versionCode: Long): Int = try {
        if (isLoaded) s(versionCode) else when (versionCode) { 40093L -> 0; 40099L -> 1; 40089L -> 2; else -> -1 }
    } catch (_: Throwable) {
        when (versionCode) { 40093L -> 0; 40099L -> 1; 40089L -> 2; else -> -1 }
    }
    fun safeT(): Array<String> = try { if (isLoaded) t().takeIf { it.isNotEmpty() } ?: FALLBACK_GUARD_CLASSES else FALLBACK_GUARD_CLASSES } catch (_: Throwable) { FALLBACK_GUARD_CLASSES }
    fun safeU(): Array<String> = try { if (isLoaded) u().takeIf { it.isNotEmpty() } ?: FALLBACK_FEATURE_FLAGS else FALLBACK_FEATURE_FLAGS } catch (_: Throwable) { FALLBACK_FEATURE_FLAGS }
}
