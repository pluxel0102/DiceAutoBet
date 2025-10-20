package com.example.diceautobet.models

import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.opencv.DotCounter

// Модель для хранения области на экране
data class ScreenArea(
    val rect: Rect,
    val adaptive: com.example.diceautobet.utils.CoordinateUtils.AdaptiveRect? = null,
    val name: String
) {
    // Конструктор для совместимости со старым кодом (без адаптивных координат)
    constructor(name: String, rect: Rect) : this(
        rect = rect,
        adaptive = null,
        name = name
    )

    // Конструктор для создания с абсолютными координатами
    constructor(name: String, left: Int, top: Int, right: Int, bottom: Int) :
            this(name, Rect(left, top, right, bottom))
}

// Перечисление для типов областей
enum class AreaType(val displayName: String) {
    DICE_AREA("Область кубиков"),
    RED_BUTTON("Красный кубик"),
    DRAW_BUTTON("Ничья X"),
    ORANGE_BUTTON("Оранжевый кубик"),
    BET_10("Ставка 10"),
    BET_50("Ставка 50"),
    BET_100("Ставка 100"),
    BET_500("Ставка 500"),
    BET_2500("Ставка 2500"),
    CONFIRM_BET("Заключить пари"),
    DOUBLE_BUTTON("Удвоить x2"),
    BET_RESULT("Результат ставки")
}

// Выбор на что ставить
enum class BetChoice {
    RED, ORANGE
}

// Тип хода в новой альтернирующей стратегии
enum class TurnType {
    ACTIVE,   // Активный ход - делаем ставку
    PASSIVE   // Пассивный ход - только наблюдаем
}

// === НОВЫЕ МОДЕЛИ ДЛЯ ДВОЙНОГО РЕЖИМА ===

// Тип окна в двойном режиме
enum class WindowType {
    LEFT,    // Левое окно (горизонтальное разделение)
    RIGHT,   // Правое окно (горизонтальное разделение)
    TOP,     // Верхнее окно (вертикальное разделение)
    BOTTOM   // Нижнее окно (вертикальное разделение)
}

// Режим работы приложения
enum class GameMode {
    SINGLE_WINDOW,  // Обычный режим (одно окно)
    DUAL_WINDOW     // Двойной режим (два окна)
}

// Тип разделения экрана
enum class SplitScreenType {
    HORIZONTAL,  // Горизонтальное разделение (левое/правое)
    VERTICAL     // Вертикальное разделение (верхнее/нижнее)
}

// Стратегия двойного режима
enum class DualWindowStrategy {
    WIN_SWITCH,        // При выигрыше переходим в другое окно с базовой ставкой
    LOSS_DOUBLE,       // При проигрыше удваиваем в другом окне
    COLOR_ALTERNATING  // После 2 проигрышей переходим на другой цвет
}

// Результат раунда
data class RoundResult(
    val redDots: Int,
    val orangeDots: Int,
    val winner: BetChoice?,
    val isDraw: Boolean = false,
    val confidence: Float = 0.0f,
    val isValid: Boolean = true
) {
    companion object {
        fun fromDotResult(result: DotCounter.Result): RoundResult {
            val isDraw = result.leftDots == result.rightDots
            val winner = when {
                isDraw -> null
                result.leftDots > result.rightDots -> BetChoice.RED
                else -> BetChoice.ORANGE
            }

            // Валидация результата
            val isValid = validateResult(result)

            val roundResult = RoundResult(
                redDots = result.leftDots,
                orangeDots = result.rightDots,
                winner = winner,
                isDraw = isDraw,
                confidence = result.confidence,
                isValid = isValid
            )
            Log.d("RoundResult", "Создаем результат: leftDots=${result.leftDots}, rightDots=${result.rightDots}, isDraw=$isDraw, winner=$winner, confidence=${result.confidence}, isValid=$isValid")
            return roundResult
        }

        private fun validateResult(result: DotCounter.Result): Boolean {
            // Проверяем, что результат логичен
            val totalDots = result.leftDots + result.rightDots

            // ИСПРАВЛЕНИЕ: Каждый кубик должен показывать от 1 до 6 точек
            // Результаты типа 0:X или X:0 - это анимация загрузки, не реальные кубики
            if (result.leftDots !in 1..6 || result.rightDots !in 1..6) {
                Log.d("RoundResult", "Невалидное распределение точек: ${result.leftDots}:${result.rightDots} (кубики должны показывать 1-6 точек)")
                return false
            }

            // Проверяем уверенность - для расширенных областей используем низкий порог
            if (result.confidence < 0.05f) { // Ещё более лояльный: 0.1f → 0.05f
                Log.d("RoundResult", "Низкая уверенность (${result.confidence}) - результат ненадежен")
                return false
            }

            Log.d("RoundResult", "✅ Результат валиден: totalDots=$totalDots, confidence=${result.confidence}")
            return true
        }
    }
}

// Состояние игры
data class GameState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentBet: Int = 10,
    val baseBet: Int = 10,
    val betChoice: BetChoice = BetChoice.RED,
    val consecutiveLosses: Int = 0,
    val maxAttempts: Int = 10,
    val roundHistory: List<RoundResult> = emptyList(),
    val totalAttempts: Int = 0,  // Общее количество попыток
    val isBetPlaced: Boolean = false, // Флаг: ставка уже сделана и ожидает результата
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val lastResult: GameResultType = GameResultType.UNKNOWN,
    val statistics: GameStatistics = GameStatistics(),
    val balance: Int = 10000, // Баланс пользователя

    // НОВАЯ АЛЬТЕРНИРУЮЩАЯ ЛОГИКА
    val currentTurnNumber: Int = 0,  // Номер текущего хода (0, 1, 2, 3...)
    val lastActiveResult: GameResultType = GameResultType.UNKNOWN,  // Результат последнего активного хода
    val firstResultIgnored: Boolean = false,  // Флаг: первый результат уже проигнорирован
    val totalProfit: Int = 0,
    val totalBetsPlaced: Int = 0
) {

    // МЕТОДЫ ДЛЯ НОВОЙ АЛЬТЕРНИРУЮЩЕЙ ЛОГИКИ

    /**
     * Определяет тип текущего хода (активный или пассивный)
     */
    fun getCurrentTurnType(): TurnType {
        return if (currentTurnNumber % 2 == 0) TurnType.ACTIVE else TurnType.PASSIVE
    }

    /**
     * Проверяет, нужно ли игнорировать первый результат
     */
    fun shouldIgnoreFirstResult(): Boolean {
        return !firstResultIgnored
    }

    fun calculateBetAmount(): Int {
        Log.d("GameState", "🎯 РАСЧЕТ СТАВКИ ДЛЯ АКТИВНОГО ХОДА")
        Log.d("GameState", "Текущий ход: ${currentTurnNumber + 1}")
        Log.d("GameState", "Последний активный результат: $lastActiveResult")
        Log.d("GameState", "Текущая ставка: $currentBet")
        Log.d("GameState", "Базовая ставка: $baseBet")

        val betAmount = when (lastActiveResult) {
            GameResultType.WIN -> {
                Log.d("GameState", "✅ Последний активный ход ВЫИГРАЛИ → ставим базовую ставку: $baseBet")
                baseBet
            }
            GameResultType.LOSS, GameResultType.DRAW -> {
                // ИСПРАВЛЕНИЕ: не удваиваем, а используем текущую ставку
                // которая уже была рассчитана в updateBalanceAfterActiveTurn
                Log.d("GameState", "❌ Последний активный ход ПРОИГРАЛИ/НИЧЬЯ → используем текущую ставку: $currentBet")
                currentBet
            }
            GameResultType.UNKNOWN -> {
                Log.d("GameState", "🎬 Первый активный ход → ставим базовую ставку: $baseBet")
                baseBet
            }
        }

        Log.d("GameState", "💰 ИТОГОВАЯ СТАВКА: $betAmount")
        return betAmount
    }

    /**
     * Обновляет состояние после завершения хода
     */
    fun advanceToNextTurn(result: GameResultType = GameResultType.UNKNOWN): GameState {
        val currentType = getCurrentTurnType()

        return when (currentType) {
            TurnType.ACTIVE -> {
                // Активный ход завершен - запоминаем результат
                Log.d("GameState", "Завершен активный ход ${currentTurnNumber + 1}, результат: $result")
                this.copy(
                    currentTurnNumber = currentTurnNumber + 1,
                    lastActiveResult = result,
                    lastResult = result
                )
            }
            TurnType.PASSIVE -> {
                // Пассивный ход завершен - просто переходим к следующему
                Log.d("GameState", "Завершен пассивный ход ${currentTurnNumber + 1}")
                this.copy(
                    currentTurnNumber = currentTurnNumber + 1,
                    lastResult = result
                )
            }
        }
    }

    /**
     * Помечает первый результат как проигнорированный
     */
    fun markFirstResultIgnored(): GameState {
        return this.copy(
            firstResultIgnored = true,
            currentTurnNumber = 0  // Сбрасываем счетчик после игнорирования
        )
    }

    /**
     * Обновляет баланс после активного хода
     */
    fun updateBalanceAfterActiveTurn(betAmount: Int, result: GameResultType): GameState {
        // Рассчитываем выигрыш с коэффициентом (как в OverlayService)
        val winAmount = (betAmount * 2.28).toInt() - betAmount

        val newBalance = when (result) {
            GameResultType.WIN -> {
                Log.d("GameState", "🎉 ВЫИГРЫШ! Ставка: $betAmount, выигрыш: $winAmount")
                balance + winAmount  // Добавляем чистый выигрыш (без ставки)
            }
            GameResultType.LOSS -> {
                Log.d("GameState", "💸 ПРОИГРЫШ! -$betAmount")
                balance - betAmount
            }
            GameResultType.DRAW -> {
                Log.d("GameState", "🤝 НИЧЬЯ (ставка возвращается)")
                balance // При ничьей ставка возвращается
            }
            GameResultType.UNKNOWN -> {
                Log.w("GameState", "❓ Неизвестный результат")
                balance
            }
        }

        val newProfit = when (result) {
            GameResultType.WIN -> totalProfit + winAmount  // Чистый выигрыш
            GameResultType.LOSS -> totalProfit - betAmount
            GameResultType.DRAW -> totalProfit
            GameResultType.UNKNOWN -> totalProfit
        }

        // ✅ ГЛАВНОЕ ИСПРАВЛЕНИЕ: правильно рассчитываем ставку для СЛЕДУЮЩЕГО хода
        val nextBet = when (result) {
            GameResultType.WIN -> {
                Log.d("GameState", "Следующая ставка после выигрыша: базовая $baseBet")
                baseBet  // При выигрыше возвращаемся к базовой ставке
            }
            GameResultType.LOSS, GameResultType.DRAW -> {
                val doubled = betAmount * 2
                val maxBet = 2500
                val finalBet = doubled.coerceAtMost(maxBet)
                Log.d("GameState", "Следующая ставка после проигрыша/ничьей: $betAmount * 2 = $doubled → $finalBet")
                finalBet  // При проигрыше/ничьей удваиваем для следующего хода
            }
            GameResultType.UNKNOWN -> currentBet
        }

        val newBetsPlaced = if (result != GameResultType.UNKNOWN) totalBetsPlaced + 1 else totalBetsPlaced

        return this.copy(
            balance = newBalance,
            currentBet = nextBet,  // ✅ Ставка для СЛЕДУЮЩЕГО хода
            totalProfit = newProfit,
            totalBetsPlaced = newBetsPlaced
        )
    }

    /**
     * Получает описание текущего состояния для UI
     */
    fun getStatusDescription(): String {
        val turnType = getCurrentTurnType()
        val turnDescription = when (turnType) {
            TurnType.ACTIVE -> "Активный ход"
            TurnType.PASSIVE -> "Пассивный ход"
        }

        val lastResultDescription = when (lastActiveResult) {
            GameResultType.WIN -> "Последний активный: ВЫИГРЫШ"
            GameResultType.LOSS -> "Последний активный: ПРОИГРЫШ"
            GameResultType.DRAW -> "Последний активный: НИЧЬЯ"
            GameResultType.UNKNOWN -> "Первая игра"
        }

        return "$turnDescription (${currentTurnNumber + 1}). $lastResultDescription"
    }

    /**
     * Проверяет условия остановки
     */
    fun shouldStop(): Boolean {
    // Останавливаемся, если количество подряд проигрышей достигло лимита попыток
    return consecutiveLosses >= maxAttempts
    }

    // УСТАРЕВШИЕ МЕТОДЫ (оставляем для совместимости)
    val currentAttempt: Int get() = consecutiveLosses + 1

    fun getNextBet(): Int {
    // Логика для обратной совместимости с тестами: удвоение текущей ставки с ограничением
    // Если проигрышей нет — возвращаем базовую ставку, иначе удваиваем текущую (до 2500)
    return if (consecutiveLosses <= 0) baseBet else (currentBet * 2).coerceAtMost(2500)
    }
}

// Общий результат операций
sealed class GameResult<out T> {
    data class Success<T>(val data: T) : GameResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : GameResult<Nothing>()
    object Loading : GameResult<Nothing>()
}

// Результат ставки
enum class GameResultType {
    WIN, LOSS, DRAW, UNKNOWN
}

// Результат расчета ставки
sealed class BetCalculationResult {
    data class Success(val amount: Int) : BetCalculationResult()
    data class InsufficientBalance(val required: Int, val available: Int) : BetCalculationResult()
    data class MaxStepsReached(val maxSteps: Int) : BetCalculationResult()
    object NoAvailableBet : BetCalculationResult()
    data class Error(val message: String) : BetCalculationResult()
}

// Статистика игры
data class GameStatistics(
    val totalBets: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val totalProfit: Int = 0,
    val currentWinStreak: Int = 0,
    val currentLossStreak: Int = 0,
    val maxWinStreak: Int = 0,
    val maxLossStreak: Int = 0,
    val averageBet: Double = 0.0,
    val winRate: Double = 0.0
) {
    // Вычисляемое свойство для winRate
    val calculatedWinRate: Double
        get() = if (totalBets > 0) wins.toDouble() / totalBets.toDouble() else 0.0
}

// Интерфейс для наблюдения за состоянием игры
interface GameStateObserver {
    fun onGameStateChanged(gameState: GameState)
}