/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

/** Shared AOD colour transform. Black stays black and alpha stays unchanged. */
public final class AodWidgetAppearance {
    private AodWidgetAppearance() {}

    public static float[] colorMatrix(int brightness, int saturation) {
        float b = Math.max(5, Math.min(100, brightness)) / 100f;
        float s = Math.max(0, Math.min(100, saturation)) / 100f;
        float r = .213f * (1 - s) * b;
        float g = .715f * (1 - s) * b;
        float blue = .072f * (1 - s) * b;
        return new float[]{
                r + s * b, g, blue, 0, 0,
                r, g + s * b, blue, 0, 0,
                r, g, blue + s * b, 0, 0,
                0, 0, 0, 1, 0,
        };
    }

    /** Brightness only; used for the whole AOD content so modules share one setting. */
    public static float[] brightnessMatrix(int brightness) {
        return colorMatrix(brightness, 100);
    }

    public static int bitmapDimension(int dimension, boolean highQuality) {
        return Math.max(1, highQuality ? dimension : dimension / 2);
    }
}
