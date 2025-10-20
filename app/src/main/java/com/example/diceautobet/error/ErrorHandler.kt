package com.example.diceautobet.error

import android.util.Log
import com.example.diceautobet.models.GameResult

/**
 * Централизованный обработчик ошибок
 */
object ErrorHandler {
    private const val TAG = "ErrorHandler"
    
    /**
     * Обрабатывает ошибку и возвращает стандартизированный результат
     */
    fun handleError(throwable: Throwable): GameResult.Error {
        Log.e(TAG, "Обработка ошибки", throwable)
        
        val errorMessage = when (throwable) {
            is SecurityException -> "Нет необходимых разрешений"
            is IllegalStateException -> "Неверное состояние приложения"
            is IllegalArgumentException -> "Неверные параметры"
            is NullPointerException -> "Обращение к null объекту"
            is OutOfMemoryError -> "Недостаточно памяти"
            is InterruptedException -> "Операция была прервана"
            else -> throwable.message ?: "Неизвестная ошибка"
        }
        
        return GameResult.Error(errorMessage, throwable)
    }
    
    /**
     * Обрабатывает ошибку с кастомным сообщением
     */
    fun handleError(message: String, throwable: Throwable? = null): GameResult.Error {
        Log.e(TAG, message, throwable)
        return GameResult.Error(message, throwable)
    }
    
    /**
     * Обрабатывает ошибку распознавания результатов
     */
    fun handleRecognitionError(context: String, throwable: Throwable? = null): GameResult.Error {
        val message = "Ошибка распознавания результатов в $context"
        Log.e(TAG, message, throwable)
        
        // Дополнительная диагностическая информация
        Log.e(TAG, "Диагностика: $context")
        Log.e(TAG, "Система: ${android.os.Build.MODEL}")
        Log.e(TAG, "Android: ${android.os.Build.VERSION.RELEASE}")
        
        return GameResult.Error(message, throwable)
    }
    
    /**
     * Обрабатывает ошибку захвата экрана
     */
    fun handleScreenCaptureError(throwable: Throwable? = null): GameResult.Error {
        val message = "Ошибка захвата экрана - возможно, отсутствуют разрешения"
        Log.e(TAG, message, throwable)
        
        // Диагностическая информация для захвата экрана
        Log.e(TAG, "Рекомендации по устранению:")
        Log.e(TAG, "1. Проверьте разрешение на захват экрана")
        Log.e(TAG, "2. Перезапустите приложение")
        Log.e(TAG, "3. Проверьте настройки системы")
        Log.e(TAG, "4. Убедитесь, что другие приложения не блокируют захват")
        
        return GameResult.Error(message, throwable)
    }
    
    /**
     * Обрабатывает ошибку MediaProjection
     */
    fun handleMediaProjectionError(context: String, throwable: Throwable? = null): GameResult.Error {
        val message = "Ошибка MediaProjection в $context - необходимо переустановить разрешение"
        Log.e(TAG, message, throwable)
        
        Log.e(TAG, "Действия для устранения:")
        Log.e(TAG, "1. Остановить приложение")
        Log.e(TAG, "2. Запустить заново")
        Log.e(TAG, "3. Предоставить разрешение на захват экрана")
        
        return GameResult.Error(message, throwable)
    }
    
    /**
     * Обрабатывает ошибку ImageReader
     */
    fun handleImageReaderError(context: String, throwable: Throwable? = null): GameResult.Error {
        val message = "Ошибка ImageReader в $context - проблема с получением изображения"
        Log.e(TAG, message, throwable)
        
        Log.e(TAG, "Диагностика ImageReader:")
        Log.e(TAG, "1. VirtualDisplay может быть не готов")
        Log.e(TAG, "2. Нет доступных изображений в очереди")
        Log.e(TAG, "3. Возможна проблема с разрешением экрана")
        Log.e(TAG, "4. Другое приложение может блокировать захват")
        
        return GameResult.Error(message, throwable)
    }
    
    /**
     * Обрабатывает ошибку отсутствия области
     */
    fun handleMissingAreaError(areaType: String): GameResult.Error {
        val message = "Не настроена область: $areaType - необходимо настроить в интерфейсе"
        Log.e(TAG, message)
        return GameResult.Error(message, null)
    }
    
    /**
     * Логирует предупреждение
     */
    fun logWarning(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }
    
    /**
     * Логирует информацию
     */
    fun logInfo(message: String) {
        Log.i(TAG, message)
    }
}
