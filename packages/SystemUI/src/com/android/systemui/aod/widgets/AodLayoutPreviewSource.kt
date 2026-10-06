/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import java.lang.ref.WeakReference
import javax.inject.Inject
import com.android.systemui.shared.R as sharedR

/** In-process native content only. No screenshot files or placeholder notification text. */
@SysUISingleton
class AodLayoutPreviewSource @Inject constructor(
    private val clock: KeyguardClockViewModel,
    private val notifications: SharedNotificationContainer,
    private val indications: KeyguardIndicationController,
) {
    private var root = WeakReference<ConstraintLayout>(null)
    data class Content(val bitmap: Bitmap, val bounds: Rect)
    fun attach(view: ConstraintLayout) { root = WeakReference(view) }
    fun detach(view: ConstraintLayout) { if (root.get() === view) root.clear() }
    val largeClock: Boolean get() = clock.isLargeClockVisible.value

    fun captureClock(controller: ClockController): Content? {
        val live = clock.currentClock.value ?: return null
        val template = if (largeClock) live.largeClock else live.smallClock
        val face = if (largeClock) controller.largeClock else controller.smallClock
        face.events.onTimeTick()
        val originals = template.layout.views
        val previews = face.layout.views
        if (originals.size != previews.size || originals.isEmpty()) return null
        originals.zip(previews).forEach { (original, preview) ->
            if (original.width <= 0 || original.height <= 0) return null
            if (preview is TextView && original is TextView) {
                // The native face may have adjusted its font to fit a display/clock setting.
                // Measure independent text at that size; a cached hidden-view width can belong
                // to the previous time string and must not clip the last digit.
                if (original.textSize > 8f) preview.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX, original.textSize)
                preview.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                preview.layout(original.left, original.top, original.left + preview.measuredWidth,
                    original.top + preview.measuredHeight)
            } else {
                preview.measure(View.MeasureSpec.makeMeasureSpec(original.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(original.height, View.MeasureSpec.EXACTLY))
                preview.layout(original.left, original.top, original.right, original.bottom)
            }
        }
        return draw(previews, AodDisplayLayoutStore.TIME)
    }

    fun capture(module: Int, aod: Boolean): Content? {
        val view = root.get() ?: return null
        if (module == AodDisplayLayoutStore.NOTIFICATIONS && aod) {
            val area = view.findViewById<android.view.ViewGroup>(R.id.aod_notification_icon_container)
                ?: return null
            // Keyguard fades the icon container while this unlocked editor is foreground.
            // Draw each current drawable directly, without mutating native view alpha/visibility.
            val icons = (0 until area.childCount).map(area::getChildAt)
                .filterIsInstance<android.widget.ImageView>()
                .filter { it.width > 0 && it.height > 0 && it.drawable != null }
            if (icons.isEmpty() || area.width <= 0 || area.height <= 0) return null
            val bitmap = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            icons.forEach { icon ->
                // NotificationIconContainer positions icons with translations, not child layout.
                canvas.save()
                canvas.translate(icon.left + icon.translationX, icon.top + icon.translationY)
                icon.draw(canvas)
                canvas.restore()
            }
            return Content(bitmap, Rect(area.left, area.top, area.right, area.bottom))
        }
        if (module == AodDisplayLayoutStore.INDICATION) {
            val message = indications.getIndicationForPreview(aod) ?: return null
            val area = view.findViewById<android.view.ViewGroup>(R.id.keyguard_indication_area) ?: return null
            val template = (0 until area.childCount).map(area::getChildAt).filterIsInstance<TextView>().firstOrNull() ?: return null
            val text = TextView(view.context).apply {
                this.text = message
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.textSize)
                typeface = template.typeface
                gravity = template.gravity
                includeFontPadding = template.includeFontPadding
                letterSpacing = template.letterSpacing
                setTextColor(android.graphics.Color.WHITE)
            }
            val width = area.width.coerceAtLeast(1)
            text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            text.layout(area.left, area.top, area.left + width, area.top + text.measuredHeight)
            return draw(listOf(text), AodDisplayLayoutStore.TIME)
        }
        val views = when (module) {
            AodDisplayLayoutStore.TIME -> {
                val controller = clock.currentClock.value ?: return null
                val face = if (largeClock) controller.largeClock else controller.smallClock
                face.layout.views
            }
            AodDisplayLayoutStore.DATE -> listOfNotNull(
                view.findViewById<View>(sharedR.id.date_smartspace_view),
                view.findViewById<View>(sharedR.id.date_smartspace_view_large),
                view.findViewById<View>(sharedR.id.bc_smartspace_view),
                view.findViewById<View>(R.id.keyguard_slice_view))
            AodDisplayLayoutStore.NOTIFICATIONS -> if (aod) listOfNotNull(
                view.findViewById<View>(R.id.aod_notification_icon_container)) else listOfNotNull(
                notifications.findViewById<View>(R.id.notification_stack_scroller))
            AodDisplayLayoutStore.INDICATION -> listOfNotNull(
                view.findViewById<View>(R.id.keyguard_indication_area))
            else -> emptyList()
        }.filter { it.width > 0 && it.height > 0 && (module == AodDisplayLayoutStore.TIME || module == AodDisplayLayoutStore.NOTIFICATIONS
                || module == AodDisplayLayoutStore.INDICATION || it.visibility != View.GONE) }
        if (views.isEmpty()) return null
        return draw(views, module)
    }
    private fun draw(views: List<View>, module: Int): Content? {
        if (views.isEmpty()) return null
        val bounds = Rect(views.minOf { it.left }, views.minOf { it.top },
            views.maxOf { it.right }, views.maxOf { it.bottom })
        if (module == AodDisplayLayoutStore.INDICATION) {
            val area = views.singleOrNull() as? android.view.ViewGroup
            val rows = area?.let { (0 until it.childCount).map(it::getChildAt) }
                ?.filterIsInstance<TextView>()?.filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
                ?: emptyList()
            if (rows.isEmpty()) return null
            bounds.top = checkNotNull(area).top + rows.minOf { it.top }
            bounds.bottom = area.top + rows.maxOf { it.bottom }
        }
        return try {
            val bitmap = Bitmap.createBitmap(bounds.width().coerceAtLeast(1),
                bounds.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            views.forEach {
                canvas.save()
                canvas.translate((it.left - bounds.left).toFloat(), (it.top - bounds.top).toFloat())
                it.draw(canvas)
                canvas.restore()
            }
            Content(bitmap, bounds)
        } catch (e: RuntimeException) {
            Log.w("AodLayoutPreview", "Cannot draw module $module", e)
            null
        }
    }
}
