/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.os.Bundle;
import android.graphics.Color;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/** Shared native Settings list styling and edge-to-edge navigation insets. */
abstract class AodWidgetPreferenceFragment extends SettingsBasePreferenceFragment {
    @Override
    public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        View list = getListView();
        list.setBackgroundColor(Color.BLACK);
        int left = list.getPaddingLeft();
        int top = list.getPaddingTop();
        int right = list.getPaddingRight();
        int bottom = list.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(list, (v, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // The collapsing toolbar handles the top inset.
            v.setPadding(left + bars.left, top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(list);
    }

    AodWidgetSettingsActivity hostActivity() {
        return (AodWidgetSettingsActivity) requireActivity();
    }
}
