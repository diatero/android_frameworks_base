/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Dumpable
import com.android.systemui.aod.widgets.AodLayoutPreviewSource
import com.android.systemui.aod.widgets.AodDisplayLayoutGeometry
import com.android.systemui.aod.widgets.AodDisplayLayoutStore
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.util.kotlin.collectFlow
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import com.android.systemui.shared.R as sharedR

/** Keeps native controllers and event handling; only customises their display geometry. */
@SysUISingleton
class AodDisplayLayoutSection @Inject constructor(
    @ShadeDisplayAware private val context: Context,
    private val keyguard: KeyguardInteractor,
    private val power: PowerInteractor,
    private val clock: KeyguardClockViewModel,
    private val burnIn: AodBurnInViewModel,
    private val userTracker: UserTracker,
    private val blueprints: Lazy<KeyguardBlueprintInteractor>,
    private val notifications: SharedNotificationContainer,
    private val previewSource: AodLayoutPreviewSource,
    dumpManager: DumpManager,
) : KeyguardSection(), Dumpable {
    private val handler = Handler(Looper.getMainLooper())
    private var root: ConstraintLayout? = null
    private var layouts = AodDisplayLayoutStore.Layouts()
    private val bindings = mutableListOf<DisposableHandle>()
    private var selected = -1
    private var doze = DozeStateModel.UNINITIALIZED
    private val saved = mutableMapOf<View, Original>()
    private val refresh = Runnable { blueprints.get().refreshBlueprint() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            layouts = AodDisplayLayoutStore.load(context)
            restore()
            changed()
        }
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener { positionModules(); true }
    private data class Original(
        val x: Float, val y: Float, val sx: Float, val sy: Float,
        val pivotX: Float, val pivotY: Float, val visibility: Int,
        val accessibility: Int,
        var appliedX: Float? = null, var appliedY: Float? = null,
        var appliedSx: Float? = null, var appliedSy: Float? = null,
        var hidden: Boolean = false,
        var horizontal: Horizontal? = null, var appliedHorizontal: Horizontal? = null,
    )
    private data class Horizontal(
        val width: Int, val left: Int, val right: Int, val start: Int, val end: Int,
        val startToStart: Int, val startToEnd: Int, val endToStart: Int, val endToEnd: Int,
        val bias: Float, val min: Int, val max: Int, val mode: Int,
    ) {
        constructor(p: ConstraintLayout.LayoutParams) : this(p.width, p.leftMargin, p.rightMargin,
            p.marginStart, p.marginEnd, p.startToStart, p.startToEnd, p.endToStart, p.endToEnd,
            p.horizontalBias, p.matchConstraintMinWidth, p.matchConstraintMaxWidth,
            p.matchConstraintDefaultWidth)
        fun apply(p: ConstraintLayout.LayoutParams) {
            p.width = width; p.leftMargin = left; p.rightMargin = right
            p.marginStart = start; p.marginEnd = end
            p.startToStart = startToStart; p.startToEnd = startToEnd
            p.endToStart = endToStart; p.endToEnd = endToEnd; p.horizontalBias = bias
            p.matchConstraintMinWidth = min; p.matchConstraintMaxWidth = max
            p.matchConstraintDefaultWidth = mode
        }
    }
    init { dumpManager.registerNormalDumpable("AodDisplayLayoutSection", this) }
    override fun addViews(constraintLayout: ConstraintLayout) {
        root = constraintLayout
        previewSource.attach(constraintLayout)
    }
    override fun bindData(constraintLayout: ConstraintLayout) {
        bindings.forEach { it.dispose() }; bindings.clear()
        context.contentResolver.unregisterContentObserver(observer)
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(AodDisplayLayoutStore.KEY),
            false, observer, UserHandle.USER_SYSTEM)
        layouts = AodDisplayLayoutStore.load(context)
        constraintLayout.viewTreeObserver.removeOnPreDrawListener(preDraw)
        // Register after the native burn-in layer; its transforms pause while custom geometry owns
        // the visible modules, avoiding competing transforms and a continuous redraw loop on AOD.
        constraintLayout.viewTreeObserver.addOnPreDrawListener(preDraw)
        bindings += collectFlow(constraintLayout, keyguard.dozeTransitionModel, { doze = it.to; changed() })
        bindings += collectFlow(constraintLayout, keyguard.isDozing, { changed() })
        bindings += collectFlow(constraintLayout, keyguard.isKeyguardShowing, { changed() })
        bindings += collectFlow(constraintLayout, keyguard.isKeyguardOccluded, { changed() })
        bindings += collectFlow(constraintLayout, keyguard.primaryBouncerShowing, { changed() })
        bindings += collectFlow(constraintLayout, keyguard.alternateBouncerShowing, { changed() })
        bindings += collectFlow(constraintLayout, keyguard.statusBarState, { changed() })
        bindings += collectFlow(constraintLayout, power.detailedWakefulness, { changed() })
        bindings += collectFlow(constraintLayout, burnIn.movement, { constraintLayout.invalidate() })
        bindings += collectFlow(constraintLayout, clock.currentClock, { restore(); changed() })
    }
    private fun index(): Int {
        val view = root ?: return -1
        if (userTracker.userId != UserHandle.USER_SYSTEM || userTracker.isUserSwitching ||
            !keyguard.isKeyguardShowing.value || keyguard.isKeyguardOccluded.value ||
            keyguard.primaryBouncerShowing.value || keyguard.alternateBouncerShowing.value ||
            keyguard.statusBarState.value != StatusBarState.KEYGUARD) return -1
        val aod = keyguard.isDozing.value
        if (aod && doze != DozeStateModel.DOZE_AOD && doze != DozeStateModel.DOZE_AOD_DOCKED) return -1
        if (!aod && !power.detailedWakefulness.value.isAwake()) return -1
        val i = AodDisplayLayoutStore.index(aod, view.width, view.height)
        return if (layouts.profiles[i].enabled) i else -1
    }
    private fun changed() {
        if (index() < 0) restore()
        handler.removeCallbacks(refresh); handler.post(refresh)
        root?.invalidate()
    }
    private fun original(view: View): Original = saved.getOrPut(view) {
        Original(view.translationX, view.translationY, view.scaleX, view.scaleY,
            view.pivotX, view.pivotY, view.visibility, view.importantForAccessibility)
    }
    private fun restoreView(view: View, value: Original) {
        // Preserve newer authoritative native updates made during authentication/clock changes.
        if (view.translationX == value.appliedX) view.translationX = value.x
        if (view.translationY == value.appliedY) view.translationY = value.y
        if (view.scaleX == value.appliedSx) view.scaleX = value.sx
        if (view.scaleY == value.appliedSy) view.scaleY = value.sy
        view.pivotX = value.pivotX; view.pivotY = value.pivotY
        if (value.hidden && view.visibility == View.INVISIBLE) view.visibility = value.visibility
        view.importantForAccessibility = value.accessibility
        val p = view.layoutParams as? ConstraintLayout.LayoutParams
        if (p != null && value.horizontal != null && Horizontal(p) == value.appliedHorizontal) {
            checkNotNull(value.horizontal).apply(p); view.layoutParams = p
        }
    }
    private fun restore() {
        saved.forEach { (v, original) -> restoreView(v, original) }
        saved.clear(); selected = -1
        (clock.burnInLayer as? AodBurnInLayer)?.setCustomLayoutActive(false)
    }
    private fun rect(box: AodDisplayLayoutStore.Box): AodDisplayLayoutGeometry.Rect {
        val view = checkNotNull(root)
        val density = context.resources.displayMetrics.density
        return AodDisplayLayoutGeometry.resolve(view.width / density, view.height / density,
            box.x, box.y, box.width, box.height)
    }
    private fun positionModules() {
        val view = root ?: return
        val i = index()
        if (i < 0 || view.width <= 0 || view.height <= 0) { restore(); return }
        if (selected != i) { restore(); selected = i }
        (clock.burnInLayer as? AodBurnInLayer)?.setCustomLayoutActive(true)
        val profile = layouts.profiles[i]
        val current = clock.currentClock.value
        val face = if (clock.isLargeClockVisible.value) current?.largeClock else current?.smallClock
        val groups = listOf(
            face?.layout?.views?.filter { it.parent === view && it.visibility != View.GONE } ?: emptyList(),
            listOfNotNull(view.findViewById<View>(sharedR.id.date_smartspace_view),
                view.findViewById<View>(sharedR.id.date_smartspace_view_large),
                view.findViewById<View>(sharedR.id.bc_smartspace_view),
                view.findViewById<View>(R.id.keyguard_slice_view)).filter {
                    it.parent === view && it.visibility != View.GONE },
            if (i < 2) listOfNotNull(view.findViewById<View>(R.id.aod_notification_icon_container))
                .filter { it.visibility != View.GONE } else emptyList(),
            emptyList(), // Widget geometry is applied by AodWidgetSection before snapshot rendering.
            listOfNotNull(view.findViewById<View>(R.id.keyguard_indication_area)).filter { it.visibility != View.GONE },
        )
        val used = groups.flatten().toMutableSet()
        for ((module, members) in groups.withIndex()) {
            fit(members, profile.boxes[module], i < 2)
        }
        if (i >= 2) {
            notifications.findViewById<View>(R.id.notification_stack_scroller)?.let { nssl ->
                used += nssl
                val box = profile.boxes[AodDisplayLayoutStore.NOTIFICATIONS]
                val r = rect(box)
                val original = original(nssl)
                if (!box.visible) hide(nssl, original) else {
                    show(nssl, original)
                    val p = nssl.layoutParams as? ConstraintLayout.LayoutParams
                    if (p != null && notifications.width > 0) {
                        // Reflow native rows at their normal font size instead of squeezing text.
                        val density = context.resources.displayMetrics.density
                        val left = (r.left * density).toInt().coerceAtLeast(0)
                        val right = (notifications.width - (r.left + r.width) * density).toInt().coerceAtLeast(0)
                        if (original.horizontal == null) original.horizontal = Horizontal(p)
                        val desired = Horizontal(0, left, right, left, right,
                            ConstraintSet.PARENT_ID, -1, -1, ConstraintSet.PARENT_ID, .5f, 0, 0, 0)
                        original.appliedHorizontal = desired
                        if (Horizontal(p) != desired) { desired.apply(p); nssl.layoutParams = p }
                    }
                }
            }
        }
        saved.keys.filter { it !in used }.forEach { stale ->
            saved.remove(stale)?.let { restoreView(stale, it) }
        }
    }
    private fun hide(view: View, original: Original) {
        view.visibility = View.INVISIBLE; original.hidden = true
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private fun show(view: View, original: Original) {
        if (original.hidden && view.visibility == View.INVISIBLE) view.visibility = original.visibility
        original.hidden = false; view.importantForAccessibility = original.accessibility
    }
    private fun fit(views: List<View>, box: AodDisplayLayoutStore.Box, aod: Boolean) {
        if (views.isEmpty()) return
        val measurable = views.filter { it.width > 0 && it.height > 0 }
        if (measurable.isEmpty()) return
        val left = measurable.minOf { it.left }; var top = measurable.minOf { it.top }
        val width = measurable.maxOf { it.right } - left
        var height = measurable.maxOf { it.bottom } - top
        // The indication area may reserve an empty second row. Fit only rows containing text,
        // retaining native font size when that blank space would otherwise shrink the message.
        val indication = measurable.singleOrNull()?.takeIf { it.id == R.id.keyguard_indication_area } as? ViewGroup
        if (indication != null) {
            val rows = (0 until indication.childCount).map { indication.getChildAt(it) }
                .filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
            if (rows.isNotEmpty()) {
                top = indication.top + rows.minOf { it.top }
                height = rows.maxOf { it.bottom } - rows.minOf { it.top }
            }
        }
        val r = rect(box); val density = context.resources.displayMetrics.density
        val scale = minOf(r.width * density / width.coerceAtLeast(1),
            r.height * density / height.coerceAtLeast(1)).coerceIn(.1f, 4f)
        val movement = burnIn.movement.value
        val x = r.left * density + (r.width * density - width * scale) / 2
        val y = r.top * density + (r.height * density - height * scale) / 2
        val viewport = root ?: return
        val dx = if (aod) AodDisplayLayoutGeometry.boundedOffset(
            x, width * scale, viewport.width.toFloat(), movement.translationX.toFloat()) else 0f
        val dy = if (aod) AodDisplayLayoutGeometry.boundedOffset(
            y, height * scale, viewport.height.toFloat(), movement.translationY.toFloat()) else 0f
        for (v in measurable) {
            val original = original(v)
            if (!box.visible) { hide(v, original); continue }
            show(v, original)
            v.pivotX = 0f; v.pivotY = 0f
            original.appliedX = x + (v.left - left) * scale - v.left + dx
            original.appliedY = y + (v.top - top) * scale - v.top + dy
            original.appliedSx = scale; original.appliedSy = scale
            v.translationX = checkNotNull(original.appliedX); v.translationY = checkNotNull(original.appliedY)
            v.scaleX = scale; v.scaleY = scale
        }
    }
    override fun applyConstraints(constraintSet: ConstraintSet) {
        val i = index()
        if (i < 0) return
        val density = context.resources.displayMetrics.density
        val date = rect(layouts.profiles[i].boxes[AodDisplayLayoutStore.DATE])
        // These native containers fill the screen and can contain mostly blank width. Reflow
        // them into the selected column before fitting so their text is not compressed.
        listOf(R.id.keyguard_slice_view, sharedR.id.bc_smartspace_view).forEach { id ->
            if (root?.findViewById<View>(id)?.parent === root) constraintSet.apply {
                constrainWidth(id, (date.width * density).toInt())
                clear(id, ConstraintSet.END)
                connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START,
                    (date.left * density).toInt())
            }
        }
        if (i < 2) return
        val box = layouts.profiles[i].boxes[AodDisplayLayoutStore.NOTIFICATIONS]
        val r = rect(box)
        constraintSet.apply {
            val id = R.id.nssl_placeholder
            clear(id, ConstraintSet.TOP); clear(id, ConstraintSet.BOTTOM)
            connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, (r.top * density).toInt())
            constrainHeight(id, if (box.visible) (r.height * density).toInt() else 0)
        }
    }
    override fun removeViews(constraintLayout: ConstraintLayout) {
        restore(); bindings.forEach { it.dispose() }; bindings.clear()
        constraintLayout.viewTreeObserver.removeOnPreDrawListener(preDraw)
        context.contentResolver.unregisterContentObserver(observer); handler.removeCallbacks(refresh)
        previewSource.detach(constraintLayout)
        root = null
    }
    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("customProfile=${index()} transformedViews=${saved.size} doze=$doze")
        val i = index()
        if (i >= 0) layouts.profiles[i].boxes.forEachIndexed { module, box ->
            val r = rect(box)
            pw.println("module=$module visible=${box.visible} boundsDp=${r.left},${r.top},${r.width},${r.height}")
        }
        saved.keys.forEach { v ->
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.id.toString())
            pw.println("view=$name size=${v.width}x${v.height} position=${v.x},${v.y} scale=${v.scaleX},${v.scaleY} visibility=${v.visibility}")
        }
    }
}
