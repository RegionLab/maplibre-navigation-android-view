package org.maplibre.navigation.android.navigation.ui.v5.map;

import org.junit.Before;
import org.junit.Test;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.PropertyValue;
import org.maplibre.android.style.layers.SymbolLayer;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NavigationMapStyleOptimizerTest {

  private MapLibreMap mapLibreMap;
  private Style style;
  private FillExtrusionLayer buildings3d;
  private FillLayer flatBuildings;
  private SymbolLayer poi;
  private SymbolLayer oneWayArrow;
  private SymbolLayer roadNames;
  private LineLayer railHatching;
  private LineLayer road;
  private NavigationMapStyleOptimizer optimizer;

  @Before
  public void setUp() {
    mapLibreMap = mock(MapLibreMap.class);
    style = mock(Style.class);
    when(mapLibreMap.getStyle()).thenReturn(style);
    when(mapLibreMap.getPrefetchZoomDelta()).thenReturn(4);
    when(style.isFullyLoaded()).thenReturn(true);

    buildings3d = layer(FillExtrusionLayer.class, "building-3d");
    flatBuildings = layer(FillLayer.class, "building");
    when(flatBuildings.getSourceLayer()).thenReturn("building");
    when(flatBuildings.getMaxZoom()).thenReturn(14f);
    poi = layer(SymbolLayer.class, "poi_r1");
    when(poi.getSourceLayer()).thenReturn("poi");
    oneWayArrow = layer(SymbolLayer.class, "road_one_way_arrow");
    when(oneWayArrow.getSourceLayer()).thenReturn("transportation");
    roadNames = layer(SymbolLayer.class, "highway-name-major");
    when(roadNames.getSourceLayer()).thenReturn("transportation_name");
    railHatching = layer(LineLayer.class, "road_major_rail_hatching");
    when(railHatching.getSourceLayer()).thenReturn("transportation");
    road = layer(LineLayer.class, "road_trunk_primary");
    when(road.getSourceLayer()).thenReturn("transportation");

    when(style.getLayers()).thenReturn(Arrays.<Layer>asList(
      buildings3d, flatBuildings, poi, oneWayArrow, roadNames, railHatching, road));
    optimizer = new NavigationMapStyleOptimizer(mapLibreMap);
  }

  @Test
  public void apply_hidesExpensiveLayers() {
    optimizer.apply();

    assertHidden(buildings3d);
    assertHidden(poi);
    assertHidden(oneWayArrow);
    assertHidden(railHatching);
    verify(roadNames, never()).setProperties(any(PropertyValue.class));
    verify(road, never()).setProperties(any(PropertyValue.class));
  }

  @Test
  public void apply_showsFlatBuildingsInsteadOf3d() {
    optimizer.apply();

    verify(flatBuildings).setMaxZoom(24f);
  }

  @Test
  public void apply_disablesTilePrefetching() {
    optimizer.apply();

    assertTrue(optimizer.isApplied());
    verify(mapLibreMap).setPrefetchZoomDelta(0);
  }

  @Test
  public void restore_revertsAllChanges() {
    optimizer.apply();
    optimizer.restore();

    assertFalse(optimizer.isApplied());
    verify(mapLibreMap).setPrefetchZoomDelta(4);
    verify(flatBuildings).setMaxZoom(14f);
    assertEquals(Property.VISIBLE, lastVisibility(poi));
  }

  @Test
  public void apply_ignoredWhenStyleNotLoaded() {
    when(style.isFullyLoaded()).thenReturn(false);

    optimizer.apply();

    assertFalse(optimizer.isApplied());
    verify(mapLibreMap, never()).setPrefetchZoomDelta(0);
  }

  private <T extends Layer> T layer(Class<T> type, String id) {
    T layer = mock(type);
    when(layer.getId()).thenReturn(id);
    when(layer.getVisibility()).thenReturn(PropertyFactory.visibility(Property.VISIBLE));
    when(style.getLayer(id)).thenReturn(layer);
    return layer;
  }

  private void assertHidden(Layer layer) {
    ArgumentCaptor<PropertyValue> captor = ArgumentCaptor.forClass(PropertyValue.class);
    verify(layer).setProperties(captor.capture());
    assertEquals(Property.NONE, captor.getValue().getValue());
  }

  private String lastVisibility(Layer layer) {
    ArgumentCaptor<PropertyValue> captor = ArgumentCaptor.forClass(PropertyValue.class);
    verify(layer, org.mockito.Mockito.times(2)).setProperties(captor.capture());
    return (String) captor.getValue().getValue();
  }
}
