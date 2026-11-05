package com.gomocalc.app;

import android.content.pm.ActivityInfo;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Applies the "布局方式" setting to the real screen orientation. */
@CapacitorPlugin(name = "AppOrientation")
public class AppOrientationPlugin extends Plugin {

    /** @param call {@code mode}: {@code landscape}, {@code portrait} or {@code auto}. */
    @PluginMethod
    public void setOrientation(PluginCall call) {
        String mode = call.getString("mode", "auto");
        final int orientation;
        if ("landscape".equals(mode)) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else if ("portrait".equals(mode)) {
            orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        getActivity().runOnUiThread(() -> {
            getActivity().setRequestedOrientation(orientation);
            call.resolve();
        });
    }
}
