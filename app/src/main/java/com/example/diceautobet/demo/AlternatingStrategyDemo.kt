package com.example.diceautobet.demo

import android.util.Log
import com.example.diceautobet.models.*

/**
 * ДЕМО-КЛАСС ДЛЯ ПОКАЗА НОВОЙ АЛЬТЕРНИРУЮЩЕЙ ЛОГИКИ
 * 
 * Показывает пошагово как работает новая стратегия ставок
 */
class AlternatingStrategyDemo {
    
    companion object {
        private const val TAG = "AlternatingStrategyDemo"
    }
    
    /**
     * Демонстрация альтернирующей стратегии
     */
    fun demonstrateStrategy() {
        Log.d(TAG, "\n" + "=".repeat(60))
        Log.d(TAG, "🎲 ДЕМОНСТРАЦИЯ НОВОЙ АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИИ СТАВОК")
        Log.d(TAG, "=".repeat(60))
        
        // Начальное состояние
        var gameState = GameState(
            baseBet = 10,
            balance = 1000,
            betChoice = BetChoice.RED,
            firstResultIgnored = false,
            currentTurnNumber = 0,
            lastActiveResult = GameResultType.UNKNOWN
        )
        
        Log.d(TAG, "💰 Начальный баланс: ${gameState.balance}")
        Log.d(TAG, "💳 Базовая ставка: ${gameState.baseBet}")
        Log.d(TAG, "🎯 Цвет ставки: ${gameState.betChoice}")
        Log.d(TAG, "")
        
        // Имитируем результаты (W = выигрыш, L = проигрыш, D = ничья)
        val simulatedResults = listOf(
            GameResultType.LOSS,    // Первый результат - игнорируем
            GameResultType.WIN,     // Ход 1 (активный)
            GameResultType.LOSS,    // Ход 2 (пассивный - наблюдение)
            GameResultType.LOSS,    // Ход 3 (активный)
            GameResultType.WIN,     // Ход 4 (пассивный - наблюдение)
            GameResultType.WIN,     // Ход 5 (активный)
            GameResultType.DRAW,    // Ход 6 (пассивный - наблюдение)
            GameResultType.LOSS     // Ход 7 (активный)
        )
        
        var resultIndex = 0
        
        // Игнорируем первый результат
        if (gameState.shouldIgnoreFirstResult()) {
            val firstResult = simulatedResults[resultIndex++]
            Log.d(TAG, "🔥 ИГНОРИРУЕМ ПЕРВЫЙ РЕЗУЛЬТАТ: $firstResult")
            gameState = gameState.markFirstResultIgnored()
            Log.d(TAG, "   Результат проигнорирован, начинаем реальную игру")
            Log.d(TAG, "")
        }
        
        // Основной цикл
        while (resultIndex < simulatedResults.size && gameState.balance > 0) {
            val currentResult = simulatedResults[resultIndex++]
            val turnType = gameState.getCurrentTurnType()
            val turnNumber = gameState.currentTurnNumber + 1
            
            Log.d(TAG, "🎯 ХОД $turnNumber (${turnType})")
            Log.d(TAG, "   Статус: ${gameState.getStatusDescription()}")
            
            when (turnType) {
                TurnType.ACTIVE -> {
                    // Активный ход - делаем ставку
                    val betAmount = gameState.calculateBetAmount()
                    Log.d(TAG, "   💰 Делаем ставку: $betAmount")
                    Log.d(TAG, "   🎲 Результат: $currentResult")
                    
                    // Обновляем баланс
                    gameState = gameState.updateBalanceAfterActiveTurn(betAmount, currentResult)
                    Log.d(TAG, "   💳 Новый баланс: ${gameState.balance}")
                    
                    // Переходим к следующему ходу
                    gameState = gameState.advanceToNextTurn(currentResult)
                    
                    val resultEmoji = when (currentResult) {
                        GameResultType.WIN -> "🎉"
                        GameResultType.LOSS -> "💸"
                        GameResultType.DRAW -> "🤝"
                        else -> "❓"
                    }
                    Log.d(TAG, "   $resultEmoji Итог активного хода")
                }
                
                TurnType.PASSIVE -> {
                    // Пассивный ход - только наблюдаем
                    Log.d(TAG, "   👁️ Только наблюдаем (не ставим)")
                    Log.d(TAG, "   🎲 Результат: $currentResult (для информации)")
                    
                    // Переходим к следующему ходу без изменения баланса
                    gameState = gameState.advanceToNextTurn(currentResult)
                    Log.d(TAG, "   ⏭️ Пассивный ход завершен")
                }
            }
            
            Log.d(TAG, "")
        }
        
        // Финальная статистика
        Log.d(TAG, "🏁 РЕЗУЛЬТАТЫ ДЕМОНСТРАЦИИ:")
        Log.d(TAG, "   Финальный баланс: ${gameState.balance}")
        Log.d(TAG, "   Прибыль/убыток: ${gameState.totalProfit}")
        Log.d(TAG, "   Всего ставок: ${gameState.totalBetsPlaced}")
        Log.d(TAG, "   Всего ходов: ${gameState.currentTurnNumber}")
        Log.d(TAG, "=".repeat(60))
    }
    
    /**
     * Показывает схему работы стратегии
     */
    fun showStrategyScheme() {
        Log.d(TAG, "\n" + "📋 СХЕМА РАБОТЫ АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИИ:")
        Log.d(TAG, "")
        Log.d(TAG, "🔥 СТАРТ: Игнорируем первый результат")
        Log.d(TAG, "")
        Log.d(TAG, "🎯 ХОД 1 (АКТИВНЫЙ): Ставим базовую ставку → результат запоминаем")
        Log.d(TAG, "👁️ ХОД 2 (ПАССИВНЫЙ): Пропускаем, только наблюдаем")
        Log.d(TAG, "🎯 ХОД 3 (АКТИВНЫЙ): Смотрим результат хода 1:")
        Log.d(TAG, "   ✅ Если выиграли → ставим базовую")
        Log.d(TAG, "   ❌ Если проиграли → ставим удвоенную")
        Log.d(TAG, "👁️ ХОД 4 (ПАССИВНЫЙ): Пропускаем, только наблюдаем")
        Log.d(TAG, "🎯 ХОД 5 (АКТИВНЫЙ): Смотрим результат хода 3:")
        Log.d(TAG, "   ✅ Если выиграли → ставим базовую")
        Log.d(TAG, "   ❌ Если проиграли → ставим удвоенную")
        Log.d(TAG, "...")
        Log.d(TAG, "И так далее...")
        Log.d(TAG, "")
    }
    
    /**
     * Показывает преимущества новой стратегии
     */
    fun showAdvantages() {
        Log.d(TAG, "\n" + "⭐ ПРЕИМУЩЕСТВА АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИИ:")
        Log.d(TAG, "")
        Log.d(TAG, "1. 🎯 Снижение частоты ставок в 2 раза")
        Log.d(TAG, "2. 💰 Меньше риска потерь при длинных сериях")
        Log.d(TAG, "3. 🧠 Больше времени на анализ и принятие решений")
        Log.d(TAG, "4. 📊 Более стабильная игра без эмоциональных решений")
        Log.d(TAG, "5. ⏱️ Дополнительное время на обработку результатов")
        Log.d(TAG, "6. 🔄 Простая логика: активный → пассивный → активный...")
        Log.d(TAG, "")
    }
}
