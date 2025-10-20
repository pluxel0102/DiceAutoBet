package com.example.diceautobet.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.diceautobet.R
import com.example.diceautobet.databinding.ActivitySingleModeSettingsBinding
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.logging.GameLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Активность настроек одиночного режима
 */
class SingleModeSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySingleModeSettingsBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var gameLogger: GameLogger
    
    private var currentSettings = SingleModeSettings()
    
    companion object {
        private const val TAG = "SingleModeSettings"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySingleModeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferencesManager = PreferencesManager(this)
        gameLogger = GameLogger(this)
        
        setupToolbar()
        setupSpinners()
        setupListeners()
        loadSettings()
        
        Log.d(TAG, "SingleModeSettingsActivity создана")
    }
    
    /**
     * Настройка тулбара
     */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }
    
    /**
     * Настройка выпадающих списков
     */
    private fun setupSpinners() {
        // Настройка спиннера базовой ставки
        val availableBets = SingleModeAreaType.getAvailableBetAmounts()
        val betAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            availableBets.map { it.toString() }
        )
        
        (binding.spinnerBaseBet as? AutoCompleteTextView)?.apply {
            setAdapter(betAdapter)
            setText(currentSettings.baseBet.toString(), false)
        }
    }
    
    /**
     * Настройка слушателей событий
     */
    private fun setupListeners() {
        // Базовая ставка
        binding.spinnerBaseBet.setOnItemClickListener { _, _, position, _ ->
            val selectedBet = SingleModeAreaType.getAvailableBetAmounts()[position]
            currentSettings = currentSettings.withBaseBet(selectedBet)
            updateStrategyDescription()
            Log.d(TAG, "Выбрана базовая ставка: $selectedBet")
        }
        
        // Предпочитаемый цвет
        binding.chipGroupColor.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val selectedColor = when (checkedIds[0]) {
                    R.id.chipBlue -> BetColor.BLUE
                    R.id.chipRed -> BetColor.RED
                    else -> BetColor.BLUE
                }
                currentSettings = currentSettings.withPreferredColor(selectedColor)
                updateStrategyDescription()
                Log.d(TAG, "Выбран цвет: $selectedColor")
            }
        }
        
        // УДАЛЕНО: Максимальная ставка (убран лимит по требованию пользователя)
        
        // Переключатель смены цвета
        binding.switchColorSwitching.setOnCheckedChangeListener { _, isChecked ->
            currentSettings = currentSettings.copy(enableColorSwitching = isChecked)
            binding.layoutColorSwitchLosses.isEnabled = isChecked
            binding.etColorSwitchLosses.isEnabled = isChecked
            updateStrategyDescription()
            Log.d(TAG, "Смена цвета: $isChecked")
        }
        
        // Количество проигрышей до смены цвета
        binding.etColorSwitchLosses.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val lossesText = s?.toString() ?: ""
                if (lossesText.isNotEmpty()) {
                    try {
                        val losses = lossesText.toInt()
                        currentSettings = currentSettings.copy(maxLossesBeforeColorSwitch = losses)
                        updateStrategyDescription()
                        Log.d(TAG, "Проигрышей до смены цвета: $losses")
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Некорректное значение проигрышей: $lossesText")
                    }
                }
            }
        })
        
        // Задержка детекции
        binding.etDetectionDelay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val delayText = s?.toString() ?: ""
                if (delayText.isNotEmpty()) {
                    try {
                        val delay = delayText.toLong()
                        currentSettings = currentSettings.copy(detectionDelay = delay)
                        Log.d(TAG, "Задержка детекции: $delay мс")
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Некорректное значение задержки детекции: $delayText")
                    }
                }
            }
        })
        
        // Задержка между кликами
        binding.etClickDelay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val delayText = s?.toString() ?: ""
                if (delayText.isNotEmpty()) {
                    try {
                        val delay = delayText.toLong()
                        currentSettings = currentSettings.copy(clickDelay = delay)
                        Log.d(TAG, "Задержка кликов: $delay мс")
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Некорректное значение задержки кликов: $delayText")
                    }
                }
            }
        })
        
        // Подробное логирование
        binding.switchDetailedLogging.setOnCheckedChangeListener { _, isChecked ->
            currentSettings = currentSettings.copy(enableDetailedLogging = isChecked)
            Log.d(TAG, "Подробное логирование: $isChecked")
        }
        
        // Тестовый режим
        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            currentSettings = currentSettings.copy(enableTestMode = isChecked)
            if (isChecked) {
                showTestModeWarning()
            }
            Log.d(TAG, "Тестовый режим: $isChecked")
        }
        
        // Кнопка сохранения
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
        
        // Кнопка сброса
        binding.btnResetSettings.setOnClickListener {
            showResetDialog()
        }
    }
    
    /**
     * Загрузка настроек
     */
    private fun loadSettings() {
        try {
            currentSettings = preferencesManager.getSingleModeSettings()
            
            applySettingsToUI()
            updateStrategyDescription()
            
            Log.d(TAG, "Настройки загружены из PreferencesManager: $currentSettings")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки настроек", e)
            Toast.makeText(this, "Ошибка загрузки настроек: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Применение настроек к UI
     */
    private fun applySettingsToUI() {
        // Базовая ставка
        binding.spinnerBaseBet.setText(currentSettings.baseBet.toString(), false)
        
        // Предпочитаемый цвет
        when (currentSettings.preferredColor) {
            BetColor.BLUE -> binding.chipBlue.isChecked = true
            BetColor.RED -> binding.chipRed.isChecked = true
        }
        
        // УДАЛЕНО: Максимальная ставка (убран лимит)
        
        // Смена цвета
        binding.switchColorSwitching.isChecked = currentSettings.enableColorSwitching
        binding.layoutColorSwitchLosses.isEnabled = currentSettings.enableColorSwitching
        binding.etColorSwitchLosses.isEnabled = currentSettings.enableColorSwitching
        binding.etColorSwitchLosses.setText(currentSettings.maxLossesBeforeColorSwitch.toString())
        
        // Производительность
        binding.etDetectionDelay.setText(currentSettings.detectionDelay.toString())
        binding.etClickDelay.setText(currentSettings.clickDelay.toString())
        
        // Отладка
        binding.switchDetailedLogging.isChecked = currentSettings.enableDetailedLogging
        binding.switchTestMode.isChecked = currentSettings.enableTestMode
    }
    
    /**
     * Обновление описания стратегии
     */
    private fun updateStrategyDescription() {
        binding.tvStrategyDescription.text = currentSettings.getStrategyDescription()
    }
    
    /**
     * Сохранение настроек
     */
    private fun saveSettings() {
        try {
            // Валидация настроек
            val validation = currentSettings.validate()
            if (!validation.isValid) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Ошибка в настройках")
                    .setMessage(validation.message)
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            
            // Сохранение в PreferencesManager
            preferencesManager.saveSingleModeSettings(currentSettings)
            
            gameLogger.logUserAction("Сохранены настройки одиночного режима", mapOf(
                "baseBet" to currentSettings.baseBet,
                "preferredColor" to currentSettings.preferredColor.name,
                "maxBet" to currentSettings.maxBet
            ))
            
            Toast.makeText(this, "Настройки сохранены успешно!", Toast.LENGTH_SHORT).show()
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения настроек", e)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Показать диалог сброса настроек
     */
    private fun showResetDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Сброс настроек")
            .setMessage("Вы уверены, что хотите сбросить все настройки к значениям по умолчанию?")
            .setPositiveButton("Сбросить") { _, _ ->
                resetSettings()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    /**
     * Сброс настроек к значениям по умолчанию
     */
    private fun resetSettings() {
        currentSettings = SingleModeSettings()
        applySettingsToUI()
        updateStrategyDescription()
        
        Toast.makeText(this, "Настройки сброшены к значениям по умолчанию", Toast.LENGTH_SHORT).show()
        gameLogger.logUserAction("Сброшены настройки одиночного режима к умолчанию")
        
        Log.d(TAG, "Настройки сброшены к умолчанию")
    }
    
    /**
     * Показать предупреждение о тестовом режиме
     */
    private fun showTestModeWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Тестовый режим")
            .setMessage("""
                Тестовый режим включен!
                
                В этом режиме:
                • Реальные клики НЕ выполняются
                • Производится только анализ результатов
                • Все действия записываются в лог
                
                Используйте для отладки и тестирования стратегии.
            """.trimIndent())
            .setPositiveButton("Понятно", null)
            .show()
    }
}