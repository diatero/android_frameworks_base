/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.content.Context;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Four independent, atomically saved profiles. Missing/corrupt data retains the native layout. */
public final class AodDisplayLayoutStore {
    public static final String KEY = "aod_display_layouts";
    public static final String ROTATION = "aod_display_rotation";
    public static final int TIME = 0, DATE = 1, NOTIFICATIONS = 2, WIDGET = 3, INDICATION = 4;
    public static final int MODULE_COUNT = 5;
    private AodDisplayLayoutStore() {}

    public static final class Box {
        public boolean visible = true;
        public int x, y, width, height;
        Box(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    public static final class Profile {
        public boolean enabled;
        public final Box[] boxes;
        public final Map<Integer, Box> widgets = new LinkedHashMap<>();
        Profile(Box[] boxes) { this.boxes = boxes; }
    }
    public static final class Layouts {
        public final Profile[] profiles = new Profile[4];
        public Layouts() { for (int i = 0; i < 4; i++) profiles[i] = defaults(i); }
    }
    public static Profile defaults(int index) {
        boolean landscape = index % 2 != 0;
        boolean aod = index < 2;
        return new Profile(landscape ? new Box[]{
                new Box(0, 0, 44, 27), new Box(0, 33, 44, 12),
                new Box(0, 83, 44, aod ? 12 : 50), new Box(100, 0, 48, 88),
                new Box(50, 100, 90, 8),
        } : new Box[]{
                new Box(0, 3, 90, 22), new Box(0, 27, 90, 8),
                new Box(0, 46, 95, aod ? 8 : 22), new Box(50, 88, 95, 30),
                new Box(50, 100, 90, 6),
        });
    }
    public static int index(boolean aod, int width, int height) {
        return (aod ? 0 : 2) + (width > height ? 1 : 0);
    }
    public static Layouts load(Context context) {
        String saved = Settings.Secure.getString(context.getContentResolver(), KEY);
        Layouts layouts = decode(saved);
        // Version 1 had one widget and a separate dp geometry editor.
        boolean legacy = saved == null;
        try { legacy |= saved != null && new JSONObject(saved).optInt("version") == 1; }
        catch (JSONException ignored) { }
        if (legacy) {
            var metrics = context.getResources().getDisplayMetrics();
            float shortSide = Math.min(metrics.widthPixels, metrics.heightPixels) / metrics.density;
            float longSide = Math.max(metrics.widthPixels, metrics.heightPixels) / metrics.density;
            int id = AodWidgetStore.widgetId(context);
            for (int i = 0; i < 4; i++) {
                Profile p = layouts.profiles[i];
                if (p.enabled || id < 0) continue;
                float width = i % 2 == 0 ? shortSide : longSide;
                float height = i % 2 == 0 ? longSide : shortSide;
                var r = AodWidgetGeometry.resolve(width, height, AodWidgetStore.width(context),
                        AodWidgetStore.height(context), AodWidgetStore.positionX(context),
                        AodWidgetStore.positionY(context));
                var full = AodDisplayLayoutGeometry.resolve(width, height, 0, 0, 100, 100);
                Box b = new Box(AodDisplayLayoutGeometry.position(r.left, full.left,
                        full.width - r.width, 50), AodDisplayLayoutGeometry.position(r.top, full.top,
                        full.height - r.height, 100),
                        AodDisplayLayoutGeometry.clamp(Math.round(r.width * 100 / full.width), 5, 100),
                        AodDisplayLayoutGeometry.clamp(Math.round(r.height * 100 / full.height), 5, 100));
                p.widgets.put(id, b);
            }
        }
        return layouts;
    }
    public static Layouts decode(String value) {
        Layouts result = new Layouts();
        if (value == null) return result;
        try {
            JSONObject object = new JSONObject(value);
            int version = object.optInt("version", 0);
            if (version != 1 && version != 2) return result;
            JSONArray profiles = object.getJSONArray("profiles");
            for (int i = 0; i < Math.min(4, profiles.length()); i++) {
                JSONObject saved = profiles.optJSONObject(i);
                if (saved == null) continue;
                Profile profile = result.profiles[i];
                JSONArray boxes = saved.optJSONArray("boxes");
                // Do not enable an incomplete profile after corrupted or older data.
                if (boxes == null || boxes.length() != MODULE_COUNT) continue;
                profile.enabled = saved.optBoolean("enabled", false);
                for (int j = 0; j < MODULE_COUNT; j++) {
                    JSONArray values = boxes.optJSONArray(j);
                    if (values == null || values.length() != 5) { profile.enabled = false; continue; }
                    Box box = profile.boxes[j];
                    box.visible = values.optInt(0, 1) != 0;
                    box.x = AodDisplayLayoutGeometry.clamp(values.optInt(1, box.x), 0, 100);
                    box.y = AodDisplayLayoutGeometry.clamp(values.optInt(2, box.y), 0, 100);
                    box.width = AodDisplayLayoutGeometry.clamp(values.optInt(3, box.width), 5, 100);
                    box.height = AodDisplayLayoutGeometry.clamp(values.optInt(4, box.height), 5, 100);
                }
                JSONObject widgets = saved.optJSONObject("widgets");
                if (widgets != null) {
                    var keys = widgets.keys();
                    while (keys.hasNext() && profile.widgets.size() < AodWidgetStore.MAX_WIDGETS) {
                        String key = keys.next();
                        try {
                            int id = Integer.parseInt(key);
                            JSONArray values = widgets.optJSONArray(key);
                            if (id < 0 || values == null || values.length() != 5) continue;
                            Box b = new Box(AodDisplayLayoutGeometry.clamp(values.optInt(1, 50), 0, 100),
                                    AodDisplayLayoutGeometry.clamp(values.optInt(2, 50), 0, 100),
                                    AodDisplayLayoutGeometry.clamp(values.optInt(3, 50), 5, 100),
                                    AodDisplayLayoutGeometry.clamp(values.optInt(4, 20), 5, 100));
                            b.visible = values.optInt(0, 1) != 0;
                            profile.widgets.put(id, b);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
        } catch (JSONException ignored) { return new Layouts(); }
        return result;
    }
    public static String encode(Layouts layouts) {
        try {
            JSONArray profiles = new JSONArray();
            for (Profile profile : layouts.profiles) {
                JSONArray boxes = new JSONArray();
                for (Box box : profile.boxes) boxes.put(new JSONArray()
                        .put(box.visible ? 1 : 0).put(box.x).put(box.y)
                        .put(box.width).put(box.height));
                JSONObject widgets = new JSONObject();
                for (var entry : profile.widgets.entrySet()) {
                    Box box = entry.getValue();
                    widgets.put(Integer.toString(entry.getKey()), new JSONArray()
                            .put(box.visible ? 1 : 0).put(box.x).put(box.y)
                            .put(box.width).put(box.height));
                }
                profiles.put(new JSONObject().put("enabled", profile.enabled)
                        .put("boxes", boxes).put("widgets", widgets));
            }
            return new JSONObject().put("version", 2).put("profiles", profiles).toString();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public static void save(Context context, Layouts layouts) {
        Settings.Secure.putString(context.getContentResolver(), KEY, encode(layouts));
    }
    /** Both the editor and actual surfaces resolve each instance through this function. */
    public static Box widgetBox(Profile profile, int id, int ordinal) {
        Box saved = profile.widgets.get(id);
        if (saved != null) return saved;
        Box legacy = profile.boxes[WIDGET];
        Box box = new Box(legacy.x, ordinal == 0 ? legacy.y : Math.max(0, legacy.y - ordinal * 25),
                ordinal == 0 ? legacy.width : Math.min(legacy.width, 70),
                ordinal == 0 ? legacy.height : Math.min(legacy.height, 22));
        box.visible = legacy.visible;
        return box;
    }

    public static Box editWidgetBox(Profile profile, int id, int ordinal) {
        Box box = widgetBox(profile, id, ordinal);
        profile.widgets.put(id, box);
        return box;
    }

    public static int rotation(Context context) {
        return AodDisplayLayoutGeometry.clamp(Settings.Secure.getInt(
                context.getContentResolver(), ROTATION, 0), 0, 3);
    }
}
