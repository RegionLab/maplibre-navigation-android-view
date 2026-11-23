package org.maplibre.navigation.android.navigation.ui.v5

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.UnitType
import java.util.Locale

class RoutingServiceTest {

    private val request = RouteRequest(
        origin = Point.fromLngLat(5.1, 52.1),
        waypoints = listOf(Point.fromLngLat(5.2, 52.2)),
        destination = Point.fromLngLat(5.3, 52.3),
        language = Locale("ru", "RU"),
        voiceUnits = UnitType.METRIC,
        originBearing = 45.0,
        approaches = listOf(null, RoutingService.APPROACH_CURB, null),
        waypointNames = listOf("Start", "Stop", "End"),
    )

    @Test
    fun mapbox_createsDirectionsApiRequest() {
        val url = RoutingService.Mapbox(accessToken = "pk.token").createHttpRequest(request).url

        assertEquals("GET", RoutingService.Mapbox(accessToken = "pk.token").createHttpRequest(request).method)
        assertEquals(
            "/directions/v5/mapbox/driving-traffic/5.1,52.1;5.2,52.2;5.3,52.3",
            url.encodedPath
        )
        assertEquals("api.mapbox.com", url.host)
        assertEquals("pk.token", url.queryParameter("access_token"))
        assertEquals("true", url.queryParameter("voice_instructions"))
        assertEquals("true", url.queryParameter("banner_instructions"))
        assertEquals("polyline6", url.queryParameter("geometries"))
        assertEquals("ru", url.queryParameter("language"))
        assertEquals("metric", url.queryParameter("voice_units"))
        assertEquals("45,90;;", url.queryParameter("bearings"))
        assertEquals(";curb;", url.queryParameter("approaches"))
        assertEquals("Start;Stop;End", url.queryParameter("waypoint_names"))
        // Live traffic for the route line, driving-traffic is the default profile
        assertEquals("maxspeed,speed,distance,duration,congestion", url.queryParameter("annotations"))
    }

    @Test
    fun mapbox_requestsTrafficOnlyWithTrafficProfile() {
        val service = RoutingService.Mapbox(accessToken = "pk.token")

        val driving = service.createHttpRequest(request.copy(profile = RoutingService.PROFILE_DRIVING)).url

        assertEquals("maxspeed,speed,distance,duration", driving.queryParameter("annotations"))
    }

    @Test
    fun graphHopper_usesNavigateEndpointWithKey() {
        val service = RoutingService.GraphHopper("https://graphhopper.com/api/1/navigate/", "gh-key")

        val url = service.createHttpRequest(request.copy(profile = "bike")).url

        assertEquals("/api/1/navigate/directions/v5/gh/bike/5.1,52.1;5.2,52.2;5.3,52.3", url.encodedPath)
        assertEquals("gh-key", url.queryParameter("key"))
    }

    @Test
    fun graphHopper_sendsPrefixedTokenOnlyAsAccessToken() {
        // A "pk." token sent as "key" is rejected by GraphHopper with HTTP 401
        val service = RoutingService.GraphHopper("https://graphhopper.com/api/1/navigate/", "pk.cb243ba3")

        val url = service.createHttpRequest(request).url

        assertEquals("pk.cb243ba3", url.queryParameter("access_token"))
        assertNull(url.queryParameter("key"))
    }

    @Test
    fun graphHopper_usesItsKazakhLanguageCode() {
        val service = RoutingService.GraphHopper("https://graphhopper.com/api/1/navigate/", "key")

        val url = service.createHttpRequest(request.copy(language = Locale("kk"))).url

        assertEquals("kz", url.queryParameter("language"))
        assertEquals(1, url.queryParameterValues("language").size)
    }

    @Test
    fun graphHopper_defaultsToCarProfile() {
        val service = RoutingService.GraphHopper("https://graphhopper.com/api/1/navigate", "gh-key")

        val url = service.createHttpRequest(request).url

        assertTrue(url.encodedPath.contains("/gh/car/"))
    }

    @Test
    fun valhalla_postsOsrmRequestWithInstructions() {
        val service = RoutingService.Valhalla(
            baseUrl = "https://valhalla.example.com/route",
            apiKey = "valhalla-key",
            costingOptions = buildJsonObject { putJsonObject("auto") { put("top_speed", 130) } }
        )

        val httpRequest = service.createHttpRequest(request)
        val body = httpRequest.bodyAsJson()

        assertEquals("POST", httpRequest.method)
        assertEquals("/route", httpRequest.url.encodedPath)
        assertEquals("valhalla-key", httpRequest.url.queryParameter("api_key"))
        assertEquals("osrm", body.string("format"))
        assertEquals("true", body.string("banner_instructions"))
        assertEquals("true", body.string("voice_instructions"))
        assertEquals("auto", body.string("costing"))
        assertEquals("ru-RU", body.string("language"))
        assertEquals("kilometers", body.string("units"))
        assertEquals(130, body["costing_options"]!!.jsonObject["auto"]!!.jsonObject.int("top_speed"))

        val locations = body["locations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, locations.size)
        assertEquals(45, locations[0].int("heading"))
        assertEquals("Stop", locations[1].string("name"))
        assertEquals("same", locations[1].string("preferred_side"))
        assertNull(locations[2]["heading"])
    }

    @Test
    fun routeOptions_restoreTheService() {
        val valhalla = RoutingService.Valhalla("https://valhalla.example.com")
        val graphHopper = RoutingService.GraphHopper("https://graphhopper.com/api/1/navigate/", "key")
        val other = RoutingService.Other("https://directions.example.com", "token", defaultProfile = "truck")

        assertTrue(RoutingService.fromRouteOptions(valhalla.createRouteOptions(request)) is RoutingService.Valhalla)
        assertTrue(RoutingService.fromRouteOptions(graphHopper.createRouteOptions(request)) is RoutingService.GraphHopper)
        val restored = RoutingService.fromRouteOptions(other.createRouteOptions(request))!!
        assertEquals(
            "/directions/v5/mapbox/truck/5.1,52.1;5.2,52.2;5.3,52.3",
            restored.createHttpRequest(request).url.encodedPath
        )
        assertNull(RoutingService.fromRouteOptions(null))
    }

    @Test
    fun routeOptions_containWhatNavigationNeeds() {
        val options = RoutingService.Valhalla("https://valhalla.example.com").createRouteOptions(request)

        assertEquals(true, options.voiceInstructions)
        assertEquals(true, options.bannerInstructions)
        assertEquals("ru", options.language)
        assertEquals(UnitType.METRIC, options.voiceUnits)
        assertEquals(3, options.coordinates.size)
        assertEquals("Start;Stop;End", options.waypointNames)
    }

    private fun okhttp3.Request.bodyAsJson(): JsonObject {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return kotlinx.serialization.json.Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
}
