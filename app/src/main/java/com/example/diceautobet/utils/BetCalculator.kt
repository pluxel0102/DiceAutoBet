package com.example.diceautobet.utils

import com.example.diceautobet.models.AreaType

/**
 * Калькулятор ставок для двойного режима
 * 
 * Поддерживает все базовые ставки: 10, 20, 50, 100, 500, 2500
 * Рассчитывает необходимую кнопку и количество удвоений для любой суммы
 */
object BetCalculator {
    
    // Доступные кнопки ставок в порядке убывания
    private val AVAILABLE_BUTTONS = listOf(2500, 500, 100, 50, 10)
    
    // Максимальная ставка
    private const val MAX_BET = 30000
    
    /**
     * Результат расчета ставки
     */
    data class BetStrategy(
        val buttonAmount: Int,        // Сумма кнопки для нажатия (10, 50, 100, 500, 2500)
        val doublingClicks: Int,      // Количество нажатий кнопки x2
        val finalAmount: Int,         // Итоговая сумма ставки
        val areaType: AreaType        // Тип области для нажатия
    ) {
        /**
         * Проверяет, является ли стратегия валидной
         */
        fun isValid(): Boolean = finalAmount <= MAX_BET && doublingClicks >= 0
        
        /**
         * Получает последовательность удвоений для этой базовой ставки
         */
        fun getDoublingSequence(): List<Int> {
            val sequence = mutableListOf<Int>()
            var current = buttonAmount
            
            // Для ставки 20 (10 + x2) начинаем сразу с 20
            if (buttonAmount == 10 && doublingClicks > 0) {
                current = 20 // 10 + x2 = 20
                sequence.add(current)
                
                // Остальные удвоения
                repeat(doublingClicks - 1) {
                    current *= 2
                    if (current <= MAX_BET) {
                        sequence.add(current)
                    }
                }
            } else {
                // Для остальных ставок: обычные удвоения
                sequence.add(current)
                repeat(doublingClicks) {
                    current *= 2
                    if (current <= MAX_BET) {
                        sequence.add(current)
                    }
                }
            }
            
            return sequence
        }
    }
    
    /**
     * Рассчитывает стратегию ставки для указанной суммы
     */
    fun calculateBetStrategy(targetAmount: Int): BetStrategy {
        when (targetAmount) {
            // Прямые кнопки без удвоений
            10 -> return BetStrategy(10, 0, 10, AreaType.BET_10)
            50 -> return BetStrategy(50, 0, 50, AreaType.BET_50)
            100 -> return BetStrategy(100, 0, 100, AreaType.BET_100)
            500 -> return BetStrategy(500, 0, 500, AreaType.BET_500)
            2500 -> return BetStrategy(2500, 0, 2500, AreaType.BET_2500)
            
            // Ставка 20 = 10 + x2
            20 -> return BetStrategy(10, 1, 20, AreaType.BET_10)
            
            else -> {
                // Для остальных сумм ищем оптимальную комбинацию
                return findOptimalStrategy(targetAmount)
            }
        }
    }
    
    /**
     * Ищет оптимальную стратегию для произвольной суммы
     */
    private fun findOptimalStrategy(targetAmount: Int): BetStrategy {
        // Пробуем каждую кнопку и ищем подходящее количество удвоений
        for (buttonAmount in AVAILABLE_BUTTONS) {
            val strategy = calculateForButton(buttonAmount, targetAmount)
            if (strategy.isValid() && strategy.finalAmount == targetAmount) {
                return strategy
            }
        }
        
        // Если точное совпадение не найдено, ищем ближайшую меньшую сумму
        for (buttonAmount in AVAILABLE_BUTTONS) {
            val strategy = findClosestStrategy(buttonAmount, targetAmount)
            if (strategy.isValid()) {
                return strategy
            }
        }
        
        // В крайнем случае возвращаем максимально возможную ставку
        return BetStrategy(2500, 4, 40000.coerceAtMost(MAX_BET), AreaType.BET_2500)
    }
    
    /**
     * Рассчитывает стратегию для конкретной кнопки
     */
    private fun calculateForButton(buttonAmount: Int, targetAmount: Int): BetStrategy {
        var current = buttonAmount
        var doublings = 0
        
        // Специальный случай для ставки 20 (10 + x2)
        if (buttonAmount == 10 && targetAmount >= 20) {
            current = 20 // 10 + x2
            doublings = 1
            
            // Продолжаем удваивать пока не достигнем цели
            while (current < targetAmount && current <= MAX_BET / 2) {
                current *= 2
                doublings++
            }
        } else {
            // Обычные удвоения
            while (current < targetAmount && current <= MAX_BET / 2) {
                current *= 2
                doublings++
            }
        }
        
        val areaType = getAreaTypeForButton(buttonAmount)
        return BetStrategy(buttonAmount, doublings, current, areaType)
    }
    
    /**
     * Ищет ближайшую стратегию, не превышающую целевую сумму
     */
    private fun findClosestStrategy(buttonAmount: Int, targetAmount: Int): BetStrategy {
        var current = buttonAmount
        var doublings = 0
        var lastValid = BetStrategy(buttonAmount, 0, buttonAmount, getAreaTypeForButton(buttonAmount))
        
        // Специальный случай для ставки 20 (10 + x2)
        if (buttonAmount == 10) {
            current = 20 // 10 + x2
            doublings = 1
            if (current <= targetAmount) {
                lastValid = BetStrategy(buttonAmount, doublings, current, getAreaTypeForButton(buttonAmount))
            }
        }
        
        // Удваиваем пока не превысим цель или максимум
        while (current <= MAX_BET / 2) {
            val next = current * 2
            if (next <= targetAmount && next <= MAX_BET) {
                current = next
                doublings++
                lastValid = BetStrategy(buttonAmount, doublings, current, getAreaTypeForButton(buttonAmount))
            } else {
                break
            }
        }
        
        return lastValid
    }
    
    /**
     * Возвращает тип области для кнопки ставки
     */
    private fun getAreaTypeForButton(buttonAmount: Int): AreaType {
        return when (buttonAmount) {
            10 -> AreaType.BET_10
            50 -> AreaType.BET_50
            100 -> AreaType.BET_100
            500 -> AreaType.BET_500
            2500 -> AreaType.BET_2500
            else -> AreaType.BET_10 // По умолчанию
        }
    }
    
    /**
     * Генерирует полную последовательность удвоений для базовой ставки
     */
    fun generateDoublingSequence(baseBet: Int): List<Int> {
        val sequence = mutableListOf<Int>()
        var current = baseBet
        
        // Добавляем базовую ставку
        sequence.add(current)
        
        // Удваиваем до максимума
        while (current <= MAX_BET / 2) {
            current *= 2
            if (current <= MAX_BET) {
                sequence.add(current)
            }
        }
        
        return sequence
    }
    
    /**
     * Проверяет, является ли сумма валидной базовой ставкой
     */
    fun isValidBaseBet(amount: Int): Boolean {
        return amount in listOf(10, 20, 50, 100, 500, 2500)
    }
    
    /**
     * Возвращает следующую ставку при удвоении
     */
    fun getNextBet(currentBet: Int): Int {
        val doubled = currentBet * 2
        return doubled.coerceAtMost(MAX_BET)
    }
    
    /**
     * Возвращает максимально возможную ставку
     */
    fun getMaxBet(): Int = MAX_BET
    
    /**
     * Логирует детали стратегии для отладки
     */
    fun logStrategy(strategy: BetStrategy, tag: String = "BetCalculator") {
        android.util.Log.d(tag, "🎰 Стратегия ставки:")
        android.util.Log.d(tag, "   Кнопка: ${strategy.buttonAmount}")
        android.util.Log.d(tag, "   Удвоений: ${strategy.doublingClicks}")
        android.util.Log.d(tag, "   Итоговая сумма: ${strategy.finalAmount}")
        android.util.Log.d(tag, "   Область: ${strategy.areaType}")
        android.util.Log.d(tag, "   Последовательность: ${strategy.getDoublingSequence()}")
    }
}
