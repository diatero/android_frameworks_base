/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

/** Percentage-based layouts adapt independently to portrait and landscape viewports. */
public final class AodDisplayLayoutGeometry {
    private AodDisplayLayoutGeometry() {}
    public static final class Rect {
        public final float left, top, width, height;
        Rect(float left, float top, float width, float height) {
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
    }
    public static Rect resolve(float width, float height, int x, int y, int w, int h) {
        float inset = Math.min(16, Math.max(0, width / 10));
        float top = Math.min(32, Math.max(0, height / 10));
        float usableWidth = Math.max(1, width - inset * 2);
        float usableHeight = Math.max(1, height - top);
        float boxWidth = usableWidth * clamp(w, 5, 100) / 100f;
        float boxHeight = usableHeight * clamp(h, 5, 100) / 100f;
        return new Rect(inset + (usableWidth - boxWidth) * clamp(x, 0, 100) / 100f,
                top + (usableHeight - boxHeight) * clamp(y, 0, 100) / 100f, boxWidth, boxHeight);
    }
    /** Bound actual AOD movement to the viewport without reducing the editable region. */
    public static float boundedOffset(float origin, float size, float viewport, float offset) {
        if (viewport <= 0 || size <= 0) return 0;
        float position = Math.max(0, Math.min(Math.max(0, viewport - size), origin + offset));
        return position - origin;
    }
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    public static int position(float location, float start, float travel, int fallback) {
        return travel <= 0 ? fallback : clamp(Math.round((location - start) * 100 / travel), 0, 100);
    }
}
