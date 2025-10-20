package com.example.diceautobet.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.diceautobet.R
import com.example.diceautobet.databinding.ActivityDualModeAreaConfigBinding
import com.example.diceautobet.managers.DualWindowAreaManager
import com.example.diceautobet.models.WindowType
import com.example.diceautobet.models.SplitScreenType
import com.example.diceautobet.services.AreaConfigurationService
import com.example.diceautobet.utils.SplitScreenUtils
import com.example.diceautobet.utils.PreferencesManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Активность для настройки областей в двойном режиме
 * Позволяет отдельно настроить области для левого и правого окна
 */
class DualModeAreaConfigActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DualModeAreaConfig"
    }
    
    private lateinit var binding: ActivityDualModeAreaConfigBinding
    private lateinit var areaManager: DualWindowAreaManager
    private lateinit var prefsManager: PreferencesManager
    private var splitScreenType: SplitScreenType = SplitScreenType.HORIZONTAL
    private var firstWindowType: WindowType = WindowType.LEFT
    private var secondWindowType: WindowType = WindowType.RIGHT
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityDualModeAreaConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        areaManager = DualWindowAreaManager(this)
        prefsManager = PreferencesManager(this)
        
        // Получаем настройки двойного режима
        val dualModeSettings = prefsManager.getDualModeSettings()
        splitScreenType = dualModeSettings.splitScreenType
        
        // Определяем типы окон в зависимости от типа разделения
        val windowTypes = SplitScreenUtils.getWindowTypes(splitScreenType)
        firstWindowType = windowTypes.first
        secondWindowType = windowTypes.second
        
        setupUI()
        updateStatusDisplay()
        
        Log.d(TAG, "DualModeAreaConfigActivity создана, тип разделения: $splitScreenType")
    }
    
    private fun setupUI() {
        setupToolbar()
        setupButtons()
        checkSplitScreenSupport()
    }
    
    private fun setupToolbar() {
        binding.toolbar.title = "Настройка областей для двойного режима"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
    
    private fun setupButtons() {
        // Обновляем названия кнопок в зависимости от типа разделения
        when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> {
                binding.btnConfigureLeftWindow.text = "🔧 Настроить левое окно"
                binding.btnConfigureRightWindow.text = "🔧 Настроить правое окно"
                binding.btnCopyLeftToRight.text = "📋 Копировать: левое → правое"
                binding.btnCopyRightToLeft.text = "📋 Копировать: правое → левое"
            }
            SplitScreenType.VERTICAL -> {
                binding.btnConfigureLeftWindow.text = "🔧 Настроить верхнее окно"
                binding.btnConfigureRightWindow.text = "🔧 Настроить нижнее окно"
                binding.btnCopyLeftToRight.text = "📋 Копировать: верхнее → нижнее"
                binding.btnCopyRightToLeft.text = "📋 Копировать: нижнее → верхнее"
            }
        }
        
        // Настройка левого окна
        binding.btnConfigureLeftWindow.setOnClickListener {
            configureWindow(firstWindowType)
        }
        
        // Настройка правого окна
        binding.btnConfigureRightWindow.setOnClickListener {
            configureWindow(secondWindowType)
        }
        
        // Копирование настроек
        binding.btnCopyLeftToRight.setOnClickListener {
            copyAreas(firstWindowType, secondWindowType)
        }
        
        binding.btnCopyRightToLeft.setOnClickListener {
            copyAreas(secondWindowType, firstWindowType)
        }
        
        // Автонастройка
        binding.btnAutoConfig.setOnClickListener {
            autoConfigureAreas()
        }
        
        // Проверка настроек
        binding.btnTestConfiguration.setOnClickListener {
            testConfiguration()
        }
    }
    
    private fun checkSplitScreenSupport() {
        val isSupported = SplitScreenUtils.isSplitScreenSupported(this)
        
        if (!isSupported) {
            binding.textSplitScreenStatus.text = "❌ Разделенный экран не поддерживается"
            binding.textSplitScreenStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            
            // Отключаем кнопки настройки
            binding.btnConfigureLeftWindow.isEnabled = false
            binding.btnConfigureRightWindow.isEnabled = false
            binding.btnAutoConfig.isEnabled = false
            
            showSplitScreenWarning()
        } else {
            binding.textSplitScreenStatus.text = "✅ Разделенный экран поддерживается"
            binding.textSplitScreenStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        }
    }
    
    private fun showSplitScreenWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Предупреждение")
            .setMessage("""
                Ваше устройство не поддерживает разделенный экран или имеет слишком маленький размер экрана.
                
                Минимальные требования:
                • Разрешение экрана: 1000x600 пикселей
                • Android 7.0 или выше
                • Поддержка Multi-Window
                
                Двойной режим может работать некорректно.
            """.trimIndent())
            .setPositiveButton("Понятно") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun configureWindow(windowType: WindowType) {
        Log.d(TAG, "Настройка областей для окна: $windowType")
        
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        
        // Запускаем оверлей настройки областей БЕЗ сворачивания приложения
        DualModeAreaConfigService.configureWindow(this, windowType)
        
        // НЕ сворачиваем приложение - оверлей появится поверх
        // moveTaskToBack(true) - убрано
    }
    
    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Требуется разрешение")
            .setMessage("Для настройки областей необходимо разрешение на отображение поверх других приложений.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(intent)
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun copyAreas(fromWindow: WindowType, toWindow: WindowType) {
        Log.d(TAG, "Копирование областей из $fromWindow в $toWindow")
        
        try {
            areaManager.copyAreasToWindow(fromWindow, toWindow)
            
            Toast.makeText(
                this,
                "Области скопированы из ${fromWindow.name} в ${toWindow.name}",
                Toast.LENGTH_SHORT
            ).show()
            
            updateStatusDisplay()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка копирования областей", e)
            Toast.makeText(this, "Ошибка копирования: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun autoConfigureAreas() {
        Log.d(TAG, "Автоматическая настройка областей")
        
        try {
            areaManager.autoConfigureAreas()
            
            Toast.makeText(this, "Автоматическая настройка выполнена", Toast.LENGTH_SHORT).show()
            updateStatusDisplay()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка автонастройки", e)
            Toast.makeText(this, "Ошибка автонастройки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun testConfiguration() {
        Log.d(TAG, "Проверка конфигурации")
        
        val status = areaManager.getConfigurationStatus()
        val message = StringBuilder()
        
        message.append("📊 Состояние настройки:\n\n")
        
        // Поддержка разделенного экрана
        if (status.splitScreenSupported) {
            message.append("✅ Разделенный экран: поддерживается\n")
        } else {
            message.append("❌ Разделенный экран: не поддерживается\n")
        }
        
        // Левое окно
        if (status.leftWindowConfigured) {
            message.append("✅ Левое окно: настроено (${status.leftAreasCount} областей)\n")
        } else {
            message.append("❌ Левое окно: не настроено\n")
        }
        
        // Правое окно
        if (status.rightWindowConfigured) {
            message.append("✅ Правое окно: настроено (${status.rightAreasCount} областей)\n")
        } else {
            message.append("❌ Правое окно: не настроено\n")
        }
        
        // Общий статус
        message.append("\n")
        if (status.readyForDualMode) {
            message.append("🎯 Готовность: ГОТОВ К РАБОТЕ")
        } else {
            message.append("⚠️ Готовность: ТРЕБУЕТСЯ НАСТРОЙКА")
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Проверка конфигурации")
            .setMessage(message.toString())
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun updateStatusDisplay() {
        val status = areaManager.getConfigurationStatus()
        
        // Обновляем статус левого окна
        if (status.leftWindowConfigured) {
            binding.textLeftWindowStatus.text = "✅ Настроено (${status.leftAreasCount} областей)"
            binding.textLeftWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            binding.btnCopyLeftToRight.isEnabled = true
        } else {
            binding.textLeftWindowStatus.text = "❌ Не настроено"
            binding.textLeftWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            binding.btnCopyLeftToRight.isEnabled = false
        }
        
        // Обновляем статус правого окна
        if (status.rightWindowConfigured) {
            binding.textRightWindowStatus.text = "✅ Настроено (${status.rightAreasCount} областей)"
            binding.textRightWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            binding.btnCopyRightToLeft.isEnabled = true
        } else {
            binding.textRightWindowStatus.text = "❌ Не настроено"
            binding.textRightWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            binding.btnCopyRightToLeft.isEnabled = false
        }
        
        // Обновляем общий статус готовности
        if (status.readyForDualMode) {
            binding.textReadyStatus.text = "🎯 ГОТОВ К РАБОТЕ"
            binding.textReadyStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            binding.textReadyStatus.text = "⚠️ ТРЕБУЕТСЯ НАСТРОЙКА"
            binding.textReadyStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
