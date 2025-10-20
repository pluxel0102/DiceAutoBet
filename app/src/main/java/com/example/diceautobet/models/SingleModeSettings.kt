package com.example.diceautobet.models

import com.example.diceautobet.models.ValidationResult

/**
 * Настройки одиночного режима игры
 */
data class SingleModeSettings(
    // Основные настройки ставки
    val baseBet: Int = 20,                      // Начальная ставка (20 через 10 + x2)
    val preferredColor: BetColor = BetColor.BLUE, // Предпочитаемый цвет для старта
    val maxBet: Int = 200000,                   // Максимальная ставка (остановка при достижении)
    
    // Настройки стратегии
    val maxLossesBeforeColorSwitch: Int = 2,    // Количество проигрышей до смены цвета
    val enableColorSwitching: Boolean = true,   // Включить автоматическую смену цвета
    
    // Настройки безопасности
    val enableMaxBetLimit: Boolean = true,      // Включить ограничение максимальной ставки
    val enableProfitStop: Boolean = false,      // Останавливать при достижении целевой прибыли
    val targetProfit: Int = 1000,               // Целевая прибыль для остановки
    
    // Настройки производительности
    val detectionDelay: Long = 1000L,           // Задержка между детекциями (мс)
    val clickDelay: Long = 500L,                // Задержка между кликами (мс)
    val analysisTimeout: Long = 10000L,         // Таймаут анализа Gemini (мс)
    
    // Настройки отладки
    val enableDetailedLogging: Boolean = false, // Включить подробное логирование
    val saveDebugScreenshots: Boolean = false,  // Сохранять отладочные скриншоты
    val enableTestMode: Boolean = false         // Включить тестовый режим (без реальных кликов)
) {
    /**
     * Валидация настроек
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Проверка базовой ставки
        if (baseBet !in SingleModeAreaType.getAvailableBetAmounts()) {
            errors.add("Начальная ставка должна быть одной из: ${SingleModeAreaType.getAvailableBetAmounts()}")
        }
        
        // Проверка максимальной ставки
        if (maxBet < baseBet) {
            errors.add("Максимальная ставка не может быть меньше начальной")
        }
        
        if (maxBet > 200000) {
            errors.add("Максимальная ставка не может превышать 200,000")
        }
        
        // Проверка настроек стратегии
        if (maxLossesBeforeColorSwitch < 1 || maxLossesBeforeColorSwitch > 10) {
            errors.add("Количество проигрышей до смены цвета должно быть от 1 до 10")
        }
        
        // Проверка целевой прибыли
        if (enableProfitStop && targetProfit <= 0) {
            errors.add("Целевая прибыль должна быть положительной")
        }
        
        // Проверка задержек
        if (detectionDelay < 100L || detectionDelay > 10000L) {
            errors.add("Задержка детекции должна быть от 100 до 10,000 мс")
        }
        
        if (clickDelay < 100L || clickDelay > 5000L) {
            errors.add("Задержка между кликами должна быть от 100 до 5,000 мс")
        }
        
        if (analysisTimeout < 5000L || analysisTimeout > 30000L) {
            errors.add("Таймаут анализа должен быть от 5,000 до 30,000 мс")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult(true, "Настройки корректны")
        } else {
            ValidationResult(false, "Ошибки в настройках: ${errors.joinToString("; ")}")
        }
    }
    
    /**
     * Получить описание стратегии
     */
    fun getStrategyDescription(): String {
        val colorStrategy = if (enableColorSwitching) {
            "со сменой цвета после $maxLossesBeforeColorSwitch проигрышей"
        } else {
            "без смены цвета"
        }
        
        val stopCondition = when {
            enableMaxBetLimit && enableProfitStop -> 
                "до $maxBet или прибыли $targetProfit"
            enableMaxBetLimit -> "до $maxBet"
            enableProfitStop -> "до прибыли $targetProfit"
            else -> "без ограничений"
        }
        
        return "Мартингейл с базовой ставкой $baseBet, $colorStrategy, $stopCondition"
    }
    
    /**
     * Создать копию с обновленной базовой ставкой
     */
    fun withBaseBet(newBaseBet: Int): SingleModeSettings {
        return copy(baseBet = newBaseBet)
    }
    
    /**
     * Создать копию с обновленным предпочитаемым цветом
     */
    fun withPreferredColor(newColor: BetColor): SingleModeSettings {
        return copy(preferredColor = newColor)
    }
    
    /**
     * Создать копию с обновленной максимальной ставкой
     */
    fun withMaxBet(newMaxBet: Int): SingleModeSettings {
        return copy(maxBet = newMaxBet)
    }
    
    companion object {
        /**
         * Настройки по умолчанию для консервативной игры
         */
        fun conservative(): SingleModeSettings {
            return SingleModeSettings(
                baseBet = 50,
                maxBet = 10000,
                maxLossesBeforeColorSwitch = 1,
                detectionDelay = 2000L,
                clickDelay = 1000L
            )
        }
        
        /**
         * Настройки по умолчанию для агрессивной игры
         */
        fun aggressive(): SingleModeSettings {
            return SingleModeSettings(
                baseBet = 500,
                maxBet = 200000,
                maxLossesBeforeColorSwitch = 3,
                detectionDelay = 500L,
                clickDelay = 300L
            )
        }
        
        /**
         * Настройки для тестирования
         */
        fun testing(): SingleModeSettings {
            return SingleModeSettings(
                baseBet = 10,
                maxBet = 1000,
                enableTestMode = true,
                enableDetailedLogging = true,
                saveDebugScreenshots = true
            )
        }
    }
}