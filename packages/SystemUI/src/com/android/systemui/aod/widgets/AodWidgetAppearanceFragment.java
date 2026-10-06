/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.SliderPreference;
import com.android.systemui.res.R;

/** AOD-only appearance editor; preview and presets remain local until Save. */
public class AodWidgetAppearanceFragment extends AodWidgetPreferenceFragment {
    private int[] mValues;
    private SliderPreference mBrightness;
    private SliderPreference mSaturation;
    private ListPreference mQuality;
    private ListPreference mPreset;
    private FrameLayout mPreviewFrame;
    private View mPreview;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        var context = getPreferenceManager().getContext();
        mValues = state == null ? null : state.getIntArray("editing_appearance");
        if (mValues == null || mValues.length != 3) {
            mValues = new int[]{AodWidgetStore.brightness(context), AodWidgetStore.saturation(context),
                    AodWidgetStore.highQuality(context) ? 1 : 0};
        }
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        PreferenceCategory effects = new PreferenceCategory(context);
        effects.setKey("effects");
        effects.setTitle(R.string.aod_widgets_appearance);
        effects.setIconSpaceReserved(false);
        screen.addPreference(effects);

        mPreset = new ListPreference(context);
        mPreset.setKey("preset");
        mPreset.setTitle(R.string.aod_widgets_preset);
        mPreset.setEntries(new CharSequence[]{getString(R.string.aod_widgets_preset_custom),
                getString(R.string.aod_widgets_preset_power), getString(R.string.aod_widgets_preset_balanced),
                getString(R.string.aod_widgets_preset_original)});
        mPreset.setEntryValues(new String[]{"custom", "power", "balanced", "original"});
        mPreset.setPersistent(false);
        mPreset.setIconSpaceReserved(false);
        mPreset.setOnPreferenceChangeListener((p, value) -> {
            switch ((String) value) {
                case "power": mValues = new int[]{25, 0, 0}; break;
                case "balanced": mValues = new int[]{60, 40, 0}; break;
                case "original": mValues = new int[]{100, 100, 1}; break;
                default: break;
            }
            refreshValues();
            // refreshValues selects the matching preset, including custom values.
            return false;
        });
        effects.addPreference(mPreset);
        mBrightness = slider(effects, "brightness", R.string.aod_widgets_brightness, 5, 0);
        mSaturation = slider(effects, "saturation", R.string.aod_widgets_saturation, 0, 1);

        mQuality = new ListPreference(context);
        mQuality.setKey("quality");
        mQuality.setTitle(R.string.aod_widgets_quality);
        mQuality.setEntries(new CharSequence[]{getString(R.string.aod_widgets_quality_standard),
                getString(R.string.aod_widgets_quality_high)});
        mQuality.setEntryValues(new String[]{"0", "1"});
        mQuality.setPersistent(false);
        mQuality.setIconSpaceReserved(false);
        mQuality.setOnPreferenceChangeListener((p, value) -> {
            mValues[2] = Integer.parseInt((String) value);
            refreshValues();
            return false;
        });
        effects.addPreference(mQuality);

        Preference reset = new Preference(context);
        reset.setKey("reset_appearance");
        reset.setTitle(R.string.aod_widgets_reset_appearance);
        reset.setIconSpaceReserved(false);
        reset.setPersistent(false);
        reset.setOnPreferenceClickListener(p -> {
            mValues = new int[]{AodWidgetStore.DEFAULT_BRIGHTNESS, AodWidgetStore.DEFAULT_SATURATION, 0};
            refreshValues();
            return true;
        });
        screen.addPreference(reset);
        FooterPreference hint = new FooterPreference(context);
        hint.setKey("appearance_hint");
        hint.setTitle(R.string.aod_widgets_appearance_hint);
        hint.setPersistent(false);
        hint.setSelectable(false);
        screen.addPreference(hint);

        PreferenceCategory preview = new PreferenceCategory(context);
        preview.setKey("aod_preview");
        preview.setTitle(R.string.aod_widgets_appearance_preview);
        preview.setIconSpaceReserved(false);
        screen.addPreference(preview);
        mPreviewFrame = new FrameLayout(context);
        mPreviewFrame.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        LayoutPreference image = new LayoutPreference(context, mPreviewFrame);
        image.setKey("appearance_preview");
        image.setPersistent(false);
        image.setSelectable(false);
        preview.addPreference(image);
        setPreferenceScreen(screen);
        refreshValues();
    }

    @Override
    public void onResume() {
        super.onResume();
        AodWidgetSettingsActivity activity = hostActivity();
        activity.setTitle(R.string.aod_widgets_appearance);
        activity.setActionButtonText(getString(R.string.aod_widgets_save_appearance));
        activity.setActionButtonContentDescription(getString(R.string.aod_widgets_save_appearance));
        activity.setActionButtonEnabled(true);
        activity.setActionButtonListener(v -> save());
        // Fragment restoration runs before the Activity initializes its widget host.
        var info = activity.currentWidget();
        if (mPreview == null && info != null) {
            mPreview = activity.createPreview(info);
            mPreviewFrame.addView(mPreview);
        }
        refreshValues();
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        state.putIntArray("editing_appearance", mValues.clone());
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        mPreviewFrame.removeAllViews();
        mPreview = null;
        super.onDestroyView();
    }

    private SliderPreference slider(PreferenceCategory group, String key, int title, int min, int index) {
        SliderPreference slider = new SliderPreference(getPreferenceManager().getContext());
        slider.setKey(key);
        slider.setTitle(title);
        slider.setPersistent(false);
        slider.setIconSpaceReserved(false);
        slider.setMax(100);
        slider.setMin(min);
        slider.setSliderIncrement(1);
        slider.setShowSliderValue(true);
        slider.setUpdatesContinuously(true);
        slider.setLabelFormater(value -> percent(Math.round(value)));
        slider.setOnPreferenceChangeListener((p, value) -> {
            mValues[index] = (Integer) value;
            refreshDescriptions();
            return true;
        });
        group.addPreference(slider);
        return slider;
    }

    private String percent(int value) { return getString(R.string.aod_widgets_percent_value, value); }

    private void refreshValues() {
        mValues[0] = Math.max(5, Math.min(100, mValues[0]));
        mValues[1] = Math.max(0, Math.min(100, mValues[1]));
        mValues[2] = Math.max(0, Math.min(1, mValues[2]));
        mBrightness.setValue(mValues[0]);
        mSaturation.setValue(mValues[1]);
        mQuality.setValue(Integer.toString(mValues[2]));
        refreshDescriptions();
    }

    private void refreshDescriptions() {
        mBrightness.setSummary(percent(mValues[0]));
        mBrightness.setSliderStateDescription(percent(mValues[0]));
        mSaturation.setSummary(percent(mValues[1]));
        mSaturation.setSliderStateDescription(percent(mValues[1]));
        mQuality.setSummary(getString(mValues[2] == 0 ? R.string.aod_widgets_quality_standard
                : R.string.aod_widgets_quality_high));
        String preset = "custom";
        if (mValues[0] == 25 && mValues[1] == 0 && mValues[2] == 0) preset = "power";
        if (mValues[0] == 60 && mValues[1] == 40 && mValues[2] == 0) preset = "balanced";
        if (mValues[0] == 100 && mValues[1] == 100 && mValues[2] == 1) preset = "original";
        mPreset.setValue(preset);
        mPreset.setSummary(mPreset.getEntry());
        if (mPreview != null) hostActivity().stylePreview(mPreview, mValues[0], mValues[1]);
    }

    private void save() {
        String[] keys = {AodWidgetStore.BRIGHTNESS, AodWidgetStore.SATURATION, AodWidgetStore.HIGH_QUALITY};
        for (int i = 0; i < keys.length; i++) AodWidgetStore.put(requireContext(), keys[i], mValues[i]);
        getParentFragmentManager().popBackStackImmediate();
    }
}
