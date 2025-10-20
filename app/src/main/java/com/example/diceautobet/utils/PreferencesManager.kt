package com.example.diceautobet.utils

import android.content.Context
import android.graphics.Rect
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.BetChoice
import com.example.diceautobet.models.ScreenArea
import com.example.diceautobet.models.WindowType
import com.example.diceautobet.models.DualModeSettings
import com.example.diceautobet.models.DualWindowStrategy
import org.json.JSONArray
import org.json.JSONObject
import android.os.Parcel
import android.content.Intent
import android.util.Base64
import android.util.Log
import android.app.Activity
import android.os.Build
import java.io.File

class PreferencesManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("DiceAutoBetPrefs", Context.MODE_PRIVATE)
    
    // Публичный доступ к контексту для отладочных целей
    val appContext: Context get() = context

    // Сохранение области (ПРОСТАЯ ЛОГИКА - как выделил, так и сохранил)
    fun saveArea(areaType: AreaType, rect: Rect) {
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        val absoluteRect = Rect(left, top, right, bottom)
        
        Log.d("PreferencesManager", "Сохраняем область: $areaType")
        Log.d("PreferencesManager", "Координаты (как есть): $absoluteRect")
        
        // Сохраняем координаты напрямую - никаких пересчетов!
        with(prefs.edit()) {
            putInt("${areaType.name}_left", absoluteRect.left)
            putInt("${areaType.name}_top", absoluteRect.top)
            putInt("${areaType.name}_right", absoluteRect.right)
            putInt("${areaType.name}_bottom", absoluteRect.bottom)
            apply()
        }
        
        Log.d("PreferencesManager", "Область сохранена напрямую: left=${absoluteRect.left}, top=${absoluteRect.top}, right=${absoluteRect.right}, bottom=${absoluteRect.bottom}")
    }

    // Загрузка области (ПРОСТАЯ ЛОГИКА - как сохранил, так и загружаем)
    fun loadArea(areaType: AreaType): ScreenArea? {
        val left = prefs.getInt("${areaType.name}_left", -1)
        if (left == -1) return null

        val top = prefs.getInt("${areaType.name}_top", -1)
        val right = prefs.getInt("${areaType.name}_right", -1)
        val bottom = prefs.getInt("${areaType.name}_bottom", -1)
        
        val rect = Rect(left, top, right, bottom)
        
        Log.d("PreferencesManager", "Загружена область $areaType: $rect (напрямую)")
        
        return ScreenArea(areaType.displayName, rect)
    }

    // ==================== DUAL MODE ОБЛАСТИ (ПРОСТАЯ ЛОГИКА) ====================
    
    // Сохранение области для конкретного окна dual mode
    fun saveDualModeArea(windowType: String, areaType: AreaType, rect: Rect) {
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        val absoluteRect = Rect(left, top, right, bottom)
        
        Log.d("PreferencesManager", "Сохраняем область для dual mode: $windowType -> $areaType")
        Log.d("PreferencesManager", "Координаты (как есть): $absoluteRect")
        
        // Сохраняем с префиксом окна - никаких пересчетов!
        with(prefs.edit()) {
            putInt("${windowType}_${areaType.name}_left", absoluteRect.left)
            putInt("${windowType}_${areaType.name}_top", absoluteRect.top)
            putInt("${windowType}_${areaType.name}_right", absoluteRect.right)
            putInt("${windowType}_${areaType.name}_bottom", absoluteRect.bottom)
            apply()
        }
        
        Log.d("PreferencesManager", "Область dual mode сохранена: $windowType -> $areaType = $absoluteRect")
    }
    
    // Загрузка области для конкретного окна dual mode (ОПТИМИЗИРОВАНО: минимум логов)
    fun loadDualModeArea(windowType: String, areaType: AreaType): ScreenArea? {
        val left = prefs.getInt("${windowType}_${areaType.name}_left", -1)
        if (left == -1) return null

        val top = prefs.getInt("${windowType}_${areaType.name}_top", -1)
        val right = prefs.getInt("${windowType}_${areaType.name}_right", -1)
        val bottom = prefs.getInt("${windowType}_${areaType.name}_bottom", -1)
        
        val rect = Rect(left, top, right, bottom)
        
        // Убираем подробные логи каждой области для скорости
        // Log.d("PreferencesManager", "Загружена область dual mode: $windowType -> $areaType = $rect")
        
        return ScreenArea(areaType.displayName, rect)
    }
    
    // Загрузка всех областей для конкретного окна dual mode (ОПТИМИЗИРОВАНО: минимум логов)
    fun loadAreasForWindow(windowType: WindowType): Map<AreaType, ScreenArea> {
        val windowTypeString = windowType.name
        val areas = mutableMapOf<AreaType, ScreenArea>()
        
        // Только один лог на весь процесс загрузки
        Log.d("PreferencesManager", "Загружаем все области для окна: $windowTypeString")
        
        AreaType.values().forEach { areaType ->
            val area = loadDualModeArea(windowTypeString, areaType)
            if (area != null) {
                areas[areaType] = area
                // Убираем подробные логи каждой области для скорости
                // Log.d("PreferencesManager", "Загружена область $areaType для $windowTypeString: ${area.rect}")
            }
        }
        
        Log.d("PreferencesManager", "Загружено ${areas.size} областей для $windowTypeString")
        return areas
    }

    // Универсальная загрузка области
    fun loadAreaUniversal(areaType: AreaType): ScreenArea? {
        return loadArea(areaType)
    }

    // Проверка, все ли области настроены
    fun areAllAreasConfigured(): Boolean {
        Log.d("PreferencesManager", "Проверяем настройку всех областей")
        
        val allConfigured = AreaType.values().all { loadAreaUniversal(it) != null }
        Log.d("PreferencesManager", "Все области настроены: $allConfigured")
        
        if (!allConfigured) {
            val missingAreas = AreaType.values().filter { loadAreaUniversal(it) == null }
            Log.d("PreferencesManager", "Отсутствуют области: $missingAreas")
        }
        
        return allConfigured
    }

    // Настройки игры
    fun saveBaseBet(bet: Int) {
        Log.d("PreferencesManager", "Сохраняем базовую ставку: $bet")
        prefs.edit().putInt("base_bet", bet).apply()
        Log.d("PreferencesManager", "Базовая ставка сохранена")
    }

    fun getBaseBet(): Int {
        val bet = prefs.getInt("base_bet", 20)
        Log.d("PreferencesManager", "Получаем базовую ставку: $bet")
        return bet
    }

    fun saveMaxAttempts(attempts: Int) {
        Log.d("PreferencesManager", "Сохраняем максимальное количество попыток: $attempts")
        prefs.edit().putInt("max_attempts", attempts).apply()
        Log.d("PreferencesManager", "Максимальное количество попыток сохранено")
    }

    fun getMaxAttempts(): Int {
        val attempts = prefs.getInt("max_attempts", 10)
        Log.d("PreferencesManager", "Получаем максимальное количество попыток: $attempts")
        return attempts
    }

    fun saveBetChoice(choice: BetChoice) {
        Log.d("PreferencesManager", "Сохраняем выбор ставки: $choice")
        prefs.edit().putString("bet_choice", choice.name).apply()
        Log.d("PreferencesManager", "Выбор ставки сохранен")
    }

    fun getBetChoice(): BetChoice {
        val choiceStr = prefs.getString("bet_choice", BetChoice.RED.name)
        val choice = BetChoice.valueOf(choiceStr ?: BetChoice.RED.name)
        Log.d("PreferencesManager", "Получаем выбор ставки: $choice (из строки: $choiceStr)")
        return choice
    }

    // Интеллектуальная система
    fun saveIntelligentMode(enabled: Boolean) {
        Log.d("PreferencesManager", "Сохраняем режим интеллектуальной системы: $enabled")
        prefs.edit().putBoolean("intelligent_mode", enabled).apply()
        Log.d("PreferencesManager", "Режим интеллектуальной системы сохранен")
    }

    fun getIntelligentMode(): Boolean {
        val enabled = prefs.getBoolean("intelligent_mode", true)
        Log.d("PreferencesManager", "Получаем режим интеллектуальной системы: $enabled")
        return enabled
    }

    fun saveButtonTrackingEnabled(enabled: Boolean) {
        Log.d("PreferencesManager", "Сохраняем отслеживание кнопок: $enabled")
        prefs.edit().putBoolean("button_tracking_enabled", enabled).apply()
    }

    fun getButtonTrackingEnabled(): Boolean {
        val enabled = prefs.getBoolean("button_tracking_enabled", true)
        Log.d("PreferencesManager", "Получаем отслеживание кнопок: $enabled")
        return enabled
    }

    fun saveResultTrackingEnabled(enabled: Boolean) {
        Log.d("PreferencesManager", "Сохраняем отслеживание результатов: $enabled")
        prefs.edit().putBoolean("result_tracking_enabled", enabled).apply()
    }

    fun getResultTrackingEnabled(): Boolean {
        val enabled = prefs.getBoolean("result_tracking_enabled", true)
        Log.d("PreferencesManager", "Получаем отслеживание результатов: $enabled")
        return enabled
    }

    fun saveIntelligentDelayEnabled(enabled: Boolean) {
        Log.d("PreferencesManager", "Сохраняем умные задержки: $enabled")
        prefs.edit().putBoolean("intelligent_delay_enabled", enabled).apply()
    }

    fun getIntelligentDelayEnabled(): Boolean {
        val enabled = prefs.getBoolean("intelligent_delay_enabled", true)
        Log.d("PreferencesManager", "Получаем умные задержки: $enabled")
        return enabled
    }
    
    fun getAutoStopOnInsufficientBalance(): Boolean {
        val enabled = prefs.getBoolean("auto_stop_insufficient_balance", false)
        Log.d("PreferencesManager", "Получаем автостоп по балансу: $enabled")
        return enabled
    }

    fun getCurrentBalance(): Int {
        val balance = prefs.getInt("current_balance", 10000)
        Log.d("PreferencesManager", "Получаем текущий баланс: $balance")
        return balance
    }

    // Тайминги
    fun saveClickDelay(delay: Long) {
        Log.d("PreferencesManager", "Сохраняем задержку клика: $delay")
        prefs.edit().putLong("click_delay", delay).apply()
        Log.d("PreferencesManager", "Задержка клика сохранена")
    }

    fun getClickDelay(): Long {
        val delay = prefs.getLong("click_delay", 200L) // Быстрая задержка по умолчанию 200мс
        Log.d("PreferencesManager", "Получаем задержку клика: $delay")
        return delay
    }

    fun saveCheckDelay(delay: Long) {
        Log.d("PreferencesManager", "Сохраняем задержку проверки: $delay")
        prefs.edit().putLong("check_delay", delay).apply()
        Log.d("PreferencesManager", "Задержка проверки сохранена")
    }

    fun getCheckDelay(): Long {
        val delay = prefs.getLong("check_delay", 6000L)
        Log.d("PreferencesManager", "Получаем задержку проверки: $delay")
        return delay
    }

    // Дополнительные задержки для последовательных кликов
    fun getBetSequenceDelay(): Long {
        val delay = prefs.getLong("bet_sequence_delay", 300L) // Быстрая задержка 300мс
        Log.d("PreferencesManager", "Получаем задержку между кликами в ставке: $delay")
        return delay
    }

    fun saveBetSequenceDelay(delay: Long) {
        Log.d("PreferencesManager", "Сохраняем задержку между кликами в ставке: $delay")
        prefs.edit().putLong("bet_sequence_delay", delay).apply()
        Log.d("PreferencesManager", "Задержка между кликами в ставке сохранена")
    }

    // Задержки для отдельных кликов
    fun getClickStabilityDelay(): Long {
        val delay = prefs.getLong("click_stability_delay", 100L) // Очень быстро - 100мс
        Log.d("PreferencesManager", "Получаем задержку стабилизации кликов: $delay")
        return delay
    }

    fun saveClickStabilityDelay(delay: Long) {
        Log.d("PreferencesManager", "Сохраняем задержку стабилизации кликов: $delay")
        prefs.edit().putLong("click_stability_delay", delay).apply()
        Log.d("PreferencesManager", "Задержка стабилизации кликов сохранена")
    }

    // Очистка всех настроек
    fun clearAllAreas() {
        Log.d("PreferencesManager", "Очищаем все области")
        Log.d("PreferencesManager", "Удаляем области: ${AreaType.values().joinToString(", ") { it.name }}")
        
        with(prefs.edit()) {
            AreaType.values().forEach { areaType ->
                // Удаляем старые координаты
                remove("${areaType.name}_left")
                remove("${areaType.name}_top")
                remove("${areaType.name}_right")
                remove("${areaType.name}_bottom")
                // Удаляем новые адаптивные координаты
                remove("${areaType.name}_left_percent")
                remove("${areaType.name}_top_percent")
                remove("${areaType.name}_right_percent")
                remove("${areaType.name}_bottom_percent")
            }
            apply()
        }
        Log.d("PreferencesManager", "Все области очищены")
    }

    /**
     * Облегчённая проверка: есть ли сохранённые данные разрешения MediaProjection.
     * Не пытается создать MediaProjection (это требует foreground service).
     */
    fun hasValidMediaProjection(): Boolean {
        // 1) Проверяем процессный токен
        val inMemory = com.example.diceautobet.utils.MediaProjectionTokenStore.get()
        if (inMemory != null) {
            Log.d("PreferencesManager", "Валидное разрешение найдено по процессному токену")
            return true
        }

        // 2) Проверяем сохранённые данные
        val permissionData = getMediaProjectionPermission() ?: return false
        val (resultCode, intent) = permissionData
        val isValid = resultCode == Activity.RESULT_OK && intent != null
        
        Log.d("PreferencesManager", "Проверка сохранённых данных: resultCode=$resultCode, hasIntent=${intent != null}, valid=$isValid")
        return isValid
    }

    // Функция для получения пути к папке со скриншотами областей
    fun getAreaScreenshotsDirectory(): String {
        val screenshotsDir = File(context.getExternalFilesDir(null), "area_screenshots")
        Log.d("PreferencesManager", "Путь к папке со скриншотами: ${screenshotsDir.absolutePath}")
        return screenshotsDir.absolutePath
    }
    
    // Предустановленные суммы ставок
    fun getAvailableBetAmounts(): List<Int> {
        return listOf(10, 20, 50, 100, 200, 500, 1000, 2500)
    }

    // Сохранение текущего баланса
    fun saveCurrentBalance(balance: Int) {
        prefs.edit()
            .putInt("current_balance", balance)
            .apply()
    }

    // Сброс статистики сессии
    fun resetSessionStatistics() {
        with(prefs.edit()) {
            putInt("session_total_bets", 0)
            putInt("session_wins", 0)
            putInt("session_losses", 0)
            putInt("session_draws", 0)
            putInt("session_total_profit", 0)
            putInt("session_current_streak", 0)
            putString("session_streak_type", "NONE")
            putInt("session_max_win_streak", 0)
            putInt("session_max_loss_streak", 0)
            putLong("session_start_time", System.currentTimeMillis())
            // Сброс дополнительных полей статистики
            putInt("session_start_balance", 0)
            putInt("current_balance", 0)
            putInt("total_profit", 0)
            putInt("total_bets_placed", 0)
            putInt("max_consecutive_losses", 0)
            apply()
        }
    }
    
    // === МЕТОДЫ ДЛЯ ДВОЙНОГО РЕЖИМА ===
    
    /**
     * Сохранение настроек двойного режима
     */
    fun saveDualModeSettings(settings: com.example.diceautobet.models.DualModeSettings) {
        Log.d("PreferencesManager", "Сохраняем настройки двойного режима: $settings")
        with(prefs.edit()) {
            putBoolean("dual_mode_enabled", settings.enabled)
            putString("dual_mode_strategy", settings.strategy.name)
            putString("dual_mode_split_screen_type", settings.splitScreenType.name)
            putInt("dual_mode_base_bet", settings.baseBet)
            putInt("dual_mode_max_bet", settings.maxBet)
            putInt("dual_mode_max_consecutive_losses", settings.maxConsecutiveLosses)
            putLong("dual_mode_delay_between_actions", settings.delayBetweenActions)
            putBoolean("dual_mode_auto_switch_windows", settings.autoSwitchWindows)
            putBoolean("dual_mode_auto_color_change", settings.autoColorChange)
            putBoolean("dual_mode_timing_optimization", settings.enableTimingOptimization)
            putBoolean("dual_mode_smart_synchronization", settings.smartSynchronization)
            apply()
        }
        Log.d("PreferencesManager", "Настройки двойного режима сохранены")
    }
    
    /**
     * Загрузка настроек двойного режима
     */
    fun getDualModeSettings(): com.example.diceautobet.models.DualModeSettings {
        val strategyName = prefs.getString("dual_mode_strategy", com.example.diceautobet.models.DualStrategy.WIN_SWITCH.name)
        val strategy = try {
            com.example.diceautobet.models.DualStrategy.valueOf(strategyName!!)
        } catch (e: Exception) {
            Log.w("PreferencesManager", "Неизвестная стратегия: $strategyName, используем по умолчанию")
            com.example.diceautobet.models.DualStrategy.WIN_SWITCH
        }
        
        val splitScreenTypeName = prefs.getString("dual_mode_split_screen_type", com.example.diceautobet.models.SplitScreenType.HORIZONTAL.name)
        val splitScreenType = try {
            val requestedType = com.example.diceautobet.models.SplitScreenType.valueOf(splitScreenTypeName!!)
            
            // Проверяем наличие областей для выбранного типа разделения
            val (firstWindow, secondWindow) = when (requestedType) {
                com.example.diceautobet.models.SplitScreenType.HORIZONTAL -> 
                    Pair(com.example.diceautobet.models.WindowType.LEFT, com.example.diceautobet.models.WindowType.RIGHT)
                com.example.diceautobet.models.SplitScreenType.VERTICAL -> 
                    Pair(com.example.diceautobet.models.WindowType.TOP, com.example.diceautobet.models.WindowType.BOTTOM)
            }
            
            val firstAreasCount = loadAreasForWindow(firstWindow).size
            val secondAreasCount = loadAreasForWindow(secondWindow).size
            
            Log.d("PreferencesManager", "Загружен тип разделения: $requestedType, области: $firstWindow=$firstAreasCount, $secondWindow=$secondAreasCount")
            
            // Если для горизонтального режима нет областей, но есть для TOP, копируем их
            if (requestedType == com.example.diceautobet.models.SplitScreenType.HORIZONTAL) {
                copyAreasFromTopToHorizontal()
            }
            
            requestedType
        } catch (e: Exception) {
            Log.w("PreferencesManager", "Неизвестный тип разделения экрана: $splitScreenTypeName, используем по умолчанию")
            com.example.diceautobet.models.SplitScreenType.HORIZONTAL
        }
        
        val settings = com.example.diceautobet.models.DualModeSettings(
            enabled = prefs.getBoolean("dual_mode_enabled", false),
            strategy = strategy,
            splitScreenType = splitScreenType,
            baseBet = prefs.getInt("dual_mode_base_bet", 20),  // Изменено с 10 на 20
            maxBet = prefs.getInt("dual_mode_max_bet", 30000),  // Изменено с 2500 на 30000
            maxConsecutiveLosses = prefs.getInt("dual_mode_max_consecutive_losses", 3),
            delayBetweenActions = prefs.getLong("dual_mode_delay_between_actions", 1000L),
            autoSwitchWindows = prefs.getBoolean("dual_mode_auto_switch_windows", true),
            autoColorChange = prefs.getBoolean("dual_mode_auto_color_change", true),
            enableTimingOptimization = prefs.getBoolean("dual_mode_timing_optimization", true),
            smartSynchronization = prefs.getBoolean("dual_mode_smart_synchronization", true)
        )
        
        Log.d("PreferencesManager", "Загружены настройки двойного режима: $settings")
        return settings
    }
    
    /**
     * Проверка, включен ли двойной режим
     */
    fun isDualModeEnabled(): Boolean {
        val enabled = prefs.getBoolean("dual_mode_enabled", false)
        Log.d("PreferencesManager", "Двойной режим включен: $enabled")
        return enabled
    }
    
    /**
     * Проверяет, готов ли двойной режим для выбранного типа разделения экрана
     */
    fun isDualModeReadyForSplitType(): Pair<Boolean, String> {
        val settings = getDualModeSettings()
        val (firstWindow, secondWindow) = when (settings.splitScreenType) {
            com.example.diceautobet.models.SplitScreenType.HORIZONTAL -> 
                Pair(com.example.diceautobet.models.WindowType.LEFT, com.example.diceautobet.models.WindowType.RIGHT)
            com.example.diceautobet.models.SplitScreenType.VERTICAL -> 
                Pair(com.example.diceautobet.models.WindowType.TOP, com.example.diceautobet.models.WindowType.BOTTOM)
        }
        
        val firstAreasCount = loadAreasForWindow(firstWindow).size
        val secondAreasCount = loadAreasForWindow(secondWindow).size
        
        val isReady = firstAreasCount > 0 && secondAreasCount > 0
        val message = if (isReady) {
            "Двойной режим готов (${settings.splitScreenType}): $firstWindow=$firstAreasCount областей, $secondWindow=$secondAreasCount областей"
        } else {
            "Двойной режим НЕ готов (${settings.splitScreenType}): $firstWindow=$firstAreasCount областей, $secondWindow=$secondAreasCount областей. Необходимо настроить области для обоих окон."
        }
        
        Log.d("PreferencesManager", message)
        return Pair(isReady, message)
    }
    
    /**
     * Сохранение областей для определенного окна (ПРОСТАЯ ЛОГИКА)
     */
    fun saveAreasForWindow(windowType: com.example.diceautobet.models.WindowType, areas: Map<AreaType, ScreenArea>) {
        Log.d("PreferencesManager", "Сохраняем области для окна: $windowType, количество: ${areas.size}")
        
        areas.forEach { (areaType, screenArea) ->
            // Используем новую простую логику - сохраняем координаты как есть
            saveDualModeArea(windowType.name, areaType, screenArea.rect)
            Log.d("PreferencesManager", "Область $areaType для $windowType сохранена: ${screenArea.rect}")
        }
        
        Log.d("PreferencesManager", "Все области для окна $windowType сохранены (простая логика)")
    }
    
    /**
     * Сохранение одной области для указанного окна (ПРОСТАЯ ЛОГИКА)
     */
    fun saveAreaForWindow(windowType: com.example.diceautobet.models.WindowType, areaType: AreaType, screenArea: ScreenArea) {
        Log.d("PreferencesManager", "Сохраняем область $areaType для окна $windowType: ${screenArea.rect}")
        
        // Используем новую простую логику - сохраняем координаты как есть
        saveDualModeArea(windowType.name, areaType, screenArea.rect)
        
        Log.d("PreferencesManager", "Область $areaType для окна $windowType сохранена (простая логика)")
    }
    
    // ТЕСТОВЫЙ МЕТОД: Проверка сохраненных областей
    fun debugPrintSavedAreas() {
        Log.d("PreferencesManager", "=== ПРОВЕРКА СОХРАНЕННЫХ ОБЛАСТЕЙ ===")
        
        val windowTypes = listOf("TOP", "BOTTOM", "LEFT", "RIGHT")
        
        windowTypes.forEach { windowType ->
            Log.d("PreferencesManager", "--- Окно $windowType ---")
            
            AreaType.values().forEach { areaType ->
                val left = prefs.getInt("${windowType}_${areaType.name}_left", -1)
                if (left != -1) {
                    val top = prefs.getInt("${windowType}_${areaType.name}_top", -1)
                    val right = prefs.getInt("${windowType}_${areaType.name}_right", -1)
                    val bottom = prefs.getInt("${windowType}_${areaType.name}_bottom", -1)
                    
                    Log.d("PreferencesManager", "  ${areaType.name}: Rect($left, $top, $right, $bottom)")
                } else {
                    Log.d("PreferencesManager", "  ${areaType.name}: НЕ СОХРАНЕНО")
                }
            }
        }
        
        Log.d("PreferencesManager", "=== КОНЕЦ ПРОВЕРКИ ===")
    }
    
    /**
     * Сохранение границ окон разделенного экрана
     */
    fun saveWindowBounds(leftBounds: android.graphics.Rect, rightBounds: android.graphics.Rect) {
        Log.d("PreferencesManager", "Сохраняем границы окон: left=$leftBounds, right=$rightBounds")
        
        // Конвертируем в адаптивные координаты
        val leftAdaptive = CoordinateUtils.convertToAdaptiveCoordinates(leftBounds, context)
        val rightAdaptive = CoordinateUtils.convertToAdaptiveCoordinates(rightBounds, context)
        
        with(prefs.edit()) {
            // Левое окно
            putFloat("left_window_left_percent", leftAdaptive.leftPercent)
            putFloat("left_window_top_percent", leftAdaptive.topPercent)
            putFloat("left_window_right_percent", leftAdaptive.rightPercent)
            putFloat("left_window_bottom_percent", leftAdaptive.bottomPercent)
            
            // Правое окно
            putFloat("right_window_left_percent", rightAdaptive.leftPercent)
            putFloat("right_window_top_percent", rightAdaptive.topPercent)
            putFloat("right_window_right_percent", rightAdaptive.rightPercent)
            putFloat("right_window_bottom_percent", rightAdaptive.bottomPercent)
            
            apply()
        }
        
        Log.d("PreferencesManager", "Границы окон сохранены")
    }
    
    /**
     * Загрузка границ окон разделенного экрана
     */
    fun loadWindowBounds(): Pair<android.graphics.Rect?, android.graphics.Rect?> {
        Log.d("PreferencesManager", "Загружаем границы окон")
        
        val leftLeftPercent = prefs.getFloat("left_window_left_percent", -1f)
        val rightLeftPercent = prefs.getFloat("right_window_left_percent", -1f)
        
        val leftBounds = if (leftLeftPercent != -1f) {
            val leftAdaptive = CoordinateUtils.AdaptiveRect(
                leftLeftPercent,
                prefs.getFloat("left_window_top_percent", 0f),
                prefs.getFloat("left_window_right_percent", 0f),
                prefs.getFloat("left_window_bottom_percent", 0f)
            )
            CoordinateUtils.convertFromAdaptiveCoordinates(leftAdaptive, context)
        } else null
        
        val rightBounds = if (rightLeftPercent != -1f) {
            val rightAdaptive = CoordinateUtils.AdaptiveRect(
                rightLeftPercent,
                prefs.getFloat("right_window_top_percent", 0f),
                prefs.getFloat("right_window_right_percent", 0f),
                prefs.getFloat("right_window_bottom_percent", 0f)
            )
            CoordinateUtils.convertFromAdaptiveCoordinates(rightAdaptive, context)
        } else null
        
        Log.d("PreferencesManager", "Загружены границы окон: left=$leftBounds, right=$rightBounds")
        return Pair(leftBounds, rightBounds)
    }
    
    // Автоматическое копирование областей TOP в LEFT и RIGHT для горизонтального режима
    private fun copyAreasFromTopToHorizontal() {
        if (!hasAreasForWindow(WindowType.LEFT) && hasAreasForWindow(WindowType.TOP)) {
            Log.d("PreferencesManager", "Копируем области из TOP в LEFT для горизонтального режима")
            copyWindowAreas(WindowType.TOP, WindowType.LEFT)
        }
        
        if (!hasAreasForWindow(WindowType.RIGHT) && hasAreasForWindow(WindowType.TOP)) {
            Log.d("PreferencesManager", "Копируем области из TOP в RIGHT для горизонтального режима")
            copyWindowAreas(WindowType.TOP, WindowType.RIGHT)
        }
    }
    
    // Копирование всех областей из одного окна в другое
    private fun copyWindowAreas(fromWindow: WindowType, toWindow: WindowType) {
        val fromAreas = loadAreasForWindow(fromWindow)
        Log.d("PreferencesManager", "Копируем ${fromAreas.size} областей из $fromWindow в $toWindow")
        
        if (fromAreas.isNotEmpty()) {
            saveAreasForWindow(toWindow, fromAreas)
        }
    }
    
    // === MediaProjection Permission Management ===
    
    /**
     * Сохраняет разрешение MediaProjection для многократного использования
     */
    fun saveMediaProjectionPermission(resultCode: Int, data: Intent) {
        Log.d("PreferencesManager", "💾 Сохранение разрешения MediaProjection")
        Log.d("PreferencesManager", "🤖 Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d("PreferencesManager", "📊 ResultCode: $resultCode, Data не null: ${data != null}")
        Log.d("PreferencesManager", "� Анализ resultCode: RESULT_OK=${android.app.Activity.RESULT_OK}, RESULT_CANCELED=${android.app.Activity.RESULT_CANCELED}")
        
        // КРИТИЧЕСКАЯ ПРОВЕРКА: сохраняем только валидные разрешения
        if (resultCode != android.app.Activity.RESULT_OK) {
            Log.e("PreferencesManager", "❌ КРИТИЧЕСКАЯ ОШИБКА: resultCode = $resultCode (должен быть ${android.app.Activity.RESULT_OK})")
            Log.e("PreferencesManager", "🚫 ОТКЛОНЕНИЕ СОХРАНЕНИЯ - невалидный resultCode")
            
            // Очищаем любые сохраненные данные при невалидном resultCode
            clearMediaProjectionPermission()
            // ВАЖНО: Очищаем TokenStore при невалидном resultCode
            com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
            Log.w("PreferencesManager", "🧹 Очищены все данные разрешения из-за невалидного resultCode")
            return
        }
        
        Log.d("PreferencesManager", "✅ ResultCode валиден (${android.app.Activity.RESULT_OK}), продолжаем сохранение...")
        
        try {
            // ВНИМАНИЕ: Intent с MediaProjection содержит Binder объекты и не может быть сериализован
            // Поэтому мы сохраняем только в процессном сторе (MediaProjectionTokenStore)
            
            // Сохраняем в процессном сторе (работает пока приложение активно)
            com.example.diceautobet.utils.MediaProjectionTokenStore.set(data)
            Log.d("PreferencesManager", "✅ Данные сохранены в MediaProjectionTokenStore")
            
            // Сохраняем только метаданные в SharedPreferences
            Log.d("PreferencesManager", "💾 Сохраняем метаданные в SharedPreferences...")
            with(prefs.edit()) {
                putInt("media_projection_result_code", resultCode)
                putBoolean("media_projection_available", true)
                putLong("media_projection_save_time", System.currentTimeMillis())
                apply()
            }
            Log.d("PreferencesManager", "✅ Метаданные сохранены в SharedPreferences")
            
            // Немедленная проверка сохранения
            val savedResultCode = prefs.getInt("media_projection_result_code", -1)
            val savedAvailable = prefs.getBoolean("media_projection_available", false)
            val savedTime = prefs.getLong("media_projection_save_time", 0)
            
            Log.d("PreferencesManager", "🔍 Немедленная проверка сохранения:")
            Log.d("PreferencesManager", "   - savedResultCode: $savedResultCode")
            Log.d("PreferencesManager", "   - savedAvailable: $savedAvailable")
            Log.d("PreferencesManager", "   - savedTime: $savedTime")
            
            if (savedResultCode != resultCode) {
                Log.e("PreferencesManager", "❌ КРИТИЧЕСКАЯ ОШИБКА: resultCode не сохранился! Ожидали $resultCode, получили $savedResultCode")
            }
            
            // Проверяем сохранение через метод
            val verification = getMediaProjectionPermission()
            Log.d("PreferencesManager", "🔍 Верификация через getMediaProjectionPermission(): ${verification != null}")
            
            Log.d("PreferencesManager", "✅ Разрешение MediaProjection сохранено в процессном сторе")
        } catch (e: Exception) {
            Log.e("PreferencesManager", "❌ Ошибка сохранения разрешения MediaProjection", e)
        }
    }
    
    /**
     * Загружает сохраненное разрешение MediaProjection
     */
    fun getMediaProjectionPermission(): Pair<Int, Intent>? {
        return try {
            Log.d("PreferencesManager", "📂 Загружаем разрешение MediaProjection")
            
            val resultCode = prefs.getInt("media_projection_result_code", -1)
            val isAvailable = prefs.getBoolean("media_projection_available", false)
            
            Log.d("PreferencesManager", "📊 Метаданные: resultCode=$resultCode, isAvailable=$isAvailable")
            
            if (resultCode == -1 || !isAvailable) {
                Log.d("PreferencesManager", "❌ Нет сохраненного разрешения MediaProjection")
                return null
            }
            
            // Проверяем время сохранения (разрешение может истечь)
            val saveTime = prefs.getLong("media_projection_save_time", 0)
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - saveTime
            
            Log.d("PreferencesManager", "⏰ Время сохранения: $saveTime, текущее: $currentTime, разница: ${timeDiff / 1000 / 60} минут")
            
            // На Android 15+ ужесточаем время истечения до 1 часа
            val expirationTime = if (Build.VERSION.SDK_INT >= 35) {
                1 * 60 * 60 * 1000 // 1 час для Android 15+
            } else {
                24 * 60 * 60 * 1000 // 24 часа для остальных
            }
            
            if (timeDiff > expirationTime) {
                val hoursAgo = timeDiff / 1000 / 60 / 60
                Log.w("PreferencesManager", "⏰ Разрешение MediaProjection истекло ($hoursAgo часов назад)")
                if (Build.VERSION.SDK_INT >= 35) {
                    Log.w("PreferencesManager", "⚠️ Android 15+ требует более частого обновления разрешений")
                }
                clearMediaProjectionPermission()
                return null
            }
            
            // Пытаемся получить Intent из процессного стора
            val intent = com.example.diceautobet.utils.MediaProjectionTokenStore.get()
            if (intent == null) {
                Log.w("PreferencesManager", "❌ Intent MediaProjection не найден в процессном сторе")
                Log.w("PreferencesManager", "💡 Возможно, приложение было перезапущено - требуется новый запрос разрешения")
                clearMediaProjectionPermission()
                return null
            }
            
            Log.d("PreferencesManager", "✅ Разрешение MediaProjection загружено из процессного стора")
            Pair(resultCode, intent)
            
        } catch (e: Exception) {
            Log.e("PreferencesManager", "❌ Ошибка загрузки разрешения MediaProjection", e)
            clearMediaProjectionPermission()
            null
        }
    }
    
    /**
     * Проверяет, есть ли сохраненное разрешение MediaProjection
     */
    fun hasMediaProjectionPermission(): Boolean {
        Log.d("PreferencesManager", "🔍 Проверка hasMediaProjectionPermission...")
        
        val resultCode = prefs.getInt("media_projection_result_code", -1)
        val isAvailable = prefs.getBoolean("media_projection_available", false)
        val hasIntent = com.example.diceautobet.utils.MediaProjectionTokenStore.get() != null
        
        Log.d("PreferencesManager", "📊 Компоненты проверки:")
        Log.d("PreferencesManager", "   - resultCode: $resultCode (должен быть != -1)")
        Log.d("PreferencesManager", "   - isAvailable: $isAvailable (должен быть true)")
        Log.d("PreferencesManager", "   - hasIntent: $hasIntent (должен быть true)")
        
        val hasPermission = resultCode != -1 && isAvailable && hasIntent
        
        Log.d("PreferencesManager", "🎯 Логика: ($resultCode != -1) && $isAvailable && $hasIntent = $hasPermission")
        
        if (!hasPermission) {
            if (resultCode == -1) {
                Log.w("PreferencesManager", "❌ Проблема: resultCode = -1 (не был сохранен)")
            }
            if (!isAvailable) {
                Log.w("PreferencesManager", "❌ Проблема: isAvailable = false")
            }
            if (!hasIntent) {
                Log.w("PreferencesManager", "❌ Проблема: TokenStore пустой")
            }
        }
        
        Log.d("PreferencesManager", "✅ Результат hasMediaProjectionPermission: $hasPermission")
        return hasPermission
    }
    
    /**
     * Очищает сохраненное разрешение MediaProjection
     */
    fun clearMediaProjectionPermission() {
        Log.d("PreferencesManager", "🧹 Очистка сохраненного разрешения MediaProjection")
        
        // Очищаем процессный стор
        com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
        
        // Очищаем SharedPreferences
        with(prefs.edit()) {
            remove("media_projection_result_code")
            remove("media_projection_available")
            remove("media_projection_save_time")
            apply()
        }
    }
    
    // Проверка, есть ли области для окна
    private fun hasAreasForWindow(window: WindowType): Boolean {
        return loadAreasForWindow(window).isNotEmpty()
    }

    // Отладка изображений
    fun saveDebugImagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("debug_images_enabled", enabled).apply()
    }

    fun isDebugImagesEnabled(): Boolean {
        return prefs.getBoolean("debug_images_enabled", true) // По умолчанию включено
    }

    // ==================== GEMINI API КЛЮЧИ (СПИСОК С РОТАЦИЕЙ) ====================
    
    // Сохранение списка API ключей Gemini
    fun saveGeminiApiKeys(apiKeys: List<String>) {
        val keysJson = JSONArray(apiKeys).toString()
        prefs.edit().putString("gemini_api_keys", keysJson).apply()
        Log.d("PreferencesManager", "Список Gemini API ключей сохранен (${apiKeys.size} ключей)")
    }
    
    // Загрузка списка API ключей Gemini
    fun getGeminiApiKeys(): List<String> {
        val keysJson = prefs.getString("gemini_api_keys", null)
        return if (keysJson != null) {
            try {
                val jsonArray = JSONArray(keysJson)
                val keys = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    keys.add(jsonArray.getString(i))
                }
                keys
            } catch (e: Exception) {
                Log.e("PreferencesManager", "Ошибка парсинга списка ключей: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    
    // Сохранение текущего индекса активного ключа
    fun saveCurrentGeminiKeyIndex(index: Int) {
        prefs.edit().putInt("current_gemini_key_index", index).apply()
        Log.d("PreferencesManager", "Текущий индекс Gemini ключа: $index")
    }
    
    // Получение текущего индекса активного ключа
    fun getCurrentGeminiKeyIndex(): Int {
        return prefs.getInt("current_gemini_key_index", 0)
    }
    
    // Получение текущего активного API ключа с автоматической ротацией
    fun getCurrentGeminiApiKey(): String {
        val keys = getGeminiApiKeys()
        if (keys.isEmpty()) {
            Log.w("PreferencesManager", "Список Gemini API ключей пуст")
            return ""
        }
        
        val currentIndex = getCurrentGeminiKeyIndex()
        if (currentIndex >= keys.size) {
            // Сброс индекса если он вышел за границы
            saveCurrentGeminiKeyIndex(0)
            return keys[0]
        }
        
        return keys[currentIndex]
    }
    
    // Переключение на следующий API ключ
    fun switchToNextGeminiKey(): String {
        val keys = getGeminiApiKeys()
        if (keys.isEmpty()) {
            Log.w("PreferencesManager", "Невозможно переключить ключ - список пуст")
            return ""
        }
        
        val currentIndex = getCurrentGeminiKeyIndex()
        val nextIndex = (currentIndex + 1) % keys.size
        
        saveCurrentGeminiKeyIndex(nextIndex)
        val nextKey = keys[nextIndex]
        
        Log.d("PreferencesManager", "🔄 Переключение на следующий Gemini ключ: ${currentIndex + 1} -> ${nextIndex + 1} из ${keys.size}")
        return nextKey
    }
    
    // Проверка, настроены ли Gemini ключи
    fun isGeminiKeysConfigured(): Boolean {
        return getGeminiApiKeys().isNotEmpty()
    }
    
    // Получение количества доступных ключей
    fun getGeminiKeysCount(): Int {
        return getGeminiApiKeys().size
    }
    
    // ==================== LEGACY МЕТОДЫ (ОБРАТНАЯ СОВМЕСТИМОСТЬ) ====================
    
    // Сохранение API ключа OpenAI
    fun saveOpenAIApiKey(apiKey: String) {
        prefs.edit().putString("openai_api_key", apiKey).apply()
        Log.d("PreferencesManager", "OpenAI API ключ сохранен (длина: ${apiKey.length})")
    }
    
    // Загрузка API ключа OpenAI
    fun getOpenAIApiKey(): String {
        return prefs.getString("openai_api_key", "") ?: ""
    }
    
    // Сохранение API ключа Gemini (legacy - для обратной совместимости)
    fun saveGeminiApiKey(apiKey: String) {
        if (apiKey.isNotEmpty()) {
            saveGeminiApiKeys(listOf(apiKey))
            saveCurrentGeminiKeyIndex(0)
        }
        Log.d("PreferencesManager", "Gemini API ключ сохранен через legacy метод (длина: ${apiKey.length})")
    }
    
    // Загрузка API ключа Gemini (legacy - для обратной совместимости)
    fun getGeminiApiKey(): String {
        // Сначала проверяем новый формат
        val keys = getGeminiApiKeys()
        if (keys.isNotEmpty()) {
            return getCurrentGeminiApiKey()
        }
        
        // Если новый формат пустой, проверяем старый
        val legacyKey = prefs.getString("gemini_api_key", "") ?: ""
        if (legacyKey.isNotEmpty()) {
            // Автоматически конвертируем в новый формат
            saveGeminiApiKeys(listOf(legacyKey))
            saveCurrentGeminiKeyIndex(0)
            // Очищаем старый ключ
            prefs.edit().remove("gemini_api_key").apply()
            Log.d("PreferencesManager", "Legacy Gemini ключ конвертирован в новый формат")
            return legacyKey
        }
        
        return ""
    }
    
    // ==================== OPENROUTER API НАСТРОЙКИ ====================
    
    // Сохранение OpenRouter API ключа
    fun saveOpenRouterApiKey(apiKey: String) {
        prefs.edit().putString("openrouter_api_key", apiKey).apply()
        Log.d("PreferencesManager", "OpenRouter API ключ сохранен (длина: ${apiKey.length})")
    }
    
    // Загрузка OpenRouter API ключа
    fun getOpenRouterApiKey(): String {
        return prefs.getString("openrouter_api_key", "") ?: ""
    }
    
    // Сохранение выбранной модели OpenRouter
    fun saveOpenRouterModel(model: OpenRouterModel) {
        prefs.edit().putString("openrouter_model", model.name).apply()
        Log.d("PreferencesManager", "OpenRouter модель установлена: ${model.displayName}")
    }
    
    // Загрузка выбранной модели OpenRouter
    fun getOpenRouterModel(): OpenRouterModel {
        val modelName = prefs.getString("openrouter_model", OpenRouterModel.GEMINI_25_FLASH_LITE.name)
        return try {
            OpenRouterModel.valueOf(modelName ?: OpenRouterModel.GEMINI_25_FLASH_LITE.name)
        } catch (e: IllegalArgumentException) {
            OpenRouterModel.GEMINI_25_FLASH_LITE // По умолчанию самая дешевая
        }
    }
    
    // Проверка, настроен ли OpenRouter
    fun isOpenRouterConfigured(): Boolean {
        val apiKey = getOpenRouterApiKey()
        return apiKey.isNotEmpty() && apiKey.startsWith("sk-or-")
    }
    
    // Сохранение AI провайдера
    fun saveAIProvider(provider: AIProvider) {
        prefs.edit().putString("ai_provider", provider.name).apply()
        Log.d("PreferencesManager", "AI провайдер установлен: $provider")
    }
    
    // Загрузка AI провайдера
    fun getAIProvider(): AIProvider {
        val providerName = prefs.getString("ai_provider", AIProvider.OPENROUTER.name) ?: AIProvider.OPENROUTER.name
        return try {
            AIProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            AIProvider.OPENROUTER // По умолчанию OpenRouter
        }
    }
    
    // Проверка, настроен ли OpenAI
    fun isOpenAIConfigured(): Boolean {
        val apiKey = getOpenAIApiKey()
        return apiKey.isNotEmpty() && apiKey.startsWith("sk-")
    }
    
    // Проверка, настроен ли Gemini
    fun isGeminiConfigured(): Boolean {
        val keys = getGeminiApiKeys()
        return keys.isNotEmpty() && keys.any { it.isNotEmpty() && it.length > 10 }
    }
    
    // Проверка, настроен ли текущий AI провайдер
    fun isAIConfigured(): Boolean {
        return when (getAIProvider()) {
            AIProvider.OPENAI -> isOpenAIConfigured()
            AIProvider.GEMINI -> isGeminiConfigured() // Устаревший, но оставлен для совместимости
            AIProvider.OPENROUTER -> isOpenRouterConfigured()
        }
    }
    
    // Включение/отключение OpenAI распознавания
    fun saveOpenAIEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("openai_enabled", enabled).apply()
        Log.d("PreferencesManager", "OpenAI распознавание: ${if (enabled) "включено" else "отключено"}")
    }
    
    // Проверка, включено ли OpenAI распознавание
    fun isOpenAIEnabled(): Boolean {
        return prefs.getBoolean("openai_enabled", false) // По умолчанию отключено
    }
    
    // Сохранение режима распознавания (OpenCV / OpenAI / Hybrid)
    fun saveRecognitionMode(mode: RecognitionMode) {
        prefs.edit().putString("recognition_mode", mode.name).apply()
        Log.d("PreferencesManager", "Режим распознавания: $mode")
    }
    
    // Загрузка режима распознавания
    fun getRecognitionMode(): RecognitionMode {
        val modeName = prefs.getString("recognition_mode", RecognitionMode.OPENCV.name)
        return try {
            RecognitionMode.valueOf(modeName!!)
        } catch (e: Exception) {
            RecognitionMode.OPENCV // По умолчанию OpenCV
        }
    }
    
    enum class RecognitionMode {
        OPENCV,      // Только OpenCV
        OPENAI,      // Только OpenAI (устаревший, оставлен для совместимости)
        GEMINI,      // Только Gemini (УСТАРЕВШИЙ - заменен на OpenRouter)
        OPENROUTER,  // OpenRouter с выбором модели
        HYBRID       // OpenCV + AI для проверки
    }
    
    enum class AIProvider {
        OPENAI,      // OpenAI GPT-4o Vision (устаревший)
        GEMINI,      // Google Gemini 2.0 Flash-Lite (УСТАРЕВШИЙ - заменен на OpenRouter)
        OPENROUTER   // OpenRouter - универсальный доступ к AI моделям
    }
    
    enum class OpenRouterModel(val modelId: String, val displayName: String) {
        CLAUDE_45("anthropic/claude-3.5-sonnet", "Claude 4.5"),
        CHATGPT_5("openai/gpt-4o", "ChatGPT 5"),
        GEMINI_25_FLASH_LITE("google/gemini-2.0-flash-exp:free", "Gemini 2.5 Flash-Lite")
    }
    
    // ==================== НАСТРОЙКИ ПРОКСИ ====================
    
    // Сохранение настроек прокси
    fun saveProxySettings(host: String, port: Int, username: String, password: String) {
        prefs.edit().apply {
            putString("proxy_host", host)
            putInt("proxy_port", port)
            putString("proxy_username", username)
            putString("proxy_password", password)
        }.apply()
        Log.d("PreferencesManager", "Настройки прокси сохранены: $host:$port")
    }
    
    // Получение хоста прокси
    fun getProxyHost(): String {
        return prefs.getString("proxy_host", "200.10.39.135") ?: "200.10.39.135"
    }
    
    // Получение порта прокси
    fun getProxyPort(): Int {
        return prefs.getInt("proxy_port", 8000)
    }
    
    // Получение логина прокси
    fun getProxyUsername(): String {
        return prefs.getString("proxy_username", "ZpUR2q") ?: "ZpUR2q"
    }
    
    // Получение пароля прокси
    fun getProxyPassword(): String {
        return prefs.getString("proxy_password", "Hd1foV") ?: "Hd1foV"
    }
    
    // Включение/отключение прокси
    fun saveProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("proxy_enabled", enabled).apply()
        Log.d("PreferencesManager", "Прокси: ${if (enabled) "включен" else "отключен"}")
    }
    
    // Проверка, включен ли прокси
    fun isProxyEnabled(): Boolean {
        return prefs.getBoolean("proxy_enabled", true) // По умолчанию включен
    }
    
    // Сохранение результата последнего теста прокси
    fun saveLastProxyTestResult(success: Boolean, message: String, timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().apply {
            putBoolean("proxy_last_test_success", success)
            putString("proxy_last_test_message", message)
            putLong("proxy_last_test_time", timestamp)
        }.apply()
        Log.d("PreferencesManager", "Результат теста прокси сохранен: ${if (success) "успех" else "ошибка"}")
    }
    
    // Получение результата последнего теста прокси
    fun getLastProxyTestResult(): Triple<Boolean, String, Long> {
        val success = prefs.getBoolean("proxy_last_test_success", false)
        val message = prefs.getString("proxy_last_test_message", "") ?: ""
        val timestamp = prefs.getLong("proxy_last_test_time", 0L)
        return Triple(success, message, timestamp)
    }
    
    // Вспомогательные методы для совместимости с ProxyManager
    fun getProxyConfigSummary(): String {
        val host = getProxyHost()
        val port = getProxyPort()
        val username = getProxyUsername()
        val enabled = isProxyEnabled()
        
        return if (enabled) {
            "$username@$host:$port"
        } else {
            "Отключен"
        }
    }
    
    // === МЕТОДЫ ДЛЯ ОДИНОЧНОГО РЕЖИМА ===
    
    /**
     * Сохранение области для одиночного режима
     */
    fun saveSingleModeAreaRect(areaType: com.example.diceautobet.models.SingleModeAreaType, rect: Rect) {
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        val absoluteRect = Rect(left, top, right, bottom)
        
        Log.d("PreferencesManager", "Сохраняем область одиночного режима: $areaType")
        Log.d("PreferencesManager", "Координаты: $absoluteRect")
        
        with(prefs.edit()) {
            putInt("single_${areaType.name}_left", absoluteRect.left)
            putInt("single_${areaType.name}_top", absoluteRect.top)
            putInt("single_${areaType.name}_right", absoluteRect.right)
            putInt("single_${areaType.name}_bottom", absoluteRect.bottom)
            apply()
        }
        
        Log.d("PreferencesManager", "Область одиночного режима сохранена: ${areaType.displayName}")
    }
    
    /**
     * Загрузка области для одиночного режима
     */
    fun getSingleModeAreaRect(areaType: com.example.diceautobet.models.SingleModeAreaType): Rect? {
        val left = prefs.getInt("single_${areaType.name}_left", -1)
        val top = prefs.getInt("single_${areaType.name}_top", -1)
        val right = prefs.getInt("single_${areaType.name}_right", -1)
        val bottom = prefs.getInt("single_${areaType.name}_bottom", -1)
        
        if (left == -1 || top == -1 || right == -1 || bottom == -1) {
            Log.d("PreferencesManager", "Область одиночного режима не найдена: ${areaType.displayName}")
            return null
        }
        
        val rect = Rect(left, top, right, bottom)
        Log.d("PreferencesManager", "Загружена область одиночного режима ${areaType.displayName}: $rect")
        return rect
    }
    
    /**
     * Сохранение области для одиночного режима (ScreenArea)
     */
    fun saveSingleModeArea(areaType: com.example.diceautobet.models.SingleModeAreaType, screenArea: ScreenArea) {
        val rect = Rect(screenArea.rect.left, screenArea.rect.top, screenArea.rect.right, screenArea.rect.bottom)
        saveSingleModeAreaRect(areaType, rect)
    }
    
    /**
     * Загрузка области для одиночного режима (ScreenArea)
     */
    fun getSingleModeArea(areaType: com.example.diceautobet.models.SingleModeAreaType): ScreenArea? {
        val rect = getSingleModeAreaRect(areaType) 
        return rect?.let { 
            ScreenArea(
                name = areaType.displayName,
                rect = it
            )
        }
    }
    
    /**
     * Удаление области одиночного режима
     */
    fun removeSingleModeArea(areaType: com.example.diceautobet.models.SingleModeAreaType) {
        Log.d("PreferencesManager", "Удаляем область одиночного режима: ${areaType.displayName}")
        
        with(prefs.edit()) {
            remove("single_${areaType.name}_left")
            remove("single_${areaType.name}_top")
            remove("single_${areaType.name}_right")
            remove("single_${areaType.name}_bottom")
            apply()
        }
    }
    
    /**
     * Проверка, настроены ли все области одиночного режима
     */
    fun areAllSingleModeAreasConfigured(): Boolean {
        val areas = com.example.diceautobet.models.SingleModeAreaType.values()
        var configuredCount = 0
        
        areas.forEach { areaType ->
            if (getSingleModeAreaRect(areaType) != null) {
                configuredCount++
            }
        }
        
        val allConfigured = configuredCount == areas.size
        Log.d("PreferencesManager", "Области одиночного режима: $configuredCount из ${areas.size} настроены (все: $allConfigured)")
        
        return allConfigured
    }
    
    /**
     * Сохранение настроек одиночного режима
     */
    fun saveSingleModeSettings(settings: com.example.diceautobet.models.SingleModeSettings) {
        Log.d("PreferencesManager", "Сохраняем настройки одиночного режима: $settings")
        
        with(prefs.edit()) {
            // Основные настройки
            putInt("single_base_bet", settings.baseBet)
            putString("single_preferred_color", settings.preferredColor.name)
            putInt("single_max_bet", settings.maxBet)
            
            // Настройки стратегии
            putInt("single_max_losses_before_color_switch", settings.maxLossesBeforeColorSwitch)
            putBoolean("single_enable_color_switching", settings.enableColorSwitching)
            
            // Настройки безопасности
            putBoolean("single_enable_max_bet_limit", settings.enableMaxBetLimit)
            putBoolean("single_enable_profit_stop", settings.enableProfitStop)
            putInt("single_target_profit", settings.targetProfit)
            
            // Настройки производительности
            putLong("single_detection_delay", settings.detectionDelay)
            putLong("single_click_delay", settings.clickDelay)
            putLong("single_analysis_timeout", settings.analysisTimeout)
            
            // Настройки отладки
            putBoolean("single_enable_detailed_logging", settings.enableDetailedLogging)
            putBoolean("single_save_debug_screenshots", settings.saveDebugScreenshots)
            putBoolean("single_enable_test_mode", settings.enableTestMode)
            
            apply()
        }
        
        Log.d("PreferencesManager", "Настройки одиночного режима сохранены")
    }
    
    /**
     * Загрузка настроек одиночного режима
     */
    fun getSingleModeSettings(): com.example.diceautobet.models.SingleModeSettings {
        return try {
            val preferredColorName = prefs.getString("single_preferred_color", "BLUE") ?: "BLUE"
            val preferredColor = try {
                com.example.diceautobet.models.BetColor.valueOf(preferredColorName)
            } catch (e: IllegalArgumentException) {
                Log.w("PreferencesManager", "Неверный цвет: $preferredColorName, используем BLUE")
                com.example.diceautobet.models.BetColor.BLUE
            }
            
            val settings = com.example.diceautobet.models.SingleModeSettings(
                // Основные настройки
                baseBet = prefs.getInt("single_base_bet", 20),  // Изменено на 20 по умолчанию
                preferredColor = preferredColor,
                maxBet = prefs.getInt("single_max_bet", 30000),
                
                // Настройки стратегии
                maxLossesBeforeColorSwitch = prefs.getInt("single_max_losses_before_color_switch", 2),
                enableColorSwitching = prefs.getBoolean("single_enable_color_switching", true),
                
                // Настройки безопасности
                enableMaxBetLimit = prefs.getBoolean("single_enable_max_bet_limit", true),
                enableProfitStop = prefs.getBoolean("single_enable_profit_stop", false),
                targetProfit = prefs.getInt("single_target_profit", 1000),
                
                // Настройки производительности
                detectionDelay = prefs.getLong("single_detection_delay", 1000L),
                clickDelay = prefs.getLong("single_click_delay", 500L),
                analysisTimeout = prefs.getLong("single_analysis_timeout", 10000L),
                
                // Настройки отладки
                enableDetailedLogging = prefs.getBoolean("single_enable_detailed_logging", false),
                saveDebugScreenshots = prefs.getBoolean("single_save_debug_screenshots", false),
                enableTestMode = prefs.getBoolean("single_enable_test_mode", false)
            )
            
            Log.d("PreferencesManager", "Настройки одиночного режима загружены: базовая ставка=${settings.baseBet}, цвет=${settings.preferredColor}")
            settings
            
        } catch (e: Exception) {
            Log.e("PreferencesManager", "Ошибка загрузки настроек одиночного режима, используем по умолчанию", e)
            com.example.diceautobet.models.SingleModeSettings()
        }
    }
    
    /**
     * Сброс всех настроек одиночного режима
     */
    fun resetSingleModeSettings() {
        Log.d("PreferencesManager", "Сброс всех настроек одиночного режима")
        
        val editor = prefs.edit()
        
        // Удаляем все настройки одиночного режима
        val allKeys = prefs.all.keys
        allKeys.filter { it.startsWith("single_") }.forEach { key ->
            editor.remove(key)
        }
        
        editor.apply()
        Log.d("PreferencesManager", "Настройки одиночного режима сброшены")
    }
    
    /**
     * Получение статистики одиночного режима
     */
    fun getSingleModeStatistics(): Map<String, Any> {
        return mapOf(
            "areasConfigured" to areAllSingleModeAreasConfigured(),
            "settingsExists" to prefs.contains("single_base_bet"),
            "totalAreas" to com.example.diceautobet.models.SingleModeAreaType.values().size,
            "configuredAreas" to com.example.diceautobet.models.SingleModeAreaType.values().count { getSingleModeArea(it) != null },
            "baseBet" to prefs.getInt("single_base_bet", 100),
            "maxBet" to prefs.getInt("single_max_bet", 30000),
            "preferredColor" to (prefs.getString("single_preferred_color", "BLUE") ?: "BLUE")
        )
    }
    
    /**
     * Сохранение режима игры
     */
    fun saveGameMode(mode: String) {
        prefs.edit().putString("game_mode", mode).apply()
        Log.d("PreferencesManager", "Сохранён режим игры: $mode")
    }
    
    /**
     * Получение режима игры
     */
    fun getGameMode(): String {
        return prefs.getString("game_mode", "dual") ?: "dual"
    }
}