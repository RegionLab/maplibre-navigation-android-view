package org.maplibre.navigation.core.navigation

import co.touchlab.kermit.Logger
import org.maplibre.navigation.core.location.Location
import org.maplibre.navigation.core.milestone.Milestone
import org.maplibre.navigation.core.milestone.MilestoneEventListener
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.offroute.OffRouteListener
import org.maplibre.navigation.core.route.FasterRouteListener
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener
import org.maplibre.navigation.core.routeprogress.RouteProgress

open class NavigationEventDispatcher {
    private val navigationEventListeners = mutableListOf<NavigationEventListener>()
    private val milestoneEventListeners = mutableListOf<MilestoneEventListener>()
    private val progressChangeListeners = mutableListOf<ProgressChangeListener>()
    private val offRouteListeners = mutableListOf<OffRouteListener>()
    private val fasterRouteListeners = mutableListOf<FasterRouteListener>()

    fun addMilestoneEventListener(milestoneEventListener: MilestoneEventListener) {
        if (milestoneEventListeners.contains(milestoneEventListener)) {
            Logger.w { "The specified MilestoneEventListener has already been added to the stack." }
            return
        }
        milestoneEventListeners.add(milestoneEventListener)
    }

    fun removeMilestoneEventListener(milestoneEventListener: MilestoneEventListener?) {
        if (milestoneEventListener == null) {
            milestoneEventListeners.clear()
        } else if (!milestoneEventListeners.contains(milestoneEventListener)) {
            Logger.w { "The specified MilestoneEventListener isn't found in stack, therefore, cannot be removed." }
        } else {
            milestoneEventListeners.remove(milestoneEventListener)
        }
    }

    fun addProgressChangeListener(progressChangeListener: ProgressChangeListener) {
        if (progressChangeListeners.contains(progressChangeListener)) {
            Logger.w { "The specified ProgressChangeListener has already been added to the stack." }
            return
        }
        progressChangeListeners.add(progressChangeListener)
    }

    fun removeProgressChangeListener(progressChangeListener: ProgressChangeListener?) {
        if (progressChangeListener == null) {
            progressChangeListeners.clear()
        } else if (!progressChangeListeners.contains(progressChangeListener)) {
            Logger.w { "The specified ProgressChangeListener isn't found in stack, therefore, cannot be removed." }
        } else {
            progressChangeListeners.remove(progressChangeListener)
        }
    }

    fun addOffRouteListener(offRouteListener: OffRouteListener) {
        if (offRouteListeners.contains(offRouteListener)) {
            Logger.w { "The specified OffRouteListener has already been added to the stack." }
            return
        }
        offRouteListeners.add(offRouteListener)
    }

    fun removeOffRouteListener(offRouteListener: OffRouteListener?) {
        if (offRouteListener == null) {
            offRouteListeners.clear()
        } else if (!offRouteListeners.contains(offRouteListener)) {
            Logger.w { "The specified OffRouteListener isn't found in stack, therefore, cannot be removed." }
        } else {
            offRouteListeners.remove(offRouteListener)
        }
    }

    fun addNavigationEventListener(navigationEventListener: NavigationEventListener) {
        if (navigationEventListeners.contains(navigationEventListener)) {
            Logger.w { "The specified NavigationEventListener has already been added to the stack." }
            return
        }
        navigationEventListeners.add(navigationEventListener)
    }

    fun removeNavigationEventListener(navigationEventListener: NavigationEventListener?) {
        if (navigationEventListener == null) {
            navigationEventListeners.clear()
        } else if (!navigationEventListeners.contains(navigationEventListener)) {
            Logger.w { "The specified NavigationEventListener isn't found in stack, therefore, cannot be removed." }
        } else {
            navigationEventListeners.remove(navigationEventListener)
        }
    }

    fun addFasterRouteListener(fasterRouteListener: FasterRouteListener) {
        if (fasterRouteListeners.contains(fasterRouteListener)) {
            Logger.w { "The specified FasterRouteListener has already been added to the stack." }
            return
        }
        fasterRouteListeners.add(fasterRouteListener)
    }

    fun removeFasterRouteListener(fasterRouteListener: FasterRouteListener?) {
        if (fasterRouteListener == null) {
            fasterRouteListeners.clear()
        } else if (!fasterRouteListeners.contains(fasterRouteListener)) {
            Logger.w { "The specified FasterRouteListener isn't found in stack, therefore, cannot be removed." }
        } else {
            fasterRouteListeners.remove(fasterRouteListener)
        }
    }

    fun onMilestoneEvent(
        routeProgress: RouteProgress,
        instruction: String?,
        milestone: Milestone
    ) {
        for (milestoneEventListener in milestoneEventListeners) {
            milestoneEventListener.onMilestoneEvent(routeProgress, instruction, milestone)
        }
    }

    fun onProgressChange(location: Location, routeProgress: RouteProgress) {
        for (progressChangeListener in progressChangeListeners) {
            progressChangeListener.onProgressChange(location, routeProgress)
        }
    }

    fun onUserOffRoute(location: Location) {
        for (offRouteListener in offRouteListeners) {
            offRouteListener.userOffRoute(location)
        }
    }

    fun onNavigationEvent(isRunning: Boolean) {
        for (navigationEventListener in navigationEventListeners) {
            navigationEventListener.onRunning(isRunning)
        }
    }

    fun onFasterRouteEvent(directionsRoute: DirectionsRoute?) {
        for (fasterRouteListener in fasterRouteListeners) {
            fasterRouteListener.fasterRouteFound(directionsRoute)
        }
    }
}
