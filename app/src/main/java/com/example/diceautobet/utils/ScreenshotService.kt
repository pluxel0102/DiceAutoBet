package com.example.diceautobet.utils

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import java.nio.ByteBuffer

/**
 * Сервис для создания скриншотов экрана
 */
class ScreenshotService {
    
    companion object {
        private const val TAG = "ScreenshotService"
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    /**
     * Инициализация сервиса с MediaProjection
     */
    fun initialize(mediaProjection: MediaProjection, displayMetrics: DisplayMetrics) {
        this.mediaProjection = mediaProjection
        
        // Создаем ImageReader для получения скриншотов
        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            1
        )
        
        // Создаем VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenshotService",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        
        Log.d(TAG, "ScreenshotService инициализирован")
    }
    
    /**
     * Создание скриншота
     */
    fun takeScreenshot(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "Не удалось получить изображение")
                return null
            }
            
            val bitmap = convertImageToBitmap(image)
            image.close()
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при создании скриншота", e)
            null
        }
    }
    
    /**
     * Преобразование Image в Bitmap
     */
    private fun convertImageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Обрезаем лишние пиксели если есть padding
            if (rowPadding == 0) {
                bitmap
            } else {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                croppedBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при преобразовании Image в Bitmap", e)
            null
        }
    }
    
    /**
     * Освобождение ресурсов
     */
    fun release() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            
            Log.d(TAG, "ScreenshotService освобожден")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при освобождении ресурсов", e)
        }
    }
}
