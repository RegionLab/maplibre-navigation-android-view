package org.maplibre.navigation.android.example

import android.location.Location as AndroidLocation
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.geojson.Point
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import okhttp3.Request
import org.maplibre.navigation.android.example.databinding.ActivitySnapToRouteNavigationBinding
import org.maplibre.navigation.android.navigation.ui.v5.notification.NavigationNotification
import org.maplibre.navigation.android.navigation.ui.v5.notification.service.NavigationNotificationService
import org.maplibre.navigation.android.navigation.ui.v5.notification.service.NavigationNotificationServiceConnection
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.AndroidMapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber

/**
 * This activity shows you how navigation with active foreground notification is setup.
 *
 * You need to do the following steps to enable foreground notification:
 * 1. When [MapLibreNavigation] is ready, create instance of [NavigationNotificationServiceConnection]
 * 2. Start the service with [NavigationNotificationServiceConnection.start]
 * 3. Don't forget to disconnect the service when the activity gets destroyed
 *
 * You can also handle the lifecycle of [NavigationNotificationService] by yourself. Using your own
 * custom [NavigationNotification] can done by injecting in [NavigationNotificationServiceConnection] constructor.
 */
class NavigationWithForegroundNotificationActivity : AppCompatActivity(), OnMapReadyCallback,
    ProgressChangeListener {

    private lateinit var binding: ActivitySnapToRouteNavigationBinding
    private lateinit var mapLibreMap: MapLibreMap
    private var locationEngine: ReplayRouteLocationEngine =
        ReplayRouteLocationEngine()
    private lateinit var navigation: MapLibreNavigation
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var notificationServiceConnection: NavigationNotificationServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySnapToRouteNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navigation = AndroidMapLibreNavigation(
            this,
            MapLibreNavigationOptions(snapToRoute = true)
        ).apply {
            snapEngine
            addProgressChangeListener(this@NavigationWithForegroundNotificationActivity)
        }

        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@NavigationWithForegroundNotificationActivity)
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
            startNavigationService(navigation)
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

    private fun startNavigationService(mapLibreNavigation: MapLibreNavigation) {
        notificationServiceConnection = NavigationNotificationServiceConnection(this, mapLibreNavigation)
        notificationServiceConnection?.start(this)
    }

    private fun calculateRouteAndStartNavigation() {
        val navigationRouteBuilder = NavigationRoute.builder(this).apply {
            this.accessToken(getString(R.string.mapbox_access_token))
            this.origin(Point.fromLngLat(9.7536318, 52.3717979))
            this.addWaypoint(Point.fromLngLat(9.741052, 52.360496))
            this.destination(Point.fromLngLat(9.756259, 52.342620))
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
                response.body()?.let { responseBody ->
                    if (responseBody.routes.isNotEmpty()) {
                        val maplibreResponse = DirectionsResponse.fromJson(responseBody.toJson());
                        val directionsRoute = maplibreResponse.routes.first()
                        this@NavigationWithForegroundNotificationActivity.route = directionsRoute
                        navigationMapRoute?.addRoutes(maplibreResponse.routes)

                        startNavigation()
                    }
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                Timber.e(throwable, "onFailure: navigation.getRoute()")
            }
        })
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
        locationComponent?.forceLocationUpdate(
            AndroidLocation(location.provider).apply {
                latitude = location.latitude
                longitude = location.longitude
                bearing = location.bearing ?: 0f
                accuracy = location.accuracyMeters ?: 0.0f
            }
        )
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
        notificationServiceConnection?.stop(this)
        navigation.onDestroy()
        binding.mapView.onDestroy()
    }
}
