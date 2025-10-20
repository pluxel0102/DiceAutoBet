
package com.example.diceautobet.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import com.example.diceautobet.models.GameResult
import com.example.diceautobet.managers.MediaProjectionPermissionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

class ScreenCaptureManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ScreenCaptureManager"
    private const val MAX_IMAGE_TRIES = 8            // ~8 * 8ms ~= 64ms ожидание кадра (быстрее)
    private const val POLL_DELAY_MS = 8L             // ожидание между попытками (быстрее)
    }
    
    private val permissionManager = MediaProjectionPermissionManager.getInstance(context)
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false
    private var consecutiveAcquireFailures = 0
    private val captureMutex = Mutex()
    
    private val displayMetrics = context.resources.displayMetrics
    private val screenDensity = displayMetrics.densityDpi
    
    /**
     * Запускает захват экрана, автоматически используя сохраненное разрешение если доступно
     */
    fun startCapture(): GameResult<Unit> {
        return try {
            Log.d(TAG, "🚀 Запуск захвата экрана с автоматическим получением разрешения")
            
            // Пробуем получить сохраненное разрешение
            mediaProjection = permissionManager.getMediaProjection()
            
            if (mediaProjection == null) {
                Log.e(TAG, "❌ Не удалось получить MediaProjection - нет сохраненного разрешения")
                return GameResult.Error("Нет сохраненного разрешения на захват экрана. Получите разрешение в главном меню.")
            }
            
            Log.d(TAG, "✅ MediaProjection получен из сохраненного разрешения")
            
            setupImageReader()
            setupVirtualDisplay()
            
            isCapturing = true
            Log.d(TAG, "✅ Захват экрана запущен успешно")
            GameResult.Success(Unit)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException при запуске захвата экрана", e)
            // Если это ошибка Invalid media projection, очищаем сохраненные разрешения
            if (e.message?.contains("Invalid media projection") == true) {
                Log.w(TAG, "🧹 MediaProjection недействителен, очищаем сохраненные разрешения")
                permissionManager.clearPermission()
                isCapturing = false
                return GameResult.Error("Разрешение на захват экрана устарело. Требуется новое разрешение.")
            }
            GameResult.Error("Ошибка безопасности при захвате экрана: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запуска захвата экрана", e)
            GameResult.Error("Ошибка запуска захвата: ${e.message}", e)
        }
    }
    
    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            3 // немного увеличим буфер кадров
        )
        
        Log.d(TAG, "ImageReader настроен: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
    }
    
    private fun setupVirtualDisplay() {
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            
            Log.d(TAG, "VirtualDisplay настроен")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException при создании VirtualDisplay", e)
            if (e.message?.contains("Invalid media projection") == true) {
                Log.w(TAG, "🧹 MediaProjection недействителен при создании VirtualDisplay, очищаем разрешения")
                permissionManager.clearPermission()
                isCapturing = false
            }
            throw e // Пробрасываем исключение дальше
        }
    }
    
    suspend fun captureScreen(): GameResult<Bitmap> {
        if (!isCapturing || imageReader == null) {
            return GameResult.Error("Захват экрана не инициализирован")
        }

        return withContext(Dispatchers.IO) {
            try {
                return@withContext captureMutex.withLock {
                    // Проверяем валидность MediaProjection перед использованием
                    if (mediaProjection == null) {
                        Log.w(TAG, "⚠️ MediaProjection равен null, пытаемся восстановить")
                        
                        // Пытаемся восстановить из сохраненного разрешения
                        mediaProjection = permissionManager.getMediaProjection()
                        if (mediaProjection == null) {
                            Log.e(TAG, "❌ MediaProjection недоступен и не может быть восстановлен")
                            return@withLock GameResult.Error("MediaProjection недоступен")
                        }
                        Log.d(TAG, "✅ MediaProjection успешно восстановлен")
                    }                    // Делаем несколько быстрых попыток дождаться кадра
                    var image: Image? = null
                    var tries = 0
                    while (tries < MAX_IMAGE_TRIES && image == null) {
                        image = imageReader?.acquireLatestImage()
                        if (image == null) {
                            delay(POLL_DELAY_MS)
                        }
                        tries++
                    }
                    if (image == null) {
                        consecutiveAcquireFailures++
                        // Если много неудач подряд — переинициализируем virtual display + reader
                        if (consecutiveAcquireFailures >= 20) { // Увеличиваем порог с 12 до 20
                            Log.w(TAG, "acquireLatestImage() возвращает null. Переинициализируем VirtualDisplay/ImageReader...")
                            try {
                                // Освобождаем старые ресурсы
                                virtualDisplay?.release()
                                imageReader?.close()
                                
                                // Проверяем, не стал ли MediaProjection недействительным
                                if (mediaProjection == null) {
                                    Log.e(TAG, "❌ MediaProjection стал недействительным")
                                    
                                    // Пытаемся восстановить из сохраненного разрешения
                                    mediaProjection = permissionManager.getMediaProjection()
                                    if (mediaProjection == null) {
                                        Log.e(TAG, "❌ Не удалось восстановить MediaProjection")
                                        return@withLock GameResult.Error("MediaProjection недействителен - требуется новое разрешение")
                                    }
                                    Log.d(TAG, "✅ MediaProjection восстановлен из сохраненного разрешения")
                                }
                                
                                // Пересоздаем ImageReader и VirtualDisplay
                                setupImageReader()
                                setupVirtualDisplay()
                                
                                // Ещё одна быстрая попытка после реинициализации
                                var retryImage: Image? = null
                                var retryTries = 0
                                while (retryTries < MAX_IMAGE_TRIES && retryImage == null) {
                                    retryImage = imageReader?.acquireLatestImage()
                                    if (retryImage == null) delay(POLL_DELAY_MS)
                                    retryTries++
                                }
                                if (retryImage != null) {
                                    image = retryImage
                                    consecutiveAcquireFailures = 0 // Сбрасываем счетчик при успехе
                                }
                            } catch (re: Exception) {
                                Log.e(TAG, "Ошибка переинициализации VirtualDisplay/ImageReader", re)
                                // Если получили SecurityException, значит MediaProjection недействителен
                                if (re is SecurityException && re.message?.contains("Invalid media projection") == true) {
                                    Log.e(TAG, "❌ MediaProjection стал недействительным - требуется восстановление")
                                    // НЕ ОЧИЩАЕМ разрешения! Позволяем вышестоящему коду попробовать восстановить
                                    isCapturing = false
                                    return@withLock GameResult.Error("MediaProjection недействителен - требуется новое разрешение")
                                }
                            }
                        }
                        if (image == null) {
                            return@withLock GameResult.Error("Не удалось получить изображение")
                        }
                    } else {
                        consecutiveAcquireFailures = 0
                    }
                
                    val imgW = image.width
                    val imgH = image.height
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * imgW
                
                    val bitmap = Bitmap.createBitmap(
                        imgW + rowPadding / pixelStride,
                        imgH,
                        Bitmap.Config.ARGB_8888
                    )
                    
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()
                    
                    // Обрезаем bitmap если есть padding
                    val finalBitmap = if (rowPadding == 0) bitmap else {
                        val cropped = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            imgW,
                            imgH
                        )
                        bitmap.recycle()
                        cropped
                    }
                    
                    Log.d(TAG, "Скриншот сделан: ${finalBitmap.width}x${finalBitmap.height}")
                    GameResult.Success(finalBitmap)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка захвата экрана", e)
                GameResult.Error("Ошибка захвата: ${e.message}", e)
            }
        }
    }
    
    fun stopCapture() {
        Log.d(TAG, "Остановка захвата экрана")
        
        try {
            isCapturing = false
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            
            Log.d(TAG, "Захват экрана остановлен")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки захвата", e)
        }
    }
    
    fun isCapturing(): Boolean = isCapturing && mediaProjection != null
    
    /**
     * Проверяет статус MediaProjection и пытается обновить его при необходимости
     */
    fun validateMediaProjection(): Boolean {
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection равен null")
            return false
        }
        return true
    }
    
    /**
     * Обновляет MediaProjection новыми данными разрешения
     */
    fun updateMediaProjection(resultCode: Int, data: android.content.Intent): GameResult<Unit> {
        return try {
            Log.d(TAG, "Обновление MediaProjection")
            
            // Освобождаем старые ресурсы
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            // Создаем новый MediaProjection
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection == null) {
                isCapturing = false
                return GameResult.Error("Не удалось обновить MediaProjection")
            }
            
            // Пересоздаем компоненты
            setupImageReader()
            setupVirtualDisplay()
            
            isCapturing = true
            consecutiveAcquireFailures = 0
            
            Log.d(TAG, "MediaProjection успешно обновлен")
            GameResult.Success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления MediaProjection", e)
            isCapturing = false
            GameResult.Error("Ошибка обновления MediaProjection: ${e.message}", e)
        }
    }
    
    fun destroy() {
        Log.d(TAG, "Уничтожение ScreenCaptureManager")
        stopCapture()
    }
}
