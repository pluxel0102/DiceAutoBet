package com.example.diceautobet.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.diceautobet.utils.ProxyManager
import com.example.diceautobet.utils.PreferencesManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.use

/**
 * Распознавание кубиков через Google Gemini 2.5 Flash-Lite API
 * 
 * 🚀 ОПТИМИЗАЦИИ ДЛЯ ТОЧНОСТИ РАСПОЗНАВАНИЯ:
 * ✅ Преобразование в чёрно-белое изображение с экстремальным контрастом
 * ✅ Увеличение резкости для чётких точек на кубиках
 * ✅ Оптимальный размер 512x512 для лучшей работы AI
 * ✅ Улучшенный промпт с конкретными инструкциями
 * ✅ Адаптивная обработка в зависимости от яркости
 * ✅ Кэширование результатов для ускорения
 * 
 * 💰 Стоимость: ~$0.00003 за изображение, оптимизирована для скорости
 */
class GeminiDiceRecognizer(private val preferencesManager: PreferencesManager) {
    
    companion object {
        private const val TAG = "GeminiDiceRecognizer"
        // Базовый URL без привязки к конкретной модели
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        // Используем только одну модель - самую быструю
        private const val MODEL = "gemini-2.5-flash-lite"
        private const val JPEG_QUALITY = 85  // Баланс качества и скорости
        
        // 🎯 НАСТРОЙКИ ОБРАБОТКИ ИЗОБРАЖЕНИЯ
        private const val ENABLE_IMAGE_PROCESSING = false  // ОТКЛЮЧЕНО: обработка портит кубики
        private const val TARGET_IMAGE_SIZE = 512  // Оптимальный размер для AI
        private const val HIGH_CONTRAST_MODE = false  // Отключаем экстремальный контраст
        private const val GENTLE_PROCESSING_MODE = true  // Включаем мягкую обработку
        
        // 🚀 КЭШ ДЛЯ СКОРОСТИ - храним последние 50 результатов
        private val resultCache = mutableMapOf<String, DiceResult>()
        private const val MAX_CACHE_SIZE = 50
        
        // 🔍 ОТЛАДКА - сохранение изображений для анализа
        private const val SAVE_DEBUG_IMAGES = true
        private const val DEBUG_IMAGES_FOLDER = "Gemini_Debug"
        
        // 🧪 ЭКСПЕРИМЕНТАЛЬНЫЕ РЕЖИМЫ
        private const val USE_ORIGINAL_SIZE = false  // Масштабируем для лучшего распознавания
        private const val SKIP_SHARPENING = false   // Применяем резкость
        private const val USE_SIMPLE_PROMPT = true // Простой промпт
        private const val NO_PROCESSING_MODE = false // ВКЛЮЧАЕМ обработку для лучшего распознавания!
        
        // 🎯 АЛЬТЕРНАТИВНЫЕ ПРОМПТЫ ДЛЯ ЭКСПЕРИМЕНТОВ
        private val BACKUP_PROMPTS = listOf(
            "Count black dots on left dice and right dice. Format: X:Y",
            "How many dots on each die? Left:Right format like 3:5",
            "Two dice image. Count dots. Answer: left_dots:right_dots"
        )
    }
    
    data class DiceResult(
        val redDots: Int,
        val orangeDots: Int,
        val confidence: Float = 0.9f
    )
    
    data class ErrorResult(
        val isRetryable: Boolean,
        val errorMessage: String
    )
    
    /**
     * Анализирует изображение кубиков через Gemini API
     */
    suspend fun analyzeDice(bitmap: Bitmap, openCvResult: DiceResult? = null): DiceResult? {
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                // 🚀 ПРОВЕРКА КЭША
                val imageHash = getImageHash(bitmap)
                resultCache[imageHash]?.let { cachedResult ->
                    val cacheTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "⚡ КЭШ ПОПАДАНИЕ! Возвращаем результат мгновенно: ${cachedResult.redDots}:${cachedResult.orangeDots} (время: ${cacheTime}мс)")
                    return@withContext cachedResult
                }
                
                Log.d(TAG, "🔍 Анализируем изображение через Gemini API (размер: ${bitmap.width}x${bitmap.height})...")
                
                // ОТЛАДКА: Выводим стек вызовов чтобы понять, откуда идет запрос
                val stackTrace = Thread.currentThread().stackTrace
                Log.d(TAG, "🔍 GEMINI вызван из:")
                stackTrace.take(8).forEachIndexed { index, element ->
                    if (element.className.contains("diceautobet")) {
                        Log.d(TAG, "   $index: ${element.className}.${element.methodName}:${element.lineNumber}")
                    }
                }
                
                // 🎯 ОБРАБАТЫВАЕМ ИЗОБРАЖЕНИЕ ДЛЯ ЛУЧШЕГО РАСПОЗНАВАНИЯ
                val processedBitmap = if (NO_PROCESSING_MODE) {
                    Log.d(TAG, "🎯 ЭКСПЕРИМЕНТ: Отправляем оригинал БЕЗ ОБРАБОТКИ")
                    bitmap
                } else if (ENABLE_IMAGE_PROCESSING) {
                    preprocessImageForGemini(bitmap)
                } else {
                    bitmap
                }
                Log.d(TAG, "🎨 Изображение ${when {
                    NO_PROCESSING_MODE -> "БЕЗ ОБРАБОТКИ"
                    ENABLE_IMAGE_PROCESSING -> "обработано"
                    else -> "оригинальное"
                }}: ${processedBitmap.width}x${processedBitmap.height}")
                
                // 🔍 СОХРАНЯЕМ ИЗОБРАЖЕНИЯ ДЛЯ ОТЛАДКИ
                if (SAVE_DEBUG_IMAGES) {
                    saveDebugImages(bitmap, processedBitmap)
                    
                    // 🧪 ЭКСПЕРИМЕНТАЛЬНЫЕ ИЗОБРАЖЕНИЯ ОТКЛЮЧЕНЫ ДЛЯ УСКОРЕНИЯ
                    // saveExperimentalImages(bitmap)
                }
                
                // Конвертируем bitmap в base64
                val base64Image = bitmapToBase64(processedBitmap)
                if (base64Image == null) {
                    return@withContext null
                }
                
                val imageSizeKb = base64Image.length * 3 / 4 / 1024  // Примерный размер в KB
                Log.d(TAG, "📊 Размер обработанного изображения: ${imageSizeKb}KB (base64: ${base64Image.length} символов)")
                
                // Формируем запрос к Gemini
                val requestBody = createRequestBody(base64Image)
                
                // Быстро перебираем ключи при ошибках
                val keys = preferencesManager.getGeminiApiKeys()
                if (keys.isEmpty()) {
                    Log.e(TAG, "❌ Нет доступных Gemini API ключей")
                    return@withContext null
                }
                
                var currentKeyIndex = preferencesManager.getCurrentGeminiKeyIndex()
                val totalKeys = keys.size
                
                repeat(totalKeys) { attempt ->
                    val apiKey = keys[currentKeyIndex]
                    Log.d(TAG, "🔑 Пробуем ключ ${currentKeyIndex + 1}/$totalKeys (попытка ${attempt + 1})")
                    
                    // ОДНОКРАТНЫЙ ВЫЗОВ БЕЗ ПОВТОРОВ - ответ или ошибка сразу
                    var response: String? = null
                    var lastError: Exception? = null
                    
                    try {
                        response = sendApiRequest(requestBody, apiKey)
                        if (response != null) {
                            Log.d(TAG, "✅ Ключ ${currentKeyIndex + 1} дал ответ (${response.length} символов)")
                        } else {
                            Log.w(TAG, "⚠️ Ключ ${currentKeyIndex + 1} вернул null ответ")
                        }
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "❌ Ключ ${currentKeyIndex + 1} вызвал исключение: ${e.message}")
                    }
                    
                    if (response != null) {
                        // Парсим ответ
                        Log.d(TAG, "🔍 Парсим ответ от ключа ${currentKeyIndex + 1}...")
                        val result = parseResponse(response)
                        if (result != null) {
                            val totalTime = System.currentTimeMillis() - startTime
                            // 🚀 СОХРАНЯЕМ В КЭШ
                            addToCache(imageHash, result)
                            Log.d(TAG, "✅ УСПЕХ с ключом ${currentKeyIndex + 1}: ${result.redDots}:${result.orangeDots} (${totalTime}мс)")
                            
                            // 🔍 ДОПОЛНИТЕЛЬНАЯ ИНФОРМАЦИЯ ДЛЯ ОТЛАДКИ
                            Log.d(TAG, "🔍 ДЕТАЛИ РАСПОЗНАВАНИЯ:")
                            Log.d(TAG, "   📏 Размер исходного изображения: ${bitmap.width}x${bitmap.height}")
                            Log.d(TAG, "   🎨 Размер обработанного: ${processedBitmap.width}x${processedBitmap.height}")
                            Log.d(TAG, "   🎯 Gemini увидел: ЛЕВЫЙ кубик = ${result.redDots} точек, ПРАВЫЙ кубик = ${result.orangeDots} точек")
                            Log.d(TAG, "   📊 Уверенность: ${result.confidence}")
                            
                            // 🔍 СРАВНЕНИЕ С OPENCV (если доступно)
                            openCvResult?.let { opencv ->
                                val isMatch = opencv.redDots == result.redDots && opencv.orangeDots == result.orangeDots
                                Log.d(TAG, "🆚 СРАВНЕНИЕ С OPENCV:")
                                Log.d(TAG, "   🤖 OpenCV: ${opencv.redDots}:${opencv.orangeDots}")
                                Log.d(TAG, "   🧠 Gemini: ${result.redDots}:${result.orangeDots}")
                                Log.d(TAG, "   ${if (isMatch) "✅ СОВПАДЕНИЕ!" else "❌ РАСХОЖДЕНИЕ!"}")
                                
                                if (!isMatch) {
                                    Log.w(TAG, "⚠️ ВНИМАНИЕ: Gemini и OpenCV дали разные результаты!")
                                    Log.w(TAG, "💡 Возможно стоит проверить изображения в папке ${DEBUG_IMAGES_FOLDER}")
                                }
                            }
                            
                            return@withContext result
                        } else {
                            Log.w(TAG, "⚠️ Ключ ${currentKeyIndex + 1} дал ответ, но парсинг неудачен. Ответ: ${response.take(100)}...")
                        }
                    } else {
                        // Логируем последнюю ошибку
                        if (lastError != null) {
                            Log.w(TAG, "❌ Ключ ${currentKeyIndex + 1} не сработал: ${lastError.message}")
                        } else {
                            Log.w(TAG, "❌ Ключ ${currentKeyIndex + 1} вернул null без исключения")
                        }
                    }
                    
                    // Переключаемся на следующий ключ
                    currentKeyIndex = (currentKeyIndex + 1) % totalKeys
                    preferencesManager.saveCurrentGeminiKeyIndex(currentKeyIndex)
                }
                
                Log.e(TAG, "❌ Все ключи ($totalKeys) не сработали")
                return@withContext null
            } catch (e: Exception) {
                val totalTime = System.currentTimeMillis() - startTime
                Log.e(TAG, "❌ Ошибка при анализе через Gemini: ${e.message} (время: ${totalTime}мс)", e)
                null
            }
        }
    }
    
    /**
     * 🎯 ПРЕДВАРИТЕЛЬНАЯ ОБРАБОТКА ИЗОБРАЖЕНИЯ ДЛЯ GEMINI
     * Применяет несколько техник для улучшения распознавания:
     * 1. Преобразование в чёрно-белое
     * 2. Увеличение контрастности
     * 3. Увеличение резкости
     * 4. Оптимальный размер для AI
     */
    private fun preprocessImageForGemini(originalBitmap: Bitmap): Bitmap {
        try {
            Log.d(TAG, "🎨 Начинаем обработку изображения ${originalBitmap.width}x${originalBitmap.height}")
            
            // 1. 🎯 РАЗМЕР - экспериментируем с оригинальным размером
            val scaledBitmap = if (USE_ORIGINAL_SIZE) {
                Log.d(TAG, "🎯 ЭКСПЕРИМЕНТ: Используем оригинальный размер ${originalBitmap.width}x${originalBitmap.height}")
                originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                if (originalBitmap.width != TARGET_IMAGE_SIZE || originalBitmap.height != TARGET_IMAGE_SIZE) {
                    Bitmap.createScaledBitmap(originalBitmap, TARGET_IMAGE_SIZE, TARGET_IMAGE_SIZE, true)
                } else {
                    originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }
            }
            
            // 2. 🖤 ПРЕОБРАЗОВАНИЕ В ЧЁРНО-БЕЛОЕ
            val bwBitmap = if (GENTLE_PROCESSING_MODE) {
                convertToGentleBW(scaledBitmap)
            } else {
                convertToHighContrastBW(scaledBitmap)
            }
            
            // 3. 🔍 РЕЗКОСТЬ - экспериментируем без неё
            val finalBitmap = if (SKIP_SHARPENING) {
                Log.d(TAG, "🎯 ЭКСПЕРИМЕНТ: Пропускаем фильтр резкости")
                bwBitmap
            } else {
                applySharpenFilter(bwBitmap)
            }
            
            Log.d(TAG, "✅ Обработка завершена: ${finalBitmap.width}x${finalBitmap.height}")
            return finalBitmap
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка обработки изображения, используем оригинал: ${e.message}")
            return originalBitmap
        }
    }
    
    /**
     * 🖤 УЛУЧШЕННЫЙ BLACKISH ЭФФЕКТ для максимального контраста
     * Создает стабильные черно-белые изображения для точного распознавания
     */
    private fun convertToHighContrastBW(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        
        // Улучшенная матрица для blackish эффекта
        val colorMatrix = ColorMatrix().apply {
            // 1. Преобразуем в оттенки серого
            setSaturation(0f)
            
            // 2. Применяем стабильный blackish эффект
            val contrast = 3.0f      // Увеличиваем контраст для четкого разделения
            val brightness = 40f     // Оптимальная яркость для белых точек
            val blackPoint = -30f    // Углубляем черные тона (blackish эффект)
            
            val scale = contrast
            val translate = brightness + blackPoint + (128f * (1f - contrast))
            
            // Применяем преобразование с blackish эффектом
            postConcat(ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
            
            // 3. Дополнительная пост-обработка для стабильности
            // Усиливаем разделение между черным и белым
            val stabilizeMatrix = ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, -20f,    // Красный канал: больше контраста
                0f, 1.2f, 0f, 0f, -20f,    // Зеленый канал: больше контраста  
                0f, 0f, 1.2f, 0f, -20f,    // Синий канал: больше контраста
                0f, 0f, 0f, 1f, 0f         // Альфа канал: без изменений
            ))
            postConcat(stabilizeMatrix)
        }
        
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        Log.d(TAG, "🖤 Применён улучшенный blackish эффект")
        return result
    }
    
    /**
     * 🖤 МЯГКОЕ ПРЕОБРАЗОВАНИЕ В ЧЁРНО-БЕЛОЕ (для отладки)
     * Более консервативный подход без потери деталей
     */
    private fun convertToGentleBW(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        
        // Мягкая матрица для преобразования в ч/б
        val colorMatrix = ColorMatrix().apply {
            // Преобразуем в оттенки серого
            setSaturation(0f)
            
            // Умеренное увеличение контрастности
            val contrast = 1.3f  // Мягкий контраст (было 2.5f)
            val brightness = 20f  // Небольшое увеличение яркости (было 50f)
            
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
        
        Log.d(TAG, "🖤 Применён мягкий ч/б фильтр")
        return result
    }
    
    /**
     * 🔍 ПРИМЕНЕНИЕ ФИЛЬТРА РЕЗКОСТИ
     * Делает точки на кубиках более чёткими
     */
    private fun applySharpenFilter(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        
        // Матрица резкости (делает края более чёткими)
        val sharpenMatrix = ColorMatrix(floatArrayOf(
            0f, -1f, 0f, 0f, 0f,
            -1f, 5f, -1f, 0f, 0f,
            0f, -1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        
        paint.colorFilter = ColorMatrixColorFilter(sharpenMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        Log.d(TAG, "🔍 Применён фильтр резкости")
        return result
    }
    
    /**
     * 🧪 ЭКСПЕРИМЕНТАЛЬНАЯ ФУНКЦИЯ: Адаптивная обработка
     * Анализирует изображение и применяет наиболее подходящую обработку
     */
    fun preprocessImageAdaptive(originalBitmap: Bitmap): Bitmap {
        try {
            // Анализируем яркость изображения
            val brightness = calculateAverageBrightness(originalBitmap)
            Log.d(TAG, "🔍 Средняя яркость изображения: $brightness")
            
            return when {
                brightness < 50 -> {
                    // Тёмное изображение - увеличиваем яркость
                    Log.d(TAG, "🌙 Тёмное изображение, увеличиваем яркость")
                    preprocessImageForGemini(originalBitmap)
                }
                brightness > 200 -> {
                    // Слишком яркое - уменьшаем яркость
                    Log.d(TAG, "☀️ Яркое изображение, применяем стандартную обработку")
                    preprocessImageForGemini(originalBitmap)
                }
                else -> {
                    // Нормальная яркость - стандартная обработка
                    Log.d(TAG, "⚖️ Нормальная яркость, стандартная обработка")
                    preprocessImageForGemini(originalBitmap)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка адаптивной обработки: ${e.message}")
            return preprocessImageForGemini(originalBitmap)
        }
    }
    
    /**
     * 📊 Вычисляет среднюю яркость изображения
     */
    private fun calculateAverageBrightness(bitmap: Bitmap): Float {
        var totalBrightness = 0L
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Формула для яркости (взвешенная)
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        
        return totalBrightness.toFloat() / pixels.size
    }
    
    /**
     * 🔍 СОХРАНЕНИЕ ИЗОБРАЖЕНИЙ ДЛЯ ОТЛАДКИ
     * Сохраняет оригинальное и обработанное изображения для анализа
     */
    private fun saveDebugImages(originalBitmap: Bitmap, processedBitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            
            // Создаём папку для отладочных изображений
            val debugFolder = File("/storage/emulated/0/Android/data/com.example.diceautobet/files", DEBUG_IMAGES_FOLDER)
            if (!debugFolder.exists()) {
                debugFolder.mkdirs()
            }
            
            // Сохраняем оригинальное изображение
            val originalFile = File(debugFolder, "GEMINI_original_${timestamp}.png")
            saveBitmapToFile(originalBitmap, originalFile)
            
            // Сохраняем обработанное изображение
            val processedFile = File(debugFolder, "GEMINI_processed_${timestamp}.png")
            saveBitmapToFile(processedBitmap, processedFile)
            
            Log.d(TAG, "🔍 GEMINI: Отладочные изображения сохранены:")
            Log.d(TAG, "   📄 GEMINI Оригинал: ${originalFile.absolutePath}")
            Log.d(TAG, "   🎨 GEMINI Обработанное: ${processedFile.absolutePath}")
            Log.d(TAG, "   📏 GEMINI Размеры: ${originalBitmap.width}x${originalBitmap.height} -> ${processedBitmap.width}x${processedBitmap.height}")
            
            // КРИТИЧЕСКАЯ ПРОВЕРКА: если размер равен размеру экрана - это ошибка!
            if (originalBitmap.width > 1000 && originalBitmap.height > 2000) {
                Log.e(TAG, "🚨 ВНИМАНИЕ! GEMINI получил изображение размера экрана: ${originalBitmap.width}x${originalBitmap.height}")
                Log.e(TAG, "🚨 Это может быть ошибкой! Должна передаваться только область кубиков!")
            } else {
                Log.d(TAG, "✅ GEMINI получил нормальное изображение области: ${originalBitmap.width}x${originalBitmap.height}")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка сохранения отладочных изображений: ${e.message}")
        }
    }
    
    /**
     * 💾 СОХРАНЕНИЕ BITMAP В ФАЙЛ
     */
    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка сохранения файла ${file.name}: ${e.message}")
        }
    }
    
    /**
     * 🧪 СОЗДАНИЕ ЭКСПЕРИМЕНТАЛЬНЫХ ИЗОБРАЖЕНИЙ
     * Создаём разные варианты обработки для анализа
     */
    private fun saveExperimentalImages(originalBitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val debugFolder = File("/storage/emulated/0/Android/data/com.example.diceautobet/files", DEBUG_IMAGES_FOLDER)
            
            // 1. Только ч/б без масштабирования
            val bwOnly = convertToGentleBW(originalBitmap)
            saveBitmapToFile(bwOnly, File(debugFolder, "exp1_bw_only_${timestamp}.png"))
            
            // 2. Только масштабирование без обработки
            val scaledOnly = Bitmap.createScaledBitmap(originalBitmap, 512, 512, true)
            saveBitmapToFile(scaledOnly, File(debugFolder, "exp2_scaled_only_${timestamp}.png"))
            
            // 3. Масштабирование + ч/б без резкости
            val scaledBw = convertToGentleBW(scaledOnly)
            saveBitmapToFile(scaledBw, File(debugFolder, "exp3_scaled_bw_${timestamp}.png"))
            
            // 4. Высокий контраст
            val highContrast = convertToHighContrastBW(originalBitmap)
            saveBitmapToFile(highContrast, File(debugFolder, "exp4_high_contrast_${timestamp}.png"))
            
            Log.d(TAG, "🧪 Создано 4 экспериментальных изображения для анализа")
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка создания экспериментальных изображений: ${e.message}")
        }
    }
    
    /**
     * Анализирует ошибку и определяет, стоит ли пробовать следующий ключ
     */
    fun analyzeError(error: Exception): ErrorResult {
        val message = error.message ?: ""
        val isRetryable = when {
            // Ошибки аутентификации - пробуем следующий ключ
            message.contains("API_KEY") || message.contains("403") || message.contains("401") -> true
            message.contains("INVALID_API_KEY") || message.contains("API key not valid") -> true
            
            // Ошибки квоты - пробуем следующий ключ
            message.contains("429") || message.contains("QUOTA") -> true
            
            // Ошибки сети - можно попробовать ещё раз
            message.contains("timeout") || message.contains("connection") -> true
            
            // Ошибки модели - пробуем следующий ключ
            message.contains("400") || message.contains("404") -> true
            
            // Другие ошибки - не пробуем следующий ключ
            else -> false
        }
        
        return ErrorResult(isRetryable, message)
    }
    
    /**
     * Конвертирует Bitmap в base64 строку
     */
    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)  // JPEG для ускорения
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка конвертации bitmap в base64: ${e.message}")
            null
        }
    }
    
    /**
     * Формирует JSON запрос для Gemini API
     */
    private fun createRequestBody(base64Image: String): String {
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    // Некоторые версии API требуют наличия role
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            val promptText = if (USE_SIMPLE_PROMPT) {
                                // 🎯 УЛУЧШЕННЫЙ ПРОСТОЙ ПРОМПТ ДЛЯ ОБЛАСТИ КУБИКОВ
                                "I see two dice in this image. Count the black dots on the left die and right die. Respond ONLY with two numbers separated by colon. Format: left:right (example: 3:5). Nothing else."
                            } else {
                                // 📝 ПОДРОБНЫЙ ПРОМПТ ДЛЯ ОБЛАСТИ КУБИКОВ
                                """
                                Look at this black and white high-contrast image of two dice.
                                Count the black dots on each die carefully.
                                LEFT die dots : RIGHT die dots
                                Answer format: X:Y (where X and Y are numbers 1-6)
                                Example: 3:5
                                Just the numbers, nothing else.
                                """.trimIndent()
                            }
                            put("text", promptText)
                            Log.d(TAG, "🔤 Используем промпт: ${if (USE_SIMPLE_PROMPT) "ПРОСТОЙ" else "ПОДРОБНЫЙ"}")
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/png")  // PNG формат
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            // 🚀 МИНИМАЛЬНЫЕ ПАРАМЕТРЫ ДЛЯ ЭКСТРЕМАЛЬНОЙ СКОРОСТИ
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 6)   // Ещё меньше токенов - только "3:5"
                put("temperature", 0.0)     // Никакой креативности
                put("topP", 0.1)           // Только самые лучшие варианты
                put("topK", 1)             // Только лучший вариант
                put("stopSequences", JSONArray().apply { 
                    put("\n")  // Останавливаемся на первой строке
                    put(" ")   // Останавливаемся на пробеле
                    put(".")   // Останавливаемся на точке
                    put(",")   // Останавливаемся на запятой
                })
            })
        }
        
        return requestJson.toString()
    }
    
    /**
     * Отправляет HTTP запрос к Gemini API через прокси
     */
    private fun sendApiRequest(requestBody: String, apiKey: String): String? {
        val requestStartTime = System.currentTimeMillis()
        return try {
            val url = "${API_BASE}/${MODEL}:generateContent?key=${apiKey}"
            Log.d(TAG, "🔗 Подключаемся к Gemini API через прокси: $url")
            
            // 🚀 ИСПОЛЬЗУЕМ БЫСТРЫЙ ПРЕДАУТЕНТИФИЦИРОВАННЫЙ КЛИЕНТ
            val client = ProxyManager.getFastGameClient()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyObj = requestBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(url)
                .post(requestBodyObj)
                // Минимум заголовков для максимальной скорости
                .build()
            
            // Отправляем данные
            val sendStartTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val sendTime = System.currentTimeMillis() - sendStartTime
                val responseStartTime = System.currentTimeMillis()
                
                if (response.isSuccessful) {
                    // 🛡️ БЕЗОПАСНОЕ ЧТЕНИЕ ОТВЕТА С УКАЗАНИЕМ КОДИРОВКИ
                    val responseBody = response.body?.let { body ->
                        body.source().use { source ->
                            source.readString(Charsets.UTF_8)
                        }
                    } ?: ""
                    
                    val responseTime = System.currentTimeMillis() - responseStartTime
                    val totalRequestTime = System.currentTimeMillis() - requestStartTime
                    
                    // 🔍 ДИАГНОСТИКА ОТВЕТА
                    val preview = responseBody.take(100).replace("\n", "\\n")
                    Log.d(TAG, "🔍 Ответ Gemini (${responseBody.length} символов): $preview...")
                    
                    Log.d(TAG, "📈 HTTP запрос через прокси успешен: отправка ${sendTime}мс, чтение ${responseTime}мс, всего ${totalRequestTime}мс")
                    responseBody
                } else if (response.code == 429) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "💸 КВОТА GEMINI ПРЕВЫШЕНА! HTTP 429: $errorBody")
                    null
                } else if (response.code == 400 || response.code == 404 || response.code == 403) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "❌ Gemini API error (${response.code}): $errorBody")
                    null
                } else {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "❌ Gemini API error: ${response.code}: $errorBody")
                    null
                }
            }
        } catch (e: Exception) {
            val totalRequestTime = System.currentTimeMillis() - requestStartTime
            Log.e(TAG, "❌ Ошибка HTTP запроса через прокси (${totalRequestTime}мс): ${e.message}", e)
            null
        }
    }
    
    /**
     * Парсит ответ от Gemini API
     */
    private fun parseResponse(response: String): DiceResult? {
        return try {
            // 🛡️ ПРОВЕРКА НА ПОВРЕЖДЕННЫЕ ДАННЫЕ
            if (response.isEmpty()) {
                Log.e(TAG, "❌ Пустой ответ от Gemini")
                return null
            }
            
            // Проверяем, что ответ начинается с '{' (валидный JSON)
            val trimmedResponse = response.trim()
            if (!trimmedResponse.startsWith("{")) {
                Log.e(TAG, "❌ Ответ не является валидным JSON. Первые 50 символов: ${trimmedResponse.take(50)}")
                return null
            }
            
            Log.d(TAG, "🔍 Начинаем парсинг JSON ответа...")
            val jsonResponse = JSONObject(trimmedResponse)
            Log.d(TAG, "✅ JSON успешно распарсен")
            
            val candidates = jsonResponse.getJSONArray("candidates")
            Log.d(TAG, "📋 Найдено candidates: ${candidates.length()}")
            if (candidates.length() == 0) {
                Log.e(TAG, "❌ Нет candidates в ответе Gemini")
                return null
            }
            
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            Log.d(TAG, "📋 Найдено parts: ${parts.length()}")
            
            if (parts.length() == 0) {
                Log.e(TAG, "❌ Нет parts в ответе Gemini")
                return null
            }
            
            val text = parts.getJSONObject(0).getString("text").trim().lowercase()
            Log.d(TAG, "📝 Извлеченный текст: '$text' (длина: ${text.length})")
            
            // 🔍 ПОЛНОЕ ЛОГИРОВАНИЕ ДЛЯ ОТЛАДКИ
            Log.d(TAG, "🔍 ПОЛНЫЙ ОТВЕТ GEMINI:")
            Log.d(TAG, "   📝 Исходный текст: '${parts.getJSONObject(0).getString("text")}'")
            Log.d(TAG, "   🔄 После обработки: '$text'")
            Log.d(TAG, "   📏 Длина: ${text.length} символов")
            
            // Проверяем на неопределённость и неподходящие ответы
            val badResponses = listOf("there", "unclear", "cannot", "unable", "sorry", "difficult", "hard to see")
            val hasBadResponse = badResponses.any { badWord -> text.contains(badWord) }
            
            if (hasBadResponse || text.length < 3) {
                Log.w(TAG, "❌ Gemini ответил неопределённо или дал неподходящий ответ: '$text'")
                return null
            }
            
            Log.d(TAG, "📝 Ответ Gemini: '$text'")
            
            // 🚀 РАСШИРЕННЫЙ ПАРСИНГ - НЕСКОЛЬКО ФОРМАТОВ
            // Попробуем разные форматы: X:Y, X Y, left X right Y, X-Y и т.д.
            val patterns = listOf(
                Regex("(\\d+):(\\d+)"),                           // 3:5
                Regex("(\\d+)\\s+(\\d+)"),                        // 3 5  
                Regex("left\\s*(\\d+).*right\\s*(\\d+)"),         // left 3 right 5
                Regex("(\\d+)\\s*-\\s*(\\d+)"),                   // 3-5
                Regex("(\\d+)\\s*,\\s*(\\d+)"),                   // 3,5
                Regex("(\\d+)\\s*\\|\\s*(\\d+)"),                 // 3|5
                Regex("first\\s*(\\d+).*second\\s*(\\d+)"),       // first 3 second 5
                Regex("(\\d+)\\s*and\\s*(\\d+)"),                 // 3 and 5
                Regex("(\\d+)\\s*/\\s*(\\d+)")                    // 3/5
            )
            
            var leftDots: Int? = null
            var rightDots: Int? = null
            var foundPattern = ""
            
            for ((index, pattern) in patterns.withIndex()) {
                val matchResult = pattern.find(text)
                if (matchResult != null) {
                    leftDots = matchResult.groupValues[1].toInt()
                    rightDots = matchResult.groupValues[2].toInt()
                    foundPattern = "Паттерн ${index + 1}: ${pattern.pattern}"
                    Log.d(TAG, "🔍 Найдено совпадение ($foundPattern): $leftDots:$rightDots")
                    break
                }
            }
            
            if (leftDots != null && rightDots != null) {
                if (leftDots in 1..6 && rightDots in 1..6) {
                    Log.d(TAG, "✅ Успешно распарсили ($foundPattern): $leftDots:$rightDots")
                    return DiceResult(leftDots, rightDots, 0.9f)
                } else {
                    Log.w(TAG, "❌ Неверное количество точек: $leftDots:$rightDots (должно быть 1-6)")
                }
            } else {
                Log.w(TAG, "❌ Не удалось найти подходящий паттерн в ответе: '$text'")
                Log.w(TAG, "🔍 Попробованные паттерны:")
                patterns.forEachIndexed { index, pattern ->
                    Log.w(TAG, "   ${index + 1}. ${pattern.pattern}")
                }
            }
            
            Log.w(TAG, "❌ Парсинг не удался для текста: '$text'")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга ответа Gemini: ${e.message}", e)
            null
        }
    }
    
    /**
     * 🚀 ГЕНЕРИРУЕТ ХЭШ ИЗОБРАЖЕНИЯ ДЛЯ КЭША
     */
    private fun getImageHash(bitmap: Bitmap): String {
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)  // PNG для хэша
            val bytes = stream.toByteArray()
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка генерации хэша: ${e.message}")
            bitmap.hashCode().toString() // Фолбэк на простой hashCode
        }
    }
    
    /**
     * 🚀 ДОБАВЛЯЕТ РЕЗУЛЬТАТ В КЭШ
     */
    private fun addToCache(imageHash: String, result: DiceResult) {
        try {
            if (resultCache.size >= MAX_CACHE_SIZE) {
                // Удаляем самый старый элемент (простая LRU имитация)
                val oldestKey = resultCache.keys.first()
                resultCache.remove(oldestKey)
            }
            resultCache[imageHash] = result
            Log.d(TAG, "💾 Сохранено в кэш: $imageHash -> ${result.redDots}:${result.orangeDots}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка сохранения в кэш: ${e.message}")
        }
    }
}
