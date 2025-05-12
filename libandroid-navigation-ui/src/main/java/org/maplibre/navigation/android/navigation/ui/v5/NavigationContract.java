package org.maplibre.navigation.android.navigation.ui.v5;

import androidx.annotation.NonNull;

import org.maplibre.navigation.core.location.Location;
import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.geojson.Point;

public interface NavigationContract {

  interface View {

    void updateWayNameVisibility(boolean isVisible);

    void updateWayNameView(@NonNull String wayName);

    void resetCameraPosition();

    void showRecenterBtn();

    void hideRecenterBtn();

    void drawRoute(DirectionsRoute directionsRoute);

    void addMarker(Point point);

    void startCamera(DirectionsRoute directionsRoute);

    void resumeCamera(Location location);

    void updateNavigationMap(Location location);

    boolean isRecenterButtonVisible();

    void updateCameraRouteOverview();
  }
}
