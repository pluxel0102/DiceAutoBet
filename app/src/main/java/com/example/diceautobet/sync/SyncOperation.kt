package com.example.diceautobet.sync

import com.example.diceautobet.models.WindowType
import com.example.diceautobet.timing.OperationType

/**
 * Операция синхронизации для двойного режима
 */
data class SyncOperation(
    val type: SyncOperationType,
    val windowType: WindowType,
    val priority: Int = 0,
    val estimatedDuration: Long = 1000L,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun canRetry(): Boolean = retryCount < maxRetries
    
    fun withRetry(): SyncOperation = copy(retryCount = retryCount + 1)
}

/**
 * Типы операций синхронизации
 */
enum class SyncOperationType {
    WINDOW_ACCESS,      // Доступ к окну
    BET_PLACEMENT,      // Размещение ставки
    RESULT_READING,     // Чтение результата
    SCREENSHOT_TAKING,  // Снятие скриншота
    UI_INTERACTION,     // Взаимодействие с UI
    SYSTEM_OPERATION;   // Системная операция
    
    /**
     * Конвертирует в OperationType для timingOptimizer
     */
    fun toOperationType(): OperationType {
        return when (this) {
            WINDOW_ACCESS -> OperationType.WINDOW_SWITCH
            BET_PLACEMENT -> OperationType.BET_PLACEMENT
            RESULT_READING -> OperationType.RESULT_DETECTION
            SCREENSHOT_TAKING -> OperationType.SCREENSHOT_CAPTURE
            UI_INTERACTION -> OperationType.UI_INTERACTION
            SYSTEM_OPERATION -> OperationType.SYSTEM_WAIT
        }
    }
}
