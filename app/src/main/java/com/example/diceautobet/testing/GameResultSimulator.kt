package com.example.diceautobet.testing

import android.util.Log
import com.example.diceautobet.models.GameResultType
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Симулятор результатов игры для тестирования упрощенной стратегии
 * Генерирует случайные результаты с реалистичными вероятностями
 */
class GameResultSimulator {
    
    companion object {
        private const val TAG = "GameResultSimulator"
        
        // Вероятности результатов (в процентах)
        private const val WIN_PROBABILITY = 45.0  // 45% выигрышей
        private const val LOSS_PROBABILITY = 50.0 // 50% проигрышей
        private const val DRAW_PROBABILITY = 5.0  // 5% ничьих
        
        // Интервал между результатами (миллисекунды)
        private const val RESULT_INTERVAL = 10000L // 10 секунд
    }
    
    private val simulatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var simulationJob: Job? = null
    private var isRunning = false
    
    // Колбэк для отправки результатов
    var onResultGenerated: ((GameResultType) -> Unit)? = null
    
    /**
     * Запускает симуляцию результатов
     */
    fun startSimulation() {
        if (isRunning) {
            Log.w(TAG, "Симуляция уже запущена")
            return
        }
        
        Log.d(TAG, "🎯 Запуск симуляции результатов игры")
        isRunning = true
        
        simulationJob = simulatorScope.launch {
            while (isRunning) {
                delay(RESULT_INTERVAL)
                
                if (isRunning) {
                    val result = generateRandomResult()
                    Log.d(TAG, "🎲 Сгенерирован результат: $result")
                    onResultGenerated?.invoke(result)
                }
            }
        }
        
        Log.d(TAG, "✅ Симуляция запущена")
    }
    
    /**
     * Останавливает симуляцию результатов
     */
    fun stopSimulation() {
        if (!isRunning) {
            Log.w(TAG, "Симуляция не запущена")
            return
        }
        
        Log.d(TAG, "🛑 Остановка симуляции результатов")
        isRunning = false
        simulationJob?.cancel()
        simulationJob = null
        
        Log.d(TAG, "✅ Симуляция остановлена")
    }
    
    /**
     * Генерирует случайный результат на основе вероятностей
     */
    private fun generateRandomResult(): GameResultType {
        val random = Random.nextDouble(0.0, 100.0)
        
        return when {
            random < WIN_PROBABILITY -> GameResultType.WIN
            random < WIN_PROBABILITY + LOSS_PROBABILITY -> GameResultType.LOSS
            random < WIN_PROBABILITY + LOSS_PROBABILITY + DRAW_PROBABILITY -> GameResultType.DRAW
            else -> GameResultType.LOSS // Резервный случай
        }
    }
    
    /**
     * Генерирует тестовую последовательность результатов
     */
    fun startTestSequence(results: List<GameResultType>, intervalMs: Long = 3000L) {
        if (isRunning) {
            Log.w(TAG, "Симуляция уже запущена")
            return
        }
        
        Log.d(TAG, "🧪 Запуск тестовой последовательности: ${results.joinToString()}")
        isRunning = true
        
        simulationJob = simulatorScope.launch {
            for ((index, result) in results.withIndex()) {
                if (!isRunning) break
                
                delay(intervalMs)
                Log.d(TAG, "🎯 Тест ${index + 1}/${results.size}: $result")
                onResultGenerated?.invoke(result)
            }
            
            Log.d(TAG, "✅ Тестовая последовательность завершена")
            isRunning = false
        }
    }
    
    /**
     * Проверяет, запущена ли симуляция
     */
    fun isRunning(): Boolean = isRunning
    
    /**
     * Освобождает ресурсы
     */
    fun destroy() {
        Log.d(TAG, "🧹 Освобождение ресурсов симулятора")
        stopSimulation()
        simulatorScope.cancel()
    }
    
    /**
     * Генерирует тестовую последовательность для проверки смены цвета
     */
    fun createColorChangeTestSequence(): List<GameResultType> {
        return listOf(
            GameResultType.LOSS,  // 1-й проигрыш на красном
            GameResultType.LOSS,  // 2-й проигрыш на красном -> смена на оранжевый
            GameResultType.WIN,   // выигрыш на оранжевом
            GameResultType.LOSS,  // 1-й проигрыш на оранжевом
            GameResultType.LOSS,  // 2-й проигрыш на оранжевом -> смена на красный
            GameResultType.WIN,   // выигрыш на красном
            GameResultType.LOSS   // проигрыш на красном
        )
    }
    
    /**
     * Генерирует тестовую последовательность для проверки удвоения ставок
     */
    fun createBetDoublingTestSequence(): List<GameResultType> {
        return listOf(
            GameResultType.LOSS,  // ставка 10 -> 20
            GameResultType.LOSS,  // ставка 20 -> 40
            GameResultType.LOSS,  // ставка 40 -> 80
            GameResultType.WIN,   // ставка 80 -> выигрыш, сброс на 10
            GameResultType.LOSS,  // ставка 10 -> 20
            GameResultType.WIN    // ставка 20 -> выигрыш, сброс на 10
        )
    }
    
    /**
     * Получает статистику симуляции
     */
    fun getSimulationInfo(): String {
        return """
            🎯 Симулятор результатов игры:
            
            📊 Настройки:
            • Вероятность выигрыша: ${WIN_PROBABILITY}%
            • Вероятность проигрыша: ${LOSS_PROBABILITY}%
            • Вероятность ничьи: ${DRAW_PROBABILITY}%
            • Интервал результатов: ${RESULT_INTERVAL / 1000} сек
            
            📈 Статус: ${if (isRunning) "Запущен" else "Остановлен"}
        """.trimIndent()
    }
}
