package dev.saned.assistant

data class IncomingOrder(
    val id: String,
    val restaurantName: String,
    val price: Double,
    val restLat: Double?,
    val restLng: Double?,
    val custLat: Double?,
    val custLng: Double?
)

sealed class FilterResult {
    object Accept : FilterResult()
    data class Reject(val reason: String) : FilterResult()
    data class LeaveForManual(val reason: String) : FilterResult()
}

class OrderFilter(private val settings: SettingsStore) {

    fun evaluate(order: IncomingOrder, driverLat: Double?, driverLng: Double?): FilterResult {
        // 1. فحص تفعيل القبول التلقائي
        if (!settings.isAutoAcceptEnabled) {
            return FilterResult.LeaveForManual("القبول التلقائي متوقف")
        }

        // 2. فحص السعر الأدنى
        if (order.price < settings.minPrice) {
            return FilterResult.Reject("السعر أقل من الأدنى (${order.price} < ${settings.minPrice})")
        }

        // 3. فحص المسافة إلى المطعم
        if (order.restLat != null && order.restLng != null) {
            val dLat = driverLat ?: LocationProvider.lastKnownLatitude
            val dLng = driverLng ?: LocationProvider.lastKnownLongitude

            if (dLat != null && dLng != null) {
                val distToRest = LocationProvider.calculateDistanceKm(dLat, dLng, order.restLat, order.restLng)
                if (distToRest > settings.maxRestaurantDistKm) {
                    return FilterResult.Reject("المطعم بعيد (%.1f كم > %.1f كم)".format(distToRest, settings.maxRestaurantDistKm))
                }
            } else {
                // هنا كان يحدث خطأ location unknown!
                if (!settings.acceptWhenLocationUnknown) {
                    return FilterResult.LeaveForManual("location unknown")
                }
                // إذا تم تفعيل التجاوز، يقبل الطلب مباشرة
            }
        }

        // 4. فحص مسافة التوصيل (من المطعم للعميل)
        if (order.restLat != null && order.restLng != null && order.custLat != null && order.custLng != null) {
            val deliveryDist = LocationProvider.calculateDistanceKm(order.restLat, order.restLng, order.custLat, order.custLng)
            if (deliveryDist > settings.maxCustomerDistKm) {
                return FilterResult.Reject("مسافة التوصيل بعيدة (%.1f كم > %.1f كم)".format(deliveryDist, settings.maxCustomerDistKm))
            }
        }

        return FilterResult.Accept
    }
}
