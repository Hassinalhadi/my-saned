package dev.saned.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager

object LocationProvider {
    @Volatile
    var lastKnownLatitude: Double? = 24.7136   // افتراضي: الرياض
    @Volatile
    var lastKnownLongitude: Double? = 46.6753

    @SuppressLint("MissingPermission")
    fun updateLocation(context: Context) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                lastKnownLatitude = loc.latitude
                lastKnownLongitude = loc.longitude
            }
        } catch (e: Exception) {
            // صامت
        }
    }

    // حساب المسافة الدقيقة بين نقطتين بالكيلومتر عبر Haversine Formula
    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // نصف قطر الأرض
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
