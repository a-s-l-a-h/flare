package dev.flareframework.extensions.builtin.panes.placeholder_pane;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.flareframework.client.flare.nativepane.FlareNativePaneContext;
import dev.flareframework.client.flare.nativepane.FlareNativePaneProvider;
import org.json.JSONObject;

/**
 * Deliberately trivial — no DivKit imports, no third-party SDK. Its only
 * job is to prove the createView/bindView/release pipeline end-to-end
 * before anyone builds a real (map/camera/chart) pane on top of it.
 */
public class PlaceholderPaneProvider implements FlareNativePaneProvider {
    public static final String ID = "placeholder_pane";

    @Override
    public String id() { return ID; }

    @Override
    public View createView(Context context, JSONObject initialProps, FlareNativePaneContext paneContext) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(0xFF2D3436);
        layout.setPadding(32, 32, 32, 32);

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setTag("label_title");

        layout.setOnClickListener(v -> {
            paneContext.setVariable("local_placeholder_tapped", true);
            paneContext.fireAction("placeholder_pane_click", new JSONObject());
        });

        layout.addView(title);
        bindView(layout, initialProps, paneContext);
        return layout;
    }

    @Override
    public void bindView(View view, JSONObject props, FlareNativePaneContext paneContext) {
        if (!(view instanceof LinearLayout)) return;
        TextView title = view.findViewWithTag("label_title");
        if (title != null) {
            title.setText(props.optString("title", "Native Placeholder Pane"));
        }
    }

    @Override
    public void release(View view) {
        if (view instanceof LinearLayout) {
            ((LinearLayout) view).removeAllViews();
        }
    }
}