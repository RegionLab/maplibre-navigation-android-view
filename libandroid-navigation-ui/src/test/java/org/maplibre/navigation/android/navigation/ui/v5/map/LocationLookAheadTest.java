package org.maplibre.navigation.android.navigation.ui.v5.map;

import android.location.Location;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.android.location.LocationComponent;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import kotlin.jvm.functions.Function0;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(RobolectricTestRunner.class)
public class LocationLookAheadTest {

  private long now = 1_000_000L;
  private final Function0<Long> clock = () -> now;

  @Test
  public void movingLocation_isPredictedAlongBearing() {
    LocationComponent component = mock(LocationComponent.class);
    LocationLookAhead lookAhead = new LocationLookAhead(clock);

    // 10 m/s heading north, default interval 1s -> 10 m ahead
    lookAhead.update(component, location(10f, 0f));

    Location predicted = capturePredicted(component);
    float[] distance = new float[1];
    Location.distanceBetween(0.0, 0.0, predicted.getLatitude(), predicted.getLongitude(), distance);
    assertEquals(10.0, distance[0], 0.5);
    assertTrue(predicted.getLatitude() > 0.0);
    assertEquals(0.0, predicted.getLongitude(), 1e-9);
    assertEquals(now + 1000L, predicted.getTime());
  }

  @Test
  public void predictionDistance_isCapped() {
    LocationComponent component = mock(LocationComponent.class);
    LocationLookAhead lookAhead = new LocationLookAhead(clock);

    lookAhead.update(component, location(100f, 90f));

    float[] distance = new float[1];
    Location predicted = capturePredicted(component);
    Location.distanceBetween(0.0, 0.0, predicted.getLatitude(), predicted.getLongitude(), distance);
    assertEquals(50.0, distance[0], 0.5);
  }

  @Test
  public void slowLocation_isAppliedWithoutPrediction() {
    LocationComponent component = mock(LocationComponent.class);
    LocationLookAhead lookAhead = new LocationLookAhead(clock);
    Location location = location(0.5f, 0f);

    lookAhead.update(component, location);

    verify(component).forceLocationUpdate(location);
    verify(component, never()).forceLocationUpdate(anyList(), anyBoolean());
  }

  @Test
  public void locationWithoutBearing_isAppliedWithoutPrediction() {
    LocationComponent component = mock(LocationComponent.class);
    LocationLookAhead lookAhead = new LocationLookAhead(clock);
    Location location = new Location("test");
    location.setSpeed(10f);

    lookAhead.update(component, location);

    verify(component).forceLocationUpdate(location);
  }

  @Test
  public void predictionUsesMeasuredUpdateInterval() {
    LocationComponent component = mock(LocationComponent.class);
    LocationLookAhead lookAhead = new LocationLookAhead(clock);

    // Updates every 2s: smoothed interval moves from 1000 towards 2000
    for (int i = 0; i < 20; i++) {
      lookAhead.update(component, location(10f, 0f));
      now += 2000L;
    }
    now -= 2000L;

    Location predicted = capturePredicted(component);
    long interval = predicted.getTime() - now;
    assertTrue("interval " + interval, interval > 1900L && interval <= 2000L);
  }

  private Location location(float speed, float bearing) {
    Location location = new Location("test");
    location.setLatitude(0.0);
    location.setLongitude(0.0);
    location.setSpeed(speed);
    location.setBearing(bearing);
    return location;
  }

  @SuppressWarnings("unchecked")
  private Location capturePredicted(LocationComponent component) {
    ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
    verify(component, org.mockito.Mockito.atLeastOnce()).forceLocationUpdate(captor.capture(), eq(true));
    List<Location> locations = captor.getValue();
    assertEquals(1, locations.size());
    return locations.get(0);
  }
}
