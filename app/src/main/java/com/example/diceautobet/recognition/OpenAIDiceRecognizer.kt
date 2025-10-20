package com.example.diceautobet.recognition

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.example.diceautobet.utils.ProxyManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Распознавание кубиков с помощью OpenAI Vision API
 */
class OpenAIDiceRecognizer(private val apiKey: String) {

    companion object {
        private const val TAG = "OpenAIDiceRecognizer"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o" // Модель с vision
        private const val MAX_TOKENS = 50
        private const val JPEG_QUALITY = 85
    }

    data class DiceResult(
        val redDots: Int,
        val orangeDots: Int,
        val confidence: Float = 1.0f,
        val rawResponse: String = ""
    )

    /**
     * Анализирует изображение кубиков через OpenAI API
     */
    suspend fun analyzeDice(bitmap: Bitmap): DiceResult? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🤖 Начинаем анализ кубиков через OpenAI...")
                Log.d(TAG, "📊 Размер изображения: ${bitmap.width}x${bitmap.height}")
                
                // Проверим интернет соединение
                Log.d(TAG, "🌐 Проверяем интернет соединение...")
                
                // Конвертируем bitmap в base64
                val base64Image = bitmapToBase64(bitmap)
                if (base64Image == null) {
                    Log.e(TAG, "❌ Не удалось конвертировать изображение в base64")
                    return@withContext null
                }
                
                Log.d(TAG, "📷 Изображение конвертировано в base64 (размер: ${base64Image.length} символов)")
                
                // Формируем запрос к OpenAI
                val requestBody = createRequestBody(base64Image)
                Log.d(TAG, "📝 JSON запрос сформирован (размер: ${requestBody.length} символов)")
                
                // Отправляем запрос
                Log.d(TAG, "🌐 Отправляем HTTP запрос к OpenAI API...")
                val response = sendApiRequest(requestBody)
                if (response == null) {
                    Log.e(TAG, "❌ Не удалось получить ответ от OpenAI API")
                    return@withContext null
                }
                
                Log.d(TAG, "📨 Получен ответ от OpenAI (размер: ${response.length} символов)")
                
                // Парсим ответ
                val result = parseResponse(response)
                if (result != null) {
                    Log.d(TAG, "✅ OpenAI результат: red=${result.redDots}, orange=${result.orangeDots}, confidence=${result.confidence}")
                } else {
                    Log.e(TAG, "❌ Не удалось распарсить ответ OpenAI")
                }
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка при анализе через OpenAI: ${e.message}", e)
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
     * Формирует JSON запрос для OpenAI API
     */
    private fun createRequestBody(base64Image: String): String {
        val requestJson = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", """
                                Analyze this dice image. I see two dice - one RED on the left and one ORANGE on the right.
                                Count the dots on each die and respond ONLY in this exact format: red{number}:orange{number}
                                For example: red3:orange5 or red1:orange6
                                Count carefully - each die shows 1 to 6 dots.
                            """.trimIndent())
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                                put("detail", "low") // Экономим токены
                            })
                        })
                    })
                })
            })
        }
        return requestJson.toString()
    }

    /**
     * Отправляет запрос к OpenAI API через прокси
     */
    private fun sendApiRequest(requestBody: String): String? {
        return try {
            Log.d(TAG, "🔗 Подключаемся к OpenAI API через прокси: $OPENAI_API_URL")
            
            val client = ProxyManager.getHttpClient()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBodyObj = requestBody.toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(OPENAI_API_URL)
                .post(requestBodyObj)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            
            Log.d(TAG, "📤 Отправляем данные через прокси (размер: ${requestBody.length} байт)...")
            
            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                Log.d(TAG, "📨 OpenAI API response code: $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "✅ Успешный ответ получен через прокси (размер: ${responseBody.length} символов)")
                    responseBody
                } else if (responseCode == 429) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "💸 КВОТА OPENAI ПРЕВЫШЕНА! HTTP 429")
                    Log.e(TAG, "💡 Пополните баланс на https://platform.openai.com/account/billing")
                    Log.e(TAG, "💡 Или переключитесь на режим OpenCV в настройках")
                    Log.e(TAG, "❌ Error details: $errorBody")
                    null
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "❌ OpenAI API error: $responseCode")
                    Log.e(TAG, "❌ HTTP Status: ${response.message}")
                    Log.e(TAG, "❌ Error details: $errorBody")
                    Log.e(TAG, "❌ Request URL: ${request.url}")
                    Log.e(TAG, "❌ Request headers: Authorization=Bearer ${apiKey.take(10)}..., Content-Type=${request.header("Content-Type")}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка HTTP запроса через прокси: ${e.message}", e)
            Log.e(TAG, "❌ Exception class: ${e.javaClass.simpleName}")
            if (e.cause != null) {
                Log.e(TAG, "❌ Caused by: ${e.cause?.message}")
            }
            null
        }
    }

    /**
     * Парсит ответ от OpenAI API
     */
    private fun parseResponse(response: String): DiceResult? {
        return try {
            val jsonResponse = JSONObject(response)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() == 0) {
                Log.e(TAG, "OpenAI ответ не содержит choices")
                return null
            }
            
            val message = choices.getJSONObject(0).getJSONObject("message")
            val content = message.getString("content").trim()
            
            Log.d(TAG, "OpenAI ответ: '$content'")
            
            // Парсим формат "red3:orange5"
            val regex = Regex("red(\\d):orange(\\d)")
            val matchResult = regex.find(content)
            
            if (matchResult != null) {
                val redDots = matchResult.groupValues[1].toInt()
                val orangeDots = matchResult.groupValues[2].toInt()
                
                // Валидация результата
                if (redDots in 1..6 && orangeDots in 1..6) {
                    DiceResult(
                        redDots = redDots,
                        orangeDots = orangeDots,
                        confidence = 1.0f, // OpenAI всегда уверен
                        rawResponse = content
                    )
                } else {
                    Log.e(TAG, "Неверные значения кубиков: red=$redDots, orange=$orangeDots")
                    null
                }
            } else {
                Log.e(TAG, "Не удалось распарсить ответ OpenAI: '$content'")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга ответа OpenAI: ${e.message}", e)
            null
        }
    }
}
