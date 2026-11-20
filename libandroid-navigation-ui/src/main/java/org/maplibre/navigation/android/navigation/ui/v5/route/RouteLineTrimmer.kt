package org.maplibre.navigation.android.navigation.ui.v5.route

import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Cuts the traveled part off a route line: the remaining line starts at the user location
 * projected onto the route.
 *
 * Projections search forward from the last position within a window of segments, so long routes
 * aren't searched completely on every location update. Positions farther than
 * [maxSnapDistanceMeters] from the route (e.g. while off route) don't change the line.
 */
internal class RouteLineTrimmer @JvmOverloads constructor(
    private val coordinates: List<Point>,
    private val maxSnapDistanceMeters: Double = DEFAULT_MAX_SNAP_DISTANCE_METERS,
    private val minUpdateDistanceMeters: Double = DEFAULT_MIN_UPDATE_DISTANCE_METERS,
    private val searchWindow: Int = DEFAULT_SEARCH_WINDOW,
) {

    /** Index of the segment the remaining line starts on. */
    var segmentIndex: Int = 0
        private set

    private var lastStart: Point? = null

    class Result(
        /** Coordinates of the remaining route, starting at the projected location. */
        val remaining: List<Point>,
        /** Index of the route segment the remaining route starts on. */
        val segmentIndex: Int,
    )

    /**
     * @return the remaining route, or null if it didn't change noticeably or [location] is too
     * far from the route
     */
    fun trim(location: Point): Result? {
        val located = locate(location) ?: return null
        val remaining = ArrayList<Point>(coordinates.size - located.segmentIndex)
        remaining += located.point
        remaining += coordinates.subList(located.segmentIndex + 1, coordinates.size)
        return Result(remaining, located.segmentIndex)
    }

    class Located(
        /** Index of the route segment the location is projected onto. */
        val segmentIndex: Int,
        /** Location projected onto the route. */
        val point: Point,
    )

    /**
     * @return the location projected onto the route, or null if it didn't change noticeably or
     * [location] is too far from the route
     */
    fun locate(location: Point): Located? {
        if (coordinates.size < 2) {
            return null
        }
        val lastSegment = coordinates.size - 2
        val windowStart = (segmentIndex - 1).coerceAtLeast(0)
        val windowEnd = (segmentIndex + searchWindow).coerceAtMost(lastSegment)
        var projection = project(location, windowStart, windowEnd)
        if (projection.distance > maxSnapDistanceMeters && (windowStart > 0 || windowEnd < lastSegment)) {
            // Lost the position, e.g. after a jump of the location, search the whole route
            projection = project(location, 0, lastSegment)
        }
        if (projection.distance > maxSnapDistanceMeters) {
            return null
        }
        val previousStart = lastStart
        if (previousStart != null && projection.segment == segmentIndex &&
            distanceMeters(previousStart, projection.point) < minUpdateDistanceMeters
        ) {
            return null
        }
        segmentIndex = projection.segment
        lastStart = projection.point
        return Located(projection.segment, projection.point)
    }

    private class Projection(val segment: Int, val point: Point, val distance: Double)

    private fun project(location: Point, fromSegment: Int, toSegment: Int): Projection {
        var best: Projection? = null
        for (segment in fromSegment..toSegment) {
            val candidate = projectOnSegment(location, segment)
            if (best == null || candidate.distance < best.distance) {
                best = candidate
            }
        }
        return best!!
    }

    /**
     * Projection in a local equirectangular approximation, accurate enough for the few meters
     * between a location and its route.
     */
    private fun projectOnSegment(location: Point, segment: Int): Projection {
        val start = coordinates[segment]
        val end = coordinates[segment + 1]
        val metersPerDegreeLon = METERS_PER_DEGREE * cos(Math.toRadians(location.latitude()))
        val ax = (start.longitude() - location.longitude()) * metersPerDegreeLon
        val ay = (start.latitude() - location.latitude()) * METERS_PER_DEGREE
        val bx = (end.longitude() - location.longitude()) * metersPerDegreeLon
        val by = (end.latitude() - location.latitude()) * METERS_PER_DEGREE
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        val point = Point.fromLngLat(
            start.longitude() + t * (end.longitude() - start.longitude()),
            start.latitude() + t * (end.latitude() - start.latitude()),
        )
        return Projection(segment, point, hypot(px, py))
    }

    private fun distanceMeters(a: Point, b: Point): Double {
        val metersPerDegreeLon = METERS_PER_DEGREE * cos(Math.toRadians(a.latitude()))
        return hypot(
            (a.longitude() - b.longitude()) * metersPerDegreeLon,
            (a.latitude() - b.latitude()) * METERS_PER_DEGREE,
        )
    }

    companion object {
        const val DEFAULT_MAX_SNAP_DISTANCE_METERS = 50.0
        const val DEFAULT_MIN_UPDATE_DISTANCE_METERS = 2.0
        const val DEFAULT_SEARCH_WINDOW = 200
        private const val METERS_PER_DEGREE = 111_320.0
    }
}
