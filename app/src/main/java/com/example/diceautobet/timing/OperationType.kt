package com.example.diceautobet.timing

/**
 * Типы операций для оптимизации тайминга
 */
enum class OperationType {
    WINDOW_SWITCH,      // Переключение между окнами
    BET_PLACEMENT,      // Размещение ставки
    RESULT_DETECTION,   // Детекция результата
    SCREENSHOT_CAPTURE, // Снятие скриншота
    UI_INTERACTION,     // Взаимодействие с UI
    STRATEGY_EXECUTION, // Выполнение стратегии
    SYSTEM_WAIT,        // Ожидание системы
    ERROR_RECOVERY,     // Восстановление после ошибки
    
    // Дополнительные типы для совместимости
    DETECTION,          // Алиас для RESULT_DETECTION
    REACTION,           // Реакция на результат
    STRATEGY_APPLICATION, // Применение стратегии
    CLICK,              // Клик по элементу
    BET_CONFIRMATION,   // Подтверждение ставки
    SCREENSHOT          // Алиас для SCREENSHOT_CAPTURE
}
