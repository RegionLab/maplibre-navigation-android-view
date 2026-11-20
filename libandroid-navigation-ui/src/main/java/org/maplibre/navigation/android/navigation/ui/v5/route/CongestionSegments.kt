package org.maplibre.navigation.android.navigation.ui.v5.route

import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.navigation.core.models.DirectionsRoute

/**
 * Traffic on the route line: the `congestion` annotation (Mapbox `driving-traffic` profile) has one
 * level per segment between two route coordinates. Consecutive segments with the same level are
 * merged into one line, so a route has a few dozen features instead of one per segment.
 *
 * Only moderate, heavy and severe traffic gets a line, the route line below shows free flow and
 * unknown traffic.
 */
internal object CongestionSegments {

    /** First and last route segment of a congestion line, used to cut off the traveled part. */
    const val START_SEGMENT_KEY = "congestion_start"
    const val END_SEGMENT_KEY = "congestion_end"

    private val DRAWN_LEVELS = setOf(
        RouteConstants.MODERATE_CONGESTION_VALUE,
        RouteConstants.HEAVY_CONGESTION_VALUE,
        RouteConstants.SEVERE_CONGESTION_VALUE,
    )

    /**
     * @param coordinates coordinates of the route geometry
     */
    @JvmStatic
    fun build(route: DirectionsRoute, coordinates: List<Point>, isPrimary: Boolean): List<Feature> {
        val levels = segmentLevels(route) ?: return emptyList()
        if (levels.size != coordinates.size - 1) {
            // Annotations don't match the geometry (e.g. a simplified overview), drawing them would
            // put traffic on the wrong streets
            return emptyList()
        }
        val features = ArrayList<Feature>()
        var start = 0
        while (start < levels.size) {
            val level = levels[start]
            var end = start
            while (end + 1 < levels.size && levels[end + 1] == level) {
                end++
            }
            if (level in DRAWN_LEVELS) {
                features += feature(coordinates.subList(start, end + 2), level, isPrimary, start, end)
            }
            start = end + 1
        }
        return features
    }

    /**
     * Levels of all route segments. Legs share their connecting coordinate, so the segments of
     * the legs follow each other. A leg without congestion counts as unknown traffic.
     *
     * @return null if no leg has traffic or the segment count of a leg is unknown
     */
    private fun segmentLevels(route: DirectionsRoute): List<String?>? {
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

    /**
     * Removes the traveled part of a congestion line.
     *
     * @param segmentIndex route segment of the current location
     * @param location current location projected onto the route
     * @return the remaining part, null if the line is traveled completely, [feature] unchanged if
     * it is completely ahead or isn't a congestion line
     */
    @JvmStatic
    fun trim(feature: Feature, segmentIndex: Int, location: Point): Feature? {
        if (!feature.hasProperty(START_SEGMENT_KEY) || !feature.hasProperty(END_SEGMENT_KEY)) {
            return feature
        }
        val start = feature.getNumberProperty(START_SEGMENT_KEY).toInt()
        val end = feature.getNumberProperty(END_SEGMENT_KEY).toInt()
        return when {
            segmentIndex < start -> feature
            segmentIndex > end -> null
            else -> {
                val coordinates = (feature.geometry() as LineString).coordinates()
                // Coordinates of the line: route coordinates start..end + 1
                val remaining = listOf(location) + coordinates.subList(segmentIndex - start + 1, coordinates.size)
                Feature.fromGeometry(LineString.fromLngLats(remaining), feature.properties())
            }
        }
    }

    private fun feature(points: List<Point>, level: String?, isPrimary: Boolean, start: Int, end: Int) =
        Feature.fromGeometry(LineString.fromLngLats(ArrayList(points))).apply {
            addStringProperty(RouteConstants.CONGESTION_KEY, level)
            addBooleanProperty(RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY, isPrimary)
            addNumberProperty(START_SEGMENT_KEY, start)
            addNumberProperty(END_SEGMENT_KEY, end)
        }
}
