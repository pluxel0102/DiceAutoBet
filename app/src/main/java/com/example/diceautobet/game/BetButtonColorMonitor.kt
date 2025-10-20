package com.example.diceautobet.game

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.GameResult
import com.example.diceautobet.models.WindowType
import kotlinx.coroutines.delay

/**
 * Менеджер для отслеживания изменения цвета пикселей в кнопках ставок.
 * Используется для определения момента, когда кнопки становятся активными после результата игры.
 */
class BetButtonColorMonitor(
    private val areaManager: DualWindowAreaManager,
    private val screenCaptureManager: ScreenCaptureManager
) {
    
    companion object {
        private const val TAG = "BetButtonColorMonitor"
        
        // Пороги для определения изменения цвета
        private const val COLOR_CHANGE_THRESHOLD = 30 // Минимальная разница в яркости для считаемого изменения
        private const val STABILITY_CHECKS = 3 // Количество последовательных проверок для стабильности
        private const val CHECK_INTERVAL_MS = 150L // Интервал между проверками
        private const val MAX_WAIT_TIME_MS = 15000L // Максимальное время ожидания (15 сек)
    }
    
    // Кэш начальных цветов кнопок для быстрого сравнения
    private val initialColorsCache = mutableMapOf<String, PixelColorData>()
    
    /**
     * Очищает кэш начальных цветов (вызывать при начале нового игрового цикла)
     */
    fun clearInitialColorsCache() {
        initialColorsCache.clear()
        Log.d(TAG, "🧹 Кэш начальных цветов очищен")
    }
    
    /**
     * Данные о цвете пикселя в определенной точке
     */
    data class PixelColorData(
        val x: Int,
        val y: Int,
        val color: Int,
        val brightness: Float,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun calculateColorDifference(other: PixelColorData): Float {
            return kotlin.math.abs(this.brightness - other.brightness)
        }
    }
    
    /**
     * Результат мониторинга изменения цвета
     */
    data class ColorChangeResult(
        val hasChanged: Boolean,
        val initialColor: PixelColorData?,
        val finalColor: PixelColorData?,
        val changeTime: Long,
        val totalWaitTime: Long
    )
    
    /**
     * Ожидает изменения цвета пикселя в кнопке RED для указанного окна
     */
    suspend fun waitForRedButtonColorChange(window: WindowType): ColorChangeResult {
        return waitForButtonColorChange(window, AreaType.RED_BUTTON, "красной кнопки")
    }
    
    /**
     * Ожидает изменения цвета пикселя в кнопке ORANGE для указанного окна
     */
    suspend fun waitForOrangeButtonColorChange(window: WindowType): ColorChangeResult {
        return waitForButtonColorChange(window, AreaType.ORANGE_BUTTON, "оранжевой кнопки")
    }
    
    /**
     * Универсальный метод для ожидания изменения цвета кнопки
     */
    private suspend fun waitForButtonColorChange(
        window: WindowType, 
        buttonType: AreaType,
        buttonName: String
    ): ColorChangeResult {
        Log.d(TAG, "🎨 Начинаем мониторинг изменения цвета $buttonName в окне $window")
        
        val startTime = System.currentTimeMillis()
        
        // Получаем область кнопки
        val buttonArea = areaManager.  getAreaForWindow(window, buttonType)
        if (buttonArea == null) {
            Log.e(TAG, "❌ Область $buttonName не найдена для окна $window")
            return ColorChangeResult(false, null, null, 0L, 0L)
        }
        
        Log.d(TAG, "✅ Область $buttonName найдена: ${buttonArea.rect}")
        
        // Вычисляем центральную точку кнопки для мониторинга
        val monitorX = buttonArea.rect.centerX()
        val monitorY = buttonArea.rect.centerY()
        
        Log.d(TAG, "📍 Мониторим пиксель в точке ($monitorX, $monitorY)")
        
        // Получаем начальный цвет
        val initialColor = capturePixelColor(monitorX, monitorY, buttonName)
        if (initialColor == null) {
            Log.e(TAG, "❌ Не удалось получить начальный цвет пикселя")
            return ColorChangeResult(false, null, null, 0L, System.currentTimeMillis() - startTime)
        }
        
        Log.d(TAG, "🎨 Начальный цвет: яркость=${initialColor.brightness}, цвет=#${Integer.toHexString(initialColor.color)}")
        
        var attempts = 0
        var consecutiveFailures = 0
        val maxAttempts = (MAX_WAIT_TIME_MS / CHECK_INTERVAL_MS).toInt()
        val maxConsecutiveFailures = 10 // Максимум 10 неудачных попыток подряд
        var stableChangeCount = 0
        var lastSignificantColor: PixelColorData? = null
        
        while (attempts < maxAttempts && consecutiveFailures < maxConsecutiveFailures) {
            delay(CHECK_INTERVAL_MS)
            
            val currentColor = capturePixelColor(monitorX, monitorY, buttonName)
            if (currentColor == null) {
                consecutiveFailures++
                Log.w(TAG, "⚠️ Не удалось получить текущий цвет пикселя, попытка ${attempts + 1}, неудач подряд: $consecutiveFailures")
                attempts++
                continue
            }
            
            // Сбрасываем счетчик неудач при успешном захвате
            consecutiveFailures = 0
            
            // Вычисляем разность цветов
            val colorDifference = initialColor.calculateColorDifference(currentColor)
            
            if (colorDifference >= COLOR_CHANGE_THRESHOLD) {
                // Обнаружено значительное изменение цвета
                if (lastSignificantColor == null || 
                    kotlin.math.abs(lastSignificantColor.brightness - currentColor.brightness) < 5f) {
                    // Цвет стабилизировался на новом значении
                    stableChangeCount++
                    lastSignificantColor = currentColor
                    
                    Log.d(TAG, "🎨 Изменение цвета обнаружено: яркость ${initialColor.brightness} → ${currentColor.brightness} (разность: $colorDifference), стабильность: $stableChangeCount/$STABILITY_CHECKS")
                    
                    if (stableChangeCount >= STABILITY_CHECKS) {
                        val totalTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "✅ Стабильное изменение цвета $buttonName подтверждено за ${totalTime}мс")
                        return ColorChangeResult(
                            hasChanged = true,
                            initialColor = initialColor,
                            finalColor = currentColor,
                            changeTime = currentColor.timestamp - initialColor.timestamp,
                            totalWaitTime = totalTime
                        )
                    }
                } else {
                    // Цвет продолжает изменяться, сбрасываем счетчик стабильности
                    stableChangeCount = 1
                    lastSignificantColor = currentColor
                    Log.d(TAG, "🎨 Цвет продолжает изменяться: ${currentColor.brightness}")
                }
            } else {
                // Изменение незначительное, сбрасываем счетчик
                if (stableChangeCount > 0) {
                    Log.d(TAG, "🎨 Цвет вернулся к исходному: ${currentColor.brightness} (разность: $colorDifference)")
                }
                stableChangeCount = 0
                lastSignificantColor = null
            }
            
            attempts++
            
            // Логируем прогресс каждые 2 секунды
            if (attempts % (2000 / CHECK_INTERVAL_MS).toInt() == 0) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "⏳ Мониторинг $buttonName: ${elapsed}мс, текущая яркость: ${currentColor.brightness}")
            }
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        
        if (consecutiveFailures >= maxConsecutiveFailures) {
            Log.e(TAG, "❌ Прерываем мониторинг $buttonName из-за слишком большого количества ошибок захвата экрана ($consecutiveFailures)")
        } else {
            Log.w(TAG, "⏰ Время ожидания изменения цвета $buttonName истекло (${totalTime}мс)")
        }
        
        return ColorChangeResult(
            hasChanged = false,
            initialColor = initialColor,
            finalColor = lastSignificantColor,
            changeTime = 0L,
            totalWaitTime = totalTime
        )
    }
    
    /**
     * Захватывает цвет пикселя в указанной точке с повторными попытками
     */
    private suspend fun capturePixelColor(x: Int, y: Int, contextName: String): PixelColorData? {
        val maxRetries = 3
        var retryCount = 0
        
        while (retryCount < maxRetries) {
            try {
                val screenshot = screenCaptureManager.captureScreen()
                
                when (screenshot) {
                    is GameResult.Success -> {
                        val bitmap = screenshot.data
                        
                        // Проверяем, что координаты в пределах изображения
                        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) {
                            Log.e(TAG, "❌ Координаты ($x, $y) вне пределов изображения ${bitmap.width}x${bitmap.height}")
                            return null
                        }
                        
                        val pixelColor = bitmap.getPixel(x, y)
                        val brightness = calculateBrightness(pixelColor)
                        
                        return PixelColorData(x, y, pixelColor, brightness)
                    }
                    is GameResult.Error -> {
                        Log.w(TAG, "⚠️ Ошибка захвата экрана для $contextName (попытка ${retryCount + 1}/$maxRetries): ${screenshot.message}")
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(100) // Небольшая пауза перед повтором
                        }
                    }
                    is GameResult.Loading -> {
                        Log.d(TAG, "⏳ Захват экрана в процессе для $contextName (попытка ${retryCount + 1}/$maxRetries)")
                        retryCount++
                        if (retryCount < maxRetries) {
                            delay(200) // Ждем завершения загрузки
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Исключение при захвате цвета пикселя для $contextName (попытка ${retryCount + 1}/$maxRetries)", e)
                retryCount++
                if (retryCount < maxRetries) {
                    delay(100)
                }
            }
        }
        
        Log.e(TAG, "❌ Не удалось захватить цвет пикселя для $contextName после $maxRetries попыток")
        return null
    }
    
    /**
     * Вычисляет яркость цвета по формуле luminance
     */
    private fun calculateBrightness(color: Int): Float {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        
        // Формула относительной яркости (luminance)
        return (0.299f * red + 0.587f * green + 0.114f * blue)
    }
    
    /**
     * Ожидает изменения цвета любой из кнопок ставок (красной или оранжевой)
     * Полезно когда не знаем, какая кнопка изменится первой
     */
    suspend fun waitForAnyButtonColorChange(window: WindowType): ColorChangeResult {
        Log.d(TAG, "🎨 Мониторинг изменения цвета любой кнопки ставки в окне $window")
        
        // Запускаем мониторинг обеих кнопок параллельно и возвращаем первый успешный результат
        val startTime = System.currentTimeMillis()
        
        // Для простоты реализации будем поочередно проверять обе кнопки
        var attempts = 0
        val maxAttempts = (MAX_WAIT_TIME_MS / (CHECK_INTERVAL_MS * 2)).toInt() // Делим на 2, т.к. проверяем 2 кнопки за цикл
        
        while (attempts < maxAttempts) {
            // Проверяем красную кнопку
            val redResult = quickCheckButtonColorChange(window, AreaType.RED_BUTTON, "красной кнопки")
            if (redResult.hasChanged) {
                Log.d(TAG, "✅ Изменение цвета обнаружено в красной кнопке")
                return redResult
            }
            
            delay(CHECK_INTERVAL_MS)
            
            // Проверяем оранжевую кнопку
            val orangeResult = quickCheckButtonColorChange(window, AreaType.ORANGE_BUTTON, "оранжевой кнопки")
            if (orangeResult.hasChanged) {
                Log.d(TAG, "✅ Изменение цвета обнаружено в оранжевой кнопке")
                return orangeResult
            }
            
            delay(CHECK_INTERVAL_MS)
            attempts++
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        Log.w(TAG, "⏰ Время ожидания изменения цвета любой кнопки истекло (${totalTime}мс)")
        
        return ColorChangeResult(false, null, null, 0L, totalTime)
    }
    
    /**
     * Быстрая проверка изменения цвета кнопки (для использования в циклах)
     */
    private suspend fun quickCheckButtonColorChange(
        window: WindowType,
        buttonType: AreaType,
        buttonName: String
    ): ColorChangeResult {
        // Это упрощенная версия для быстрой проверки
        // Можно кэшировать начальные цвета кнопок для ускорения
        
        val buttonArea = areaManager.getAreaForWindow(window, buttonType) ?: return ColorChangeResult(false, null, null, 0L, 0L)
        
        val monitorX = buttonArea.rect.centerX()
        val monitorY = buttonArea.rect.centerY()
        
        val currentColor = capturePixelColor(monitorX, monitorY, buttonName) ?: return ColorChangeResult(false, null, null, 0L, 0L)
        
        // Для быстрой проверки просто возвращаем текущее состояние
        // В реальной реализации здесь должно быть сравнение с сохраненным начальным цветом
        return ColorChangeResult(false, null, currentColor, 0L, 0L)
    }
}
