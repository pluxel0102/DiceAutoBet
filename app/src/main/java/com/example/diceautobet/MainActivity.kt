package com.example.diceautobet

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.graphics.Color
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.diceautobet.databinding.ActivityMainBinding
import com.example.diceautobet.models.*
import com.example.diceautobet.demo.AlternatingStrategyDemo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import com.example.diceautobet.services.AreaConfigurationService
import com.example.diceautobet.services.OverlayService
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import com.example.diceautobet.services.DualModeService
import com.example.diceautobet.services.AutoClickService
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.CoordinateUtils
import com.example.diceautobet.utils.SplitScreenUtils
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.validation.GameValidator
import com.example.diceautobet.logging.GameLogger
import com.example.diceautobet.error.ErrorHandler
import com.example.diceautobet.utils.FileLogger
import com.example.diceautobet.utils.UpdateManager
import com.example.diceautobet.ui.UpdateDialog
import org.opencv.android.OpenCVLoader
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.diceautobet.utils.ProxyManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        const val EXTRA_REQUEST_PROJECTION = "request_projection"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var permissionManager: MediaProjectionPermissionManager
    private lateinit var gameLogger: GameLogger
    private lateinit var updateManager: UpdateManager
    private var isServiceRunning = false

    // Двойной режим
    private var dualModeService: DualModeService? = null
    private var dualWindowAreaManager: DualWindowAreaManager? = null
    private var isDualModeServiceBound = false

    private var isRequestFlow = false
    private var projectionLaunched = false
    private var pendingAreaConfig = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) updatePermissionButtons() }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> 
        if (granted) {
            Log.d("MainActivity", "✅ Разрешение на хранилище получено")
            Toast.makeText(this, "✅ Разрешение получено", Toast.LENGTH_SHORT).show()
        } else {
            Log.w("MainActivity", "⚠️ Разрешение на хранилище отклонено пользователем")
            // Не показываем навязчивый Toast - пользователь сам решил отказать
        }
    }

    // ServiceConnection для DualModeService
    private val dualModeServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("MainActivity", "DualModeService подключен")
            val binder = service as DualModeService.LocalBinder
            dualModeService = binder.getService()
            isDualModeServiceBound = true
            
            // Устанавливаем слушатели (временно закомментированы, так как методы изменились)
            // dualModeService?.setOnGameStateChangedListener { gameState ->
            //     runOnUiThread { updateDualModeUI(gameState) }
            // }
            
            // dualModeService?.setOnWindowSwitchedListener { windowType ->
            //     runOnUiThread { onWindowSwitched(windowType) }
            // }
            
            // Инициализируем менеджер областей
            dualWindowAreaManager = DualWindowAreaManager(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "DualModeService отключен")
            dualModeService = null
            isDualModeServiceBound = false
            dualWindowAreaManager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate: инициализируем приложение")

        // Диагностика для Android 15
        if (Build.VERSION.SDK_INT >= 35) {
            Log.w("MainActivity", "⚠️ ВНИМАНИЕ: Обнаружен Android 15 (API ${Build.VERSION.SDK_INT})")
            Log.w("MainActivity", "⚠️ Требуется немедленный запуск foreground service после получения MediaProjection!")
        }

        // Инициализируем FileLogger для записи всех логов в файл
        FileLogger.initialize(this)
        FileLogger.i("MainActivity", "🚀 Приложение DiceAutoBet запущено")
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("MainActivity", "Ошибка инициализации OpenCV")
            FileLogger.e("MainActivity", "Ошибка инициализации OpenCV")
            Toast.makeText(this, "Ошибка инициализации OpenCV", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Log.d("MainActivity", "OpenCV инициализирован успешно")
        FileLogger.d("MainActivity", "OpenCV инициализирован успешно")
        
        // Инициализируем настройки прокси
        ProxyManager.initFromPreferences(this)
        Log.d("MainActivity", "Настройки прокси инициализированы")
        FileLogger.d("MainActivity", "Настройки прокси инициализированы")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        permissionManager = MediaProjectionPermissionManager.getInstance(this)
        gameLogger = GameLogger(this)
        updateManager = UpdateManager(this)

        setupUI()
        updatePermissionButtons()
        loadSettings()
        
        // Показываем текущую версию
        updateVersionDisplay()
        
        // � ЗАПРОС РАЗРЕШЕНИЯ НА ХРАНИЛИЩЕ (для обновлений)
        checkStoragePermission()
        
        // �🔄 ПРОВЕРКА ОБНОВЛЕНИЙ ПРИ ЗАПУСКЕ
        checkForUpdatesOnStartup()
        
        // � ПРОГРЕВ СОЕДИНЕНИЯ ДЛЯ УСКОРЕНИЯ ИГРЫ
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "🔥 Запускаем прогрев соединения в фоне...")
                
                // Показываем индикатор прогрева
                runOnUiThread { showWarmupStatus() }
                
                val result = ProxyManager.warmupConnection()
                when (result) {
                    is ProxyManager.WarmupResult.Success -> {
                        Log.d("MainActivity", "✅ Соединение прогрето за ${result.duration}мс через ${result.connectionType}")
                        Log.d("MainActivity", "✅ Успешные запросы: ${result.successfulRequests.joinToString(", ")}")
                        if (result.errors.isNotEmpty()) {
                            Log.w("MainActivity", "⚠️ Ошибки при прогреве: ${result.errors.joinToString(", ")}")
                        }
                        // Показываем успешный прогрев
                        runOnUiThread { 
                            showWarmupSuccess()
                            // Через 3 секунды возвращаемся к обычному статусу
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                updateProxyStatus()
                            }, 3000)
                        }
                    }
                    is ProxyManager.WarmupResult.Error -> {
                        Log.e("MainActivity", "❌ Ошибка прогрева соединения за ${result.duration}мс: ${result.error}")
                        // Показываем ошибку прогрева
                        runOnUiThread { 
                            showWarmupError()
                            // Через 3 секунды возвращаемся к обычному статусу
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                updateProxyStatus()
                            }, 3000)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Исключение при прогреве соединения: ${e.message}", e)
                runOnUiThread { 
                    showWarmupError()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateProxyStatus()
                    }, 3000)
                }
            }
        }
        
        // Инициализируем DualModeService
        initializeDualModeService()
        
        intent?.let { handleRequestIntent(it) }

        gameLogger.logSystemEvent("MainActivity создан")

        Log.d("MainActivity", "onCreate завершен")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent: intent=$intent")
        setIntent(intent)
        handleRequestIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: обновляем кнопки разрешений")
        gameLogger.logSystemEvent("MainActivity возобновлен")
        updatePermissionButtons()
    }

    private fun handleRequestIntent(intent: Intent) {
        Log.d("MainActivity", "Обрабатываем запрос: intent=$intent")
        if (!projectionLaunched && intent.getBooleanExtra(EXTRA_REQUEST_PROJECTION, false)) {
            Log.d("MainActivity", "Запускаем диалог запроса разрешения")
            intent.removeExtra(EXTRA_REQUEST_PROJECTION)
            isRequestFlow = true
            projectionLaunched = true
            startProjectionDialog()
        } else {
            Log.d("MainActivity", "Запрос не обработан: projectionLaunched=$projectionLaunched, hasExtra=${intent.getBooleanExtra(EXTRA_REQUEST_PROJECTION, false)}")
        }
    }

    private fun startProjectionDialog() {
        Log.d("MainActivity", "Запрашиваем разрешение на захват экрана через центральный менеджер")
        val captureIntent = permissionManager.createScreenCaptureIntent()
        Log.d("MainActivity", "Intent создан: $captureIntent")
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        Log.d("MainActivity", "Активность для запроса разрешения запущена")
    }

    /**
     * Проверка и запрос разрешения на доступ к хранилищу
     */
    private fun checkStoragePermission() {
        // Для Android 13+ (API 33+) используем MANAGE_EXTERNAL_STORAGE или обходимся без разрешений
        // Для Android 10-12 (API 29-32) используем WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - DownloadManager работает без специальных разрешений
            Log.d("MainActivity", "✅ Android 11+ - разрешения на хранилище не требуются для DownloadManager")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            
            if (!granted) {
                // Проверяем SharedPreferences - запрашивали ли мы уже разрешение
                val prefs = getSharedPreferences("app_permissions", MODE_PRIVATE)
                val hasAsked = prefs.getBoolean("storage_permission_asked", false)
                
                if (hasAsked) {
                    // Уже спрашивали - не беспокоим пользователя
                    Log.d("MainActivity", "⏭️ Разрешение на хранилище уже запрашивалось ранее")
                    return
                }
                
                // Первый запрос - сохраняем флаг и запрашиваем
                prefs.edit().putBoolean("storage_permission_asked", true).apply()
                Log.d("MainActivity", "📁 Запрашиваем разрешение на хранилище (первый раз)")
                storagePermissionLauncher.launch(permission)
            } else {
                Log.d("MainActivity", "✅ Разрешение на хранилище уже предоставлено")
            }
        }
    }
    
    /**
     * Проверка обновлений при запуске приложения
     */
    private fun checkForUpdatesOnStartup() {
        lifecycleScope.launch {
            try {
                // Проверяем только если прошло достаточно времени
                if (!updateManager.shouldCheckForUpdates()) {
                    Log.d("MainActivity", "⏭️ Пропускаем проверку обновлений (недавно проверяли)")
                    return@launch
                }

                Log.d("MainActivity", "🔍 Проверяем обновления...")
                FileLogger.i("MainActivity", "🔍 Проверка обновлений при запуске")

                val updateInfo = updateManager.checkForUpdates()

                if (updateInfo != null && updateManager.isUpdateAvailable(updateInfo)) {
                    if (!updateManager.isVersionSkipped(updateInfo)) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(updateInfo)
                        }
                    } else {
                        Log.d("MainActivity", "⏭️ Версия ${updateInfo.latestVersion} пропущена пользователем")
                    }
                } else {
                    Log.d("MainActivity", "✅ Установлена последняя версия")
                    FileLogger.i("MainActivity", "✅ Версия актуальна: ${updateManager.getCurrentVersionName()}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Ошибка проверки обновлений", e)
                FileLogger.e("MainActivity", "❌ Ошибка проверки обновлений: ${e.message}")
            }
        }
    }

    /**
     * Показать диалог обновления
     */
    private fun showUpdateDialog(updateInfo: com.example.diceautobet.utils.UpdateInfo) {
        UpdateDialog.show(
            context = this,
            updateInfo = updateInfo,
            currentVersion = updateManager.getCurrentVersionName(),
            onUpdate = {
                Log.d("MainActivity", "📥 Пользователь начал загрузку обновления")
                FileLogger.i("MainActivity", "📥 Загрузка обновления v${updateInfo.latestVersion}")
                updateManager.downloadAndInstall(updateInfo)
            },
            onSkip = {
                Log.d("MainActivity", "⏭️ Пользователь пропустил обновление")
                FileLogger.i("MainActivity", "⏭️ Пропущена версия ${updateInfo.latestVersion}")
                updateManager.skipVersion(updateInfo)
            }
        )
    }

    /**
     * Обновляет отображение текущей версии
     */
    private fun updateVersionDisplay() {
        try {
            val currentVersion = updateManager.getCurrentVersionName()
            binding.tvCurrentVersion?.text = "Текущая версия: $currentVersion"
            Log.d("MainActivity", "Отображение версии обновлено: $currentVersion")
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка обновления отображения версии", e)
        }
    }

    /**
     * Принудительная проверка обновлений (можно вызывать из меню)
     */
    private fun checkForUpdatesManually() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "🔍 Проверяем обновления...", Toast.LENGTH_SHORT).show()
                }

                val updateInfo = updateManager.checkForUpdates()

                withContext(Dispatchers.Main) {
                    if (updateInfo != null && updateManager.isUpdateAvailable(updateInfo)) {
                        showUpdateDialog(updateInfo)
                    } else {
                        val currentVersion = updateManager.getCurrentVersionName()
                        Toast.makeText(
                            this@MainActivity,
                            "✅ У вас последняя версия ($currentVersion)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Ошибка проверки обновлений", e)
                withContext(Dispatchers.Main) {
                    UpdateDialog.showError(this@MainActivity, e.message ?: "Неизвестная ошибка")
                }
            }
        }
    }

    private fun setupUI() = with(binding) {
        Log.d("MainActivity", "Настраиваем UI")

        btnOverlayPermission.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка разрешения наложения")
            if (Settings.canDrawOverlays(this@MainActivity)) {
                Toast.makeText(this@MainActivity, "Разрешено", Toast.LENGTH_SHORT).show()
                // Обновляем цвет кнопки на зелёный
                updatePermissionButtons()
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .apply { data = Uri.parse("package:$packageName") })
                Log.d("MainActivity", "Открыты настройки разрешения наложения")
            }
        }
        btnAccessibilityPermission.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка разрешения доступности")
            val enabledAccessibility = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().contains("$packageName/${AutoClickService::class.java.canonicalName}")
            if (enabledAccessibility) {
                Toast.makeText(this@MainActivity, "Разрешено", Toast.LENGTH_SHORT).show()
                // Обновляем цвет кнопки на зелёный
                updatePermissionButtons()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity,
                    "Найдите 'Dice Auto Bet' и включите сервис", Toast.LENGTH_LONG).show()
                Log.d("MainActivity", "Открыты настройки доступности")
            }
        }
        btnNotificationPermission.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка разрешения уведомлений")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    Toast.makeText(this@MainActivity, "Разрешено", Toast.LENGTH_SHORT).show()
                    // Обновляем цвет кнопки на зелёный
                    updatePermissionButtons()
                } else {
                    Log.d("MainActivity", "Запрашиваем разрешение на уведомления")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                Log.d("MainActivity", "Разрешение на уведомления не требуется для этой версии Android")
                Toast.makeText(this@MainActivity, "Не требуется", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnConfigureAreas.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка настройки областей")

            if (!Settings.canDrawOverlays(this@MainActivity)) {
                Log.d("MainActivity", "Нет разрешения на наложение, показываем предупреждение")
                Toast.makeText(this@MainActivity,
                    "Сначала разрешите отображение поверх других приложений", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                // Запускаем настройку областей
                Log.d("MainActivity", "Запускаем настройку областей")
                AreaConfigurationService.start(this@MainActivity)

                // Даем сервису время на инициализацию перед сворачиванием
                binding.root.postDelayed({
                    moveTaskToBack(true)
                }, 500)
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при запуске настройки областей", e)
                Toast.makeText(this@MainActivity,
                    "Ошибка при запуске настройки: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // === ОБРАБОТЧИКИ ДВОЙНОГО РЕЖИМА ===
        
        // Кнопка запуска двойного режима
        binding.btnStartDualMode?.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка запуска двойного режима")
            startDualModeActivity()
        }
        
        // Переключатель двойного режима
        binding.switchDualMode?.setOnCheckedChangeListener { _, isChecked ->
            Log.d("MainActivity", "Переключатель двойного режима: $isChecked")
            
            // Сохраняем состояние
            val currentSettings = prefsManager.getDualModeSettings()
            val newSettings = currentSettings.copy(enabled = isChecked)
            prefsManager.saveDualModeSettings(newSettings)
            
            gameLogger.logUserAction(
                "Двойной режим ${if (isChecked) "включен" else "выключен"}", 
                mapOf("enabled" to isChecked)
            )
            
            if (isChecked) {
                Toast.makeText(this@MainActivity, "Двойной режим включен. Настройте области для каждого окна.", Toast.LENGTH_LONG).show()
            }
        }
        
        // Кнопка настроек двойного режима
        binding.btnConfigureDualMode?.setOnClickListener {
            Log.d("MainActivity", "Открываем настройки двойного режима")
            openDualModeConfiguration()
        }
        
        // === ОБРАБОТЧИКИ ОДИНОЧНОГО РЕЖИМА ===
        
        // Переключатель одиночного режима
        binding.switchSingleMode?.setOnCheckedChangeListener { _, isChecked ->
            Log.d("MainActivity", "Переключатель одиночного режима: $isChecked")
            
            // Сохраняем состояние в SharedPreferences
            val prefs = getSharedPreferences("single_mode_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("single_mode_enabled", isChecked).apply()
            
            // ИСПРАВЛЕНИЕ: Также сохраняем режим игры в PreferencesManager для OverlayService
            val preferencesManager = PreferencesManager(this@MainActivity)
            val gameMode = if (isChecked) "single" else "dual"
            preferencesManager.saveGameMode(gameMode)
            Log.d("MainActivity", "Сохранен режим игры в PreferencesManager: $gameMode")
            
            gameLogger.logUserAction(
                "Одиночный режим ${if (isChecked) "включен" else "выключен"}", 
                mapOf("enabled" to isChecked)
            )
            
            // Обновляем доступность кнопок
            updateSingleModeButtonsState()
            
            if (isChecked) {
                Toast.makeText(this@MainActivity, "Одиночный режим включен. Настройте области и параметры игры.", Toast.LENGTH_LONG).show()
            }
        }
        
        // Кнопка настроек одиночного режима
        binding.btnConfigureSingleMode?.setOnClickListener {
            Log.d("MainActivity", "Открываем настройки одиночного режима")
            openSingleModeSettings()
        }
        
        // Кнопка настройки областей одиночного режима
        binding.btnConfigureSingleAreas?.setOnClickListener {
            Log.d("MainActivity", "Открываем настройку областей одиночного режима")
            openSingleModeAreaConfiguration()
        }
        
        // Кнопка запуска одиночного режима
        binding.btnStartSingleMode?.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка запуска одиночного режима")
            startSingleMode()
        }
        
        // === ОСТАЛЬНЫЕ ОБРАБОТЧИКИ ===
        
        // Кнопка настроек ИИ
        binding.btnConfigureAI?.setOnClickListener {
            Log.d("MainActivity", "Открываем настройки ИИ")
            openAIConfiguration()
        }
        
        // Кнопка тестирования прокси
        binding.btnTestProxy?.setOnClickListener {
            Log.d("MainActivity", "Запуск тестирования прокси")
            testProxyConnection()
        }
        
        // === ОБРАБОТЧИКИ ПРОКСИ ===
        
        // Переключатель прокси
        binding.switchProxyEnabled?.setOnCheckedChangeListener { _, isChecked ->
            Log.d("MainActivity", "Переключатель прокси: $isChecked")
            ProxyManager.setProxyEnabled(isChecked, this@MainActivity)
            updateProxyStatus()
            toggleProxySettingsVisibility(isChecked)
            
            gameLogger.logUserAction(
                "Прокси ${if (isChecked) "включен" else "отключен"}",
                mapOf("proxy_enabled" to isChecked)
            )
        }
        
        // === НОВЫЕ ОБРАБОТЧИКИ НАСТРОЕК ПРОКСИ ===
        
        // Переключатель типа прокси
        binding.toggleProxyType?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.btnSocks5?.id -> {
                        Log.d("MainActivity", "Выбран тип прокси: SOCKS5")
                    }
                    binding.btnHttp?.id -> {
                        Log.d("MainActivity", "Выбран тип прокси: HTTP")
                    }
                }
            }
        }
        
        // Кнопка сохранения настроек прокси
        binding.btnSaveProxy?.setOnClickListener {
            Log.d("MainActivity", "Сохранение настроек прокси")
            saveProxySettings()
        }
        
        // Кнопка сброса настроек прокси
        binding.btnResetProxy?.setOnClickListener {
            Log.d("MainActivity", "Сброс настроек прокси")
            resetProxySettings()
        }
        
        // Кнопка проверки обновлений
        binding.btnCheckUpdates?.setOnClickListener {
            Log.d("MainActivity", "Нажата кнопка проверки обновлений")
            checkForUpdatesManually()
        }
    }

    private fun loadSettings() {
        val savedChoice = prefsManager.getBetChoice()

        Log.d("MainActivity", "Загружаем настройки: betChoice=$savedChoice")

        Log.d("MainActivity", "Загружаем сохраненный выбор ставки: $savedChoice")

        when (savedChoice) {
            BetChoice.RED    -> {
                Log.d("MainActivity", "Устанавливаем красный chip")
                binding.chipRed.isChecked = true
                binding.chipOrange.isChecked = false
            }
            BetChoice.ORANGE-> {
                Log.d("MainActivity", "Устанавливаем оранжевый chip")
                binding.chipOrange.isChecked = true
                binding.chipRed.isChecked = false
            }
        }
        updateBetChoiceText(savedChoice)

        // Загружаем настройки двойного режима
        val dualModeSettings = prefsManager.getDualModeSettings()
        binding.switchDualMode?.isChecked = dualModeSettings.enabled
        Log.d("MainActivity", "Загружены настройки двойного режима: enabled=${dualModeSettings.enabled}")
        
        // Загружаем настройки одиночного режима
        val singleModePrefs = getSharedPreferences("single_mode_prefs", MODE_PRIVATE)
        val singleModeEnabled = singleModePrefs.getBoolean("single_mode_enabled", false)
        binding.switchSingleMode?.isChecked = singleModeEnabled
        Log.d("MainActivity", "Загружены настройки одиночного режима: enabled=$singleModeEnabled")
        
        // ИСПРАВЛЕНИЕ: Синхронизируем режим игры в PreferencesManager
        val gameMode = if (singleModeEnabled) "single" else "dual"
        prefsManager.saveGameMode(gameMode)
        Log.d("MainActivity", "Синхронизирован режим игры в PreferencesManager: $gameMode")
        
        // Загружаем настройки прокси
        binding.switchProxyEnabled?.isChecked = ProxyManager.isProxyEnabled()
        loadProxySettingsToUI()
        updateProxyStatus()
        toggleProxySettingsVisibility(ProxyManager.isProxyEnabled())
        Log.d("MainActivity", "Загружены настройки прокси: enabled=${ProxyManager.isProxyEnabled()}")

        Log.d("MainActivity", "Настройки загружены: chipRed=${binding.chipRed.isChecked}, chipOrange=${binding.chipOrange.isChecked}")
    }

    private fun updateBetChoiceText(choice: BetChoice) {
        val txt = if (choice == BetChoice.RED) "Красный" else "Оранжевый"
        Log.d("MainActivity", "Обновляем текст выбора ставки: $choice -> $txt")
        binding.tvBetChoice.text = getString(R.string.bet_choice, txt)
        Log.d("MainActivity", "Текст обновлен: ${binding.tvBetChoice.text}")
    }

    private fun notifyServiceSettingsChanged() {
        Log.d("MainActivity", "Уведомляем сервис об изменении настроек")
        Log.d("MainActivity", "Текущие настройки: betChoice=${prefsManager.getBetChoice()}")

        Intent(this, OverlayService::class.java).also { intent ->
            intent.action = "SETTINGS_CHANGED"
            // Для уведомлений используем обычный startService
            startService(intent)
        }
        Log.d("MainActivity", "Intent отправлен сервису")
    }
    
    /**
     * Обновляет статус прокси в интерфейсе
     */
    private fun updateProxyStatus() {
        val isEnabled = ProxyManager.isProxyEnabled()
        val connectionInfo = ProxyManager.getCurrentConnectionInfo()
        
        binding.tvProxyStatus?.apply {
            if (isEnabled) {
                text = getString(R.string.proxy_status_enabled, connectionInfo)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            } else {
                text = getString(R.string.proxy_status_disabled)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
            }
        }
        
        // Обновляем текст кнопки тестирования
        binding.btnTestProxy?.text = if (isEnabled) {
            "🌐 Протестировать прокси"
        } else {
            "🌐 Протестировать соединение"
        }
        
        Log.d("MainActivity", "Статус прокси обновлен: enabled=$isEnabled, info=$connectionInfo")
    }

    /**
     * Показывает статус прогрева соединения
     */
    private fun showWarmupStatus() {
        binding.tvProxyStatus?.apply {
            text = getString(R.string.proxy_status_warming_up)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_bright))
        }
        binding.btnTestProxy?.isEnabled = false
    }

    /**
     * Показывает что соединение успешно прогрето
     */
    private fun showWarmupSuccess() {
        binding.tvProxyStatus?.apply {
            text = getString(R.string.proxy_status_warmed_up)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
        }
        binding.btnTestProxy?.isEnabled = true
    }

    /**
     * Показывает ошибку прогрева соединения
     */
    private fun showWarmupError() {
        binding.tvProxyStatus?.apply {
            text = getString(R.string.proxy_status_warmup_error)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
        }
        binding.btnTestProxy?.isEnabled = true
    }

    // === НОВЫЕ МЕТОДЫ ДЛЯ НАСТРОЕК ПРОКСИ ===
    
    /**
     * Переключает видимость настроек прокси
     */
    private fun toggleProxySettingsVisibility(enabled: Boolean) {
        binding.layoutProxySettings?.visibility = if (enabled) View.VISIBLE else View.GONE
    }
    
    /**
     * Загружает настройки прокси в UI
     */
    private fun loadProxySettingsToUI() {
        val config = ProxyManager.getCurrentProxyConfig()
        
        // Заполняем поля ввода
        binding.etProxyHost?.setText(config.host)
        binding.etProxyPort?.setText(config.port.toString())
        binding.etProxyUsername?.setText(config.username)
        binding.etProxyPassword?.setText(config.password)
        
        // Устанавливаем тип прокси
        when (config.type) {
            ProxyManager.ProxyType.SOCKS5 -> {
                binding.toggleProxyType?.check(binding.btnSocks5?.id ?: -1)
            }
            ProxyManager.ProxyType.HTTP -> {
                binding.toggleProxyType?.check(binding.btnHttp?.id ?: -1)
            }
        }
        
        Log.d("MainActivity", "Настройки прокси загружены в UI: ${config.type.name} ${config.username}@${config.host}:${config.port}")
    }
    
    /**
     * Сохраняет настройки прокси из UI
     */
    private fun saveProxySettings() {
        val host = binding.etProxyHost?.text?.toString()?.trim() ?: ""
        val port = binding.etProxyPort?.text?.toString()?.trim() ?: ""
        val username = binding.etProxyUsername?.text?.toString()?.trim() ?: ""
        val password = binding.etProxyPassword?.text?.toString()?.trim() ?: ""
        
        // Определяем выбранный тип прокси
        val selectedType = when (binding.toggleProxyType?.checkedButtonId) {
            binding.btnSocks5?.id -> ProxyManager.ProxyType.SOCKS5
            binding.btnHttp?.id -> ProxyManager.ProxyType.HTTP
            else -> ProxyManager.ProxyType.SOCKS5 // По умолчанию SOCKS5
        }
        
        // Валидируем данные
        val validationError = ProxyManager.validateProxyConfig(host, port, username, password)
        if (validationError != null) {
            showProxyError(validationError)
            return
        }
        
        // Создаем конфигурацию
        val config = ProxyManager.ProxyConfig(
            host = host,
            port = port.toInt(),
            username = username,
            password = password,
            type = selectedType
        )
        
        // Сохраняем
        if (ProxyManager.saveProxyConfig(this, config)) {
            updateProxyStatus()
            Toast.makeText(this, getString(R.string.proxy_success_saved), Toast.LENGTH_SHORT).show()
            
            gameLogger.logUserAction(
                "Настройки прокси сохранены",
                mapOf(
                    "proxy_type" to selectedType.name,
                    "proxy_host" to host,
                    "proxy_port" to port
                )
            )
        } else {
            showProxyError("Ошибка сохранения настроек")
        }
    }
    
    /**
     * Сбрасывает настройки прокси к значениям по умолчанию
     */
    private fun resetProxySettings() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Сброс настроек прокси")
            .setMessage("Вы уверены, что хотите сбросить все настройки прокси к значениям по умолчанию?")
            .setPositiveButton("Сбросить") { _, _ ->
                ProxyManager.resetProxyConfigToDefaults(this)
                loadProxySettingsToUI()
                updateProxyStatus()
                Toast.makeText(this, getString(R.string.proxy_success_reset), Toast.LENGTH_SHORT).show()
                
                gameLogger.logUserAction("Настройки прокси сброшены к умолчанию", emptyMap())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    /**
     * Показывает ошибку настроек прокси
     */
    private fun showProxyError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("❌ Ошибка настроек прокси")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updatePermissionButtons() {
        Log.d("MainActivity", "Обновляем кнопки разрешений")

        val hasOverlay = Settings.canDrawOverlays(this)
        val enabledAccessibility = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().contains("$packageName/${AutoClickService::class.java.canonicalName}")
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        val hasScreenCapture = prefsManager.hasValidMediaProjection()
        val hasAreas = prefsManager.areAllAreasConfigured()

        Log.d("MainActivity", "Состояние разрешений: hasOverlay=$hasOverlay, enabledAccessibility=$enabledAccessibility, hasNotification=$hasNotification, hasScreenCapture=$hasScreenCapture, hasAreas=$hasAreas")

        // Применяем цветовую индикацию к кнопкам разрешений с закругленными углами
        // Зелёный цвет - если разрешение выдано, красный - если нужно разрешить
        fun applyPermissionState(button: androidx.appcompat.widget.AppCompatButton, granted: Boolean) {
            if (granted) {
                button.text = "Разрешено"
                button.isEnabled = true
                button.alpha = 1f
                // 🟢 ЗЕЛЁНЫЙ фон с закругленными углами для выданных разрешений
                button.setBackgroundResource(R.drawable.button_permission_granted)
            } else {
                button.text = getString(R.string.grant_permission)
                button.isEnabled = true
                button.alpha = 1f
                // 🔴 КРАСНЫЙ фон с закругленными углами для невыданных разрешений
                button.setBackgroundResource(R.drawable.button_permission_denied)
            }
        }

        applyPermissionState(binding.btnOverlayPermission, hasOverlay)
        applyPermissionState(binding.btnAccessibilityPermission, enabledAccessibility)
        applyPermissionState(binding.btnNotificationPermission, hasNotification)

        // Показываем/скрываем секцию уведомлений в зависимости от версии Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.notificationPermissionLayout.visibility = View.VISIBLE
            Log.d("MainActivity", "Показываем секцию уведомлений для Android 13+")
        } else {
            binding.notificationPermissionLayout.visibility = View.GONE
            Log.d("MainActivity", "Скрываем секцию уведомлений для Android < 13")
        }

        val serviceEnabled = hasOverlay && enabledAccessibility && hasNotification && hasAreas
    binding.btnToggleService.isEnabled = serviceEnabled
        
        // Включаем кнопку настроек двойного режима если есть основные разрешения
        binding.btnConfigureDualMode?.isEnabled = hasOverlay && enabledAccessibility

        // Обновляем состояние кнопок одиночного режима
        updateSingleModeButtonsState()

        Log.d("MainActivity", "Кнопки обновлены: btnToggleService=${binding.btnToggleService.isEnabled}, btnConfigureDualMode=${binding.btnConfigureDualMode?.isEnabled}, serviceEnabled=$serviceEnabled")
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun toggleService() {
        Log.d("MainActivity", "Переключаем сервис: isServiceRunning=$isServiceRunning")

        try {
            if (!prefsManager.areAllAreasConfigured()) {
                Log.d("MainActivity", "Области не настроены, сервис не запускается")
                gameLogger.logWarning("Попытка запуска без настроенных областей")
                Toast.makeText(this, "Сначала настройте области", Toast.LENGTH_SHORT).show()
                return
            }

            Intent(this, OverlayService::class.java).also { svc ->
                if (isServiceRunning) {
                    Log.d("MainActivity", "Останавливаем сервис")
                    gameLogger.logUserAction("Остановка сервиса")
                    stopService(svc)
                    binding.btnToggleService.text = getString(R.string.start_service)
                } else {
                    Log.d("MainActivity", "Запускаем сервис")
                    gameLogger.logUserAction("Запуск сервиса")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(svc) else startService(svc)
                    binding.btnToggleService.text = getString(R.string.stop_service)
                }
                isServiceRunning = !isServiceRunning
                Log.d("MainActivity", "Состояние сервиса изменено: isServiceRunning=$isServiceRunning")
            }
        } catch (e: Exception) {
            val error = ErrorHandler.handleError(e)
            gameLogger.logError(e, "Переключение сервиса")
            Toast.makeText(this, "Ошибка: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchAreaConfigDialog() {
        if (!prefsManager.areAllAreasConfigured()) {
            Log.d("MainActivity", "Области не настроены, показываем диалог настройки")
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Настройка областей")
                .setMessage("Перейдите в приложение и выберите зоны автоклика.")
                .setPositiveButton("Начать") { _, _ ->
                    Log.d("MainActivity", "Запускаем настройку областей")
                    AreaConfigurationService.start(this@MainActivity)
                    moveTaskToBack(true)
                }
                .show()
        } else {
            Log.d("MainActivity", "Области уже настроены, показываем диалог перенастройки")
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Перенастройка областей")
                .setMessage("Все области уже настроены. Перенастроить?")
                .setPositiveButton("Да") { _, _ ->
                    Log.d("MainActivity", "Очищаем области и запускаем перенастройку")
                    prefsManager.clearAllAreas()
                    AreaConfigurationService.start(this@MainActivity)
                    moveTaskToBack(true)
                }
                .setNegativeButton("Нет", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.d("MainActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data")
            projectionLaunched = false
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d("MainActivity", "✅ Разрешение получено, сохраняем через центральный менеджер")
                
                // Сохраняем через новый менеджер
                permissionManager.savePermission(resultCode, data)
                
                // Для совместимости также сохраняем в старом формате
                prefsManager.saveMediaProjectionPermission(resultCode, data)
                com.example.diceautobet.utils.MediaProjectionTokenStore.set(data)
                
                if (pendingAreaConfig) {
                    Log.d("MainActivity", "Запускаем AreaConfigurationService после получения разрешения MediaProjection")
                    AreaConfigurationService.start(this, resultCode, data)
                    pendingAreaConfig = false
                    moveTaskToBack(true)
                } else {
                    Intent(this, OverlayService::class.java).also { svc ->
                        svc.action = OverlayService.ACTION_START_PROJECTION
                        svc.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        svc.putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(svc)
                        else
                            startService(svc)
                    }
                    Toast.makeText(this, "✅ Разрешение сохранено. Больше не будет запрашиваться!", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.d("MainActivity", "❌ Разрешение не получено: resultCode=$resultCode")
                permissionManager.clearPermission()
                prefsManager.clearMediaProjectionPermission()
                com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
                Toast.makeText(this, "Разрешение на захват экрана не получено", Toast.LENGTH_SHORT).show()
            }
            if (isRequestFlow) {
                Log.d("MainActivity", "Завершаем поток запроса разрешения")
                isRequestFlow = false
                OverlayService.isRequestingProjection = false
                updatePermissionButtons()
            }
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "MainActivity уничтожается")
        try {
            // Отключаемся от DualModeService
            if (isDualModeServiceBound) {
                unbindService(dualModeServiceConnection)
                isDualModeServiceBound = false
            }
            
            gameLogger.logSystemEvent("MainActivity уничтожен")
            gameLogger.destroy()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при уничтожении", e)
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        gameLogger.logSystemEvent("MainActivity на паузе")
    }

    // === МЕТОДЫ ДВОЙНОГО РЕЖИМА ===

    /**
     * Открывает диалог настроек двойного режима
     */
    private fun openDualModeConfiguration() {
        Log.d("MainActivity", "Открываем настройки двойного режима")
        
        val currentSettings = prefsManager.getDualModeSettings()
        
        // Создаем диалог с настройками
        val dialogView = layoutInflater.inflate(R.layout.dialog_dual_mode_settings, null)
    // Кнопка для перехода к настройке областей окон
    val btnOpenAreaConfig = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton?>(R.id.btnOpenAreaConfig)
        
        // Получаем элементы диалога
        val spinnerStrategy = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerStrategy)
        val spinnerSplitType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerSplitType)
        val rgBaseBet = dialogView.findViewById<RadioGroup>(R.id.rgBaseBet)
        val rbBet10 = dialogView.findViewById<RadioButton>(R.id.rbBet10)
        val rbBet20 = dialogView.findViewById<RadioButton>(R.id.rbBet20)
        val rbBet50 = dialogView.findViewById<RadioButton>(R.id.rbBet50)
        val rbBet100 = dialogView.findViewById<RadioButton>(R.id.rbBet100)
        val rbBet500 = dialogView.findViewById<RadioButton>(R.id.rbBet500)
        val rbBet2500 = dialogView.findViewById<RadioButton>(R.id.rbBet2500)
        val etColorSwitchLosses = dialogView.findViewById<TextInputEditText>(R.id.etColorSwitchLosses)
        val etTimingDelay = dialogView.findViewById<TextInputEditText>(R.id.etTimingDelay)
        val cbEnableLogging = dialogView.findViewById<MaterialCheckBox>(R.id.cbEnableLogging)
        val cbAutoCalibration = dialogView.findViewById<MaterialCheckBox>(R.id.cbAutoCalibration)
        
        // Настраиваем выпадающий список стратегий
        val strategies = arrayOf(
            "При выигрыше → другое окно",
            "При проигрыше → другое окно", 
            "Чередование каждую игру"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, strategies)
        spinnerStrategy.setAdapter(adapter)
        
        // Настраиваем выпадающий список типов разделения
        val splitTypes = arrayOf(
            "Горизонтальное (левое/правое)",
            "Вертикальное (верхнее/нижнее)"
        )
        val splitAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, splitTypes)
        spinnerSplitType.setAdapter(splitAdapter)
        
        // Заполняем текущими значениями
        spinnerStrategy.setText(when(currentSettings.strategy) {
            DualStrategy.WIN_SWITCH -> "При выигрыше → другое окно"
            DualStrategy.LOSS_DOUBLE -> "При проигрыше → другое окно"
            DualStrategy.COLOR_ALTERNATING -> "Чередование каждую игру"
        }, false)
        
        spinnerSplitType.setText(when(currentSettings.splitScreenType) {
            SplitScreenType.HORIZONTAL -> "Горизонтальное (левое/правое)"
            SplitScreenType.VERTICAL -> "Вертикальное (верхнее/нижнее)"
        }, false)
        
        // Устанавливаем базовую ставку в RadioGroup
        when(currentSettings.baseBet) {
            10 -> rbBet10.isChecked = true
            20 -> rbBet20.isChecked = true  
            50 -> rbBet50.isChecked = true
            100 -> rbBet100.isChecked = true
            500 -> rbBet500.isChecked = true
            2500 -> rbBet2500.isChecked = true
            else -> rbBet20.isChecked = true // По умолчанию 20
        }
        
        etColorSwitchLosses.setText(currentSettings.maxConsecutiveLosses.toString())
        etTimingDelay.setText(currentSettings.delayBetweenActions.toString())
        cbEnableLogging.isChecked = currentSettings.enableTimingOptimization
        cbAutoCalibration.isChecked = currentSettings.smartSynchronization
        
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Настройки двойного режима")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { dialog, _ ->
                saveDualModeSettingsFromDialog(dialogView)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Помощь") { _, _ ->
                showDualModeHelpDialog()
            }
            .create().also { dialog ->
                // Обработчик кнопки открытия конфигурации областей
                btnOpenAreaConfig?.setOnClickListener {
                    // Сохраняем текущие настройки перед переходом (опционально)
                    saveDualModeSettingsFromDialog(dialogView)
                    dialog.dismiss()
                    // Открываем экран конфигурации областей для двойного режима
                    val intent = Intent(this, com.example.diceautobet.ui.DualModeAreaConfigActivity::class.java)
                    startActivity(intent)
                }
                dialog.show()
            }
    }

    /**
     * Сохраняет настройки двойного режима из диалога
     */
    private fun saveDualModeSettingsFromDialog(dialogView: View) {
        Log.d("MainActivity", "Сохраняем настройки двойного режима")
        
        try {
            // Получаем элементы диалога
            val spinnerStrategy = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerStrategy)
            val spinnerSplitType = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerSplitType)
            val rgBaseBet = dialogView.findViewById<RadioGroup>(R.id.rgBaseBet)
            val etColorSwitchLosses = dialogView.findViewById<TextInputEditText>(R.id.etColorSwitchLosses)
            val etTimingDelay = dialogView.findViewById<TextInputEditText>(R.id.etTimingDelay)
            val cbEnableLogging = dialogView.findViewById<MaterialCheckBox>(R.id.cbEnableLogging)
            val cbAutoCalibration = dialogView.findViewById<MaterialCheckBox>(R.id.cbAutoCalibration)
            
            // Определяем стратегию
            val strategy = when(spinnerStrategy.text.toString()) {
                "При выигрыше → другое окно" -> DualStrategy.WIN_SWITCH
                "При проигрыше → другое окно" -> DualStrategy.LOSS_DOUBLE
                "Чередование каждую игру" -> DualStrategy.COLOR_ALTERNATING
                else -> DualStrategy.WIN_SWITCH
            }
            
            // Определяем тип разделения экрана
            val splitScreenType = when(spinnerSplitType.text.toString()) {
                "Горизонтальное (левое/правое)" -> SplitScreenType.HORIZONTAL
                "Вертикальное (верхнее/нижнее)" -> SplitScreenType.VERTICAL
                else -> SplitScreenType.HORIZONTAL
            }
            
            // Получаем выбранную базовую ставку из RadioGroup
            val baseBet = when(rgBaseBet.checkedRadioButtonId) {
                R.id.rbBet10 -> 10
                R.id.rbBet20 -> 20
                R.id.rbBet50 -> 50
                R.id.rbBet100 -> 100
                R.id.rbBet500 -> 500
                R.id.rbBet2500 -> 2500
                else -> 20 // По умолчанию
            }
            
            // Создаем новые настройки с выбранной базовой ставкой
            val newSettings = DualModeSettings(
                enabled = binding.switchDualMode?.isChecked ?: false,
                strategy = strategy,
                splitScreenType = splitScreenType,
                baseBet = baseBet, // Базовая ставка из RadioGroup
                maxBet = 30000, // Увеличено до 30.000
                maxConsecutiveLosses = etColorSwitchLosses.text.toString().toIntOrNull() ?: 2,
                delayBetweenActions = etTimingDelay.text.toString().toLongOrNull() ?: 1000,
                enableTimingOptimization = cbEnableLogging.isChecked,
                smartSynchronization = cbAutoCalibration.isChecked
            )
            
            // Сохраняем настройки
            prefsManager.saveDualModeSettings(newSettings)
            
            // Проверяем готовность двойного режима для выбранного типа разделения
            val (isReady, readinessMessage) = prefsManager.isDualModeReadyForSplitType()
            
            if (isReady) {
                Toast.makeText(this, "✅ Настройки двойного режима сохранены и готовы к использованию", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "⚠️ Настройки сохранены, но требуется настройка областей", Toast.LENGTH_LONG).show()
                Log.w("MainActivity", readinessMessage)
            }
            
            Log.d("MainActivity", "Настройки сохранены: $newSettings")
            Log.d("MainActivity", "Готовность: $readinessMessage")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка сохранения настроек", e)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Показывает справку по двойному режиму
     */
    private fun showDualModeHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("❓ Справка по двойному режиму")
            .setMessage("""
                🎯 Двойной режим позволяет работать с двумя окнами приложения одновременно.
                
                📱 Стратегии:
                • При выигрыше → другое окно: Переход в другое окно при выигрыше
                • При проигрыше → другое окно: Удвоение ставки в другом окне при проигрыше  
                • Чередование каждую игру: Смена цвета после проигрышей
                
                ⚙️ Настройки:
                • Базовая ставка: Начальная сумма ставки
                • Максимальная ставка: Лимит ставки
                • Смена цвета после: Количество проигрышей до смены цвета
                • Задержка: Время между действиями (мс)
                
                ⚠️ Требования:
                • Настроенные области для обоих окон
                • Планшет с функцией разделения экрана
                • Два клонированных приложения
            """.trimIndent())
            .setPositiveButton("Понятно") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // === МЕТОДЫ РАБОТЫ С DUALMODESERVICE ===

    /**
     * Инициализирует DualModeService
     */
    private fun initializeDualModeService() {
        Log.d("MainActivity", "Инициализация DualModeService")
        
        val intent = Intent(this, DualModeService::class.java)
    // Не запускаем foreground cервис заранее.
    // Просто подключаемся; сервис создастся из bind (BIND_AUTO_CREATE),
    // а перевод в foreground произойдет только при фактическом старте режима.
    bindService(intent, dualModeServiceConnection, Context.BIND_AUTO_CREATE)
        
        Log.d("MainActivity", "DualModeService инициализирован")
    }

    /**
     * Обновляет UI двойного режима
     */
    private fun updateDualModeUI(gameState: SimpleDualModeState) {
        Log.d("MainActivity", "Обновление UI двойного режима: $gameState")
        
        // Обновляем состояние переключателя
        binding.switchDualMode?.isChecked = gameState.isRunning
        
        // Можно добавить индикаторы состояния, если они есть в layout
        // binding.tvDualModeStatus?.text = if (gameState.isRunning) "Активен" else "Остановлен"
        // binding.tvActiveWindow?.text = "Активное: ${gameState.currentWindow}"
        // binding.tvCurrentColor?.text = "Цвет: ${gameState.currentColor}"
        
        Toast.makeText(this, "Двойной режим: ${if (gameState.isRunning) "активен" else "остановлен"}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Обработчик переключения окна
     */
    private fun onWindowSwitched(windowType: WindowType) {
        Log.d("MainActivity", "Переключение на окно: $windowType")
        
        // Обновляем менеджер областей
        dualWindowAreaManager?.setActiveWindow(windowType)
        
        Toast.makeText(this, "Активное окно: ${windowType.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Запускает двойной режим
     */
    private fun startDualMode() {
        Log.d("MainActivity", "Запуск двойного режима")
        
        if (!isDualModeServiceBound) {
            Log.w("MainActivity", "DualModeService не подключен")
            Toast.makeText(this, "Сервис двойного режима не готов", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Проверяем готовность
        val configStatus = dualWindowAreaManager?.getConfigurationStatus()
        if (configStatus?.readyForDualMode != true) {
            Log.w("MainActivity", "Двойной режим не готов: $configStatus")
            showDualModeSetupDialog()
            return
        }
        
        // Запускаем через сервис
        dualModeService?.startDualMode()
        
        gameLogger.logUserAction("Запуск двойного режима")
    }

    /**
     * Останавливает двойной режим
     */
    private fun stopDualMode() {
        Log.d("MainActivity", "Остановка двойного режима")
        
        dualModeService?.stopDualMode()
        
        gameLogger.logUserAction("Остановка двойного режима")
    }

    /**
     * Показывает диалог настройки двойного режима
     */
    private fun showDualModeSetupDialog() {
        val configStatus = dualWindowAreaManager?.getConfigurationStatus()
        
        val message = StringBuilder()
        message.append("Для работы двойного режима необходимо:\n\n")
        
        if (!configStatus?.splitScreenSupported!!) {
            message.append("❌ Разделенный экран не поддерживается\n")
        } else {
            message.append("✅ Разделенный экран поддерживается\n")
        }
        
        if (!configStatus.leftWindowConfigured) {
            message.append("❌ Настройте области для левого окна\n")
        } else {
            message.append("✅ Левое окно настроено (${configStatus.leftAreasCount} областей)\n")
        }
        
        if (!configStatus.rightWindowConfigured) {
            message.append("❌ Настройте области для правого окна\n")
        } else {
            message.append("✅ Правое окно настроено (${configStatus.rightAreasCount} областей)\n")
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Настройка двойного режима")
            .setMessage(message.toString())
            .setPositiveButton("Настроить области") { _, _ ->
                // Здесь можно открыть настройку областей для конкретного окна
                openAreaConfigurationForDualMode()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Открывает настройку областей для двойного режима
     */
    private fun openAreaConfigurationForDualMode() {
        Log.d("MainActivity", "Открытие настройки областей для двойного режима")
        
        // Открываем специализированную активность для настройки двойного режима
        val intent = Intent(this, com.example.diceautobet.ui.DualModeAreaConfigActivity::class.java)
        startActivity(intent)
    }

    /**
     * Показывает диалог с информацией о двойном режиме
     */
    private fun showDualModeInfoDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Двойной режим")
            .setMessage("""
                🎯 Режим для планшетов с разделенным экраном
                
                📱 Требования:
                • Планшет с функцией разделения экрана
                • Клонированное приложение (через стороннюю программу)
                • Два окна приложения открыты одновременно
                
                🎮 Логика работы:
                • При выигрыше → переход к другому окну с минимальной ставкой
                • При проигрыше → удвоение ставки в другом окне  
                • После 2 проигрышей подряд → смена цвета кубика
                
                ⚠️ Внимание:
                Это экспериментальная функция. Рекомендуется сначала протестировать на небольших ставках.
            """.trimIndent())
            .setPositiveButton("Понятно") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Запускает активность управления двойным режимом
     */
    private fun startDualModeActivity() {
        Log.d("MainActivity", "Запуск DualModeControlActivity")
        
        // Проверки НЕ нужны - просто открываем активность управления
        // Все проверки и инициализация будут выполнены при нажатии СТАРТ в overlay
        
        // Запускаем активность управления двойным режимом
        val intent = Intent(this, com.example.diceautobet.ui.DualModeControlActivity::class.java)
        startActivity(intent)
    }
    
    /**
     * Открывает диалог настроек ИИ
     */
    private fun openAIConfiguration() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_settings, null)
        
        // Элементы диалога
        val spinnerRecognitionMode = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerRecognitionMode)
        val spinnerOpenRouterModel = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerOpenRouterModel)
        val etOpenRouterApiKey = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOpenRouterApiKey)
        val tvApiKeyStatus = dialogView.findViewById<TextView>(R.id.tvApiKeyStatus)
        val tvDescription = dialogView.findViewById<TextView>(R.id.tvDescription)
        val tvStatistics = dialogView.findViewById<TextView>(R.id.tvStatistics)
        val btnTestOpenRouter = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestOpenRouter)
        val btnSaveAISettings = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveAISettings)
        
        // Загружаем текущие настройки
        val currentMode = prefsManager.getRecognitionMode()
        val currentOpenRouterKey = prefsManager.getOpenRouterApiKey()
        val currentModel = prefsManager.getOpenRouterModel()
        
        // Настройка выпадающего списка режимов
        val modes = arrayOf("OpenCV (встроенный)", "OpenRouter", "Гибридный (OpenCV + OpenRouter)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modes)
        spinnerRecognitionMode.setAdapter(adapter)
        
        // Настройка выпадающего списка моделей OpenRouter
        val models = arrayOf(
            "Claude 4.5",
            "ChatGPT 5",
            "Gemini 2.5 Flash-Lite"
        )
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models)
        spinnerOpenRouterModel.setAdapter(modelAdapter)
        
        // Устанавливаем текущий режим
        when (currentMode) {
            PreferencesManager.RecognitionMode.OPENCV -> spinnerRecognitionMode.setText(modes[0], false)
            PreferencesManager.RecognitionMode.OPENROUTER -> spinnerRecognitionMode.setText(modes[1], false)
            PreferencesManager.RecognitionMode.HYBRID -> spinnerRecognitionMode.setText(modes[2], false)
            PreferencesManager.RecognitionMode.OPENAI,
            PreferencesManager.RecognitionMode.GEMINI -> {
                // УСТАРЕВШИЕ режимы - переключаем на OpenRouter
                spinnerRecognitionMode.setText(modes[1], false)
            }
        }
        
        // Устанавливаем текущую модель
        when (currentModel) {
            PreferencesManager.OpenRouterModel.CLAUDE_45 -> spinnerOpenRouterModel.setText(models[0], false)
            PreferencesManager.OpenRouterModel.CHATGPT_5 -> spinnerOpenRouterModel.setText(models[1], false)
            PreferencesManager.OpenRouterModel.GEMINI_25_FLASH_LITE -> spinnerOpenRouterModel.setText(models[2], false)
        }
        
        // Устанавливаем текущий API ключ
        etOpenRouterApiKey.setText(currentOpenRouterKey)
        
        // Обновляем статус
        updateOpenRouterDialogStatus(tvApiKeyStatus, tvStatistics, currentOpenRouterKey, currentModel)
        
        // Проверяем и включаем кнопку тестирования, если ключ уже есть
        btnTestOpenRouter.isEnabled = currentOpenRouterKey.startsWith("sk-or-") && currentOpenRouterKey.length > 20
        Log.d("MainActivity", "🔑 Инициализация: API ключ ${if (btnTestOpenRouter.isEnabled) "валиден" else "отсутствует/невалиден"}")
        
        // Обработчик изменения OpenRouter API ключа
        etOpenRouterApiKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val apiKey = s?.toString() ?: ""
                updateOpenRouterDialogStatus(tvApiKeyStatus, tvStatistics, apiKey, currentModel)
                btnTestOpenRouter.isEnabled = apiKey.startsWith("sk-or-") && apiKey.length > 20
                Log.d("MainActivity", "🔑 API ключ изменён, кнопка тестирования: ${if (btnTestOpenRouter.isEnabled) "включена" else "выключена"}")
            }
        })
        
        // Создаем диалог
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Обработчик кнопки тестирования
        btnTestOpenRouter.setOnClickListener {
            Log.d("MainActivity", "🧪 Кнопка 'Тест API' нажата")
            
            val apiKey = etOpenRouterApiKey.text?.toString() ?: ""
            Log.d("MainActivity", "🔑 API ключ: ${apiKey.take(10)}... (длина: ${apiKey.length})")
            
            if (!apiKey.startsWith("sk-or-") || apiKey.length < 20) {
                Log.w("MainActivity", "❌ Некорректный API ключ")
                android.widget.Toast.makeText(this, "❌ Некорректный API ключ OpenRouter", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Отключаем кнопку во время теста
            btnTestOpenRouter.isEnabled = false
            btnTestOpenRouter.text = "Тестирование..."
            Log.d("MainActivity", "⏳ Начинаем тест API...")
            
            // Получаем выбранную модель
            val selectedModelText = spinnerOpenRouterModel.text.toString()
            Log.d("MainActivity", "🤖 Выбрана модель: $selectedModelText")
            
            val selectedModel = when (selectedModelText) {
                models[0] -> com.example.diceautobet.recognition.OpenRouterDiceRecognizer.Model.CLAUDE_45
                models[1] -> com.example.diceautobet.recognition.OpenRouterDiceRecognizer.Model.CHATGPT_5
                else -> com.example.diceautobet.recognition.OpenRouterDiceRecognizer.Model.GEMINI_25_FLASH_LITE
            }
            
            // Запускаем тест в корутине
            lifecycleScope.launchWhenStarted {
                try {
                    Log.d("MainActivity", "🚀 Создаём OpenRouterDiceRecognizer...")
                    val recognizer = com.example.diceautobet.recognition.OpenRouterDiceRecognizer(apiKey)
                    
                    Log.d("MainActivity", "📡 Отправляем тестовый запрос...")
                    val (success, message) = recognizer.testApiConnection(selectedModel)
                    
                    Log.d("MainActivity", "🧪 Результат теста API: success=$success, message=$message")
                    
                    // Восстанавливаем кнопку
                    btnTestOpenRouter.isEnabled = true
                    btnTestOpenRouter.text = "Тест API"
                    
                    // Показываем результат
                    android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "❌ Ошибка при тестировании API: ${e.message}", e)
                    btnTestOpenRouter.isEnabled = true
                    btnTestOpenRouter.text = "Тест API"
                    android.widget.Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        
        // Обработчик кнопки сохранения
        btnSaveAISettings.setOnClickListener {
            val openRouterKey = etOpenRouterApiKey.text?.toString() ?: ""
            
            val selectedMode = when (spinnerRecognitionMode.text.toString()) {
                modes[1] -> PreferencesManager.RecognitionMode.OPENROUTER
                modes[2] -> PreferencesManager.RecognitionMode.HYBRID
                else -> PreferencesManager.RecognitionMode.OPENCV
            }
            
            val selectedModel = when (spinnerOpenRouterModel.text.toString()) {
                models[0] -> PreferencesManager.OpenRouterModel.CLAUDE_45
                models[1] -> PreferencesManager.OpenRouterModel.CHATGPT_5
                else -> PreferencesManager.OpenRouterModel.GEMINI_25_FLASH_LITE
            }
            
            // Сохраняем настройки
            prefsManager.saveOpenRouterApiKey(openRouterKey)
            prefsManager.saveOpenRouterModel(selectedModel)
            prefsManager.saveRecognitionMode(selectedMode)
            prefsManager.saveAIProvider(PreferencesManager.AIProvider.OPENROUTER)
            
            android.widget.Toast.makeText(this, "Настройки сохранены (OpenRouter: ${selectedModel.displayName})", android.widget.Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "AI настройки сохранены: mode=$selectedMode, model=${selectedModel.displayName}")
            
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * Обновляет статус в диалоге настроек OpenRouter
     */
    private fun updateOpenRouterDialogStatus(
        statusView: TextView, 
        statsView: TextView, 
        apiKey: String, 
        model: PreferencesManager.OpenRouterModel
    ) {
        // Статус API ключа
        when {
            apiKey.isEmpty() -> {
                statusView.text = "❌ API ключ не введен"
                statusView.setTextColor(getColor(android.R.color.holo_red_dark))
            }
            apiKey.startsWith("sk-or-") && apiKey.length > 20 -> {
                statusView.text = "✅ OpenRouter ключ корректный"
                statusView.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            else -> {
                statusView.text = "⚠️ API ключ некорректный"
                statusView.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
        }
        
        // Статистика
        val currentMode = prefsManager.getRecognitionMode()
        val modeName = when (currentMode) {
            PreferencesManager.RecognitionMode.OPENCV -> "OpenCV (встроенный)"
            PreferencesManager.RecognitionMode.OPENROUTER -> "OpenRouter"
            PreferencesManager.RecognitionMode.HYBRID -> "Гибридный"
            else -> "OpenCV (по умолчанию)"
        }
        
        statsView.text = """
            Текущий режим: $modeName
            Модель: ${model.displayName}
            OpenRouter настроен: ${if (apiKey.startsWith("sk-or-")) "Да" else "Нет"}
        """.trimIndent()
    }
    
    /**
     * Тестирует подключение к прокси-серверу
     */
    private fun testProxyConnection() {
        val connectionType = if (ProxyManager.isProxyEnabled()) "прокси" else "VPN/прямое подключение"
        
        // Показываем прогресс
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("🌐 Тестирование соединения")
            .setMessage("Проверяем подключение через $connectionType...")
            .setCancelable(false)
            .show()
        
        // Запускаем тест в корутине
        lifecycleScope.launch {
            try {
                val result = ProxyManager.testConnection()
                
                progressDialog.dismiss()
                
                when (result) {
                    is ProxyManager.ProxyTestResult.Success -> {
                        // Сохраняем успешный результат
                        prefsManager.saveLastProxyTestResult(
                            success = true,
                            message = "Подключение успешно (${result.duration}мс)"
                        )
                        
                        // Извлекаем IP из ответа для показа пользователю
                        val displayInfo = try {
                            when {
                                result.response.contains("\"ip\"") -> {
                                    val ip = result.response.substringAfter("\"ip\":\"").substringBefore("\"")
                                    "IP: $ip"
                                }
                                result.response.contains("origin") -> {
                                    val ip = result.response.substringAfter("\"origin\":\"").substringBefore("\"")
                                    "IP: $ip"
                                }
                                else -> "Ответ получен"
                            }
                        } catch (e: Exception) {
                            "Ответ получен"
                        }
                        
                        val title = if (ProxyManager.isProxyEnabled()) "✅ Прокси работает!" else "✅ Соединение работает!"
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(title)
                            .setMessage("""
                                Подключение через $connectionType успешно установлено.
                                
                                ⏱️ Время отклика: ${result.duration} мс
                                🌍 Внешний адрес: $displayInfo
                                � Соединение: ${ProxyManager.getCurrentConnectionInfo()}
                                
                                Все запросы к AI API теперь будут проходить через прокси-сервер.
                                VPN больше не нужен!
                            """.trimIndent())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is ProxyManager.ProxyTestResult.Error -> {
                        // Сохраняем результат ошибки
                        prefsManager.saveLastProxyTestResult(
                            success = false,
                            message = result.message
                        )
                        
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("❌ Ошибка прокси")
                            .setMessage("""
                                Не удалось подключиться к прокси-серверу.
                                
                                🔍 Детали ошибки:
                                ${result.message}
                                
                                💡 Возможные причины:
                                • Прокси-сервер недоступен
                                • Неверные учетные данные
                                • Проблемы с интернет-соединением
                                
                                Попробуйте позже или обратитесь к администратору.
                            """.trimIndent())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("MainActivity", "Ошибка при тестировании прокси", e)
                
                // Сохраняем результат критической ошибки
                prefsManager.saveLastProxyTestResult(
                    success = false,
                    message = "Критическая ошибка: ${e.message}"
                )
                
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("❌ Критическая ошибка")
                    .setMessage("Произошла непредвиденная ошибка при тестировании прокси:\n\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    // === МЕТОДЫ ОДИНОЧНОГО РЕЖИМА ===
    
    /**
     * Открывает настройки одиночного режима
     */
    private fun openSingleModeSettings() {
        try {
            Log.d("MainActivity", "Запуск настроек одиночного режима")
            
            val intent = Intent(this, com.example.diceautobet.ui.SingleModeSettingsActivity::class.java)
            startActivity(intent)
            
            gameLogger.logUserAction("Открыты настройки одиночного режима")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка открытия настроек одиночного режима", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Открывает настройку областей для одиночного режима
     */
    private fun openSingleModeAreaConfiguration() {
        try {
            Log.d("MainActivity", "Запуск настройки областей одиночного режима")
            
            // Проверяем разрешение на отображение поверх других приложений
            if (!Settings.canDrawOverlays(this)) {
                Log.w("MainActivity", "Нет разрешения на отображение поверх других приложений")
                showOverlayPermissionDialog {
                    // После получения разрешения запускаем настройку
                    startSingleModeAreaConfiguration()
                }
                return
            }
            
            startSingleModeAreaConfiguration()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка открытия настройки областей одиночного режима", e)
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Запускает сервис настройки областей одиночного режима
     */
    private fun startSingleModeAreaConfiguration() {
        // Запускаем сервис настройки областей с первой области
        com.example.diceautobet.ui.SingleModeAreaConfigService.configureArea(
            this, 
            SingleModeAreaType.DICE_AREA
        )
        
        gameLogger.logUserAction("Запущен сервис настройки областей одиночного режима")
        
        // Сворачиваем приложение, чтобы показать overlay
        moveTaskToBack(true)
    }
    
    /**
     * Показывает диалог запроса разрешения на отображение поверх других приложений
     */
    private fun showOverlayPermissionDialog(onPermissionGranted: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Требуется разрешение")
            .setMessage("Для настройки областей необходимо разрешение на отображение поверх других приложений.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(intent)
                // Пользователь должен будет вернуться и повторить действие
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    /**
     * Запускает одиночный режим игры
     */
    private fun startSingleMode() {
        try {
            Log.d("MainActivity", "Запуск одиночного режима")
            
            // Проверяем разрешения
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "Требуется разрешение на отображение поверх других приложений", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return
            }
            
            // Проверяем настройки одиночного режима
            val prefs = getSharedPreferences("single_mode_prefs", MODE_PRIVATE)
            val singleModeEnabled = prefs.getBoolean("single_mode_enabled", false)
            
            if (!singleModeEnabled) {
                Toast.makeText(this, "Сначала включите одиночный режим", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Проверяем, настроены ли области
            if (!areSingleModeAreasConfigured()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Области не настроены")
                    .setMessage("Для работы одиночного режима необходимо настроить области. Открыть настройку сейчас?")
                    .setPositiveButton("Настроить") { _, _ ->
                        openSingleModeAreaConfiguration()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
                return
            }
            
            // Запускаем OverlayService в режиме одиночной игры
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("MODE", "SINGLE_MODE")
                putExtra("AUTO_START", true)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            gameLogger.logUserAction("Запущен одиночный режим игры")
            Toast.makeText(this, "Одиночный режим запущен! Используйте плавающее окно для управления.", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка запуска одиночного режима", e)
            Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Проверяет, настроены ли области для одиночного режима
     */
    private fun areSingleModeAreasConfigured(): Boolean {
        return prefsManager.areAllSingleModeAreasConfigured()
    }
    
    /**
     * Обновляет состояние кнопок одиночного режима
     */
    private fun updateSingleModeButtonsState() {
        val prefs = getSharedPreferences("single_mode_prefs", MODE_PRIVATE)
        val singleModeEnabled = prefs.getBoolean("single_mode_enabled", false)
        val hasOverlay = hasOverlayPermission()
        
        // Кнопки настроек доступны всегда
        binding.btnConfigureSingleMode?.isEnabled = true
        binding.btnConfigureSingleAreas?.isEnabled = true
        
        // Кнопка запуска доступна только при включенном режиме и наличии разрешений
        binding.btnStartSingleMode?.isEnabled = singleModeEnabled && hasOverlay
        
        Log.d("MainActivity", "Кнопки одиночного режима обновлены: enabled=$singleModeEnabled, hasOverlay=$hasOverlay")
    }
}