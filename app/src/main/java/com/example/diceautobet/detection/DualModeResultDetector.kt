package com.example.diceautobet.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.opencv.DotCounter
import com.example.diceautobet.recognition.HybridDiceRecognizer
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.ScreenshotService
import com.example.diceautobet.timing.DualModeTimingOptimizer
import com.example.diceautobet.timing.OperationType
import kotlinx.coroutines.*

/**
 * Детектор результатов для двойного режима с экономной AI логикой
 * Отслеживает результаты игры в реальном времени в обоих окнах
 * 💰 ЭКОНОМИЯ: AI запросы только при изменении кубиков
 */
class DualModeResultDetector(
    private val context: Context,
    private val screenshotService: ScreenshotService,
    private val timingOptimizer: DualModeTimingOptimizer? = null
) {
    
    companion object {
        private const val TAG = "DualModeResultDetector"
        private const val BASE_DETECTION_INTERVAL_MS = 500L
        private const val RESULT_CONFIDENCE_THRESHOLD = 0.7f
        private const val DICE_CHANGE_THRESHOLD = 0.1f // Порог изменения для детекции
    }
    
    private val detectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var detectionJob: Job? = null
    
    // 💰 ЭКОНОМИЯ: кэш для отслеживания изменений кубиков
    private var lastLeftResult: RoundResult? = null
    private var lastRightResult: RoundResult? = null
    private var lastDetectionTime = 0L
    
    // 🎯 НОВАЯ ЛОГИКА: отслеживание изменений кубиков
    private var previousLeftDice: DotCounter.Result? = null
    private var previousRightDice: DotCounter.Result? = null
    private var isFirstBet = true // Флаг первой ставки
    
    // 🤖 AI компоненты для экономных запросов
    private var hybridRecognizer: HybridDiceRecognizer? = null
    private var preferencesManager: PreferencesManager? = null
    
    // Слушатели событий
    private var onResultDetected: ((WindowType, RoundResult) -> Unit)? = null
    private var onDetectionError: ((String, Exception?) -> Unit)? = null
    
    /**
     * 🔧 Инициализирует AI компоненты для экономных запросов
     */
    fun initializeAI(prefsManager: PreferencesManager) {
        Log.d(TAG, "💰 Инициализация экономной AI логики")
        
        preferencesManager = prefsManager
        hybridRecognizer = HybridDiceRecognizer(prefsManager)
        
        Log.d(TAG, "✅ AI компоненты инициализированы для экономии средств")
    }
    
    /**
     * 🎯 Сбрасывает флаг первой ставки (вызывается при нажатии START)
     */
    fun resetFirstBetFlag() {
        Log.d(TAG, "🚀 Сброс флага первой ставки - начинаем экономную игру")
        isFirstBet = true
        previousLeftDice = null
        previousRightDice = null
    }
    
    /**
     * Запускает детекцию результатов
     */
    fun startDetection(leftWindowAreas: Map<AreaType, ScreenArea>, rightWindowAreas: Map<AreaType, ScreenArea>) {
        Log.d(TAG, "Запуск детекции результатов")
        
        if (detectionJob?.isActive == true) {
            Log.w(TAG, "Детекция уже запущена")
            return
        }
        
        detectionJob = detectionScope.launch {
            while (isActive) {
                val detectionStartTime = System.currentTimeMillis()
                
                try {
                    // Детектируем результаты в обоих окнах
                    detectResultsInBothWindows(leftWindowAreas, rightWindowAreas)
                    
                    // Записываем метрику успешной детекции
                    val detectionTime = System.currentTimeMillis() - detectionStartTime
                    timingOptimizer?.recordOperationMetric(OperationType.DETECTION, detectionTime, true)
                    
                    // Применяем оптимизированный интервал детекции
                    val detectionInterval = timingOptimizer?.getDelayForOperation(OperationType.DETECTION) 
                        ?: BASE_DETECTION_INTERVAL_MS
                    delay(detectionInterval)
                    
                } catch (e: CancellationException) {
                    Log.d(TAG, "Детекция остановлена")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка детекции", e)
                    
                    // Записываем метрику неудачной детекции
                    val detectionTime = System.currentTimeMillis() - detectionStartTime
                    timingOptimizer?.recordOperationMetric(OperationType.DETECTION, detectionTime, false)
                    
                    onDetectionError?.invoke("Ошибка детекции результатов", e)
                    delay(1000) // Пауза при ошибке
                }
            }
        }
        
        Log.d(TAG, "Детекция результатов запущена")
    }
    
    /**
     * Останавливает детекцию результатов
     */
    fun stopDetection() {
        Log.d(TAG, "Остановка детекции результатов")
        detectionJob?.cancel()
        detectionJob = null
        
        // Сбрасываем кэш
        lastLeftResult = null
        lastRightResult = null
        lastDetectionTime = 0L
    }
    
    /**
     * Детектирует результаты в обоих окнах с оптимизацией
     */
    private suspend fun detectResultsInBothWindows(
        leftWindowAreas: Map<AreaType, ScreenArea>,
        rightWindowAreas: Map<AreaType, ScreenArea>
    ) {
        val currentTime = System.currentTimeMillis()
        
        // Применяем оптимизированную защиту от частых детекций
        val minInterval = timingOptimizer?.getDelayForOperation(OperationType.DETECTION)?.div(2) 
            ?: (BASE_DETECTION_INTERVAL_MS / 2)
        
        if (currentTime - lastDetectionTime < minInterval) {
            return
        }
        
        lastDetectionTime = currentTime
        
        // Получаем скриншот с измерением времени
        val screenshotStartTime = System.currentTimeMillis()
        val screenshot = screenshotService.takeScreenshot()
        
        if (screenshot == null) {
            Log.w(TAG, "Не удалось получить скриншот")
            timingOptimizer?.recordOperationMetric(OperationType.SCREENSHOT, 
                System.currentTimeMillis() - screenshotStartTime, false)
            return
        }
        
        // Записываем метрику скриншота
        val screenshotTime = System.currentTimeMillis() - screenshotStartTime
        timingOptimizer?.recordOperationMetric(OperationType.SCREENSHOT, screenshotTime, true)
        
        // Детектируем результаты параллельно
        val leftJob = detectionScope.async { detectResultInWindow(screenshot, leftWindowAreas, WindowType.LEFT) }
        val rightJob = detectionScope.async { detectResultInWindow(screenshot, rightWindowAreas, WindowType.RIGHT) }
        
        // Ждем результаты
        val leftResult = leftJob.await()
        val rightResult = rightJob.await()
        
        // Обрабатываем результаты
        leftResult?.let { result ->
            if (isNewResult(result, lastLeftResult)) {
                lastLeftResult = result
                onResultDetected?.invoke(WindowType.LEFT, result)
                Log.d(TAG, "Новый результат в левом окне: $result")
            }
        }
        
        rightResult?.let { result ->
            if (isNewResult(result, lastRightResult)) {
                lastRightResult = result
                onResultDetected?.invoke(WindowType.RIGHT, result)
                Log.d(TAG, "Новый результат в правом окне: $result")
            }
        }
    }
    
    /**
     * Детектирует результат в конкретном окне
     */
    private suspend fun detectResultInWindow(
        screenshot: Bitmap,
        windowAreas: Map<AreaType, ScreenArea>,
        windowType: WindowType
    ): RoundResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем область кубиков для этого окна
                val diceArea = windowAreas[AreaType.DICE_AREA]
                if (diceArea == null) {
                    Log.w(TAG, "Область кубиков не найдена для окна $windowType")
                    return@withContext null
                }
                
                // Вырезаем область кубиков из скриншота
                val diceRegion = extractRegion(screenshot, diceArea.rect)
                if (diceRegion == null) {
                    Log.w(TAG, "Не удалось вырезать область кубиков для окна $windowType")
                    return@withContext null
                }
                
                // Анализируем кубики с экономной AI логикой
                val roundResult = analyzeWithEconomicAI(diceRegion, windowType)
                
                if (roundResult == null) {
                    Log.v(TAG, "Результат не определен для окна $windowType")
                    return@withContext null
                }
                
                Log.v(TAG, "✅ Результат в окне $windowType: красный=${roundResult.redDots}, оранжевый=${roundResult.orangeDots}")
                
                return@withContext roundResult
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка детекции в окне $windowType", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Вырезает регион из скриншота
     */
    private fun extractRegion(screenshot: Bitmap, rect: Rect): Bitmap? {
        return try {
            // Проверяем границы
            val safeLeft = maxOf(0, rect.left)
            val safeTop = maxOf(0, rect.top)
            val safeRight = minOf(screenshot.width, rect.right)
            val safeBottom = minOf(screenshot.height, rect.bottom)
            
            val width = safeRight - safeLeft
            val height = safeBottom - safeTop
            
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Некорректные размеры региона: ${width}x${height}")
                return null
            }
            
            Bitmap.createBitmap(screenshot, safeLeft, safeTop, width, height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вырезания региона", e)
            null
        }
    }
    
    /**
     * Проверяет, является ли результат новым
     */
    private fun isNewResult(newResult: RoundResult, lastResult: RoundResult?): Boolean {
        if (lastResult == null) return true
        
        // Сравниваем ключевые параметры
        return newResult.redDots != lastResult.redDots ||
               newResult.orangeDots != lastResult.orangeDots ||
               newResult.winner != lastResult.winner
    }
    
    /**
     * 💰 ЭКОНОМНЫЙ АНАЛИЗ: AI запросы только при изменении кубиков
     */
    private suspend fun analyzeWithEconomicAI(diceRegion: Bitmap, windowType: WindowType): RoundResult? {
        // 1️⃣ Всегда начинаем с OpenCV (быстрый и бесплатный)
        val openCvResult = DotCounter.count(diceRegion)
        
        // 2️⃣ Проверяем уверенность OpenCV
        if (openCvResult.confidence < RESULT_CONFIDENCE_THRESHOLD) {
            Log.v(TAG, "📊 Низкая уверенность OpenCV для $windowType: ${openCvResult.confidence}")
            return null
        }
        
        // 3️⃣ Получаем предыдущий результат для сравнения
        val previousResult = when (windowType) {
            WindowType.LEFT, WindowType.TOP -> previousLeftDice
            WindowType.RIGHT, WindowType.BOTTOM -> previousRightDice
        }
        
        // 4️⃣ ЭКОНОМИЯ: проверяем изменились ли кубики
        val diceChanged = hasDiceChanged(openCvResult, previousResult)
        
        // 5️⃣ Логика AI запросов
        val finalResult = when {
            isFirstBet -> {
                Log.d(TAG, "🚀 Первая ставка - только OpenCV, AI не используем")
                isFirstBet = false // После первой ставки переключаемся в экономный режим
                RoundResult.fromDotResult(openCvResult)
            }
            
            !diceChanged -> {
                Log.v(TAG, "📊 Кубики не изменились - используем OpenCV (экономия средств)")
                RoundResult.fromDotResult(openCvResult)
            }
            
            shouldUseAI() -> {
                Log.d(TAG, "💎 Кубики изменились! Отправляем запрос к AI для точности")
                requestAIAnalysis(diceRegion, openCvResult)
            }
            
            else -> {
                Log.d(TAG, "📊 AI не настроен - используем OpenCV")
                RoundResult.fromDotResult(openCvResult)
            }
        }
        
        // 6️⃣ Сохраняем текущий результат для следующего сравнения
        when (windowType) {
            WindowType.LEFT, WindowType.TOP -> previousLeftDice = openCvResult
            WindowType.RIGHT, WindowType.BOTTOM -> previousRightDice = openCvResult
        }
        
        return finalResult
    }
    
    /**
     * 🔍 Проверяет изменились ли кубики
     */
    private fun hasDiceChanged(current: DotCounter.Result, previous: DotCounter.Result?): Boolean {
        if (previous == null) return true // Первый анализ
        
        val leftChanged = current.leftDots != previous.leftDots
        val rightChanged = current.rightDots != previous.rightDots
        val confidenceChanged = kotlin.math.abs(current.confidence - previous.confidence) > DICE_CHANGE_THRESHOLD
        
        val changed = leftChanged || rightChanged || confidenceChanged
        
        if (changed) {
            Log.d(TAG, "🎲 ИЗМЕНЕНИЕ: left ${previous.leftDots}→${current.leftDots}, right ${previous.rightDots}→${current.rightDots}")
        }
        
        return changed
    }
    
    /**
     * 🤖 Проверяет настроен ли AI
     */
    private fun shouldUseAI(): Boolean {
        val prefsManager = preferencesManager ?: return false
        val mode = prefsManager.getRecognitionMode()
        
        return mode in listOf(
            PreferencesManager.RecognitionMode.OPENAI,
            PreferencesManager.RecognitionMode.GEMINI,
            PreferencesManager.RecognitionMode.HYBRID
        ) && prefsManager.isAIConfigured()
    }
    
    /**
     * 💎 Отправляет запрос к AI (только при изменении кубиков)
     */
    private suspend fun requestAIAnalysis(diceRegion: Bitmap, fallbackResult: DotCounter.Result): RoundResult {
        val recognizer = hybridRecognizer
        
        if (recognizer == null) {
            Log.w(TAG, "⚠️ AI не инициализирован - используем OpenCV")
            return RoundResult.fromDotResult(fallbackResult)
        }
        
        return try {
            val aiResult = recognizer.analyzeDice(diceRegion)
            if (aiResult != null) {
                Log.d(TAG, "✅ AI ответ получен: left=${aiResult.leftDots}, right=${aiResult.rightDots}")
                RoundResult.fromDotResult(aiResult)
            } else {
                Log.w(TAG, "⚠️ AI не смог распознать - используем OpenCV")
                RoundResult.fromDotResult(fallbackResult)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка AI запроса - используем OpenCV", e)
            RoundResult.fromDotResult(fallbackResult)
        }
    }

    /**
     * Принудительно сбрасывает кэш результатов
     */
    fun resetResultCache() {
        Log.d(TAG, "Сброс кэша результатов")
        lastLeftResult = null
        lastRightResult = null
        lastDetectionTime = 0L
        
        // 💰 Также сбрасываем экономный кэш
        previousLeftDice = null
        previousRightDice = null
    }
    
    /**
     * Получает последний результат для окна
     */
    fun getLastResult(windowType: WindowType): RoundResult? {
        return when (windowType) {
            WindowType.LEFT -> lastLeftResult
            WindowType.RIGHT -> lastRightResult
            WindowType.TOP -> lastLeftResult    // Используем левый результат для верхнего окна
            WindowType.BOTTOM -> lastRightResult // Используем правый результат для нижнего окна
        }
    }
    
    /**
     * Проверяет, активна ли детекция
     */
    fun isDetectionActive(): Boolean {
        return detectionJob?.isActive == true
    }
    
    // === СЕТТЕРЫ ДЛЯ СЛУШАТЕЛЕЙ ===
    
    fun setOnResultDetectedListener(listener: (WindowType, RoundResult) -> Unit) {
        onResultDetected = listener
    }
    
    fun setOnDetectionErrorListener(listener: (String, Exception?) -> Unit) {
        onDetectionError = listener
    }
    
    /**
     * Освобождает ресурсы
     */
    fun cleanup() {
        Log.d(TAG, "Очистка ресурсов DualModeResultDetector")
        stopDetection()
        detectionScope.cancel()
    }
}
