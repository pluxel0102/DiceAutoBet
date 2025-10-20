package com.example.diceautobet.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import android.util.Log
import com.example.diceautobet.R
import com.example.diceautobet.MediaProjectionRequestActivity
import com.example.diceautobet.SelectionOverlayView
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.CoordinateUtils
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import android.app.Activity
import android.content.res.Resources
import androidx.appcompat.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.os.Handler
import android.os.Looper

class AreaConfigurationService : Service() {

    companion object {
        const val ACTION_START_CONFIGURATION = "com.example.diceautobet.START_CONFIGURATION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int? = null, resultData: Intent? = null) {
            Log.e("AreaConfig", "AreaConfigurationService.start() вызван из:\n" + Log.getStackTraceString(Throwable()))
            Log.d("AreaConfig", "AreaConfigurationService.start() вызван: resultCode=$resultCode, resultData=$resultData")
            val intent = Intent(context, AreaConfigurationService::class.java)
            intent.action = ACTION_START_CONFIGURATION
            Log.d("AreaConfig", "Установлен action: ${intent.action}")
            if (resultCode != null && resultData != null) {
                intent.putExtra(EXTRA_RESULT_CODE, resultCode)
                intent.putExtra(EXTRA_RESULT_DATA, resultData)
                Log.d("AreaConfig", "Добавлены extras: EXTRA_RESULT_CODE=$resultCode, EXTRA_RESULT_DATA=$resultData")
            } else {
                Log.d("AreaConfig", "resultCode или resultData null, extras не добавляются")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("AreaConfig", "Сервис запущен")
        }
        
        fun requestMediaProjectionPermission(context: Context) {
            Log.d("AreaConfig", "Запрашиваем разрешение MediaProjection")
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = projectionManager.createScreenCaptureIntent()
            Log.d("AreaConfig", "Intent для MediaProjection создан: $captureIntent")
            
            // Создаем новую активность для запроса разрешения
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefsManager: PreferencesManager
    private lateinit var projectionManager: MediaProjectionManager

    // MediaProjection для скриншотов
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionResultCode: Int? = null
    private var projectionResultData: Intent? = null

    // Плавающее окно
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    // Прозрачные подсказки
    private var hintView: View? = null
    private var selectionView: SelectionOverlayView? = null
    private var hintParams: WindowManager.LayoutParams? = null

    private var currentAreaType: AreaType? = null
    private var configuredAreas = mutableSetOf<AreaType>()
    private var areaIndex = 0

    // UI элементы подсказки
    private lateinit var tvHintText: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnSave: Button
    private lateinit var btnSkip: Button
    private lateinit var btnCancel: Button

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val buttonAreaTypes = setOf(
        AreaType.RED_BUTTON,
        AreaType.DRAW_BUTTON,
        AreaType.ORANGE_BUTTON,
        AreaType.BET_10,
        AreaType.BET_50,
        AreaType.BET_100,
        AreaType.BET_500,
        AreaType.BET_2500,
        AreaType.CONFIRM_BET,
        AreaType.DOUBLE_BUTTON
    )

    private var waitingForTap = false
    private var tapListener: View.OnTouchListener? = null

    private var configMenuView: View? = null
    private var configMenuParams: WindowManager.LayoutParams? = null

    // Полноэкранный прозрачный overlay для перехвата касаний кнопок
    private var buttonTapOverlay: View? = null
    private var buttonTapParams: WindowManager.LayoutParams? = null
    private var isButtonTapOverlayShown = false // Флаг для отслеживания состояния

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("AreaConfig", "onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager = PreferencesManager(this)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Проверяем набор кнопок
        Log.d("AreaConfig", "Инициализированы типы кнопок: ${buttonAreaTypes.joinToString()}")
        
        setupTapListener()
        
        // Создаем уведомление для foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "area_configuration_channel"
            val channel = NotificationChannel(
                channelId,
                "Area Configuration",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Настройка областей")
                .setContentText("Перейдите в игру и нажмите 'Начать'")
                .setSmallIcon(R.drawable.ic_dice)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(2001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2001, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AreaConfig", "onStartCommand вызван")
        
        when (intent?.action) {
            ACTION_START_CONFIGURATION -> {
                Log.d("AreaConfig", "Получена команда запуска конфигурации областей")
                
                // Сбрасываем состояние при каждом запуске
                configuredAreas.clear()
                areaIndex = 0
                currentAreaType = AreaType.values()[areaIndex]
                waitingForTap = buttonAreaTypes.contains(currentAreaType)
                Log.d("AreaConfig", "Инициализация: configuredAreas сброшен, currentAreaType=${currentAreaType?.name}, isButton=${buttonAreaTypes.contains(currentAreaType)}, waitingForTap=$waitingForTap")
                
                if (configMenuView == null) {
                    showFloatingConfigMenu()
                }
                
                // Проверяем состояние после создания окна
                Log.d("AreaConfig", "Проверка состояния после createFloatingWindow: currentAreaType=${currentAreaType?.name}, isButton=${buttonAreaTypes.contains(currentAreaType)}, waitingForTap=$waitingForTap")
                
                // updateUI() здесь больше не вызываем!
            }
        }
        return START_NOT_STICKY
    }

    private fun showFloatingConfigMenu() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DiceAutoBet)
        configMenuView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_config_menu, null)
        configMenuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        configMenuParams?.gravity = Gravity.TOP or Gravity.START
        configMenuParams?.x = 100 // стартовая позиция слева
        configMenuParams?.y = 300 // стартовая позиция сверху
        val btnStart = configMenuView!!.findViewById<Button>(R.id.btnStartConfiguration)
        val btnCancel = configMenuView!!.findViewById<Button>(R.id.btnCloseConfiguration)

        // --- Перетаскивание ---
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        configMenuView!!.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = configMenuParams?.x ?: 0
                    initialY = configMenuParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    configMenuParams?.let { params ->
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(configMenuView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
        // --- конец перетаскивания ---

        btnStart.setOnClickListener {
            removeConfigMenu()
            // Сбрасываем состояние при начале конфигурации
            configuredAreas.clear()
            areaIndex = 0
            currentAreaType = AreaType.values()[areaIndex]
            waitingForTap = buttonAreaTypes.contains(currentAreaType)
            Log.d("AreaConfig", "Начало конфигурации: configuredAreas сброшен, currentAreaType=${currentAreaType?.name}")
            createFloatingWindow()
            updateUI()
        }
        btnCancel.setOnClickListener {
            removeConfigMenu()
            stopSelf()
        }
        windowManager.addView(configMenuView, configMenuParams)
    }

    private fun removeConfigMenu() {
        configMenuView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            configMenuView = null
            configMenuParams = null
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        Log.d("AreaConfig", "Создаем плавающее окно для overlay")
        // Если overlay уже был добавлен — удаляем
        try {
            floatingView?.let { windowManager.removeView(it) }
            Log.d("AreaConfig", "Старый overlay удалён")
        } catch (e: Exception) {
            Log.w("AreaConfig", "Ошибка при удалении старого overlay: ${e.message}")
        }

        // Создаем layout для overlay
        val context = ContextThemeWrapper(this, R.style.Theme_DiceAutoBet)
        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.layout_transparent_hint, null)

        // Инициализируем UI элементы
        tvHintText = floatingView?.findViewById(R.id.tvHintText) ?: throw IllegalStateException("tvHintText not found")
        tvProgress = floatingView?.findViewById(R.id.tvProgress) ?: throw IllegalStateException("tvProgress not found")
        btnSave = floatingView?.findViewById(R.id.btnSave) ?: throw IllegalStateException("btnSave not found")
        btnSkip = floatingView?.findViewById(R.id.btnSkip) ?: throw IllegalStateException("btnSkip not found")
        btnCancel = floatingView?.findViewById(R.id.btnCancel) ?: throw IllegalStateException("btnCancel not found")
        
        // Создаем и добавляем SelectionOverlayView программно
        val overlayContainer = floatingView?.findViewById<FrameLayout>(R.id.overlayContainer)
            ?: throw IllegalStateException("overlayContainer not found")
            
        selectionView = SelectionOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlayContainer.addView(selectionView)
        
        // Настраиваем параметры окна
        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        // Настраиваем перетаскивание для контейнера кнопок
        val buttonsContainer = floatingView?.findViewById<LinearLayout>(R.id.floatingButtonsContainer)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        buttonsContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = buttonsContainer.x.toInt()
                    initialY = buttonsContainer.y.toInt()
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    buttonsContainer.x = initialX + dx
                    buttonsContainer.y = initialY + dy
                    true
                }
                else -> false
            }
        }
        
        // Добавляем окно
        // Настраиваем обработчики
        setupTapListener()
        setupButtons()
        
        Log.d("AreaConfig", "Overlay: обработчики настроены")

        // Добавляем overlay
        try {
            windowManager.addView(floatingView, floatingParams)
            floatingView?.visibility = View.VISIBLE
            Log.d("AreaConfig", "Overlay успешно добавлен на экран")
        } catch (e: Exception) {
            Log.e("AreaConfig", "Ошибка при добавлении overlay: ${e.message}", e)
        }

        // Настраиваем обработчики
        setupTapListener()
        setupButtons()
        Log.d("AreaConfig", "Overlay: обработчики настроены")

        // Инициализируем прогресс
        updateProgressCounter()
        
        updateUI()
        Log.d("AreaConfig", "Overlay: updateUI вызван")
    }

    private fun hideFloatingWindow() {
        floatingView?.visibility = View.GONE
    }

    private fun showFloatingWindow() {
        floatingView?.visibility = View.VISIBLE
    }

    private fun showMediaProjectionRequestDialog() {
        Log.d("AreaConfig", "Показываем диалог запроса разрешений MediaProjection")
        // Используем ContextThemeWrapper для Material темы
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DiceAutoBet)
        val dialogView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_config_menu, null)
        val dialogParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
        }

        // Изменяем текст в диалоге
        dialogView.findViewById<TextView>(R.id.tvTitle)?.text = "Разрешение на захват экрана"
        
        val btnStart = dialogView.findViewById<Button>(R.id.btnStartConfiguration)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseConfiguration)
        
        btnStart?.text = "Предоставить разрешение"
        btnStart?.setOnClickListener {
            Log.d("AreaConfig", "Нажата кнопка 'Предоставить разрешение'")
            windowManager.removeView(dialogView)
            requestMediaProjectionPermission()
        }
        
        btnClose?.text = "Отмена"
        btnClose?.setOnClickListener {
            Log.d("AreaConfig", "Нажата кнопка 'Отмена'")
            windowManager.removeView(dialogView)
            stopSelf()
        }

        try {
            windowManager.addView(dialogView, dialogParams)
            Log.d("AreaConfig", "Диалог запроса разрешений добавлен на экран")
        } catch (e: Exception) {
            Log.e("AreaConfig", "Ошибка при добавлении диалога", e)
            Toast.makeText(this, "Ошибка при создании диалога: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaProjectionPermission() {
        Log.d("AreaConfig", "Запрашиваем разрешение MediaProjection")
        AreaConfigurationService.requestMediaProjectionPermission(this)
    }

    @SuppressLint("InflateParams")
    private fun createTransparentHint() {
        // Получаем размеры экрана
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Создаем прозрачный overlay для подсказок
        hintParams = WindowManager.LayoutParams().apply {
            width = screenWidth // Явно устанавливаем размеры экрана
            height = screenHeight
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START // Изменяем gravity чтобы окно начиналось с левого верхнего угла
            x = 0 // Устанавливаем начальную позицию
            y = 0
        }

        Log.d("AreaConfig", "Создаем плавающее окно: width=${hintParams?.width}, height=${hintParams?.height}, gravity=${hintParams?.gravity}, x=${hintParams?.x}, y=${hintParams?.y}")
        Log.d("AreaConfig", "Размеры экрана: ${screenWidth}x${screenHeight}")

        // Используем ContextThemeWrapper для Material темы
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DiceAutoBet)
        hintView = LayoutInflater.from(themedContext).inflate(R.layout.layout_transparent_hint, null)

        // Находим элементы UI
        tvHintText = hintView!!.findViewById(R.id.tvHintText)
        tvProgress = hintView!!.findViewById(R.id.tvProgress)
        btnSave = hintView!!.findViewById(R.id.btnSave)
        btnSkip = hintView!!.findViewById(R.id.btnSkip)
        btnCancel = hintView!!.findViewById(R.id.btnCancel)
        val btnTest = hintView!!.findViewById<Button>(R.id.btnTest)

        // Создаем view для выделения
        selectionView = SelectionOverlayView(this)
        val overlayContainer = hintView!!.findViewById<ViewGroup>(R.id.overlayContainer)
        overlayContainer.addView(selectionView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Настраиваем кнопки
        setupButtons()
        
        // Настраиваем кнопку теста
        btnTest.setOnClickListener {
            testCoordinates()
        }
        
        // Настраиваем перетаскивание для плавающих кнопок
        setupDraggableButtons()

        // Добавляем overlay
        windowManager.addView(hintView, hintParams)
        
        Log.d("AreaConfig", "Плавающее окно создано и добавлено")
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            Log.d("AreaConfig", "Нажата кнопка Сохранить")
            saveCurrentArea()
        }
        
        btnSkip.setOnClickListener {
            Log.d("AreaConfig", "Нажата кнопка Пропустить")
            showNextArea()
        }
        
        btnCancel.setOnClickListener {
            Log.d("AreaConfig", "Нажата кнопка Отмена")
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableButtons() {
        val floatingButtonsContainer = hintView?.findViewById<View>(R.id.floatingButtonsContainer)
        
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        floatingButtonsContainer?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = hintParams?.x ?: 0
                    initialY = hintParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    Log.d("AreaConfig", "Начало перетаскивания: initialX=$initialX, initialY=$initialY")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    hintParams?.let { params ->
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        
                        val newX = initialX + deltaX
                        val newY = initialY + deltaY
                        
                        // Ограничиваем перемещение в пределах экрана
                        val displayMetrics = resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - view.width
                        val maxY = displayMetrics.heightPixels - view.height
                        
                        params.x = newX.coerceIn(0, maxX)
                        params.y = newY.coerceIn(0, maxY)
                        
                        Log.d("AreaConfig", "Перемещение окна: x=${params.x}, y=${params.y}")
                        
                        try {
                            windowManager.updateViewLayout(hintView, params)
                        } catch (e: Exception) {
                            // Игнорируем ошибки обновления layout
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Log.d("AreaConfig", "Конец перетаскивания: x=${hintParams?.x}, y=${hintParams?.y}")
                    true
                }
                else -> false
            }
        }
    }

    private fun startConfiguration() {
        val allAreas = AreaType.values()
        var anyConfigured = false
        allAreas.forEach { areaType ->
            val loaded = prefsManager.loadAreaUniversal(areaType)
            Log.d("AreaConfig", "Пробуем загрузить область: $areaType, результат: $loaded")
            if (loaded != null) {
                configuredAreas.add(areaType)
                anyConfigured = true
            }
        }
        Log.d("AreaConfig", "Список уже настроенных областей: $configuredAreas")
        if (anyConfigured) {
            Log.d("AreaConfig", "Обнаружены уже настроенные области, сбрасываем все области!")
            prefsManager.clearAllAreas()
            configuredAreas.clear()
        }
        showNextArea()
    }

    private fun showNextArea() {
        Log.d("AreaConfig", "============= showNextArea НАЧАЛО =============")
        Log.d("AreaConfig", "showNextArea: текущий индекс=$areaIndex")
        Log.d("AreaConfig", "showNextArea: configuredAreas до обработки: $configuredAreas")
        val allAreas = AreaType.values()
        
        Log.d("AreaConfig", "showNextArea: всего областей = ${allAreas.size}")
        Log.d("AreaConfig", "showNextArea: текущая область до while = ${if (areaIndex < allAreas.size) allAreas[areaIndex].name else "INDEX_OUT_OF_BOUNDS"}")

        while (areaIndex < allAreas.size && configuredAreas.contains(allAreas[areaIndex])) {
            Log.d("AreaConfig", "Пропускаем уже настроенную область: ${allAreas[areaIndex].name}")
            areaIndex++
            Log.d("AreaConfig", "areaIndex увеличен до: $areaIndex")
        }

        if (areaIndex >= allAreas.size) {
            Log.d("AreaConfig", "Все области настроены! areaIndex=$areaIndex >= allAreas.size=${allAreas.size}")
            onConfigurationComplete()
            return
        }

        currentAreaType = allAreas[areaIndex]
        val isButton = buttonAreaTypes.contains(currentAreaType)
        waitingForTap = isButton
        Log.d("AreaConfig", "showNextArea: переходим к области: ${currentAreaType?.name}, isButton=$isButton, waitingForTap=$waitingForTap")
        Log.d("AreaConfig", "showNextArea: финальные configuredAreas: $configuredAreas")
        Log.d("AreaConfig", "============= showNextArea КОНЕЦ =============")
        updateUI()
    }

    private fun moveToNextArea() {
        Log.d("AreaConfig", "moveToNextArea: текущий индекс=$areaIndex")
        areaIndex++
        if (areaIndex >= AreaType.values().size) {
            Log.d("AreaConfig", "Все области настроены, останавливаем сервис")
            stopSelf()
            return
        }
        
        currentAreaType = AreaType.values()[areaIndex]
        val isButton = buttonAreaTypes.contains(currentAreaType)
        waitingForTap = isButton
        Log.d("AreaConfig", "Переход к следующей области: ${currentAreaType?.name}, isButton=$isButton, waitingForTap=$waitingForTap")
        
        // Очищаем текущее выделение
        selectionView?.clearSelection()
        
        updateUI() // обязательно вызываем для корректной инициализации состояния
    }

    private fun setupTapListener() {
        Log.d("AreaConfig", "Настраиваем tap listener для currentAreaType=${currentAreaType?.name}")
        tapListener = View.OnTouchListener { view, event ->
            Log.d("AreaConfig", "OnTouch event: action=${event.action}, rawX=${event.rawX}, rawY=${event.rawY}, waitingForTap=$waitingForTap, currentAreaType=${currentAreaType?.name}, view=$view")
            if (waitingForTap && buttonAreaTypes.contains(currentAreaType)) {
                Log.d("AreaConfig", "waitingForTap=true, обрабатываем касание для кнопки ${currentAreaType?.displayName}")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        Log.d("AreaConfig", "ACTION_DOWN на координатах (raw): x=${event.rawX}, y=${event.rawY}")
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        Log.d("AreaConfig", "ACTION_UP на координатах (raw): x=${event.rawX}, y=${event.rawY}")
                        val x = event.rawX.toInt()
                        val y = event.rawY.toInt()
                        // Создаем более крупную область 40x40 вокруг точки касания.
                        // Это делает клик более надежным, если пользователь нажал не точно в центр кнопки.
                        // Центр этой области все равно будет точкой касания.
                        val tapAreaSize = 40
                        val left = x - tapAreaSize / 2
                        val top = y - tapAreaSize / 2
                        val right = x + tapAreaSize / 2
                        val bottom = y + tapAreaSize / 2
                        val touchRect = Rect(left, top, right, bottom)
                        Log.d("AreaConfig", "Сохраняем область касания (40x40): $touchRect")
                        currentAreaType?.let { areaType ->
                            Log.d("AreaConfig", "Сохраняем область для: ${areaType.name}")
                            PreferencesManager(this).saveArea(areaType, touchRect)
                            // ВАЖНО: Добавляем область в configuredAreas!
                            configuredAreas.add(areaType)
                            Log.d("AreaConfig", "Область ${areaType.name} добавлена в configuredAreas: $configuredAreas")
                            Toast.makeText(this, "Координаты сохранены для ${areaType.displayName}", Toast.LENGTH_SHORT).show()
                            
                            // Скрываем overlay для кнопок после сохранения
                            hideButtonTapOverlay()
                            
                            // ВАЖНО: Увеличиваем areaIndex перед переходом к следующей области!
                            areaIndex++
                            Log.d("AreaConfig", "areaIndex увеличен до: $areaIndex")
                            
                            showNextArea()
                        }
                        // waitingForTap НЕ сбрасываем здесь!
                        true
                    }
                    else -> {
                        Log.d("AreaConfig", "Неизвестное действие: ${event.action}")
                        false
                    }
                }
            } else {
                Log.d("AreaConfig", "waitingForTap=false или не кнопка, игнорируем касание")
                false
            }
        }
        Log.d("AreaConfig", "Tap listener настроен и готов к использованию")
    }

    /**
     * Функция для обновления счётчика прогресса
     * Показывает текущий прогресс в формате "настроено/всего"
     */
    private fun updateProgressCounter() {
        if (!::tvProgress.isInitialized) {
            Log.w("AreaConfig", "tvProgress не инициализирован, пропускаем обновление")
            return
        }
        
        val totalAreas = AreaType.values().size
        val currentProgress = configuredAreas.size
        tvProgress.text = "$currentProgress/$totalAreas"
        Log.d("AreaConfig", "Обновлен счётчик прогресса: $currentProgress/$totalAreas (настроенные области: $configuredAreas)")
    }

    private fun updateUI() {
        if (!::btnSave.isInitialized || !::btnSkip.isInitialized || !::btnCancel.isInitialized) {
            Log.w("AreaConfig", "updateUI: кнопки ещё не инициализированы, выходим")
            return
        }
        Log.d("AreaConfig", "updateUI: currentAreaType=${currentAreaType?.name}, isButton=${buttonAreaTypes.contains(currentAreaType)}, waitingForTap=$waitingForTap")

        // Обновляем счётчик прогресса
        updateProgressCounter()
        
        if (buttonAreaTypes.contains(currentAreaType)) {
            // Для кнопок: показываем overlay для касания
            selectionView?.visibility = View.GONE
            btnSave.visibility = View.GONE
            btnSkip.visibility = View.GONE
            tvHintText.text = "Нажмите на кнопку: ${currentAreaType?.displayName}"
            waitingForTap = true
            // Убедимся, что tapListener настроен правильно
            setupTapListener()
            
            // Скрываем обычные listeners и показываем полноэкранный overlay
            floatingView?.setOnTouchListener(null)
            selectionView?.setOnTouchListener(null)
            
            // Показываем полноэкранный overlay для кнопок
            showButtonTapOverlay()
            
            Log.d("AreaConfig", "Полноэкранный overlay активирован для кнопки ${currentAreaType?.displayName}")
        } else {
            // Для областей (DICE_AREA, BET_RESULT и т.д.): показываем интерфейс выделения
            selectionView?.visibility = View.VISIBLE
            btnSave.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            tvHintText.text = "Выделите область: ${currentAreaType?.displayName} и нажмите 'Сохранить'"
            waitingForTap = false
            floatingView?.setOnTouchListener(null)
            selectionView?.setOnTouchListener(null)
            hideButtonTapOverlay()
            
            Log.d("AreaConfig", "Интерфейс выделения активирован для области ${currentAreaType?.displayName}")
        }
    }

    private fun enableTapModeForButton(areaType: AreaType) {
        if (waitingForTap) return
        waitingForTap = true
        tapListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                // Гарантируем, что left < right, top < bottom, и размер хотя бы 2px
                val left = x
                val top = y
                val right = x + 2
                val bottom = y + 2
                Log.d("AreaConfig", "Tap для ${areaType.displayName}: left=$left, top=$top, right=$right, bottom=$bottom")
                val rect = Rect(left, top, right, bottom)
                prefsManager.saveArea(areaType, rect)
                configuredAreas.add(areaType)
                updateProgressCounter()
                Toast.makeText(this, "Координаты кнопки сохранены: x=$x, y=$y", Toast.LENGTH_SHORT).show()
                areaIndex++
                waitingForTap = false
                disableTapModeForButton()
                showNextArea()
                return@OnTouchListener true
            }
            false
        }
        hintView?.setOnTouchListener(tapListener)
        // Отключаем кнопки сохранения/пропуска для кнопок
        btnSave.isEnabled = false
        btnSkip.isEnabled = false
    }

    private fun disableTapModeForButton() {
        waitingForTap = false
        tapListener = null
        hintView?.setOnTouchListener(null)
        btnSave.isEnabled = true
        btnSkip.isEnabled = true
    }

    private fun saveCurrentArea() {
        // Для областей (не кнопок) - сохраняем выделенный прямоугольник
        if (!buttonAreaTypes.contains(currentAreaType)) {
            val selection = selectionView?.getAbsoluteSelection()
            if (selection == null) {
                Toast.makeText(this, "Сначала выделите область", Toast.LENGTH_SHORT).show()
                return
            }
            
            currentAreaType?.let { areaType ->
                Log.d("AreaConfig", "Сохраняем область: $selection для ${areaType.name}")
                
                if (!CoordinateUtils.validateCoordinates(selection, this)) {
                    Log.w("AreaConfig", "Координаты некорректны, корректируем...")
                    val correctedSelection: Rect = CoordinateUtils.correctCoordinates(selection, this)
                    Log.d("AreaConfig", "Скорректированная область: $correctedSelection")
                    prefsManager.saveArea(areaType, correctedSelection)
                } else {
                    prefsManager.saveArea(areaType, selection)
                }
                
                configuredAreas.add(areaType)
                updateProgressCounter()
                saveAreaScreenshot(areaType, selection)
                
                val screenshotsDir = File(getExternalFilesDir(null), "area_screenshots")
                Toast.makeText(this, "Область ${areaType.displayName} сохранена\nСкриншот: ${screenshotsDir.absolutePath}", Toast.LENGTH_LONG).show()
                
                areaIndex++
                showNextArea()
            }
        }
    }

    private fun skipCurrentArea() {
        Log.d("AreaConfig", "Пропускаем область: ${currentAreaType?.name}")
        Log.d("AreaConfig", "configuredAreas до пропуска: $configuredAreas")
        
        // Добавляем пропущенную область в configuredAreas, чтобы не возвращаться к ней
        currentAreaType?.let { areaType ->
            configuredAreas.add(areaType)
            Log.d("AreaConfig", "Пропущенная область ${areaType.name} добавлена в configuredAreas: $configuredAreas")
        }
        
        // Скрываем overlay для кнопок, если был активен
        hideButtonTapOverlay()
        
        areaIndex++
        showNextArea()
    }

    private fun onConfigurationComplete() {
        Log.d("AreaConfig", "Конфигурация областей завершена")
        Toast.makeText(this, "Конфигурация завершена!", Toast.LENGTH_LONG).show()
        // Удаляем overlay-конфигуратор
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatingView = null
            floatingParams = null
        }
        stopSelf()
    }

    private fun removeTransparentHint() {
        hintView?.let {
            windowManager.removeView(it)
            hintView = null
            selectionView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        // Очищаем MediaProjection ресурсы
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        
        floatingView?.let { windowManager.removeView(it) }
        hintView?.let { windowManager.removeView(it) }
        configMenuView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        hideButtonTapOverlay()
        // Сбрасываем флаг принудительно
        isButtonTapOverlayShown = false
        floatingView = null
        hintView = null
        selectionView = null
        configMenuView = null
        configMenuParams = null
    }

    private fun saveAreaScreenshot(areaType: AreaType, rect: Rect) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d("AreaConfigurationService", "Создаем скриншот для области: ${areaType.displayName}")
                
                // Создаем скриншот экрана
                val screenshot = createScreenshot()
                if (screenshot != null) {
                    // Обрезаем область
                    val areaBitmap = Bitmap.createBitmap(
                        screenshot,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    
                    // Сохраняем в файл
                    saveBitmapToFile(areaBitmap, areaType)
                    
                    Log.d("AreaConfigurationService", "Скриншот области сохранен: ${areaType.displayName}")
                } else {
                    Log.e("AreaConfigurationService", "Не удалось создать скриншот экрана")
                }
            } catch (e: Exception) {
                Log.e("AreaConfigurationService", "Ошибка сохранения скриншота области", e)
            }
        }
    }

    private fun createScreenshot(): Bitmap? {
        return try {
            // Получаем размеры экрана
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            
            Log.d("AreaConfigurationService", "Создаем скриншот: ${width}x${height}")
            
            // Пытаемся создать реальный скриншот с MediaProjection
            val realScreenshot = createRealScreenshot()
            if (realScreenshot != null) {
                Log.d("AreaConfigurationService", "Реальный скриншот создан успешно")
                return realScreenshot
            }
            
            // Если не удалось создать реальный скриншот, создаем заглушку
            Log.d("AreaConfigurationService", "Создаем заглушку скриншота")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Создаем canvas для рисования
            val canvas = Canvas(bitmap)
            
            // Рисуем содержимое экрана
            // Примечание: это упрощенная версия. Для полного захвата экрана нужно использовать MediaProjection API
            // Но для целей отладки областей этого достаточно
            canvas.drawColor(Color.WHITE) // Рисуем белый фон как заглушку
            
            // Добавляем текст с информацией о времени создания
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 40f
                isAntiAlias = true
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Screenshot created at: $timestamp", 50f, 100f, paint)
            canvas.drawText("This is a placeholder screenshot", 50f, 150f, paint)
            canvas.drawText("Real screenshot requires MediaProjection API", 50f, 200f, paint)
            
            bitmap
        } catch (e: Exception) {
            Log.e("AreaConfigurationService", "Ошибка создания скриншота", e)
            null
        }
    }

    private fun createRealScreenshot(): Bitmap? {
        return try {
            // Используем локальные переменные разрешения
            val code = projectionResultCode
            val data = projectionResultData
            Log.d("AreaConfig", "createRealScreenshot: code=$code, data=$data")
            
            // Также проверяем сохраненные разрешения
            val savedPermission = prefsManager.getMediaProjectionPermission()
            Log.d("AreaConfig", "Сохраненные разрешения: $savedPermission")
            
            if (code == null || data == null) {
                Log.d("AreaConfigurationService", "Нет разрешения MediaProjection (code/data null)")
                return null
            }
            mediaProjection = projectionManager.getMediaProjection(code, data)
            Log.d("AreaConfig", "mediaProjection создан: $mediaProjection")
            if (mediaProjection == null) {
                Log.d("AreaConfigurationService", "Не удалось создать MediaProjection")
                return null
            }
            
            // Получаем размеры экрана
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            Log.d("AreaConfigurationService", "Создаем ImageReader: ${width}x${height}")
            
            // Создаем ImageReader
            imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            
            // Создаем VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            
            if (virtualDisplay == null) {
                Log.d("AreaConfigurationService", "Не удалось создать VirtualDisplay")
                return null
            }
            
            // Ждем немного для стабилизации
            Thread.sleep(400)
            
            // Пробуем получить изображение несколько раз
            var image = imageReader?.acquireLatestImage()
            var attempts = 0
            while (image == null && attempts < 3) {
                Thread.sleep(200)
                image = imageReader?.acquireLatestImage()
                attempts++
            }
            if (image == null) {
                Log.d("AreaConfigurationService", "Не удалось получить изображение после повторных попыток")
                return null
            }
            
            // Конвертируем изображение в Bitmap
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            
            // Очищаем ресурсы
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            
            Log.d("AreaConfigurationService", "Реальный скриншот создан: ${bitmap.width}x${bitmap.height}")
            bitmap
            
        } catch (e: Exception) {
            Log.e("AreaConfigurationService", "Ошибка создания реального скриншота", e)
            null
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, areaType: AreaType) {
        try {
            // Создаем папку для скриншотов
            val screenshotsDir = File(getExternalFilesDir(null), "area_screenshots")
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }
            
            // Создаем имя файла с временной меткой
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${areaType.name}_${timestamp}.png"
            val file = File(screenshotsDir, fileName)
            
            Log.d("AreaConfigurationService", "Сохраняем скриншот в: ${file.absolutePath}")
            
            // Сохраняем bitmap в файл
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            Log.d("AreaConfigurationService", "Скриншот сохранен: ${file.absolutePath}")
            Log.d("AreaConfigurationService", "Размер файла: ${file.length()} байт")
            
            // Показываем путь к папке в логах
            Log.d("AreaConfigurationService", "Папка со скриншотами: ${screenshotsDir.absolutePath}")
            
        } catch (e: Exception) {
            Log.e("AreaConfigurationService", "Ошибка сохранения скриншота в файл", e)
        }
    }

    private fun testCoordinates() {
        val relativeSelection = selectionView?.getSelection()
        val absoluteSelection = selectionView?.getAbsoluteSelection()
        val absoluteSelectionWithInsets = selectionView?.getAbsoluteSelectionWithInsets()
        
        Log.d("AreaConfig", "=== ТЕСТ КООРДИНАТ ===")
        Log.d("AreaConfig", "Относительные координаты: $relativeSelection")
        Log.d("AreaConfig", "Абсолютные координаты (без учёта отступов): $absoluteSelection")
        Log.d("AreaConfig", "Абсолютные координаты с учётом отступов: $absoluteSelectionWithInsets")
        
        // Получаем информацию о view
        val viewInfo = selectionView?.getViewInfo()
        Log.d("AreaConfig", "Информация о view: $viewInfo")
        
        // Получаем размеры экрана
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        Log.d("AreaConfig", "Размеры экрана: ${screenWidth}x${screenHeight}")
        
        // Получаем позицию окна
        val windowX = hintParams?.x ?: 0
        val windowY = hintParams?.y ?: 0
        Log.d("AreaConfig", "Позиция окна: x=$windowX, y=$windowY")
        
        // Проверяем все варианты координат
        absoluteSelection?.let { rect ->
            val isValid = CoordinateUtils.validateCoordinates(rect, this)
            Log.d("AreaConfig", "Обычные абсолютные координаты валидны: $isValid")
        }
        
        absoluteSelectionWithInsets?.let { rect ->
            val isValid = CoordinateUtils.validateCoordinates(rect, this)
            Log.d("AreaConfig", "Координаты с отступами валидны: $isValid")
            
            if (!isValid) {
                val corrected = CoordinateUtils.correctCoordinates(rect, this)
                Log.d("AreaConfig", "Скорректированные координаты с отступами: $corrected")
            }
        }
        
        // Получаем системные отступы
        val insets = CoordinateUtils.getSystemInsets(this)
        Log.d("AreaConfig", "Системные отступы: statusBar=${insets.statusBarHeight}, navigationBar=${insets.navigationBarHeight}")
        
        Toast.makeText(this, "Координаты записаны в лог", Toast.LENGTH_SHORT).show()
    }

    /**
     * Создает полноэкранный прозрачный overlay для перехвата касаний кнопок
     */
    private fun createButtonTapOverlay() {
        if (buttonTapOverlay != null) {
            Log.d("AreaConfig", "Overlay для кнопок уже создан")
            return
        }
        
        Log.d("AreaConfig", "Создаем полноэкранный overlay для кнопок")
        
        // Создаем простой прозрачный View на весь экран
        buttonTapOverlay = View(this).apply {
            setBackgroundColor(Color.argb(20, 255, 0, 0)) // Слегка красноватый для отладки
            isClickable = true
            isFocusable = false
        }
        
        // Настраиваем параметры для полноэкранного overlay
        buttonTapParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        Log.d("AreaConfig", "Полноэкранный overlay для кнопок создан")
    }

    /**
     * Показывает полноэкранный overlay для кнопок
     */
    private fun showButtonTapOverlay() {
        // Проверяем, не показан ли уже overlay
        if (isButtonTapOverlayShown) {
            Log.d("AreaConfig", "Overlay для кнопок уже показан, пропускаем")
            return
        }
        
        createButtonTapOverlay()
        if (buttonTapOverlay != null && buttonTapParams != null) {
            try {
                windowManager.addView(buttonTapOverlay, buttonTapParams)
                isButtonTapOverlayShown = true
                Log.d("AreaConfig", "Полноэкранный overlay для кнопок показан")
                
                // Устанавливаем tapListener на этот overlay
                buttonTapOverlay?.setOnTouchListener(tapListener)
                Log.d("AreaConfig", "tapListener установлен на buttonTapOverlay")
            } catch (e: Exception) {
                Log.e("AreaConfig", "Ошибка при показе overlay для кнопок", e)
            }
        }
    }

    /**
     * Скрывает полноэкранный overlay для кнопок  
     */
    private fun hideButtonTapOverlay() {
        if (!isButtonTapOverlayShown) {
            Log.d("AreaConfig", "Overlay для кнопок уже скрыт, пропускаем")
            return
        }
        
        buttonTapOverlay?.let { overlay ->
            try {
                windowManager.removeView(overlay)
                isButtonTapOverlayShown = false
                Log.d("AreaConfig", "Полноэкранный overlay для кнопок скрыт")
            } catch (e: Exception) {
                Log.e("AreaConfig", "Ошибка при скрытии overlay для кнопок", e)
                // Если ошибка - сбрасываем флаг принудительно
                isButtonTapOverlayShown = false
            }
        }
        buttonTapOverlay = null
    }
}
