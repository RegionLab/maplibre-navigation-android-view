package org.maplibre.navigation.android.navigation.ui.v5.map

import android.location.Location
import org.maplibre.android.location.LocationComponent
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Removes the user location puck lag.
 *
 * [LocationComponent] animates the puck from its current position to a new location over about the
 * time since the previous update, so with plain updates the puck is always ~1 update interval
 * behind the real position. Here the location is extrapolated by speed and bearing to where the
 * user will be at the next expected update and passed as a look-ahead target, so the puck arrives
 * there in time and stays close to the real position while moving.
 *
 * Without movement (low speed or no bearing) the location is applied as is.
 */
internal class LocationLookAhead(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var lastUpdateMillis = 0L
    private var updateIntervalMillis = DEFAULT_UPDATE_INTERVAL_MILLIS

    fun update(locationComponent: LocationComponent, location: Location) {
        val now = clock()
        updateInterval(now)

        val predictedLocation = predict(location, now)
        if (predictedLocation == null) {
            locationComponent.forceLocationUpdate(location)
        } else {
            locationComponent.forceLocationUpdate(listOf(predictedLocation), true)
        }
    }

    private fun updateInterval(now: Long) {
        if (lastUpdateMillis > 0) {
            val elapsed = now - lastUpdateMillis
            if (elapsed in MIN_UPDATE_INTERVAL_MILLIS..MAX_UPDATE_INTERVAL_MILLIS) {
                // Smooth out irregular update timing
                updateIntervalMillis = (updateIntervalMillis * (1 - INTERVAL_SMOOTHING) + elapsed * INTERVAL_SMOOTHING).toLong()
            }
        }
        lastUpdateMillis = now
    }

    private fun predict(location: Location, now: Long): Location? {
        if (!location.hasBearing() || !location.hasSpeed() || location.speed < MIN_SPEED_METERS_PER_SECOND) {
            return null
        }
        val distance = min(location.speed * updateIntervalMillis / 1000.0, MAX_LOOK_AHEAD_METERS)
        return Location(location).apply {
            moveBy(distance, location.bearing.toDouble())
            // Look-ahead target time: the puck reaches this position at the next expected update
            time = now + updateIntervalMillis
        }
    }

    private fun Location.moveBy(distanceMeters: Double, bearingDegrees: Double) {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        latitude = Math.toDegrees(lat2)
        longitude = Math.toDegrees(lon2)
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MILLIS = 1000L
        private const val MIN_UPDATE_INTERVAL_MILLIS = 200L
        private const val MAX_UPDATE_INTERVAL_MILLIS = 3000L
        private const val INTERVAL_SMOOTHING = 0.3
        private const val MIN_SPEED_METERS_PER_SECOND = 1.5f
        private const val MAX_LOOK_AHEAD_METERS = 50.0
        private const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
