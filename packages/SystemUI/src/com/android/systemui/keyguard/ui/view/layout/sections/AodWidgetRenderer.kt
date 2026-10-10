/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.app.PendingIntent
import android.app.ActivityManager
import android.app.WindowConfiguration
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.DataSetObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Dumpable
import com.android.systemui.aod.widgets.AodWidgetAppearance
import com.android.systemui.aod.widgets.AodDisplayLayoutStore
import com.android.systemui.aod.widgets.AodDisplayLayoutGeometry
import com.android.systemui.aod.widgets.AodWidgetGeometry
import com.android.systemui.aod.widgets.AodWidgetPolicy
import com.android.systemui.aod.widgets.AodWidgetStore
import com.android.systemui.aod.widgets.AodWidgetSnapshotRefresher
import com.android.systemui.aod.widgets.AodWidgetRenderPolicy
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.collectFlow
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/** Independent rendering/recovery for one instance; the section owns the shared host. */
internal class AodWidgetRenderer(
    private val context: Context,
    private val keyguardInteractor: KeyguardInteractor,
    private val burnIn: AodBurnInViewModel,
    private val userTracker: UserTracker,
    private val powerInteractor: PowerInteractor,
    private val activityStarter: ActivityStarter,
    private val taskStackChanges: TaskStackChangeListeners,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val host: AppWidgetHost,
    private val configuredId: Int,
    private val ordinal: Int,
    private val containerId: Int,
    private val acquireHost: () -> Unit,
    private val releaseHost: () -> Unit,
) : KeyguardSection(), Dumpable {
    private enum class Surface { NONE, SNAPSHOT, LIVE }
    private val handler = Handler(Looper.getMainLooper())
    private val bindings = mutableListOf<DisposableHandle>()
    private val manager = AppWidgetManager.getInstance(context)
    private var root: ConstraintLayout? = null
    private var container: FrameLayout? = null
    private var liveSlot: FrameLayout? = null
    private var image: ImageView? = null
    private var hostView: SnapshotHostView? = null
    private var bitmap: Bitmap? = null
    private var widgetId = -1
    private var listening = false
    private var attached = false
    private var visible = false
    private var surface = Surface.NONE
    private var switchingUser = false
    private var pendingLaunch: Any? = null
    private var trampolineListener: TaskStackChangeListener? = null
    private var trampolineDeadline = 0L
    private val stopTrampolineMonitoring = Runnable { cancelTrampolineMonitoring() }
    private var dozeState = DozeStateModel.UNINITIALIZED
    private var lastRender = 0L
    private var lastRenderedMinute: Long? = null
    private var snapshotDirty = true
    private var renderingSnapshot = false
    private var recoveryId = -1
    private var recoveryAttempts = 0
    private var nextRecovery = 0L
    private val recover = Runnable { update() }
    private var renderWidth = 0
    private var renderHeight = 0
    private var providerWidth = 0
    private var providerHeight = 0
    private var layoutPreferences = emptyList<Int>()
    private var appearancePreferences = emptyList<Int>()
    private val render = Runnable { renderSnapshot() }
    private val refreshLayout = Runnable {
        if (attached) blueprintInteractor.get().refreshBlueprint()
    }
    private val resizeHost = Runnable {
        val view = hostView ?: return@Runnable
        if (renderWidth <= 0 || renderHeight <= 0) return@Runnable
        val width = (renderWidth / density).toInt().coerceAtLeast(1)
        val height = (renderHeight / density).toInt().coerceAtLeast(1)
        if (width != providerWidth || height != providerHeight) {
            providerWidth = width
            providerHeight = height
            try {
                view.updateAppWidgetSize(null, width, height, width, height, true)
                scheduleRender(contentChanged = true, force = true)
            } catch (e: RuntimeException) {
                recoverFrom(e)
            }
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) requestLayoutRefresh()
    }
    private var clockReceiverRegistered = false
    private val clockObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = scheduleRender(contentChanged = true, force = true)
    }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scheduleRender(contentChanged = true, force = true)
        }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { update(); requestLayoutRefresh() }
    }
    private val userCallback = object : UserTracker.Callback {
        override fun onBeforeUserSwitching(newUser: Int) {
            pendingLaunch = null
            cancelTrampolineMonitoring()
            switchingUser = true
            resetRecovery()
            clear()
        }
        override fun onUserChanged(newUser: Int, userContext: Context) {
            switchingUser = false
            update()
        }
    }
    fun createHostView(c: Context): AppWidgetHostView = SnapshotHostView(c)

    fun providerChanged() {
        resetRecovery()
        clear()
        update()
    }

    private inner class SnapshotHostView(c: Context) : AppWidgetHostView(c) {
        var snapshotMode = true
        var inflationFailed = false
            private set
        var drawingFailed = false
            private set
        private var dispatchingDraw = false
        private var pending: RemoteViews? = null
        private var hasPending = false
        private val pendingData = mutableSetOf<Int>()
        private val observedAdapters = mutableMapOf<Adapter, DataSetObserver>()

        override fun updateAppWidget(views: RemoteViews?) {
            if (!snapshotMode) {
                inflationFailed = false
                drawingFailed = false
                try {
                    super.updateAppWidget(views)
                    if (inflationFailed) postFailure()
                } catch (e: RuntimeException) {
                    postFailure(e)
                }
                return
            }
            pending = views
            hasPending = true
            changed()
        }

        override fun onViewDataChanged(viewId: Int) {
            if (!snapshotMode) {
                try {
                    super.onViewDataChanged(viewId)
                } catch (e: RuntimeException) {
                    postFailure(e)
                }
                return
            }
            pendingData.add(viewId)
            changed()
        }

        override fun getErrorView(): View {
            inflationFailed = true
            postFailure(onlyIfInflationFailed = true)
            return super.getErrorView()
        }

        override fun dispatchDraw(canvas: Canvas) {
            dispatchingDraw = true
            try {
                super.dispatchDraw(canvas)
            } finally {
                dispatchingDraw = false
            }
            // A live host is healthy only after its actual first frame succeeds.
            if (!snapshotMode && !drawingFailed && !inflationFailed && hostView === this) {
                resetRecovery()
            }
        }

        override fun post(action: Runnable): Boolean {
            // AppWidgetHostView posts an error callback when a child draw fails.
            if (dispatchingDraw) drawingFailed = true
            if (!snapshotMode) return super.post(action)
            // AppWidgetHostView catches child draw failures and posts handleViewError().
            // View.post queues work until attachment; this host intentionally stays detached.
            return this@AodWidgetRenderer.handler.post {
                if (hostView === this) {
                    try {
                        action.run()
                    } catch (e: RuntimeException) {
                        recoverFrom(e)
                    }
                }
            }
        }

        override fun onDefaultViewClicked(view: View) {
            openDefaultWidgetApp(view)
        }

        override fun requestLayout() {
            super.requestLayout()
            // Detached adapter rows still request layout when their async content arrives.
            changed()
        }

        private fun changed() {
            if (snapshotMode && hostView === this && !renderingSnapshot) {
                scheduleRender(contentChanged = true)
            }
        }

        private fun postFailure(error: RuntimeException =
            IllegalStateException("Widget RemoteViews inflation failed"),
            onlyIfInflationFailed: Boolean = false) {
            this@AodWidgetRenderer.handler.post {
                if (hostView === this && (!onlyIfInflationFailed || inflationFailed)) recoverFrom(error)
            }
        }

        fun applyPending() {
            if (hasPending) {
                hasPending = false
                val views = pending
                pending = null
                inflationFailed = false
                drawingFailed = false
                super.updateAppWidget(views)
            }
            observeCollections()
            val ids = pendingData.toList()
            pendingData.clear()
            for (id in ids) super.onViewDataChanged(id)
        }

        fun observeCollections() {
            val current = mutableSetOf<Adapter>()
            fun visit(view: View) {
                if (view is AdapterView<*>) view.adapter?.let { current.add(it) }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(this)
            for (adapter in observedAdapters.keys.toList()) {
                if (adapter !in current) {
                    adapter.unregisterDataSetObserver(checkNotNull(observedAdapters.remove(adapter)))
                }
            }
            for (adapter in current) {
                if (adapter !in observedAdapters) {
                    val observer = object : DataSetObserver() {
                        override fun onChanged() = changed()
                        override fun onInvalidated() = changed()
                    }
                    observedAdapters[adapter] = observer
                    adapter.registerDataSetObserver(observer)
                }
            }
        }

        fun releaseObservers() {
            observedAdapters.forEach { (adapter, observer) ->
                try {
                    adapter.unregisterDataSetObserver(observer)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to unregister collection observer", e)
                }
            }
            observedAdapters.clear()
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        root = constraintLayout
        constraintLayout.addOnLayoutChangeListener(layoutListener)
        container = FrameLayout(context).apply {
            // Keep provider content above native clock faces, matching the editor draw order.
            elevation = context.resources.displayMetrics.density
            id = containerId
            visibility = View.GONE
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTranslation() }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    attached = true
                    update()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    attached = false
                    cancelTrampolineMonitoring()
                    resetRecovery()
                    clear()
                }
            })
        }
        image = ImageView(context).apply {
            id = R.id.aod_widget_snapshot
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.aod_widgets_title)
            // Brightness is applied to the whole Keyguard root by AodDisplayLayoutSection.
            colorFilter = ColorMatrixColorFilter(AodWidgetAppearance.colorMatrix(
                AodWidgetPolicy.ORIGINAL, AodWidgetStore.saturation(context)))
            // AOD is display-only. Normal wake gestures lead to the interactive lockscreen.
            isClickable = false
        }
        liveSlot = FrameLayout(context).apply {
            id = R.id.aod_widget_live
            visibility = View.GONE
        }
        container?.addView(image, FrameLayout.LayoutParams(-1, -1))
        container?.addView(liveSlot, FrameLayout.LayoutParams(-1, -1))
        constraintLayout.addView(container)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        bindings.forEach { it.dispose() }
        bindings.clear()
        context.contentResolver.unregisterContentObserver(observer)
        context.contentResolver.unregisterContentObserver(clockObserver)
        if (clockReceiverRegistered) context.unregisterReceiver(clockReceiver)
        context.registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }, null, handler, Context.RECEIVER_NOT_EXPORTED)
        clockReceiverRegistered = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24), false, clockObserver,
            UserHandle.USER_SYSTEM,
        )
        userTracker.removeCallback(userCallback)
        val view = checkNotNull(container)
        for (key in listOf(AodWidgetStore.WIDGET_ID, AodWidgetStore.ENABLED,
            AodWidgetStore.SHOW_WHEN_LOCKED, AodWidgetStore.WIDTH, AodWidgetStore.HEIGHT,
            AodWidgetStore.POSITION_X, AodWidgetStore.POSITION_Y, AodWidgetStore.BRIGHTNESS,
            AodWidgetStore.SATURATION, AodWidgetStore.HIGH_QUALITY, AodDisplayLayoutStore.KEY)) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_SYSTEM,
            )
        }
        userTracker.addCallback(userCallback, context.mainExecutor)
        bindings += collectFlow(view, keyguardInteractor.dozeTransitionModel, {
            dozeState = it.to
            update()
        })
        bindings += collectFlow(view, keyguardInteractor.isKeyguardDismissible, { update() })
        bindings += collectFlow(view, keyguardInteractor.isKeyguardShowing, { update() })
        bindings += collectFlow(view, keyguardInteractor.statusBarState, { update() })
        bindings += collectFlow(view, keyguardInteractor.isKeyguardOccluded, { update() })
        bindings += collectFlow(view, keyguardInteractor.primaryBouncerShowing, { update() })
        bindings += collectFlow(view, keyguardInteractor.alternateBouncerShowing, { update() })
        bindings += collectFlow(view, keyguardInteractor.isDozing, { update() })
        bindings += collectFlow(view, powerInteractor.detailedWakefulness, { update() })
        bindings += collectFlow(view, keyguardInteractor.dozeTimeTick, {
            if (!listening) update() else scheduleRender()
        })
        bindings += collectFlow(view, burnIn.movement, { updateTranslation() })
    }

    private fun isOwner() = userTracker.userId == UserHandle.USER_SYSTEM &&
        UserHandle.myUserId() == UserHandle.USER_SYSTEM

    private fun desiredSurface(): Surface {
        if (!attached) return Surface.NONE
        val profile = customProfile()
        if (!AodDisplayLayoutStore.widgetBox(profile, configuredId, ordinal).visible) return Surface.NONE
        val switching = switchingUser || userTracker.isUserSwitching
        val enabled = AodWidgetStore.enabled(context)
        val dismissible = keyguardInteractor.isKeyguardDismissible.value
        val consent = AodWidgetStore.showWhenLocked(context)
        if (keyguardInteractor.isDozing.value) {
            return if (AodWidgetPolicy.canShow(dozeState, isOwner(), switching, enabled,
                dismissible, consent, keyguardInteractor.isKeyguardShowing.value,
                keyguardInteractor.isKeyguardOccluded.value,
                keyguardInteractor.primaryBouncerShowing.value ||
                    keyguardInteractor.alternateBouncerShowing.value)) Surface.SNAPSHOT else Surface.NONE
        }
        return if (AodWidgetPolicy.canShowLockscreen(isOwner(), switching, enabled, dismissible,
            consent, DozeStateModel.isDozeOff(dozeState) && keyguardInteractor.isKeyguardShowing.value &&
                keyguardInteractor.statusBarState.value == StatusBarState.KEYGUARD,
            keyguardInteractor.isKeyguardOccluded.value,
            keyguardInteractor.primaryBouncerShowing.value ||
                keyguardInteractor.alternateBouncerShowing.value,
            powerInteractor.detailedWakefulness.value.isAwake(),
            keyguardInteractor.isDozing.value)) Surface.LIVE else Surface.NONE
    }

    private fun customProfile(): AodDisplayLayoutStore.Profile {
        val metrics = context.resources.displayMetrics
        val width = root?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = root?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        return AodDisplayLayoutStore.load(context).profiles[AodDisplayLayoutStore.index(
            keyguardInteractor.isDozing.value, width, height)]
    }

    private fun sameSelection(id: Int, provider: android.content.ComponentName): Boolean =
        isOwner() && !switchingUser && !userTracker.isUserSwitching &&
            AodWidgetStore.enabled(context) && AodWidgetStore.widgetIds(context).contains(id) && configuredId == id &&
            host.appWidgetIds.contains(id) && manager.getAppWidgetInfo(id)?.let {
                it.provider == provider && it.profile.identifier == UserHandle.USER_SYSTEM &&
                    it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD == 0
            } == true

    private fun afterUnlock(id: Int, provider: android.content.ComponentName, launch: () -> Unit) {
        cancelTrampolineMonitoring()
        if (pendingLaunch != null) return
        val request = Any()
        pendingLaunch = request
        activityStarter.executeRunnableDismissingKeyguard(
            {
                if (pendingLaunch === request) {
                    pendingLaunch = null
                    if (sameSelection(id, provider)) launch()
                }
            },
            { if (pendingLaunch === request) pendingLaunch = null },
            true /* dismissShade */, true /* afterKeyguardGone */, false /* deferred */,
        )
    }

    private fun cancelTrampolineMonitoring() {
        handler.removeCallbacks(stopTrampolineMonitoring)
        trampolineListener?.let { taskStackChanges.unregisterTaskStackListener(it) }
        trampolineListener = null
        trampolineDeadline = 0L
    }

    private fun monitorWidgetTrampoline(id: Int, provider: android.content.ComponentName,
        displayId: Int) {
        cancelTrampolineMonitoring()
        trampolineDeadline = SystemClock.uptimeMillis() + TRAMPOLINE_TIMEOUT_MS
        val listener = object : TaskStackChangeListener {
            private fun activityStarted(task: ActivityManager.RunningTaskInfo) {
                // Keep registration changes outside the shared listener dispatch loop.
                handler.post {
                    if (trampolineListener !== this) return@post
                    if (SystemClock.uptimeMillis() > trampolineDeadline ||
                        !sameSelection(id, provider) || !attached ||
                        !keyguardInteractor.isKeyguardShowing.value ||
                        keyguardInteractor.isDozing.value ||
                        !powerInteractor.detailedWakefulness.value.isAwake() ||
                        keyguardInteractor.primaryBouncerShowing.value ||
                        keyguardInteractor.alternateBouncerShowing.value) {
                        cancelTrampolineMonitoring()
                        return@post
                    }
                    if (task.userId != UserHandle.USER_SYSTEM || task.displayId != displayId ||
                        task.activityType != WindowConfiguration.ACTIVITY_TYPE_STANDARD ||
                        task.topActivity == null ||
                        task.topActivity?.packageName == context.packageName) return@post
                    Log.d(TAG, "Widget trampoline detected id=$id; requesting Keyguard dismissal")
                    // The receiver/service has already launched the target. Only dismiss Keyguard;
                    // do not resend the PendingIntent or replace its destination/fill-in extras.
                    afterUnlock(id, provider) {}
                }
            }

            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) =
                activityStarted(taskInfo)

            override fun onActivityRestartAttempt(task: ActivityManager.RunningTaskInfo,
                homeTaskVisible: Boolean, clearedTask: Boolean, wasVisible: Boolean) =
                activityStarted(task)
        }
        trampolineListener = listener
        taskStackChanges.registerTaskStackListener(listener)
        handler.postDelayed(stopTrampolineMonitoring, TRAMPOLINE_TIMEOUT_MS)
    }

    fun handleWidgetInteraction(view: View, intent: PendingIntent,
        response: RemoteViews.RemoteResponse): Boolean {
        if (surface != Surface.LIVE || desiredSurface() != Surface.LIVE || !view.isShown) return false
        val id = widgetId
        val info = manager.getAppWidgetInfo(id) ?: return false
        if (!sameSelection(id, info.provider)) return false
        val options = response.getLaunchOptions(view)
        if (!intent.isActivity) {
            // A broadcast/service can open an Activity indirectly. Observe only this interaction's
            // short launch window; refresh/playback buttons still leave Keyguard in place.
            monitorWidgetTrampoline(id, info.provider, view.display?.displayId ?: context.displayId)
            val sent = RemoteViews.startPendingIntent(view, intent, options)
            if (!sent) cancelTrampolineMonitoring()
            return sent
        }
        afterUnlock(id, info.provider) {
            // Keep RemoteViews fill-in intents for collection rows and provider launch options.
            activityStarter.startPendingIntentMaybeDismissingKeyguard(
                intent, true, null, null, options.first, options.second.toBundle(), null,
            )
        }
        return true
    }

    private fun openDefaultWidgetApp(view: View) {
        if (surface != Surface.LIVE || desiredSurface() != Surface.LIVE || !view.isShown) return
        val id = widgetId
        val info = manager.getAppWidgetInfo(id) ?: return
        if (!sameSelection(id, info.provider)) return
        val intent = context.packageManager.getLaunchIntentForPackage(info.provider.packageName) ?: return
        afterUnlock(id, info.provider) {
            activityStarter.startActivity(intent, true, null, false, UserHandle.SYSTEM)
        }
    }

    private fun update() {
        if (trampolineListener != null &&
            (!keyguardInteractor.isKeyguardShowing.value || keyguardInteractor.isDozing.value ||
                !powerInteractor.detailedWakefulness.value.isAwake() || !isOwner() ||
                switchingUser || userTracker.isUserSwitching || !AodWidgetStore.enabled(context))) {
            cancelTrampolineMonitoring()
        }
        val next = desiredSurface()
        if (next == Surface.NONE) {
            resetRecovery()
            clear()
            return
        }
        val preferences = listOf(AodWidgetStore.width(context), AodWidgetStore.height(context),
            AodWidgetStore.positionX(context), AodWidgetStore.positionY(context))
        if (preferences != layoutPreferences) {
            resetRecovery()
            layoutPreferences = preferences
            requestLayoutRefresh()
        }
        val appearance = listOf(AodWidgetStore.brightness(context), AodWidgetStore.saturation(context),
            if (AodWidgetStore.highQuality(context)) 1 else 0)
        if (appearance != appearancePreferences) {
            val qualityChanged = appearance.get(2) != appearancePreferences.getOrNull(2)
            appearancePreferences = appearance
            image?.colorFilter = ColorMatrixColorFilter(
                AodWidgetAppearance.colorMatrix(AodWidgetPolicy.ORIGINAL, appearance[1]))
            // Colours update the existing ImageView immediately; resolution needs a fresh bitmap.
            if (qualityChanged) scheduleRender(contentChanged = true, force = true)
        }
        val id = configuredId
        if (id < 0) {
            resetRecovery()
            clear()
            return
        }
        if (id != recoveryId) {
            resetRecovery()
            recoveryId = id
        }
        if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS || SystemClock.uptimeMillis() < nextRecovery) return
        handler.removeCallbacks(recover)
        nextRecovery = 0L
        try {
            if (!listening || id != widgetId) {
                clear()
                if (!host.appWidgetIds.contains(id)) return
                val info = manager.getAppWidgetInfo(id) ?: return
                if (info.profile.identifier != UserHandle.USER_SYSTEM ||
                    info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD != 0) return
                widgetId = id
                acquireHost()
                listening = true
                hostView = host.createView(context, id, info) as SnapshotHostView
                hostView?.setOnLightBackground(false)
                providerWidth = 0
                providerHeight = 0
                requestLayoutRefresh()
            }
            val view = hostView ?: return
            if (surface != next) {
                surface = next
                lastRenderedMinute = null
                if (next == Surface.LIVE) {
                    handler.removeCallbacks(render)
                    view.snapshotMode = false
                    view.applyPending()
                    if (view.inflationFailed) throw IllegalStateException("Widget inflation failed")
                    view.releaseObservers()
                    if (view.parent == null) liveSlot?.addView(view, FrameLayout.LayoutParams(-1, -1))
                    image?.setImageDrawable(null)
                    bitmap = null
                    setVisible(true)
                } else {
                    view.snapshotMode = true
                    liveSlot?.removeView(view)
                    scheduleRender()
                }
                image?.visibility = if (next == Surface.SNAPSHOT) View.VISIBLE else View.GONE
                liveSlot?.visibility = if (next == Surface.LIVE) View.VISIBLE else View.GONE
                updateTranslation()
            }
            handler.removeCallbacks(resizeHost)
            handler.post(resizeHost)
            scheduleRender()
        } catch (e: RuntimeException) {
            recoverFrom(e)
        }
    }

    private fun updateTranslation() {
        val movement = burnIn.movement.value
        val view = container ?: return
        val viewport = root ?: return
        view.translationX = if (surface == Surface.SNAPSHOT) AodDisplayLayoutGeometry.boundedOffset(
            view.left.toFloat(), view.width.toFloat(), viewport.width.toFloat(), movement.translationX.toFloat()) else 0f
        view.translationY = if (surface == Surface.SNAPSHOT) AodDisplayLayoutGeometry.boundedOffset(
            view.top.toFloat(), view.height.toFloat(), viewport.height.toFloat(), movement.translationY.toFloat()) else 0f
    }

    private fun scheduleRender(contentChanged: Boolean = false, force: Boolean = false) {
        if (contentChanged) snapshotDirty = true
        if (!listening || surface != Surface.SNAPSHOT || desiredSurface() != Surface.SNAPSHOT ||
            renderWidth <= 0 || renderHeight <= 0) return
        val delay = AodWidgetRenderPolicy.delayMillis(SystemClock.uptimeMillis(),
            System.currentTimeMillis() / 60_000L, lastRender, lastRenderedMinute,
            snapshotDirty, force)
        if (delay < 0) return
        if (handler.hasCallbacks(render)) {
            if (delay > 0) return
            handler.removeCallbacks(render)
        }
        handler.postDelayed(render, delay)
    }

    private fun renderSnapshot() {
        if (!listening || surface != Surface.SNAPSHOT || desiredSurface() != Surface.SNAPSHOT) return
        val view = hostView ?: return
        renderingSnapshot = true
        try {
            view.applyPending()
            if (view.inflationFailed) throw IllegalStateException("Widget RemoteViews inflation failed")
            fun layout() {
                view.measure(View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, renderWidth, renderHeight)
            }
            // Layout can select a different responsive RemoteViews tree. Refresh that final tree.
            layout()
            AodWidgetSnapshotRefresher.refresh(view)
            if (view.isLayoutRequested) {
                layout()
                AodWidgetSnapshotRefresher.refresh(view)
                if (view.isLayoutRequested) layout()
            }
            view.observeCollections()
            if (view.inflationFailed) throw IllegalStateException("Responsive widget inflation failed")
            val highQuality = AodWidgetStore.highQuality(context)
            val next = Bitmap.createBitmap(AodWidgetAppearance.bitmapDimension(renderWidth, highQuality),
                AodWidgetAppearance.bitmapDimension(renderHeight, highQuality), Bitmap.Config.ARGB_8888)
            Canvas(next).apply {
                scale(next.width.toFloat() / renderWidth, next.height.toFloat() / renderHeight)
                view.draw(this)
            }
            if (view.drawingFailed || view.inflationFailed) {
                throw IllegalStateException("Widget child drawing failed")
            }
            image?.setImageBitmap(next)
            bitmap = next
            lastRender = SystemClock.uptimeMillis()
            lastRenderedMinute = System.currentTimeMillis() / 60_000L
            snapshotDirty = false
            resetRecovery()
            setVisible(true)
        } catch (e: RuntimeException) {
            recoverFrom(e)
        } finally {
            renderingSnapshot = false
        }
    }

    private fun resetRecovery() {
        handler.removeCallbacks(recover)
        recoveryId = -1
        recoveryAttempts = 0
        nextRecovery = 0L
    }

    private fun recoverFrom(error: RuntimeException) {
        val id = widgetId.takeIf { it >= 0 } ?: configuredId
        if (recoveryId != id) {
            resetRecovery()
            recoveryId = id
        }
        recoveryAttempts++
        Log.w(TAG, "Widget failure id=$id attempt=$recoveryAttempts", error)
        clear()
        if (id < 0 || desiredSurface() == Surface.NONE || recoveryAttempts > MAX_RECOVERY_ATTEMPTS) return
        nextRecovery = SystemClock.uptimeMillis() + RECOVERY_DELAYS[recoveryAttempts - 1]
        handler.postDelayed(recover, RECOVERY_DELAYS[recoveryAttempts - 1])
    }

    private fun clear() {
        handler.removeCallbacks(render)
        handler.removeCallbacks(resizeHost)
        handler.removeCallbacks(recover)
        hostView?.releaseObservers()
        liveSlot?.removeAllViews()
        if (listening) {
            try { releaseHost() } catch (e: RuntimeException) { Log.w(TAG, "Stop widget host", e) }
        }
        if (widgetId >= 0) host.removeListener(widgetId)
        listening = false
        widgetId = -1
        hostView = null
        image?.setImageDrawable(null)
        bitmap = null
        lastRenderedMinute = null
        snapshotDirty = true
        surface = Surface.NONE
        liveSlot?.visibility = View.GONE
        image?.visibility = View.GONE
        updateTranslation()
        setVisible(false)
    }

    private fun setVisible(value: Boolean) {
        container?.visibility = if (value) View.VISIBLE else View.GONE
        if (visible == value) return
        visible = value
        requestLayoutRefresh()
    }

    private fun requestLayoutRefresh() {
        handler.removeCallbacks(refreshLayout)
        handler.post(refreshLayout)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val metrics = context.resources.displayMetrics
        val width = (root?.width?.takeIf { it > 0 } ?: metrics.widthPixels) / density
        val height = (root?.height?.takeIf { it > 0 } ?: metrics.heightPixels) / density
        val profile = customProfile()
        val box = AodDisplayLayoutStore.widgetBox(profile, configuredId, ordinal)
        val custom = AodDisplayLayoutGeometry.resolve(width, height,
            box.x, box.y, box.width, box.height)
        val nextWidth = dp(custom.width)
        val nextHeight = dp(custom.height)
        if (nextWidth != renderWidth || nextHeight != renderHeight) {
            renderWidth = nextWidth
            renderHeight = nextHeight
            lastRenderedMinute = null
            handler.removeCallbacks(resizeHost)
            handler.post(resizeHost)
        }
        constraintSet.apply {
            val id = containerId
            constrainWidth(id, renderWidth)
            constrainHeight(id, renderHeight)
            clear(id, ConstraintSet.END)
            clear(id, ConstraintSet.BOTTOM)
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, dp(custom.left))
            connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(custom.top))
            setVisibility(id, if (visible) View.VISIBLE else View.GONE)
            if (visible && !profile.enabled && ordinal == 0) {
                connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, ConstraintSet.BOTTOM,
                    id, ConstraintSet.TOP, dp(32f))
                // Notification bounds feed max-count calculation; reserve the widget's slot.
                createBarrier(R.id.nssl_placeholder_barrier_bottom, Barrier.TOP, 0,
                    R.id.device_entry_icon_view, R.id.ambient_indication_container, id)
                connect(R.id.nssl_placeholder, ConstraintSet.BOTTOM,
                    R.id.nssl_placeholder_barrier_bottom, ConstraintSet.TOP, dp(16f))
            }
        }
        scheduleRender()
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        cancelTrampolineMonitoring()
        bindings.forEach { it.dispose() }
        bindings.clear()
        context.contentResolver.unregisterContentObserver(observer)
        context.contentResolver.unregisterContentObserver(clockObserver)
        if (clockReceiverRegistered) context.unregisterReceiver(clockReceiver)
        clockReceiverRegistered = false
        userTracker.removeCallback(userCallback)
        attached = false
        resetRecovery()
        clear()
        handler.removeCallbacks(refreshLayout)
        constraintLayout.removeOnLayoutChangeListener(layoutListener)
        constraintLayout.removeView(container)
        container = null
        liveSlot = null
        image = null
        root = null
    }

    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Float) = kotlin.math.round(value * density).toInt().coerceAtLeast(0)

    override fun dump(pw: PrintWriter, args: Array<String>) {
        val location = IntArray(2)
        container?.getLocationOnScreen(location)
        pw.println("state=$dozeState surface=$surface user=${userTracker.userId} switching=$switchingUser attached=$attached")
        pw.println("enabled=${AodWidgetStore.enabled(context)} showWhenLocked=${AodWidgetStore.showWhenLocked(context)}")
        pw.println("widgetId=$widgetId listening=$listening visible=$visible liveAttached=${hostView?.isAttachedToWindow == true} bitmapBytes=${bitmap?.allocationByteCount ?: 0}")
        pw.println("bounds=(${location[0]},${location[1]},${container?.width},${container?.height}) sizeDp=${AodWidgetStore.width(context)}x${AodWidgetStore.height(context)} position=${AodWidgetStore.positionX(context)},${AodWidgetStore.positionY(context)}")
        pw.println("aodBrightness=${AodWidgetStore.brightness(context)} aodSaturation=${AodWidgetStore.saturation(context)} highQuality=${AodWidgetStore.highQuality(context)} bitmapSize=${bitmap?.width ?: 0}x${bitmap?.height ?: 0}")
        pw.println("lastRenderUptime=$lastRender renderScheduled=${handler.hasCallbacks(render)} dirty=$snapshotDirty")
        pw.println("recoveryAttempts=$recoveryAttempts nextRecovery=$nextRecovery recoveryScheduled=${handler.hasCallbacks(recover)}")
        pw.println("trampolineMonitoring=${trampolineListener != null} trampolineDeadline=$trampolineDeadline pendingLaunch=${pendingLaunch != null}")
    }

    companion object {
        private const val TAG = "AodWidgetSection"
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val TRAMPOLINE_TIMEOUT_MS = 2_000L
        private val RECOVERY_DELAYS = longArrayOf(2_000, 5_000, 15_000)
    }
}
