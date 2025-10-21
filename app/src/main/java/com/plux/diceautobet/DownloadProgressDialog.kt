package com.example.diceautobet

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlin.math.max

class DownloadProgressDialog(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadId: Long,
    private val version: String
) {
    private var dialog: AlertDialog? = null
    private var monitoringJob: Job? = null
    
    // UI элементы
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvDownloaded: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTimeLeft: TextView
    private lateinit var tvTotalSize: TextView
    private lateinit var tvDownloadVersion: TextView
    private lateinit var btnCancel: MaterialButton
    
    // Для расчёта скорости
    private var lastBytes = 0L
    private var lastTime = 0L
    private val speedHistory = mutableListOf<Float>()
    private val maxSpeedSamples = 5
    
    var onCancelled: (() -> Unit)? = null
    
    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null)
        
        // Инициализация UI
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        tvDownloaded = view.findViewById(R.id.tvDownloaded)
        tvSpeed = view.findViewById(R.id.tvSpeed)
        tvTimeLeft = view.findViewById(R.id.tvTimeLeft)
        tvTotalSize = view.findViewById(R.id.tvTotalSize)
        tvDownloadVersion = view.findViewById(R.id.tvDownloadVersion)
        btnCancel = view.findViewById(R.id.btnCancelDownload)
        
        tvDownloadVersion.text = "Версия $version"
        
        // Обработчик отмены
        btnCancel.setOnClickListener {
            cancel()
        }
        
        // Создание диалога
        dialog = AlertDialog.Builder(context, R.style.DialogDark)
            .setView(view)
            .setCancelable(false)
            .create()
        
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.show()
        
        // Запуск мониторинга
        startMonitoring()
    }
    
    private fun startMonitoring() {
        lastTime = System.currentTimeMillis()
        
        monitoringJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateProgress()
                delay(500) // Обновление каждые 500мс
            }
        }
    }
    
    private fun updateProgress() {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor? = downloadManager.query(query)
        
        cursor?.use {
            if (it.moveToFirst()) {
                val bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val bytesTotal = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                
                if (bytesTotal > 0) {
                    // Прогресс
                    val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                    progressBar.progress = progress
                    tvProgress.text = "$progress%"
                    
                    // Загружено
                    tvDownloaded.text = formatBytes(bytesDownloaded)
                    
                    // Общий размер
                    tvTotalSize.text = "Размер: ${formatBytes(bytesTotal)}"
                    
                    // Скорость
                    calculateSpeed(bytesDownloaded, bytesTotal)
                }
                
                // Проверка статуса
                if (status == DownloadManager.STATUS_SUCCESSFUL || 
                    status == DownloadManager.STATUS_FAILED) {
                    dismiss()
                }
            }
        }
    }
    
    private fun calculateSpeed(bytesDownloaded: Long, bytesTotal: Long) {
        val currentTime = System.currentTimeMillis()
        val timeDelta = currentTime - lastTime
        
        if (timeDelta > 0 && lastBytes > 0) {
            val bytesDelta = bytesDownloaded - lastBytes
            val speedBps = (bytesDelta * 1000f) / timeDelta // bytes per second
            
            // Сглаживание скорости
            speedHistory.add(speedBps)
            if (speedHistory.size > maxSpeedSamples) {
                speedHistory.removeAt(0)
            }
            
            val avgSpeed = speedHistory.average().toFloat()
            tvSpeed.text = formatSpeed(avgSpeed)
            
            // Оставшееся время
            if (avgSpeed > 0) {
                val bytesLeft = bytesTotal - bytesDownloaded
                val secondsLeft = (bytesLeft / avgSpeed).toLong()
                tvTimeLeft.text = formatTime(secondsLeft)
            }
        }
        
        lastBytes = bytesDownloaded
        lastTime = currentTime
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    private fun formatSpeed(bytesPerSecond: Float): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.2f MB/s", bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> String.format("%.2f KB/s", bytesPerSecond / 1024)
            else -> String.format("%.0f B/s", bytesPerSecond)
        }
    }
    
    private fun formatTime(seconds: Long): String {
        return when {
            seconds >= 3600 -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                String.format("%d ч %d мин", hours, mins)
            }
            seconds >= 60 -> {
                val mins = seconds / 60
                val secs = seconds % 60
                String.format("%d:%02d", mins, secs)
            }
            else -> String.format("0:%02d", seconds)
        }
    }
    
    fun cancel() {
        downloadManager.remove(downloadId)
        onCancelled?.invoke()
        dismiss()
    }
    
    fun dismiss() {
        monitoringJob?.cancel()
        dialog?.dismiss()
        dialog = null
    }
}
