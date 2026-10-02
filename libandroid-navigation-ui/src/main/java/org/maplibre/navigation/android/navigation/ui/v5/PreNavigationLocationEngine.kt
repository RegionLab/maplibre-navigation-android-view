package org.maplibre.navigation.android.navigation.ui.v5


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.location.LocationComponent
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.LocationValidator
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.location.engine.LocationEngine.Request.Accuracy
import org.maplibre.navigation.core.location.toAndroidLocation
import org.maplibre.navigation.android.navigation.ui.v5.map.LocationLookAhead
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions.Defaults

class PreNavigationLocationEngine(
    private val locationEngine: LocationEngine,
    private val locationComponent: LocationComponent,
    private val onLocationUpdate: ((Location) -> Unit)? = null,
    private val locationValidator: LocationValidator = LocationValidator(Defaults.LOCATION_ACCEPTABLE_ACCURACY_IN_METERS_THRESHOLD),//todo maybe provide accuracyThreshold through options
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    /**
     * Location request used while navigation is not running.
     */
    private val locationRequest: LocationEngine.Request = DEFAULT_REQUEST,
) {

    private var collectLocationJob: Job? = null
    private val locationLookAhead = LocationLookAhead()

    fun start() {
        collectLocationJob?.cancel() // Cancel previous started run

        collectLocationJob = backgroundScope.launch {
            locationEngine.getLastLocation()?.let { processLocationUpdate(it) }

            // Android location engines need a Looper thread when no explicit looper is given
            withContext(Dispatchers.Main) {
                locationEngine.listenToLocation(locationRequest).collect(::processLocationUpdate)
            }
        }
    }

    fun stop() {
        collectLocationJob?.cancel()
        collectLocationJob = null
    }

    private fun processLocationUpdate(rawLocation: Location) {
        if (!locationValidator.isValidUpdate(rawLocation)) {
            return
        }
        mainScope.launch {
            onLocationUpdate?.invoke(rawLocation)
            locationLookAhead.update(locationComponent, rawLocation.toAndroidLocation())
        }
    }

    companion object {
        val DEFAULT_REQUEST = LocationEngine.Request(
            accuracy = Accuracy.HIGH,
            minUpdateDistanceMeters = 0f,
            // Longer intervals make the user location puck lag behind
            intervalMilliseconds = 1000L,
        )
    }
}
