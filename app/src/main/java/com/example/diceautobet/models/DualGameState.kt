package com.example.diceautobet.models

/**
 * Состояние игры в двойном режиме
 */
data class DualGameState(
    val mode: GameMode = GameMode.SINGLE_WINDOW,
    val isRunning: Boolean = false,
    val strategy: DualStrategy = DualStrategy.WIN_SWITCH,
    val currentColor: BetChoice = BetChoice.RED,
    val currentActiveWindow: WindowType = WindowType.LEFT,
    val leftWindow: WindowState? = null,
    val rightWindow: WindowState? = null,
    val lastBetWindow: WindowType? = null,
    val waitingForResult: Boolean = false,
    val totalBetsPlaced: Int = 0,
    val totalProfit: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val sessionStartTime: Long = 0L
) {
    /**
     * Проверяет готовность для двойного режима
     * Проверяет наличие состояний окон и их областей
     */
    fun isReadyForDualMode(): Boolean {
        // Базовая проверка наличия окон
        val hasWindows = leftWindow != null && rightWindow != null
        
        // Проверка наличия областей в окнах
        val hasAreas = leftWindow?.areas?.isNotEmpty() == true && 
                      rightWindow?.areas?.isNotEmpty() == true
        
        return hasWindows && hasAreas
    }
    
    /**
     * Переключает активное окно
     */
    fun switchActiveWindow(): DualGameState {
        val newActiveWindow = when (currentActiveWindow) {
            WindowType.LEFT -> WindowType.RIGHT
            WindowType.RIGHT -> WindowType.LEFT
            WindowType.TOP -> WindowType.BOTTOM
            WindowType.BOTTOM -> WindowType.TOP
        }
        
        return copy(
            currentActiveWindow = newActiveWindow,
            leftWindow = leftWindow?.copy(isActive = newActiveWindow == WindowType.LEFT),
            rightWindow = rightWindow?.copy(isActive = newActiveWindow == WindowType.RIGHT)
        )
    }
    
    /**
     * Получает активное окно
     */
    fun getActiveWindow(): WindowState? {
        return when (currentActiveWindow) {
            WindowType.LEFT -> leftWindow
            WindowType.RIGHT -> rightWindow
            WindowType.TOP -> leftWindow    // Используем левое окно для верхнего
            WindowType.BOTTOM -> rightWindow // Используем правое окно для нижнего
        }
    }
    
    /**
     * Получает неактивное окно
     */
    fun getInactiveWindow(): WindowState? {
        return when (currentActiveWindow) {
            WindowType.LEFT -> rightWindow
            WindowType.RIGHT -> leftWindow
            WindowType.TOP -> rightWindow   // Неактивное - правое (нижнее)
            WindowType.BOTTOM -> leftWindow // Неактивное - левое (верхнее)
        }
    }
}
