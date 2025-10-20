package com.example.diceautobet.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.diceautobet.R
import com.example.diceautobet.SelectionOverlayView
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.SplitScreenUtils

/**
 * Специализированный сервис для настройки областей в двойном режиме
 * Учитывает WindowType (LEFT/RIGHT) и границы окон
 */
class DualModeAreaConfigService : Service() {
    
    companion object {
        private const val TAG = "DualModeAreaConfigService"
        const val EXTRA_WINDOW_TYPE = "window_type"
        
        fun configureWindow(context: Context, windowType: WindowType) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Требуется разрешение на отображение поверх других приложений", Toast.LENGTH_LONG).show()
                return
            }
            
            val intent = Intent(context, DualModeAreaConfigService::class.java)
            intent.putExtra(EXTRA_WINDOW_TYPE, windowType.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: com.example.diceautobet.utils.PreferencesManager
    private lateinit var areaManager: DualWindowAreaManager
    
    // Настройка областей
    private lateinit var currentWindowType: WindowType
    private var currentAreaIndex = 0
    private val requiredAreas = AreaType.values()
    private var isInPreparationPhase = true // Новая переменная для отслеживания фазы
    
    // UI элементы
    private var overlayView: View? = null
    private var selectionView: SelectionOverlayView? = null
    private var controlPanelView: View? = null
    private var startPanelView: View? = null // Новая панель для старта
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null
    private var startLayoutParams: WindowManager.LayoutParams? = null // Новые параметры
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = com.example.diceautobet.utils.PreferencesManager(this)
        areaManager = DualWindowAreaManager(this)

        // Переводим сервис в foreground, чтобы система не убила его после сворачивания
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "dual_mode_area_config_channel"
            val channel = NotificationChannel(
                channelId,
                "Dual Mode Area Configuration",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Настройка областей (двойной режим)")
                .setContentText("Выберите области для текущего окна")
                .setSmallIcon(R.drawable.ic_dice)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(2101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2101, notification)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Команда запуска сервиса")
        
        if (intent == null) {
            Log.e(TAG, "Intent is null")
            stopSelf()
            return START_NOT_STICKY
        }
        
        val windowTypeName = intent.getStringExtra(EXTRA_WINDOW_TYPE)
        if (windowTypeName == null) {
            Log.e(TAG, "WindowType не указан")
            stopSelf()
            return START_NOT_STICKY
        }
        
        try {
            currentWindowType = WindowType.valueOf(windowTypeName)
            Log.d(TAG, "Настройка областей для окна: $currentWindowType")
            
            setupAreaConfiguration()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запуске настройки", e)
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun setupAreaConfiguration() {
        Log.d(TAG, "Настройка конфигурации областей")
        
        currentAreaIndex = 0
        isInPreparationPhase = true
        
        // Сначала показываем панель подготовки
        createStartPanel()
    }
    
    private fun createOverlayView() {
        // Получаем границы текущего окна
        val windowBounds = SplitScreenUtils.getWindowBounds(this, currentWindowType)
        
        Log.d(TAG, "Границы окна $currentWindowType: $windowBounds")
        
        // Создаем overlay для выбора области - ПОЛНОЭКРАННЫЙ для удобства
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_area_selection, null)
        selectionView = overlayView?.findViewById(R.id.selectionOverlayView)
        
        // Определяем корректный тип окна для разных версий Android
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Настраиваем параметры overlay - ПОЛНОЭКРАННЫЙ
        val displayMetrics = resources.displayMetrics
        val overlayParams = WindowManager.LayoutParams(
            displayMetrics.widthPixels,  // Полная ширина экрана
            displayMetrics.heightPixels, // Полная высота экрана
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        
        overlayParams.gravity = Gravity.TOP or Gravity.START
        overlayParams.x = 0
        overlayParams.y = 0
        
        // Устанавливаем цвет границы в зависимости от окна
        val borderColor = when (currentWindowType) {
            WindowType.LEFT -> ContextCompat.getColor(this, R.color.blue)
            WindowType.RIGHT -> ContextCompat.getColor(this, R.color.green)
            WindowType.TOP -> ContextCompat.getColor(this, R.color.blue)
            WindowType.BOTTOM -> ContextCompat.getColor(this, R.color.green)
        }
        
        selectionView?.setBorderColor(borderColor)
        
        // Добавляем визуальную подсказку для границ окна
        selectionView?.setWindowBounds(windowBounds)
        
        windowManager.addView(overlayView, overlayParams)
        
        Log.d(TAG, "Полноэкранный overlay создан для окна $currentWindowType")
    }    private fun createControlPanel() {
        // Создаем плавающую контрольную панель
        controlPanelView = LayoutInflater.from(this).inflate(R.layout.layout_floating_dual_control, null)
        
        val titleText = controlPanelView?.findViewById<TextView>(R.id.textTitle)
        val areaText = controlPanelView?.findViewById<TextView>(R.id.textCurrentArea)
        val saveButton = controlPanelView?.findViewById<Button>(R.id.btnSave)
        val skipButton = controlPanelView?.findViewById<Button>(R.id.btnSkip)
        val cancelButton = controlPanelView?.findViewById<Button>(R.id.btnCancel)
        
        // Настраиваем заголовок в зависимости от типа разделения экрана
        val dualModeSettings = prefsManager.getDualModeSettings()
        val windowName = when (dualModeSettings.splitScreenType) {
            SplitScreenType.HORIZONTAL -> when (currentWindowType) {
                WindowType.LEFT -> "ЛЕВОЕ"
                WindowType.RIGHT -> "ПРАВОЕ"
                WindowType.TOP -> "ЛЕВОЕ"  // Fallback
                WindowType.BOTTOM -> "ПРАВОЕ"  // Fallback
            }
            SplitScreenType.VERTICAL -> when (currentWindowType) {
                WindowType.TOP -> "ВЕРХНЕЕ"
                WindowType.BOTTOM -> "НИЖНЕЕ"
                WindowType.LEFT -> "ВЕРХНЕЕ"  // Fallback
                WindowType.RIGHT -> "НИЖНЕЕ"  // Fallback
            }
        }
        titleText?.text = "Настройка $windowName окна (полный экран)"
        
        // Устанавливаем обработчики
        saveButton?.setOnClickListener { saveCurrentArea() }
        skipButton?.setOnClickListener { skipCurrentArea() }
        cancelButton?.setOnClickListener { finishConfiguration() }
        
        // Определяем корректный тип окна для разных версий Android
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Параметры плавающей панели управления
        controlLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        controlLayoutParams?.gravity = Gravity.TOP or Gravity.START
        controlLayoutParams?.x = 50  // Отступ слева
        controlLayoutParams?.y = 200 // Отступ сверху
        
        // Добавляем функциональность перетаскивания
        addDragFunctionality()
        
        try {
            windowManager.addView(controlPanelView, controlLayoutParams)
            Log.d(TAG, "Плавающая панель управления создана")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания панели управления", e)
        }
    }
    
    private fun addDragFunctionality() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        controlPanelView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlLayoutParams?.x ?: 0
                    initialY = controlLayoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    controlLayoutParams?.let { params ->
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(controlPanelView, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка обновления позиции панели", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        
        Log.d(TAG, "Панель управления создана")
    }
    
    private fun createStartPanel() {
        // Создаем плавающую панель для старта настройки
        startPanelView = LayoutInflater.from(this).inflate(R.layout.layout_dual_start_control, null)
        
        val titleText = startPanelView?.findViewById<TextView>(R.id.textTitle)
        val windowTypeText = startPanelView?.findViewById<TextView>(R.id.textWindowType)
        val startButton = startPanelView?.findViewById<Button>(R.id.btnStart)
        val cancelButton = startPanelView?.findViewById<Button>(R.id.btnCancel)
        
        // Устанавливаем текст в зависимости от типа окна
        val windowTypeName = when (currentWindowType) {
            WindowType.LEFT -> "Левое окно"
            WindowType.RIGHT -> "Правое окно"
            WindowType.TOP -> "Верхнее окно"
            WindowType.BOTTOM -> "Нижнее окно"
        }
        windowTypeText?.text = windowTypeName
        
        // Обработчики кнопок
        startButton?.setOnClickListener {
            startAreaConfigurationProcess()
        }
        
        cancelButton?.setOnClickListener {
            finishConfiguration()
        }
        
        // Параметры для плавающей панели
        startLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        
        startLayoutParams!!.gravity = Gravity.CENTER
        
        // Добавляем панель на экран
        try {
            windowManager.addView(startPanelView, startLayoutParams)
            Log.d(TAG, "Панель старта показана")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка показа панели старта", e)
        }
        
        // Добавляем возможность перетаскивания
        addDragFunctionalityToStartPanel()
        
        Log.d(TAG, "Панель старта создана")
    }
    
    private fun addDragFunctionalityToStartPanel() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        startPanelView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = startLayoutParams?.x ?: 0
                    initialY = startLayoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    startLayoutParams?.let { params ->
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(startPanelView, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка обновления позиции панели старта", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun startAreaConfigurationProcess() {
        Log.d(TAG, "Начинаем процесс настройки областей")
        
        // Убираем панель старта
        removeStartPanel()
        
        // Переходим к фазе настройки
        isInPreparationPhase = false
        
        // Создаем полноэкранный overlay и панель управления
        createOverlayView()
        createControlPanel()
        
        startAreaConfiguration()
    }
    
    private fun startAreaConfiguration() {
        if (currentAreaIndex >= requiredAreas.size) {
            finishConfiguration()
            return
        }
        
        val currentArea = requiredAreas[currentAreaIndex]
        Log.d(TAG, "Настройка области: ${currentArea.displayName}")
        
        // Обновляем UI
        controlPanelView?.findViewById<TextView>(R.id.textCurrentArea)?.text = 
            "${currentAreaIndex + 1}/${requiredAreas.size}: ${currentArea.displayName}"
        
        // Обновляем прогресс-бар
        controlPanelView?.findViewById<android.widget.ProgressBar>(R.id.progressBar)?.progress = currentAreaIndex + 1
        
        // Очищаем предыдущий выбор
        selectionView?.clearSelection()
        
        // Показываем подсказку
        showAreaHint(currentArea)
    }
    
    private fun showAreaHint(areaType: AreaType) {
        // Подсказка с указанием на выделенную зону
        val windowName = when (currentWindowType) {
            WindowType.LEFT -> "левой"
            WindowType.RIGHT -> "правой"
            WindowType.TOP -> "верхней"
            WindowType.BOTTOM -> "нижней"
        }
        val hint = "Выберите область '${areaType.displayName}' в $windowName зоне экрана"
        
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    }
    
    private fun saveCurrentArea() {
        val selection = selectionView?.getAbsoluteSelection()
        if (selection == null) {
            Toast.makeText(this, "Сначала выберите область", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentArea = requiredAreas[currentAreaIndex]
        
        try {
            // Для двойного режима сохраняем абсолютные экранные координаты как есть
            // selection уже содержит правильные координаты для текущего окна
            Log.d(TAG, "Исходные координаты выбора: $selection")
            Log.d(TAG, "Окно: $currentWindowType")
            
            // Сохраняем область для конкретного окна без дополнительных преобразований
            areaManager.saveAreaForWindow(currentWindowType, currentArea, ScreenArea(selection, null, currentArea.displayName))
            
            Log.d(TAG, "Область сохранена: $currentArea для окна $currentWindowType, координаты: $selection")
            
            Toast.makeText(this, "Область '${currentArea.displayName}' сохранена", Toast.LENGTH_SHORT).show()
            
            // Переходим к следующей области
            moveToNextArea()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения области", e)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun skipCurrentArea() {
        Log.d(TAG, "Пропуск области: ${requiredAreas[currentAreaIndex].displayName}")
        Toast.makeText(this, "Область пропущена", Toast.LENGTH_SHORT).show()
        moveToNextArea()
    }
    
    private fun moveToNextArea() {
        currentAreaIndex++
        startAreaConfiguration()
    }
    
    private fun finishConfiguration() {
        Log.d(TAG, "Завершение настройки областей для окна $currentWindowType")
        
        // Показываем результат
        val configuredAreasCount = areaManager.getAreasForWindow(currentWindowType).size
        Toast.makeText(
            this,
            "Окно $currentWindowType настроено!\nОбластей: $configuredAreasCount",
            Toast.LENGTH_SHORT
        ).show()
        
        // Определяем следующее окно для настройки
        val nextWindow = getNextWindowToConfigure()
        
        if (nextWindow != null) {
            Log.d(TAG, "Переходим к настройке следующего окна: $nextWindow")
            
            // Очищаем текущие элементы интерфейса
            cleanupCurrentViews()
            
            // Переходим к следующему окну
            currentWindowType = nextWindow
            currentAreaIndex = 0
            isInPreparationPhase = true
            
            // Показываем панель старта для следующего окна
            createStartPanel()
            
        } else {
            // Все окна настроены - завершаем
            Log.d(TAG, "Все окна настроены! Завершение работы")
            
            // ТЕСТОВАЯ ПРОВЕРКА: Выводим все сохраненные области
            prefsManager.debugPrintSavedAreas()
            
            Toast.makeText(
                this,
                "Настройка всех окон завершена!",
                Toast.LENGTH_LONG
            ).show()
            
            // Закрываем сервис
            cleanup()
            stopSelf()
        }
    }
    
    private fun getNextWindowToConfigure(): WindowType? {
        // Получаем настройки разделения экрана
        val dualModeSettings = prefsManager.getDualModeSettings()
        val splitScreenType = dualModeSettings.splitScreenType
        
        return when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> {
                // Горизонтальное разделение: LEFT -> RIGHT
                when (currentWindowType) {
                    WindowType.LEFT -> WindowType.RIGHT
                    WindowType.RIGHT -> null // Все настроено
                    else -> null
                }
            }
            SplitScreenType.VERTICAL -> {
                // Вертикальное разделение: TOP -> BOTTOM
                when (currentWindowType) {
                    WindowType.TOP -> WindowType.BOTTOM
                    WindowType.BOTTOM -> null // Все настроено
                    else -> null
                }
            }
        }
    }
    
    private fun cleanupCurrentViews() {
        try {
            overlayView?.let { 
                windowManager.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay уже удален", e)
        }
        
        try {
            controlPanelView?.let { 
                windowManager.removeView(it)
                controlPanelView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Панель управления уже удалена", e)
        }
    }
    
    private fun removeStartPanel() {
        try {
            startPanelView?.let { 
                windowManager.removeView(it) 
                startPanelView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при удалении панели старта", e)
        }
    }
    
    private fun cleanup() {
        try {
            overlayView?.let { 
                windowManager.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay уже удален или не прикреплен", e)
        }
        
        try {
            controlPanelView?.let { 
                windowManager.removeView(it)
                controlPanelView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Панель управления уже удалена", e)
        }
        
        try {
            startPanelView?.let { 
                windowManager.removeView(it)
                startPanelView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Панель старта уже удалена", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        Log.d(TAG, "Сервис уничтожен")
    }
}
