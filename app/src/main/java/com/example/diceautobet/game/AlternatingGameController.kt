package com.example.diceautobet.game

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import kotlinx.coroutines.*

/**
 * НОВЫЙ КОНТРОЛЛЕР ИГРЫ С АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИЕЙ
 * 
 * Логика:
 * 1. Активный ход: делаем ставку → получаем результат
 * 2. Пассивный ход: пропускаем (не делаем ставку)
 * 3. Чередование: активный → пассивный → активный → пассивный...
 * 4. Размер ставки: выиграли последний активный → базовая, проиграли → удвоенная
 * 5. ВАЖНО: Первый результат кубиков после СТАРТ игнорируем полностью
 */
class AlternatingGameController(
    private val context: Context,
    private val prefsManager: PreferencesManager,
    private val clickManager: ClickManager,
    private val screenCapture: suspend () -> Bitmap?,
    private val resultAnalyzer: ResultAnalyzer? = null  // Опциональный анализатор результатов
) {
    
    companion object {
        private const val TAG = "AlternatingGameController"
    }
    
    private val gameScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob: Job? = null
    private var isRunning = false
    
    // Observers для уведомлений
    private val observers = mutableListOf<GameStateObserver>()
    
    fun addObserver(observer: GameStateObserver) {
        observers.add(observer)
    }
    
    fun removeObserver(observer: GameStateObserver) {
        observers.remove(observer)
    }
    
    private fun notifyObservers(gameState: GameState) {
        observers.forEach { it.onGameStateChanged(gameState) }
    }
    
    /**
     * Запускает игру с новой альтернирующей логикой
     */
    fun startGame(initialState: GameState = GameState()): GameState {
        if (isRunning) {
            Log.d(TAG, "Игра уже запущена")
            return initialState
        }
        
        Log.d(TAG, "🚀 ЗАПУСК ИГРЫ С АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИЕЙ")
        isRunning = true
        
        var currentGameState = initialState.copy(
            isRunning = true,
            startTime = System.currentTimeMillis(),
            isPaused = false,
            firstResultIgnored = false,
            currentTurnNumber = 0
        )
        
        gameJob = gameScope.launch {
            try {
                currentGameState = runAlternatingGameLoop(currentGameState)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в игровом цикле", e)
                stopGame()
            }
        }
        
        notifyObservers(currentGameState)
        return currentGameState
    }
    
    /**
     * Останавливает игру
     */
    fun stopGame(): GameState {
        if (!isRunning) {
            return GameState()
        }
        
        Log.d(TAG, "🛑 ОСТАНОВКА ИГРЫ")
        isRunning = false
        gameJob?.cancel()
        gameJob = null
        
        val finalState = GameState(
            isRunning = false,
            isPaused = false,
            endTime = System.currentTimeMillis()
        )
        
        notifyObservers(finalState)
        return finalState
    }
    
    /**
     * Основной игровой цикл с альтернирующей логикой
     */
    private suspend fun runAlternatingGameLoop(initialState: GameState): GameState {
        var gameState = initialState
        
        Log.d(TAG, "=== НАЧАЛО АЛЬТЕРНИРУЮЩЕГО ИГРОВОГО ЦИКЛА ===")
        
        while (isRunning && !gameState.isPaused && !gameState.shouldStop()) {
            try {
                Log.d(TAG, "\n" + "=".repeat(50))
                Log.d(TAG, "🎯 ${gameState.getStatusDescription()}")
                Log.d(TAG, "💰 Баланс: ${gameState.balance}, Прибыль: ${gameState.totalProfit}")
                
                // Проверяем, нужно ли игнорировать первый результат
                if (gameState.shouldIgnoreFirstResult()) {
                    Log.d(TAG, "🔥 ИГНОРИРУЕМ ПЕРВЫЙ РЕЗУЛЬТАТ ПОСЛЕ СТАРТА")
                    gameState = ignoreFirstResult(gameState)
                    continue
                }
                
                val currentTurnType = gameState.getCurrentTurnType()
                
                when (currentTurnType) {
                    TurnType.ACTIVE -> {
                        Log.d(TAG, "🎯 АКТИВНЫЙ ХОД - делаем ставку")
                        gameState = performActiveTurn(gameState)
                    }
                    TurnType.PASSIVE -> {
                        Log.d(TAG, "👁️ ПАССИВНЫЙ ХОД - только наблюдаем")
                        gameState = performPassiveTurn(gameState)
                    }
                }
                
                notifyObservers(gameState)
                
                // Пауза между ходами
                delay(1000)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в игровом цикле", e)
                delay(5000)
            }
        }
        
        Log.d(TAG, "=== ЗАВЕРШЕНИЕ ИГРОВОГО ЦИКЛА ===")
        return stopGame()
    }
    
    /**
     * Игнорирует первый результат после старта
     */
    private suspend fun ignoreFirstResult(gameState: GameState): GameState {
        Log.d(TAG, "Ожидание первого результата для игнорирования...")
        
        // Ждем первый результат
        delay(3000) // Даем время на один раунд
        
        // Анализируем результат (но игнорируем его)
        val screenshot = screenCapture()
        val result = if (screenshot != null) {
            analyzeGameResult(screenshot)
        } else {
            GameResultType.UNKNOWN
        }
        
        Log.d(TAG, "Первый результат проигнорирован: $result")
        
        return gameState.markFirstResultIgnored()
    }
    
    /**
     * Выполняет активный ход - делает ставку
     */
    private suspend fun performActiveTurn(gameState: GameState): GameState {
        Log.d(TAG, "--- Выполняем активный ход ---")
        
        try {
            // Рассчитываем размер ставки
            val betAmount = gameState.calculateBetAmount()
            Log.d(TAG, "💰 Размер ставки для активного хода: $betAmount")
            
            // Размещаем ставку
            val betResult = placeBet(betAmount, gameState.betChoice)
            if (betResult is GameResult.Error) {
                Log.e(TAG, "Ошибка размещения ставки: ${betResult.message}")
                return gameState
            }
            
            Log.d(TAG, "✅ Ставка $betAmount размещена, ждем результат...")
            
            // Ждем результат
            val result = waitForGameResult()
            Log.d(TAG, "🎲 Результат активного хода: $result")
            
            // Обновляем состояние
            var newGameState = gameState.updateBalanceAfterActiveTurn(betAmount, result)
            newGameState = newGameState.advanceToNextTurn(result)
            
            Log.d(TAG, "Активный ход завершен. Новый баланс: ${newGameState.balance}")
            return newGameState
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в активном ходе", e)
            return gameState
        }
    }
    
    /**
     * Выполняет пассивный ход - только наблюдает
     */
    private suspend fun performPassiveTurn(gameState: GameState): GameState {
        Log.d(TAG, "--- Выполняем пассивный ход ---")
        Log.d(TAG, "Пропускаем ставку, только наблюдаем за результатом...")
        
        try {
            // Просто ждем результат без ставки
            val result = waitForAnyResult()
            Log.d(TAG, "🎲 Результат пассивного хода (для наблюдения): $result")
            
            // Переходим к следующему ходу без изменения баланса
            val newGameState = gameState.advanceToNextTurn(result)
            
            Log.d(TAG, "Пассивный ход завершен. Следующий будет активным.")
            return newGameState
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в пассивном ходе", e)
            return gameState
        }
    }
    
    /**
     * Размещает ставку указанной суммы
     */
    private suspend fun placeBet(amount: Int, betChoice: BetChoice): GameResult<Unit> {
        Log.d(TAG, "Размещаем ставку: $amount на $betChoice")
        
        try {
            // 1. Выбираем сумму ставки
            val betAmountResult = selectBetAmount(amount)
            if (betAmountResult is GameResult.Error) {
                return betAmountResult
            }
            
            delay(500) // Задержка между кликами
            
            // 2. Выбираем цвет ставки
            val betColorResult = selectBetColor(betChoice)
            if (betColorResult is GameResult.Error) {
                return betColorResult
            }
            
            delay(500) // Задержка перед подтверждением
            
            // 3. Подтверждаем ставку
            val confirmResult = confirmBet()
            if (confirmResult is GameResult.Error) {
                return confirmResult
            }
            
            Log.d(TAG, "✅ Ставка успешно размещена")
            return GameResult.Success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка размещения ставки", e)
            return GameResult.Error("Ошибка размещения ставки: ${e.message}", e)
        }
    }
    
    /**
     * Выбирает сумму ставки
     */
    private suspend fun selectBetAmount(targetAmount: Int): GameResult<Unit> {
        val availableBets = listOf(10, 50, 100, 500, 2500)
        val closestBet = availableBets.filter { it <= targetAmount }.maxOrNull() 
            ?: availableBets.first()
        
        val areaType = when (closestBet) {
            10 -> AreaType.BET_10
            50 -> AreaType.BET_50
            100 -> AreaType.BET_100
            500 -> AreaType.BET_500
            2500 -> AreaType.BET_2500
            else -> return GameResult.Error("Неподдерживаемая сумма ставки: $closestBet")
        }
        
        val area = prefsManager.loadAreaUniversal(areaType)
            ?: return GameResult.Error("Область для ставки $closestBet не настроена")
        
        return clickManager.clickArea(area)
    }
    
    /**
     * Выбирает цвет ставки
     */
    private suspend fun selectBetColor(betChoice: BetChoice): GameResult<Unit> {
        val areaType = when (betChoice) {
            BetChoice.RED -> AreaType.RED_BUTTON
            BetChoice.ORANGE -> AreaType.ORANGE_BUTTON
        }
        
        val area = prefsManager.loadAreaUniversal(areaType)
            ?: return GameResult.Error("Область для цвета $betChoice не настроена")
        
        return clickManager.clickArea(area)
    }
    
    /**
     * Подтверждает ставку
     */
    private suspend fun confirmBet(): GameResult<Unit> {
        val area = prefsManager.loadAreaUniversal(AreaType.CONFIRM_BET)
            ?: return GameResult.Error("Область подтверждения ставки не настроена")
        
        return clickManager.clickArea(area)
    }
    
    /**
     * Ждет результат игры после ставки
     */
    private suspend fun waitForGameResult(): GameResultType {
        Log.d(TAG, "Ожидание результата после ставки...")
        
        // Ждем, пока игра начнется и закончится
        delay(4000) // 4 секунды на полный цикл игры
        
        // Анализируем результат
        val screenshot = screenCapture()
        if (screenshot != null) {
            return analyzeGameResult(screenshot)
        }
        
        Log.w(TAG, "Не удалось получить скриншот для анализа")
        return GameResultType.UNKNOWN
    }
    
    /**
     * Ждет любой результат (для пассивного хода)
     */
    private suspend fun waitForAnyResult(): GameResultType {
        Log.d(TAG, "Ожидание любого результата (пассивный ход)...")
        
        // Просто ждем стандартное время раунда
        delay(3000) // 3 секунды на раунд
        
        // Анализируем результат для статистики
        val screenshot = screenCapture()
        if (screenshot != null) {
            return analyzeGameResult(screenshot)
        }
        
        return GameResultType.UNKNOWN
    }
    
    /**
     * Анализирует результат игры по скриншоту
     */
    private fun analyzeGameResult(screenshot: Bitmap): GameResultType {
        // TODO: Здесь должна быть интеграция с системой анализа результатов OpenCV
        // Пока возвращаем случайный результат для тестирования
        val random = (0..2).random()
        return when (random) {
            0 -> {
                Log.d(TAG, "🎉 Симуляция: ВЫИГРЫШ")
                GameResultType.WIN
            }
            1 -> {
                Log.d(TAG, "💸 Симуляция: ПРОИГРЫШ")
                GameResultType.LOSS
            }
            else -> {
                Log.d(TAG, "🤝 Симуляция: НИЧЬЯ")
                GameResultType.DRAW
            }
        }
    }
    
    /**
     * Получает статистику альтернирующей стратегии
     */
    fun getStatistics(gameState: GameState): String {
        val activeTurns = (gameState.currentTurnNumber + 1) / 2
        val passiveTurns = gameState.currentTurnNumber / 2
        
        return buildString {
            appendLine("=== СТАТИСТИКА АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИИ ===")
            appendLine("Всего ходов: ${gameState.currentTurnNumber}")
            appendLine("Активных ходов: $activeTurns")
            appendLine("Пассивных ходов: $passiveTurns")
            appendLine("Текущий ход: ${gameState.getCurrentTurnType()}")
            appendLine("Последний активный результат: ${gameState.lastActiveResult}")
            appendLine("Баланс: ${gameState.balance}")
            appendLine("Общая прибыль: ${gameState.totalProfit}")
            appendLine("Всего ставок: ${gameState.totalBetsPlaced}")
            appendLine("Статус: ${gameState.getStatusDescription()}")
        }
    }
}
