package com.example.diceautobet.models

import com.example.diceautobet.models.DiceResult
import com.example.diceautobet.utils.FileLogger
import android.util.Log

/**
 * Цвета для ставок в одиночном режиме
 */
enum class BetColor(val displayName: String) {
    BLUE("Синий"),
    RED("Красный")
}

/**
 * Состояние игры в одиночном режиме
 */
data class SingleModeGameState(
    // Основные параметры ставки
    val baseBet: Int = 20,                     // Начальная ставка (20 через 10 + x2)
    val currentBet: Int = 20,                  // Текущая ставка
    val currentColor: BetColor = BetColor.BLUE, // Текущий цвет ставки
    
    // Счетчики проигрышей
    val consecutiveLossesOnColor: Int = 0,      // Проигрыши подряд на текущем цвете
    val totalConsecutiveLosses: Int = 0,        // Общие проигрыши подряд
    
    // Статистика
    val totalGames: Int = 0,                    // Общее количество игр
    val totalWins: Int = 0,                     // Общее количество выигрышей
    val totalLosses: Int = 0,                   // Общее количество проигрышей
    val totalDraws: Int = 0,                    // Общее количество ничьих
    val totalProfit: Int = 0,                   // Общая прибыль/убыток
    
    // Состояние игры
    val isGameActive: Boolean = false,          // Активна ли игра
    val isPaused: Boolean = false,              // На паузе ли игра
    val lastResult: DiceResult? = null,         // Последний результат
    val gameStartTime: Long = 0L,               // Время начала игры
    
    // Настройки
    val maxBet: Int = 200000,                   // Максимальная ставка (остановка)
    val maxLossesBeforeColorSwitch: Int = 2     // Проигрышей до смены цвета
) {
    /**
     * Получить следующую ставку после проигрыша
     * ИЗМЕНЕНО: Убран лимит maxBet - удваиваем без ограничений
     */
    fun getNextBetAfterLoss(): Int {
        val nextBet = currentBet * 2
        Log.d("SingleModeGameState", "💰 getNextBetAfterLoss(): $currentBet × 2 = $nextBet")
        FileLogger.d("SingleModeGameState", "💰 Удвоение ставки: $currentBet → $nextBet")
        return nextBet // Удваиваем без лимита
    }
    
    /**
     * Нужно ли менять цвет ставки
     */
    fun shouldSwitchColor(): Boolean {
        return consecutiveLossesOnColor >= maxLossesBeforeColorSwitch
    }
    
    /**
     * Получить противоположный цвет
     */
    fun getOppositeColor(): BetColor {
        return if (currentColor == BetColor.BLUE) BetColor.RED else BetColor.BLUE
    }
    
    /**
     * Нужно ли остановить игру (достигнута максимальная ставка)
     * ОТКЛЮЧЕНО: Убран лимит максимальной ставки по требованию пользователя
     */
    fun shouldStopGame(): Boolean {
        return false // Всегда продолжаем игру, без лимита ставки
    }
    
    /**
     * Сколько раз нужно нажать кнопку удвоения для текущей ставки
     * НОВАЯ ЛОГИКА: Для базовой ставки 20 всегда рассчитываем от 10
     */
    fun getDoublingClicksNeeded(): Int {
        if (baseBet == 20) {
            // Новая логика для системы 10 + x2
            return calculateMultiplierClicksFor20System(currentBet)
        } else {
            // Старая логика для других базовых ставок
            if (currentBet <= baseBet) return 0
            
            var bet = baseBet
            var clicks = 0
            
            while (bet < currentBet) {
                bet *= 2
                clicks++
            }
            
            return clicks
        }
    }
    
    /**
     * Вычисляет количество нажатий x2 для системы с базовой ставкой 20 (10 + x2)
     */
    private fun calculateMultiplierClicksFor20System(targetBet: Int): Int {
        if (targetBet < 10) return 0
        
        // Находим степень двойки: targetBet = 10 * 2^n
        var current = 10
        var clicks = 0
        
        while (current < targetBet) {
            current *= 2
            clicks++
        }
        
        return clicks
    }
    
    /**
     * Обработать результат игры и получить новое состояние
     */
    fun processGameResult(result: DiceResult): SingleModeGameState {
        val TAG = "SingleModeGameState"
        
        val isWin = when (currentColor) {
            BetColor.BLUE -> result.leftDots > result.rightDots
            BetColor.RED -> result.rightDots > result.leftDots
        }
        
        val isDraw = result.leftDots == result.rightDots
        
        Log.d(TAG, "🎲 processGameResult: кубики=${result.leftDots}:${result.rightDots}, цвет=$currentColor, текущая_ставка=$currentBet")
        FileLogger.i(TAG, "🎲 Обработка результата: ${result.leftDots}:${result.rightDots}, цвет=$currentColor, ставка=$currentBet")
        
        return when {
            isWin -> {
                Log.d(TAG, "✅ ВЫИГРЫШ! Возврат к базовой ставке: $baseBet")
                FileLogger.i(TAG, "✅ ВЫИГРЫШ → базовая ставка $baseBet")
                // Выигрыш - возвращаемся к базовой ставке
                copy(
                    currentBet = baseBet,
                    consecutiveLossesOnColor = 0,
                    totalConsecutiveLosses = 0,
                    totalGames = totalGames + 1,
                    totalWins = totalWins + 1,
                    totalProfit = totalProfit + currentBet,
                    lastResult = result
                )
            }
            isDraw -> {
                val nextBet = getNextBetAfterLoss()
                Log.d(TAG, "🟰 НИЧЬЯ! Текущая ставка: $currentBet → Следующая ставка: $nextBet")
                FileLogger.w(TAG, "🟰 НИЧЬЯ: $currentBet → удвоение → $nextBet")
                
                // Ничья считается проигрышем
                val newLossesOnColor = consecutiveLossesOnColor + 1
                val newColor = if (shouldSwitchColor()) getOppositeColor() else currentColor
                val newLossesAfterSwitch = if (shouldSwitchColor()) 0 else newLossesOnColor
                
                if (shouldSwitchColor()) {
                    Log.d(TAG, "🔄 Смена цвета: $currentColor → $newColor (проигрышей на цвете: $newLossesOnColor)")
                    FileLogger.i(TAG, "🔄 Смена цвета: $currentColor → $newColor")
                }
                
                copy(
                    currentBet = nextBet,
                    currentColor = newColor,
                    consecutiveLossesOnColor = newLossesAfterSwitch,
                    totalConsecutiveLosses = totalConsecutiveLosses + 1,
                    totalGames = totalGames + 1,
                    totalDraws = totalDraws + 1,
                    totalProfit = totalProfit - currentBet,
                    lastResult = result
                )
            }
            else -> {
                val nextBet = getNextBetAfterLoss()
                Log.d(TAG, "❌ ПРОИГРЫШ! Текущая ставка: $currentBet → Следующая ставка: $nextBet")
                FileLogger.w(TAG, "❌ ПРОИГРЫШ: $currentBet → удвоение → $nextBet")
                
                // Проигрыш
                val newLossesOnColor = consecutiveLossesOnColor + 1
                val newColor = if (shouldSwitchColor()) getOppositeColor() else currentColor
                val newLossesAfterSwitch = if (shouldSwitchColor()) 0 else newLossesOnColor
                
                if (shouldSwitchColor()) {
                    Log.d(TAG, "🔄 Смена цвета: $currentColor → $newColor (проигрышей на цвете: $newLossesOnColor)")
                    FileLogger.i(TAG, "🔄 Смена цвета: $currentColor → $newColor")
                }
                
                copy(
                    currentBet = nextBet,
                    currentColor = newColor,
                    consecutiveLossesOnColor = newLossesAfterSwitch,
                    totalConsecutiveLosses = totalConsecutiveLosses + 1,
                    totalGames = totalGames + 1,
                    totalLosses = totalLosses + 1,
                    totalProfit = totalProfit - currentBet,
                    lastResult = result
                )
            }
        }
    }
    
    /**
     * Получить процент выигрышей
     */
    fun getWinRate(): Float {
        return if (totalGames > 0) (totalWins.toFloat() / totalGames.toFloat()) * 100f else 0f
    }
    
    /**
     * Получить время игры в миллисекундах
     */
    fun getGameDuration(): Long {
        return if (gameStartTime > 0) System.currentTimeMillis() - gameStartTime else 0L
    }
}