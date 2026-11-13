package com.example.surveillance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ZoneOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val zone = RectF(0f, 0f, 1f, 1f)   // proportions 0..1
    var editing = false

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private var sx = 0f
    private var sy = 0f

    fun setZone(l: Float, t: Float, r: Float, b: Float) {
        zone.set(l, t, r, b)
        invalidate()
    }

    private fun isFull() = zone.left <= 0.001f && zone.top <= 0.001f &&
        zone.right >= 0.999f && zone.bottom >= 0.999f

    override fun onDraw(canvas: Canvas) {
        if (isFull() && !editing) return
        val w = width.toFloat()
        val h = height.toFloat()
        val l = zone.left * w
        val t = zone.top * h
        val r = zone.right * w
        val b = zone.bottom * h
        canvas.drawRect(0f, 0f, w, t, dim)
        canvas.drawRect(0f, b, w, h, dim)
        canvas.drawRect(0f, t, l, b, dim)
        canvas.drawRect(r, t, w, b, dim)
        canvas.drawRect(l, t, r, b, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing || width == 0 || height == 0) return false
        val w = width.toFloat()
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x.coerceIn(0f, w)
                sy = e.y.coerceIn(0f, h)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val x = e.x.coerceIn(0f, w)
                val y = e.y.coerceIn(0f, h)
                zone.set(min(sx, x) / w, min(sy, y) / h, max(sx, x) / w, max(sy, y) / h)
                invalidate()
            }
        }
        return true
    }
}
