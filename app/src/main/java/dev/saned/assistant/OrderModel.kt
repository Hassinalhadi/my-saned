package dev.saned.assistant

import org.json.JSONObject

/**
 * Data model matching Luf1; Dalvik bytecode structure from SanedAssistant:
 * a: id (Int)
 * c: reference (String)
 * d: status (String)
 * e: restaurant (String)
 * f: customerName (String)
 * g: customerPhone (String)
 * r: address (String)
 * l: price (Double)
 * bonus: bonus (Double)
 * n: currency (String)
 * o: cashAmount (Double)
 */
data class OrderModel(
    val id: Int = 0,
    val reference: String = "",
    val status: String = "ACCEPTED",
    val restaurant: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val address: String = "",
    val price: Double = 0.0,
    val bonus: Double = 0.0,
    val currency: String = "SAR",
    val cashAmount: Double = 0.0,
    val distanceKm: Double = 0.0,
    val time: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", if (id != 0) id else reference)
            put("reference", reference)
            put("status", status)
            put("restaurant", restaurant)
            put("customerName", customerName)
            put("customerPhone", customerPhone)
            put("address", address)
            put("price", price)
            put("bonus", bonus)
            put("currency", currency)
            put("cashAmount", cashAmount)
            put("distanceKm", distanceKm)
            put("time", time)
            put("timestamp", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): OrderModel {
            val rawId = json.opt("id")
            val intId = when (rawId) {
                is Number -> rawId.toInt()
                is String -> rawId.filter { it.isDigit() }.toIntOrNull() ?: 0
                else -> 0
            }
            return OrderModel(
                id = intId,
                reference = json.optString("reference", json.optString("order_number", json.optString("order_id", json.optString("id", "")))),
                status = json.optString("status", json.optString("state", "ACCEPTED")),
                restaurant = json.optString("restaurant", json.optString("store_name", json.optString("restaurant_name", ""))),
                customerName = json.optString("customerName", json.optString("customer_name", json.optString("client_name", ""))),
                customerPhone = json.optString("customerPhone", json.optString("customer_phone", json.optString("phone", ""))),
                address = json.optString("address", json.optString("customer_address", json.optString("delivery_address", ""))),
                price = json.optDouble("price", json.optDouble("delivery_cost", json.optDouble("delivery_fee", 0.0))),
                bonus = json.optDouble("bonus", json.optDouble("tip", 0.0)),
                currency = json.optString("currency", "SAR").ifEmpty { "SAR" },
                cashAmount = json.optDouble("cashAmount", json.optDouble("cash_amount", json.optDouble("cod_amount", 0.0))),
                distanceKm = json.optDouble("distanceKm", json.optDouble("distance", json.optDouble("km", 0.0))),
                time = json.optString("time", ""),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
