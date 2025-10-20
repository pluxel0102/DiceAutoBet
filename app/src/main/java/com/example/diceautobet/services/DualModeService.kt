package com.example.diceautobet.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.diceautobet.R
import com.example.diceautobet.controllers.SimpleDualModeController
import com.example.diceautobet.game.ClickManager
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.models.SimpleDualModeState
import com.example.diceautobet.utils.PreferencesManager

/**
 * Упрощенный сервис двойного режима
 */
class DualModeService : Service() {
    
    companion object {
        private const val TAG = "DualModeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "DualModeServiceChannel"
    const val ACTION_START_FOREGROUND = "com.example.diceautobet.dual.START_FOREGROUND"
    const val ACTION_STOP_FOREGROUND = "com.example.diceautobet.dual.STOP_FOREGROUND"
    }
    
    private val binder = LocalBinder()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var controller: SimpleDualModeController
    
    inner class LocalBinder : Binder() {
        fun getService(): DualModeService = this@DualModeService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Создание DualModeService")
        
        preferencesManager = PreferencesManager(this)
        val clickManager = ClickManager(preferencesManager)
        val areaManager = DualWindowAreaManager(this)
        
        controller = SimpleDualModeController(
            context = this,
            clickManager = clickManager,
            preferencesManager = preferencesManager,
            areaManager = areaManager
        )
        
        // Инициализируем обработчик ошибок
        controller.onError = { errorMessage ->
            Log.e(TAG, "Ошибка контроллера: $errorMessage")
        }
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // Проверяем, есть ли немедленное разрешение MediaProjection
        val immediateStart = intent?.getBooleanExtra("immediate_start", false) ?: false
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        
        // КРИТИЧНО: Foreground статус включаем СРАЗУ, ДО создания MediaProjection
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "✅ Foreground service запущен для MediaProjection")
                
                // Теперь, когда foreground service активен, можно создавать MediaProjection
                if (immediateStart) {
                    Log.d(TAG, "🚨 Создание MediaProjection после запуска foreground: resultCode=$resultCode")
                    if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                        Log.d(TAG, "✅ Получены валидные данные MediaProjection для немедленного использования")
                        
                        // КРИТИЧНО: Создаем MediaProjection ПОСЛЕ startForeground()
                        try {
                            Log.d(TAG, "🎯 Создаем MediaProjection внутри активного foreground service...")
                            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                            
                            if (mediaProjection != null) {
                                Log.d(TAG, "✅ MediaProjection успешно создан внутри foreground service!")
                                
                                // Сохраняем через менеджер
                                val permissionManager = com.example.diceautobet.managers.MediaProjectionPermissionManager.getInstance(this)
                                // Устанавливаем созданный MediaProjection в менеджер
                                permissionManager.setCachedMediaProjection(mediaProjection)
                                permissionManager.savePermission(resultCode, data)
                                
                                Log.d(TAG, "✅ MediaProjection сохранен в менеджере")
                            } else {
                                Log.e(TAG, "❌ Не удалось создать MediaProjection внутри foreground service")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка создания MediaProjection внутри foreground service", e)
                        }
                    }
                }
            }
            ACTION_STOP_FOREGROUND -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dual Mode Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис двойного режима"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DiceAutoBet")
            .setContentText("Сервис двойного режима работает")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Уничтожение DualModeService")
        controller.stopDualMode()
        stopForeground(true)
    }
    
    // Методы для управления
    fun startDualMode() {
        Log.d(TAG, "startDualMode() вызван")
        // Переводим в foreground только при реальном старте режима
        try {
            Log.d(TAG, "Запускаем foreground уведомление")
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground уведомление запущено успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска foreground уведомления", e)
        }
        
        Log.d(TAG, "Запускаем controller.startDualMode()")
        try {
            controller.startDualMode()
            Log.d(TAG, "controller.startDualMode() завершен успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в controller.startDualMode()", e)
        }
    }
    
    fun stopDualMode() {
        Log.d(TAG, "stopDualMode() вызван")
        
        Log.d(TAG, "Останавливаем controller.stopDualMode()")
        try {
            controller.stopDualMode()
            Log.d(TAG, "controller.stopDualMode() завершен успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в controller.stopDualMode()", e)
        }
        
        // Снимаем foreground, чтобы не создавать ощущение фоновой работы
        Log.d(TAG, "Останавливаем foreground уведомление")
        try { 
            stopForeground(STOP_FOREGROUND_REMOVE) 
            Log.d(TAG, "Foreground уведомление остановлено успешно")
        } catch (e: Exception) { 
            Log.e(TAG, "Ошибка остановки foreground уведомления", e)
        }
    }
    
    fun isRunning(): Boolean {
        return controller.getCurrentState().isRunning
    }
    
    fun getCurrentState(): SimpleDualModeState {
        return controller.getCurrentState()
    }
    
    fun getStatisticsText(): String {
        return controller.getStatistics()
    }
    
    fun getSimulatorInfo(): String {
        return "Упрощенный симулятор: минимальная ставка 10, максимальная 2500"
    }
    
    /**
     * Обновляет данные MediaProjection в контроллере
     */
    fun updateMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "updateMediaProjection вызван с resultCode=$resultCode")
        try {
            controller.updateMediaProjection(resultCode, data)
            Log.d(TAG, "MediaProjection данные переданы в контроллер успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при передаче MediaProjection данных в контроллер", e)
        }
    }
}