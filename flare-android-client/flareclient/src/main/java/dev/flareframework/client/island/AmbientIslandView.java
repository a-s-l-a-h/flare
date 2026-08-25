package dev.flareframework.client.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * High-polish Dynamic Island for Flare.
 * Features 3-dot staggered wave morphing, holographic gradient sweeps,
 * center-to-top intro launch flight, and smooth completion transitions.
 */
public class AmbientIslandView extends FrameLayout {

    private static final int IDLE_WIDTH_DP = 115;
    private static final int IDLE_HEIGHT_DP = 36;
    private static final int ACTIVE_WIDTH_DP = 195;
    private static final long MIN_DISPLAY_MS = 600L;

    private LinearLayout idleContainer;
    private LinearLayout activeContainer;

    private View dot1, dot2, dot3;
    private View shimmerBeam;

    private AnimatorSet waveAnimatorSet;
    private ObjectAnimator beamAnimator;
    private ValueAnimator widthAnimator;
    private ObjectAnimator flyAnimator;

    private boolean isLoading = false;
    private boolean isHeroCentered = false;
    private long loadStartTime = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingExitRunnable = null;

    public AmbientIslandView(Context context) { this(context, null); }
    public AmbientIslandView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public AmbientIslandView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(false);
        setFocusable(false);

        // Outer Pill Shape
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(dp(IDLE_HEIGHT_DP / 2));
        pillBg.setColor(0xF2101017); // Obsidian Glass
        pillBg.setStroke(dp(1), 0x33FFFFFF); // Specular Rim
        setBackground(pillBg);

        buildIdleView();
        buildActiveView();

        // Initial layout dimensions
        setLayoutParams(new ViewGroup.LayoutParams(dp(IDLE_WIDTH_DP), dp(IDLE_HEIGHT_DP)));
    }

    private void buildIdleView() {
        idleContainer = new LinearLayout(getContext());
        idleContainer.setOrientation(LinearLayout.HORIZONTAL);
        idleContainer.setGravity(Gravity.CENTER);

        View idleDot = new View(getContext());
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF9B51E0); // Flare Purple
        idleDot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.setMargins(0, 0, dp(8), 0);
        idleContainer.addView(idleDot, dotLp);

        TextView label = new TextView(getContext());
        label.setText("Flare");
        label.setTextColor(0xEEFFFFFF);
        label.setTextSize(12f);
        label.setLetterSpacing(0.04f);
        idleContainer.addView(label);

        addView(idleContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private void buildActiveView() {
        activeContainer = new LinearLayout(getContext());
        activeContainer.setOrientation(LinearLayout.VERTICAL);
        activeContainer.setGravity(Gravity.CENTER);
        activeContainer.setVisibility(GONE);
        activeContainer.setAlpha(0f);

        // 1. Top row: 3-orb pulsing wave
        LinearLayout orbsRow = new LinearLayout(getContext());
        orbsRow.setOrientation(LinearLayout.HORIZONTAL);
        orbsRow.setGravity(Gravity.CENTER);

        dot1 = createOrb(0xFF8E44AD); // Purple
        dot2 = createOrb(0xFF00E5FF); // Cyan
        dot3 = createOrb(0xFFFF2A85); // Neon Pink

        orbsRow.addView(dot1, createOrbLp());
        orbsRow.addView(dot2, createOrbLp());
        orbsRow.addView(dot3, createOrbLp());

        activeContainer.addView(orbsRow, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // 2. Bottom row: Glowing beam track
        FrameLayout beamTrack = new FrameLayout(getContext());
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(2));
        trackBg.setColor(0x1AFFFFFF);
        beamTrack.setBackground(trackBg);

        shimmerBeam = new View(getContext());
        GradientDrawable beamBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x008E44AD, 0xFF00E5FF, 0xFFA855F7, 0x008E44AD}
        );
        beamBg.setCornerRadius(dp(2));
        shimmerBeam.setBackground(beamBg);

        FrameLayout.LayoutParams beamLp = new FrameLayout.LayoutParams(dp(45), dp(2));
        beamTrack.addView(shimmerBeam, beamLp);

        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(110), dp(2));
        trackLp.setMargins(0, dp(4), 0, 0);
        activeContainer.addView(beamTrack, trackLp);

        addView(activeContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private View createOrb(int color) {
        View v = new View(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        v.setBackground(bg);
        return v;
    }

    private LinearLayout.LayoutParams createOrbLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Places the island in the center of the screen during initial boot/login connection.
     */
    public void setupInitialHeroState() {
        isHeroCentered = true;
        post(() -> {
            int parentHeight = ((View) getParent()).getHeight();
            if (parentHeight == 0) {
                parentHeight = getResources().getDisplayMetrics().heightPixels;
            }
            float targetCenterY = (parentHeight / 2f) - (dp(IDLE_HEIGHT_DP) / 2f) - dp(6);
            setTranslationY(targetCenterY);
            setScaleX(1.15f);
            setScaleY(1.15f);
            setLoading(true);
        });
    }

    /**
     * Glides the island from center up to its normal top status-bar position.
     */
    public void flyToTop() {
        if (!isHeroCentered) return;
        isHeroCentered = false;

        if (flyAnimator != null && flyAnimator.isRunning()) {
            flyAnimator.cancel();
        }

        animate().scaleX(1.0f).scaleY(1.0f).setDuration(480).setInterpolator(new DecelerateInterpolator()).start();

        flyAnimator = ObjectAnimator.ofFloat(this, "translationY", getTranslationY(), 0f);
        flyAnimator.setDuration(520);
        flyAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        flyAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setTranslationY(0f);
                stopLoadingAnimationGracefully();
            }
        });
        flyAnimator.start();
    }

    public void setLoading(boolean loading) {
        if (this.isLoading == loading) return;

        if (pendingExitRunnable != null) {
            handler.removeCallbacks(pendingExitRunnable);
            pendingExitRunnable = null;
        }

        if (loading) {
            this.isLoading = true;
            this.loadStartTime = System.currentTimeMillis();
            startLoadingAnimation();
        } else {
            if (isHeroCentered) {
                flyToTop();
                return;
            }
            long elapsed = System.currentTimeMillis() - loadStartTime;
            if (elapsed < MIN_DISPLAY_MS) {
                pendingExitRunnable = this::stopLoadingAnimationGracefully;
                handler.postDelayed(pendingExitRunnable, MIN_DISPLAY_MS - elapsed);
            } else {
                stopLoadingAnimationGracefully();
            }
        }
    }

    private void startLoadingAnimation() {
        animateWidth(dp(ACTIVE_WIDTH_DP), new OvershootInterpolator(1.2f), 320);

        idleContainer.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(120).withEndAction(() -> {
            idleContainer.setVisibility(GONE);
            activeContainer.setScaleX(0.85f);
            activeContainer.setScaleY(0.85f);
            activeContainer.setVisibility(VISIBLE);
            activeContainer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        }).start();

        ObjectAnimator a1 = createOrbPulse(dot1, 0);
        ObjectAnimator a2 = createOrbPulse(dot2, 160);
        ObjectAnimator a3 = createOrbPulse(dot3, 320);

        if (waveAnimatorSet != null) waveAnimatorSet.cancel();
        waveAnimatorSet = new AnimatorSet();
        waveAnimatorSet.playTogether(a1, a2, a3);
        waveAnimatorSet.start();

        if (beamAnimator != null) beamAnimator.cancel();
        beamAnimator = ObjectAnimator.ofFloat(shimmerBeam, "translationX", -dp(45), dp(110));
        beamAnimator.setDuration(900);
        beamAnimator.setRepeatCount(ValueAnimator.INFINITE);
        beamAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        beamAnimator.start();
    }

    private ObjectAnimator createOrbPulse(View orb, long startDelay) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(orb, "scaleY", 0.6f, 1.4f, 0.6f);
        anim.setDuration(700);
        anim.setStartDelay(startDelay);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        return anim;
    }

    private void stopLoadingAnimationGracefully() {
        this.isLoading = false;

        animateWidth(dp(IDLE_WIDTH_DP), new DecelerateInterpolator(1.5f), 280);

        activeContainer.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).withEndAction(() -> {
            activeContainer.setVisibility(GONE);

            if (waveAnimatorSet != null) {
                waveAnimatorSet.cancel();
                waveAnimatorSet = null;
            }
            if (beamAnimator != null) {
                beamAnimator.cancel();
                beamAnimator = null;
            }

            idleContainer.setScaleX(0.85f);
            idleContainer.setScaleY(0.85f);
            idleContainer.setVisibility(VISIBLE);
            idleContainer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        }).start();
    }

    private void animateWidth(int targetWidth, android.view.animation.Interpolator interpolator, long duration) {
        if (widthAnimator != null && widthAnimator.isRunning()) {
            widthAnimator.cancel();
        }

        int currentWidth = getWidth() > 0 ? getWidth() : dp(IDLE_WIDTH_DP);
        widthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth);
        widthAnimator.setDuration(duration);
        widthAnimator.setInterpolator(interpolator);
        widthAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null) {
                lp.width = (int) animation.getAnimatedValue();
                lp.height = dp(IDLE_HEIGHT_DP);
                setLayoutParams(lp);
            }
        });
        widthAnimator.start();
    }
}