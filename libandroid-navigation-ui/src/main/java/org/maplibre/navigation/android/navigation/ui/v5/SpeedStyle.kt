package org.maplibre.navigation.android.navigation.ui.v5

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Backgrounds and text colors of the speed and speed limit views from the navigation theme.
 * Themes that don't define the newer attributes get the library defaults.
 */
internal class SpeedStyle(
    val speedBackground: Drawable?,
    val speedTextColor: Int,
    val speedingBackground: Drawable?,
    val speedingTextColor: Int,
    val limitBackground: Drawable?,
    val noLimitBackground: Drawable?,
) {
    companion object {
        fun resolve(context: Context) = SpeedStyle(
            speedBackground = context.drawableAttr(
                R.attr.navigationViewSpeedBackground, R.drawable.navigation_speed_limit_background
            ),
            speedTextColor = context.colorAttr(R.attr.navigationViewSpeedTextColor, Color.BLACK),
            speedingBackground = context.drawableAttr(
                R.attr.navigationViewSpeedingBackground, R.drawable.navigation_speed_speeding_background
            ),
            speedingTextColor = context.colorAttr(R.attr.navigationViewSpeedingTextColor, Color.WHITE),
            limitBackground = context.drawableAttr(
                R.attr.navigationViewSpeedLimitBackground, R.drawable.navigation_speed_limit_red_background
            ),
            noLimitBackground = context.drawableAttr(
                R.attr.navigationViewSpeedLimitNoneBackground, R.drawable.navigation_speed_limit_none_background
            ),
        )

        private fun Context.drawableAttr(@AttrRes attr: Int, @DrawableRes fallback: Int): Drawable? {
            val attributes = obtainStyledAttributes(intArrayOf(attr))
            return try {
                attributes.getDrawable(0) ?: ContextCompat.getDrawable(this, fallback)
            } finally {
                attributes.recycle()
            }
        }

        private fun Context.colorAttr(@AttrRes attr: Int, fallback: Int): Int {
            val attributes = obtainStyledAttributes(intArrayOf(attr))
            return try {
                attributes.getColor(0, fallback)
            } finally {
                attributes.recycle()
            }
        }
    }
}
