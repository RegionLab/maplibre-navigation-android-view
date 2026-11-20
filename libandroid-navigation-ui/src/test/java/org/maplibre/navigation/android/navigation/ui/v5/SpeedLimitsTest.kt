package org.maplibre.navigation.android.navigation.ui.v5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.SpeedLimit
import org.maplibre.navigation.core.models.UnitType
import org.maplibre.navigation.core.utils.ValidationUtils

class SpeedLimitsTest {

    // region SpeedDisplayCalculator

    @Test
    fun speed_isShownInRouteUnit() {
        assertEquals(50, display(speedMs = 13.89f, unit = UnitType.METRIC).speed)
        assertEquals(31, display(speedMs = 13.89f, unit = UnitType.IMPERIAL).speed)
        assertEquals(0, display(speedMs = null).speed)
        assertEquals(0, display(speedMs = Float.NaN).speed)
    }

    @Test
    fun limit_isConvertedToRouteUnit() {
        val mph30 = MaxSpeed(speed = 30, unit = SpeedLimit.Unit.MPH)
        val kmh50 = MaxSpeed(speed = 50, unit = SpeedLimit.Unit.KMPH)

        assertEquals(SpeedLimitSign.Limit(48), display(maxSpeed = mph30, unit = UnitType.METRIC).sign)
        assertEquals(SpeedLimitSign.Limit(31), display(maxSpeed = kmh50, unit = UnitType.IMPERIAL).sign)
        assertEquals(SpeedLimitSign.Limit(50), display(maxSpeed = kmh50, unit = UnitType.METRIC).sign)
    }

    @Test
    fun speeding_respectsTolerance() {
        val limit = MaxSpeed(speed = 50, unit = SpeedLimit.Unit.KMPH)

        assertFalse(display(speedMs = 55 / 3.6f, maxSpeed = limit, tolerance = 5).isSpeeding)
        assertTrue(display(speedMs = 56 / 3.6f, maxSpeed = limit, tolerance = 5).isSpeeding)
        assertTrue(display(speedMs = 51 / 3.6f, maxSpeed = limit, tolerance = 0).isSpeeding)
    }

    @Test
    fun sign_dependsOnLimitAndNavigation() {
        val limit = MaxSpeed(speed = 50, unit = SpeedLimit.Unit.KMPH)

        assertEquals(SpeedLimitSign.Hidden, display(maxSpeed = limit, isNavigating = false).sign)
        assertEquals(SpeedLimitSign.NoLimit, display(maxSpeed = MaxSpeed(none = true)).sign)
        assertEquals(SpeedLimitSign.Hidden, display(maxSpeed = MaxSpeed(unknown = true)).sign)
        assertEquals(SpeedLimitSign.Hidden, display(maxSpeed = null).sign)
        assertEquals(SpeedLimitSign.Unknown, display(maxSpeed = null, showUnknown = true).sign)
        assertFalse(display(speedMs = 100f, maxSpeed = MaxSpeed(none = true)).isSpeeding)
    }

    private fun display(
        speedMs: Float? = 10f,
        maxSpeed: MaxSpeed? = null,
        unit: UnitType = UnitType.METRIC,
        tolerance: Int = 5,
        isNavigating: Boolean = true,
        showUnknown: Boolean = false,
    ) = SpeedDisplayCalculator.calculate(speedMs, maxSpeed, unit, tolerance, isNavigating, showUnknown)

    // endregion

    // region ValhallaSpeedLimits

    @Test
    fun valhallaEdges_becomeNavigableLegAnnotations() {
        val route = valhallaRoute()

        val withLimits = ValhallaSpeedLimits.applyTo(route, traceAttributes())

        // Throws if maxspeed annotations come without distances
        ValidationUtils.validDirectionsRoute(withLimits.copy(routeOptions = null), defaultMilestonesEnabled = false)
        withLimits.legs.forEachIndexed { index, leg ->
            val annotation = leg.annotation!!
            assertEquals(annotation.distance!!.size, annotation.maxSpeed!!.size)
            assertEquals(route.legs[index].distance, annotation.distance!!.sum(), 0.5)
        }
        val limits = withLimits.legs.flatMap { it.annotation!!.maxSpeed!! }.map { it.speed }.toSet()
        assertEquals(setOf(30, 50), limits)
        assertTrue(withLimits.legs.flatMap { it.annotation!!.maxSpeed!! }.all { it.unit == SpeedLimit.Unit.KMPH })
    }

    @Test
    fun valhallaEdges_withEqualLimitsAreMerged() {
        val annotation = ValhallaSpeedLimits.applyTo(valhallaRoute(), traceAttributes()).legs.first().annotation!!

        // 91 edges along the route, only a few speed limit changes
        assertTrue(annotation.distance!!.size < 10)
        annotation.maxSpeed!!.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
    }

    @Test
    fun valhallaEdges_parseUnlimitedAndMissingLimits() {
        val trace = Json.parseToJsonElement(
            """{"edges":[{"length":1.0,"speed_limit":"unlimited"},{"length":1.0},{"length":1.0,"speed_limit":0}]}"""
        ).jsonObject
        val route = valhallaRoute().let { it.copy(legs = listOf(it.legs.first()), distance = it.legs.first().distance) }

        val maxSpeeds = ValhallaSpeedLimits.applyTo(route, trace).legs.first().annotation!!.maxSpeed!!

        assertEquals(listOf(MaxSpeed(none = true), MaxSpeed(unknown = true)), maxSpeeds)
    }

    @Test
    fun valhallaEdges_missing_leaveRouteUnchanged() {
        val route = valhallaRoute()

        assertSame(route, ValhallaSpeedLimits.applyTo(route, Json.parseToJsonElement("{}").jsonObject))
    }

    private fun valhallaRoute(): DirectionsRoute = DirectionsResponse
        .fromJson(TestRouteBuilder().loadJsonFixture("valhalla_osrm_response.json"))
        .routes.first()

    private fun traceAttributes() = Json.parseToJsonElement(
        TestRouteBuilder().loadJsonFixture("valhalla_trace_attributes_response.json")
    ).jsonObject

    // endregion
}
