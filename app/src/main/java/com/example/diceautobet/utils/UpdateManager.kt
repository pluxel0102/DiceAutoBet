package com.example.diceautobet.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val latestVersion: String,
    val versionCode: Int,
    val downloadUrl: String,
    val changelog: String,
    val mandatory: Boolean,
    val minSupportedVersion: String
)

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "UpdateManager"
        // URL для приватного репозитория GitHub
        private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/pluxel0102/DiceAutoBet/main/update.json"
        // GitHub Personal Access Token для доступа к приватному репозиторию
        private const val GITHUB_TOKEN = "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"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_SKIP_VERSION = "skip_version"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val CHECK_INTERVAL_MS = 60 * 1000L // 1 минута
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    /**
     * Проверить наличие обновлений
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Проверяем обновления...")
            FileLogger.i(TAG, "🔍 Проверка обновлений: $UPDATE_JSON_URL")
            
            // Добавляем timestamp для обхода кэша GitHub
            val urlWithTimestamp = "$UPDATE_JSON_URL?t=${System.currentTimeMillis()}"
            val url = URL(urlWithTimestamp)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            // Токен не нужен для update.json в публичном репозитории

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val updateInfo = UpdateInfo(
                    latestVersion = json.getString("latestVersion"),
                    versionCode = json.getInt("versionCode"),
                    downloadUrl = json.getString("downloadUrl"),
                    changelog = json.getString("changelog"),
                    mandatory = json.getBoolean("mandatory"),
                    minSupportedVersion = json.getString("minSupportedVersion")
                )

                Log.d(TAG, "✅ Информация об обновлении получена: v${updateInfo.latestVersion}")
                FileLogger.i(TAG, "✅ Доступная версия: ${updateInfo.latestVersion} (код: ${updateInfo.versionCode})")
                
                // Сохраняем время последней проверки
                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                
                updateInfo
            } else {
                Log.e(TAG, "❌ Ошибка проверки обновлений: HTTP ${connection.responseCode}")
                FileLogger.e(TAG, "❌ Ошибка проверки обновлений: HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки обновлений", e)
            FileLogger.e(TAG, "❌ Ошибка проверки обновлений: ${e.message}")
            null
        }
    }

    /**
     * Проверить, нужно ли обновление
     */
    fun isUpdateAvailable(updateInfo: UpdateInfo): Boolean {
        val currentVersionCode = getCurrentVersionCode()
        val isAvailable = updateInfo.versionCode > currentVersionCode
        
        Log.d(TAG, "📊 Текущая версия: $currentVersionCode, доступная: ${updateInfo.versionCode}")
        FileLogger.i(TAG, "📊 Проверка версии: текущая=$currentVersionCode, доступная=${updateInfo.versionCode}, нужно обновление=$isAvailable")
        
        return isAvailable
    }

    /**
     * Проверить, пропустил ли пользователь эту версию
     */
    fun isVersionSkipped(updateInfo: UpdateInfo): Boolean {
        val skippedVersion = prefs.getString(KEY_SKIP_VERSION, "")
        return skippedVersion == updateInfo.latestVersion && !updateInfo.mandatory
    }

    /**
     * Проверить, нужна ли проверка обновлений (прошло ли достаточно времени)
     */
    fun shouldCheckForUpdates(): Boolean {
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastCheckTime) > CHECK_INTERVAL_MS
    }

    /**
     * Пропустить версию
     */
    fun skipVersion(updateInfo: UpdateInfo) {
        if (!updateInfo.mandatory) {
            prefs.edit().putString(KEY_SKIP_VERSION, updateInfo.latestVersion).apply()
            Log.d(TAG, "⏭️ Версия ${updateInfo.latestVersion} пропущена")
            FileLogger.i(TAG, "⏭️ Пользователь пропустил версию ${updateInfo.latestVersion}")
        }
    }

    /**
     * Скачать и установить обновление
     */
    fun downloadAndInstall(updateInfo: UpdateInfo) {
        try {
            Log.d(TAG, "📥 Начинаем загрузку обновления v${updateInfo.latestVersion}")
            FileLogger.i(TAG, "📥 Загрузка обновления: ${updateInfo.downloadUrl}")
            
            val fileName = "DiceAutoBet_v${updateInfo.latestVersion}.apk"
            val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
                .setTitle("Обновление DiceAutoBet")
                .setDescription("Загрузка версии ${updateInfo.latestVersion}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                // Для публичного репозитория используем правильный Accept заголовок
                .addRequestHeader("Accept", "application/octet-stream")
                .addRequestHeader("User-Agent", "DiceAutoBet-UpdateManager")

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            // Регистрируем receiver для автоматической установки
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Log.d(TAG, "✅ Загрузка завершена, запускаем установку")
                        FileLogger.i(TAG, "✅ Загрузка завершена: $fileName")
                        
                        // Проверяем статус загрузки
                        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)
                            
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                // Даем системе время на финализацию файла
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    installApk(fileName, id)
                                }, 500) // Задержка 500мс для финализации
                            } else {
                                // Получаем причину ошибки
                                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                
                                val errorMessage = when (status) {
                                    DownloadManager.STATUS_FAILED -> {
                                        when (reason) {
                                            DownloadManager.ERROR_CANNOT_RESUME -> "Загрузка прервана"
                                            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Нет доступа к хранилищу"
                                            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Файл уже существует"
                                            DownloadManager.ERROR_FILE_ERROR -> "Ошибка файла"
                                            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Ошибка данных HTTP"
                                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Недостаточно места"
                                            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Слишком много перенаправлений"
                                            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Файл не найден на сервере (404)"
                                            404 -> "Обновление еще не опубликовано на GitHub"
                                            else -> when {
                                                reason >= 400 && reason < 500 -> "HTTP ошибка клиента: $reason"
                                                reason >= 500 && reason < 600 -> "HTTP ошибка сервера: $reason"
                                                else -> "Неизвестная ошибка ($reason)"
                                            }
                                        }
                                    }
                                    else -> "Статус загрузки: $status"
                                }
                                
                                Log.e(TAG, "❌ Загрузка завершилась с ошибкой: status=$status, reason=$reason")
                                Log.e(TAG, "❌ $errorMessage")
                                FileLogger.e(TAG, "Ошибка загрузки: $errorMessage")
                                Toast.makeText(context, "❌ Ошибка загрузки: $errorMessage", Toast.LENGTH_LONG).show()
                            }
                        }
                        cursor.close()
                        
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            Log.w(TAG, "Receiver уже отменён")
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    downloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    downloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

            Toast.makeText(context, "📥 Загрузка обновления...", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка загрузки обновления", e)
            FileLogger.e(TAG, "❌ Ошибка загрузки: ${e.message}")
            Toast.makeText(context, "❌ Ошибка загрузки обновления", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Установить APK
     */
    private fun installApk(fileName: String, dlId: Long) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            Log.d(TAG, "🔍 Проверяем файл: ${file.absolutePath}")
            Log.d(TAG, "🔍 Папка Downloads: ${downloadsDir.absolutePath}")
            Log.d(TAG, "🔍 Папка существует: ${downloadsDir.exists()}")
            Log.d(TAG, "🔍 Файл существует: ${file.exists()}")
            
            if (downloadsDir.exists()) {
                val files = downloadsDir.listFiles()
                Log.d(TAG, "🔍 Файлы в Downloads (первые 10):")
                files?.take(10)?.forEach {
                    Log.d(TAG, "   - ${it.name} (${it.length()} bytes)")
                }
            }

            if (!file.exists()) {
                Log.e(TAG, "❌ Файл обновления не найден: ${file.absolutePath}")
                FileLogger.e(TAG, "❌ Файл не найден: ${file.absolutePath}")
                Toast.makeText(context, "❌ Файл обновления не найден. Попробуйте еще раз.", Toast.LENGTH_LONG).show()
                return
            }
            
            val fileSize = file.length()
            Log.d(TAG, "✅ Файл найден! Размер: $fileSize bytes (${fileSize / 1024 / 1024} MB)")
            
            // Вычисляем MD5 хеш для диагностики
            try {
                val md5 = MessageDigest.getInstance("MD5")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        md5.update(buffer, 0, bytesRead)
                    }
                }
                val md5Hash = md5.digest().joinToString("") { "%02x".format(it) }
                Log.d(TAG, "🔐 MD5 хеш файла: $md5Hash")
                FileLogger.i(TAG, "MD5: $md5Hash")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Не удалось вычислить MD5: ${e.message}")
            }
            
            // Проверка на минимальный размер APK (должен быть > 1MB)
            if (fileSize < 1024 * 1024) {
                Log.e(TAG, "❌ Файл слишком маленький ($fileSize bytes), возможно загружен HTML вместо APK")
                FileLogger.e(TAG, "❌ Подозрительный размер файла: $fileSize bytes")
                Toast.makeText(context, "❌ Ошибка загрузки файла. Попробуйте еще раз.", Toast.LENGTH_LONG).show()
                file.delete() // Удаляем битый файл
                return
            }
            
            // Проверяем что это действительно APK (начинается с "PK")
            try {
                file.inputStream().use { stream ->
                    val header = ByteArray(2)
                    stream.read(header)
                    val isPkZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                    Log.d(TAG, "🔍 Проверка заголовка файла: ${if (isPkZip) "✅ ZIP/APK" else "❌ НЕ APK"}")
                    
                    if (!isPkZip) {
                        Log.e(TAG, "❌ Файл не является APK (заголовок: ${header[0].toString(16)}, ${header[1].toString(16)})")
                        FileLogger.e(TAG, "❌ Неверный формат файла")
                        
                        // Читаем первые 100 байт для диагностики
                        val preview = ByteArray(100)
                        file.inputStream().use { it.read(preview) }
                        val previewText = String(preview).take(100)
                        Log.e(TAG, "Содержимое файла: $previewText")
                        FileLogger.e(TAG, "Содержимое: $previewText")
                        
                        Toast.makeText(context, "❌ Загружен неверный файл. Попробуйте еще раз.", Toast.LENGTH_LONG).show()
                        file.delete()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Не удалось проверить файл (возможно нет разрешения): ${e.message}")
                Log.w(TAG, "⚠️ Продолжаем установку без проверки...")
                FileLogger.w(TAG, "Пропуск проверки файла: ${e.message}")
            }
            
            Log.d(TAG, "📦 Запускаем установку: ${file.absolutePath}")
            FileLogger.i(TAG, "📦 Запуск установки: ${file.name}")
            
            // Получаем информацию о текущей установленной версии
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                Log.d(TAG, "📱 Установленная версия: ${packageInfo.versionName} (${packageInfo.versionCode})")
                FileLogger.i(TAG, "Текущая версия: ${packageInfo.versionName}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Не удалось получить info: ${e.message}")
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            // Для файлов из Downloads используем content:// URI через DownloadManager
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Получаем content:// URI от DownloadManager
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.getUriForDownloadedFile(dlId) ?: run {
                    // Если не удалось получить URI от DownloadManager, используем FileProvider
                    Log.w(TAG, "⚠️ Не удалось получить URI от DownloadManager, используем FileProvider")
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
            } else {
                Uri.fromFile(file)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка установки APK", e)
            FileLogger.e(TAG, "❌ Ошибка установки: ${e.message}")
            Toast.makeText(context, "❌ Ошибка установки обновления", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Получить текущую версию приложения
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка получения версии", e)
            0
        }
    }

    /**
     * Получить текущее имя версии
     */
    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка получения имени версии", e)
            "unknown"
        }
    }

    /**
     * Очистить данные обновлений (для отладки)
     */
    fun clearUpdateData() {
        prefs.edit().clear().apply()
        Log.d(TAG, "🗑️ Данные обновлений очищены")
    }
}
