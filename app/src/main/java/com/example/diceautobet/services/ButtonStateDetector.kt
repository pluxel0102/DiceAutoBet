package com.example.diceautobet.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.ScreenArea
import com.example.diceautobet.utils.PreferencesManager
import kotlinx.coroutines.*

/**
 * Диапазон цветов для определения состояния кнопок
 */
data class ColorRange(
    val minR: Int, val maxR: Int,
    val minG: Int, val maxG: Int, 
    val minB: Int, val maxB: Int
)

/**
 * Сервис для отслеживания состояния кнопок по конкретным цветам
 */
class ButtonStateDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "ButtonStateDetector"
        private const val MONITORING_INTERVAL = 200L // Проверка каждые 200мс
        private const val COLOR_TOLERANCE = 30 // Увеличенный допуск для надежности
        
        // Эталонные цвета для кнопки "Заключить пари"
        private val CONFIRM_BET_DISABLED_COLOR = Color.rgb(91, 130, 132) // #5B8284 - заблокированная
        private val CONFIRM_BET_ENABLED_COLOR = Color.rgb(85, 100, 106)  // #55646A - разблокированная
        
        // Диапазоны цветов для более надежного определения
        private val DISABLED_COLOR_RANGE = ColorRange(
            minR = 80, maxR = 105,   // R: 91 ± 14
            minG = 115, maxG = 145,  // G: 130 ± 15  
            minB = 117, maxB = 147   // B: 132 ± 15
        )
        
        private val ENABLED_COLOR_RANGE = ColorRange(
            minR = 70, maxR = 100,   // R: 85 ± 15
            minG = 85, maxG = 115,   // G: 100 ± 15
            minB = 91, maxB = 121    // B: 106 ± 15
        )
        
        // Для кнопок выбора цвета используем детектор яркости
        private const val COLOR_BUTTON_BRIGHTNESS_THRESHOLD = 80
    }
    
    private val prefsManager = PreferencesManager(context)
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Ожидание разблокировки кнопки "Заключить пари" с автоматическим поиском
     */
    suspend fun waitForConfirmBetEnabled(screenshotProvider: suspend () -> Bitmap?): Boolean {
        Log.d(TAG, "Ожидание разблокировки кнопки 'Заключить пари'")
        
        var confirmBetArea = prefsManager.loadArea(AreaType.CONFIRM_BET)
        
        // Если сохраненная область не найдена, пытаемся найти автоматически
        if (confirmBetArea == null) {
            Log.w(TAG, "Сохраненная область кнопки 'Заключить пари' не найдена, ищем автоматически")
            
            val screenshot = screenshotProvider()
            if (screenshot != null) {
                val autoFoundRect = findConfirmBetButtonAutomatically(screenshot)
                if (autoFoundRect != null) {
                    confirmBetArea = ScreenArea("Заключить пари (автопоиск)", autoFoundRect)
                    Log.i(TAG, "Кнопка найдена автоматически: $autoFoundRect")
                } else {
                    Log.e(TAG, "Не удалось найти кнопку 'Заключить пари' автоматически")
                    return false
                }
            } else {
                Log.e(TAG, "Не удалось получить скриншот для автоматического поиска")
                return false
            }
        }
        
        var attempts = 0
        val maxAttempts = 150 // 150 * 200ms = 30 секунд максимум
        
        while (attempts < maxAttempts) {
            val screenshot = screenshotProvider()
            if (screenshot != null) {
                val isEnabled = isConfirmBetButtonEnabled(screenshot, confirmBetArea.rect)
                
                Log.d(TAG, "Проверка кнопки 'Заключить пари': ${if (isEnabled) "АКТИВНА" else "ЗАБЛОКИРОВАНА"} (попытка ${attempts + 1})")
                
                if (isEnabled) {
                    Log.i(TAG, "Кнопка 'Заключить пари' разблокирована!")
                    return true
                }
            }
            
            attempts++
            delay(MONITORING_INTERVAL)
        }
        
        Log.w(TAG, "Превышен таймаут ожидания разблокировки кнопки 'Заключить пари'")
        return false
    }
    
    /**
     * Ожидание разблокировки кнопок выбора цвета
     */
    suspend fun waitForColorButtonsEnabled(screenshotProvider: suspend () -> Bitmap?): Boolean {
        Log.d(TAG, "Ожидание разблокировки кнопок выбора цвета")
        
        val redButtonArea = prefsManager.loadArea(AreaType.RED_BUTTON)
        val orangeButtonArea = prefsManager.loadArea(AreaType.ORANGE_BUTTON)
        
        if (redButtonArea == null || orangeButtonArea == null) {
            Log.e(TAG, "Области кнопок выбора цвета не найдены")
            return false
        }
        
        var attempts = 0
        val maxAttempts = 150 // 30 секунд максимум
        
        while (attempts < maxAttempts) {
            val screenshot = screenshotProvider()
            if (screenshot != null) {
                val redEnabled = isColorButtonEnabled(screenshot, redButtonArea.rect)
                val orangeEnabled = isColorButtonEnabled(screenshot, orangeButtonArea.rect)
                
                Log.d(TAG, "Проверка кнопок цвета: RED=${if (redEnabled) "АКТИВНА" else "ЗАБЛОКИРОВАНА"}, ORANGE=${if (orangeEnabled) "АКТИВНА" else "ЗАБЛОКИРОВАНА"} (попытка ${attempts + 1})")
                
                if (redEnabled && orangeEnabled) {
                    Log.i(TAG, "Кнопки выбора цвета разблокированы!")
                    return true
                }
            }
            
            attempts++
            delay(MONITORING_INTERVAL)
        }
        
        Log.w(TAG, "Превышен таймаут ожидания разблокировки кнопок выбора цвета")
        return false
    }
    
    /**
     * Ожидание разблокировки всех критических кнопок
     */
    suspend fun waitForAllButtonsEnabled(screenshotProvider: suspend () -> Bitmap?): Boolean {
        Log.d(TAG, "Ожидание разблокировки всех кнопок")
        
        // Сначала ждем кнопки выбора цвета
        val colorButtonsReady = waitForColorButtonsEnabled(screenshotProvider)
        if (!colorButtonsReady) {
            return false
        }
        
        // Затем ждем кнопку подтверждения
        val confirmButtonReady = waitForConfirmBetEnabled(screenshotProvider)
        
        val allReady = colorButtonsReady && confirmButtonReady
        Log.i(TAG, "Все кнопки ${if (allReady) "готовы" else "не готовы"}")
        
        return allReady
    }
    
    /**
     * Проверка состояния кнопки "Заключить пари" с улучшенным алгоритмом
     */
    fun isConfirmBetButtonEnabled(screenshot: Bitmap, buttonRect: Rect): Boolean {
        // Проверяем несколько точек для надежности
        val checkPoints = listOf(
            Pair(buttonRect.centerX(), buttonRect.centerY()),                    // Центр
            Pair(buttonRect.left + buttonRect.width() / 4, buttonRect.centerY()), // Левая четверть
            Pair(buttonRect.right - buttonRect.width() / 4, buttonRect.centerY()), // Правая четверть
            Pair(buttonRect.centerX(), buttonRect.top + buttonRect.height() / 4),  // Верхняя четверть
            Pair(buttonRect.centerX(), buttonRect.bottom - buttonRect.height() / 4) // Нижняя четверть
        )
        
        var enabledVotes = 0
        var disabledVotes = 0
        
        for ((x, y) in checkPoints) {
            val safeX = x.coerceIn(0, screenshot.width - 1)
            val safeY = y.coerceIn(0, screenshot.height - 1)
            val pixelColor = screenshot.getPixel(safeX, safeY)
            
            val r = Color.red(pixelColor)
            val g = Color.green(pixelColor)
            val b = Color.blue(pixelColor)
            
            Log.d(TAG, "Проверка точки ($safeX, $safeY): ${colorToString(pixelColor)}")
            
            // Проверяем попадание в диапазоны
            val matchesDisabled = isColorInRange(r, g, b, DISABLED_COLOR_RANGE)
            val matchesEnabled = isColorInRange(r, g, b, ENABLED_COLOR_RANGE)
            
            if (matchesDisabled && !matchesEnabled) {
                disabledVotes++
            } else if (matchesEnabled && !matchesDisabled) {
                enabledVotes++
            } else {
                // Если не попадает ни в один диапазон, используем расстояние
                val distanceToDisabled = calculateColorDistance(pixelColor, CONFIRM_BET_DISABLED_COLOR)
                val distanceToEnabled = calculateColorDistance(pixelColor, CONFIRM_BET_ENABLED_COLOR)
                
                if (distanceToEnabled < distanceToDisabled && distanceToEnabled < COLOR_TOLERANCE) {
                    enabledVotes++
                } else if (distanceToDisabled < COLOR_TOLERANCE) {
                    disabledVotes++
                }
            }
        }
        
        Log.d(TAG, "Голосование: активна=$enabledVotes, заблокирована=$disabledVotes")
        
        // Кнопка активна, если больше голосов за активное состояние
        return enabledVotes > disabledVotes
    }
    
    /**
     * Проверка попадания цвета в заданный диапазон
     */
    private fun isColorInRange(r: Int, g: Int, b: Int, range: ColorRange): Boolean {
        return r >= range.minR && r <= range.maxR &&
               g >= range.minG && g <= range.maxG &&
               b >= range.minB && b <= range.maxB
    }
    
    /**
     * Проверка состояния кнопки выбора цвета с улучшенным алгоритмом
     */
    private fun isColorButtonEnabled(screenshot: Bitmap, buttonRect: Rect): Boolean {
        // Проверяем несколько точек в кнопке
        val checkPoints = listOf(
            Pair(buttonRect.centerX(), buttonRect.centerY()),                    // Центр
            Pair(buttonRect.left + buttonRect.width() / 3, buttonRect.centerY()), // Левая треть
            Pair(buttonRect.right - buttonRect.width() / 3, buttonRect.centerY()) // Правая треть
        )
        
        var totalBrightness = 0
        var validPoints = 0
        
        for ((x, y) in checkPoints) {
            val safeX = x.coerceIn(0, screenshot.width - 1)
            val safeY = y.coerceIn(0, screenshot.height - 1)
            val pixelColor = screenshot.getPixel(safeX, safeY)
            
            val brightness = getBrightness(pixelColor)
            totalBrightness += brightness
            validPoints++
            
            Log.d(TAG, "Кнопка цвета точка ($safeX, $safeY): ${colorToString(pixelColor)}, яркость: $brightness")
        }
        
        val avgBrightness = if (validPoints > 0) totalBrightness / validPoints else 0
        val isEnabled = avgBrightness > COLOR_BUTTON_BRIGHTNESS_THRESHOLD
        
        Log.d(TAG, "Кнопка цвета: средняя яркость=$avgBrightness, активна=$isEnabled")
        
        return isEnabled
    }
    
    /**
     * Вычисление расстояния между цветами
     */
    private fun calculateColorDistance(color1: Int, color2: Int): Double {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)
        
        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)
        
        return Math.sqrt(((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble())
    }
    
    /**
     * Получение яркости цвета
     */
    private fun getBrightness(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        return (r + g + b) / 3
    }
    
    /**
     * Преобразование цвета в строку для логирования
     */
    private fun colorToString(color: Int): String {
        return String.format("#%06X (R:%d G:%d B:%d)", 
            color and 0xFFFFFF, 
            Color.red(color), 
            Color.green(color), 
            Color.blue(color)
        )
    }
    
    /**
     * Диагностическая функция для анализа цветов кнопки
     */
    fun analyzeButtonColors(screenshot: Bitmap, buttonRect: Rect, buttonName: String) {
        Log.i(TAG, "=== Анализ цветов кнопки '$buttonName' ===")
        
        // Анализируем всю область кнопки
        val colors = mutableMapOf<Int, Int>()
        var totalPixels = 0
        
        for (y in buttonRect.top until buttonRect.bottom) {
            for (x in buttonRect.left until buttonRect.right) {
                if (x >= 0 && x < screenshot.width && y >= 0 && y < screenshot.height) {
                    val pixelColor = screenshot.getPixel(x, y)
                    colors[pixelColor] = colors.getOrDefault(pixelColor, 0) + 1
                    totalPixels++
                }
            }
        }
        
        Log.i(TAG, "Всего пикселей: $totalPixels")
        
        // Показываем топ-10 самых частых цветов
        val sortedColors = colors.toList().sortedByDescending { it.second }.take(10)
        
        sortedColors.forEachIndexed { index, (color, count) ->
            val percentage = (count * 100.0 / totalPixels)
            Log.i(TAG, "${index + 1}. ${colorToString(color)} - $count пикселей (${String.format("%.1f", percentage)}%)")
        }
        
        // Проверяем соответствие нашим эталонным цветам
        val centerColor = screenshot.getPixel(buttonRect.centerX(), buttonRect.centerY())
        val distanceToDisabled = calculateColorDistance(centerColor, CONFIRM_BET_DISABLED_COLOR)
        val distanceToEnabled = calculateColorDistance(centerColor, CONFIRM_BET_ENABLED_COLOR)
        
        Log.i(TAG, "Цвет центра: ${colorToString(centerColor)}")
        Log.i(TAG, "Расстояние до заблокированной: $distanceToDisabled")
        Log.i(TAG, "Расстояние до разблокированной: $distanceToEnabled")
        
        Log.i(TAG, "=== Конец анализа ===")
    }
    
    /**
     * Остановка мониторинга
     */
    fun stop() {
        monitoringScope.cancel()
    }
    
    /**
     * Автоматический поиск кнопки "Заключить пари" на экране
     */
    private fun findConfirmBetButtonAutomatically(screenshot: Bitmap): Rect? {
        Log.d(TAG, "Автоматический поиск кнопки 'Заключить пари'")
        
        val width = screenshot.width
        val height = screenshot.height
        
        // Ищем в нижней части экрана (обычно кнопки там)
        val searchAreaTop = (height * 0.7).toInt()
        val searchAreaBottom = height
        
        // Ищем прямоугольную область с цветами, похожими на кнопку
        for (y in searchAreaTop until searchAreaBottom step 10) {
            for (x in 0 until width step 10) {
                val pixelColor = screenshot.getPixel(x, y)
                
                // Проверяем, похож ли цвет на наши эталонные цвета
                val distanceToDisabled = calculateColorDistance(pixelColor, CONFIRM_BET_DISABLED_COLOR)
                val distanceToEnabled = calculateColorDistance(pixelColor, CONFIRM_BET_ENABLED_COLOR)
                
                if (distanceToDisabled < COLOR_TOLERANCE || distanceToEnabled < COLOR_TOLERANCE) {
                    // Найдена потенциальная кнопка, определяем её границы
                    val buttonRect = findButtonBounds(screenshot, x, y)
                    if (buttonRect != null && isValidButtonSize(buttonRect)) {
                        Log.i(TAG, "Автоматически найдена кнопка 'Заключить пари' в области: $buttonRect")
                        return buttonRect
                    }
                }
            }
        }
        
        Log.w(TAG, "Кнопка 'Заключить пари' не найдена автоматически")
        return null
    }
    
    /**
     * Определение границ кнопки по начальной точке
     */
    private fun findButtonBounds(screenshot: Bitmap, startX: Int, startY: Int): Rect? {
        val width = screenshot.width
        val height = screenshot.height
        
        // Ищем границы кнопки, расширяя область от начальной точки
        var left = startX
        var right = startX
        var top = startY
        var bottom = startY
        
        // Расширяем влево
        while (left > 0) {
            val pixelColor = screenshot.getPixel(left - 1, startY)
            if (isButtonColor(pixelColor)) {
                left--
            } else {
                break
            }
        }
        
        // Расширяем вправо
        while (right < width - 1) {
            val pixelColor = screenshot.getPixel(right + 1, startY)
            if (isButtonColor(pixelColor)) {
                right++
            } else {
                break
            }
        }
        
        // Расширяем вверх
        while (top > 0) {
            val pixelColor = screenshot.getPixel(startX, top - 1)
            if (isButtonColor(pixelColor)) {
                top--
            } else {
                break
            }
        }
        
        // Расширяем вниз
        while (bottom < height - 1) {
            val pixelColor = screenshot.getPixel(startX, bottom + 1)
            if (isButtonColor(pixelColor)) {
                bottom++
            } else {
                break
            }
        }
        
        return Rect(left, top, right, bottom)
    }
    
    /**
     * Проверка, является ли цвет цветом кнопки
     */
    private fun isButtonColor(color: Int): Boolean {
        val distanceToDisabled = calculateColorDistance(color, CONFIRM_BET_DISABLED_COLOR)
        val distanceToEnabled = calculateColorDistance(color, CONFIRM_BET_ENABLED_COLOR)
        return distanceToDisabled < COLOR_TOLERANCE || distanceToEnabled < COLOR_TOLERANCE
    }
    
    /**
     * Проверка, имеет ли найденная область разумные размеры для кнопки
     */
    private fun isValidButtonSize(rect: Rect): Boolean {
        val width = rect.width()
        val height = rect.height()
        
        // Кнопка должна быть разумного размера (не слишком маленькая и не слишком большая)
        return width in 50..500 && height in 30..200
    }
    
    /**
     * Полный анализ состояния кнопок с попыткой автоматического поиска
     */
    fun performFullButtonAnalysis(screenshot: Bitmap): String {
        val analysis = StringBuilder()
        analysis.append("=== ПОЛНЫЙ АНАЛИЗ СОСТОЯНИЯ КНОПОК ===\n\n")
        
        // 1. Проверяем сохраненные области
        val savedConfirmArea = prefsManager.loadArea(AreaType.CONFIRM_BET)
        val savedRedArea = prefsManager.loadArea(AreaType.RED_BUTTON)
        val savedOrangeArea = prefsManager.loadArea(AreaType.ORANGE_BUTTON)
        
        analysis.append("1. СОХРАНЕННЫЕ ОБЛАСТИ:\n")
        analysis.append("   Заключить пари: ${savedConfirmArea?.rect ?: "НЕ НАЙДЕНА"}\n")
        analysis.append("   Красная кнопка: ${savedRedArea?.rect ?: "НЕ НАЙДЕНА"}\n")
        analysis.append("   Оранжевая кнопка: ${savedOrangeArea?.rect ?: "НЕ НАЙДЕНА"}\n\n")
        
        // 2. Автоматический поиск кнопки "Заключить пари"
        analysis.append("2. АВТОМАТИЧЕСКИЙ ПОИСК:\n")
        val autoFoundRect = findConfirmBetButtonAutomatically(screenshot)
        if (autoFoundRect != null) {
            analysis.append("   ✅ Кнопка найдена автоматически: $autoFoundRect\n")
            analysis.append("   Размеры: ${autoFoundRect.width()}x${autoFoundRect.height()}\n")
            
            // Анализируем цвета в найденной области
            analyzeButtonColors(screenshot, autoFoundRect, "Автоматически найденная кнопка")
        } else {
            analysis.append("   ❌ Кнопка не найдена автоматически\n")
        }
        
        // 3. Проверяем состояние сохраненных кнопок
        analysis.append("\n3. СОСТОЯНИЕ СОХРАНЕННЫХ КНОПОК:\n")
        if (savedConfirmArea != null) {
            val isEnabled = isConfirmBetButtonEnabled(screenshot, savedConfirmArea.rect)
            analysis.append("   Заключить пари: ${if (isEnabled) "АКТИВНА ✅" else "ЗАБЛОКИРОВАНА ❌"}\n")
        }
        
        if (savedRedArea != null) {
            val isEnabled = isColorButtonEnabled(screenshot, savedRedArea.rect)
            analysis.append("   Красная кнопка: ${if (isEnabled) "АКТИВНА ✅" else "ЗАБЛОКИРОВАНА ❌"}\n")
        }
        
        if (savedOrangeArea != null) {
            val isEnabled = isColorButtonEnabled(screenshot, savedOrangeArea.rect)
            analysis.append("   Оранжевая кнопка: ${if (isEnabled) "АКТИВНА ✅" else "ЗАБЛОКИРОВАНА ❌"}\n")
        }
        
        analysis.append("\n=== КОНЕЦ АНАЛИЗА ===")
        
        val result = analysis.toString()
        Log.i(TAG, result)
        return result
    }
}
