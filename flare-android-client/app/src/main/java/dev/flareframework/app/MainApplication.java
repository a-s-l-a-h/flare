package dev.flareframework.app;

import android.app.Application;

public class MainApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FlareExtensions.registerAll();
    }
}