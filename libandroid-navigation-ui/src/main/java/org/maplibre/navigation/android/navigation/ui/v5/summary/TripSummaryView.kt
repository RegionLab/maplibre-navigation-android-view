package org.maplibre.navigation.android.navigation.ui.v5.summary

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.navigation.android.navigation.ui.v5.R
import org.maplibre.navigation.android.navigation.ui.v5.utils.DistanceFormatter
import org.maplibre.navigation.android.navigation.ui.v5.utils.time.TimeFormatter
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.routeprogress.RouteProgress
import java.util.Calendar

/**
 * Shows the remaining time, the remaining distance and the arrival time of the route.
 */
class TripSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val timeRemainingText: TextView
    private val detailsText: TextView

    init {
        orientation = HORIZONTAL
        inflate(context, R.layout.trip_summary_view_layout, this)
        timeRemainingText = findViewById(R.id.tripTimeRemainingText)
        detailsText = findViewById(R.id.tripDetailsText)
    }

    fun update(
        routeProgress: RouteProgress,
        distanceFormatter: DistanceFormatter,
        timeFormat: MapLibreNavigationOptions.TimeFormat,
    ) {
        val durationRemaining = routeProgress.durationRemaining.coerceAtLeast(0.0)
        setTextIfChanged(timeRemainingText, TimeFormatter.formatTimeRemaining(context, durationRemaining))

        val arrivalTime = TimeFormatter.formatTime(
            Calendar.getInstance(),
            durationRemaining,
            timeFormat,
            DateFormat.is24HourFormat(context)
        )
        val details = SpannableStringBuilder(distanceFormatter.formatDistance(routeProgress.distanceRemaining))
        if (!arrivalTime.isNullOrBlank()) {
            details.append(SEPARATOR).append(arrivalTime.trim())
        }
        setTextIfChanged(detailsText, details)
    }

    fun clear() {
        timeRemainingText.text = null
        detailsText.text = null
    }

    // Avoids relayouts: the texts only change about once a minute, progress arrives every second
    private fun setTextIfChanged(textView: TextView, text: CharSequence) {
        if (textView.text.toString() != text.toString()) {
            textView.text = text
        }
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}
