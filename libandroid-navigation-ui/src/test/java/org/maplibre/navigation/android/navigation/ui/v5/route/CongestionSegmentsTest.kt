package org.maplibre.navigation.android.navigation.ui.v5.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegAnnotation
import org.maplibre.navigation.core.models.RouteLeg

class CongestionSegmentsTest {

    private val coordinates = (0..6).map { Point.fromLngLat(76.9 + it * 0.001, 43.2) }

    private fun leg(congestion: List<String>?, distance: List<Double>? = null) = RouteLeg(
        distance = 0.0, duration = 0.0, steps = emptyList(),
        annotation = LegAnnotation(congestion = congestion, distance = distance),
    )

    private fun route(vararg legs: RouteLeg) =
        DirectionsRoute(geometry = "", legs = legs.toList(), distance = 0.0, duration = 0.0)

    private fun Feature.points() = (geometry() as LineString).coordinates()
    private fun Feature.level() = getStringProperty(RouteConstants.CONGESTION_KEY)

    @Test
    fun consecutiveLevelsMerged_freeFlowSkipped() {
        val route = route(leg(listOf("low", "moderate", "moderate", "heavy", "unknown", "severe")))

        val features = CongestionSegments.build(route, coordinates, true)

        assertEquals(listOf("moderate", "heavy", "severe"), features.map { it.level() })
        assertEquals(coordinates.subList(1, 4), features[0].points())
        assertEquals(coordinates.subList(3, 5), features[1].points())
        assertEquals(coordinates.subList(5, 7), features[2].points())
    }

    @Test
    fun secondLegContinuesAfterFirst() {
        // Was drawn from the start of the route for every leg before
        val route = route(
            leg(listOf("low", "low", "low")),
            leg(listOf("heavy", "heavy", "low")),
        )

        val features = CongestionSegments.build(route, coordinates, true)

        assertEquals(1, features.size)
        assertEquals(coordinates.subList(3, 6), features[0].points())
    }

    @Test
    fun legWithoutTrafficCountedByDistanceAnnotation() {
        val route = route(
            leg(null, distance = listOf(1.0, 1.0, 1.0, 1.0)),
            leg(listOf("severe", "low")),
        )

        val features = CongestionSegments.build(route, coordinates, true)

        assertEquals(coordinates.subList(4, 6), features.single().points())
    }

    @Test
    fun noTrafficOrMismatchedGeometry_nothingDrawn() {
        assertTrue(CongestionSegments.build(route(leg(null, listOf(1.0))), coordinates, true).isEmpty())
        assertTrue(CongestionSegments.build(route(leg(listOf("heavy", "heavy"))), coordinates, true).isEmpty())
    }

    @Test
    fun trim_dropsTraveledCutsCurrentKeepsAhead() {
        val features = CongestionSegments.build(
            route(leg(listOf("heavy", "heavy", "heavy", "low", "moderate", "moderate"))), coordinates, true
        )
        val heavy = features[0] // segments 0..2
        val moderate = features[1] // segments 4..5
        val location = Point.fromLngLat(76.9015, 43.2)

        val cut = CongestionSegments.trim(heavy, 1, location)!!
        assertEquals(listOf(location) + coordinates.subList(2, 4), cut.points())
        assertEquals("heavy", cut.level())
        assertSame(moderate, CongestionSegments.trim(moderate, 1, location))
        assertNull(CongestionSegments.trim(heavy, 3, location))
    }
}
