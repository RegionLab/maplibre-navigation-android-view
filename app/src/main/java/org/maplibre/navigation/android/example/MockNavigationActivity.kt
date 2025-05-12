package org.maplibre.navigation.android.example

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfMeasurement
import okhttp3.Request
import org.maplibre.navigation.android.example.databinding.ActivityMockNavigationBinding
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.core.instruction.Instruction
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.milestone.Milestone
import org.maplibre.navigation.core.milestone.MilestoneEventListener
import org.maplibre.navigation.core.milestone.RouteMilestone
import org.maplibre.navigation.core.milestone.Trigger
import org.maplibre.navigation.core.milestone.TriggerProperty
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.AndroidMapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.NavigationEventListener
import org.maplibre.geojson.Point
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.lang.ref.WeakReference

class MockNavigationActivity :
    AppCompatActivity(),
    OnMapReadyCallback,
    MapLibreMap.OnMapClickListener,
    ProgressChangeListener,
    NavigationEventListener,
    MilestoneEventListener,
    OffRouteListener {
    private val BEGIN_ROUTE_MILESTONE = 1001
    private lateinit var mapLibreMap: MapLibreMap

    // Navigation related variables
    private var locationEngine: ReplayRouteLocationEngine =
        ReplayRouteLocationEngine()
    private lateinit var navigation: MapLibreNavigation
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var waypoint: Point? = null
    private var locationComponent: LocationComponent? = null

    private lateinit var binding: ActivityMockNavigationBinding

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMockNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@MockNavigationActivity)
        }

        navigation = AndroidMapLibreNavigation(applicationContext)
        navigation.addMilestone(
            RouteMilestone(
                identifier = BEGIN_ROUTE_MILESTONE,
                instruction = BeginRouteInstruction(),
                trigger = Trigger.all(
                    Trigger.lt(
                        TriggerProperty.STEP_INDEX, 3
                    ),
                    Trigger.gt(
                        TriggerProperty.STEP_DISTANCE_TOTAL_METERS, 200
                    ),
                    Trigger.gte(
                        TriggerProperty.STEP_DISTANCE_TRAVELED_METERS, 75
                    ),
                ),
            )
        )

        binding.startRouteButton.setOnClickListener {
            route?.let { route ->
                binding.startRouteButton.visibility = View.INVISIBLE

                // Attach all of our navigation listeners.
                navigation.apply {
                    addNavigationEventListener(this@MockNavigationActivity)
                    addProgressChangeListener(this@MockNavigationActivity)
                    addMilestoneEventListener(this@MockNavigationActivity)
                    addOffRouteListener(this@MockNavigationActivity)
                }

                locationEngine.also {
                    it.assign(route)
                    navigation.locationEngine = it
                    navigation.startNavigation(route)
                    if (::mapLibreMap.isInitialized) {
                        mapLibreMap.removeOnMapClickListener(this)
                    }
                }
            }
        }

        binding.newLocationFab.setOnClickListener {
            newOrigin()
        }

        binding.clearPoints.setOnClickListener {
            if (::mapLibreMap.isInitialized) {
                mapLibreMap.markers.forEach {
                    mapLibreMap.removeMarker(it)
                }
            }
            destination = null
            waypoint = null
            it.visibility = View.GONE

            navigationMapRoute?.removeRoute()
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.mapLibreMap = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri(getString(R.string.map_style_light))
        ) { style ->
            enableLocationComponent(style)
            navigationMapRoute = NavigationMapRoute(navigation, binding.mapView, mapLibreMap)

            mapLibreMap.addOnMapClickListener(this)
            Snackbar.make(
                findViewById(R.id.container),
                "Tap map to place waypoint",
                Snackbar.LENGTH_LONG,
            ).show()

            newOrigin()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        // Get an instance of the component
        locationComponent = mapLibreMap.locationComponent

        locationComponent?.let {
            // Activate with a built LocationComponentActivationOptions object
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build(),
            )

            // Enable to make component visible
            it.isLocationComponentEnabled = true

            // Set the component's camera mode
            it.cameraMode = CameraMode.TRACKING

            // Set the component's render mode
            it.renderMode = RenderMode.GPS

//            it.locationEngine = locationEngine
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        var addMarker = true
        when {
            destination == null -> destination = Point.fromLngLat(point.longitude, point.latitude, point.altitude)
            waypoint == null -> waypoint = Point.fromLngLat(point.longitude, point.latitude, point.altitude)
            else -> {
                Toast.makeText(this, "Only 2 waypoints supported", Toast.LENGTH_LONG).show()
                addMarker = false
            }
        }

        if (addMarker) {
            mapLibreMap.addMarker(MarkerOptions().position(point))
        }
        binding.clearPoints.visibility = View.VISIBLE

        binding.startRouteButton.visibility = View.VISIBLE
        calculateRoute()
        return true
    }

    private fun calculateRoute() {
        val userLocation = locationEngine.lastLocation
        val destination = destination
        if (userLocation == null) {
            Timber.d("calculateRoute: User location is null, therefore, origin can't be set.")
            return
        }

        if (destination == null) {
            return
        }

        val origin = Point.fromLngLat(userLocation.longitude, userLocation.latitude, userLocation.altitude ?: 0.0)
        if (TurfMeasurement.distance(origin, destination, TurfConstants.UNIT_METERS) < 50) {
            binding.startRouteButton.visibility = View.GONE
            return
        }

        val navigationRouteBuilder = NavigationRoute.builder(this).apply {
            this.accessToken(getString(R.string.mapbox_access_token))
            this.origin(origin)
            this.destination(destination)
            this.voiceUnits(UnitType.METRIC)
            this.alternatives(true)
            this.baseUrl(getString(R.string.base_url))
        }

        navigationRouteBuilder.build().getRoute(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>,
            ) {
                Timber.d("Url: %s", (call.request() as Request).url.toString())
                response.body()?.let { response ->
                    if (response.routes.isNotEmpty()) {
                        val maplibreResponse = DirectionsResponse.fromJson(response.toJson());
                        val directionsRoute = maplibreResponse.routes.first()
                        this@MockNavigationActivity.route = directionsRoute
                        navigationMapRoute?.addRoutes(maplibreResponse.routes)
                    }
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                Timber.e(throwable, "onFailure: navigation.getRoute()")
            }
        })
    }

    override fun onProgressChange(location: Location, routeProgress: RouteProgress) {
    }

    override fun onRunning(running: Boolean) {
    }

    override fun onMilestoneEvent(
        routeProgress: RouteProgress,
        instruction: String?,
        milestone: Milestone,
    ) {
    }

    override fun userOffRoute(location: Location) {
    }

    private class BeginRouteInstruction : Instruction {

        override fun buildInstruction(routeProgress: RouteProgress): String {
            return "Have a safe trip!"
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        navigation.onDestroy()
        if (::mapLibreMap.isInitialized) {
            mapLibreMap.removeOnMapClickListener(this)
        }
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    private class MyBroadcastReceiver internal constructor(navigation: MapLibreNavigation) :
        BroadcastReceiver() {
        private val weakNavigation: WeakReference<MapLibreNavigation> = WeakReference(navigation)

        override fun onReceive(context: Context, intent: Intent) {
            weakNavigation.get()?.stopNavigation()
        }
    }

    private fun newOrigin() {
        mapLibreMap.let {
            val latLng = LatLng(52.039176, 5.550339)
            locationEngine.assignLastLocation(
                Point.fromLngLat(latLng.longitude, latLng.latitude),
            )
            it.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12.0))
        }
    }
}
