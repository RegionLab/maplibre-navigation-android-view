package org.maplibre.navigation.android.navigation.ui.v5.map;

import android.content.Context;
import android.content.res.Resources;

import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.navigation.android.navigation.ui.v5.R;

class MapPaddingAdjustor {

  private static final int BOTTOMSHEET_PADDING_MULTIPLIER = 4;
  private static final int WAYNAME_PADDING_MULTIPLIER = 2;

  private static final double DEFAULT_TOP_PADDING_FRACTION = .4;

  private final MapLibreMap mapLibreMap;
  private final MapView mapView;
  private final int[] fixedDefaultPadding;
  private int[] customPadding;
  private int bottomInset;

  MapPaddingAdjustor(MapView mapView, MapLibreMap mapLibreMap) {
    this.mapLibreMap = mapLibreMap;
    this.mapView = mapView;
    this.fixedDefaultPadding = null;
  }

  // Testing only
  MapPaddingAdjustor(MapLibreMap mapLibreMap, int[] defaultPadding) {
    this.mapLibreMap = mapLibreMap;
    this.mapView = null;
    this.fixedDefaultPadding = defaultPadding;
  }

  void updatePaddingWithDefault() {
    customPadding = null;
    updatePaddingWith(calculateDefaultPadding());
  }

  /**
   * Height covered at the bottom of the map, e.g. by a bottom sheet. The default padding keeps the
   * location icon in the visible part of the map.
   */
  void updateBottomInset(int bottomInset) {
    this.bottomInset = Math.max(0, bottomInset);
  }

  int retrieveBottomInset() {
    return bottomInset;
  }

  void adjustLocationIconWith(int[] customPadding) {
    this.customPadding = customPadding;
    updatePaddingWith(customPadding);
  }

  int[] retrieveCurrentPadding() {
    return mapLibreMap.getPadding();
  }

  boolean isUsingDefault() {
    return customPadding == null;
  }

  void updatePaddingWith(int[] padding) {
    mapLibreMap.setPadding(padding[0], padding[1], padding[2], padding[3]);
  }

  void resetPadding() {
    if (isUsingDefault()) {
      updatePaddingWithDefault();
    } else {
      adjustLocationIconWith(customPadding);
    }
  }

  private int[] calculateDefaultPadding() {
    if (fixedDefaultPadding != null) {
      return fixedDefaultPadding;
    }
    // Calculated on demand, the map size changes e.g. on rotation or split screen
    int visibleHeight = Math.max(0, mapView.getHeight() - bottomInset);
    int defaultTopPadding = (int) (visibleHeight * DEFAULT_TOP_PADDING_FRACTION);
    return new int[] {0, defaultTopPadding, 0, bottomInset};
  }

}
