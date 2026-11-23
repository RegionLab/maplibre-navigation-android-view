package org.maplibre.navigation.android.navigation.ui.v5.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.geojson.Point

class RouteLineTrimmerTest {

    // About 111 m per 0.001° latitude, a straight line north with 10 points
    private val route = (0..9).map { Point.fromLngLat(76.9, 43.2 + it * 0.001) }

    @Test
    fun trim_startsAtProjectedLocation() {
        val trimmer = RouteLineTrimmer(route)

        // 5 m east of the route, half way along the third segment
        val result = trimmer.trim(Point.fromLngLat(76.90006, 43.2025))!!

        assertEquals(2, result.segmentIndex)
        assertEquals(76.9, result.remaining.first().longitude(), 1e-9)
        assertEquals(43.2025, result.remaining.first().latitude(), 1e-6)
        assertEquals(route.drop(3), result.remaining.drop(1))
    }

    @Test
    fun trim_ignoresSmallMovements() {
        val trimmer = RouteLineTrimmer(route)
        trimmer.trim(Point.fromLngLat(76.9, 43.2025))

        // 1 m further
        assertNull(trimmer.trim(Point.fromLngLat(76.9, 43.20251)))
        assertNotNull(trimmer.trim(Point.fromLngLat(76.9, 43.2026)))
    }

    @Test
    fun trim_farFromRoute_keepsLine() {
        val trimmer = RouteLineTrimmer(route)

        // About 400 m east
        assertNull(trimmer.trim(Point.fromLngLat(76.905, 43.2025)))
    }

    @Test
    fun trim_findsPositionOutsideSearchWindow() {
        val trimmer = RouteLineTrimmer(route, searchWindow = 2)
        trimmer.trim(Point.fromLngLat(76.9, 43.2005))

        // Jump to the second to last segment, outside the window of 2 segments
        val result = trimmer.trim(Point.fromLngLat(76.9, 43.2085))!!

        assertEquals(8, result.segmentIndex)
        assertEquals(2, result.remaining.size)
    }

    @Test
    fun trim_tooShortRoute_returnsNull() {
        assertNull(RouteLineTrimmer(route.take(1)).trim(route.first()))
    }
}
