package com.example.diceautobet.timing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

/**
 * Оптимизатор тайминга для двойного режима
 * Адаптивно настраивает задержки для оптимальной производительности
 */
class DualModeTimingOptimizer(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "DualModeTimingOptimizer"
        
        // Базовые тайминги (мс)
        const val BASE_DETECTION_INTERVAL = 500L
        const val BASE_REACTION_DELAY = 100L
        const val BASE_STRATEGY_DELAY = 300L
        const val BASE_CLICK_DELAY = 200L
        const val BASE_CONFIRMATION_DELAY = 500L
        
        // Адаптивные лимиты
        const val MIN_DETECTION_INTERVAL = 200L
        const val MAX_DETECTION_INTERVAL = 1000L
        const val MIN_REACTION_DELAY = 50L
        const val MAX_REACTION_DELAY = 500L
        
        // Производительность
        const val PERFORMANCE_HISTORY_SIZE = 20
        const val SLOW_OPERATION_THRESHOLD = 2000L
        const val FAST_OPERATION_THRESHOLD = 800L
    }
    
    private val preferencesManager = PreferencesManager(context)
    
    // Текущие оптимизированные тайминги
    private var optimizedTimings = OptimizedTimings()
    
    // История производительности
    private val performanceHistory = mutableListOf<PerformanceMetric>()
    private var lastOptimizationTime = 0L
    
    // Статистика
    private var totalOperations = 0
    private var slowOperations = 0
    private var averageReactionTime = 0L
    
    /**
     * Получает оптимизированные тайминги
     */
    fun getOptimizedTimings(): OptimizedTimings = optimizedTimings
    
    /**
     * Инициализирует оптимизатор
     */
    fun initialize() {
        Log.d(TAG, "Инициализация оптимизатора тайминга")
        
        // Загружаем базовые настройки
        loadBaseTimings()
        
        // Применяем адаптивную оптимизацию
        applyDeviceOptimizations()
        
        Log.d(TAG, "Оптимизатор инициализирован: $optimizedTimings")
    }
    
    /**
     * Записывает метрику производительности операции
     */
    fun recordOperationMetric(operation: OperationType, duration: Long, success: Boolean) {
        val metric = PerformanceMetric(
            operation = operation,
            duration = duration,
            success = success,
            timestamp = System.currentTimeMillis()
        )
        
        // Добавляем в историю
        performanceHistory.add(metric)
        if (performanceHistory.size > PERFORMANCE_HISTORY_SIZE) {
            performanceHistory.removeFirst()
        }
        
        // Обновляем статистику
        updateStatistics(metric)
        
        // Проверяем необходимость оптимизации
        checkForOptimization()
        
        Log.v(TAG, "Записана метрика: $operation = ${duration}мс (успех: $success)")
    }
    
    /**
     * Адаптирует тайминги на основе производительности
     */
    fun adaptTimings() {
        if (performanceHistory.size < 5) {
            Log.d(TAG, "Недостаточно данных для адаптации")
            return
        }
        
        Log.d(TAG, "Адаптация таймингов на основе производительности")
        
        val oldTimings = optimizedTimings.copy()
        
        // Адаптируем интервал детекции
        adaptDetectionInterval()
        
        // Адаптируем задержки реакции
        adaptReactionDelays()
        
        // Адаптируем задержки между кликами
        adaptClickDelays()
        
        // Сохраняем новые тайминги
        saveOptimizedTimings()
        
        Log.d(TAG, "Тайминги адаптированы:")
        Log.d(TAG, "  Было: $oldTimings")
        Log.d(TAG, "  Стало: $optimizedTimings")
    }
    
    /**
     * Получает рекомендованную задержку для операции
     */
    fun getDelayForOperation(operation: OperationType): Long {
        return when (operation) {
            OperationType.DETECTION -> optimizedTimings.detectionInterval
            OperationType.REACTION -> optimizedTimings.reactionDelay
            OperationType.STRATEGY_APPLICATION -> optimizedTimings.strategyDelay
            OperationType.CLICK -> optimizedTimings.clickDelay
            OperationType.BET_CONFIRMATION -> optimizedTimings.confirmationDelay
            OperationType.WINDOW_SWITCH -> optimizedTimings.windowSwitchDelay
            OperationType.SCREENSHOT -> optimizedTimings.screenshotDelay
            OperationType.BET_PLACEMENT -> optimizedTimings.clickDelay
            OperationType.RESULT_DETECTION -> optimizedTimings.detectionInterval
            OperationType.SCREENSHOT_CAPTURE -> optimizedTimings.screenshotDelay
            OperationType.UI_INTERACTION -> optimizedTimings.clickDelay
            OperationType.STRATEGY_EXECUTION -> optimizedTimings.strategyDelay
            OperationType.SYSTEM_WAIT -> optimizedTimings.confirmationDelay
            OperationType.ERROR_RECOVERY -> optimizedTimings.reactionDelay
        }
    }
    
    /**
     * Проверяет, нужна ли оптимизация производительности
     */
    fun shouldReduceLoad(): Boolean {
        val recentSlow = performanceHistory.takeLast(10).count { 
            it.duration > SLOW_OPERATION_THRESHOLD 
        }
        return recentSlow >= 3 // Если 3+ медленных операций из последних 10
    }
    
    /**
     * Применяет режим пониженной нагрузки
     */
    fun applyReducedLoadMode() {
        Log.w(TAG, "Применение режима пониженной нагрузки")
        
        optimizedTimings = optimizedTimings.copy(
            detectionInterval = min(optimizedTimings.detectionInterval * 1.5f, MAX_DETECTION_INTERVAL.toFloat()).toLong(),
            reactionDelay = min(optimizedTimings.reactionDelay * 1.2f, MAX_REACTION_DELAY.toFloat()).toLong(),
            clickDelay = optimizedTimings.clickDelay + 100L,
            screenshotDelay = optimizedTimings.screenshotDelay + 200L
        )
        
        Log.d(TAG, "Режим пониженной нагрузки активирован: $optimizedTimings")
    }
    
    /**
     * Применяет режим высокой производительности
     */
    fun applyHighPerformanceMode() {
        Log.d(TAG, "Применение режима высокой производительности")
        
        optimizedTimings = optimizedTimings.copy(
            detectionInterval = max(optimizedTimings.detectionInterval * 0.8f, MIN_DETECTION_INTERVAL.toFloat()).toLong(),
            reactionDelay = max(optimizedTimings.reactionDelay * 0.8f, MIN_REACTION_DELAY.toFloat()).toLong(),
            clickDelay = max(optimizedTimings.clickDelay - 50L, 100L),
            screenshotDelay = max(optimizedTimings.screenshotDelay - 100L, 200L)
        )
        
        Log.d(TAG, "Режим высокой производительности активирован: $optimizedTimings")
    }
    
    /**
     * Получает статистику производительности
     */
    fun getPerformanceStats(): PerformanceStats {
        val recentMetrics = performanceHistory.takeLast(10)
        val avgDuration = if (recentMetrics.isNotEmpty()) {
            recentMetrics.map { it.duration }.average().toLong()
        } else 0L
        
        val successRate = if (recentMetrics.isNotEmpty()) {
            recentMetrics.count { it.success }.toFloat() / recentMetrics.size
        } else 1.0f
        
        return PerformanceStats(
            totalOperations = totalOperations,
            slowOperations = slowOperations,
            averageReactionTime = averageReactionTime,
            recentAverageTime = avgDuration,
            successRate = successRate,
            currentMode = when {
                shouldReduceLoad() -> "REDUCED_LOAD"
                avgDuration < FAST_OPERATION_THRESHOLD -> "HIGH_PERFORMANCE"
                else -> "NORMAL"
            }
        )
    }
    
    // === ПРИВАТНЫЕ МЕТОДЫ ===
    
    /**
     * Загружает базовые тайминги
     */
    private fun loadBaseTimings() {
        optimizedTimings = OptimizedTimings(
            detectionInterval = BASE_DETECTION_INTERVAL,
            reactionDelay = BASE_REACTION_DELAY,
            strategyDelay = BASE_STRATEGY_DELAY,
            clickDelay = BASE_CLICK_DELAY,
            confirmationDelay = BASE_CONFIRMATION_DELAY,
            windowSwitchDelay = 150L,
            screenshotDelay = 300L
        )
    }
    
    /**
     * Применяет оптимизации для конкретного устройства
     */
    private fun applyDeviceOptimizations() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024 // МБ
        val availableProcessors = runtime.availableProcessors()
        
        Log.d(TAG, "Устройство: ${availableProcessors} ядер, ${maxMemory}МБ памяти")
        
        // Оптимизация для слабых устройств
        if (maxMemory < 256 || availableProcessors < 4) {
            Log.d(TAG, "Применение оптимизаций для слабого устройства")
            optimizedTimings = optimizedTimings.copy(
                detectionInterval = optimizedTimings.detectionInterval + 200L,
                screenshotDelay = optimizedTimings.screenshotDelay + 200L
            )
        }
        
        // Оптимизация для мощных устройств
        if (maxMemory > 512 && availableProcessors >= 8) {
            Log.d(TAG, "Применение оптимизаций для мощного устройства")
            optimizedTimings = optimizedTimings.copy(
                detectionInterval = max(optimizedTimings.detectionInterval - 100L, MIN_DETECTION_INTERVAL),
                reactionDelay = max(optimizedTimings.reactionDelay - 30L, MIN_REACTION_DELAY)
            )
        }
    }
    
    /**
     * Адаптирует интервал детекции
     */
    private fun adaptDetectionInterval() {
        val detectionMetrics = performanceHistory.filter { it.operation == OperationType.DETECTION }
        if (detectionMetrics.isEmpty()) return
        
        val avgDuration = detectionMetrics.map { it.duration }.average()
        val successRate = detectionMetrics.count { it.success }.toFloat() / detectionMetrics.size
        
        when {
            avgDuration > SLOW_OPERATION_THRESHOLD -> {
                // Увеличиваем интервал для медленных детекций
                optimizedTimings = optimizedTimings.copy(
                    detectionInterval = min(optimizedTimings.detectionInterval + 100L, MAX_DETECTION_INTERVAL)
                )
            }
            avgDuration < FAST_OPERATION_THRESHOLD && successRate > 0.9f -> {
                // Уменьшаем интервал для быстрых успешных детекций
                optimizedTimings = optimizedTimings.copy(
                    detectionInterval = max(optimizedTimings.detectionInterval - 50L, MIN_DETECTION_INTERVAL)
                )
            }
        }
    }
    
    /**
     * Адаптирует задержки реакции
     */
    private fun adaptReactionDelays() {
        val reactionMetrics = performanceHistory.filter { it.operation == OperationType.REACTION }
        if (reactionMetrics.isEmpty()) return
        
        val avgDuration = reactionMetrics.map { it.duration }.average()
        
        if (avgDuration < 200) {
            // Система быстро реагирует, можем уменьшить задержки
            optimizedTimings = optimizedTimings.copy(
                reactionDelay = max(optimizedTimings.reactionDelay - 20L, MIN_REACTION_DELAY)
            )
        } else if (avgDuration > 800) {
            // Медленная реакция, увеличиваем задержки
            optimizedTimings = optimizedTimings.copy(
                reactionDelay = min(optimizedTimings.reactionDelay + 50L, MAX_REACTION_DELAY)
            )
        }
    }
    
    /**
     * Адаптирует задержки между кликами
     */
    private fun adaptClickDelays() {
        val clickMetrics = performanceHistory.filter { it.operation == OperationType.CLICK }
        if (clickMetrics.isEmpty()) return
        
        val successRate = clickMetrics.count { it.success }.toFloat() / clickMetrics.size
        
        if (successRate < 0.8f) {
            // Низкий успех кликов, увеличиваем задержки
            optimizedTimings = optimizedTimings.copy(
                clickDelay = optimizedTimings.clickDelay + 50L,
                confirmationDelay = optimizedTimings.confirmationDelay + 100L
            )
        } else if (successRate > 0.95f) {
            // Высокий успех, можем уменьшить задержки
            optimizedTimings = optimizedTimings.copy(
                clickDelay = max(optimizedTimings.clickDelay - 25L, 150L),
                confirmationDelay = max(optimizedTimings.confirmationDelay - 50L, 400L)
            )
        }
    }
    
    /**
     * Обновляет статистику
     */
    private fun updateStatistics(metric: PerformanceMetric) {
        totalOperations++
        if (metric.duration > SLOW_OPERATION_THRESHOLD) {
            slowOperations++
        }
        
        // Обновляем среднее время реакции
        if (metric.operation == OperationType.REACTION) {
            averageReactionTime = if (averageReactionTime == 0L) {
                metric.duration
            } else {
                (averageReactionTime + metric.duration) / 2
            }
        }
    }
    
    /**
     * Проверяет необходимость оптимизации
     */
    private fun checkForOptimization() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastOptimization = currentTime - lastOptimizationTime
        
        // Оптимизируем каждые 30 секунд или при накоплении достаточных данных
        if (timeSinceLastOptimization > 30000 || performanceHistory.size >= PERFORMANCE_HISTORY_SIZE) {
            adaptTimings()
            lastOptimizationTime = currentTime
        }
    }
    
    /**
     * Сохраняет оптимизированные тайминги
     */
    private fun saveOptimizedTimings() {
        // Здесь можно сохранить тайминги в preferences для восстановления
        // preferencesManager.saveOptimizedTimings(optimizedTimings)
    }
    
    /**
     * Обновляет настройки оптимизатора
     */
    fun updateSettings(newSettings: DualModeSettings) {
        Log.d(TAG, "Обновление настроек оптимизатора")
        // Здесь можно добавить логику обновления настроек если нужно
    }
    
    /**
     * Принудительно оптимизирует производительность
     */
    fun optimize() {
        Log.d(TAG, "Принудительная оптимизация производительности")
        enableHighPerformanceMode()
    }
    
    /**
     * Включает режим высокой производительности
     */
    private fun enableHighPerformanceMode() {
        Log.d(TAG, "Включение режима высокой производительности")
        optimizedTimings = optimizedTimings.copy(
            detectionInterval = (optimizedTimings.detectionInterval * 0.8).toLong(),
            reactionDelay = (optimizedTimings.reactionDelay * 0.7).toLong(),
            strategyDelay = (optimizedTimings.strategyDelay * 0.8).toLong(),
            clickDelay = (optimizedTimings.clickDelay * 0.7).toLong(),
            windowSwitchDelay = (optimizedTimings.windowSwitchDelay * 0.8).toLong(),
            screenshotDelay = (optimizedTimings.screenshotDelay * 0.8).toLong()
        )
    }
}

/**
 * Оптимизированные тайминги
 */
data class OptimizedTimings(
    val detectionInterval: Long = 500L,
    val reactionDelay: Long = 100L,
    val strategyDelay: Long = 300L,
    val clickDelay: Long = 200L,
    val confirmationDelay: Long = 500L,
    val windowSwitchDelay: Long = 150L,
    val screenshotDelay: Long = 300L
)

/**
 * Метрика производительности операции
 */
data class PerformanceMetric(
    val operation: OperationType,
    val duration: Long,
    val success: Boolean,
    val timestamp: Long
)

/**
 * Статистика производительности
 */
data class PerformanceStats(
    val totalOperations: Int,
    val slowOperations: Int,
    val averageReactionTime: Long,
    val recentAverageTime: Long,
    val successRate: Float,
    val currentMode: String
)
