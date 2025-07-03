package org.maplibre.navigation.android.example

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import okhttp3.Request
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER
import org.maplibre.geojson.Point
import org.maplibre.navigation.android.example.databinding.ActivityNavigationUiBinding
import org.maplibre.navigation.android.navigation.ui.v5.MapRouteData
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncher
import org.maplibre.navigation.android.navigation.ui.v5.NavigationViewOptions
import org.maplibre.navigation.android.navigation.ui.v5.OnNavigationReadyCallback
import org.maplibre.navigation.android.navigation.ui.v5.camera.NavigationCamera
import org.maplibre.navigation.android.navigation.ui.v5.listeners.NavigationListener
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.NavigationConstants
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

class NavigationUIActivity : ComponentActivity(), MapLibreMap.OnMapClickListener,
    OnNavigationReadyCallback, NavigationListener {

    // Navigation related variables
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var waypoint: Point? = null
    private var locationComponent: LocationComponent? = null

    private lateinit var binding: ActivityNavigationUiBinding

    private var simulateRoute = false
    var mapLibreMap: MapLibreMap? = null

    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        binding = ActivityNavigationUiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navigationView.apply {
            onCreate(savedInstanceState, mapStyleUri = "https://api.maptiler.com/maps/streets-v2/style.json?key=ZkZSWT2Q0ta4f3S1VyrZ")
            initialize(
                simulateRoute,
                onMapReadyCallback = {
                    this@NavigationUIActivity.mapLibreMap = it
                    val initialMapCameraPosition =
                        CameraPosition.Builder()
                            .target(LatLng(43.230633, 76.933810))
                            .zoom(16.0)
                            .build()
                    it.cameraPosition = initialMapCameraPosition
                    it.setMaxZoomPreference(18.0)
                    it.setMinZoomPreference(5.0)
                    getDrawable(R.drawable.ic_car_top)?.let {
                        mapLibreMap?.style?.addImage("DRIVER_MARKER_ICON_ID",
                            it
                        )
                    }
                    val symbolOptions = SymbolOptions()
                        .withLatLng(LatLng(43.230361,76.930137))
                        .withIconImage("DRIVER_MARKER_ICON_ID")
                        .withIconAnchor(ICON_ANCHOR_CENTER)

                    binding.navigationView.addSymbol(symbolOptions)
                }
            )

        }

        binding.locate.setOnClickListener {
//            val initialMapCameraPosition =
//                CameraPosition.Builder()
//                    .target(LatLng(43.230633, 76.933810))
//                    .zoom(16.0)
//                    .build()
//            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(initialMapCameraPosition))
            mapLibreMap?.locationComponent?.setCameraMode(
                CameraMode.TRACKING_GPS,
                750,
                16.0,
                null,
                null,
                null
            )

            mapLibreMap?.locationComponent?.renderMode = RenderMode.GPS
        }

//        binding.mapView.apply {
//            onCreate(savedInstanceState)
//            getMapAsync(this@NavigationUIActivity)
//        }

        val points = mutableListOf<Point>()
//        points.add(Pair(76.928316, 43.236109))
        points.add(Point.fromLngLat(76.933810, 43.230633))
        points.add(Point.fromLngLat(76.920187, 43.236783))
//        points.add(Pair(76.930137, 43.230361))

        binding.simulateRouteSwitch.setOnCheckedChangeListener { _, isChecked ->
//            mapLibreMap?.locationComponent?.cameraMode = CameraMode.TRACKING
            simulateRoute = isChecked
            if (simulateRoute) {

            } else {

            }
            mapLibreMap?.locationComponent?.setCameraMode(CameraMode.TRACKING_GPS, 100, 16.0, null, null, null)
        }
        binding.navigationView.showInstructionView()

        binding.startRouteButton.setOnClickListener {
            if (isStarted) {
                binding.navigationView.stopNavigation()
                binding.navigationView.retrieveNavigationMapLibreMap()?.removeRoute()
                isStarted = false
            } else {
                isStarted = true
                binding.navigationView.calculateRouteAndStartNavigation(
                    MapRouteData(
                        getString(R.string.mapbox_access_token),
                        userLocation = Point.fromLngLat(76.930137, 43.230361),
                        stops = points,
                        destination = Point.fromLngLat(76.930137, 43.230361),
                    )
                )
                binding.navigationView.enableNavigatorSound(false)

            }
        }

//        binding.clearPoints.setOnClickListener {
//            if (::mapboxMap.isInitialized) {
//                mapboxMap.markers.forEach {
//                    mapboxMap.removeMarker(it)
//                }
//            }
//            destination = null
//            waypoint = null
//            it.visibility = View.GONE
//            binding.startRouteLayout.visibility = View.GONE
//
//            navigationMapRoute?.removeRoute()
//        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        // Get an instance of the component
//        locationComponent = mapLibreMap.locationComponent

        locationComponent?.let {
            // Activate with a built LocationComponentActivationOptions object
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build(),
            )

            // Enable to make component visible
            it.isLocationComponentEnabled = true

            // Set the component's camera mode
            it.cameraMode = CameraMode.TRACKING_GPS_NORTH

            // Set the component's render mode
            it.renderMode = RenderMode.NORMAL
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
//        var addMarker = true
//        when {
//            destination == null -> destination = Point.fromLngLat(point.longitude, point.latitude)
//            waypoint == null -> waypoint = Point.fromLngLat(point.longitude, point.latitude)
//            else -> {
//                Toast.makeText(this, "Only 2 waypoints supported", Toast.LENGTH_LONG).show()
//                addMarker = false
//            }
//        }
//
//        if (addMarker) {
//            mapboxMap.addMarker(MarkerOptions().position(point))
//            binding.clearPoints.visibility = View.VISIBLE
//        }
//        calculateRoute()
        return true
    }

    private fun calculateRoute() {
//        binding.startRouteLayout.visibility = View.GONE
//        val userLocation = mapLibreMap.locationComponent.lastKnownLocation
        val destination = destination
//        if (userLocation == null) {
//            Timber.d("calculateRoute: User location is null, therefore, origin can't be set.")
//            return
//        }

        if (destination == null) {
            return
        }

//        val origin = Point.fromLngLat(userLocation.longitude, userLocation.latitude)
//        if (TurfMeasurement.distance(origin, destination, TurfConstants.UNIT_METERS) < 50) {
//            binding.startRouteLayout.visibility = View.GONE
//            return
//        }

        val navigationRouteBuilder = NavigationRoute.builder(this).apply {
            this.accessToken(getString(R.string.mapbox_access_token))
//            this.origin(origin)
            this.destination(destination)
            this.voiceUnits(UnitType.METRIC)
            this.alternatives(true)
            // If you are using this with the GraphHopper Directions API, you need to uncomment user and profile here.
            //this.user("gh")
            //this.profile("car")
            this.baseUrl(getString(R.string.base_url))
        }


        navigationRouteBuilder.addWaypoint(
            Point.fromLngLat(76.930137, 43.230361)
        )

        navigationRouteBuilder.addWaypoint(
            Point.fromLngLat(76.928316, 43.236109)
        )

        navigationRouteBuilder.addWaypoint(
            Point.fromLngLat(76.920187, 43.236783)
        )


        navigationRouteBuilder.build().getRoute(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>,
            ) {
                Timber.d("Url: %s", (call.request() as Request).url.toString())
                response.body()?.let { response ->
                    if (response.routes.isNotEmpty()) {
                        val maplibreResponse =
                            DirectionsResponse.fromJson(
                                response.toJson()
                            );
                        this@NavigationUIActivity.route = maplibreResponse.routes.first()
                        navigationMapRoute?.addRoutes(maplibreResponse.routes)
//                        binding.startRouteLayout.visibility = View.VISIBLE
                    }
                }

            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                Timber.e(throwable, "onFailure: navigation.getRoute()")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        binding.navigationView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.navigationView.onPause()
    }

    override fun onStart() {
        super.onStart()
        binding.navigationView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.navigationView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.navigationView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
//        if (::mapboxMap.isInitialized) {
//            mapboxMap.removeOnMapClickListener(this)
//        }
//        binding.navigationView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.navigationView.onSaveInstanceState(outState)
    }

    override fun onNavigationReady(isRunning: Boolean) {
//        val options = NavigationViewOptions.builder()
//        options.navigationListener(this)
//        extractRoute(options)
//        extractConfiguration(options)
//        options.navigationOptions(MapLibreNavigationOptions.Builder().build())
//        binding.navigationView.startNavigation(options.build())
    }

    override fun onCancelNavigation() {
        finishNavigation()
    }

    override fun onNavigationFinished() {
        finishNavigation()
    }

    override fun onNavigationRunning() {
        // Intentionally empty
    }

    private fun extractRoute(options: NavigationViewOptions.Builder) {
        val route = NavigationLauncher.extractRoute(this)
        options.directionsRoute(route)
    }

    private fun extractConfiguration(options: NavigationViewOptions.Builder) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        options.shouldSimulateRoute(
            preferences.getBoolean(
                NavigationConstants.NAVIGATION_VIEW_SIMULATE_ROUTE,
                false
            )
        )
    }

    private fun finishNavigation() {
        NavigationLauncher.cleanUpPreferences(this)
//        binding.navigationView.stopNavigation()
        finish()
    }
}
