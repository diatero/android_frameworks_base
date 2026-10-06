/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

/** Coalesce content updates without postponing existing AOD minute ticks or the first frame. */
public final class AodWidgetRenderPolicy {
    private AodWidgetRenderPolicy() {}

    public static long delayMillis(long now, long minute, long lastRender, Long lastMinute,
            boolean dirty, boolean force) {
        if (force || lastMinute == null || minute != lastMinute) return 0;
        if (!dirty) return -1;
        // No new alarm: the existing AOD minute tick remains the only periodic wake source.
        return Math.max(250, 5_000 - (now - lastRender));
    }
}
