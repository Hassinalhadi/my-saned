package dev.saned.assistant

import org.json.JSONObject

/**
 * Data model matching Luf1; Dalvik bytecode structure from SanedAssistant:
 * Supports both Active/Accepted orders and Missed/Rejected/Manual orders (as shown in Orders tab).
 */
data class OrderModel(
    val id: Int = 0,
    val reference: String = "",
    val status: String = "ACCEPTED",
    val restaurant: String = "",
    val pickupAddress: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val address: String = "",
    val price: Double = 0.0,
    val bonus: Double = 0.0,
    val currency: String = "SAR",
    val cashAmount: Double = 0.0,
    val distanceKm: Double = 0.0,
    val customerDistanceKm: Double = 0.0,
    val decisionReason: String = "",
    val isMissedOrRejected: Boolean = status.uppercase().let { s ->
        s.contains("REJECT") || s.contains("MISSED") || s.contains("FAIL") ||
        s.contains("SKIP") || s.contains("MANUAL") || s.contains("DRY") ||
        s.contains("رفض") || s.contains("فائت") || s.contains("تخطي") || s.contains("فشل")
    },
    val time: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", if (id != 0) id else reference)
            put("reference", reference)
            put("status", status)
            put("restaurant", restaurant)
            put("pickupAddress", pickupAddress)
            put("customerName", customerName)
            put("customerPhone", customerPhone)
            put("address", address)
            put("price", price)
            put("bonus", bonus)
            put("currency", currency)
            put("cashAmount", cashAmount)
            put("distanceKm", distanceKm)
            put("customerDistanceKm", customerDistanceKm)
            put("decisionReason", decisionReason)
            put("isMissedOrRejected", isMissedOrRejected)
            put("time", time)
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): OrderModel {
            val rawId = json.opt("id")
            val intId = when (rawId) {
                is Number -> rawId.toInt()
                is String -> rawId.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
                else -> 0
            }
            val parsedStatus = json.optString("status", json.optString("state", "ACCEPTED"))
            val defaultMissed = parsedStatus.uppercase().let { s ->
                s.contains("REJECT") || s.contains("MISSED") || s.contains("FAIL") ||
                s.contains("SKIP") || s.contains("MANUAL") || s.contains("DRY") ||
                s.contains("رفض") || s.contains("فائت") || s.contains("تخطي") || s.contains("فشل")
            }
            return OrderModel(
                id = intId,
                reference = json.optString("reference", json.optString("order_number", json.optString("order_id", json.optString("id", "")))),
                status = parsedStatus,
                restaurant = json.optString("restaurant", json.optString("store_name", json.optString("restaurant_name", ""))),
                pickupAddress = json.optString("pickupAddress", json.optString("pickup_address", "")),
                customerName = json.optString("customerName", json.optString("customer_name", json.optString("client_name", ""))),
                customerPhone = json.optString("customerPhone", json.optString("customer_phone", json.optString("phone", ""))),
                address = json.optString("address", json.optString("customer_address", json.optString("delivery_address", ""))),
                price = json.optDouble("price", json.optDouble("delivery_cost", json.optDouble("delivery_fee", 0.0))),
                bonus = json.optDouble("bonus", json.optDouble("tip", 0.0)),
                currency = json.optString("currency", "SAR").ifEmpty { "SAR" },
                cashAmount = json.optDouble("cashAmount", json.optDouble("cash_amount", json.optDouble("cod_amount", 0.0))),
                distanceKm = json.optDouble("distanceKm", json.optDouble("distance", json.optDouble("km", 0.0))),
                customerDistanceKm = json.optDouble("customerDistanceKm", json.optDouble("customer_distance", 0.0)),
                decisionReason = json.optString("decisionReason", ""),
                isMissedOrRejected = json.optBoolean("isMissedOrRejected", defaultMissed),
                time = json.optString("time", ""),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
