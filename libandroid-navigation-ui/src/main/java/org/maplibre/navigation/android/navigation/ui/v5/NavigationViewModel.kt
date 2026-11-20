package org.maplibre.navigation.android.navigation.ui.v5

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.maplibre.navigation.android.navigation.ui.v5.camera.DynamicCamera
import org.maplibre.navigation.android.navigation.ui.v5.instruction.BannerInstructionModel
import org.maplibre.navigation.android.navigation.ui.v5.instruction.InstructionModel
import org.maplibre.navigation.android.navigation.ui.v5.route.MapLibreRouteFetcher
import org.maplibre.navigation.android.navigation.ui.v5.utils.DistanceFormatter
import org.maplibre.navigation.android.navigation.ui.v5.utils.LocaleUtils
import org.maplibre.navigation.android.navigation.ui.v5.voice.NavigationSpeechPlayer
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechAnnouncement
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayer
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerFactory
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerProvider
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.location.engine.MapLibreLocationEngine
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.milestone.BannerInstructionMilestone
import org.maplibre.navigation.core.milestone.Milestone
import org.maplibre.navigation.core.milestone.MilestoneEventListener
import org.maplibre.navigation.core.milestone.VoiceInstructionMilestone
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.route.FasterRouteListener
import org.maplibre.navigation.core.route.RouteFetcher
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.navigation.core.utils.RouteUtils
import java.util.Locale

/**
 * Runs a navigation session for [NavigationView].
 *
 * One-off events go to [listener]. Continuous state (instructions, location, speed limit, ...) is
 * published through [LiveData], so the view only renders it while it is started and gets the
 * latest value when it comes back to the foreground.
 */
internal class NavigationViewModel(
    private val context: Context,
    val navigation: MapLibreNavigation,
    /**
     * Engine for real device locations, also used to show the user location while not navigating.
     */
    val deviceLocationEngine: LocationEngine,
    private val routeFetcher: RouteFetcher,
    private val defaultSpeechPlayerFactory: (language: String, isVoiceLanguageSupported: Boolean) -> SpeechPlayer,
    private val replayEngineFactory: () -> ReplayRouteLocationEngine = ::ReplayRouteLocationEngine,
    private val localeUtils: LocaleUtils = LocaleUtils(),
    private val routeUtils: RouteUtils = RouteUtils(),
) {

    var listener: NavigationViewListener? = null

    /** Say that the route was recalculated after leaving the route. */
    var announceReroute: Boolean = true

    /** Language of the voice instructions of the current session. */
    private var voiceLanguage: String? = null

    /** Own speech engine for the next navigation session, null for the Android text-to-speech. */
    var speechPlayerFactory: SpeechPlayerFactory? = null

    var isMuted: Boolean = false
        set(value) {
            field = value
            speechPlayer?.isMuted = value
        }

    var isNavigating: Boolean = false
        private set

    var distanceFormatter: DistanceFormatter? = null
        private set

    var timeFormat: MapLibreNavigationOptions.TimeFormat = MapLibreNavigationOptions.TimeFormat.NONE_SPECIFIED
        private set

    /** Unit of the route being navigated, used for distances and speeds. */
    var unitType: UnitType = UnitType.METRIC
        private set

    /** Speed above the limit, in [unitType], that doesn't count as speeding. */
    var speedingTolerance: Int = DEFAULT_SPEEDING_TOLERANCE

    var isSpeeding: Boolean = false
        private set

    private val mutableRoute = MutableLiveData<DirectionsRoute?>()
    private val mutableLocation = MutableLiveData<Location>()
    private val mutableInstruction = MutableLiveData<InstructionModel>()
    private val mutableBannerInstruction = MutableLiveData<BannerInstructionModel>()
    private val mutableIsOffRoute = MutableLiveData(false)
    private val mutableSpeedLimit = MutableLiveData<MaxSpeed?>()
    private val mutableRouteProgress = MutableLiveData<RouteProgress?>()

    /** Route being navigated, null while not navigating. */
    val route: LiveData<DirectionsRoute?> = mutableRoute
    val location: LiveData<Location> = mutableLocation
    val instruction: LiveData<InstructionModel> = mutableInstruction
    val bannerInstruction: LiveData<BannerInstructionModel> = mutableBannerInstruction
    val isOffRoute: LiveData<Boolean> = mutableIsOffRoute
    val speedLimit: LiveData<MaxSpeed?> = mutableSpeedLimit
    val routeProgress: LiveData<RouteProgress?> = mutableRouteProgress

    private var speechPlayer: SpeechPlayer? = null
    private var replayEngine: ReplayRouteLocationEngine? = null
    private var lastRouteProgress: RouteProgress? = null
    private val arrivedLegIndices = mutableSetOf<Int>()

    private val rerouteController = RerouteController(
        routeFetcher = routeFetcher,
        onRouteFound = ::onRerouteFound,
        onError = ::onRerouteFailed,
    )

    private val progressChangeListener = ProgressChangeListener { location, routeProgress ->
        onProgressChange(location, routeProgress)
    }

    private val milestoneEventListener = MilestoneEventListener { routeProgress, _, milestone ->
        onMilestone(routeProgress, milestone)
    }

    private val offRouteListener = OffRouteListener { location -> onOffRoute(location) }

    private val fasterRouteListener = FasterRouteListener { route ->
        if (route != null && isNavigating) {
            followRoute(route)
            listener?.onRerouted(route)
        }
    }

    init {
        navigation.addProgressChangeListener(progressChangeListener)
        navigation.addMilestoneEventListener(milestoneEventListener)
        navigation.addOffRouteListener(offRouteListener)
        navigation.addFasterRouteListener(fasterRouteListener)
    }

    /**
     * Starts navigating along [route]. A running navigation is replaced.
     *
     * @param routingService used for rerouting, by default recreated from the route options
     */
    fun startNavigation(
        route: DirectionsRoute,
        options: MapLibreNavigationOptions,
        simulateRoute: Boolean,
        routingService: RoutingService? = null,
    ) {
        if (isNavigating) {
            stopSession()
        }
        (routeFetcher as? MapLibreRouteFetcher)?.routingService = routingService
        val language = localeUtils.getNonEmptyLanguage(
            context,
            route.routeOptions?.language ?: route.voiceLanguage
        )
        voiceLanguage = language
        unitType = localeUtils.retrieveNonNullUnitType(context, route.routeOptions?.voiceUnits)
        distanceFormatter = DistanceFormatter(context, language, unitType, options.roundingIncrement)
        timeFormat = options.timeFormatType
        speechPlayer = (speechPlayerFactory?.create(language)
            ?: defaultSpeechPlayerFactory(language, route.voiceLanguage != null)).also {
            it.isMuted = isMuted
        }

        val locationEngine = if (simulateRoute) {
            replayEngineFactory().also { engine ->
                engine.assign(route)
                replayEngine = engine
            }
        } else {
            deviceLocationEngine
        }
        navigation.options = options
        if (navigation.locationEngine !== locationEngine) {
            navigation.locationEngine = locationEngine
        }

        arrivedLegIndices.clear()
        mutableIsOffRoute.value = false
        isNavigating = true
        followRoute(route)
        listener?.onNavigationStarted(route)
    }

    fun stopNavigation() {
        if (!isNavigating) {
            return
        }
        stopSession()
        updateSpeeding(false)
        listener?.onNavigationStopped()
    }

    fun onDestroy() {
        stopNavigation()
        rerouteController.destroy()
        navigation.onDestroy()
        navigation.removeFasterRouteListener(fasterRouteListener)
        (navigation.cameraEngine as? DynamicCamera)?.clearMap()
        listener = null
    }

    private fun stopSession() {
        isNavigating = false
        voiceLanguage = null
        rerouteController.cancel()
        navigation.stopNavigation()
        replayEngine?.onStop()
        replayEngine = null
        speechPlayer?.onDestroy()
        speechPlayer = null
        lastRouteProgress = null
        mutableRoute.value = null
        mutableRouteProgress.value = null
        mutableSpeedLimit.value = null
        mutableIsOffRoute.value = false
    }

    private fun followRoute(route: DirectionsRoute) {
        navigation.startNavigation(route)
        replayEngine?.assign(route)
        mutableRoute.value = route
    }

    private fun onProgressChange(location: Location, routeProgress: RouteProgress) {
        if (!isNavigating) {
            return
        }
        lastRouteProgress = routeProgress
        distanceFormatter?.let { formatter ->
            mutableInstruction.value = InstructionModel(formatter, routeProgress)
        }
        mutableRouteProgress.value = routeProgress
        val maxSpeed = routeProgress.currentLegAnnotation?.maxSpeed
        if (mutableSpeedLimit.value != maxSpeed) {
            mutableSpeedLimit.value = maxSpeed
            listener?.onSpeedLimitChanged(maxSpeed)
        }
        mutableLocation.value = location
        updateSpeeding(
            SpeedDisplayCalculator.calculate(
                speedMetersPerSecond = location.speedMetersPerSeconds,
                maxSpeed = maxSpeed,
                unit = unitType,
                speedingTolerance = speedingTolerance,
                isNavigating = true,
                showUnknown = false,
            ).isSpeeding
        )
        listener?.onProgressChange(location, routeProgress)
    }

    private fun updateSpeeding(isSpeeding: Boolean) {
        if (this.isSpeeding != isSpeeding) {
            this.isSpeeding = isSpeeding
            listener?.onSpeedingChanged(isSpeeding)
        }
    }

    private fun onMilestone(routeProgress: RouteProgress, milestone: Milestone) {
        if (!isNavigating) {
            return
        }
        if (milestone is VoiceInstructionMilestone) {
            playVoiceInstruction(milestone)
        }
        if (milestone is BannerInstructionMilestone) {
            updateBannerInstruction(routeProgress, milestone)
        }
        // Arrival is reported once per leg, the arrival banner milestone may trigger again
        if (routeUtils.isArrivalEvent(routeProgress, milestone) &&
            arrivedLegIndices.add(routeProgress.legIndex)
        ) {
            if (routeProgress.remainingWaypoints <= 1) {
                listener?.onFinalDestinationArrival(routeProgress)
            } else {
                listener?.onWaypointArrival(routeProgress)
            }
        }
    }

    private fun playVoiceInstruction(milestone: VoiceInstructionMilestone) {
        play(SpeechAnnouncement.builder().voiceInstructionMilestone(milestone).build())
    }

    private fun play(announcement: SpeechAnnouncement) {
        val player = speechPlayer ?: return
        if (isMuted) {
            return
        }
        val announcementToPlay = listener?.let { it.willVoice(announcement) ?: return } ?: announcement
        player.play(announcementToPlay)
    }

    private fun announceReroute() {
        val language = voiceLanguage ?: return
        // In the language of the voice instructions, which may differ from the app language
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale(language)) }
        val text = context.createConfigurationContext(configuration)
            .getString(R.string.navigation_view_rerouted_announcement)
        play(SpeechAnnouncement.builder().announcement(text).build())
    }

    private fun updateBannerInstruction(routeProgress: RouteProgress, milestone: BannerInstructionMilestone) {
        val formatter = distanceFormatter ?: return
        val instructions = milestone.bannerInstructions ?: return
        val instructionsToDisplay = listener?.let { it.willDisplay(instructions) ?: return } ?: instructions
        mutableBannerInstruction.value =
            BannerInstructionModel(formatter, routeProgress, instructionsToDisplay)
    }

    private fun onOffRoute(location: Location) {
        if (!isNavigating || rerouteController.isBusy) {
            return
        }
        if (listener?.allowReroute(location) == false) {
            return
        }
        val routeProgress = lastRouteProgress ?: return
        speechPlayer?.onOffRoute()
        if (rerouteController.reroute(location, routeProgress)) {
            mutableIsOffRoute.value = true
            listener?.onOffRoute(location)
        }
    }

    private fun onRerouteFound(route: DirectionsRoute) {
        if (!isNavigating) {
            return
        }
        // Leg indices restart with the new route
        arrivedLegIndices.clear()
        // Before following the route, so it is spoken before the first instruction of the new route
        if (announceReroute) {
            announceReroute()
        }
        followRoute(route)
        mutableIsOffRoute.value = false
        listener?.onRerouted(route)
    }

    private fun onRerouteFailed(error: Throwable) {
        if (!isNavigating) {
            return
        }
        mutableIsOffRoute.value = false
        listener?.onRerouteFailed(error)
    }

    companion object {

        const val DEFAULT_SPEEDING_TOLERANCE = 5

        fun create(
            context: Context,
            locationEngine: LocationEngine?,
            routingClient: RoutingClient,
        ): NavigationViewModel {
            val appContext = context.applicationContext
            val deviceLocationEngine = locationEngine
                ?: MapLibreLocationEngine(appContext, Looper.getMainLooper())
            return NavigationViewModel(
                context = context,
                navigation = MapLibreNavigation(locationEngine = deviceLocationEngine),
                deviceLocationEngine = deviceLocationEngine,
                routeFetcher = MapLibreRouteFetcher(routingClient),
                defaultSpeechPlayerFactory = { language, isVoiceLanguageSupported ->
                    NavigationSpeechPlayer(
                        SpeechPlayerProvider(appContext, language, isVoiceLanguageSupported)
                    )
                },
            )
        }
    }
}
