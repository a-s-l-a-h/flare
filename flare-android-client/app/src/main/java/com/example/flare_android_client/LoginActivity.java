package com.example.flare_android_client;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "FlareLogin";
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private String baseHttpUrl;
    private String wsUrl;
    private String entryScreen;

    private EditText etEmail, etPassword;
    private Button btnLogin, btnGuest;
    private TextView tvError;
    private ProgressBar pbLoading;

    private OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        baseHttpUrl = getIntent().getStringExtra("base_http_url");
        wsUrl = getIntent().getStringExtra("ws_url");
        entryScreen = getIntent().getStringExtra("entry_screen");

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        btnGuest = findViewById(R.id.btn_guest);
        tvError = findViewById(R.id.tv_error);
        pbLoading = findViewById(R.id.pb_loading);

        btnLogin.setOnClickListener(v -> handleLogin());
        btnGuest.setOnClickListener(v -> handleGuest());
    }

    private void handleLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            showError("Please enter email and password");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            payload.put("password", password);
            makeAuthRequest("/auth/login", payload);
        } catch (Exception e) {
            showError("Failed to build request");
        }
    }

    private void handleGuest() {
        makeAuthRequest("/auth/guest", new JSONObject());
    }

    private void makeAuthRequest(String path, JSONObject payload) {
        setLoading(true);
        String url = baseHttpUrl + path;
        Log.d(TAG, "POST " + url);

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Network error: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String responseData = response.body() != null ? response.body().string() : "";

                runOnUiThread(() -> {
                    setLoading(false);
                    try {
                        JSONObject json = new JSONObject(responseData);
                        if (!response.isSuccessful()) {
                            showError(json.optString("error", "Authentication failed"));
                        } else {
                            String token = json.optString("token");
                            if (!TextUtils.isEmpty(token)) {
                                // Success! Pass token to FlareClientActivity
                                FlareClientActivity.launch(LoginActivity.this, wsUrl, entryScreen, token);
                                finish(); // Close LoginActivity
                            } else {
                                showError("Server returned empty token");
                            }
                        }
                    } catch (Exception e) {
                        showError("Invalid server response");
                    }
                });
            }
        });
    }

    private void setLoading(boolean isLoading) {
        pbLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!isLoading);
        btnGuest.setEnabled(!isLoading);
        tvError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}