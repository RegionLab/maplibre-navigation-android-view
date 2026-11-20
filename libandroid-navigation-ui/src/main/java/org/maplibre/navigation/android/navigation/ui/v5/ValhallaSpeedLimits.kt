package org.maplibre.navigation.android.navigation.ui.v5

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegAnnotation
import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.SpeedLimit

/**
 * Turns Valhalla `trace_attributes` edges into `distance` and `maxspeed` leg annotations, which
 * navigation uses to show the speed limit at the current position.
 *
 * Navigation looks up annotations by the distance traveled along a leg, so the edges only need
 * correct lengths, not the exact route geometry.
 */
internal object ValhallaSpeedLimits {

    private const val METERS_PER_KILOMETER = 1000.0

    fun createTraceAttributesBody(polyline6: String, costing: String): JsonObject = buildJsonObject {
        put("encoded_polyline", polyline6)
        // Map matching, the exact edge walk fails at waypoints where the route turns back
        put("shape_match", "map_snap")
        put("costing", costing)
        put("units", "kilometers")
        putJsonObject("filters") {
            put("action", "include")
            putJsonArray("attributes") {
                add("edge.speed_limit")
                add("edge.length")
            }
        }
    }

    /**
     * Adds speed limit annotations to all legs of [route]. Legs that already have annotations,
     * and routes without edges, are left unchanged.
     */
    fun applyTo(route: DirectionsRoute, traceAttributes: JsonObject): DirectionsRoute {
        val segments = traceAttributes["edges"]?.jsonArray
            ?.mapNotNull { edge -> edge.jsonObject.toSegment() }
            ?.mergeEqualSpeedLimits()
            ?.scaledTo(route.distance)
            ?.takeIf { it.isNotEmpty() }
            ?: return route

        val legSegments = segments.splitByLegs(route.legs.map { it.distance })
        return route.copy(legs = route.legs.mapIndexed { index, leg ->
            val forLeg = legSegments[index]
            if (leg.annotation != null || forLeg.isEmpty()) {
                leg
            } else {
                leg.copy(annotation = LegAnnotation(
                    distance = forLeg.map { it.length },
                    maxSpeed = forLeg.map { it.maxSpeed },
                ))
            }
        })
    }

    private data class Segment(val length: Double, val maxSpeed: MaxSpeed)

    private fun JsonObject.toSegment(): Segment? {
        val lengthKm = (get("length") as? JsonPrimitive)?.doubleOrNull ?: return null
        val speedLimit = get("speed_limit") as? JsonPrimitive
        val maxSpeed = when {
            speedLimit == null -> MaxSpeed(unknown = true)
            speedLimit.isString && speedLimit.content == "unlimited" -> MaxSpeed(none = true)
            (speedLimit.intOrNull ?: 0) > 0 -> MaxSpeed(speed = speedLimit.intOrNull, unit = SpeedLimit.Unit.KMPH)
            else -> MaxSpeed(unknown = true)
        }
        return Segment(lengthKm * METERS_PER_KILOMETER, maxSpeed)
    }

    private fun List<Segment>.mergeEqualSpeedLimits(): List<Segment> = fold(mutableListOf()) { merged, segment ->
        val last = merged.lastOrNull()
        if (last != null && last.maxSpeed == segment.maxSpeed) {
            merged[merged.lastIndex] = last.copy(length = last.length + segment.length)
        } else {
            merged += segment
        }
        merged
    }

    /** Map matched lengths differ slightly from the route, make them add up to its distance. */
    private fun List<Segment>.scaledTo(totalLength: Double): List<Segment> {
        val sum = sumOf { it.length }
        if (sum <= 0.0 || totalLength <= 0.0) {
            return this
        }
        val factor = totalLength / sum
        return map { it.copy(length = it.length * factor) }
    }

    private fun List<Segment>.splitByLegs(legLengths: List<Double>): List<List<Segment>> {
        val remaining = ArrayDeque(this)
        return legLengths.mapIndexed { legIndex, legLength ->
            val isLastLeg = legIndex == legLengths.lastIndex
            val legSegments = mutableListOf<Segment>()
            var missing = legLength
            while (remaining.isNotEmpty() && (isLastLeg || missing > 0.0)) {
                val segment = remaining.removeFirst()
                if (!isLastLeg && segment.length > missing) {
                    // The segment continues in the next leg
                    legSegments += segment.copy(length = missing)
                    remaining.addFirst(segment.copy(length = segment.length - missing))
                    missing = 0.0
                } else {
                    legSegments += segment
                    missing -= segment.length
                }
            }
            legSegments
        }
    }
}
