/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

/** Shared by the layout editor and Keyguard. Coordinates and sizes are in dp. */
public final class AodWidgetGeometry {
    private AodWidgetGeometry() {}

    public static final class Layout {
        public final float left, top, width, height, minLeft, minTop, travelX, travelY;

        private Layout(float left, float top, float width, float height,
                float minLeft, float minTop, float travelX, float travelY) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.minLeft = minLeft;
            this.minTop = minTop;
            this.travelX = travelX;
            this.travelY = travelY;
        }
    }

    public static Layout resolve(float viewportWidth, float viewportHeight,
            int width, int height, int xPercent, int yPercent) {
        float minLeft = Math.min(32, viewportWidth / 4);
        // Keep widgets within the screen while allowing placement in the bottom entry area.
        float bottom = Math.max(1, viewportHeight);
        float minTop = Math.min(Math.min(300, viewportHeight * .4f), Math.max(0, bottom - 1));
        float actualWidth = Math.max(1, Math.min(Math.max(120, Math.min(600, width)),
                viewportWidth - minLeft * 2));
        float actualHeight = Math.max(1, Math.min(Math.max(64, Math.min(320, height)),
                bottom - minTop));
        float travelX = Math.max(0, viewportWidth - minLeft * 2 - actualWidth);
        float travelY = Math.max(0, bottom - minTop - actualHeight);
        return new Layout(minLeft + travelX * clampPercent(xPercent) / 100f,
                minTop + travelY * clampPercent(yPercent) / 100f,
                actualWidth, actualHeight, minLeft, minTop, travelX, travelY);
    }

    public static int positionPercent(float coordinate, float minimum, float travel, int fallback) {
        return travel <= 0 ? clampPercent(fallback)
                : clampPercent(Math.round((coordinate - minimum) * 100 / travel));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
