package org.maplibre.navigation.android.navigation.ui.v5.route

import org.maplibre.geojson.Point
import org.maplibre.navigation.android.navigation.ui.v5.RouteRequest
import org.maplibre.navigation.android.navigation.ui.v5.RoutingClient
import org.maplibre.navigation.android.navigation.ui.v5.RoutingService
import org.maplibre.navigation.android.navigation.ui.v5.toMapLibrePoints
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.route.RouteFetcher
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.navigation.core.utils.RouteUtils
import java.util.Locale

/**
 * Fetches a new route from the current [Location] to the remaining waypoints of a [RouteProgress],
 * re-using the profile, language and units of the route being navigated. Used for rerouting.
 *
 * Results are delivered to the listeners added with [addRouteListener]. Every request ends with
 * exactly one listener call, unless it was cancelled with [cancelRouteCall].
 *
 * @param routingService engine to request routes from. When null, it is recreated from the route
 * options of the route being navigated, see [RoutingService.fromRouteOptions].
 */
class MapLibreRouteFetcher @JvmOverloads constructor(
    private val routingClient: RoutingClient = RoutingClient(),
    var routingService: RoutingService? = null,
) : RouteFetcher() {

    private val routeUtils = RouteUtils()
    private var activeCall: RoutingClient.RouteCall? = null

    override fun findRouteFromRouteProgress(location: Location, routeProgress: RouteProgress) {
        val service = routingService
            ?: RoutingService.fromRouteOptions(routeProgress.directionsRoute.routeOptions)
        val request = buildRequest(location, routeProgress)
        if (service == null || request == null) {
            notifyError(IllegalStateException("The route has no route options to request a new route from"))
            return
        }
        cancelRouteCall()
        activeCall = routingClient.getRoute(
            service,
            request,
            onRoutesReady = { response ->
                activeCall = null
                routeListeners.toList().forEach { it.onResponseReceived(response, routeProgress) }
            },
            onError = { error ->
                activeCall = null
                notifyError(error)
            }
        )
    }

    /**
     * Builds a request from [location] to the waypoints remaining in [progress], or null when
     * the route has no route options to derive the request from.
     */
    fun buildRequest(location: Location, progress: RouteProgress): RouteRequest? {
        val options = progress.directionsRoute.routeOptions ?: return null
        val remainingWaypoints = routeUtils.calculateRemainingWaypoints(progress)
            ?.toMapLibrePoints()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val remainingCount = remainingWaypoints.size
        return RouteRequest(
            origin = Point.fromLngLat(location.longitude, location.latitude),
            destination = remainingWaypoints.last(),
            waypoints = remainingWaypoints.dropLast(1),
            profile = options.profile,
            language = options.language?.let(Locale::forLanguageTag) ?: Locale.getDefault(),
            voiceUnits = options.voiceUnits ?: UnitType.METRIC,
            alternatives = false,
            originBearing = location.bearing?.toDouble(),
            annotations = options.annotations?.split(",")?.filter { it.isNotEmpty() }
                ?: RouteRequest.DEFAULT_ANNOTATIONS,
            // Names and approaches of the origin plus the remaining waypoints
            waypointNames = options.waypointNames?.split(";")?.withRemaining(remainingCount),
            approaches = options.approaches?.split(";")?.withRemaining(remainingCount),
            exclude = options.exclude,
        )
    }

    override fun cancelRouteCall() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun notifyError(throwable: Throwable) {
        routeListeners.toList().forEach { it.onErrorReceived(throwable) }
    }

    /**
     * Keeps the entry of the origin and the entries of the last [remainingCount] coordinates.
     */
    private fun List<String>.withRemaining(remainingCount: Int): List<String?>? {
        if (size <= remainingCount) {
            return null
        }
        return listOf<String?>(first()) + takeLast(remainingCount)
    }
}
