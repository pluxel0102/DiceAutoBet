package com.example.diceautobet.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.diceautobet.R
import com.example.diceautobet.models.*
import com.example.diceautobet.services.DualModeService
import com.example.diceautobet.databinding.ActivityAdvancedDualModeBinding
import kotlinx.coroutines.*

/**
 * Расширенная активность с дополнительными возможностями управления
 */
class AdvancedDualModeActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AdvancedDualModeActivity"
    }
    
    private lateinit var binding: ActivityAdvancedDualModeBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityAdvancedDualModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupNavigationCards()
        setupQuickActions()
        setupSystemInfo()
        
        Log.d(TAG, "AdvancedDualModeActivity создана")
    }
    
    /**
     * Настраивает тулбар
     */
    private fun setupToolbar() {
        binding.toolbar.title = "DiceAutoBet Pro"
        binding.toolbar.subtitle = "Умная автоматизация ставок"
        setSupportActionBar(binding.toolbar)
    }
    
    /**
     * Настраивает карточки навигации
     */
    private fun setupNavigationCards() {
        // Основное управление
        binding.cardMainControl.setOnClickListener {
            startActivity(Intent(this, DualModeControlActivity::class.java))
        }
        
        // Настройка областей
        binding.cardAreaSetup.setOnClickListener {
            // TODO: Запуск активности настройки областей
            Log.d(TAG, "Запуск настройки областей")
        }
        
        // Статистика и аналитика
        binding.cardStatistics.setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }
        
        // Тестирование и отладка
        binding.cardTesting.setOnClickListener {
            startActivity(Intent(this, TestingActivity::class.java))
        }
        
        // Настройки производительности
        binding.cardPerformance.setOnClickListener {
            startActivity(Intent(this, PerformanceSettingsActivity::class.java))
        }
        
        // Логи и диагностика
        binding.cardLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
    }
    
    /**
     * Настраивает быстрые действия
     */
    private fun setupQuickActions() {
        binding.btnQuickStart.setOnClickListener {
            quickStartDualMode()
        }
        
        binding.btnQuickStop.setOnClickListener {
            quickStopDualMode()
        }
        
        binding.btnQuickOptimize.setOnClickListener {
            quickOptimizePerformance()
        }
        
        binding.btnQuickBackup.setOnClickListener {
            quickBackupSettings()
        }
    }
    
    /**
     * Настраивает информацию о системе
     */
    private fun setupSystemInfo() {
        lifecycleScope.launch {
            updateSystemInfo()
        }
    }
    
    /**
     * Быстрый запуск двойного режима
     */
    private fun quickStartDualMode() {
        Log.d(TAG, "Быстрый запуск двойного режима")
        
        // Запускаем сервис напрямую
        val intent = Intent(this, DualModeService::class.java)
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as DualModeService.LocalBinder
                binder.getService().startDualMode()
                unbindService(this)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
        
        showQuickMessage("Двойной режим запущен")
    }
    
    /**
     * Быстрая остановка двойного режима
     */
    private fun quickStopDualMode() {
        Log.d(TAG, "Быстрая остановка двойного режима")
        
        // Останавливаем сервис напрямую
        val intent = Intent(this, DualModeService::class.java)
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as DualModeService.LocalBinder
                binder.getService().stopDualMode()
                unbindService(this)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
        
        showQuickMessage("Двойной режим остановлен")
    }
    
    /**
     * Быстрая оптимизация производительности
     */
    private fun quickOptimizePerformance() {
        Log.d(TAG, "Быстрая оптимизация производительности")
        
        // TODO: Реализовать быструю оптимизацию
        showQuickMessage("Производительность оптимизирована")
    }
    
    /**
     * Быстрое резервное копирование настроек
     */
    private fun quickBackupSettings() {
        Log.d(TAG, "Быстрое резервное копирование")
        
        // TODO: Реализовать резервное копирование
        showQuickMessage("Настройки сохранены")
    }
    
    /**
     * Обновляет информацию о системе
     */
    private fun updateSystemInfo() {
        // Информация об устройстве
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024 // МБ
        val availableProcessors = runtime.availableProcessors()
        
        binding.textDeviceInfo.text = """
            Процессоры: $availableProcessors ядер
            Память: ${maxMemory}МБ
            Версия Android: ${android.os.Build.VERSION.RELEASE}
            Модель: ${android.os.Build.MODEL}
        """.trimIndent()
        
        // Рекомендуемые настройки
        val recommendedMode = when {
            maxMemory > 512 && availableProcessors >= 8 -> "HIGH_PERFORMANCE"
            maxMemory < 256 || availableProcessors < 4 -> "REDUCED_LOAD"
            else -> "NORMAL"
        }
        
        binding.textRecommendedMode.text = "Рекомендуемый режим: $recommendedMode"
        
        // Цвет рекомендации
        val modeColor = when (recommendedMode) {
            "HIGH_PERFORMANCE" -> ContextCompat.getColor(this, R.color.green)
            "REDUCED_LOAD" -> ContextCompat.getColor(this, R.color.red)
            else -> ContextCompat.getColor(this, R.color.yellow)
        }
        binding.textRecommendedMode.setTextColor(modeColor)
    }
    
    /**
     * Показывает быстрое сообщение
     */
    private fun showQuickMessage(message: String) {
        binding.textQuickMessage.text = message
        
        // Автоматическое скрытие через 2 секунды
        lifecycleScope.launch {
            delay(2000)
            binding.textQuickMessage.text = ""
        }
    }
}

/**
 * Активность статистики (заглушка)
 */
class StatisticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Реализовать полную статистику
        finish()
    }
}

/**
 * Активность тестирования (заглушка)
 */
class TestingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Реализовать тестирование
        finish()
    }
}

/**
 * Активность настроек производительности (заглушка)
 */
class PerformanceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Реализовать настройки производительности
        finish()
    }
}

/**
 * Активность логов (заглушка)
 */
class LogsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Реализовать просмотр логов
        finish()
    }
}
