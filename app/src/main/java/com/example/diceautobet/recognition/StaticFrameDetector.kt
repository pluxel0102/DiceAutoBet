package com.example.diceautobet.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.security.MessageDigest

/**
 * 🛡️ Детектор статичных кадров и текстовых оверлеев
 * 
 * Предназначен для детекции:
 * - Статичных надписей ("ОЖИДАНИЕ ПАРТИИ")
 * - Зависаний на одном изображении
 * - Текстовых оверлеев
 * 
 * Использует комбинацию методов:
 * 1. Анализ яркости центральной области (детекция белого текста)
 * 2. MD5 хэш + таймаут (детекция зависания на одном кадре)
 */
object StaticFrameDetector {
    private const val TAG = "StaticFrameDetector"
    
    // 🎯 Настройки детекции
    private const val BRIGHT_THRESHOLD = 200 // Порог яркости для текста (0-255)
    private const val BRIGHT_PERCENTAGE_THRESHOLD = 20 // % ярких пикселей (реально 15.5% для "ОЖИДАНИЕ ПАРТИИ")
    private const val STATIC_IMAGE_TIMEOUT = 3000L // 3 секунды без изменений
    
    // 📊 Состояние для детекции зависаний
    private var lastStableHash: String? = null
    private var lastStableTime: Long = 0
    
    /**
     * 🔍 Главная функция: проверяет, нужно ли пропустить кадр
     * 
     * @param bitmap Изображение для проверки
     * @param checkTextOverlay Проверять ли текстовые оверлеи (true для области кубиков, false для полного экрана)
     */
    fun shouldSkipFrame(bitmap: Bitmap, checkTextOverlay: Boolean = true): Boolean {
        Log.v(TAG, "🛡️ Проверка кадра ${bitmap.width}x${bitmap.height}, checkText=$checkTextOverlay")
        
        // 1️⃣ Быстрая проверка: похоже на текстовый оверлей?
        // Только для вырезанных областей (двойной режим)
        if (checkTextOverlay) {
            val isTextOverlay = isLikelyTextOverlay(bitmap)
            Log.v(TAG, "🔍 Проверка текста: $isTextOverlay")
            if (isTextOverlay) {
                Log.d(TAG, "⏭️ Обнаружен текстовый оверлей (яркая область)")
                return true
            }
        }
        
        // 2️⃣ Проверка: застряли на одном изображении?
        // Работает для всех режимов
        val imageHash = getImageHash(bitmap)
        if (isStuckOnStaticImage(imageHash)) {
            Log.d(TAG, "⏭️ Застряли на статичном изображении >3 сек")
            return true
        }
        
        Log.v(TAG, "✅ Кадр прошел проверку")
        return false
    }
    
    /**
     * 🔍 Детектирует яркие области (вероятно текстовый оверлей)
     * 
     * Анализирует центральную область изображения на наличие большого количества
     * ярких пикселей. Белый текст на темном фоне даст высокий процент яркости.
     */
    private fun isLikelyTextOverlay(bitmap: Bitmap): Boolean {
        return try {
            val centerX = bitmap.width / 2
            val centerY = bitmap.height / 2
            
            // Размер области для анализа (квадрат вокруг центра)
            val sampleSize = 100.coerceAtMost(bitmap.width / 4).coerceAtMost(bitmap.height / 4)
            
            var brightPixels = 0
            var totalPixels = 0
            
            // Анализируем центральную область
            val startX = (centerX - sampleSize).coerceAtLeast(0)
            val endX = (centerX + sampleSize).coerceAtMost(bitmap.width)
            val startY = (centerY - sampleSize).coerceAtLeast(0)
            val endY = (centerY + sampleSize).coerceAtMost(bitmap.height)
            
            for (x in startX until endX) {
                for (y in startY until endY) {
                    val pixel = bitmap.getPixel(x, y)
                    
                    // Вычисляем яркость пикселя (среднее RGB)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                    
                    if (brightness > BRIGHT_THRESHOLD) {
                        brightPixels++
                    }
                    totalPixels++
                }
            }
            
            if (totalPixels == 0) {
                Log.w(TAG, "⚠️ Нулевой размер выборки")
                return false
            }
            
            val brightPercentage = (brightPixels * 100f) / totalPixels
            
            // ВСЕГДА логируем яркость для отладки
            Log.v(TAG, "🔍 Яркая область: ${String.format("%.1f", brightPercentage)}% (порог: $BRIGHT_PERCENTAGE_THRESHOLD%)")
            
            if (brightPercentage > BRIGHT_PERCENTAGE_THRESHOLD) {
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка детекции оверлея: ${e.message}")
            false
        }
    }
    
    /**
     * ⏱️ Проверяет, застряли ли мы на одном изображении
     * 
     * Сравнивает текущий хэш с предыдущим. Если изображение не меняется
     * больше STATIC_IMAGE_TIMEOUT миллисекунд - считаем статичным.
     */
    private fun isStuckOnStaticImage(currentHash: String): Boolean {
        val now = System.currentTimeMillis()
        
        if (currentHash == lastStableHash) {
            // Хэш не изменился - проверяем время
            val stuckDuration = now - lastStableTime
            
            if (stuckDuration > STATIC_IMAGE_TIMEOUT) {
                Log.v(TAG, "⏸️ Изображение не меняется ${stuckDuration}мс (порог: ${STATIC_IMAGE_TIMEOUT}мс)")
                return true
            }
        } else {
            // Новое изображение - обновляем состояние
            lastStableHash = currentHash
            lastStableTime = now
        }
        
        return false
    }
    
    /**
     * 🔐 Вычисляет быстрый MD5 хэш изображения
     * 
     * Для скорости берем только каждый 10-й пиксель (sample).
     * Этого достаточно для детекции изменений.
     */
    private fun getImageHash(bitmap: Bitmap): String {
        return try {
            // Берем каждый 10-й пиксель для скорости
            val sampleWidth = (bitmap.width / 10).coerceAtLeast(1)
            val sampleHeight = (bitmap.height / 10).coerceAtLeast(1)
            
            val digest = MessageDigest.getInstance("MD5")
            
            for (y in 0 until sampleHeight) {
                for (x in 0 until sampleWidth) {
                    val actualX = (x * 10).coerceAtMost(bitmap.width - 1)
                    val actualY = (y * 10).coerceAtMost(bitmap.height - 1)
                    val pixel = bitmap.getPixel(actualX, actualY)
                    
                    // Добавляем пиксель в хэш
                    digest.update((pixel shr 24 and 0xFF).toByte()) // Alpha
                    digest.update((pixel shr 16 and 0xFF).toByte()) // Red
                    digest.update((pixel shr 8 and 0xFF).toByte())  // Green
                    digest.update((pixel and 0xFF).toByte())         // Blue
                }
            }
            
            // Возвращаем хэш в hex формате
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка вычисления хэша: ${e.message}")
            // Fallback - возвращаем timestamp как уникальное значение
            System.currentTimeMillis().toString()
        }
    }
    
    /**
     * 🔄 Сбрасывает состояние детектора
     * 
     * Вызывайте при переходе между экранами или смене режима игры
     */
    fun reset() {
        lastStableHash = null
        lastStableTime = 0
        Log.d(TAG, "🔄 Детектор сброшен")
    }
    
    /**
     * 📊 Возвращает статистику детектора
     */
    fun getStats(): String {
        val timeSinceLastChange = if (lastStableHash != null) {
            System.currentTimeMillis() - lastStableTime
        } else {
            0
        }
        
        return "StaticFrameDetector: hash=${lastStableHash?.take(8)}, stable=${timeSinceLastChange}ms"
    }
}
