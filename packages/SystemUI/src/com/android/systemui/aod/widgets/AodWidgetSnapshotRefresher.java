/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AnalogClock;
import android.widget.Chronometer;
import android.widget.TextClock;

import java.lang.reflect.Field;

/** Updates detached clocks without attaching them or starting per-second tickers. */
public final class AodWidgetSnapshotRefresher {
    private static final Field CHRONOMETER_STARTED = chronometerStartedField();

    private AodWidgetSnapshotRefresher() {}

    private static Field chronometerStartedField() {
        try {
            // This platform host is built with the same framework. Chronometer has no getter
            // for its started state; preserve paused text instead of resetting its base/state.
            Field field = Chronometer.class.getDeclaredField("mStarted");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static void refresh(View view) {
        if (view instanceof TextClock clock) {
            // Detached views miss timezone broadcasts and the 12/24-hour format observer.
            clock.setTimeZone(clock.getTimeZone());
            clock.setFormat12Hour(clock.getFormat12Hour());
            clock.refreshTime();
        } else if (view instanceof AnalogClock clock) {
            // Rebuild its clock and calculate hands while preserving an explicit widget zone.
            clock.setTimeZone(clock.getTimeZone());
        } else if (view instanceof Chronometer timer) {
            if (CHRONOMETER_STARTED == null) {
                throw new IllegalStateException("Chronometer started state unavailable");
            }
            try {
                if (CHRONOMETER_STARTED.getBoolean(timer)) timer.updateText();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Chronometer started state inaccessible", e);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) refresh(group.getChildAt(i));
        }
    }
}
