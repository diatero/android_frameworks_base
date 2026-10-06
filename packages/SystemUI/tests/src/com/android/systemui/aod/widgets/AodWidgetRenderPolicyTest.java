/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AodWidgetRenderPolicyTest {
    @Test public void firstFrameAndMinuteTicksAreImmediate() {
        assertEquals(0, AodWidgetRenderPolicy.delayMillis(100, 1, 0, null, true, false));
        assertEquals(0, AodWidgetRenderPolicy.delayMillis(100, 2, 90, 1L, false, false));
        // A corrected wall clock must not strand the previous minute's snapshot.
        assertEquals(0, AodWidgetRenderPolicy.delayMillis(100, 0, 90, 1L, false, false));
    }

    @Test public void duplicateTicksDoNotRedrawUnchangedContent() {
        assertEquals(-1, AodWidgetRenderPolicy.delayMillis(100, 1, 90, 1L, false, false));
    }

    @Test public void asyncContentIsCoalescedButCanUpdateWithinTheSameMinute() {
        assertEquals(4_000, AodWidgetRenderPolicy.delayMillis(2_000, 1, 1_000, 1L, true, false));
        assertEquals(250, AodWidgetRenderPolicy.delayMillis(7_000, 1, 1_000, 1L, true, false));
    }

    @Test public void layoutAndTimeConfigurationChangesBypassContentThrottle() {
        assertEquals(0, AodWidgetRenderPolicy.delayMillis(2_000, 1, 1_000, 1L, true, true));
    }
}
