package com.example.diceautobet

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.diceautobet.models.AreaType
import com.example.diceautobet.models.SingleModeAreaType
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.SelectionOverlayView
import com.example.diceautobet.logging.GameLogger
import com.example.diceautobet.error.ErrorHandler
import com.example.diceautobet.validation.GameValidator

class AreaConfigActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AreaConfigActivity"
    }
    
    private lateinit var selectionView: SelectionOverlayView
    private lateinit var btnSave: Button
    private lateinit var btnSkip: Button
    private lateinit var btnCancel: Button
    private lateinit var btnTest: Button
    private lateinit var prefsManager: PreferencesManager
    private lateinit var gameLogger: GameLogger
    
    // Поддержка разных режимов
    private var configMode: String = "DUAL_MODE" // По умолчанию двойной режим
    private var currentAreaType: AreaType? = null
    private var currentSingleAreaType: SingleModeAreaType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_area_config)

        // Инициализируем компоненты
        prefsManager = PreferencesManager(this)
        gameLogger = GameLogger(this)
        
        // Получаем режим из Intent
        configMode = intent.getStringExtra("MODE") ?: "DUAL_MODE"
        val customTitle = intent.getStringExtra("TITLE")
        
        // Инициализируем UI элементы
        selectionView = findViewById(R.id.selectionOverlayView)
        
        // Настраиваем SelectionOverlayView для single mode
        if (configMode == "SINGLE_MODE") {
            Log.d(TAG, "Настройка SelectionOverlayView для single mode")
            selectionView?.setBorderColor(
                ContextCompat.getColor(this, R.color.blue)
            )
            // Очищаем границы окна для single mode (используем весь экран)
            selectionView?.clearWindowBounds()
            Log.d(TAG, "SelectionOverlayView настроен: цвет границы установлен, windowBounds очищены")
        }
        
        btnSave = findViewById(R.id.btnSave)
        btnSkip = findViewById(R.id.btnSkip)
        btnCancel = findViewById(R.id.btnCancel)
        btnTest = findViewById(R.id.btnTest)

        // Настраиваем обработчики
        setupButtons()
        
        // Инициализируем в зависимости от режима
        when (configMode) {
            "SINGLE_MODE" -> {
                currentSingleAreaType = SingleModeAreaType.values()[0]
                title = customTitle ?: "Настройка областей - Одиночный режим"
            }
            else -> {
                currentAreaType = AreaType.values()[0]
                title = customTitle ?: "Настройка областей - Двойной режим"
            }
        }
        
        updateUI()
        
        gameLogger.logSystemEvent("AreaConfigActivity создан в режиме: $configMode")
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            saveCurrentArea()
        }
        
        btnSkip.setOnClickListener {
            moveToNextArea()
        }
        
        btnCancel.setOnClickListener {
            finish()
        }
        
        btnTest.setOnClickListener {
            testCoordinates()
        }
    }

    private fun saveCurrentArea() {
        val selection = selectionView.getAbsoluteSelection()
        if (selection == null) {
            Toast.makeText(this, "Сначала выберите область", Toast.LENGTH_SHORT).show()
            return
        }

        when (configMode) {
            "SINGLE_MODE" -> {
                currentSingleAreaType?.let { areaType ->
                    prefsManager.saveSingleModeAreaRect(areaType, selection)
                    
                    gameLogger.logUserAction("Сохранена область одиночного режима: ${areaType.displayName}", mapOf(
                        "area" to areaType.name,
                        "coordinates" to selection.toString()
                    ))
                    
                    moveToNextArea()
                }
            }
            else -> {
                currentAreaType?.let { areaType ->
                    prefsManager.saveArea(areaType, selection)
                    gameLogger.logAreaSaved(areaType, selection)
                    moveToNextArea()
                }
            }
        }
    }

    private fun moveToNextArea() {
        try {
            when (configMode) {
                "SINGLE_MODE" -> {
                    val areas = SingleModeAreaType.values()
                    val currentIndex = areas.indexOf(currentSingleAreaType)
                    
                    if (currentIndex + 1 >= areas.size) {
                        // Все области одиночного режима настроены
                        gameLogger.logSystemEvent("Все области одиночного режима настроены, завершение активности")
                        Toast.makeText(this, "Все области одиночного режима успешно настроены!", Toast.LENGTH_LONG).show()
                        finish()
                        return
                    }
                    
                    currentSingleAreaType = areas[currentIndex + 1]
                    selectionView.clearSelection()
                    updateUI()
                    
                    gameLogger.logSystemEvent("Переключение на область одиночного режима: ${currentSingleAreaType?.displayName}")
                }
                else -> {
                    val areas = AreaType.values()
                    val currentIndex = areas.indexOf(currentAreaType)
                    
                    if (currentIndex + 1 >= areas.size) {
                        // Все области настроены
                        gameLogger.logSystemEvent("Все области настроены, завершение активности")
                        finish()
                        return
                    }
                    
                    currentAreaType = areas[currentIndex + 1]
                    selectionView.clearSelection()
                    updateUI()
                    
                    gameLogger.logSystemEvent("Переключение на область: ${currentAreaType?.displayName}")
                }
            }
        } catch (e: Exception) {
            val error = ErrorHandler.handleError(e)
            gameLogger.logError(e, "Переход к следующей области")
            Toast.makeText(this, "Ошибка: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        try {
            when (configMode) {
                "SINGLE_MODE" -> {
                    currentSingleAreaType?.let { areaType ->
                        title = "Настройка: ${areaType.displayName}"
                        
                        // Показываем дополнительную информацию для областей одиночного режима
                        val description = when (areaType) {
                            SingleModeAreaType.DICE_AREA -> "Выберите область с кубиками для анализа результата"
                            SingleModeAreaType.BET_BLUE -> "Выберите кнопку ставки на синий цвет"
                            SingleModeAreaType.BET_RED -> "Выберите кнопку ставки на красный цвет"
                            SingleModeAreaType.DOUBLE_BUTTON -> "Выберите кнопку удвоения ставки (Х2)"
                            else -> if (SingleModeAreaType.isBetArea(areaType)) {
                                "Выберите кнопку ставки ${SingleModeAreaType.getBetAmountByArea(areaType)}"
                            } else {
                                areaType.description
                            }
                        }
                        
                        Toast.makeText(this, description, Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    currentAreaType?.let { areaType ->
                        title = "Настройка: ${areaType.displayName}"
                    }
                }
            }
            
            // Обновляем отображение selectionView
            selectionView?.invalidate()
            
        } catch (e: Exception) {
            gameLogger.logError(e, "Обновление UI")
        }
    }

    private fun testCoordinates() {
        try {
            val relativeSelection = selectionView.getSelection()
            val absoluteSelection = selectionView.getAbsoluteSelection()
            
            Log.d("AreaConfig", "=== ТЕСТ КООРДИНАТ ===")
            Log.d("AreaConfig", "Относительные координаты: $relativeSelection")
            Log.d("AreaConfig", "Абсолютные координаты: $absoluteSelection")
            
            gameLogger.logCoordinatesTest(relativeSelection, absoluteSelection)
            
            if (absoluteSelection != null) {
                val validationResult = GameValidator.validateCoordinates(absoluteSelection, this)
                if (validationResult.isValid) {
                    Toast.makeText(this, "Координаты корректны", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMessage = (validationResult as com.example.diceautobet.validation.ValidationResult.Error).message
                    Toast.makeText(this, "Проблема с координатами: $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            val error = ErrorHandler.handleError(e)
            gameLogger.logError(e, "Тест координат")
            Toast.makeText(this, "Ошибка тестирования: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Получить информацию о прогрессе настройки
     */
    private fun getProgressInfo(): String {
        return when (configMode) {
            "SINGLE_MODE" -> {
                val areas = SingleModeAreaType.values()
                val currentIndex = areas.indexOf(currentSingleAreaType)
                "${currentIndex + 1} из ${areas.size}"
            }
            else -> {
                val areas = AreaType.values()
                val currentIndex = areas.indexOf(currentAreaType)
                "${currentIndex + 1} из ${areas.size}"
            }
        }
    }

    override fun onDestroy() {
        try {
            gameLogger.logSystemEvent("AreaConfigActivity уничтожен (режим: $configMode)")
            gameLogger.destroy()
        } catch (e: Exception) {
            Log.e("AreaConfigActivity", "Ошибка при уничтожении", e)
        }
        super.onDestroy()
    }
}