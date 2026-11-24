package com.zheyou.majsoul

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal class DraggablePrompt(private val host: View, private val prompt: View, private val margin: Int) {
    private val preferences = host.context.getSharedPreferences("prompt_position", Context.MODE_PRIVATE)
    private var horizontal = preferences.getFloat("x_fraction", 1f)
    private var vertical = preferences.getFloat("y_fraction", 0.5f)
    private val bounds = RectF()
    private var safeInsets = Insets.NONE
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var tracking = false
    private var dragging = false

    init {
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            updateBounds()
            insets
        }
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        prompt.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }
        prompt.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    downX = event.rawX
                    downY = event.rawY
                    originX = view.x
                    originY = view.y
                }
                MotionEvent.ACTION_MOVE -> if (tracking) move(event)
                MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        move(event)
                        if (!dragging && view.isClickable) view.performClick()
                    }
                    finishGesture()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishGesture()
            }
            true
        }
    }

    private fun updateBounds() {
        val farX = (host.width - prompt.width).coerceAtLeast(0).toFloat()
        val farY = (host.height - prompt.height).coerceAtLeast(0).toFloat()
        val left = (safeInsets.left + margin).toFloat().coerceAtMost(farX)
        val top = (safeInsets.top + margin).toFloat().coerceAtMost(farY)
        bounds.set(
            left, top,
            (farX - safeInsets.right - margin).coerceAtLeast(left),
            (farY - safeInsets.bottom - margin).coerceAtLeast(top)
        )
        place()
    }

    private fun move(event: MotionEvent) {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (!dragging && dx * dx + dy * dy <= touchSlop * touchSlop) return
        dragging = true
        if (bounds.width() > 0) {
            horizontal = ((originX + dx - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        }
        if (bounds.height() > 0) {
            vertical = ((originY + dy - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }
        place()
    }

    private fun place() {
        prompt.x = bounds.left + horizontal * bounds.width()
        prompt.y = bounds.top + vertical * bounds.height()
    }

    private fun finishGesture() {
        if (dragging) {
            preferences.edit().putFloat("x_fraction", horizontal).putFloat("y_fraction", vertical).apply()
        }
        tracking = false
        dragging = false
    }
}
