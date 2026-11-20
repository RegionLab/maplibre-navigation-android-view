package org.maplibre.navigation.android.navigation.ui.v5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.spatialk.geojson.Position
import timber.log.Timber

/**
 * A routing engine that returns routes in the Mapbox Directions API format with voice and banner
 * instructions, which is what navigation needs.
 *
 * Built in: [Mapbox], [GraphHopper], [Valhalla] and [Other] for any other Mapbox Directions API
 * compatible server. Subclass it to support another engine: create the HTTP request in
 * [createHttpRequest] and convert the response in [parseResponse].
 */
abstract class RoutingService {

    /**
     * Creates the HTTP request for [request].
     */
    abstract fun createHttpRequest(request: RouteRequest): Request

    /**
     * Converts the response body into a [DirectionsResponse]. Called on a background thread.
     */
    open fun parseResponse(body: String, request: RouteRequest): DirectionsResponse =
        DirectionsResponse.fromJson(body)

    /**
     * Adds data to parsed routes, e.g. by requesting more data with [httpClient]. Called on a
     * background thread after [parseResponse], synchronous requests are fine here. Failures of
     * optional data should be ignored instead of failing the route request.
     */
    open fun enrichResponse(
        response: DirectionsResponse,
        request: RouteRequest,
        httpClient: OkHttpClient,
    ): DirectionsResponse = response

    /**
     * Route options stored in the returned routes. Navigation reads the language, units and
     * waypoints from them, and they are used to request a new route when the user leaves the
     * route, see [fromRouteOptions].
     */
    open fun createRouteOptions(request: RouteRequest): RouteOptions = RouteOptions(
        baseUrl = baseUrl,
        user = user,
        profile = request.profile ?: defaultProfile,
        coordinates = request.coordinates.map { Position(it.longitude(), it.latitude()) },
        alternatives = request.alternatives,
        // Read back with Locale(language) for distance formatting and speech
        language = request.language.language,
        voiceUnits = request.voiceUnits,
        annotations = request.annotations.joinToString(",").ifEmpty { null },
        exclude = request.exclude,
        approaches = request.approaches?.joinToString(";") { it.orEmpty() },
        waypointNames = request.waypointNames?.joinToString(";") { it.orEmpty() },
        accessToken = accessToken,
        geometries = GEOMETRY_POLYLINE6,
        overview = OVERVIEW_FULL,
        steps = true,
        continueStraight = true,
        roundaboutExits = true,
        voiceInstructions = true,
        bannerInstructions = true,
    )

    /** Base url of the engine, stored in [RouteOptions.baseUrl]. */
    protected abstract val baseUrl: String

    /** Stored in [RouteOptions.user], identifies the engine in [fromRouteOptions]. */
    protected abstract val user: String

    /** Profile used when [RouteRequest.profile] is null. */
    protected abstract val defaultProfile: String

    /** Stored in [RouteOptions.accessToken]. */
    protected open val accessToken: String? = null

    /**
     * Any server implementing the
     * [Mapbox Directions API](https://docs.mapbox.com/api/navigation/directions/).
     * The request is `{baseUrl}/directions/v5/{user}/{profile}/{coordinates}`.
     */
    open class MapboxCompatible(
        override val baseUrl: String,
        override val accessToken: String?,
        override val user: String,
        override val defaultProfile: String,
    ) : RoutingService() {

        override fun createHttpRequest(request: RouteRequest): Request =
            Request.Builder().url(createUrl(request)).get().build()

        protected open fun createUrl(request: RouteRequest): HttpUrl {
            val coordinates = request.coordinates.joinToString(";") { point ->
                "${point.longitude()},${point.latitude()}"
            }
            return baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("directions/v5")
                .addPathSegment(user)
                .addPathSegment(request.profile ?: defaultProfile)
                .addPathSegment(coordinates)
                .apply {
                    accessToken?.let { addQueryParameter("access_token", it) }
                    addQueryParameter("steps", "true")
                    addQueryParameter("geometries", GEOMETRY_POLYLINE6)
                    addQueryParameter("overview", OVERVIEW_FULL)
                    addQueryParameter("continue_straight", "true")
                    addQueryParameter("roundabout_exits", "true")
                    addQueryParameter("voice_instructions", "true")
                    addQueryParameter("banner_instructions", "true")
                    addQueryParameter("alternatives", request.alternatives.toString())
                    addQueryParameter("language", request.language.language)
                    addQueryParameter("voice_units", request.voiceUnits.text)
                    if (request.annotations.isNotEmpty()) {
                        addQueryParameter("annotations", request.annotations.joinToString(","))
                    }
                    request.originBearing?.let { bearing ->
                        // Only the origin has a bearing, the other coordinates get empty entries
                        val bearings = listOf("${bearing.toInt()},$BEARING_TOLERANCE") +
                            List(request.coordinates.size - 1) { "" }
                        addQueryParameter("bearings", bearings.joinToString(";"))
                    }
                    request.approaches?.let { approaches ->
                        addQueryParameter("approaches", approaches.joinToString(";") { it.orEmpty() })
                    }
                    request.waypointNames?.let { names ->
                        addQueryParameter("waypoint_names", names.joinToString(";") { it.orEmpty() })
                    }
                    request.exclude?.let { addQueryParameter("exclude", it) }
                }
                .build()
        }
    }

    /**
     * [Mapbox Directions API](https://docs.mapbox.com/api/navigation/directions/).
     */
    class Mapbox @JvmOverloads constructor(
        baseUrl: String = "https://api.mapbox.com",
        accessToken: String,
    ) : MapboxCompatible(baseUrl, accessToken, USER_MAPBOX, PROFILE_DRIVING_TRAFFIC)

    /**
     * GraphHopper [navigate endpoint](https://docs.graphhopper.com/#tag/Navigation-API), e.g.
     * `https://graphhopper.com/api/1/navigate/` or a self-hosted GraphHopper with the
     * navigation module. Profiles are GraphHopper profiles like "car", "bike" or "foot".
     */
    class GraphHopper(baseUrl: String, accessToken: String) :
        MapboxCompatible(baseUrl, accessToken, USER_GRAPHHOPPER, PROFILE_CAR) {

        override fun createUrl(request: RouteRequest): HttpUrl {
            val language = request.language.language
            val url = super.createUrl(request).newBuilder()
                // GraphHopper uses its own codes for some languages, e.g. "kz" for Kazakh. With an
                // unknown code it answers in English.
                .setQueryParameter("language", GRAPHHOPPER_LANGUAGES[language] ?: language)
                .build()
            val token = accessToken?.takeIf { it.isNotBlank() } ?: return url
            // Tokens with a Mapbox style "pk." prefix (needed by the Mapbox SDK this library used
            // before) are accepted as access_token only, GraphHopper rejects them as "key".
            // Plain GraphHopper API keys are sent as "key", the parameter GraphHopper documents.
            if (token.startsWith(MAPBOX_TOKEN_PREFIX)) {
                return url
            }
            return url.newBuilder().addQueryParameter("key", token).build()
        }
    }

    /**
     * Any other Mapbox Directions API compatible server, e.g. a self-hosted proxy.
     */
    class Other @JvmOverloads constructor(
        baseUrl: String,
        accessToken: String,
        user: String = USER_MAPBOX,
        defaultProfile: String = PROFILE_DRIVING,
    ) : MapboxCompatible(baseUrl, accessToken, user, defaultProfile)

    /**
     * [Valhalla](https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/) with its
     * OSRM compatible output. Profiles are Valhalla costing models like "auto", "bicycle",
     * "pedestrian", "truck" or "motor_scooter".
     *
     * @param baseUrl server url, with or without the trailing `/route`
     * @param apiKey sent as `api_key` query parameter, for hosted Valhalla services
     * @param costingOptions Valhalla `costing_options`, e.g. `{"auto": {"top_speed": 130}}`
     * @param speedLimits load speed limits along the route with an additional
     * `trace_attributes` request, Valhalla doesn't include them in routes. Routes are still
     * returned without speed limits if that request fails.
     */
    class Valhalla @JvmOverloads constructor(
        baseUrl: String,
        private val apiKey: String? = null,
        private val costingOptions: JsonObject? = null,
        private val speedLimits: Boolean = true,
    ) : RoutingService() {

        override val baseUrl: String = baseUrl.trimEnd('/').removeSuffix("/route")
        override val user: String = USER_VALHALLA
        override val defaultProfile: String = PROFILE_AUTO
        override val accessToken: String? = apiKey

        override fun createHttpRequest(request: RouteRequest): Request {
            val url = "$baseUrl/route".toHttpUrl().newBuilder()
                .apply { apiKey?.let { addQueryParameter("api_key", it) } }
                .build()
            return Request.Builder()
                .url(url)
                .post(createBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        override fun enrichResponse(
            response: DirectionsResponse,
            request: RouteRequest,
            httpClient: OkHttpClient,
        ): DirectionsResponse {
            if (!speedLimits) {
                return response
            }
            return response.copy(routes = response.routes.map { route ->
                runCatching { loadSpeedLimits(route, request, httpClient) }
                    .getOrElse { error ->
                        Timber.w(error, "Loading speed limits from Valhalla failed")
                        route
                    }
            })
        }

        private fun loadSpeedLimits(
            route: DirectionsRoute,
            request: RouteRequest,
            httpClient: OkHttpClient,
        ): DirectionsRoute {
            val body = ValhallaSpeedLimits.createTraceAttributesBody(route.geometry, request.profile ?: defaultProfile)
            val httpRequest = Request.Builder()
                .url("$baseUrl/trace_attributes".toHttpUrl().newBuilder()
                    .apply { apiKey?.let { addQueryParameter("api_key", it) } }
                    .build())
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val traceResponse = httpClient.newCall(httpRequest).execute().use { response ->
                check(response.isSuccessful) { "trace_attributes failed with HTTP ${response.code}" }
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
            return ValhallaSpeedLimits.applyTo(route, traceResponse)
        }

        internal fun createBody(request: RouteRequest): JsonObject = buildJsonObject {
            val units = if (request.voiceUnits == UnitType.IMPERIAL) "miles" else "kilometers"
            put("format", "osrm")
            put("shape_format", GEOMETRY_POLYLINE6)
            put("banner_instructions", true)
            put("voice_instructions", true)
            put("costing", request.profile ?: defaultProfile)
            put("language", request.language.toLanguageTag())
            put("units", units)
            put("alternates", if (request.alternatives) 2 else 0)
            putJsonObject("directions_options") {
                put("units", units)
                put("language", request.language.toLanguageTag())
            }
            costingOptions?.let { put("costing_options", it) }
            putJsonArray("locations") {
                request.coordinates.forEachIndexed { index, point ->
                    addJsonObject {
                        put("lon", point.longitude())
                        put("lat", point.latitude())
                        put("type", "break")
                        if (index == 0) {
                            request.originBearing?.let { bearing ->
                                put("heading", bearing.toInt())
                                put("heading_tolerance", BEARING_TOLERANCE)
                            }
                        }
                        request.waypointNames?.getOrNull(index)?.takeIf { it.isNotEmpty() }
                            ?.let { put("name", it) }
                        if (request.approaches?.getOrNull(index) == APPROACH_CURB) {
                            put("preferred_side", "same")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val USER_MAPBOX = "mapbox"
        const val USER_GRAPHHOPPER = "gh"
        const val USER_VALHALLA = "valhalla"

        const val PROFILE_DRIVING_TRAFFIC = "driving-traffic"
        const val PROFILE_DRIVING = "driving"
        const val PROFILE_WALKING = "walking"
        const val PROFILE_CYCLING = "cycling"
        const val PROFILE_CAR = "car"
        const val PROFILE_AUTO = "auto"

        const val APPROACH_UNRESTRICTED = "unrestricted"
        const val APPROACH_CURB = "curb"

        private const val MAPBOX_TOKEN_PREFIX = "pk."
        private val GRAPHHOPPER_LANGUAGES = mapOf("kk" to "kz")
        private const val GEOMETRY_POLYLINE6 = "polyline6"
        private const val OVERVIEW_FULL = "full"
        private const val BEARING_TOLERANCE = 90
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Recreates the service a route was requested from, using the [RouteOptions] stored in
         * the route. Returns null if the route has no options.
         */
        @JvmStatic
        fun fromRouteOptions(options: RouteOptions?): RoutingService? {
            options ?: return null
            val token = options.accessToken
            return when (options.user) {
                USER_VALHALLA -> Valhalla(options.baseUrl, token)
                USER_GRAPHHOPPER -> GraphHopper(options.baseUrl, token.orEmpty())
                else -> MapboxCompatible(options.baseUrl, token, options.user, options.profile)
            }
        }
    }
}

/**
 * Request parameters for a [RoutingService].
 *
 * @param waypointNames names of all coordinates, starting with the origin
 * @param approaches approach of all coordinates, starting with the origin, e.g.
 * [RoutingService.APPROACH_CURB] to arrive on the side of the road the waypoint is on
 */
data class RouteRequest @JvmOverloads constructor(
    val origin: Point,
    val destination: Point,
    val waypoints: List<Point> = emptyList(),
    val profile: String? = null,
    val language: java.util.Locale = java.util.Locale.getDefault(),
    val voiceUnits: UnitType = UnitType.METRIC,
    val alternatives: Boolean = false,
    /** Direction the user is heading at the origin in degrees, improves the route start. */
    val originBearing: Double? = null,
    val annotations: List<String> = DEFAULT_ANNOTATIONS,
    val waypointNames: List<String?>? = null,
    val approaches: List<String?>? = null,
    /** Road types to avoid, e.g. "toll", "motorway" or "ferry". Not supported by Valhalla. */
    val exclude: String? = null,
) {
    val coordinates: List<Point>
        get() = listOf(origin) + waypoints + destination

    companion object {
        const val ANNOTATION_DURATION = "duration"
        const val ANNOTATION_DISTANCE = "distance"
        const val ANNOTATION_SPEED = "speed"
        const val ANNOTATION_MAXSPEED = "maxspeed"
        const val ANNOTATION_CONGESTION = "congestion"

        /** Speed limits and distances, used for the speed limit view. */
        @JvmField
        val DEFAULT_ANNOTATIONS = listOf(
            ANNOTATION_MAXSPEED,
            ANNOTATION_SPEED,
            ANNOTATION_DISTANCE,
            ANNOTATION_DURATION,
        )
    }
}
