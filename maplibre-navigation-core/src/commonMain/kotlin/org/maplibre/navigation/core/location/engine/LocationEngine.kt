package org.maplibre.navigation.core.location.engine

import kotlinx.coroutines.flow.Flow
import org.maplibre.navigation.core.location.Location

/**
 * Location engine that is used to fetch current location and listen to location updates.
 */
interface LocationEngine {

    /**
     * Listen to location updates.
     *
     * @param request request to configure location updates parameters
     * @return flow of location updates
     */
    fun listenToLocation(request: Request): Flow<Location>

    /**
     * Get last known location. If last location is not available, this method will return null.
     *
     * @return last known location or null if not available
     */
    suspend fun getLastLocation(): Location?

    /**
     * Request to configure location updates parameters
     */
    data class Request(
        /**
         * Accuracy type for location fetching
         */
        val accuracy: Accuracy,

        /**
         * Minimum distance between location updates. All updates that are closer than
         * this distance will be ignored.
         */
        val minUpdateDistanceMeters: Float,

        /**
         * Minimum interval between location updates. This is the fastest interval that will
         * be used to get location updates.
         * **Note:** This value is ignored for iOS because there is no configuration available
         * for it.
         */
        val intervalMilliseconds: Long,
    ) {

        /**
         * Accuracy type of location updates.
         */
        enum class Accuracy {
            /**
             * A lowest accuracy.
             * This will be `PRIORITY_PASSIVE` in Android
             * and `CLLocationAccuracyThreeKilometers` in iOS.
             */
            LOWEST,

            /**
             * Low accuracy for save battery power.
             * This will be `PRIORITY_LOW_POWER` in Android
             * and `CLLocationAccuracyHundredMeters` in iOS.
             */
            LOW,

            /**
             * A medium accuracy, that is saving battery power and give good location results.
             * This will be `PRIORITY_BALANCED_POWER_ACCURACY` in Android
             * and `kCLLocationAccuracyNearestTenMeters` in iOS.
             */
            MEDIUM,

            /**
             * Highest possible accuracy. This all possible sensors and calculate the most recent location.
             * Because of intensive use of sensors and GPS, it will cost more battery power.
             * This will be `PRIORITY_HIGH_ACCURACY` in Android
             * and `CLLocationAccuracyBestForNavigation` in iOS.
             */
            HIGH
        }
    }
}