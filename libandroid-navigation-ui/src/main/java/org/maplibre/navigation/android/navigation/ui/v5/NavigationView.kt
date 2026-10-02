package org.maplibre.navigation.android.navigation.ui.v5

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.Style.OnStyleLoaded
import org.maplibre.android.plugins.annotation.OnSymbolClickListener
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
import org.maplibre.navigation.android.navigation.ui.v5.camera.NavigationCamera
import org.maplibre.navigation.android.navigation.ui.v5.instruction.ImageCreator
import org.maplibre.navigation.android.navigation.ui.v5.instruction.InstructionView
import org.maplibre.navigation.android.navigation.ui.v5.listeners.NavigationListener
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapLibreMap
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapLibreMapInstanceState
import org.maplibre.navigation.android.navigation.ui.v5.map.NavigationMapStyleOptimizer
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationRoute
import org.maplibre.navigation.android.navigation.ui.v5.utils.DistanceFormatter
import org.maplibre.navigation.android.navigation.ui.v5.utils.LocaleUtils
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import androidx.core.view.isGone


class NavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1
) : CoordinatorLayout(context, attrs, defStyleAttr), LifecycleOwner, OnMapReadyCallback,
    NavigationContract.View {
    private lateinit var mapView: MapView
    private lateinit var instructionView: InstructionView
    private var speedContainer: View? = null
    private var speedLimitView: TextView? = null
    private var speedView: TextView? = null
    private var instructionTopMargin = 0
    private var speedContainerTopMargin = 0
    private var isSpeedLimitVisible = false
    private var lastRenderedSpeedKmh: Int? = null
    private var lastRenderedSpeedLimit: String? = null

    private lateinit var navigationPresenter: NavigationPresenter
    private var navigationViewEventDispatcher: NavigationViewEventDispatcher? = null
    private lateinit var navigationViewModel: NavigationViewModel
    private var navigationMap: NavigationMapLibreMap? = null
    private var preNavigationLocationEngine: PreNavigationLocationEngine? = null
    private var navigationRoute: NavigationRoute? = null
    private var onTrackingChangedListener: NavigationOnCameraTrackingChangedListener? = null
    private var mapInstanceState: NavigationMapLibreMapInstanceState? = null
    private var isMapInitialized = false
    private var isMapRequested = false
    private var isSubscribed = false
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var onMapReadyCallback: OnMapReadyCallback? = null
    private var onNavigationReadyCallback: OnNavigationReadyCallback? = null
    private var initialMapCameraPosition: CameraPosition? = null
    private var pendingNavigationStart: (() -> Unit)? = null
    private var isNavigationActive = false
    private var mapStyleOptimizer: NavigationMapStyleOptimizer? = null
    private var lightweightNavigationMap = true
    private val onStyleChangedListener = MapView.OnDidFinishLoadingStyleListener {
        // A new style (e.g. day/night switch) brings its own layers, optimize them too
        mapStyleOptimizer?.onStyleLoaded()
    }
    private var symbolManager: SymbolManager? = null
    private var routeRequestExecutor: RouteRequestExecutor? = null
    private var enableInstructionList = true
    private var showSpeedLimitView = false

    private var mapStyleUri: String? = null
    private val instructionVisibilityNavigationListener = object : NavigationListener {
        override fun onCancelNavigation() {
            hideInstructionView()
        }

        override fun onNavigationFinished() {
            hideInstructionView()
        }

        override fun onNavigationRunning() {
            showInstructionView()
        }
    }

    init {
        ThemeSwitcher.setTheme(context, attrs)
        initializeView()
    }

    /**
     * Uses savedInstanceState as a cue to restore state (if not null).
     *
     * @param savedInstanceState to restore state if not null
     */
    @JvmOverloads
    fun onCreate(
        savedInstanceState: Bundle?,
        mapStyleUri: String? = null
    ) {
        mapView.onCreate(savedInstanceState)
        savedInstanceState?.let(::restoreNavigationMapInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        this.mapStyleUri = mapStyleUri
        routeRequestExecutor = RouteRequestExecutor(context)
    }

    /**
     * Low memory must be reported so the [MapView]
     * can react appropriately.
     */
    fun onLowMemory() {
        mapView.onLowMemory()
    }

    /**
     * If the instruction list is showing and onBackPressed is called,
     * hide the instruction list and do not hide the activity or fragment.
     *
     * @return true if back press handled, false if not
     */
    fun onBackPressed(): Boolean {
        return instructionView.handleBackPressed()
    }

    /**
     * Stores the [MapView] state and the navigation map settings (camera tracking mode, padding).
     *
     * Navigation itself is not kept across activity recreation, it is stopped in [onDestroy].
     *
     * @param outState to store state variables
     */
    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
        saveNavigationMapInstanceState(outState)
    }

    /**
     * Restores the navigation map settings stored by [onSaveInstanceState].
     * Not required if the same bundle was already passed to [onCreate].
     *
     * @param savedInstanceState to extract state variables
     */
    fun onRestoreInstanceState(savedInstanceState: Bundle) {
        restoreNavigationMapInstanceState(savedInstanceState)
        mapInstanceState?.let { navigationMap?.restoreFrom(it) }
    }

    /**
     * Called to ensure the [MapView] is destroyed
     * properly.
     *
     *
     * In an [Activity] this should be in [Activity.onDestroy].
     *
     *
     * In a [Fragment], this should
     * be in [Fragment.onDestroyView].
     */
    @UiThread
    fun onDestroy() {
        shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    fun onStart() {
        mapView.onStart()
        navigationMap?.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        if (!isNavigationActive) {
            preNavigationLocationEngine?.start()
        }
    }

    fun onResume() {
        mapView.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        mapView.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        mapView.onStop()
        navigationMap?.onStop()
        // No need for GPS updates while the map isn't visible and navigation isn't running
        preNavigationLocationEngine?.stop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * Fired after the map is ready, this is our cue to finish
     * setting up the rest of the plugins / location engine.
     *
     *
     * Also, we check for launch data (coordinates or route).
     *
     * @param mapLibreMap used for route, camera, and location UI
     * @since 0.6.0
     */
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        initialMapCameraPosition?.let { mapLibreMap.cameraPosition = it }
        val onStyleLoaded = OnStyleLoaded { style ->
            initializeSymbolManager(mapView, mapLibreMap, style)
            initializeNavigationMap(mapView, mapLibreMap)
            mapStyleOptimizer = NavigationMapStyleOptimizer(mapLibreMap)
            mapView.addOnDidFinishLoadingStyleListener(onStyleChangedListener)
            initializeWayNameListener()
            initializePreNavigationLocationEngine(mapLibreMap)
            isMapInitialized = true
            onMapReadyCallback?.onMapReady(mapLibreMap)
            onNavigationReadyCallback?.onNavigationReady(navigationViewModel.isRunning)
            pendingNavigationStart?.let {
                pendingNavigationStart = null
                it()
            }
        }

        mapStyleUri?.let { mapLibreMap.setStyle(Style.Builder().fromUri(it), onStyleLoaded) }
            ?: mapLibreMap.setStyle(ThemeSwitcher.retrieveMapStyle(context), onStyleLoaded)
    }

    /**
     * Requests a route and starts navigation along the first returned route.
     * If the map is not ready yet, navigation is started as soon as it is.
     *
     * @param request route request parameters
     * @param onError called when the route request fails or returns no routes
     */
    @JvmOverloads
    fun startNavigation(
        request: NavigationRequest,
        onError: ((RouteRequestException) -> Unit)? = null
    ) {
        pendingNavigationStart = null
        navigationRoute = routeRequestExecutor?.request(
            request,
            onRoutesReady = ::startNavigation,
            onError = { error -> onError?.invoke(error) }
        )
    }

    private fun startNavigation(
        routes: List<DirectionsRoute>,
        navigationOptions: MapLibreNavigationOptions
    ) {
        if (!isMapInitialized) {
            pendingNavigationStart = { startNavigation(routes, navigationOptions) }
            return
        }
        preNavigationLocationEngine?.stop()
        isNavigationActive = true
        val route = routes.first()
        val options = NavigationViewOptions.builder()
        options.directionsRoute(route)
        options.navigationOptions(navigationOptions)
        options.navigationListener(instructionVisibilityNavigationListener)
        showInstructionView()
        initializeNavigation(options.build())
        updateMapStyleOptimization()
    }

    override fun resetCameraPosition() {
        navigationMap?.resetPadding()
        navigationMap?.resetCameraPositionWith(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS)
    }

    override fun drawRoute(directionsRoute: DirectionsRoute) {
        navigationMap?.drawRoute(directionsRoute)
    }

    fun addSymbol(symbolOptions: SymbolOptions): Symbol {
        return requireSymbolManager().create(symbolOptions)
    }

    fun removeSymbol(symbol: Symbol) {
        requireSymbolManager().delete(symbol)
    }

    fun addOnSymbolClickListener(listener: OnSymbolClickListener) {
        requireSymbolManager().addClickListener(listener)
    }

    fun updateSymbol(symbol: Symbol) {
        requireSymbolManager().update(symbol)
    }

    /**
     * Used when starting this [android.app.Activity]
     * for the first time.
     *
     *
     * Zooms to the beginning of the [DirectionsRoute].
     *
     * @param directionsRoute where camera should move to
     */
    override fun startCamera(directionsRoute: DirectionsRoute) {
        navigationMap?.startCamera(directionsRoute)
    }

    /**
     * Used after configuration changes to resume the camera
     * to the last location update from the Navigation SDK.
     *
     * @param location where the camera should move to
     */
    override fun resumeCamera(location: Location) {
        navigationMap?.resumeCamera(location)
    }

    override fun updateNavigationMap(location: Location) {
        navigationMap?.updateLocation(location)
    }

    override fun updateSpeed(speed: Double) {
        val speedTextView = speedView ?: return
        if (!showSpeedLimitView) {
            if (speedTextView.visibility != GONE) {
                speedTextView.visibility = GONE
            }
            return
        }
        val safeSpeed = if (speed.isFinite() && speed >= 0.0) speed else 0.0
        val speedKmH = (safeSpeed * 3.6).toInt()
        if (lastRenderedSpeedKmh != speedKmH) {
            speedTextView.text = speedKmH.toString()
            lastRenderedSpeedKmh = speedKmH
        }
        if (speedTextView.visibility != VISIBLE) {
            speedTextView.visibility = VISIBLE
        }
    }

    override fun updateCameraRouteOverview() {
        val padding = buildRouteOverviewPadding(context)
        navigationMap?.showRouteOverview(padding)
    }

    /**
     * Call this when the navigation session needs to end navigation without finishing the whole view
     *
     * @since 0.16.0
     */
    @UiThread
    fun stopNavigation() {
        pendingNavigationStart = null
        isNavigationActive = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            preNavigationLocationEngine?.start()
        }
        routeRequestExecutor?.cancel()
        navigationRoute = null
        hideInstructionView()
        navigationPresenter.onNavigationStopped()
        navigationViewModel.stopNavigation()
        updateMapStyleOptimization()
    }


    /**
     * Should be called after [NavigationView.onCreate].
     */
    @JvmOverloads
    fun initialize(
        onNavigationReadyCallback: OnNavigationReadyCallback,
        initialMapCameraPosition: CameraPosition? = null
    ) {
        this.onNavigationReadyCallback = onNavigationReadyCallback
        this.initialMapCameraPosition = initialMapCameraPosition
        requestMap(shouldSimulateRoute = false)
    }

    /**
     * Should be called after [NavigationView.onCreate].
     */
    fun initialize(
        shouldSimulateRoute: Boolean,
        onMapReadyCallback: OnMapReadyCallback,
    ) {
        this.onMapReadyCallback = onMapReadyCallback
        requestMap(shouldSimulateRoute)
    }

    private fun requestMap(shouldSimulateRoute: Boolean) {
        // Guard against repeated initialize() calls while the map/style is still loading
        if (isMapRequested) {
            return
        }
        isMapRequested = true
        navigationViewModel.initializeNavigation(shouldSimulateRoute)
        mapView.getMapAsync(this)
    }


    fun enableNavigatorSound(enabled: Boolean) {
        navigationViewModel.isMuted = !enabled
    }

    /**
     * Gives the ability to manipulate the map directly for anything that might not currently be
     * supported. This returns null until the view is initialized.
     *
     *
     * The [NavigationMapLibreMap] gives direct access to the map UI (location marker, route, etc.).
     *
     * @return navigation mapbox map object, or null if view has not been initialized
     */
    fun retrieveNavigationMapLibreMap(): NavigationMapLibreMap? {
        return navigationMap
    }

    /**
     * Returns the instance of [MapLibreNavigation] powering the [NavigationView]
     * once navigation has started.  Will return null if navigation has not been started with
     * [NavigationView.startNavigation].
     *
     * @return mapbox navigation, or null if navigation has not started
     */
    fun retrieveMapLibreNavigation(): MapLibreNavigation? {
        return navigationViewModel.retrieveNavigation()
    }

    fun showInstructionView() {
        instructionView.isVisible = true
    }

    fun hideInstructionView() {
        instructionView.isVisible = false
    }

    /**
     * @param enableInstructionList allow opening the instruction list
     * @param showSpeedLimitView show current speed and speed limit
     * @param lightweightNavigationMap while navigating, hide map layers that are expensive to
     * render but not useful while driving (3D buildings, POIs, water labels, one-way arrows,
     * decorative patterns) and disable tile prefetching. Restored when navigation stops.
     */
    @JvmOverloads
    fun configureUi(
        enableInstructionList: Boolean = true,
        showSpeedLimitView: Boolean = false,
        lightweightNavigationMap: Boolean = true
    ) {
        this.enableInstructionList = enableInstructionList
        this.showSpeedLimitView = showSpeedLimitView
        this.lightweightNavigationMap = lightweightNavigationMap
        applyUiConfiguration()
        updateMapStyleOptimization()
    }

    private fun updateMapStyleOptimization() {
        val optimizer = mapStyleOptimizer ?: return
        if (isNavigationActive && lightweightNavigationMap) {
            optimizer.apply()
        } else {
            optimizer.restore()
        }
    }

    private fun initializeView() {
        inflate(context, R.layout.navigation_view_layout, this)
        bind()
        applyUiConfiguration()
        initializeNavigationViewModel(context)
        initializeNavigationEventDispatcher()
        initializeNavigationPresenter()
        initializeInstructionListListener()
    }

    private fun bind() {
        mapView = findViewById(R.id.navigationMapView)
        instructionView = findViewById(R.id.instructionView)
        instructionView.let {
            ViewCompat.setElevation(it, 10f)
        }
        speedContainer = findViewById(R.id.speedContainer)
        speedLimitView = findViewById(R.id.speedLimitView)
        speedView = findViewById(R.id.speedView)
        isSpeedLimitVisible = speedLimitView?.visibility == VISIBLE
        instructionTopMargin = findTopMargin(instructionView)
        speedContainerTopMargin = speedContainer?.let(::findTopMargin) ?: 0
        applyStatusBarInsets()
    }

    private fun applyStatusBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            setTopMargin(instructionView, instructionTopMargin + statusBarTop)
            speedContainer?.let {
                setTopMargin(it, speedContainerTopMargin + statusBarTop)
            }
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun findTopMargin(view: View): Int {
        return (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    }

    private fun setTopMargin(view: View, marginTop: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == marginTop) {
            return
        }
        params.topMargin = marginTop
        view.layoutParams = params
    }

    private fun initializeNavigationViewModel(context: Context) {
        try {
            navigationViewModel = NavigationViewModel(context)
        } catch (exception: ClassCastException) {
            throw ClassCastException("Please ensure that the provided Context is a valid FragmentActivity")
        }
    }

    private fun initializeNavigationEventDispatcher() {
        navigationViewEventDispatcher = NavigationViewEventDispatcher()
        navigationViewModel.initializeEventDispatcher(navigationViewEventDispatcher)
    }

    private fun initializeInstructionListListener() {
        instructionView.setInstructionListListener(
            NavigationInstructionListListener(
                navigationPresenter,
                navigationViewEventDispatcher
            )
        )
    }

    private fun initializeNavigationMap(mapView: MapView, map: MapLibreMap) {
        navigationMap = NavigationMapLibreMap(mapView, map)
        navigationMap?.updateLocationLayerRenderMode(RenderMode.GPS)
        if (mapInstanceState != null) {
            navigationMap?.restoreFrom(mapInstanceState)
            return
        }
    }

    private fun initializeSymbolManager(mapView: MapView, mapLibreMap: MapLibreMap, style: Style) {
        symbolManager = SymbolManager(mapView, mapLibreMap, style).apply {
            iconAllowOverlap = true
            iconRotationAlignment = ICON_ROTATION_ALIGNMENT_VIEWPORT
        }
    }

    private fun initializeWayNameListener() {
        navigationMap?.updateWaynameQueryMap(false)
    }

    private fun initializePreNavigationLocationEngine(map: MapLibreMap) {
        val locationEngine = navigationViewModel.retrieveNavigation()?.locationEngine ?: return
        preNavigationLocationEngine?.stop()
        preNavigationLocationEngine = PreNavigationLocationEngine(
            locationEngine = locationEngine,
            locationComponent = map.locationComponent,
            onLocationUpdate = { location ->
                updateSpeed(location.speedMetersPerSeconds?.toDouble() ?: 0.0)
            }
        )
        if (!isNavigationActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            preNavigationLocationEngine?.start()
        }
    }


    private fun saveNavigationMapInstanceState(outState: Bundle) {
        navigationMap?.saveStateWith(MAP_INSTANCE_STATE_KEY, outState)
    }

    private fun restoreNavigationMapInstanceState(savedInstanceState: Bundle) {
        BundleCompat.getParcelable(
            savedInstanceState,
            MAP_INSTANCE_STATE_KEY,
            NavigationMapLibreMapInstanceState::class.java
        )?.let { mapInstanceState = it }
    }

    private fun updateInstructionListState(visible: Boolean) {
        if (!enableInstructionList) {
            instructionView.hideInstructionList()
            return
        }
        if (visible) {
            instructionView.showInstructionList()
        } else {
            instructionView.hideInstructionList()
        }
    }

    private fun updateInstructionMutedState(isMuted: Boolean) {
    }

    private fun buildRouteOverviewPadding(context: Context): IntArray {
        val resources = context.resources
        val leftRightPadding =
            resources.getDimension(R.dimen.route_overview_left_right_padding).toInt()
        val paddingBuffer = resources.getDimension(R.dimen.route_overview_buffer_padding).toInt()
        val instructionHeight =
            (resources.getDimension(R.dimen.instruction_layout_height) + paddingBuffer).toInt()
        val summaryHeight = resources.getDimension(R.dimen.summary_bottomsheet_height).toInt()
        return intArrayOf(leftRightPadding, instructionHeight, leftRightPadding, summaryHeight)
    }

    private fun initializeNavigationPresenter() {
        navigationPresenter = NavigationPresenter(this)
    }

    private fun initializeNavigation(options: NavigationViewOptions) {
        establish(options)
        navigationViewModel.initialize(options)
        initializeNavigationListeners(options, navigationViewModel)
        setupNavigationMapLibreMap(options)

        if (onTrackingChangedListener == null) {
            initializeOnCameraTrackingChangedListener()
        }
        if (!isSubscribed) {
            subscribeViewModels()
        }
    }

    private fun initializeOnCameraTrackingChangedListener() {
        onTrackingChangedListener =
            NavigationOnCameraTrackingChangedListener(navigationPresenter)
        navigationMap?.addOnCameraTrackingChangedListener(onTrackingChangedListener)
    }

    private fun establish(options: NavigationViewOptions) {
        val localeUtils = LocaleUtils()
        establishDistanceFormatter(localeUtils, options)
    }

    private fun establishDistanceFormatter(
        localeUtils: LocaleUtils,
        options: NavigationViewOptions
    ) {
        val unitType = establishUnitType(localeUtils, options)
        val language = establishLanguage(localeUtils, options)
        val roundingIncrement = establishRoundingIncrement(options)
        val distanceFormatter = DistanceFormatter(context, language, unitType, roundingIncrement)

        instructionView.setDistanceFormatter(distanceFormatter)
    }

    private fun establishRoundingIncrement(navigationViewOptions: NavigationViewOptions): MapLibreNavigationOptions.RoundingIncrement {
        val mapboxNavigationOptions = navigationViewOptions.navigationOptions()
        return mapboxNavigationOptions.roundingIncrement
    }

    private fun establishLanguage(
        localeUtils: LocaleUtils,
        options: NavigationViewOptions
    ): String {
        return localeUtils.getNonEmptyLanguage(context, options.directionsRoute().voiceLanguage)
    }

    private fun establishUnitType(
        localeUtils: LocaleUtils,
        options: NavigationViewOptions
    ): UnitType {
        val routeOptions = options.directionsRoute().routeOptions
        val voiceUnits = routeOptions?.voiceUnits
        return localeUtils.retrieveNonNullUnitType(context, voiceUnits)
    }

    private fun initializeNavigationListeners(
        options: NavigationViewOptions,
        navigationViewModel: NavigationViewModel?
    ) {
        val navigation = navigationViewModel?.retrieveNavigation() ?: return
        navigationMap?.addProgressChangeListener(navigation)
        navigationViewEventDispatcher?.initializeListeners(options, navigationViewModel)
    }

    private fun setupNavigationMapLibreMap(@Suppress("UNUSED_PARAMETER") options: NavigationViewOptions) {
        navigationMap?.updateWaynameQueryMap(false)
    }

    /**
     * Subscribes the [InstructionView] to the [NavigationViewModel].
     *
     *
     * Then, creates an instance of [NavigationViewSubscriber], which takes a presenter.
     *
     *
     * The subscriber then subscribes to the view models, setting up the appropriate presenter / listener
     * method calls based on the [androidx.lifecycle.LiveData] updates.
     */
    private fun subscribeViewModels() {
        instructionView.subscribe(this, navigationViewModel)
        navigationViewModel.speedLimitModel.observe(this) { speedLimit ->
            updateSpeedLimit(speedLimit)
        }

        NavigationViewSubscriber(this, navigationViewModel, navigationPresenter).subscribe()
        isSubscribed = true
    }

    private fun shutdown() {
        pendingNavigationStart = null
        navigationMap?.removeOnCameraTrackingChangedListener(onTrackingChangedListener)
        onTrackingChangedListener = null
        navigationMap?.onDestroy()
        preNavigationLocationEngine?.stop()
        routeRequestExecutor?.cancel()
        routeRequestExecutor = null

        navigationViewEventDispatcher?.onDestroy(navigationViewModel.retrieveNavigation())
        mapView.removeOnDidFinishLoadingStyleListener(onStyleChangedListener)
        mapView.onDestroy()
        // NavigationViewModel lives as long as this view, so a recreated view can't pick up a
        // running navigation: always tear it down to avoid leaking location updates and TTS.
        navigationViewModel.onDestroy(false)
        ImageCreator.getInstance().shutdown()
        navigationMap = null
        mapStyleOptimizer = null
        isMapInitialized = false
        isMapRequested = false
    }

    private fun requireSymbolManager(): SymbolManager {
        return symbolManager ?: throw IllegalStateException(
            "Map is not initialized yet. Call initialize() and wait for onMapReady callback."
        )
    }

    private fun applyUiConfiguration() {
        if (::instructionView.isInitialized) {
            instructionView.setInstructionListEnabled(enableInstructionList)
        }
        speedLimitView?.visibility = if (showSpeedLimitView) VISIBLE else GONE
        speedView?.visibility = if (showSpeedLimitView) VISIBLE else GONE
        if (!showSpeedLimitView) {
            speedLimitView?.text = ""
            speedView?.text = ""
            lastRenderedSpeedLimit = null
            lastRenderedSpeedKmh = null
        } else {
            if (speedLimitView?.text.isNullOrBlank()) {
                speedLimitView?.text = "--"
            }
            if (speedView?.text.isNullOrBlank()) {
                speedView?.text = "0"
            }
            lastRenderedSpeedLimit = speedLimitView?.text?.toString()
            lastRenderedSpeedKmh = speedView?.text?.toString()?.toIntOrNull()
            // speedLimitModel only emits on change, so render the current value right away
            if (::navigationViewModel.isInitialized) {
                navigationViewModel.speedLimitModel.value?.let(::updateSpeedLimit)
            }
        }
        updateSpeedViewTranslation()
    }

    override fun updateSpeedLimit(maxSpeed: MaxSpeed?) {
        val speedLimitView = speedLimitView ?: return
        if (!showSpeedLimitView) {
            if (speedLimitView.text.isNotEmpty()) {
                speedLimitView.text = ""
            }
            lastRenderedSpeedLimit = null
            return
        }
        val value = when {
            maxSpeed?.none == true -> "∞"
            maxSpeed?.unknown == true -> "--"
            maxSpeed?.speed != null -> maxSpeed.speed.toString()
            else -> "--"
        }
        if (lastRenderedSpeedLimit != value) {
            speedLimitView.text = value
            lastRenderedSpeedLimit = value
        }
    }

    private fun updateSpeedViewTranslation() {
        val speedLimitView = speedLimitView ?: return
        val speedView = speedView ?: return

        if (speedLimitView.isGone) {
            speedView.translationX = 0f
            speedView.translationY = 0f
        } else {
            val density = resources.displayMetrics.density
            speedView.translationX = 22f * density
            speedView.translationY = -22f * density
        }
    }

    companion object {
        private const val MAP_INSTANCE_STATE_KEY = "navigation_maplibre_map_instance_state"
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

}
