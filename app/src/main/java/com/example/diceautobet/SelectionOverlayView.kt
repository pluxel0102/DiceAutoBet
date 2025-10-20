package com.example.diceautobet

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FF5722")
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33FF5722")
    }
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isSelecting = false
    private var selection: Rect? = null
    
    // Для отображения границ окна в двойном режиме
    private var windowBounds: Rect? = null
    private val windowBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#4CAF50")
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    
    init { setWillNotDraw(false) }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Работаем в абсолютных координатах экрана. rawX / rawY уже содержат
        // положение с учётом всех системных элементов (status bar / navigation bar).
        // Поэтому нам НЕ нужно вычитать высоту status bar вручную – это приводило
        // к смещению выделенной области и, как следствие, к неверному клику.
        // Оставляем лог для отладки, но без корректировки координат.
        Log.d("SelectionOverlay", "ACTION event received – operating in raw screen coords")
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                endX = startX
                endY = startY
                isSelecting = true
                Log.d("SelectionOverlay", "ACTION_DOWN: rawX=${event.rawX}, adjustedY=${startY}")
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSelecting) {
                    endX = event.rawX
                    endY = event.rawY
                    Log.d("SelectionOverlay", "ACTION_MOVE: rawX=${event.rawX}, adjustedY=${endY}")
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isSelecting) {
                    endX = event.rawX
                    endY = event.rawY
                    isSelecting = false
                    
                    val left = kotlin.math.min(startX, endX).toInt()
                    val top = kotlin.math.min(startY, endY).toInt()
                    val right = kotlin.math.max(startX, endX).toInt()
                    val bottom = kotlin.math.max(startY, endY).toInt()
                    
                    if (right - left > 20 && bottom - top > 20) {
                        selection = Rect(left, top, right, bottom)
                        Log.d("SelectionOverlay", "Создано выделение: $selection (absolute screen coords)")
                    }
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        Log.d("SelectionOverlay", "onDraw called - windowBounds: $windowBounds, isSelecting: $isSelecting, selection: $selection")
        
        // Рисуем границы окна в двойном режиме
        windowBounds?.let { bounds ->
            val location = IntArray(2)
            getLocationOnScreen(location)
            
            val windowRect = RectF(
                bounds.left.toFloat() - location[0],
                bounds.top.toFloat() - location[1],
                bounds.right.toFloat() - location[0],
                bounds.bottom.toFloat() - location[1]
            )
            
            canvas.drawRect(windowRect, windowBorderPaint)
            
            // Добавляем текст-подсказку
            val textPaint = Paint().apply {
                color = Color.parseColor("#4CAF50")
                textSize = 48f
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            
            val text = "Выберите область в этой зоне"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            
            val textX = windowRect.centerX() - textBounds.width() / 2
            val textY = windowRect.top + 60
            
            // Фон для текста
            val backgroundPaint = Paint().apply {
                color = Color.parseColor("#88000000")
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                textX - 20, textY - textBounds.height() - 10,
                textX + textBounds.width() + 20, textY + 10,
                backgroundPaint
            )
            
            canvas.drawText(text, textX, textY, textPaint)
        }
        
        // Для single mode показываем подсказку по всему экрану
        if (windowBounds == null) {
            val textPaint = Paint().apply {
                color = Color.parseColor("#2196F3") // Синий цвет
                textSize = 48f
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            
            val text = "Выберите область на экране"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            
            val textX = (width / 2f) - (textBounds.width() / 2f)
            val textY = 100f
            
            // Фон для текста
            val backgroundPaint = Paint().apply {
                color = Color.parseColor("#AA000000")
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                textX - 20, textY - textBounds.height() - 10,
                textX + textBounds.width() + 20, textY + 10,
                backgroundPaint
            )
            
            canvas.drawText(text, textX, textY, textPaint)
        }
        
        if (isSelecting || selection != null) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            Log.d("SelectionOverlay", "onDraw: View position x=${location[0]}, y=${location[1]}")
            
            val rect = if (isSelecting) {
                RectF(
                    kotlin.math.min(startX, endX) - location[0],
                    kotlin.math.min(startY, endY) - location[1],
                    kotlin.math.max(startX, endX) - location[0],
                    kotlin.math.max(startY, endY) - location[1]
                )
            } else {
                selection?.let { 
                    RectF(
                        it.left.toFloat() - location[0],
                        it.top.toFloat() - location[1],
                        it.right.toFloat() - location[0],
                        it.bottom.toFloat() - location[1]
                    )
                }
            }
            rect?.let {
                canvas.drawRect(it, paint)
                canvas.drawRect(it, fillPaint)
            }
        }
    }

    fun getSelection(): Rect? = selection
    
    fun getAbsoluteSelection(): Rect? = selection
    
    fun getAbsoluteSelectionWithInsets(): Rect? = selection
    
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
    
    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
    
    // Метод для получения информации о позиции view
    fun getViewInfo(): String {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return "View: x=${location[0]}, y=${location[1]}, width=$width, height=$height"
    }
    
    fun clearSelection() {
        selection = null
        invalidate()
    }
    
    /**
     * Устанавливает цвет границы выделения
     */
    fun setBorderColor(color: Int) {
        paint.color = color
        
        // Устанавливаем полупрозрачную заливку того же цвета
        val alpha = (255 * 0.2).toInt() // 20% прозрачности
        fillPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        
        // Обновляем цвет границы окна
        windowBorderPaint.color = color
        
        invalidate()
    }
    
    /**
     * Устанавливает границы окна для отображения в двойном режиме
     */
    fun setWindowBounds(bounds: Rect) {
        windowBounds = bounds
        invalidate()
    }
    
    /**
     * Очищает границы окна
     */
    fun clearWindowBounds() {
        windowBounds = null
        invalidate()
    }
} 