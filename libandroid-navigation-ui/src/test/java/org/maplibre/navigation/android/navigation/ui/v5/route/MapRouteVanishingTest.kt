package org.maplibre.navigation.android.navigation.ui.v5.route

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.routeprogress.RouteProgress

class MapRouteVanishingTest {

    private val route: DirectionsRoute = mockk()
    private val routeProgress: RouteProgress = mockk { every { directionsRoute } returns route }
    private val location = Location(latitude = 43.2, longitude = 76.9)

    private fun routeLine(): MapRouteLine = mockk(relaxed = true) {
        every { retrieveDirectionsRoutes() } returns listOf(route)
        every { retrievePrimaryRouteIndex() } returns 0
    }

    @Test
    fun progress_vanishesTraveledRouteWhenEnabled() {
        val routeLine = routeLine()
        val listener = MapRouteProgressChangeListener(routeLine, mockk(relaxed = true))

        listener.onProgressChange(location, routeProgress)
        listener.updateVanishingEnabled(true)
        listener.onProgressChange(location, routeProgress)

        verify(exactly = 1) { routeLine.vanishTraveledRoute(any()) }
    }

    @Test
    fun recreatedRouteLine_keepsVanishingAndReceivesProgress() {
        // A style change recreates the route line, the registered listener must follow it
        val oldRouteLine = routeLine()
        val newRouteLine = routeLine()
        val listener = MapRouteProgressChangeListener(oldRouteLine, mockk(relaxed = true))
        listener.updateVanishingEnabled(true)

        listener.updateRouteLine(newRouteLine, mockk(relaxed = true))
        listener.onProgressChange(location, routeProgress)

        verify(exactly = 1) { newRouteLine.vanishTraveledRoute(any()) }
        verify(exactly = 0) { oldRouteLine.vanishTraveledRoute(any()) }
    }

    @Test
    fun routeDrawnAfterRemoveRoute_receivesProgressAgain() {
        // DriverApp hides the old route (removeRoute) before every new navigation
        val routeLine = routeLine()
        val listener = MapRouteProgressChangeListener(routeLine, mockk(relaxed = true))
        listener.updateVanishingEnabled(true)
        val mapRoute = NavigationMapRoute(
            null, mockk(relaxed = true), mockk(relaxed = true), 0, "",
            mockk(relaxed = true), mockk(relaxed = true), listener, routeLine, mockk(relaxed = true)
        )

        mapRoute.removeRoute()
        listener.onProgressChange(location, routeProgress)
        mapRoute.addRoute(route)
        listener.onProgressChange(location, routeProgress)

        verify(exactly = 1) { routeLine.vanishTraveledRoute(any()) }
    }

    @Test
    fun disablingVanishing_restoresCompleteRoute() {
        val routeLine = routeLine()
        val listener = MapRouteProgressChangeListener(routeLine, mockk(relaxed = true))
        listener.updateVanishingEnabled(true)

        listener.updateVanishingEnabled(false)

        verify(exactly = 1) { routeLine.restoreTraveledRoute() }
    }
}
