package dev.flareframework.client.flare.nativepane;

import android.content.Context;
import android.view.View;
import org.json.JSONObject;

/**
 * Interface every native pane implements. Lives in flareclient ONLY to
 * define the contract — concrete implementations never live here, exactly
 * like FlareClientTask/FlareClientPlugin. Registered explicitly by the
 * host app (or a builtin/community extension module) at startup.
 */
public interface FlareNativePaneProvider {
    String id();
    View createView(Context context, JSONObject initialProps, FlareNativePaneContext paneContext);
    void bindView(View view, JSONObject props, FlareNativePaneContext paneContext);
    void release(View view);
    default void register() { FlareNativePaneRegistry.register(this); }
}