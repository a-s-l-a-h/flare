package com.example.flare_android_client;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FlareMain";

    private static final String PREF_FILE    = "flare_prefs";
    private static final String PREF_URL_KEY = "last_http_url";

    private EditText etServerUrl;
    private Button   btnConnect;
    private TextView tvHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerUrl = findViewById(R.id.et_server_url);
        btnConnect  = findViewById(R.id.btn_connect);
        tvHint      = findViewById(R.id.tv_hint);

        // Restore last used URL (Purely for developer convenience, not UI caching)
        SharedPreferences prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        String savedUrl = prefs.getString(PREF_URL_KEY, "http://10.0.2.2:4000/");
        etServerUrl.setText(savedUrl);

        tvHint.setText(
                "🔧 TESTING MODE\n\n" +
                        "Enter your Flare HTTP Endpoint.\n\n" +
                        "The app will automatically extract the path to find the entry screen, and connect to the /socket endpoint.\n\n" +
                        "Examples:\n" +
                        "  http://...:4000/          → (Welcome)\n" +
                        "  http://...:4000/profile   → (Profile)\n" +
                        "  http://...:4000/cart      → (Cart)"
        );

        btnConnect.setOnClickListener(v -> onConnectClicked());
    }

    private void onConnectClicked() {
        String urlString = etServerUrl.getText().toString().trim();

        if (TextUtils.isEmpty(urlString)) {
            Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save URL for the next time the dev opens the app
        getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                .edit()
                .putString(PREF_URL_KEY, urlString)
                .apply();

        try {
            Uri uri = Uri.parse(urlString);

            String wsScheme = "https".equals(uri.getScheme()) ? "wss" : "ws";
            String host = uri.getHost();
            int port = uri.getPort();
            String portStr = port != -1 ? ":" + port : "";
            String wsUrl = wsScheme + "://" + host + portStr + "/socket";

            // Dynamically extract screen name from URL
            String path = uri.getPath();
            String entryScreen = "welcome"; // Default
            if (path != null && path.length() > 1) {
                entryScreen = path.substring(1); // Removes the "/"
            }

            // Check for existing token
            String storedToken = getSharedPreferences(PREF_FILE, MODE_PRIVATE).getString("flare_auth_token", null);

            if (storedToken != null) {
                // Already logged in! Skip directly to Flare.
                FlareClientActivity.launch(this, wsUrl, entryScreen);
            } else {
                // Needs to log in. Launch LoginActivity with our URLs.
                Intent intent = new Intent(this, LoginActivity.class);
                intent.putExtra("base_http_url", urlString.replaceAll("/+$", "")); // trim trailing slash
                intent.putExtra("ws_url", wsUrl);
                intent.putExtra("entry_screen", entryScreen);
                startActivity(intent);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse URL", e);
            Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show();
        }
    }
}