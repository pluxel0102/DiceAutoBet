package com.example.diceautobet.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.diceautobet.recognition.OpenAIDiceRecognizer
import com.example.diceautobet.recognition.OpenRouterDiceRecognizer
import com.example.diceautobet.opencv.DotCounter
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Гибридный анализатор кубиков, поддерживающий OpenCV, OpenAI (устаревший) и OpenRouter
 * OpenRouter предоставляет доступ к Claude 4.5, ChatGPT 5, Gemini 2.5 Flash-Lite
 * 
 * ✅ Поддерживает детекцию статичных надписей ("ОЖИДАНИЕ ПАРТИИ")
 */
class HybridDiceRecognizer(
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "HybridDiceRecognizer"
        
        // 🎯 Настройки детекции статичных экранов
        private const val BRIGHT_THRESHOLD = 200 // Порог яркости для текста
        private const val BRIGHT_PERCENTAGE_THRESHOLD = 60 // % ярких пикселей
        private const val STATIC_IMAGE_TIMEOUT = 3000L // 3 секунды
    }

    private var openAIRecognizer: OpenAIDiceRecognizer? = null
    private var openRouterRecognizer: OpenRouterDiceRecognizer? = null
    
    // 🔍 Детекция статичных изображений
    private var lastStableHash: String? = null
    private var lastStableTime: Long = 0

    /**
     * Инициализирует AI распознаватели при необходимости
     */
    private fun initAI() {
        val provider = preferencesManager.getAIProvider()
        
        try {
            when (provider) {
                PreferencesManager.AIProvider.OPENAI -> {
                    val apiKey = preferencesManager.getOpenAIApiKey()
                    if (apiKey.isNotEmpty() && openAIRecognizer == null) {
                        Log.d(TAG, "🔧 Инициализируем OpenAI распознаватель (УСТАРЕВШИЙ)")
                        openAIRecognizer = OpenAIDiceRecognizer(apiKey)
                    }
                }
                PreferencesManager.AIProvider.OPENROUTER -> {
                    val apiKey = preferencesManager.getOpenRouterApiKey()
                    if (apiKey.isNotEmpty() && openRouterRecognizer == null) {
                        val model = preferencesManager.getOpenRouterModel()
                        Log.d(TAG, "🔧 Инициализируем OpenRouter распознаватель (модель: ${model.displayName})")
                        openRouterRecognizer = OpenRouterDiceRecognizer(apiKey)
                    }
                }
                PreferencesManager.AIProvider.GEMINI -> {
                    // УСТАРЕВШИЙ: Gemini теперь через OpenRouter
                    Log.w(TAG, "⚠️ Gemini API устарел! Используйте OpenRouter вместо него.")
                    Log.w(TAG, "💡 OpenRouter поддерживает Gemini 2.5 Flash-Lite бесплатно!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка инициализации AI: ${e.message}", e)
        }
    }

    /**
     * Анализирует изображение кубика и возвращает количество точек
     */
    suspend fun analyzeDice(bitmap: Bitmap): DotCounter.Result? {
        FileLogger.i(TAG, "🎲 analyzeDice() START: размер=${bitmap.width}x${bitmap.height}")
        return withContext(Dispatchers.Default) {
            // 🛡️ ПРОВЕРКА: Статичная надпись или зависание?
            // Определяем тип изображения: если маленькое (<1000x1000) - это вырезанная область
            val isCroppedArea = bitmap.width < 1000 && bitmap.height < 1000
            val checkTextOverlay = isCroppedArea // Проверяем текст для вырезанных областей
            
            Log.d(TAG, "🔍 Изображение: ${bitmap.width}x${bitmap.height}, проверка текста: $checkTextOverlay")
            FileLogger.d(TAG, "🔍 Тип: ${if (isCroppedArea) "вырезка" else "полный"}, проверка текста: $checkTextOverlay")
            
            if (StaticFrameDetector.shouldSkipFrame(bitmap, checkTextOverlay)) {
                Log.d(TAG, "⏭️ Пропускаем кадр (статичная надпись или зависание)")
                FileLogger.w(TAG, "⏭️ КАДР ПРОПУЩЕН: статичная надпись/зависание")
                return@withContext null
            }
            
            val mode = preferencesManager.getRecognitionMode()
            val aiProvider = preferencesManager.getAIProvider()
            val isConfigured = preferencesManager.isAIConfigured()
            
            Log.d(TAG, "🎯 Режим распознавания: $mode")
            Log.d(TAG, "🤖 AI провайдер: $aiProvider")
            Log.d(TAG, "🔑 AI настроен: $isConfigured")
            FileLogger.i(TAG, "⚙️ Режим=$mode, провайдер=$aiProvider, настроен=$isConfigured")

            when (mode) {
                PreferencesManager.RecognitionMode.OPENCV -> {
                    Log.d(TAG, "📊 Используем только OpenCV")
                    FileLogger.d(TAG, "📊 Режим: только OpenCV")
                    analyzeWithOpenCV(bitmap)
                }
                PreferencesManager.RecognitionMode.OPENAI -> {
                    Log.d(TAG, "🤖 Используем только OpenAI (УСТАРЕВШИЙ)")
                    analyzeWithAI(bitmap)
                }
                PreferencesManager.RecognitionMode.GEMINI -> {
                    Log.w(TAG, "⚠️ Gemini режим УСТАРЕЛ! Переключитесь на OpenRouter.")
                    Log.w(TAG, "� Используем OpenCV как fallback")
                    analyzeWithOpenCV(bitmap)
                }
                PreferencesManager.RecognitionMode.OPENROUTER -> {
                    Log.d(TAG, "🌐 Используем OpenRouter")
                    analyzeWithAI(bitmap)
                }
                PreferencesManager.RecognitionMode.HYBRID -> {
                    Log.d(TAG, "🔄 Используем гибридный режим (OpenCV + AI)")
                    analyzeHybrid(bitmap)
                }
                else -> {
                    Log.w(TAG, "⚠️ Неизвестный режим, используем OpenCV")
                    analyzeWithOpenCV(bitmap)
                }
            }
        }
    }

    /**
     * Распознавание только через OpenCV
     */
    private suspend fun analyzeWithOpenCV(bitmap: Bitmap): DotCounter.Result {
        Log.d(TAG, "🔧 Анализ через OpenCV")
        return DotCounter.count(bitmap)
    }

    /**
     * Распознавание через AI (универсальный метод)
     */
    private suspend fun analyzeWithAI(bitmap: Bitmap): DotCounter.Result? {
        val mode = preferencesManager.getRecognitionMode()
        val provider = preferencesManager.getAIProvider()
        
        Log.d(TAG, "🤖 Анализ через AI: mode=$mode, provider=$provider")
        
        if (!preferencesManager.isAIConfigured()) {
            Log.w(TAG, "⚠️ AI не настроен, переключаемся на OpenCV")
            return analyzeWithOpenCV(bitmap)
        }
        
        return when (provider) {
            PreferencesManager.AIProvider.OPENAI -> analyzeWithOpenAI(bitmap)
            PreferencesManager.AIProvider.OPENROUTER -> analyzeWithOpenRouter(bitmap)
            PreferencesManager.AIProvider.GEMINI -> {
                Log.w(TAG, "⚠️ Gemini API УСТАРЕЛ! Используйте OpenRouter.")
                Log.w(TAG, "💡 Используем OpenCV как fallback")
                analyzeWithOpenCV(bitmap)
            }
        }
    }

    /**
     * Анализ через OpenAI API
     */
    private suspend fun analyzeWithOpenAI(bitmap: Bitmap): DotCounter.Result? {
        initAI()
        
        if (openAIRecognizer == null) {
            Log.w(TAG, "⚠️ OpenAI не инициализирован, используем OpenCV")
            return analyzeWithOpenCV(bitmap)
        }

        Log.d(TAG, "🌐 Отправляем запрос к OpenAI API...")
        val result = try {
            openAIRecognizer!!.analyzeDice(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "🚨 КРИТИЧЕСКАЯ ОШИБКА OpenAI: ${e.message}", e)
            Log.e(TAG, "🚨 Стектрейс: ${e.stackTrace.contentToString()}")
            null
        }
        
        return if (result != null) {
            Log.d(TAG, "✅ OpenAI ответил: red=${result.redDots}, orange=${result.orangeDots}")
            DotCounter.Result(
                leftDots = result.redDots,
                rightDots = result.orangeDots,
                confidence = result.confidence
            )
        } else {
            Log.w(TAG, "⚠️ OpenAI не смог распознать, используем OpenCV как fallback")
            analyzeWithOpenCV(bitmap)
        }
    }

    /**
     * Анализ через OpenRouter API
     */
    private suspend fun analyzeWithOpenRouter(bitmap: Bitmap): DotCounter.Result? {
        initAI()
        
        if (openRouterRecognizer == null) {
            Log.w(TAG, "⚠️ OpenRouter не инициализирован, используем OpenCV")
            return analyzeWithOpenCV(bitmap)
        }

        val selectedModel = preferencesManager.getOpenRouterModel()
        val model = when (selectedModel) {
            PreferencesManager.OpenRouterModel.CLAUDE_45 -> OpenRouterDiceRecognizer.Model.CLAUDE_45
            PreferencesManager.OpenRouterModel.CHATGPT_5 -> OpenRouterDiceRecognizer.Model.CHATGPT_5
            PreferencesManager.OpenRouterModel.GEMINI_25_FLASH_LITE -> OpenRouterDiceRecognizer.Model.GEMINI_25_FLASH_LITE
        }

        Log.d(TAG, "🌐 Отправляем запрос к OpenRouter API (модель: ${model.displayName})...")
        val result = try {
            openRouterRecognizer!!.analyzeDice(bitmap, model)
        } catch (e: Exception) {
            Log.e(TAG, "🚨 КРИТИЧЕСКАЯ ОШИБКА OpenRouter: ${e.message}", e)
            Log.e(TAG, "🚨 Стектрейс: ${e.stackTrace.contentToString()}")
            null
        }
        
        return if (result != null) {
            Log.d(TAG, "✅ OpenRouter (${model.displayName}) ответил: red=${result.redDots}, orange=${result.orangeDots}")
            DotCounter.Result(
                leftDots = result.redDots,
                rightDots = result.orangeDots,
                confidence = result.confidence
            )
        } else {
            Log.w(TAG, "⚠️ OpenRouter не смог распознать, используем OpenCV как fallback")
            analyzeWithOpenCV(bitmap)
        }
    }

    /**
     * Гибридный анализ: сначала OpenCV, потом AI для подтверждения
     */
    private suspend fun analyzeHybrid(bitmap: Bitmap): DotCounter.Result {
        Log.d(TAG, "🔄 Гибридный анализ: OpenCV + AI")
        
        // Сначала пробуем OpenCV
        val openCvResult = analyzeWithOpenCV(bitmap)
        Log.d(TAG, "📊 OpenCV результат: left=${openCvResult.leftDots}, right=${openCvResult.rightDots}, confidence=${openCvResult.confidence}")
        
        // Если уверенность OpenCV низкая, пробуем AI
        if (openCvResult.confidence < 0.7) {
            Log.d(TAG, "⚠️ Низкая уверенность OpenCV (${openCvResult.confidence}), пробуем AI")
            
            val aiResult = analyzeWithAI(bitmap)
            if (aiResult != null) {
                Log.d(TAG, "🤖 AI результат: left=${aiResult.leftDots}, right=${aiResult.rightDots}")
                
                // Если AI более уверен, используем его результат
                if (aiResult.confidence > openCvResult.confidence) {
                    Log.d(TAG, "✅ Используем AI результат (выше уверенность)")
                    return aiResult
                }
            }
        }
        
        Log.d(TAG, "✅ Используем OpenCV результат")
        return openCvResult
    }

    /**
     * Очистка ресурсов
     */
    fun cleanup() {
        openAIRecognizer = null
        openRouterRecognizer = null
        Log.d(TAG, "🧹 Ресурсы очищены")
    }

    /**
     * Проверяет доступность AI сервисов
     */
    suspend fun checkAIAvailability(): Boolean {
        val provider = preferencesManager.getAIProvider()
        val isConfigured = preferencesManager.isAIConfigured()
        
        Log.d(TAG, "🔍 Проверка AI: provider=$provider, configured=$isConfigured")
        
        if (!isConfigured) {
            Log.w(TAG, "❌ AI не настроен")
            return false
        }
        
        initAI()
        
        return when (provider) {
            PreferencesManager.AIProvider.OPENAI -> {
                Log.d(TAG, "🔍 Проверяем OpenAI (УСТАРЕВШИЙ)...")
                openAIRecognizer != null
            }
            PreferencesManager.AIProvider.OPENROUTER -> {
                Log.d(TAG, "🔍 Проверяем OpenRouter...")
                openRouterRecognizer != null
            }
            PreferencesManager.AIProvider.GEMINI -> {
                Log.w(TAG, "⚠️ Gemini API УСТАРЕЛ! Используйте OpenRouter.")
                false
            }
        }
    }
}
