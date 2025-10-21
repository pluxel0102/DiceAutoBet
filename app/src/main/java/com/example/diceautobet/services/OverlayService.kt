package com.example.diceautobet.services

import android.annotation.SuppressLint
import android.app.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.example.diceautobet.MainActivity
import com.example.diceautobet.R
import com.example.diceautobet.databinding.LayoutFloatingCollapsedBinding
import com.example.diceautobet.databinding.LayoutFloatingWindowBinding
import com.example.diceautobet.databinding.LayoutFloatingSingleModeBinding
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.CoordinateUtils
import com.example.diceautobet.utils.FileLogger
import com.example.diceautobet.controllers.SingleModeController
import com.example.diceautobet.services.AutoClickService
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.io.File
import kotlinx.coroutines.CancellationException
import com.example.diceautobet.models.RoundResult
import com.example.diceautobet.models.BetChoice
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.ScreenArea
import com.example.diceautobet.models.GameState
import com.example.diceautobet.opencv.DotCounter
import android.util.Log
import java.lang.Math
import androidx.appcompat.view.ContextThemeWrapper
import android.graphics.Color


class OverlayService : Service() {

    enum class GameMode {
        DUAL,   // Двойной режим (оригинальный)
        SINGLE  // Одиночный режим (новый)
    }
    
    // Функции конвертации между enum и string
    private fun stringToGameMode(mode: String): GameMode {
        return when (mode.lowercase()) {
            "single" -> GameMode.SINGLE
            "dual" -> GameMode.DUAL
            else -> GameMode.DUAL // по умолчанию
        }
    }
    
    private fun gameModeToString(mode: GameMode): String {
        return when (mode) {
            GameMode.SINGLE -> "single"
            GameMode.DUAL -> "dual"
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "DiceAutoBetChannel"

        const val ACTION_STOP             = "com.example.diceautobet.ACTION_STOP"
        const val ACTION_START_PROJECTION = "com.example.diceautobet.ACTION_START_PROJECTION"
        const val ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED"
        const val EXTRA_RESULT_CODE       = "result_code"
        const val EXTRA_RESULT_DATA       = "result_data"

        @Volatile
        var isRequestingProjection = false

        private var instance: OverlayService? = null
        fun getInstance(): OverlayService? = instance
    }

    // region system
    private lateinit var windowManager      : WindowManager
    private lateinit var prefsManager       : PreferencesManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var projectionManager  : MediaProjectionManager
    // endregion

    // region overlay views
    private var floatingView : View? = null
    private var collapsedView: View? = null
    private lateinit var expandedBinding : LayoutFloatingWindowBinding
    private lateinit var collapsedBinding: LayoutFloatingCollapsedBinding
    private lateinit var singleModeBinding: LayoutFloatingSingleModeBinding
    private var isExpanded = true
    // endregion

    // region screen-capture
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay : VirtualDisplay?  = null
    private var imageReader    : ImageReader?     = null
    // endregion

    // region game
    private var currentMode = GameMode.DUAL  // Текущий режим игры
    private var gameState = GameState()
    private val gameScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob  : Job? = null
    private var lastToggleTime = 0L  // Защита от двойного нажатия
    
    // Single mode controller
    private var singleModeController: SingleModeController? = null

    private val savedAreas = mutableMapOf<AreaType, ScreenArea>()
    private var totalWins = 0
    private var totalLosses = 0
    private var totalBalance = 0
    private var lastResult: RoundResult? = null // Предыдущий результат для сравнения
    private var lastResultTime = 0L // Время последнего результата для обнаружения зависаний
    // УДАЛЕНО: lastRoundWasLoss - не нужно в новой альтернирующей стратегии
    // endregion

    private val uiHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────── Result Validation & Monitoring ──────────────────────────────

    private var resultHistory = mutableListOf<RoundResult>()
    private val maxHistorySize = 10

    // УЛУЧШЕННАЯ система валидации результатов с более мягкими критериями
    private fun validateResultWithHistory(result: RoundResult): Boolean {
        // Базовая валидация
        if (!result.isValid) {
            Log.d("OverlayService", "Результат не прошел базовую валидацию")
            return false
        }

        // Более мягкая проверка уверенности
        if (result.confidence < 0.25f) {
            Log.d("OverlayService", "Слишком низкая уверенность: ${result.confidence}")
            return false
        }

        // Проверяем логичность результата
        val totalDots = result.redDots + result.orangeDots
        if (totalDots < 1 || totalDots > 12) {
            Log.d("OverlayService", "Недопустимое количество точек: $totalDots")
            return false
        }

        // Проверяем историю результатов (более мягко)
        if (resultHistory.isNotEmpty()) {
            val lastResult = resultHistory.last()

            // Проверяем на резкие изменения (более мягко)
            val redDiff = kotlin.math.abs(result.redDots - lastResult.redDots)
            val orangeDiff = kotlin.math.abs(result.orangeDots - lastResult.orangeDots)

            if (redDiff > 5 || orangeDiff > 5) {
                Log.d("OverlayService", "Слишком резкое изменение: redDiff=$redDiff, orangeDiff=$orangeDiff")
                // Не отклоняем, только логируем
            }

            // Проверяем на повторяющиеся паттерны (более мягко)
            val similarResults = resultHistory.count {
                it.redDots == result.redDots && it.orangeDots == result.orangeDots
            }
            if (similarResults > 5) {
                Log.d("OverlayService", "Слишком много повторяющихся результатов: $similarResults")
                // Не отклоняем, только логируем
            }
        }

        // Добавляем в историю
        resultHistory.add(result)
        if (resultHistory.size > maxHistorySize) {
            resultHistory.removeAt(0)
        }

        Log.d("OverlayService", "Результат прошел валидацию: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
        return true
    }

    // Система мониторинга производительности распознавания
    private var recognitionStats = RecognitionStats()

    private data class RecognitionStats(
        var totalAttempts: Int = 0,
        var successfulRecognitions: Int = 0,
        var failedRecognitions: Int = 0,
        var averageConfidence: Float = 0.0f,
        var lastUpdateTime: Long = 0
    ) {
        fun updateStats(result: RoundResult?) {
            totalAttempts++
            if (result != null && result.isValid) {
                successfulRecognitions++
                averageConfidence = (averageConfidence * (successfulRecognitions - 1) + result.confidence) / successfulRecognitions
            } else {
                failedRecognitions++
            }
            lastUpdateTime = System.currentTimeMillis()
        }

        fun getSuccessRate(): Float {
            return if (totalAttempts > 0) successfulRecognitions.toFloat() / totalAttempts else 0.0f
        }

        fun logStats() {
            Log.d("OverlayService", "=== СТАТИСТИКА РАСПОЗНАВАНИЯ ===")
            Log.d("OverlayService", "Всего попыток: $totalAttempts")
            Log.d("OverlayService", "Успешных: $successfulRecognitions")
            Log.d("OverlayService", "Неудачных: $failedRecognitions")
            Log.d("OverlayService", "Процент успеха: ${getSuccessRate() * 100}%")
            Log.d("OverlayService", "Средняя уверенность: $averageConfidence")
        }
    }

    // Улучшенный метод анализа с дополнительными проверками
    private fun analyzeDiceAreaWithValidation(screenshot: Bitmap, diceRect: android.graphics.Rect): RoundResult? {
        return try {
            Log.d("OverlayService", "Начинаем анализ области кубиков...")

            val result = analyzeDiceArea(screenshot, diceRect)

            // Обновляем статистику
            recognitionStats.updateStats(result)

            // Валидируем результат
            if (result != null && !validateResultWithHistory(result)) {
                Log.d("OverlayService", "Результат не прошел валидацию с историей")
                return null
            }

            // Логируем статистику каждые 10 попыток
            if (recognitionStats.totalAttempts % 10 == 0) {
                recognitionStats.logStats()
            }

            return result
        } catch (e: Exception) {
            Log.e("OverlayService", "❌ КРИТИЧЕСКАЯ ОШИБКА в analyzeDiceAreaWithValidation", e)
            Log.e("OverlayService", "Размер скриншота: ${screenshot.width}x${screenshot.height}")
            Log.e("OverlayService", "Область кубиков: ${diceRect.toShortString()}")
            e.printStackTrace()
            return null
        }
    }

    // ────────────────────────────── Service lifecycle ───────────────────────────
    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager       = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefsManager        = PreferencesManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        projectionManager   = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        
        // Загружаем сохранённый режим игры
        loadGameMode()
        
        // Инициализируем SingleModeController
        initializeSingleModeController()

        loadSavedAreas()
    }

    private var isForegroundStarted = false

    private fun startForegroundSafely() {
        if (isForegroundStarted) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            isForegroundStarted = true
            Log.d("OverlayService", "Foreground service запущен успешно")
        } catch (e: SecurityException) {
            Log.e("OverlayService", "Ошибка startForeground: ${e.message}")
            // Не останавливаем сервис, просто работаем как обычный сервис
            Log.w("OverlayService", "Продолжаем работу как обычный сервис")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SETTINGS_CHANGED -> {
                Log.d("OverlayService", "Получено уведомление об изменении настроек")
                updateGameSettings()
                return START_STICKY
            }
            ACTION_START_PROJECTION -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                Log.d("OverlayService", "ACTION_START_PROJECTION: code=$code, data=$data")
                if (code == Activity.RESULT_OK && data != null) {
                    // Сохраняем разрешение в постоянное хранилище
                    prefsManager.saveMediaProjectionPermission(code, data)
                    Log.d("OverlayService", "Разрешение сохранено: code=$code")
                    showToast("Разрешение сохранено: $code")
                    isRequestingProjection = false
                    startMediaProjection(code, data)
                } else {
                    Log.d("OverlayService", "Неверный resultCode: $code - разрешение не сохраняется")
                    isRequestingProjection = false
                    // Очищаем любое некорректное сохраненное разрешение
                    prefsManager.clearMediaProjectionPermission()
                }
            }
            else -> {
                if (floatingView == null) {
                    // Запускаем foreground service при создании UI
                    startForegroundSafely()
                    createFloatingWindow()
                }
                // Попробуем восстановить разрешение из постоянного хранилища
                if (mediaProjection == null) {
                    val permissionData = prefsManager.getMediaProjectionPermission()
                    Log.d("OverlayService", "Проверяем сохраненное разрешение: data=$permissionData")
                    if (permissionData != null) {
                        val (resultCode, resultData) = permissionData
                        Log.d("OverlayService", "Восстанавливаем разрешение: code=$resultCode")
                        showToast("Восстанавливаем разрешение: $resultCode")
                        startMediaProjection(resultCode, resultData)
                    } else {
                        Log.d("OverlayService", "Нет сохраненного разрешения")
                        showToast("Нет сохраненного разрешения")
                        if (!isRequestingProjection) {
                            isRequestingProjection = true
                            requestMediaProjection()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        try {
            Log.d("OverlayService", "onDestroy: уничтожаем сервис")
            gameScope.cancel()
            stopMediaProjection()
            removeFloatingWindow()
            Log.d("OverlayService", "onDestroy: сервис уничтожен")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка в onDestroy", e)
        }
        instance = null
        super.onDestroy()
    }

    // ─────────────────────────────── Areas loading ──────────────────────────────
    private fun loadSavedAreas() {
        Log.d("OverlayService", "Загружаем сохраненные области")
        AreaType.values().forEach { areaType ->
            prefsManager.loadAreaUniversal(areaType)?.let { area ->
                savedAreas[areaType] = area
                Log.d("OverlayService", "Загружена область: $areaType = ${area.rect}")
                Log.d("OverlayService", "  - left: ${area.rect.left}, top: ${area.rect.top}")
                Log.d("OverlayService", "  - right: ${area.rect.right}, bottom: ${area.rect.bottom}")
                Log.d("OverlayService", "  - centerX: ${area.rect.centerX()}, centerY: ${area.rect.centerY()}")
            } ?: run {
                Log.d("OverlayService", "Область не найдена: $areaType")
            }
        }
        Log.d("OverlayService", "Всего загружено областей: ${savedAreas.size}")

        // Специальная проверка для кнопки удвоения
        val doubleButtonArea = savedAreas[AreaType.DOUBLE_BUTTON]
        if (doubleButtonArea == null) {
            Log.w("OverlayService", "⚠️ ВАЖНО: Область кнопки удвоения (DOUBLE_BUTTON) не настроена!")
            Log.w("OverlayService", "Это может привести к тому, что при проигрыше не будет происходить удвоение ставки")
        } else {
            Log.d("OverlayService", "✅ Область кнопки удвоения настроена: ${doubleButtonArea.rect}")
        }
    }

    // ─────────────────────────────── Notification ───────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Dice Auto Bet", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopSrv = PendingIntent.getService(
            this, 0,
            Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dice)
            .setContentTitle("Dice Auto Bet")
            .setContentText("Сервис запущен")
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_loss, "Остановить", stopSrv)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ───────────────────────────── Overlay window ───────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWindow() {
        Log.d("OverlayService", "Создаем плавающее окно")

        // Используем ContextThemeWrapper для Material темы
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DiceAutoBet)
        val inflater = LayoutInflater.from(themedContext)

        // Выбираем layout в зависимости от режима
        if (currentMode == GameMode.SINGLE) {
            floatingView = inflater.inflate(R.layout.layout_floating_single_mode, null)
            singleModeBinding = LayoutFloatingSingleModeBinding.bind(floatingView!!)
        } else {
            floatingView = inflater.inflate(R.layout.layout_floating_window, null)
            expandedBinding = LayoutFloatingWindowBinding.bind(floatingView!!)
        }
        
        collapsedView = inflater.inflate(R.layout.layout_floating_collapsed, null)
        collapsedBinding = LayoutFloatingCollapsedBinding.bind(collapsedView!!)

        val lp = WindowManager.LayoutParams().apply {
            width  = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            flags  = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        showExpandedView()
        windowManager.addView(floatingView , lp)
        windowManager.addView(collapsedView, lp)

        setupWindowDrag(lp)
        setupButtons()
        // Явно показываем, что игра остановлена до нажатия Старт
        gameState = gameState.copy(isRunning = false, isPaused = false)
        
        // Инициализируем состояние кнопок в зависимости от режима
        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            try {
                singleModeBinding.btnStartStop.text = "Старт"
                singleModeBinding.btnPause.isEnabled = false
            } catch (_: Exception) { }
        } else if (::expandedBinding.isInitialized) {
            try {
                expandedBinding.btnStartStop.text = "Старт"
                expandedBinding.btnPause.isEnabled = false
                // Обновляем состояние кнопки режима
                updateModeToggleButton()
            } catch (_: Exception) { }
        }
        updateUI()

        Log.d("OverlayService", "Плавающее окно создано успешно")
    }

    private fun setupWindowDrag(lp: WindowManager.LayoutParams) {
        Log.d("OverlayService", "Настраиваем перетаскивание окна")

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        val listener = View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    Log.d("OverlayService", "Начало перетаскивания: startX=$startX, startY=$startY")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    windowManager.updateViewLayout(
                        if (isExpanded) floatingView else collapsedView, lp
                    )
                    Log.d("OverlayService", "Перетаскивание: x=${lp.x}, y=${lp.y}")
                    true
                }
                else -> false
            }
        }
        floatingView ?.setOnTouchListener(listener)
        collapsedView?.setOnTouchListener(listener)

        Log.d("OverlayService", "Перетаскивание окна настроено")
    }

    private fun setupButtons() {
        Log.d("OverlayService", "Настраиваем кнопки плавающего окна")

        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            setupSingleModeButtons()
        } else if (::expandedBinding.isInitialized) {
            setupDualModeButtons()
        }

        // Настраиваем кнопку свернутого состояния независимо от режима
        if (::collapsedBinding.isInitialized) {
            collapsedBinding.btnExpand.setOnClickListener {
                Log.d("OverlayService", "Кнопка развертывания нажата")
                toggleView()
            }
        }

        Log.d("OverlayService", "Кнопки настроены")
    }

    private fun setupSingleModeButtons() = with(singleModeBinding) {
        Log.d("OverlayService", "Настраиваем кнопки одиночного режима")

        btnStartStop.setOnClickListener {
            Log.d("OverlayService", "Кнопка Старт/Стоп нажата (одиночный режим)")
            toggleGame()
        }
        btnPause.setOnClickListener {
            Log.d("OverlayService", "Кнопка паузы нажата (одиночный режим)")
            togglePause()
        }
        btnHide.setOnClickListener {
            Log.d("OverlayService", "Кнопка скрытия нажата (одиночный режим)")
            toggleView()
        }
        btnSendLogs.setOnClickListener {
            Log.d("OverlayService", "Кнопка отправки логов нажата")
            FileLogger.i("OverlayService", "Пользователь запросил отправку логов")
            sendLogsToUser()
        }
    }

    private fun setupDualModeButtons() = with(expandedBinding) {
        Log.d("OverlayService", "Настраиваем кнопки двойного режима")

        btnModeToggle.setOnClickListener {
            Log.d("OverlayService", "Кнопка переключения режима нажата")
            toggleGameMode()
        }
        btnMinimize  .setOnClickListener {
            Log.d("OverlayService", "Кнопка минимизации нажата")
            toggleView()
        }
        btnStartStop .setOnClickListener {
            Log.d("OverlayService", "Кнопка Старт/Стоп нажата")
            toggleGame()
        }
        btnPause     .setOnClickListener {
            Log.d("OverlayService", "Кнопка паузы нажата")
            togglePause()
        }
        btnTestDouble.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования удвоения нажата")
            testDoubleProcess()
        }
        expandedBinding.btnTestWin.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования выигрыша нажата")
            testWinLogic()
        }
        expandedBinding.btnTestResult.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования обнаружения результата нажата")
            testResultDetection()
        }
        expandedBinding.btnTestNewRoll.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования обнаружения нового броска нажата")
            testNewRollDetection()
        }
        expandedBinding.btnTestResultComparison.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования сравнения результатов нажата")
            testResultComparison()
        }
        expandedBinding.btnTestDoubleOnly.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования только кнопки удвоения нажата")
            showToast("🔄 Тест удвоения отключен - используется новая альтернирующая стратегия")
        }

        // Добавляем новые кнопки для тестирования улучшенных функций
        expandedBinding.btnTestButtonUnlock.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования разблокировки кнопок нажата")
            // testButtonUnlockDetection() // Функция не реализована
            showToast("Функция тестирования разблокировки не реализована")
        }

        expandedBinding.btnTestImprovedDouble.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования улучшенного удвоения нажата")
            // testImprovedDoubleProcess() // Функция не реализована
            showToast("Функция тестирования улучшенного удвоения не реализована")
        }

        expandedBinding.btnTestDoublingLogic.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования логики удвоения нажата")
            // testDoublingLogic() // Функция не реализована
            showToast("Функция тестирования логики удвоения не реализована")
        }

        expandedBinding.btnTestBetSetup.setOnClickListener {
            Log.d("OverlayService", "Кнопка тестирования установки ставки нажата")
            // testCorrectBetSetup() // Функция не реализована
            showToast("Функция тестирования установки ставки не реализована")
        }
    }

    private fun toggleView() {
        Log.d("OverlayService", "Переключаем вид плавающего окна: isExpanded=$isExpanded")
        isExpanded = !isExpanded
        if (isExpanded) {
            Log.d("OverlayService", "Показываем развернутый вид")
            showExpandedView()
        } else {
            Log.d("OverlayService", "Показываем свернутый вид")
            showCollapsedView()
        }
    }

    private fun showExpandedView()  {
        Log.d("OverlayService", "Показываем развернутый вид")
        floatingView?.visibility = View.VISIBLE
        collapsedView?.visibility = View.GONE
        Log.d("OverlayService", "Развернутый вид показан")
    }

    private fun showCollapsedView() {
        Log.d("OverlayService", "Показываем свернутый вид")
        floatingView?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE
        Log.d("OverlayService", "Свернутый вид показан")
    }

    private fun toggleGameMode() {
        Log.d("OverlayService", "Переключаем режим игры")
        Log.d("OverlayService", "Текущий режим ДО переключения: $currentMode")
        
        // Если игра запущена, останавливаем её перед сменой режима
        if (gameState.isRunning) {
            Log.d("OverlayService", "Останавливаем игру перед сменой режима")
            stopGame()
            showToast("🔄 Игра остановлена для смены режима")
        }
        
        // Переключаем режим
        currentMode = if (currentMode == GameMode.DUAL) GameMode.SINGLE else GameMode.DUAL
        
        Log.d("OverlayService", "Новый режим ПОСЛЕ переключения: $currentMode")
        
        // Сохраняем новый режим
        saveGameMode()
        
        // Пересоздаем плавающее окно с новым layout'ом
        recreateFloatingWindow()
        
        // Показываем уведомление
        val modeText = if (currentMode == GameMode.DUAL) "Двойной" else "Одиночный"
        showToast("🎯 Режим переключен: $modeText")
        
        // Обновляем UI
        updateUI()
        
        Log.d("OverlayService", "Режим переключен на: $currentMode")
    }

    private fun recreateFloatingWindow() {
        Log.d("OverlayService", "Пересоздаем плавающее окно для нового режима")
        
        // Удаляем старые view
        try {
            floatingView?.let { windowManager.removeView(it) }
            collapsedView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка при удалении старых view: ${e.message}")
        }
        
        // Создаем новое окно
        createFloatingWindow()
        
        Log.d("OverlayService", "Плавающее окно пересоздано")
    }

    private fun loadGameMode() {
        val savedModeString = prefsManager.getGameMode()
        currentMode = stringToGameMode(savedModeString)
        Log.d("OverlayService", "Загружен режим игры: $currentMode")
    }

    private fun saveGameMode() {
        val modeString = gameModeToString(currentMode)
        prefsManager.saveGameMode(modeString)
        Log.d("OverlayService", "Сохранён режим игры: $modeString")
    }

    private fun initializeSingleModeController() {
        try {
            singleModeController = SingleModeController(
                context = this,
                takeScreenshot = { callback ->
                    gameScope.launch {
                        val screenshot = captureScreen()
                        callback(screenshot)
                    }
                },
                performClick = { x, y, callback ->
                    performClick(x, y)
                    callback(true)
                },
                preferencesManager = prefsManager
            ).apply {
                // Настраиваем callback для обновления UI при изменении состояния
                onGameStateChanged = { controllerState ->
                    uiHandler.post {
                        updateUI()
                    }
                }
            }
            
            // ИСПРАВЛЕНИЕ: Вызываем initialize() для загрузки областей
            singleModeController?.initialize()
            
            Log.d("OverlayService", "SingleModeController инициализирован и настроен")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка инициализации SingleModeController", e)
        }
    }

    private fun performClick(x: Int, y: Int) {
        // Используем существующий AutoClickService для выполнения клика
        val clickRect = android.graphics.Rect(x - 5, y - 5, x + 5, y + 5)
        AutoClickService.performClick(clickRect) { success ->
            Log.d("OverlayService", "Клик выполнен в ($x, $y): success=$success")
        }
    }

    private fun removeFloatingWindow() {
        try {
            Log.d("OverlayService", "Удаляем плавающее окно")
            Log.d("OverlayService", "Состояние перед удалением: floatingView=$floatingView, collapsedView=$collapsedView")

            floatingView ?.let { windowManager.removeView(it) }
            collapsedView?.let { windowManager.removeView(it) }
            floatingView = null
            collapsedView = null

            Log.d("OverlayService", "Плавающее окно удалено")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка удаления плавающего окна", e)
        }
    }

    // ──────────────────────────── Screen-capture ────────────────────────────────
    private fun requestMediaProjection() {
        Log.d("OverlayService", "Запрашиваем разрешение на захват экрана")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("request_projection", true)
        }
        startActivity(intent)
        Log.d("OverlayService", "Intent для запроса разрешения отправлен")
    }

    private fun startMediaProjection(resultCode: Int, resultData: Intent?) {
        if (resultData == null) {
            Log.e("OverlayService", "resultData is null, cannot start MediaProjection")
            return
        }
        if (mediaProjection != null) {
            Log.d("OverlayService", "MediaProjection уже существует, игнорируем")
            return
        }

        try {
            Log.d("OverlayService", "Создаем MediaProjection: resultCode=$resultCode")
            Log.d("OverlayService", "ResultData: $resultData")
            showToast("Создаем MediaProjection...")

            // Проверяем валидность resultCode
            if (resultCode != Activity.RESULT_OK) {
                Log.e("OverlayService", "Неверный resultCode: $resultCode (ожидается ${Activity.RESULT_OK})")
                throw Exception("Неверный код результата: $resultCode")
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e("OverlayService", "Ошибка: MediaProjection = null")
                Log.e("OverlayService", "Параметры: resultCode=$resultCode, resultData=$resultData")
                // Если не удалось создать MediaProjection, очищаем сохраненное разрешение
                prefsManager.clearMediaProjectionPermission()
                showToast("Ошибка восстановления разрешения на захват экрана")
                throw Exception("Не удалось создать MediaProjection")
            }

            Log.d("OverlayService", "MediaProjection создан успешно")

            // Добавляем callback для отслеживания состояния MediaProjection
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w("OverlayService", "MediaProjection остановлен пользователем или системой")
                    showToast("Разрешение на захват экрана отозвано")
                    stopMediaProjection()
                    // Принудительно запрашиваем разрешение заново
                    if (!isRequestingProjection) {
                        isRequestingProjection = true
                        requestMediaProjection()
                    }
                }
            }, uiHandler)

            val metrics: DisplayMetrics = resources.displayMetrics
            val density = metrics.densityDpi
            val w = metrics.widthPixels
            val h = metrics.heightPixels

            Log.d("OverlayService", "Настройки экрана: width=$w, height=$h, density=$density")

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            if (imageReader != null) {
                Log.d("OverlayService", "ImageReader создан успешно")

                imageReader?.setOnImageAvailableListener({ reader ->
                    // Обработка изображений будет происходить в captureScreen()
                }, uiHandler)

                val surface = imageReader?.surface
                if (surface != null) {
                    Log.d("OverlayService", "Surface получен: $surface")
                } else {
                    Log.e("OverlayService", "Ошибка: Surface = null")
                    throw Exception("Не удалось получить Surface из ImageReader")
                }
            } else {
                Log.e("OverlayService", "Ошибка: ImageReader = null")
                throw Exception("Не удалось создать ImageReader")
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DiceAutoBetCapture", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, uiHandler
            )

            val createdVirtualDisplay = virtualDisplay
            if (createdVirtualDisplay != null) {
                Log.d("OverlayService", "VirtualDisplay создан успешно: $createdVirtualDisplay")
                Log.d("OverlayService", "VirtualDisplay properties: name=${createdVirtualDisplay.display?.name}, size=${createdVirtualDisplay.display?.mode}")
                showToast("Захват экрана инициализирован")

                // Даем время VirtualDisplay для стабилизации в фоновом потоке
                uiHandler.postDelayed({
                    Log.d("OverlayService", "VirtualDisplay стабилизирован")
                }, 1000)
            } else {
                Log.e("OverlayService", "Ошибка: VirtualDisplay = null")
                Log.e("OverlayService", "Параметры создания: name=DiceAutoBetCapture, w=$w, h=$h, density=$density")
                Log.e("OverlayService", "Surface: ${imageReader?.surface}")
                Log.e("OverlayService", "MediaProjection: $mediaProjection")
                throw Exception("Не удалось создать VirtualDisplay")
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка создания MediaProjection", e)
            e.printStackTrace()
            // Очищаем сохраненное разрешение при ошибке
            prefsManager.clearMediaProjectionPermission()
            showToast("Ошибка инициализации захвата экрана: ${e.message}")
        }
    }

    private fun stopMediaProjection() {
        try {
            Log.d("OverlayService", "Останавливаем захват экрана")
            Log.d("OverlayService", "Состояние перед остановкой: virtualDisplay=$virtualDisplay, imageReader=$imageReader, mediaProjection=$mediaProjection")

            virtualDisplay ?.release()
            virtualDisplay  = null
            imageReader    ?.close()
            imageReader     = null
            mediaProjection?.stop()
            mediaProjection = null

            Log.d("OverlayService", "Захват экрана остановлен")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка остановки захвата экрана", e)
        }
    }

    // ─────────────────────────────── Game control ───────────────────────────────
    private fun toggleGame() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToggleTime < 500) {  // Защита от двойного нажатия (500мс)
            Log.d("OverlayService", "toggleGame: игнорируем двойное нажатие")
            return
        }
        lastToggleTime = currentTime

        Log.d("OverlayService", "toggleGame вызван")
        
        // Отладочная информация о текущем состоянии
        Log.d("OverlayService", "Текущее состояние:")
        Log.d("OverlayService", "  gameState.isRunning = ${gameState.isRunning}")
        Log.d("OverlayService", "  gameJob?.isActive = ${gameJob?.isActive}")
        Log.d("OverlayService", "  currentMode = $currentMode")
        Log.d("OverlayService", "  singleModeController?.isGameActive() = ${singleModeController?.isGameActive()}")
        
        if (!checkRequirements()) {
            Log.d("OverlayService", "Требования не выполнены, игра не запускается")
            return
        }

        // Проверяем состояние игры в зависимости от режима
        val isGameRunning = when (currentMode) {
            GameMode.SINGLE -> gameState.isRunning || singleModeController?.isGameActive() == true
            GameMode.DUAL -> gameJob?.isActive == true
        }

        if (isGameRunning) {
            Log.d("OverlayService", "Игра запущена в режиме $currentMode, останавливаем")
            stopGame()
        } else {
            Log.d("OverlayService", "Игра не запущена в режиме $currentMode, запускаем")
            startGame()
        }
    }

    private fun checkRequirements(): Boolean {
        Log.d("OverlayService", "Проверяем требования для запуска игры")
        
        // Синхронизируем режим с настройками перед проверкой
        val savedModeString = prefsManager.getGameMode()
        val savedMode = stringToGameMode(savedModeString)
        if (currentMode != savedMode) {
            Log.d("OverlayService", "Обновляем режим: $currentMode -> $savedMode")
            currentMode = savedMode
            // Обновляем кнопку переключения режима в UI
            if (::expandedBinding.isInitialized) {
                try {
                    updateModeToggleButton()
                } catch (_: Exception) { }
            }
        }
        
        Log.d("OverlayService", "Текущий режим: $currentMode")
        
        when (currentMode) {
            GameMode.SINGLE -> {
                return checkSingleModeRequirements()
            }
            GameMode.DUAL -> {
                return checkDualModeRequirements()
            }
        }
    }
    
    private fun checkSingleModeRequirements(): Boolean {
        Log.d("OverlayService", "Проверяем требования для одиночного режима")
        
        // Проверяем наличие критически важных областей single mode
        val requiredAreas = listOf(
            SingleModeAreaType.DICE_AREA,
            SingleModeAreaType.BET_BLUE,
            SingleModeAreaType.BET_RED,
            SingleModeAreaType.BET_10,
            SingleModeAreaType.BET_50,
            SingleModeAreaType.BET_100,
            SingleModeAreaType.BET_500,
            SingleModeAreaType.DOUBLE_BUTTON
        )

        var configuredCount = 0
        val missingAreas = mutableListOf<SingleModeAreaType>()
        
        requiredAreas.forEach { areaType ->
            val area = prefsManager.getSingleModeAreaRect(areaType)
            if (area != null) {
                configuredCount++
                Log.d("OverlayService", "  ${areaType.displayName} = $area")
            } else {
                missingAreas.add(areaType)
            }
        }

        Log.d("OverlayService", "Одиночный режим: $configuredCount из ${requiredAreas.size} областей настроено")
        
        if (missingAreas.isNotEmpty()) {
            Log.d("OverlayService", "Отсутствуют области одиночного режима: ${missingAreas.map { it.displayName }}")
            showToast("Настройте области для одиночного режима")
            return false
        }

        // Проверяем остальные требования
        return checkCommonRequirements()
    }
    
    private fun checkDualModeRequirements(): Boolean {
        Log.d("OverlayService", "Проверяем требования для двойного режима")
        Log.d("OverlayService", "savedAreas.size: ${savedAreas.size}")

        // Проверяем наличие критически важных областей dual mode
        val requiredAreas = listOf(
            AreaType.BET_10, AreaType.BET_50, AreaType.BET_100, AreaType.BET_500, AreaType.BET_2500,
            AreaType.RED_BUTTON, AreaType.ORANGE_BUTTON, AreaType.CONFIRM_BET, AreaType.DICE_AREA
        )

        val missingAreas = requiredAreas.filter { savedAreas[it] == null }
        if (missingAreas.isNotEmpty()) {
            Log.d("OverlayService", "Отсутствуют области: $missingAreas")
        }

        Log.d("OverlayService", "Настроенные области:")
        savedAreas.forEach { (type, area) ->
            Log.d("OverlayService", "  $type = ${area.rect}")
        }

        if (savedAreas.isEmpty()) {
            Log.d("OverlayService", "Ошибка: savedAreas пуст")
            showToast("Сначала настройте области в главном приложении")
            return false
        }
        
        // Проверяем остальные требования
        return checkCommonRequirements()
    }
    
    private fun checkCommonRequirements(): Boolean {
        Log.d("OverlayService", "mediaProjection: $mediaProjection")
        Log.d("OverlayService", "AutoClickService.getInstance(): ${AutoClickService.getInstance()}")
        
        if (mediaProjection == null) {
            val permissionData = prefsManager.getMediaProjectionPermission()
            Log.d("OverlayService", "checkRequirements: mediaProjection=null, permissionData=$permissionData")
            if (permissionData != null) {
                val (resultCode, resultData) = permissionData
                Log.d("OverlayService", "Попытка восстановления разрешения с сохраненными данными...")
                showToast("Попытка восстановления разрешения...")
                startMediaProjection(resultCode, resultData)
                // Проверяем, удалось ли восстановить
                if (mediaProjection != null) {
                    Log.d("OverlayService", "Разрешение восстановлено успешно")
                    showToast("Разрешение восстановлено успешно")
                    return true
                } else {
                    Log.d("OverlayService", "Не удалось восстановить разрешение")
                    showToast("Не удалось восстановить разрешение")
                    prefsManager.clearMediaProjectionPermission()
                }
            }

            Log.d("OverlayService", "Требуется разрешение на захват экрана")
            showToast("Требуется разрешение на захват экрана")
            if (!isRequestingProjection) {
                isRequestingProjection = true
                requestMediaProjection()
            }
            return false
        }
        if (AutoClickService.getInstance() == null) {
            Log.d("OverlayService", "Ошибка: AutoClickService не доступен")
            showToast("Включите Accessibility Service в настройках")
            return false
        }
        Log.d("OverlayService", "Все требования выполнены")
        return true
    }

    private fun startGame() {
        try {
            Log.d("OverlayService", "Запускаем игру в режиме: $currentMode")

            // Проверяем, не запущена ли уже игра
            if (gameJob?.isActive == true) {
                Log.d("OverlayService", "Игра уже запущена, игнорируем повторный запуск")
                return
            }

            // Запускаем игру в зависимости от режима
            when (currentMode) {
                GameMode.DUAL -> startDualModeGame()
                GameMode.SINGLE -> startSingleModeGame()
            }

            Log.d("OverlayService", "Игра запущена успешно в режиме: $currentMode")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка запуска игры", e)
            showToast("Ошибка запуска игры: ${e.message}")
        }
    }

    private fun startDualModeGame() {
        Log.d("OverlayService", "Запускаем игру в двойном режиме")
        
        // Получаем актуальные настройки
        val baseBet = prefsManager.getBaseBet()
        val maxAttempts = prefsManager.getMaxAttempts()
        val betChoice = prefsManager.getBetChoice()

        Log.d("OverlayService", "Настройки игры: baseBet=$baseBet, maxAttempts=$maxAttempts, betChoice=$betChoice")
        Log.d("OverlayService", "Создаем новое состояние игры с этими настройками")

        gameState = gameState.copy(
            isRunning = true,
            isPaused = false,
            consecutiveLosses = 0,
            totalAttempts = 0
        )

        Log.d("OverlayService", "Новое состояние игры создано: baseBet=${gameState.baseBet}, currentBet=${gameState.currentBet}, betChoice=${gameState.betChoice}")

        updateButtonsForGameStart()

        // Отменяем предыдущую корутину, если она существует
        gameJob?.cancel()

        gameJob = gameScope.launch {
            try {
                Log.d("OverlayService", "Создаем новую игровую корутину для двойного режима")

                // Запускаем мониторинг производительности
                // startPerformanceMonitoring() // Функция не реализована
                Log.d("OverlayService", "Мониторинг производительности не реализован")

                runGameLoop()
            } catch (e: CancellationException) {
                Log.d("OverlayService", "Игровая корутина отменена")
                throw e
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка в игровой корутине", e)
                showToast("Ошибка в игре: ${e.message}")
            }
        }
    }

    private fun startSingleModeGame() {
        Log.d("OverlayService", "Запускаем игру в одиночном режиме")
        
        singleModeController?.let { controller ->
            gameState = gameState.copy(isRunning = true, isPaused = false)
            updateButtonsForGameStart()
            
            gameJob = gameScope.launch {
                try {
                    controller.startGame()
                } catch (e: CancellationException) {
                    Log.d("OverlayService", "SingleMode игровая корутина отменена")
                    throw e
                } catch (e: Exception) {
                    Log.e("OverlayService", "Ошибка в SingleMode игровой корутине", e)
                    showToast("Ошибка в одиночном режиме: ${e.message}")
                }
            }
        } ?: run {
            Log.e("OverlayService", "SingleModeController не инициализирован")
            showToast("❌ Ошибка: SingleModeController не готов")
        }
    }

    private fun stopGame() {
        try {
            Log.d("OverlayService", "Останавливаем игру в режиме: $currentMode")

            // Подробная диагностика состояния перед остановкой
            Log.d("OverlayService", "Состояние перед остановкой:")
            Log.d("OverlayService", "  gameState.isRunning = ${gameState.isRunning}")
            Log.d("OverlayService", "  gameJob?.isActive = ${gameJob?.isActive}")
            Log.d("OverlayService", "  singleModeController?.isGameActive() = ${singleModeController?.isGameActive()}")

            // Для одиночного режима проверяем gameState.isRunning, для двойного - gameJob
            val canStop = when (currentMode) {
                GameMode.SINGLE -> gameState.isRunning || singleModeController?.isGameActive() == true
                GameMode.DUAL -> gameJob?.isActive == true
            }
            
            Log.d("OverlayService", "canStop = $canStop (режим: $currentMode)")
            
            if (!canStop) {
                Log.d("OverlayService", "Игра не запущена в режиме $currentMode, игнорируем остановку")
                Log.d("OverlayService", "  gameState.isRunning = ${gameState.isRunning}")
                Log.d("OverlayService", "  gameJob?.isActive = ${gameJob?.isActive}")
                Log.d("OverlayService", "  singleModeController?.isGameActive() = ${singleModeController?.isGameActive()}")
                return
            }

            // Останавливаем игру в зависимости от режима
            when (currentMode) {
                GameMode.DUAL -> stopDualModeGame()
                GameMode.SINGLE -> stopSingleModeGame()
            }

            Log.d("OverlayService", "Игра остановлена в режиме: $currentMode")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка остановки игры", e)
        }
    }

    private fun stopDualModeGame() {
        Log.d("OverlayService", "Останавливаем двойной режим")
        
        gameState = gameState.copy(isRunning = false)
        updateButtonsForGameStop()

        // Отменяем игровую корутину
        gameJob?.let { job ->
            if (job.isActive) {
                Log.d("OverlayService", "Отменяем игровую корутину")
                job.cancel()
            }
        }
        gameJob = null
        updateUI()
    }

    private fun stopSingleModeGame() {
        Log.d("OverlayService", "Останавливаем одиночный режим")
        
        // КРИТИЧНО: Сначала останавливаем контроллер
        singleModeController?.let { controller ->
            Log.d("OverlayService", "Вызываем controller.stopGame()")
            controller.stopGame()
            Log.d("OverlayService", "Контроллер остановлен, isGameActive = ${controller.isGameActive()}")
        } ?: run {
            Log.w("OverlayService", "singleModeController = null, не можем остановить контроллер")
        }
        
        // Обновляем состояние UI
        gameState = gameState.copy(isRunning = false, isPaused = false)
        updateButtonsForGameStop()

        // Отменяем игровую корутину OverlayService (если есть)
        gameJob?.let { job ->
            if (job.isActive) {
                Log.d("OverlayService", "Отменяем игровую корутину одиночного режима")
                job.cancel()
            } else {
                Log.d("OverlayService", "gameJob уже неактивен")
            }
        } ?: run {
            Log.d("OverlayService", "gameJob = null")
        }
        gameJob = null
        
        updateUI()
        
        Log.d("OverlayService", "Одиночный режим полностью остановлен")
    }

    private fun togglePause() {
        Log.d("OverlayService", "Переключаем паузу: isRunning=${gameState.isRunning}, isPaused=${gameState.isPaused}")
        Log.d("OverlayService", "Состояние контроллера: singleModeController?.isGameActive() = ${singleModeController?.isGameActive()}")
        
        if (!gameState.isRunning) {
            Log.d("OverlayService", "Игра не запущена, пауза не переключается")
            return
        }
        
        val paused = !gameState.isPaused
        gameState = gameState.copy(isPaused = paused)
        
        // Обновляем текст кнопки в зависимости от режима
        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            singleModeBinding.btnPause.text = if (paused) "Возобновить" else "Пауза"
        } else if (::expandedBinding.isInitialized) {
            expandedBinding.btnPause.text = if (paused) "Возобновить" else "Пауза"
        }
        
        // Если используется single mode, также передаем состояние паузы в контроллер  
        if (currentMode == GameMode.SINGLE) {
            singleModeController?.togglePause()
            Log.d("OverlayService", "Single mode: команда паузы передана в SingleModeController, пауза установлена в $paused")
        }
        
        Log.d("OverlayService", "Пауза переключена: isPaused=$paused, режим=$currentMode")
        updateUI()
    }

    // ─────────────────────────────── Game Loop ──────────────────────────────────
    private suspend fun runGameLoop() {
        try {
            Log.d("OverlayService", "Запускаем игровой цикл")

            // Простая диагностика системы
            Log.d("OverlayService", "=== ДИАГНОСТИКА СИСТЕМЫ ===")

            // Проверяем область кубиков
            val diceArea = savedAreas[AreaType.DICE_AREA]
            if (diceArea == null) {
                Log.e("OverlayService", "❌ КРИТИЧЕСКАЯ ОШИБКА: Не настроена область кубиков!")
                showToast("Необходимо настроить область кубиков")
                stopGame()
                return
            } else {
                Log.d("OverlayService", "✓ Область кубиков настроена: ${diceArea.rect.toShortString()}")
            }

            // Тестируем захват экрана
            val screenshot = captureScreen()
            if (screenshot == null) {
                Log.e("OverlayService", "❌ КРИТИЧЕСКАЯ ОШИБКА: Захват экрана не работает!")
                showToast("Ошибка захвата экрана - проверьте разрешения")
                stopGame()
                return
            } else {
                Log.d("OverlayService", "✓ Захват экрана работает: ${screenshot.width}x${screenshot.height}")
            }

            lastResult = null // Сбрасываем предыдущий результат при запуске игры
            lastResultTime = System.currentTimeMillis() // Сбрасываем время при запуске игры

            // ОСОБЫЙ СЛУЧАЙ: Игнорируем первый результат после старта
            if (gameState.shouldIgnoreFirstResult()) {
                Log.d("OverlayService", "🔥 ИГНОРИРУЕМ ПЕРВЫЙ РЕЗУЛЬТАТ ПОСЛЕ СТАРТА")

                // Ждем первый результат и просто игнорируем его
                val firstResult = waitForAnyResult()
                if (firstResult != null) {
                    Log.d("OverlayService", "Первый результат получен и проигнорирован: redDots=${firstResult.redDots}, orangeDots=${firstResult.orangeDots}")
                    gameState = gameState.markFirstResultIgnored()
                    Log.d("OverlayService", "Первый результат проигнорирован, начинаем альтернирующую стратегию")
                } else {
                    Log.e("OverlayService", "Не удалось получить первый результат для игнорирования")
                    showToast("Ошибка при получении первого результата")
                    stopGame()
                    return
                }
            }

            while (gameState.isRunning && gameScope.isActive) {
                // Дополнительная проверка состояния игры
                if (!gameState.isRunning) {
                    Log.d("OverlayService", "Игра остановлена, выходим из цикла")
                    break
                }

                if (gameState.isPaused) {
                    delay(1000)
                    continue
                }

                try {
                    Log.d("OverlayService", "=== НАЧАЛО НОВОГО ХОДА ===")

                    val currentTurnType = gameState.getCurrentTurnType()
                    val statusDescription = gameState.getStatusDescription()

                    Log.d("OverlayService", "📋 $statusDescription")
                    Log.d("OverlayService", "Ход №${gameState.currentTurnNumber + 1}: $currentTurnType")

                    when (currentTurnType) {
                        TurnType.ACTIVE -> {
                            Log.d("OverlayService", "🎯 АКТИВНЫЙ ХОД - делаем ставку")

                            // Рассчитываем размер ставки на основе результата последнего активного хода
                            val betAmount = gameState.calculateBetAmount()

                            Log.d("OverlayService", "Размер ставки для активного хода: $betAmount")

                            // Устанавливаем ставку
                            performCorrectBetSetup(betAmount)
                            delay(200) // Ждем завершения установки ставки

                            // Ждем начало нового броска
                            Log.d("OverlayService", "Ждем начало нового броска...")
                            val newRollStarted = waitForNewRoll()
                            Log.d("OverlayService", "Новый бросок обнаружен: $newRollStarted")

                            // Ждем результат
                            val result = waitForResultAdaptive()

                            if (result != null) {
                                Log.d("OverlayService", "Получен результат активного хода: $result")
                                processActiveResult(result, betAmount)
                                updateUI()
                            } else {
                                Log.e("OverlayService", "❌ Не удалось получить результат активного хода")
                                showToast("Ошибка получения результата")
                                delay(2000)
                                continue
                            }
                        }


                        TurnType.PASSIVE -> {
                            Log.d("OverlayService", "👁️ ПАССИВНЫЙ ХОД - только наблюдаем")

                            // В пассивном ходу мы НЕ делаем ставку, только ждем результат
                            val result = waitForAnyResult()

                            if (result != null) {
                                Log.d("OverlayService", "Получен результат пассивного хода: $result")
                                processPassiveResult(result)
                                updateUI()
                            } else {
                                Log.e("OverlayService", "❌ Не удалось получить результат пассивного хода")
                                showToast("Ошибка получения результата")
                                delay(2000)
                                continue
                            }
                        }
                    }

                    // Проверяем условия остановки
                    if (!gameState.isRunning) {
                        Log.d("OverlayService", "Игра остановлена во время обработки хода")
                        break
                    }

                    if (gameState.shouldStop()) {
                        Log.d("OverlayService", "Условия остановки выполнены")
                        stopGame()
                        break
                    }

                    // Минимальная задержка для стабильности
                    delay(50)

                } catch (e: CancellationException) {
                    Log.d("OverlayService", "Игровая корутина отменена во время выполнения")
                    throw e
                } catch (e: Exception) {
                    Log.e("OverlayService", "Ошибка в игровом цикле", e)
                    e.printStackTrace()
                    showToast("Ошибка: ${e.message}")
                    delay(5000)
                }
            }
            Log.d("OverlayService", "Игровой цикл завершен")
        } catch (e: CancellationException) {
            Log.d("OverlayService", "Игровой цикл отменен")
            // Это нормально при остановке игры
        } catch (e: Exception) {
            Log.e("OverlayService", "Критическая ошибка в игровом цикле", e)
            showToast("Критическая ошибка: ${e.message}")
        }
    }

    // ──────────────────── Alternating Strategy Methods ─────────────────────────

    /**
     * Ждет любой результат (для пассивных ходов и игнорирования первого результата)
     */
    private suspend fun waitForAnyResult(): RoundResult? {
        Log.d("OverlayService", "Ждем любой результат...")

        // Ждем начало нового броска
        val newRollStarted = waitForNewRoll()
        if (!newRollStarted) {
            Log.d("OverlayService", "Новый бросок не обнаружен, ждем результат напрямую")
        }

        // Ждем результат
        return waitForResultAdaptive()
    }

    /**
     * Обрабатывает результат активного хода
     */
    private suspend fun processActiveResult(result: RoundResult, betAmount: Int) {
        Log.d("OverlayService", "=== ОБРАБОТКА РЕЗУЛЬТАТА АКТИВНОГО ХОДА ===")
        Log.d("OverlayService", "Результат: $result")
        Log.d("OverlayService", "Размер ставки: $betAmount")

        // Определяем выигрыш: только если наш выбор совпадает с победителем И это не ничья
        val isWin = when (gameState.betChoice) {
            BetChoice.RED -> result.winner == BetChoice.RED && !result.isDraw
            BetChoice.ORANGE -> result.winner == BetChoice.ORANGE && !result.isDraw
            else -> false
        }

        Log.d("OverlayService", "Результат активного хода: ${if (isWin) "ВЫИГРЫШ" else "ПРОИГРЫШ/НИЧЬЯ"}")


        // Определяем тип результата для GameState
        val gameResultType = when {
            isWin -> GameResultType.WIN
            result.isDraw -> GameResultType.LOSS  // Ничья = проигрыш
            else -> GameResultType.LOSS
        }

        // Обновляем баланс после активного хода
        gameState = gameState.updateBalanceAfterActiveTurn(betAmount, gameResultType)

        // Переходим к следующему ходу и запоминаем результат
        gameState = gameState.advanceToNextTurn(gameResultType)

        // Добавляем результат в историю
        gameState = gameState.copy(roundHistory = gameState.roundHistory + result)

        // Показываем сообщение пользователю
        val message = when {
            isWin -> {
                val winAmount = (betAmount * 2.28).toInt() - betAmount
                "🎉 АКТИВНЫЙ ХОД: Выигрыш! +$winAmount ₽"
            }
            result.isDraw -> "💸 АКТИВНЫЙ ХОД: Ничья (проигрыш)!"
            else -> "💸 АКТИВНЫЙ ХОД: Проигрыш!"
        }
        showToast(message)

        Log.d("OverlayService", "Результат активного хода обработан. Следующий ход: ${gameState.getCurrentTurnType()}")
    }

    /**
     * Обрабатывает результат пассивного хода
     */
    private suspend fun processPassiveResult(result: RoundResult) {
        Log.d("OverlayService", "=== ОБРАБОТКА РЕЗУЛЬТАТА ПАССИВНОГО ХОДА ===")
        Log.d("OverlayService", "Результат: $result")
        Log.d("OverlayService", "👁️ Пассивный ход - только наблюдаем, ставки не было")

        // В пассивном ходу мы просто переходим к следующему ходу без изменения баланса
        gameState = gameState.advanceToNextTurn(GameResultType.UNKNOWN)

        // Добавляем результат в историю для статистики
        gameState = gameState.copy(roundHistory = gameState.roundHistory + result)

        // Показываем информационное сообщение
        val winner = when (result.winner) {
            BetChoice.RED -> "Красный"
            BetChoice.ORANGE -> "Оранжевый"
            null -> "Ничья"
        }
        showToast("👁️ ПАССИВНЫЙ ХОД: Наблюдаем ($winner)")

        Log.d("OverlayService", "Результат пассивного хода обработан. Следующий ход: ${gameState.getCurrentTurnType()}")
    }

    // ────────────────────────────── Game Actions ────────────────────────────────
    private suspend fun selectBetAmount(amount: Int) {
        Log.d("OverlayService", "Выбираем сумму ставки: $amount")
        Log.d("OverlayService", "Текущее состояние: currentBet=${gameState.currentBet}, baseBet=${gameState.baseBet}, consecutiveLosses=${gameState.consecutiveLosses}")

        // Составляем ставку из доступных номиналов
        val betClicks = decomposeBetAmount(amount)

        if (betClicks.isEmpty()) {
            Log.d("OverlayService", "Не удалось составить ставку $amount из доступных номиналов")
            showToast("Невозможно поставить $amount")
            return
        }

        Log.d("OverlayService", "Составляем ставку $amount из ${betClicks.size} кликов: $betClicks")

        // Выполняем клики по всем нужным номиналам
        for ((betType, clicks) in betClicks) {
            val betArea = savedAreas[betType]
            if (betArea == null) {
                Log.d("OverlayService", "Область для ставки $betType не найдена")
                showToast("Область для ставки не настроена: $betType")
                return
            }

            // Делаем нужное количество кликов
            repeat(clicks) {
                Log.d("OverlayService", "Кликаем по области ставки $betType (${it + 1}/$clicks): ${betArea.rect}")
                AutoClickService.performClick(betArea.rect) { success ->
                    if (success) {
                        Log.d("OverlayService", "Клик по ставке $betType успешен")
                    } else {
                        Log.d("OverlayService", "Ошибка клика по ставке $betType")
                    }
                }

                // Добавляем небольшую задержку между кликами
                if (it < clicks - 1) {
                    delay(100)
                }
            }

            // Добавляем задержку после всех кликов для стабильности
            delay(prefsManager.getClickStabilityDelay())
        }

        Log.d("OverlayService", "Составная ставка $amount установлена успешно")
    }

    /**
     * Раскладывает сумму ставки на доступные номиналы
     */
    private fun decomposeBetAmount(amount: Int): List<Pair<AreaType, Int>> {
        val availableNominals = listOf(
            2500 to AreaType.BET_2500,
            500 to AreaType.BET_500,
            100 to AreaType.BET_100,
            50 to AreaType.BET_50,
            10 to AreaType.BET_10
        )

        val result = mutableListOf<Pair<AreaType, Int>>()
        var remaining = amount

        for ((nominal, areaType) in availableNominals) {
            if (remaining >= nominal) {
                val clicks = remaining / nominal
                if (clicks > 0) {
                    result.add(areaType to clicks)
                    remaining %= nominal
                }
            }
        }

        // Если остался остаток, который нельзя составить
        if (remaining > 0) {
            Log.w("OverlayService", "Остался неразложимый остаток: $remaining из суммы $amount")
            return emptyList()
        }

        return result
    }

    private suspend fun selectBetChoice(choice: BetChoice) {
        Log.d("OverlayService", "Выбираем кубик: $choice")

        val choiceArea = when (choice) {
            BetChoice.RED -> {
                Log.d("OverlayService", "Ищем область для красного кубика")
                savedAreas[AreaType.RED_BUTTON]
            }
            BetChoice.ORANGE -> {
                Log.d("OverlayService", "Ищем область для оранжевого кубика")
                savedAreas[AreaType.ORANGE_BUTTON]
            }
        }

        Log.d("OverlayService", "Найдена область для выбора кубика: $choiceArea")

        choiceArea?.let { area ->
            Log.d("OverlayService", "Кликаем по области кубика: ${area.rect}")
            AutoClickService.performClick(area.rect) { success ->
                if (success) {
                    Log.d("OverlayService", "Клик по кубику успешен: $choice")
                } else {
                    Log.d("OverlayService", "Ошибка клика по кубику: $choice")
                    showToast("Ошибка клика по кубику")
                }
            }
            // Добавляем задержку после клика для стабильности
            delay(prefsManager.getClickStabilityDelay())
        } ?: run {
            Log.d("OverlayService", "Область для выбора кубика не найдена: $choice")
            showToast("Область для выбора кубика не настроена: $choice")
        }
    }

    private suspend fun clickConfirmBet() {
        Log.d("OverlayService", "=== КЛИК ПО КНОПКЕ ЗАКЛЮЧИТЬ ПАРИ ===")

        val confirmArea = savedAreas[AreaType.CONFIRM_BET]
        if (confirmArea == null) {
            Log.e("OverlayService", "ОШИБКА: Область подтверждения ставки не найдена!")
            showToast("Область подтверждения не настроена")
            return
        }

        Log.d("OverlayService", "Найдена область подтверждения: $confirmArea")

        try {
            Log.d("OverlayService", "Отправляем клик по кнопке подтверждения...")
            AutoClickService.performClick(confirmArea.rect) { success ->
                if (success) {
                    Log.d("OverlayService", "✓ Клик по кнопке подтверждения успешен")
                } else {
                    Log.e("OverlayService", "✗ Ошибка клика по кнопке подтверждения")
                    showToast("Ошибка клика по кнопке подтверждения")
                }
            }
            // Добавляем задержку после клика для стабильности
            delay(prefsManager.getClickStabilityDelay())
            Log.d("OverlayService", "=== КЛИК ПО КНОПКЕ ЗАКЛЮЧИТЬ ПАРИ ЗАВЕРШЕН ===")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка в clickConfirmBet", e)
            showToast("Ошибка подтверждения ставки: ${e.message}")
        }
    }

    private suspend fun clickDouble() {
        Log.d("OverlayService", "=== ВЫПОЛНЯЕМ КЛИК ПО КНОПКЕ УДВОЕНИЯ ===")
        Log.d("OverlayService", "Текущая ставка: ${gameState.currentBet}")
        Log.d("OverlayService", "Ожидаемая ставка после удвоения: ${gameState.currentBet * 2}")

        val doubleArea = savedAreas[AreaType.DOUBLE_BUTTON]
        if (doubleArea == null) {
            Log.e("OverlayService", "ОШИБКА: Область кнопки удвоения не найдена!")
            Log.e("OverlayService", "Доступные области: ${savedAreas.keys}")
            showToast("Область кнопки удвоения не настроена")
            return
        }

        Log.d("OverlayService", "✓ Найдена область кнопки удвоения: ${doubleArea.rect}")
        Log.d("OverlayService", "Координаты: left=${doubleArea.rect.left}, top=${doubleArea.rect.top}, right=${doubleArea.rect.right}, bottom=${doubleArea.rect.bottom}")
        Log.d("OverlayService", "Центр: centerX=${doubleArea.rect.centerX()}, centerY=${doubleArea.rect.centerY()}")

        Log.d("OverlayService", "Отправляем клик по кнопке удвоения...")
        AutoClickService.performClick(doubleArea.rect) { success ->
            if (success) {
                Log.d("OverlayService", "✓ Клик по кнопке удвоения успешен")
            } else {
                Log.e("OverlayService", "✗ Ошибка клика по кнопке удвоения")
                showToast("Ошибка клика по кнопке удвоения")
            }
        }

        // Добавляем задержку после клика для стабильности
        delay(prefsManager.getClickStabilityDelay())
        Log.d("OverlayService", "Клик по кнопке удвоения отправлен")
        Log.d("OverlayService", "=== КЛИК ПО КНОПКЕ УДВОЕНИЯ ЗАВЕРШЕН ===")
    }

    // ──────────────────────────── Result Detection ──────────────────────────────

    // Ждем начала нового броска (кубики исчезают)
    private suspend fun waitForNewRoll(maxAttempts: Int = 15): Boolean {
        val checkInterval = 300L // Уменьшено для более частых проверок
        var attempts = 0
        var emptyResultCount = 0 // Счетчик пустых результатов подряд
        val requiredEmptyCount = 3 // Требуем 3 пустых результата подряд для подтверждения

        Log.d("OverlayService", "Начинаем ожидание нового броска (интервал: ${checkInterval}мс)")

        try {
            while (attempts < maxAttempts && gameScope.isActive && gameState.isRunning) {
                Log.d("OverlayService", "Проверяем начало нового броска (попытка ${attempts + 1}/$maxAttempts)")

                // Проверяем, не была ли игра остановлена
                if (!gameState.isRunning) {
                    Log.d("OverlayService", "Игра остановлена во время ожидания нового броска")
                    return false
                }

                val screenshot = captureScreen()
                if (screenshot != null) {
                    val diceArea = savedAreas[AreaType.DICE_AREA]
                    if (diceArea != null) {
                        val result = analyzeDiceAreaWithValidation(screenshot, diceArea.rect)
                        // Если кубики исчезли (нет точек), увеличиваем счетчик
                        if (result == null || (result.redDots == 0 && result.orangeDots == 0)) {
                            emptyResultCount++
                            Log.d("OverlayService", "Кубики исчезли (счетчик: $emptyResultCount/$requiredEmptyCount)")

                            // Если кубики исчезли несколько раз подряд, считаем что новый бросок начался
                            if (emptyResultCount >= requiredEmptyCount) {
                                Log.d("OverlayService", "✓ Новый бросок начался (кубики исчезли $emptyResultCount раза подряд)")
                                lastResult = null // Сбрасываем предыдущий результат для нового броска
                                lastResultTime = System.currentTimeMillis() // Сбрасываем время для нового броска
                                return true
                            }
                        } else {
                            Log.d("OverlayService", "Кубики еще видны: redDots=${result.redDots}, orangeDots=${result.orangeDots}")
                            emptyResultCount = 0 // Сбрасываем счетчик, если кубики видны
                        }
                    } else {
                        Log.d("OverlayService", "Область кубиков не найдена")
                        emptyResultCount = 0 // Сбрасываем счетчик
                    }
                } else {
                    Log.d("OverlayService", "Скриншот не получен")
                    emptyResultCount = 0 // Сбрасываем счетчик
                }

                attempts++
                if (attempts < maxAttempts) {
                    Log.d("OverlayService", "Ждем ${checkInterval}мс до следующей проверки...")
                    delay(checkInterval)
                }
            }
        } catch (e: CancellationException) {
            Log.d("OverlayService", "Ожидание нового броска отменено")
            throw e
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка при ожидании нового броска", e)
        }

        Log.d("OverlayService", "Новый бросок не начался после $maxAttempts попыток (${maxAttempts * checkInterval / 1000} секунд)")
        return false
    }

    // Железобетонная система ожидания результатов с адаптивными интервалами
    private suspend fun waitForResultWithInterval(maxAttempts: Int = 60): RoundResult? {
        var currentInterval = 500L // Начинаем с 500мс для быстрой реакции
        val minInterval = 200L // Минимальный интервал
        val maxInterval = 2000L // Максимальный интервал 2 секунды
        val intervalStep = 100L // Увеличиваем интервал на 100мс

        var attempts = 0
        var lastResult: RoundResult? = null
        var sameResultCount = 0
        val maxSameResultCount = 3 // Результат считается стабильным после 3 одинаковых проверок
        var consecutiveInvalidResults = 0
        val maxConsecutiveInvalid = 5 // Максимум 5 невалидных результатов подряд

        Log.d("OverlayService", "=== ЖЕЛЕЗОБЕТОННОЕ ОЖИДАНИЕ РЕЗУЛЬТАТА ===")
        Log.d("OverlayService", "Начальный интервал: ${currentInterval}мс, максимальный: ${maxInterval}мс")
        Log.d("OverlayService", "Требуем $maxSameResultCount стабильных результатов")

        // Дополнительная задержка перед началом анализа
        Log.d("OverlayService", "Ждем 800мс перед началом анализа результата...")
        delay(800)

        try {
            while (attempts < maxAttempts && gameScope.isActive && gameState.isRunning) {
                Log.d("OverlayService", "Проверяем результат (попытка ${attempts + 1}/$maxAttempts, интервал: ${currentInterval}мс)")

                // Проверяем, не была ли игра остановлена
                if (!gameState.isRunning) {
                    Log.d("OverlayService", "Игра остановлена во время ожидания результата")
                    return null
                }

                // Делаем скриншот и анализируем результат
                val screenshot = captureScreen()
                if (screenshot != null) {
                    Log.d("OverlayService", "Скриншот получен: ${screenshot.width}x${screenshot.height}")
                    val diceArea = savedAreas[AreaType.DICE_AREA]
                    if (diceArea != null) {
                        Log.d("OverlayService", "Область кубиков найдена, анализируем...")
                        val result = analyzeDiceAreaWithValidation(screenshot, diceArea.rect)

                        if (result != null && result.isValid && (result.redDots > 0 || result.orangeDots > 0)) {
                            consecutiveInvalidResults = 0 // Сбрасываем счетчик невалидных результатов

                            // Проверяем, изменился ли результат
                            if (lastResult != null && result == lastResult) {
                                sameResultCount++
                                Log.d("OverlayService", "Результат тот же: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}, счетчик=$sameResultCount")

                                // Если результат стабилен и имеет высокую уверенность, возвращаем его
                                if (sameResultCount >= maxSameResultCount && result.confidence >= 0.6f) {
                                    Log.d("OverlayService", "✓ ЖЕЛЕЗОБЕТОННЫЙ РЕЗУЛЬТАТ: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                    return result
                                }

                                // Если результат стабилен, но уверенность средняя, продолжаем
                                if (sameResultCount >= maxSameResultCount) {
                                    Log.d("OverlayService", "Результат стабилен, но уверенность средняя (${result.confidence}), продолжаем...")
                                }
                            } else {
                                // Новый результат
                                if (lastResult != null) {
                                    Log.d("OverlayService", "НОВЫЙ РЕЗУЛЬТАТ: было ${lastResult.redDots}:${lastResult.orangeDots}, стало ${result.redDots}:${result.orangeDots}")
                                } else {
                                    Log.d("OverlayService", "ПЕРВЫЙ РЕЗУЛЬТАТ: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                }
                                lastResult = result
                                sameResultCount = 1

                                // Если это первый результат с высокой уверенность, возвращаем его сразу
                                if (attempts == 0 && result.confidence >= 0.8f) {
                                    Log.d("OverlayService", "✓ Первый результат с высокой уверенность: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                    return result
                                }
                            }

                            // Уменьшаем интервал при получении валидного результата
                            if (currentInterval > minInterval) {
                                currentInterval = (currentInterval - intervalStep).coerceAtLeast(minInterval)
                                Log.d("OverlayService", "Уменьшаем интервал до ${currentInterval}мс (получен валидный результат)")
                            }
                        } else {
                            consecutiveInvalidResults++
                            Log.d("OverlayService", "Результат невалидный или пустой: redDots=${result?.redDots}, orangeDots=${result?.orangeDots}, isValid=${result?.isValid}, confidence=${result?.confidence}")
                            Log.d("OverlayService", "Счетчик невалидных результатов: $consecutiveInvalidResults/$maxConsecutiveInvalid")

                            // Если слишком много невалидных результатов подряд, увеличиваем интервал
                            if (consecutiveInvalidResults >= maxConsecutiveInvalid) {
                                currentInterval = (currentInterval + intervalStep).coerceAtMost(maxInterval)
                                Log.d("OverlayService", "Увеличиваем интервал до ${currentInterval}мс (много невалидных результатов)")
                                consecutiveInvalidResults = 0 // Сбрасываем счетчик
                            }

                            sameResultCount = 0 // Сбрасываем счетчик стабильности
                        }
                    } else {
                        Log.d("OverlayService", "Область кубиков не найдена")
                        consecutiveInvalidResults++
                    }
                } else {
                    Log.d("OverlayService", "Скриншот не получен")
                    consecutiveInvalidResults++
                }

                attempts++

                // Ждем перед следующей проверкой
                if (attempts < maxAttempts) {
                    Log.d("OverlayService", "Ждем ${currentInterval}мс до следующей проверки...")
                    delay(currentInterval)
                }
            }
        } catch (e: CancellationException) {
            Log.d("OverlayService", "Ожидание результата отменено")
            throw e
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка при ожидании результата", e)
        }

        Log.d("OverlayService", "Результат не получен после $maxAttempts попыток")
        return null
    }

    // Старый метод для обратной совместимости
    private suspend fun waitForResult(): RoundResult? {
        val checkDelay = prefsManager.getCheckDelay()
        var attempts = 0
        val maxAttempts = 10

        try {
            while (attempts < maxAttempts && gameScope.isActive && gameState.isRunning) {
                delay(checkDelay)

                // Проверяем, не была ли игра остановлена
                if (!gameState.isRunning) {
                    Log.d("OverlayService", "Игра остановлена во время ожидания результата")
                    return null
                }

                val screenshot = captureScreen()
                if (screenshot != null) {
                    Log.d("OverlayService", "Скриншот получен: ${screenshot.width}x${screenshot.height}")
                    val diceArea = savedAreas[AreaType.DICE_AREA]
                    if (diceArea != null) {
                        Log.d("OverlayService", "Область кубиков найдена, анализируем...")
                        val result = analyzeDiceAreaWithValidation(screenshot, diceArea.rect)
                        if (result != null && (result.redDots > 0 || result.orangeDots > 0)) {
                            Log.d("OverlayService", "ВАЛИДНЫЙ РЕЗУЛЬТАТ ПОЛУЧЕН: redDots=${result.redDots}, orangeDots=${result.orangeDots}")
                            return result
                        } else {
                            Log.d("OverlayService", "Результат невалидный или пустой: redDots=${result?.redDots}, orangeDots=${result?.orangeDots}")
                        }
                    } else {
                        Log.d("OverlayService", "Область кубиков не найдена")
                    }
                } else {
                    Log.d("OverlayService", "Скриншот не получен")
                }

                attempts++
                Log.d("OverlayService", "Попытка $attempts/$maxAttempts")
            }
        } catch (e: CancellationException) {
            Log.d("OverlayService", "Ожидание результата отменено")
            throw e
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка при ожидании результата", e)
        }

        Log.d("OverlayService", "Результат не получен после $maxAttempts попыток")
        return null
    }

    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Диагностика состояния
            if (mediaProjection == null) {
                Log.e("OverlayService", "❌ MediaProjection не инициализирован")
                return@withContext null
            }

            if (imageReader == null) {
                Log.e("OverlayService", "❌ ImageReader не инициализирован")
                return@withContext null
            }

            if (virtualDisplay == null) {
                Log.e("OverlayService", "❌ VirtualDisplay не инициализирован")
                return@withContext null
            }

            // Попытка получить изображение с повторными попытками
            var image: Image? = null
            var attempts = 0
            val maxAttempts = 20 // Увеличено с 10 до 20

            while (image == null && attempts < maxAttempts) {
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    attempts++
                    Log.d("OverlayService", "Попытка получить изображение $attempts/$maxAttempts")
                    delay(200) // Увеличена задержка с 150мс до 200мс
                }
            }

            if (image != null) {
                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride: Int = planes[0].pixelStride
                val rowStride: Int = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                Log.d("OverlayService", "✓ Изображение получено успешно: ${bitmap.width}x${bitmap.height}")
                return@withContext bitmap
            } else {
                Log.e("OverlayService", "❌ Не удалось получить изображение из ImageReader после $maxAttempts попыток")
                return@withContext null
            }
        } catch (e: CancellationException) {
            Log.d("OverlayService", "captureScreen: отменено")
            throw e
        } catch (e: Exception) {
            Log.e("OverlayService", "❌ Ошибка захвата экрана", e)
            Log.e("OverlayService", "Состояние: mediaProjection=$mediaProjection, imageReader=$imageReader, virtualDisplay=$virtualDisplay")
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun analyzeDiceArea(screenshot: Bitmap, diceRect: android.graphics.Rect): RoundResult? {
        return try {
            Log.d("OverlayService", "Анализируем область кубиков: left=${diceRect.left}, top=${diceRect.top}, width=${diceRect.width()}, height=${diceRect.height()}")

            val diceBitmap = Bitmap.createBitmap(
                screenshot,
                diceRect.left,
                diceRect.top,
                diceRect.width(),
                diceRect.height()
            )

            Log.d("OverlayService", "Создан bitmap для анализа: ${diceBitmap.width}x${diceBitmap.height}")

            val dotResult = DotCounter.count(diceBitmap)
            Log.d("OverlayService", "DotCounter результат: leftDots=${dotResult.leftDots}, rightDots=${dotResult.rightDots}, confidence=${dotResult.confidence}")

            val result = RoundResult.fromDotResult(dotResult)
            Log.d("OverlayService", "Результат анализа: redDots=${result.redDots}, orangeDots=${result.orangeDots}, winner=${result.winner}, isDraw=${result.isDraw}, confidence=${result.confidence}, isValid=${result.isValid}")

            // Дополнительная информация о результате
            when {
                !result.isValid -> Log.d("OverlayService", "НЕВАЛИДНЫЙ РЕЗУЛЬТАТ: confidence=${result.confidence}")
                result.isDraw -> Log.d("OverlayService", "НИЧЬЯ: ${result.redDots} = ${result.orangeDots}")
                result.winner == BetChoice.RED -> Log.d("OverlayService", "ПОБЕДА КРАСНОГО: ${result.redDots} > ${result.orangeDots}")
                result.winner == BetChoice.ORANGE -> Log.d("OverlayService", "ПОБЕДА ОРАНЖЕВОГО: ${result.orangeDots} > ${result.redDots}")
                else -> Log.d("OverlayService", "НЕОПРЕДЕЛЕННЫЙ РЕЗУЛЬТАТ")
            }

            result
        } catch (e: Exception) {
            Log.e("OverlayService", "❌ КРИТИЧЕСКАЯ ОШИБКА в analyzeDiceArea", e)
            Log.e("OverlayService", "Размер скриншота: ${screenshot.width}x${screenshot.height}")
            Log.e("OverlayService", "Область кубиков: left=${diceRect.left}, top=${diceRect.top}, width=${diceRect.width()}, height=${diceRect.height()}")
            Log.e("OverlayService", "Тип ошибки: ${e.javaClass.simpleName}")
            Log.e("OverlayService", "Сообщение: ${e.message}")

            // Проверяем, не выходит ли область за пределы экрана
            if (diceRect.left < 0 || diceRect.top < 0 ||
                diceRect.right > screenshot.width || diceRect.bottom > screenshot.height) {
                Log.e("OverlayService", "❌ ОШИБКА: Область кубиков выходит за пределы экрана!")
                Log.e("OverlayService", "Размер экрана: ${screenshot.width}x${screenshot.height}")
                Log.e("OverlayService", "Область кубиков: ${diceRect.toShortString()}")
            }

            e.printStackTrace()
            null
        }
    }

    // СТАРЫЙ МЕТОД УДАЛЕН: логика перенесена в processActiveResult() и processPassiveResult()
    // для реализации новой альтернирующей стратегии ставок

    private fun calculateWinAmount(bet: Int): Int {
        val winAmount = (bet * 2.28).toInt() - bet
        Log.d("OverlayService", "Рассчитываем выигрыш: bet=$bet, winAmount=$winAmount")
        return winAmount
    }

    private fun updateGameSettings() {
        Log.d("OverlayService", "Обновляем настройки игры")

        val newBaseBet = prefsManager.getBaseBet()
        val newMaxAttempts = prefsManager.getMaxAttempts()
        val newBetChoice = prefsManager.getBetChoice()

        Log.d("OverlayService", "Новые настройки: baseBet=$newBaseBet, maxAttempts=$newMaxAttempts, betChoice=$newBetChoice")
        Log.d("OverlayService", "Текущие настройки игры: baseBet=${gameState.baseBet}, currentBet=${gameState.currentBet}, betChoice=${gameState.betChoice}")

        // Обновляем состояние игры только если игра не запущена
        if (!gameState.isRunning) {
            gameState = gameState.copy(
                baseBet = newBaseBet,
                currentBet = newBaseBet,
                maxAttempts = newMaxAttempts,
                betChoice = newBetChoice
            )
            Log.d("OverlayService", "Настройки обновлены (игра не запущена): baseBet=${gameState.baseBet}, currentBet=${gameState.currentBet}, betChoice=${gameState.betChoice}")
        } else {
            // Если игра запущена, обновляем только базовую ставку и выбор
            // Текущая ставка не меняется во время игры
            gameState = gameState.copy(
                baseBet = newBaseBet,
                maxAttempts = newMaxAttempts,
                betChoice = newBetChoice
            )
            Log.d("OverlayService", "Настройки обновлены (игра запущена): baseBet=${gameState.baseBet}, betChoice=${gameState.betChoice}")
        }

        // Обновляем UI только если плавающее окно создано
        if (::expandedBinding.isInitialized) {
            updateUI()
        } else {
            Log.d("OverlayService", "UI не обновлен - плавающее окно еще не создано")
        }
    }

    // УСТАРЕВШИЕ МЕТОДЫ УДАЛЕНЫ: doubleBetAfterLoss, testDoubleOnly, testWinLogic
    // Заменены новой альтернирующей стратегией в processActiveResult() и processPassiveResult()

    private fun testWinLogic() {
        Log.d("OverlayService", "=== ТЕСТ ЛОГИКИ ВЫИГРЫША ===")

        gameScope.launch {
            try {
                // Симулируем состояние после проигрыша
                Log.d("OverlayService", "Симулируем состояние после проигрыша")
                val oldState = gameState
                gameState = gameState.copy(
                    consecutiveLosses = 3,
                    currentBet = 80 // Увеличенная ставка
                )
                Log.d("OverlayService", "Состояние до выигрыша: consecutiveLosses=${gameState.consecutiveLosses}, currentBet=${gameState.currentBet}, baseBet=${gameState.baseBet}")

                // Симулируем выигрыш
                Log.d("OverlayService", "Симулируем выигрыш")
                val winResult = RoundResult(
                    redDots = 5,
                    orangeDots = 3,
                    winner = BetChoice.RED,
                    isDraw = false
                )

                Log.d("OverlayService", "Тестируем новую альтернирующую логику")
                // Имитируем активный ход с выигрышем
                gameScope.launch {
                    processActiveResult(winResult, gameState.baseBet)
                }

                Log.d("OverlayService", "Результат после выигрыша: consecutiveLosses=${gameState.consecutiveLosses}, currentBet=${gameState.currentBet}, baseBet=${gameState.baseBet}")

                if (gameState.consecutiveLosses == 0 && gameState.currentBet == gameState.baseBet) {
                    Log.d("OverlayService", "✓ Логика выигрыша работает правильно!")
                    showToast("✓ Логика выигрыша работает правильно!")
                } else {
                    Log.d("OverlayService", "✗ Логика выигрыша работает неправильно!")
                    Log.d("OverlayService", "Ожидалось: consecutiveLosses=0, currentBet=${gameState.baseBet}")
                    Log.d("OverlayService", "Получено: consecutiveLosses=${gameState.consecutiveLosses}, currentBet=${gameState.currentBet}")
                    showToast("✗ Логика выигрыша работает неправильно!")
                }

                // Восстанавливаем исходное состояние
                gameState = oldState
                Log.d("OverlayService", "Восстановлено исходное состояние")
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка в тесте логики выигрыша", e)
                showToast("Ошибка теста: ${e.message}")
            }
        }
    }

    private fun testResultDetection() {
        Log.d("OverlayService", "=== ТЕСТ ДИНАМИЧЕСКОГО ОБНАРУЖЕНИЯ РЕЗУЛЬТАТА ===")

        gameScope.launch {
            try {
                Log.d("OverlayService", "Запускаем тест динамического обнаружения результата")
                Log.d("OverlayService", "Интервалы: 1с, максимум 30 попыток")
                val result = waitForResultWithInterval()

                if (result != null) {
                    Log.d("OverlayService", "✓ Результат обнаружен: $result")
                    showToast("✓ Результат обнаружен: ${result.redDots} vs ${result.orangeDots}")
                } else {
                    Log.d("OverlayService", "✗ Результат не обнаружен")
                    showToast("✗ Результат не обнаружен")
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка в тесте обнаружения результата", e)
                showToast("Ошибка теста: ${e.message}")
            }
        }
    }

    private fun testNewRollDetection() {
        Log.d("OverlayService", "=== ТЕСТ ОБНАРУЖЕНИЯ НОВОГО БРОСКА ===")

        gameScope.launch {
            try {
                Log.d("OverlayService", "Запускаем тест обнаружения нового броска")
                val newRollStarted = waitForNewRoll(maxAttempts = 5) // 5 секунд максимум

                if (newRollStarted) {
                    Log.d("OverlayService", "✓ Новый бросок обнаружен")
                    showToast("✓ Новый бросок обнаружен")
                } else {
                    Log.d("OverlayService", "✗ Новый бросок не обнаружен")
                    showToast("✗ Новый бросок не обнаружен")
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка в тесте обнаружения нового броска", e)
                showToast("Ошибка теста: ${e.message}")
            }
        }
    }

    private fun testResultComparison() {
        Log.d("OverlayService", "=== ТЕСТ ДИНАМИЧЕСКОГО СРАВНЕНИЯ РЕЗУЛЬТАТОВ ===")

        gameScope.launch {
            try {
                Log.d("OverlayService", "Сбрасываем предыдущий результат")
                lastResult = null

                Log.d("OverlayService", "Запускаем тест динамического обнаружения результата")
                Log.d("OverlayService", "Автокликер будет адаптироваться к скорости игры...")

                val result = waitForResultWithInterval()

                if (result != null) {
                    Log.d("OverlayService", "✓ Результат обнаружен: $result")
                    showToast("✓ Результат: ${result.redDots} vs ${result.orangeDots}")
                } else {
                    Log.d("OverlayService", "✗ Результат не обнаружен")
                    showToast("✗ Результат не обнаружен")
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка в тесте сравнения результатов", e)
                showToast("Ошибка теста: ${e.message}")
            }
        }
    }

    // ───────────────────────────────── UI ───────────────────────────────────────
    private fun updateUI() {
        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            updateSingleModeUI()
        } else if (::expandedBinding.isInitialized) {
            updateDualModeUI()
        } else {
            Log.d("OverlayService", "UI не обновлен - binding не инициализирован")
        }
    }

    private fun updateSingleModeUI() {
        with(singleModeBinding) {
            try {
                Log.d("OverlayService", "Обновляем UI одиночного режима: isRunning=${gameState.isRunning}, isPaused=${gameState.isPaused}")

                // Получаем состояние из контроллера
                val controllerState = singleModeController?.getGameState()

                textStatus.text = when {
                    gameState.isPaused  -> "Пауза"
                    gameState.isRunning -> "Играем"
                    else                -> "Остановлено"
                }

                textCurrentBet.text = "${controllerState?.currentBet ?: gameState.currentBet} ₽"
                textCurrentColor.text = when (controllerState?.currentColor) {
                    BetColor.BLUE -> "Синий"
                    BetColor.RED -> "Красный"
                    else -> when (gameState.betChoice) {
                        BetChoice.RED -> "Красное"
                        BetChoice.ORANGE -> "Черное"
                        else -> "Не выбрано"
                    }
                }

                // Используем данные из контроллера, если доступны
                if (controllerState != null) {
                    textWins.text = "${controllerState.totalWins}"
                    textLosses.text = "${controllerState.totalLosses}"
                    textDraws.text = "${controllerState.totalDraws}"
                    textConsecutiveDraws.text = "${controllerState.consecutiveTies}"
                } else {
                    textWins.text = "$totalWins"
                    textLosses.text = "$totalLosses"
                    textDraws.text = "0"
                    textConsecutiveDraws.text = "0"
                }

                Log.d("OverlayService", "UI одиночного режима обновлен успешно")
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка обновления UI одиночного режима", e)
            }
        }
    }

    private fun updateDualModeUI() {
        with(expandedBinding) {
            try {
                Log.d("OverlayService", "Обновляем UI двойного режима: isRunning=${gameState.isRunning}, isPaused=${gameState.isPaused}")
                Log.d("OverlayService", "Настройки игры: baseBet=${gameState.baseBet}, currentBet=${gameState.currentBet}, betChoice=${gameState.betChoice}")

                tvStatus.text = when {
                    gameState.isPaused  -> "Пауза"
                    gameState.isRunning -> "Играем (${gameState.totalAttempts}/${gameState.maxAttempts})"
                    else                -> "Остановлено"
                }

                tvBalance.text = buildString {
                    append("Баланс: $totalBalance ₽ | ")
                    append("П: $totalWins | ")
                    append("П: $totalLosses | ")
                    append("Ставка: ${gameState.currentBet} ₽")
                }

                tvAttempt.text = getString(R.string.attempt_info, gameState.totalAttempts, gameState.maxAttempts)
                tvCurrentBet.text = getString(R.string.current_bet_info, gameState.currentBet)

                // Обновляем историю результатов
                updateHistoryDisplay()

                Log.d("OverlayService", "UI двойного режима обновлен успешно")
                Log.d("OverlayService", "Статус: ${tvStatus.text}")
                Log.d("OverlayService", "Баланс: ${tvBalance.text}")
                Log.d("OverlayService", "Попытка: ${tvAttempt.text}")
                Log.d("OverlayService", "Ставка: ${tvCurrentBet.text}")
            } catch (e: Exception) {
                Log.e("OverlayService", "Ошибка обновления UI двойного режима", e)
            }
        }
    }

    private fun updateHistoryDisplay() {
        if (!::expandedBinding.isInitialized) {
            Log.d("OverlayService", "История не обновлена - expandedBinding не инициализирован")
            return
        }

        try {
            val historyContainer = expandedBinding.historyContainer
            historyContainer.removeAllViews()

            val lastResults = gameState.roundHistory.takeLast(5)
            Log.d("OverlayService", "Обновляем историю: ${lastResults.size} результатов")

            lastResults.forEach { result: RoundResult ->
                val resultText = when {
                    result.isDraw -> "Н"
                    result.winner == BetChoice.RED -> "К"
                    result.winner == BetChoice.ORANGE -> "О"
                    else -> "?"
                }
                Log.d("OverlayService", "Добавляем результат в историю: $resultText (redDots=${result.redDots}, orangeDots=${result.orangeDots})")

                val resultView = TextView(this).apply {
                    text = resultText
                    // Никогда не запускать игру автоматически при старте сервиса
                    // Ожидаем явного нажатия пользователем кнопки "Старт"
                    textSize = 10f
                    setPadding(4, 2, 4, 2)
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    background = resources.getDrawable(R.drawable.card_background, null)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 2
                    }
                }
                historyContainer.addView(resultView)
            }
            Log.d("OverlayService", "История обновлена успешно")
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка обновления истории", e)
        }
    }

    // Универсальные функции для управления состоянием кнопок
    private fun updateButtonsForGameStart() {
        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            singleModeBinding.btnStartStop.text = "Стоп"
            singleModeBinding.btnPause.isEnabled = true
        } else if (::expandedBinding.isInitialized) {
            expandedBinding.btnStartStop.text = "Стоп"
            expandedBinding.btnPause.isEnabled = true
        }
    }

    private fun updateButtonsForGameStop() {
        if (currentMode == GameMode.SINGLE && ::singleModeBinding.isInitialized) {
            singleModeBinding.btnStartStop.text = "Старт"
            singleModeBinding.btnPause.isEnabled = false
        } else if (::expandedBinding.isInitialized) {
            expandedBinding.btnStartStop.text = "Старт"
            expandedBinding.btnPause.isEnabled = false
        }
    }

    private fun updateModeToggleButton() {
        if (::expandedBinding.isInitialized) {
            expandedBinding.btnModeToggle.isChecked = (currentMode == GameMode.SINGLE)
        }
    }

    private fun showToast(message: String) {
        Log.d("OverlayService", "Показываем Toast: $message")
        try {
            uiHandler.post {
                try {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    Log.d("OverlayService", "Toast показан: $message")
                } catch (e: Exception) {
                    Log.e("OverlayService", "Ошибка показа Toast", e)
                }
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка в showToast", e)
        }
    }

    // ──────────────────────────── Adaptive Waiting System ──────────────────────────────

    // Система для работы с рандомными интервалами появления кубиков
    private var adaptiveLastResultTime: Long = 0
    private var resultIntervals = mutableListOf<Long>()
    private val maxIntervalsToTrack = 20

    // БЫСТРАЯ адаптивная система ожидания с учетом истории интервалов
    private suspend fun waitForResultAdaptive(maxAttempts: Int = 60): RoundResult? {
        Log.d("OverlayService", "=== БЫСТРОЕ АДАПТИВНОЕ ОЖИДАНИЕ РЕЗУЛЬТАТА ===")

        // Вычисляем ожидаемый интервал на основе истории
        val expectedInterval = calculateExpectedInterval()
        Log.d("OverlayService", "Ожидаемый интервал: ${expectedInterval}мс")

        var currentInterval = 100L // Начинаем с сверхбыстрых проверок
        val minInterval = 50L
        val maxInterval = 1500L
        var attempts = 0
        var lastResult: RoundResult? = null
        var sameResultCount = 0
        val maxSameResultCount = 2 // Уменьшаем для более быстрой реакции
        var consecutiveInvalidResults = 0
        val maxConsecutiveInvalid = 5 // Максимум 5 невалидных результатов подряд

        // Сверхбыстрая начальная задержка перед анализом
        val initialDelay = (expectedInterval * 0.05).toLong().coerceAtMost(150L)
        Log.d("OverlayService", "Сверхбыстрая начальная задержка: ${initialDelay}мс")
        delay(initialDelay)

        try {
            while (attempts < maxAttempts && gameScope.isActive && gameState.isRunning) {
                Log.d("OverlayService", "Проверяем результат (попытка ${attempts + 1}/$maxAttempts, интервал: ${currentInterval}мс)")

                if (!gameState.isRunning) {
                    Log.d("OverlayService", "Игра остановлена во время ожидания результата")
                    return null
                }

                val screenshot = captureScreen()
                if (screenshot != null) {
                    val diceArea = savedAreas[AreaType.DICE_AREA]
                    if (diceArea != null) {
                        val result = analyzeDiceAreaWithValidation(screenshot, diceArea.rect)

                        if (result != null && result.isValid && (result.redDots > 0 || result.orangeDots > 0)) {
                            consecutiveInvalidResults = 0

                            // Проверяем стабильность результата
                            if (lastResult != null && result == lastResult) {
                                sameResultCount++
                                Log.d("OverlayService", "Результат стабилен: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}, счетчик=$sameResultCount")

                                // Возвращаем результат если он стабилен и имеет хорошую уверенность
                                if (sameResultCount >= maxSameResultCount && result.confidence >= 0.6f) {
                                    updateResultInterval()
                                    Log.d("OverlayService", "✓ АДАПТИВНЫЙ РЕЗУЛЬТАТ: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                    return result
                                }
                            } else {
                                // Новый результат
                                if (lastResult != null) {
                                    Log.d("OverlayService", "НОВЫЙ РЕЗУЛЬТАТ: было ${lastResult.redDots}:${lastResult.orangeDots}, стало ${result.redDots}:${result.orangeDots}")
                                } else {
                                    Log.d("OverlayService", "ПЕРВЫЙ РЕЗУЛЬТАТ: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                }
                                lastResult = result
                                sameResultCount = 1

                                // Быстрая реакция на высокоуверенный результат
                                if (result.confidence >= 0.6f) {
                                    updateResultInterval()
                                    Log.d("OverlayService", "✓ БЫСТРЫЙ РЕЗУЛЬТАТ: redDots=${result.redDots}, orangeDots=${result.orangeDots}, confidence=${result.confidence}")
                                    return result
                                }
                            }

                            // Уменьшаем интервал при получении валидного результата
                            if (currentInterval > minInterval) {
                                currentInterval = (currentInterval - 50).coerceAtLeast(minInterval)
                                Log.d("OverlayService", "Уменьшаем интервал до ${currentInterval}мс")
                            }
                        } else {
                            consecutiveInvalidResults++
                            Log.d("OverlayService", "Невалидный результат: redDots=${result?.redDots}, orangeDots=${result?.orangeDots}, isValid=${result?.isValid}")
                            Log.d("OverlayService", "Счетчик невалидных: $consecutiveInvalidResults/$maxConsecutiveInvalid")

                            // Адаптивно увеличиваем интервал
                            if (consecutiveInvalidResults >= maxConsecutiveInvalid) {
                                currentInterval = (currentInterval + 200).coerceAtMost(maxInterval)
                                Log.d("OverlayService", "Увеличиваем интервал до ${currentInterval}мс")
                                consecutiveInvalidResults = 0
                            }

                        }
                    } else {
                        Log.e("OverlayService", "❌ КРИТИЧЕСКАЯ ОШИБКА: Область кубиков (DICE_AREA) не настроена!")
                        Log.e("OverlayService", "Необходимо настроить области в интерфейсе приложения")
                        consecutiveInvalidResults++
                    }
                } else {
                    Log.e("OverlayService", "❌ ОШИБКА: Не удалось захватить экран - проверьте разрешения")

                    // Попытка автоматического восстановления разрешения
                    if (consecutiveInvalidResults == 0) { // Только при первой ошибке
                        Log.d("OverlayService", "Пытаемся восстановить разрешение на захват экрана...")

                        // Проверяем, есть ли сохраненное разрешение
                        val permissionData = prefsManager.getMediaProjectionPermission()
                        if (permissionData != null) {
                            val (savedCode, savedData) = permissionData
                            Log.d("OverlayService", "Найдено сохраненное разрешение: $savedCode с данными")
                            try {
                                startMediaProjection(savedCode, savedData)
                                Log.d("OverlayService", "✓ Разрешение восстановлено")
                                showToast("Разрешение восстановлено")
                                delay(1000) // Дать время на инициализацию
                                // Сбрасываем счетчик ошибок и продолжаем
                                consecutiveInvalidResults = 0
                                continue
                            } catch (e: Exception) {
                                Log.e("OverlayService", "Не удалось восстановить разрешение", e)
                                prefsManager.clearMediaProjectionPermission()
                            }
                        } else {
                            Log.d("OverlayService", "Нет сохраненного разрешения MediaProjection")
                        }

                        showToast("Необходимо переустановить разрешение на захват экрана")
                    }

                    // Если 5 ошибок подряд - пытаемся пересоздать ImageReader
                    if (consecutiveInvalidResults == 5) {
                        Log.d("OverlayService", "Пытаемся пересоздать ImageReader...")
                        if (recreateImageReader()) {
                            Log.d("OverlayService", "✓ ImageReader пересоздан успешно")
                            showToast("ImageReader пересоздан")
                            consecutiveInvalidResults = 0
                            continue
                        } else {
                            Log.e("OverlayService", "❌ Не удалось пересоздать ImageReader")
                        }
                    }

                    // Если много последовательных ошибок - принудительно запрашиваем разрешение
                    if (consecutiveInvalidResults >= 10) {
                        Log.e("OverlayService", "Слишком много ошибок захвата экрана - принудительный запрос разрешения")
                        forceRequestMediaProjection()
                        return null
                    }

                    consecutiveInvalidResults++
                }

                attempts++

                // Адаптивная задержка на основе истории
                val adaptiveDelay = calculateAdaptiveDelay(attempts, expectedInterval)
                if (attempts < maxAttempts) {
                    Log.d("OverlayService", "Ждем ${adaptiveDelay}мс до следующей проверки...")
                    delay(adaptiveDelay)
                }
            }
        } catch (e: CancellationException) {
            Log.d("OverlayService", "Адаптивное ожидание отменено")
            throw e
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка адаптивного ожидания", e)
        }

        Log.d("OverlayService", "Адаптивный результат не получен после $maxAttempts попыток")
        return null
    }

    // Вычисляем ожидаемый интервал на основе истории
    private fun calculateExpectedInterval(): Long {
        if (resultIntervals.isEmpty()) {
            return 800L // По умолчанию 800мс
        }

        // Убираем выбросы (слишком короткие и слишком длинные интервалы)
        val sortedIntervals = resultIntervals.sorted()
        val q1 = sortedIntervals[sortedIntervals.size / 4]
        val q3 = sortedIntervals[sortedIntervals.size * 3 / 4]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5 * iqr
        val upperBound = q3 + 1.5 * iqr

        val filteredIntervals = resultIntervals.filter { it.toDouble() in lowerBound..upperBound }

        if (filteredIntervals.isEmpty()) {
            return 800L
        }

        val averageInterval = filteredIntervals.average().toLong()
        Log.d("OverlayService", "Ожидаемый интервал: ${averageInterval}мс (на основе ${filteredIntervals.size} интервалов)")

        return averageInterval
    }

    // Вычисляем СВЕРХБЫСТРУЮ адаптивную задержку
    private fun calculateAdaptiveDelay(attempt: Int, expectedInterval: Long): Long {
        val baseDelay = when {
            attempt < 10 -> 50L // Сверхбыстрые проверки в начале
            attempt < 20 -> 100L // Очень быстрые интервалы
            attempt < 30 -> 150L // Быстрые интервалы
            else -> 300L // Средние интервалы
        }

        // Корректируем на основе ожидаемого интервала
        val adjustedDelay = (baseDelay * (expectedInterval / 2000.0)).toLong().coerceIn(50L, 1000L)

        return adjustedDelay
    }

    // Обновляем историю интервалов
    private fun updateResultInterval() {
        val currentTime = System.currentTimeMillis()
        if (adaptiveLastResultTime > 0) {
            val interval = currentTime - adaptiveLastResultTime
            resultIntervals.add(interval)

            if (resultIntervals.size > maxIntervalsToTrack) {
                resultIntervals.removeAt(0)
            }

            Log.d("OverlayService", "Обновлен интервал: ${interval}мс, всего интервалов: ${resultIntervals.size}")
        }
        adaptiveLastResultTime = currentTime
    }

    // ──────────────────────────── Testing & Debugging ──────────────────────────────

    // Диагностика системы для выявления проблем
    private fun performSystemDiagnostics(): String {
        val diagnostics = StringBuilder()

        diagnostics.append("=== ДИАГНОСТИКА СИСТЕМЫ ===\n")

        // Проверка областей
        diagnostics.append("1. Настроенные области:\n")
        AreaType.values().forEach { areaType ->
            val area = savedAreas[areaType]
            if (area != null) {
                diagnostics.append("   ✓ ${areaType.name}: ${area.rect.toShortString()}\n")
            } else {
                diagnostics.append("   ❌ ${areaType.name}: НЕ НАСТРОЕНА\n")
            }
        }

        // Проверка разрешений
        diagnostics.append("\n2. Разрешения:\n")
        val mediaProjectionData = prefsManager.getMediaProjectionPermission()
        if (mediaProjectionData != null) {
            val (resultCode, intent) = mediaProjectionData
            diagnostics.append("   ✓ Разрешение на захват экрана: ЕСТЬ (код: $resultCode)\n")
        } else {
            diagnostics.append("   ❌ Разрешение на захват экрана: НЕТ\n")
        }

        // Проверка OpenCV
        diagnostics.append("\n3. OpenCV:\n")
        try {
            val opencvInitialized = org.opencv.android.OpenCVLoader.initDebug()
            if (opencvInitialized) {
                diagnostics.append("   ✓ OpenCV инициализирован\n")
            } else {
                diagnostics.append("   ❌ OpenCV НЕ инициализирован\n")
            }
        } catch (e: Exception) {
            diagnostics.append("   ❌ OpenCV ошибка: ${e.message}\n")
        }

        // Проверка состояния игры
        diagnostics.append("\n4. Состояние игры:\n")
        diagnostics.append("   - Игра запущена: ${gameState.isRunning}\n")
        diagnostics.append("   - Текущая ставка: ${gameState.currentBet}\n")
        diagnostics.append("   - Базовая ставка: ${gameState.baseBet}\n")
        diagnostics.append("   - Проигрыши подряд: ${gameState.consecutiveLosses}\n")
        diagnostics.append("   - Всего попыток: ${gameState.totalAttempts}\n")

        // Проверка памяти
        diagnostics.append("\n5. Память:\n")
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        diagnostics.append("   - Используется: ${usedMemory}MB\n")
        diagnostics.append("   - Доступно: ${freeMemory}MB\n")
        diagnostics.append("   - Всего: ${totalMemory}MB\n")

        return diagnostics.toString()
    }

    // Функция для быстрого тестирования захвата экрана
    private suspend fun testScreenCapture(): Boolean {
        return try {
            val screenshot = captureScreen()
            if (screenshot != null) {
                Log.d("OverlayService", "✓ Тест захвата экрана успешен: ${screenshot.width}x${screenshot.height}")
                true
            } else {
                Log.e("OverlayService", "❌ Тест захвата экрана: получен null")
                false
            }
        } catch (e: Exception) {
            Log.e("OverlayService", "❌ Тест захвата экрана: ошибка", e)
            false
        }
    }

    private suspend fun performCorrectBetSetup(amount: Int) {
        Log.d("OverlayService", "=== ПРАВИЛЬНАЯ УСТАНОВКА СТАВКИ ===")
        Log.d("OverlayService", "Устанавливаем ставку: $amount")
        Log.d("OverlayService", "Текущая ставка: ${gameState.currentBet}, Целевая ставка: $amount")
        Log.d("OverlayService", "Базовая ставка: ${gameState.baseBet}")
        Log.d("OverlayService", "Последний активный результат: ${gameState.lastActiveResult}")

        try {
            // Проверяем, нужно ли использовать кнопку удвоения
            val shouldUseDouble = (gameState.lastActiveResult == GameResultType.LOSS ||
                    gameState.lastActiveResult == GameResultType.DRAW) &&
                    savedAreas[AreaType.DOUBLE_BUTTON] != null

            if (shouldUseDouble && amount > gameState.baseBet) {
                Log.d("OverlayService", "🔄 ИСПОЛЬЗУЕМ КНОПКУ УДВОЕНИЯ x2")

                // Рассчитываем, сколько раз нужно нажать x2
                var currentAmount = gameState.baseBet
                var clicksNeeded = 0

                while (currentAmount < amount && currentAmount * 2 <= amount) {
                    currentAmount *= 2
                    clicksNeeded++
                }

                Log.d("OverlayService", "Нужно нажать x2 $clicksNeeded раз: ${gameState.baseBet} → $amount")
                showToast("Удваиваем ставку ${clicksNeeded}x: ${gameState.baseBet} → $amount")

                // 1. Сначала ставим базовую ставку
                selectBetAmount(gameState.baseBet)
                delay(200)

                // 2. Выбираем цвет ставки
                selectBetChoice(gameState.betChoice)
                delay(200)

                // 3. Нажимаем кнопку удвоения нужное количество раз
                repeat(clicksNeeded) { i ->
                    Log.d("OverlayService", "Нажимаем x2 (${i + 1}/$clicksNeeded)")
                    clickDouble()
                    delay(200)
                }

                // 4. Подтверждаем ставку
                clickConfirmBet()
            } else {
                Log.d("OverlayService", "📊 ИСПОЛЬЗУЕМ ОБЫЧНУЮ УСТАНОВКУ СТАВКИ")

                // 1. Выбираем сумму ставки из номиналов
                selectBetAmount(amount)
                delay(200)

                // 2. Выбираем цвет ставки
                selectBetChoice(gameState.betChoice)
                delay(200)

                // 3. Подтверждаем ставку
                clickConfirmBet()
            }

            // Обновляем текущую ставку в состоянии
            gameState = gameState.copy(currentBet = amount)

            Log.d("OverlayService", "✓ Ставка успешно установлена: $amount на ${gameState.betChoice}")

        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка в установке ставки", e)
            showToast("Ошибка установки ставки: ${e.message}")
        }
    }

    // Функция для принудительного запроса разрешения при критических ошибках
    private fun forceRequestMediaProjection() {
        Log.d("OverlayService", "=== ПРИНУДИТЕЛЬНЫЙ ЗАПРОС РАЗРЕШЕНИЯ ===")

        // Очищаем все состояния
        stopMediaProjection()
        prefsManager.clearMediaProjectionPermission()

        // Показываем детальную информацию пользователю
        showToast("КРИТИЧЕСКАЯ ОШИБКА: Необходимо переустановить разрешение")

        // Останавливаем игру если она запущена
        if (gameState.isRunning) {
            stopGame()
        }

        // Запрашиваем разрешение заново
        if (!isRequestingProjection) {
            isRequestingProjection = true
            requestMediaProjection()
        }

        Log.d("OverlayService", "Принудительный запрос разрешения отправлен")
    }

    // Функция для пересоздания ImageReader при критических ошибках
    private fun recreateImageReader(): Boolean {
        return try {
            Log.d("OverlayService", "=== ПЕРЕСОЗДАНИЕ IMAGEREADER ===")

            // Останавливаем старый ImageReader
            imageReader?.close()
            imageReader = null

            // Пересоздаем ImageReader
            val metrics: DisplayMetrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            Log.d("OverlayService", "Новый ImageReader создан: ${w}x${h}")

            imageReader?.setOnImageAvailableListener({ reader ->
                // Обработка изображений будет происходить в captureScreen()
            }, uiHandler)

            // Пересоздаем VirtualDisplay с новым ImageReader
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DiceAutoBetCapture", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, uiHandler
            )

            Log.d("OverlayService", "VirtualDisplay пересоздан")
            Thread.sleep(1000) // Стабилизация

            true
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка пересоздания ImageReader", e)
            false
        }
    }

    private fun testDoubleProcess() {
        Log.d("OverlayService", "=== ТЕСТИРОВАНИЕ НОВОЙ АЛЬТЕРНИРУЮЩЕЙ СТРАТЕГИИ ===")
        showToast("🔄 Удвоение отключено - используется альтернирующая стратегия")
    }
    
    /**
     * Отправка логов пользователю через Android Share Intent
     */
    private fun sendLogsToUser() {
        try {
            val logFile = FileLogger.getLogFile()
            
            if (logFile == null || !logFile.exists()) {
                showToast("❌ Файл логов не найден")
                Log.e("OverlayService", "Файл логов не существует")
                return
            }
            
            // Создаем URI для файла через FileProvider
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )
            
            // Создаем Intent для отправки
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "DiceAutoBet Logs - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                putExtra(Intent.EXTRA_TEXT, "Логи приложения DiceAutoBet")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Показываем диалог выбора приложения для отправки
            val chooser = Intent.createChooser(shareIntent, "Отправить логи через...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            startActivity(chooser)
            showToast("📤 Выберите приложение для отправки логов")
            Log.d("OverlayService", "Диалог отправки логов открыт")
            
        } catch (e: Exception) {
            Log.e("OverlayService", "Ошибка отправки логов", e)
            showToast("❌ Ошибка отправки логов: ${e.message}")
        }
    }
}
