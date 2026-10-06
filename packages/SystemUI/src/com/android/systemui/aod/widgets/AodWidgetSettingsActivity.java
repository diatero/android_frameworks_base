/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;

/** Transactional widget selection: the old instance survives binding/configuration cancellation. */
public class AodWidgetSettingsActivity extends CollapsingToolbarBaseActivity {
    private static final int BIND = 1;
    private static final int CONFIGURE = 2;
    private AppWidgetHost mHost;
    private AppWidgetManager mManager;
    private int mPendingId = -1;
    private boolean mListening;


    @Override
    protected void attachBaseContext(Context base) {
        // Keep SettingsLib text and controls legible on black even in system light mode.
        // This override belongs only to this Activity, not the rest of SystemUI.
        Configuration dark = new Configuration();
        dark.uiMode = Configuration.UI_MODE_NIGHT_YES;
        super.attachBaseContext(base.createConfigurationContext(dark));
    }

    @Override
    public void setTheme(int resourceId) {
        // The base Activity reapplies its expressive theme before inflating the toolbar.
        super.setTheme(R.style.Theme_AodWidgetSettings);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.aod_widget_settings);
        // Edge-to-edge insets expose the Activity content underneath the gesture bar.
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().setNavigationBarContrastEnforced(false);
        findViewById(android.R.id.content).setBackgroundColor(Color.BLACK);
        if (getAppBarLayout() != null) getAppBarLayout().setBackgroundColor(Color.BLACK);
        if (getCollapsingToolbarLayout() != null) {
            getCollapsingToolbarLayout().setBackgroundColor(Color.BLACK);
            getCollapsingToolbarLayout().setContentScrimColor(Color.BLACK);
            getCollapsingToolbarLayout().setStatusBarScrimColor(Color.BLACK);
        }
        setTitle(R.string.aod_widgets_title);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            message(R.string.aod_widgets_owner_only);
            finish();
            return;
        }
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            message(R.string.aod_widgets_unlock);
            finish();
            return;
        }
        mHost = new AppWidgetHost(this, AodWidgetStore.HOST_ID) {
            @Override
            public AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
                return new AppWidgetHostView(context) {
                    @Override
                    protected void onDefaultViewClicked(View view) {
                        // Default layouts bypass the RemoteViews interaction handler as well.
                    }
                };
            }
        };
        // Also block keyboard/accessibility activation, which can bypass the touch shield.
        mHost.setInteractionHandler((view, pendingIntent, response) -> false);
        mManager = AppWidgetManager.getInstance(this);
        if (state != null) mPendingId = state.getInt("pending_id", -1);
        // Recover allocations abandoned by a killed picker/configuration activity.
        List<Integer> active = AodWidgetStore.widgetIds(this);
        for (int id : mHost.getAppWidgetIds()) {
            if (!active.contains(id) && id != mPendingId) mHost.deleteAppWidgetId(id);
        }
        if (state == null) showContent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mHost != null) {
            try {
                AodWidgetStore.startListening(mHost);
                mListening = true;
            } catch (RuntimeException e) {
                message(R.string.aod_widgets_error);
            }
        }
    }

    @Override
    protected void onStop() {
        if (mListening) AodWidgetStore.stopListening(mHost);
        mListening = false;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("pending_id", mPendingId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) cancelPending();
        super.onDestroy();
    }

    private void showContent() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(
                R.id.aod_widget_settings_content);
        if (fragment instanceof AodWidgetSettingsFragment settings) {
            settings.refresh();
        } else if (fragment == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    R.id.aod_widget_settings_content, new AodWidgetSettingsFragment()).commit();
        }
    }

    AppWidgetProviderInfo currentWidget() {
        return widgetInfo(AodWidgetStore.widgetIds(this).stream().findFirst().orElse(-1));
    }

    AppWidgetProviderInfo widgetInfo(int id) {
        AppWidgetProviderInfo info = owns(id) ? mManager.getAppWidgetInfo(id) : null;
        if (info != null && (info.widgetCategory
                & AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD) == 0) return info;
        if (id >= 0) {
            AodWidgetStore.removeWidget(this, id);
            if (owns(id)) mHost.deleteAppWidgetId(id);
        }
        return null;
    }

    View createPreview(AppWidgetProviderInfo info) {
        AodWidgetGeometry.Layout layout = previewLayout();
        int width = Math.max(1, (int) layout.width);
        int height = Math.max(1, (int) layout.height);
        AppWidgetHostView preview = mHost.createView(this, AodWidgetStore.widgetId(this), info);
        preview.updateAppWidgetSize(null, width, height, width, height, true);
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
        frame.setBackgroundResource(R.drawable.aod_widget_preview_background);
        int padding = dp(16);
        frame.setPadding(padding, padding, padding, padding);
        frame.setMinimumHeight(dp(height) + padding * 2);
        frame.addView(preview, new FrameLayout.LayoutParams(dp(width), dp(height), Gravity.CENTER));
        View shield = new View(this);
        shield.setOnTouchListener((v, event) -> true);
        frame.addView(shield, new FrameLayout.LayoutParams(-1, -1));
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.setContentDescription(getString(R.string.aod_widgets_preview_description,
                info.loadLabel(getPackageManager())));
        return frame;
    }

    void editAppearance() {
        getSupportFragmentManager().beginTransaction().replace(
                R.id.aod_widget_settings_content, new AodWidgetAppearanceFragment())
                .addToBackStack("appearance").commit();
    }

    void editDisplayLayout() {
        startActivity(new Intent(this, AodLayoutEditorActivity.class));
    }

    void stylePreview(View preview, int brightness, int saturation) {
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(
                AodWidgetAppearance.colorMatrix(brightness, saturation)));
        ((FrameLayout) preview).getChildAt(0).setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    void removeWidget(int id) {
        AodWidgetStore.removeWidget(this, id);
        if (owns(id)) mHost.deleteAppWidgetId(id);
        showContent();
    }

    void chooseProvider() {
        if (mPendingId != -1) return;
        if (AodWidgetStore.widgetIds(this).size() >= AodWidgetStore.MAX_WIDGETS) {
            message(R.string.aod_widgets_limit);
            return;
        }
        List<AppWidgetProviderInfo> providers = new ArrayList<>(mManager.getInstalledProvidersForProfile(
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                        | AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD, UserHandle.of(0), null));
        providers.removeIf(p -> (p.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD) != 0);
        if (providers.isEmpty()) {
            message(R.string.aod_widgets_none);
            return;
        }
        AodWidgetProviderAdapter adapter = new AodWidgetProviderAdapter(this, providers);
        new AlertDialog.Builder(this).setTitle(R.string.aod_widgets_choose)
                .setAdapter(adapter, (dialog, index) -> bindProvider(adapter.getItem(index))).show();
    }

    private AodWidgetGeometry.Layout previewLayout() {
        android.graphics.Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        float density = getResources().getDisplayMetrics().density;
        float height = bounds.height() / density;
        return AodWidgetGeometry.resolve(bounds.width() / density, height,
                AodWidgetStore.width(this), AodWidgetStore.height(this),
                AodWidgetStore.positionX(this), AodWidgetStore.positionY(this));
    }

    private void bindProvider(AppWidgetProviderInfo info) {
        try {
            mPendingId = mHost.allocateAppWidgetId();
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD);
            AodWidgetGeometry.Layout layout = previewLayout();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (int) layout.width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (int) layout.width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (int) layout.height);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (int) layout.height);
            if (mManager.bindAppWidgetIdIfAllowed(mPendingId, info.getProfile(), info.provider, options)) {
                configurePending();
            } else {
                Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mPendingId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile())
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
                startActivityForResult(intent, BIND);
            }
        } catch (RuntimeException e) {
            cancelPending();
            message(R.string.aod_widgets_error);
        }
    }

    private void configurePending() {
        AppWidgetProviderInfo info = mManager.getAppWidgetInfo(mPendingId);
        if (info == null) {
            cancelPending();
            message(R.string.aod_widgets_error);
        } else if (info.configure == null) {
            commitPending();
        } else {
            mHost.startAppWidgetConfigureActivityForResult(this, mPendingId, 0, CONFIGURE, null);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != BIND && request != CONFIGURE) || mPendingId == -1) return;
        if (result != RESULT_OK) {
            cancelPending();
            return;
        }
        try {
            if (request == BIND) configurePending();
            else commitPending();
        } catch (RuntimeException e) {
            cancelPending();
            message(R.string.aod_widgets_error);
        }
    }

    private void commitPending() {
        AppWidgetProviderInfo info = mManager.getAppWidgetInfo(mPendingId);
        if (!owns(mPendingId) || info == null || info.getProfile().getIdentifier() != UserHandle.USER_SYSTEM
                || (info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD) != 0) {
            cancelPending();
            message(R.string.aod_widgets_error);
            return;
        }
        AodWidgetStore.addWidget(this, mPendingId);
        mPendingId = -1;
        showContent();
    }

    private void cancelPending() {
        if (mHost != null && mPendingId >= 0) mHost.deleteAppWidgetId(mPendingId);
        mPendingId = -1;
    }

    private boolean owns(int id) {
        if (id < 0) return false;
        for (int owned : mHost.getAppWidgetIds()) if (id == owned) return true;
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            return onNavigateUp();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void onBackPressed() {
        // Returning from the editor keeps the main screen in this Activity.
        if (!getSupportFragmentManager().popBackStackImmediate()) super.onBackPressed();
    }

    private void message(int text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
