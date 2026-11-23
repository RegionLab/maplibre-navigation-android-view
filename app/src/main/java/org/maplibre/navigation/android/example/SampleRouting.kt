package org.maplibre.navigation.android.example

import android.content.Context
import org.maplibre.navigation.android.navigation.ui.v5.RoutingClient
import org.maplibre.navigation.android.navigation.ui.v5.RoutingService

/**
 * Routing used by the samples. The public Valhalla demo server works without an API key, it must
 * not be used in production. Any [RoutingService] works here, e.g.
 * `RoutingService.Mapbox(accessToken = ...)` or `RoutingService.GraphHopper(baseUrl, apiKey)`.
 */
internal object SampleRouting {

    val client = RoutingClient()

    fun service(context: Context): RoutingService =
        RoutingService.Valhalla(context.getString(R.string.valhalla_url))
}
