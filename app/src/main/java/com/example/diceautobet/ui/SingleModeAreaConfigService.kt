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
import com.example.diceautobet.managers.SingleModeAreaManager
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager

/**
 * Специализированный сервис для настройки областей в одиночном режиме
 * Создает полноэкранный overlay для выбора областей
 */
class SingleModeAreaConfigService : Service() {
    
    companion object {
        private const val TAG = "SingleModeAreaConfigService"
        const val EXTRA_AREA_TYPE = "area_type"
        
        fun configureArea(context: Context, areaType: SingleModeAreaType) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Требуется разрешение на отображение поверх других приложений", Toast.LENGTH_LONG).show()
                return
            }
            
            val intent = Intent(context, SingleModeAreaConfigService::class.java)
            intent.putExtra(EXTRA_AREA_TYPE, areaType.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PreferencesManager
    private lateinit var areaManager: SingleModeAreaManager
    
    private var overlayView: View? = null
    private var controlPanelView: View? = null
    private var startPanelView: View? = null
    private var selectionView: SelectionOverlayView? = null
    
    private var currentAreaType: SingleModeAreaType = SingleModeAreaType.DICE_AREA
    private var currentAreaIndex = 0
    private var isInPreparationPhase = true
    private var isConfiguring = false
    
    private val allAreas = SingleModeAreaType.values()
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PreferencesManager(this)
        areaManager = SingleModeAreaManager(prefsManager)
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Команда запуска сервиса")
        
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Нет разрешения на отображение поверх других приложений")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Получаем тип области из Intent или начинаем с первой
        val areaTypeName = intent?.getStringExtra(EXTRA_AREA_TYPE)
        currentAreaType = if (areaTypeName != null) {
            try {
                SingleModeAreaType.valueOf(areaTypeName)
            } catch (e: Exception) {
                SingleModeAreaType.DICE_AREA
            }
        } else {
            SingleModeAreaType.DICE_AREA
        }
        
        Log.d(TAG, "Настройка области: ${currentAreaType.displayName}")
        
        startConfigurationProcess()
        
        return START_STICKY
    }
    
    private fun startConfigurationProcess() {
        Log.d(TAG, "Настройка конфигурации областей")
        
        // Показываем уведомление
        val notification = createForegroundNotification()
        startForeground(2101, notification)
        
        currentAreaIndex = allAreas.indexOf(currentAreaType)
        isInPreparationPhase = true
        
        // Сначала показываем панель подготовки
        createStartPanel()
    }
    
    private fun createOverlayView() {
        Log.d(TAG, "Создание полноэкранного overlay для single mode")
        
        // Создаем overlay для выбора области - ПОЛНОЭКРАННЫЙ
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
        
        // Устанавливаем цвет границы для single mode
        val borderColor = ContextCompat.getColor(this, R.color.blue)
        selectionView?.setBorderColor(borderColor)
        
        // Очищаем границы окна для single mode (используем весь экран)
        selectionView?.clearWindowBounds()
        
        windowManager.addView(overlayView, overlayParams)
        
        Log.d(TAG, "Полноэкранный overlay создан для single mode")
    }
    
    private fun createControlPanel() {
        // Создаем плавающую контрольную панель
        controlPanelView = LayoutInflater.from(this).inflate(R.layout.layout_floating_single_control, null)
        
        val titleText = controlPanelView?.findViewById<TextView>(R.id.textTitle)
        val areaText = controlPanelView?.findViewById<TextView>(R.id.textCurrentArea)
        val saveButton = controlPanelView?.findViewById<Button>(R.id.btnSave)
        val skipButton = controlPanelView?.findViewById<Button>(R.id.btnSkip)
        val cancelButton = controlPanelView?.findViewById<Button>(R.id.btnCancel)
        
        // Настраиваем заголовок
        titleText?.text = "Настройка областей - Одиночный режим"
        
        // Настраиваем текст текущей области
        updateAreaText(areaText)
        
        saveButton?.setOnClickListener {
            saveCurrentArea()
        }
        
        skipButton?.setOnClickListener {
            skipCurrentArea()
        }
        
        cancelButton?.setOnClickListener {
            cancelConfiguration()
        }
        
        // Настраиваем параметры окна для плавающей панели
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        panelParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        panelParams.y = 100 // Отступ сверху
        
        windowManager.addView(controlPanelView, panelParams)
        
        Log.d(TAG, "Панель управления создана")
    }
    
    private fun createStartPanel() {
        // Создаем стартовую панель
        startPanelView = LayoutInflater.from(this).inflate(R.layout.layout_single_mode_start_panel, null)
        
        val titleText = startPanelView?.findViewById<TextView>(R.id.textTitle)
        val descriptionText = startPanelView?.findViewById<TextView>(R.id.textDescription)
        val startButton = startPanelView?.findViewById<Button>(R.id.btnStart)
        val cancelButton = startPanelView?.findViewById<Button>(R.id.btnCancel)
        
        titleText?.text = "Настройка областей - Одиночный режим"
        descriptionText?.text = getAreaDescription(currentAreaType)
        
        startButton?.setOnClickListener {
            startAreaConfiguration()
        }
        
        cancelButton?.setOnClickListener {
            cancelConfiguration()
        }
        
        // Настраиваем параметры окна
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        panelParams.gravity = Gravity.CENTER
        
        windowManager.addView(startPanelView, panelParams)
        
        Log.d(TAG, "Панель старта показана")
    }
    
    private fun getAreaDescription(areaType: SingleModeAreaType): String {
        return when (areaType) {
            SingleModeAreaType.DICE_AREA -> "Выберите область с кубиками для анализа результата"
            SingleModeAreaType.BET_BLUE -> "Выберите кнопку ставки на синий цвет"
            SingleModeAreaType.BET_RED -> "Выберите кнопку ставки на красный цвет"
            SingleModeAreaType.DOUBLE_BUTTON -> "Выберите кнопку удвоения ставки (Х2)"
            else -> if (SingleModeAreaType.isBetArea(areaType)) {
                "Выберите кнопку ставки ${SingleModeAreaType.getBetAmountByArea(areaType)}"
            } else {
                areaType.description
            }
        }
    }
    
    private fun startAreaConfiguration() {
        Log.d(TAG, "Начинаем процесс настройки областей")
        
        // Убираем стартовую панель
        startPanelView?.let { windowManager.removeView(it) }
        startPanelView = null
        
        isInPreparationPhase = false
        isConfiguring = true
        
        // Создаем основной overlay и панель управления
        createOverlayView()
        createControlPanel()
        
        Log.d(TAG, "Настройка области: ${currentAreaType.displayName}")
    }
    
    private fun updateAreaText(areaText: TextView?) {
        val current = currentAreaIndex + 1
        val total = allAreas.size
        areaText?.text = "Область $current из $total: ${currentAreaType.displayName}"
    }
    
    private fun saveCurrentArea() {
        val selection = selectionView?.getAbsoluteSelection()
        if (selection != null) {
            Log.d(TAG, "Сохраняем область ${currentAreaType.displayName}: $selection")
            
            areaManager.saveArea(currentAreaType, selection)
            
            Toast.makeText(this, "Область '${currentAreaType.displayName}' сохранена", Toast.LENGTH_SHORT).show()
            
            // Переходим к следующей области
            moveToNextArea()
        } else {
            Toast.makeText(this, "Сначала выберите область на экране", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun skipCurrentArea() {
        Log.d(TAG, "Пропускаем область ${currentAreaType.displayName}")
        
        Toast.makeText(this, "Область '${currentAreaType.displayName}' пропущена", Toast.LENGTH_SHORT).show()
        
        // Переходим к следующей области
        moveToNextArea()
    }
    
    private fun moveToNextArea() {
        currentAreaIndex++
        
        if (currentAreaIndex >= allAreas.size) {
            // Все области настроены
            finishConfiguration()
            return
        }
        
        currentAreaType = allAreas[currentAreaIndex]
        
        // Обновляем UI для новой области
        val areaText = controlPanelView?.findViewById<TextView>(R.id.textCurrentArea)
        updateAreaText(areaText)
        
        // Очищаем выделение
        selectionView?.clearSelection()
        
        Log.d(TAG, "Переход к области: ${currentAreaType.displayName}")
        
        Toast.makeText(this, getAreaDescription(currentAreaType), Toast.LENGTH_LONG).show()
    }
    
    private fun finishConfiguration() {
        Log.d(TAG, "Настройка областей завершена")
        
        Toast.makeText(this, "Настройка областей одиночного режима завершена!", Toast.LENGTH_LONG).show()
        
        // Останавливаем сервис
        stopSelf()
    }
    
    private fun cancelConfiguration() {
        Log.d(TAG, "Настройка областей отменена")
        
        Toast.makeText(this, "Настройка областей отменена", Toast.LENGTH_SHORT).show()
        
        // Останавливаем сервис
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Сервис уничтожается")
        
        // Убираем все overlay
        try {
            overlayView?.let { windowManager.removeView(it) }
            controlPanelView?.let { windowManager.removeView(it) }
            startPanelView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при удалении overlay", e)
        }
    }
    
    override fun onBind(intent: Intent): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "single_mode_config_channel",
                "Single Mode Configuration",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Настройка областей одиночного режима"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createForegroundNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "single_mode_config_channel")
                .setContentTitle("Настройка областей")
                .setContentText("Настройка областей одиночного режима")
                .setSmallIcon(R.drawable.ic_dice)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Настройка областей")
                .setContentText("Настройка областей одиночного режима")
                .setSmallIcon(R.drawable.ic_dice)
                .setOngoing(true)
                .build()
        }
    }
}