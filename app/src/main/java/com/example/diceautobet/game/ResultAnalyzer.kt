package com.example.diceautobet.game

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.RoundResult
import com.example.diceautobet.models.ScreenArea
import com.example.diceautobet.models.GameResult
import com.example.diceautobet.opencv.DotCounter
import com.example.diceautobet.utils.PreferencesManager
import kotlinx.coroutines.*

class ResultAnalyzer(
    private val prefsManager: PreferencesManager,
    private val screenCaptureManager: ScreenCaptureManager
) {
    
    companion object {
        private const val TAG = "ResultAnalyzer"
        private const val MIN_CONFIDENCE = 0.3f
        private const val MAX_VALIDATION_ATTEMPTS = 3
    }
    
    private var lastResult: RoundResult? = null
    private var resultHistory = mutableListOf<RoundResult>()
    private val maxHistorySize = 10
    
    suspend fun analyzeResult(): GameResult<RoundResult> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Начинаем анализ результата")
                
                // Получаем скриншот
                val screenshotResult = screenCaptureManager.captureScreen()
                if (screenshotResult !is GameResult.Success) {
                    return@withContext GameResult.Error("Не удалось сделать скриншот")
                }
                
                val screenshot = screenshotResult.data
                
                // Получаем область результата: предпочитаем BET_RESULT, иначе DICE_AREA
                val resultArea = prefsManager.loadAreaUniversal(AreaType.BET_RESULT)
                    ?: prefsManager.loadAreaUniversal(AreaType.DICE_AREA)
                    ?: return@withContext GameResult.Error("Область результата (BET_RESULT/DICE_AREA) не настроена")
                
                // Обрезаем изображение до области результата
                val diceBitmap = cropBitmapToArea(screenshot, resultArea.rect)
                
                // Анализируем кубики
                val dotResult = DotCounter.count(diceBitmap)
                val roundResult = RoundResult.fromDotResult(dotResult)
                
                Log.d(TAG, "Результат анализа: $roundResult")
                
                // Валидируем результат
                val validationResult = validateResult(roundResult)
                if (validationResult !is GameResult.Success) {
                    return@withContext validationResult
                }
                
                // Сохраняем в историю
                addToHistory(roundResult)
                lastResult = roundResult
                
                GameResult.Success(roundResult)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка анализа результата", e)
                GameResult.Error("Ошибка анализа: ${e.message}", e)
            }
        }
    }
    
    private fun cropBitmapToArea(bitmap: Bitmap, area: Rect): Bitmap {
        // Компенсация статус-бара
        val insets = com.example.diceautobet.utils.CoordinateUtils.getSystemInsets(prefsManager.appContext)
        val adjustedTop = area.top + insets.statusBarHeight
        val adjustedBottom = area.bottom + insets.statusBarHeight
        val left = maxOf(0, area.left)
        val top = maxOf(0, adjustedTop)
        val right = minOf(bitmap.width, area.right)
        val bottom = minOf(bitmap.height, adjustedBottom)
        
        val width = right - left
        val height = bottom - top
        
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Некорректная область: $area, размер bitmap: ${bitmap.width}x${bitmap.height}")
            return bitmap
        }
        
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
    
    private fun validateResult(result: RoundResult): GameResult<RoundResult> {
        // Базовая валидация
        if (!result.isValid) {
            Log.d(TAG, "Результат не прошел базовую валидацию")
            return GameResult.Error("Результат не прошел базовую валидацию")
        }
        
        // Проверка уверенности
        if (result.confidence < MIN_CONFIDENCE) {
            Log.d(TAG, "Слишком низкая уверенность: ${result.confidence}")
            return GameResult.Error("Слишком низкая уверенность в результате")
        }
        
        // Проверка логичности
        val totalDots = result.redDots + result.orangeDots
        if (totalDots < 2 || totalDots > 12) {
            Log.d(TAG, "Нелогичное количество точек: $totalDots")
            return GameResult.Error("Нелогичное количество точек")
        }
        
        // Проверка на повторяющиеся результаты
        if (isResultSuspicious(result)) {
            Log.d(TAG, "Подозрительный результат (слишком много повторений)")
            return GameResult.Error("Подозрительный результат")
        }
        
        // Проверка на резкие изменения
        lastResult?.let { last ->
            if (isResultTooSudden(last, result)) {
                Log.d(TAG, "Слишком резкое изменение результата")
                return GameResult.Error("Слишком резкое изменение")
            }
        }
        
        return GameResult.Success(result)
    }
    
    private fun isResultSuspicious(result: RoundResult): Boolean {
        val similarResults = resultHistory.count {
            it.redDots == result.redDots && 
            it.orangeDots == result.orangeDots &&
            it.winner == result.winner
        }
        
        return similarResults > 5 // Более 5 одинаковых результатов подозрительно
    }
    
    private fun isResultTooSudden(last: RoundResult, current: RoundResult): Boolean {
        val redDiff = kotlin.math.abs(current.redDots - last.redDots)
        val orangeDiff = kotlin.math.abs(current.orangeDots - last.orangeDots)
        
        // Если изменение больше 4 точек на любом кубике - подозрительно
        return redDiff > 4 || orangeDiff > 4
    }
    
    private fun addToHistory(result: RoundResult) {
        resultHistory.add(result)
        
        // Ограничиваем размер истории
        if (resultHistory.size > maxHistorySize) {
            resultHistory.removeAt(0)
        }
        
        Log.d(TAG, "Результат добавлен в историю. Размер истории: ${resultHistory.size}")
    }
    
    fun getResultHistory(): List<RoundResult> = resultHistory.toList()
    
    fun getLastResult(): RoundResult? = lastResult
    
    fun clearHistory() {
        Log.d(TAG, "Очистка истории результатов")
        resultHistory.clear()
        lastResult = null
    }
    
    fun getStatistics(): AnalysisStatistics {
        val totalResults = resultHistory.size
        val avgConfidence = if (totalResults > 0) {
            resultHistory.sumOf { it.confidence.toDouble() } / totalResults
        } else 0.0
        
        val redWins = resultHistory.count { it.winner == com.example.diceautobet.models.BetChoice.RED }
        val orangeWins = resultHistory.count { it.winner == com.example.diceautobet.models.BetChoice.ORANGE }
        val draws = resultHistory.count { it.isDraw }
        
        return AnalysisStatistics(
            totalResults = totalResults,
            averageConfidence = avgConfidence.toFloat(),
            redWins = redWins,
            orangeWins = orangeWins,
            draws = draws
        )
    }
}

data class AnalysisStatistics(
    val totalResults: Int,
    val averageConfidence: Float,
    val redWins: Int,
    val orangeWins: Int,
    val draws: Int
) {
    val redWinRate: Float = if (totalResults > 0) redWins.toFloat() / totalResults else 0f
    val orangeWinRate: Float = if (totalResults > 0) orangeWins.toFloat() / totalResults else 0f
    val drawRate: Float = if (totalResults > 0) draws.toFloat() / totalResults else 0f
}
