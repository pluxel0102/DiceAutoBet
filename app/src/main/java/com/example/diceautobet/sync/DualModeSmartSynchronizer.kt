package com.example.diceautobet.sync

import android.content.Context
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.timing.DualModeTimingOptimizer
import com.example.diceautobet.timing.OperationType
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Интеллектуальный синхронизатор для двойного режима
 * Обеспечивает точную координацию между окнами и предотвращает конфликты
 */
class DualModeSmartSynchronizer(
    private val context: Context,
    private val timingOptimizer: DualModeTimingOptimizer
) {
    
    companion object {
        private const val TAG = "DualModeSmartSynchronizer"
        
        // Тайминги синхронизации
        private const val MAX_WAIT_FOR_WINDOW_MS = 3000L
        private const val WINDOW_SWITCH_BUFFER_MS = 200L
        private const val OPERATION_TIMEOUT_MS = 5000L
        
        // Приоритеты операций
        private const val PRIORITY_HIGH = 3
        private const val PRIORITY_NORMAL = 2
        private const val PRIORITY_LOW = 1
    }
    
    private val syncScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val leftWindowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val rightWindowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Состояние синхронизации
    private val isLeftWindowBusy = AtomicBoolean(false)
    private val isRightWindowBusy = AtomicBoolean(false)
    private val lastWindowSwitchTime = AtomicLong(0L)
    private val operationCounter = AtomicLong(0L)
    
    // Очереди операций для каждого окна
    private val leftWindowQueue = mutableListOf<SyncOperation>()
    private val rightWindowQueue = mutableListOf<SyncOperation>()
    private val operationQueues = mutableMapOf<WindowType, MutableList<SyncOperation>>(
        WindowType.LEFT to leftWindowQueue,
        WindowType.RIGHT to rightWindowQueue
    )
    
    // Слушатели событий
    private var onWindowReady: ((WindowType) -> Unit)? = null
    private var onOperationCompleted: ((SyncOperation, Boolean) -> Unit)? = null
    private var onSyncError: ((String, Exception?) -> Unit)? = null
    
    /**
     * Запрашивает доступ к окну для выполнения операции
     */
    suspend fun requestWindowAccess(
        windowType: WindowType,
        operation: SyncOperation,
        priority: Int = PRIORITY_NORMAL
    ): Boolean {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Запрос доступа к окну $windowType для операции ${operation.type}")
        
        try {
            // Проверяем доступность окна
            if (!isWindowAvailable(windowType)) {
                // Добавляем в очередь если окно занято
                addToQueue(windowType, operation.copy(priority = priority))
                
                // Ждем освобождения окна
                val success = waitForWindow(windowType, MAX_WAIT_FOR_WINDOW_MS)
                if (!success) {
                    Log.w(TAG, "Таймаут ожидания доступа к окну $windowType")
                    return false
                }
            }
            
            // Блокируем окно
            lockWindow(windowType)
            
            // Записываем метрику времени ожидания
            val waitTime = System.currentTimeMillis() - startTime
            timingOptimizer.recordOperationMetric(
                OperationType.WINDOW_SWITCH,
                waitTime,
                true
            )
            
            Log.d(TAG, "Доступ к окну $windowType получен за ${waitTime}мс")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запросе доступа к окну $windowType", e)
            onSyncError?.invoke("Ошибка доступа к окну", e)
            return false
        }
    }
    
    /**
     * Освобождает окно после выполнения операции
     */
    fun releaseWindow(windowType: WindowType, operation: SyncOperation, success: Boolean) {
        Log.d(TAG, "Освобождение окна $windowType после операции ${operation.type}")
        
        // Разблокируем окно
        unlockWindow(windowType)
        
        // Обновляем время последнего переключения
        lastWindowSwitchTime.set(System.currentTimeMillis())
        
        // Уведомляем о завершении операции
        onOperationCompleted?.invoke(operation, success)
        
        // Обрабатываем очередь
        processWindowQueue(windowType)
        
        // Уведомляем о готовности окна
        onWindowReady?.invoke(windowType)
        
        Log.d(TAG, "Окно $windowType освобождено")
    }
    
    /**
     * Выполняет синхронизированную операцию в окне
     */
    suspend fun executeSynchronizedOperation(
        windowType: WindowType,
        operation: SyncOperation,
        execution: suspend () -> Boolean
    ): Boolean {
        val operationId = operationCounter.incrementAndGet()
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "Начало синхронизированной операции #$operationId: ${operation.type} в окне $windowType")
        
        try {
            // Запрашиваем доступ к окну
            val accessGranted = requestWindowAccess(windowType, operation, operation.priority)
            if (!accessGranted) {
                Log.w(TAG, "Не удалось получить доступ к окну $windowType")
                return false
            }
            
            // Применяем умную задержку
            applySmartDelay(operation)
            
            // Выполняем операцию с таймаутом
            val result = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                execution()
            } ?: false
            
            // Записываем метрику выполнения
            val executionTime = System.currentTimeMillis() - startTime
            timingOptimizer.recordOperationMetric(
                operation.type.toOperationType(),
                executionTime,
                result
            )
            
            Log.d(TAG, "Операция #$operationId завершена за ${executionTime}мс, результат: $result")
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выполнения операции #$operationId", e)
            onSyncError?.invoke("Ошибка выполнения операции", e)
            return false
        } finally {
            // Всегда освобождаем окно
            releaseWindow(windowType, operation, true)
        }
    }
    
    /**
     * Синхронизирует операции между окнами
     */
    suspend fun synchronizeBetweenWindows(
        leftOperation: SyncOperation?,
        rightOperation: SyncOperation?
    ): Pair<Boolean, Boolean> {
        Log.d(TAG, "Синхронизация операций между окнами")
        
        val results = mutableListOf<Deferred<Boolean>>()
        
        // Запускаем операции параллельно
        leftOperation?.let { op ->
            val deferred = syncScope.async {
                executeSynchronizedOperation(WindowType.LEFT, op) {
                    // Заглушка для выполнения операции
                    delay(op.estimatedDuration)
                    true
                }
            }
            results.add(deferred)
        }
        
        rightOperation?.let { op ->
            val deferred = syncScope.async {
                // Небольшая задержка для предотвращения конфликтов
                delay(WINDOW_SWITCH_BUFFER_MS)
                executeSynchronizedOperation(WindowType.RIGHT, op) {
                    // Заглушка для выполнения операции
                    delay(op.estimatedDuration)
                    true
                }
            }
            results.add(deferred)
        }
        
        // Ждем завершения всех операций
        val completedResults = results.awaitAll()
        
        val leftResult = if (leftOperation != null && completedResults.isNotEmpty()) {
            completedResults[0]
        } else true
        
        val rightResult = if (rightOperation != null && completedResults.size > 1) {
            completedResults[1]
        } else if (rightOperation != null && completedResults.isNotEmpty()) {
            completedResults[0]
        } else true
        
        Log.d(TAG, "Синхронизация завершена: левое=$leftResult, правое=$rightResult")
        return Pair(leftResult, rightResult)
    }
    
    /**
     * Проверяет готовность системы для быстрого переключения
     */
    fun isReadyForFastSwitch(): Boolean {
        val timeSinceLastSwitch = System.currentTimeMillis() - lastWindowSwitchTime.get()
        val bothWindowsFree = !isLeftWindowBusy.get() && !isRightWindowBusy.get()
        val queuesEmpty = leftWindowQueue.isEmpty() && rightWindowQueue.isEmpty()
        
        return timeSinceLastSwitch >= WINDOW_SWITCH_BUFFER_MS && 
               bothWindowsFree && 
               queuesEmpty
    }
    
    /**
     * Получает статистику синхронизации
     */
    fun getSyncStats(): SyncStats {
        val totalOperations = operationCounter.get()
        val leftQueueSize = leftWindowQueue.size
        val rightQueueSize = rightWindowQueue.size
        val bothWindowsBusy = isLeftWindowBusy.get() && isRightWindowBusy.get()
        
        return SyncStats(
            totalOperations = totalOperations.toInt(),
            leftQueueSize = leftQueueSize,
            rightQueueSize = rightQueueSize,
            isLeftBusy = isLeftWindowBusy.get(),
            isRightBusy = isRightWindowBusy.get(),
            bothWindowsBusy = bothWindowsBusy,
            readyForFastSwitch = isReadyForFastSwitch()
        )
    }
    
    /**
     * Очищает все очереди и сбрасывает состояние
     */
    fun reset() {
        Log.d(TAG, "Сброс синхронизатора")
        
        leftWindowQueue.clear()
        rightWindowQueue.clear()
        isLeftWindowBusy.set(false)
        isRightWindowBusy.set(false)
        lastWindowSwitchTime.set(0L)
        
        Log.d(TAG, "Синхронизатор сброшен")
    }
    
    // === ПРИВАТНЫЕ МЕТОДЫ ===
    
    /**
     * Проверяет доступность окна
     */
    private fun isWindowAvailable(windowType: WindowType): Boolean {
        return when (windowType) {
            WindowType.LEFT, WindowType.TOP -> !isLeftWindowBusy.get()
            WindowType.RIGHT, WindowType.BOTTOM -> !isRightWindowBusy.get()
        }
    }
    
    /**
     * Блокирует окно
     */
    private fun lockWindow(windowType: WindowType) {
        when (windowType) {
            WindowType.LEFT, WindowType.TOP -> isLeftWindowBusy.set(true)
            WindowType.RIGHT, WindowType.BOTTOM -> isRightWindowBusy.set(true)
        }
    }
    
    /**
     * Разблокирует окно
     */
    private fun unlockWindow(windowType: WindowType) {
        when (windowType) {
            WindowType.LEFT, WindowType.TOP -> isLeftWindowBusy.set(false)
            WindowType.RIGHT, WindowType.BOTTOM -> isRightWindowBusy.set(false)
        }
    }
    
    /**
     * Ждет освобождения окна
     */
    private suspend fun waitForWindow(windowType: WindowType, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isWindowAvailable(windowType)) {
                return true
            }
            delay(50) // Проверяем каждые 50мс
        }
        
        return false
    }
    
    /**
     * Добавляет операцию в очередь
     */
    private fun addToQueue(windowType: WindowType, operation: SyncOperation) {
        when (windowType) {
            WindowType.LEFT, WindowType.TOP -> {
                leftWindowQueue.add(operation)
                leftWindowQueue.sortByDescending { it.priority } // Сортируем по приоритету
            }
            WindowType.RIGHT, WindowType.BOTTOM -> {
                rightWindowQueue.add(operation)
                rightWindowQueue.sortByDescending { it.priority }
            }
        }
        
        Log.d(TAG, "Операция ${operation.type} добавлена в очередь окна $windowType")
    }
    
    /**
     * Обрабатывает очередь операций для окна
     */
    private fun processWindowQueue(windowType: WindowType) {
        val queue = when (windowType) {
            WindowType.LEFT, WindowType.TOP -> leftWindowQueue
            WindowType.RIGHT, WindowType.BOTTOM -> rightWindowQueue
        }
        
        if (queue.isNotEmpty()) {
            val nextOperation = queue.removeFirst()
            Log.d(TAG, "Обработка следующей операции из очереди: ${nextOperation.type}")
            
            // Запускаем следующую операцию асинхронно
            syncScope.launch {
                requestWindowAccess(windowType, nextOperation, nextOperation.priority)
            }
        }
    }
    
    /**
     * Применяет умную задержку на основе операции
     */
    private suspend fun applySmartDelay(operation: SyncOperation) {
        val delay = timingOptimizer.getDelayForOperation(operation.type.toOperationType())
        
        // Адаптируем задержку на основе приоритета
        val adjustedDelay = when (operation.priority) {
            PRIORITY_HIGH -> (delay * 0.7f).toLong()
            PRIORITY_NORMAL -> delay
            PRIORITY_LOW -> (delay * 1.3f).toLong()
            else -> delay
        }
        
        if (adjustedDelay > 0) {
            delay(adjustedDelay)
        }
    }
    
    // === СЕТТЕРЫ ДЛЯ СЛУШАТЕЛЕЙ ===
    
    fun setOnWindowReadyListener(listener: (WindowType) -> Unit) {
        onWindowReady = listener
    }
    
    fun setOnOperationCompletedListener(listener: (SyncOperation, Boolean) -> Unit) {
        onOperationCompleted = listener
    }
    
    fun setOnSyncErrorListener(listener: (String, Exception?) -> Unit) {
        onSyncError = listener
    }
    
    /**
     * Освобождает ресурсы синхронизатора
     */
    fun cleanup() {
        Log.d(TAG, "Очистка ресурсов синхронизатора")
        
        // Отменяем все запущенные корутины
        leftWindowScope.cancel()
        rightWindowScope.cancel()
        
        // Очищаем очереди операций
        operationQueues.clear()
        
        Log.d(TAG, "Ресурсы синхронизатора очищены")
    }
}

/**
 * Статистика синхронизации
 */
data class SyncStats(
    val totalOperations: Int,
    val leftQueueSize: Int,
    val rightQueueSize: Int,
    val isLeftBusy: Boolean,
    val isRightBusy: Boolean,
    val bothWindowsBusy: Boolean,
    val readyForFastSwitch: Boolean
)
