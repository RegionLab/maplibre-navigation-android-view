package org.maplibre.navigation.android.navigation.ui.v5.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegAnnotation
import org.maplibre.navigation.core.models.RouteLeg

class RouteLinePainterTest {

    // 11 points along a meridian, 100 m apart: a 1 km route with 10 segments
    private val metersPerDegree = 111_320.0
    private val coordinates = (0..10).map { Point.fromLngLat(76.9, 43.2 + it * 100 / metersPerDegree) }
    private val geometry = RouteGeometry(coordinates)

    private fun Feature.length(): Double = (geometry() as LineString).coordinates()
        .zipWithNext { a, b -> RouteGeometry.distanceMeters(a, b) }.sum()

    private fun Feature.startDistance(): Double =
        RouteGeometry.distanceMeters(coordinates.first(), (geometry() as LineString).coordinates().first())

    private fun Feature.paint(): String? =
        if (hasProperty(RouteLinePainter.PAINT_KEY)) getStringProperty(RouteLinePainter.PAINT_KEY) else null

    private fun painter(levels: List<String?>?) = RouteLinePainter(geometry, levels, blendMeters = 40.0, fadeMeters = 20.0)

    // region Geometry

    @Test
    fun geometry_sliceBetweenCoordinates() {
        assertEquals(1000.0, geometry.length, 0.01)

        val slice = geometry.slice(150.0, 420.0)

        assertEquals(5, slice.size) // 150, 200, 300, 400, 420
        assertEquals(150.0, RouteGeometry.distanceMeters(coordinates.first(), slice.first()), 0.01)
        assertEquals(420.0, RouteGeometry.distanceMeters(coordinates.first(), slice.last()), 0.01)
        assertTrue(geometry.slice(500.0, 500.0).isEmpty())
        assertEquals(2, geometry.slice(990.0, 2000.0).size)
    }

    @Test
    fun geometry_distanceOfProjectedPoint() {
        assertEquals(250.0, geometry.distanceAt(2, geometry.pointAt(250.0)), 0.01)
    }

    // endregion

    @Test
    fun noTraffic_onlyRouteLine() {
        val features = painter(null).features(0.0, isPrimary = true, fade = false)

        assertEquals(1, features.size)
        assertEquals(1000.0, features.single().length(), 0.01)
        assertTrue(features.single().getBooleanProperty(RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY))
    }

    @Test
    fun traffic_solidPartAndBlendSteps() {
        // Moderate traffic on segments 3..5 (300 m - 600 m), heavy is drawn as severe
        val levels = listOf("low", "low", "low", "moderate", "moderate", "moderate", "unknown", "heavy", "low", "low")
        val features = painter(levels).features(0.0, isPrimary = true, fade = false)

        val moderate = features.single { it.paint() == RouteLinePainter.LEVEL_MODERATE }
        // 300..600 without the half blend of 20 m at both ends
        assertEquals(320.0, moderate.startDistance(), 0.01)
        assertEquals(260.0, moderate.length(), 0.01)

        val blendIn = features.filter { it.paint()?.startsWith("base>moderate:") == true }
        assertEquals(RouteLinePainter.BLEND_STEPS, blendIn.size)
        assertEquals(280.0, blendIn.first().startDistance(), 0.01)
        // 40 m, steps overlap by 1 m except the last one
        assertEquals(40.0 + (RouteLinePainter.BLEND_STEPS - 1), blendIn.sumOf { it.length() }, 0.01)
        assertTrue(blendIn.all { it.getBooleanProperty(RouteLinePainter.BLEND_KEY) })
        assertFalse(moderate.hasProperty(RouteLinePainter.BLEND_KEY))
        assertEquals(RouteLinePainter.blendKey("base", "moderate", 0), blendIn.first().paint())

        assertTrue(features.any { it.paint() == RouteLinePainter.LEVEL_SEVERE })
        assertTrue(features.any { it.paint() == RouteLinePainter.blendKey("severe", "base", 7) })
    }

    @Test
    fun traffic_shortIntervalKeepsColorInTheMiddle() {
        // 100 m of severe traffic: blends take at most half of it
        val levels = listOf("low", "low", "low", "low", "severe", "low", "low", "low", "low", "low")
        val features = painter(levels).features(0.0, isPrimary = true, fade = false)

        val severe = features.single { it.paint() == RouteLinePainter.LEVEL_SEVERE }
        assertEquals(420.0, severe.startDistance(), 0.01)
        assertEquals(60.0, severe.length(), 0.01)
    }

    @Test
    fun traveled_lineStartsAtTraveledDistance() {
        val levels = listOf("moderate") + List(9) { "low" }
        val features = painter(levels).features(250.0, isPrimary = true, fade = false)

        assertEquals(750.0, features.first().length(), 0.01)
        assertEquals(250.0, features.first().startDistance(), 0.01)
        // The moderate part (0..100 m) is traveled
        assertFalse(features.any { it.paint()?.contains("moderate") == true })
    }

    @Test
    fun fade_lineStartsAfterFadeFeature() {
        val painter = painter(null)

        val line = painter.features(250.0, isPrimary = true, fade = true).single()
        val fade = painter.fadeFeature(250.0)!!

        assertEquals(270.0, line.startDistance(), 0.01)
        assertEquals(250.0, fade.startDistance(), 0.01)
        assertEquals(20.0, fade.length(), 0.01)
        assertEquals(RouteLinePainter.LEVEL_BASE, fade.paint())
    }

    @Test
    fun fade_takesTrafficColor() {
        val levels = List(2) { "low" } + List(8) { "severe" }

        assertEquals(RouteLinePainter.LEVEL_SEVERE, painter(levels).fadeFeature(500.0)!!.paint())
        assertEquals(null, painter(levels).fadeFeature(1000.0))
    }

    @Test
    fun create_routeWithStops_trafficContinuesInSecondLeg() {
        val route = DirectionsRoute(
            geometry = LineString.fromLngLats(coordinates).toPolyline(6), distance = 1000.0, duration = 0.0,
            legs = listOf(
                RouteLeg(distance = 0.0, duration = 0.0, steps = emptyList(), annotation = LegAnnotation(distance = List(6) { 100.0 })),
                RouteLeg(distance = 0.0, duration = 0.0, steps = emptyList(), annotation = LegAnnotation(congestion = List(4) { "heavy" })),
            ),
        )

        val features = RouteLinePainter.create(route).features(0.0, isPrimary = false, fade = false)

        val severe = features.single { it.paint() == RouteLinePainter.LEVEL_SEVERE }
        assertEquals(620.0, severe.startDistance(), 0.5)
        assertFalse(severe.getBooleanProperty(RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY))
    }

    @Test
    fun mismatchedAnnotations_noTraffic() {
        assertFalse(painter(listOf("heavy", "heavy")).hasTraffic)
    }
}
