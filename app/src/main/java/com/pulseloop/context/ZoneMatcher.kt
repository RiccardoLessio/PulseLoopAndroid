package com.pulseloop.context

import com.pulseloop.data.entity.ZoneEntity
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place ring-hub detected on its own (`GET /api/v1/places`), cached on the phone. */
@Serializable
data class AutoPlace(
    val id: Int,
    val name: String,
    val autoLabel: String? = null,
    val zoneId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val visitCount: Int = 0,
    val totalMinutes: Int = 0,
)

/** Where the phone is, in the categories the label suggestion understands. */
data class PlaceMatch(val name: String, val category: String, val fromZone: Boolean)

/**
 * Point-in-zone matching. User zones always win over ring-hub's auto places; between overlapping
 * zones the nearest centre wins, the same rule as ringhub.places.zone_containing.
 */
object ZoneMatcher {
    const val AUTO_PLACE_RADIUS_M = 150.0
    const val CATEGORY_HOME = "home"
    const val CATEGORY_WORK = "work"
    const val CATEGORY_GYM = "gym"
    const val CATEGORY_CUSTOM = "custom"

    val CATEGORY_LABELS = linkedMapOf(
        CATEGORY_HOME to "Casa",
        CATEGORY_WORK to "Lavoro",
        CATEGORY_GYM to "Palestra",
        CATEGORY_CUSTOM to "Altro",
    )

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    fun zoneContaining(lat: Double, lon: Double, zones: List<ZoneEntity>): ZoneEntity? =
        zones.asSequence()
            .filter { it.deletedAt == null }
            .map { it to distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { (zone, d) -> d <= zone.radiusMeters }
            .minByOrNull { it.second }
            ?.first

    /** Auto labels the server assigns, mapped to zone categories. */
    fun categoryOfAutoLabel(label: String?): String? = when (label) {
        "Casa" -> CATEGORY_HOME
        "Lavoro" -> CATEGORY_WORK
        else -> null
    }

    fun match(lat: Double, lon: Double, zones: List<ZoneEntity>, places: List<AutoPlace>): PlaceMatch? {
        zoneContaining(lat, lon, zones)?.let { return PlaceMatch(it.name, it.category, fromZone = true) }
        val place = places.asSequence()
            .filter { it.zoneId == null }
            .map { it to distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= AUTO_PLACE_RADIUS_M }
            .minByOrNull { it.second }
            ?.first ?: return null
        return PlaceMatch(place.name, categoryOfAutoLabel(place.autoLabel) ?: CATEGORY_CUSTOM, fromZone = false)
    }
}
