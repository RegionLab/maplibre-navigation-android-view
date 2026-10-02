package org.maplibre.navigation.android.navigation.ui.v5

import com.google.gson.JsonParser
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.geojson.BoundingBox as MapLibreBoundingBox
import org.maplibre.geojson.Feature as MapLibreFeature
import org.maplibre.geojson.FeatureCollection as MapLibreFeatureCollection
import org.maplibre.geojson.Geometry as MapLibreGeometry
import org.maplibre.geojson.GeometryCollection as MapLibreGeometryCollection
import org.maplibre.geojson.LineString as MapLibreLineString
import org.maplibre.geojson.MultiLineString as MapLibreMultiLineString
import org.maplibre.geojson.MultiPoint as MapLibreMultiPoint
import org.maplibre.geojson.MultiPolygon as MapLibreMultiPolygon
import org.maplibre.geojson.Point as MapLibrePoint
import org.maplibre.geojson.Polygon as MapLibrePolygon

@JvmName("positionsToMapLibre")
fun List<Position>.toMapLibrePoints(): List<MapLibrePoint> = map { pos -> pos.toMapLibre() }

@JvmName("pointsToMapLibre")
fun List<Point>.toMapLibrePoints(): List<MapLibrePoint> = map { pt -> pt.toMapLibre() }

fun Point.toMapLibre(): MapLibrePoint {
    return altitude?.let { alt ->
        MapLibrePoint.fromLngLat(
            longitude,
            latitude,
            alt,
            bbox?.toMapLibre()
        )
    } ?: MapLibrePoint.fromLngLat(
        longitude,
        latitude,
        bbox?.toMapLibre()
    )
}

fun LineString.toMapLibre(): MapLibreLineString = MapLibreLineString.fromLngLats(
    coordinates.toMapLibrePoints(),
    bbox?.toMapLibre()
)

fun MultiPoint.toMapLibre(): MapLibreMultiPoint = MapLibreMultiPoint.fromLngLats(
    coordinates.toMapLibrePoints(),
    bbox?.toMapLibre()
)

fun MultiLineString.toMapLibre(): MapLibreMultiLineString = MapLibreMultiLineString.fromLngLats(
    coordinates.map { it.toMapLibrePoints() },
    bbox?.toMapLibre()
)

fun Polygon.toMapLibre(): MapLibrePolygon = MapLibrePolygon.fromLngLats(
    coordinates.map { it.toMapLibrePoints() },
    bbox?.toMapLibre()
)

fun MultiPolygon.toMapLibre(): MapLibreMultiPolygon = MapLibreMultiPolygon.fromLngLats(
    coordinates.map { polygon -> polygon.map { it.toMapLibrePoints() } },
    bbox?.toMapLibre()
)

fun <G : Geometry> GeometryCollection<G>.toMapLibre(): MapLibreGeometryCollection = MapLibreGeometryCollection.fromGeometries(
    this.map { it.toMapLibre() },
    bbox?.toMapLibre()
)

fun Geometry.toMapLibre(): MapLibreGeometry {
    return when (this) {
        is Point -> toMapLibre()
        is LineString -> toMapLibre()
        is MultiPoint -> toMapLibre()
        is MultiLineString -> toMapLibre()
        is Polygon -> toMapLibre()
        is MultiPolygon -> toMapLibre()
        is GeometryCollection<*> -> toMapLibre()
    }
}

fun <G : Geometry?, P> Feature<G, P>.toMapLibre(): MapLibreFeature {
    return MapLibreFeature.fromGeometry(
        geometry?.toMapLibre(),
        properties?.let { JsonParser.parseString(it.toString()).asJsonObject },
        id?.toString(),
        bbox?.toMapLibre()
    )
}

fun <G : Geometry?, P> FeatureCollection<G, P>.toMapLibre(): MapLibreFeatureCollection {
    return MapLibreFeatureCollection.fromFeatures(
        this.map { it.toMapLibre() },
        bbox?.toMapLibre()
    )
}

fun Position.toMapLibre(): MapLibrePoint {
    return altitude?.let { alt ->
        MapLibrePoint.fromLngLat(longitude, latitude, alt)
    } ?: MapLibrePoint.fromLngLat(longitude, latitude)
}

fun BoundingBox.toMapLibre(): MapLibreBoundingBox {
    return MapLibreBoundingBox.fromPoints(
        southwest.toMapLibre(),
        northeast.toMapLibre()
    )
}
