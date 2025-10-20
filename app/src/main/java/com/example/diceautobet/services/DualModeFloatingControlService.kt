package com.example.diceautobet.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.diceautobet.R
import com.example.diceautobet.MediaProjectionRequestActivity
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import com.example.diceautobet.logging.DiagnosticLogger

class DualModeFloatingControlService : Service() {

    companion object {
        private const val TAG = "DualFloatingControl"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "dual_mode_floating_channel"
    }

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusTextView: TextView? = null // Добавляем ссылку на TextView статуса
    
    private var dualModeService: DualModeService? = null
    private var bound = false
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var diagnosticLogger: DiagnosticLogger
    private var pendingStart = false // Флаг, что нужно запустить после получения разрешения
    
    private val uiHandler = Handler(Looper.getMainLooper())

    // BroadcastReceiver для получения уведомления о получении разрешения
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.diceautobet.PERMISSION_GRANTED") {
                val serviceType = intent.getStringExtra("service_type")
                if (serviceType == "dual_mode" && pendingStart) {
                    Log.d(TAG, "✅ Получено уведомление о разрешении на захват экрана")
                    pendingStart = false // Сбрасываем флаг ожидания
                    
                    // Показываем уведомление пользователю, что разрешение получено
                    uiHandler.post {
                        Toast.makeText(this@DualModeFloatingControlService, 
                            "✅ Разрешение получено! Теперь можно запускать двойной режим", 
                            Toast.LENGTH_SHORT).show()
                    }
                    
                    Log.d(TAG, "🎯 Разрешение готово. Ожидаем повторного нажатия пользователя для запуска")
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DualModeService.LocalBinder
            dualModeService = binder.getService()
            bound = true
            Log.d(TAG, "✅ Подключился к DualModeService")
        }

        override fun onServiceDisconnected(className: ComponentName) {
            dualModeService = null
            bound = false
            Log.d(TAG, "❌ Отключился от DualModeService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🎨 Создание DualModeFloatingControlService")
        createNotificationChannel()
        
        // Инициализируем PreferencesManager
        preferencesManager = PreferencesManager(this)
        diagnosticLogger = DiagnosticLogger(this)
        
        // Регистрируем BroadcastReceiver для получения уведомления о разрешении
        val filter = IntentFilter("com.example.diceautobet.PERMISSION_GRANTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        
        // Подключаемся к DualModeService
        val intent = Intent(this, DualModeService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 Запуск плавающего управления")
        
        showFloatingControl()
        
        // Создаем уведомление
        val notification = createForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    private fun showFloatingControl() {
        if (overlayView != null) {
            Log.d(TAG, "⚠️ Плавающее управление уже отображается")
            return
        }

        // Проверяем разрешение на overlay
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "❌ Нет разрешения на отображение поверх других приложений")
            Toast.makeText(this, "Нет разрешения на отображение поверх других приложений", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = createFloatingView()

        // Параметры окна
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            Log.d(TAG, "✅ Плавающее управление отображено")
            Toast.makeText(this, "Плавающее управление активировано", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отображения плавающего управления: ${e.message}")
            when {
                e.message?.contains("permission") == true -> {
                    Toast.makeText(this, "Ошибка разрешений для плавающего окна", Toast.LENGTH_LONG).show()
                }
                e.message?.contains("BadTokenException") == true -> {
                    Toast.makeText(this, "Ошибка токена окна (перезапустите приложение)", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(this, "Ошибка создания плавающего окна: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            stopSelf()
        }
    }

    private fun createFloatingView(): View {
        // Используем XML layout вместо программного создания UI
        val inflater = LayoutInflater.from(this)
        val container = inflater.inflate(R.layout.layout_floating_dual_control_main, null)
        
        // Находим кнопки и элементы в layout
        val startButton = container.findViewById<Button>(R.id.btnStart)
        val stopButton = container.findViewById<Button>(R.id.btnStop)
        val hideButton = container.findViewById<Button>(R.id.btnHide)
        val logsButton = container.findViewById<Button>(R.id.btnLogs)
        statusTextView = container.findViewById<TextView>(R.id.textStatus) // Инициализируем TextView статуса
        
        // Устанавливаем начальный статус
        updateStatusReady()
        
        // Назначаем обработчики
        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        hideButton.setOnClickListener { onHideClicked() }
        logsButton.setOnClickListener { onLogsClicked() }
        
        // Добавляем возможность перетаскивания для всего контейнера
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
        
        return container
    }

    private fun onStartClicked() {
        Log.d(TAG, "🎯 Нажата кнопка запуска")
        
        // Записываем диагностику нажатия кнопки
        diagnosticLogger.logStartButtonClick()
        
        // Проверяем наличие разрешения на захват экрана (через централизованный менеджер)
        val mpManager = MediaProjectionPermissionManager.getInstance(this)
        
        // Принудительно синхронизируем менеджеры разрешений
        Log.d(TAG, "🔄 Проверка состояния разрешений...")
        
        // ИСПРАВЛЕНИЕ: Более строгая проверка разрешения
        val hasManagerPermission = mpManager.hasPermission()
        val tokenStoreHasData = com.example.diceautobet.utils.MediaProjectionTokenStore.get() != null
        
        Log.d(TAG, "🔍 Детальная проверка:")
        Log.d(TAG, "   Manager hasPermission: $hasManagerPermission")
        Log.d(TAG, "   TokenStore hasData: $tokenStoreHasData")
        
        val hasValidPermission = hasManagerPermission && tokenStoreHasData
        Log.d(TAG, "✅ Итоговое решение - разрешение валидно: $hasValidPermission")
        
        // На Android 15+ возможны дополнительные проверки
        if (mpManager.shouldRerequestPermission()) {
            Log.d(TAG, "⚠️ Android 15+ требует перезапроса разрешения")
            Toast.makeText(this, "Android 15+ требует повторного предоставления разрешения", Toast.LENGTH_LONG).show()
            requestPermissionForAndroid15()
            return
        }
        
        if (!hasValidPermission) {
            Log.d(TAG, "🔑 Нет валидного разрешения на захват экрана, запрашиваем")
            
            if (!hasManagerPermission) {
                Log.d(TAG, "❌ Причина: MediaProjectionPermissionManager.hasPermission() = false")
            }
            if (!tokenStoreHasData) {
                Log.d(TAG, "❌ Причина: MediaProjectionTokenStore пустой (приложение было перезапущено?)")
            }
            
            diagnosticLogger.logFullDiagnostic() // Записываем полную диагностику
            pendingStart = true // Устанавливаем флаг для запуска после получения разрешения
            requestMediaProjectionPermission()
        } else {
            // Разрешение есть, запускаем двойной режим сразу
            Log.d(TAG, "✅ Разрешение валидно, запускаем сразу")
            startDualModeDirectly()
        }
    }
    
    private fun requestPermissionForAndroid15() {
        Log.d(TAG, "📱 Специальный запрос разрешения для Android 15+")
        pendingStart = true
        
        // Очищаем старое разрешение
        val mpManager = MediaProjectionPermissionManager.getInstance(this)
        mpManager.clearPermission()
        
        // Запрашиваем новое
        requestMediaProjectionPermission()
    }
    
    private fun requestMediaProjectionPermission() {
        try {
            val intent = Intent(this, MediaProjectionRequestActivity::class.java)
            intent.putExtra(MediaProjectionRequestActivity.EXTRA_TARGET_SERVICE, MediaProjectionRequestActivity.SERVICE_DUAL_MODE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "🔑 Предоставьте разрешение, затем нажмите зеленую кнопку снова", Toast.LENGTH_LONG).show()
            Log.d(TAG, "🔑 Запрос разрешения отправлен")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запроса разрешения: ${e.message}")
            Toast.makeText(this, "Ошибка запроса разрешения: ${e.message}", Toast.LENGTH_SHORT).show()
            pendingStart = false
        }
    }

    private fun startDualModeDirectly() {
        Log.d(TAG, "🎯 Прямой запуск двойного режима")
        diagnosticLogger.logDualModeStart() // Записываем диагностику запуска
        
        dualModeService?.let { service ->
            service.startDualMode()
            updateStatusRunning() // Обновляем статус на "Работает"
            Log.d(TAG, "🎯 Команда запуска отправлена")
            Toast.makeText(this, "Запуск двойного режима...", Toast.LENGTH_SHORT).show()
        } ?: run {
            Log.e(TAG, "❌ DualModeService не подключен")
            Toast.makeText(this, "Сервис не подключен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onStopClicked() {
        Log.d(TAG, "🛑 Нажата кнопка остановки")
        dualModeService?.let { service ->
            service.stopDualMode()
            updateStatusPaused() // Обновляем статус на "Пауза"
            Log.d(TAG, "🛑 Команда остановки отправлена")
        } ?: run {
            Log.e(TAG, "❌ DualModeService не подключен")
        }
    }

    private fun onHideClicked() {
        Log.d(TAG, "🗕 Нажата кнопка скрытия")
        stopSelf()
    }
    
    private fun onLogsClicked() {
        Log.d(TAG, "📋 Нажата кнопка логов")
        
        try {
            // Создаем полную диагностику
            diagnosticLogger.logFullDiagnostic()
            
            // Экспортируем логи для отправки
            val exportedFile = diagnosticLogger.exportLogs()
            
            if (exportedFile != null) {
                // Создаем Intent для отправки логов
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Dice Auto Bet - Диагностические логи")
                    putExtra(Intent.EXTRA_TEXT, "Диагностические логи для поддержки. Файл: ${exportedFile.name}")
                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                        this@DualModeFloatingControlService,
                        "${packageName}.fileprovider",
                        exportedFile
                    ))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                val chooserIntent = Intent.createChooser(shareIntent, "Отправить логи разработчику")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chooserIntent)
                
                Toast.makeText(this, "📋 Логи готовы к отправке. Отправьте их разработчику.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "❌ Ошибка создания логов", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обработки логов", e)
            Toast.makeText(this, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideFloatingControl() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
            overlayView = null
            Log.d(TAG, "🗕 Плавающее управление скрыто")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Плавающее управление двойным режимом",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления для плавающего управления двойным режимом"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Двойной режим")
            .setContentText("Плавающее управление активно")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    // Методы для обновления статуса
    private fun updateStatus(status: String) {
        uiHandler.post {
            statusTextView?.text = status
            Log.d(TAG, "📊 Статус обновлен: $status")
        }
    }
    
    private fun updateStatusReady() {
        updateStatus("Готов к запуску")
    }
    
    private fun updateStatusRunning() {
        updateStatus("Работает")
    }
    
    private fun updateStatusPaused() {
        updateStatus("Пауза")
    }

    override fun onDestroy() {
        Log.d(TAG, "🧹 Уничтожение DualModeFloatingControlService")
        
        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "BroadcastReceiver уже отменен: ${e.message}")
        }
        
        // Скрываем плавающее управление
        hideFloatingControl()
        
        // Отвязываемся от сервиса
        if (bound) {
            unbindService(connection)
            bound = false
        }
        
        super.onDestroy()
        Log.d(TAG, "🧹 DualModeFloatingControlService уничтожен")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
