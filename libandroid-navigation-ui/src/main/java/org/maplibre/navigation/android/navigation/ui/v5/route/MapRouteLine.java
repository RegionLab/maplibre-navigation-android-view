package org.maplibre.navigation.android.navigation.ui.v5.route;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import org.maplibre.navigation.android.navigation.ui.v5.R;
import org.maplibre.navigation.android.navigation.ui.v5.utils.MapUtils;
import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.navigation.core.models.RouteLeg;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.PRIMARY_ROUTE_PROPERTY_KEY;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_LAYER_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_SHIELD_LAYER_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_BLEND_LAYER_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_FADE_SOURCE_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_NEAR_LAYER_SUFFIX;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_NEAR_SOURCE_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.ROUTE_SOURCE_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.WAYPOINT_SOURCE_ID;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.WAYPOINT_DESTINATION_VALUE;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.WAYPOINT_ORIGIN_VALUE;
import static org.maplibre.navigation.android.navigation.ui.v5.route.RouteConstants.WAYPOINT_PROPERTY_KEY;

class MapRouteLine {

  @ColorInt
  private int routeDefaultColor;
  @ColorInt
  private int routeModerateColor;
  @ColorInt
  private int routeSevereColor;
  @ColorInt
  private int alternativeRouteDefaultColor;
  @ColorInt
  private int alternativeRouteModerateColor;
  @ColorInt
  private int alternativeRouteSevereColor;
  @ColorInt
  private int alternativeRouteShieldColor;
  @ColorInt
  private int routeShieldColor;
  private float routeScale;
  private float alternativeRouteScale;
  private boolean roundedLineCap;

  private Style style;

  private final HashMap<LineString, DirectionsRoute> routeLineStrings = new HashMap<>();
  private final List<FeatureCollection> routeFeatureCollections = new ArrayList<>();
  private final List<DirectionsRoute> directionsRoutes = new ArrayList<>();
  private final List<String> routeLayerIds;

  private final GeoJsonSource wayPointSource;
  private final GeoJsonSource routeLineSource;
  // Fading start of the vanishing primary route, line gradients need line metrics
  private GeoJsonSource routeFadeSource;
  // Part of the vanishing primary route near the user location, updated on every frame. The rest
  // of the route is updated only every NEAR_ROUTE_STEP_METERS, on large routes re-tiling the whole
  // route on every frame costs a lot of CPU.
  private GeoJsonSource routeNearSource;
  private double farRouteStart = Double.NaN;
  private static final double NEAR_ROUTE_STEP_METERS = 200;
  private static final double NEAR_ROUTE_MIN_METERS = 200;
  private int primaryRouteIndex;
  private boolean isVisible = true;
  private boolean alternativesVisible = true;
  private FeatureCollection drawnRouteFeatureCollection;
  private FeatureCollection drawnWaypointsFeatureCollection;
  private AtomicReference<FeatureProcessingTask> featureProcessingTaskRef = new AtomicReference<>(null);
  private FeatureProcessingTask featureProcessingTask;
  private boolean isFeatureProcessingTaskInjected = false;
  private AtomicReference<PrimaryRouteUpdateTask> primaryRouteUpdateTaskRef = new AtomicReference<>(null);
  private PrimaryRouteUpdateTask primaryRouteUpdateTask;
  private boolean isPrimaryRouteUpdateTaskInjected = false;
  private Handler mainHandler;
  // Route collections last drawn with the current primary route flags, the base for trimming
  private List<FeatureCollection> drawnRouteCollections = new ArrayList<>();
  private RouteLineTrimmer routeLineTrimmer;
  // Primary route by distance, for the vanishing route line
  private RouteLinePainter primaryRoutePainter;
  private final RouteVanishAnimator vanishAnimator = new RouteVanishAnimator(traveled -> {
    drawVanishedRoute(traveled);
    return kotlin.Unit.INSTANCE;
  });

  MapRouteLine(Context context,
               Style style,
               int styleRes,
               String belowLayer,
               MapRouteDrawableProvider drawableProvider,
               MapRouteSourceProvider sourceProvider,
               MapRouteLayerProvider layerProvider,
               Handler handler) {
    this(context, style, styleRes, belowLayer, drawableProvider, sourceProvider, layerProvider,
      FeatureCollection.fromFeatures(new Feature[]{}),
      FeatureCollection.fromFeatures(new Feature[]{}),
      new ArrayList<DirectionsRoute>(),
      new ArrayList<FeatureCollection>(),
      new HashMap<LineString, DirectionsRoute>(),
      0,
      true,
      true,
      handler);
  }

  MapRouteLine(Context context,
               Style style,
               int styleRes,
               String belowLayer,
               MapRouteDrawableProvider drawableProvider,
               MapRouteSourceProvider sourceProvider,
               MapRouteLayerProvider layerProvider,
               FeatureCollection routesFeatureCollection,
               FeatureCollection waypointsFeatureCollection,
               List<DirectionsRoute> directionsRoutes,
               List<FeatureCollection> routeFeatureCollections,
               HashMap<LineString, DirectionsRoute> routeLineStrings,
               int primaryRouteIndex,
               boolean isVisible,
               boolean alternativesVisible,
               Handler handler
  ) {
    this.routeLayerIds = new ArrayList<>();
    this.mainHandler = handler;
    this.style = style;

    TypedArray typedArray = context.obtainStyledAttributes(styleRes, R.styleable.NavigationMapRoute);
    // Primary Route attributes
    routeDefaultColor = typedArray.getColor(R.styleable.NavigationMapRoute_routeColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_layer_blue));
    routeModerateColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_routeModerateCongestionColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_layer_congestion_yellow));
    routeSevereColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_routeSevereCongestionColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_layer_congestion_red));
    routeShieldColor = typedArray.getColor(R.styleable.NavigationMapRoute_routeShieldColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_shield_layer_color));
    routeScale = typedArray.getFloat(R.styleable.NavigationMapRoute_routeScale, 1.0f);
    roundedLineCap = typedArray.getBoolean(R.styleable.NavigationMapRoute_roundedLineCap, true);

    // Secondary Routes attributes
    alternativeRouteDefaultColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_alternative_color));
    alternativeRouteModerateColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteModerateCongestionColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_alternative_congestion_yellow));
    alternativeRouteSevereColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteSevereCongestionColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_alternative_congestion_red));
    alternativeRouteShieldColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteShieldColor,
      ContextCompat.getColor(context, R.color.maplibre_navigation_route_alternative_shield_color));
    alternativeRouteScale = typedArray.getFloat(
      R.styleable.NavigationMapRoute_alternativeRouteScale, 1.0f);

    // Waypoint source is intentionally not added to the style: origin/destination icons are hidden,
    // markers are managed by the app via NavigationView.addSymbol
    GeoJsonOptions wayPointGeoJsonOptions = new GeoJsonOptions().withMaxZoom(16);
    drawnWaypointsFeatureCollection = waypointsFeatureCollection;
    wayPointSource = sourceProvider.build(WAYPOINT_SOURCE_ID, drawnWaypointsFeatureCollection, wayPointGeoJsonOptions);

    GeoJsonOptions routeLineGeoJsonOptions = new GeoJsonOptions().withMaxZoom(16);
    drawnRouteFeatureCollection = routesFeatureCollection;
    routeLineSource = addOrReuseSource(style, ROUTE_SOURCE_ID,
      sourceProvider.build(ROUTE_SOURCE_ID, drawnRouteFeatureCollection, routeLineGeoJsonOptions),
      drawnRouteFeatureCollection);

    FeatureCollection noNearRoute = FeatureCollection.fromFeatures(new Feature[]{});
    routeNearSource = addOrReuseSource(style, ROUTE_NEAR_SOURCE_ID,
      sourceProvider.build(ROUTE_NEAR_SOURCE_ID, noNearRoute, new GeoJsonOptions().withMaxZoom(16)),
      noNearRoute);

    FeatureCollection noFade = FeatureCollection.fromFeatures(new Feature[]{});
    routeFadeSource = addOrReuseSource(style, ROUTE_FADE_SOURCE_ID,
      sourceProvider.build(ROUTE_FADE_SOURCE_ID, noFade, new GeoJsonOptions().withMaxZoom(16).withLineMetrics(true)),
      noFade);

    typedArray.recycle();
    belowLayer = findRouteBelowLayerId(belowLayer, style);

    initializeLayers(style, layerProvider, belowLayer);

    this.directionsRoutes.addAll(directionsRoutes);
    this.routeFeatureCollections.addAll(routeFeatureCollections);
    this.routeLineStrings.putAll(routeLineStrings);

    updateAlternativeVisibilityTo(alternativesVisible);
    updateRoutesFor(primaryRouteIndex);
    updateVisibilityTo(isVisible);
  }

  // For testing only
  MapRouteLine(GeoJsonSource routeLineSource, GeoJsonSource wayPointSource, List<String> routeLayerIds) {
    this.routeLineSource = routeLineSource;
    this.wayPointSource = wayPointSource;
    this.routeLayerIds = routeLayerIds;
  }

  void draw(DirectionsRoute directionsRoute) {
    List<DirectionsRoute> route = new ArrayList<>();
    route.add(directionsRoute);
    draw(route);
  }

  void draw(List<DirectionsRoute> directionsRoutes) {
    if (directionsRoutes.isEmpty()) {
      return;
    }
    clearRouteData();
    this.directionsRoutes.addAll(directionsRoutes);
    primaryRouteIndex = 0;
    alternativesVisible = directionsRoutes.size() > 1;
    isVisible = true;
    generateRouteFeatureCollectionsFrom(directionsRoutes);
  }

  void toggleAlternativeVisibilityWith(boolean alternativesVisible) {
    this.alternativesVisible = alternativesVisible;
    updateAlternativeVisibilityTo(alternativesVisible);
  }

  boolean retrieveAlternativesVisible() {
    return alternativesVisible;
  }

  void updateVisibilityTo(boolean isVisible) {
    updateAllLayersVisibilityTo(isVisible);
  }

  boolean retrieveVisibility() {
    return isVisible;
  }

  HashMap<LineString, DirectionsRoute> retrieveRouteLineStrings() {
    return routeLineStrings;
  }

  List<FeatureCollection> retrieveRouteFeatureCollections() {
    return routeFeatureCollections;
  }

  FeatureCollection retrieveDrawnRouteFeatureCollections() {
    return drawnRouteFeatureCollection;
  }

  FeatureCollection retrieveDrawnWaypointsFeatureCollections() {
    return drawnWaypointsFeatureCollection;
  }

  List<DirectionsRoute> retrieveDirectionsRoutes() {
    return directionsRoutes;
  }

  boolean updatePrimaryRouteIndex(int primaryRouteIndex) {
    boolean isNewIndex = this.primaryRouteIndex != primaryRouteIndex
      && primaryRouteIndex < directionsRoutes.size() && primaryRouteIndex >= 0;
    if (isNewIndex) {
      this.primaryRouteIndex = primaryRouteIndex;
      updateRoutesFor(primaryRouteIndex);
    }
    return isNewIndex;
  }

  int retrievePrimaryRouteIndex() {
    return primaryRouteIndex;
  }

  /**
   * Removes the traveled part of the primary route, the line fades in after the given location
   * projected onto the route. The vanishing point moves smoothly to the location. Does nothing
   * while the location is far from the route.
   */
  void vanishTraveledRoute(Point location) {
    if (drawnRouteCollections.isEmpty() || primaryRouteIndex >= drawnRouteCollections.size()
      || primaryRouteIndex >= directionsRoutes.size()) {
      return;
    }
    if (routeLineTrimmer == null || primaryRoutePainter == null) {
      primaryRoutePainter = RouteLinePainter.create(directionsRoutes.get(primaryRouteIndex));
      routeLineTrimmer = new RouteLineTrimmer(primaryRoutePainter.getGeometry().getCoordinates());
      vanishAnimator.reset();
    }
    RouteLineTrimmer.Located located = routeLineTrimmer.locate(location);
    if (located == null) {
      return;
    }
    double traveled = primaryRoutePainter.getGeometry().distanceAt(located.getSegmentIndex(), located.getPoint());
    vanishAnimator.animateTo(traveled);
  }

  private void drawVanishedRoute(double traveled) {
    if (primaryRoutePainter == null || primaryRouteIndex >= drawnRouteCollections.size()) {
      return;
    }
    // The near part ends 200-400 m ahead, the far part changes only when that end moves
    double nearRouteEnd = Math.floor(traveled / NEAR_ROUTE_STEP_METERS) * NEAR_ROUTE_STEP_METERS
      + NEAR_ROUTE_STEP_METERS + NEAR_ROUTE_MIN_METERS;
    if (nearRouteEnd != farRouteStart) {
      farRouteStart = nearRouteEnd;
      List<Feature> routeFeatures = new ArrayList<>();
      for (int i = drawnRouteCollections.size() - 1; i >= 0; i--) {
        if (i == primaryRouteIndex) {
          routeFeatures.addAll(primaryRoutePainter.features(nearRouteEnd, true, false));
          continue;
        }
        List<Feature> features = drawnRouteCollections.get(i).features();
        if (features != null) {
          routeFeatures.addAll(features);
        }
      }
      routeLineSource.setGeoJson(FeatureCollection.fromFeatures(routeFeatures));
    }
    setNearRouteSource(primaryRoutePainter.features(traveled, true, true, nearRouteEnd));
    Feature fade = primaryRoutePainter.fadeFeature(traveled);
    setFadeSource(fade == null ? new Feature[]{} : new Feature[]{fade});
  }

  private void setNearRouteSource(List<Feature> features) {
    if (routeNearSource != null) {
      routeNearSource.setGeoJson(FeatureCollection.fromFeatures(features));
    }
  }

  private void setFadeSource(Feature[] features) {
    if (routeFadeSource != null) {
      routeFadeSource.setGeoJson(FeatureCollection.fromFeatures(features));
    }
  }

  /**
   * Shows the complete route again after {@link #vanishTraveledRoute(Point)}.
   */
  void restoreTraveledRoute() {
    resetVanishing();
    if (!drawnRouteCollections.isEmpty()) {
      drawRoutes(drawnRouteCollections);
    }
  }

  private void resetVanishing() {
    vanishAnimator.reset();
    setFadeSource(new Feature[]{});
    setNearRouteSource(new ArrayList<Feature>());
    farRouteStart = Double.NaN;
    routeLineTrimmer = null;
    primaryRoutePainter = null;
  }

  private void drawRoutes(List<FeatureCollection> routeFeatureCollections) {
    drawnRouteCollections = new ArrayList<>(routeFeatureCollections);
    List<Feature> routeFeatures = new ArrayList<>();
    for (int i = routeFeatureCollections.size() - 1; i >= 0; i--) {
      routeFeatures.addAll(routeFeatureCollections.get(i).features());
    }
    setRoutesSource(FeatureCollection.fromFeatures(routeFeatures));
  }

  private void clearRouteData() {
    clearRouteListData();
    setRoutesSource(FeatureCollection.fromFeatures(new Feature[]{}));
    setWaypointsSource(FeatureCollection.fromFeatures(new Feature[]{}));
  }

  private void clearRouteListData() {
    drawnRouteCollections = new ArrayList<>();
    resetVanishing();
    if (!directionsRoutes.isEmpty()) {
      directionsRoutes.clear();
    }
    if (!routeLineStrings.isEmpty()) {
      routeLineStrings.clear();
    }
    if (!routeFeatureCollections.isEmpty()) {
      routeFeatureCollections.clear();
    }
  }

  private void generateRouteFeatureCollectionsFrom(List<DirectionsRoute> routes) {
    // Retrieve a possibly null task. The retrieve is atomic.
    FeatureProcessingTask task = featureProcessingTaskRef.getAndSet(retrieveFeatureProcessingTask(routes));
    // If the previous task is valid, cancel it.
    if (task != null) {
      task.cancel();
    }
    // Retrieve the newly created task again. Maybe null
    task = featureProcessingTaskRef.get();

    // If the new task is not null, start it
    if (task != null) {
      task.start();
    }
  }

  // Testing only
  void injectFeatureProcessingTask(FeatureProcessingTask featureProcessingTask) {
    this.isFeatureProcessingTaskInjected = true;
    this.featureProcessingTask = featureProcessingTask;
  }

  private FeatureProcessingTask retrieveFeatureProcessingTask(List<DirectionsRoute> routes) {
    if (isFeatureProcessingTaskInjected) {
      return featureProcessingTask;
    }
    return new FeatureProcessingTask(routes, routeFeaturesProcessedCallback, mainHandler);
  }

  private OnRouteFeaturesProcessedCallback routeFeaturesProcessedCallback = new OnRouteFeaturesProcessedCallback() {
    @Override
    public void onRouteFeaturesProcessed(List<FeatureCollection> routeFeatureCollections,
                                         HashMap<LineString, DirectionsRoute> routeLineStrings) {
      MapRouteLine.this.routeFeatureCollections.addAll(routeFeatureCollections);
      MapRouteLine.this.routeLineStrings.putAll(routeLineStrings);
      drawRoutes(routeFeatureCollections);
      drawWayPoints();
      updateAlternativeVisibilityTo(alternativesVisible);
      updateRoutesFor(primaryRouteIndex);
      updateVisibilityTo(isVisible);
    }
  };

  // Testing only
  OnRouteFeaturesProcessedCallback retrieveRouteFeaturesProcessedCallback() {
    return routeFeaturesProcessedCallback;
  }

  private void drawWayPoints() {
    DirectionsRoute primaryRoute = directionsRoutes.get(primaryRouteIndex);
    setWaypointsSource(buildWayPointFeatureCollectionFrom(primaryRoute));
  }

  private FeatureCollection buildWayPointFeatureCollectionFrom(DirectionsRoute route) {
    final List<Feature> wayPointFeatures = new ArrayList<>();
    for (RouteLeg leg : route.getLegs()) {
      wayPointFeatures.add(buildWayPointFeatureFromLeg(leg, 0));
      wayPointFeatures.add(buildWayPointFeatureFromLeg(leg, leg.getSteps().size() - 1));
    }
    return FeatureCollection.fromFeatures(wayPointFeatures);
  }

  private Feature buildWayPointFeatureFromLeg(RouteLeg leg, int index) {
    Feature feature = Feature.fromGeometry(Point.fromLngLat(
      leg.getSteps().get(index).getManeuver().getLocation().getLongitude(),
      leg.getSteps().get(index).getManeuver().getLocation().getLatitude()
    ));
    feature.addStringProperty(WAYPOINT_PROPERTY_KEY, index == 0 ? WAYPOINT_ORIGIN_VALUE : WAYPOINT_DESTINATION_VALUE);
    return feature;
  }

  private void updateRoutesFor(int newPrimaryIndex) {
    if (this.primaryRouteIndex != newPrimaryIndex) {
      resetVanishing();
    }
    this.primaryRouteIndex = newPrimaryIndex;
    if (newPrimaryIndex < 0 || newPrimaryIndex > routeFeatureCollections.size() - 1) {
      return;
    }
    PrimaryRouteUpdateTask task = primaryRouteUpdateTaskRef.getAndSet(retrievePrimaryRouteUpdateTask(newPrimaryIndex));
    if (task != null) {
      task.cancel();
    }

    // Retrieve the newly created task again. Maybe null
    task = primaryRouteUpdateTaskRef.get();

    // If the new task is not null, start it
    if (task != null) {
      task.start();
    }
  }

  // Testing only
  void injectPrimaryRouteUpdateTask(PrimaryRouteUpdateTask primaryRouteUpdateTask) {
    this.isPrimaryRouteUpdateTaskInjected = true;
    this.primaryRouteUpdateTask = primaryRouteUpdateTask;
  }

  private PrimaryRouteUpdateTask retrievePrimaryRouteUpdateTask(int newPrimaryIndex) {
    if (isPrimaryRouteUpdateTaskInjected) {
      return primaryRouteUpdateTask;
    }
    return new PrimaryRouteUpdateTask(newPrimaryIndex,
      routeFeatureCollections, primaryRouteUpdatedCallback, mainHandler);
  }

  private OnPrimaryRouteUpdatedCallback primaryRouteUpdatedCallback = new OnPrimaryRouteUpdatedCallback() {
    @Override
    public void onPrimaryRouteUpdated(List<FeatureCollection> updatedRouteCollections) {
      drawRoutes(updatedRouteCollections);
    }
  };

  // Testing only
  OnPrimaryRouteUpdatedCallback retrievePrimaryRouteUpdatedCallback() {
    return primaryRouteUpdatedCallback;
  }

  private String findRouteBelowLayerId(String belowLayer, Style style) {
    if (belowLayer == null || belowLayer.isEmpty()) {
      List<Layer> styleLayers = style.getLayers();
      for (int i = 0; i < styleLayers.size(); i++) {
        if (!(styleLayers.get(i) instanceof SymbolLayer)
          // Avoid placing the route on top of the user location layer
          && !styleLayers.get(i).getId().contains(RouteConstants.MAPLIBRE_LOCATION_ID)) {
          belowLayer = styleLayers.get(i).getId();
        }
      }
    }
    return belowLayer;
  }

  private static GeoJsonSource addOrReuseSource(Style style, String sourceId, GeoJsonSource source,
                                                FeatureCollection featureCollection) {
    GeoJsonSource existingSource = style.getSourceAs(sourceId);
    if (existingSource == null) {
      style.addSource(source);
      return source;
    }
    existingSource.setGeoJson(featureCollection);
    return existingSource;
  }

  private void initializeLayers(Style style, MapRouteLayerProvider layerProvider, String belowLayer) {
    LineLayer routeShieldLayer = layerProvider.initializeRouteShieldLayer(
      style, routeScale, alternativeRouteScale,
      routeShieldColor, alternativeRouteShieldColor
    );
    LineLayer routeLayer = layerProvider.initializeRouteLayer(
      style, roundedLineCap, routeScale, alternativeRouteScale,
      routeDefaultColor, routeModerateColor, routeSevereColor,
      alternativeRouteDefaultColor, alternativeRouteModerateColor,
      alternativeRouteSevereColor
    );
    LineLayer blendLayer = layerProvider.initializeRouteBlendLayer(
      style, routeLayer, routeDefaultColor, routeModerateColor, routeSevereColor,
      alternativeRouteDefaultColor, alternativeRouteModerateColor, alternativeRouteSevereColor);

    // Shields below both route parts, so the near part joins the far part without a seam
    for (LineLayer layer : new LineLayer[]{routeShieldLayer, routeLayer, blendLayer}) {
      MapUtils.addLayerToMap(style, layer, belowLayer);
      routeLayerIds.add(layer.getId());
      LineLayer nearLayer = layerProvider.copyLineLayer(
        style, layer, layer.getId() + ROUTE_NEAR_LAYER_SUFFIX, ROUTE_NEAR_SOURCE_ID);
      MapUtils.addLayerToMap(style, nearLayer, belowLayer);
      routeLayerIds.add(nearLayer.getId());
    }

    LineLayer fadeShieldLayer = layerProvider.initializeRouteFadeShieldLayer(style, routeShieldLayer, routeShieldColor);
    MapUtils.addLayerToMap(style, fadeShieldLayer, belowLayer);
    routeLayerIds.add(fadeShieldLayer.getId());

    int[] levelColors = {routeDefaultColor, routeModerateColor, routeSevereColor};
    for (int i = 0; i < RouteLinePainter.LEVELS.size(); i++) {
      LineLayer fadeLayer = layerProvider.initializeRouteFadeLayer(
        style, routeLayer, RouteLinePainter.LEVELS.get(i), levelColors[i]);
      MapUtils.addLayerToMap(style, fadeLayer, belowLayer);
      routeLayerIds.add(fadeLayer.getId());
    }
  }

  private void updateAlternativeVisibilityTo(boolean isAlternativeVisible) {
    this.alternativesVisible = isAlternativeVisible;
    if (style != null && style.isFullyLoaded()) {
      for (String layerId : routeLayerIds) {
        Layer layer = style.getLayer(layerId);
        if (!(layer instanceof LineLayer)) {
          continue;
        }
        LineLayer route = (LineLayer) layer;
        if (layerId.equals(ROUTE_SHIELD_LAYER_ID)) {
          route.setFilter(isAlternativeVisible ? Expression.literal(true)
            : Expression.eq(Expression.get(PRIMARY_ROUTE_PROPERTY_KEY), true));
        } else if (layerId.equals(ROUTE_LAYER_ID) || layerId.equals(ROUTE_BLEND_LAYER_ID)) {
          route.setFilter(MapRouteLayerProvider.blendFilter(layerId.equals(ROUTE_BLEND_LAYER_ID), isAlternativeVisible));
        }
      }
    }
  }

  private void updateAllLayersVisibilityTo(boolean isVisible) {
    this.isVisible = isVisible;
    if (style != null && style.isFullyLoaded()) {
      for (String layerId : routeLayerIds) {
        Layer layer = style.getLayer(layerId);
        if (layer != null) {
          layer.setProperties(
            visibility(isVisible ? VISIBLE : NONE)
          );
        }
      }
    }
  }

  private void setRoutesSource(FeatureCollection featureCollection) {
    drawnRouteFeatureCollection = featureCollection;
    routeLineSource.setGeoJson(drawnRouteFeatureCollection);
  }

  private void setWaypointsSource(FeatureCollection featureCollection) {
    drawnWaypointsFeatureCollection = featureCollection;
    wayPointSource.setGeoJson(drawnWaypointsFeatureCollection);
  }
}
