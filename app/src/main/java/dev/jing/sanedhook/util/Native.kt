package dev.jing.sanedhook.util

object Native {
    @JvmField val a: Native = this
    @Volatile var isLoaded: Boolean = true
        private set

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

    fun safeA(ctx: Any, pkg: String = "dev.jing.sanedhook"): Boolean = true

    fun safeB(kind: Int, orderId: Int, token: String, lang: String, driverCipher: String): Array<String> {
        val cleanTok = if (token.startsWith("Bearer ", ignoreCase = true)) token.substring(7).trim() else token.trim()
        val cleanLang = lang.trim().ifEmpty { "en" }
        val (method, url) = when (kind) {
  0 -> "POST" to "https://gateway.saned.io/api/v1/dispatch/$orderId"
  1 -> "PUT" to "https://gateway.saned.io/api/v1/dispatch/reject/$orderId"
  2 -> "GET" to "https://gateway.saned.io/api/v1/dispatch/active"
  3 -> "GET" to "https://gateway.saned.io/api/v1/dispatch-location/contact-number/$orderId"
  else -> return emptyArray()
        }
        val list = mutableListOf(
  method, url,
  "Authorization", "Bearer $cleanTok",
  "Accept-Language", cleanLang,
  "User-Agent", "okhttp/5.1.0"
        )
        if (driverCipher.isNotEmpty()) {
  list.add("x-unique-Identifier")
  list.add(driverCipher)
        }
        return list.toTypedArray()
    }

    fun safeC(ctx: Any, pkg: String, s1: String, s2: String, v: Long): Int = 1
    fun safeD(): String = FALLBACK_FCM_CLASS
    fun safeE(): String = FALLBACK_ORDER_ACTIVITY
    fun safeF(): String = FALLBACK_DB_NAME
    fun safeG(): String = FALLBACK_SQL_QUERY
    fun safeH(): String = FALLBACK_ENCRYPTED_PREFS
    fun safeI(): String = FALLBACK_TOKEN_KEY
    fun safeJ(): String = FALLBACK_LANG_KEY
    fun safeK(): Array<String> = FALLBACK_INTENT_KEYS
    fun safeL(): Array<String> = FALLBACK_FCM_KEYS
    fun safeM(): Array<String> = FALLBACK_POLL_KEYS
    fun safeN(): Array<String> = FALLBACK_SECURITY_CLASSES
    fun safeO(s: String): String = ""
    fun safeP(): Array<String> = FALLBACK_VERSION_PROFILES
    fun safeQ(): String = ""
    fun safeR(path: String): Boolean = true
    fun safeS(versionCode: Long): Int = when (versionCode) {
        40093L -> 0
        40099L, 4099L, 40104L -> 1
        40089L -> 2
        else -> 1
    }
    fun safeT(): Array<String> = FALLBACK_GUARD_CLASSES
    fun safeU(): Array<String> = FALLBACK_FEATURE_FLAGS
}
