package com.example.diceautobet.managers

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager

/**
 * Менеджер для управления областями экрана в одиночном режиме
 * Обеспечивает сохранение и загрузку областей для single mode
 */
class SingleModeAreaManager(private val preferencesManager: PreferencesManager) {
    
    companion object {
        private const val TAG = "SingleModeAreaManager"
    }
    
    // Кэш областей для быстрого доступа
    private var areas: Map<SingleModeAreaType, ScreenArea> = emptyMap()
    private var areasLoaded = false
    
    /**
     * Загружает все области из настроек
     */
    fun loadAreas() {
        if (areasLoaded) return
        
        Log.d(TAG, "Загрузка областей одиночного режима")
        
        val loadedAreas = mutableMapOf<SingleModeAreaType, ScreenArea>()
        
        SingleModeAreaType.values().forEach { areaType ->
            val area = preferencesManager.getSingleModeArea(areaType)
            if (area != null) {
                loadedAreas[areaType] = area
                Log.d(TAG, "Загружена область ${areaType.displayName}: $area")
            }
        }
        
        areas = loadedAreas
        areasLoaded = true
        
        Log.d(TAG, "Загружено ${areas.size} областей из ${SingleModeAreaType.values().size}")
    }
    
    /**
     * Сохраняет область для указанного типа
     */
    fun saveArea(areaType: SingleModeAreaType, rect: Rect) {
        Log.d(TAG, "Сохранение области ${areaType.displayName}: $rect")
        
        val screenArea = ScreenArea(
            name = areaType.displayName,
            rect = rect
        )
        
        // Сохраняем в preferences
        preferencesManager.saveSingleModeArea(areaType, screenArea)
        
        // Обновляем кэш
        val mutableAreas = areas.toMutableMap()
        mutableAreas[areaType] = screenArea
        areas = mutableAreas
        
        Log.d(TAG, "Область ${areaType.displayName} сохранена")
    }
    
    /**
     * Получает область для указанного типа
     */
    fun getArea(areaType: SingleModeAreaType): ScreenArea? {
        if (!areasLoaded) {
            loadAreas()
        }
        
        return areas[areaType]
    }
    
    /**
     * Получает абсолютные координаты области
     */
    fun getAbsoluteCoordinates(areaType: SingleModeAreaType): Rect? {
        val area = getArea(areaType)
        return area?.let {
            Rect(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom)
        }
    }
    
    /**
     * Проверяет, настроена ли область
     */
    fun isAreaConfigured(areaType: SingleModeAreaType): Boolean {
        return getArea(areaType) != null
    }
    
    /**
     * Получает все настроенные области
     */
    fun getAllAreas(): Map<SingleModeAreaType, ScreenArea> {
        if (!areasLoaded) {
            loadAreas()
        }
        return areas.toMap()
    }
    
    /**
     * Получает статус конфигурации областей
     */
    fun getConfigurationStatus(): SingleModeConfigurationStatus {
        if (!areasLoaded) {
            loadAreas()
        }
        
        val requiredAreas = SingleModeAreaType.values()
        val configuredAreas = areas.keys
        
        val missingAreas = requiredAreas.filter { it !in configuredAreas }
        val isFullyConfigured = missingAreas.isEmpty()
        
        Log.d(TAG, "Статус конфигурации: ${configuredAreas.size}/${requiredAreas.size} областей настроено")
        
        return SingleModeConfigurationStatus(
            isFullyConfigured = isFullyConfigured,
            configuredAreas = configuredAreas.toList(),
            missingAreas = missingAreas,
            totalAreas = requiredAreas.size,
            configuredCount = configuredAreas.size
        )
    }
    
    /**
     * Очищает все области
     */
    fun clearAllAreas() {
        Log.d(TAG, "Очистка всех областей одиночного режима")
        
        SingleModeAreaType.values().forEach { areaType ->
            preferencesManager.removeSingleModeArea(areaType)
        }
        
        areas = emptyMap()
        areasLoaded = false
        
        Log.d(TAG, "Все области очищены")
    }
    
    /**
     * Принудительно перезагружает области из настроек
     */
    fun reloadAreas() {
        Log.d(TAG, "Принудительная перезагрузка областей")
        areasLoaded = false
        loadAreas()
    }
    
    /**
     * Экспортирует настройки областей
     */
    fun exportAreas(): Map<String, ScreenArea> {
        if (!areasLoaded) {
            loadAreas()
        }
        
        return areas.mapKeys { it.key.name }
    }
    
    /**
     * Импортирует настройки областей
     */
    fun importAreas(areasMap: Map<String, ScreenArea>) {
        Log.d(TAG, "Импорт ${areasMap.size} областей")
        
        areasMap.forEach { (areaTypeName, screenArea) ->
            try {
                val areaType = SingleModeAreaType.valueOf(areaTypeName)
                preferencesManager.saveSingleModeArea(areaType, screenArea)
                Log.d(TAG, "Импортирована область $areaTypeName")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Неизвестный тип области: $areaTypeName")
            }
        }
        
        // Перезагружаем кэш
        reloadAreas()
    }
}

/**
 * Статус конфигурации областей одиночного режима
 */
data class SingleModeConfigurationStatus(
    val isFullyConfigured: Boolean,
    val configuredAreas: List<SingleModeAreaType>,
    val missingAreas: List<SingleModeAreaType>,
    val totalAreas: Int,
    val configuredCount: Int
) {
    val completionPercentage: Int
        get() = if (totalAreas > 0) (configuredCount * 100) / totalAreas else 0
        
    val statusText: String
        get() = "$configuredCount/$totalAreas областей настроено ($completionPercentage%)"
}