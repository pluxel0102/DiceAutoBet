package com.example.diceautobet.automation

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.services.AutoClickService
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.timing.DualModeTimingOptimizer
import com.example.diceautobet.timing.OperationType
import kotlinx.coroutines.*

/**
 * Автоматический размещатель ставок для двойного режима
 * Выполняет последовательность действий для размещения ставки в указанном окне
 */
class DualModeBetPlacer(
    private val context: Context,
    private val areaManager: DualWindowAreaManager,
    private val timingOptimizer: DualModeTimingOptimizer? = null
) {
    
    companion object {
        private const val TAG = "DualModeBetPlacer"
    }
    
    private val placementScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Текущее активное окно для преобразования координат
    private var currentWindowType: WindowType = WindowType.TOP
    
    // Слушатели событий
    private var onBetPlaced: ((WindowType, BetChoice, Int) -> Unit)? = null
    private var onBetError: ((String, Exception?) -> Unit)? = null
    
    /**
     * Размещает ставку в указанном окне
     */
    suspend fun placeBet(
        windowType: WindowType,
        betChoice: BetChoice,
        amount: Int,
        strategy: DualStrategy
    ): Boolean {
        Log.d(TAG, "Размещение ставки: окно=$windowType, выбор=$betChoice, сумма=$amount")
        
        return withContext(Dispatchers.Main) {
            try {
                // Сохраняем текущее окно для преобразования координат
                currentWindowType = windowType
                
                // Переключаемся на нужное окно
                areaManager.setActiveWindow(windowType)
                
                // Выполняем последовательность действий
                val success = executeBetSequence(windowType, betChoice, amount)
                
                if (success) {
                    onBetPlaced?.invoke(windowType, betChoice, amount)
                    Log.d(TAG, "Ставка успешно размещена")
                } else {
                    onBetError?.invoke("Не удалось разместить ставку", null)
                    Log.w(TAG, "Ошибка размещения ставки")
                }
                
                success
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при размещении ставки", e)
                onBetError?.invoke("Ошибка размещения ставки", e)
                false
            }
        }
    }
    
    /**
     * Выполняет последовательность действий для размещения ставки с оптимизированными таймингами
     */
    private suspend fun executeBetSequence(
        windowType: WindowType,
        betChoice: BetChoice,
        amount: Int
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Выбираем сумму ставки
            val amountStartTime = System.currentTimeMillis()
            val amountSelected = selectBetAmount(windowType, amount)
            
            if (!amountSelected) {
                Log.e(TAG, "Не удалось выбрать сумму ставки")
                return false
            }
            
            // Записываем метрику и применяем оптимизированную задержку
            val amountTime = System.currentTimeMillis() - amountStartTime
            timingOptimizer?.recordOperationMetric(OperationType.CLICK, amountTime, amountSelected)
            
            val clickDelay = timingOptimizer?.getDelayForOperation(OperationType.CLICK) ?: 200L
            delay(clickDelay)
            
            // 2. Выбираем цвет (красный/оранжевый)
            val colorStartTime = System.currentTimeMillis()
            val colorSelected = selectBetColor(windowType, betChoice)
            
            if (!colorSelected) {
                Log.e(TAG, "Не удалось выбрать цвет ставки")
                return false
            }
            
            // Записываем метрику и применяем задержку
            val colorTime = System.currentTimeMillis() - colorStartTime
            timingOptimizer?.recordOperationMetric(OperationType.CLICK, colorTime, colorSelected)
            delay(clickDelay)
            
            // 3. Подтверждаем ставку
            val confirmStartTime = System.currentTimeMillis()
            val betConfirmed = confirmBet(windowType)
            
            if (!betConfirmed) {
                Log.e(TAG, "Не удалось подтвердить ставку")
                return false
            }
            
            // Записываем метрику подтверждения
            val confirmTime = System.currentTimeMillis() - confirmStartTime
            timingOptimizer?.recordOperationMetric(OperationType.BET_CONFIRMATION, confirmTime, betConfirmed)
            
            // Применяем оптимизированную задержку подтверждения
            val confirmationDelay = timingOptimizer?.getDelayForOperation(OperationType.BET_CONFIRMATION) ?: 500L
            delay(confirmationDelay)
            
            // Записываем общую метрику размещения ставки
            val totalTime = System.currentTimeMillis() - startTime
            timingOptimizer?.recordOperationMetric(OperationType.CLICK, totalTime, true)
            
            Log.d(TAG, "Последовательность размещения ставки выполнена за ${totalTime}мс")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в последовательности размещения ставки", e)
            
            // Записываем метрику неудачи
            val totalTime = System.currentTimeMillis() - startTime
            timingOptimizer?.recordOperationMetric(OperationType.CLICK, totalTime, false)
            
            return false
        }
    }
    
    /**
     * Выбирает сумму ставки
     */
    private suspend fun selectBetAmount(windowType: WindowType, amount: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Определяем какую кнопку ставки нажать
                val betAreaType = getBetAreaType(amount)
                if (betAreaType == null) {
                    Log.w(TAG, "Неподдерживаемая сумма ставки: $amount")
                    return@withContext false
                }
                
                // Получаем координаты кнопки ставки
                val betArea = areaManager.getAreaForWindow(windowType, betAreaType)
                if (betArea == null) {
                    Log.w(TAG, "Область ставки $betAreaType не найдена для окна $windowType")
                    return@withContext false
                }
                
                // Кликаем по кнопке ставки
                val clicked = performClick(betArea.rect)
                if (clicked) {
                    Log.d(TAG, "Выбрана сумма ставки $amount ($betAreaType)")
                } else {
                    Log.w(TAG, "Не удалось кликнуть по кнопке ставки $betAreaType")
                }
                
                clicked
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выбора суммы ставки", e)
                false
            }
        }
    }
    
    /**
     * Выбирает цвет ставки (красный/оранжевый)
     */
    private suspend fun selectBetColor(windowType: WindowType, betChoice: BetChoice): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val colorAreaType = when (betChoice) {
                    BetChoice.RED -> AreaType.RED_BUTTON
                    BetChoice.ORANGE -> AreaType.ORANGE_BUTTON
                }
                
                // Получаем координаты кнопки цвета
                val colorArea = areaManager.getAreaForWindow(windowType, colorAreaType)
                if (colorArea == null) {
                    Log.w(TAG, "Область цвета $colorAreaType не найдена для окна $windowType")
                    return@withContext false
                }
                
                // Кликаем по кнопке цвета
                val clicked = performClick(colorArea.rect)
                if (clicked) {
                    Log.d(TAG, "Выбран цвет $betChoice")
                } else {
                    Log.w(TAG, "Не удалось кликнуть по кнопке цвета $betChoice")
                }
                
                clicked
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выбора цвета ставки", e)
                false
            }
        }
    }
    
    /**
     * Подтверждает ставку
     */
    private suspend fun confirmBet(windowType: WindowType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем координаты кнопки подтверждения
                val confirmArea = areaManager.getAreaForWindow(windowType, AreaType.CONFIRM_BET)
                if (confirmArea == null) {
                    Log.w(TAG, "Область подтверждения ставки не найдена для окна $windowType")
                    return@withContext false
                }
                
                // Кликаем по кнопке подтверждения
                val clicked = performClick(confirmArea.rect)
                if (clicked) {
                    Log.d(TAG, "Ставка подтверждена")
                } else {
                    Log.w(TAG, "Не удалось кликнуть по кнопке подтверждения")
                }
                
                clicked
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подтверждения ставки", e)
                false
            }
        }
    }
    
    /**
     * Определяет тип области ставки по сумме
     */
    private fun getBetAreaType(amount: Int): AreaType? {
        return when (amount) {
            10 -> AreaType.BET_10
            50 -> AreaType.BET_50
            100 -> AreaType.BET_100
            500 -> AreaType.BET_500
            2500 -> AreaType.BET_2500
            else -> {
                // Для других сумм ищем ближайшую
                when {
                    amount <= 25 -> AreaType.BET_10
                    amount <= 75 -> AreaType.BET_50
                    amount <= 300 -> AreaType.BET_100
                    amount <= 1250 -> AreaType.BET_500
                    else -> AreaType.BET_2500
                }
            }
        }
    }
    
    /**
     * Выполняет клик по указанным координатам
     */
    private suspend fun performClick(rect: Rect): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // Преобразуем абсолютные координаты в координаты относительно текущего окна
                val transformedRect = transformCoordinatesForCurrentWindow(rect)
                val centerX = transformedRect.centerX()
                val centerY = transformedRect.centerY()
                
                Log.v(TAG, "Выполняем клик по координатам: ($centerX, $centerY) [преобразованные из ${rect.centerX()}, ${rect.centerY()}]")
                
                // Используем AutoClickService для выполнения клика
                val autoClickService = AutoClickService.getInstance()
                if (autoClickService == null) {
                    Log.w(TAG, "AutoClickService недоступен")
                    return@withContext false
                }
                
                // Выполняем клик с использованием статического метода
                var clickResult = false
                AutoClickService.performClick(centerX, centerY) { success ->
                    clickResult = success
                }
                
                // Ждем немного для завершения клика
                delay(100)
                clickResult
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выполнения клика", e)
                false
            }
        }
    }
    
    /**
     * Вычисляет сумму ставки по стратегии
     */
    fun calculateBetAmount(
        baseBet: Int,
        consecutiveLosses: Int,
        maxBet: Int,
        strategy: DualStrategy
    ): Int {
        return when (strategy) {
            DualStrategy.WIN_SWITCH -> {
                // При выигрыше используем базовую ставку
                // При проигрыше остаемся в том же окне с той же ставкой
                baseBet
            }
            DualStrategy.LOSS_DOUBLE -> {
                // При проигрыше удваиваем ставку в другом окне
                // Начинаем с 20 рублей, затем 40, 80, 160 и т.д.
                var betAmount = baseBet
                repeat(consecutiveLosses) {
                    betAmount = minOf(betAmount * 2, maxBet)
                }
                betAmount
            }
            DualStrategy.COLOR_ALTERNATING -> {
                // При смене цвета удваиваем ставку
                if (consecutiveLosses > 0) {
                    var betAmount = baseBet
                    repeat(consecutiveLosses) {
                        betAmount = minOf(betAmount * 2, maxBet)
                    }
                    betAmount
                } else {
                    baseBet
                }
            }
        }
    }
    
    /**
     * Проверяет доступность кнопок для размещения ставки
     */
    suspend fun validateBetAvailability(windowType: WindowType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Проверяем наличие всех необходимых областей
                val requiredAreas = listOf(
                    AreaType.RED_BUTTON,
                    AreaType.ORANGE_BUTTON,
                    AreaType.CONFIRM_BET,
                    AreaType.BET_10
                )
                
                val allAreasAvailable = requiredAreas.all { areaType ->
                    areaManager.getAreaForWindow(windowType, areaType) != null
                }
                
                if (!allAreasAvailable) {
                    Log.w(TAG, "Не все области настроены для окна $windowType")
                    return@withContext false
                }
                
                Log.d(TAG, "Все области доступны для размещения ставки в окне $windowType")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки доступности ставки", e)
                false
            }
        }
    }
    
    /**
     * Размещает ставку с автоматическим расчетом суммы
     */
    suspend fun placeAutomaticBet(
        windowType: WindowType,
        betChoice: BetChoice,
        gameState: DualGameState,
        settings: DualModeSettings
    ): Boolean {
        val amount = calculateBetAmount(
            baseBet = settings.baseBet,
            consecutiveLosses = gameState.consecutiveLosses,
            maxBet = settings.maxBet,
            strategy = settings.strategy
        )
        
        Log.d(TAG, "Автоматическая ставка: сумма=$amount, потери подряд=${gameState.consecutiveLosses}")
        
        return placeBet(windowType, betChoice, amount, settings.strategy)
    }
    
    // === СЕТТЕРЫ ДЛЯ СЛУШАТЕЛЕЙ ===
    
    fun setOnBetPlacedListener(listener: (WindowType, BetChoice, Int) -> Unit) {
        onBetPlaced = listener
    }
    
    fun setOnBetErrorListener(listener: (String, Exception?) -> Unit) {
        onBetError = listener
    }
    
    /**
     * Освобождает ресурсы
     */
    fun cleanup() {
        Log.d(TAG, "Очистка ресурсов DualModeBetPlacer")
        placementScope.cancel()
    }

    /**
     * Преобразует абсолютные координаты в координаты относительно текущего окна
     */
    private fun transformCoordinatesForCurrentWindow(absoluteRect: Rect): Rect {
        // Получаем границы текущего окна
        val windowBounds = com.example.diceautobet.utils.SplitScreenUtils.getWindowBounds(
            context, 
            currentWindowType
        )
        
        // Преобразуем координаты: вычитаем offset окна
        val transformedRect = Rect(
            absoluteRect.left - windowBounds.left,
            absoluteRect.top - windowBounds.top,
            absoluteRect.right - windowBounds.left,
            absoluteRect.bottom - windowBounds.top
        )
        
        Log.d(TAG, "Преобразование координат для $currentWindowType:")
        Log.d(TAG, "  Границы окна: $windowBounds")
        Log.d(TAG, "  Абсолютные координаты: $absoluteRect")
        Log.d(TAG, "  Локальные координаты: $transformedRect")
        
        return transformedRect
    }
}
