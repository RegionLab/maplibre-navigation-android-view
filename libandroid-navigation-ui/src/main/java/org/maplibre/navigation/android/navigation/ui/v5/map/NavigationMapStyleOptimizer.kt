package org.maplibre.navigation.android.navigation.ui.v5.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Lightens the map while navigating: hides style layers that cost rendering time but don't help
 * while driving (3D buildings, POIs, water labels, one-way arrows, decorative patterns) and
 * disables tile prefetching. When 3D buildings are hidden, flat buildings are shown instead.
 *
 * Layers are matched by type and OpenMapTiles source layer (OpenFreeMap, MapTiler, ...), so layers
 * of other schemas and the navigation's own layers are left untouched.
 * Everything changed is restored by [restore].
 */
internal class NavigationMapStyleOptimizer(private val mapLibreMap: MapLibreMap) {

    /** Layer id to its original visibility. */
    private val hiddenLayers = mutableMapOf<String, String>()

    /** Layer id to its original max zoom. */
    private val extendedLayers = mutableMapOf<String, Float>()

    private var originalPrefetchZoomDelta: Int? = null

    val isApplied: Boolean
        get() = originalPrefetchZoomDelta != null

    fun apply() {
        if (isApplied) {
            return
        }
        val style = mapLibreMap.style?.takeIf { it.isFullyLoaded } ?: return

        originalPrefetchZoomDelta = mapLibreMap.prefetchZoomDelta
        mapLibreMap.prefetchZoomDelta = 0

        val layers = style.layers
        val hasBuildingExtrusions = layers.any { it is FillExtrusionLayer }
        for (layer in layers) {
            when {
                shouldHide(layer) -> hide(layer)
                hasBuildingExtrusions && layer is FillLayer && layer.sourceLayer == SOURCE_LAYER_BUILDING ->
                    showAtAllZooms(layer)
            }
        }
    }

    fun restore() {
        val style = mapLibreMap.style?.takeIf { it.isFullyLoaded }
        if (style != null) {
            hiddenLayers.forEach { (id, originalVisibility) ->
                style.getLayer(id)?.setProperties(visibility(originalVisibility))
            }
            extendedLayers.forEach { (id, originalMaxZoom) ->
                style.getLayer(id)?.maxZoom = originalMaxZoom
            }
        }
        hiddenLayers.clear()
        extendedLayers.clear()

        originalPrefetchZoomDelta?.let { mapLibreMap.prefetchZoomDelta = it }
        originalPrefetchZoomDelta = null
    }

    private fun shouldHide(layer: Layer): Boolean {
        return when (layer) {
            is FillExtrusionLayer -> true
            is SymbolLayer -> layer.sourceLayer in HIDDEN_SYMBOL_SOURCE_LAYERS
                    || ONE_WAY_LAYER_ID_PARTS.any { layer.id.contains(it) }

            is LineLayer -> layer.sourceLayer == SOURCE_LAYER_PARK
                    || layer.id.contains(HATCHING_LAYER_ID_PART)

            else -> false
        }
    }

    private fun hide(layer: Layer) {
        val originalVisibility = layer.visibility?.value ?: Property.VISIBLE
        if (originalVisibility == Property.NONE) {
            return
        }
        hiddenLayers[layer.id] = originalVisibility
        layer.setProperties(visibility(Property.NONE))
    }

    private fun showAtAllZooms(layer: FillLayer) {
        extendedLayers[layer.id] = layer.maxZoom
        layer.maxZoom = MAX_ZOOM
    }

    private companion object {
        const val SOURCE_LAYER_BUILDING = "building"
        const val SOURCE_LAYER_PARK = "park"
        const val HATCHING_LAYER_ID_PART = "hatching"
        const val MAX_ZOOM = 24f
        val HIDDEN_SYMBOL_SOURCE_LAYERS = setOf("poi", "water_name", "waterway")
        val ONE_WAY_LAYER_ID_PARTS = listOf("one_way", "oneway")
    }
}
