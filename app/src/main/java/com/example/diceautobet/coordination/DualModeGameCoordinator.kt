package com.example.diceautobet.coordination

import android.content.Context
import android.util.Log
import com.example.diceautobet.automation.DualModeBetPlacer
import com.example.diceautobet.detection.DualModeResultDetector
import com.example.diceautobet.logging.GameLogger
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.models.*
import com.example.diceautobet.sync.DualModeSmartSynchronizer
import com.example.diceautobet.timing.DualModeTimingOptimizer
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.ScreenshotService
import kotlinx.coroutines.*

/**
 * Координатор игры в двойном режиме с экономной AI логикой
 * Управляет всеми аспектами автоматической игры с двумя окнами
 * 💰 ЭКОНОМИЯ: AI запросы только при изменении кубиков
 */
class DualModeGameCoordinator(
    private val context: Context,
    private val areaManager: DualWindowAreaManager,
    private val screenshotService: ScreenshotService,
    private val gameLogger: GameLogger
) {
    companion object {
        private const val TAG = "DualModeGameCoordinator"
        private const val STRATEGY_DELAY_MS = 100L
    }

    // Компоненты координатора
    private lateinit var betPlacer: DualModeBetPlacer
    private lateinit var resultDetector: DualModeResultDetector
    private lateinit var smartSynchronizer: DualModeSmartSynchronizer
    private lateinit var timingOptimizer: DualModeTimingOptimizer
    
    // Состояние координатора
    private var gameState = DualGameState()
    private var settings = DualModeSettings()
    
    // 💰 ЭКОНОМИЯ: AI компоненты
    private lateinit var preferencesManager: PreferencesManager
    
    // Корутины
    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob: Job? = null
    private var isRunning = false
    
    // Слушатели событий
    private var onStateChanged: ((DualGameState) -> Unit)? = null
    private var onBetCompleted: ((WindowType, BetChoice, Int) -> Unit)? = null
    private var onResultProcessed: ((WindowType, RoundResult) -> Unit)? = null
    private var onErrorOccurred: ((String, String) -> Unit)? = null

    /**
     * Инициализирует координатор с настройками
     */
    fun initialize(initialState: DualGameState, initialSettings: DualModeSettings) {
        Log.d(TAG, "💰 Инициализация экономного координатора")
        
        gameState = initialState
        settings = initialSettings
        
        // Инициализируем PreferencesManager для AI
        preferencesManager = PreferencesManager(context)
        
        // Инициализируем компоненты
        initializeComponents()
        
        Log.d(TAG, "✅ Экономный координатор инициализирован")
    }

    /**
     * Запускает координатор
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Координатор уже запущен")
            return
        }
        
        Log.d(TAG, "🚀 Запуск экономного координатора")
        
        // 💰 ВАЖНО: сбрасываем флаг первой ставки при старте
        if (::resultDetector.isInitialized) {
            resultDetector.resetFirstBetFlag()
        }
        
        isRunning = true
        
        // Запускаем основной игровой цикл
        gameJob = coordinatorScope.launch {
            runGameLoop()
        }
        
        // Уведомляем об изменении состояния
        notifyStateChanged()
        
        Log.d(TAG, "✅ Экономный координатор запущен")
    }

    /**
     * Останавливает координатор
     */
    fun stop() {
        Log.d(TAG, "Остановка координатора")
        
        isRunning = false
        
        // Отменяем игровой цикл
        gameJob?.cancel()
        gameJob = null
        
        // Останавливаем компоненты
        if (::resultDetector.isInitialized) {
            resultDetector.stopDetection()
        }
        
        // Уведомляем об изменении состояния
        notifyStateChanged()
        
        Log.d(TAG, "Координатор остановлен")
    }

    /**
     * Обновляет настройки
     */
    fun updateSettings(newSettings: DualModeSettings) {
        Log.d(TAG, "Обновление настроек координатора")
        settings = newSettings
        
        // Обновляем компоненты если они инициализированы
        if (::timingOptimizer.isInitialized) {
            timingOptimizer.updateSettings(newSettings)
        }
    }

    /**
     * Получает статистику
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "isRunning" to isRunning,
            "totalBets" to gameState.totalBetsPlaced,
            "profit" to gameState.totalProfit,
            "activeWindow" to gameState.currentActiveWindow.name
        )
    }

    /**
     * Оптимизирует производительность
     */
    fun optimize() {
        Log.d(TAG, "Оптимизация производительности")
        
        if (::timingOptimizer.isInitialized) {
            timingOptimizer.optimize()
        }
    }

    /**
     * Основной игровой цикл
     */
    private suspend fun runGameLoop() {
        Log.d(TAG, "Запуск игрового цикла")
        
        try {
            while (isRunning && coordinatorScope.isActive) {
                // Проверяем текущее состояние
                if (!gameState.isReadyForDualMode()) {
                    Log.w(TAG, "Игра не готова для двойного режима")
                    delay(1000)
                    continue
                }
                
                // Выполняем цикл игры
                performGameCycle()
                
                // Пауза между циклами
                delay(settings.delayBetweenActions)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Игровой цикл отменен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в игровом цикле", e)
            onErrorOccurred?.invoke("Ошибка игрового цикла", e.message ?: "")
        }
        
        Log.d(TAG, "Игровой цикл завершен")
    }

    /**
     * Выполняет один цикл игры
     */
    private suspend fun performGameCycle() {
        try {
            // 1. Размещаем ставку если нужно
            if (shouldPlaceBet()) {
                placeBetInActiveWindow()
            }
            
            // 2. Проверяем результаты
            checkForResults()
            
            // 3. Применяем стратегию если есть результат
            if (gameState.waitingForResult) {
                processStrategy()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в цикле игры", e)
            onErrorOccurred?.invoke("Ошибка цикла игры", e.message ?: "")
        }
    }

    /**
     * Проверяет, нужно ли размещать ставку
     */
    private fun shouldPlaceBet(): Boolean {
        return !gameState.waitingForResult && 
               gameState.isRunning &&
               gameState.currentActiveWindow != null
    }

    /**
     * Размещает ставку в активном окне
     */
    private suspend fun placeBetInActiveWindow() {
        val activeWindow = gameState.currentActiveWindow ?: return
        
        try {
            Log.d(TAG, "Размещение ставки в окне $activeWindow")
            
            val betAmount = calculateBetAmount()
            val betChoice = determineBetChoice()
            
            // Используем размещателя ставок
            if (::betPlacer.isInitialized) {
                val success = betPlacer.placeBet(activeWindow, betChoice, betAmount, settings.strategy)
                if (success) {
                    handleBetPlaced(activeWindow, betChoice, betAmount)
                } else {
                    handleBetFailed(activeWindow, betChoice, betAmount)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка размещения ставки", e)
            onErrorOccurred?.invoke("Ошибка размещения ставки", e.message ?: "")
        }
    }

    /**
     * Обрабатывает успешное размещение ставки
     */
    private fun handleBetPlaced(windowType: WindowType, betChoice: BetChoice, amount: Int) {
        Log.d(TAG, "Ставка размещена: $windowType, $betChoice, $amount")
        
        gameState = gameState.copy(
            waitingForResult = true,
            lastBetWindow = windowType,
            totalBetsPlaced = gameState.totalBetsPlaced + 1
        )
        
        gameLogger.logBet(betChoice, amount, mapOf(
            "window" to windowType.name,
            "strategy" to settings.strategy.name
        ))
        
        onBetCompleted?.invoke(windowType, betChoice, amount)
        notifyStateChanged()
    }

    /**
     * Обрабатывает неудачное размещение ставки
     */
    private fun handleBetFailed(windowType: WindowType, betChoice: BetChoice, amount: Int) {
        Log.w(TAG, "Не удалось разместить ставку: $windowType, $betChoice, $amount")
        
        gameLogger.logError(Exception("Ошибка размещения ставки"), "Окно: $windowType, Выбор: $betChoice, Сумма: $amount")
        
        onErrorOccurred?.invoke("Ошибка размещения ставки", "Не удалось разместить ставку в окне $windowType")
    }

    /**
     * Проверяет результаты игры
     */
    private suspend fun checkForResults() {
        if (!gameState.waitingForResult) return
        
        try {
            if (::resultDetector.isInitialized) {
                // Детектор уже работает автономно через startDetection
                // Здесь можно добавить дополнительную логику если нужно
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки результатов", e)
        }
    }

    /**
     * Обрабатывает стратегию
     */
    private suspend fun processStrategy() {
        try {
            when (settings.strategy) {
                DualStrategy.WIN_SWITCH -> applyWinSwitchStrategy()
                DualStrategy.LOSS_DOUBLE -> applyLossDoubleStrategy()
                DualStrategy.COLOR_ALTERNATING -> applyColorAlternatingStrategy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки стратегии", e)
        }
    }

    /**
     * Применяет стратегию переключения при выигрыше
     */
    private suspend fun applyWinSwitchStrategy() {
        // Логика стратегии WIN_SWITCH
        Log.d(TAG, "Применение стратегии WIN_SWITCH")
        
        delay(STRATEGY_DELAY_MS)
        
        // Переключаем окно при выигрыше
        gameState = gameState.switchActiveWindow()
        notifyStateChanged()
    }

    /**
     * Применяет стратегию удвоения при проигрыше
     */
    private suspend fun applyLossDoubleStrategy() {
        // Логика стратегии LOSS_DOUBLE
        Log.d(TAG, "Применение стратегии LOSS_DOUBLE")
        
        delay(STRATEGY_DELAY_MS)
        
        // Переключаем окно при проигрыше и удваиваем
        gameState = gameState.switchActiveWindow()
        notifyStateChanged()
    }

    /**
     * Применяет стратегию чередования цветов
     */
    private suspend fun applyColorAlternatingStrategy() {
        // Логика стратегии COLOR_ALTERNATING
        Log.d(TAG, "Применение стратегии COLOR_ALTERNATING")
        
        delay(STRATEGY_DELAY_MS)
        
        // Меняем цвет после определенного количества проигрышей
        if (gameState.consecutiveLosses >= settings.maxConsecutiveLosses) {
            val newColor = if (gameState.currentColor == BetChoice.RED) 
                BetChoice.ORANGE else BetChoice.RED
            
            gameState = gameState.copy(
                currentColor = newColor,
                consecutiveLosses = 0
            )
        }
        
        notifyStateChanged()
    }

    /**
     * Вычисляет сумму ставки
     */
    private fun calculateBetAmount(): Int {
        // Простая логика вычисления суммы ставки
        return when (settings.strategy) {
            DualStrategy.WIN_SWITCH -> settings.baseBet
            DualStrategy.LOSS_DOUBLE -> {
                if (gameState.consecutiveLosses > 0) {
                    minOf(settings.baseBet * (1 shl gameState.consecutiveLosses), settings.maxBet)
                } else {
                    settings.baseBet
                }
            }
            DualStrategy.COLOR_ALTERNATING -> settings.baseBet
        }
    }

    /**
     * Определяет выбор ставки
     */
    private fun determineBetChoice(): BetChoice {
        return gameState.currentColor
    }

    /**
     * Инициализирует компоненты
     */
    private fun initializeComponents() {
        Log.d(TAG, "💰 Инициализация экономных компонентов")
        
        // Сначала инициализируем оптимизатор тайминга
        timingOptimizer = DualModeTimingOptimizer(context)
        
        // Затем инициализируем остальные компоненты, которые зависят от него
        betPlacer = DualModeBetPlacer(context, areaManager, timingOptimizer)
        
        // Инициализируем детектор результатов с экономной AI логикой
        resultDetector = DualModeResultDetector(context, screenshotService, timingOptimizer)
        
        // 🤖 ВАЖНО: инициализируем AI компоненты для экономии
        resultDetector.initializeAI(preferencesManager)
        
        resultDetector.setOnResultDetectedListener { windowType, result ->
            handleResultDetected(windowType, result)
        }
        resultDetector.setOnDetectionErrorListener { message, details ->
            onErrorOccurred?.invoke(message, details?.message ?: "Unknown error")
        }
        
        // Инициализируем синхронизатор
        smartSynchronizer = DualModeSmartSynchronizer(context, timingOptimizer)
        
        Log.d(TAG, "✅ Экономные компоненты инициализированы")
    }

    /**
     * Обрабатывает обнаружение результата
     */
    private fun handleResultDetected(windowType: WindowType, result: RoundResult) {
        Log.d(TAG, "Обнаружен результат в окне $windowType: $result")

        // Обновляем состояние игры
        gameState = gameState.copy(
            waitingForResult = false
        )

        // Обновляем статистику
        updateStatistics(result)

        // Уведомляем слушателей
        onResultProcessed?.invoke(windowType, result)
        notifyStateChanged()
    }

    /**
     * Обновляет статистику
     */
    private fun updateStatistics(result: RoundResult) {
        val isWin = result.winner != null && !result.isDraw
        
        if (isWin) {
            gameState = gameState.copy(
                consecutiveLosses = 0
            )
        } else {
            gameState = gameState.copy(
                consecutiveLosses = gameState.consecutiveLosses + 1
            )
        }
    }

    /**
     * Уведомляет об изменении состояния
     */
    private fun notifyStateChanged() {
        onStateChanged?.invoke(gameState)
    }

    /**
     * Проверяет, запущен ли координатор
     */
    fun isRunning(): Boolean = isRunning

    // === СЕТТЕРЫ ДЛЯ СЛУШАТЕЛЕЙ ===

    fun setOnStateChangedListener(listener: (DualGameState) -> Unit) {
        onStateChanged = listener
    }

    fun setOnBetCompletedListener(listener: (WindowType, BetChoice, Int) -> Unit) {
        onBetCompleted = listener
    }

    fun setOnResultProcessedListener(listener: (WindowType, RoundResult) -> Unit) {
        onResultProcessed = listener
    }

    fun setOnErrorOccurredListener(listener: (String, String) -> Unit) {
        onErrorOccurred = listener
    }

    /**
     * Освобождает ресурсы
     */
    fun cleanup() {
        Log.d(TAG, "Очистка ресурсов координатора")
        
        stop()
        
        if (::resultDetector.isInitialized) {
            resultDetector.cleanup()
        }
        
        if (::smartSynchronizer.isInitialized) {
            smartSynchronizer.cleanup()
        }
        
        coordinatorScope.cancel()
        
        Log.d(TAG, "Ресурсы координатора очищены")
    }
}
