package dev.flareframework.client;

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
import android.widget.ProgressBar;

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

    // After this long without a server response, we consider it a connection problem.
    // The overlay stays up; a popup appears asking the user to retry.
    private static final long TIMEOUT_MS = 8_000L;

    private final ProgressBar progressBar;
    private final View errorCard;
    private final android.widget.TextView tvErrorMessage;
    private final android.widget.Button btnRetry;
    private final android.widget.Button btnSignOut;

    // Fired when the user taps "Sign Out" on the error card. Wired once by
    // FlareClientActivity via setOnSignOutListener() — same treatment as onRetry.
    private Runnable onSignOut;

    // Fired when the error card becomes visible / is fully dismissed — lets
    // FlareClientActivity hide persistent scaffold regions that are no
    // longer actually tappable underneath this full-screen overlay, and
    // restore them once it's gone. Deliberately NOT fired by show() (the
    // ordinary loading spinner) — only by showError()/doHide().
    private Runnable onErrorShown;
    private Runnable onErrorHidden;

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
        progressBar   = findViewById(R.id.progress_transition);
        errorCard     = findViewById(R.id.card_error);
        tvErrorMessage = findViewById(R.id.tv_transition_error);
        btnRetry      = findViewById(R.id.btn_retry);
        btnSignOut    = findViewById(R.id.btn_sign_out);
        btnSignOut.setOnClickListener(v -> { if (onSignOut != null) onSignOut.run(); });

        // ProgressBar with android:indeterminate="true" spins continuously
        // on its own while visible — no play/pause/repeat calls needed.

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
        show(onRetryAction, null);
    }

    /**
     *  overload that lets the caller take over what happens when the
     * internal TIMEOUT_MS deadline fires, instead of always showing this
     * view's own generic "Connection problem" error card.
     *
     * @param onTimeoutOverride  If non-null, called INSTEAD of the built-in
     *                           error card when the timeout elapses. Pass a
     *                           no-op lambda to suppress the card entirely —
     *                           e.g. during the very first connect attempt
     *                           in FlareClientActivity, where a higher-level,
     *                           blocking "Something went wrong" dialog should
     *                           own the escalation instead of this transient
     *                           card flashing first. Pass null to keep the
     *                           old default behavior (used for every screen
     *                           after the first successful load).
     */
    public void show(Runnable onRetryAction, Runnable onTimeoutOverride) {
        this.onRetry = onRetryAction;
        showStartMs = System.currentTimeMillis();
        visible = true;

        errorCard.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        // Instant show — no fade, no artificial delay.
        setVisibility(View.VISIBLE);

        // Arm timeout
        cancelTimeout();
        timeoutRunnable = () -> {
            if (onTimeoutOverride != null) {
                Log.w(TAG, "Transition timeout — deferring to caller-supplied handler");
                onTimeoutOverride.run();
            } else {
                Log.w(TAG, "Transition timeout — showing error popup");
                showError("Connection problem. Please check your network.", onRetryAction);
            }
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
        doHide();
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

        if (onErrorShown != null) onErrorShown.run();

        progressBar.setVisibility(View.GONE);

        tvErrorMessage.setText(message);
        errorCard.setVisibility(View.VISIBLE);

        if (onRetryAction != null) {
            btnRetry.setVisibility(View.VISIBLE);
            btnRetry.setOnClickListener(v -> {
                errorCard.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
                // Re-arm timeout for the retry attempt
                cancelTimeout();
                timeoutRunnable = () -> showError(message, onRetryAction);
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS);
                onRetryAction.run();
            });
        } else {
            btnRetry.setVisibility(View.GONE);
        }

        //btnDismiss.setOnClickListener(v -> doHide());

        Log.d(TAG, "showError: " + message);
    }

    public boolean isVisible() {
        return visible;
    }
    /**
     * Registers the callback fired when the user taps "Sign Out" on the
     * error card. Call once from FlareClientActivity.onCreate().
     */
    public void setOnSignOutListener(Runnable onSignOut) {
        this.onSignOut = onSignOut;
    }

    /**
     * Registers callbacks fired when the error card appears/disappears.
     * Call once from FlareClientActivity.onCreate(), alongside setOnSignOutListener().
     */
    public void setOnErrorVisibilityListener(Runnable onErrorShown, Runnable onErrorHidden) {
        this.onErrorShown = onErrorShown;
        this.onErrorHidden = onErrorHidden;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void doHide() {
        visible = false;
        cancelTimeout();

        if (onErrorHidden != null) onErrorHidden.run();

        // Instant hide — no fade, no artificial delay.
        setVisibility(View.GONE);

        Log.d(TAG, "hide()");
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }
}