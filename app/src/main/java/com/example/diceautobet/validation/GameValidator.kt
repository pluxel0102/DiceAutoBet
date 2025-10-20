package com.example.diceautobet.validation

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.CoordinateUtils

object GameValidator {
    
    private const val TAG = "GameValidator"
    
    /**
     * Валидирует сумму ставки
     */
    fun validateBetAmount(amount: Int): ValidationResult {
        return when {
            amount <= 0 -> ValidationResult.Error("Сумма ставки должна быть больше 0")
            amount > 2500 -> ValidationResult.Error("Максимальная ставка: 2500")
            !listOf(10, 50, 100, 500, 2500).contains(amount) -> {
                ValidationResult.Error("Недопустимая сумма ставки. Доступны: 10, 50, 100, 500, 2500")
            }
            else -> ValidationResult.Success
        }
    }
    
    /**
     * Валидирует состояние игры
     */
    fun validateGameState(state: GameState): ValidationResult {
        // Проверяем базовую ставку
        val baseBetResult = validateBetAmount(state.baseBet)
        if (baseBetResult !is ValidationResult.Success) {
            return ValidationResult.Error("Базовая ставка: ${(baseBetResult as ValidationResult.Error).message}")
        }
        
        // Проверяем текущую ставку
        val currentBetResult = validateBetAmount(state.currentBet)
        if (currentBetResult !is ValidationResult.Success) {
            return ValidationResult.Error("Текущая ставка: ${(currentBetResult as ValidationResult.Error).message}")
        }
        
        // Проверяем максимальное количество попыток
        if (state.maxAttempts <= 0) {
            return ValidationResult.Error("Максимальное количество попыток должно быть больше 0")
        }
        
        if (state.maxAttempts > 20) {
            return ValidationResult.Error("Максимальное количество попыток не должно превышать 20")
        }
        
        // Проверяем количество проигрышей
        if (state.consecutiveLosses < 0) {
            return ValidationResult.Error("Количество последовательных проигрышей не может быть отрицательным")
        }
        
        // Проверяем логику остановки
        if (state.consecutiveLosses >= state.maxAttempts && state.isRunning) {
            return ValidationResult.Error("Игра должна быть остановлена по достижению лимита проигрышей")
        }
        
        return ValidationResult.Success
    }
    
    /**
     * Валидирует координаты области
     */
    fun validateCoordinates(rect: Rect, context: Context): ValidationResult {
        if (rect.isEmpty) {
            return ValidationResult.Error("Область не может быть пустой")
        }
        
        if (rect.width() < 10 || rect.height() < 10) {
            return ValidationResult.Error("Область слишком мала (минимум 10x10 пикселей)")
        }
        
        if (!CoordinateUtils.validateCoordinates(rect, context)) {
            return ValidationResult.Error("Координаты выходят за пределы экрана")
        }
        
        return ValidationResult.Success
    }
    
    /**
     * Валидирует результат раунда
     */
    fun validateRoundResult(result: RoundResult): ValidationResult {
        // Проверяем количество точек
        if (result.redDots < 0 || result.orangeDots < 0) {
            return ValidationResult.Error("Количество точек не может быть отрицательным")
        }
        
        if (result.redDots > 6 || result.orangeDots > 6) {
            return ValidationResult.Error("На кубике не может быть больше 6 точек")
        }
        
        val totalDots = result.redDots + result.orangeDots
        if (totalDots == 0) {
            return ValidationResult.Error("Общее количество точек не может быть 0")
        }
        
        if (totalDots > 12) {
            return ValidationResult.Error("Общее количество точек не может быть больше 12")
        }
        
        // Проверяем уверенность
        if (result.confidence < 0f || result.confidence > 1f) {
            return ValidationResult.Error("Уверенность должна быть от 0 до 1")
        }
        
        // Проверяем логику победителя
        if (result.isDraw) {
            if (result.winner != null) {
                return ValidationResult.Error("В ничьей не может быть победителя")
            }
            if (result.redDots != result.orangeDots) {
                return ValidationResult.Error("В ничьей количество точек должно совпадать")
            }
        } else {
            if (result.winner == null) {
                return ValidationResult.Error("Должен быть определен победитель")
            }
            if (result.redDots == result.orangeDots) {
                return ValidationResult.Error("При равном количестве точек должна быть ничья")
            }
        }
        
        return ValidationResult.Success
    }
    
    /**
     * Валидирует настройки областей
     */
    fun validateAreaConfiguration(context: Context): ValidationResult {
        val requiredAreas = AreaType.values()
        val missingAreas = mutableListOf<AreaType>()
        
        // Здесь нужно получить PreferencesManager, но для примера пропустим
        // В реальном коде нужно передавать PreferencesManager как параметр
        
        return if (missingAreas.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Error("Отсутствуют области: ${missingAreas.joinToString(", ") { it.displayName }}")
        }
    }
    
    /**
     * Валидирует таймеры и задержки
     */
    fun validateTimings(clickDelay: Long, checkDelay: Long): ValidationResult {
        if (clickDelay < 100) {
            return ValidationResult.Error("Задержка клика не может быть меньше 100мс")
        }
        
        if (clickDelay > 10000) {
            return ValidationResult.Error("Задержка клика не может быть больше 10 секунд")
        }
        
        if (checkDelay < 1000) {
            return ValidationResult.Error("Задержка проверки не может быть меньше 1 секунды")
        }
        
        if (checkDelay > 30000) {
            return ValidationResult.Error("Задержка проверки не может быть больше 30 секунд")
        }
        
        return ValidationResult.Success
    }
    
    /**
     * Валидирует выбор цвета ставки
     */
    fun validateBetChoice(choice: BetChoice): ValidationResult {
        return ValidationResult.Success // BetChoice - это enum, всегда валиден
    }
    
    /**
     * Валидирует историю результатов на аномалии
     */
    fun validateResultHistory(history: List<RoundResult>): ValidationResult {
        if (history.isEmpty()) {
            return ValidationResult.Success
        }
        
        // Проверяем на слишком много одинаковых результатов подряд
        var consecutiveCount = 1
        var maxConsecutive = 1
        
        for (i in 1 until history.size) {
            val prev = history[i-1]
            val curr = history[i]
            
            if (prev.redDots == curr.redDots && 
                prev.orangeDots == curr.orangeDots &&
                prev.winner == curr.winner) {
                consecutiveCount++
                maxConsecutive = maxOf(maxConsecutive, consecutiveCount)
            } else {
                consecutiveCount = 1
            }
        }
        
        if (maxConsecutive > 8) {
            return ValidationResult.Error("Слишком много одинаковых результатов подряд ($maxConsecutive)")
        }
        
        // Проверяем среднюю уверенность
        val avgConfidence = history.sumOf { it.confidence.toDouble() } / history.size
        if (avgConfidence < 0.3) {
            return ValidationResult.Error("Слишком низкая средняя уверенность в результатах: $avgConfidence")
        }
        
        return ValidationResult.Success
    }
    
    /**
     * Комплексная валидация перед запуском игры
     */
    fun validateGameStart(
        gameState: GameState,
        context: Context,
        clickDelay: Long,
        checkDelay: Long
    ): ValidationResult {
        
        // Валидируем состояние игры
        val stateResult = validateGameState(gameState)
        if (stateResult !is ValidationResult.Success) {
            return stateResult
        }
        
        // Валидируем таймеры
        val timingResult = validateTimings(clickDelay, checkDelay)
        if (timingResult !is ValidationResult.Success) {
            return timingResult
        }
        
        // Валидируем выбор ставки
        val betChoiceResult = validateBetChoice(gameState.betChoice)
        if (betChoiceResult !is ValidationResult.Success) {
            return betChoiceResult
        }
        
        return ValidationResult.Success
    }
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
    
    val isValid: Boolean
        get() = this is Success
}
