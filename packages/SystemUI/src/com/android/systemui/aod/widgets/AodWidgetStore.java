/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.content.Context;
import android.appwidget.AppWidgetHost;
import android.provider.Settings;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Independent of the launcher's host and widget database. Owner user only for now. */
public final class AodWidgetStore {
    public static final int HOST_ID = 117;
    public static final String WIDGET_IDS = "aod_widget_ids";
    public static final int MAX_WIDGETS = 8;
    public static final String WIDGET_ID = "aod_widget_id";
    public static final String ENABLED = "aod_widget_enabled";
    public static final String SHOW_WHEN_LOCKED = "aod_widget_show_when_locked";
    public static final int WIDTH_DP = 320;
    public static final int HEIGHT_DP = 120;
    public static final String WIDTH = "aod_widget_width";
    public static final String HEIGHT = "aod_widget_height";
    public static final String POSITION_X = "aod_widget_position_x";
    public static final String POSITION_Y = "aod_widget_position_y";
    public static final int MIN_WIDTH_DP = 120;
    public static final int MAX_WIDTH_DP = 600;
    public static final int MIN_HEIGHT_DP = 64;
    public static final int MAX_HEIGHT_DP = 320;
    public static final String BRIGHTNESS = "aod_widget_brightness";
    public static final String SATURATION = "aod_widget_saturation";
    public static final String HIGH_QUALITY = "aod_widget_high_quality";
    public static final int DEFAULT_BRIGHTNESS = 35;
    public static final int DEFAULT_SATURATION = 0;

    private AodWidgetStore() {}

    // Host ID 117 has one server callback. Activity lifecycle transitions overlap:
    // the old screen must not stop the callback just acquired by its successor.
    // All callers are on the SystemUI main thread.
    private static AppWidgetHost sListeningHost;

    public static void startListening(AppWidgetHost host) {
        host.startListening();
        sListeningHost = host;
    }

    public static void stopListening(AppWidgetHost host) {
        if (sListeningHost != host) return;
        sListeningHost = null;
        host.stopListening();
    }

    public static int widgetId(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), WIDGET_ID, -1);
    }

    /** Legacy selection remains readable until the first explicit list mutation. */
    public static List<Integer> widgetIds(Context context) {
        return decodeIds(Settings.Secure.getString(context.getContentResolver(), WIDGET_IDS),
                widgetId(context));
    }

    public static List<Integer> decodeIds(String value, int legacy) {
        List<Integer> ids = new ArrayList<>();
        if (value == null) {
            if (legacy >= 0) ids.add(legacy);
            return ids;
        }
        try {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length() && ids.size() < MAX_WIDGETS; i++) {
                int id = array.optInt(i, -1);
                if (id >= 0 && !ids.contains(id)) ids.add(id);
            }
        } catch (org.json.JSONException ignored) {
            // Corrupt lists do not resurrect removed legacy widgets.
        }
        return ids;
    }

    public static void addWidget(Context context, int id) {
        List<Integer> ids = widgetIds(context);
        if (id < 0 || ids.contains(id)) return;
        if (ids.size() >= MAX_WIDGETS) throw new IllegalStateException("Widget limit reached");
        var layouts = AodDisplayLayoutStore.load(context);
        ids.add(id);
        prepareLayouts(layouts, ids);
        AodDisplayLayoutStore.save(context, layouts);
        saveIds(context, ids);
    }

    public static void removeWidget(Context context, int id) {
        List<Integer> ids = widgetIds(context);
        if (!ids.contains(id)) return;
        var layouts = AodDisplayLayoutStore.load(context);
        // Materialize before removing/reordering: another widget must not inherit this one's slot.
        prepareLayouts(layouts, ids);
        ids.remove(Integer.valueOf(id));
        for (var profile : layouts.profiles) profile.widgets.remove(id);
        AodDisplayLayoutStore.save(context, layouts);
        saveIds(context, ids);
    }

    private static void prepareLayouts(AodDisplayLayoutStore.Layouts layouts, List<Integer> ids) {
        for (var profile : layouts.profiles) {
            profile.widgets.keySet().retainAll(ids);
            for (int i = 0; i < ids.size(); i++) {
                AodDisplayLayoutStore.editWidgetBox(profile, ids.get(i), i);
            }
        }
    }

    private static void saveIds(Context context, List<Integer> ids) {
        Settings.Secure.putString(context.getContentResolver(), WIDGET_IDS,
                new JSONArray(ids).toString());
        // Retain the primary ID for older diagnostics and appearance previews.
        put(context, WIDGET_ID, ids.isEmpty() ? -1 : ids.get(0));
    }

    public static boolean enabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), ENABLED, 0) != 0;
    }

    public static boolean showWhenLocked(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), SHOW_WHEN_LOCKED, 0) != 0;
    }

    public static int width(Context context) {
        return read(context, WIDTH, WIDTH_DP, MIN_WIDTH_DP, MAX_WIDTH_DP);
    }

    public static int height(Context context) {
        return read(context, HEIGHT, HEIGHT_DP, MIN_HEIGHT_DP, MAX_HEIGHT_DP);
    }

    public static int positionX(Context context) {
        return read(context, POSITION_X, 50, 0, 100);
    }

    public static int positionY(Context context) {
        return read(context, POSITION_Y, 100, 0, 100);
    }

    public static int brightness(Context context) {
        return read(context, BRIGHTNESS, DEFAULT_BRIGHTNESS, 5, 100);
    }

    public static int saturation(Context context) {
        return read(context, SATURATION, DEFAULT_SATURATION, 0, 100);
    }

    public static boolean highQuality(Context context) {
        return read(context, HIGH_QUALITY, 0, 0, 1) != 0;
    }

    private static int read(Context context, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max,
                Settings.Secure.getInt(context.getContentResolver(), key, fallback)));
    }

    public static void put(Context context, String key, int value) {
        Settings.Secure.putInt(context.getContentResolver(), key, value);
    }
}
