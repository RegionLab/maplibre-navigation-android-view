package org.maplibre.navigation.android.navigation.ui.v5

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.OnSymbolClickListener
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
import org.maplibre.navigation.android.navigation.ui.v5.camera.NavigationCamera
import org.maplibre.navigation.android.navigation.ui.v5.instruction.ImageCreator
import org.maplibre.navigation.android.navigation.ui.v5.instruction.InstructionView
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapLibreMap
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapLibreMapInstanceState
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapStyleOptimizer
import org.maplibre.navigation.android.navigation.ui.v5.summary.TripSummaryView
import org.maplibre.navigation.android.navigation.ui.v5.utils.LocaleUtils
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerFactory
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions

/**
 * Map with turn-by-turn navigation UI: route line, maneuver banner, voice instructions, speed and
 * speed limit, trip summary and a re-center button.
 *
 * Usage:
 * 1. [onCreate] and [attachLifecycle] (or forward [onStart], [onResume], [onPause], [onStop],
 *    [onDestroy] yourself), [onSaveInstanceState] from the host.
 * 2. [initialize] to load the map.
 * 3. [startNavigation] with a [NavigationRequest] or an already fetched route, [stopNavigation].
 *
 * Navigation events are delivered to the [NavigationViewListener] set with [setNavigationListener].
 * A destroyed view can't be used again.
 */
class NavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val mapView: MapView
    private val instructionView: InstructionView
    private val speedContainer: View
    private val speedLimitView: TextView
    private val speedView: TextView
    private val bottomPanel: View
    private val recenterButton: View
    private val tripSummaryView: TripSummaryView
    private val instructionTopMargin: Int
    private val speedContainerTopMargin: Int
    private val bottomPanelBottomMargin: Int

    private var viewModel: NavigationViewModel? = null
    private var navigationMap: NavigationMapLibreMap? = null
    private var symbolManager: SymbolManager? = null
    private var mapStyleOptimizer: NavigationMapStyleOptimizer? = null
    private var preNavigationLocationEngine: PreNavigationLocationEngine? = null
    private var routingClient = RoutingClient()
    private val routeRequestExecutor by lazy { RouteRequestExecutor(routingClient) }

    private var navigationListener: NavigationViewListener? = null
    private var speechPlayerFactory: SpeechPlayerFactory? = null
    private var onMapReadyCallback: OnMapReadyCallback? = null
    private var initialMapCameraPosition: CameraPosition? = null
    private var mapStyleUri: String? = null
    private var mapInstanceState: NavigationMapLibreMapInstanceState? = null
    private var pendingNavigationStart: (() -> Unit)? = null
    private var hostLifecycle: Lifecycle? = null

    private var isMapRequested = false
    private var isMapInitialized = false
    private var isStarted = false
    private var isResumed = false
    private var isCallbacksRegistered = false
    private var isDestroyed = false
    private var isMuted = false

    private var enableInstructionList = true
    private var showSpeedLimitView = false
    private var lightweightNavigationMap = true
    private var lightweightIdleMap = false
    private var idleMaxFps = UNLIMITED_FPS
    private var appliedMaxFps = UNLIMITED_FPS
    private var showTripSummary = false
    private var showRecenterButton = true
    private var vanishTraveledRoute = true
    private var announceReroute = true

    private var speedingTolerance = NavigationViewModel.DEFAULT_SPEEDING_TOLERANCE
    private var showUnknownSpeedLimit = false
    private val deviceUnitType = LocaleUtils().getUnitTypeForDeviceLocale(context)
    private val speedStyle = SpeedStyle.resolve(context)
    private var lastSpeed: Float? = null
    private var lastMaxSpeed: MaxSpeed? = null
    private var renderedSpeed: SpeedDisplay? = null

    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var overlayTopInset = 0
    private var overlayBottomInset = 0
    private var trackedBottomInset = 0
    private var appliedMapBottomInset = 0
    private var trackedBottomOverlay: View? = null
    private val overlayPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateTrackedBottomInset()
        true
    }
    private val applyMapBottomInset = Runnable {
        navigationMap?.updateBottomInset(appliedMapBottomInset)
    }

    private val onStyleChangedListener = MapView.OnDidFinishLoadingStyleListener {
        // A new style (e.g. day/night switch) brings its own layers, optimize them too
        mapStyleOptimizer?.onStyleLoaded()
    }

    private val cameraTrackingListener = object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() {
            updateRecenterButton(visible = true)
        }

        override fun onCameraTrackingChanged(currentMode: Int) = Unit
    }

    private val hostLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = this@NavigationView.onStart()
        override fun onResume(owner: LifecycleOwner) = this@NavigationView.onResume()
        override fun onPause(owner: LifecycleOwner) = this@NavigationView.onPause()
        override fun onStop(owner: LifecycleOwner) = this@NavigationView.onStop()
        override fun onDestroy(owner: LifecycleOwner) = this@NavigationView.onDestroy()
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                mapView.onLowMemory()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = mapView.onLowMemory()
    }

    val isNavigating: Boolean
        get() = viewModel?.isNavigating == true

    init {
        ThemeSwitcher.setTheme(context, attrs)
        inflate(context, R.layout.navigation_view_layout, this)
        mapView = findViewById(R.id.navigationMapView)
        instructionView = findViewById(R.id.instructionView)
        speedContainer = findViewById(R.id.speedContainer)
        speedLimitView = findViewById(R.id.speedLimitView)
        speedView = findViewById(R.id.speedView)
        bottomPanel = findViewById(R.id.bottomPanel)
        recenterButton = findViewById(R.id.recenterButton)
        tripSummaryView = findViewById(R.id.tripSummaryView)

        instructionView.elevation = INSTRUCTION_VIEW_ELEVATION
        instructionView.setInstructionListListener { visible ->
            navigationListener?.onInstructionListVisibilityChanged(visible)
        }
        recenterButton.setOnClickListener { recenterCamera() }

        instructionTopMargin = instructionView.topMargin
        speedContainerTopMargin = speedContainer.topMargin
        bottomPanelBottomMargin = bottomPanel.bottomMargin
        applySystemBarInsets()
        applyUiConfiguration()
    }

    // region Lifecycle

    /**
     * Creates the map. Pass the bundle stored with [onSaveInstanceState] to restore the map
     * camera tracking mode and padding.
     *
     * @param mapStyleUri style of the map, by default the style of the navigation theme
     */
    @JvmOverloads
    fun onCreate(savedInstanceState: Bundle?, mapStyleUri: String? = null) {
        mapView.onCreate(savedInstanceState)
        savedInstanceState?.let(::restoreNavigationMapInstanceState)
        this.mapStyleUri = mapStyleUri
        if (!isCallbacksRegistered) {
            context.registerComponentCallbacks(memoryCallbacks)
            isCallbacksRegistered = true
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * Forwards start, resume, pause, stop and destroy of [owner] to this view, so they don't have
     * to be called by hand. In a Fragment pass `viewLifecycleOwner`.
     */
    fun attachLifecycle(owner: LifecycleOwner) {
        hostLifecycle?.removeObserver(hostLifecycleObserver)
        hostLifecycle = owner.lifecycle.also { it.addObserver(hostLifecycleObserver) }
    }

    // MapView lifecycle calls must be balanced: hosts that forward both the activity lifecycle and
    // their own pause/resume (e.g. screens covered by overlays) would otherwise send duplicates,
    // so repeated calls are ignored and onStop() pauses first if onPause() was skipped.
    fun onStart() {
        if (isStarted) {
            return
        }
        isStarted = true
        mapView.onStart()
        navigationMap?.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        if (!isNavigating) {
            preNavigationLocationEngine?.start()
        }
    }

    fun onResume() {
        if (isResumed) {
            return
        }
        onStart()
        isResumed = true
        mapView.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        if (!isResumed) {
            return
        }
        isResumed = false
        mapView.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        if (!isStarted) {
            return
        }
        onPause()
        isStarted = false
        mapView.onStop()
        navigationMap?.onStop()
        // No need for GPS updates while the map isn't visible and navigation isn't running
        preNavigationLocationEngine?.stop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * Stops navigation and releases the map. In an Activity call it from `onDestroy`, in a
     * Fragment from `onDestroyView`.
     */
    @UiThread
    fun onDestroy() {
        if (isDestroyed) {
            return
        }
        isDestroyed = true
        onStop()
        shutdown()
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    /**
     * Only needed if the host doesn't use [attachLifecycle] and wants to report low memory itself,
     * the view already listens to memory pressure after [onCreate].
     */
    fun onLowMemory() {
        @Suppress("DEPRECATION")
        mapView.onLowMemory()
    }

    /**
     * Stores the map state and the navigation map settings (camera tracking mode, padding).
     * Navigation itself is not kept across activity recreation, it is stopped in [onDestroy].
     */
    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
        navigationMap?.saveStateWith(MAP_INSTANCE_STATE_KEY, outState)
    }

    /**
     * Restores the navigation map settings stored by [onSaveInstanceState].
     * Not required if the same bundle was already passed to [onCreate].
     */
    fun onRestoreInstanceState(savedInstanceState: Bundle) {
        restoreNavigationMapInstanceState(savedInstanceState)
        mapInstanceState?.let { navigationMap?.restoreFrom(it) }
    }

    /**
     * Hides the instruction list if it is shown.
     *
     * @return true if the back press was handled
     */
    fun onBackPressed(): Boolean = instructionView.handleBackPressed()

    // endregion

    // region Setup

    /**
     * Loads the map. Call after [onCreate].
     *
     * @param onMapReadyCallback called once the map and its style are loaded
     * @param initialMapCameraPosition camera position before the first location is known
     * @param locationEngine source of device locations, e.g. `GoogleLocationEngine` from the
     * `maplibre-navigation-location-gms` module. By default the MapLibre fused location engine.
     * @param routingClient client for route requests and rerouting, e.g. with your own
     * `OkHttpClient` for logging, caching or authentication headers
     */
    @JvmOverloads
    fun initialize(
        onMapReadyCallback: OnMapReadyCallback? = null,
        initialMapCameraPosition: CameraPosition? = null,
        locationEngine: LocationEngine? = null,
        routingClient: RoutingClient? = null,
    ) {
        // Guard against repeated initialize() calls while the map/style is still loading
        if (isMapRequested) {
            return
        }
        isMapRequested = true
        this.onMapReadyCallback = onMapReadyCallback
        this.initialMapCameraPosition = initialMapCameraPosition
        routingClient?.let { this.routingClient = it }
        viewModel = NavigationViewModel.create(context, locationEngine, this.routingClient).also {
            it.listener = navigationListener
            it.isMuted = isMuted
            it.speedingTolerance = speedingTolerance
            it.speechPlayerFactory = speechPlayerFactory
            it.announceReroute = announceReroute
            observe(it)
        }
        mapView.getMapAsync(::onMapReady)
    }

    /**
     * Receives navigation events. Can be called any time, also before [initialize].
     */
    fun setNavigationListener(listener: NavigationViewListener?) {
        navigationListener = listener
        viewModel?.listener = listener
    }

    /**
     * @param enableInstructionList allow opening the instruction list
     * @param showSpeedLimitView show current speed and speed limit
     * @param lightweightNavigationMap while navigating, hide map layers that are expensive to
     * render but not useful while driving (3D buildings, POIs, water labels, one-way arrows,
     * decorative patterns) and disable tile prefetching. Restored when navigation stops.
     * @param lightweightIdleMap the same lightweight map while navigation is not running, for
     * apps that draw their own routes and only use this view as a map. Off by default.
     * @param idleMaxFps maximum frames per second of the map while navigation is not running,
     * for weak devices. During navigation the map FPS is throttled by the navigation map.
     * Unlimited ([Int.MAX_VALUE]) by default.
     * @param showTripSummary while navigating, show remaining time, distance and arrival time
     * at the bottom
     * @param showRecenterButton while navigating, show a button to follow the user location again
     * after the map was moved by hand
     * @param speedingTolerance speed above the limit, in the unit of the route (km/h or mph),
     * that is not shown as speeding
     * @param showUnknownSpeedLimit show a "--" sign where the route has no speed limit data,
     * by default no sign is shown there
     * @param vanishTraveledRoute remove the traveled part of the route line, so it starts at the
     * user location
     * @param announceReroute say that the route was recalculated after leaving the route
     */
    @JvmOverloads
    fun configureUi(
        enableInstructionList: Boolean = true,
        showSpeedLimitView: Boolean = false,
        lightweightNavigationMap: Boolean = true,
        lightweightIdleMap: Boolean = false,
        idleMaxFps: Int = UNLIMITED_FPS,
        showTripSummary: Boolean = false,
        showRecenterButton: Boolean = true,
        speedingTolerance: Int = NavigationViewModel.DEFAULT_SPEEDING_TOLERANCE,
        showUnknownSpeedLimit: Boolean = false,
        vanishTraveledRoute: Boolean = true,
        announceReroute: Boolean = true,
    ) {
        this.enableInstructionList = enableInstructionList
        this.showSpeedLimitView = showSpeedLimitView
        this.lightweightNavigationMap = lightweightNavigationMap
        this.lightweightIdleMap = lightweightIdleMap
        this.idleMaxFps = idleMaxFps
        this.showTripSummary = showTripSummary
        this.showRecenterButton = showRecenterButton
        this.speedingTolerance = speedingTolerance
        this.showUnknownSpeedLimit = showUnknownSpeedLimit
        this.vanishTraveledRoute = vanishTraveledRoute
        this.announceReroute = announceReroute
        viewModel?.announceReroute = announceReroute
        navigationMap?.updateVanishingRouteLine(vanishTraveledRoute)
        applyUiConfiguration()
        updateMapPerformanceSettings()
    }

    /**
     * Own text-to-speech engine for voice instructions, e.g. `SherpaSpeechPlayerFactory` from the
     * `maplibre-navigation-tts-sherpa` module. Null uses the Android text-to-speech. Applies from
     * the next [startNavigation].
     */
    fun setSpeechPlayerFactory(factory: SpeechPlayerFactory?) {
        speechPlayerFactory = factory
        viewModel?.speechPlayerFactory = factory
    }

    fun enableNavigatorSound(enabled: Boolean) {
        isMuted = !enabled
        viewModel?.isMuted = isMuted
    }

    /**
     * Heights in pixels covered by your own UI on top of this view, e.g. a toolbar or a bottom
     * panel. The navigation UI moves out of these areas and the camera keeps the user location
     * above the bottom area. For a bottom sheet that moves, use [trackBottomOverlay] instead.
     */
    @JvmOverloads
    fun setOverlayInsets(top: Int = 0, bottom: Int = 0) {
        overlayTopInset = top.coerceAtLeast(0)
        overlayBottomInset = bottom.coerceAtLeast(0)
        applyInsets()
    }

    /**
     * Follows [overlay], e.g. a bottom sheet, while it moves or changes size: the navigation UI
     * stays above it and the camera keeps the user location visible above it. Pass null to stop.
     */
    fun trackBottomOverlay(overlay: View?) {
        trackedBottomOverlay = overlay
        if (overlay == null && trackedBottomInset != 0) {
            trackedBottomInset = 0
            applyInsets()
        } else {
            updateTrackedBottomInset()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(overlayPreDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(overlayPreDrawListener)
        removeCallbacks(applyMapBottomInset)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            post { applyInsets() }
        }
    }

    // endregion

    // region Navigation

    /**
     * Requests a route and starts navigation along the first returned route.
     * If the map is not ready yet, navigation is started as soon as it is.
     *
     * @param onError called when the route request fails or returns no routes
     */
    @JvmOverloads
    fun startNavigation(
        request: NavigationRequest,
        onError: ((RouteRequestException) -> Unit)? = null
    ) {
        requestRoute(
            request,
            onRoutesReady = { routes ->
                startNavigation(
                    routes.first(),
                    request.navigationOptions,
                    request.simulateRoute,
                    request.routingService
                )
            },
            onError = onError
        )
    }

    /**
     * Requests routes without starting navigation, e.g. to preview them before calling
     * [startNavigation] with the chosen route. A new request or [stopNavigation] cancels a
     * request that is still running.
     */
    @JvmOverloads
    fun requestRoute(
        request: NavigationRequest,
        onRoutesReady: (List<DirectionsRoute>) -> Unit,
        onError: ((RouteRequestException) -> Unit)? = null
    ) {
        pendingNavigationStart = null
        routeRequestExecutor.request(
            request,
            onRoutesReady = onRoutesReady,
            onError = { error -> onError?.invoke(error) }
        )
    }

    /**
     * Starts navigation along an already fetched [route]. A running navigation is replaced.
     * If the map is not ready yet, navigation is started as soon as it is.
     *
     * @param simulateRoute drive along the route with simulated locations
     * @param routingService engine for new routes when the user leaves the route. By default
     * recreated from the route options of [route], set it for a custom [RoutingService].
     */
    @JvmOverloads
    fun startNavigation(
        route: DirectionsRoute,
        navigationOptions: MapLibreNavigationOptions = MapLibreNavigationOptions(),
        simulateRoute: Boolean = false,
        routingService: RoutingService? = null,
    ) {
        val viewModel = viewModel
        if (!isMapInitialized || viewModel == null) {
            pendingNavigationStart = {
                startNavigation(route, navigationOptions, simulateRoute, routingService)
            }
            return
        }
        pendingNavigationStart = null
        preNavigationLocationEngine?.stop()
        viewModel.startNavigation(route, navigationOptions, simulateRoute, routingService)
        viewModel.distanceFormatter?.let(instructionView::setDistanceFormatter)
        navigationMap?.resetPadding()
        updateRecenterButton(visible = false)
        tripSummaryView.clear()
        showInstructionView()
        applyUiConfiguration()
        updateMapPerformanceSettings()
        // The camera padding uses the bottom inset as soon as tracking starts
        navigationMap?.updateBottomInset(appliedMapBottomInset)
    }

    /**
     * Ends navigation, removes the route from the map and cancels a running route request.
     * The view keeps showing the map and the user location.
     */
    @UiThread
    fun stopNavigation() {
        pendingNavigationStart = null
        routeRequestExecutor.cancel()
        val wasNavigating = isNavigating
        viewModel?.stopNavigation()
        hideInstructionView()
        updateRecenterButton(visible = false)
        tripSummaryView.isVisible = false
        if (wasNavigating) {
            navigationMap?.removeRoute()
            navigationMap?.retrieveMap()?.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            preNavigationLocationEngine?.start()
        }
        updateMapPerformanceSettings()
        lastMaxSpeed = null
        renderSpeed()
    }

    /**
     * Moves the camera back to follow the user location, e.g. after the map was moved by hand
     * or [showRouteOverview].
     */
    fun recenterCamera() {
        navigationMap?.resetPadding()
        navigationMap?.resetCameraPositionWith(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS)
        updateRecenterButton(visible = false)
    }

    /**
     * Shows the remaining route. Use [recenterCamera] to follow the user location again.
     */
    fun showRouteOverview() {
        navigationMap?.showRouteOverview(buildRouteOverviewPadding())
        updateRecenterButton(visible = true)
    }

    // endregion

    // region Map access

    fun addSymbol(symbolOptions: SymbolOptions): Symbol = requireSymbolManager().create(symbolOptions)

    fun removeSymbol(symbol: Symbol) = requireSymbolManager().delete(symbol)

    fun updateSymbol(symbol: Symbol) = requireSymbolManager().update(symbol)

    fun addOnSymbolClickListener(listener: OnSymbolClickListener) {
        requireSymbolManager().addClickListener(listener)
    }

    fun removeOnSymbolClickListener(listener: OnSymbolClickListener) {
        symbolManager?.removeClickListener(listener)
    }

    /**
     * Direct access to the map UI (location marker, route, camera), null until the map is ready.
     */
    fun retrieveNavigationMapLibreMap(): NavigationMapLibreMap? = navigationMap

    /**
     * The [MapLibreNavigation] powering this view, null before [initialize] and after [onDestroy].
     */
    fun retrieveMapLibreNavigation(): MapLibreNavigation? = viewModel?.navigation

    fun showInstructionView() {
        instructionView.isVisible = true
    }

    fun hideInstructionView() {
        instructionView.isVisible = false
    }

    // endregion

    private fun onMapReady(mapLibreMap: MapLibreMap) {
        initialMapCameraPosition?.let { mapLibreMap.cameraPosition = it }
        val viewModel = viewModel ?: return

        val onStyleLoaded = Style.OnStyleLoaded { style ->
            symbolManager = SymbolManager(mapView, mapLibreMap, style).apply {
                iconAllowOverlap = true
                iconRotationAlignment = ICON_ROTATION_ALIGNMENT_VIEWPORT
            }
            navigationMap = NavigationMapLibreMap(mapView, mapLibreMap).apply {
                updateLocationLayerRenderMode(RenderMode.GPS)
                updateWaynameQueryMap(false)
                addProgressChangeListener(viewModel.navigation)
                addOnCameraTrackingChangedListener(cameraTrackingListener)
                updateVanishingRouteLine(vanishTraveledRoute)
                mapInstanceState?.let(::restoreFrom)
            }
            mapStyleOptimizer = NavigationMapStyleOptimizer(mapLibreMap)
            mapView.addOnDidFinishLoadingStyleListener(onStyleChangedListener)
            // configureUi() may have been called before the style was ready
            updateMapPerformanceSettings()
            preNavigationLocationEngine = PreNavigationLocationEngine(
                locationEngine = viewModel.deviceLocationEngine,
                locationComponent = mapLibreMap.locationComponent,
                onLocationUpdate = { location -> onSpeedChanged(location.speedMetersPerSeconds) }
            )
            if (!isNavigating && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                preNavigationLocationEngine?.start()
            }
            isMapInitialized = true
            onMapReadyCallback?.onMapReady(mapLibreMap)
            pendingNavigationStart?.let { start ->
                pendingNavigationStart = null
                start()
            }
        }

        val styleUri = mapStyleUri ?: ThemeSwitcher.retrieveMapStyle(context)
        mapLibreMap.setStyle(Style.Builder().fromUri(styleUri), onStyleLoaded)
    }

    /**
     * Renders the navigation state. LiveData only delivers while this view is started, so nothing
     * is rendered in the background and the latest state is shown when it comes back.
     */
    private fun observe(viewModel: NavigationViewModel) {
        viewModel.route.observe(this) { route ->
            route ?: return@observe
            navigationMap?.drawRoute(route)
            navigationMap?.startCamera(route)
        }
        viewModel.instruction.observe(this, instructionView::updateInstruction)
        viewModel.bannerInstruction.observe(this, instructionView::updateBannerInstruction)
        viewModel.isOffRoute.observe(this, instructionView::updateRerouteState)
        viewModel.location.observe(this) { location ->
            navigationMap?.updateLocation(location)
            onSpeedChanged(location.speedMetersPerSeconds)
        }
        viewModel.speedLimit.observe(this, ::onSpeedLimitChanged)
        viewModel.routeProgress.observe(this) { routeProgress ->
            val formatter = viewModel.distanceFormatter
            if (showTripSummary && routeProgress != null && formatter != null) {
                tripSummaryView.update(routeProgress, formatter, viewModel.timeFormat)
                tripSummaryView.isVisible = true
            }
        }
    }

    private fun shutdown() {
        pendingNavigationStart = null
        routeRequestExecutor.cancel()
        hostLifecycle?.removeObserver(hostLifecycleObserver)
        hostLifecycle = null
        if (isCallbacksRegistered) {
            context.unregisterComponentCallbacks(memoryCallbacks)
            isCallbacksRegistered = false
        }
        preNavigationLocationEngine?.stop()
        preNavigationLocationEngine = null
        viewModel?.onDestroy()
        viewModel = null
        navigationMap?.removeOnCameraTrackingChangedListener(cameraTrackingListener)
        navigationMap?.onDestroy()
        navigationMap = null
        symbolManager?.onDestroy()
        symbolManager = null
        mapStyleOptimizer = null
        trackedBottomOverlay = null
        removeCallbacks(applyMapBottomInset)
        mapView.removeOnDidFinishLoadingStyleListener(onStyleChangedListener)
        mapView.onDestroy()
        ImageCreator.getInstance().shutdown()
        isMapInitialized = false
        isMapRequested = false
    }

    private fun requireSymbolManager(): SymbolManager = checkNotNull(symbolManager) {
        "Map is not initialized yet. Call initialize() and wait for onMapReady callback."
    }

    private fun restoreNavigationMapInstanceState(savedInstanceState: Bundle) {
        BundleCompat.getParcelable(
            savedInstanceState,
            MAP_INSTANCE_STATE_KEY,
            NavigationMapLibreMapInstanceState::class.java
        )?.let { mapInstanceState = it }
    }

    private fun updateRecenterButton(visible: Boolean) {
        recenterButton.isVisible = visible && showRecenterButton && isNavigating
    }

    // region Map performance

    private fun updateMapPerformanceSettings() {
        updateMapStyleOptimization()
        updateIdleMaxFps()
    }

    private fun updateMapStyleOptimization() {
        val optimizer = mapStyleOptimizer ?: return
        val isLightweight = if (isNavigating) lightweightNavigationMap else lightweightIdleMap
        if (isLightweight) {
            optimizer.apply()
        } else {
            optimizer.restore()
        }
    }

    /**
     * Caps the map FPS while navigation is not running; the cap is lifted when it starts so it
     * doesn't fight with the navigation FPS throttling.
     */
    private fun updateIdleMaxFps() {
        val maxFps = if (isNavigating) UNLIMITED_FPS else idleMaxFps
        if (maxFps == appliedMaxFps) {
            return
        }
        appliedMaxFps = maxFps
        mapView.setMaximumFps(maxFps)
    }

    // endregion

    // region UI

    private fun applyUiConfiguration() {
        instructionView.setInstructionListEnabled(enableInstructionList)
        if (!showTripSummary || !isNavigating) {
            tripSummaryView.isVisible = false
        }
        if (!showRecenterButton) {
            recenterButton.isVisible = false
        }
        viewModel?.speedingTolerance = speedingTolerance
        // speedLimit only emits on change, so render the current value right away
        lastMaxSpeed = viewModel?.speedLimit?.value
        renderSpeed(force = true)
    }

    private fun onSpeedChanged(speedMetersPerSecond: Float?) {
        lastSpeed = speedMetersPerSecond
        renderSpeed()
    }

    private fun onSpeedLimitChanged(maxSpeed: MaxSpeed?) {
        lastMaxSpeed = maxSpeed
        renderSpeed()
    }

    // Speed signs use plain digits in every locale
    @SuppressLint("SetTextI18n")
    private fun renderSpeed(force: Boolean = false) {
        if (!showSpeedLimitView) {
            speedView.isVisible = false
            speedLimitView.isVisible = false
            renderedSpeed = null
            return
        }
        val display = SpeedDisplayCalculator.calculate(
            speedMetersPerSecond = lastSpeed,
            maxSpeed = lastMaxSpeed,
            unit = viewModel?.takeIf { it.isNavigating }?.unitType ?: deviceUnitType,
            speedingTolerance = speedingTolerance,
            isNavigating = isNavigating,
            showUnknown = showUnknownSpeedLimit,
        )
        val previous = renderedSpeed
        if (!force && display == previous) {
            return
        }
        renderedSpeed = display

        speedView.isVisible = true
        if (force || previous?.speed != display.speed) {
            speedView.text = display.speed.toString()
            speedView.contentDescription =
                context.getString(R.string.navigation_view_speed_description, display.speed.toString())
        }
        if (force || previous?.isSpeeding != display.isSpeeding) {
            speedView.background = if (display.isSpeeding) speedStyle.speedingBackground else speedStyle.speedBackground
            speedView.setTextColor(if (display.isSpeeding) speedStyle.speedingTextColor else speedStyle.speedTextColor)
        }
        if (force || previous?.sign != display.sign) {
            renderSpeedLimitSign(display.sign)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderSpeedLimitSign(sign: SpeedLimitSign) {
        speedLimitView.isVisible = sign != SpeedLimitSign.Hidden
        when (sign) {
            SpeedLimitSign.Hidden -> Unit
            SpeedLimitSign.Unknown -> {
                speedLimitView.text = UNKNOWN_SPEED_LIMIT
                speedLimitView.background = speedStyle.limitBackground
                speedLimitView.contentDescription = null
            }
            SpeedLimitSign.NoLimit -> {
                speedLimitView.text = null
                speedLimitView.background = speedStyle.noLimitBackground
                speedLimitView.contentDescription =
                    context.getString(R.string.navigation_view_no_speed_limit_description)
            }
            is SpeedLimitSign.Limit -> {
                speedLimitView.text = sign.value.toString()
                speedLimitView.background = speedStyle.limitBackground
                speedLimitView.contentDescription =
                    context.getString(R.string.navigation_view_speed_limit_description, sign.value.toString())
            }
        }
        updateSpeedViewTranslation()
    }

    private fun updateSpeedViewTranslation() {
        if (speedLimitView.isGone) {
            speedView.translationX = 0f
            speedView.translationY = 0f
        } else {
            val offset = SPEED_VIEW_OFFSET_DP * resources.displayMetrics.density
            speedView.translationX = offset
            speedView.translationY = -offset
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            systemTopInset = systemBars.top
            systemBottomInset = systemBars.bottom
            applyInsets()
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * Moves the UI out of the system bars and the areas covered by the host, and keeps the user
     * location visible above a bottom overlay.
     */
    private fun applyInsets() {
        val maxOverlayInset = (height * MAX_OVERLAY_FRACTION).toInt()
        val bottomOverlay = maxOf(overlayBottomInset, trackedBottomInset).coerceIn(0, maxOverlayInset)
        val top = maxOf(systemTopInset, overlayTopInset)
        val bottom = maxOf(systemBottomInset, bottomOverlay)
        instructionView.topMargin = instructionTopMargin + top
        speedContainer.topMargin = speedContainerTopMargin + top
        bottomPanel.bottomMargin = bottomPanelBottomMargin + bottom

        appliedMapBottomInset = bottomOverlay
        // A dragged bottom sheet changes the inset every frame, move the camera once it settles
        removeCallbacks(applyMapBottomInset)
        postDelayed(applyMapBottomInset, MAP_INSET_DELAY_MS)
    }

    private fun updateTrackedBottomInset() {
        val overlay = trackedBottomOverlay
        val inset = if (overlay == null || !overlay.isShown || height == 0) {
            0
        } else {
            val overlayLocation = IntArray(2).also(overlay::getLocationInWindow)
            val viewLocation = IntArray(2).also(::getLocationInWindow)
            (viewLocation[1] + height - overlayLocation[1]).coerceIn(0, height)
        }
        if (inset != trackedBottomInset) {
            trackedBottomInset = inset
            applyInsets()
        }
    }

    private fun buildRouteOverviewPadding(): IntArray {
        val leftRightPadding = resources.getDimension(R.dimen.route_overview_left_right_padding).toInt()
        val paddingBuffer = resources.getDimension(R.dimen.route_overview_buffer_padding).toInt()
        val instructionHeight =
            (resources.getDimension(R.dimen.instruction_layout_height) + paddingBuffer).toInt()
        val summaryHeight = resources.getDimension(R.dimen.summary_bottomsheet_height).toInt()
        return intArrayOf(
            leftRightPadding,
            instructionHeight + maxOf(systemTopInset, overlayTopInset),
            leftRightPadding,
            summaryHeight + appliedMapBottomInset,
        )
    }

    // endregion

    private companion object {
        const val MAP_INSTANCE_STATE_KEY = "navigation_maplibre_map_instance_state"
        const val UNLIMITED_FPS = Int.MAX_VALUE
        const val INSTRUCTION_VIEW_ELEVATION = 10f
        const val SPEED_VIEW_OFFSET_DP = 22f
        const val UNKNOWN_SPEED_LIMIT = "--"
        const val MAX_OVERLAY_FRACTION = 0.6
        const val MAP_INSET_DELAY_MS = 150L
    }
}

private var View.topMargin: Int
    get() = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    set(value) = updateMargins { it.topMargin = value }

private var View.bottomMargin: Int
    get() = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    set(value) = updateMargins { it.bottomMargin = value }

private inline fun View.updateMargins(update: (ViewGroup.MarginLayoutParams) -> Unit) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val before = params.topMargin to params.bottomMargin
    update(params)
    if (before != (params.topMargin to params.bottomMargin)) {
        layoutParams = params
    }
}
