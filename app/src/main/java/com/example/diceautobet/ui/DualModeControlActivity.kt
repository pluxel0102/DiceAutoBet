package com.example.diceautobet.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.diceautobet.R
import com.example.diceautobet.models.*
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.services.DualModeService
import com.example.diceautobet.services.DualModeFloatingControlService
import com.example.diceautobet.timing.PerformanceStats
import com.example.diceautobet.sync.SyncStats
import com.example.diceautobet.databinding.ActivityDualModeControlBinding
import kotlinx.coroutines.*
import java.io.File

/**
 * Упрощенный интерфейс управления двойным режимом
 * Работает с новой стратегией SimpleDualModeController
 */
class DualModeControlActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DualModeControlActivity"
        private const val STATS_UPDATE_INTERVAL = 1000L // 1 секунда
    }
    
    private lateinit var binding: ActivityDualModeControlBinding
    private var dualModeService: DualModeService? = null
    private var dualWindowAreaManager: DualWindowAreaManager? = null
    private var bound = false
    
    // Корутины для обновлений в реальном времени
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statsUpdateJob: Job? = null
    
    // Текущее состояние упрощенного режима
    private var currentGameState: SimpleDualModeState? = null
    
    // Подключение к сервису
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "Подключение к DualModeService")
            val binder = service as DualModeService.LocalBinder
            dualModeService = binder.getService()
            bound = true
            
            Log.d(TAG, "Сервис подключен, вызываем setupServiceListeners")
            setupServiceListeners()
            
            Log.d(TAG, "Вызываем loadInitialData")
            loadInitialData()
            
            Log.d(TAG, "Запускаем обновления в реальном времени")
            // Не запускаем обновления до фактического старта ▶
            // startRealTimeUpdates()
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d(TAG, "Отключение от DualModeService")
            bound = false
            dualModeService = null
            dualWindowAreaManager = null
            stopRealTimeUpdates()
            enableUI(false)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "=== DualModeControlActivity.onCreate() вызван ===")
        
        binding = ActivityDualModeControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // НЕ инициализируем DualWindowAreaManager в onCreate() - только при старте автоматизации
        Log.d(TAG, "DualWindowAreaManager будет инициализирован при запуске автоматизации")
        
        Log.d(TAG, "Вызываем setupUI()")
        setupUI()
        
        Log.d(TAG, "Вызываем bindService()")
        bindService()
        
        Log.d(TAG, "DualModeControlActivity создана")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopRealTimeUpdates()
        unbindService()
        uiScope.cancel()
    }
    
    override fun onResume() {
        super.onResume()
        // Проверяем разрешение на overlay при возвращении в активность
        checkOverlayPermissionStatus()
    }
    
    /**
     * Проверяет статус разрешения на отображение поверх других приложений
     */
    private fun checkOverlayPermissionStatus() {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Разрешение на overlay предоставлено")
            showStatusMessage("Разрешения настроены корректно", true)
        } else {
            Log.w(TAG, "Разрешение на overlay не предоставлено")
            showStatusMessage("Требуется разрешение для плавающих окон", false)
        }
    }
    
    // === НАСТРОЙКА UI ===
    
    /**
     * Настраивает пользовательский интерфейс
     */
    private fun setupUI() {
        Log.d(TAG, "setupUI() начался")
        setupToolbar()
        setupControlButtons()
        setupStrategySelector()
        setupSettingsPanel()
        setupStatisticsPanel()
        setupPerformancePanel()
        
        Log.d(TAG, "Вызываем enableUI(false) - отключаем кнопки до подключения сервиса")
        // Изначально все отключено до подключения к сервису
        enableUI(false)
        Log.d(TAG, "setupUI() завершен")
    }
    
    /**
     * Настраивает тулбар
     */
    private fun setupToolbar() {
        binding.toolbar.title = "Двойной режим"
        binding.toolbar.subtitle = "Автоматическая игра на двух окнах"
        setSupportActionBar(binding.toolbar)
    }
    
    /**
     * Настраивает кнопки управления
     */
    private fun setupControlButtons() {
        binding.btnStartDualMode.setOnClickListener {
            startDualMode()
        }
        
        binding.btnStopDualMode.setOnClickListener {
            stopDualMode()
        }
        
        binding.btnSwitchWindow.setOnClickListener {
            switchWindow()
        }
        
        binding.btnOptimizePerformance.setOnClickListener {
            optimizePerformance()
        }
        
        // Добавляем кнопку настройки областей для двойного режима
        binding.btnConfigureAreas?.setOnClickListener {
            openAreaConfiguration()
        }
        
        // Изначально кнопка старт активна, остальные нет
        updateControlButtons(false)
    }
    
    /**
     * Настраивает селектор стратегий
     */
    private fun setupStrategySelector() {
        // Упрощенная версия использует фиксированную стратегию
        // Убираем селектор стратегии из UI
        
        // Показываем информацию о стратегии
        updateSettingsUI()
    }
    
    /**
     * Настраивает панель настроек (упрощенная)
     */
    private fun setupSettingsPanel() {
        // Упрощенная версия не требует настройки параметров
        // Все параметры зафиксированы в контроллере
        
        // Настройка переключателя отладки изображений
        setupDebugImagesSwitch()

        // Настройка кнопки просмотра изображений
        setupViewDebugImagesButton()
        
        // Скрываем ненужные элементы настроек если они есть
        try {
            binding.sliderMaxLosses?.let { slider ->
                slider.isEnabled = false
                slider.alpha = 0.5f
            }
            binding.radioGroupStrategy?.let { group ->
                group.isEnabled = false
                group.alpha = 0.5f
            }
            binding.switchDualModeEnabled?.let { switch ->
                switch.isChecked = true
                switch.isEnabled = false
                switch.alpha = 0.5f
            }
        } catch (e: Exception) {
            Log.d(TAG, "Некоторые элементы настроек не найдены в макете: ${e.message}")
        }
    }

    /**
     * Настраивает переключатель отладки изображений
     */
    private fun setupDebugImagesSwitch() {
        binding.switchDebugImages?.let { switch ->
            // Загружаем текущее состояние
            val prefsManager = com.example.diceautobet.utils.PreferencesManager(this)
            switch.isChecked = prefsManager.isDebugImagesEnabled()

            // Обработчик изменений
            switch.setOnCheckedChangeListener { _, isChecked ->
                prefsManager.saveDebugImagesEnabled(isChecked)
                Log.d(TAG, "Отладка изображений ${if (isChecked) "включена" else "отключена"}")

                if (isChecked) {
                    Toast.makeText(this, "📸 Отладочные изображения будут сохраняться в Android/data/com.example.diceautobet/files/", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "📸 Сохранение отладочных изображений отключено", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Настраивает кнопку просмотра отладочных изображений
     */
    private fun setupViewDebugImagesButton() {
        binding.btnViewDebugImages?.setOnClickListener {
            try {
                val externalFilesDir = getExternalFilesDir(null)
                if (externalFilesDir != null) {
                    val geminiDir = File(externalFilesDir, "Gemini_Crops")
                    val debugDir = File(externalFilesDir, "DiceAutoBet_Debug_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date()))

                    val dirs = mutableListOf<File>()
                    if (geminiDir.exists() && geminiDir.listFiles()?.isNotEmpty() == true) {
                        dirs.add(geminiDir)
                    }
                    if (debugDir.exists() && debugDir.listFiles()?.isNotEmpty() == true) {
                        dirs.add(debugDir)
                    }

                    when {
                        dirs.isEmpty() -> {
                            Toast.makeText(this, "📁 Изображения не найдены. Включите отладку и запустите анализ.", Toast.LENGTH_LONG).show()
                        }
                        dirs.size == 1 -> {
                            openDirectory(dirs[0])
                        }
                        else -> {
                            // Показываем диалог выбора папки
                            showDirectorySelectionDialog(dirs)
                        }
                    }
                } else {
                    Toast.makeText(this, "❌ Не удалось получить доступ к хранилищу", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка открытия папки с изображениями", e)
                Toast.makeText(this, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Открывает директорию с изображениями
     */
    private fun openDirectory(directory: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(directory), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Если стандартный файловый менеджер не может открыть папку,
            // показываем путь к папке
            if (intent.resolveActivity(packageManager) == null) {
                val path = directory.absolutePath
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Путь к изображениям", path)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(
                    this,
                    "📋 Путь скопирован в буфер:\n$path\n\nИспользуйте файловый менеджер с доступом к данным приложений.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startActivity(intent)
                Toast.makeText(this, "📂 Открыта папка: ${directory.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия директории", e)
            val path = directory.absolutePath
            Toast.makeText(this, "📋 Путь к изображениям: $path", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Показывает диалог выбора папки
     */
    private fun showDirectorySelectionDialog(directories: List<File>) {
        val items = directories.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите папку с изображениями")
            .setItems(items) { _, which ->
                openDirectory(directories[which])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    /**
     * Настраивает панель статистики
     */
    private fun setupStatisticsPanel() {
        // Статистика обновляется автоматически через updateStatistics()
    }
    
    /**
     * Настраивает панель производительности
     */
    private fun setupPerformancePanel() {
        // Производительность обновляется автоматически через updatePerformanceStats()
    }
    
    // === УПРАВЛЕНИЕ СЕРВИСОМ ===
    
    /**
     * Подключается к сервису
     */
    private fun bindService() {
        Log.d(TAG, "bindService() начался - попытка подключения к DualModeService")
        val intent = Intent(this, DualModeService::class.java)
        val result = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "bindService() завершен, результат: $result")
    }
    
    /**
     * Отключается от сервиса
     */
    private fun unbindService() {
        if (bound) {
            Log.d(TAG, "Отключение от DualModeService")
            unbindService(connection)
            bound = false
        }
    }
    
    /**
     * Настраивает слушатели сервиса
     */
    private fun setupServiceListeners() {
        // В упрощенной версии мы используем опрос состояния вместо событий
        dualModeService?.let { service ->
            Log.d(TAG, "Сервис настроен для работы с упрощенным контроллером")
            // service.setOnGameStateChangedListener { gameState ->
            //     currentGameState = gameState
            //     updateGameStateUI(gameState)
            // }
            
            // service.setOnWindowSwitchedListener { windowType ->
            //     updateActiveWindowUI(windowType)
            // }
            
            // service.setOnBetPlacedListener { windowType, betChoice, amount ->
            //     updateBetPlacedUI(windowType, betChoice, amount)
            // }
            
            // service.setOnResultDetectedListener { windowType, result ->
            //     updateResultDetectedUI(windowType, result)
            // }
        }
    }
    
    /**
     * Загружает начальные данные для упрощенного режима
     */
    private fun loadInitialData() {
        dualModeService?.let { service ->
            // Получаем текущее состояние через новый метод
            val currentState = service.getCurrentState()
            Log.d(TAG, "Текущее состояние сервиса: $currentState")
            
            // Инициализируем менеджер областей
            dualWindowAreaManager = DualWindowAreaManager(this)
            
            // Обновляем UI состояние
            updateSimplifiedGameStateUI(currentState)
            
            enableUI(true)
            
            Log.d(TAG, "Начальные данные загружены, UI включен")
        } ?: run {
            Log.e(TAG, "DualModeService недоступен в loadInitialData")
        }
    }
    
    // === ДЕЙСТВИЯ ПОЛЬЗОВАТЕЛЯ ===
    
    /**
     * Запускает двойной режим
     */
    private fun startDualMode() {
        Log.d(TAG, "Запуск двойного режима - кнопка нажата")
        
        // Проверяем разрешение на отображение поверх других приложений
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Нет разрешения на отображение поверх других приложений")
            showStatusMessage("Требуется разрешение для плавающих окон", false)
            requestOverlayPermission()
            return
        }
        
        if (dualModeService == null) {
            Log.e(TAG, "DualModeService не подключен!")
            showStatusMessage("Сервис не готов", false)
            return
        }
        
        // ТОЛЬКО СЕЙЧАС инициализируем DualWindowAreaManager
        if (dualWindowAreaManager == null) {
            Log.d(TAG, "Инициализируем DualWindowAreaManager при запуске автоматизации")
            dualWindowAreaManager = DualWindowAreaManager(this)
        }
        
        // Проверяем готовность через AreaManager
        val areaManager = dualWindowAreaManager
        if (areaManager != null) {
            val configStatus = areaManager.getConfigurationStatus()
            Log.d(TAG, "Статус конфигурации: splitScreen=${configStatus.splitScreenSupported}, " +
                    "${configStatus.firstWindowType}=${configStatus.leftWindowConfigured} (${configStatus.leftAreasCount} областей), " +
                    "${configStatus.secondWindowType}=${configStatus.rightWindowConfigured} (${configStatus.rightAreasCount} областей)")
            
            if (!configStatus.readyForDualMode) {
                Log.w(TAG, "Конфигурация не готова для запуска")
                showStatusMessage("Не все области настроены", false)
                return
            }
        } else {
            Log.e(TAG, "DualWindowAreaManager не удалось инициализировать!")
            showStatusMessage("Менеджер областей не готов", false)
            return
        }
    // ВАЖНО: не запускаем режим здесь. Только показываем плавающий контроль,
    // а фактический старт произойдет по нажатию ▶ в overlay.
    Log.d(TAG, "Не запускаем dualModeService здесь — только открываем плавающее окно управления")

    // Показываем плавающий контроль
    val floatingServiceIntent = Intent(this, DualModeFloatingControlService::class.java)
    startService(floatingServiceIntent)

        // Страховка: останавливаем одиночный OverlayService, если он еще работал
        try {
            stopService(Intent(this, com.example.diceautobet.services.OverlayService::class.java))
        } catch (_: Exception) { }
        
        // Анимация кнопки
        animateButton(binding.btnStartDualMode, Color.GREEN)
        
    // Обновляем состояние: режим еще не запущен, ожидаем нажатия ▶ в overlay
    updateControlButtons(false)
    showStatusMessage("Плавающее окно открыто. Запуск по кнопке ▶", true)
        
        // Сворачиваем активность, чтобы пользователь мог перейти к нужному приложению
        moveTaskToBack(true)
        
    Toast.makeText(this, "Плавающий контроль активирован. Перейдите к нужному приложению", Toast.LENGTH_LONG).show()
    }
    
    /**
     * Останавливает двойной режим
     */
    private fun stopDualMode() {
        Log.d(TAG, "Остановка двойного режима")
        
        dualModeService?.stopDualMode()
        
        // Анимация кнопки
        animateButton(binding.btnStopDualMode, Color.RED)
        
        updateControlButtons(false)
        showStatusMessage("Двойной режим остановлен", false)
    }
    
    /**
     * Переключает активное окно
     */
    private fun switchWindow() {
        Log.d(TAG, "Переключение окна (в упрощенной версии не поддерживается)")
        
        // dualModeService?.switchActiveWindow()
        
        // Анимация кнопки
        animateButton(binding.btnSwitchWindow, Color.BLUE)
    }
    
    /**
     * Принудительно оптимизирует производительность
     */
    private fun optimizePerformance() {
        Log.d(TAG, "Оптимизация производительности (упрощенная версия)")
        
        // Упрощенная версия - показываем информацию о симуляторе
        val simulatorInfo = dualModeService?.getSimulatorInfo() ?: "Симулятор недоступен"
        
        // Анимация кнопки
        try {
            animateButton(binding.btnOptimizePerformance, Color.CYAN)
        } catch (e: Exception) {
            Log.d(TAG, "Кнопка оптимизации не найдена: ${e.message}")
        }
        
        showStatusMessage("Симулятор: ${simulatorInfo.lines().firstOrNull() ?: "Активен"}", true)
    }
    
    /**
     * Открывает настройку областей для двойного режима
     */
    private fun openAreaConfiguration() {
        Log.d(TAG, "Открытие настройки областей")
        
        val intent = Intent(this, DualModeAreaConfigActivity::class.java)
        startActivity(intent)
    }
    
    // === ОБНОВЛЕНИЕ НАСТРОЕК (упрощенная версия) ===
    
    private fun updateStrategy(strategy: DualStrategy) {
        // Упрощенная версия использует фиксированную стратегию
        Log.d(TAG, "Упрощенная версия: настройка стратегии не требуется")
    }
    
    private fun updateMaxLosses(maxLosses: Int) {
        // Упрощенная версия использует фиксированные параметры
        Log.d(TAG, "Упрощенная версия: настройка максимальных проигрышей не требуется")
    }
    
    private fun updateDualModeEnabled(enabled: Boolean) {
        // Упрощенная версия всегда включена
        Log.d(TAG, "Упрощенная версия: двойной режим всегда доступен")
    }
    
    // === ОБНОВЛЕНИЕ UI ===
    
    /**
     * Включает/отключает UI
     */
    private fun enableUI(enabled: Boolean) {
        Log.d(TAG, "enableUI($enabled) вызван")
        binding.btnStartDualMode.isEnabled = enabled
        binding.btnStopDualMode.isEnabled = enabled
        binding.btnSwitchWindow.isEnabled = enabled
        binding.btnOptimizePerformance.isEnabled = enabled
        binding.radioGroupStrategy.isEnabled = enabled
        binding.sliderMaxLosses.isEnabled = enabled
        binding.switchDualModeEnabled.isEnabled = enabled
        Log.d(TAG, "enableUI($enabled) завершен, btnStartDualMode.isEnabled = ${binding.btnStartDualMode.isEnabled}")
    }
    
    /**
     * Обновляет кнопки управления
     */
    private fun updateControlButtons(isRunning: Boolean) {
        binding.btnStartDualMode.isEnabled = !isRunning
        binding.btnStopDualMode.isEnabled = isRunning
        binding.btnSwitchWindow.isEnabled = isRunning
        binding.btnOptimizePerformance.isEnabled = true
        
        // Цвета кнопок
        if (isRunning) {
            binding.btnStartDualMode.setBackgroundColor(Color.GRAY)
            binding.btnStopDualMode.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
        } else {
            binding.btnStartDualMode.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            binding.btnStopDualMode.setBackgroundColor(Color.GRAY)
        }
    }
    
    /**
     * Обновляет UI состояния игры
     */
    private fun updateGameStateUI(gameState: SimpleDualModeState) {
        lifecycleScope.launch {
            // Основная информация
            binding.textCurrentMode.text = if (gameState.isRunning) "АКТИВЕН" else "ОСТАНОВЛЕН"
            binding.textCurrentStrategy.text = "Упрощенная стратегия"
            binding.textActiveWindow.text = gameState.currentWindow.name
            
            // Статистика
            binding.textTotalBets.text = gameState.totalBets.toString()
            binding.textTotalProfit.text = "${gameState.totalProfit}"
            binding.textConsecutiveLosses.text = gameState.consecutiveLosses.toString()
            binding.textWaitingForResult.text = "НЕТ" // Упрощенная версия не отслеживает ожидание
            
            // Дополнительная информация для упрощенной стратегии (если элементы есть в layout)
            try {
                // Заменяем несуществующие элементы на логирование
                // binding.textCurrentColor?.text = if (gameState.currentColor == BetChoice.RED) "Красный" else "Оранжевый"
                // binding.textCurrentBet?.text = "${gameState.currentBet}"
                // binding.textLastResult?.text = when (gameState.lastResult) {
                //     GameResultType.WIN -> "Выигрыш"
                //     GameResultType.LOSS -> "Проигрыш" 
                //     GameResultType.DRAW -> "Ничья"
                //     GameResultType.UNKNOWN -> "Неизвестно"
                // }
                
                // Используем логирование вместо несуществующих элементов
                Log.d(TAG, "Цвет: ${if (gameState.currentColor == BetChoice.RED) "Красный" else "Оранжевый"}")
                Log.d(TAG, "Ставка: ${gameState.currentBet}")
                Log.d(TAG, "Результат: ${when (gameState.lastResult) {
                    GameResultType.WIN -> "Выигрыш"
                    GameResultType.LOSS -> "Проигрыш" 
                    GameResultType.DRAW -> "Проигрыш (ничья)"
                    GameResultType.UNKNOWN -> "Неизвестно"
                }}")
            } catch (e: Exception) {
                // Игнорируем ошибки если элементы отсутствуют в layout
                Log.d(TAG, "Некоторые элементы UI не найдены: ${e.message}")
            }
            
            // Обновляем кнопки
            updateControlButtons(gameState.isRunning)
            
            // Цвет статуса
            val statusColor = if (gameState.isRunning) Color.GREEN else Color.RED
            binding.textCurrentMode.setTextColor(statusColor)
            
            // Цвет прибыли
            val profitColor = if (gameState.totalProfit >= 0) Color.GREEN else Color.RED
            binding.textTotalProfit.setTextColor(profitColor)
        }
    }
    
    /**
     * Обновляет UI для упрощенного состояния игры
     */
    private fun updateSimplifiedGameStateUI(state: SimpleDualModeState) {
        try {
            // Основная информация о состоянии
            binding.textCurrentMode.text = if (state.isRunning) "Запущен" else "Остановлен"
            binding.textTotalBets.text = "Ставок: ${state.totalBets}"
            binding.textTotalProfit.text = "Прибыль: ${state.totalProfit}"
            
            // Текущее состояние ставки (заменяем несуществующие элементы на логирование)
            // binding.textCurrentBet.text = "Ставка: ${state.currentBet}"
            // binding.textCurrentColor.text = "Цвет: ${if (state.currentColor == BetChoice.RED) "Красный" else "Оранжевый"}"
            // binding.textLastResult.text = "Результат: ${when (state.lastResult) {
            //     GameResultType.WIN -> "Выигрыш"
            //     GameResultType.LOSS -> "Проигрыш"
            //     GameResultType.DRAW -> "Ничья"
            //     else -> "Неизвестно"
            // }}"
            
            // Логируем информацию о ставке
            Log.d(TAG, "Упрощенное состояние: Ставка=${state.currentBet}, Цвет=${if (state.currentColor == BetChoice.RED) "Красный" else "Оранжевый"}, Результат=${when (state.lastResult) {
                GameResultType.WIN -> "Выигрыш"
                GameResultType.LOSS -> "Проигрыш"
                GameResultType.DRAW -> "Проигрыш (ничья)"
                else -> "Неизвестно"
            }}")
            
            // Статистика проигрышей
            binding.textConsecutiveLosses?.text = "Проигрыши подряд: ${state.consecutiveLosses}"
            
            // Обновляем кнопки
            updateControlButtons(state.isRunning)
            // Запускаем/останавливаем обновления только при изменении в запущенное состояние
            if (state.isRunning && statsUpdateJob == null) startRealTimeUpdates()
            if (!state.isRunning && statsUpdateJob != null) stopRealTimeUpdates()
            
            // Цвет статуса
            val statusColor = if (state.isRunning) Color.GREEN else Color.RED
            binding.textCurrentMode.setTextColor(statusColor)
            
            // Цвет прибыли
            val profitColor = if (state.totalProfit >= 0) Color.GREEN else Color.RED
            binding.textTotalProfit.setTextColor(profitColor)
            
            Log.d(TAG, "UI обновлен для состояния: $state")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при обновлении UI: ${e.message}")
        }
    }

    /**
     * Обновляет UI настроек (упрощенная версия)
     */
    private fun updateSettingsUI() {
        lifecycleScope.launch {
            // Упрощенная версия не требует настроек стратегии
            // Все параметры фиксированы в SimpleDualModeController
            
            // Показываем информацию о текущей стратегии (логируем вместо отображения)
            try {
                // binding.textStrategyInfo?.text = """
                //     📋 Упрощенная стратегия:
                //     • Старт: минимальная ставка (10) на красный в левом окне
                //     • Выигрыш: минимальная ставка на тот же цвет в правом окне
                //     • Проигрыш: ставка ×2 на тот же цвет в другом окне
                //     • 2 проигрыша подряд: смена цвета + ставка ×2
                // """.trimIndent()
                
                Log.d(TAG, """
                    📋 Упрощенная стратегия:
                    • Старт: минимальная ставка (10) на красный в левом окне
                    • Выигрыш: минимальная ставка на тот же цвет в правом окне
                    • Проигрыш: ставка ×2 на тот же цвет в другом окне
                    • 2 проигрыша подряд: смена цвета + ставка ×2
                """.trimIndent())
            } catch (e: Exception) {
                // Игнорируем ошибки если элемент отсутствует в layout
                Log.d(TAG, "Элемент textStrategyInfo не найден: ${e.message}")
            }
        }
    }
    
    /**
     * Обновляет UI активного окна
     */
    private fun updateActiveWindowUI(windowType: WindowType) {
        lifecycleScope.launch {
            binding.textActiveWindow.text = windowType.name
            
            // Анимация смены окна
            animateWindowSwitch(windowType)
        }
    }
    
    /**
     * Обновляет UI при размещении ставки
     */
    private fun updateBetPlacedUI(windowType: WindowType, betChoice: BetChoice, amount: Int) {
        lifecycleScope.launch {
            val message = "Ставка $amount на ${betChoice.name} в окне ${windowType.name}"
            showStatusMessage(message, true)
            
            // Анимация ставки
            animateBetPlaced(amount)
        }
    }
    
    /**
     * Обновляет UI при детекции результата
     */
    private fun updateResultDetectedUI(windowType: WindowType, result: RoundResult) {
        lifecycleScope.launch {
            val isWin = result.winner != null && !result.isDraw
            val resultText = if (isWin) "ВЫИГРЫШ" else "ПРОИГРЫШ"
            val message = "$resultText в окне ${windowType.name}: ${result.redDots} - ${result.orangeDots}"
            showStatusMessage(message, isWin)
            
            // Анимация результата
            animateResult(isWin)
        }
    }
    
    // === ОБНОВЛЕНИЯ В РЕАЛЬНОМ ВРЕМЕНИ ===
    
    /**
     * Запускает обновления в реальном времени
     */
    private fun startRealTimeUpdates() {
        statsUpdateJob = uiScope.launch {
            while (isActive) {
                updateRealTimeStats()
                delay(STATS_UPDATE_INTERVAL)
            }
        }
    }
    
    /**
     * Останавливает обновления в реальном времени
     */
    private fun stopRealTimeUpdates() {
        statsUpdateJob?.cancel()
        statsUpdateJob = null
    }
    
    /**
     * Обновляет статистику в реальном времени
     */
    private fun updateRealTimeStats() {
        try {
            dualModeService?.let { service ->
                // До реального старта не шумим логами и не обновляем панель
                if (!service.isRunning()) return
                // Получаем статистику через контроллер
                val statsText = service.getStatisticsText()
                Log.d(TAG, "Обновление статистики: $statsText")
                
                // Создаем базовую карту статистики
                val stats = mapOf(
                    "statisticsText" to statsText,
                    "isRunning" to service.isRunning(),
                    "timestamp" to System.currentTimeMillis()
                )
                updatePerformanceStats(stats)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при обновлении статистики: ${e.message}")
        }
    }
    
    /**
     * Обновляет статистику производительности
     */
    private fun updatePerformanceStats(stats: Map<String, Any>) {
        lifecycleScope.launch {
            // Извлекаем данные из статистики
            val timingStats = stats["timing"] as? PerformanceStats
            val syncStats = stats["synchronization"] as? SyncStats
            val optimizedTimings = stats["optimized_timings"] as? Map<String, Long>
            val readyForFastSwitch = stats["ready_for_fast_switch"] as? Boolean ?: false
            
            // Обновляем UI производительности
            timingStats?.let { timing ->
                binding.textTotalOperations.text = timing.totalOperations.toString()
                binding.textSlowOperations.text = timing.slowOperations.toString()
                binding.textAverageReactionTime.text = "${timing.averageReactionTime}мс"
                binding.textSuccessRate.text = "${(timing.successRate * 100).toInt()}%"
                binding.textPerformanceMode.text = timing.currentMode
                
                // Цвет режима производительности
                val modeColor = when (timing.currentMode) {
                    "HIGH_PERFORMANCE" -> Color.GREEN
                    "REDUCED_LOAD" -> Color.RED
                    else -> Color.YELLOW
                }
                binding.textPerformanceMode.setTextColor(modeColor)
            }
            
            // Обновляем UI синхронизации
            syncStats?.let { sync ->
                binding.textLeftQueueSize.text = sync.leftQueueSize.toString()
                binding.textRightQueueSize.text = sync.rightQueueSize.toString()
                binding.textBothWindowsBusy.text = if (sync.bothWindowsBusy) "ДА" else "НЕТ"
                binding.textReadyForFastSwitch.text = if (readyForFastSwitch) "ДА" else "НЕТ"
                
                // Цвета состояния окон
                binding.textLeftQueueSize.setTextColor(if (sync.isLeftBusy) Color.RED else Color.GREEN)
                binding.textRightQueueSize.setTextColor(if (sync.isRightBusy) Color.RED else Color.GREEN)
            }
            
            // Обновляем оптимизированные тайминги
            optimizedTimings?.let { timings ->
                binding.textDetectionInterval.text = "${timings["detectionInterval"]}мс"
                binding.textReactionDelay.text = "${timings["reactionDelay"]}мс"
                binding.textClickDelay.text = "${timings["clickDelay"]}мс"
            }
        }
    }
    
    // === АНИМАЦИИ ===
    
    /**
     * Анимирует кнопку
     */
    private fun animateButton(button: android.widget.Button, color: Int) {
        val originalColor = button.currentTextColor
        
        ValueAnimator.ofArgb(originalColor, color, originalColor).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                button.setTextColor(animator.animatedValue as Int)
            }
            start()
        }
    }
    
    /**
     * Анимирует смену окна
     */
    private fun animateWindowSwitch(windowType: WindowType) {
        val windowView = if (windowType == WindowType.LEFT) {
            binding.viewLeftWindow
        } else {
            binding.viewRightWindow
        }
        
        // Подсветка активного окна
        ValueAnimator.ofFloat(0.3f, 1.0f, 0.8f).apply {
            duration = 500
            addUpdateListener { animator ->
                windowView.alpha = animator.animatedValue as Float
            }
            start()
        }
    }
    
    /**
     * Анимирует размещение ставки
     */
    private fun animateBetPlaced(amount: Int) {
        // Анимация суммы ставки
        binding.textTotalBets.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(200)
            .withEndAction {
                binding.textTotalBets.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }
    
    /**
     * Анимирует результат
     */
    private fun animateResult(isWin: Boolean) {
        val color = if (isWin) Color.GREEN else Color.RED
        val profitView = binding.textTotalProfit
        
        // Мигание цветом результата
        ValueAnimator.ofArgb(profitView.currentTextColor, color, profitView.currentTextColor).apply {
            duration = 600
            addUpdateListener { animator ->
                profitView.setTextColor(animator.animatedValue as Int)
            }
            start()
        }
    }
    
    /**
     * Показывает статусное сообщение
     */
    private fun showStatusMessage(message: String, isSuccess: Boolean) {
        lifecycleScope.launch {
            binding.textStatusMessage.text = message
            val color = if (isSuccess) Color.GREEN else Color.RED
            binding.textStatusMessage.setTextColor(color)
            
            // Автоматическое скрытие через 3 секунды
            delay(3000)
            binding.textStatusMessage.text = ""
        }
    }
    
    /**
     * Запрашивает разрешение на отображение поверх других приложений
     */
    private fun requestOverlayPermission() {
        Log.d(TAG, "Запрос разрешения на отображение поверх других приложений")
        
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            
            Toast.makeText(
                this,
                "Предоставьте разрешение 'Поверх других приложений' и вернитесь в приложение",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запросе разрешения на overlay: ${e.message}")
            Toast.makeText(
                this,
                "Не удалось открыть настройки разрешений",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
