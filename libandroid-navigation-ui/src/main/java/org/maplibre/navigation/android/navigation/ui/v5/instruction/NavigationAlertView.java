package org.maplibre.navigation.android.navigation.ui.v5.instruction;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import org.maplibre.navigation.android.navigation.ui.v5.R;
import org.maplibre.navigation.android.navigation.ui.v5.alert.AlertView;
import org.maplibre.navigation.core.navigation.NavigationConstants;

/**
 * Alert view shown inside the {@link InstructionView}.
 */
public class NavigationAlertView extends AlertView {

  private static final long THREE_SECOND_DELAY_IN_MILLIS = 3000;
  private final Runnable showReportProblem = () -> show(getContext().getString(R.string.report_problem),
    NavigationConstants.ALERT_VIEW_PROBLEM_DURATION, true);
  private boolean isEnabled = true;

  public NavigationAlertView(Context context) {
    this(context, null);
  }

  public NavigationAlertView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, -1);
  }

  public NavigationAlertView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  /**
   * Shows this alert view to let user report a problem after a short delay.
   */
  public void showReportProblem() {
    if (!isEnabled) {
      return;
    }
    removeCallbacks(showReportProblem);
    postDelayed(showReportProblem, THREE_SECOND_DELAY_IN_MILLIS);
  }

  /**
   * Enables or disables showing this alert view.
   *
   * @param isEnabled true to allow showing, false to never show
   */
  public void updateEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
    if (!isEnabled) {
      removeCallbacks(showReportProblem);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(showReportProblem);
    super.onDetachedFromWindow();
  }
}
