package com.example.diceautobet.models

/**
 * Стратегии для двойного режима
 */
enum class DualStrategy {
    WIN_SWITCH,        // При выигрыше переходим в другое окно с базовой ставкой
    LOSS_DOUBLE,       // При проигрыше удваиваем в другом окне
    COLOR_ALTERNATING  // После 2 проигрышей переходим на другой цвет
}

/**
 * Настройки двойного режима
 */
data class DualModeSettings(
    val enabled: Boolean = false,
    val strategy: DualStrategy = DualStrategy.WIN_SWITCH,
    val splitScreenType: SplitScreenType = SplitScreenType.HORIZONTAL,  // Тип разделения экрана
    val baseBet: Int = 20,  // Базовая ставка (10, 20, 50, 100, 500, 2500)
    val maxBet: Int = 30000,  // Максимальная ставка (увеличено до 30.000)
    val autoSwitchWindows: Boolean = true,
    val delayBetweenActions: Long = 1000L,
    val maxConsecutiveLosses: Int = 3,
    val autoColorChange: Boolean = true,
    val enableTimingOptimization: Boolean = true,
    val smartSynchronization: Boolean = true
)

/**
 * Состояние окна в двойном режиме
 */
data class WindowState(
    val windowType: WindowType,
    val windowBounds: android.graphics.Rect,
    val areas: Map<AreaType, ScreenArea> = emptyMap(),
    val gameState: GameState = GameState(),
    val isActive: Boolean = false,
    val lastResult: RoundResult? = null,
    val currentBet: Int = 0,
    val consecutiveLosses: Int = 0,
    val totalProfit: Double = 0.0
)

/**
 * Общее состояние игры
 */
data class DualModeGameState(
    val isGameRunning: Boolean = false,
    val currentBetChoice: BetChoice = BetChoice.RED,
    val currentBetAmount: Int = 10,
    val lastResult: RoundResult? = null,
    val winStreak: Int = 0,
    val lossStreak: Int = 0,
    val totalBets: Int = 0,
    val totalWins: Int = 0,
    val totalLosses: Int = 0
)

/**
 * Упрощенное состояние двойного режима для новой стратегии
 */
data class SimpleDualModeState(
    val isRunning: Boolean = false,
    val currentWindow: WindowType = WindowType.LEFT,
    val currentColor: BetChoice = BetChoice.RED,
    val previousColor: BetChoice? = null, // Предыдущий цвет для циклического переключения
    val currentBet: Int = 10,
    val consecutiveLosses: Int = 0,
    val consecutiveLossesOnCurrentColor: Int = 0,
    val totalBets: Int = 0,
    val totalProfit: Int = 0,
    val lastResult: GameResultType = GameResultType.UNKNOWN
) {
    /**
     * Определяет следующее окно для ставки
     */
    fun getNextWindow(): WindowType {
        return when (currentWindow) {
            WindowType.LEFT -> WindowType.RIGHT
            WindowType.RIGHT -> WindowType.LEFT
            else -> WindowType.LEFT // Для совместимости
        }
    }
    
    /**
     * Определяет следующий цвет после 2 проигрышей подряд на текущем цвете
     * ЦИКЛИЧЕСКОЕ ПЕРЕКЛЮЧЕНИЕ: red → orange → red → orange...
     */
    fun getNextColor(): BetChoice {
        return if (previousColor != null && previousColor != currentColor) {
            // Возврат к предыдущему цвету (циклическое переключение)
            previousColor
        } else {
            // Первичное переключение на противоположный цвет
            when (currentColor) {
                BetChoice.RED -> BetChoice.ORANGE
                BetChoice.ORANGE -> BetChoice.RED
            }
        }
    }
    
    /**
     * Вычисляет следующую ставку по правильной стратегии
     * @param result - результат предыдущей игры
     * @param baseBet - базовая ставка из настроек
     */
    fun calculateNextBet(result: GameResultType, baseBet: Int): Int {
        return when (result) {
            GameResultType.WIN -> baseBet // При выигрыше всегда базовая ставка из настроек
            GameResultType.LOSS, GameResultType.DRAW -> {
                val nextBet = currentBet * 2 // При проигрыше удваиваем
                nextBet.coerceAtMost(30000) // Ограничиваем максимумом 30.000
            }
            GameResultType.UNKNOWN -> baseBet // Начальная ставка из настроек
        }
    }
    
    /**
     * Проверяет, нужно ли менять цвет (после 2 проигрышей подряд на текущем цвете)
     */
    fun shouldChangeColor(): Boolean {
        return consecutiveLossesOnCurrentColor >= 2
    }
}
