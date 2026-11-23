package org.maplibre.navigation.android.navigation.ui.v5.route

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.navigation.android.navigation.ui.v5.RoutingClient
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.RouteOptions
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.route.RouteListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.spatialk.geojson.Position

class MapLibreRouteFetcherTest {

    private val fetcher = MapLibreRouteFetcher(routingClient = mockk<RoutingClient>())
    private val location = Location(latitude = 52.0, longitude = 5.0, bearing = 90f)

    @Test
    fun buildRequest_startsAtLocationAndKeepsRemainingWaypoints() {
        val progress = progressWith(
            RouteOptions(
                baseUrl = "https://valhalla.example.com",
                user = "valhalla",
                profile = "bicycle",
                coordinates = listOf(Position(4.0, 51.0), Position(6.0, 53.0)),
                language = "ru",
                voiceUnits = UnitType.IMPERIAL,
                waypointNames = "Start;End",
                approaches = ";curb",
                annotations = "maxspeed,distance",
            )
        )

        val request = fetcher.buildRequest(location, progress)!!

        assertEquals(5.0, request.origin.longitude(), 0.0)
        assertEquals(52.0, request.origin.latitude(), 0.0)
        assertEquals(90.0, request.originBearing!!, 0.0)
        assertEquals(6.0, request.destination.longitude(), 0.0)
        assertTrue(request.waypoints.isEmpty())
        assertEquals("bicycle", request.profile)
        assertEquals("ru", request.language.language)
        assertEquals(UnitType.IMPERIAL, request.voiceUnits)
        assertEquals(listOf("Start", "End"), request.waypointNames)
        assertEquals(listOf("", "curb"), request.approaches)
        assertEquals(listOf("maxspeed", "distance"), request.annotations)
    }

    @Test
    fun buildRequest_withoutRouteOptions_returnsNull() {
        assertNull(fetcher.buildRequest(location, progressWith(null)))
    }

    @Test
    fun findRoute_withoutRouteOptions_reportsError() {
        val errors = mutableListOf<Throwable>()
        fetcher.addRouteListener(object : RouteListener {
            override fun onResponseReceived(response: DirectionsResponse, routeProgress: RouteProgress) = Unit
            override fun onErrorReceived(throwable: Throwable) {
                errors += throwable
            }
        })

        fetcher.findRouteFromRouteProgress(location, progressWith(null))

        assertEquals(1, errors.size)
    }

    private fun progressWith(options: RouteOptions?): RouteProgress {
        val json = javaClass.classLoader!!.getResource("directions_v5_precision_6.json")!!.readText()
        val route = DirectionsResponse.fromJson(json).routes.first().copy(routeOptions = options)
        return RouteProgress.Builder(
            directionsRoute = route,
            legIndex = 0,
            distanceRemaining = 100.0,
            currentStepPoints = emptyList(),
            stepIndex = 0,
            legDistanceRemaining = 100.0,
            stepDistanceRemaining = 100.0,
        ).build()
    }
}
