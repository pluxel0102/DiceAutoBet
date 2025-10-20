package com.example.diceautobet.utils

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.diceautobet.models.WindowType
import com.example.diceautobet.models.SplitScreenType

/**
 * Утилита для работы с разделенным экраном и определения границ окон
 */
object SplitScreenUtils {
    
    private const val TAG = "SplitScreenUtils"
    
    /**
     * Определяет границы окна для указанного типа на разделенном экране
     */
    fun getWindowBounds(context: Context, windowType: WindowType): Rect {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        Log.d(TAG, "Размер экрана: ${screenWidth}x${screenHeight}")
        
        return when (windowType) {
            WindowType.LEFT -> {
                // Левая половина экрана (горизонтальное разделение)
                val bounds = Rect(0, 0, screenWidth / 2, screenHeight)
                Log.d(TAG, "Границы левого окна: $bounds")
                bounds
            }
            WindowType.RIGHT -> {
                // Правая половина экрана (горизонтальное разделение)
                val bounds = Rect(screenWidth / 2, 0, screenWidth, screenHeight)
                Log.d(TAG, "Границы правого окна: $bounds")
                bounds
            }
            WindowType.TOP -> {
                // Верхняя половина экрана (вертикальное разделение)
                val bounds = Rect(0, 0, screenWidth, screenHeight / 2)
                Log.d(TAG, "Границы верхнего окна: $bounds")
                bounds
            }
            WindowType.BOTTOM -> {
                // Нижняя половина экрана (вертикальное разделение)
                val bounds = Rect(0, screenHeight / 2, screenWidth, screenHeight)
                Log.d(TAG, "Границы нижнего окна: $bounds")
                bounds
            }
        }
    }
    
    /**
     * Проверяет, находится ли точка в границах указанного окна
     */
    fun isPointInWindow(context: Context, windowType: WindowType, x: Int, y: Int): Boolean {
        val bounds = getWindowBounds(context, windowType)
        val result = bounds.contains(x, y)
        Log.v(TAG, "Точка ($x, $y) в окне $windowType: $result")
        return result
    }
    
    /**
     * Определяет, к какому окну относится область экрана
     */
    fun detectWindowType(context: Context, area: Rect): WindowType? {
        val centerX = area.centerX()
        val centerY = area.centerY()
        
        return when {
            isPointInWindow(context, WindowType.LEFT, centerX, centerY) -> WindowType.LEFT
            isPointInWindow(context, WindowType.RIGHT, centerX, centerY) -> WindowType.RIGHT
            else -> {
                Log.w(TAG, "Не удалось определить тип окна для области: $area")
                null
            }
        }
    }
    
    /**
     * Преобразует абсолютные координаты в координаты относительно окна
     */
    fun convertToWindowCoordinates(context: Context, windowType: WindowType, absoluteRect: Rect): Rect {
        val windowBounds = getWindowBounds(context, windowType)
        
        val relativeRect = Rect(
            absoluteRect.left - windowBounds.left,
            absoluteRect.top - windowBounds.top,
            absoluteRect.right - windowBounds.left,
            absoluteRect.bottom - windowBounds.top
        )
        
        Log.d(TAG, "Преобразование координат для $windowType: $absoluteRect -> $relativeRect")
        return relativeRect
    }
    
    /**
     * Преобразует координаты относительно окна в абсолютные координаты
     */
    fun convertToAbsoluteCoordinates(context: Context, windowType: WindowType, windowRect: Rect): Rect {
        val windowBounds = getWindowBounds(context, windowType)
        
        val absoluteRect = Rect(
            windowRect.left + windowBounds.left,
            windowRect.top + windowBounds.top,
            windowRect.right + windowBounds.left,
            windowRect.bottom + windowBounds.top
        )
        
        Log.d(TAG, "Преобразование в абсолютные координаты для $windowType: $windowRect -> $absoluteRect")
        return absoluteRect
    }
    
    /**
     * Проверяет, поддерживается ли разделенный экран
     */
    fun isSplitScreenSupported(context: Context): Boolean {
        // Проверяем разрешение экрана и другие параметры
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Минимальные требования для разделенного экрана
        val minWidth = 1000
        val minHeight = 600
        
        val supported = screenWidth >= minWidth && screenHeight >= minHeight
        Log.d(TAG, "Поддержка разделенного экрана: $supported (${screenWidth}x${screenHeight})")
        
        return supported
    }
    
    /**
     * Получает параметры разделенного экрана
     */
    fun getSplitScreenInfo(context: Context): SplitScreenInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        return SplitScreenInfo(
            totalWidth = screenWidth,
            totalHeight = screenHeight,
            density = density,
            leftBounds = getWindowBounds(context, WindowType.LEFT),
            rightBounds = getWindowBounds(context, WindowType.RIGHT),
            isSupported = isSplitScreenSupported(context)
        )
    }
    
    /**
     * Информация о разделенном экране
     */
    data class SplitScreenInfo(
        val totalWidth: Int,
        val totalHeight: Int,
        val density: Float,
        val leftBounds: Rect,
        val rightBounds: Rect,
        val isSupported: Boolean
    ) {
        fun getWindowWidth(): Int = totalWidth / 2
        fun getWindowHeight(): Int = totalHeight
        
        override fun toString(): String {
            return "SplitScreenInfo(${totalWidth}x${totalHeight}, density=$density, supported=$isSupported)"
        }
    }
    
    /**
     * Получает типы окон на основе типа разделения экрана
     */
    fun getWindowTypes(splitScreenType: SplitScreenType): Pair<WindowType, WindowType> {
        return when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> Pair(WindowType.LEFT, WindowType.RIGHT)
            SplitScreenType.VERTICAL -> Pair(WindowType.TOP, WindowType.BOTTOM)
        }
    }
    
    /**
     * Получает информацию о разделенном экране с учетом типа разделения
     */
    fun getSplitScreenInfo(context: Context, splitScreenType: SplitScreenType): SplitScreenInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        val (firstWindow, secondWindow) = getWindowTypes(splitScreenType)
        
        return SplitScreenInfo(
            totalWidth = screenWidth,
            totalHeight = screenHeight,
            density = density,
            leftBounds = getWindowBounds(context, firstWindow),
            rightBounds = getWindowBounds(context, secondWindow),
            isSupported = isSplitScreenSupported(context)
        )
    }
}
