package com.example.diceautobet.utils

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.WindowManager

object CoordinateUtils {
    
    /**
     * Проверяет, что координаты находятся в пределах экрана
     */
    fun validateCoordinates(rect: Rect, context: Context): Boolean {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = context.resources.displayMetrics
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Log.d("CoordinateUtils", "Проверяем координаты: $rect")
        // Log.d("CoordinateUtils", "Размеры экрана: ${screenWidth}x${screenHeight}")
        
        val isValid = rect.left >= 0 && rect.top >= 0 && 
                     rect.right <= screenWidth && rect.bottom <= screenHeight &&
                     rect.width() > 0 && rect.height() > 0
        
        if (!isValid) {
            // Log.w("CoordinateUtils", "Координаты некорректны:")
            // Log.w("CoordinateUtils", "  - left: ${rect.left} (должно быть >= 0)")
            // Log.w("CoordinateUtils", "  - top: ${rect.top} (должно быть >= 0)")
            // Log.w("CoordinateUtils", "  - right: ${rect.right} (должно быть <= $screenWidth)")
            // Log.w("CoordinateUtils", "  - bottom: ${rect.bottom} (должно быть <= $screenHeight)")
            // Log.w("CoordinateUtils", "  - width: ${rect.width()} (должно быть > 0)")
            // Log.w("CoordinateUtils", "  - height: ${rect.height()} (должно быть > 0)")
        } else {
            // Log.d("CoordinateUtils", "Координаты корректны")
        }
        
        return isValid
    }
    
    /**
     * Корректирует координаты, если они выходят за пределы экрана
     */
    fun correctCoordinates(rect: Rect, context: Context): Rect {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val correctedLeft = rect.left.coerceIn(0, screenWidth - 1)
        val correctedTop = rect.top.coerceIn(0, screenHeight - 1)
        val correctedRight = rect.right.coerceIn(correctedLeft + 1, screenWidth)
        val correctedBottom = rect.bottom.coerceIn(correctedTop + 1, screenHeight)
        // Гарантируем правильный порядок
        val left = minOf(correctedLeft, correctedRight)
        val right = maxOf(correctedLeft, correctedRight)
        val top = minOf(correctedTop, correctedBottom)
        val bottom = maxOf(correctedTop, correctedBottom)
        return Rect(left, top, right, bottom)
    }
    
    /**
     * Получает системные отступы
     */
    fun getSystemInsets(context: Context): SystemInsets {
        val statusBarHeight = getStatusBarHeight(context)
        val navigationBarHeight = getNavigationBarHeight(context)
        
        return SystemInsets(statusBarHeight, navigationBarHeight)
    }
    
    /**
     * Конвертирует абсолютные координаты в адаптивные (проценты)
     */
    fun convertToAdaptiveCoordinates(rect: Rect, context: Context): AdaptiveRect {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        val leftPercent = left.toFloat() / screenWidth
        val topPercent = top.toFloat() / screenHeight
        val rightPercent = right.toFloat() / screenWidth
        val bottomPercent = bottom.toFloat() / screenHeight
        return AdaptiveRect(leftPercent, topPercent, rightPercent, bottomPercent)
    }
    
    /**
     * Конвертирует абсолютные координаты в адаптивные относительно границ окна
     */
    fun convertToAdaptiveCoordinates(rect: Rect, windowBounds: Rect): AdaptiveRect {
        val windowWidth = windowBounds.width()
        val windowHeight = windowBounds.height()
        
        // Преобразуем абсолютные координаты в относительные (относительно окна)
        val left = minOf(rect.left, rect.right) - windowBounds.left
        val right = maxOf(rect.left, rect.right) - windowBounds.left
        val top = minOf(rect.top, rect.bottom) - windowBounds.top
        val bottom = maxOf(rect.top, rect.bottom) - windowBounds.top
        
        val leftPercent = left.toFloat() / windowWidth
        val topPercent = top.toFloat() / windowHeight
        val rightPercent = right.toFloat() / windowWidth
        val bottomPercent = bottom.toFloat() / windowHeight
        
        return AdaptiveRect(leftPercent, topPercent, rightPercent, bottomPercent)
    }
    
    /**
     * Конвертирует адаптивные координаты в абсолютные для текущего экрана
     */
    fun convertFromAdaptiveCoordinates(adaptiveRect: AdaptiveRect, context: Context): Rect {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val left = (minOf(adaptiveRect.leftPercent, adaptiveRect.rightPercent) * screenWidth).toInt()
        val right = (maxOf(adaptiveRect.leftPercent, adaptiveRect.rightPercent) * screenWidth).toInt()
        val top = (minOf(adaptiveRect.topPercent, adaptiveRect.bottomPercent) * screenHeight).toInt()
        val bottom = (maxOf(adaptiveRect.topPercent, adaptiveRect.bottomPercent) * screenHeight).toInt()
        return Rect(left, top, right, bottom)
    }
    
    /**
     * Конвертирует адаптивные координаты в абсолютные относительно границ окна
     */
    fun convertFromAdaptiveCoordinates(adaptiveRect: AdaptiveRect, windowBounds: Rect): Rect {
        val windowWidth = windowBounds.width()
        val windowHeight = windowBounds.height()
        
        val left = (minOf(adaptiveRect.leftPercent, adaptiveRect.rightPercent) * windowWidth).toInt()
        val right = (maxOf(adaptiveRect.leftPercent, adaptiveRect.rightPercent) * windowWidth).toInt()
        val top = (minOf(adaptiveRect.topPercent, adaptiveRect.bottomPercent) * windowHeight).toInt()
        val bottom = (maxOf(adaptiveRect.topPercent, adaptiveRect.bottomPercent) * windowHeight).toInt()
        
        // Преобразуем относительные координаты в абсолютные
        return Rect(
            left + windowBounds.left,
            top + windowBounds.top,
            right + windowBounds.left,
            bottom + windowBounds.top
        )
    }
    
    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
    
    private fun getNavigationBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
    
    data class SystemInsets(
        val statusBarHeight: Int,
        val navigationBarHeight: Int
    )
    
    data class AdaptiveRect(
        val leftPercent: Float,
        val topPercent: Float,
        val rightPercent: Float,
        val bottomPercent: Float
    )
} 