package org.maplibre.navigation.android.navigation.ui.v5

import android.content.Context
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.UnitType
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
        onRoutesReady: (routes: List<DirectionsRoute>, options: MapLibreNavigationOptions) -> Unit
    ): NavigationRoute {
        cancel()
        val navigationSource = request.routingService
        val navigationRoute = NavigationRoute.builder(context).apply {
            origin(request.origin)
            destination(request.destination)
            request.stops?.forEach { addWaypoint(it) }
            voiceUnits(UnitType.METRIC)
            language(request.language)
            alternatives(true)
            if (navigationSource is RoutingService.GraphHopper) {
                user("gh")
                profile("car")
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
                Timber.d("MAPLIBRE Response: ${response.body()?.toJson()}")
                val routes = response.body()?.routes.orEmpty()
                if (routes.isEmpty()) {
                    Timber.w("MAPLIBRE Route request completed with empty routes.")
                    return
                }
                val maplibreResponse = DirectionsResponse.fromJson(response.body()!!.toJson())
                onRoutesReady(maplibreResponse.routes, request.navigationOptions)
            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                Timber.e(throwable, "MAPLIBRE onFailure: navigation.getRoute()")
            }
        })
        return navigationRoute
    }

    fun cancel() {
        activeRouteRequest?.cancelCall()
        activeRouteRequest = null
    }
}
