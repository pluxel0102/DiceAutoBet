package com.example.diceautobet.recognition

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.diceautobet.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.example.diceautobet.utils.ProxyManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Распознавание кубиков через OpenRouter API
 * Поддерживаемые модели:
 * - Claude 4.5 (anthropic/claude-3.5-sonnet)
 * - ChatGPT 5 (openai/gpt-4o)
 * - Gemini 2.5 Flash-Lite (google/gemini-2.0-flash-exp:free)
 * 
 * ✅ Сохраняет логику MD5 хэширования и кэширования
 * ✅ Использует ProxyManager для соединения
 * ✅ Поддерживает выбор модели
 */
class OpenRouterDiceRecognizer(private val apiKey: String) {

    companion object {
        private const val TAG = "OpenRouterRecognizer"
        private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MAX_TOKENS = 50
        private const val JPEG_QUALITY = 85
        
        // 🚀 КЭШ ДЛЯ СКОРОСТИ - храним последние 50 результатов
        private val resultCache = mutableMapOf<String, DiceResult>()
        private const val MAX_CACHE_SIZE = 50
    }
    
    /**
     * Поддерживаемые модели OpenRouter
     */
    enum class Model(val modelId: String, val displayName: String) {
        CLAUDE_45("anthropic/claude-sonnet-4.5", "Claude 4.5"),
        CHATGPT_5("openai/gpt-5-chat", "ChatGPT 5"), 
        GEMINI_25_FLASH_LITE("google/gemini-2.5-flash-preview-09-2025", "Gemini 2.5 Flash-Lite")
    }

    data class DiceResult(
        val redDots: Int,
        val orangeDots: Int,
        val confidence: Float = 0.9f,
        val rawResponse: String = ""
    )

    /**
     * Анализирует изображение кубиков через OpenRouter API
     * @param bitmap - изображение для анализа
     * @param model - выбранная модель для распознавания
     * @param openCvResult - результат OpenCV для сравнения (опционально)
     */
    suspend fun analyzeDice(bitmap: Bitmap, model: Model, openCvResult: DiceResult? = null): DiceResult? {
        val startTime = System.currentTimeMillis()
        FileLogger.i(TAG, "🤖 analyzeDice() START: модель=${model.displayName}, размер=${bitmap.width}x${bitmap.height}")
        
        return withContext(Dispatchers.IO) {
            try {
                // 🚀 ПРОВЕРКА КЭША ПО MD5 ХЭШУ
                val imageHash = getImageHash(bitmap)
                resultCache[imageHash]?.let { cachedResult ->
                    val cacheTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "⚡ КЭШ ПОПАДАНИЕ! Возвращаем результат мгновенно: ${cachedResult.redDots}:${cachedResult.orangeDots} (время: ${cacheTime}мс)")
                    FileLogger.d(TAG, "⚡ КЭШ: ${cachedResult.redDots}:${cachedResult.orangeDots} (${cacheTime}мс)")
                    return@withContext cachedResult
                }
                
                Log.d(TAG, "🔍 Анализируем изображение через OpenRouter API (модель: ${model.displayName})...")
                Log.d(TAG, "📊 Размер изображения: ${bitmap.width}x${bitmap.height}")
                FileLogger.i(TAG, "🌐 Отправка запроса к OpenRouter API: ${model.displayName}")
                
                // Конвертируем bitmap в base64
                val base64Image = bitmapToBase64(bitmap)
                if (base64Image == null) {
                    Log.e(TAG, "❌ Не удалось конвертировать изображение в base64")
                    FileLogger.e(TAG, "❌ ОШИБКА: Конвертация в base64 не удалась")
                    return@withContext null
                }
                
                val imageSizeKb = base64Image.length * 3 / 4 / 1024
                Log.d(TAG, "📊 Размер base64: ${imageSizeKb}KB (${base64Image.length} символов)")
                FileLogger.d(TAG, "📊 Base64: ${imageSizeKb}KB")
                
                // Формируем запрос к OpenRouter
                val requestBody = createRequestBody(base64Image, model)
                Log.d(TAG, "📝 JSON запрос сформирован для модели ${model.displayName}")
                
                // Отправляем запрос
                Log.d(TAG, "🌐 Отправляем HTTP запрос к OpenRouter API...")
                FileLogger.d(TAG, "📤 HTTP запрос к API...")
                val response = sendApiRequest(requestBody)
                if (response == null) {
                    Log.e(TAG, "❌ Не удалось получить ответ от OpenRouter API")
                    FileLogger.e(TAG, "❌ ОШИБКА: Нет ответа от OpenRouter API")
                    return@withContext null
                }
                
                Log.d(TAG, "📨 Получен ответ от OpenRouter (размер: ${response.length} символов)")
                FileLogger.d(TAG, "📨 Ответ получен: ${response.length} символов")
                
                // Парсим ответ
                val result = parseResponse(response)
                if (result != null) {
                    val totalTime = System.currentTimeMillis() - startTime
                    
                    // 🚀 СОХРАНЯЕМ В КЭШ ПО MD5 ХЭШУ
                    addToCache(imageHash, result)
                    
                    Log.d(TAG, "✅ УСПЕХ: ${result.redDots}:${result.orangeDots} (модель: ${model.displayName}, время: ${totalTime}мс)")
                    FileLogger.i(TAG, "✅ УСПЕХ OpenRouter: левый=${result.redDots}, правый=${result.orangeDots}, confidence=${result.confidence}, время=${totalTime}мс")
                    
                    Log.d(TAG, "🔍 ДЕТАЛИ РАСПОЗНАВАНИЯ:")
                    Log.d(TAG, "   🤖 Модель: ${model.displayName}")
                    Log.d(TAG, "   📏 Размер изображения: ${bitmap.width}x${bitmap.height}")
                    Log.d(TAG, "   🎯 Результат: ЛЕВЫЙ кубик = ${result.redDots} точек, ПРАВЫЙ кубик = ${result.orangeDots} точек")
                    Log.d(TAG, "   📊 Уверенность: ${result.confidence}")
                    
                    // 🔍 СРАВНЕНИЕ С OPENCV (если доступно)
                    openCvResult?.let { opencv ->
                        val isMatch = opencv.redDots == result.redDots && opencv.orangeDots == result.orangeDots
                        Log.d(TAG, "🆚 СРАВНЕНИЕ С OPENCV:")
                        Log.d(TAG, "   🤖 OpenCV: ${opencv.redDots}:${opencv.orangeDots}")
                        Log.d(TAG, "   🧠 OpenRouter (${model.displayName}): ${result.redDots}:${result.orangeDots}")
                        Log.d(TAG, "   ${if (isMatch) "✅ СОВПАДЕНИЕ!" else "❌ РАСХОЖДЕНИЕ!"}")
                        
                        if (!isMatch) {
                            Log.w(TAG, "⚠️ ВНИМАНИЕ: OpenRouter и OpenCV дали разные результаты!")
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Не удалось распарсить ответ OpenRouter")
                }
                
                result
            } catch (e: Exception) {
                val totalTime = System.currentTimeMillis() - startTime
                Log.e(TAG, "❌ Ошибка при анализе через OpenRouter: ${e.message} (время: ${totalTime}мс)", e)
                null
            }
        }
    }

    /**
     * Конвертирует Bitmap в base64 строку
     */
    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка конвертации bitmap в base64: ${e.message}")
            null
        }
    }

    /**
     * Формирует JSON запрос для OpenRouter API
     */
    private fun createRequestBody(base64Image: String, model: Model): String {
        val requestJson = JSONObject().apply {
            put("model", model.modelId)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", """
                                Analyze this dice image. I see two dice - one on the left and one on the right.
                                Count the dots on each die and respond ONLY in this exact format: left:right
                                For example: 3:5 or 1:6
                                Count carefully - each die shows 1 to 6 dots.
                                Just the numbers in format X:Y, nothing else.
                            """.trimIndent())
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }
        return requestJson.toString()
    }

    /**
     * Отправляет запрос к OpenRouter API (с fallback на прямое подключение)
     */
    private fun sendApiRequest(requestBody: String): String? {
        FileLogger.d(TAG, "📡 sendApiRequest() START")
        return try {
            val isProxyEnabled = ProxyManager.isProxyEnabled()
            val connectionType = if (isProxyEnabled) "через прокси" else "напрямую"
            Log.d(TAG, "🔗 Подключаемся к OpenRouter API $connectionType: $OPENROUTER_API_URL")
            FileLogger.i(TAG, "🔗 Подключение к OpenRouter API $connectionType")
            
            val client = ProxyManager.getHttpClient()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyObj = requestBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(OPENROUTER_API_URL)
                .post(requestBodyObj)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://diceautobet.app")
                .addHeader("X-Title", "Dice Auto Bet")
                .build()
            
            Log.d(TAG, "📤 Отправляем данные $connectionType (размер: ${requestBody.length} байт)...")
            FileLogger.d(TAG, "📤 Отправка запроса: размер=${requestBody.length} байт")
            
            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                Log.d(TAG, "📨 OpenRouter API response code: $responseCode")
                FileLogger.d(TAG, "📨 HTTP код ответа: $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "✅ Успешный ответ получен $connectionType (размер: ${responseBody.length} символов)")
                    FileLogger.i(TAG, "✅ Успешный ответ: ${responseBody.length} символов")
                    responseBody
                } else if (responseCode == 429) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "💸 КВОТА OPENROUTER ПРЕВЫШЕНА! HTTP 429")
                    Log.e(TAG, "💡 Проверьте баланс на https://openrouter.ai/")
                    Log.e(TAG, "❌ Error details: $errorBody")
                    FileLogger.e(TAG, "💸 КВОТА ПРЕВЫШЕНА! HTTP 429: $errorBody")
                    null
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "❌ OpenRouter API error: $responseCode")
                    Log.e(TAG, "❌ HTTP Status: ${response.message}")
                    Log.e(TAG, "❌ Error details: $errorBody")
                    Log.e(TAG, "❌ Request URL: ${request.url}")
                    Log.e(TAG, "❌ Request headers: Authorization=Bearer ${apiKey.take(10)}..., Content-Type=${request.header("Content-Type")}")
                    FileLogger.e(TAG, "❌ API ОШИБКА: код=$responseCode, сообщение=${response.message}, детали=$errorBody")
                    null
                }
            }
        } catch (e: Exception) {
            val isProxyEnabled = ProxyManager.isProxyEnabled()
            Log.e(TAG, "❌ Ошибка HTTP запроса (${if (isProxyEnabled) "прокси" else "прямое"}): ${e.message}", e)
            Log.e(TAG, "❌ Exception class: ${e.javaClass.simpleName}")
            FileLogger.e(TAG, "❌ HTTP ОШИБКА (${if (isProxyEnabled) "прокси" else "прямое"}): ${e.javaClass.simpleName} - ${e.message}")
            
            // 🔄 FALLBACK: если прокси не работает, пробуем напрямую
            if (isProxyEnabled && e is java.net.SocketException && e.message?.contains("SOCKS") == true) {
                Log.w(TAG, "⚠️ Прокси блокирует OpenRouter! Пробуем прямое подключение...")
                FileLogger.w(TAG, "⚠️ Прокси блокирует! Переключаюсь на прямое подключение...")
                return sendApiRequestDirect(requestBody)
            }
            
            if (e.cause != null) {
                Log.e(TAG, "❌ Caused by: ${e.cause?.message}")
                FileLogger.e(TAG, "❌ Причина: ${e.cause?.message}")
            }
            null
        }
    }
    
    /**
     * Отправляет запрос напрямую (без прокси) - fallback метод
     */
    private fun sendApiRequestDirect(requestBody: String): String? {
        FileLogger.d(TAG, "🌍 sendApiRequestDirect() START - попытка прямого подключения")
        return try {
            Log.d(TAG, "🌍 Отправляем запрос к OpenRouter напрямую (без прокси)...")
            
            // Создаем клиент БЕЗ прокси
            val directClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyObj = requestBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(OPENROUTER_API_URL)
                .post(requestBodyObj)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://diceautobet.app")
                .addHeader("X-Title", "Dice Auto Bet")
                .build()
            
            directClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                Log.d(TAG, "📨 OpenRouter API response (direct): $responseCode")
                FileLogger.d(TAG, "📨 Прямое подключение: HTTP код $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "✅ Успешный ответ через прямое подключение!")
                    FileLogger.i(TAG, "✅ Прямое подключение успешно: ${responseBody.length} символов")
                    responseBody
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "❌ OpenRouter API error (direct): $responseCode - $errorBody")
                    FileLogger.e(TAG, "❌ Прямое подключение ошибка: $responseCode - $errorBody")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка прямого подключения: ${e.message}")
            FileLogger.e(TAG, "❌ Прямое подключение провалено: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    /**
     * Парсит ответ от OpenRouter API
     */
    private fun parseResponse(response: String): DiceResult? {
        FileLogger.d(TAG, "🔍 parseResponse() START: длина=${response.length}")
        return try {
            if (response.isEmpty()) {
                Log.e(TAG, "❌ Пустой ответ от OpenRouter")
                FileLogger.e(TAG, "❌ ПАРСИНГ: Пустой ответ")
                return null
            }
            
            val jsonResponse = JSONObject(response)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() == 0) {
                Log.e(TAG, "❌ Нет choices в ответе OpenRouter")
                FileLogger.e(TAG, "❌ ПАРСИНГ: Нет choices в JSON")
                return null
            }
            
            val message = choices.getJSONObject(0).getJSONObject("message")
            val content = message.getString("content").trim().lowercase()
            
            Log.d(TAG, "📝 Ответ OpenRouter: '$content'")
            FileLogger.d(TAG, "📝 Содержимое ответа API: '$content'")
            
            // Проверяем на неопределённость
            val badResponses = listOf("unable", "cannot", "unclear", "sorry", "difficult")
            if (badResponses.any { content.contains(it) } || content.length < 3) {
                Log.w(TAG, "❌ OpenRouter ответил неопределённо: '$content'")
                FileLogger.w(TAG, "⚠️ ПАРСИНГ: Неопределённый ответ: '$content'")
                return null
            }
            
            // 🚀 РАСШИРЕННЫЙ ПАРСИНГ - НЕСКОЛЬКО ФОРМАТОВ
            val patterns = listOf(
                Regex("(\\d+):(\\d+)"),                           // 3:5
                Regex("left\\s*(\\d+).*right\\s*(\\d+)"),         // left 3 right 5
                Regex("(\\d+)\\s+(\\d+)"),                        // 3 5
                Regex("(\\d+)\\s*-\\s*(\\d+)"),                   // 3-5
                Regex("(\\d+)\\s*,\\s*(\\d+)"),                   // 3,5
                Regex("(\\d+)\\s*\\|\\s*(\\d+)"),                 // 3|5
                Regex("(\\d+)\\s*/\\s*(\\d+)")                    // 3/5
            )
            
            for (pattern in patterns) {
                val matchResult = pattern.find(content)
                if (matchResult != null) {
                    val leftDots = matchResult.groupValues[1].toInt()
                    val rightDots = matchResult.groupValues[2].toInt()
                    
                    if (leftDots in 1..6 && rightDots in 1..6) {
                        val isDraw = leftDots == rightDots
                        Log.d(TAG, "✅ Успешно распарсили: $leftDots:$rightDots${if (isDraw) " (НИЧЬЯ)" else ""}")
                        FileLogger.i(TAG, "✅ ПАРСИНГ УСПЕШЕН: левый=$leftDots, правый=$rightDots${if (isDraw) " (НИЧЬЯ)" else ""}")
                        // Возвращаем внутренний DiceResult с правильными именами параметров
                        return DiceResult(
                            redDots = leftDots,
                            orangeDots = rightDots,
                            confidence = 0.9f,
                            rawResponse = content
                        )
                    } else {
                        Log.w(TAG, "❌ Неверное количество точек: $leftDots:$rightDots")
                        FileLogger.w(TAG, "⚠️ ПАРСИНГ: Недопустимые значения: $leftDots:$rightDots")
                    }
                }
            }
            
            Log.w(TAG, "❌ Не удалось распарсить ответ: '$content'")
            FileLogger.w(TAG, "❌ ПАРСИНГ ПРОВАЛЕН: '$content'")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга ответа OpenRouter: ${e.message}", e)
            FileLogger.e(TAG, "❌ ОШИБКА ПАРСИНГА: ${e.message}")
            null
        }
    }
    
    /**
     * 🚀 ГЕНЕРИРУЕТ MD5 ХЭШ ИЗОБРАЖЕНИЯ ДЛЯ КЭША
     */
    private fun getImageHash(bitmap: Bitmap): String {
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка генерации MD5 хэша: ${e.message}")
            bitmap.hashCode().toString()
        }
    }
    
    /**
     * 🚀 ДОБАВЛЯЕТ РЕЗУЛЬТАТ В КЭШ
     */
    private fun addToCache(imageHash: String, result: DiceResult) {
        try {
            if (resultCache.size >= MAX_CACHE_SIZE) {
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
