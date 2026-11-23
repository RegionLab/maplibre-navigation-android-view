package org.maplibre.navigation.android.navigation.ui.v5.route

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RouteVanishAnimatorTest {

    private val frames = mutableListOf<Double>()
    private val animator = RouteVanishAnimator({ frames += it })

    /** In small steps: Robolectric runs animation frames only for the steps it idles. */
    private fun advance(ms: Long) {
        repeat((ms / 50).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
    }

    @Test
    fun firstPosition_drawnImmediately() {
        animator.animateTo(100.0)

        assertEquals(listOf(100.0), frames)
    }

    @Test
    fun nextPosition_animatedInSmallSteps() {
        animator.animateTo(100.0)
        advance(1000)

        animator.animateTo(115.0)
        advance(2000)

        val steps = frames.drop(1)
        assertTrue("intermediate frames $frames", steps.count { it > 100.0 && it < 115.0 } > 5)
        assertTrue("steps of at most 2 m: $frames", (listOf(100.0) + steps).zipWithNext { a, b -> b - a }.all { it <= 2.0 })
        assertEquals(115.0, frames.last(), 0.001)
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun movingBack_ignored() {
        animator.animateTo(100.0)
        advance(1000)

        animator.animateTo(95.0)
        advance(1000)

        assertEquals(listOf(100.0), frames)
    }

    @Test
    fun longJump_notAnimated() {
        animator.animateTo(100.0)
        advance(1000)

        animator.animateTo(1000.0)

        assertEquals(listOf(100.0, 1000.0), frames)
    }

    @Test
    fun reset_startsOver() {
        animator.animateTo(500.0)
        animator.reset()

        animator.animateTo(10.0)

        assertEquals(listOf(500.0, 10.0), frames)
    }
}
