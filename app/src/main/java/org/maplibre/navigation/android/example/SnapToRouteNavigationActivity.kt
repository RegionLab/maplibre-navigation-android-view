package org.maplibre.navigation.android.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.navigation.android.example.databinding.ActivitySnapToRouteNavigationBinding
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.android.navigation.ui.v5.toMapLibre
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.location.toAndroidLocation
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.navigation.AndroidMapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.navigation.core.snap.SnapToRoute
import org.maplibre.spatialk.geojson.Point
import org.maplibre.navigation.android.navigation.ui.v5.RouteRequest
import timber.log.Timber

/**
 * This activity shows you simulated navigation with enabled route snapping.
 *
 * You need to do the following steps to enable route snapping:
 * 1. Disable default location engine by set [LocationComponentActivationOptions.useDefaultLocationEngine] to false.
 * 2. Get snapped location from [ProgressChangeListener] callback and set it to [LocationComponent] by [LocationComponent.forceLocationUpdate] method.
 * 3. Activate route snapping by set [MapLibreNavigationOptions.snapToRoute] to true.
 *
 * By default [SnapToRoute] is used. If you want to use your own snapping logic, you can set with [MapLibreNavigation.setSnapEngine] for your own implementation.
 */
class SnapToRouteNavigationActivity : AppCompatActivity(), OnMapReadyCallback,
    ProgressChangeListener {

    private lateinit var binding: ActivitySnapToRouteNavigationBinding
    private lateinit var mapLibreMap: MapLibreMap
    private var locationEngine: ReplayRouteLocationEngine =
        ReplayRouteLocationEngine()
    private lateinit var navigation: MapLibreNavigation
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySnapToRouteNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navigation = AndroidMapLibreNavigation(
            this,
            MapLibreNavigationOptions(snapToRoute = true)
        ).apply {
            snapEngine
            addProgressChangeListener(this@SnapToRouteNavigationActivity)
        }

        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@SnapToRouteNavigationActivity)
        }

        binding.btnFollow.setOnClickListener {
            followLocation()
        }
    }

    private var locationComponent: LocationComponent? = null

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.mapLibreMap = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri(getString(R.string.map_style_light))
        ) { style ->
            enableLocationComponent(style)
            navigationMapRoute = NavigationMapRoute(navigation, binding.mapView, mapLibreMap)
            calculateRouteAndStartNavigation()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        locationComponent = mapLibreMap.locationComponent
        mapLibreMap.locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(
                this,
                style,
            )
                .useDefaultLocationEngine(false)
                .build()
        )

        followLocation()

        mapLibreMap.locationComponent.isLocationComponentEnabled = true
    }

    private fun followLocation() {
        if (!mapLibreMap.locationComponent.isLocationComponentActivated) {
            return
        }

        mapLibreMap.locationComponent.renderMode = RenderMode.GPS
        mapLibreMap.locationComponent.setCameraMode(
            CameraMode.TRACKING_GPS,
            object :
                OnLocationCameraTransitionListener {
                override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                    mapLibreMap.locationComponent.zoomWhileTracking(17.0)
                    mapLibreMap.locationComponent.tiltWhileTracking(60.0)
                }

                override fun onLocationCameraTransitionCanceled(cameraMode: Int) {}
            }
        )
    }

    private fun calculateRouteAndStartNavigation() {
        SampleRouting.client.getRoute(
            SampleRouting.service(this),
            RouteRequest(
                origin = Point(9.7536318, 52.3717979).toMapLibre(),
                destination = Point(9.756259, 52.342620).toMapLibre(),
                waypoints = listOf(Point(9.741052, 52.360496).toMapLibre()),
                alternatives = true,
            ),
            onRoutesReady = { response ->
                this@SnapToRouteNavigationActivity.route = response.routes.first()
                navigationMapRoute?.addRoutes(response.routes)
                startNavigation()
            },
            onError = { error -> Timber.e(error, "Route request failed") }
        )
    }

    fun startNavigation() {
        route?.let { route ->
            locationEngine.also { locationEngine ->
                locationEngine.assign(route)
                navigation.locationEngine = locationEngine
                navigation.startNavigation(route)
            }
        }
    }

    override fun onProgressChange(location: Location, routeProgress: RouteProgress) {
        // Update own location with the snapped location
        locationComponent?.forceLocationUpdate(location.toAndroidLocation())
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
        binding.mapView.onDestroy()
    }

}
