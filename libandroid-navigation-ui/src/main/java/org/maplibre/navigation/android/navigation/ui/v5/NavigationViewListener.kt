package org.maplibre.navigation.android.navigation.ui.v5

import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechAnnouncement
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.BannerInstructions
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.routeprogress.RouteProgress

/**
 * Navigation events of a [NavigationView], set with [NavigationView.setNavigationListener].
 *
 * Every method has a default implementation, override only the ones you need.
 * All methods are called on the main thread.
 */
interface NavigationViewListener {

    /**
     * Navigation started along [route], either with [NavigationView.startNavigation] or when a
     * running navigation was replaced by a new one.
     */
    fun onNavigationStarted(route: DirectionsRoute) {}

    /**
     * Navigation stopped with [NavigationView.stopNavigation] or because the view was destroyed.
     */
    fun onNavigationStopped() {}

    /**
     * Called for every location update while navigating.
     */
    fun onProgressChange(location: Location, routeProgress: RouteProgress) {}

    /**
     * The user left the route. Return false to keep following the current route without
     * requesting a new one.
     */
    fun allowReroute(location: Location): Boolean = true

    /**
     * The user left the route and a new route is being requested.
     */
    fun onOffRoute(location: Location) {}

    /**
     * Navigation continues along [route] after an off-route event or a faster route was found.
     */
    fun onRerouted(route: DirectionsRoute) {}

    /**
     * Requesting a new route after an off-route event failed. Navigation continues along the old
     * route and a new request is made the next time the user is detected off route.
     */
    fun onRerouteFailed(error: Throwable) {}

    /**
     * The user arrived at an intermediate waypoint of the route.
     */
    fun onWaypointArrival(routeProgress: RouteProgress) {}

    /**
     * The user arrived at the final destination. Navigation keeps running until
     * [NavigationView.stopNavigation] is called.
     */
    fun onFinalDestinationArrival(routeProgress: RouteProgress) {}

    /**
     * The speed limit at the current position changed, null if the route has no speed limit
     * data there. Only called while navigating.
     */
    fun onSpeedLimitChanged(maxSpeed: MaxSpeed?) {}

    /**
     * The user started or stopped driving faster than the speed limit plus the tolerance set
     * with `configureUi(speedingTolerance = ...)`. Also called while the app is in the background, e.g. to warn
     * with a sound.
     */
    fun onSpeedingChanged(isSpeeding: Boolean) {}

    /**
     * Called before a voice instruction is played.
     *
     * @return the announcement to play, possibly modified, or null to skip it
     */
    fun willVoice(announcement: SpeechAnnouncement): SpeechAnnouncement? = announcement

    /**
     * Called before banner instructions are displayed.
     *
     * @return the instructions to display, possibly modified, or null to skip them
     */
    fun willDisplay(instructions: BannerInstructions): BannerInstructions? = instructions

    /**
     * The instruction list was shown or hidden.
     */
    fun onInstructionListVisibilityChanged(visible: Boolean) {}
}
