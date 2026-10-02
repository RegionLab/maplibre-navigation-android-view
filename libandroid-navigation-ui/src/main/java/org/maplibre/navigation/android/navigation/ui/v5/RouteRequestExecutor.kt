package org.maplibre.navigation.android.navigation.ui.v5

import android.content.Context
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

internal class RouteRequestExecutor(
    private val context: Context
) {

    private var activeRouteRequest: NavigationRoute? = null

    fun request(
        request: NavigationRequest,
        onRoutesReady: (routes: List<DirectionsRoute>, options: MapLibreNavigationOptions) -> Unit,
        onError: (RouteRequestException) -> Unit,
    ): NavigationRoute {
        cancel()
        val navigationSource = request.routingService
        val navigationRoute = NavigationRoute.builder(context).apply {
            origin(request.origin)
            destination(request.destination)
            request.stops?.forEach { addWaypoint(it) }
            voiceUnits(request.voiceUnits)
            language(request.language)
            // Only the first route is used for navigation
            alternatives(false)
            if (navigationSource is RoutingService.GraphHopper) {
                user("gh")
                profile(request.profile ?: "car")
            } else {
                request.profile?.let { profile(it) }
            }
            accessToken(navigationSource.accessToken)
            baseUrl(navigationSource.baseUrl)
            annotations(
                NavigationRoute.ANNOTATION_MAXSPEED,
                NavigationRoute.ANNOTATION_SPEED,
                NavigationRoute.ANNOTATION_DISTANCE,
                NavigationRoute.ANNOTATION_DURATION
            )
        }.build()

        activeRouteRequest = navigationRoute
        navigationRoute.getRoute(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>
            ) {
                if (activeRouteRequest !== navigationRoute) {
                    return
                }
                activeRouteRequest = null
                if (!response.isSuccessful) {
                    Timber.w("MAPLIBRE Route request failed with HTTP ${response.code()}")
                    onError(RouteRequestException("Route request failed with HTTP ${response.code()}"))
                    return
                }
                val routes = response.body()?.routes.orEmpty()
                if (routes.isEmpty()) {
                    Timber.w("MAPLIBRE Route request completed with empty routes.")
                    onError(RouteRequestException("No routes found"))
                    return
                }
                onRoutesReady(routes, request.navigationOptions)
            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                if (call.isCanceled || activeRouteRequest !== navigationRoute) {
                    return
                }
                activeRouteRequest = null
                Timber.e(throwable, "MAPLIBRE onFailure: navigation.getRoute()")
                onError(RouteRequestException("Route request failed", throwable))
            }
        })
        return navigationRoute
    }

    fun cancel() {
        activeRouteRequest?.cancelCall()
        activeRouteRequest = null
    }
}

class RouteRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)
