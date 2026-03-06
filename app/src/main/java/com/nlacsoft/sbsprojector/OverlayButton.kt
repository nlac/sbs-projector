package com.nlacsoft.sbsprojector

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent

class OverlayButton(private val text: String, private val bgCol: Int, private val rect: RectF) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgCol }
    private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 32f
                isFakeBoldText = true
            }

    private var pressing = false

    fun updateRect(left: Float, top: Float, right: Float, bottom: Float) {
        rect.set(left, top, right, bottom)
    }

    fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawText(text, rect.centerX(), rect.top + rect.height() * 0.68f, textPaint)
    }

    /** Returns true if the event was consumed by this button. Calls [action] on confirmed tap. */
    fun hitTest(event: MotionEvent, action: () -> Unit): Boolean {
        val inside = event.x in rect.left..rect.right && event.y in rect.top..rect.bottom
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inside) { pressing = true; return true }
            MotionEvent.ACTION_MOVE -> if (pressing) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressing) {
                    pressing = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && inside) action()
                    return true
                }
            }
        }
        return false
    }
}
