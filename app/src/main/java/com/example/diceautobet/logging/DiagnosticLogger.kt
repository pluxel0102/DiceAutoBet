package com.example.diceautobet.logging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import com.example.diceautobet.services.AutoClickService
import com.example.diceautobet.utils.PreferencesManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Специализированный логгер для диагностики разрешений и работы приложения
 */
class DiagnosticLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "DiagnosticLogger"
        private const val DIAGNOSTIC_LOG_FILE = "diagnostic_log.txt"
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val preferencesManager = PreferencesManager(context)
    
    /**
     * Записывает полную диагностику системы
     */
    fun logFullDiagnostic() {
        val timestamp = dateFormat.format(Date())
        val diagnostic = buildString {
            appendLine("=== ПОЛНАЯ ДИАГНОСТИКА СИСТЕМЫ ===")
            appendLine("Время: $timestamp")
            appendLine("Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            
            // Проверка разрешений
            appendLine("=== РАЗРЕШЕНИЯ ===")
            appendLine("1. Overlay (поверх других приложений): ${checkOverlayPermission()}")
            appendLine("2. Accessibility (доступность): ${checkAccessibilityPermission()}")
            appendLine("3. Notification (уведомления): ${checkNotificationPermission()}")
            appendLine("4. MediaProjection (захват экрана): ${checkMediaProjectionPermission()}")
            appendLine()
            
            // Проверка MediaProjection детально
            appendLine("=== ДЕТАЛИ MEDIA PROJECTION ===")
            appendMediaProjectionDiagnostic()
            appendLine()
            
            // Проверка служб
            appendLine("=== СОСТОЯНИЕ СЛУЖБ ===")
            appendServiceDiagnostic()
            appendLine()
            
            // Проверка настроек приложения
            appendLine("=== НАСТРОЙКИ ПРИЛОЖЕНИЯ ===")
            appendPreferencesDiagnostic()
            appendLine()
            
            appendLine("=== КОНЕЦ ДИАГНОСТИКИ ===")
        }
        
        Log.d(TAG, diagnostic)
        saveToFile(diagnostic)
    }
    
    /**
     * Логирует событие нажатия кнопки Start
     */
    fun logStartButtonClick() {
        val timestamp = dateFormat.format(Date())
        val message = buildString {
            appendLine("[$timestamp] НАЖАТИЕ КНОПКИ START")
            appendLine("Overlay разрешение: ${checkOverlayPermission()}")
            appendLine("MediaProjection разрешение: ${checkMediaProjectionPermission()}")
            appendLine("MediaProjection менеджер hasPermission(): ${MediaProjectionPermissionManager.getInstance(context).hasPermission()}")
            appendLine("---")
        }
        
        Log.d(TAG, message)
        writeToFile(message)
    }
    
    /**
     * Логирует предоставление разрешения MediaProjection
     */
    fun logMediaProjectionGranted(resultCode: Int, data: Intent?) {
        val timestamp = dateFormat.format(Date())
        val message = buildString {
            appendLine("[$timestamp] MEDIA PROJECTION РАЗРЕШЕНИЕ ПРЕДОСТАВЛЕНО")
            appendLine("Result Code: $resultCode")
            appendLine("Data not null: ${data != null}")
            appendLine("Data extras: ${data?.extras?.keySet()?.joinToString()}")
            appendLine("После предоставления hasPermission(): ${MediaProjectionPermissionManager.getInstance(context).hasPermission()}")
            appendLine("---")
        }
        
        Log.d(TAG, message)
        writeToFile(message)
    }
    
    /**
     * Логирует попытку запуска двойного режима
     */
    fun logDualModeStart() {
        val timestamp = dateFormat.format(Date())
        val message = buildString {
            appendLine("[$timestamp] ПОПЫТКА ЗАПУСКА ДВОЙНОГО РЕЖИМА")
            appendLine("Все разрешения проверены перед запуском:")
            appendLine("- Overlay: ${checkOverlayPermission()}")
            appendLine("- Accessibility: ${checkAccessibilityPermission()}")
            appendLine("- MediaProjection: ${checkMediaProjectionPermission()}")
            appendLine("- MediaProjection Manager: ${MediaProjectionPermissionManager.getInstance(context).hasPermission()}")
            appendLine("---")
        }
        
        Log.d(TAG, message)
        writeToFile(message)
    }
    
    /**
     * Экспортирует логи для отправки пользователем
     */
    fun exportLogs(): File? {
        try {
            val exportFile = File(context.getExternalFilesDir(null), "diagnostic_export_${System.currentTimeMillis()}.txt")
            
            // Добавляем полную диагностику в начало
            logFullDiagnostic()
            
            // Копируем существующий лог
            val logFile = File(context.filesDir, DIAGNOSTIC_LOG_FILE)
            if (logFile.exists()) {
                logFile.copyTo(exportFile, overwrite = true)
            } else {
                exportFile.writeText("Лог файл не найден. Создан новый файл с диагностикой.\n\n")
                logFullDiagnostic()
                val newLogFile = File(context.filesDir, DIAGNOSTIC_LOG_FILE)
                if (newLogFile.exists()) {
                    newLogFile.copyTo(exportFile, overwrite = true)
                }
            }
            
            Log.d(TAG, "Логи экспортированы в: ${exportFile.absolutePath}")
            return exportFile
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка экспорта логов", e)
            return null
        }
    }
    
    // === PRIVATE METHODS ===
    
    private fun checkOverlayPermission(): String {
        return if (Settings.canDrawOverlays(context)) "✅ РАЗРЕШЕНО" else "❌ НЕ РАЗРЕШЕНО"
    }
    
    private fun checkAccessibilityPermission(): String {
        val enabledAccessibility = Settings.Secure.getString(
            context.contentResolver, 
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        
        val isEnabled = enabledAccessibility.contains("${context.packageName}/${AutoClickService::class.java.canonicalName}")
        return if (isEnabled) "✅ ВКЛЮЧЕНО" else "❌ НЕ ВКЛЮЧЕНО"
    }
    
    private fun checkNotificationPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) "✅ РАЗРЕШЕНО" else "❌ НЕ РАЗРЕШЕНО"
        } else {
            "✅ НЕ ТРЕБУЕТСЯ (API < 33)"
        }
    }
    
    private fun checkMediaProjectionPermission(): String {
        val manager = MediaProjectionPermissionManager.getInstance(context)
        return if (manager.hasPermission()) "✅ ЕСТЬ" else "❌ НЕТ"
    }
    
    private fun StringBuilder.appendMediaProjectionDiagnostic() {
        val manager = MediaProjectionPermissionManager.getInstance(context)
        val prefsManager = PreferencesManager(context)
        
        appendLine("MediaProjectionPermissionManager.hasPermission(): ${manager.hasPermission()}")
        appendLine("PreferencesManager.hasMediaProjectionPermission(): ${prefsManager.hasMediaProjectionPermission()}")
        
        // Детальная диагностика компонентов
        try {
            val prefs = context.getSharedPreferences("DiceAutoBetPrefs", Context.MODE_PRIVATE)
            val resultCode = prefs.getInt("media_projection_result_code", -1)
            val isAvailable = prefs.getBoolean("media_projection_available", false)
            val tokenStoreHasData = com.example.diceautobet.utils.MediaProjectionTokenStore.get() != null
            
            appendLine("Детальная диагностика:")
            appendLine("- Сохраненный result_code: $resultCode")
            appendLine("- Флаг available в настройках: $isAvailable")
            appendLine("- Есть сохраненные data: $tokenStoreHasData")
            appendLine("- TokenStore содержит Intent: $tokenStoreHasData")
            
            if (resultCode == -1 && !isAvailable && !tokenStoreHasData) {
                appendLine("❌ ПРОБЛЕМА: Разрешение вообще не было сохранено!")
            } else if (resultCode != -1 && isAvailable && !tokenStoreHasData) {
                appendLine("⚠️ ПРОБЛЕМА: Настройки есть, но TokenStore пустой (приложение было перезапущено)")
            }
            
            if (Build.VERSION.SDK_INT >= 35) {
                appendLine("⚠️ Android 15+ (API 35+): MediaProjection может требовать повторного запроса")
            }
            
        } catch (e: Exception) {
            appendLine("Ошибка детальной диагностики: ${e.message}")
        }
    }
    
    private fun StringBuilder.appendServiceDiagnostic() {
        // Здесь можно добавить проверку статуса различных служб
        appendLine("AutoClickService: ${if (isAccessibilityServiceEnabled()) "АКТИВЕН" else "НЕАКТИВЕН"}")
        appendLine("Overlay Service: Проверка через overlay permission")
    }
    
    private fun StringBuilder.appendPreferencesDiagnostic() {
        appendLine("Dual mode enabled: ${preferencesManager.isDualModeEnabled()}")
        // Можно добавить другие важные настройки
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledAccessibility = Settings.Secure.getString(
            context.contentResolver, 
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        
        return enabledAccessibility.contains("${context.packageName}/${AutoClickService::class.java.canonicalName}")
    }
    
    private fun saveToFile(content: String) {
        try {
            val file = File(context.filesDir, DIAGNOSTIC_LOG_FILE)
            FileWriter(file, false).use { writer ->
                writer.write(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка записи в файл", e)
        }
    }
    
    private fun writeToFile(content: String) {
        try {
            val file = File(context.filesDir, DIAGNOSTIC_LOG_FILE)
            FileWriter(file, true).use { writer ->
                writer.write(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка дозаписи в файл", e)
        }
    }
    
    // Публичный метод для внешнего использования
    fun appendToFile(message: String) {
        writeToFile(message)
    }
}
