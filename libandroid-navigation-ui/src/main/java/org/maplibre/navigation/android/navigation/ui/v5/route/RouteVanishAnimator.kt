package org.maplibre.navigation.android.navigation.ui.v5.route

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.LinearInterpolator

/**
 * Moves the vanishing point of the route smoothly between location updates instead of jumping
 * once per update. The point follows the user location puck, which is animated between location
 * updates as well: each move takes as long as the time since the previous location update.
 *
 * @param onFrame draws the route from the given traveled distance, at most every [FRAME_MS] and
 * after moving at least [MIN_FRAME_METERS]
 */
internal class RouteVanishAnimator @JvmOverloads constructor(
    private val onFrame: (traveled: Double) -> Unit,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {

    /** Traveled distance currently drawn. */
    var traveled: Double = 0.0
        private set

    private var animator: ValueAnimator? = null
    /** Time of the previous [animateTo], null before the first one. */
    private var lastTargetTime: Long? = null
    private var lastFrameTime = 0L
    private var lastDrawn = 0.0

    /**
     * Moves the vanishing point to [target] meters from the route start. Moving back by GPS noise
     * is ignored, the traveled route never appears again. Jumps longer than [MAX_ANIMATED_METERS]
     * aren't animated.
     */
    fun animateTo(target: Double) {
        val now = clock()
        val previousTargetTime = lastTargetTime
        lastTargetTime = now
        if (target <= traveled) {
            return
        }
        animator?.cancel()
        if (previousTargetTime == null || target - traveled > MAX_ANIMATED_METERS) {
            draw(target, now)
            return
        }
        val from = traveled
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (now - previousTargetTime).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction.toDouble()
                if (fraction <= 0.0) return@addUpdateListener
                val distance = from + (target - from) * fraction
                val frameTime = clock()
                val moved = distance - lastDrawn >= MIN_FRAME_METERS
                if (fraction >= 1.0 || (moved && frameTime - lastFrameTime >= FRAME_MS)) {
                    draw(distance, frameTime)
                } else {
                    traveled = distance
                }
            }
            start()
        }
    }

    /** Stops the animation and starts over at the route start. */
    fun reset() {
        animator?.cancel()
        animator = null
        traveled = 0.0
        lastTargetTime = null
        lastFrameTime = 0L
        lastDrawn = 0.0
    }

    private fun draw(distance: Double, time: Long) {
        traveled = distance
        lastDrawn = distance
        lastFrameTime = time
        onFrame(distance)
    }

    companion object {
        /** About 15 fps: about 1 m per frame at 50 km/h. */
        const val FRAME_MS = 66L

        /** Frames moving less, e.g. in a traffic jam, aren't drawn. */
        const val MIN_FRAME_METERS = 0.5
        const val MIN_DURATION_MS = 200L
        const val MAX_DURATION_MS = 1500L
        const val MAX_ANIMATED_METERS = 150.0
    }
}
