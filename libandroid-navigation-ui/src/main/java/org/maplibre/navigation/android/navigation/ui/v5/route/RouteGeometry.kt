package org.maplibre.navigation.android.navigation.ui.v5.route

import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Route coordinates with the distance of every coordinate from the start, to cut parts of the
 * route by distance, e.g. "from 1250 m to 1300 m".
 */
internal class RouteGeometry(val coordinates: List<Point>) {

    /** Distance of each coordinate from the start of the route, in meters. */
    private val cumulative = DoubleArray(coordinates.size).also { distances ->
        for (index in 1 until coordinates.size) {
            distances[index] = distances[index - 1] + distanceMeters(coordinates[index - 1], coordinates[index])
        }
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Distance from the start of a point on the segment starting at coordinate [segment]. */
    fun distanceAt(segment: Int, point: Point): Double {
        val index = segment.coerceIn(0, (coordinates.size - 1).coerceAtLeast(0))
        if (coordinates.isEmpty()) return 0.0
        return cumulative[index] + distanceMeters(coordinates[index], point)
    }

    /** Distance of coordinate [index] from the start of the route. */
    fun distanceOfCoordinate(index: Int): Double = cumulative[index.coerceIn(0, cumulative.size - 1)]

    /** Point at [distance] meters from the start of the route. */
    fun pointAt(distance: Double): Point {
        val segment = segmentAt(distance)
        val start = coordinates[segment]
        if (segment >= coordinates.size - 1) return start
        val end = coordinates[segment + 1]
        val segmentLength = cumulative[segment + 1] - cumulative[segment]
        val fraction = if (segmentLength <= 0.0) 0.0 else ((distance - cumulative[segment]) / segmentLength).coerceIn(0.0, 1.0)
        return Point.fromLngLat(
            start.longitude() + fraction * (end.longitude() - start.longitude()),
            start.latitude() + fraction * (end.latitude() - start.latitude()),
        )
    }

    /**
     * Part of the route between two distances from the start.
     *
     * @return at least two points, or an empty list if the part is shorter than a centimeter
     */
    fun slice(from: Double, to: Double): List<Point> {
        val start = from.coerceIn(0.0, length)
        val end = to.coerceIn(0.0, length)
        if (end - start < MIN_SLICE_METERS || coordinates.size < 2) {
            return emptyList()
        }
        val firstSegment = segmentAt(start)
        val lastSegment = segmentAt(end)
        val points = ArrayList<Point>(lastSegment - firstSegment + 2)
        points += pointAt(start)
        for (index in firstSegment + 1..lastSegment) {
            if (cumulative[index] > start && cumulative[index] < end) {
                points += coordinates[index]
            }
        }
        points += pointAt(end)
        return points
    }

    /** Index of the segment containing [distance], the last segment for the route end. */
    private fun segmentAt(distance: Double): Int {
        if (coordinates.size < 2) return 0
        var low = 0
        var high = coordinates.size - 2
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (cumulative[middle] <= distance) low = middle else high = middle - 1
        }
        return low
    }

    companion object {
        private const val MIN_SLICE_METERS = 0.01
        private const val METERS_PER_DEGREE = 111_320.0

        /** Local equirectangular approximation, exact enough for the short segments of a route. */
        fun distanceMeters(a: Point, b: Point): Double {
            val metersPerDegreeLon = METERS_PER_DEGREE * cos(Math.toRadians((a.latitude() + b.latitude()) / 2))
            return hypot(
                (a.longitude() - b.longitude()) * metersPerDegreeLon,
                (a.latitude() - b.latitude()) * METERS_PER_DEGREE,
            )
        }
    }
}
