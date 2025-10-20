package com.example.diceautobet.game

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.ScreenshotService
import com.example.diceautobet.intelligent.IntelligentGameController
import com.example.diceautobet.services.ButtonStateDetector
import kotlinx.coroutines.*
import kotlin.math.pow

class BettingStrategy(
    private val context: Context,
    private val prefsManager: PreferencesManager,
    private val clickManager: ClickManager,
    private val screenCapture: suspend () -> Bitmap?
) {
    
    companion object {
        private const val TAG = "BettingStrategy"
        private val BET_AMOUNTS = listOf(10, 50, 100, 500, 2500)
        private const val MAX_MARTINGALE_STEPS = 10
        private const val BALANCE_SAFETY_MULTIPLIER = 0.8 // 80% от баланса максимум
    }
    
    // Инициализация интеллектуального контроллера
    private val intelligentController = IntelligentGameController()
    
    // Детектор состояния кнопок по цвету
    private val buttonStateDetector = ButtonStateDetector(context)
    
    // Статистика для аналитики
    private var totalProfit = 0
    private var totalBetsPlaced = 0
    private var maxConsecutiveLosses = 0
    private var currentStreak = 0
    private var sessionStartBalance = 0
    
    // Состояние Мартингейла
    private var martingaleStep = 0
    private var baseBetAmount = 0
    
    /**
     * Выполняет интеллектуальную ставку с полным контролем процесса
     */
    suspend fun performIntelligentBet(gameState: GameState): GameResult<GameState> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Начинаем интеллектуальную ставку: ${gameState.currentBet} на ${gameState.betChoice}")
                
                // ВАЖНО: Дополнительная задержка для стабилизации после предыдущей игры
                delay(2000) // Ждем 2 секунды для полного завершения предыдущей игры
                
                // ВАЖНО: Ждем разблокировки кнопок после предыдущего результата
                if (!waitForButtonsEnabled()) {
                    Log.e(TAG, "Кнопки не разблокированы, прерываем ставку")
                    return@withContext GameResult.Error("Кнопки не разблокированы")
                }
                
                // Дополнительная проверка стабильности
                Log.d(TAG, "Дополнительная проверка стабильности кнопок")
                delay(1000) // Еще одна секунда для стабилизации
                
                // Выбираем область для ставки
                val betArea = when (gameState.betChoice) {
                    BetChoice.RED -> AreaType.RED_BUTTON
                    BetChoice.ORANGE -> AreaType.ORANGE_BUTTON
                    else -> {
                        Log.e(TAG, "Неизвестный тип ставки: ${gameState.betChoice}")
                        return@withContext GameResult.Error("Неизвестный тип ставки")
                    }
                }
                
                // Выполняем ставку с проверкой состояния кнопок
                val betResult = placeBetWithMonitoring(gameState.currentBet, betArea)
                if (betResult is GameResult.Error) {
                    Log.e(TAG, "Ошибка при размещении ставки: ${betResult.message}")
                    return@withContext betResult
                }
                
                // Ждем и анализируем результат
                val gameResult = waitForGameResult()
                
                // Обрабатываем результат
                val newGameState = processGameResult(gameState, gameResult)
                
                Log.d(TAG, "Интеллектуальная ставка завершена: $gameResult")
                GameResult.Success(newGameState)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в интеллектуальной ставке", e)
                GameResult.Error("Ошибка интеллектуальной ставки: ${e.message}", e)
            }
        }
    }
    
    /**
     * Размещение ставки с мониторингом кнопок
     */
    private suspend fun placeBetWithMonitoring(amount: Int, betArea: AreaType): GameResult<Unit> {
        Log.d(TAG, "Размещение ставки $amount на ${betArea.displayName}")
        
        try {
            // 1. Выбираем сумму ставки
            val betAmountResult = selectBetAmount(amount)
            if (betAmountResult is GameResult.Error) {
                return betAmountResult
            }
            
            // 2. Кликаем по кнопке выбора цвета (кнопки уже должны быть разблокированы)
            val colorButtonArea = prefsManager.loadArea(betArea)
            if (colorButtonArea == null) {
                return GameResult.Error("Область кнопки ${betArea.displayName} не найдена")
            }
            
            val colorClickResult = clickManager.clickArea(colorButtonArea)
            if (colorClickResult is GameResult.Error) {
                return GameResult.Error("Ошибка клика по кнопке ${betArea.displayName}")
            }
            
            // 3. Кликаем по кнопке подтверждения
            val confirmButtonArea = prefsManager.loadArea(AreaType.CONFIRM_BET)
            if (confirmButtonArea == null) {
                return GameResult.Error("Область кнопки 'Заключить пари' не найдена")
            }
            
            val confirmClickResult = clickManager.clickArea(confirmButtonArea)
            if (confirmClickResult is GameResult.Error) {
                return GameResult.Error("Ошибка клика по кнопке 'Заключить пари'")
            }
            
            Log.d(TAG, "Ставка успешно размещена")
            return GameResult.Success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при размещении ставки", e)
            return GameResult.Error("Ошибка размещения ставки: ${e.message}", e)
        }
    }
    
    /**
     * Ожидание результата игры с правильной синхронизацией
     */
    private suspend fun waitForGameResult(): String {
        Log.d(TAG, "Ожидание результата игры")
        
        // Шаг 1: Ждем, пока кнопки НЕ СТАНУТ заблокированными (игра началась)
        Log.d(TAG, "Ждем блокировки кнопок (начало игры)")
        var attempts = 0
        val maxWaitForDisable = 50 // 50 * 200ms = 10 секунд
        
        while (attempts < maxWaitForDisable) {
            val screenshot = screenCapture()
            if (screenshot != null) {
                val confirmArea = prefsManager.loadArea(AreaType.CONFIRM_BET)
                if (confirmArea != null) {
                    val isEnabled = buttonStateDetector.isConfirmBetButtonEnabled(screenshot, confirmArea.rect)
                    if (!isEnabled) {
                        Log.d(TAG, "Кнопки заблокированы - игра началась!")
                        break
                    }
                }
            }
            
            attempts++
            delay(200)
        }
        
        if (attempts >= maxWaitForDisable) {
            Log.w(TAG, "Кнопки не заблокировались, возможно игра не началась")
        }
        
        // Шаг 2: Ждем, пока кнопки СТАНУТ разблокированными (игра закончилась)
        Log.d(TAG, "Ждем разблокировки кнопок (окончание игры)")
        val buttonsEnabled = waitForButtonsEnabled()
        
        if (!buttonsEnabled) {
            Log.w(TAG, "Кнопки не разблокировались, возможно игра зависла")
            return "UNKNOWN"
        }
        
        // Шаг 3: Анализируем результат
        Log.d(TAG, "Кнопки разблокированы - игра завершена, анализируем результат")
        val screenshot = screenCapture()
        if (screenshot != null) {
            val result = analyzeGameResult(screenshot)
            Log.d(TAG, "Результат игры: $result")
            return result
        }
        
        Log.w(TAG, "Не удалось определить результат игры")
        return "UNKNOWN"
    }
    
    /**
     * Анализ результата игры по скриншоту
     */
    private fun analyzeGameResult(screenshot: Bitmap): String {
        // Здесь должна быть логика анализа области результата
        // Пока возвращаем заглушку
        return "WIN" // Временная заглушка
    }
    
    /**
     * Обработка результата игры
     */
    private fun processGameResult(gameState: GameState, result: String): GameState {
        val isWin = result == "WIN"
        val isLoss = result == "LOSS"
        
        Log.d(TAG, "Обработка результата: $result")
        
        return if (isWin) {
            // Выигрыш: сбрасываем Мартингейл
            martingaleStep = 0
            val profit = gameState.currentBet
            totalProfit += profit
            currentStreak = 0
            
            Log.d(TAG, "Выигрыш! Прибыль: $profit, общая прибыль: $totalProfit")
            
            gameState.copy(
                balance = gameState.balance + profit,
                currentBet = baseBetAmount,
                consecutiveLosses = 0,
                totalProfit = totalProfit
            )
        } else if (isLoss) {
            // Проигрыш: увеличиваем Мартингейл
            martingaleStep++
            currentStreak++
            maxConsecutiveLosses = maxOf(maxConsecutiveLosses, currentStreak)
            
            val newBetAmount = calculateMartingaleBet(baseBetAmount, martingaleStep)
            totalProfit -= gameState.currentBet
            
            Log.d(TAG, "Проигрыш! Шаг Мартингейла: $martingaleStep, новая ставка: $newBetAmount")
            
            gameState.copy(
                balance = gameState.balance - gameState.currentBet,
                currentBet = newBetAmount,
                consecutiveLosses = gameState.consecutiveLosses + 1,
                totalProfit = totalProfit
            )
        } else {
            // Неизвестный результат
            Log.w(TAG, "Неизвестный результат игры: $result")
            gameState
        }
    }
    
    /**
     * Вычисление ставки по системе Мартингейла
     */
    private fun calculateMartingaleBet(baseBet: Int, step: Int): Int {
        val multiplier = 2.0.pow(step.toDouble())
        val newBet = (baseBet * multiplier).toInt()
        
        // Находим ближайшую доступную сумму ставки
        val availableBets = BET_AMOUNTS
        return availableBets.minByOrNull { kotlin.math.abs(it - newBet) } ?: baseBet
    }
    
    /**
     * Логирует производительность игрового цикла
     */
    private fun logCyclePerformance(cycle: IntelligentGameController.GameCycle) {
        Log.d(TAG, "=== Производительность цикла ===")
        Log.d(TAG, "Общее время: ${cycle.totalTime}мс")
        Log.d(TAG, "Время по фазам:")
        
        cycle.phaseTimings.forEach { (phase, time) ->
            Log.d(TAG, "  $phase: ${time}мс")
        }
        
        if (cycle.issues.isNotEmpty()) {
            Log.w(TAG, "Проблемы в цикле:")
            cycle.issues.forEach { issue ->
                Log.w(TAG, "  - $issue")
            }
        }
    }

    // Статистика для аналитики
    private var totalProfitLegacy = 0
    private var totalBetsPlacedLegacy = 0
    private var maxConsecutiveLossesLegacy = 0
    private var currentStreakLegacy = 0
    private var sessionStartBalanceLegacy = 0
    
    // Состояние Мартингейла
    private var martingaleStepLegacy = 0
    private var baseBetAmountLegacy = 0
    
    suspend fun placeBet(gameState: GameState): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Размещение ставки: ${gameState.currentBet} на ${gameState.betChoice}")
                
                // 1. Выбираем сумму ставки
                val betAmountResult = selectBetAmount(gameState.currentBet)
                if (betAmountResult !is GameResult.Success) {
                    return@withContext betAmountResult
                }
                
                // Дополнительная задержка между кликами для стабильности
                delay(prefsManager.getBetSequenceDelay())
                
                // 2. Выбираем цвет ставки
                val betColorResult = selectBetColor(gameState.betChoice)
                if (betColorResult !is GameResult.Success) {
                    return@withContext betColorResult
                }
                
                // Дополнительная задержка перед подтверждением
                delay(prefsManager.getBetSequenceDelay())
                
                // 3. Подтверждаем ставку
                val confirmResult = confirmBet()
                if (confirmResult !is GameResult.Success) {
                    return@withContext confirmResult
                }
                
                // Финальная задержка после подтверждения
                delay(prefsManager.getBetSequenceDelay())
                
                Log.d(TAG, "Ставка успешно размещена")
                GameResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка размещения ставки", e)
                GameResult.Error("Ошибка размещения ставки: ${e.message}", e)
            }
        }
    }
    
    private suspend fun selectBetAmount(targetAmount: Int): GameResult<Unit> {
        Log.d(TAG, "Выбираем сумму ставки: $targetAmount")
        
        // Находим ближайшую доступную ставку
        val availableBet = findClosestBetAmount(targetAmount)
        
        val areaType = when (availableBet) {
            10 -> AreaType.BET_10
            50 -> AreaType.BET_50
            100 -> AreaType.BET_100
            500 -> AreaType.BET_500
            2500 -> AreaType.BET_2500
            else -> {
                Log.e(TAG, "Неподдерживаемая сумма ставки: $availableBet")
                return GameResult.Error("Неподдерживаемая сумма ставки")
            }
        }
        
        val area = prefsManager.loadAreaUniversal(areaType)
            ?: return GameResult.Error("Область для ставки $availableBet не настроена")
        
        return clickManager.clickArea(area)
    }
    
    private suspend fun selectBetColor(betChoice: BetChoice): GameResult<Unit> {
        Log.d(TAG, "Выбираем цвет ставки: $betChoice")
        
        val areaType = when (betChoice) {
            BetChoice.RED -> AreaType.RED_BUTTON
            BetChoice.ORANGE -> AreaType.ORANGE_BUTTON
        }
        
        val area = prefsManager.loadAreaUniversal(areaType)
            ?: return GameResult.Error("Область для цвета $betChoice не настроена")
        
        return clickManager.clickArea(area)
    }
    
    private suspend fun confirmBet(): GameResult<Unit> {
        Log.d(TAG, "Подтверждаем ставку")
        
        val confirmArea = prefsManager.loadAreaUniversal(AreaType.CONFIRM_BET)
            ?: return GameResult.Error("Область подтверждения ставки не настроена")
        
        return clickManager.clickArea(confirmArea)
    }
    
    private fun findClosestBetAmount(targetAmount: Int): Int {
        // Находим ближайшую доступную ставку, не превышающую целевую
        val availableBets = BET_AMOUNTS.filter { it <= targetAmount }
        
        return if (availableBets.isNotEmpty()) {
            availableBets.maxOrNull() ?: BET_AMOUNTS.first()
        } else {
            // Если целевая ставка меньше минимальной, используем минимальную
            BET_AMOUNTS.first()
        }
    }
    
    /**
     * Рассчитывает следующую ставку по стратегии Мартингейла
     */
    fun calculateNextBet(currentBet: Int, baseBet: Int, consecutiveLosses: Int): Int {
        return if (consecutiveLosses == 0) {
            // После выигрыша возвращаемся к базовой ставке
            baseBet
        } else {
            // Удваиваем ставку после проигрыша
            val nextBet = currentBet * 2
            // Ограничиваем максимальной доступной ставкой
            findClosestBetAmount(nextBet)
        }
    }
    
    /**
     * Проверяет, можно ли разместить ставку указанной суммы
     */
    fun canPlaceBet(amount: Int): Boolean {
        return BET_AMOUNTS.contains(amount)
    }
    
    /**
     * Получает список всех доступных ставок
     */
    fun getAvailableBets(): List<Int> {
        return BET_AMOUNTS.toList()
    }
    
    /**
     * Альтернативная стратегия - фиксированная ставка
     */
    suspend fun placeFixedBet(amount: Int, betChoice: BetChoice): GameResult<Unit> {
        Log.d(TAG, "Размещение фиксированной ставки: $amount на $betChoice")
        
        val gameState = GameState(
            currentBet = amount,
            betChoice = betChoice
        )
        
        return placeBet(gameState)
    }
    
    /**
     * Стратегия прогрессивного увеличения ставки
     */
    fun calculateProgressiveBet(baseBet: Int, consecutiveLosses: Int, progression: Float = 1.5f): Int {
        if (consecutiveLosses == 0) return baseBet
        
        val multiplier = Math.pow(progression.toDouble(), consecutiveLosses.toDouble())
        val nextBet = (baseBet * multiplier).toInt()
        
        return findClosestBetAmount(nextBet)
    }
    
    /**
     * Улучшенная логика расчета следующей ставки с учетом Мартингейла
     */
    fun calculateNextBet(previousResult: RoundResult?, currentBalance: Int): BetCalculationResult {
        val baseAmount = prefsManager.getBaseBet()
        val maxSteps = 5 // Фиксированное значение вместо удаленного getMaxMartingaleSteps()
        
        // Если это первая ставка или выигрыш - сбрасываем Мартингейл
        if (previousResult == null || previousResult.winner != null && !previousResult.isDraw) {
            resetMartingale()
            baseBetAmount = baseAmount
            return BetCalculationResult.Success(baseAmount)
        }
        
        // Если проигрыш или ничья - применяем Мартингейл
        if (previousResult.isDraw || previousResult.winner == null) {
            martingaleStep++
            currentStreak++
            
            // Обновляем максимальную серию проигрышей
            if (currentStreak > maxConsecutiveLosses) {
                maxConsecutiveLosses = currentStreak
            }
            
            // Проверяем лимит шагов Мартингейла
            if (martingaleStep >= maxSteps) {
                resetMartingale()
                return BetCalculationResult.MaxStepsReached(maxSteps)
            }
            
            // Рассчитываем новую ставку (удваиваем)
            val newBet = baseBetAmount * 2.0.pow(martingaleStep.toDouble()).toInt()
            
            // Проверяем доступность ставки
            val availableBet = findClosestBetAmount(newBet)
            if (availableBet == null) {
                resetMartingale()
                return BetCalculationResult.NoAvailableBet
            }
            
            // Проверяем достаточность баланса
            val safetyBalance = (currentBalance * BALANCE_SAFETY_MULTIPLIER).toInt()
            if (availableBet > safetyBalance) {
                resetMartingale()
                return BetCalculationResult.InsufficientBalance(
                    availableBet, 
                    currentBalance
                )
            }
            
            return BetCalculationResult.Success(availableBet)
        }
        
        // Если выигрыш - сбрасываем и начинаем заново
        resetMartingale()
        return BetCalculationResult.Success(baseAmount)
    }
    
    /**
     * Расширенная аналитика ставок
     */
    fun getAdvancedStatistics(): BettingStatistics {
        val winRate = if (totalBetsPlaced > 0) {
            (totalBetsPlaced - maxConsecutiveLosses).toFloat() / totalBetsPlaced
        } else 0f
        
        val profitability = if (sessionStartBalance > 0) {
            (totalProfit.toFloat() / sessionStartBalance) * 100
        } else 0f
        
        return BettingStatistics(
            totalBetsPlaced = totalBetsPlaced,
            totalProfit = totalProfit,
            maxConsecutiveLosses = maxConsecutiveLosses,
            currentStreak = currentStreak,
            winRate = winRate,
            profitability = profitability,
            martingaleStep = martingaleStep,
            recommendedNextBet = baseBetAmount * 2.0.pow(martingaleStep.toDouble()).toInt()
        )
    }
    
    /**
     * Умная стратегия с защитой от больших потерь
     */
    fun calculateSafeBet(currentBalance: Int, targetProfit: Int): SafeBetResult {
        val baseAmount = prefsManager.getBaseBet()
        val maxRisk = currentBalance * 0.1 // Максимум 10% от баланса на риск
        
        // Рассчитываем максимальные потери при полном Мартингейле
        var totalRisk = 0
        var step = 0
        var currentBet = baseAmount
        
        while (step < 5) { // Фиксированное значение вместо удаленного getMaxMartingaleSteps()
            totalRisk += currentBet
            if (totalRisk > maxRisk) {
                break
            }
            currentBet *= 2
            step++
        }
        
        val safeSteps = step
        val potentialProfit = baseAmount // При выигрыше получаем базовую ставку
        val riskRewardRatio = if (totalRisk > 0) potentialProfit.toFloat() / totalRisk else 0f
        
        return SafeBetResult(
            recommendedBaseBet = baseAmount,
            maxSafeSteps = safeSteps,
            totalRiskAmount = totalRisk,
            potentialProfit = potentialProfit,
            riskRewardRatio = riskRewardRatio,
            isRecommended = riskRewardRatio > 0.1f && totalRisk < maxRisk
        )
    }
    
    /**
     * Анализ паттернов для оптимизации ставок
     */
    fun analyzePatterns(recentResults: List<RoundResult>): PatternAnalysis {
        if (recentResults.isEmpty()) {
            return PatternAnalysis.NoData
        }
        
        val recentWins = recentResults.count { !it.isDraw && it.winner != null }
        val recentDraws = recentResults.count { it.isDraw }
        val recentLosses = recentResults.size - recentWins - recentDraws
        
        val winStreaks = mutableListOf<Int>()
        val lossStreaks = mutableListOf<Int>()
        
        var currentWinStreak = 0
        var currentLossStreak = 0
        
        for (result in recentResults) {
            if (!result.isDraw && result.winner != null) {
                if (currentLossStreak > 0) {
                    lossStreaks.add(currentLossStreak)
                    currentLossStreak = 0
                }
                currentWinStreak++
            } else {
                if (currentWinStreak > 0) {
                    winStreaks.add(currentWinStreak)
                    currentWinStreak = 0
                }
                currentLossStreak++
            }
        }
        
        // Добавляем текущие серии
        if (currentWinStreak > 0) winStreaks.add(currentWinStreak)
        if (currentLossStreak > 0) lossStreaks.add(currentLossStreak)
        
        val avgWinStreak = winStreaks.average().takeIf { !it.isNaN() } ?: 0.0
        val avgLossStreak = lossStreaks.average().takeIf { !it.isNaN() } ?: 0.0
        val maxLossStreak = lossStreaks.maxOrNull() ?: 0
        
        return PatternAnalysis.WithData(
            recentWins = recentWins,
            recentLosses = recentLosses,
            recentDraws = recentDraws,
            averageWinStreak = avgWinStreak,
            averageLossStreak = avgLossStreak,
            maxLossStreak = maxLossStreak,
            recommendation = when {
                maxLossStreak > 5 -> "Высокий риск серии проигрышей"
                avgLossStreak > 3 -> "Средний риск, будьте осторожны"
                else -> "Низкий риск, можно продолжать"
            }
        )
    }
    
    private fun resetMartingale() {
        martingaleStep = 0
        currentStreak = 0
    }
    
    fun initializeSession(startBalance: Int) {
        sessionStartBalance = startBalance
        totalProfit = 0
        totalBetsPlaced = 0
        maxConsecutiveLosses = 0
        resetMartingale()
    }
    
    fun recordBetResult(betAmount: Int, won: Boolean, payout: Int = 0) {
        totalBetsPlaced++
        if (won) {
            totalProfit += payout - betAmount
            resetMartingale()
        } else {
            totalProfit -= betAmount
        }
    }
    
    /**
     * Вычисляет следующую ставку на основе результата
     */
    private fun calculateNextBet(currentBet: Int, isWin: Boolean): Int {
        return if (isWin) {
            // При выигрыше возвращаемся к базовой ставке
            prefsManager.getBaseBet()
        } else {
            // При проигрыше удваиваем ставку (Мартингейл)
            val nextBet = currentBet * 2
            // Ограничиваем максимальной ставкой
            minOf(nextBet, 2500)
        }
    }
    
    /**
     * Рассчитывает размер ставки на основе текущей статистики
     */
    fun calculateBet(statistics: GameStatistics): BetCalculationResult {
        return try {
            val currentBalance = prefsManager.getCurrentBalance()
            val baseBet = prefsManager.getBaseBet()
            val maxSteps = 5 // Фиксированное значение вместо удаленного getMaxMartingaleSteps()
            
            // Рассчитываем ставку по стратегии Мартингейла
            val step = minOf(statistics.currentLossStreak, maxSteps)
            val betAmount = baseBet * (2.0.pow(step.toDouble())).toInt()
            
            // Проверяем лимиты
            val maxBet = (currentBalance * BALANCE_SAFETY_MULTIPLIER).toInt()
            val finalBet = betAmount.coerceAtMost(maxBet).coerceAtMost(2500)
            
            if (finalBet < baseBet) {
                BetCalculationResult.InsufficientBalance(finalBet, currentBalance)
            } else {
                BetCalculationResult.Success(finalBet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка расчета ставки", e)
            BetCalculationResult.Error("Ошибка расчета ставки: ${e.message}")
        }
    }
    
    /**
     * Обновляет стратегию на основе результата игры
     */
    fun updateStrategy(result: GameResultType) {
        when (result) {
            GameResultType.WIN -> {
                // Сброс мартингейла при выигрыше
                martingaleStep = 0
                currentStreak = if (currentStreak > 0) currentStreak + 1 else 1
            }
            GameResultType.LOSS -> {
                // Увеличение шага мартингейла при проигрыше
                martingaleStep++
                currentStreak = if (currentStreak < 0) currentStreak - 1 else -1
                maxConsecutiveLosses = maxOf(maxConsecutiveLosses, -currentStreak)
            }
            GameResultType.DRAW -> {
                // Ничья = проигрыш - увеличиваем шаг мартингейла
                martingaleStep++
                currentStreak = if (currentStreak < 0) currentStreak - 1 else -1
                maxConsecutiveLosses = maxOf(maxConsecutiveLosses, -currentStreak)
                Log.d(TAG, "Ничья = проигрыш - шаг мартингейла увеличен")
            }
            GameResultType.UNKNOWN -> {
                Log.w(TAG, "Неизвестный результат - стратегия не изменяется")
            }
        }
        
        totalBetsPlaced++
        Log.d(TAG, "Стратегия обновлена: шаг=$martingaleStep, серия=$currentStreak")
    }
    
    /**
     * Остановка мониторинга кнопок
     */
    fun stopButtonMonitoring() {
        Log.d(TAG, "Остановка мониторинга кнопок")
        buttonStateDetector.stop()
    }
    
    /**
     * Ожидание разблокировки кнопок после результата игры
     */
    private suspend fun waitForButtonsEnabled(): Boolean {
        Log.d(TAG, "Ожидание разблокировки кнопок после результата по цвету пикселей")
        
        // Сначала проведем диагностику текущего состояния кнопок
        val screenshot = screenCapture()
        if (screenshot != null) {
            val confirmBetArea = prefsManager.loadArea(AreaType.CONFIRM_BET)
            val redButtonArea = prefsManager.loadArea(AreaType.RED_BUTTON)
            val orangeButtonArea = prefsManager.loadArea(AreaType.ORANGE_BUTTON)
            
            // Анализируем цвета кнопок для диагностики
            confirmBetArea?.let { area ->
                buttonStateDetector.analyzeButtonColors(screenshot, area.rect, "Заключить пари")
            }
            
            redButtonArea?.let { area ->
                buttonStateDetector.analyzeButtonColors(screenshot, area.rect, "Красная кнопка")
            }
            
            orangeButtonArea?.let { area ->
                buttonStateDetector.analyzeButtonColors(screenshot, area.rect, "Оранжевая кнопка")
            }
        }
        
        // Используем новый детектор состояния по точным цветам
        val allButtonsReady = buttonStateDetector.waitForAllButtonsEnabled(screenCapture)
        
        if (allButtonsReady) {
            Log.i(TAG, "Все кнопки разблокированы по цвету, можно продолжать")
        } else {
            Log.w(TAG, "Кнопки не разблокированы в течение таймаута")
        }
        
        return allButtonsReady
    }
    
    /**
     * Проверка доступности кнопки перед кликом
     */
    private suspend fun waitForButtonAndClick(buttonType: AreaType, maxWaitTime: Long = 10000): Boolean {
        Log.d(TAG, "Ожидание доступности кнопки ${buttonType.displayName}")
        
        // Для новой системы цветового детектора, проверяем общую готовность кнопок
        if (!waitForButtonsEnabled()) {
            Log.w(TAG, "Кнопки не активировались в течение ${maxWaitTime}мс")
            return false
        }
        
        // Получаем область для клика
        val screenArea = prefsManager.loadArea(buttonType)
        if (screenArea == null) {
            Log.e(TAG, "Область для кнопки ${buttonType.displayName} не найдена")
            return false
        }
        
        // Кликаем по кнопке
        val clickResult = clickManager.clickArea(screenArea)
        if (clickResult is GameResult.Success) {
            Log.d(TAG, "Успешный клик по кнопке ${buttonType.displayName}")
            return true
        } else {
            Log.e(TAG, "Ошибка клика по кнопке ${buttonType.displayName}")
            return false
        }
    }
}

// Статистика ставок
data class BettingStatistics(
    val totalBetsPlaced: Int,
    val totalProfit: Int,
    val maxConsecutiveLosses: Int,
    val currentStreak: Int,
    val winRate: Float,
    val profitability: Float,
    val martingaleStep: Int,
    val recommendedNextBet: Int
)

// Результат анализа безопасности
data class SafeBetResult(
    val recommendedBaseBet: Int,
    val maxSafeSteps: Int,
    val totalRiskAmount: Int,
    val potentialProfit: Int,
    val riskRewardRatio: Float,
    val isRecommended: Boolean
)

// Анализ паттернов
sealed class PatternAnalysis {
    object NoData : PatternAnalysis()
    data class WithData(
        val recentWins: Int,
        val recentLosses: Int,
        val recentDraws: Int,
        val averageWinStreak: Double,
        val averageLossStreak: Double,
        val maxLossStreak: Int,
        val recommendation: String
    ) : PatternAnalysis()
}

