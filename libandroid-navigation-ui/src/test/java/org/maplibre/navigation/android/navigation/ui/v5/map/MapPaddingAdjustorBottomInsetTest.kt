package org.maplibre.navigation.android.navigation.ui.v5.map

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

class MapPaddingAdjustorBottomInsetTest {

    private val mapView: MapView = mockk { every { height } returns 1000 }
    private val mapLibreMap: MapLibreMap = mockk(relaxed = true)
    private val adjustor = MapPaddingAdjustor(mapView, mapLibreMap)

    @Test
    fun defaultPadding_placesLocationIconInLowerPartOfMap() {
        adjustor.updatePaddingWithDefault()

        verify { mapLibreMap.setPadding(0, 400, 0, 0) }
    }

    @Test
    fun defaultPadding_keepsLocationIconAboveBottomInset() {
        adjustor.updateBottomInset(300)

        adjustor.updatePaddingWithDefault()

        // 40 % of the 700 px that are still visible
        verify { mapLibreMap.setPadding(0, 280, 0, 300) }
    }
}
