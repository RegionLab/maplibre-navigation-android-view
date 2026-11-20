package org.maplibre.navigation.android.navigation.ui.v5.route

import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.utils.Constants

/**
 * Builds the line features of a route: the route line, traffic on top of it and the fade at the
 * vanishing point. Everything is placed by distance along the route, so the line can be cut at
 * any point, also between two route coordinates.
 *
 * - Traffic: the `congestion` annotation (Mapbox `driving-traffic`) has one level per segment
 *   between two route coordinates. Moderate traffic and heavy/severe traffic get their color, the
 *   route line below shows free flow and unknown traffic. Where the level changes the colors blend
 *   over [blendMeters] in [BLEND_STEPS] steps, marked with [BLEND_KEY] for their own layer: round
 *   caps of the short steps would show as beads.
 * - Fade: with [features] `fade = true` the line starts [fadeMeters] after the traveled distance,
 *   [fadeFeature] is the part in between, drawn with a gradient from transparent to opaque.
 *
 * Features don't contain colors, only paint keys ([PAINT_KEY]) that the route layers map to the
 * colors of the primary or an alternative route, so a route can change between both.
 */
internal class RouteLinePainter(
    val geometry: RouteGeometry,
    segmentLevels: List<String?>?,
    private val blendMeters: Double = DEFAULT_BLEND_METERS,
    private val fadeMeters: Double = DEFAULT_FADE_METERS,
) {

    /** Part of the route with one traffic level. */
    private class Interval(val start: Double, val end: Double, val level: String) {
        val length get() = end - start
    }

    /** Colored part of the line on top of the route line, [blend] for the steps between colors. */
    private class Piece(val start: Double, val end: Double, val paint: String, val blend: Boolean = false)

    private val intervals: List<Interval> = buildIntervals(segmentLevels)
    private val pieces: List<Piece> = buildPieces()

    /** True if the route has traffic, i.e. any colored part. */
    val hasTraffic: Boolean get() = pieces.isNotEmpty()

    /**
     * @param traveled distance from the start of the route, the line starts here
     * @param fade line gets opaque over [fadeMeters] after [traveled] instead of starting opaque
     */
    fun features(traveled: Double, isPrimary: Boolean, fade: Boolean): List<Feature> {
        val length = geometry.length
        val start = traveled.coerceIn(0.0, length)
        val fadeEnd = if (fade) (start + fadeMeters).coerceAtMost(length) else start
        val features = ArrayList<Feature>()

        geometry.slice(fadeEnd, length).takeIf { it.isNotEmpty() }?.let { points ->
            features += line(points, isPrimary)
        }
        for (piece in pieces) {
            if (piece.end <= fadeEnd) continue
            geometry.slice(maxOf(piece.start, fadeEnd), piece.end).takeIf { it.isNotEmpty() }?.let { points ->
                features += line(points, isPrimary).apply {
                    addStringProperty(PAINT_KEY, piece.paint)
                    if (piece.blend) addBooleanProperty(BLEND_KEY, true)
                }
            }
        }
        return features
    }

    /**
     * Part of the route between [traveled] and the start of the line drawn by [features] with
     * `fade = true`, null if nothing is left. Its paint key is the traffic level in its middle.
     */
    fun fadeFeature(traveled: Double): Feature? {
        val start = traveled.coerceIn(0.0, geometry.length)
        val end = (start + fadeMeters).coerceAtMost(geometry.length)
        val points = geometry.slice(start, end).takeIf { it.isNotEmpty() } ?: return null
        return line(points, isPrimary = true).apply { addStringProperty(PAINT_KEY, levelAt((start + end) / 2)) }
    }

    private fun line(points: List<org.maplibre.geojson.Point>, isPrimary: Boolean) =
        Feature.fromGeometry(LineString.fromLngLats(points)).apply {
            addBooleanProperty(RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY, isPrimary)
        }

    private fun levelAt(distance: Double): String =
        intervals.firstOrNull { distance >= it.start && distance < it.end }?.level ?: LEVEL_BASE

    /** Intervals covering the whole route, consecutive segments with the same level merged. */
    private fun buildIntervals(segmentLevels: List<String?>?): List<Interval> {
        val coordinates = geometry.coordinates
        if (segmentLevels == null || segmentLevels.size != coordinates.size - 1 || segmentLevels.isEmpty()) {
            // No traffic, or annotations that don't match the geometry (e.g. a simplified overview)
            return listOf(Interval(0.0, geometry.length, LEVEL_BASE))
        }
        val result = ArrayList<Interval>()
        var start = 0
        while (start < segmentLevels.size) {
            val level = normalize(segmentLevels[start])
            var end = start
            while (end + 1 < segmentLevels.size && normalize(segmentLevels[end + 1]) == level) {
                end++
            }
            result += Interval(geometry.distanceOfCoordinate(start), geometry.distanceOfCoordinate(end + 1), level)
            start = end + 1
        }
        return result.filter { it.length > 0.0 }
    }

    /**
     * Solid parts of the traffic intervals and blend steps where the level changes. Blends take at
     * most half of each neighbouring interval, so short intervals keep their color in the middle.
     */
    private fun buildPieces(): List<Piece> {
        val result = ArrayList<Piece>()
        // Half blend lengths at the start and end of each interval
        val startBlend = DoubleArray(intervals.size)
        val endBlend = DoubleArray(intervals.size)
        for (index in 0 until intervals.size - 1) {
            val left = intervals[index]
            val right = intervals[index + 1]
            val half = minOf(blendMeters / 2, left.length / 2, right.length / 2)
            endBlend[index] = half
            startBlend[index + 1] = half
            if (half <= 0.0) continue
            val boundary = left.end
            val step = 2 * half / BLEND_STEPS
            for (k in 0 until BLEND_STEPS) {
                val from = boundary - half + k * step
                // Steps are drawn with butt caps, the overlap closes gaps in curves
                val to = if (k == BLEND_STEPS - 1) from + step else from + step + minOf(BLEND_OVERLAP_METERS, step)
                result += Piece(from, to, blendKey(left.level, right.level, k), blend = true)
            }
        }
        intervals.forEachIndexed { index, interval ->
            if (interval.level == LEVEL_BASE) return@forEachIndexed
            val from = interval.start + startBlend[index]
            val to = interval.end - endBlend[index]
            if (to > from) result += Piece(from, to, interval.level)
        }
        result.sortBy { it.start }
        return result
    }

    companion object {
        const val PAINT_KEY = "paint"
        const val BLEND_KEY = "blend"

        const val LEVEL_BASE = "base"
        const val LEVEL_MODERATE = "moderate"
        const val LEVEL_SEVERE = "severe"

        /** Levels in the paint keys, heavy traffic has the color of severe traffic. */
        @JvmField
        val LEVELS = listOf(LEVEL_BASE, LEVEL_MODERATE, LEVEL_SEVERE)

        const val BLEND_STEPS = 8
        const val DEFAULT_BLEND_METERS = 40.0
        private const val BLEND_OVERLAP_METERS = 1.0
        const val DEFAULT_FADE_METERS = 25.0

        /** Paint key of blend step [step] of [BLEND_STEPS] from level [from] to [to]. */
        @JvmStatic
        fun blendKey(from: String, to: String, step: Int) = "$from>$to:$step"

        /** Mix of the blend step: 0 is the color of the first level, 1 of the second. */
        @JvmStatic
        fun blendFraction(step: Int) = (step + 0.5) / BLEND_STEPS

        private fun normalize(level: String?): String = when (level) {
            RouteConstants.MODERATE_CONGESTION_VALUE -> LEVEL_MODERATE
            RouteConstants.HEAVY_CONGESTION_VALUE, RouteConstants.SEVERE_CONGESTION_VALUE -> LEVEL_SEVERE
            else -> LEVEL_BASE
        }

        /** Painter of [route], decodes its geometry. */
        @JvmStatic
        fun create(route: DirectionsRoute): RouteLinePainter {
            val coordinates = LineString.fromPolyline(route.geometry, Constants.PRECISION_6).coordinates()
            return RouteLinePainter(RouteGeometry(coordinates), segmentLevels(route))
        }

        /**
         * Levels of all route segments. Legs share their connecting coordinate, so the segments of
         * the legs follow each other. A leg without congestion counts as unknown traffic.
         *
         * @return null if no leg has traffic or the segment count of a leg is unknown
         */
        internal fun segmentLevels(route: DirectionsRoute): List<String?>? {
            if (route.legs.none { !it.annotation?.congestion.isNullOrEmpty() }) {
                return null
            }
            val levels = ArrayList<String?>()
            for (leg in route.legs) {
                val annotation = leg.annotation ?: return null
                val congestion = annotation.congestion
                if (congestion != null) {
                    levels += congestion
                } else {
                    val segments = (annotation.distance ?: annotation.duration ?: return null).size
                    repeat(segments) { levels += null }
                }
            }
            return levels
        }
    }
}
