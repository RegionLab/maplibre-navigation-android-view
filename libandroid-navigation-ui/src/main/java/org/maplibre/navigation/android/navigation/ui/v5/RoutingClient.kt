package org.maplibre.navigation.android.navigation.ui.v5

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.navigation.core.models.DirectionsResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Requests routes from a [RoutingService].
 *
 * Responses are parsed on a background thread and delivered on the main thread. The returned
 * routes contain the [org.maplibre.navigation.core.models.RouteOptions] navigation needs.
 *
 * @param httpClient client for the requests, e.g. with interceptors for authentication headers
 * @param logger logs requests, responses and failures, e.g. [RoutingLogger.logcat]. Off by default.
 * @param logBodies also log request and response bodies. They contain the route coordinates.
 */
class RoutingClient @JvmOverloads constructor(
    httpClient: OkHttpClient = defaultHttpClient,
    private val logger: RoutingLogger? = null,
    logBodies: Boolean = false,
) {

    private val httpClient: OkHttpClient = if (logger == null) {
        httpClient
    } else {
        // First in the chain, so requests answered by your own interceptors (e.g. a cache) are logged too
        httpClient.newBuilder()
            .apply { interceptors().add(0, RoutingLoggingInterceptor(logger, logBodies)) }
            .build()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Requests routes. Exactly one of the callbacks is called on the main thread, unless the
     * request was cancelled with [RouteCall.cancel].
     */
    fun getRoute(
        service: RoutingService,
        request: RouteRequest,
        onRoutesReady: (DirectionsResponse) -> Unit,
        onError: (RouteRequestException) -> Unit,
    ): RouteCall {
        val routeCall = RouteCall()
        val httpCall = try {
            httpClient.newCall(service.createHttpRequest(request).withUserAgent())
        } catch (exception: IllegalArgumentException) {
            // e.g. an invalid base url
            mainHandler.post { routeCall.deliver { onError(RouteRequestException("Invalid route request", exception)) } }
            return routeCall
        }
        routeCall.httpCall = httpCall
        httpCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    logger?.log("Route request cancelled")
                    return
                }
                mainHandler.post {
                    routeCall.deliver { onError(RouteRequestException("Route request failed", e)) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use { readResponse(service, request, it) }
                mainHandler.post {
                    routeCall.deliver {
                        result.fold(onSuccess = onRoutesReady, onFailure = { error ->
                            onError(error as? RouteRequestException
                                ?: RouteRequestException("Invalid route response", error))
                        })
                    }
                }
            }
        })
        return routeCall
    }

    /**
     * Suspending variant of [getRoute], cancelling the coroutine cancels the request.
     *
     * @throws RouteRequestException if the request fails or returns no routes
     */
    suspend fun getRoute(service: RoutingService, request: RouteRequest): DirectionsResponse =
        suspendCancellableCoroutine { continuation ->
            val call = getRoute(
                service,
                request,
                onRoutesReady = { continuation.resume(it) },
                onError = { continuation.resumeWithException(it) }
            )
            continuation.invokeOnCancellation { call.cancel() }
        }

    private fun readResponse(
        service: RoutingService,
        request: RouteRequest,
        response: Response,
    ): Result<DirectionsResponse> = runCatching {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw RouteRequestException(
                "Route request failed with HTTP ${response.code}: ${body.take(MAX_ERROR_LENGTH)}",
                httpCode = response.code
            )
        }
        val startNs = System.nanoTime()
        val parsedResponse = service.parseResponse(body, request)
        if (parsedResponse.routes.isEmpty()) {
            val reason = parsedResponse.message ?: parsedResponse.code
            throw RouteRequestException("No routes found ($reason)", httpCode = response.code)
        }
        val parsedNs = System.nanoTime()
        val directionsResponse = service.enrichResponse(parsedResponse, request, httpClient)
        logger?.log(
            "Parsed ${directionsResponse.routes.size} route(s) in ${TimeUnit.NANOSECONDS.toMillis(parsedNs - startNs)} ms" +
                ", enriched in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - parsedNs)} ms"
        )
        logger?.let { log -> directionsResponse.routes.forEachIndexed { index, route -> log.log(route.summary(index)) } }
        if (directionsResponse.routes.isEmpty()) {
            val reason = directionsResponse.message ?: directionsResponse.code
            throw RouteRequestException("No routes found ($reason)", httpCode = response.code)
        }
        val routeOptions = service.createRouteOptions(request)
            .copy(requestUuid = directionsResponse.uuid)
        directionsResponse.copy(
            routes = directionsResponse.routes.map { route ->
                route.copy(routeOptions = routeOptions)
            }
        )
    }

    private fun okhttp3.Request.withUserAgent(): okhttp3.Request =
        if (header(USER_AGENT) != null) this else newBuilder().header(USER_AGENT, USER_AGENT_VALUE).build()

    /**
     * A running route request.
     */
    class RouteCall internal constructor() {
        internal var httpCall: Call? = null

        @Volatile
        var isCancelled: Boolean = false
            private set

        fun cancel() {
            isCancelled = true
            httpCall?.cancel()
        }

        internal inline fun deliver(block: () -> Unit) {
            if (!isCancelled) {
                block()
            }
        }
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
        const val USER_AGENT_VALUE = "MapLibreNavigationAndroid"
        const val MAX_ERROR_LENGTH = 300

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * One log line per route: distance and how much of it has speed limit data.
 */
private fun org.maplibre.navigation.core.models.DirectionsRoute.summary(index: Int): String {
    val maxSpeeds = legs.flatMap { it.annotation?.maxSpeed.orEmpty() }
    val speedLimits = when {
        maxSpeeds.isEmpty() -> "no maxspeed annotations in the response"
        else -> {
            val known = maxSpeeds.count { it.speed != null && it.unknown != true }
            val none = maxSpeeds.count { it.none == true }
            "maxspeed for ${maxSpeeds.size} segments: $known known, $none without limit, " +
                "${maxSpeeds.size - known - none} unknown"
        }
    }
    return "Route ${index + 1}: ${"%.1f".format(java.util.Locale.ROOT, distance / 1000)} km, ${legs.size} leg(s), $speedLimits"
}

/**
 * A route request failed, returned an error or no routes.
 *
 * @param httpCode HTTP status code, null if no response was received
 */
class RouteRequestException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
) : Exception(message, cause)
