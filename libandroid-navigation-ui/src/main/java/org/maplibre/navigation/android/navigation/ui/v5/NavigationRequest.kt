package org.maplibre.navigation.android.navigation.ui.v5

import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import java.util.Locale

/**
 * Route request used by [NavigationView.startNavigation] and [NavigationView.requestRoute].
 */
data class NavigationRequest(
    val origin: Point,
    val stops: List<Point>? = null,
    val destination: Point,
    val routingService: RoutingService,
    val language: Locale,
    val navigationOptions: MapLibreNavigationOptions = MapLibreNavigationOptions(),
    val voiceUnits: UnitType = UnitType.METRIC,
    /**
     * Routing profile, e.g. "car", "bike", "foot" for GraphHopper.
     * When null, GraphHopper requests use "car" and other services use their default profile.
     */
    val profile: String? = null,
    /**
     * Drive along the route with simulated locations instead of the device location,
     * e.g. for testing routes or demos.
     */
    val simulateRoute: Boolean = false,
)
