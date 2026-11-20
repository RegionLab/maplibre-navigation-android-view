package org.maplibre.navigation.android.navigation.ui.v5

import org.maplibre.navigation.core.models.MaxSpeed
import org.maplibre.navigation.core.models.SpeedLimit
import org.maplibre.navigation.core.models.UnitType
import kotlin.math.roundToInt

/**
 * What the speed and speed limit views show, in the unit of the route.
 */
internal data class SpeedDisplay(
    val speed: Int,
    val sign: SpeedLimitSign,
    val isSpeeding: Boolean,
)

internal sealed interface SpeedLimitSign {
    /** No sign, e.g. while not navigating or when the limit is unknown. */
    data object Hidden : SpeedLimitSign

    /** The route has no speed limit data here. */
    data object Unknown : SpeedLimitSign

    /** No speed limit applies, e.g. on parts of the German Autobahn. */
    data object NoLimit : SpeedLimitSign

    data class Limit(val value: Int) : SpeedLimitSign
}

internal object SpeedDisplayCalculator {

    private const val MS_TO_KMH = 3.6
    private const val MS_TO_MPH = 2.2369363
    private const val KM_PER_MILE = 1.609344

    /**
     * @param speedMetersPerSecond current speed, null or invalid values count as standing still
     * @param maxSpeed speed limit at the current position, null if there is none (yet)
     * @param unit unit to show speed and limit in
     * @param speedingTolerance speed above the limit, in [unit], that still doesn't count as speeding
     * @param isNavigating speed limits are only known while navigating
     * @param showUnknown show a placeholder sign when the speed limit is unknown
     */
    fun calculate(
        speedMetersPerSecond: Float?,
        maxSpeed: MaxSpeed?,
        unit: UnitType,
        speedingTolerance: Int,
        isNavigating: Boolean,
        showUnknown: Boolean,
    ): SpeedDisplay {
        val speed = displaySpeed(speedMetersPerSecond, unit)
        val sign = when {
            !isNavigating -> SpeedLimitSign.Hidden
            maxSpeed?.none == true -> SpeedLimitSign.NoLimit
            maxSpeed?.speed != null && maxSpeed.unknown != true ->
                SpeedLimitSign.Limit(convertLimit(maxSpeed.speed!!, maxSpeed.unit, unit))
            showUnknown -> SpeedLimitSign.Unknown
            else -> SpeedLimitSign.Hidden
        }
        val isSpeeding = sign is SpeedLimitSign.Limit && speed > sign.value + speedingTolerance
        return SpeedDisplay(speed, sign, isSpeeding)
    }

    fun displaySpeed(speedMetersPerSecond: Float?, unit: UnitType): Int {
        val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val factor = if (unit == UnitType.IMPERIAL) MS_TO_MPH else MS_TO_KMH
        return (speed * factor).roundToInt()
    }

    private fun convertLimit(speed: Int, from: SpeedLimit.Unit?, to: UnitType): Int = when {
        from == SpeedLimit.Unit.MPH && to == UnitType.METRIC -> (speed * KM_PER_MILE).roundToInt()
        from == SpeedLimit.Unit.KMPH && to == UnitType.IMPERIAL -> (speed / KM_PER_MILE).roundToInt()
        else -> speed
    }
}
