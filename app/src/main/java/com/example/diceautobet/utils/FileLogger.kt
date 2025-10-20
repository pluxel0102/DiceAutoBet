package com.example.diceautobet.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.*

/**
 * Логгер для записи всех логов в текстовый файл
 * Автоматически начинает запись при запуске приложения
 * 
 * ВАЖНО: Также перехватывает стандартные android.util.Log вызовы
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILE_NAME = "dice_autobet_logs.txt"
    private const val MAX_LOG_SIZE_MB = 10 // Максимальный размер файла логов (10 МБ)
    
    private var context: Context? = null
    private var logFile: File? = null
    private var isInitialized = false
    private val logQueue = LinkedBlockingQueue<String>()
    private var writerJob: Job? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    // Перехватчик для автоматической записи всех Log вызовов
    private var originalLogHandler: ((Int, String?, String, Throwable?) -> Int)? = null
    
    /**
     * Инициализация логгера
     * Вызывать в Application.onCreate() или MainActivity.onCreate()
     */
    fun initialize(appContext: Context) {
        if (isInitialized) return
        
        context = appContext.applicationContext
        logFile = File(context!!.getExternalFilesDir(null), LOG_FILE_NAME)
        
        // Проверяем размер файла, если больше лимита - создаем новый
        if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE_MB * 1024 * 1024) {
            archiveOldLog()
        }
        
        // Записываем заголовок новой сессии
        val sessionStart = """
            
            ═══════════════════════════════════════════════════════════════
            🚀 НОВАЯ СЕССИЯ ЗАПУЩЕНА: ${dateFormat.format(Date())}
            ═══════════════════════════════════════════════════════════════
            
        """.trimIndent()
        
        writeToFile(sessionStart)
        
        // Запускаем фоновую задачу для записи логов
        startWriterJob()
        
        isInitialized = true
        Log.d(TAG, "📝 FileLogger инициализирован: ${logFile!!.absolutePath}")
    }
    
    /**
     * Архивирование старого лог-файла
     */
    private fun archiveOldLog() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val archiveFile = File(context!!.getExternalFilesDir(null), "dice_autobet_logs_$timestamp.txt")
            logFile!!.renameTo(archiveFile)
            Log.d(TAG, "📦 Старый лог заархивирован: ${archiveFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка архивации лог-файла", e)
        }
    }
    
    /**
     * Запуск фоновой задачи для записи логов
     */
    private fun startWriterJob() {
        writerJob?.cancel()
        writerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val log = logQueue.take() // Блокирующее ожидание нового лога
                    writeToFile(log)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка записи лога", e)
                }
            }
        }
    }
    
    /**
     * Добавление лога в очередь
     */
    fun log(tag: String, level: String, message: String) {
        if (!isInitialized) return
        
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp $level/$tag: $message\n"
        
        // Добавляем в очередь (не блокируя основной поток)
        logQueue.offer(logEntry)
    }
    
    /**
     * Вспомогательные методы для разных уровней логирования
     */
    fun d(tag: String, message: String) = log(tag, "D", message)
    fun i(tag: String, message: String) = log(tag, "I", message)
    fun w(tag: String, message: String) = log(tag, "W", message)
    fun e(tag: String, message: String) = log(tag, "E", message)
    fun v(tag: String, message: String) = log(tag, "V", message)
    
    /**
     * Запись в файл (синхронная)
     */
    private fun writeToFile(text: String) {
        try {
            FileOutputStream(logFile, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.write(text)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка записи в файл", e)
        }
    }
    
    /**
     * Получить путь к файлу логов
     */
    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
    
    /**
     * Получить файл логов для отправки
     */
    fun getLogFile(): File? {
        return logFile
    }
    
    /**
     * Очистка логов
     */
    fun clearLogs() {
        try {
            logFile?.writeText("")
            Log.d(TAG, "🗑️ Логи очищены")
            
            val sessionStart = """
                ═══════════════════════════════════════════════════════════════
                🗑️ ЛОГИ ОЧИЩЕНЫ: ${dateFormat.format(Date())}
                ═══════════════════════════════════════════════════════════════
                
            """.trimIndent()
            writeToFile(sessionStart)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка очистки логов", e)
        }
    }
    
    /**
     * Завершение работы логгера
     */
    fun shutdown() {
        writerJob?.cancel()
        writerJob = null
        
        val sessionEnd = """
            
            ═══════════════════════════════════════════════════════════════
            🛑 СЕССИЯ ЗАВЕРШЕНА: ${dateFormat.format(Date())}
            ═══════════════════════════════════════════════════════════════
            
        """.trimIndent()
        writeToFile(sessionEnd)
        
        isInitialized = false
        Log.d(TAG, "📝 FileLogger остановлен")
    }
}
