package org.maplibre.navigation.android.navigation.ui.v5;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.maplibre.geojson.Point;
import org.maplibre.navigation.core.location.Location;
import org.maplibre.navigation.core.models.DirectionsRoute;

class NavigationPresenter {

    private NavigationContract.View view;
    private boolean resumeState;
    private boolean isTrackingCamera = false;

    NavigationPresenter(NavigationContract.View view) {
        this.view = view;
    }

    void updateResumeState(boolean resumeState) {
        this.resumeState = resumeState;
    }

    void onRecenterClick() {
        view.resetCameraPosition();
        isTrackingCamera = true;
    }

    void onCameraTrackingDismissed() {
        isTrackingCamera = false;
    }


    void onRouteUpdate(DirectionsRoute directionsRoute) {
        view.drawRoute(directionsRoute);
        if (resumeState && isTrackingCamera) {
            view.updateCameraRouteOverview();
        } else {
            view.startCamera(directionsRoute);
        }
    }

    void onDestinationUpdate(Point point) {
        // Disabled adding destination marker
//        view.addMarker(point);
    }

    void onNavigationLocationUpdate(Location location) {
        if (resumeState && !isTrackingCamera) {
            view.resumeCamera(location);
            resumeState = false;
        }
        view.updateNavigationMap(location);
        Object speedObj = location.getSpeedMetersPerSeconds();
        if (speedObj instanceof Float) {
            view.updateSpeed(((Float) speedObj).doubleValue());
        } else {
            view.updateSpeed(0.0);
        }
    }

    void onWayNameChanged(@NonNull String wayName) {
    }

    void onNavigationStopped() {
    }

    void onRouteOverviewClick() {
        view.updateCameraRouteOverview();
    }
}