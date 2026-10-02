package org.maplibre.navigation.android.navigation.ui.v5;

import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class NavigationOnCameraTrackingChangedListenerTest {

  @Test
  public void onCameraTrackingDismissed_presenterIsNotified() {
    NavigationPresenter presenter = mock(NavigationPresenter.class);
    NavigationOnCameraTrackingChangedListener listener = new NavigationOnCameraTrackingChangedListener(
      presenter
    );

    listener.onCameraTrackingDismissed();

    verify(presenter).onCameraTrackingDismissed();
  }
}
