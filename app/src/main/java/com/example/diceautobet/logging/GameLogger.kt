package com.example.diceautobet.logging

import android.content.Context
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.game.BettingStatistics
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*

class GameLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "GameLogger"
        private const val LOG_FILE_NAME = "game_log.txt"
        private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_LOG_ENTRIES = 1000
    }
    
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var isLoggingEnabled = true
    private var logToFile = true
    private var logToConsole = true
    
    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startLogProcessor()
    }
    
    /**
     * Логирует размещение ставки
     */
    fun logBet(amount: Int, choice: BetChoice, consecutiveLosses: Int = 0) {
        if (!isLoggingEnabled) return
        
        val message = "Ставка: $amount на $choice" + 
                     if (consecutiveLosses > 0) " (проигрыш $consecutiveLosses)" else ""
        
        addLogEntry(LogLevel.INFO, "BET", message, mapOf(
            "amount" to amount,
            "choice" to choice.name,
            "consecutiveLosses" to consecutiveLosses
        ))
    }

    /**
     * Логирует размещение ставки с дополнительными деталями
     */
    fun logBet(choice: BetChoice, amount: Int, details: Map<String, Any>) {
        if (!isLoggingEnabled) return
        
        val message = "Ставка: $amount на $choice"
        
        addLogEntry(LogLevel.INFO, "BET", message, details + mapOf(
            "amount" to amount,
            "choice" to choice.name
        ))
    }

    /**
     * Логирует игровое событие
     */
    fun logGameEvent(event: String, details: Map<String, Any> = emptyMap()) {
        if (!isLoggingEnabled) return
        
        addLogEntry(LogLevel.INFO, "GAME_EVENT", event, details)
    }
    
    /**
     * Логирует результат раунда
     */
    fun logResult(result: RoundResult) {
        if (!isLoggingEnabled) return
        
        val message = "Результат: ${result.redDots} красный, ${result.orangeDots} оранжевый, " +
                     "победитель: ${result.winner?.name ?: "ничья"}, " +
                     "уверенность: ${String.format("%.2f", result.confidence)}"
        
        addLogEntry(LogLevel.INFO, "RESULT", message, mapOf(
            "redDots" to result.redDots,
            "orangeDots" to result.orangeDots,
            "winner" to (result.winner?.name ?: "draw"),
            "confidence" to result.confidence,
            "isDraw" to result.isDraw,
            "isValid" to result.isValid
        ))
    }
    
    /**
     * Логирует ошибку
     */
    fun logError(error: Throwable, context: String = "") {
        if (!isLoggingEnabled) return
        
        val message = "Ошибка${if (context.isNotEmpty()) " [$context]" else ""}: ${error.message}"
        
        addLogEntry(LogLevel.ERROR, "ERROR", message, mapOf(
            "errorType" to error.javaClass.simpleName,
            "context" to context,
            "stackTrace" to error.stackTraceToString()
        ))
    }
    
    /**
     * Логирует действие пользователя
     */
    fun logUserAction(action: String, details: Map<String, Any> = emptyMap()) {
        if (!isLoggingEnabled) return
        
        addLogEntry(LogLevel.INFO, "USER_ACTION", action, details)
    }
    
    /**
     * Логирует начало игры
     */
    fun logGameStart(gameState: GameState) {
        if (!isLoggingEnabled) return
        
        val message = "Игра запущена: базовая ставка ${gameState.baseBet}, " +
                     "максимум попыток ${gameState.maxAttempts}, " +
                     "ставка на ${gameState.betChoice.name}"
        
        addLogEntry(LogLevel.INFO, "GAME_START", message, mapOf(
            "baseBet" to gameState.baseBet,
            "maxAttempts" to gameState.maxAttempts,
            "betChoice" to gameState.betChoice.name
        ))
    }
    
    /**
     * Логирует начало двойного режима (перегрузка для DualGameState)
     */
    fun logGameStart(dualGameState: DualGameState, dualSettings: DualModeSettings) {
        if (!isLoggingEnabled) return
        
        val message = "Двойной режим запущен: базовая ставка ${dualSettings.baseBet}, " +
                     "стратегия ${dualSettings.strategy.name}"
        
        addLogEntry(LogLevel.INFO, "DUAL_GAME_START", message, mapOf(
            "baseBet" to dualSettings.baseBet,
            "strategy" to dualSettings.strategy.name,
            "leftWindow" to (dualGameState.leftWindow != null),
            "rightWindow" to (dualGameState.rightWindow != null)
        ))
    }
    
    /**
     * Логирует остановку игры
     */
    fun logGameStop(reason: String, finalStats: GameStats? = null) {
        if (!isLoggingEnabled) return
        
        val message = "Игра остановлена: $reason"
        val details = mutableMapOf<String, Any>("reason" to reason)
        
        finalStats?.let { stats ->
            details.putAll(mapOf(
                "totalRounds" to stats.totalRounds,
                "totalWins" to stats.totalWins,
                "totalLosses" to stats.totalLosses,
                "winRate" to stats.winRate,
                "maxConsecutiveLosses" to stats.maxConsecutiveLosses
            ))
        }
        
        addLogEntry(LogLevel.INFO, "GAME_STOP", message, details)
    }
    
    /**
     * Логирует клик
     */
    fun logClick(area: ScreenArea, success: Boolean) {
        if (!isLoggingEnabled) return
        
        val message = "Клик по ${area.name}: ${if (success) "успех" else "неудача"}"
        
        addLogEntry(LogLevel.DEBUG, "CLICK", message, mapOf(
            "areaName" to area.name,
            "coordinates" to area.rect.toString(),
            "success" to success
        ))
    }
    
    /**
     * Логирует анализ изображения
     */
    fun logImageAnalysis(
        analysisTime: Long,
        confidence: Float,
        dotsFound: Int,
        success: Boolean
    ) {
        if (!isLoggingEnabled) return
        
        val message = "Анализ изображения: ${analysisTime}мс, " +
                     "уверенность: ${String.format("%.2f", confidence)}, " +
                     "точек найдено: $dotsFound"
        
        addLogEntry(LogLevel.DEBUG, "IMAGE_ANALYSIS", message, mapOf(
            "analysisTime" to analysisTime,
            "confidence" to confidence,
            "dotsFound" to dotsFound,
            "success" to success
        ))
    }
    
    /**
     * Логирует системные события
     */
    fun logSystemEvent(event: String, details: Map<String, Any> = emptyMap()) {
        if (!isLoggingEnabled) return
        
        addLogEntry(LogLevel.INFO, "SYSTEM", event, details)
    }
    
    /**
     * Логирует предупреждение
     */
    fun logWarning(message: String, details: Map<String, Any> = emptyMap()) {
        if (!isLoggingEnabled) return
        
        addLogEntry(LogLevel.WARNING, "WARNING", message, details)
    }
    
    /**
     * Логирует сохранение области
     */
    fun logAreaSaved(areaType: AreaType, selection: android.graphics.Rect) {
        if (!isLoggingEnabled) return
        
        val message = "Область ${areaType.displayName} сохранена: ${selection.left},${selection.top} - ${selection.right},${selection.bottom}"
        
        addLogEntry(LogLevel.INFO, "AREA_CONFIG", message, mapOf(
            "areaType" to areaType.name,
            "left" to selection.left,
            "top" to selection.top,
            "right" to selection.right,
            "bottom" to selection.bottom
        ))
    }
    
    /**
     * Логирует тест координат
     */
    fun logCoordinatesTest(relativeSelection: android.graphics.Rect?, absoluteSelection: android.graphics.Rect?) {
        if (!isLoggingEnabled) return
        
        val message = "Тест координат: относительные=$relativeSelection, абсолютные=$absoluteSelection"
        
        addLogEntry(LogLevel.DEBUG, "COORDINATES_TEST", message, mapOf(
            "relativeSelection" to relativeSelection?.toString().orEmpty(),
            "absoluteSelection" to absoluteSelection?.toString().orEmpty()
        ))
    }
    
    /**
     * Логирует расчет ставки Мартингейла
     */
    fun logMartingaleBet(currentStep: Int, betAmount: Int, reason: String) {
        if (!isLoggingEnabled) return
        
        val message = "Мартингейл шаг $currentStep: ставка $betAmount (${reason})"
        
        addLogEntry(LogLevel.INFO, "MARTINGALE", message, mapOf(
            "step" to currentStep,
            "betAmount" to betAmount,
            "reason" to reason
        ))
    }
    
    /**
     * Логирует статистику сессии
     */
    fun logSessionStatistics(stats: BettingStatistics) {
        if (!isLoggingEnabled) return
        
        val message = "Статистика сессии: прибыль=${stats.totalProfit}, " +
                     "ставок=${stats.totalBetsPlaced}, " +
                     "процент=${String.format("%.2f", stats.winRate)}%"
        
        addLogEntry(LogLevel.INFO, "SESSION_STATS", message, mapOf(
            "totalProfit" to stats.totalProfit,
            "totalBetsPlaced" to stats.totalBetsPlaced,
            "winRate" to stats.winRate,
            "profitability" to stats.profitability,
            "currentStreak" to stats.currentStreak,
            "maxConsecutiveLosses" to stats.maxConsecutiveLosses,
            "martingaleStep" to stats.martingaleStep,
            "recommendedNextBet" to stats.recommendedNextBet
        ))
    }

    private fun addLogEntry(level: LogLevel, category: String, message: String, details: Map<String, Any>) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            details = details
        )
        
        logQueue.offer(entry)
        
        // Ограничиваем количество записей в памяти
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
    }
    
    private fun startLogProcessor() {
        logScope.launch {
            try {
                while (isActive) {
                    try {
                        val entry = logQueue.poll()
                        if (entry != null) {
                            processLogEntry(entry)
                        } else {
                            delay(100) // Небольшая задержка, если нет записей
                        }
                    } catch (e: CancellationException) {
                        // Корутина отменена - выходим
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки лога", e)
                    }
                }
            } catch (e: CancellationException) {
                // Корутина отменена
                Log.d(TAG, "Log processor отменен")
            }
        }
    }
    
    private fun processLogEntry(entry: LogEntry) {
        val formattedMessage = formatLogEntry(entry)
        
        if (logToConsole) {
            when (entry.level) {
                LogLevel.DEBUG -> Log.d(entry.category, formattedMessage)
                LogLevel.INFO -> Log.i(entry.category, formattedMessage)
                LogLevel.WARNING -> Log.w(entry.category, formattedMessage)
                LogLevel.ERROR -> Log.e(entry.category, formattedMessage)
            }
        }
        
        if (logToFile) {
            writeToFile(formattedMessage)
        }
    }
    
    private fun formatLogEntry(entry: LogEntry): String {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val details = if (entry.details.isNotEmpty()) {
            " [${entry.details.entries.joinToString(", ") { "${it.key}=${it.value}" }}]"
        } else ""
        
        return "$timestamp [${entry.level.name}] ${entry.message}$details"
    }
    
    private fun writeToFile(message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
            
            // Проверяем размер файла и ротируем если нужно
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                rotateLogFile(logFile)
            }
            
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(message)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка записи в файл лога", e)
        }
    }
    
    private fun rotateLogFile(logFile: File) {
        try {
            val backupFile = File(logFile.parent, "${LOG_FILE_NAME}.backup")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            logFile.renameTo(backupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка ротации файла лога", e)
        }
    }
    
    /**
     * Получает последние записи лога
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logQueue.toList().takeLast(count)
    }
    
    /**
     * Получает путь к файлу лога
     */
    fun getLogFilePath(): String {
        return File(context.getExternalFilesDir(null), LOG_FILE_NAME).absolutePath
    }
    
    /**
     * Очищает логи
     */
    fun clearLogs() {
        logQueue.clear()
        try {
            val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки файла лога", e)
        }
    }
    
    /**
     * Настройки логирования
     */
    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
        Log.d(TAG, "Логирование ${if (enabled) "включено" else "отключено"}")
    }
    
    fun setLogToFile(enabled: Boolean) {
        logToFile = enabled
    }
    
    fun setLogToConsole(enabled: Boolean) {
        logToConsole = enabled
    }
    
    /**
     * Уничтожает логгер
     */
    fun destroy() {
        try {
            isLoggingEnabled = false
            
            // Дожидаемся завершения всех корутин
            runBlocking {
                try {
                    logScope.cancel()
                    logScope.coroutineContext[Job]?.join()
                } catch (e: CancellationException) {
                    // Ожидаемое поведение при отмене
                }
            }
            
            logQueue.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка уничтожения логгера", e)
        }
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
    val details: Map<String, Any>
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

data class GameStats(
    val totalRounds: Int,
    val totalWins: Int,
    val totalLosses: Int,
    val maxConsecutiveLosses: Int
) {
    val winRate: Float = if (totalRounds > 0) totalWins.toFloat() / totalRounds else 0f
}
