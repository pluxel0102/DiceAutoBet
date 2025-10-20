package com.example.diceautobet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class ClickVisualizationView(context: Context, private val x: Int, private val y: Int) : View(context) {
    private val paint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(x.toFloat(), y.toFloat(), 80f, paint)
        canvas.drawCircle(x.toFloat(), y.toFloat(), 80f, borderPaint)
    }
} 