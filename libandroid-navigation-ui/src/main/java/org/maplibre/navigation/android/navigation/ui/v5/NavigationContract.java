package org.maplibre.navigation.android.navigation.ui.v5;

import androidx.annotation.NonNull;

import org.maplibre.navigation.core.location.Location;
import org.maplibre.navigation.core.models.DirectionsRoute;

public interface NavigationContract {

  interface View {

    void resetCameraPosition();

    void drawRoute(DirectionsRoute directionsRoute);

    void startCamera(DirectionsRoute directionsRoute);

    void resumeCamera(Location location);

    void updateNavigationMap(Location location);

    void updateSpeed(double speed);

    void updateSpeedLimit(org.maplibre.navigation.core.models.MaxSpeed maxSpeed);

    void updateCameraRouteOverview();
  }
}
