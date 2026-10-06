/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.res.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Each provider carries its owning application's identity, including duplicate widget labels. */
final class AodWidgetProviderAdapter extends BaseAdapter {
    private final Context mContext;
    private final List<Entry> mEntries = new ArrayList<>();

    private static final class Entry {
        final AppWidgetProviderInfo info;
        final String label;
        final String application;
        Drawable icon;

        Entry(AppWidgetProviderInfo info, PackageManager pm) {
            this.info = info;
            label = info.loadLabel(pm).toString();
            application = applicationLabel(info, pm).toString();
        }
    }

    AodWidgetProviderAdapter(Context context, List<AppWidgetProviderInfo> providers) {
        mContext = context;
        PackageManager pm = context.getPackageManager();
        for (AppWidgetProviderInfo info : providers) mEntries.add(new Entry(info, pm));
        Collator collator = Collator.getInstance(context.getResources().getConfiguration()
                .getLocales().get(0));
        mEntries.sort((a, b) -> {
            int appOrder = collator.compare(a.application, b.application);
            if (appOrder != 0) return appOrder;
            int labelOrder = collator.compare(a.label, b.label);
            return labelOrder != 0 ? labelOrder
                    : a.info.provider.compareTo(b.info.provider);
        });
    }

    static CharSequence applicationLabel(AppWidgetProviderInfo info, PackageManager pm) {
        return info.providerInfo.applicationInfo.loadLabel(pm);
    }

    static Drawable applicationIcon(AppWidgetProviderInfo info, PackageManager pm) {
        Drawable icon = info.providerInfo.applicationInfo.loadIcon(pm);
        return icon != null ? icon : pm.getDefaultActivityIcon();
    }

    @Override public int getCount() { return mEntries.size(); }
    @Override public AppWidgetProviderInfo getItem(int position) { return mEntries.get(position).info; }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        View row = recycled != null ? recycled : LayoutInflater.from(mContext).inflate(
                R.layout.aod_widget_provider_item, parent, false);
        Entry entry = mEntries.get(position);
        if (entry.icon == null) entry.icon = applicationIcon(entry.info, mContext.getPackageManager());
        ((ImageView) row.findViewById(R.id.aod_widget_provider_icon)).setImageDrawable(entry.icon);
        ((TextView) row.findViewById(R.id.aod_widget_provider_label)).setText(entry.label);
        ((TextView) row.findViewById(R.id.aod_widget_provider_application)).setText(entry.application);
        return row;
    }
}
