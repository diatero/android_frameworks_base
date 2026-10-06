/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.app.AlertDialog;
import android.appwidget.AppWidgetProviderInfo;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.systemui.res.R;

/** Uses the same preference groups, switch and typography as the system Settings app. */
public class AodWidgetSettingsFragment extends AodWidgetPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        // The host is initialized after FragmentActivity restores fragments during onCreate.
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        hostActivity().setTitle(R.string.aod_widgets_title);
        hostActivity().setActionButtonEnabled(false);
        hostActivity().setActionButtonListener(null);
        refresh();
    }

    void refresh() {
        AodWidgetSettingsActivity activity = hostActivity();
        var context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        MainSwitchPreference enabled = new MainSwitchPreference(context);
        enabled.setKey("enabled");
        enabled.setTitle(R.string.aod_widgets_enable);
        enabled.setPersistent(false);
        enabled.setIconSpaceReserved(false);
        screen.addPreference(enabled);
        enabled.setChecked(AodWidgetStore.enabled(activity));
        enabled.setOnPreferenceChangeListener((p, value) -> {
            AodWidgetStore.put(activity, AodWidgetStore.ENABLED, (Boolean) value ? 1 : 0);
            return true;
        });

        PreferenceCategory display = category(screen, "display_layout", R.string.aod_layout_title);
        Preference editor = action(display, "display_layout_editor", R.string.aod_layout_edit,
                activity::editDisplayLayout);
        editor.setSummary(R.string.aod_layout_summary);
        ListPreference rotation = new ListPreference(context);
        rotation.setKey("display_rotation"); rotation.setTitle(R.string.aod_layout_rotation);
        rotation.setEntries(getResources().getStringArray(R.array.aod_layout_rotations));
        rotation.setEntryValues(new String[]{"0", "1", "2", "3"});
        rotation.setPersistent(false); rotation.setIconSpaceReserved(false);
        display.addPreference(rotation);
        rotation.setValue(Integer.toString(AodDisplayLayoutStore.rotation(activity)));
        rotation.setSummary(rotation.getEntry());
        rotation.setOnPreferenceChangeListener((p, value) -> {
            AodWidgetStore.put(activity, AodDisplayLayoutStore.ROTATION, Integer.parseInt((String) value));
            rotation.setValue((String) value); rotation.setSummary(rotation.getEntry()); return false;
        });

        PreferenceCategory widget = category(screen, "widget", R.string.aod_widgets_widget);
        Preference choose = action(widget, "choose", R.string.aod_widgets_add, activity::chooseProvider);
        choose.setSummary(R.string.aod_widgets_multiple_summary);
        for (int id : AodWidgetStore.widgetIds(activity)) {
            AppWidgetProviderInfo info = activity.widgetInfo(id);
            if (info == null) continue;
            Preference item = new Preference(context);
            item.setKey("widget_" + id);
            item.setPersistent(false);
            item.setTitle(info.loadLabel(activity.getPackageManager()));
            item.setSummary(AodWidgetProviderAdapter.applicationLabel(info, activity.getPackageManager()));
            item.setIcon(AodWidgetProviderAdapter.applicationIcon(info, activity.getPackageManager()));
            item.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(activity).setTitle(item.getTitle())
                        .setItems(new CharSequence[]{getString(R.string.aod_layout_edit),
                                getString(R.string.aod_widgets_remove)}, (dialog, index) -> {
                            if (index == 0) activity.editDisplayLayout();
                            else activity.removeWidget(id);
                        }).show();
                return true;
            });
            widget.addPreference(item);
        }
        if (!AodWidgetStore.widgetIds(activity).isEmpty()) {
            Preference appearance = action(widget, "appearance", R.string.aod_widgets_appearance,
                    activity::editAppearance);
            appearance.setSummary(getString(R.string.aod_widgets_appearance_summary,
                    AodWidgetStore.brightness(activity), AodWidgetStore.saturation(activity),
                    getString(AodWidgetStore.highQuality(activity)
                            ? R.string.aod_widgets_quality_high : R.string.aod_widgets_quality_standard)));
        }

        PreferenceCategory privacy = category(screen, "privacy", R.string.aod_widgets_privacy);
        SwitchPreferenceCompat consent = new SwitchPreferenceCompat(context);
        consent.setKey("consent");
        consent.setTitle(R.string.aod_widgets_show_locked);
        consent.setSummary(R.string.aod_widgets_show_locked_summary);
        consent.setIconSpaceReserved(false);
        consent.setPersistent(false);
        privacy.addPreference(consent);
        consent.setChecked(AodWidgetStore.showWhenLocked(activity));
        consent.setOnPreferenceChangeListener((p, value) -> {
            if (!(Boolean) value) {
                AodWidgetStore.put(activity, AodWidgetStore.SHOW_WHEN_LOCKED, 0);
                return true;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.aod_widgets_show_locked)
                    .setMessage(R.string.aod_widgets_privacy_warning)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        AodWidgetStore.put(activity, AodWidgetStore.SHOW_WHEN_LOCKED, 1);
                        consent.setChecked(true);
                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
            return false;
        });

        FooterPreference footer = new FooterPreference(context);
        footer.setKey("explanation");
        footer.setTitle(R.string.aod_widgets_description);
        footer.setSelectable(false);
        footer.setPersistent(false);
        screen.addPreference(footer);
        setPreferenceScreen(screen);
    }

    private PreferenceCategory category(PreferenceScreen screen, String key, int title) {
        PreferenceCategory category = new PreferenceCategory(getPreferenceManager().getContext());
        category.setKey(key);
        category.setTitle(title);
        category.setIconSpaceReserved(false);
        screen.addPreference(category);
        return category;
    }

    private Preference action(PreferenceCategory group, String key, int title, Runnable onClick) {
        Preference preference = new Preference(getPreferenceManager().getContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
        preference.setOnPreferenceClickListener(p -> {
            onClick.run();
            return true;
        });
        group.addPreference(preference);
        return preference;
    }
}
