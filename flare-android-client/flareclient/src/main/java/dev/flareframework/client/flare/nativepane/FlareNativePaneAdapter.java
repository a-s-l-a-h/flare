package dev.flareframework.client.flare.nativepane;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.yandex.div.core.DivCustomContainerViewAdapter;
import com.yandex.div.core.DivPreloader;
import com.yandex.div.core.state.DivStatePath;
import com.yandex.div.core.view2.Div2View;
import com.yandex.div.json.expressions.ExpressionResolver;
import com.yandex.div2.DivCustom;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ONE place flareclient talks to DivKit's real custom-view API
 * (DivCustomContainerViewAdapter — confirmed against the actual DivKit
 * 32.52.0 source, not guessed).
 *
 * CRITICAL SAFETY NOTE: DivKit's own DivCustomBinder.kt calls
 * createView()/bindView() DIRECTLY, with NO try/catch of its own
 * (confirmed by reading DivCustomBinder.kt). That means THIS adapter is
 * the only thing standing between a broken third-party pane and a full
 * app crash / DivKit render-thread crash. Every single call into a
 * FlareNativePaneProvider is wrapped in try/catch{Throwable} — nothing
 * in this file may ever let an exception escape upward into DivKit.
 *
 * Field access is now DIRECT, not reflected — confirmed from the
 * generated DivCustom.kt: `customProps` and `customType` are
 * `@JvmField`, so they are ordinary public fields in Java. `id` comes
 * from the DivBase Kotlin interface, so in Java it's `div.getId()`.
 *
 * This class contains NO logic specific to any one pane id — pure
 * routing, exactly like FlareClientTaskEngine / FlareClientPluginEngine.
 */
public final class FlareNativePaneAdapter implements DivCustomContainerViewAdapter {
    private static final String TAG = "FlareNativePaneAdapter";
    private final FlareNativePaneContext paneContext;

        // Tracks which provider created each live View, so bindView/release
    // route back to the exact same provider even if the registry changes
    // underneath (e.g. hot-swap during development).
    private final Map<View, FlareNativePaneProvider> activeProviders = new ConcurrentHashMap<>();

    public FlareNativePaneAdapter(FlareNativePaneContext context) {
        this.paneContext = context;
    }

    @Override
    public boolean isCustomTypeSupported(String customType) {
        try {
            return FlareNativePaneRegistry.get(customType) != null;
        } catch (Throwable t) {
            // Even a registry lookup must never crash a DivKit render pass.
            Log.e(TAG, "isCustomTypeSupported() crash intercepted for '" + customType + "'", t);
            return false;
        }
    }

    @Override
    public DivPreloader.PreloadReference preload(DivCustom div, DivPreloader.Callback callback) {
        // No pane currently needs asset preloading.
        //
        // NOTE: PreloadReference.EMPTY is NOT reachable from Java as a
        // plain static field — confirmed from DivPreloader.kt: unlike
        // PreloadFilter's ONLY_PRELOAD_REQUIRED_FILTER/PRELOAD_ALL_FILTER
        // (both @JvmField), PreloadReference.EMPTY has no @JvmField, so
        // Kotlin only exposes it via PreloadReference.Companion.getEMPTY().
        // Since PreloadReference is a `fun interface` (single abstract
        // method: cancel()), the simplest and most version-stable fix is
        // to just implement it inline with a no-op lambda instead of
        // depending on that companion-object access path at all.
        return () -> { /* nothing to cancel — no preloading was started */ };
    }

    @Override
    public View createView(DivCustom div, Div2View divView, ExpressionResolver resolver, DivStatePath path) {
        String type = div.customType; // @JvmField — real public field, never null
        FlareNativePaneProvider provider = FlareNativePaneRegistry.get(type);

        if (provider == null) {
            Log.e(TAG, "No pane registered for custom_type='" + type + "'");
            return createPlaceholder(divView.getContext(), "Missing pane: " + type);
        }

        // Nested `items` inside a custom div are NOT supported by
        // FlareNativePaneProvider in this version — warn once, loudly,
        // instead of silently dropping content with no trace.
        if (div.items != null && !div.items.isEmpty()) {
            Log.w(TAG, "custom_type='" + type + "' declares 'items' but FlareNativePaneProvider " +
                    "does not support nested Div children yet — items will be ignored.");
        }

        try {
            JSONObject props = div.customProps != null ? div.customProps : new JSONObject();
            View view = provider.createView(divView.getContext(), props, paneContext);

            if (view == null) {
                Log.e(TAG, "Pane '" + type + "' returned a null View from createView()");
                return createPlaceholder(divView.getContext(), "Null view: " + type);
            }

            // Defensive: if a misbehaving provider returns a View that's
            // already attached somewhere else, DivKit's addView() would
            // throw IllegalStateException and crash the render pass.
            // Detach it here so that can never happen.
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }

            activeProviders.put(view, provider);
            return view;
        } catch (Throwable t) {
            // Crash isolation: a bug in ANY pane's createView() must never
            // take down DivKit rendering or the host Activity.
            Log.e(TAG, "Crash intercepted in createView() for pane: " + type, t);
            return createPlaceholder(divView.getContext(), "Error in pane: " + type);
        }
    }

    @Override
    public void bindView(View view, DivCustom div, Div2View divView, ExpressionResolver resolver, DivStatePath path) {
        String type = div.customType;
        FlareNativePaneProvider provider = activeProviders.get(view);
        if (provider == null) provider = FlareNativePaneRegistry.get(type);
        if (provider == null) return;

        try {
            JSONObject props = div.customProps != null ? div.customProps : new JSONObject();
            provider.bindView(view, props, paneContext);
        } catch (Throwable t) {
            // bindView ticks frequently on rebind — log at debug, never let
            // it crash a render pass.
            Log.d(TAG, "Non-fatal bindView error for pane: " + type, t);
        }
    }

        @Override
    public void release(View view, DivCustom div) {
        String type = div.customType;
        FlareNativePaneProvider provider = activeProviders.remove(view);
        if (provider == null) provider = FlareNativePaneRegistry.get(type);

        if (provider != null) {
            try {
                provider.release(view);
            } catch (Throwable t) {
                Log.e(TAG, "Crash intercepted in release() for pane: " + type, t);
            }
        }
    }

    private View createPlaceholder(Context context, String label) {
        try {
            TextView tv = new TextView(context);
            tv.setText(label);
            tv.setTextColor(Color.RED);
            tv.setBackgroundColor(0x1AFF0000);
            tv.setPadding(24, 24, 24, 24);
            tv.setGravity(Gravity.CENTER);
            return tv;
        } catch (Throwable t) {
            // Absolute last resort — even the fallback placeholder must
            // never throw. If this somehow fails, return a bare, empty View.
            Log.e(TAG, "Failed to build placeholder view", t);
            return new View(context);
        }
    }
}