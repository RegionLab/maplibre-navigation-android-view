package org.maplibre.navigation.android.navigation.ui.v5.camera;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import org.maplibre.geojson.Point;
import org.maplibre.navigation.core.location.Location;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.constants.MapLibreConstants;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.OnLocationCameraTransitionListener;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.navigation.core.navigation.MapLibreNavigation;
import org.maplibre.navigation.core.navigation.camera.Camera;
import org.maplibre.navigation.core.navigation.camera.RouteInformation;
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener;
import org.maplibre.navigation.core.routeprogress.RouteProgress;
import org.maplibre.navigation.core.utils.MathUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import timber.log.Timber;

import static org.maplibre.navigation.android.navigation.ui.v5.GeoJsonExtKt.toJvmPoints;
import static org.maplibre.navigation.core.navigation.NavigationConstants.NAVIGATION_MAX_CAMERA_ADJUSTMENT_ANIMATION_DURATION;
import static org.maplibre.navigation.core.navigation.NavigationConstants.NAVIGATION_MIN_CAMERA_TILT_ADJUSTMENT_ANIMATION_DURATION;
import static org.maplibre.navigation.core.navigation.NavigationConstants.NAVIGATION_MIN_CAMERA_ZOOM_ADJUSTMENT_ANIMATION_DURATION;

/**
 * Updates the map camera while navigating.
 * <p>
 * This class listens to the progress of {@link MapLibreNavigation} and moves
 * the {@link MapLibreMap} camera based on the location updates.
 *
 * @since 0.6.0
 */
public class NavigationCamera implements LifecycleObserver {

  /**
   * Camera tracks the user location, with bearing provided by the location update.
   * <p>
   * Equivalent of the {@link CameraMode#TRACKING_GPS}.
   */
  public static final int NAVIGATION_TRACKING_MODE_GPS = 0;
  /**
   * Camera tracks the user location, with bearing always set to north (0).
   * <p>
   * Equivalent of the {@link CameraMode#TRACKING_GPS_NORTH}.
   */
  public static final int NAVIGATION_TRACKING_MODE_NORTH = 1;
  /**
   * Camera does not tack the user location.
   * <p>
   * Equivalent of the {@link CameraMode#NONE}.
   */
  public static final int NAVIGATION_TRACKING_MODE_NONE = 2;
  private static final int ONE_POINT = 1;
  private final CopyOnWriteArrayList<OnTrackingModeTransitionListener> onTrackingModeTransitionListeners
    = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<OnTrackingModeChangedListener> onTrackingModeChangedListeners
    = new CopyOnWriteArrayList<>();
  private final OnLocationCameraTransitionListener cameraTransitionListener
    = new NavigationCameraTransitionListener(this);
  private final OnCameraTrackingChangedListener cameraTrackingChangedListener
    = new NavigationCameraTrackingChangedListener(this);
  private MapLibreMap mapLibreMap;
  private LocationComponent locationComponent;
  private MapLibreNavigation navigation;
  private RouteInformation currentRouteInformation;
  private RouteProgress currentRouteProgress;
  @TrackingMode
  private int trackingCameraMode = NAVIGATION_TRACKING_MODE_GPS;
  private boolean isCameraResetting;
  private CameraAnimationDelegate animationDelegate;
  private ProgressChangeListener progressChangeListener = new ProgressChangeListener() {
    @Override
    public void onProgressChange(Location location, RouteProgress routeProgress) {
      currentRouteProgress = routeProgress;
      if (isTrackingEnabled()) {
        currentRouteInformation = buildRouteInformationFromLocation(location, routeProgress);
        if (!isCameraResetting) {
          adjustCameraFromLocation(currentRouteInformation);
        }
      }
    }
  };

  /**
   * Creates an instance of {@link NavigationCamera}.
   *
   * @param mapLibreMap         for moving the camera
   * @param navigation        for listening to location updates
   * @param locationComponent for managing camera mode
   */
  public NavigationCamera(@NonNull MapLibreMap mapLibreMap, @NonNull MapLibreNavigation navigation,
                          @NonNull LocationComponent locationComponent) {
    this.mapLibreMap = mapLibreMap;
    this.navigation = navigation;
    this.locationComponent = locationComponent;
    this.animationDelegate = new CameraAnimationDelegate(mapLibreMap);
    this.locationComponent.addOnCameraTrackingChangedListener(cameraTrackingChangedListener);
    initializeWith(navigation);
  }

  /**
   * Creates an instance of {@link NavigationCamera}.
   * <p>
   * Camera will start tracking current user location by default.
   *
   * @param mapLibreMap         for moving the camera
   * @param locationComponent for managing camera mode
   */
  public NavigationCamera(@NonNull MapLibreMap mapLibreMap, LocationComponent locationComponent) {
    this.mapLibreMap = mapLibreMap;
    this.locationComponent = locationComponent;
    this.animationDelegate = new CameraAnimationDelegate(mapLibreMap);
    this.locationComponent.addOnCameraTrackingChangedListener(cameraTrackingChangedListener);
    updateCameraTrackingMode(trackingCameraMode);
  }

  /**
   * Used for testing only.
   */
  NavigationCamera(MapLibreMap mapLibreMap, MapLibreNavigation navigation, ProgressChangeListener progressChangeListener,
                   LocationComponent locationComponent, RouteInformation currentRouteInformation) {
    this.mapLibreMap = mapLibreMap;
    this.locationComponent = locationComponent;
    this.navigation = navigation;
    this.progressChangeListener = progressChangeListener;
    this.currentRouteInformation = currentRouteInformation;
  }

  /**
   * Called when beginning navigation with a route.
   *
   * @param route used to update route information
   */
  public void start(DirectionsRoute route) {
    if (route != null) {
      currentRouteInformation = buildRouteInformationFromRoute(route);
    }
    navigation.addProgressChangeListener(progressChangeListener);
  }

  /**
   * Called during rotation to update route information.
   *
   * @param location used to update route information
   */
  public void resume(Location location) {
    if (location != null) {
      currentRouteInformation = buildRouteInformationFromLocation(location, null);
    }
    navigation.addProgressChangeListener(progressChangeListener);
  }

  /**
   * Updates the {@link TrackingMode} that's going to be used when camera tracking is enabled.
   *
   * @param trackingMode the tracking mode
   */
  public void updateCameraTrackingMode(@TrackingMode int trackingMode) {
    setCameraMode(trackingMode);
  }

  /**
   * Getter for current state of tracking.
   *
   * @return true if tracking, false if not
   */
  public boolean isTrackingEnabled() {
    return trackingCameraMode != NAVIGATION_TRACKING_MODE_NONE;
  }

  /**
   * Getter for {@link TrackingMode} that's being used when tracking is enabled.
   *
   * @return tracking mode
   */
  @TrackingMode
  public int getCameraTrackingMode() {
    return trackingCameraMode;
  }

  /**
   * Resets the map camera / padding to the last known camera position.
   * <p>
   * You can also specify a tracking mode to reset with.  For example if you would like
   * to reset the camera and continue tracking, you would use {@link NavigationCamera#NAVIGATION_TRACKING_MODE_GPS}.
   *
   * @param trackingMode the tracking mode
   */
  public void resetCameraPositionWith(@TrackingMode int trackingMode) {
    resetWith(trackingMode);
  }

  /**
   * This method stops the map camera from tracking the current location, and then zooms
   * out to an overview of the current route being traveled.
   *
   * @param padding in pixels around the bounding box of the overview (left, top, right, bottom)
   */
  public void showRouteOverview(int[] padding) {
    updateCameraTrackingMode(NAVIGATION_TRACKING_MODE_NONE);
    RouteInformation routeInformation = buildRouteInformationFromProgress(currentRouteProgress);
    animateCameraForRouteOverview(routeInformation, padding);
  }

  /**
   * Animate the camera to a new location defined within {@link CameraUpdate} passed to the
   * {@link NavigationCameraUpdate} using a transition animation that evokes powered flight.
   * If the camera is in a tracking mode, this animation is going to be ignored, or break the tracking,
   * based on the {@link CameraUpdateMode}.
   *
   * @param update the change that should be applied to the camera.
   * @see CameraUpdateMode for how this update interacts with the current tracking
   */
  public void update(NavigationCameraUpdate update) {
    animationDelegate.render(update, MapLibreConstants.ANIMATION_DURATION, null);
  }

  /**
   * Animate the camera to a new location defined within {@link CameraUpdate} passed to the
   * {@link NavigationCameraUpdate} using a transition animation that evokes powered flight.
   * The animation will last a specified amount of time given in milliseconds. If the camera is in a tracking mode,
   * this animation is going to be ignored, or break the tracking, based on the {@link CameraUpdateMode}.
   *
   * @param update     the change that should be applied to the camera.
   * @param durationMs the duration of the animation in milliseconds. This must be strictly
   *                   positive, otherwise an IllegalArgumentException will be thrown.
   * @see CameraUpdateMode for how this update interacts with the current tracking
   */
  public void update(NavigationCameraUpdate update, int durationMs) {
    animationDelegate.render(update, durationMs, null);
  }

  /**
   * Animate the camera to a new location defined within {@link CameraUpdate} passed to the
   * {@link NavigationCameraUpdate} using a transition animation that evokes powered flight. The animation will
   * last a specified amount of time given in milliseconds. A callback can be used to be notified when animating
   * the camera stops. During the animation, a call to {@link MapLibreMap#getCameraPosition()} returns an intermediate
   * location of the camera in flight. If the camera is in a tracking mode,
   * this animation is going to be ignored, or break the tracking, based on the {@link CameraUpdateMode}.
   *
   * @param update     the change that should be applied to the camera.
   * @param durationMs the duration of the animation in milliseconds. This must be strictly
   *                   positive, otherwise an IllegalArgumentException will be thrown.
   * @param callback   an optional callback to be notified from the main thread when the animation
   *                   stops. If the animation stops due to its natural completion, the callback
   *                   will be notified with onFinish(). If the animation stops due to interruption
   *                   by a later camera movement or a user gesture, onCancel() will be called.
   *                   Do not update or animate the camera from within onCancel(). If a callback
   *                   isn't required, leave it as null.
   * @see CameraUpdateMode for how this update interacts with the current tracking
   */
  public void update(NavigationCameraUpdate update, int durationMs, @Nullable MapLibreMap.CancelableCallback callback) {
    animationDelegate.render(update, durationMs, callback);
  }

  /**
   * Call in {@link FragmentActivity#onStart()} to properly add the {@link ProgressChangeListener}
   * for the camera and prevent any leaks or further updates.
   */
  @OnLifecycleEvent(Lifecycle.Event.ON_START)
  public void onStart() {
    if (navigation != null) {
      navigation.addProgressChangeListener(progressChangeListener);
    }
  }

  /**
   * Call in {@link FragmentActivity#onStop()} to properly remove the {@link ProgressChangeListener}
   * for the camera and prevent any leaks or further updates.
   */
  @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
  public void onStop() {
    if (navigation != null) {
      navigation.removeProgressChangeListener(progressChangeListener);
    }
  }

  /**
   * This method can be called if you did not pass an instance of {@link MapLibreNavigation}
   * to the constructor.
   * <p>
   * The camera will begin listening to progress updates and update the route accordingly.
   *
   * @param navigation to add the camera progress change listener
   */
  public void addProgressChangeListener(MapLibreNavigation navigation) {
    this.navigation = navigation;
    navigation.setCameraEngine(new DynamicCamera(mapLibreMap));
    navigation.addProgressChangeListener(progressChangeListener);
  }

  /**
   * Adds given tracking mode transition listener for receiving notification of camera
   * transition updates.
   *
   * @param listener to be added
   */
  public void addOnTrackingModeTransitionListener(@NonNull OnTrackingModeTransitionListener listener) {
    onTrackingModeTransitionListeners.add(listener);
  }

  /**
   * Removes given tracking mode transition listener for receiving notification of camera
   * transition updates.
   *
   * @param listener to be removed
   */
  public void removeOnTrackingModeTransitionListener(@NonNull OnTrackingModeTransitionListener listener) {
    onTrackingModeTransitionListeners.remove(listener);
  }

  /**
   * Adds given tracking mode changed listener for receiving notification of camera
   * mode changes.
   *
   * @param listener to be added
   */
  public void addOnTrackingModeChangedListener(@NonNull OnTrackingModeChangedListener listener) {
    onTrackingModeChangedListeners.add(listener);
  }

  /**
   * Removes given tracking mode transition listener for receiving notification of camera
   * mode changes.
   *
   * @param listener to be removed
   */
  public void removeOnTrackingModeChangedListener(@NonNull OnTrackingModeChangedListener listener) {
    onTrackingModeChangedListeners.remove(listener);
  }

  void updateTransitionListenersFinished(@CameraMode.Mode int cameraMode) {
    onCameraTransitionFinished();
    Integer trackingCameraMode = findTrackingModeFor(cameraMode);
    if (trackingCameraMode == null) {
      return;
    }
    for (OnTrackingModeTransitionListener listener : onTrackingModeTransitionListeners) {
      listener.onTransitionFinished(trackingCameraMode);
    }
  }

  void updateTransitionListenersCancelled(@CameraMode.Mode int cameraMode) {
    Integer trackingCameraMode = findTrackingModeFor(cameraMode);
    if (trackingCameraMode == null) {
      return;
    }
    for (OnTrackingModeTransitionListener listener : onTrackingModeTransitionListeners) {
      listener.onTransitionCancelled(trackingCameraMode);
    }
  }

  @Nullable
  Integer findTrackingModeFor(@CameraMode.Mode int cameraMode) {
    if (cameraMode == CameraMode.TRACKING_GPS) {
      return NAVIGATION_TRACKING_MODE_GPS;
    } else if (cameraMode == CameraMode.TRACKING_GPS_NORTH) {
      return NAVIGATION_TRACKING_MODE_NORTH;
    } else if (cameraMode == CameraMode.NONE) {
      return NAVIGATION_TRACKING_MODE_NONE;
    } else {
      return null;
    }
  }

  void updateIsResetting(boolean isResetting) {
    this.isCameraResetting = isResetting;
  }

  private void initializeWith(MapLibreNavigation navigation) {
    navigation.setCameraEngine(new DynamicCamera(mapLibreMap));
    updateCameraTrackingMode(trackingCameraMode);
  }

  /**
   * Creates a camera position based on the given route.
   * <p>
   * From the {@link DirectionsRoute}, an initial bearing and target position are created.
   * Then using a preset tilt and zoom (based on screen orientation), a {@link CameraPosition} is built.
   *
   * @param route used to build the camera position
   * @return camera position to be animated to
   */
  @NonNull
  private RouteInformation buildRouteInformationFromRoute(DirectionsRoute route) {
    return new RouteInformation(route, null, null);
  }

  /**
   * Creates a camera position based on the given location.
   * <p>
   * From the {@link Location}, a target position is created.
   * Then using a preset tilt and zoom (based on screen orientation), a {@link CameraPosition} is built.
   *
   * @param location used to build the camera position
   * @return camera position to be animated to
   */
  @NonNull
  private RouteInformation buildRouteInformationFromLocation(Location location, RouteProgress routeProgress) {
    return new RouteInformation(null, location, routeProgress);
  }

  @NonNull
  private RouteInformation buildRouteInformationFromProgress(RouteProgress routeProgress) {
    if (routeProgress == null) {
      return new RouteInformation(null, null, null);
    }
    return new RouteInformation(routeProgress.getDirectionsRoute(), null, null);
  }

  private void onCameraTransitionFinished() {
    if (isCameraResetting && currentRouteInformation != null) {
      adjustCameraForReset(currentRouteInformation);
    }
  }

  private void animateCameraForRouteOverview(RouteInformation routeInformation, int[] padding) {
    Camera cameraEngine = navigation.getCameraEngine();
    List<Point> routePoints = toJvmPoints(cameraEngine.overview(routeInformation));
    if (!routePoints.isEmpty()) {
      animateMapLibreMapForRouteOverview(padding, routePoints);
    }
  }

  private void animateMapLibreMapForRouteOverview(int[] padding, List<Point> routePoints) {
    if (routePoints.size() <= ONE_POINT) {
      return;
    }
    CameraUpdate resetUpdate = buildResetCameraUpdate();
    final CameraUpdate overviewUpdate = buildOverviewCameraUpdate(padding, routePoints);
    mapLibreMap.animateCamera(resetUpdate, 150,
      new CameraOverviewCancelableCallback(overviewUpdate, mapLibreMap)
    );
  }

  @NonNull
  private CameraUpdate buildResetCameraUpdate() {
    CameraPosition resetPosition = new CameraPosition.Builder().tilt(0).bearing(0).build();
    return CameraUpdateFactory.newCameraPosition(resetPosition);
  }

  @NonNull
  private CameraUpdate buildOverviewCameraUpdate(int[] padding, List<Point> routePoints) {
    final LatLngBounds routeBounds = convertRoutePointsToLatLngBounds(routePoints);
    return CameraUpdateFactory.newLatLngBounds(
      routeBounds, padding[0], padding[1], padding[2], padding[3]
    );
  }

  private LatLngBounds convertRoutePointsToLatLngBounds(List<Point> routePoints) {
    List<LatLng> latLngs = new ArrayList<>();
    for (Point routePoint : routePoints) {
      latLngs.add(new LatLng(routePoint.latitude(), routePoint.longitude()));
    }
    return new LatLngBounds.Builder()
      .includes(latLngs)
      .build();
  }

  private void setCameraMode(@TrackingMode int trackingCameraMode) {
    @CameraMode.Mode Integer cameraMode = findCameraModeFor(trackingCameraMode);
    if (cameraMode != null) {
      this.trackingCameraMode = trackingCameraMode;
      updateTrackingModeListenersWith(this.trackingCameraMode);
      if (cameraMode != locationComponent.getCameraMode()) {
        locationComponent.setCameraMode(cameraMode, cameraTransitionListener);
      }
    } else {
      Timber.e("Using unsupported camera tracking mode - %d.", trackingCameraMode);
    }
  }

  @Nullable
  private Integer findCameraModeFor(@TrackingMode int trackingCameraMode) {
    if (trackingCameraMode == NAVIGATION_TRACKING_MODE_GPS) {
      return CameraMode.TRACKING_GPS;
    } else if (trackingCameraMode == NAVIGATION_TRACKING_MODE_NORTH) {
      return CameraMode.TRACKING_GPS_NORTH;
    } else if (trackingCameraMode == NAVIGATION_TRACKING_MODE_NONE) {
      return CameraMode.NONE;
    } else {
      return null;
    }
  }

  private void updateTrackingModeListenersWith(@TrackingMode int trackingMode) {
    for (OnTrackingModeChangedListener listener : onTrackingModeChangedListeners) {
      listener.onTrackingModeChanged(trackingMode);
    }
  }

  private void resetWith(@TrackingMode int trackingMode) {
    updateIsResetting(true);
    resetDynamicCamera(navigation.getCameraEngine());
    updateCameraTrackingMode(trackingMode);
  }

  private void resetDynamicCamera(Camera camera) {
    if (camera instanceof DynamicCamera) {
      ((DynamicCamera) camera).forceResetZoomLevel();
    }
  }

  private void adjustCameraForReset(RouteInformation routeInformation) {
    Camera camera = navigation.getCameraEngine();
    float tilt = (float) camera.tilt(routeInformation);
    double zoom = camera.zoom(routeInformation);
    locationComponent.zoomWhileTracking(zoom, getZoomAnimationDuration(zoom), new ResetCancelableCallback(this));
    locationComponent.tiltWhileTracking(tilt, getTiltAnimationDuration(tilt));
  }

  private void adjustCameraFromLocation(RouteInformation routeInformation) {
    Camera camera = navigation.getCameraEngine();
    float tilt = (float) camera.tilt(routeInformation);
    double zoom = camera.zoom(routeInformation);
    locationComponent.zoomWhileTracking(zoom, getZoomAnimationDuration(zoom));
    locationComponent.tiltWhileTracking(tilt, getTiltAnimationDuration(tilt));
  }

  private long getZoomAnimationDuration(double zoom) {
    double zoomDiff = Math.abs(mapLibreMap.getCameraPosition().zoom - zoom);
    return (long) MathUtils.clamp(
      500 * zoomDiff,
      NAVIGATION_MIN_CAMERA_ZOOM_ADJUSTMENT_ANIMATION_DURATION,
      NAVIGATION_MAX_CAMERA_ADJUSTMENT_ANIMATION_DURATION);
  }

  private long getTiltAnimationDuration(double tilt) {
    double tiltDiff = Math.abs(mapLibreMap.getCameraPosition().tilt - tilt);
    return (long) MathUtils.clamp(
      500 * tiltDiff,
      NAVIGATION_MIN_CAMERA_TILT_ADJUSTMENT_ANIMATION_DURATION,
      NAVIGATION_MAX_CAMERA_ADJUSTMENT_ANIMATION_DURATION);
  }

  @Retention(RetentionPolicy.SOURCE)
  @IntDef( {NAVIGATION_TRACKING_MODE_GPS,
    NAVIGATION_TRACKING_MODE_NORTH,
    NAVIGATION_TRACKING_MODE_NONE
  })
  public @interface TrackingMode {
  }
}
