package com.example.diceautobet.game

import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.ScreenArea
import com.example.diceautobet.models.GameResult
import com.example.diceautobet.services.AutoClickService
import com.example.diceautobet.utils.PreferencesManager
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ClickManager(
    private val prefsManager: PreferencesManager
) {
    
    companion object {
        private const val TAG = "ClickManager"
        private const val DEFAULT_CLICK_DELAY = 1000L // Увеличиваем базовую задержку
        private const val CLICK_TIMEOUT = 8000L // Увеличиваем таймаут
        private const val CLICK_STABILITY_DELAY = 300L // Дополнительная задержка для стабильности
    }
    
    suspend fun clickArea(area: ScreenArea): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Клик по области: ${area.name} (${area.rect})")
                
                // Дополнительная задержка перед кликом для стабильности
                delay(CLICK_STABILITY_DELAY)
                
                val clickResult = performClick(area.rect)
                if (clickResult) {
                    // Основная задержка после клика
                    delay(prefsManager.getClickDelay())
                    
                    // Дополнительная задержка для медленных приложений
                    delay(CLICK_STABILITY_DELAY)
                    
                    Log.d(TAG, "Клик выполнен успешно")
                    GameResult.Success(Unit)
                } else {
                    Log.e(TAG, "Клик не выполнен")
                    GameResult.Error("Не удалось выполнить клик")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при клике", e)
                GameResult.Error("Ошибка клика: ${e.message}", e)
            }
        }
    }
    
    /**
     * Быстрый клик специально для двойного режима с минимальными задержками
     */
    suspend fun clickAreaFast(area: ScreenArea, customDelay: Long = 50L): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Быстрый клик по области: ${area.name} (${area.rect})")
                
                val clickResult = performClick(area.rect)
                if (clickResult) {
                    // Используем кастомную быструю задержку
                    delay(customDelay)
                    
                    Log.d(TAG, "Быстрый клик выполнен успешно")
                    GameResult.Success(Unit)
                } else {
                    Log.e(TAG, "Быстрый клик не выполнен")
                    GameResult.Error("Не удалось выполнить быстрый клик")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при быстром клике", e)
                GameResult.Error("Ошибка быстрого клика: ${e.message}", e)
            }
        }
    }
    
    suspend fun performClick(areaType: com.example.diceautobet.models.AreaType): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Клик по типу области: $areaType")
                
                val area = prefsManager.loadAreaUniversal(areaType)
                    ?: return@withContext GameResult.Error("Область $areaType не настроена")
                
                return@withContext clickArea(area)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при клике по типу области", e)
                GameResult.Error("Ошибка клика: ${e.message}", e)
            }
        }
    }
    
    suspend fun clickCoordinates(x: Int, y: Int): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Клик по координатам: ($x, $y)")
                
                val clickResult = performClick(x, y)
                if (clickResult) {
                    delay(prefsManager.getClickDelay())
                    Log.d(TAG, "Клик по координатам выполнен успешно")
                    GameResult.Success(Unit)
                } else {
                    Log.e(TAG, "Клик по координатам не выполнен")
                    GameResult.Error("Не удалось выполнить клик")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выполнения клика по координатам", e)
                GameResult.Error("Ошибка клика: ${e.message}", e)
            }
        }
    }
    
    private suspend fun performClick(rect: Rect): Boolean {
        return suspendCoroutine { continuation ->
            try {
                val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(CLICK_TIMEOUT)
                    continuation.resume(false)
                }
                
                AutoClickService.performClick(rect) { success ->
                    timeoutJob.cancel()
                    continuation.resume(success)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при выполнении клика", e)
                continuation.resume(false)
            }
        }
    }
    
    private suspend fun performClick(x: Int, y: Int): Boolean {
        return suspendCoroutine { continuation ->
            try {
                val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(CLICK_TIMEOUT)
                    continuation.resume(false)
                }
                
                AutoClickService.performClick(x, y) { success ->
                    timeoutJob.cancel()
                    continuation.resume(success)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при выполнении клика по координатам", e)
                continuation.resume(false)
            }
        }
    }
    
    suspend fun clickSequence(areas: List<ScreenArea>, delays: List<Long> = emptyList()): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Выполнение последовательности кликов: ${areas.size} областей")
                
                areas.forEachIndexed { index, area ->
                    val clickResult = clickArea(area)
                    if (clickResult !is GameResult.Success) {
                        return@withContext GameResult.Error("Ошибка клика по области ${area.name}")
                    }
                    
                    // Добавляем задержку между кликами
                    val delay = delays.getOrNull(index) ?: DEFAULT_CLICK_DELAY
                    delay(delay)
                }
                
                Log.d(TAG, "Последовательность кликов выполнена успешно")
                GameResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выполнения последовательности кликов", e)
                GameResult.Error("Ошибка последовательности кликов: ${e.message}", e)
            }
        }
    }
    
    fun isAccessibilityServiceEnabled(): Boolean {
        return AutoClickService.getInstance() != null
    }
    
    suspend fun clickByText(vararg texts: String): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Клик по тексту: ${texts.joinToString(", ")}")
                
                val success = AutoClickService.clickByText(*texts)
                if (success) {
                    delay(prefsManager.getClickDelay())
                    Log.d(TAG, "Клик по тексту выполнен успешно")
                    GameResult.Success(Unit)
                } else {
                    Log.e(TAG, "Не удалось найти элемент с текстом")
                    GameResult.Error("Элемент с текстом не найден")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка клика по тексту", e)
                GameResult.Error("Ошибка клика по тексту: ${e.message}", e)
            }
        }
    }
    
    /**
     * Выполняет двойной клик по области
     */
    suspend fun doubleClickArea(area: ScreenArea): GameResult<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Двойной клик по области: ${area.name}")
                
                val firstClick = clickArea(area)
                if (firstClick !is GameResult.Success) {
                    return@withContext firstClick
                }
                
                delay(100) // Короткая задержка между кликами
                
                val secondClick = clickArea(area)
                if (secondClick !is GameResult.Success) {
                    return@withContext secondClick
                }
                
                Log.d(TAG, "Двойной клик выполнен успешно")
                GameResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка двойного клика", e)
                GameResult.Error("Ошибка двойного клика: ${e.message}", e)
            }
        }
    }
    
    /**
     * Выполняет клик с повторными попытками
     */
    suspend fun clickWithRetry(
        area: ScreenArea, 
        maxRetries: Int = 3, 
        retryDelay: Long = 1000L
    ): GameResult<Unit> {
        repeat(maxRetries) { attempt ->
            Log.d(TAG, "Попытка клика ${attempt + 1}/$maxRetries по области: ${area.name}")
            
            val result = clickArea(area)
            if (result is GameResult.Success) {
                return result
            }
            
            if (attempt < maxRetries - 1) {
                delay(retryDelay)
            }
        }
        
        return GameResult.Error("Не удалось выполнить клик после $maxRetries попыток")
    }
}
