package org.maplibre.navigation.android.navigation.ui.v5

import android.os.SystemClock
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.route.RouteFetcher
import org.maplibre.navigation.core.route.RouteListener
import org.maplibre.navigation.core.routeprogress.RouteProgress

/**
 * Requests a new route when the user went off route.
 *
 * Only one request runs at a time. A request that doesn't answer within [requestTimeoutMs] no
 * longer blocks new ones, and after a failure new requests are held back for [retryDelayMs], so a
 * device without network doesn't send a request on every location update.
 */
internal class RerouteController(
    private val routeFetcher: RouteFetcher,
    private val onRouteFound: (DirectionsRoute) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) : RouteListener {

    private var requestStartedAt: Long? = null
    private var lastFailureAt: Long? = null

    init {
        routeFetcher.addRouteListener(this)
    }

    /**
     * True while a request is running or a failed request is still cooling down.
     */
    val isBusy: Boolean
        get() {
            val now = clock()
            val isRequesting = requestStartedAt?.let { now - it < requestTimeoutMs } == true
            val isCoolingDown = lastFailureAt?.let { now - it < retryDelayMs } == true
            return isRequesting || isCoolingDown
        }

    /**
     * Requests a route from [location] to the remaining waypoints of [routeProgress].
     *
     * @return false if a request is already running or cooling down after a failure
     */
    fun reroute(location: Location, routeProgress: RouteProgress): Boolean {
        if (isBusy) {
            return false
        }
        requestStartedAt = clock()
        routeFetcher.findRouteFromRouteProgress(location, routeProgress)
        return true
    }

    fun cancel() {
        routeFetcher.cancelRouteCall()
        requestStartedAt = null
        lastFailureAt = null
    }

    fun destroy() {
        cancel()
        routeFetcher.clearListeners()
    }

    override fun onResponseReceived(response: DirectionsResponse, routeProgress: RouteProgress) {
        if (requestStartedAt == null) {
            return
        }
        requestStartedAt = null
        val route = response.routes.firstOrNull()
        if (route == null) {
            onErrorReceived(RouteRequestException("No routes found"))
            return
        }
        lastFailureAt = null
        onRouteFound(route)
    }

    override fun onErrorReceived(throwable: Throwable) {
        requestStartedAt = null
        lastFailureAt = clock()
        onError(throwable)
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
        const val DEFAULT_RETRY_DELAY_MS = 5_000L
    }
}
