package org.maplibre.navigation.android.navigation.ui.v5.route;

import android.os.Handler;

import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.navigation.core.utils.Constants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY;

class FeatureProcessingTask extends Thread {

  private final List<DirectionsRoute> routes;
  private final List<FeatureCollection> routeFeatureCollections = new ArrayList<>();
  private final WeakReference<OnRouteFeaturesProcessedCallback> callbackWeakReference;
  private final HashMap<LineString, DirectionsRoute> routeLineStrings = new HashMap<>();
  private AtomicBoolean cancelThread = new AtomicBoolean(false);
  private Handler postHandler;

  FeatureProcessingTask(List<DirectionsRoute> routes, OnRouteFeaturesProcessedCallback callback, Handler handler) {
    this.routes = routes;
    this.callbackWeakReference = new WeakReference<>(callback);
    this.postHandler = handler;
  }

  @Override
  public void run() {
    for (int i = 0; i < routes.size(); i++) {
      if (cancelThread.get()) {
        return;
      }
      DirectionsRoute route = routes.get(i);
      boolean isPrimary = i == 0;
      FeatureCollection routeFeatureCollection = createRouteFeatureCollection(route, isPrimary);
      routeFeatureCollections.add(routeFeatureCollection);
    }
    if (!cancelThread.get()) {
      completion();
    }
  }

  void cancel() {
    cancelThread.set(true);
  }

  private void completion() {
    final OnRouteFeaturesProcessedCallback callback = callbackWeakReference.get();
    if (callback != null) {
      postHandler.post(new Runnable() {
        @Override
        public void run() {
          if (cancelThread.get()) {
            return;
          }
          callback.onRouteFeaturesProcessed(routeFeatureCollections, routeLineStrings);
        }
      });
    }
  }

  private FeatureCollection createRouteFeatureCollection(DirectionsRoute route, boolean isPrimary) {
    final List<Feature> features = new ArrayList<>();

    LineString routeGeometry = LineString.fromPolyline(route.getGeometry(), Constants.PRECISION_6);
    Feature routeFeature = Feature.fromGeometry(routeGeometry);
    routeFeature.addBooleanProperty(PRIMARY_ROUTE_PROPERTY_KEY, isPrimary);
    features.add(routeFeature);
    routeLineStrings.put(routeGeometry, route);

    features.addAll(CongestionSegments.build(route, routeGeometry.coordinates(), isPrimary));
    return FeatureCollection.fromFeatures(features);
  }
}
