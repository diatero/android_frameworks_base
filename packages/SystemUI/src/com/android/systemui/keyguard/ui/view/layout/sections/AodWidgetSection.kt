/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Dumpable
import com.android.systemui.aod.widgets.AodWidgetStore
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.shade.ShadeDisplayAware
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject

/** One listener per host ID; all widgets have independent live/snapshot/recovery state. */
@SysUISingleton
class AodWidgetSection @Inject constructor(
    @ShadeDisplayAware private val context: Context,
    private val keyguardInteractor: KeyguardInteractor,
    private val burnIn: AodBurnInViewModel,
    private val userTracker: UserTracker,
    private val powerInteractor: PowerInteractor,
    private val activityStarter: ActivityStarter,
    private val taskStackChanges: TaskStackChangeListeners,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    dumpManager: DumpManager,
) : KeyguardSection(), Dumpable {
    private val handler = Handler(Looper.getMainLooper())
    private var root: ConstraintLayout? = null
    private var bound = false
    private var hostUsers = 0
    private val renderers = linkedMapOf<Int, AodWidgetRenderer>()
    private val host = object : AppWidgetHost(context, AodWidgetStore.HOST_ID) {
        override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
            checkNotNull(renderers[id]).createHostView(c)
        override fun onProviderChanged(id: Int, info: AppWidgetProviderInfo) {
            super.onProviderChanged(id, info)
            renderers[id]?.providerChanged()
        }
        override fun onAppWidgetRemoved(id: Int) {
            AodWidgetStore.removeWidget(context, id)
            reconcile()
        }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = reconcile()
    }
    init {
        dumpManager.registerNormalDumpable("AodWidgetSection", this)
        host.setInteractionHandler { view, intent, response ->
            var parent: Any? = view
            while (parent is View && parent !is AppWidgetHostView) parent = parent.parent
            val id = (parent as? AppWidgetHostView)?.appWidgetId
            renderers[id]?.handleWidgetInteraction(view, intent, response) ?: false
        }
    }
    private fun acquireHost() {
        if (hostUsers == 0) AodWidgetStore.startListening(host)
        hostUsers++
    }
    private fun releaseHost() {
        hostUsers = (hostUsers - 1).coerceAtLeast(0)
        if (hostUsers == 0) AodWidgetStore.stopListening(host)
    }
    private fun reconcile() {
        val view = root ?: return
        val ids = AodWidgetStore.widgetIds(context)
        if (renderers.keys.toList() == ids) return
        // Reassign ordinals only after releasing all old listeners and callbacks.
        renderers.values.forEach { it.removeViews(view) }
        renderers.clear()
        ids.forEachIndexed { ordinal, id ->
            val renderer = AodWidgetRenderer(context, keyguardInteractor, burnIn, userTracker,
                powerInteractor, activityStarter, taskStackChanges, blueprintInteractor, host,
                id, ordinal, if (ordinal == 0) R.id.aod_widget_container else View.generateViewId(),
                ::acquireHost, ::releaseHost)
            renderers[id] = renderer
            renderer.addViews(view)
            if (bound) renderer.bindData(view)
        }
        handler.post { if (root === view) blueprintInteractor.get().refreshBlueprint() }
    }
    override fun addViews(constraintLayout: ConstraintLayout) {
        root = constraintLayout
        reconcile()
    }
    override fun bindData(constraintLayout: ConstraintLayout) {
        context.contentResolver.unregisterContentObserver(observer)
        for (key in listOf(AodWidgetStore.WIDGET_IDS, AodWidgetStore.WIDGET_ID)) {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key),
                false, observer, UserHandle.USER_SYSTEM)
        }
        bound = true
        renderers.values.forEach { it.bindData(constraintLayout) }
    }
    override fun applyConstraints(constraintSet: ConstraintSet) {
        renderers.values.forEach { it.applyConstraints(constraintSet) }
    }
    override fun removeViews(constraintLayout: ConstraintLayout) {
        bound = false
        context.contentResolver.unregisterContentObserver(observer)
        renderers.values.forEach { it.removeViews(constraintLayout) }
        renderers.clear()
        root = null
    }
    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("selectedWidgets=${AodWidgetStore.widgetIds(context)} hostUsers=$hostUsers")
        renderers.forEach { (id, renderer) ->
            pw.println("--- widget $id ---")
            renderer.dump(pw, args)
        }
    }
}
