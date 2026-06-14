package com.example.flare_android_client.divkit;

import com.yandex.div.DivDataTag;
import com.yandex.div.core.Div2Context;
import com.yandex.div.core.expression.variables.DivVariableController;
import com.yandex.div.core.view2.Div2View;
import com.yandex.div.data.DivParsingEnvironment;
import com.yandex.div.json.ParsingErrorLogger;
import com.yandex.div2.DivData;

import org.json.JSONObject;

/**
 * ═══════════════════════════════════════════════════════════════
 * Converts a raw JSON object from Elixir into a native Android UI.
 * ═══════════════════════════════════════════════════════════════
 */
public class FlareDivViewFactory {

    private final Div2Context context;
    private final DivVariableController globalVarsController;

    public FlareDivViewFactory(Div2Context context, DivVariableController globalVarsController) {
        this.context = context;
        this.globalVarsController = globalVarsController;
    }

    /**
     * Takes the "layout" JSON from the server envelope and builds a Div2View.
     */
//    public Div2View createView(JSONObject layoutJson) {
//        // 1. Setup parsing environment. ASSERT logs errors to logcat.
//        DivParsingEnvironment environment = new DivParsingEnvironment(ParsingErrorLogger.ASSERT);
//
//        // NOTE: Flare sends the card directly without a 'templates' wrapper currently.
//        // If you ever add templates in Elixir, you would parse them into the environment here.
//
//        // 2. Parse the card data
//        DivData divData = com.yandex.div2.DivData.Companion.fromJson(environment, layoutJson);
//
//        // 3. Create the native Android view
//        Div2View div2View = new Div2View(context);
//
//        // 4. Attach the global variables controller (so state patches update the UI automatically)
//        // Note: In newer DivKit versions, this is passed automatically via the Div2Context,
//        // but it doesn't hurt to ensure variables are synced.
//
//        // 5. Set the data to render it
//        div2View.setData(divData, new DivDataTag("flare_screen"));
//
//        return div2View;
//    }
    public Div2View createView(JSONObject layoutJson) {
        // 1. Setup parsing environment. ASSERT logs errors to logcat.
        DivParsingEnvironment environment = new DivParsingEnvironment(ParsingErrorLogger.ASSERT);

        // 2. Extract the actual card data
        // DivData.fromJson expects the actual card object (which contains log_id and states),
        // NOT the wrapper object that has a "card" key.
        JSONObject cardJson = layoutJson;
        if (layoutJson.has("card")) {
            cardJson = layoutJson.optJSONObject("card");
        }

        // 3. Parse the card data
        com.yandex.div2.DivData divData = com.yandex.div2.DivData.Companion.fromJson(environment, cardJson);

        // 4. Create the native Android view
        Div2View div2View = new Div2View(context);

        // 5. Set the data to render it
        div2View.setData(divData, new DivDataTag("flare_screen"));

        return div2View;
    }
}