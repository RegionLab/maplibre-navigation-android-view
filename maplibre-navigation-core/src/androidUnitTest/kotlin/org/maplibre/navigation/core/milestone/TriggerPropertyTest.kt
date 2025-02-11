package org.maplibre.navigation.core.milestone

import org.maplibre.navigation.core.BaseTest
import org.maplibre.navigation.core.milestone.Trigger.eq
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.routeprogress.RouteProgress
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class TriggerPropertyTest : BaseTest() {

    @Test
    @Throws(Exception::class)
    fun stepDurationRemainingProperty_onlyPassesValidationWhenEqual() {
        val routeProgress = buildTestRouteProgressForTrigger()
        val stepDuration: Double =
            routeProgress.currentLegProgress.currentStepProgress.durationRemaining

        for (i in 10 downTo 1) {
            val milestone = StepMilestone(
                identifier = i,
                trigger = eq(TriggerProperty.STEP_DURATION_REMAINING_SECONDS, (stepDuration / i))
            )

            val result = milestone.isOccurring(routeProgress, routeProgress)
            if ((stepDuration / i) == stepDuration) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun stepDistanceRemainingProperty_onlyPassesValidationWhenEqual() {
        val routeProgress = buildTestRouteProgressForTrigger()
        val stepDistance: Double =
            routeProgress.currentLegProgress.currentStepProgress.distanceRemaining

        for (i in 10 downTo 1) {
            val milestone = StepMilestone(
                identifier = i,
                trigger = eq(TriggerProperty.STEP_DISTANCE_REMAINING_METERS, (stepDistance / i))
            )

            val result = milestone.isOccurring(routeProgress, routeProgress)
            if ((stepDistance / i) == stepDistance) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun stepDistanceTotalProperty_onlyPassesValidationWhenEqual() {
        val routeProgress = buildTestRouteProgressForTrigger()
        val stepDistanceTotal: Double =
            routeProgress.currentLegProgress.currentStep.distance

        for (i in 10 downTo 1) {
            val milestone = StepMilestone(
                identifier = i,
                trigger = eq(TriggerProperty.STEP_DISTANCE_TOTAL_METERS, (stepDistanceTotal / i))
            )

            val result = milestone.isOccurring(routeProgress, routeProgress)
            if ((stepDistanceTotal / i) == stepDistanceTotal) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun stepDurationTotalProperty_onlyPassesValidationWhenEqual() {
        val routeProgress = buildTestRouteProgressForTrigger()
        val stepDurationTotal: Double =
            routeProgress.currentLegProgress.currentStep.duration

        for (i in 10 downTo 1) {
            val milestone = StepMilestone(
                identifier = i,
                trigger = eq(TriggerProperty.STEP_DURATION_TOTAL_SECONDS, (stepDurationTotal / i))
            )

            val result = milestone.isOccurring(routeProgress, routeProgress)
            if ((stepDurationTotal / i) == stepDurationTotal) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun stepIndexProperty_onlyPassesValidationWhenEqual() {
        val routeProgress = buildTestRouteProgressForTrigger()
        val stepIndex: Int = routeProgress.currentLegProgress.stepIndex

        for (i in 10 downTo 1) {
            val milestone = StepMilestone(
                identifier = 1,
                instruction = null,
                trigger = eq(TriggerProperty.STEP_INDEX, abs((stepIndex - i)))
            )

            val result = milestone.isOccurring(routeProgress, routeProgress)
            if (abs((stepIndex - i)) == stepIndex) {
                assertTrue(result)
            } else {
                assertFalse(result)
            }
        }
    }

    @Throws(Exception::class)
    private fun buildTestRouteProgressForTrigger(): RouteProgress {
        val body = loadJsonFixture(ROUTE_FIXTURE)
        val response = DirectionsResponse.fromJson(body)

        val route = response.routes[0]
        val distanceRemaining = route.distance
        val legDistanceRemaining = route.legs[0].distance
        val stepDistanceRemaining = route.legs[0].steps[0].distance
        return buildTestRouteProgress(
            route, stepDistanceRemaining,
            legDistanceRemaining, distanceRemaining, 1, 0
        )
    }

    companion object {
        private const val ROUTE_FIXTURE = "directions_v5_precision_6.json"
    }
}
