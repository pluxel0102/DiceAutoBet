package com.example.diceautobet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.example.diceautobet.services.AreaConfigurationService
import com.example.diceautobet.utils.PreferencesManager
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import com.example.diceautobet.logging.DiagnosticLogger

class MediaProjectionRequestActivity : Activity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val MAX_RETRY_COUNT = 3
        const val EXTRA_TARGET_SERVICE = "target_service"
        const val SERVICE_AREA_CONFIG = "area_config"
        const val SERVICE_DUAL_MODE = "dual_mode"
    }

    private lateinit var prefsManager: PreferencesManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var diagnosticLogger: DiagnosticLogger
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MediaProjectionRequest", "Активность создана")
        Log.d("MediaProjectionRequest", "🤖 Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        
        prefsManager = PreferencesManager(this)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        diagnosticLogger = DiagnosticLogger(this)
        
        // Записываем диагностику запроса
        diagnosticLogger.appendToFile("[MEDIA_PROJECTION_REQUEST] Активность создана на Android ${Build.VERSION.RELEASE}\n")
        
        // Запрашиваем разрешение с небольшой задержкой
        handler.postDelayed({
            requestMediaProjectionPermission()
        }, 100)
    }

    private fun requestMediaProjectionPermission() {
        try {
            Log.d("MediaProjectionRequest", "Запрашиваем разрешение на захват экрана")
            val captureIntent = projectionManager.createScreenCaptureIntent()
            Log.d("MediaProjectionRequest", "Intent создан: $captureIntent")
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
            Log.d("MediaProjectionRequest", "Активность для запроса разрешения запущена")
        } catch (e: Exception) {
            Log.e("MediaProjectionRequest", "Ошибка при запросе разрешения", e)
            if (retryCount < MAX_RETRY_COUNT) {
                retryCount++
                handler.postDelayed({
                    requestMediaProjectionPermission()
                }, 500)
            } else {
                Toast.makeText(this, "Не удалось запросить разрешение на захват экрана", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("MediaProjectionRequest", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data")
        Log.d("MediaProjectionRequest", "🔍 Анализ результата: RESULT_OK=${Activity.RESULT_OK}, RESULT_CANCELED=${Activity.RESULT_CANCELED}")
        
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            // Записываем диагностику результата (независимо от успеха/неудачи)
            diagnosticLogger.appendToFile("[MEDIA_PROJECTION_RESULT] resultCode=$resultCode, success=${resultCode == Activity.RESULT_OK}\n")
            
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d("MediaProjectionRequest", "✅ Разрешение получено УСПЕШНО (resultCode=${Activity.RESULT_OK})")
                
                // Записываем диагностику получения разрешения
                diagnosticLogger.logMediaProjectionGranted(resultCode, data)
                
                Log.d("MediaProjectionRequest", "📊 Получены данные разрешения:")
                Log.d("MediaProjectionRequest", "   - resultCode: $resultCode")
                Log.d("MediaProjectionRequest", "   - data не null: ${data != null}")
                Log.d("MediaProjectionRequest", "   - data.extras: ${data.extras?.keySet()?.joinToString()}")
                
                // КРИТИЧНО: Для всех Android версий запускаем foreground service СРАЗУ
                Log.d("MediaProjectionRequest", "🚀 Android ${Build.VERSION.SDK_INT} - запускаем foreground service СРАЗУ")
                startForegroundServiceImmediately(resultCode, data)
                
                // НЕ создаем MediaProjection здесь - это сделает foreground service!
                // Сохраняем только данные разрешения
                try {
                    Log.d("MediaProjectionRequest", "� Сохраняем данные разрешения (БЕЗ создания MediaProjection)...")
                    // Сохраняем в TokenStore и PreferencesManager напрямую
                    com.example.diceautobet.utils.MediaProjectionTokenStore.set(data)
                    prefsManager.saveMediaProjectionPermission(resultCode, data)
                    Log.d("MediaProjectionRequest", "✅ Данные разрешения сохранены")
                } catch (e: Exception) {
                    Log.e("MediaProjectionRequest", "❌ Ошибка сохранения данных разрешения", e)
                }
                
                // Сохраняем разрешение через центральный менеджер
                try {
                    Log.d("MediaProjectionRequest", "💾 Сохраняем через MediaProjectionPermissionManager...")
                    MediaProjectionPermissionManager.getInstance(this).savePermission(resultCode, data)
                    Log.d("MediaProjectionRequest", "✅ Разрешение сохранено через MediaProjectionPermissionManager")
                } catch (e: Exception) {
                    Log.e("MediaProjectionRequest", "❌ Ошибка сохранения через менеджер, fallback на прямое сохранение", e)
                    // Fallback: сохраняем в процессном сторе и preferences
                    Log.d("MediaProjectionRequest", "🔄 Fallback: сохраняем напрямую...")
                    com.example.diceautobet.utils.MediaProjectionTokenStore.set(data)
                    prefsManager.saveMediaProjectionPermission(resultCode, data)
                    Log.d("MediaProjectionRequest", "✅ Fallback сохранение завершено")
                }
                
                try {
                    // Определяем, какой сервис запустить
                    val targetService = intent.getStringExtra(EXTRA_TARGET_SERVICE) ?: SERVICE_AREA_CONFIG
                    
                    when (targetService) {
                        SERVICE_DUAL_MODE -> {
                            // Отправляем broadcast для уведомления UI
                            Log.d("MediaProjectionRequest", "Разрешение для двойного режима получено, отправляем broadcast")
                            val broadcastIntent = Intent("com.example.diceautobet.PERMISSION_GRANTED")
                            broadcastIntent.putExtra("service_type", "dual_mode")
                            broadcastIntent.setPackage(packageName)
                            sendBroadcast(broadcastIntent)
                            
                            Toast.makeText(this, "✅ Сервис запущен! Можете начинать игру", Toast.LENGTH_LONG).show()
                        }
                        SERVICE_AREA_CONFIG -> {
                            // Запускаем AreaConfigurationService (только для конфигурации областей)
                            Log.d("MediaProjectionRequest", "Запускаем AreaConfigurationService с разрешениями: resultCode=$resultCode, data=$data")
                            AreaConfigurationService.start(this, resultCode, data)
                            Toast.makeText(this, "Разрешение получено! Запускаем настройку областей", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MediaProjectionRequest", "❌ Ошибка при запуске сервиса", e)
                    Toast.makeText(this, "Ошибка при запуске: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                // Разрешение отклонено или произошла ошибка
                Log.w("MediaProjectionRequest", "❌ Разрешение НЕ получено: resultCode=$resultCode")
                Log.w("MediaProjectionRequest", "🔍 Детали:")
                Log.w("MediaProjectionRequest", "   - RESULT_OK = ${Activity.RESULT_OK}")
                Log.w("MediaProjectionRequest", "   - RESULT_CANCELED = ${Activity.RESULT_CANCELED}")
                Log.w("MediaProjectionRequest", "   - data != null: ${data != null}")
                
                // Записываем диагностику отклонения
                diagnosticLogger.appendToFile("[MEDIA_PROJECTION_DENIED] resultCode=$resultCode, пользователь отклонил разрешение\n")
                
                // ВАЖНО: НЕ сохраняем никаких данных при отклонении!
                Log.d("MediaProjectionRequest", "🧹 Очищаем все сохраненные данные разрешений...")
                prefsManager.clearMediaProjectionPermission()
                com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
                Log.d("MediaProjectionRequest", "✅ Данные разрешений очищены")
                
                val message = when (resultCode) {
                    Activity.RESULT_CANCELED -> "Разрешение на захват экрана отклонено пользователем"
                    else -> "Не удалось получить разрешение на захват экрана (код: $resultCode)"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            
            // Небольшая задержка перед завершением активности
            handler.postDelayed({
                finish()
            }, 500)
        }
    }

    /**
     * КРИТИЧНО для Android 15+: Тестируем MediaProjection немедленно
     */
    private fun testMediaProjectionImmediately(resultCode: Int, data: Intent): Boolean {
        return try {
            Log.d("MediaProjectionRequest", "🧪 Тестируем создание MediaProjection немедленно...")
            val testProjection = projectionManager.getMediaProjection(resultCode, data)
            val isValid = testProjection != null
            
            if (isValid) {
                Log.d("MediaProjectionRequest", "✅ MediaProjection тест УСПЕШЕН")
                testProjection?.stop() // Останавливаем тестовый MediaProjection
            } else {
                Log.e("MediaProjectionRequest", "❌ MediaProjection тест ПРОВАЛЕН - не удалось создать")
            }
            
            isValid
        } catch (e: Exception) {
            Log.e("MediaProjectionRequest", "❌ MediaProjection тест ПРОВАЛЕН с ошибкой", e)
            false
        }
    }

    /**
     * КРИТИЧНО: Запуск foreground service немедленно для всех Android версий
     */
    private fun startForegroundServiceImmediately(resultCode: Int, data: Intent) {
        try {
            val targetService = intent.getStringExtra(EXTRA_TARGET_SERVICE) ?: SERVICE_AREA_CONFIG
            
            // ВСЕГДА запускаем DualModeService как foreground для MediaProjection
            val serviceIntent = Intent(this, com.example.diceautobet.services.DualModeService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("immediate_start", true)
                putExtra("target_service", targetService) // Передаём тип сервиса
                action = com.example.diceautobet.services.DualModeService.ACTION_START_FOREGROUND
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d("MediaProjectionRequest", "✅ DualModeService запущен как foreground service для $targetService")
        } catch (e: Exception) {
            Log.e("MediaProjectionRequest", "❌ Ошибка запуска foreground service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
} 