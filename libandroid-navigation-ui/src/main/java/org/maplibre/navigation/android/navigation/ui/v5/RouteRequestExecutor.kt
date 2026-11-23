package org.maplibre.navigation.android.navigation.ui.v5

import org.maplibre.navigation.core.models.DirectionsRoute
import timber.log.Timber

/**
 * Executes [NavigationRequest]s. Starting a request cancels the previous one, so only the latest
 * request reports a result.
 */
internal class RouteRequestExecutor(
    private val routingClient: RoutingClient,
) {

    private var activeCall: RoutingClient.RouteCall? = null

    fun request(
        request: NavigationRequest,
        onRoutesReady: (routes: List<DirectionsRoute>) -> Unit,
        onError: (RouteRequestException) -> Unit,
    ) {
        cancel()
        activeCall = routingClient.getRoute(
            service = request.routingService,
            request = request.toRouteRequest(),
            onRoutesReady = { response ->
                activeCall = null
                onRoutesReady(response.routes)
            },
            onError = { error ->
                activeCall = null
                Timber.w(error, "Route request failed")
                onError(error)
            }
        )
    }

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }
}

internal fun NavigationRequest.toRouteRequest() = RouteRequest(
    origin = origin,
    destination = destination,
    waypoints = stops.orEmpty(),
    profile = profile,
    language = language,
    voiceUnits = voiceUnits,
    // Only the first route is used for navigation
    alternatives = false,
)
