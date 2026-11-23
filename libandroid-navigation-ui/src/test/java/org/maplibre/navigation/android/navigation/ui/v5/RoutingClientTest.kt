package org.maplibre.navigation.android.navigation.ui.v5

import android.os.Looper
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.utils.ValidationUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class RoutingClientTest {

    private val service = RoutingService.Valhalla("https://valhalla.example.com")
    private val request = RouteRequest(
        origin = Point.fromLngLat(5.1, 52.1),
        destination = Point.fromLngLat(5.3, 52.3),
        profile = "bicycle",
    )
    private var requestedUrl: String? = null

    @Test
    fun success_returnsRoutesWithRouteOptions() {
        val client = clientResponding(200, fixture())

        val result = execute(client)

        val routes = result.response!!.routes
        assertTrue(routes.isNotEmpty())
        val options = routes.first().routeOptions!!
        assertEquals(RoutingService.USER_VALHALLA, options.user)
        assertEquals("bicycle", options.profile)
        assertEquals(true, options.voiceInstructions)
        assertEquals("https://valhalla.example.com/route", requestedUrl)
    }

    @Test
    fun valhallaResponse_isNavigable() {
        // Recorded from valhalla1.openstreetmap.de with format=osrm and voice/banner instructions
        val body = TestRouteBuilder().loadJsonFixture("valhalla_osrm_response.json")
        val client = clientResponding(200, body)

        val route = execute(client).response!!.routes.first()

        // Throws if the route can't be used with the default voice and banner milestones
        ValidationUtils.validDirectionsRoute(route, defaultMilestonesEnabled = true)
        assertEquals(2, route.legs.size)
        assertEquals("ru-RU", route.voiceLanguage)
        assertTrue(route.legs.first().steps.first().voiceInstructions!!.isNotEmpty())
        assertTrue(route.legs.first().steps.first().bannerInstructions!!.isNotEmpty())
    }

    @Test
    fun valhalla_loadsSpeedLimits() {
        val client = clientRoutingByPath(traceCode = 200)

        val route = execute(client).response!!.routes.first()

        val maxSpeeds = route.legs.flatMap { it.annotation!!.maxSpeed!! }
        assertEquals(setOf(30, 50), maxSpeeds.map { it.speed }.toSet())
    }

    @Test
    fun valhalla_speedLimitFailure_stillReturnsRoute() {
        val client = clientRoutingByPath(traceCode = 500)

        val route = execute(client).response!!.routes.first()

        assertNull(route.legs.first().annotation)
    }

    @Test
    fun logger_logsRequestsWithMaskedToken() {
        val messages = mutableListOf<String>()
        val client = RoutingClient(
            OkHttpClient.Builder().addInterceptor { chain -> response(chain, 200, fixture()) }.build(),
            logger = { messages += it },
        )

        var done = false
        client.getRoute(
            RoutingService.Mapbox(accessToken = "pk.secret"),
            request,
            onRoutesReady = { done = true },
            onError = { done = true }
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (!done && System.currentTimeMillis() < deadline) {
            idleMainLooper()
            Thread.sleep(10)
        }

        val log = messages.joinToString("\n")
        assertTrue(log, log.contains("--> GET https://api.mapbox.com/directions/v5/mapbox/bicycle/"))
        assertTrue(log, log.contains("access_token=***"))
        assertFalse(log, log.contains("pk.secret"))
        assertTrue(log, log.contains("<-- 200 GET"))
        assertTrue(log, log.contains("Parsed 1 route(s)"))
    }

    @Test
    fun httpError_isReportedWithStatusCode() {
        val client = clientResponding(400, """{"error":"No suitable edges near location"}""")

        val error = execute(client).error!!

        assertEquals(400, error.httpCode)
        assertTrue(error.message!!.contains("No suitable edges"))
    }

    @Test
    fun responseWithoutRoutes_isAnError() {
        val client = clientResponding(200, """{"code":"NoRoute","message":"No route found","routes":[]}""")

        val error = execute(client).error!!

        assertTrue(error.message!!.contains("No route found"))
    }

    @Test
    fun invalidJson_isAnError() {
        val client = clientResponding(200, "<html>proxy error</html>")

        assertNotNull(execute(client).error)
    }

    @Test
    fun cancelledCall_doesNotDeliver() {
        val responded = CountDownLatch(1)
        val client = RoutingClient(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 200, fixture()).also { responded.countDown() }
        }.build())
        var delivered = false

        val call = client.getRoute(service, request, onRoutesReady = { delivered = true }, onError = { delivered = true })
        call.cancel()
        responded.await(5, TimeUnit.SECONDS)
        idleMainLooper()

        assertTrue(call.isCancelled)
        assertFalse(delivered)
    }

    private class Result(val response: DirectionsResponse?, val error: RouteRequestException?)

    private fun execute(client: RoutingClient): Result {
        var response: DirectionsResponse? = null
        var error: RouteRequestException? = null
        client.getRoute(service, request, onRoutesReady = { response = it }, onError = { error = it })
        // The response is parsed on an OkHttp thread and posted to the main looper
        val deadline = System.currentTimeMillis() + 5_000
        while (response == null && error == null && System.currentTimeMillis() < deadline) {
            idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("No result delivered", response != null || error != null)
        if (error != null) {
            assertNull(response)
        }
        return Result(response, error)
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun clientResponding(code: Int, body: String) = RoutingClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            // Valhalla makes a second request for speed limits, keep the route request
            if (requestedUrl == null) {
                requestedUrl = chain.request().url.toString()
            }
            response(chain, code, body)
        }.build()
    )

    private fun clientRoutingByPath(traceCode: Int) = RoutingClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/trace_attributes")) {
                response(chain, traceCode, TestRouteBuilder().loadJsonFixture("valhalla_trace_attributes_response.json"))
            } else {
                response(chain, 200, TestRouteBuilder().loadJsonFixture("valhalla_osrm_response.json"))
            }
        }.build()
    )

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun fixture() = TestRouteBuilder().loadJsonFixture("directions_v5_precision_6.json")
}
