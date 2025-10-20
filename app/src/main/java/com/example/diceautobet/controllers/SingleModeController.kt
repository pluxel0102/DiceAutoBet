package com.example.diceautobet.controllers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import com.example.diceautobet.game.ClickManager
import com.example.diceautobet.game.ScreenCaptureManager
import com.example.diceautobet.models.*
import com.example.diceautobet.recognition.HybridDiceRecognizer
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.FileLogger
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

// Extension функции для автоматического дублирования в FileLogger
private fun logD(tag: String, message: String) {
    Log.d(tag, message)
    FileLogger.d(tag, message)
}

private fun logI(tag: String, message: String) {
    Log.i(tag, message)
    FileLogger.i(tag, message)
}

private fun logW(tag: String, message: String) {
    Log.w(tag, message)
    FileLogger.w(tag, message)
}

private fun logE(tag: String, message: String) {
    Log.e(tag, message)
    FileLogger.e(tag, message)
}

private fun logE(tag: String, message: String, throwable: Throwable) {
    Log.e(tag, message, throwable)
    FileLogger.e(tag, "$message: ${throwable.message}")
}

/**
 * Контроллер одиночного режима игры в нарды
 *
 * Логика одиночного режима:
 * 1. Старт: выбираем предпочитаемый цвет и начальную ставку
 * 2. Ждем результат игры через детекцию области кубиков
 * 3. При проигрыше: удваиваем ставку (нажимаем цвет + N раз X2)
 * 4. После 2 проигрышей на одном цвете: переходим на другой цвет + продолжаем удваивать
 * 5. При выигрыше: возвращаемся к базовой ставке
 * 6. Остановка при достижении максимальной ставки (200,000)
 */
class SingleModeController(
    private val context: Context,
    private val takeScreenshot: (callback: (Bitmap?) -> Unit) -> Unit,
    private val performClick: (x: Int, y: Int, callback: (Boolean) -> Unit) -> Unit,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "SingleModeController"

        // Константы детекции
        private const val DETECTION_INTERVAL_MS = 50L
        private const val STABLE_HASH_DURATION_MS = 1500L
        private const val MAX_DETECTION_TIME_MS = 300000L // Увеличено до 5 минут (300 секунд)
        private const val GAME_RESTART_TIMEOUT_MS = 60000L

        // Константы кликов
        private const val CLICK_DELAY_MS = 500L
        private const val BETWEEN_CLICKS_DELAY_MS = 300L
    }

    private var isActive = false
    private var gameJob: Job? = null
    private var detectionJob: Job? = null

    // Игровое состояние
    private var gameState = SingleModeGameState()
    private var settings = SingleModeSettings()

    // Области кликов
    private val areas = mutableMapOf<SingleModeAreaType, Rect>()

    // Система детекции изменений
    private var lastDiceAreaHash: String? = null
    private var stableHashStartTime: Long = 0
    private var isInStablePhase = false
    
    // Счетчик неудачных попыток детекции
    private var consecutiveFailedDetections = 0
    private val maxFailedDetections = 3 // Максимум 3 неудачных попытки подряд

    // Коллбэки для UI
    var onGameStateChanged: ((SingleModeGameState) -> Unit)? = null
    var onGameStopped: ((String) -> Unit)? = null
    var onError: ((String, Throwable?) -> Unit)? = null
    var onDebugMessage: ((String) -> Unit)? = null

    /**
     * Инициализация контроллера
     */
    fun initialize() {
        try {
            Log.d(TAG, "Инициализация SingleModeController")
            FileLogger.d(TAG, "Инициализация SingleModeController")

            // Загружаем настройки
            loadSettings()

            // Загружаем области
            loadAreas()

            // Инициализируем состояние игры
            gameState = gameState.copy(
                baseBet = settings.baseBet,
                currentBet = settings.baseBet,
                currentColor = settings.preferredColor,
                maxBet = settings.maxBet,
                maxLossesBeforeColorSwitch = settings.maxLossesBeforeColorSwitch
            )

            Log.d(TAG, "SingleModeController инициализирован с настройками: $settings")
            FileLogger.i(TAG, "SingleModeController инициализирован: baseBet=${settings.baseBet}, цвет=${settings.preferredColor}")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации", e)
            onError?.invoke("Ошибка инициализации: ${e.message}", e)
        }
    }

    /**
     * Запуск одиночного режима
     */
    fun startGame() {
        if (isActive) {
            Log.w(TAG, "Игра уже запущена")
            return
        }

        try {
            Log.d(TAG, "Запуск одиночного режима")

            isActive = true
            consecutiveFailedDetections = 0 // Сбрасываем счетчик неудачных попыток
            gameState = gameState.copy(
                isGameActive = true,
                gameStartTime = System.currentTimeMillis()
            )

            onGameStateChanged?.invoke(gameState)
            onDebugMessage?.invoke("🎮 Одиночный режим запущен")

            // Запускаем основной игровой цикл
            gameJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    runGameLoop()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Игровой цикл отменен")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в игровом цикле", e)
                    onError?.invoke("Ошибка в игре: ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска игры", e)
            onError?.invoke("Ошибка запуска: ${e.message}", e)
            stopGame()
        }
    }

    /**
     * Остановка игры
     */
    fun stopGame() {
        try {
            Log.d(TAG, "Остановка одиночного режима")

            isActive = false
            gameJob?.cancel()
            detectionJob?.cancel()

            val duration = gameState.getGameDuration()
            val durationText = formatDuration(duration)

            gameState = gameState.copy(
                isGameActive = false,
                isPaused = false
            )

            onGameStateChanged?.invoke(gameState)
            onGameStopped?.invoke("Игра остановлена. Длительность: $durationText")

            Log.d(TAG, "Игра остановлена. Статистика: ${gameState.totalGames} игр, ${gameState.totalWins} побед, ${gameState.getWinRate()}% побед")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки игры", e)
        }
    }

    /**
     * Пауза/возобновление игры
     */
    fun togglePause() {
        try {
            val newPausedState = !gameState.isPaused
            gameState = gameState.copy(isPaused = newPausedState)

            Log.d(TAG, "Игра ${if (newPausedState) "поставлена на паузу" else "возобновлена"}")
            onGameStateChanged?.invoke(gameState)
            onDebugMessage?.invoke(if (newPausedState) "⏸️ Пауза" else "▶️ Возобновление")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка переключения паузы", e)
        }
    }

    /**
     * Основной игровой цикл
     */
    private suspend fun runGameLoop() {
        while (isActive) {
            try {
                // Проверяем паузу
                if (gameState.isPaused) {
                    delay(1000)
                    continue
                }

                // Проверяем условие остановки
                if (gameState.shouldStopGame()) {
                    withContext(Dispatchers.Main) {
                        onDebugMessage?.invoke("🛑 Достигнута максимальная ставка ${gameState.maxBet}")
                        stopGame()
                    }
                    break
                }

                Log.d(TAG, "Начинаем новый раунд игры")
                logD(TAG, "🎲 Начинаем новый раунд: ставка=${gameState.currentBet}, цвет=${gameState.currentColor.displayName}")
                onDebugMessage?.invoke("🎯 Делаем ставку: ${gameState.currentColor.displayName} ${gameState.currentBet}")

                // Этап 1: Делаем ставку
                logD(TAG, "Этап 1: Размещение ставки")
                placeBet()

                // Этап 2: Ждем результат
                logD(TAG, "Этап 2: Ожидание результата игры")
                val result = waitForGameResult()

                if (result != null) {
                    // Этап 3: Обрабатываем результат
                    consecutiveFailedDetections = 0 // Сбрасываем счетчик при успехе
                    logI(TAG, "✅ Результат получен: левый=${result.leftDots}, правый=${result.rightDots}")
                    processGameResult(result)
                } else {
                    consecutiveFailedDetections++
                    Log.w(TAG, "Не удалось получить результат игры, пропускаем раунд (попытка $consecutiveFailedDetections/$maxFailedDetections)")
                    logW(TAG, "❌ Таймаут детекции! Пропускаем раунд ($consecutiveFailedDetections/$maxFailedDetections)")
                    onDebugMessage?.invoke("⚠️ Результат не определен, попытка $consecutiveFailedDetections/$maxFailedDetections")
                    
                    if (consecutiveFailedDetections >= maxFailedDetections) {
                        Log.e(TAG, "🚨 Превышено максимальное количество неудачных детекций ($maxFailedDetections), останавливаем игру")
                        onDebugMessage?.invoke("🚨 Остановка: слишком много неудачных детекций")
                        withContext(Dispatchers.Main) {
                            stopGame()
                        }
                        break
                    }
                    
                    delay(5000) // Пауза перед следующей попыткой
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в игровом цикле", e)
                onError?.invoke("Ошибка в раунде: ${e.message}", e)
                delay(5000) // Пауза перед повтором при ошибке
            }
        }
    }

    /**
     * Размещение ставки
     */
    private suspend fun placeBet() {
        try {
            logI(TAG, "💰 placeBet() START: ставка=${gameState.currentBet}, цвет=${gameState.currentColor}")
            
            // ПРОВЕРКА ПАУЗЫ перед размещением ставки
            if (gameState.isPaused) {
                Log.d(TAG, "🛑 Размещение ставки отложено - игра на паузе")
                logW(TAG, "🛑 Пауза - ставка отложена")
                return
            }

            Log.d(TAG, "Размещаем ставку: ${gameState.currentColor.displayName} ${gameState.currentBet}")

            // НОВАЯ ЛОГИКА: Всегда используем кнопку 10 + x2 для создания любой ставки
            if (gameState.baseBet == 20) {
                // Специальная логика для базовой ставки 20
                val multiplierClicks = calculateMultiplierClicks(gameState.currentBet)
                
                Log.d(TAG, "Используем кнопку 10 + x2 ($multiplierClicks раз) для ставки ${gameState.currentBet}")
                
                // ПРОВЕРКА ПАУЗЫ перед кликом базовой кнопки 10
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Пауза во время размещения базовой кнопки 10")
                    return
                }
                
                // Всегда нажимаем кнопку 10
                if (areas.containsKey(SingleModeAreaType.BET_10)) {
                    clickArea(SingleModeAreaType.BET_10)
                    delay(CLICK_DELAY_MS)
                } else {
                    Log.e(TAG, "❌ Область кнопки BET_10 не настроена!")
                    throw IllegalStateException("Область кнопки BET_10 не настроена")
                }
                
                // ПРОВЕРКА ПАУЗЫ перед кликом цвета
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Пауза во время выбора цвета")
                    return
                }

                // Нажимаем цвет ставки
                val colorArea = when (gameState.currentColor) {
                    BetColor.BLUE -> SingleModeAreaType.BET_BLUE
                    BetColor.RED -> SingleModeAreaType.BET_RED
                }

                if (areas.containsKey(colorArea)) {
                    clickArea(colorArea)
                    delay(CLICK_DELAY_MS)
                } else {
                    Log.e(TAG, "❌ Область цвета $colorArea не настроена!")
                    throw IllegalStateException("Область цвета не настроена")
                }

                // ПРОВЕРКА ПАУЗЫ перед умножением
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Пауза во время умножения ставки")
                    return
                }

                // Нажимаем кнопку x2 нужное количество раз
                if (multiplierClicks > 0) {
                    if (!areas.containsKey(SingleModeAreaType.DOUBLE_BUTTON)) {
                        Log.e(TAG, "❌ Область кнопки удвоения не настроена!")
                        throw IllegalStateException("Область кнопки удвоения не настроена")
                    }
                    
                    Log.d(TAG, "Нажимаем X2 $multiplierClicks раз для получения ставки ${gameState.currentBet}")

                    repeat(multiplierClicks) { i ->
                        // ПРОВЕРКА ПАУЗЫ перед каждым кликом удвоения
                        if (gameState.isPaused) {
                            Log.d(TAG, "🛑 Пауза во время клика удвоения ${i + 1}")
                            return
                        }
                        
                        clickArea(SingleModeAreaType.DOUBLE_BUTTON)
                        Log.d(TAG, "  ✅ Клик x2 ${i + 1}/$multiplierClicks выполнен")
                        
                        if (i < multiplierClicks - 1) {
                            delay(BETWEEN_CLICKS_DELAY_MS)
                        }
                    }
                }
                
            } else {
                // СТАРАЯ ЛОГИКА: для других базовых ставок
                // Если это первая ставка или мы возвращаемся к базовой ставке
                if (gameState.currentBet == gameState.baseBet) {
                    // ПРОВЕРКА ПАУЗЫ перед кликом базовой ставки
                    if (gameState.isPaused) {
                        Log.d(TAG, "🛑 Пауза во время размещения базовой ставки")
                        return
                    }
                    
                    // Нажимаем кнопку базовой ставки
                    val baseBetArea = SingleModeAreaType.getBetAreaByAmount(gameState.baseBet)
                    if (baseBetArea != null && areas.containsKey(baseBetArea)) {
                        clickArea(baseBetArea)
                        delay(CLICK_DELAY_MS)
                    }
                }

                // ПРОВЕРКА ПАУЗЫ перед кликом цвета
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Пауза во время выбора цвета")
                    return
                }

                // Нажимаем цвет ставки
                val colorArea = when (gameState.currentColor) {
                    BetColor.BLUE -> SingleModeAreaType.BET_BLUE
                    BetColor.RED -> SingleModeAreaType.BET_RED
                }

                if (areas.containsKey(colorArea)) {
                    clickArea(colorArea)
                    delay(CLICK_DELAY_MS)
                }

                // ПРОВЕРКА ПАУЗЫ перед удвоением
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Пауза во время удвоения ставки")
                    return
                }

                // Нажимаем кнопку удвоения нужное количество раз (СТАРАЯ ЛОГИКА)
                val doublingClicks = gameState.getDoublingClicksNeeded()
                if (doublingClicks > 0 && areas.containsKey(SingleModeAreaType.DOUBLE_BUTTON)) {
                    Log.d(TAG, "Нажимаем X2 $doublingClicks раз для ставки ${gameState.currentBet}")

                    repeat(doublingClicks) {
                        // ПРОВЕРКА ПАУЗЫ перед каждым кликом удвоения
                        if (gameState.isPaused) {
                            Log.d(TAG, "🛑 Пауза во время клика удвоения")
                            return
                        }
                        
                        clickArea(SingleModeAreaType.DOUBLE_BUTTON)
                        delay(BETWEEN_CLICKS_DELAY_MS)
                    }
                }
            }

            onDebugMessage?.invoke("✅ Ставка размещена: ${gameState.currentColor.displayName} ${gameState.currentBet}")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка размещения ставки", e)
            throw e
        }
    }

    /**
     * Вычисляет количество нажатий кнопки x2 для получения нужной ставки из базы 10
     * Например: 20 = 10 * 2^1 → 1 клик, 40 = 10 * 2^2 → 2 клика, и т.д.
     */
    private fun calculateMultiplierClicks(targetBet: Int): Int {
        if (targetBet < 10) return 0
        
        // Находим степень двойки: targetBet = 10 * 2^n
        var current = 10
        var clicks = 0
        
        while (current < targetBet) {
            current *= 2
            clicks++
        }
        
        // Проверяем, что получилась точно нужная сумма
        if (current != targetBet) {
            Log.w(TAG, "⚠️ Ставка $targetBet не может быть получена из 10 через удвоения. Ближайшая: $current")
        }
        
        Log.d(TAG, "💰 Для ставки $targetBet нужно $clicks нажатий x2 (10 → $current)")
        return clicks
    }

    /**
     * Ожидание результата игры
     */
    private suspend fun waitForGameResult(): com.example.diceautobet.models.DiceResult? {
        return try {
            Log.d(TAG, "Ожидаем результат игры...")
            onDebugMessage?.invoke("⏳ Ожидание результата...")

            Log.d(TAG, "🔍 Проверяем наличие области кубиков...")
            Log.d(TAG, "🔍 Всего загружено областей: ${areas.size}")
            areas.forEach { (areaType, rect) ->
                Log.d(TAG, "🔍   ${areaType.displayName} -> $rect")
            }

            val diceArea = areas[SingleModeAreaType.DICE_AREA]
            if (diceArea == null) {
                Log.e(TAG, "🚨 Область кубиков не настроена")
                Log.e(TAG, "🚨 Ключ DICE_AREA: ${SingleModeAreaType.DICE_AREA}")
                Log.e(TAG, "🚨 Доступные ключи: ${areas.keys}")
                return null
            }

            Log.d(TAG, "✅ Область кубиков найдена: $diceArea")
            detectGameResult(diceArea)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка ожидания результата", e)
            null
        }
    }

    /**
     * Детекция результата игры через анализ изменений
     */
    private suspend fun detectGameResult(diceArea: Rect): com.example.diceautobet.models.DiceResult? {
        val startTime = System.currentTimeMillis()
        lastDiceAreaHash = null
        stableHashStartTime = 0
        isInStablePhase = false

        while (System.currentTimeMillis() - startTime < MAX_DETECTION_TIME_MS) {
            try {
                // ПРОВЕРКА ПАУЗЫ: если игра на паузе, ждем
                if (gameState.isPaused) {
                    Log.d(TAG, "🛑 Детекция на паузе, ожидание...")
                    delay(500)
                    continue
                }

                // Используем callback для получения скриншота
                var screenshot: Bitmap? = null
                var completed = false

                takeScreenshot { bitmap ->
                    screenshot = bitmap
                    completed = true
                }

                // Ждем завершения callback (простая блокирующая синхронизация)
                while (!completed && System.currentTimeMillis() - startTime < MAX_DETECTION_TIME_MS) {
                    // ПРОВЕРКА ПАУЗЫ во время ожидания скриншота
                    if (gameState.isPaused) {
                        Log.d(TAG, "🛑 Детекция на паузе во время ожидания скриншота")
                        delay(500)
                        break
                    }
                    delay(10)
                }

                // Если игра на паузе, не обрабатываем скриншот
                if (gameState.isPaused) {
                    continue
                }

                if (screenshot == null) {
                    delay(DETECTION_INTERVAL_MS)
                    continue
                }

                // Извлекаем область кубиков
                val diceAreaBitmap = Bitmap.createBitmap(
                    screenshot!!,
                    diceArea.left,
                    diceArea.top,
                    diceArea.width(),
                    diceArea.height()
                )

                // Вычисляем хеш области
                val currentHash = calculateBitmapHash(diceAreaBitmap)

                if (lastDiceAreaHash == null) {
                    lastDiceAreaHash = currentHash
                    delay(DETECTION_INTERVAL_MS)
                    continue
                }

                // Проверяем изменения
                if (currentHash != lastDiceAreaHash) {
                    // Область изменилась - сбрасываем стабильное состояние
                    isInStablePhase = false
                    stableHashStartTime = 0
                    lastDiceAreaHash = currentHash
                } else if (!isInStablePhase) {
                    // Область стабильна - начинаем отсчет
                    if (stableHashStartTime == 0L) {
                        stableHashStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stableHashStartTime >= STABLE_HASH_DURATION_MS) {
                        // Область стабильна достаточно долго - проверяем что это
                        isInStablePhase = true

                        // Проверяем, является ли это таймером (используем улучшенную детекцию)
                        if (isTimerInDiceArea(diceAreaBitmap)) {
                            Log.d(TAG, "🟢🔴 Обнаружен таймер в области кубиков, ждем кубики...")
                            onDebugMessage?.invoke("🟢🔴 Ожидание окончания таймера...")

                            // Сбрасываем состояние для ожидания следующих изменений
                            isInStablePhase = false
                            stableHashStartTime = 0
                            lastDiceAreaHash = null
                        } else {
                            // Стабильные кубики - анализируем результат
                            Log.d(TAG, "🎲 Область кубиков стабилизировалась, анализируем результат...")
                            onDebugMessage?.invoke("🔍 Анализ результата...")

                            val result = analyzeGameResult(diceAreaBitmap)
                            if (result != null) {
                                Log.d(TAG, "Результат определен: ${result}")
                                return result
                            } else {
                                // Если не удалось распознать - сбрасываем и ждем дальше
                                Log.w(TAG, "Не удалось распознать результат, ждем дальше...")
                                isInStablePhase = false
                                stableHashStartTime = 0
                                lastDiceAreaHash = null
                            }
                        }
                    }
                }

                diceAreaBitmap?.recycle()
                delay(DETECTION_INTERVAL_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка детекции результата", e)
                delay(DETECTION_INTERVAL_MS)
            }
        }

        Log.w(TAG, "Таймаут детекции результата")
        Log.w(TAG, "🔍 Диагностика таймаута:")
        Log.w(TAG, "   Время детекции: ${(System.currentTimeMillis() - startTime) / 1000}с из ${MAX_DETECTION_TIME_MS / 1000}с")
        Log.w(TAG, "   Последний хеш области: $lastDiceAreaHash")
        Log.w(TAG, "   Стабильная фаза: $isInStablePhase")
        Log.w(TAG, "   Время стабилизации: ${if (stableHashStartTime > 0) (System.currentTimeMillis() - stableHashStartTime) else 0}мс")
        return null
    }

    /**
     * Анализ результата игры через Gemini
     */
    private suspend fun analyzeGameResult(fullScreenshot: Bitmap): com.example.diceautobet.models.DiceResult? {
        return try {
            if (settings.enableTestMode) {
                // В тестовом режиме генерируем случайный результат
                val leftDots = (1..6).random()
                val rightDots = (1..6).random()

                Log.d(TAG, "Тестовый результат: левый=$leftDots, правый=$rightDots")
                
                // 🎯 ПОКАЗЫВАЕМ TOAST ДЛЯ ТЕСТОВОГО РЕЖИМА
                withContext(Dispatchers.Main) {
                    val resultText = "Результат (тест): $leftDots:$rightDots"
                    Toast.makeText(context, resultText, Toast.LENGTH_SHORT).show()
                }

                com.example.diceautobet.models.DiceResult(
                    leftDots = leftDots,
                    rightDots = rightDots,
                    confidence = 1.0f,
                    isDraw = leftDots == rightDots
                )
            } else {
                // Используем AI для анализа полного скриншота
                val aiProvider = preferencesManager.getAIProvider()
                val modelName = when (aiProvider) {
                    PreferencesManager.AIProvider.OPENROUTER -> preferencesManager.getOpenRouterModel().displayName
                    else -> "OpenCV"
                }
                
                Log.d(TAG, "Анализируем результат через AI ($modelName, полный скриншот)...")

                // ОТЛАДКА: Сохраняем полное изображение для просмотра
                try {
                    val context = preferencesManager.appContext
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "single_mode_full_${timestamp}.png"
                    val file = File(context.getExternalFilesDir("debug"), fileName)
                    file.parentFile?.mkdirs()

                    val outputStream = file.outputStream()
                    fullScreenshot.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()

                    Log.d(TAG, "🖼️ Полное изображение для AI сохранено: ${file.absolutePath}")
                    Log.d(TAG, "🖼️ Размер изображения: ${fullScreenshot.width}x${fullScreenshot.height}")
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось сохранить изображение для отладки", e)
                }

                // Используем HybridDiceRecognizer, который автоматически выберет нужный метод
                val recognizer = HybridDiceRecognizer(preferencesManager)

                val aiResult = recognizer.analyzeDice(fullScreenshot)
                if (aiResult != null) {
                    Log.d(TAG, "AI результат: левый=${aiResult.leftDots}, правый=${aiResult.rightDots}")
                    
                    // 🎯 ПОКАЗЫВАЕМ TOAST С РЕЗУЛЬТАТОМ ОТ НЕЙРОСЕТИ
                    withContext(Dispatchers.Main) {
                        val isDraw = aiResult.leftDots == aiResult.rightDots
                        val emoji = when {
                            isDraw -> "🟰"
                            aiResult.leftDots > aiResult.rightDots -> "🔵"
                            else -> "🔴"
                        }
                        val statusText = when {
                            isDraw -> " (Ничья)"
                            else -> ""
                        }
                        val resultText = "$emoji Результат: ${aiResult.leftDots}:${aiResult.rightDots}$statusText"
                        Toast.makeText(context, resultText, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "📢 Показано уведомление: $resultText")
                        FileLogger.i(TAG, "📢 Toast уведомление: $resultText")
                    }

                    // Конвертируем в формат DiceResult для одиночного режима
                    com.example.diceautobet.models.DiceResult(
                        leftDots = aiResult.leftDots,
                        rightDots = aiResult.rightDots,
                        confidence = aiResult.confidence,
                        isDraw = aiResult.leftDots == aiResult.rightDots
                    )
                } else {
                    Log.w(TAG, "AI не смог распознать результат")
                    null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка анализа результата", e)
            null
        }
    }

    /**
     * Обработка результата игры
     */
    private suspend fun processGameResult(result: com.example.diceautobet.models.DiceResult) {
        try {
            Log.d(TAG, "Обрабатываем результат: $result")

            val oldGameState = gameState
            val newGameState = gameState.processGameResult(result)
            gameState = newGameState

            withContext(Dispatchers.Main) {
                onGameStateChanged?.invoke(gameState)

                val isWin = !result.isDraw && when (oldGameState.currentColor) {
                    BetColor.BLUE -> result.leftDots > result.rightDots
                    BetColor.RED -> result.rightDots > result.leftDots
                }

                val resultText = when {
                    result.isDraw -> "Ничья ${result.leftDots}-${result.rightDots} (проигрыш)"
                    isWin -> "Выигрыш! ${result.leftDots}-${result.rightDots}"
                    else -> "Проигрыш ${result.leftDots}-${result.rightDots}"
                }

                onDebugMessage?.invoke("🎲 $resultText")

                if (isWin) {
                    onDebugMessage?.invoke("🎉 Возврат к базовой ставке ${gameState.baseBet}")
                } else {
                    // Проверяем, произошла ли смена цвета
                    if (oldGameState.currentColor != newGameState.currentColor) {
                        onDebugMessage?.invoke("🔄 Смена цвета с ${oldGameState.currentColor.displayName} на ${newGameState.currentColor.displayName}")
                    }
                    onDebugMessage?.invoke("📈 Следующая ставка: ${gameState.currentBet}")
                }
            }

            // СПЕЦИАЛЬНАЯ ЗАДЕРЖКА ДЛЯ ОДИНОЧНОГО РЕЖИМА - 7 секунд после распознавания Gemini
            Log.d(TAG, "⏳ Задержка 7 секунд перед следующей ставкой...")
            onDebugMessage?.invoke("⏳ Ожидание 7 секунд перед следующей ставкой...")
            
            // Разбиваем задержку на мелкие части для проверки паузы
            var remainingDelay = 7000L
            while (remainingDelay > 0 && !gameState.isPaused) {
                val stepDelay = minOf(500L, remainingDelay)
                delay(stepDelay)
                remainingDelay -= stepDelay
            }
            
            if (gameState.isPaused) {
                Log.d(TAG, "🛑 Пауза во время 7-секундной задержки")
                return
            }
            
            // Обычная пауза между раундами (тоже с проверкой паузы)
            var detectionDelay = settings.detectionDelay
            while (detectionDelay > 0 && !gameState.isPaused) {
                val stepDelay = minOf(500L, detectionDelay)
                delay(stepDelay)
                detectionDelay -= stepDelay
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки результата", e)
            throw e
        }
    }

    /**
     * Клик по области
     */
    private suspend fun clickArea(areaType: SingleModeAreaType) {
        val rect = areas[areaType]
        if (rect != null) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()

            if (settings.enableTestMode) {
                Log.d(TAG, "Тестовый клик по ${areaType.displayName} ($centerX, $centerY)")
            } else {
                performClick(centerX, centerY) { success ->
                    Log.d(TAG, "Клик по ${areaType.displayName} ($centerX, $centerY): success=$success")
                }
            }
        } else {
            Log.w(TAG, "Область ${areaType.displayName} не настроена")
        }
    }

    /**
     * Проверка, является ли изображение таймером (зеленые или красные цифры)
     * Улучшенная версия: отличает таймер от кубиков по паттерну распределения
     */
    private fun isTimerImage(bitmap: Bitmap): Boolean {
        try {
            // Проверяем наличие зеленых или красных пикселей в центральной области
            val centerX = bitmap.width / 2
            val centerY = bitmap.height / 2
            val checkRadius = minOf(bitmap.width, bitmap.height) / 4

            var greenPixelCount = 0  // Зелёные пиксели (таймер)
            var redPixelCount = 0    // Красные пиксели (последние 5 сек)
            var bluePixelCount = 0   // Синие пиксели (кубики!)
            var totalPixelsChecked = 0

            // Проверяем область вокруг центра
            for (x in (centerX - checkRadius)..(centerX + checkRadius)) {
                for (y in (centerY - checkRadius)..(centerY + checkRadius)) {
                    if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                        val pixel = bitmap.getPixel(x, y)
                        val red = (pixel shr 16) and 0xFF
                        val green = (pixel shr 8) and 0xFF
                        val blue = pixel and 0xFF

                        totalPixelsChecked++

                        // Проверяем, является ли пиксель зеленым (обычный таймер)
                        val isGreenTimer = green > red + 40 && green > blue + 40 && green > 120

                        // Проверяем, является ли пиксель красным (последние 5 секунд или КУБИК!)
                        val isRedPixel = red > green + 40 && red > blue + 40 && red > 100
                        
                        // Проверяем, является ли пиксель синим (КУБИК!)
                        val isBluePixel = blue > red + 40 && blue > green + 40 && blue > 100

                        if (isGreenTimer) greenPixelCount++
                        if (isRedPixel) redPixelCount++
                        if (isBluePixel) bluePixelCount++
                    }
                }
            }

            // Вычисляем процент каждого цвета
            val greenPercentage = if (totalPixelsChecked > 0) {
                (greenPixelCount.toFloat() / totalPixelsChecked) * 100
            } else 0f
            
            val redPercentage = if (totalPixelsChecked > 0) {
                (redPixelCount.toFloat() / totalPixelsChecked) * 100
            } else 0f
            
            val bluePercentage = if (totalPixelsChecked > 0) {
                (bluePixelCount.toFloat() / totalPixelsChecked) * 100
            } else 0f

            // КЛЮЧЕВАЯ ЛОГИКА: 
            // Если есть синие пиксели (>2%) - это КУБИКИ, не таймер!
            // Таймер = зелёные/красные цифры БЕЗ синих кубиков
            val hasBlueDice = bluePercentage > 2.0f
            val hasGreenTimer = greenPercentage > 3.0f
            val hasRedTimer = redPercentage > 3.0f
            
            val isTimer = (hasGreenTimer || hasRedTimer) && !hasBlueDice

            if (hasGreenTimer || hasRedTimer || hasBlueDice) {
                Log.d(TAG, "🎨 Анализ цветов: " +
                    "Зелёный=${greenPercentage.toInt()}%, " +
                    "Красный=${redPercentage.toInt()}%, " +
                    "Синий=${bluePercentage.toInt()}%")
                
                if (isTimer) {
                    Log.d(TAG, "🟢🔴 Обнаружен ТАЙМЕР (зелёные/красные цифры)")
                } else if (hasBlueDice) {
                    Log.d(TAG, "🎲 Обнаружены КУБИКИ (синий цвет присутствует)")
                }
            }

            return isTimer

        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки таймера: ${e.message}")
            return false
        }
    }
    
    /**
     * Улучшенная проверка таймера в области кубиков
     * Использует обработанное изображение для лучшей детекции зеленых/красных цифр
     */
    private fun isTimerInDiceArea(originalBitmap: Bitmap): Boolean {
        try {
            // Создаем обработанную версию для детекции таймера
            val processedBitmap = createProcessedBitmapForTimerDetection(originalBitmap)
            
            // Используем существующую логику на обработанном изображении
            val result = isTimerImage(processedBitmap)
            
            // Освобождаем ресурсы
            if (processedBitmap != originalBitmap) {
                processedBitmap.recycle()
            }
            
            return result
            
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка улучшенной проверки таймера: ${e.message}")
            // Фолбэк на оригинальную проверку
            return isTimerImage(originalBitmap)
        }
    }
    
    /**
     * Создает обработанную версию изображения для лучшей детекции таймера
     */
    private fun createProcessedBitmapForTimerDetection(bitmap: Bitmap): Bitmap {
        try {
            // Создаем копию для обработки
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint()
            
            // Применяем фильтр для усиления зеленых и красных цветов
            val colorMatrix = ColorMatrix().apply {
                // Увеличиваем насыщенность для лучшего выделения цветов таймера
                setSaturation(2.0f)
                
                // Увеличиваем контрастность
                val contrast = 1.5f
                val brightness = 10f
                val scale = contrast
                val translate = brightness + (128f * (1f - contrast))
                
                postConcat(ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            
            Log.d(TAG, "🎨 Создано обработанное изображение для детекции таймера: ${result.width}x${result.height}")
            return result
            
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка создания обработанного изображения: ${e.message}")
            return bitmap // Возвращаем оригинал в случае ошибки
        }
    }

    /**
     * Вычисление хеша битмапа
     */
    private fun calculateBitmapHash(bitmap: Bitmap): String {
        val bytes = bitmap.rowBytes * bitmap.height
        val buffer = ByteArray(bytes)
        bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buffer))

        val md = MessageDigest.getInstance("MD5")
        val hashBytes = md.digest(buffer)

        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Загрузка настроек
     */
    private fun loadSettings() {
        try {
            settings = preferencesManager.getSingleModeSettings()
            Log.d(TAG, "Настройки загружены из PreferencesManager: $settings")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки настроек, используем по умолчанию", e)
            settings = SingleModeSettings()
        }
    }

    /**
     * Загрузка областей
     */
    private fun loadAreas() {
        try {
            areas.clear()

            Log.d(TAG, "=== НАЧИНАЕМ ЗАГРУЗКУ ОБЛАСТЕЙ ===")
            SingleModeAreaType.values().forEach { areaType ->
                val rect = preferencesManager.getSingleModeAreaRect(areaType)
                if (rect != null) {
                    areas[areaType] = rect
                    Log.d(TAG, "✅ Загружена область ${areaType.displayName}: $rect")
                } else {
                    Log.w(TAG, "❌ Область ${areaType.displayName} НЕ найдена")
                }
            }

            Log.d(TAG, "=== РЕЗУЛЬТАТ ЗАГРУЗКИ ===")
            Log.d(TAG, "Загружено ${areas.size} областей из ${SingleModeAreaType.values().size} через PreferencesManager")

            // Подробный лог для отладки
            areas.forEach { (areaType, rect) ->
                Log.d(TAG, "  ${areaType.displayName} -> $rect")
            }

            // Специальная проверка области кубиков
            val diceArea = areas[SingleModeAreaType.DICE_AREA]
            if (diceArea != null) {
                Log.d(TAG, "🎯 Область кубиков найдена: $diceArea")
            } else {
                Log.e(TAG, "🚨 КРИТИЧНО: Область кубиков НЕ найдена!")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки областей", e)
        }
    }

    /**
     * Форматирование длительности
     */
    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}ч ${minutes % 60}м ${seconds % 60}с"
            minutes > 0 -> "${minutes}м ${seconds % 60}с"
            else -> "${seconds}с"
        }
    }

    /**
     * Получить текущее состояние игры
     */
    fun getGameState(): SingleModeGameState = gameState

    /**
     * Получить текущие настройки
     */
    fun getSettings(): SingleModeSettings = settings

    /**
     * Проверить, активна ли игра
     */
    fun isGameActive(): Boolean = isActive

    /**
     * Освобождение ресурсов
     */
    fun destroy() {
        try {
            stopGame()
            Log.d(TAG, "SingleModeController уничтожен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка уничтожения контроллера", e)
        }
    }
}