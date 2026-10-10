/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import junit.framework.TestCase;

public class AodWidgetAppearanceTest extends TestCase {
    public void testOriginalColoursAndAlphaArePreservedAtFullBrightness() {
        assertColour(100, 100, new float[]{20, 100, 250, 90}, new float[]{20, 100, 250, 90});
    }

    public void testDefaultGreyMatchesPreviousThirtyFivePercentTransform() {
        float grey = (20 * .213f + 100 * .715f + 250 * .072f) * .35f;
        assertColour(35, 0, new float[]{20, 100, 250, 90}, new float[]{grey, grey, grey, 90});
    }

    public void testBrightnessDoesNotDimAlphaOrLiftBlack() {
        assertColour(5, 40, new float[]{0, 0, 0, 255}, new float[]{0, 0, 0, 255});
        assertColour(50, 100, new float[]{100, 200, 40, 0}, new float[]{50, 100, 20, 0});
    }

    public void testCorruptValuesAreClamped() {
        assertColour(500, 500, new float[]{20, 100, 250, 90}, new float[]{20, 100, 250, 90});
        float grey = (20 * .213f + 100 * .715f + 250 * .072f) * .05f;
        assertColour(-1, -1, new float[]{20, 100, 250, 90}, new float[]{grey, grey, grey, 90});
    }

    public void testBrightnessMatrixKeepsHueAndAlpha() {
        float[] matrix = AodWidgetAppearance.brightnessMatrix(40);
        float[] input = {20, 100, 250, 90};
        float[] expected = {8, 40, 100, 90};
        for (int row = 0; row < 4; row++) {
            float actual = matrix[row * 5 + 4];
            for (int col = 0; col < 4; col++) actual += matrix[row * 5 + col] * input[col];
            assertEquals(expected[row], actual, .001f);
        }
    }

    public void testQualityDimensionsAreBoundedAndHandleOddSizes() {
        assertEquals(451, AodWidgetAppearance.bitmapDimension(902, false));
        assertEquals(902, AodWidgetAppearance.bitmapDimension(902, true));
        assertEquals(425, AodWidgetAppearance.bitmapDimension(851, false));
        assertEquals(1, AodWidgetAppearance.bitmapDimension(1, false));
        assertEquals(1, AodWidgetAppearance.bitmapDimension(0, true));
    }

    private void assertColour(int brightness, int saturation, float[] input, float[] expected) {
        float[] matrix = AodWidgetAppearance.colorMatrix(brightness, saturation);
        for (int row = 0; row < 4; row++) {
            float actual = matrix[row * 5 + 4];
            for (int col = 0; col < 4; col++) actual += matrix[row * 5 + col] * input[col];
            assertEquals(expected[row], actual, .001f);
        }
    }
}
