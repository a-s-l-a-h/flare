package com.example.flare_android_client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

/**
 * Full-screen overlay shown during Flare screen-to-screen navigation.
 *
 * Shows a Lottie animation while the new screen loads from the server.
 * The old screen stays visible underneath during the transition so there
 * is no jarring blank flash — the overlay just fades in on top.
 *
 * USAGE:
 *   overlay.show();                    // call immediately on navigate
 *   overlay.hide();                    // call when init payload arrives
 *   overlay.showError("msg", retry);   // call on connection error
 */
public class TransitionOverlayView extends FrameLayout {

    private static final String TAG = "FlareTransition";

    // Minimum time the overlay stays visible even if the server responds instantly.
    // Prevents a flash of the overlay for fast connections.
    private static final long MIN_SHOW_MS = 200L;

    // After this long without a server response, we consider it a connection problem.
    // The overlay stays up; a popup appears asking the user to retry.
    private static final long TIMEOUT_MS = 8_000L;

    private final LottieAnimationView lottieView;
    private final View errorCard;
    private final android.widget.TextView tvErrorMessage;
    private final android.widget.Button btnRetry;
    private final android.widget.Button btnDismiss;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long showStartMs = 0;
    private Runnable timeoutRunnable = null;
    private Runnable onRetry = null;
    private boolean visible = false;

    public TransitionOverlayView(Context context) {
        this(context, null);
    }

    public TransitionOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TransitionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_transition_overlay, this, true);

        lottieView    = findViewById(R.id.lottie_transition);
        errorCard     = findViewById(R.id.card_error);
        tvErrorMessage = findViewById(R.id.tv_transition_error);
        btnRetry      = findViewById(R.id.btn_retry);
        btnDismiss    = findViewById(R.id.btn_dismiss);

        // Loop the Lottie animation while visible
        lottieView.setRepeatCount(LottieDrawable.INFINITE);

        setVisibility(View.GONE);
        setClickable(true); // consume all touch events while overlay is up
        setFocusable(true);
    }

    /**
     * Show the transition overlay immediately.
     * The Lottie animation starts playing.
     * A timeout is armed — if hide() isn't called within TIMEOUT_MS, showError() fires.
     *
     * @param onRetryAction  Runnable to call if user taps "Retry". Pass null to hide retry button.
     */
    public void show(Runnable onRetryAction) {
        this.onRetry = onRetryAction;
        showStartMs = System.currentTimeMillis();
        visible = true;

        errorCard.setVisibility(View.GONE);
        lottieView.setVisibility(View.VISIBLE);
        lottieView.playAnimation();

        // Fade in
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(120);
        fadeIn.setFillAfter(true);
        startAnimation(fadeIn);
        setVisibility(View.VISIBLE);

        // Arm timeout
        cancelTimeout();
        timeoutRunnable = () -> {
            Log.w(TAG, "Transition timeout — showing error popup");
            showError("Connection problem. Please check your network.", onRetryAction);
        };
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        Log.d(TAG, "show()");
    }

    /**
     * Hide the overlay.
     * Respects MIN_SHOW_MS so the animation doesn't flash if the server is very fast.
     */
    public void hide() {
        if (!visible) return;
        cancelTimeout();

        long elapsed = System.currentTimeMillis() - showStartMs;
        long remaining = MIN_SHOW_MS - elapsed;

        if (remaining > 0) {
            handler.postDelayed(this::doHide, remaining);
        } else {
            doHide();
        }
    }

    /**
     * Show an error state inside the overlay (without hiding it).
     * The Lottie animation is swapped for an error card with a message and retry button.
     *
     * @param message       User-facing error message.
     * @param onRetryAction Called when user taps Retry. If null, retry button is hidden.
     */
    public void showError(String message, Runnable onRetryAction) {
        cancelTimeout();
        this.onRetry = onRetryAction;
        visible = true;
        setVisibility(View.VISIBLE);

        lottieView.cancelAnimation();
        lottieView.setVisibility(View.GONE);

        tvErrorMessage.setText(message);
        errorCard.setVisibility(View.VISIBLE);

        if (onRetryAction != null) {
            btnRetry.setVisibility(View.VISIBLE);
            btnRetry.setOnClickListener(v -> {
                errorCard.setVisibility(View.GONE);
                lottieView.setVisibility(View.VISIBLE);
                lottieView.playAnimation();
                // Re-arm timeout for the retry attempt
                cancelTimeout();
                timeoutRunnable = () -> showError(message, onRetryAction);
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS);
                onRetryAction.run();
            });
        } else {
            btnRetry.setVisibility(View.GONE);
        }

        btnDismiss.setOnClickListener(v -> doHide());

        Log.d(TAG, "showError: " + message);
    }

    public boolean isVisible() {
        return visible;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void doHide() {
        visible = false;
        lottieView.cancelAnimation();
        cancelTimeout();

        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(150);
        fadeOut.setFillAfter(true);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                setVisibility(View.GONE);
                clearAnimation();
            }
        });
        startAnimation(fadeOut);

        Log.d(TAG, "hide()");
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }
}