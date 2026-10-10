/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import javax.inject.Inject
import kotlin.math.roundToInt

/** Immersive editor. Provider content and geometry update on every drag; Back drops the draft. */
class AodLayoutEditorActivity @Inject constructor(
    private val source: AodLayoutPreviewSource,
    private val clockRegistry: ClockRegistry,
) : Activity() {
    private lateinit var draft: AodDisplayLayoutStore.Layouts
    private lateinit var canvas: EditorCanvas
    private lateinit var clockHost: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var targetButton: Button
    private lateinit var moduleButton: Button
    private lateinit var enabled: CheckBox
    private lateinit var visible: CheckBox
    private lateinit var previewButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var target = 0
    private var selected = 0
    private var previewOnly = false
    private var updatingControls = false
    private var listening = false
    private var previewClock: ClockController? = null
    private var clockTarget = -1
    private val ids = mutableListOf<Int>()
    private val widgetViews = linkedMapOf<Int, AppWidgetHostView>()
    private val sizes = mutableMapOf<Int, Pair<Int, Int>>()
    private val native = mutableMapOf<Int, AodLayoutPreviewSource.Content>()
    private val host by lazy {
        object : AppWidgetHost(this, AodWidgetStore.HOST_ID) {
            override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo) =
                object : AppWidgetHostView(c) {
                    override fun onDefaultViewClicked(view: View) { }
                }
        }.apply { setInteractionHandler { _, _, _ -> false } }
    }
    private val tick = object : Runnable {
        override fun run() {
            previewClock?.let { source.captureClock(it)?.let { content -> native[0] = content } }
            for (module in intArrayOf(1, 2, 4)) {
                val content = source.capture(module, target < 2)
                if (content == null) native.remove(module) else native[module] = content
            }
            canvas.requestLayout()
            canvas.invalidate()
            handler.postDelayed(this, 1000)
        }
    }
    private fun profile() = draft.profiles[target]
    private fun box(index: Int = selected): AodDisplayLayoutStore.Box =
        if (index < 4) profile().boxes[if (index == 3) 4 else index]
        else AodDisplayLayoutStore.editWidgetBox(profile(), ids[index - 4], index - 4)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            finish()
            return
        }
        draft = if (state == null) AodDisplayLayoutStore.load(this)
            else AodDisplayLayoutStore.decode(state.getString("draft"))
        target = state?.getInt("target") ?: if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) 1 else 0
        selected = state?.getInt("selected") ?: 0
        previewOnly = state?.getBoolean("preview") ?: false
        ids.addAll(AodWidgetStore.widgetIds(this))
        // Prune deleted IDs before materializing new instances (keeps the schema bounded).
        draft.profiles.forEach { it.widgets.keys.retainAll(ids.toSet()) }
        if (selected >= ids.size + 4) selected = 0
        val manager = AppWidgetManager.getInstance(this)
        ids.forEach { id ->
            manager.getAppWidgetInfo(id)?.takeIf {
                host.appWidgetIds.contains(id) && it.profile.identifier == UserHandle.USER_SYSTEM &&
                    it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD == 0
            }?.let {
                widgetViews[id] = host.createView(this, id, it).apply {
                    setOnLightBackground(false)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.setNavigationBarContrastEnforced(false)
        canvas = EditorCanvas(this)
        widgetViews.values.forEach { canvas.addView(it) }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        // Clock plugins initialise glyph renderers on attachment. Keep independent preview
        // faces attached but out of the visible/a11y scene; the source draws them at native size.
        clockHost = object : FrameLayout(this) {
            // Attachment only: measuring faces into this 1px host would shrink clock glyphs.
            override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(1, 1) }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { }
        }.apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        root.addView(clockHost, FrameLayout.LayoutParams(1, 1))
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xdc202020.toInt())
        }
        val grip = TextView(this).apply {
            text = "━"
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.aod_layout_move_controls)
            setTextColor(0xff8acbff.toInt())
            var startY = 0f; var panelY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = event.rawY; panelY = controls.translationY }
                    MotionEvent.ACTION_MOVE -> controls.translationY =
                        (panelY + event.rawY - startY).coerceIn(-controls.top.toFloat(),
                            (resources.displayMetrics.heightPixels - controls.height - controls.top).toFloat().coerceAtLeast(0f))
                    MotionEvent.ACTION_UP -> view.performClick()
                }
                true
            }
        }
        controls.addView(grip, LinearLayout.LayoutParams(-1, dp(24)))
        val top = LinearLayout(this)
        targetButton = button(R.string.aod_layout_target) { chooseTarget() }
        moduleButton = button(R.string.aod_layout_module) { chooseModule() }
        top.addView(targetButton, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(moduleButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(top)
        val toggles = LinearLayout(this)
        enabled = CheckBox(this).apply {
            setText(R.string.aod_layout_enabled)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingControls) { profile().enabled = checked; canvas.requestLayout(); canvas.invalidate() }
            }
        }
        visible = CheckBox(this).apply {
            setText(R.string.aod_layout_visible)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingControls) {
                    box().visible = checked
                    if (selected < 4) profile().enabled = true
                    refresh()
                }
            }
        }
        toggles.addView(enabled, LinearLayout.LayoutParams(0, -2, 1f))
        toggles.addView(visible, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(toggles)
        val actions = LinearLayout(this)
        actions.addView(button(R.string.aod_layout_precision) { precision() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(R.string.aod_widgets_reset_layout) {
            AlertDialog.Builder(this).setMessage(R.string.aod_layout_reset)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    draft.profiles[target] = AodDisplayLayoutStore.defaults(target)
                    refresh()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(R.string.aod_widgets_save_layout) {
            val active = AodWidgetStore.widgetIds(this).toSet()
            draft.profiles.forEach { it.widgets.keys.retainAll(active) }
            AodDisplayLayoutStore.save(this, draft)
            finish()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(actions)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(32) })
        previewButton = button(R.string.aod_layout_preview_only) {
            previewOnly = !previewOnly
            refresh()
        }
        root.addView(previewButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(24); rightMargin = dp(16)
        })
        setContentView(root)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestedOrientation = if (target % 2 != 0) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        refresh()
    }
    override fun onStart() {
        super.onStart()
        if (!::canvas.isInitialized) return
        try { AodWidgetStore.startListening(host); listening = true }
        catch (e: RuntimeException) { android.util.Log.w("AodLayoutEditor", "Widget host unavailable", e) }
        handler.post(tick)
    }
    override fun onStop() {
        handler.removeCallbacks(tick)
        // Size notifications are live during editing, but cancellation restores persisted sizes.
        if (::canvas.isInitialized && canvas.width > 0 && canvas.height > 0) {
            val saved = AodDisplayLayoutStore.load(this).profiles[target]
            val density = resources.displayMetrics.density
            widgetViews.forEach { (id, view) ->
                val ordinal = ids.indexOf(id)
                val box = AodDisplayLayoutStore.widgetBox(saved, id, ordinal)
                val r = AodDisplayLayoutGeometry.resolve(canvas.width / density, canvas.height / density,
                    box.x, box.y, box.width, box.height)
                val width = r.width.toInt().coerceAtLeast(1)
                val height = r.height.toInt().coerceAtLeast(1)
                try { view.updateAppWidgetSize(null, width, height, width, height, true) }
                catch (e: RuntimeException) { android.util.Log.w("AodLayoutEditor", "Cannot restore $id", e) }
            }
            sizes.clear()
        }
        if (listening) AodWidgetStore.stopListening(host)
        listening = false
        super.onStop()
    }
    override fun onDestroy() {
        widgetViews.keys.forEach { host.removeListener(it) }
        widgetViews.clear()
        native.clear()
        super.onDestroy()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("draft", AodDisplayLayoutStore.encode(draft))
        state.putInt("target", target)
        state.putInt("selected", selected)
        state.putBoolean("preview", previewOnly)
        super.onSaveInstanceState(state)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun button(title: Int, action: () -> Unit) = Button(this).apply {
        setText(title); setOnClickListener { action() }
    }
    private fun labels(): Array<String> {
        val nativeLabels = resources.getStringArray(R.array.aod_layout_modules)
        val manager = AppWidgetManager.getInstance(this)
        return (listOf(nativeLabels[0], nativeLabels[1], nativeLabels[2], nativeLabels[4]) +
            ids.mapIndexed { i, id -> "${i + 1}. ${manager.getAppWidgetInfo(id)?.loadLabel(packageManager) ?: getString(R.string.aod_widgets_widget)}" }).toTypedArray()
    }
    private fun chooseTarget() {
        AlertDialog.Builder(this).setTitle(R.string.aod_layout_target)
            .setSingleChoiceItems(R.array.aod_layout_targets, target) { dialog, which ->
                target = which
                native.clear()
                val landscape = target % 2 != 0
                requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                refresh()
                dialog.dismiss()
            }.show()
    }
    private fun chooseModule() {
        AlertDialog.Builder(this).setTitle(R.string.aod_layout_module)
            .setSingleChoiceItems(labels(), selected) { dialog, which ->
                selected = which; refresh(); dialog.dismiss()
            }.show()
    }
    private fun refresh() {
        if (clockTarget != target) {
            previewClock = clockRegistry.createCurrentClock(this).apply {
                initialize(true, if (target < 2) 1f else 0f, 0f)
                smallClock.events.onFontSettingChanged(resources.getDimensionPixelSize(
                    com.android.systemui.customization.clocks.R.dimen.small_clock_text_size).toFloat())
                largeClock.events.onFontSettingChanged(resources.getDimensionPixelSize(
                    com.android.systemui.customization.clocks.R.dimen.large_clock_text_size).toFloat())
                events.onTimeFormatChanged(com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind.getFromContext(this@AodLayoutEditorActivity))
            }
            clockHost.removeAllViews()
            previewClock?.let { clock ->
                (clock.smallClock.layout.views + clock.largeClock.layout.views).distinct().forEach {
                    (it.parent as? android.view.ViewGroup)?.removeView(it)
                    clockHost.addView(it)
                }
            }
            clockTarget = target
        }
        targetButton.text = resources.getStringArray(R.array.aod_layout_targets)[target]
        moduleButton.text = labels()[selected]
        updatingControls = true
        enabled.isChecked = profile().enabled
        visible.isChecked = box().visible
        updatingControls = false
        controls.visibility = if (previewOnly) View.GONE else View.VISIBLE
        previewButton.setText(if (previewOnly) R.string.aod_layout_show_controls else R.string.aod_layout_preview_only)
        canvas.requestLayout()
        canvas.invalidate()
    }
    private fun precision() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val titles = intArrayOf(R.string.aod_widgets_position_x, R.string.aod_widgets_position_y,
            R.string.aod_widgets_width, R.string.aod_widgets_height)
        val b = box()
        val values = intArrayOf(b.x, b.y, b.width, b.height)
        titles.forEachIndexed { i, title ->
            val label = TextView(this).apply { text = "${getString(title)} ${values[i]}%" }
            content.addView(label)
            content.addView(SeekBar(this).apply {
                min = if (i < 2) 0 else 5; max = 100; progress = values[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        when (i) { 0 -> b.x = value; 1 -> b.y = value; 2 -> b.width = value; 3 -> b.height = value }
                        if (selected < 4) profile().enabled = true
                        label.text = "${getString(title)} $value%"
                        refresh()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) { }
                    override fun onStopTrackingTouch(bar: SeekBar) { }
                })
            })
        }
        AlertDialog.Builder(this).setTitle(labels()[selected]).setView(content)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private inner class EditorCanvas(context: Context) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rects = mutableMapOf<Int, RectF>()
        private var downX = 0f; private var downY = 0f
        private var initial: AodDisplayLayoutStore.Box? = null
        private var initialRect = RectF()
        private var dragging = false; private var resizing = false
        private val snapshots = mutableMapOf<Int, android.graphics.Bitmap>()
        init { setWillNotDraw(false); setBackgroundColor(Color.BLACK) }
        private val density get() = resources.displayMetrics.density
        private fun resolve(b: AodDisplayLayoutStore.Box): RectF {
            val r = AodDisplayLayoutGeometry.resolve(width / density, height / density,
                b.x, b.y, b.width, b.height)
            return RectF(r.left * density, r.top * density, (r.left + r.width) * density, (r.top + r.height) * density)
        }
        private fun bounds(index: Int): RectF {
            if (index >= 4 || profile().enabled) return resolve(box(index))
            val module = if (index == 3) 4 else index
            val r = native[module]?.bounds ?: return resolve(box(index))
            return RectF(r)
        }
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
            widgetViews.forEach { (id, view) ->
                val i = ids.indexOf(id)
                if (i < 0) return@forEach
                val b = box(i + 4)
                val r = AodDisplayLayoutGeometry.resolve(measuredWidth / density, measuredHeight / density,
                    b.x, b.y, b.width, b.height)
                val width = (r.width * density).roundToInt().coerceAtLeast(1)
                val height = (r.height * density).roundToInt().coerceAtLeast(1)
                view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                val size = Pair((width / density).toInt().coerceAtLeast(1), (height / density).toInt().coerceAtLeast(1))
                if (sizes[id] != size) {
                    sizes[id] = size
                    // Keep provider IPC outside measure/layout; coalesce to the latest frame size.
                    handler.post {
                        if (!isDestroyed && sizes[id] == size) {
                            try { view.updateAppWidgetSize(null, size.first, size.second, size.first, size.second, true) }
                            catch (e: RuntimeException) { android.util.Log.w("AodLayoutEditor", "Cannot resize $id", e) }
                        }
                    }
                }
            }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            rects.clear()
            for (i in 0 until ids.size + 4) rects[i] = bounds(i)
            widgetViews.forEach { (id, view) ->
                val i = ids.indexOf(id) + 4
                val rect = checkNotNull(rects[i])
                view.layout(rect.left.roundToInt(), rect.top.roundToInt(),
                    rect.left.roundToInt() + view.measuredWidth, rect.top.roundToInt() + view.measuredHeight)
                view.visibility = if (box(i).visible) View.VISIBLE else View.INVISIBLE
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            for (i in 0 until 4) {
                val module = if (i == 3) 4 else i
                val content = native[module] ?: continue
                val destination = bounds(i)
                rects[i] = destination
                if (profile().enabled && !box(i).visible) continue
                val scale = minOf(destination.width() / content.bitmap.width,
                    destination.height() / content.bitmap.height).coerceIn(.1f, 4f)
                val w = content.bitmap.width * scale; val h = content.bitmap.height * scale
                val fitted = RectF(destination.centerX() - w / 2, destination.centerY() - h / 2,
                    destination.centerX() + w / 2, destination.centerY() + h / 2)
                // AOD shares one brightness across native modules and widgets.
                paint.colorFilter = if (target < 2) ColorMatrixColorFilter(AodWidgetAppearance
                    .brightnessMatrix(AodWidgetStore.brightness(this@AodLayoutEditorActivity))) else null
                c.drawBitmap(content.bitmap, null, fitted, paint)
            }
        }
        override fun drawChild(c: Canvas, child: View, drawingTime: Long): Boolean {
            if (target >= 2 || child !is AppWidgetHostView) return super.drawChild(c, child, drawingTime)
            val id = child.appWidgetId
            if (child.width <= 0 || child.height <= 0) return true
            val quality = AodWidgetStore.highQuality(this@AodLayoutEditorActivity)
            val w = AodWidgetAppearance.bitmapDimension(child.width, quality)
            val h = AodWidgetAppearance.bitmapDimension(child.height, quality)
            var bitmap = snapshots[id]
            if (bitmap == null || bitmap.width != w || bitmap.height != h || child.isDirty) {
                try {
                    AodWidgetSnapshotRefresher.refresh(child)
                    bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val offscreen = Canvas(bitmap)
                    offscreen.scale(w.toFloat() / child.width, h.toFloat() / child.height)
                    child.draw(offscreen)
                    snapshots[id] = bitmap
                } catch (e: RuntimeException) {
                    android.util.Log.w("AodLayoutEditor", "Cannot preview $id", e)
                    snapshots.remove(id)
                    return true
                }
            }
            val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            tint.colorFilter = ColorMatrixColorFilter(AodWidgetAppearance.colorMatrix(
                AodWidgetStore.brightness(this@AodLayoutEditorActivity), AodWidgetStore.saturation(this@AodLayoutEditorActivity)))
            c.drawBitmap(checkNotNull(bitmap), null,
                RectF(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat()), tint)
            return true
        }
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (previewOnly) return
            val rect = rects[selected] ?: return
            paint.color = 0xff8acbff.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
            c.drawRect(rect, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(rect.right, rect.bottom, dp(9).toFloat(), paint)
        }
        // The shield captures all input before children; preview actions never launch apps.
        override fun onInterceptTouchEvent(e: MotionEvent) = true
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (previewOnly) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val rect = rects[selected]
                    resizing = rect != null && Math.hypot((e.x - rect.right).toDouble(), (e.y - rect.bottom).toDouble()) < dp(28)
                    val hit = if (resizing) selected else if (rect?.contains(e.x, e.y) == true) selected
                        else (ids.size + 3 downTo 0).firstOrNull { rects[it]?.contains(e.x, e.y) == true } ?: return true
                    selected = hit
                    if (selected < 4 && !profile().enabled) {
                        // Activate the editable profile explicitly before native modules move.
                        profile().enabled = true
                    }
                    val b = box()
                    initial = AodDisplayLayoutStore.Box(b.x, b.y, b.width, b.height)
                    initialRect = resolve(b)
                    downX = e.x; downY = e.y; dragging = true
                    refresh()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return true
                    val original = initial ?: return true
                    val current = box()
                    val full = AodDisplayLayoutGeometry.resolve(width / density, height / density, 0, 0, 100, 100)
                    val dx = (e.x - downX) / density; val dy = (e.y - downY) / density
                    if (resizing) {
                        current.width = AodDisplayLayoutGeometry.clamp((original.width + dx * 100 / full.width).roundToInt(), 5, 100)
                        current.height = AodDisplayLayoutGeometry.clamp((original.height + dy * 100 / full.height).roundToInt(), 5, 100)
                        // Keep the top-left anchored while the bottom-right handle moves.
                        val resized = AodDisplayLayoutGeometry.resolve(width / density, height / density,
                            0, 0, current.width, current.height)
                        current.x = AodDisplayLayoutGeometry.position(initialRect.left / density, full.left,
                            full.width - resized.width, original.x)
                        current.y = AodDisplayLayoutGeometry.position(initialRect.top / density, full.top,
                            full.height - resized.height, original.y)
                    } else {
                        current.x = AodDisplayLayoutGeometry.position(initialRect.left / density + dx, full.left,
                            full.width - initialRect.width() / density, original.x)
                        current.y = AodDisplayLayoutGeometry.position(initialRect.top / density + dy, full.top,
                            full.height - initialRect.height() / density, original.y)
                    }
                    requestLayout(); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; performClick() }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
