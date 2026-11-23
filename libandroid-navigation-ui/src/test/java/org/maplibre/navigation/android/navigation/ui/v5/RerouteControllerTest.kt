package org.maplibre.navigation.android.navigation.ui.v5

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.route.RouteFetcher
import org.maplibre.navigation.core.routeprogress.RouteProgress

class RerouteControllerTest {

    private val location: Location = mockk()
    private val routeProgress: RouteProgress = mockk()
    private val routeFetcher = FakeRouteFetcher()
    private val foundRoutes = mutableListOf<DirectionsRoute>()
    private val errors = mutableListOf<Throwable>()
    private var now = 0L
    private lateinit var controller: RerouteController

    @Before
    fun setUp() {
        controller = RerouteController(
            routeFetcher = routeFetcher,
            onRouteFound = { foundRoutes += it },
            onError = { errors += it },
            clock = { now },
            requestTimeoutMs = 10_000,
            retryDelayMs = 5_000,
        )
    }

    @Test
    fun reroute_requestsRouteFromCurrentLocation() {
        assertTrue(controller.reroute(location, routeProgress))

        assertEquals(1, routeFetcher.requests)
        assertSame(location, routeFetcher.lastLocation)
        assertTrue(controller.isBusy)
    }

    @Test
    fun reroute_whileRequestRunning_isIgnored() {
        controller.reroute(location, routeProgress)

        assertFalse(controller.reroute(location, routeProgress))
        assertEquals(1, routeFetcher.requests)
    }

    @Test
    fun reroute_afterRequestTimedOut_requestsAgain() {
        controller.reroute(location, routeProgress)
        now += 10_000

        assertTrue(controller.reroute(location, routeProgress))
        assertEquals(2, routeFetcher.requests)
    }

    @Test
    fun response_deliversFirstRoute() {
        val response = buildResponse()
        controller.reroute(location, routeProgress)

        controller.onResponseReceived(response, routeProgress)

        assertEquals(listOf(response.routes.first()), foundRoutes)
        assertFalse(controller.isBusy)
    }

    @Test
    fun response_withoutRoutes_isAnError() {
        controller.reroute(location, routeProgress)

        controller.onResponseReceived(buildResponse().copy(routes = emptyList()), routeProgress)

        assertTrue(foundRoutes.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test
    fun response_afterCancel_isIgnored() {
        controller.reroute(location, routeProgress)
        controller.cancel()

        controller.onResponseReceived(buildResponse(), routeProgress)

        assertTrue(foundRoutes.isEmpty())
        assertEquals(1, routeFetcher.cancelCalls)
    }

    @Test
    fun error_holdsBackNewRequestsForRetryDelay() {
        controller.reroute(location, routeProgress)
        controller.onErrorReceived(IllegalStateException("offline"))

        now += 4_999
        assertFalse(controller.reroute(location, routeProgress))

        now += 1
        assertTrue(controller.reroute(location, routeProgress))
        assertEquals(2, routeFetcher.requests)
        assertEquals(1, errors.size)
    }

    @Test
    fun destroy_cancelsRequestAndRemovesListener() {
        controller.destroy()

        assertEquals(1, routeFetcher.cancelCalls)
        assertTrue(routeFetcher.listeners.isEmpty())
    }

    private fun buildResponse(): DirectionsResponse {
        val json = TestRouteBuilder().loadJsonFixture("directions_v5_precision_6.json")
        return DirectionsResponse.fromJson(json)
    }

    private class FakeRouteFetcher : RouteFetcher() {
        var requests = 0
        var cancelCalls = 0
        var lastLocation: Location? = null
        val listeners get() = routeListeners

        override fun findRouteFromRouteProgress(location: Location, routeProgress: RouteProgress) {
            requests++
            lastLocation = location
        }

        override fun cancelRouteCall() {
            cancelCalls++
        }
    }
}
