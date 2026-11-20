package org.maplibre.navigation.android.navigation.ui.v5.instruction;

import android.content.Context;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import org.maplibre.navigation.android.navigation.ui.v5.R;
import org.maplibre.navigation.android.navigation.ui.v5.instruction.maneuver.ManeuverView;
import org.maplibre.navigation.android.navigation.ui.v5.instruction.turnlane.TurnLaneAdapter;
import org.maplibre.navigation.android.navigation.ui.v5.listeners.InstructionListListener;
import org.maplibre.navigation.android.navigation.ui.v5.summary.list.InstructionListAdapter;
import org.maplibre.navigation.android.navigation.ui.v5.utils.DistanceFormatter;
import org.maplibre.navigation.android.navigation.ui.v5.utils.LocaleUtils;
import org.maplibre.navigation.core.milestone.BannerInstructionMilestone;
import org.maplibre.navigation.core.milestone.Milestone;
import org.maplibre.navigation.core.milestone.MilestoneEventListener;
import org.maplibre.navigation.core.models.BannerComponents;
import org.maplibre.navigation.core.models.BannerInstructions;
import org.maplibre.navigation.core.models.BannerText;
import org.maplibre.navigation.core.models.LegStep;
import org.maplibre.navigation.core.models.UnitType;
import org.maplibre.navigation.core.navigation.MapLibreNavigation;
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions;
import org.maplibre.navigation.core.offroute.OffRouteListener;
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener;
import org.maplibre.navigation.core.routeprogress.RouteProgress;


/**
 * A view that can be used to display upcoming maneuver information and control
 * voice instruction mute / unmute.
 * <p>
 * An {@link ImageView} is used to display the maneuver image on the left.
 * Two {@link TextView}s are used to display distance to the next maneuver, as well
 * as the name of the destination / maneuver name / instruction based on what data is available
 * <p>
 * To automatically have this view update with information from
 * {@link MapLibreNavigation},
 * add the view as a {@link ProgressChangeListener} and / or {@link OffRouteListener}
 *
 * @since 0.6.0
 */
public class InstructionView extends RelativeLayout {

    private static final String COMPONENT_TYPE_LANE = "lane";

    private ManeuverView upcomingManeuverView;
    private TextView upcomingDistanceText;
    private TextView upcomingPrimaryText;
    private TextView upcomingSecondaryText;
    private ManeuverView subManeuverView;
    private TextView subStepText;
    private NavigationAlertView alertView;
    private View rerouteLayout;
    private View turnLaneLayout;
    private View subStepLayout;
    private RecyclerView rvTurnLanes;
    private RecyclerView rvInstructions;
    private TurnLaneAdapter turnLaneAdapter;
    private ConstraintLayout instructionLayout;
    private LinearLayout instructionLayoutText;
    private View instructionListLayout;
    private InstructionListAdapter instructionListAdapter;
    private static final long REROUTE_FADE_DURATION_MS = 200;
    private LegStep currentStep;
    private InstructionListListener instructionListListener;
    @Nullable
    private RouteProgress lastRouteProgress;

    private DistanceFormatter distanceFormatter;
    private boolean isRerouting;
    private boolean instructionListEnabled = true;
    private boolean instructionContentHidden;
    private int turnLaneLayoutVisibility = GONE;
    private int subStepLayoutVisibility = GONE;
    private int instructionListLayoutVisibility = GONE;
    private int alertViewVisibility = INVISIBLE;

    public InstructionView(Context context) {
        this(context, null);
    }

    public InstructionView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, -1);
    }

    public InstructionView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    /**
     * Adds a listener that is triggered when the instruction list in InstructionView is shown or hidden.
     *
     * @param instructionListListener to be set
     */
    public void setInstructionListListener(InstructionListListener instructionListListener) {
        this.instructionListListener = instructionListListener;
    }

    /**
     * Once this view has finished inflating, it will bind the views.
     * <p>
     * It will also initialize the {@link RecyclerView} used to display the turn lanes
     * and animations used to show / hide views.
     */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        bind();
        initializeTurnLaneRecyclerView();
        initializeInstructionListRecyclerView();
        initializeStepListClickListener();
        ImageCreator.getInstance().initialize(getContext());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelDelayedTransition();
    }

    /**
     * Updates the step distance, the instruction list and prefetches images for the next step.
     *
     * @param model created from the current route progress
     */
    public void updateInstruction(@NonNull InstructionModel model) {
        updateDataFromInstruction(model);
    }

    /**
     * Updates the maneuver, banner texts, sub step and turn lanes. Ignored while rerouting.
     *
     * @param model created from the banner instructions to display
     */
    public void updateBannerInstruction(@NonNull BannerInstructionModel model) {
        if (isRerouting) {
            return;
        }
        updateManeuverView(model.retrievePrimaryManeuverType(), model.retrievePrimaryManeuverModifier(),
                model.retrievePrimaryRoundaboutAngle(), model.retrieveDrivingSide());
        updateDataFromBannerText(model.retrievePrimaryBannerText(), model.retrieveSecondaryBannerText());
        updateSubStep(model.retrieveSubBannerText(), model.retrievePrimaryManeuverType());
    }

    /**
     * Shows the reroute state while a new route is requested and hides it afterwards.
     *
     * @param isRerouting true while a new route is requested
     */
    public void updateRerouteState(boolean isRerouting) {
        if (isRerouting == this.isRerouting) {
            return;
        }
        if (isRerouting) {
            showRerouteState();
        } else {
            hideRerouteState();
        }
        this.isRerouting = isRerouting;
    }

    /**
     * Use this method inside a {@link ProgressChangeListener} to update this view with all other information
     * that is not updated by the {@link InstructionView#updateBannerInstructionsWith(Milestone)}.
     * <p>
     * This includes the distance remaining, instruction list, turn lanes, and next step information.
     *
     * @param routeProgress for route data used to populate the views
     * @since 0.20.0
     */
    public void updateDistanceWith(RouteProgress routeProgress) {
        if (routeProgress != null && !isRerouting) {
            InstructionModel model = new InstructionModel(distanceFormatter, routeProgress);
            updateDataFromInstruction(model);
        }
    }

    /**
     * Use this in a {@link MilestoneEventListener} to update
     * this view with new banner instructions.
     * <p>
     * This method will look at the type of milestone to determine when
     * it should update.
     *
     * @param milestone for retrieving the new BannerInstructions
     * @since 0.20.0
     */
    public void updateBannerInstructionsWith(Milestone milestone) {
        if (milestone instanceof BannerInstructionMilestone) {
            BannerInstructions instructions = ((BannerInstructionMilestone) milestone).getBannerInstructions();
            if (instructions == null || instructions.getPrimary() == null) {
                return;
            }
            BannerText primary = instructions.getPrimary();
            String primaryManeuverModifier = primary.getModifier().getText();
            String drivingSide = currentStep.getDrivingSide();
            updateManeuverView(primary.getType().getText(), primaryManeuverModifier, primary.getDegrees(), drivingSide);
            updateDataFromBannerText(primary, instructions.getSecondary());
            updateSubStep(instructions.getSub(), primaryManeuverModifier);
        }
    }


    /**
     * Fades in the reroute state over the instruction card. The card keeps its size, the
     * content below it (turn lanes, sub step, list) is hidden.
     *
     * @since 0.6.0
     */
    public void showRerouteState() {
        setInstructionContentHidden(true);
        rerouteLayout.animate().cancel();
        if (rerouteLayout.getVisibility() != VISIBLE) {
            rerouteLayout.setAlpha(0f);
            rerouteLayout.setVisibility(VISIBLE);
        }
        rerouteLayout.animate().alpha(1f).setDuration(REROUTE_FADE_DURATION_MS).start();
    }

    /**
     * Fades out the reroute state and shows the instruction content again.
     *
     * @since 0.6.0
     */
    public void hideRerouteState() {
        setInstructionContentHidden(false);
        rerouteLayout.animate().cancel();
        if (rerouteLayout.getVisibility() != VISIBLE) {
            return;
        }
        rerouteLayout.animate()
                .alpha(0f)
                .setDuration(REROUTE_FADE_DURATION_MS)
                .withEndAction(() -> rerouteLayout.setVisibility(INVISIBLE))
                .start();
    }

    /**
     * Can be used to determine the visibility of the instruction list.
     *
     * @return true if instruction list is visible, false is not
     */
    public boolean isShowingInstructionList() {
        return instructionListLayout.getVisibility() == VISIBLE;
    }

    /**
     * Hide the instruction list.
     * <p>
     * This is based on orientation so the different layouts (for portrait vs. landscape)
     * can be animated appropriately.
     */
    public void hideInstructionList() {
        rvInstructions.stopScroll();
        beginDelayedTransition();
        if (isLandscape()) {
            updateLandscapeConstraintsTo(R.layout.instruction_layout);
        }
        instructionListLayout.setVisibility(GONE);
        onInstructionListVisibilityChanged(false);
    }

    private boolean isLandscape() {
        return getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Show the instruction list.
     * <p>
     * This is based on orientation so the different layouts (for portrait vs. landscape)
     * can be animated appropriately.
     */
    public void showInstructionList() {
        if (!instructionListEnabled) {
            return;
        }
        onInstructionListVisibilityChanged(true);
        // The list is not updated while hidden, catch up with the current progress
        if (lastRouteProgress != null) {
            instructionListAdapter.updateBannerListWith(lastRouteProgress, true);
        }
        instructionLayout.requestFocus();
        beginDelayedListTransition();
        if (isLandscape()) {
            updateLandscapeConstraintsTo(R.layout.instruction_layout_alt);
        }
        instructionListLayout.setVisibility(VISIBLE);
    }

    public boolean handleBackPressed() {
        if (!instructionListEnabled) {
            return false;
        }
        if (isShowingInstructionList()) {
            hideInstructionList();
            return true;
        }
        return false;
    }

    /**
     * Sets the distance formatter
     *
     * @param distanceFormatter to set
     */
    public void setDistanceFormatter(DistanceFormatter distanceFormatter) {
        if (distanceFormatter != null && !distanceFormatter.equals(this.distanceFormatter)) {
            this.distanceFormatter = distanceFormatter;
            instructionListAdapter.updateDistanceFormatter(distanceFormatter);
        }
    }

    /**
     * Enables or disables opening the instruction list.
     * Existing open list will be hidden when disabled.
     *
     * @param enabled true to allow, false to disable
     */
    public void setInstructionListEnabled(boolean enabled) {
        instructionListEnabled = enabled;
        if (!enabled && instructionListLayout != null && isShowingInstructionList()) {
            hideInstructionList();
        }
    }

    /**
     * Returns the {@link NavigationAlertView} that is shown during off-route events with
     * "Report a Problem" text.
     *
     * @return alert view that is used in the instruction view
     */
    public NavigationAlertView retrieveAlertView() {
        return alertView;
    }

    /**
     * Inflates this layout needed for this view and initializes the locale as the device locale.
     */
    private void initialize() {
        LocaleUtils localeUtils = new LocaleUtils();
        String language = localeUtils.inferDeviceLanguage(getContext());
        UnitType unitType = localeUtils.getUnitTypeForDeviceLocale(getContext());
        distanceFormatter = new DistanceFormatter(getContext(), language, unitType, MapLibreNavigationOptions.RoundingIncrement.ROUNDING_INCREMENT_FIFTY);
        inflate(getContext(), R.layout.instruction_view_layout, this);
    }

    /**
     * Finds and binds all necessary views
     */
    private void bind() {
        upcomingManeuverView = findViewById(R.id.maneuverView);
        upcomingDistanceText = findViewById(R.id.stepDistanceText);
        upcomingPrimaryText = findViewById(R.id.stepPrimaryText);
        upcomingSecondaryText = findViewById(R.id.stepSecondaryText);
        subManeuverView = findViewById(R.id.subManeuverView);
        subStepText = findViewById(R.id.subStepText);
        alertView = findViewById(R.id.alertView);
        rerouteLayout = findViewById(R.id.rerouteLayout);
        turnLaneLayout = findViewById(R.id.turnLaneLayout);
        subStepLayout = findViewById(R.id.subStepLayout);
        rvTurnLanes = findViewById(R.id.rvTurnLanes);
        instructionLayout = findViewById(R.id.instructionLayout);
        instructionLayoutText = findViewById(R.id.instructionLayoutText);
        instructionListLayout = findViewById(R.id.instructionListLayout);
        rvInstructions = findViewById(R.id.rvInstructions);
    }

    /**
     * Sets up the {@link RecyclerView} that is used to display the turn lanes.
     */
    private void initializeTurnLaneRecyclerView() {
        turnLaneAdapter = new TurnLaneAdapter();
        rvTurnLanes.setAdapter(turnLaneAdapter);
        rvTurnLanes.setHasFixedSize(true);
        rvTurnLanes.setLayoutManager(new LinearLayoutManager(getContext(),
                LinearLayoutManager.HORIZONTAL, false));
    }

    /**
     * Sets up the {@link RecyclerView} that is used to display the list of instructions.
     */
    private void initializeInstructionListRecyclerView() {
        instructionListAdapter = new InstructionListAdapter(distanceFormatter);
        rvInstructions.setAdapter(instructionListAdapter);
        rvInstructions.setHasFixedSize(true);
        rvInstructions.setLayoutManager(new LinearLayoutManager(getContext()));
    }

    private void setInstructionContentHidden(boolean hidden) {
        if (hidden == instructionContentHidden) {
            return;
        }

        // The card content stays in place under the reroute state, so the card doesn't change
        // size. Only the content below the card is hidden.
        int cardContentAccessibility = hidden
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        upcomingManeuverView.setImportantForAccessibility(cardContentAccessibility);
        instructionLayoutText.setImportantForAccessibility(cardContentAccessibility);
        upcomingDistanceText.setImportantForAccessibility(cardContentAccessibility);
        if (hidden) {
            turnLaneLayoutVisibility = turnLaneLayout.getVisibility();
            subStepLayoutVisibility = subStepLayout.getVisibility();
            instructionListLayoutVisibility = instructionListLayout.getVisibility();
            alertViewVisibility = alertView.getVisibility();

            turnLaneLayout.setVisibility(GONE);
            subStepLayout.setVisibility(GONE);
            instructionListLayout.setVisibility(GONE);
            alertView.setVisibility(GONE);
        } else {
            turnLaneLayout.setVisibility(turnLaneLayoutVisibility);
            subStepLayout.setVisibility(subStepLayoutVisibility);
            instructionListLayout.setVisibility(instructionListLayoutVisibility);
            alertView.setVisibility(alertViewVisibility);
        }

        instructionContentHidden = hidden;
    }

    private void onInstructionListVisibilityChanged(boolean visible) {
        if (instructionListListener != null) {
            instructionListListener.onInstructionListVisibilityChanged(visible);
        }
    }

    private void initializeStepListClickListener() {
        if (isLandscape()) {
            initializeLandscapeListListener();
        } else {
            initializePortraitListListener();
        }
    }

    /**
     * For portrait orientation, attach the listener to the whole layout
     * and use custom animations to hide and show the instructions /sound layout
     */
    private void initializePortraitListListener() {
//    instructionLayout.setOnClickListener(new OnClickListener() {
//      @Override
//      public void onClick(View instructionView) {
//        boolean instructionsVisible = instructionListLayout.getVisibility() == VISIBLE;
//        if (!instructionsVisible) {
//          showInstructionList();
//        } else {
//          hideInstructionList();
//        }
//      }
//    });
    }

    /**
     * For landscape orientation, the click listener is attached to
     * the instruction text layout and the constraints are adjusted before animating
     */
    private void initializeLandscapeListListener() {
        instructionLayoutText.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View instructionLayoutText) {
                if (!instructionListEnabled) {
                    return;
                }
                boolean instructionsVisible = instructionListLayout.getVisibility() == VISIBLE;
                if (!instructionsVisible) {
                    showInstructionList();
                } else {
                    hideInstructionList();
                }
            }
        });
    }

    /**
     * Looks to see if we have a new distance text.
     *
     * @param model provides distance text
     */
    private boolean newDistanceText(InstructionModel model) {
        return !upcomingDistanceText.getText().toString().isEmpty()
                && !TextUtils.isEmpty(model.retrieveStepDistanceRemaining())
                && !upcomingDistanceText.getText().toString()
                .contentEquals(model.retrieveStepDistanceRemaining().toString());
    }

    /**
     * Sets current distance text.
     *
     * @param model provides distance text
     */
    private void distanceText(InstructionModel model) {
        upcomingDistanceText.setText(model.retrieveStepDistanceRemaining());
    }

    private InstructionLoader createInstructionLoader(TextView textView, BannerText
            bannerText) {
        if (hasComponents(bannerText)) {
            return new InstructionLoader(textView, bannerText);
        } else {
            return null;
        }
    }

    private boolean hasComponents(BannerText bannerText) {
        return bannerText != null && bannerText.getComponents() != null && !bannerText.getComponents().isEmpty();
    }

    /**
     * Looks to see if we have a new step.
     *
     * @param routeProgress provides updated step information
     * @return true if new step, false if not
     */
    private boolean newStep(RouteProgress routeProgress) {
        boolean newStep = currentStep == null || !currentStep.equals(routeProgress.getCurrentLegProgress().getCurrentStep());
        currentStep = routeProgress.getCurrentLegProgress().getCurrentStep();
        return newStep;
    }

    private void updateSubStep(BannerText subText, String primaryManeuverModifier) {
        if (shouldShowSubStep(subText)) {
            String maneuverType = subText.getType().getText();
            String maneuverModifier = subText.getModifier() == null ? null : subText.getModifier().getText();
            subManeuverView.setManeuverTypeAndModifier(maneuverType, maneuverModifier);
            Double roundaboutAngle = subText.getDegrees();
            if (roundaboutAngle != null) {
                subManeuverView.setRoundaboutAngle(roundaboutAngle.floatValue());
            }
            String drivingSide = currentStep.getDrivingSide();
            subManeuverView.setDrivingSide(drivingSide);
            InstructionLoader instructionLoader = createInstructionLoader(subStepText, subText);
            if (instructionLoader != null) {
                instructionLoader.loadInstruction();
            }
            showSubLayout();
            return;
        } else {
            hideSubLayout();
        }

        if (shouldShowTurnLanes(subText, primaryManeuverModifier)) {
            turnLaneAdapter.addTurnLanes(subText.getComponents(), primaryManeuverModifier);
            showTurnLanes();
        } else {
            hideTurnLanes();
        }
    }

    private boolean shouldShowSubStep(@Nullable BannerText subText) {
        return subText != null
                && subText.getType() != null
                && !subText.getType().getText().contains(COMPONENT_TYPE_LANE);
    }

    private void showSubLayout() {
        if (!(subStepLayout.getVisibility() == VISIBLE)) {
            beginDelayedTransition();
            subStepLayout.setVisibility(VISIBLE);
        }
    }

    private void hideSubLayout() {
        if (subStepLayout.getVisibility() == VISIBLE) {
            beginDelayedTransition();
            subStepLayout.setVisibility(GONE);
        }
    }

    private boolean shouldShowTurnLanes(BannerText subText, String maneuverModifier) {
        if (!hasComponents(subText) || TextUtils.isEmpty(maneuverModifier)) {
            return false;
        }
        for (BannerComponents components : subText.getComponents()) {
            if (components.getType().equals(COMPONENT_TYPE_LANE)) {
                return true;
            }
        }
        return false;
    }

    private void showTurnLanes() {
        if (turnLaneLayout.getVisibility() == GONE) {
            beginDelayedTransition();
            turnLaneLayout.setVisibility(VISIBLE);
        }
    }

    private void hideTurnLanes() {
        if (turnLaneLayout.getVisibility() == VISIBLE) {
            beginDelayedTransition();
            turnLaneLayout.setVisibility(GONE);
        }
    }

    /**
     * Adjust the banner text layout {@link ConstraintLayout} vertical bias.
     *
     * @param percentBias to be set to the text layout
     */
    private void adjustBannerTextVerticalBias(float percentBias) {
        if (!isLandscape()) {
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) instructionLayoutText.getLayoutParams();
            params.verticalBias = percentBias;
            instructionLayoutText.setLayoutParams(params);
        }
    }

    private void beginDelayedTransition() {
        TransitionManager.beginDelayedTransition(this);
    }

    private void beginDelayedListTransition() {
        AutoTransition transition = new AutoTransition();
        transition.addListener(new InstructionListTransitionListener(rvInstructions, instructionListAdapter));
        TransitionManager.beginDelayedTransition(this, transition);
    }

    private void cancelDelayedTransition() {
        clearAnimation();
    }

    private void updateDataFromInstruction(InstructionModel model) {
        updateDistanceText(model);
        updateInstructionList(model);
        if (newStep(model.retrieveProgress())) {
            LegStep upComingStep = model.retrieveProgress().getCurrentLegProgress().getUpComingStep();
            ImageCreator.getInstance().prefetchImageCache(upComingStep);
        }
    }

    /**
     * Looks to see if we have a new instruction text.
     * Sets new instruction text if found.
     */
    private void updateDataFromBannerText(@NonNull BannerText primaryBannerText, BannerText secondaryBannerText) {
        if (secondaryBannerText == null) {
            loadPrimary(primaryBannerText);
            return;
        }
        loadPrimaryAndSecondary(primaryBannerText, secondaryBannerText);
    }

    private void loadPrimary(BannerText primaryBannerText) {
        upcomingPrimaryText.setMaxLines(2);
        upcomingSecondaryText.setVisibility(GONE);
        adjustBannerTextVerticalBias(0.5f);
        loadTextWith(primaryBannerText, upcomingPrimaryText);
    }

    private void loadPrimaryAndSecondary(BannerText primaryBannerText, BannerText secondaryBannerText) {
        upcomingPrimaryText.setMaxLines(1);
        upcomingSecondaryText.setVisibility(VISIBLE);
        adjustBannerTextVerticalBias(0.65f);
        loadTextWith(primaryBannerText, upcomingPrimaryText);

        loadTextWith(secondaryBannerText, upcomingSecondaryText);
    }

    private void loadTextWith(BannerText bannerText, TextView textView) {
        InstructionLoader instructionLoader = createInstructionLoader(textView, bannerText);
        if (instructionLoader != null) {
            instructionLoader.loadInstruction();
        }
    }

    /**
     * Looks to see if we have a new maneuver modifier or type.
     * Updates new maneuver image if one is found.
     */
    private void updateManeuverView(String maneuverViewType, String maneuverViewModifier,
                                    @Nullable Double roundaboutAngle, String drivingSide) {
        upcomingManeuverView.setManeuverTypeAndModifier(maneuverViewType, maneuverViewModifier);
        if (roundaboutAngle != null) {
            upcomingManeuverView.setRoundaboutAngle(roundaboutAngle.floatValue());
        }
        upcomingManeuverView.setDrivingSide(drivingSide);
    }

    /**
     * Looks to see if we have a new distance text.
     * Sets new distance text if found.
     *
     * @param model provides distance text
     */
    private void updateDistanceText(InstructionModel model) {
        if (newDistanceText(model)) {
            distanceText(model);
        } else if (upcomingDistanceText.getText().toString().isEmpty()) {
            distanceText(model);
        }
    }

    private void updateLandscapeConstraintsTo(int layoutRes) {
        ConstraintSet collapsed = new ConstraintSet();
        collapsed.clone(getContext(), layoutRes);
        collapsed.applyTo(instructionLayout);
    }

    /**
     * Used to update the instructions list with the current steps.
     *
     * @param model to provide the current steps and unit type
     */
    private void updateInstructionList(InstructionModel model) {
        lastRouteProgress = model.retrieveProgress();
        // Rebuilding the list on every location update is wasted work while it is hidden
        if (instructionListLayout.getVisibility() != VISIBLE) {
            return;
        }
        rvInstructions.stopScroll();
        instructionListAdapter.updateBannerListWith(lastRouteProgress, true);
    }

}
