package com.example.diceautobet.managers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.example.diceautobet.utils.PreferencesManager

/**
 * Централизованный менеджер разрешений MediaProjection.
 * Обеспечивает запрос разрешения только один раз и его переиспользование.
 */
class MediaProjectionPermissionManager private constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "MediaProjectionPermissionManager"
        
        @Volatile
        private var INSTANCE: MediaProjectionPermissionManager? = null
        
        fun getInstance(context: Context): MediaProjectionPermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaProjectionPermissionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val projectionManager: MediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    private val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context)
    }
    
    // Кэшированное разрешение
    private var cachedMediaProjection: MediaProjection? = null
    private var cachedResultCode: Int? = null
    private var cachedData: Intent? = null
    
    init {
        // При создании менеджера очищаем старые невалидные данные
        cleanupInvalidPermissions()
    }
    
    /**
     * Очищает невалидные разрешения при запуске
     */
    private fun cleanupInvalidPermissions() {
        Log.d(TAG, "🧹 Проверка и очистка невалидных разрешений при запуске...")
        
        val resultCode = context.getSharedPreferences("DiceAutoBetPrefs", Context.MODE_PRIVATE)
            .getInt("media_projection_result_code", -1)
        
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "🗑️ Обнаружен невалидный resultCode=$resultCode, очищаем...")
            
            // Очищаем все источники данных
            cachedMediaProjection = null
            cachedResultCode = null  
            cachedData = null
            preferencesManager.clearMediaProjectionPermission()
            com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
            
            Log.d(TAG, "✅ Невалидные данные очищены при запуске")
        } else {
            Log.d(TAG, "✅ Сохраненный resultCode валиден ($resultCode)")
        }
    }
    
    /**
     * Проверяет, есть ли сохраненное разрешение
     */
    fun hasPermission(): Boolean {
        val hasStoredPermission = preferencesManager.hasMediaProjectionPermission()
        val hasCachedData = cachedResultCode != null && cachedData != null
        val hasCachedMediaProjection = cachedMediaProjection != null
        
        // Дополнительная диагностика
        val tokenStoreHasData = com.example.diceautobet.utils.MediaProjectionTokenStore.get() != null
        val prefsResultCode = context.getSharedPreferences("DiceAutoBetPrefs", Context.MODE_PRIVATE)
            .getInt("media_projection_result_code", -1)
        val prefsAvailable = context.getSharedPreferences("DiceAutoBetPrefs", Context.MODE_PRIVATE)
            .getBoolean("media_projection_available", false)
        
        Log.d(TAG, "📋 Детальная проверка разрешения:")
        Log.d(TAG, "   📁 В PreferencesManager: $hasStoredPermission")
        Log.d(TAG, "   💾 Данные разрешения в кэше: $hasCachedData")
        Log.d(TAG, "   🎯 MediaProjection в кэше: $hasCachedMediaProjection")
        Log.d(TAG, "   🔢 cachedResultCode: $cachedResultCode")
        Log.d(TAG, "   📄 cachedData: ${cachedData != null}")
        Log.d(TAG, "   🗃️ TokenStore имеет данные: $tokenStoreHasData")
        Log.d(TAG, "   📊 Prefs result_code: $prefsResultCode")
        Log.d(TAG, "   📊 Prefs available: $prefsAvailable")
        Log.d(TAG, "   🤖 Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        
        // На Android 15+ могут быть дополнительные ограничения
        if (Build.VERSION.SDK_INT >= 35) { // Android 15 = API 35
            Log.d(TAG, "   ⚠️ Android 15+ обнаружен - возможны дополнительные ограничения MediaProjection")
        }
        
        // ИСПРАВЛЕННАЯ ЛОГИКА: 
        // Разрешение есть только если:
        // 1. Есть кэшированные данные (в памяти) С ВАЛИДНЫМ resultCode ИЛИ
        // 2. Есть активный MediaProjection ИЛИ 
        // 3. Есть данные в PreferencesManager И в TokenStore С ВАЛИДНЫМ resultCode
        
        // Проверяем кэшированные данные - они валидны только если resultCode == RESULT_OK
        val hasValidCachedData = (hasCachedData && cachedResultCode == Activity.RESULT_OK) || hasCachedMediaProjection
        
        // Проверяем сохраненные данные - они валидны только если resultCode == RESULT_OK
        val hasValidStoredData = hasStoredPermission && tokenStoreHasData && (prefsResultCode == Activity.RESULT_OK)
        
        val result = hasValidCachedData || hasValidStoredData
        
        Log.d(TAG, "🔧 Детальная логика:")
        Log.d(TAG, "   🎯 Валидные кэшированные данные: $hasValidCachedData (cachedResultCode=$cachedResultCode, нужен ${Activity.RESULT_OK})")
        Log.d(TAG, "   📁 Валидные сохраненные данные: $hasValidStoredData (prefsResultCode=$prefsResultCode, нужен ${Activity.RESULT_OK})") 
        Log.d(TAG, "✅ Итоговый результат hasPermission(): $result")
        
        return result
    }
    
    /**
     * Создает Intent для запроса разрешения
     */
    fun createScreenCaptureIntent(): Intent {
        Log.d(TAG, "Создание Intent для запроса разрешения на захват экрана")
        return projectionManager.createScreenCaptureIntent()
    }
    
    /**
     * Сохраняет полученное разрешение
     */
    fun savePermission(resultCode: Int, data: Intent) {
        Log.d(TAG, "💾 Сохранение разрешения MediaProjection (resultCode=$resultCode)")
        Log.d(TAG, "🤖 Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "🔍 Анализ resultCode: RESULT_OK=${Activity.RESULT_OK}, RESULT_CANCELED=${Activity.RESULT_CANCELED}")
        
        // КРИТИЧЕСКАЯ ПРОВЕРКА: сохраняем только при успешном получении разрешения
        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "❌ ОТКЛОНЕНИЕ СОХРАНЕНИЯ: resultCode=$resultCode (не равен RESULT_OK=${Activity.RESULT_OK})")
            Log.e(TAG, "🧹 Очищаем все кэшированные данные...")
            
            // Очищаем кэш при неуспешном resultCode
            cachedMediaProjection = null
            cachedResultCode = null
            cachedData = null
            
            // ВАЖНО: Очищаем TokenStore при невалидном resultCode
            com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
            Log.d(TAG, "🗃️ TokenStore очищен")
            
            // Очищаем сохраненные данные
            preferencesManager.clearMediaProjectionPermission()
            
            Log.w(TAG, "⚠️ Разрешение НЕ сохранено из-за неуспешного resultCode")
            return
        }
        
        try {
            Log.d(TAG, "✅ ResultCode валиден (${Activity.RESULT_OK}), продолжаем сохранение...")
            
            // ВНИМАНИЕ: НЕ создаем MediaProjection здесь!
            // MediaProjection должен создаваться ТОЛЬКО внутри foreground service
            Log.d(TAG, "📋 Сохраняем данные разрешения БЕЗ создания MediaProjection")
            Log.d(TAG, "⚠️ MediaProjection будет создан позже внутри foreground service")
            
            // Кэшируем данные разрешения (MediaProjection создастся в foreground service)
            cachedResultCode = resultCode
            cachedData = data
            cachedMediaProjection = null // НЕ создаем здесь!
            
            Log.d(TAG, "📋 Данные разрешения закэшированы: resultCode=$resultCode, data=${data != null}")
            
            // Сохраняем в настройки
            preferencesManager.saveMediaProjectionPermission(resultCode, data)
            Log.d(TAG, "📁 Данные сохранены в PreferencesManager")
            
            // Добавляем дополнительную диагностику для всех версий Android
            Log.d(TAG, "🔍 Проверка сохранения для Android ${Build.VERSION.SDK_INT}...")
            val verificationData = preferencesManager.getMediaProjectionPermission()
            val verificationHasPermission = preferencesManager.hasMediaProjectionPermission()
            Log.d(TAG, "✅ Верификация сохранения: data=${verificationData != null}, hasPermission=$verificationHasPermission")
            
            // Специальное примечание для всех версий Android
            Log.d(TAG, "⚠️ MediaProjection будет создан при запуске foreground service")
            
            Log.d(TAG, "✅ Разрешение MediaProjection успешно сохранено (MediaProjection будет создан при запуске foreground service)")
            
            // Проверяем, что hasPermission теперь возвращает true
            val hasPermissionNow = hasPermission()
            Log.d(TAG, "🔍 Проверка после сохранения: hasPermission=$hasPermissionNow")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при сохранении разрешения MediaProjection", e)
            // Очищаем кэш при ошибке
            cachedMediaProjection = null
            cachedResultCode = null
            cachedData = null
        }
    }
    
    /**
     * Получает MediaProjection из сохраненных данных
     */
    fun getMediaProjection(): MediaProjection? {
        // Сначала пробуем кэшированный MediaProjection
        if (cachedMediaProjection != null) {
            Log.d(TAG, "🎯 Возвращаем кэшированный MediaProjection")
            return cachedMediaProjection
        }
        
        // Если есть кэшированные данные разрешения, создаем MediaProjection
        if (cachedResultCode != null && cachedData != null) {
            Log.d(TAG, "🔄 Создаем MediaProjection из кэшированных данных разрешения")
            return try {
                val mediaProjection = projectionManager.getMediaProjection(cachedResultCode!!, cachedData!!)
                if (mediaProjection != null) {
                    cachedMediaProjection = mediaProjection
                    Log.d(TAG, "✅ MediaProjection создан и закэширован из данных разрешения")
                } else {
                    Log.w(TAG, "⚠️ Не удалось создать MediaProjection из кэшированных данных - возможно, разрешение истекло")
                    clearPermission()
                }
                mediaProjection
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка при создании MediaProjection из кэшированных данных", e)
                clearPermission()
                null
            }
        }
        
        // Затем пробуем восстановить из настроек
        return try {
            val storedData = preferencesManager.getMediaProjectionPermission()
            if (storedData != null) {
                Log.d(TAG, "🔄 Восстанавливаем MediaProjection из сохраненных настроек")
                val (resultCode, intent) = storedData
                val mediaProjection = projectionManager.getMediaProjection(resultCode, intent)
                
                if (mediaProjection != null) {
                    // Кэшируем восстановленные данные
                    cachedMediaProjection = mediaProjection
                    cachedResultCode = resultCode
                    cachedData = intent
                    
                    Log.d(TAG, "✅ MediaProjection успешно восстановлен")
                } else {
                    Log.w(TAG, "⚠️ Не удалось восстановить MediaProjection - возможно, разрешение истекло")
                    clearPermission()
                }
                
                mediaProjection
            } else {
                Log.d(TAG, "❌ Нет сохраненных данных MediaProjection")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при восстановлении MediaProjection", e)
            clearPermission()
            null
        }
    }
    
    /**
     * Получает сохраненные resultCode и Intent для создания нового MediaProjection
     */
    fun getPermissionData(): Pair<Int, Intent>? {
        // Сначала пробуем кэшированные данные
        if (cachedResultCode != null && cachedData != null) {
            Log.d(TAG, "🎯 Возвращаем кэшированные данные разрешения")
            return Pair(cachedResultCode!!, cachedData!!)
        }
        
        // Затем пробуем восстановить из настроек
        return try {
            val storedData = preferencesManager.getMediaProjectionPermission()
            if (storedData != null) {
                Log.d(TAG, "🔄 Возвращаем сохраненные данные разрешения")
                val (resultCode, intent) = storedData
                // Кэшируем восстановленные данные
                cachedResultCode = resultCode
                cachedData = intent
                storedData
            } else {
                Log.d(TAG, "❌ Нет сохраненных данных разрешения")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при получении данных разрешения", e)
            null
        }
    }
    
    /**
     * Очищает сохраненное разрешение (при ошибках или истечении)
     */
    fun clearPermission() {
        Log.d(TAG, "🧹 Очистка сохраненного разрешения MediaProjection")
        
        try {
            cachedMediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка при остановке MediaProjection", e)
        }
        
        cachedMediaProjection = null
        cachedResultCode = null
        cachedData = null
        
        preferencesManager.clearMediaProjectionPermission()
    }
    
    /**
     * Проверяет, нужно ли перезапросить разрешение (например, на Android 15+)
     */
    fun shouldRerequestPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 35) { // Android 15+
            // На Android 15+ MediaProjection может требовать повторного запроса
            // через определенное время или после перезапуска приложения
            Log.d(TAG, "🔄 Android 15+ обнаружен - рекомендуется перезапрос разрешения")
            true
        } else {
            false
        }
    }
    
    /**
     * Принудительно синхронизирует состояние между кэшем и PreferencesManager
     */
    fun forceSynchronization(): Boolean {
        Log.d(TAG, "🔄 Принудительная синхронизация менеджеров разрешений")
        
        val hasCachedData = cachedResultCode != null && cachedData != null
        val hasStoredPermission = preferencesManager.hasMediaProjectionPermission()
        
        Log.d(TAG, "   💾 Данные в кэше: $hasCachedData (resultCode: $cachedResultCode)")
        Log.d(TAG, "   📁 Данные в настройках: $hasStoredPermission")
        
        // Если есть кэшированные данные, но нет сохраненных - пересохраняем
        if (hasCachedData && !hasStoredPermission && cachedResultCode != null && cachedData != null) {
            Log.d(TAG, "🔧 Обнаружена рассинхронизация - пересохраняем данные")
            try {
                preferencesManager.saveMediaProjectionPermission(cachedResultCode!!, cachedData!!)
                val newHasStoredPermission = preferencesManager.hasMediaProjectionPermission()
                Log.d(TAG, "✅ Пересохранение завершено: $newHasStoredPermission")
                return newHasStoredPermission
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка при пересохранении", e)
                return false
            }
        }
        
        Log.d(TAG, "✅ Синхронизация не требуется или завершена")
        return hasStoredPermission || hasCachedData
    }
    
    /**
     * Проверяет валидность сохраненного разрешения
     */
    fun validatePermission(): Boolean {
        return try {
            val mediaProjection = getMediaProjection()
            if (mediaProjection == null) {
                Log.w(TAG, "⚠️ MediaProjection не найден, разрешение невалидно")
                clearPermission()
                return false
            }
            
            // Пробуем создать виртуальный дисплей для проверки валидности
            try {
                val testDisplay = mediaProjection.createVirtualDisplay(
                    "ValidationTest",
                    1, 1, 1,
                    0, // Флаги для тестирования
                    null,
                    null,
                    null
                )
                testDisplay?.release() // Сразу освобождаем
                Log.d(TAG, "✅ Разрешение MediaProjection валидно")
                return true
            } catch (e: SecurityException) {
                if (e.message?.contains("Invalid media projection") == true) {
                    Log.w(TAG, "⚠️ Сохраненное разрешение невалидно (Invalid media projection), очищаем")
                    clearPermission()
                    return false
                }
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при проверке валидности разрешения", e)
            clearPermission()
            false
        }
    }

    /**
     * КРИТИЧНО для Android 15+: Тестирует MediaProjection немедленно
     */
    fun testMediaProjectionImmediately(resultCode: Int, data: Intent): Boolean {
        return try {
            Log.d(TAG, "🧪 Тестируем создание MediaProjection для Android ${Build.VERSION.SDK_INT}...")
            
            val testProjection = projectionManager.getMediaProjection(resultCode, data)
            val isValid = testProjection != null
            
            if (isValid) {
                Log.d(TAG, "✅ MediaProjection тест УСПЕШЕН")
                // Для Android 15+ НЕ останавливаем тестовый MediaProjection - он нужен!
                if (Build.VERSION.SDK_INT < 35) {
                    testProjection?.stop() // На старых версиях можно остановить
                }
            } else {
                Log.e(TAG, "❌ MediaProjection тест ПРОВАЛЕН - не удалось создать")
            }
            
            Log.d(TAG, "🔍 Результат теста MediaProjection: ${if (isValid) "✅ УСПЕШНО" else "❌ ПРОВАЛ"}")
            return isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ MediaProjection тест ПРОВАЛЕН с ошибкой", e)
            false
        }
    }

    /**
     * Устанавливает готовый MediaProjection (созданный внутри foreground service)
     */
    fun setCachedMediaProjection(mediaProjection: MediaProjection) {
        Log.d(TAG, "🎯 Устанавливаем готовый MediaProjection в кэш")
        cachedMediaProjection = mediaProjection
        Log.d(TAG, "✅ MediaProjection установлен в кэш")
    }
}
