package org.maplibre.navigation.android.navigation.ui.v5

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechAnnouncement
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayer
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerFactory
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.location.replay.ReplayRouteLocationEngine
import org.maplibre.navigation.core.milestone.BannerInstructionMilestone
import org.maplibre.navigation.core.milestone.MilestoneEventListener
import org.maplibre.navigation.core.milestone.VoiceInstructionMilestone
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.SpeedLimit
import org.maplibre.navigation.core.navigation.MapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.route.RouteFetcher
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress
import org.maplibre.navigation.core.utils.RouteUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NavigationViewModelTest {

    private val navigation: MapLibreNavigation = mockk(relaxed = true)
    private val deviceLocationEngine: LocationEngine = mockk(relaxed = true)
    private val replayEngine: ReplayRouteLocationEngine = mockk(relaxed = true)
    private val speechPlayer: SpeechPlayer = mockk(relaxed = true)
    private val routeUtils: RouteUtils = mockk(relaxed = true)
    private val listener = RecordingListener()
    private val routeFetcher = FakeRouteFetcher()

    private val progressListener = slot<ProgressChangeListener>()
    private val milestoneListener = slot<MilestoneEventListener>()
    private val offRouteListener = slot<OffRouteListener>()

    private lateinit var route: DirectionsRoute
    private lateinit var routeProgress: RouteProgress
    private lateinit var viewModel: NavigationViewModel

    @Before
    fun setUp() {
        route = TestRouteBuilder().buildTestDirectionsRoute(null)
        routeProgress = TestRouteProgressBuilder().buildDefaultTestRouteProgress(route)
        every { navigation.addProgressChangeListener(capture(progressListener)) } returns Unit
        every { navigation.addMilestoneEventListener(capture(milestoneListener)) } returns Unit
        every { navigation.addOffRouteListener(capture(offRouteListener)) } returns Unit

        viewModel = NavigationViewModel(
            context = RuntimeEnvironment.getApplication(),
            navigation = navigation,
            deviceLocationEngine = deviceLocationEngine,
            routeFetcher = routeFetcher,
            defaultSpeechPlayerFactory = { _, _ -> speechPlayer },
            replayEngineFactory = { replayEngine },
            routeUtils = routeUtils,
        ).also { it.listener = listener }
    }

    @Test
    fun startNavigation_startsAlongRouteWithDeviceLocations() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        verify { navigation.locationEngine = deviceLocationEngine }
        verify { navigation.startNavigation(route) }
        assertTrue(viewModel.isNavigating)
        assertSame(route, viewModel.route.value)
        assertEquals(listOf("started"), listener.events)
    }

    @Test
    fun startNavigation_withSimulation_usesReplayEngine() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = true)

        verify { replayEngine.assign(route) }
        verify { navigation.locationEngine = replayEngine }
    }

    @Test
    fun startNavigation_whileNavigating_replacesSessionWithoutStopEvent() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        verify(exactly = 1) { speechPlayer.onDestroy() }
        assertEquals(listOf("started", "started"), listener.events)
    }

    @Test
    fun stopNavigation_releasesSessionOnce() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = true)

        viewModel.stopNavigation()
        viewModel.stopNavigation()

        verify(exactly = 1) { navigation.stopNavigation() }
        verify(exactly = 1) { replayEngine.onStop() }
        verify(exactly = 1) { speechPlayer.onDestroy() }
        assertFalse(viewModel.isNavigating)
        assertNull(viewModel.route.value)
        assertEquals(listOf("started", "stopped"), listener.events)
    }

    @Test
    fun progressChange_publishesStateAndNotifiesListener() {
        val location = buildLocation()
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        progressListener.captured.onProgressChange(location, routeProgress)

        assertSame(location, viewModel.location.value)
        assertSame(routeProgress, viewModel.routeProgress.value)
        assertTrue(viewModel.instruction.value != null)
        assertEquals("started,progress", listener.events.joinToString(","))
    }

    @Test
    fun progressChange_afterStop_isIgnored() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        viewModel.stopNavigation()

        progressListener.captured.onProgressChange(buildLocation(), routeProgress)

        assertNull(viewModel.routeProgress.value)
        assertEquals(listOf("started", "stopped"), listener.events)
    }

    @Test
    fun arrival_isReportedOncePerLeg() {
        val milestone: BannerInstructionMilestone = mockk(relaxed = true)
        every { routeUtils.isArrivalEvent(any(), any()) } returns true
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        milestoneListener.captured.onMilestoneEvent(routeProgress, null, milestone)
        milestoneListener.captured.onMilestoneEvent(routeProgress, null, milestone)

        // The test route has a single leg, so this is the final destination
        assertEquals(listOf("started", "final_arrival"), listener.events)
    }

    @Test
    fun voiceInstruction_isPlayed() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        milestoneListener.captured.onMilestoneEvent(routeProgress, null, buildVoiceMilestone())

        verify(exactly = 1) { speechPlayer.play(any()) }
    }

    @Test
    fun voiceInstruction_isSkippedWhenMutedOrRejectedByListener() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        viewModel.isMuted = true
        milestoneListener.captured.onMilestoneEvent(routeProgress, null, buildVoiceMilestone())
        viewModel.isMuted = false
        listener.voiceAnnouncements = false
        milestoneListener.captured.onMilestoneEvent(routeProgress, null, buildVoiceMilestone())

        verify(exactly = 0) { speechPlayer.play(any()) }
        verify { speechPlayer.isMuted = true }
    }

    @Test
    fun offRoute_requestsNewRouteAndFollowsIt() {
        val location = buildLocation()
        val newRoute = buildResponse().routes.first()
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(location, routeProgress)

        offRouteListener.captured.userOffRoute(location)

        assertEquals(1, routeFetcher.requests)
        assertEquals(true, viewModel.isOffRoute.value)
        verify { speechPlayer.onOffRoute() }

        routeFetcher.respond(buildResponse(), routeProgress)

        verify { navigation.startNavigation(newRoute) }
        assertEquals(false, viewModel.isOffRoute.value)
        assertEquals("started,progress,off_route,rerouted", listener.events.joinToString(","))
    }

    @Test
    fun reroute_isAnnounced() {
        val played = slot<SpeechAnnouncement>()
        every { speechPlayer.play(capture(played)) } returns Unit
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(buildLocation(), routeProgress)
        offRouteListener.captured.userOffRoute(buildLocation())

        routeFetcher.respond(buildResponse(), routeProgress)

        assertEquals("Route recalculated", played.captured.announcement())
    }

    @Test
    fun reroute_isAnnouncedInTheLanguageOfTheRoute() {
        val played = slot<SpeechAnnouncement>()
        every { speechPlayer.play(capture(played)) } returns Unit
        val kazakhRoute = route.copy(routeOptions = route.routeOptions!!.copy(language = "kk"))
        viewModel.startNavigation(kazakhRoute, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(buildLocation(), routeProgress)
        offRouteListener.captured.userOffRoute(buildLocation())

        routeFetcher.respond(buildResponse(), routeProgress)

        assertEquals("Маршрут қайта құрылды", played.captured.announcement())
    }

    @Test
    fun reroute_announcementCanBeDisabled() {
        viewModel.announceReroute = false
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(buildLocation(), routeProgress)
        offRouteListener.captured.userOffRoute(buildLocation())

        routeFetcher.respond(buildResponse(), routeProgress)

        verify(exactly = 0) { speechPlayer.play(any()) }
    }

    @Test
    fun offRoute_rejectedByListener_doesNotReroute() {
        listener.allowReroute = false
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(buildLocation(), routeProgress)

        offRouteListener.captured.userOffRoute(buildLocation())

        assertEquals(0, routeFetcher.requests)
        assertEquals(false, viewModel.isOffRoute.value)
    }

    @Test
    fun rerouteFailure_isReportedAndNavigationContinues() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)
        progressListener.captured.onProgressChange(buildLocation(), routeProgress)
        offRouteListener.captured.userOffRoute(buildLocation())

        routeFetcher.fail(IllegalStateException("offline"))

        assertTrue(viewModel.isNavigating)
        assertEquals(false, viewModel.isOffRoute.value)
        assertEquals("started,progress,off_route,reroute_failed", listener.events.joinToString(","))
    }

    @Test
    fun speeding_isReportedWhenItStartsAndEnds() {
        val limit = MaxSpeed(speed = 50, unit = SpeedLimit.Unit.KMPH)
        val progress = mockk<RouteProgress>(relaxed = true) {
            every { currentLegAnnotation } returns mockk(relaxed = true) { every { maxSpeed } returns limit }
        }
        viewModel.speedingTolerance = 5
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        progressListener.captured.onProgressChange(buildLocation(speedKmh = 50f), progress)
        progressListener.captured.onProgressChange(buildLocation(speedKmh = 60f), progress)
        progressListener.captured.onProgressChange(buildLocation(speedKmh = 70f), progress)
        viewModel.stopNavigation()

        assertEquals(listOf<Any?>(limit, true, false), listener.speedEvents)
    }

    @Test
    fun customSpeechPlayerFactory_replacesAndroidTextToSpeech() {
        val customPlayer: SpeechPlayer = mockk(relaxed = true)
        var requestedLanguage: String? = null
        viewModel.speechPlayerFactory = SpeechPlayerFactory { language ->
            requestedLanguage = language
            customPlayer
        }
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        milestoneListener.captured.onMilestoneEvent(routeProgress, null, buildVoiceMilestone())

        verify(exactly = 1) { customPlayer.play(any()) }
        verify(exactly = 0) { speechPlayer.play(any()) }
        assertTrue(requestedLanguage!!.isNotEmpty())
    }

    @Test
    fun onDestroy_stopsNavigationAndReleasesNavigation() {
        viewModel.startNavigation(route, MapLibreNavigationOptions(), simulateRoute = false)

        viewModel.onDestroy()

        verify { navigation.onDestroy() }
        assertTrue(routeFetcher.listeners.isEmpty())
        assertEquals(listOf("started", "stopped"), listener.events)
    }

    private fun buildLocation(speedKmh: Float = 36f) =
        Location(latitude = 52.0, longitude = 5.5, speedMetersPerSeconds = speedKmh / 3.6f)

    private fun buildVoiceMilestone(): VoiceInstructionMilestone = mockk(relaxed = true) {
        every { announcement } returns "Turn left"
        every { ssmlAnnouncement } returns null
    }

    private fun buildResponse(): DirectionsResponse =
        DirectionsResponse.fromJson(TestRouteBuilder().loadJsonFixture("directions_v5_precision_6.json"))

    private class RecordingListener : NavigationViewListener {
        val events = mutableListOf<String>()
        val speedEvents = mutableListOf<Any?>()
        var allowReroute = true
        var voiceAnnouncements = true

        override fun onNavigationStarted(route: DirectionsRoute) {
            events += "started"
        }

        override fun onNavigationStopped() {
            events += "stopped"
        }

        override fun onProgressChange(location: Location, routeProgress: RouteProgress) {
            events += "progress"
        }

        override fun allowReroute(location: Location): Boolean = allowReroute

        override fun onOffRoute(location: Location) {
            events += "off_route"
        }

        override fun onRerouted(route: DirectionsRoute) {
            events += "rerouted"
        }

        override fun onRerouteFailed(error: Throwable) {
            events += "reroute_failed"
        }

        override fun onWaypointArrival(routeProgress: RouteProgress) {
            events += "waypoint_arrival"
        }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            events += "final_arrival"
        }

        override fun onSpeedLimitChanged(maxSpeed: MaxSpeed?) {
            speedEvents += maxSpeed
        }

        override fun onSpeedingChanged(isSpeeding: Boolean) {
            speedEvents += isSpeeding
        }

        override fun willVoice(announcement: SpeechAnnouncement): SpeechAnnouncement? =
            announcement.takeIf { voiceAnnouncements }
    }

    private class FakeRouteFetcher : RouteFetcher() {
        var requests = 0
        val listeners get() = routeListeners

        override fun findRouteFromRouteProgress(location: Location, routeProgress: RouteProgress) {
            requests++
        }

        override fun cancelRouteCall() = Unit

        fun respond(response: DirectionsResponse, routeProgress: RouteProgress) {
            routeListeners.toList().forEach { it.onResponseReceived(response, routeProgress) }
        }

        fun fail(error: Throwable) {
            routeListeners.toList().forEach { it.onErrorReceived(error) }
        }
    }
}
