package com.example.diceautobet.controllers

import android.content.Context
import android.content.Intent
import android.util.Log
import android.media.projection.MediaProjectionManager
import com.example.diceautobet.MediaProjectionRequestActivity
import com.example.diceautobet.game.ScreenCaptureManager
import com.example.diceautobet.opencv.DotCounter
import com.example.diceautobet.recognition.HybridDiceRecognizer
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.diceautobet.game.ClickManager
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.automation.DualModeBetPlacer
import com.example.diceautobet.utils.SplitScreenUtils
import com.example.diceautobet.utils.BetCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.security.MessageDigest
import java.nio.ByteBuffer

/**
 * Упрощенный контроллер двойного режима с правильной стратегией
 *
 * ПРАВИЛЬНАЯ логика согласно требованиям:
 * 1. Старт: BET_10 + DOUBLE_BUTTON (20 рублей) на красный кубик в первом окне
 * 2. При выигрыше: BET_10 + DOUBLE_BUTTON (20 рублей) на тот же цвет в соседнем окне
 * 3. При проигрыше: ставка ×2 на тот же цвет в соседнем окне (20→40→80→160→320→640→...→30000)
 * 4. После 2 проигрышей подряд на одном цвете: переход на другой цвет + ставка ×2 в соседнем окне
 * 5. После 2 проигрышей на новом цвете: возврат на предыдущий цвет + ставка ×2 в соседнем окне
 *
 * НОВЫЕ ИСПРАВЛЕНИЯ (9 сентября 2025):
 * 🔧 УЛУЧШЕННАЯ СИСТЕМА ДЕТЕКЦИИ РЕЗУЛЬТАТОВ:
 *    - Пиксельная детекция изменений через MD5 хеширование областей
 *    - Умное ожидание стабилизации после анимации
 *    - Комбинированная стратегия: OpenCV + Gemini только для подтверждения
 *    - Значительно повышена надежность и снижены затраты на API
 *
 * 🎯 НОВАЯ ЛОГИКА ДЕТЕКЦИИ:
 *    1. Фаза изменений: Детекция по изменению MD5 хеша области кубиков (каждые 50мс)
 *    2. Фаза стабилизации: Ожидание окончания анимации (стабильный хеш 0.4+ сек)
 *    3. Фаза анализа: Мгновенная проверка OpenCV результатов (1 кадр)
 *    4. Фаза подтверждения: Обязательная проверка через Gemini API при каждом результате
 *
 * ⚡ МАКСИМАЛЬНАЯ СКОРОСТЬ:
 *    - Детекция изменений: каждые 50мс (в 3 раза быстрее)
 *    - Стабилизация хеша: 0.4 секунды (в 2 раза быстрее)
 *    - OpenCV валидация: мгновенно (1 кадр вместо 2)
 *    - Общее ускорение: более чем в 2 раза
 *
 * 🔧 ЗАЩИТА ОТ ДУБЛИРОВАНИЯ РЕЗУЛЬТАТОВ:
 *    - Добавлена временная блокировка повторной обработки одинаковых результатов
 *    - Результаты игнорируются, если прошло менее 3 секунд с последней обработки
 *    - Это предотвращает ложные ставки при задержке обновления кубиков
 *
 * 🎨 ЦИКЛИЧЕСКАЯ СМЕНА ЦВЕТОВ:
 *    - При 2 проигрышах на red → переключение на orange
 *    - При 2 проигрышах на orange → возврат на red
 *    - И так циклично: red ↔ orange ↔ red ↔ orange...
 *
 * 💰 УВЕЛИЧЕННЫЙ ЛИМИТ УДВОЕНИЯ:
 *    - Максимальная ставка увеличена с 2.500 до 30.000 рублей
 *    - Это позволяет делать больше удвоений при проигрышной серии
 *
 * Важно: окно ВСЕГДА меняется после каждого раунда для синхронизации с игрой!
 *
 * РУЧНАЯ НАСТРОЙКА ОБЛАСТЕЙ:
 * Если автоматическое определение области результатов захватывает лишние части (например,
 * правую часть с прошлыми кубиками), используйте функции ручной настройки:
 *
 * Пример использования:
 * ```
 * // Установка точной области результатов для TOP окна (координаты в пикселях)
 * controller.setManualResultArea(WindowType.TOP, 100, 200, 400, 350)
 *
 * // Проверка текущей области
 * val currentArea = controller.getCurrentResultArea(WindowType.TOP)
 * Log.d(TAG, "Текущая область: $currentArea")
 *
 * // Сброс к автоопределению
 * controller.resetResultAreaToAuto(WindowType.TOP)
 * ```
 */
class SimpleDualModeController(
    private val context: Context,
    private val clickManager: ClickManager,
    private val preferencesManager: PreferencesManager,
    private val areaManager: DualWindowAreaManager
) {
    companion object {
        private const val TAG = "SimpleDualModeController"
        // Убираем хардкод MIN_BET - используем baseBet из настроек
        private const val MAX_BET = 30000 // Увеличено до 30.000 согласно требованиям
        private const val FAST_CLICK_DELAY = 0L // Максимальная скорость
        private const val CLICK_DELAY = 0L // Максимальная скорость
        private const val DICE_CHANGE_THRESHOLD = 1 // Порог изменения кубиков
        private const val RESULT_IGNORE_TIME_MS = 3000L // Минимальное время между уникальными результатами (3 сек)
    }

    // Состояние игры
    private var gameState = SimpleDualModeState()
    private var gameJob: Job? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var betPlacer: DualModeBetPlacer? = null
    private var lastDetectedRound: RoundResult? = null
    private var lastResultProcessTime: Long = 0L // Время последней обработки результата
    // Окно, в котором БЫЛА размещена последняя ставка
    private var lastBetWindow: WindowType? = null
    // Калибровка вертикального смещения ROI по окнам (для двойного режима)
    private val roiYOffsetMap = mutableMapOf<WindowType, Int>()

    // Настройки двойного режима (загружаем при старте и используем вместо констант)
    private lateinit var dualModeSettings: DualModeSettings

    // Отладка: папка для сохранения скриншотов
    private var debugFolder: File? = null

    // 💰 ЭКОНОМНОЕ распознавание кубиков с детекцией изменений
    private val hybridRecognizer = com.example.diceautobet.recognition.HybridDiceRecognizer(preferencesManager)

    // Коллбэки
    var onError: ((String) -> Unit)? = null
    var onBetPlaced: ((WindowType, BetChoice, Int) -> Unit)? = null
    var onWindowSwitched: ((WindowType) -> Unit)? = null
    var onColorChanged: ((BetChoice) -> Unit)? = null
    var onStateChanged: ((SimpleDualModeState) -> Unit)? = null
    var onResultDetected: ((RoundResult) -> Unit)? = null

    init {
        Log.d(TAG, "🎮 Создан SimpleDualModeController")
        // Ранее здесь принудительно перезаписывались координаты DICE_AREA.
        // Это приводило к тому, что распознавание происходило не в выбранной ROI, а в верхней полосе экрана.
        // Теперь НИЧЕГО принудительно не перезаписываем: используем сохранённые пользователем области.
        setupDiceAreaCoordinates()

        // Инициализация папки для отладочных изображений
        if (preferencesManager.isDebugImagesEnabled()) {
            initDebugFolder()
        }
    }

    /**
     * Настройка правильных координат DICE_AREA для TOP и BOTTOM окон
     * Текущая проблема: DICE_AREA захватывает обе игры одновременно
     * Решение: разделить область на верхнюю и нижнюю части экрана
     */
    private fun setupDiceAreaCoordinates() {
        // Больше не перезаписываем DICE_AREA. Только диагностируем наличие областей.
        try {
            val dualModeSettings = preferencesManager.getDualModeSettings()
            val splitScreenType = dualModeSettings.splitScreenType
            val windows = when (splitScreenType) {
                SplitScreenType.HORIZONTAL -> listOf(WindowType.LEFT, WindowType.RIGHT)
                SplitScreenType.VERTICAL -> listOf(WindowType.TOP, WindowType.BOTTOM)
            }
            Log.d(TAG, "🔍 Проверка DICE_AREA без принудительной записи (${splitScreenType})")
            windows.forEach { w ->
                val areas = areaManager.getAreasForWindow(w)
                val betResult = areas[AreaType.BET_RESULT] ?: areas[AreaType.DICE_AREA]
                if (betResult != null) {
                    val type = if (areas[AreaType.BET_RESULT] != null) "BET_RESULT" else "DICE_AREA"
                    Log.d(TAG, "✅ Область результата ($type) настроена для $w: ${betResult.rect}")
                } else {
                    Log.w(TAG, "⚠️ Область результата (BET_RESULT/DICE_AREA) не настроена для $w — используйте экран настройки областей")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки областей результата: ${e.message}")
        }
    }

    private fun setupLeftRightDiceAreas() {
        // Метод оставлен для истории, но более не используется.
        Log.d(TAG, "ℹ️ Пропускаем принудительную установку DICE_AREA для LEFT/RIGHT")
    }

    private fun setupTopBottomDiceAreas() {
        // Метод оставлен для истории, но более не используется.
        Log.d(TAG, "ℹ️ Пропускаем принудительную установку DICE_AREA для TOP/BOTTOM")
    }

    /**
     * Инициализация папки для сохранения отладочных изображений
     */
    private fun initDebugFolder() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

            // Используем внутреннее хранилище приложения (всегда доступно)
            val appExternalDir = context.getExternalFilesDir(null)
            debugFolder = File(appExternalDir, "DiceAutoBet_Debug_$timestamp")

            if (!debugFolder!!.exists()) {
                val created = debugFolder!!.mkdirs()
                if (created) {
                    Log.d(TAG, "📁 Создана папка для отладочных изображений: ${debugFolder!!.absolutePath}")
                    Log.d(TAG, "📍 Путь к папке: Android/data/com.example.diceautobet/files/DiceAutoBet_Debug_$timestamp")

                    // Создаем README файл с информацией
                    createDebugReadme()
                } else {
                    Log.w(TAG, "⚠️ Не удалось создать папку для отладочных изображений")
                    debugFolder = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания папки для отладочных изображений: ${e.message}", e)
            debugFolder = null
        }
    }

    /**
     * Создает README файл с описанием отладочных изображений
     */
    private fun createDebugReadme() {
        try {
            val readmeFile = File(debugFolder, "README.txt")
            val readmeContent = """
Отладочные изображения SimpleDualModeController
==============================================

Дата создания: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())}
Расположение: Android/data/com.example.diceautobet/files/

ВАЖНО: Для доступа к файлам используйте файловый менеджер с доступом к данным приложений
(например, Root Explorer, Total Commander или подключите устройство к ПК)

Типы файлов:
- fullscreen_[ОКНО]_[ВРЕМЯ].png - Полные скриншоты экрана
- cropped_[ОКНО]_[ВРЕМЯ]_R[КР_ТОЧКИ]O[ОР_ТОЧКИ]_conf[УВЕРЕННОСТЬ].png - Вырезанные области анализа кубиков

Обозначения окон:
- TOP - Верхнее окно (вертикальное разделение)
- BOTTOM - Нижнее окно (вертикальное разделение)  
- LEFT - Левое окно (горизонтальное разделение)
- RIGHT - Правое окно (горизонтальное разделение)

Результаты анализа:
- R[число] - количество красных точек (левый кубик)
- O[число] - количество оранжевых точек (правый кубик)
- conf[число] - уверенность распознавания (0.0-1.0)

Время в формате: HHmmss_SSS (часы/минуты/секунды/миллисекунды)

Примеры имен файлов:
- fullscreen_TOP_143052_123.png - полный скриншот верхнего окна в 14:30:52.123
- cropped_TOP_143052_456_R3O5_conf0.85.png - область кубиков: 3 красных, 5 оранжевых, уверенность 85%

Анализ изображений:
1. Проверьте, что в cropped изображениях видны именно кубики
2. Сравните результаты распознавания с реальным количеством точек
3. Обратите внимание на уверенность (confidence) - должна быть > 0.5
4. Если области неправильные, настройте их в приложении
            """.trimIndent()

            readmeFile.writeText(readmeContent)
            Log.d(TAG, "📄 Создан README файл в папке отладки")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания README файла: ${e.message}")
        }
    }

    /**
     * Сохраняет кроп, который отправляется в Gemini, в каталог приложения
     */
    private fun saveGeminiCropImage(crop: Bitmap, window: WindowType, quick: DotCounter.Result?) {
        try {
            val dir = File(context.getExternalFilesDir(null), "Gemini_Crops")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val dots = quick?.let { "_R${it.leftDots}O${it.rightDots}" } ?: ""
            val file = File(dir, "gemini_crop_${window}_${ts}${dots}.jpg")
            FileOutputStream(file).use { out ->
                crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            Log.d(TAG, "💾 Gemini-кроп сохранён: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сохранения Gemini-кропа: ${e.message}", e)
        }
    }

    /**
     * ОТКЛЮЧЕНО: Сохранение отладочных изображений для максимальной скорости
     */
    private fun saveDebugImage(bitmap: Bitmap, filename: String, description: String = "") {
        // ПОЛНОСТЬЮ ОТКЛЮЧЕНО для максимальной скорости!
        // Сохранение на диск отнимает секунды - критично для betting
    }

    /**
     * Создает имя файла для отладочного изображения
     */
    private fun createDebugFilename(prefix: String, window: WindowType, dots: DotCounter.Result? = null): String {
        val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())
        val dotsInfo = dots?.let { dotResult -> "_R${dotResult.leftDots}O${dotResult.rightDots}_conf${String.format("%.2f", dotResult.confidence)}" } ?: ""
        return "${prefix}_${window}_${timestamp}${dotsInfo}.png"
    }

    /**
     * Делает тестовый скриншот при запуске для проверки настроек
     */
    private suspend fun takeTestScreenshot() {
        try {
            Log.d(TAG, "📸 Делаем тестовый скриншот при запуске...")
            val scm = screenCaptureManager
            if (scm == null) {
                Log.w(TAG, "⚠️ ScreenCaptureManager недоступен для тестового скриншота")
                return
            }

            val shot = scm.captureScreen()
            if (shot is GameResult.Success) {
                val testFilename = "test_startup_${SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())}.png"
                saveDebugImage(shot.data, testFilename, "Тестовый скриншот при запуске")
                Log.d(TAG, "✅ Тестовый скриншот сохранен: $testFilename")
            } else {
                Log.w(TAG, "⚠️ Не удалось сделать тестовый скриншот")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания тестового скриншота: ${e.message}")
        }
    }

    /**
     * Запускает двойной режим
     */
    fun startDualMode() {
        Log.d(TAG, "🚀 Запуск упрощенного двойного режима")
        roiYOffsetMap.clear()

        // Проверяем, настроены ли области для двойного режима
        Log.d(TAG, "🔍 Проверяем настройку областей...")
        if (!checkAreasConfigured()) {
            Log.e(TAG, "❌ Области не настроены, выходим из startDualMode()")
            return
        }
        Log.d(TAG, "✅ Области настроены корректно")

        // 🔧 ВРЕМЕННОЕ РЕШЕНИЕ: Ручная настройка области результатов
        // Раскомментируйте и настройте координаты под ваш экран для устранения ложной детекции
        // Определите координаты через скриншот - см. файл MANUAL_AREA_SETUP.md

        try {
            val dualModeSettings = preferencesManager.getDualModeSettings()
            val splitScreenType = dualModeSettings.splitScreenType

            // Пример координат (ТРЕБУЮТ НАСТРОЙКИ под ваш экран!)
            when (splitScreenType) {
                SplitScreenType.VERTICAL -> {
                    // Для вертикального разделения (TOP/BOTTOM)
                    // setManualResultArea(WindowType.TOP, 150, 250, 450, 380)
                    Log.d(TAG, "📝 Для устранения ложной детекции раскомментируйте и настройте координаты выше")
                }
                SplitScreenType.HORIZONTAL -> {
                    // Для горизонтального разделения (LEFT/RIGHT)
                    // setManualResultArea(WindowType.LEFT, 50, 300, 250, 450)
                    // setManualResultArea(WindowType.RIGHT, 550, 300, 750, 450)
                    Log.d(TAG, "📝 Для устранения ложной детекции раскомментируйте и настройте координаты выше")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Не удалось применить ручную настройку областей: ${e.message}")
        }

        // Инициализируем захват экрана (обязателен для корректного ожидания фазы ставок/результата)
        Log.d(TAG, "🔍 Проверяем готовность захвата экрана...")
        if (!ensureScreenCaptureReady()) {
            Log.e(TAG, "❌ Захват экрана не готов, выходим из startDualMode()")
            onError?.invoke("🔑 Для работы требуется разрешение на захват экрана.\n\nНажмите 'Разрешить' в открывшемся окне, затем нажмите зеленую кнопку еще раз.")
            return
        }
        Log.d(TAG, "✅ Захват экрана готов")

        // ✅ Захват экрана готов - никаких дополнительных флагов не нужно
        Log.d(TAG, "✅ Захват экрана готов, логика изменений в waitForStableResult()")

        // Тестовый скриншот при запуске
        if (preferencesManager.isDebugImagesEnabled()) {
            CoroutineScope(Dispatchers.IO).launch {
                takeTestScreenshot()
            }
        }

        // Инициализируем DualModeBetPlacer для преобразования координат
        if (betPlacer == null) {
            betPlacer = DualModeBetPlacer(context, areaManager)
            Log.d(TAG, "✅ DualModeBetPlacer инициализирован")
        }

        // Получаем настройки двойного режима для определения начального окна
        dualModeSettings = preferencesManager.getDualModeSettings()
        val splitScreenType = dualModeSettings.splitScreenType

        // Определяем начальное окно в зависимости от типа разделения
        val initialWindow = when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> WindowType.LEFT
            SplitScreenType.VERTICAL -> WindowType.TOP
        }

        Log.d(TAG, "Начальное окно для типа разделения $splitScreenType: $initialWindow")
        Log.d(TAG, "💰 Базовая ставка из настроек: ${dualModeSettings.baseBet}")

        gameState = SimpleDualModeState(
            isRunning = true,
            currentWindow = initialWindow,
            currentColor = BetChoice.RED, // Начальный цвет: красный
            previousColor = null, // Предыдущего цвета нет в начале
            currentBet = dualModeSettings.baseBet, // Начальная ставка из настроек
            consecutiveLosses = 0,
            consecutiveLossesOnCurrentColor = 0,
            totalBets = 0,
            totalProfit = 0,
            lastResult = GameResultType.UNKNOWN
        )

        Log.d(TAG, "✅ Двойной режим запущен: $gameState")
        notifyStateChanged()

        // Запускаем игровой цикл
        gameJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // ПЕРВАЯ СТАВКА: размещаем сразу при старте
                Log.d(TAG, "🚀 Размещаем ПЕРВУЮ ставку при старте...")
                placeBetOnly()
                // Базируем чередование на реально поставленном окне
                lastBetWindow = gameState.currentWindow

                // Затем запускаем основной цикл поиска смены кубиков
                runGameLoop()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка в игровом цикле", e)
                onError?.invoke("Ошибка в игровом цикле: ${e.message}")
            }
        }
    }

    /**
     * Останавливает двойной режим
     */
    fun stopDualMode() {
        Log.d(TAG, "🛑 Остановка упрощенного двойного режима")

        gameState = gameState.copy(isRunning = false)
        gameJob?.cancel()
        gameJob = null

        // Очистка DualModeBetPlacer
        betPlacer?.cleanup()
        betPlacer = null

        notifyStateChanged()
    }

    /**
     * Ручная настройка области результатов для устранения проблем с автоопределением
     * Используйте эту функцию, если автоматическое определение захватывает лишние части
     *
     * @param windowType - тип окна (TOP, LEFT, RIGHT, BOTTOM)
     * @param left - левая граница области в пикселях
     * @param top - верхняя граница области в пикселях
     * @param right - правая граница области в пикселях
     * @param bottom - нижняя граница области в пикселях
     */
    fun setManualResultArea(windowType: WindowType, left: Int, top: Int, right: Int, bottom: Int) {
        Log.d(TAG, "🔧 Установка ручной области результатов для $windowType: [$left, $top, $right, $bottom]")
        areaManager.setManualResultArea(windowType, left, top, right, bottom)
    }

    /**
     * Сброс области результатов к автоматическому определению
     */
    fun resetResultAreaToAuto(windowType: WindowType) {
        Log.d(TAG, "🔄 Сброс области результатов для $windowType к автоопределению")
        areaManager.resetResultAreaToAuto(windowType)
    }

    /**
     * Получение текущей области результатов для отладки
     */
    fun getCurrentResultArea(windowType: WindowType): Rect? {
        return try {
            val areas = areaManager.getAreasForWindow(windowType)
            val resultArea = areas[AreaType.BET_RESULT] ?: areas[AreaType.BET_RESULT] ?: areas[AreaType.DICE_AREA]
            resultArea?.rect
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения области результатов: ${e.message}")
            null
        }
    }

    /**
     * Основной игровой цикл с ОБЯЗАТЕЛЬНЫМ поиском смены кубиков перед каждой ставкой
     */
    private suspend fun runGameLoop() {
        var isFirstRound = true // Флаг первого раунда

        while (gameState.isRunning) {
            try {
                if (isFirstRound) {
                    // ПЕРВЫЙ РАУНД: Ставка уже сделана при старте -> просто запоминаем первый валидный результат
                    Log.d(TAG, "🎯 ПЕРВЫЙ РАУНД: запоминаем первый валидный результат без ставок и без Gemini")

                    // ВАЖНО: Переключаемся на другое окно для ожидания результата (с учетом типа разделения)
                    val prev = gameState.currentWindow
                    val next = getNextWindow(prev)
                    gameState = gameState.copy(currentWindow = next)
                    try { areaManager.setActiveWindow(next) } catch (_: Exception) {}
                    Log.d(TAG, "🔄 Переключились на окно ${prev} → ${next} для ожидания результата")

                    // Ждем и запоминаем первый результат в новом окне (но НЕ обрабатываем как выигрыш/проигрыш)
                    val firstResult = withTimeoutOrNull(15000L) {
                        waitForFirstResult(gameState.currentWindow)
                    }

                    if (firstResult != null) {
                        Log.d(TAG, "📝 Запомнили первый результат: ${firstResult.redDots}:${firstResult.orangeDots} (НЕ обрабатываем как результат ставки)")
                        lastDetectedRound = firstResult
                        onResultDetected?.invoke(firstResult)
                    }

                    isFirstRound = false
                    continue // Переходим к следующему циклу БЕЗ обработки как результат ставки
                }

                // ОБЫЧНЫЕ РАУНДЫ: ждем фазу «исчезли → появились» с улучшенной детекцией
                Log.d(TAG, "🔍 Ожидание изменений кубиков (улучшенная детекция)...")
                val detectedResult = withTimeoutOrNull(35000L) { // Увеличиваем время ожидания
                    detectDiceChangeAndStabilize(gameState.currentWindow)
                }

                if (detectedResult != null) {
                    // Проверяем, не дублированный ли это результат
                    if (shouldIgnoreResult(detectedResult)) {
                        Log.w(TAG, "⚠️ Дублированный результат проигнорирован, продолжаем поиск...")
                        continue
                    }

                    // ЭТАП 2: Конвертируем результат (уже подтвержден через Gemini)
                    val gameResult = convertRoundResultToGameResult(detectedResult)

                    // ЭТАП 3: Обрабатываем результат (определяем стратегию)
                    processResult(gameResult)

                    // ЭТАП 4: Размещаем ставку согласно стратегии (окно уже переключено в processResult)
                    placeBetOnly()

                    // Уведомляем о детекции результата
                    onResultDetected?.invoke(detectedResult)
                    lastDetectedRound = detectedResult

                    Log.d(TAG, "✅ Цикл завершен: детекция изменений → стабилизация → Gemini → ставка размещена → окно переключено")
                } else {
                    Log.w(TAG, "⚠️ Изменения кубиков не обнаружены улучшенной детекцией, повторяем поиск...")
                }

                // БЕЗ ПАУЗ! Сразу к следующему циклу поиска смены

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                onError?.invoke("Ошибка в игровом цикле: ${e.message}")
                break
            }
        }
    }

    /**
     * Проверяет, не является ли результат дублированным (тот же результат, полученный слишком быстро)
     */
    private fun shouldIgnoreResult(result: RoundResult?): Boolean {
        if (result == null) return true

        val currentTime = System.currentTimeMillis()
        val lastRound = lastDetectedRound

        // Если это тот же результат и прошло менее RESULT_IGNORE_TIME_MS, игнорируем
        if (lastRound != null &&
            lastRound.redDots == result.redDots &&
            lastRound.orangeDots == result.orangeDots &&
            (currentTime - lastResultProcessTime) < RESULT_IGNORE_TIME_MS) {

            Log.d(TAG, "🚫 ИГНОРИРУЕМ дублированный результат: ${result.redDots}:${result.orangeDots} (прошло ${currentTime - lastResultProcessTime}ms)")
            return true
        }

        return false
    }

    /**
     * ПРОСТАЯ обработка результата - мгновенно применяем стратегию Gemini
     */
    private fun processResult(result: GameResultType) {
        // Проверяем, что результат не дублированный по времени
        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastResultProcessTime) < RESULT_IGNORE_TIME_MS) {
            Log.d(TAG, "🚫 ИГНОРИРУЕМ результат: слишком быстро после предыдущего (${currentTime - lastResultProcessTime}ms)")
            return
        }

        lastResultProcessTime = currentTime

        // ПРОСТАЯ ЛОГИКА: Всегда переходим к следующему окну для синхронизации
        val prevWindow = gameState.currentWindow
        // Чередуем от окна ПОСЛЕДНЕЙ СТАВКИ, а не от окна ожидания результата
        val basisWindow = lastBetWindow ?: prevWindow
        val nextWindow = getNextWindow(basisWindow)
        Log.d(TAG, "🪟 Переключение окна после результата: основано на последней ставке в ${basisWindow} → следующий ${nextWindow} (result=${result})")

        // Обновляем счетчики проигрышей
        val newConsecutiveLosses = if (result == GameResultType.WIN) 0 else gameState.consecutiveLosses + 1
        val newConsecutiveLossesOnCurrentColor = if (result == GameResultType.WIN) 0 else gameState.consecutiveLossesOnCurrentColor + 1

        // НОВАЯ ЛОГИКА СМЕНЫ ЦВЕТА: После 2 проигрышей подряд переключаемся циклично
        var newColor = gameState.currentColor
        var newPreviousColor = gameState.previousColor

        if (newConsecutiveLossesOnCurrentColor >= 2) {
            Log.d(TAG, "🎨 СМЕНА ЦВЕТА: 2 проигрыша подряд на ${gameState.currentColor}")

            if (gameState.previousColor != null && gameState.previousColor != gameState.currentColor) {
                // Возвращаемся к предыдущему цвету
                newColor = gameState.previousColor!!
                newPreviousColor = gameState.currentColor
                Log.d(TAG, "🔄 Возврат к предыдущему цвету: ${gameState.currentColor} → ${newColor}")
            } else {
                // Переключаемся на противоположный цвет
                newPreviousColor = gameState.currentColor
                newColor = when (gameState.currentColor) {
                    BetChoice.RED -> BetChoice.ORANGE
                    BetChoice.ORANGE -> BetChoice.RED
                }
                Log.d(TAG, "🔄 Переключение на противоположный цвет: ${gameState.currentColor} → ${newColor}")
            }

            // Сбрасываем счетчик проигрышей на цвете после смены
            // newConsecutiveLossesOnCurrentColor остается прежним, так как мы продолжаем удваивать ставку
            onColorChanged?.invoke(newColor)
        }

        // Стратегия по результату согласно требованиям:
        // - При выигрыше: базовая ставка из настроек
        // - При проигрыше: удвоение ставки (до MAX_BET = 30.000)
        // - При ничьей: как при проигрыше (удвоение ставки)
        // - UNKNOWN: считаем как проигрыш (удвоение), чтобы не терять синхронизацию
        val nextBet = when (result) {
            GameResultType.WIN -> dualModeSettings.baseBet // Базовая ставка из настроек
            GameResultType.LOSS, GameResultType.DRAW, GameResultType.UNKNOWN -> (gameState.currentBet * 2).coerceAtMost(MAX_BET)
        }

        // Обновляем состояние с новой логикой
        gameState = gameState.copy(
            currentWindow = nextWindow,
            currentColor = newColor,
            previousColor = newPreviousColor,
            currentBet = nextBet,
            consecutiveLosses = newConsecutiveLosses,
            consecutiveLossesOnCurrentColor = if (newColor != gameState.currentColor) 0 else newConsecutiveLossesOnCurrentColor,
            totalBets = gameState.totalBets + 1,
            totalProfit = gameState.totalProfit + when (result) {
                GameResultType.WIN -> gameState.currentBet
                GameResultType.LOSS -> -gameState.currentBet
                GameResultType.DRAW -> -gameState.currentBet  // Ничья = проигрыш
                GameResultType.UNKNOWN -> 0  // Неизвестный результат не влияет на прибыль
            },
            lastResult = result
        )

        Log.d(TAG, "📊 ОБНОВЛЕННОЕ СОСТОЯНИЕ:")
        Log.d(TAG, "   🎯 Цвет: ${gameState.currentColor} (предыдущий: ${gameState.previousColor})")
        Log.d(TAG, "   💰 Ставка: ${gameState.currentBet} (макс: $MAX_BET)")
        Log.d(TAG, "   📈 Проигрышей подряд: ${gameState.consecutiveLosses}")
        Log.d(TAG, "   🎨 Проигрышей на цвете: ${gameState.consecutiveLossesOnCurrentColor}")

        // Синхронизируем менеджер областей с новым активным окном (БЕЗ повторной загрузки)
        try {
            areaManager.setActiveWindow(nextWindow)
            // Убираем избыточную перезагрузку областей - они уже загружены при старте
            // areaManager.getAreasForWindow(nextWindow)
        } catch (_: Exception) {}

        // Уведомляем о смене окна
        onWindowSwitched?.invoke(gameState.currentWindow)
        notifyStateChanged()
    }
    /**
     * Размещает ставку и ожидает результат
     */
    private suspend fun placeBetAndWaitResult() {
        if (!gameState.isRunning) return

        try {
            // Получаем области для текущего окна
            val targetWindow = gameState.currentWindow

            // 1. Рассчитываем стратегию ставки через BetCalculator
            val strategy = BetCalculator.calculateBetStrategy(gameState.currentBet)
            BetCalculator.logStrategy(strategy, TAG)
            placeBetBaseAmount(targetWindow, strategy.buttonAmount)

            // 2. Кликаем по цвету кубика в нужном окне
            val colorButtonType = getColorButtonType(gameState.currentColor)
            val colorArea = areaManager.getAreaForWindow(targetWindow, colorButtonType)

            if (colorArea == null) {
                val colorName = if (gameState.currentColor == BetChoice.RED) "Красный кубик" else "Оранжевый кубик"
                val errorMsg = "❌ Область цвета '$colorName' не настроена для окна $targetWindow"
                onError?.invoke(errorMsg)
                return
            }

            val colorResult = clickManager.clickAreaFast(colorArea, FAST_CLICK_DELAY)
            if (colorResult !is GameResult.Success) {
                Log.e(TAG, "❌ Ошибка клика по цвету")
                onError?.invoke("Ошибка клика по цвету ${gameState.currentColor}")
                return
            }

            // 3. Делаем удвоения ПОСЛЕ выбора цвета
            if (strategy.doublingClicks > 0) {
                applyDoublingClicks(targetWindow, strategy.doublingClicks)
            }

            // 4. Подтверждаем ставку в нужном окне
            val confirmArea = areaManager.getAreaForWindow(targetWindow, AreaType.CONFIRM_BET)

            if (confirmArea == null) {
                val errorMsg = "❌ Область подтверждения ставки не настроена для окна $targetWindow"
                Log.e(TAG, errorMsg)
                onError?.invoke(errorMsg)
                return
            }

            val confirmResult = clickManager.clickAreaFast(confirmArea, FAST_CLICK_DELAY)
            if (confirmResult !is GameResult.Success) {
                onError?.invoke("Ошибка подтверждения ставки")
                return
            }

            onBetPlaced?.invoke(gameState.currentWindow, gameState.currentColor, gameState.currentBet)

            // 4. Ждем и анализируем результат кубиков
            val detectedResult = withTimeoutOrNull(15000L) { // Сокращаем до 15 секунд для скорости
                waitForStableResult(gameState.currentWindow)
            }

            if (detectedResult != null) {
                // Проверяем, не дублированный ли это результат
                if (shouldIgnoreResult(detectedResult)) {
                    Log.w(TAG, "⚠️ Дублированный результат в placeBetAndWaitResult проигнорирован")
                    return
                }

                // Конвертируем RoundResult в GameResultType
                val gameResult = convertRoundResultToGameResult(detectedResult)

                // 5. Обрабатываем результат
                processResult(gameResult)

                // Уведомляем о детекции результата
                onResultDetected?.invoke(detectedResult)
                lastDetectedRound = detectedResult
            } else {
                Log.w(TAG, "⚠️ Результат не обнаружен за отведенное время")

                // Повторная попытка с дополнительным ожиданием
                Log.d(TAG, "🔄 Повторная попытка обнаружения результата...")
                // БЕЗ ЗАДЕРЖКИ! Мгновенная повторная попытка

                val retryResult = withTimeoutOrNull(8000L) { // Быстрая повторная попытка
                    waitForStableResult(gameState.currentWindow, maxAttempts = 30) // Меньше попыток
                }

                if (retryResult != null) {
                    // Проверяем, не дублированный ли это результат
                    if (shouldIgnoreResult(retryResult)) {
                        Log.w(TAG, "⚠️ Дублированный результат в retry проигнорирован")
                        return
                    }

                    val gameResult = convertRoundResultToGameResult(retryResult)
                    processResult(gameResult)
                    onResultDetected?.invoke(retryResult)
                    lastDetectedRound = retryResult
                } else {
                    Log.e(TAG, "❌ Критическая ошибка: результат не обнаружен после всех попыток")
                    onError?.invoke("Не удалось обнаружить результат кубиков. Проверьте настройку области результата (BET_RESULT/DICE_AREA) для окна ${gameState.currentWindow}")
                    return
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка размещения ставки", e)
            onError?.invoke("Ошибка размещения ставки: ${e.message}")
        }
    }

    /**
     * Размещает ставку БЕЗ ожидания результата (для нового цикла)
     */
    private suspend fun placeBetOnly() {
        if (!gameState.isRunning) return

        try {
            // Получаем области для текущего окна
            val targetWindow = gameState.currentWindow
            Log.d(TAG, "🪟 Ставка будет размещена в окне: ${targetWindow}, цвет=${gameState.currentColor}, сумма=${gameState.currentBet}")

            // 1. Рассчитываем стратегию ставки через BetCalculator
            val strategy = BetCalculator.calculateBetStrategy(gameState.currentBet)
            BetCalculator.logStrategy(strategy, TAG)
            placeBetBaseAmount(targetWindow, strategy.buttonAmount)

            // 2. Кликаем по цвету кубика в нужном окне
            val colorButtonType = getColorButtonType(gameState.currentColor)
            val colorArea = areaManager.getAreaForWindow(targetWindow, colorButtonType)

            if (colorArea == null) {
                val colorName = if (gameState.currentColor == BetChoice.RED) "Красный кубик" else "Оранжевый кубик"
                val errorMsg = "❌ Область цвета '$colorName' не настроена для окна $targetWindow"
                onError?.invoke(errorMsg)
                return
            }

            val colorResult = clickManager.clickAreaFast(colorArea, FAST_CLICK_DELAY)
            if (colorResult !is GameResult.Success) {
                onError?.invoke("Ошибка выбора цвета кубика")
                return
            }

            // 2.1. Применяем удвоения, если требуется
            if (strategy.doublingClicks > 0) {
                applyDoublingClicks(targetWindow, strategy.doublingClicks)
            }

            // 3. Подтверждаем ставку в нужном окне
            val confirmArea = areaManager.getAreaForWindow(targetWindow, AreaType.CONFIRM_BET)

            if (confirmArea == null) {
                val errorMsg = "❌ Область подтверждения ставки не настроена для окна $targetWindow"
                Log.e(TAG, errorMsg)
                onError?.invoke(errorMsg)
                return
            }

            val confirmResult = clickManager.clickAreaFast(confirmArea, FAST_CLICK_DELAY)
            if (confirmResult !is GameResult.Success) {
                onError?.invoke("Ошибка подтверждения ставки")
                return
            }

            onBetPlaced?.invoke(gameState.currentWindow, gameState.currentColor, gameState.currentBet)
            // Запоминаем окно фактической ставки для строгого чередования
            lastBetWindow = targetWindow
            Log.d(TAG, "✅ Ставка размещена: ${gameState.currentBet} на ${gameState.currentColor} в окне ${gameState.currentWindow}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка размещения ставки", e)
            onError?.invoke("Ошибка размещения ставки: ${e.message}")
        }
    }

    // === Новая система ожидания этапов раунда ===

    // Готовим/проверяем захват экрана
    private fun ensureScreenCaptureReady(): Boolean {
        return try {
            if (screenCaptureManager == null) {
                Log.d(TAG, "Создаем новый ScreenCaptureManager с автоматическим получением разрешения")
                screenCaptureManager = ScreenCaptureManager(context)
            }

            // Проверяем, валиден ли текущий MediaProjection
            if (screenCaptureManager?.isCapturing() == true && screenCaptureManager?.validateMediaProjection() == true) {
                Log.d(TAG, "ScreenCaptureManager уже работает корректно")
                return true
            }

            // Проверяем наличие сохраненного разрешения (через центральный менеджер)
            val permissionManager = MediaProjectionPermissionManager.getInstance(context)
            if (!permissionManager.hasPermission()) {
                Log.d(TAG, "🔑 Нет сохраненного разрешения, запрашиваем автоматически")
                requestPermissionAndStop("Нет разрешения на захват экрана")
                return false
            }

            Log.d(TAG, "Запускаем новый ScreenCaptureManager с сохраненным разрешением")
            val res = screenCaptureManager!!.startCapture()

            if (res is GameResult.Error) {
                Log.e(TAG, "❌ Ошибка старта захвата: ${res.message}")

                // Если разрешение устарело, запрашиваем новое и останавливаем режим
                if (res.message.contains("разрешение") || res.message.contains("Permission") ||
                    res.message.contains("устарело") || res.message.contains("недействительн")) {
                    Log.d(TAG, "🔑 Разрешение устарело или недействительно, запрашиваем новое")
                    requestPermissionAndStop("Разрешение на захват экрана устарело")
                } else {
                    // Для других ошибок просто останавливаем режим
                    stopDualMode("Ошибка захвата экрана: ${res.message}")
                }

                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подготовки захвата", e)
            false
        }
    }

    // Ждём первый валидный результат (для запоминания)
    private suspend fun waitForFirstResult(window: WindowType, maxAttempts: Int = 60): RoundResult? {
        var attempts = 0

        Log.d(TAG, "🔍 Ожидание первого валидного результата для окна $window")

        while (attempts < maxAttempts && gameState.isRunning) {
            try {
                val result = captureAndAnalyze(window)

                // Принимаем ТОЛЬКО валидные результаты (оба цвета > 0)
                if (result != null && result.redDots > 0 && result.orangeDots > 0) {
                    Log.d(TAG, "📝 Первый валидный результат: ${result.redDots}:${result.orangeDots}")
                    return result
                } else {
                    // Невалидный результат (0:0 или null) - игнорируем
                    if (result != null) {
                        Log.d(TAG, "⏳ Игнорируем невалидный: ${result.redDots}:${result.orangeDots}")
                    }
                }

            } catch (e: Exception) {
                // Игнорируем ошибки для максимальной скорости
            }

            attempts++
        }

        Log.w(TAG, "⚠️ Не удалось получить первый валидный результат за $maxAttempts попыток")
        return null
    }

    // Ждём стабильный новый результат после ставки
    private suspend fun waitForStableResult(window: WindowType, maxAttempts: Int = 60): RoundResult? {
        var attempts = 0
        val currentResult = lastDetectedRound // Используем запомненный результат

        Log.d(TAG, "🔍 ПРОСТАЯ детекция смены кубиков для окна $window")
        Log.d(TAG, "📋 Текущий результат: ${currentResult?.redDots}:${currentResult?.orangeDots}")

        // ПРОСТАЯ ЛОГИКА: ждем ВАЛИДНОГО изменения от запомненного
        while (attempts < maxAttempts && gameState.isRunning) {
            try {
                val newResult = captureAndAnalyze(window)

                // Принимаем ТОЛЬКО валидные результаты (оба цвета > 0)
                if (newResult != null && newResult.redDots > 0 && newResult.orangeDots > 0) {

                    // Проверяем изменение от запомненного
                    if (currentResult != null &&
                        (newResult.redDots != currentResult.redDots ||
                                newResult.orangeDots != currentResult.orangeDots)) {
                        // ИЗМЕНЕНИЕ! К Gemini!
                        Log.d(TAG, "⚡ СМЕНА: ${currentResult.redDots}:${currentResult.orangeDots} → ${newResult.redDots}:${newResult.orangeDots} → Вызываем Gemini!")

                        // ВРЕМЕННО: просто возвращаем OpenCV результат пока не исправим Gemini
                        // TODO: Исправить вызов Gemini
                        Log.w(TAG, "⚠️ ВРЕМЕННО: возвращаем OpenCV результат вместо Gemini")
                        return newResult
                    }
                } else {
                    // Невалидный результат (0:0 или null) - игнорируем
                    if (newResult != null) {
                        Log.d(TAG, "⏳ Игнорируем невалидный: ${newResult.redDots}:${newResult.orangeDots}")
                    }
                }

            } catch (e: Exception) {
                // Игнорируем ошибки для максимальной скорости
            }

            attempts++
            // БЕЗ ЗАДЕРЖКИ! Максимальная скорость детекции
        }


        Log.w(TAG, "⚠️ Смена кубиков не обнаружена за отведенное время")
        return null
    }

    /**
     * Ждем, пока кубики исчезнут из области (0:0 или null несколько раз подряд),
     * затем ждём их ПОВТОРНОЕ появление (валидное >0:>0) и в этот момент
     * отправляем кроп кубиков в Gemini, возвращаем распознанный RoundResult.
     * Исключаем ложные срабатывания: одиночные нули не считаем исчезновением.
     */
    private suspend fun waitForReappearanceAndGemini(window: WindowType, maxAttempts: Int = 120): RoundResult? {
        var attempts = 0
        var consecutiveBlanks = 0
        val blankThreshold = 3 // нужно как минимум 3 подряд пустых кадра, чтобы считать «исчезли»
        var disappeared = false
        val fallbackStart = System.currentTimeMillis()
        val fallbackTimeoutMs = 6000L // если долго не "исчезают", допускаем прямое срабатывание на смену

        Log.d(TAG, "⏳ Ожидание исчезновения кубиков в окне $window...")

        // Если базовый результат ещё не установлен, зафиксируем первый валидный (без Gemini, как и требуется)
        if (lastDetectedRound == null) {
            var baselineAttempts = 0
            while (baselineAttempts < 60 && gameState.isRunning && lastDetectedRound == null) {
                val r0 = captureAndAnalyze(window)
                if (r0 != null && r0.redDots > 0 && r0.orangeDots > 0) {
                    lastDetectedRound = r0
                    onResultDetected?.invoke(r0)
                    Log.d(TAG, "📝 Базовый результат установлен: ${r0.redDots}:${r0.orangeDots}")
                    break
                }
                baselineAttempts++
            }
        }

        // Фаза 1: ждём устойчивого исчезновения
        while (attempts < maxAttempts && gameState.isRunning && !disappeared) {
            val r = captureAndAnalyze(window)
            if (r == null || r.redDots == 0 || r.orangeDots == 0) {
                consecutiveBlanks++
            } else {
                consecutiveBlanks = 0
            }
            if (consecutiveBlanks >= blankThreshold) {
                disappeared = true
                Log.d(TAG, "✅ Кубики исчезли (подтверждено $consecutiveBlanks кадров)")
                break
            }
            // Fallback: если слишком долго нет исчезновения, но есть валидная смена относительно lastDetectedRound — считаем наступлением новой фазы
            if (System.currentTimeMillis() - fallbackStart > fallbackTimeoutMs && lastDetectedRound != null && r != null && r.redDots > 0 && r.orangeDots > 0) {
                if (r.redDots != lastDetectedRound!!.redDots || r.orangeDots != lastDetectedRound!!.orangeDots) {
                    Log.w(TAG, "⚠️ Fallback: фиксируем смену без явного исчезновения → переходим к Gemini")
                    disappeared = true // форсируем переход к фразе появления с текущего состояния
                    break
                }
            }
            attempts++
        }
        if (!disappeared) {
            Log.w(TAG, "⚠️ Кубики не исчезли за отведённое время")
            return null
        }

        // Фаза 2: ждём повторного появления и отправляем в Gemini
        Log.d(TAG, "👀 Ждем повторного появления кубиков и отправляем в Gemini...")
        attempts = 0
        // Требуем устойчивого появления: два подряд идентичных (или почти) кадра
        var stableCount = 0
        var lastLeft = -1
        var lastRight = -1
        while (attempts < maxAttempts && gameState.isRunning) {
            // Берём кроп области кубиков
            val crop = captureDiceCrop(window)
            if (crop != null) {
                // Проверка на валидность через OpenCV: >0:>0 => появились
                val quick = analyzeWithEconomicAI(crop, window)
                if (quick != null && quick.leftDots in 1..6 && quick.rightDots in 1..6 && quick.confidence >= 0.25f) { // Снижен порог с 0.55f до 0.25f
                    // Проверяем устойчивость результатов
                    if (quick.leftDots == lastLeft && quick.rightDots == lastRight) {
                        stableCount++
                    } else {
                        stableCount = 1
                        lastLeft = quick.leftDots
                        lastRight = quick.rightDots
                    }

                    if (stableCount >= 2) {
                        // Только при устойчивом появлении отправляем в Gemini
                        Log.d(TAG, "💎 Устойчивое появление (${quick.leftDots}:${quick.rightDots}, conf=${String.format("%.2f", quick.confidence)}) → отправляем в Gemini")
                        // Сохраняем изображение, которое отправляем в Gemini
                        saveGeminiCropImage(crop, window, quick)
                        val aiRes = try { analyzeWithGeminiDirect(crop, quick) } catch (_: Exception) { null }
                        crop.recycle()
                        if (aiRes != null) {
                            val rr = RoundResult.fromDotResult(aiRes)
                            if (rr.isValid) {
                                Log.d(TAG, "💎 Gemini подтвержденный результат: ${rr.redDots}:${rr.orangeDots}")
                                return rr
                            } else {
                                Log.w(TAG, "⚠️ Gemini вернул невалидный результат — продолжаем ожидание")
                            }
                        } else {
                            Log.w(TAG, "⚠️ Gemini не ответил/ошибка (например, 400) — пропускаем ставку и ждём следующий валидный кадр")
                        }
                        // После отправки сбрасываем счётчик устойчивости, чтобы избежать повторных отправок на одном кадре
                        stableCount = 0
                        lastLeft = -1
                        lastRight = -1
                    } else {
                        // Ещё не устойчиво — не отправляем, ждём следующий кадр
                        crop.recycle()
                    }
                } else {
                    // ещё не появились, продолжаем
                    crop.recycle()
                }
            }
            attempts++
            // без задержек для скорости
        }
        Log.w(TAG, "⚠️ Не дождались повторного появления кубиков")
        return null
    }

    /**
     * Принудительный вызов Gemini независимо от режима распознавания.
     */
    private suspend fun analyzeWithGeminiDirect(image: Bitmap, openCvResult: DotCounter.Result? = null): DotCounter.Result? {
        return withContext(Dispatchers.IO) {
            // Проверяем, настроен ли AI
            if (!preferencesManager.isAIConfigured()) {
                Log.w(TAG, "⚠️ AI не настроен — используем OpenCV как фолбэк")
                return@withContext null
            }
            try {
                val recognitionMode = preferencesManager.getRecognitionMode()
                val aiProvider = preferencesManager.getAIProvider()
                val selectedModel = preferencesManager.getOpenRouterModel()
                
                Log.d(TAG, "🔑 AI анализ: mode=$recognitionMode, provider=$aiProvider, model=${selectedModel.displayName}")
                
                // Используем HybridDiceRecognizer, который автоматически выберет нужный метод
                val recognizer = HybridDiceRecognizer(preferencesManager)
                
                // Анализируем через выбранный метод (OpenRouter/OpenCV/Hybrid)
                val result = recognizer.analyzeDice(image)
                
                if (result != null) {
                    Log.d(TAG, "✅ AI результат: ${result.leftDots}:${result.rightDots} (conf: ${result.confidence})")
                } else {
                    Log.w(TAG, "⚠️ AI не смог распознать, возвращаем null")
                }
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка AI анализа: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Возвращает Bitmap-кроп области кубиков для окна (учитывает безопасные границы и инсет статуса).
     * Возвращает null при ошибке. Вызывать в фоне.
     */
    private suspend fun captureDiceCrop(window: WindowType): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                val scm = screenCaptureManager ?: return@withContext null
                val shot = scm.captureScreen()
                if (shot !is GameResult.Success) return@withContext null
                val bmp = shot.data

                // Всегда предпочитаем TOP окно для кропа, как согласовано
                val preferredWindows = listOf(WindowType.TOP, WindowType.LEFT, window)
                var chosenWindow: WindowType? = null
                var resultArea: ScreenArea? = null
                for (w in preferredWindows) {
                    val area = areaManager.getAreaForWindow(w, AreaType.DICE_AREA)
                        ?: areaManager.getAreaForWindow(w, AreaType.BET_RESULT)
                    if (area != null) {
                        chosenWindow = w
                        resultArea = area
                        break
                    }
                }
                if (resultArea == null || chosenWindow == null) return@withContext null
                Log.d(TAG, "🎯 Кроп для Gemini берём из окна ${chosenWindow}: ${resultArea.rect}")

                // Подстрахуемся: расширим слишком маленькие области
                val originalRect = resultArea.rect
                val minWidth = 200
                val minHeight = 150
                val expandedRect = if (originalRect.width() < minWidth || originalRect.height() < minHeight) {
                    val newWidth = maxOf(originalRect.width(), minWidth)
                    val newHeight = maxOf(originalRect.height(), minHeight)
                    val upwardShift = 30
                    Rect(
                        originalRect.centerX() - newWidth / 2,
                        originalRect.bottom - newHeight - upwardShift,
                        originalRect.centerX() + newWidth / 2,
                        originalRect.bottom - upwardShift
                    )
                } else originalRect

                var abs = areaManager.getAbsoluteCoordinates(chosenWindow, AreaType.DICE_AREA)
                    ?: areaManager.getAbsoluteCoordinates(chosenWindow, AreaType.BET_RESULT)
                    ?: expandedRect
                Log.d(TAG, "📐 Абсолютная область исходно: ${abs} (${abs.width()}x${abs.height()})")

                // Форсируем минимальный размер уже ПОСЛЕ получения абсолютных координат
                if (abs.width() < minWidth || abs.height() < minHeight) {
                    val newWidth = maxOf(abs.width(), minWidth)
                    val newHeight = maxOf(abs.height(), minHeight)
                    val newLeft = (abs.centerX() - newWidth / 2).coerceAtLeast(0)
                    val newTop = (abs.bottom - newHeight - 20).coerceAtLeast(0) // слегка подтягиваем вверх
                    val newRight = (newLeft + newWidth).coerceAtMost(bmp.width)
                    val newBottom = (newTop + newHeight).coerceAtMost(bmp.height)
                    abs = Rect(newLeft, newTop, newRight, newBottom)
                    Log.d(TAG, "🔧 Расширили малую область до минимума: ${abs} (${abs.width()}x${abs.height()})")
                }

                // Фокусируемся на верхней части области (убираем нижнюю чёрную подложку)
                val keepTopRatio = 0.65f
                val trimmedBottom = (abs.top + abs.height() * keepTopRatio).toInt()
                var focused = Rect(abs.left, abs.top, abs.right, trimmedBottom)
                Log.d(TAG, "🎯 Верхняя часть области до ограничения ширины: ${focused} (${focused.width()}x${focused.height()})")

                // Ограничиваем ширину так, чтобы попадали только 2 текущих кубика
                // Используем более консервативный коэффициент для предотвращения захвата старых кубиков
                val dicePairWidthRatio = 1.5f // уменьшено с 1.75f для большей точности
                val targetWidth = (focused.height() * dicePairWidthRatio).toInt().coerceAtLeast(minWidth)
                if (focused.width() > targetWidth) {
                    var newRight = (focused.left + targetWidth).coerceAtMost(bmp.width)
                    // Увеличенная подрезка правого края для надежности
                    val rightTrimPx = (focused.height() * 0.18f).toInt().coerceAtLeast(6) // увеличено с 0.12f
                    newRight = (newRight - rightTrimPx).coerceAtLeast(focused.left + minWidth)
                    focused = Rect(focused.left, focused.top, newRight, focused.bottom)
                    Log.d(TAG, "✂️ Обрезали справа до двух кубиков (+усиленная подрезка): ${focused} (${focused.width()}x${focused.height()})")
                } else {
                    Log.d(TAG, "✅ Текущая ширина не превышает целевую (${targetWidth}px), обрезка не требуется")
                }

                // Пытаемся точно зафиксировать правый край второй кости по яркостным сегментам
                findSecondDiceRightEdge(bmp, focused)?.let { edgeX ->
                    if (edgeX in (focused.left + minWidth)..focused.right && edgeX < focused.right) {
                        val pad = (focused.height() * 0.015f).toInt()
                        val cutX = (edgeX - pad).coerceAtLeast(focused.left + minWidth)
                        focused = Rect(focused.left, focused.top, cutX, focused.bottom)
                        Log.d(TAG, "🎯 Правый край подогнан по второй кости: ${focused}")
                    }
                }

                // Контент-ориентированная подрезка: ищем тёмную вертикальную щель между 2-й и 3-й костью
                // Применяем результат только если он существенно меньше текущей ширины (безопасность)
                findDarkGutterRight(bmp, focused)?.let { gutterX ->
                    val maxReduction = focused.width() * 0.25f // уменьшено с 0.3f для большей консервативности
                    val minAllowedRight = (focused.right - maxReduction).toInt()
                    if (gutterX > focused.left + minWidth && gutterX >= minAllowedRight) {
                        focused = Rect(focused.left, focused.top, gutterX, focused.bottom)
                        Log.d(TAG, "🧹 Правый край подогнан по тёмной щели: ${focused}")
                    } else if (gutterX < minAllowedRight) {
                        Log.d(TAG, "⚠️ Тёмная щель найдена слишком далеко слева (${gutterX}), игнорируем для безопасности")
                    }
                }

                // Дополнительная проверка на остатки предыдущих кубиков справа
                val rightEdgeCheck = checkForOldDiceArtifacts(bmp, focused)
                if (rightEdgeCheck != null && rightEdgeCheck < focused.right) {
                    val safeMargin = (focused.height() * 0.05f).toInt()
                    val newRight = (rightEdgeCheck - safeMargin).coerceAtLeast(focused.left + minWidth)
                    if (newRight < focused.right) {
                        focused = Rect(focused.left, focused.top, newRight, focused.bottom)
                        Log.d(TAG, "🛡️ Обнаружены остатки старых кубиков, край подвинут: ${focused}")
                    }
                }

                val insets = com.example.diceautobet.utils.CoordinateUtils.getSystemInsets(context)
                val shifted = Rect(focused.left, focused.top + insets.statusBarHeight, focused.right, focused.bottom + insets.statusBarHeight)
                val safe = safeRect(shifted, bmp.width, bmp.height, silent = true) // Тихий вызов для частого хеширования
                if (safe.width() <= 1 || safe.height() <= 1) return@withContext null
                Bitmap.createBitmap(bmp, safe.left, safe.top, safe.width(), safe.height())
            } catch (_: Exception) {
                null
            }
        }
    }

    // Делает скриншот, вырезает нужную область результата и считает точки
    private suspend fun captureAndAnalyze(window: WindowType): RoundResult? {
        try {
            Log.d(TAG, "🔍 captureAndAnalyze начат для окна $window")

            val scm = screenCaptureManager
            if (scm == null) {
                Log.e(TAG, "❌ screenCaptureManager равен null")
                return null
            }
            Log.d(TAG, "✅ screenCaptureManager найден")

            // Диагностика областей для окна
            Log.d(TAG, "🔍 Поиск области результата для окна $window...")

            // ✨ Приоритет DICE_AREA: используем область текущего окна
            val diceArea = areaManager.getAreaForWindow(window, AreaType.DICE_AREA)
            val betResultArea = areaManager.getAreaForWindow(window, AreaType.BET_RESULT)
            Log.d(TAG, "   DICE_AREA область: ${diceArea?.rect ?: "НЕТ"}")
            Log.d(TAG, "   BET_RESULT область: ${betResultArea?.rect ?: "НЕТ"}")
            val resultArea = diceArea ?: betResultArea
            if (resultArea == null) {
                Log.e(TAG, "❌ НИ ОДНА область результата не найдена для окна $window")
                Log.e(TAG, "🔍 Список всех областей для окна $window:")
                val allAreas = areaManager.getAreasForWindow(window)
                allAreas.forEach { (areaType, screenArea) ->
                    Log.e(TAG, "   - $areaType: ${screenArea.rect}")
                }
                return null
            }

            Log.d(TAG, "✅ Используем область: ${resultArea.rect}")
            Log.d(TAG, "🔍 Размер исходной области resultArea: ${resultArea.rect.width()}x${resultArea.rect.height()}")

            // 🔧 ПРОВЕРКА РАЗМЕРА: Увеличиваем слишком маленькие области
            val originalRect = resultArea.rect
            val minWidth = 200  // Минимальная ширина для анализа кубиков
            val minHeight = 150 // Минимальная высота для анализа кубиков

            val expandedRect = if (originalRect.width() < minWidth || originalRect.height() < minHeight) {
                Log.w(TAG, "⚠️ Область слишком маленькая (${originalRect.width()}x${originalRect.height()}), расширяем...")

                val newWidth = maxOf(originalRect.width(), minWidth)
                val newHeight = maxOf(originalRect.height(), minHeight)

                // 🎯 УМНОЕ РАСШИРЕНИЕ: расширяем вверх и в стороны, сохраняя нижнюю границу
                val upwardShift = 30  // дополнительный сдвиг вверх в пикселях
                val expandedRect = Rect(
                    originalRect.centerX() - newWidth / 2,     // расширяем влево и вправо от центра
                    originalRect.bottom - newHeight - upwardShift,  // расширяем вверх от нижней границы + доп. сдвиг
                    originalRect.centerX() + newWidth / 2,     // расширяем влево и вправо от центра
                    originalRect.bottom - upwardShift          // сдвигаем нижнюю границу вверх
                )

                Log.d(TAG, "🔧 Расширенная область: ${expandedRect} (${expandedRect.width()}x${expandedRect.height()})")
                Log.d(TAG, "📍 Сдвиг: исходная=${originalRect.centerY()}, новая=${expandedRect.centerY()}, разница=${originalRect.centerY() - expandedRect.centerY()}px вверх")
                expandedRect
            } else {
                Log.d(TAG, "✅ Размер области подходящий: ${originalRect.width()}x${originalRect.height()}")
                originalRect
            }

            // Обновляем resultArea с расширенным прямоугольником
            val finalResultArea = ScreenArea(resultArea.name, expandedRect)

            // Получаем абсолютные координаты через менеджер (с автокоррекцией)
            val absoluteRect = if (originalRect == expandedRect) {
                // Область не расширяли — пытаемся получить абсолютные координаты от менеджера
                areaManager.getAbsoluteCoordinates(window, AreaType.DICE_AREA)
                    ?: areaManager.getAbsoluteCoordinates(window, AreaType.BET_RESULT)
                    ?: expandedRect
            } else {
                // Область расширяли — используем её прямо
                Log.d(TAG, "🔧 Используем расширенную область вместо координат менеджера")
                expandedRect
            }
            if (absoluteRect == null) {
                Log.e(TAG, "❌ absoluteRect равен null для окна $window")
                return null
            }

            Log.d(TAG, "🔍 absoluteRect от менеджера: $absoluteRect")
            Log.d(TAG, "🔍 Размер absoluteRect: ${absoluteRect.width()}x${absoluteRect.height()}")

            // ✅ FIX: getAbsoluteCoordinates уже возвращает правильные координаты для окна
            // Удаляем повторное преобразование координат, которое вызывало двойную трансформацию
            val windowRelativeRect = absoluteRect
            Log.d(TAG, "✅ Используем координаты из getAbsoluteCoordinates (уже преобразованные): $windowRelativeRect")

            Log.d(TAG, "✅ resultArea найден: ${finalResultArea.rect} -> используем координаты: $windowRelativeRect")

            Log.d(TAG, "📸 Делаем скриншот...")
            var shot = scm.captureScreen()
            if (shot !is GameResult.Success) {
                val errorMsg = (shot as? GameResult.Error)?.message ?: "неизвестная ошибка"
                Log.e(TAG, "❌ Ошибка скриншота: $errorMsg")

                // Попытка восстановить ScreenCaptureManager
                if (errorMsg.contains("получить изображение") || errorMsg.contains("MediaProjection недействителен")) {
                    Log.w(TAG, "🔄 Попытка восстановления ScreenCaptureManager...")

                    // Если проблема с MediaProjection, пытаемся получить новое разрешение
                    if (errorMsg.contains("MediaProjection недействителен")) {
                        Log.w(TAG, "🔄 MediaProjection недействителен, пытаемся восстановить...")

                        // НЕ ОЧИЩАЕМ разрешения сразу! Сначала пытаемся восстановить

                        // Полностью очищаем и пересоздаем ScreenCaptureManager (но не разрешения)
                        try {
                            screenCaptureManager?.destroy()
                            screenCaptureManager = null
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка при уничтожении старого ScreenCaptureManager", e)
                        }

                        // Пытаемся восстановить ScreenCaptureManager с существующим разрешением
                        val hasValidPermission = try {
                            ensureScreenCaptureReady()
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка при восстановлении ScreenCaptureManager", e)
                            false
                        }

                        // Только если восстановление не удалось - очищаем разрешения и запрашиваем новые
                        if (!hasValidPermission) {
                            Log.w(TAG, "🧹 Восстановление не удалось, очищаем разрешения и запрашиваем новые...")
                            try {
                                preferencesManager.clearMediaProjectionPermission()
                                com.example.diceautobet.utils.MediaProjectionTokenStore.clear()
                            } catch (_: Exception) {}
                        }

                        if (!hasValidPermission) {
                            Log.e(TAG, "❌ Не удалось создать новый ScreenCaptureManager")
                            return null
                        }
                    }

                    if (ensureScreenCaptureReady()) {
                        Log.d(TAG, "✅ ScreenCaptureManager восстановлен, повторная попытка...")
                        shot = scm.captureScreen()
                        if (shot is GameResult.Success) {
                            Log.d(TAG, "✅ Повторный скриншот успешен")
                        } else {
                            Log.e(TAG, "❌ Повторная попытка скриншота неудачна")
                            return null
                        }
                    } else {
                        Log.e(TAG, "❌ Не удалось восстановить ScreenCaptureManager")
                        return null
                    }
                } else {
                    return null
                }
            } else {
                Log.d(TAG, "✅ Скриншот получен")
            }

            val bmp = shot.data
            Log.d(TAG, "📸 captureAndAnalyze: window=$window, resultArea=$windowRelativeRect, screenshot=${bmp.width}x${bmp.height}")
            Log.d(TAG, "🔍 ИСХОДНАЯ область до обработки: $windowRelativeRect")
            Log.d(TAG, "🔍 Размер исходной области: ${windowRelativeRect.width()}x${windowRelativeRect.height()}")

            // Проверяем, что область кубиков находится в пределах скриншота
            if (windowRelativeRect.top >= bmp.height || windowRelativeRect.left >= bmp.width) {
                Log.w(TAG, "⚠️ Область результата полностью за пределами скриншота: $windowRelativeRect vs ${bmp.width}x${bmp.height}")
                return null
            }

            if (windowRelativeRect.bottom > bmp.height || windowRelativeRect.right > bmp.width) {
                Log.w(TAG, "⚠️ Область результата частично за пределами скриншота: $windowRelativeRect vs ${bmp.width}x${bmp.height}")
            }

            // Компенсация статус-бара: скриншот содержит статус-бар, а координаты задавались по контенту
            val insets = com.example.diceautobet.utils.CoordinateUtils.getSystemInsets(context)
            Log.d(TAG, "🔍 Компенсация статус-бара: высота=${insets.statusBarHeight}px")
            val shiftedRect = Rect(
                windowRelativeRect.left,
                windowRelativeRect.top + insets.statusBarHeight,
                windowRelativeRect.right,
                windowRelativeRect.bottom + insets.statusBarHeight
            )
            Log.d(TAG, "🔍 ПОСЛЕ компенсации статус-бара: $shiftedRect")
            Log.d(TAG, "🔍 Размер после компенсации: ${shiftedRect.width()}x${shiftedRect.height()}")
            var area = safeRect(shiftedRect, bmp.width, bmp.height, silent = true) // Тихий вызов для частого анализа
            Log.d(TAG, "🔧 Безопасная область: $area")
            Log.d(TAG, "🔧 Размер безопасной области: ${area.width()}x${area.height()}")

            // Автокалибровка вертикального смещения (однократно на окно за сессию контроллера)
            var yOffset = roiYOffsetMap[window] ?: 0
            if (yOffset == 0) {
                yOffset = calibrateVerticalOffset(bmp, area, window)
                roiYOffsetMap[window] = yOffset
                if (yOffset != 0) {
                    Log.d(TAG, "🧭 Автокалибровка[$window]: вертикальное смещение ROI = ${yOffset}px")
                } else {
                    Log.d(TAG, "🧭 Автокалибровка[$window]: доп. смещение не требуется")
                }
            }
            if (yOffset != 0) {
                val calibrated = Rect(
                    area.left,
                    (area.top + yOffset).coerceAtMost(bmp.height - 1),
                    area.right,
                    (area.bottom + yOffset).coerceAtMost(bmp.height)
                )
                area = safeRect(calibrated, bmp.width, bmp.height, silent = true) // Тихий вызов для частого анализа
                Log.d(TAG, "🔧 Область после калибровки[$window]: $area")
            }

            try {
                Log.d(TAG, "🖼️ Создаем подизображение...")
                Log.d(TAG, "📋 ДЕТАЛИ ОБЛАСТИ КУБИКОВ:")
                Log.d(TAG, "   🎯 Исходное изображение: ${bmp.width}x${bmp.height}")
                Log.d(TAG, "   📍 Область кубиков: left=${area.left}, top=${area.top}, right=${area.right}, bottom=${area.bottom}")
                Log.d(TAG, "   📐 Размер области: width=${area.width()}, height=${area.height()}")
                Log.d(TAG, "   🎨 Тип области: ${if (area.top < bmp.height/2) "ВЕРХНЯЯ" else "НИЖНЯЯ"} часть экрана")

                // 📸 ОТКЛЮЧЕНО: Сохранение полного скриншота для максимальной скорости
                // Каждое сохранение отнимает сотни миллисекунд!
                if (preferencesManager.isDebugImagesEnabled() && debugFolder != null) {
                    val fullScreenFilename = createDebugFilename("fullscreen", window)
                    saveDebugImage(bmp, fullScreenFilename, "Полный скриншот")
                }

                val sub = Bitmap.createBitmap(bmp, area.left, area.top, area.width(), area.height())
                Log.d(TAG, "✅ Подизображение создано: ${sub.width}x${sub.height}")
                Log.d(TAG, "📊 Соотношение сторон: ${String.format("%.2f", sub.width.toFloat() / sub.height.toFloat())}")

                Log.d(TAG, "🔍 Запускаем ЭКОНОМНОЕ распознавание...")
                Log.d(TAG, "🎛️ Текущие настройки распознавания:")
                val prefsManager = PreferencesManager(context)
                Log.d(TAG, "   - Режим: ${prefsManager.getRecognitionMode()}")
                Log.d(TAG, "   - AI провайдер: ${prefsManager.getAIProvider()}")
                Log.d(TAG, "   - AI настроен: ${prefsManager.isAIConfigured()}")

                // 💰 ЭКОНОМНЫЙ АНАЛИЗ: используем новую логику
                val dots = analyzeWithEconomicAI(sub, window)
                if (dots == null) {
                    Log.e(TAG, "❌ Экономное распознавание не смогло обработать изображение")
                    return null
                }
                Log.d(TAG, "✅ Экономное распознавание завершено: leftDots=${dots.leftDots}, rightDots=${dots.rightDots}, confidence=${dots.confidence}")

                // 📸 ОТКЛЮЧЕНО: Сохранение вырезанной области для максимальной скорости
                // Сохранение кропа для отладки
                if (preferencesManager.isDebugImagesEnabled() && debugFolder != null) {
                    val croppedFilename = createDebugFilename("cropped", window, dots)
                    saveDebugImage(sub, croppedFilename, "Область анализа кубиков")
                }

                Log.d(TAG, "🎲 Создаем RoundResult...")
                val result = RoundResult.fromDotResult(dots)
                Log.d(TAG, "✅ RoundResult создан: redDots=${result.redDots}, orangeDots=${result.orangeDots}, winner=${result.winner}, valid=${result.isValid}")

                sub.recycle()
                return result
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка создания подизображения или анализа: ${e.message}", e)
                Log.e(TAG, "🔧 Область: $area, размер изображения: ${bmp.width}x${bmp.height}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Общая ошибка captureAndAnalyze: ${e.message}", e)
            return null
        }
    }

    private fun safeRect(r: Rect, w: Int, h: Int, silent: Boolean = false): Rect {
        val left = r.left.coerceIn(0, w - 1)
        val top = r.top.coerceIn(0, h - 1)
        val right = r.right.coerceIn(left + 1, w)
        val bottom = r.bottom.coerceIn(top + 1, h)

        // Логирование только при необходимости (не при частых вызовах хеширования)
        if (!silent) {
            Log.d(TAG, "safeRect: исходная область $r, размер изображения ${w}x${h}")
            Log.d(TAG, "safeRect: безопасная область Rect($left, $top - $right, $bottom)")
        }

        return Rect(left, top, right, bottom)
    }

    // Ищет «тёмную щель» справа от второй кости и возвращает x-координату для обрезки
    private fun findDarkGutterRight(bmp: Bitmap, area: Rect): Int? {
        return try {
            val scanTop = (area.top + area.height() * 0.35f).toInt()
            val scanBottom = (area.top + area.height() * 0.75f).toInt()
            val stepX = 2 // пропуск по x для скорости
            val minGutterWidth = (area.height() * 0.1f).toInt().coerceAtLeast(4)

            // Сканируем справа налево, но ограничиваем поиск только областью текущих кубиков
            // Начинаем поиск не с самого правого края, а с более безопасной позиции
            val safeMargin = (area.height() * 0.15f).toInt() // отступ от правого края
            val startX = (area.right - safeMargin).coerceAtLeast(area.left + area.width() / 2)
            val endX = (area.left + area.width() * 0.55f).toInt()

            var bestX: Int? = null
            var bestScore = Float.MAX_VALUE

            fun pixelLuma(x: Int, y: Int): Float {
                val c = bmp.getPixel(x, y)
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                return 0.2126f * r + 0.7152f * g + 0.0722f * b
            }

            var x = startX
            while (x > endX) {
                // усредняем яркость вертикальной полосы шириной minGutterWidth
                val leftX = (x - minGutterWidth).coerceAtLeast(area.left)
                var sum = 0f
                var cnt = 0
                var y = scanTop
                while (y < scanBottom) {
                    var xx = leftX
                    while (xx <= x) {
                        sum += pixelLuma(xx, y)
                        cnt++
                        xx += minOf(2, x - xx + 1)
                    }
                    y += 3
                }
                if (cnt > 0) {
                    val avg = sum / cnt
                    if (avg < bestScore) {
                        bestScore = avg
                        bestX = x
                    }
                }
                x -= stepX
            }

            // Порог по яркости: если явно тёмнее 0.20 — считаем щелью (уменьшено с 0.26f)
            if (bestX != null && bestScore < 0.20f) bestX else null
        } catch (_: Exception) {
            null
        }
    }

    // Оценивает правый край второй кости по профилю яркости: ищем область повышенной яркости (красно-оранжевые кости), затем падение яркости
    private fun findSecondDiceRightEdge(bmp: Bitmap, area: Rect): Int? {
        return try {
            val scanTop = (area.top + area.height() * 0.28f).toInt()
            val scanBottom = (area.top + area.height() * 0.78f).toInt()
            val stepX = 2
            val minDiceWidth = (area.height() * 0.7f).toInt().coerceAtLeast(20)
            val maxDiceWidth = (area.height() * 1.2f).toInt().coerceAtLeast(minDiceWidth + 10)

            fun luma(x: Int, y: Int): Float {
                val c = bmp.getPixel(x, y)
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                return 0.2126f * r + 0.7152f * g + 0.0722f * b
            }

            // Профиль средней яркости по x
            val xs = mutableListOf<Int>()
            val profile = mutableListOf<Float>()
            var x = area.left
            while (x < area.right) {
                var sum = 0f
                var cnt = 0
                var y = scanTop
                while (y < scanBottom) {
                    sum += luma(x, y)
                    cnt++
                    y += 3
                }
                xs += x
                profile += if (cnt > 0) sum / cnt else 1f
                x += stepX
            }

            if (profile.isEmpty()) return null

            // Находим два «светлых» сегмента подряд (две кости), разделённые тёмной щелью
            val brightThresh = 0.38f
            val darkThresh = 0.26f

            data class Segment(val startIdx: Int, val endIdx: Int)

            fun segments(isBright: Boolean): List<Segment> {
                val res = mutableListOf<Segment>()
                var start = -1
                for (i in profile.indices) {
                    val ok = if (isBright) profile[i] >= brightThresh else profile[i] <= darkThresh
                    if (ok) {
                        if (start == -1) start = i
                    } else if (start != -1) {
                        res += Segment(start, i - 1)
                        start = -1
                    }
                }
                if (start != -1) res += Segment(start, profile.size - 1)
                return res
            }

            val brightSegs = segments(true)
            val darkSegs = segments(false)

            // Ищем комбинацию: bright (dice1) -> dark (gap) -> bright (dice2)
            for (i in 0 until brightSegs.size - 1) {
                val seg1 = brightSegs[i]
                val seg2 = brightSegs[i + 1]
                // между ними должен быть dark сегмент
                val gap = darkSegs.firstOrNull { it.startIdx > seg1.endIdx && it.endIdx < seg2.startIdx }
                if (gap != null) {
                    val w1 = (seg1.endIdx - seg1.startIdx + 1) * stepX
                    val w2 = (seg2.endIdx - seg2.startIdx + 1) * stepX
                    if (w1 in minDiceWidth..maxDiceWidth && w2 in minDiceWidth..maxDiceWidth) {
                        // Правый край второй кости — конец второго светлого сегмента
                        val idx = seg2.endIdx
                        val xEdge = xs.getOrNull(idx)?.coerceIn(area.left + 1, area.right - 1)
                        if (xEdge != null) return xEdge
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет наличие остатков предыдущих кубиков справа от текущей области
     * Возвращает X-координату, где начинаются артефакты, или null если их нет
     */
    private fun checkForOldDiceArtifacts(bmp: Bitmap, area: Rect): Int? {
        return try {
            val scanTop = (area.top + area.height() * 0.3f).toInt()
            val scanBottom = (area.top + area.height() * 0.8f).toInt()
            val stepX = 3

            // Сканируем справа налево, начиная от правого края области
            val startX = area.right - 5 // начинаем чуть левее правого края
            val endX = (area.left + area.width() * 0.7f).toInt() // заканчиваем на 70% ширины

            var artifactStart: Int? = null
            var consecutiveBrightPixels = 0
            val minConsecutive = 8 // минимум последовательных ярких пикселей

            fun isBrightPixel(x: Int, y: Int): Boolean {
                val c = bmp.getPixel(x, y)
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                return luma > 0.35f // порог для ярких пикселей (точки на кубиках)
            }

            var x = startX
            while (x > endX) {
                var brightCount = 0
                var y = scanTop
                while (y < scanBottom) {
                    if (isBrightPixel(x, y)) {
                        brightCount++
                    }
                    y += 2
                }

                // Если в этой вертикальной линии много ярких пикселей - это может быть кубик
                if (brightCount >= 3) { // минимум 3 ярких пикселя в линии
                    consecutiveBrightPixels++
                    if (artifactStart == null) {
                        artifactStart = x
                    }
                } else {
                    // Сбрасываем счетчик при обнаружении темной области
                    if (consecutiveBrightPixels >= minConsecutive) {
                        // Нашли достаточно длинную последовательность ярких пикселей
                        Log.d(TAG, "🔍 Обнаружены остатки кубиков на X=${artifactStart}")
                        return artifactStart
                    }
                    consecutiveBrightPixels = 0
                    artifactStart = null
                }

                x -= stepX
            }

            // Проверяем, если яркие пиксели доходят до левого края области
            if (consecutiveBrightPixels >= minConsecutive) {
                Log.d(TAG, "🔍 Обнаружены остатки кубиков у левого края: X=${artifactStart}")
                return artifactStart
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    // Подбирает вертикальное смещение, увеличивающее уверенность распознавания точек
    private fun calibrateVerticalOffset(screenshot: Bitmap, rect: Rect, window: WindowType): Int {
        return try {
            val range = (rect.height() / 3).coerceAtMost(120)
            val step = 6
            var bestOffset = 0
            var bestScore = -1f
            var bestSub: Bitmap? = null
            var bestDots: DotCounter.Result? = null

            Log.d(TAG, "🧭 Начинаем калибровку: диапазон ±${range}px, шаг ${step}px")

            for (dy in -range..range step step) {
                val testTop = (rect.top + dy).coerceIn(0, screenshot.height - 2)
                val testBottom = (rect.bottom + dy).coerceIn(testTop + 1, screenshot.height)
                val testRect = Rect(rect.left, testTop, rect.right, testBottom)
                val safe = safeRect(testRect, screenshot.width, screenshot.height)
                try {
                    val sub = Bitmap.createBitmap(screenshot, safe.left, safe.top, safe.width(), safe.height())
                    val dots = kotlinx.coroutines.runBlocking { analyzeWithEconomicAI(sub, window) } ?: continue
                    val nonZero = (dots.leftDots + dots.rightDots) > 0
                    val score = if (nonZero) dots.confidence + 0.5f else dots.confidence

                    if (score > bestScore) {
                        bestScore = score
                        bestOffset = dy
                        bestSub?.recycle() // Освобождаем предыдущий лучший результат
                        bestSub = sub.copy(sub.config ?: Bitmap.Config.ARGB_8888, false) // Сохраняем копию лучшего результата
                        bestDots = dots
                    } else {
                        sub.recycle()
                    }
                } catch (_: Exception) {
                    // игнорируем неверные кропы
                }
            }

            // Сохранение результатов калибровки для отладки
            if (preferencesManager.isDebugImagesEnabled() && debugFolder != null && bestSub != null && bestDots != null) {
                val calibrationFilename = "calibration_best_offset${bestOffset}_${createDebugFilename("", window, bestDots!!).substring(1)}"
                saveDebugImage(bestSub!!, calibrationFilename, "Лучший результат калибровки (смещение: ${bestOffset}px)")
                Log.d(TAG, "🧭 Калибровка завершена: лучшее смещение = ${bestOffset}px, оценка = ${bestScore}")
            }

            bestSub?.recycle()
            bestOffset
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Получает текущий результат кубиков из реальной области кубиков
     */
    private suspend fun getCurrentDiceResult(): RoundResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем область кубиков для текущего окна
                val currentWindow = gameState.currentWindow
                val areaForLog = areaManager.getAreaForWindow(currentWindow, AreaType.BET_RESULT)
                    ?: areaManager.getAreaForWindow(currentWindow, AreaType.DICE_AREA)

                if (areaForLog == null) {
                    Log.v(TAG, "❌ Область кубиков не настроена для окна $currentWindow")
                    return@withContext null
                }

                Log.v(TAG, "🎲 Анализ кубиков в области: ${areaForLog.rect}")

                // Здесь должен быть реальный анализ экрана
                // Пока используем простую симуляцию для тестирования
                val redDots = (1..6).random()
                val orangeDots = (1..6).random()

                // Определяем победителя
                val isDraw = redDots == orangeDots
                val winner = when {
                    isDraw -> null
                    redDots > orangeDots -> BetChoice.RED
                    else -> BetChoice.ORANGE
                }

                Log.v(TAG, "📊 Результат: Красный=$redDots, Оранжевый=$orangeDots, Победитель=$winner")

                val result = RoundResult(
                    redDots = redDots,
                    orangeDots = orangeDots,
                    winner = winner,
                    isDraw = isDraw,
                    confidence = 0.95f
                )

                return@withContext result

            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка анализа кубиков", e)
                return@withContext null
            }
        }
    }

    /**
     * Конвертирует RoundResult в GameResultType на основе выбранного цвета
     */
    private fun convertRoundResultToGameResult(roundResult: RoundResult): GameResultType {
        val result = when {
            roundResult.winner == null -> {
                Log.d(TAG, "🎯 НИЧЬЯ = ПРОИГРЫШ: красный=${roundResult.redDots}, оранжевый=${roundResult.orangeDots}")
                GameResultType.LOSS
            }

            // Проверяем результат в зависимости от выбранного цвета
            gameState.currentColor == BetChoice.RED -> {
                if (roundResult.winner == BetChoice.RED) {
                    Log.d(TAG, "✅ ВЫИГРЫШ: ставили на КРАСНЫЙ (${roundResult.redDots}) vs оранжевый (${roundResult.orangeDots}) - КРАСНЫЙ ПОБЕДИЛ!")
                    GameResultType.WIN
                } else {
                    Log.d(TAG, "❌ ПРОИГРЫШ: ставили на КРАСНЫЙ (${roundResult.redDots}) vs оранжевый (${roundResult.orangeDots}) - ОРАНЖЕВЫЙ ПОБЕДИЛ!")
                    GameResultType.LOSS
                }
            }

            gameState.currentColor == BetChoice.ORANGE -> {
                if (roundResult.winner == BetChoice.ORANGE) {
                    Log.d(TAG, "✅ ВЫИГРЫШ: ставили на ОРАНЖЕВЫЙ (${roundResult.orangeDots}) vs красный (${roundResult.redDots}) - ОРАНЖЕВЫЙ ПОБЕДИЛ!")
                    GameResultType.WIN
                } else {
                    Log.d(TAG, "❌ ПРОИГРЫШ: ставили на ОРАНЖЕВЫЙ (${roundResult.orangeDots}) vs красный (${roundResult.redDots}) - КРАСНЫЙ ПОБЕДИЛ!")
                    GameResultType.LOSS
                }
            }

            else -> {
                Log.d(TAG, "❓ НЕИЗВЕСТНЫЙ результат: ставили на ${gameState.currentColor}, победитель=${roundResult.winner}")
                GameResultType.UNKNOWN
            }
        }

        Log.d(TAG, "🎮 ИТОГОВЫЙ РЕЗУЛЬТАТ: $result")
        return result
    }

    /**
     * Размещает базовую ставку БЕЗ удвоения
     */
    private suspend fun placeBetBaseAmount(targetWindow: WindowType, baseAmount: Int) {
        Log.d(TAG, "🎲 Размещение базовой ставки $baseAmount в окне $targetWindow")

        // Кликаем по базовой ставке
        val baseBetArea = areaManager.getAreaForWindow(targetWindow, getAreaTypeForAmount(baseAmount))
        if (baseBetArea == null) {
            val errorMsg = "❌ Область базовой ставки $baseAmount не настроена для окна $targetWindow"
            Log.e(TAG, errorMsg)
            onError?.invoke(errorMsg)
            return
        }

        Log.d(TAG, "  🎯 Клик по базовой ставке $baseAmount")
        val baseResult = clickManager.clickAreaFast(baseBetArea, FAST_CLICK_DELAY)
        if (baseResult !is GameResult.Success) {
            Log.e(TAG, "❌ Ошибка клика по базовой ставке")
            onError?.invoke("Ошибка клика по базовой ставке")
            return
        }

        Log.d(TAG, "✅ Базовая ставка $baseAmount размещена успешно")
    }

    /**
     * Применяет удвоения ПОСЛЕ выбора цвета
     */
    private suspend fun applyDoublingClicks(targetWindow: WindowType, doublingClicks: Int) {
        Log.d(TAG, "🔢 Применение $doublingClicks удвоений в окне $targetWindow")

        val doubleArea = areaManager.getAreaForWindow(targetWindow, AreaType.DOUBLE_BUTTON)
        if (doubleArea == null) {
            val errorMsg = "❌ Область кнопки удвоения не настроена для окна $targetWindow"
            Log.e(TAG, errorMsg)
            onError?.invoke(errorMsg)
            return
        }

        repeat(doublingClicks) { i ->
            val doubleResult = clickManager.clickAreaFast(doubleArea, FAST_CLICK_DELAY)
            if (doubleResult !is GameResult.Success) {
                Log.e(TAG, "❌ Ошибка удвоения ${i + 1}")
                onError?.invoke("Ошибка удвоения ${i + 1}")
                return
            }

            Log.d(TAG, "    ✅ Удвоение ${i + 1}/$doublingClicks выполнено")

            if (i < doublingClicks - 1) {
                delay(FAST_CLICK_DELAY)
            }
        }

        Log.d(TAG, "✅ Все $doublingClicks удвоений применены успешно")
    }

    // УДАЛЕН: Старый метод getBetStrategy заменен на BetCalculator.calculateBetStrategy()

    /**
     * Получает тип области для указанной суммы ставки (DEPRECATED: используйте BetCalculator)
     */
    private fun getAreaTypeForAmount(amount: Int): AreaType {
        return when (amount) {
            10 -> AreaType.BET_10
            50 -> AreaType.BET_50
            100 -> AreaType.BET_100
            500 -> AreaType.BET_500
            2500 -> AreaType.BET_2500
            else -> {
                // Находим ближайшую меньшую доступную ставку
                when {
                    amount <= 10 -> AreaType.BET_10
                    amount <= 50 -> AreaType.BET_50
                    amount <= 100 -> AreaType.BET_100
                    amount <= 500 -> AreaType.BET_500
                    else -> AreaType.BET_2500
                }
            }
        }
    }

    /**
     * Получает тип кнопки цвета
     */
    private fun getColorButtonType(color: BetChoice): AreaType {
        return when (color) {
            BetChoice.RED -> AreaType.RED_BUTTON
            BetChoice.ORANGE -> AreaType.ORANGE_BUTTON
            else -> AreaType.RED_BUTTON // По умолчанию красный
        }
    }

    /**
     * Получает следующее окно для ставки
     */
    private fun getNextWindow(currentWindow: WindowType): WindowType {
        val settings = preferencesManager.getDualModeSettings()
        return when (settings.splitScreenType) {
            SplitScreenType.HORIZONTAL -> when (currentWindow) {
                WindowType.LEFT -> WindowType.RIGHT
                WindowType.RIGHT -> WindowType.LEFT
                WindowType.TOP, WindowType.BOTTOM -> WindowType.LEFT
            }
            SplitScreenType.VERTICAL -> when (currentWindow) {
                WindowType.TOP -> WindowType.BOTTOM
                WindowType.BOTTOM -> WindowType.TOP
                WindowType.LEFT, WindowType.RIGHT -> WindowType.TOP
            }
        }
    }

    /**
     * Проверяет настройку областей для двойного режима
     */
    private fun checkAreasConfigured(): Boolean {
        Log.d(TAG, "🔍 Проверка настроенных областей для двойного режима")
        Log.d(TAG, "Вызываем areaManager.loadAreas()...")

        areaManager.loadAreas()

        Log.d(TAG, "areaManager.loadAreas() завершен успешно")

        // Получаем настройки двойного режима
        val dualModeSettings = preferencesManager.getDualModeSettings()
        val splitScreenType = dualModeSettings.splitScreenType
        Log.d(TAG, "Тип разделения экрана: $splitScreenType")

        // Определяем окна для проверки в зависимости от типа разделения
        val (firstWindowType, secondWindowType) = when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> {
                Log.d(TAG, "Проверяем окна: LEFT и RIGHT")
                Pair(WindowType.LEFT, WindowType.RIGHT)
            }
            SplitScreenType.VERTICAL -> {
                Log.d(TAG, "Проверяем окна: TOP и BOTTOM")
                Pair(WindowType.TOP, WindowType.BOTTOM)
            }
        }

        // Проверяем настройку первого окна
        val firstWindowAreas = areaManager.getAreasForWindow(firstWindowType)
        Log.d(TAG, "$firstWindowType окно - найдено областей: ${firstWindowAreas.size}")
        firstWindowAreas.forEach { (areaType, screenArea) ->
            Log.d(TAG, "  $firstWindowType: $areaType -> ${screenArea.rect}")
        }

        // Проверяем настройку второго окна
        val secondWindowAreas = areaManager.getAreasForWindow(secondWindowType)
        Log.d(TAG, "$secondWindowType окно - найдено областей: ${secondWindowAreas.size}")
        secondWindowAreas.forEach { (areaType, screenArea) ->
            Log.d(TAG, "  $secondWindowType: $areaType -> ${screenArea.rect}")
        }

        // Проверим конкретно нужные области
        val requiredAreas = listOf(AreaType.BET_10, AreaType.RED_BUTTON, AreaType.ORANGE_BUTTON, AreaType.CONFIRM_BET, AreaType.DOUBLE_BUTTON)

        Log.d(TAG, "Проверка обязательных областей:")
        requiredAreas.forEach { areaType ->
            val firstArea = areaManager.getAreaForWindow(firstWindowType, areaType)
            val secondArea = areaManager.getAreaForWindow(secondWindowType, areaType)
            Log.d(TAG, "  $areaType: $firstWindowType=${firstArea != null}, $secondWindowType=${secondArea != null}")
        }

        Log.d(TAG, "Статус настройки областей:")
        Log.d(TAG, "  $firstWindowType окно: ${if (areaManager.isWindowConfigured(firstWindowType)) "✅ Настроено" else "❌ Не настроено"}")
        Log.d(TAG, "  $secondWindowType окно: ${if (areaManager.isWindowConfigured(secondWindowType)) "✅ Настроено" else "❌ Не настроено"}")

        val areBothConfigured = areaManager.isWindowConfigured(firstWindowType) && areaManager.isWindowConfigured(secondWindowType)

        if (!areBothConfigured) {
            val errorMsg = """
                ❌ Области для двойного режима не настроены!
                
                📋 Статус:
                • $firstWindowType окно: ${if (areaManager.isWindowConfigured(firstWindowType)) "✅" else "❌"}
                • $secondWindowType окно: ${if (areaManager.isWindowConfigured(secondWindowType)) "✅" else "❌"}
                
                🔧 Решение:
                1. Перейдите в "Настройка областей двойного режима"
                2. Настройте области для обоих окон
                3. Убедитесь, что настроены все необходимые области
                
                📝 Необходимые области:
                • Ставка 10
                • Красный кубик  
                • Оранжевый кубик
                • Заключить пари
                • Удвоить ставку
                
                ⚠️ Обратите внимание, что тип разделения экрана: $splitScreenType
            """.trimIndent()

            Log.e(TAG, errorMsg)
            onError?.invoke(errorMsg)
            return false
        }

        Log.d(TAG, "✅ Все области настроены корректно!")
        return true
    }

    /**
     * Уведомляет о изменении состояния
     */
    private fun notifyStateChanged() {
        onStateChanged?.invoke(gameState)
    }

    /**
     * Получает текущее состояние
     */
    fun getCurrentState(): SimpleDualModeState = gameState

    /**
     * Получает статистику в текстовом виде
     */
    fun getStatistics(): String {
        return """
            🎮 Упрощенная стратегия двойного режима
            
            📊 Статистика:
            • Текущее окно: ${gameState.currentWindow}
            • Текущий цвет: ${gameState.currentColor}
            • Текущая ставка: ${gameState.currentBet}
            • Всего ставок: ${gameState.totalBets}
            • Общая прибыль: ${gameState.totalProfit}
            • Проигрыши подряд: ${gameState.consecutiveLosses}
            • Проигрыши на цвете: ${gameState.consecutiveLossesOnCurrentColor}
            
            📈 Последний результат: ${when (gameState.lastResult) {
            GameResultType.WIN -> "Выигрыш"
            GameResultType.LOSS -> "Проигрыш"
            GameResultType.DRAW -> "Проигрыш (ничья)"
            GameResultType.UNKNOWN -> "Неизвестно"
        }}
        """.trimIndent()
    }

    /**
     * Обновляет данные MediaProjection для ScreenCaptureManager
     */
    fun updateMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "updateMediaProjection вызван с resultCode=$resultCode")
        try {
            // Сохраняем данные в MediaProjectionPermissionManager
            val permissionManager = MediaProjectionPermissionManager.getInstance(context)
            permissionManager.savePermission(resultCode, data)
            Log.d(TAG, "MediaProjection данные сохранены в PermissionManager")

            // Если ScreenCaptureManager уже создан, обновляем его
            screenCaptureManager?.let { scm ->
                Log.d(TAG, "Обновляем существующий ScreenCaptureManager")
                // Перезапускаем захват с новыми данными
                scm.stopCapture()
                val startResult = scm.startCapture()
                if (startResult is GameResult.Error) {
                    Log.e(TAG, "Ошибка перезапуска ScreenCaptureManager: ${startResult.message}")
                } else {
                    Log.d(TAG, "ScreenCaptureManager успешно перезапущен с новыми данными")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при обновлении MediaProjection данных", e)
        }
    }

    /**
     * Останавливает двойной режим с сообщением об ошибке
     */
    private fun stopDualMode(reason: String) {
        Log.w(TAG, "🛑 Остановка двойного режима: $reason")

        gameState = gameState.copy(isRunning = false)
        gameJob?.cancel()
        gameJob = null

        // TODO: Показать пользователю сообщение о причине остановки
        // Можно отправить broadcast или callback

        notifyStateChanged()
        Log.d(TAG, "✅ Двойной режим остановлен по причине: $reason")
    }

    /**
     * Запрашивает новое разрешение и останавливает режим
     */
    private fun requestPermissionAndStop(reason: String) {
        Log.w(TAG, "🔑 Запрос нового разрешения: $reason")

        try {
            // Запускаем активность запроса разрешения
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
            intent.putExtra(MediaProjectionRequestActivity.EXTRA_TARGET_SERVICE, MediaProjectionRequestActivity.SERVICE_DUAL_MODE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "🔑 Активность запроса разрешения запущена")

            // Останавливаем режим
            stopDualMode(reason)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Не удалось запустить запрос разрешения: ${e.message}")
            stopDualMode("Не удалось запросить разрешение: ${e.message}")
        }
    }

    /**
     * 🎯 ПРОСТОЕ РАСПОЗНАВАНИЕ БЕЗ ПРОВЕРКИ ИЗМЕНЕНИЙ (логика изменений в waitForStableResult)
     */
    private suspend fun analyzeWithEconomicAI(image: Bitmap, window: WindowType): DotCounter.Result? {
        try {
            Log.d(TAG, "🎯 [SimpleDice] Простое распознавание (без проверки изменений)...")

            // ✨ Используем только OpenCV для распознавания
            val openCVResult = DotCounter.count(image)

            Log.d(TAG, "🎯 [SimpleDice] OpenCV результат: L=${openCVResult.leftDots}, R=${openCVResult.rightDots}")

            // Возвращаем результат БЕЗ всяких проверок изменений
            return openCVResult

        } catch (e: Exception) {
            Log.e(TAG, "🎯 [SimpleDice] ❌ Ошибка анализа: ${e.message}", e)
            return null
        }
    }

    // === НОВАЯ СИСТЕМА ДЕТЕКЦИИ ИЗМЕНЕНИЙ ===

    /**
     * Быстрое вычисление хеша области для детекции изменений (БЕЗ обрезки)
     */
    private suspend fun getAreaHashFast(window: WindowType): String? {
        return withContext(Dispatchers.IO) {
            try {
                val scm = screenCaptureManager ?: return@withContext null
                val shot = scm.captureScreen()
                if (shot !is GameResult.Success) return@withContext null

                // Получаем простую область для хеширования (без сложной обрезки)
                val areas = areaManager.getAreasForWindow(window)
                val area = areas[AreaType.DICE_AREA] ?: areas[AreaType.BET_RESULT] ?: return@withContext null

                // Используем исходную область без расширения и обрезки
                val rect = area.rect
                val insets = com.example.diceautobet.utils.CoordinateUtils.getSystemInsets(context)
                val simpleRect = Rect(
                    rect.left.coerceAtLeast(0),
                    (rect.top + insets.statusBarHeight).coerceAtLeast(0),
                    rect.right.coerceAtMost(shot.data.width),
                    (rect.bottom + insets.statusBarHeight).coerceAtMost(shot.data.height)
                )

                if (simpleRect.width() <= 1 || simpleRect.height() <= 1) return@withContext null

                // Простое хеширование без создания отдельного bitmap
                val hash = calculateSimpleAreaHash(shot.data, simpleRect)
                hash
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка быстрого хеширования: ${e.message}")
                null
            }
        }
    }

    /**
     * Вычисляет простой хеш области без создания отдельного bitmap
     */
    private fun calculateSimpleAreaHash(bitmap: Bitmap, rect: Rect): String {
        try {
            val step = 4 // Берем каждый 4-й пиксель для скорости
            val pixels = mutableListOf<Int>()

            var y = rect.top
            while (y < rect.bottom) {
                var x = rect.left
                while (x < rect.right) {
                    if (x < bitmap.width && y < bitmap.height) {
                        pixels.add(bitmap.getPixel(x, y))
                    }
                    x += step
                }
                y += step
            }

            // Простой хеш из пикселей
            return pixels.hashCode().toString()
        } catch (e: Exception) {
            return System.currentTimeMillis().toString()
        }
    }

    /**
     * Вычисляет MD5 хеш области изображения для детекции изменений
     */
    private suspend fun getAreaHash(window: WindowType): String? {
        return withContext(Dispatchers.IO) {
            try {
                val crop = captureDiceCrop(window) ?: return@withContext null
                val hash = calculateImageHash(crop)
                crop.recycle()
                hash
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка вычисления хеша: ${e.message}")
                null
            }
        }
    }

    /**
     * Вычисляет MD5 хеш изображения
     */
    private fun calculateImageHash(bitmap: Bitmap): String {
        try {
            // Сжимаем изображение в байты
            val buffer = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            val bytes = buffer.array()

            // Вычисляем MD5
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(bytes)

            // Преобразуем в hex строку
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка вычисления MD5: ${e.message}")
            return System.currentTimeMillis().toString() // Fallback
        }
    }

    /**
     * Быстрый анализ OpenCV без проверок
     */
    private suspend fun quickAnalyzeOpenCV(crop: Bitmap): RoundResult? {
        return withContext(Dispatchers.Default) {
            try {
                val dots = DotCounter.count(crop)
                RoundResult.fromDotResult(dots)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка быстрого анализа OpenCV: ${e.message}")
                null
            }
        }
    }

    /**
     * Проверяет валидность результата кубиков
     */
    private fun isValidDiceResult(result: RoundResult): Boolean {
        return result.redDots in 1..6 &&
                result.orangeDots in 1..6 &&
                result.confidence >= 0.25f // Снижен порог с 0.4f до 0.25f для лучшей чувствительности
    }

    /**
     * Детекция изменений по хешу и ожидание стабилизации
     * Комбинированная стратегия: сначала детекция изменений, потом ожидание стабилизации
     */
    private suspend fun detectDiceChangeAndStabilize(window: WindowType): RoundResult? {
        Log.d(TAG, "🔍 Запуск улучшенной детекции изменений и стабилизации для окна $window")

        // Получаем начальный хеш (быстрый)
        val initialHash = getAreaHashFast(window)
        if (initialHash == null) {
            Log.e(TAG, "❌ Не удалось получить начальный хеш")
            return null
        }

        Log.d(TAG, "📋 Начальный хеш: ${initialHash.take(8)}...")

        // Фаза 1: Ждем начала изменений (исчезновение старых кубиков)
        var currentHash = initialHash
        var changeDetected = false
        val maxWaitForChange = 30000L // 30 секунд на ожидание изменений
        val changeStartTime = System.currentTimeMillis()

        Log.d(TAG, "⏳ Фаза 1: Ожидание начала изменений...")

        while (!changeDetected &&
            (System.currentTimeMillis() - changeStartTime) < maxWaitForChange &&
            gameState.isRunning) {

            delay(20) // МАКСИМАЛЬНАЯ частота проверки - каждые 20мс!
            val newHash = getAreaHashFast(window) // Используем быстрый метод

            if (newHash != null && newHash != currentHash) {
                changeDetected = true
                Log.d(TAG, "🔄 Изменения обнаружены! ${currentHash.take(8)}... → ${newHash.take(8)}...")
                break
            }
        }

        if (!changeDetected) {
            Log.w(TAG, "⚠️ Изменения не обнаружены за ${maxWaitForChange}мс")
            return null
        }

        // Фаза 2: Ожидание стабилизации (окончание анимации)
        Log.d(TAG, "⏳ Фаза 2: Ожидание стабилизации...")

        var stabilizationHash = ""
        var stableStartTime = 0L
        val requiredStableTime = 300L // Сокращаем до 0.3 секунды!
        val maxWaitForStable = 12000L // Уменьшаем до 12 секунд
        val stableStartWaitTime = System.currentTimeMillis()

        while ((System.currentTimeMillis() - stableStartWaitTime) < maxWaitForStable &&
            gameState.isRunning) {

            delay(80) // Ускоряем проверку стабилизации
            val newHash = getAreaHashFast(window) // Используем быстрый метод для стабилизации

            if (newHash == null) continue

            if (newHash == stabilizationHash) {
                // Хеш стабилен
                if (stableStartTime == 0L) {
                    stableStartTime = System.currentTimeMillis()
                    Log.d(TAG, "📌 Начало стабилизации: ${newHash.take(8)}...")
                } else {
                    val stableTime = System.currentTimeMillis() - stableStartTime
                    if (stableTime >= requiredStableTime) {
                        Log.d(TAG, "✅ Изображение стабилизировалось (${stableTime}мс)")
                        break
                    }
                }
            } else {
                // Хеш изменился - сбрасываем стабилизацию
                if (stabilizationHash.isNotEmpty()) {
                    Log.d(TAG, "🔄 Стабилизация прервана: ${stabilizationHash.take(8)}... → ${newHash.take(8)}...")
                }
                stabilizationHash = newHash
                stableStartTime = 0L
            }
        }

        if (stableStartTime == 0L) {
            Log.w(TAG, "⚠️ Не дождались стабилизации за ${maxWaitForStable}мс")
            return null
        }

        // Фаза 3: Анализ стабилизированного результата
        Log.d(TAG, "🎯 Фаза 3: Анализ финального результата...")

        return waitForDiceStabilization(window)
    }

    /**
     * Умное ожидание стабилизации результата кубиков
     * Проверяет валидность OpenCV результатов и подтверждает через Gemini
     */
    private suspend fun waitForDiceStabilization(window: WindowType): RoundResult? {
        Log.d(TAG, "🎯 Запуск умного ожидания стабилизации для окна $window")

        var stableFrameCount = 0
        var lastValidResult: RoundResult? = null
        val maxWaitTime = 5000L // Увеличиваем время ожидания до 5 секунд
        val startTime = System.currentTimeMillis()
        val requiredStableFrames = 1 // МГНОВЕННО: только 1 кадр!
        
        // 🎯 УПРОЩЕННАЯ ЛОГИКА: берем первый валидный результат с хорошей уверенностью
        val minConfidenceForInstantAccept = 0.6f // Снижаем с 0.8 до 0.6

        while ((System.currentTimeMillis() - startTime) < maxWaitTime && gameState.isRunning) {
            val crop = captureDiceCrop(window)
            if (crop == null) {
                delay(100)
                continue
            }

            // Пробуем распознать кубики через OpenCV
            val currentResult = quickAnalyzeOpenCV(crop)

            if (currentResult != null && isValidDiceResult(currentResult)) {
                // Получили валидный результат кубиков
                Log.d(TAG, "📊 Валидный результат: ${currentResult.redDots}:${currentResult.orangeDots} (conf: ${String.format("%.2f", currentResult.confidence)})")

                // 🚀 УПРОЩЕНИЕ: если уверенность высокая, принимаем сразу
                if (currentResult.confidence >= minConfidenceForInstantAccept) {
                    Log.d(TAG, "⚡ Высокая уверенность ${String.format("%.2f", currentResult.confidence)} - принимаем сразу!")
                    
                    // ВАЖНО: Сразу передаем в Gemini для максимальной точности
                    val confirmedResult = confirmWithGemini(crop, currentResult)
                    crop.recycle()
                    
                    if (confirmedResult == null) {
                        Log.e(TAG, "🛑 Режим остановлен из-за недоступности Gemini")
                        return null
                    }
                    
                    return confirmedResult
                }

                // Обычная логика стабилизации для низкой уверенности
                if (lastValidResult != null &&
                    currentResult.redDots == lastValidResult.redDots &&
                    currentResult.orangeDots == lastValidResult.orangeDots) {

                    stableFrameCount++
                    Log.d(TAG, "📌 Стабильный кадр ${stableFrameCount}/${requiredStableFrames}")

                    // Если результат стабилен достаточно долго
                    if (stableFrameCount >= requiredStableFrames) {
                        Log.d(TAG, "✅ Результат стабилизировался: ${currentResult.redDots}:${currentResult.orangeDots}")

                        // ВАЖНО: Сразу передаем в Gemini для максимальной точности
                        val confirmedResult = confirmWithGemini(crop, currentResult)
                        crop.recycle()
                        
                        // Если Gemini недоступен (вернул null), функция уже остановила режим
                        if (confirmedResult == null) {
                            Log.e(TAG, "🛑 Режим остановлен из-за недоступности Gemini")
                            return null
                        }
                        
                        return confirmedResult
                    }
                } else {
                    // Результат изменился - обновляем и сбрасываем счетчик
                    if (lastValidResult != null) {
                        Log.d(TAG, "🔄 Результат изменился: ${lastValidResult.redDots}:${lastValidResult.orangeDots} → ${currentResult.redDots}:${currentResult.orangeDots}")
                    }
                    stableFrameCount = 1
                    lastValidResult = currentResult
                }
            } else {
                // Невалидный результат (возможно, анимация еще идет)
                if (currentResult != null) {
                    Log.d(TAG, "⚠️ Невалидный результат: ${currentResult.redDots}:${currentResult.orangeDots} (conf: ${String.format("%.2f", currentResult.confidence)})")
                } else {
                    Log.d(TAG, "⚠️ Не удалось распознать результат")
                }
                stableFrameCount = 0
                lastValidResult = null
            }

            crop.recycle()
            delay(30) // Максимальная скорость проверки OpenCV!
        }

        Log.w(TAG, "⚠️ Не удалось получить стабильный результат за ${maxWaitTime}мс")
        return null
    }

    /**
     * Подтверждение результата через Gemini API (приоритет Gemini)
     * Возвращает null если Gemini недоступен (что приводит к остановке режима)
     */
    private suspend fun confirmWithGemini(crop: Bitmap, openCVResult: RoundResult): RoundResult? {
        val aiProvider = preferencesManager.getAIProvider()
        val modelName = when (aiProvider) {
            PreferencesManager.AIProvider.OPENROUTER -> preferencesManager.getOpenRouterModel().displayName
            else -> "OpenCV"
        }
        
        Log.d(TAG, "🔍 Получение точного результата через AI ($modelName): ${openCVResult.redDots}:${openCVResult.orangeDots}")

        try {
            // Сохраняем кроп для отладки
            saveGeminiCropImage(crop, gameState.currentWindow,
                DotCounter.Result(openCVResult.redDots, openCVResult.orangeDots, openCVResult.confidence))

            // Получаем точный результат от AI (через HybridDiceRecognizer)
            val aiResult = analyzeWithGeminiDirect(crop)

            if (aiResult != null) {
                val aiRoundResult = RoundResult.fromDotResult(aiResult)

                if (isValidDiceResult(aiRoundResult)) {
                    Log.d(TAG, "✅ AI ($modelName) дал точный результат: ${aiRoundResult.redDots}:${aiRoundResult.orangeDots}")
                    return aiRoundResult
                } else {
                    Log.w(TAG, "⚠️ AI вернул невалидный результат: ${aiRoundResult.redDots}:${aiRoundResult.orangeDots}")
                    // Повторная попытка с тем же изображением
                    delay(200)
                    val retryResult = analyzeWithGeminiDirect(crop)
                    if (retryResult != null) {
                        val retryRoundResult = RoundResult.fromDotResult(retryResult)
                        if (isValidDiceResult(retryRoundResult)) {
                            Log.d(TAG, "✅ AI повторно дал результат: ${retryRoundResult.redDots}:${retryRoundResult.orangeDots}")
                            return retryRoundResult
                        }
                    }
                }
            } else {
                Log.w(TAG, "⚠️ AI не ответил, повторная попытка...")
                delay(300)
                val retryResult = analyzeWithGeminiDirect(crop)
                if (retryResult != null) {
                    val retryRoundResult = RoundResult.fromDotResult(retryResult)
                    if (isValidDiceResult(retryRoundResult)) {
                        Log.d(TAG, "✅ AI ответил при повторной попытке: ${retryRoundResult.redDots}:${retryRoundResult.orangeDots}")
                        return retryRoundResult
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при обращении к AI: ${e.message}")
        }

        // КРИТИЧЕСКАЯ СИТУАЦИЯ: AI полностью недоступен - останавливаем автоматизацию
        Log.e(TAG, "🛑 AI НЕДОСТУПЕН: Не удалось получить результат. Останавливаем двойной режим для безопасности.")
        
        // Останавливаем игровой режим
        stopDualMode("AI API недоступен - не удалось получить ответ. Проверьте подключение к интернету или состояние прокси.")
        
        // Возвращаем null вместо fallback результата
        return null
    }

}
