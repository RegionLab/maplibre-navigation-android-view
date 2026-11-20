package org.maplibre.navigation.android.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER
import org.maplibre.geojson.Point
import org.maplibre.navigation.android.example.databinding.ActivityNavigationUiBinding
import org.maplibre.navigation.android.navigation.ui.v5.NavigationRequest
import org.maplibre.navigation.android.navigation.ui.v5.NavigationViewListener
import org.maplibre.navigation.android.navigation.ui.v5.RoutingClient
import org.maplibre.navigation.android.navigation.ui.v5.RoutingLogger
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.navigation.android.tts.sherpa.InstalledVoice
import org.maplibre.navigation.android.tts.sherpa.SherpaSpeechPlayerFactory
import org.maplibre.navigation.android.tts.sherpa.SherpaVoice
import org.maplibre.navigation.android.tts.sherpa.SherpaVoiceStore
import timber.log.Timber
import java.util.Locale

/**
 * Shows how to use [org.maplibre.navigation.android.navigation.ui.v5.NavigationView]:
 * lifecycle, map setup, starting and stopping navigation and listening to navigation events.
 */
class NavigationUIActivity : ComponentActivity() {

    private lateinit var binding: ActivityNavigationUiBinding
    private var mapLibreMap: MapLibreMap? = null

    private val navigationListener = object : NavigationViewListener {
        override fun onNavigationStarted(route: DirectionsRoute) {
            binding.startRouteButton.setText(R.string.stop_navigation)
        }

        override fun onNavigationStopped() {
            binding.startRouteButton.setText(R.string.start_navigation)
        }

        override fun onOffRoute(location: Location) {
            Timber.d("Off route, requesting a new route")
        }

        override fun onRerouteFailed(error: Throwable) {
            Timber.w(error, "Reroute failed")
        }

        override fun onSpeedingChanged(isSpeeding: Boolean) {
            Timber.d("Speeding: %s", isSpeeding)
        }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            Toast.makeText(this@NavigationUIActivity, R.string.arrived, Toast.LENGTH_SHORT).show()
            binding.navigationView.stopNavigation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        binding = ActivityNavigationUiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navigationView.apply {
            onCreate(savedInstanceState, mapStyleUri = getString(R.string.map_style_light))
            // Forwards start/resume/pause/stop/destroy, no need to override them in the activity
            attachLifecycle(this@NavigationUIActivity)
            setNavigationListener(navigationListener)
            configureUi(showSpeedLimitView = true, showTripSummary = true)
            // Offline voice instructions, the Android text-to-speech speaks until the voice is downloaded
            setSpeechPlayerFactory(SherpaSpeechPlayerFactory(this@NavigationUIActivity, voiceStore))
            enableNavigatorSound(true)
            initialize(
                onMapReadyCallback = ::onMapReady,
                initialMapCameraPosition = CameraPosition.Builder()
                    .target(LatLng(43.230633, 76.933810))
                    .zoom(16.0)
                    .build(),
                // Logs route requests to Logcat (tag "MapLibreRouting") in debug builds
                routingClient = RoutingClient(
                    logger = if (BuildConfig.DEBUG) RoutingLogger.logcat() else null
                ),
            )
            // Keeps the trip summary, re-center button and user location above the controls.
            // Works the same for a BottomSheetBehavior view while it is dragged.
            trackBottomOverlay(binding.startRouteLayout)
        }

        downloadVoice()

        binding.locate.setOnClickListener {
            mapLibreMap?.locationComponent?.apply {
                setCameraMode(CameraMode.TRACKING_GPS, 750, 16.0, null, null, null)
                renderMode = RenderMode.GPS
            }
        }

        binding.startRouteButton.setOnClickListener {
            if (binding.navigationView.isNavigating) {
                binding.navigationView.stopNavigation()
            } else {
                startNavigation()
            }
        }
    }

    private val voiceStore by lazy { SherpaVoiceStore(this) }

    private fun downloadVoice() {
        val voice = voiceStore.voiceFor(NAVIGATION_LANGUAGE.language) ?: return
        if (voiceStore.isInstalled(voice)) {
            return
        }
        voiceStore.download(voice, object : SherpaVoiceStore.DownloadListener {
            override fun onInstalled(voice: InstalledVoice) {
                Timber.d("Voice %s installed", voice.voice.id)
                Toast.makeText(this@NavigationUIActivity, R.string.voice_installed, Toast.LENGTH_SHORT).show()
            }

            override fun onError(voice: SherpaVoice, error: Throwable) {
                Timber.w(error, "Voice download failed")
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.navigationView.onSaveInstanceState(outState)
    }

    private fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map
        map.setMaxZoomPreference(18.0)
        map.setMinZoomPreference(5.0)
        getDrawable(R.drawable.ic_car_top)?.let { map.style?.addImage(DRIVER_MARKER_ICON_ID, it) }
        binding.navigationView.addSymbol(
            SymbolOptions()
                .withLatLng(LatLng(43.230361, 76.930137))
                .withIconImage(DRIVER_MARKER_ICON_ID)
                .withIconAnchor(ICON_ANCHOR_CENTER)
        )
    }

    private fun startNavigation() {
        val request = NavigationRequest(
            origin = Point.fromLngLat(76.93390611559153, 43.23088776754339),
            stops = listOf(
                Point.fromLngLat(76.93348165601492, 43.22596650770448),
                Point.fromLngLat(76.9333378225565, 43.22584777694432),
            ),
            destination = Point.fromLngLat(76.91994253546, 43.229125980195946),
            // Valhalla demo server, works without a key. E.g. RoutingService.Mapbox(accessToken = ...)
            routingService = SampleRouting.service(this),
            language = NAVIGATION_LANGUAGE,
            simulateRoute = binding.simulateRouteSwitch.isChecked,
        )
        binding.navigationView.startNavigation(request) { error ->
            Timber.e(error, "Route request failed")
            Toast.makeText(this, R.string.route_request_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val DRIVER_MARKER_ICON_ID = "DRIVER_MARKER_ICON_ID"
        val NAVIGATION_LANGUAGE: Locale = Locale.forLanguageTag("ru")
    }
}
