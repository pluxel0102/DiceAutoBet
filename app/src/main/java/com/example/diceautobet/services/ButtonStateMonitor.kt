package com.example.diceautobet.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.ScreenshotService
import kotlinx.coroutines.*

/**
 * Сервис для мониторинга состояния кнопок по изменению цвета пикселей
 */
class ButtonStateMonitor(private val context: Context) {
    
    private val prefsManager = PreferencesManager(context)
    private var screenshotService: ScreenshotService? = null
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "ButtonStateMonitor"
        private const val MONITORING_INTERVAL = 500L // Проверка каждые 500мс
        private const val PIXEL_TOLERANCE = 30 // Допустимое отклонение цвета
    }
    
    // Состояния кнопок
    data class ButtonState(
        val isEnabled: Boolean,
        val lastColor: Int,
        val area: Rect
    )
    
    // Карта состояний кнопок
    private val buttonStates = mutableMapOf<AreaType, ButtonState>()
    
    // Эталонные цвета для состояний кнопок
    private var enabledColor: Int? = null
    private var disabledColor: Int? = null
    
    /**
     * Инициализация мониторинга для указанных кнопок
     */
    fun initializeMonitoring(buttonTypes: List<AreaType>, screenshotService: ScreenshotService) {
        Log.d(TAG, "Инициализация мониторинга для кнопок: ${buttonTypes.map { it.displayName }}")
        
        this.screenshotService = screenshotService
        
        buttonTypes.forEach { buttonType ->
            val screenArea = prefsManager.loadArea(buttonType)
            if (screenArea != null) {
                buttonStates[buttonType] = ButtonState(
                    isEnabled = false,
                    lastColor = Color.TRANSPARENT,
                    area = screenArea.rect
                )
                Log.d(TAG, "Добавлена кнопка ${buttonType.displayName} с областью: ${screenArea.rect}")
            } else {
                Log.w(TAG, "Область для кнопки ${buttonType.displayName} не найдена")
            }
        }
    }
    
    /**
     * Запуск мониторинга состояния кнопок
     */
    fun startMonitoring() {
        Log.d(TAG, "Запуск мониторинга состояния кнопок")
        
        monitoringScope.launch {
            while (monitoringScope.isActive) {
                try {
                    checkButtonStates()
                    delay(MONITORING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при проверке состояния кнопок", e)
                    delay(1000) // Увеличиваем интервал при ошибке
                }
            }
        }
    }
    
    /**
     * Остановка мониторинга
     */
    fun stopMonitoring() {
        Log.d(TAG, "Остановка мониторинга состояния кнопок")
        monitoringScope.cancel()
    }
    
    /**
     * Проверка состояния всех отслеживаемых кнопок
     */
    private suspend fun checkButtonStates() {
        val screenshotService = this.screenshotService
        if (screenshotService == null) {
            Log.w(TAG, "ScreenshotService не инициализирован")
            return
        }
        
        val screenshot = screenshotService.takeScreenshot()
        if (screenshot == null) {
            Log.w(TAG, "Не удалось получить скриншот для проверки состояния кнопок")
            return
        }
        
        buttonStates.forEach { (buttonType, state) ->
            val currentColor = getPixelColor(screenshot, state.area)
            val wasEnabled = state.isEnabled
            val isNowEnabled = isButtonEnabled(currentColor, state.lastColor)
            
            if (wasEnabled != isNowEnabled) {
                Log.d(TAG, "Изменение состояния кнопки ${buttonType.displayName}: $wasEnabled -> $isNowEnabled")
                Log.d(TAG, "Цвет изменился с ${colorToString(state.lastColor)} на ${colorToString(currentColor)}")
                
                // Обновляем состояние
                buttonStates[buttonType] = state.copy(
                    isEnabled = isNowEnabled,
                    lastColor = currentColor
                )
                
                // Уведомляем о изменении состояния
                onButtonStateChanged(buttonType, isNowEnabled)
            }
        }
    }
    
    /**
     * Получение цвета пикселя в центре области кнопки
     */
    private fun getPixelColor(screenshot: Bitmap, area: Rect): Int {
        val centerX = area.centerX().coerceIn(0, screenshot.width - 1)
        val centerY = area.centerY().coerceIn(0, screenshot.height - 1)
        
        return screenshot.getPixel(centerX, centerY)
    }
    
    /**
     * Определение, активна ли кнопка по цвету
     */
    private fun isButtonEnabled(currentColor: Int, previousColor: Int): Boolean {
        // Если эталонные цвета еще не определены, используем эвристику
        if (enabledColor == null || disabledColor == null) {
            return detectButtonStateByHeuristic(currentColor, previousColor)
        }
        
        // Сравниваем с эталонными цветами
        val distanceToEnabled = colorDistance(currentColor, enabledColor!!)
        val distanceToDisabled = colorDistance(currentColor, disabledColor!!)
        
        return distanceToEnabled < distanceToDisabled
    }
    
    /**
     * Эвристическое определение состояния кнопки
     */
    private fun detectButtonStateByHeuristic(currentColor: Int, previousColor: Int): Boolean {
        // Активные кнопки обычно ярче и насыщеннее
        val brightness = getBrightness(currentColor)
        val saturation = getSaturation(currentColor)
        
        // Эвристика: активная кнопка имеет яркость > 100 и насыщенность > 50
        return brightness > 100 && saturation > 50
    }
    
    /**
     * Вычисление расстояния между цветами
     */
    private fun colorDistance(color1: Int, color2: Int): Double {
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
     * Получение насыщенности цвета
     */
    private fun getSaturation(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        
        return if (max == 0) 0 else ((max - min) * 100 / max)
    }
    
    /**
     * Установка эталонных цветов для состояний кнопок
     */
    fun setReferenceColors(enabledColor: Int, disabledColor: Int) {
        this.enabledColor = enabledColor
        this.disabledColor = disabledColor
        
        Log.d(TAG, "Установлены эталонные цвета:")
        Log.d(TAG, "Активная кнопка: ${colorToString(enabledColor)}")
        Log.d(TAG, "Неактивная кнопка: ${colorToString(disabledColor)}")
    }
    
    /**
     * Получение текущего состояния кнопки
     */
    fun getButtonState(buttonType: AreaType): Boolean {
        return buttonStates[buttonType]?.isEnabled ?: false
    }
    
    /**
     * Ожидание активации кнопки
     */
    suspend fun waitForButtonEnabled(buttonType: AreaType, timeoutMs: Long = 30000): Boolean {
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "Ожидание активации кнопки ${buttonType.displayName}")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (getButtonState(buttonType)) {
                Log.d(TAG, "Кнопка ${buttonType.displayName} активирована")
                return true
            }
            delay(100)
        }
        
        Log.w(TAG, "Таймаут ожидания активации кнопки ${buttonType.displayName}")
        return false
    }
    
    /**
     * Ожидание деактивации кнопки
     */
    suspend fun waitForButtonDisabled(buttonType: AreaType, timeoutMs: Long = 30000): Boolean {
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "Ожидание деактивации кнопки ${buttonType.displayName}")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (!getButtonState(buttonType)) {
                Log.d(TAG, "Кнопка ${buttonType.displayName} деактивирована")
                return true
            }
            delay(100)
        }
        
        Log.w(TAG, "Таймаут ожидания деактивации кнопки ${buttonType.displayName}")
        return false
    }
    
    /**
     * Коллбэк при изменении состояния кнопки
     */
    private fun onButtonStateChanged(buttonType: AreaType, isEnabled: Boolean) {
        Log.i(TAG, "Состояние кнопки ${buttonType.displayName} изменилось: ${if (isEnabled) "АКТИВНА" else "НЕАКТИВНА"}")
        
        // Здесь можно добавить уведомления другим компонентам
        // Например, через EventBus или другой механизм
    }
    
    /**
     * Преобразование цвета в строку для логирования
     */
    private fun colorToString(color: Int): String {
        return String.format("#%08X (R:%d G:%d B:%d)", 
            color, 
            Color.red(color), 
            Color.green(color), 
            Color.blue(color)
        )
    }
}
