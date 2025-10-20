package com.example.diceautobet.managers

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.diceautobet.models.*
import com.example.diceautobet.utils.PreferencesManager
import com.example.diceautobet.utils.SplitScreenUtils
import com.example.diceautobet.utils.CoordinateUtils

/**
 * Менеджер для управления областями экрана в двойном режиме
 * Обеспечивает быстрое переключение между областями левого и правого окон
 */
class DualWindowAreaManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DualWindowAreaManager"
        private const val CACHE_VALIDITY_MS = 30000L // 30 секунд
    }
    
    private val preferencesManager = PreferencesManager(context)
    private var currentActiveWindow = WindowType.LEFT
    
    // Кэш областей для быстрого доступа
    private var leftWindowAreas: Map<AreaType, ScreenArea> = emptyMap()
    private var rightWindowAreas: Map<AreaType, ScreenArea> = emptyMap()
    private var topWindowAreas: Map<AreaType, ScreenArea> = emptyMap()
    private var bottomWindowAreas: Map<AreaType, ScreenArea> = emptyMap()
    
    // 🚀 ДОПОЛНИТЕЛЬНЫЙ КЭШ - чтобы не загружать из Preferences каждый раз
    private var areasLoaded = false
    private var lastLoadTime = 0L
    
    // Границы окон
    private var leftWindowBounds: Rect = Rect()
    private var rightWindowBounds: Rect = Rect()
    
    // Убираем автоинициализацию - теперь вызывается только при необходимости
    private var initialized = false
    
    /**
     * Ленивая инициализация - вызывается только при первом обращении
     */
    private fun ensureInitialized() {
        if (!initialized) {
            Log.d(TAG, "Первая инициализация DualWindowAreaManager")
            refreshWindowBounds()
            loadAreas()
            initialized = true
        }
    }
    
    /**
     * Обновляет информацию о границах окон
     */
    fun refreshWindowBounds() {
        leftWindowBounds = SplitScreenUtils.getWindowBounds(context, WindowType.LEFT)
        rightWindowBounds = SplitScreenUtils.getWindowBounds(context, WindowType.RIGHT)
        
        Log.d(TAG, "Обновлены границы окон:")
        Log.d(TAG, "  Левое: $leftWindowBounds")
        Log.d(TAG, "  Правое: $rightWindowBounds")
    }
    
    /**
     * Загружает области для всех окон с кэшированием
     */
    fun loadAreas() {
        val currentTime = System.currentTimeMillis()
        
        // 🚀 ПРОВЕРКА КЭША - если данные свежие, не загружаем заново
        if (areasLoaded && (currentTime - lastLoadTime) < CACHE_VALIDITY_MS) {
            Log.d(TAG, "⚡ КЭШ ОБЛАСТЕЙ АКТУАЛЕН (загружен ${currentTime - lastLoadTime}мс назад)")
            return
        }
        
        Log.d(TAG, "🔄 Загрузка областей для всех окон (кэш устарел или пуст)")
        
        // Загружаем области для всех типов окон
        leftWindowAreas = preferencesManager.loadAreasForWindow(WindowType.LEFT)
        rightWindowAreas = preferencesManager.loadAreasForWindow(WindowType.RIGHT)
        topWindowAreas = preferencesManager.loadAreasForWindow(WindowType.TOP)
        bottomWindowAreas = preferencesManager.loadAreasForWindow(WindowType.BOTTOM)
        
        // 🚀 ОТМЕЧАЕМ КЭШ КАК АКТУАЛЬНЫЙ
        areasLoaded = true
        lastLoadTime = currentTime
        
        Log.d(TAG, "✅ Загружено областей:")
        Log.d(TAG, "  Левое окно: ${leftWindowAreas.size}")
        Log.d(TAG, "  Правое окно: ${rightWindowAreas.size}")
        Log.d(TAG, "  TOP окно: ${topWindowAreas.size}")
        Log.d(TAG, "  BOTTOM окно: ${bottomWindowAreas.size}")
    }
    
    /**
     * Переключает активное окно
     */
    fun switchActiveWindow(): WindowType {
        currentActiveWindow = when (currentActiveWindow) {
            WindowType.LEFT -> WindowType.RIGHT
            WindowType.RIGHT -> WindowType.LEFT
            WindowType.TOP -> WindowType.BOTTOM
            WindowType.BOTTOM -> WindowType.TOP
        }
        
        Log.d(TAG, "Переключение на активное окно: $currentActiveWindow")
        return currentActiveWindow
    }
    
    /**
     * Устанавливает активное окно
     */
    fun setActiveWindow(windowType: WindowType) {
        currentActiveWindow = windowType
        Log.d(TAG, "Установлено активное окно: $currentActiveWindow")
    }
    
    /**
     * Получает текущее активное окно
     */
    fun getCurrentActiveWindow(): WindowType = currentActiveWindow
    
    /**
     * Получает область для указанного типа в активном окне
     */
    fun getActiveArea(areaType: AreaType): ScreenArea? {
        return getAreaForWindow(currentActiveWindow, areaType)
    }
    
    /**
     * Получает область для указанного типа в конкретном окне
     */
    fun getAreaForWindow(windowType: WindowType, areaType: AreaType): ScreenArea? {
        ensureInitialized()
        
        // Загружаем области специально для запрашиваемого типа окна
        val areas = when (windowType) {
            WindowType.LEFT -> {
                if (leftWindowAreas.isEmpty()) {
                    loadAreasForWindow(WindowType.LEFT)
                }
                leftWindowAreas
            }
            WindowType.RIGHT -> {
                if (rightWindowAreas.isEmpty()) {
                    loadAreasForWindow(WindowType.RIGHT)
                }
                rightWindowAreas
            }
            WindowType.TOP -> {
                if (topWindowAreas.isEmpty()) {
                    loadAreasForWindow(WindowType.TOP)
                }
                topWindowAreas
            }
            WindowType.BOTTOM -> {
                if (bottomWindowAreas.isEmpty()) {
                    loadAreasForWindow(WindowType.BOTTOM)
                }
                bottomWindowAreas
            }
        }
        
        val area = areas[areaType]
        if (area == null) {
            Log.w(TAG, "Область $areaType не найдена для окна $windowType")
            return null
        }
        
        Log.d(TAG, "Найдена область $areaType для окна $windowType: ${area.rect}")
        
        // ИСПРАВЛЕНИЕ: Для кликов в BOTTOM окне преобразуем координаты в экранные
        if (windowType == WindowType.TOP || windowType == WindowType.BOTTOM) {
            val screenArea = transformAreaForClick(windowType, area)
            Log.d(TAG, "Преобразованы координаты для клика в $windowType: ${area.rect} → ${screenArea.rect}")
            return screenArea
        }
        
        return area
    }
    
    /**
     * Преобразует координаты области для кликов в разделенном экране
     */
    private fun transformAreaForClick(windowType: WindowType, area: ScreenArea): ScreenArea {
        when (windowType) {
            WindowType.TOP -> {
                // TOP окно: координаты остаются без изменений (0-929)
                return area
            }
            WindowType.BOTTOM -> {
                // BOTTOM окно: координаты уже абсолютные, не преобразуем их
                // getAbsoluteCoordinates теперь правильно обрабатывает координаты
                return area
            }
            else -> return area
        }
    }
    
    /**
     * Получает все области для указанного окна (ОПТИМИЗИРОВАНО: без повторной загрузки)
     */
    fun getAreasForWindow(windowType: WindowType): Map<AreaType, ScreenArea> {
        ensureInitialized() // Гарантируем инициализацию
        return when (windowType) {
            WindowType.LEFT -> leftWindowAreas
            WindowType.RIGHT -> rightWindowAreas
            WindowType.TOP -> topWindowAreas
            WindowType.BOTTOM -> bottomWindowAreas
        }
    }
    
    /**
     * Загружает области из настроек для указанного окна
     */
    private fun loadAreasForWindow(windowType: WindowType) {
        val areas = preferencesManager.loadAreasForWindow(windowType)
        
        // Проверяем, нужна ли миграция координат (если области сохранены по старому принципу)
        val migratedAreas = migrateAreasIfNeeded(windowType, areas)
        
        when (windowType) {
            WindowType.LEFT -> leftWindowAreas = migratedAreas
            WindowType.RIGHT -> rightWindowAreas = migratedAreas
            WindowType.TOP -> topWindowAreas = migratedAreas
            WindowType.BOTTOM -> bottomWindowAreas = migratedAreas
        }
    }
    
    /**
     * Проверяет и исправляет области, сохраненные по старому принципу (относительно полного экрана)
     * для работы с новым принципом (относительно границ окна)
     */
    private fun migrateAreasIfNeeded(windowType: WindowType, areas: Map<AreaType, ScreenArea>): Map<AreaType, ScreenArea> {
        if (areas.isEmpty()) return areas
        
        val windowBounds = when (windowType) {
            WindowType.LEFT -> leftWindowBounds
            WindowType.RIGHT -> rightWindowBounds
            WindowType.TOP -> SplitScreenUtils.getWindowBounds(context, WindowType.TOP)
            WindowType.BOTTOM -> SplitScreenUtils.getWindowBounds(context, WindowType.BOTTOM)
        }
        
        val migratedAreas = mutableMapOf<AreaType, ScreenArea>()
        var needsMigration = false
        
        areas.forEach { (areaType, screenArea) ->
            if (screenArea.adaptive != null) {
                // Конвертируем старые adaptive координаты (относительно полного экрана)
                val oldAbsoluteRect = CoordinateUtils.convertFromAdaptiveCoordinates(screenArea.adaptive, context)
                
                // Проверяем, находится ли область в границах этого окна
                val isInWindow = windowBounds.contains(oldAbsoluteRect.centerX(), oldAbsoluteRect.centerY())
                
                if (isInWindow) {
                    // Создаем новые adaptive координаты относительно границ окна
                    val newAdaptive = CoordinateUtils.convertToAdaptiveCoordinates(oldAbsoluteRect, windowBounds)
                    val migratedArea = screenArea.copy(
                        rect = oldAbsoluteRect,
                        adaptive = newAdaptive
                    )
                    migratedAreas[areaType] = migratedArea
                    
                    if (newAdaptive != screenArea.adaptive) {
                        needsMigration = true
                        Log.d(TAG, "Миграция $areaType для $windowType: старый adaptive=${screenArea.adaptive}, новый=$newAdaptive")
                    }
                } else {
                    // Область не в этом окне, оставляем как есть
                    migratedAreas[areaType] = screenArea
                }
            } else {
                // Нет adaptive координат, оставляем как есть
                migratedAreas[areaType] = screenArea
            }
        }
        
        // Если произошла миграция, сохраняем новые координаты
        if (needsMigration) {
            Log.d(TAG, "Сохраняем мигрированные области для $windowType")
            preferencesManager.saveAreasForWindow(windowType, migratedAreas)
            
            // 🚀 ИНВАЛИДИРУЕМ КЭШ после миграции
            areasLoaded = false
            lastLoadTime = 0L
        }
        
        return migratedAreas
    }
    
    /**
     * Сохраняет область для указанного окна
     */
    fun saveAreaForWindow(windowType: WindowType, areaType: AreaType, area: ScreenArea) {
        Log.d(TAG, "Сохранение области $areaType для окна $windowType: ${area.rect}")
        
        val currentAreas = getAreasForWindow(windowType).toMutableMap()
        currentAreas[areaType] = area
        
        // Обновляем кэш
        when (windowType) {
            WindowType.LEFT -> leftWindowAreas = currentAreas
            WindowType.RIGHT -> rightWindowAreas = currentAreas
            WindowType.TOP -> topWindowAreas = currentAreas
            WindowType.BOTTOM -> bottomWindowAreas = currentAreas
        }
        
        // Сохраняем в настройки с правильным типом окна
        preferencesManager.saveAreasForWindow(windowType, currentAreas)
        
        // 🚀 ИНВАЛИДИРУЕМ КЭШ после сохранения
        areasLoaded = false
        lastLoadTime = 0L
        Log.d(TAG, "🔄 Кэш областей инвалидирован после сохранения")
    }
    
    /**
     * Получает абсолютные координаты области в активном окне
     */
    fun getActiveAbsoluteCoordinates(areaType: AreaType): Rect? {
        return getAbsoluteCoordinates(currentActiveWindow, areaType)
    }
    
    /**
     * Получает абсолютные координаты области в указанном окне
     */
    fun getAbsoluteCoordinates(windowType: WindowType, areaType: AreaType): Rect? {
        val area = getAreaForWindow(windowType, areaType) ?: return null
        
        Log.d(TAG, "🔍 getAbsoluteCoordinates для $windowType.$areaType:")
        Log.d(TAG, "   📄 area.rect = ${area.rect}")
        Log.d(TAG, "   🔄 area.adaptive = ${area.adaptive}")
        
        val windowBounds = when (windowType) {
            WindowType.LEFT -> leftWindowBounds
            WindowType.RIGHT -> rightWindowBounds
            WindowType.TOP -> SplitScreenUtils.getWindowBounds(context, WindowType.TOP)
            WindowType.BOTTOM -> SplitScreenUtils.getWindowBounds(context, WindowType.BOTTOM)
        }
        
        // Если есть adaptive координаты, используем их с учетом границ окна
        val rect = if (area.adaptive != null) {
            // Используем новый метод с границами окна
            Log.d(TAG, "🔄 Преобразование adaptive координат для $areaType в окне $windowType")
            Log.d(TAG, "   🎯 windowBounds = $windowBounds")
            Log.d(TAG, "   🔄 adaptive = ${area.adaptive}")
            val converted = CoordinateUtils.convertFromAdaptiveCoordinates(area.adaptive, windowBounds)
            Log.d(TAG, "   📍 converted = $converted")
            converted
        } else {
            // Обычные координаты уже абсолютные, не преобразуем их
            Log.d(TAG, "✅ Используем сохраненные абсолютные координаты для $areaType в окне $windowType")
            Log.d(TAG, "   📍 rect = ${area.rect}")
            area.rect
        }
        
        Log.d(TAG, "Координаты $areaType для окна $windowType: окно=$windowBounds, область=$rect")
        
        // Проверяем и корректируем координаты если они выходят за пределы экрана
        // НО: для BOTTOM окна координаты могут быть больше высоты экрана - это нормально для split screen
        if (!CoordinateUtils.validateCoordinates(rect, context) && windowType != WindowType.BOTTOM) {
            Log.w(TAG, "⚠️ Область $areaType для окна $windowType выходит за пределы экрана: $rect")
            Log.d(TAG, "Применяем автокоррекцию координат для $windowType.$areaType")
            val correctedRect = CoordinateUtils.correctCoordinates(rect, context)
            Log.d(TAG, "Исправленные координаты: $correctedRect")
            return correctedRect
        } else if (windowType == WindowType.BOTTOM && !CoordinateUtils.validateCoordinates(rect, context)) {
            Log.d(TAG, "🔧 Координаты $areaType для BOTTOM окна выходят за пределы экрана, но это нормально для split screen: $rect")
        }
        
        return rect
    }    /**
     * Проверяет, настроены ли области для окна
     */
    fun isWindowConfigured(windowType: WindowType): Boolean {
        ensureInitialized()
        val areas = getAreasForWindow(windowType)
        val requiredAreas = listOf(
            AreaType.DICE_AREA,
            AreaType.RED_BUTTON,
            AreaType.ORANGE_BUTTON,
            AreaType.CONFIRM_BET,
            AreaType.BET_10
        )
        
        val configured = requiredAreas.all { areas.containsKey(it) }
        Log.d(TAG, "Окно $windowType настроено: $configured (${areas.size} областей)")
        
        return configured
    }
    
    /**
     * Проверяет, готовы ли оба окна к работе
     */
    fun areBothWindowsConfigured(): Boolean {
        val leftConfigured = isWindowConfigured(WindowType.LEFT)
        val rightConfigured = isWindowConfigured(WindowType.RIGHT)
        val result = leftConfigured && rightConfigured
        
        Log.d(TAG, "Оба окна настроены: $result (левое: $leftConfigured, правое: $rightConfigured)")
        return result
    }
    
    /**
     * Копирует области из одного окна в другое
     */
    fun copyAreasToWindow(fromWindow: WindowType, toWindow: WindowType) {
        Log.d(TAG, "Копирование областей из $fromWindow в $toWindow")
        
        val sourceAreas = getAreasForWindow(fromWindow)
        if (sourceAreas.isEmpty()) {
            Log.w(TAG, "Нет областей для копирования в окне $fromWindow")
            return
        }
        
        // Получаем границы целевого окна
        val targetBounds = when (toWindow) {
            WindowType.LEFT -> leftWindowBounds
            WindowType.RIGHT -> rightWindowBounds
            WindowType.TOP -> leftWindowBounds    // Используем границы левого окна для верхнего
            WindowType.BOTTOM -> rightWindowBounds // Используем границы правого окна для нижнего
        }
        
        val sourceBounds = when (fromWindow) {
            WindowType.LEFT -> leftWindowBounds
            WindowType.RIGHT -> rightWindowBounds
            WindowType.TOP -> leftWindowBounds    // Используем границы левого окна для верхнего
            WindowType.BOTTOM -> rightWindowBounds // Используем границы правого окна для нижнего
        }
        
        // Копируем и адаптируем координаты
        val adaptedAreas = mutableMapOf<AreaType, ScreenArea>()
        
        sourceAreas.forEach { (areaType, sourceArea) ->
            // Если есть adaptive координаты, используем их (они уже в процентах)
            val adaptedArea = if (sourceArea.adaptive != null) {
                sourceArea.copy(
                    rect = CoordinateUtils.convertFromAdaptiveCoordinates(sourceArea.adaptive, context),
                    adaptive = sourceArea.adaptive
                )
            } else {
                // Иначе создаем adaptive координаты
                val adaptive = CoordinateUtils.convertToAdaptiveCoordinates(sourceArea.rect, context)
                sourceArea.copy(
                    rect = CoordinateUtils.convertFromAdaptiveCoordinates(adaptive, context),
                    adaptive = adaptive
                )
            }
            
            adaptedAreas[areaType] = adaptedArea
        }
        
        // Сохраняем скопированные области
        when (toWindow) {
            WindowType.LEFT -> leftWindowAreas = adaptedAreas
            WindowType.RIGHT -> rightWindowAreas = adaptedAreas
            WindowType.TOP -> leftWindowAreas = adaptedAreas    // Сохраняем в левые области
            WindowType.BOTTOM -> rightWindowAreas = adaptedAreas // Сохраняем в правые области
        }
        
        // Для TOP/BOTTOM используем соответствующие LEFT/RIGHT для сохранения
        val actualWindowType = when (toWindow) {
            WindowType.TOP -> WindowType.LEFT
            WindowType.BOTTOM -> WindowType.RIGHT
            else -> toWindow
        }
        preferencesManager.saveAreasForWindow(actualWindowType, adaptedAreas)
        Log.d(TAG, "Скопировано ${adaptedAreas.size} областей")
        
        // 🚀 ИНВАЛИДИРУЕМ КЭШ после копирования
        areasLoaded = false
        lastLoadTime = 0L
    }
    
    /**
     * Автоматически определяет и настраивает области на основе текущих настроек
     */
    fun autoConfigureAreas() {
        Log.d(TAG, "Автоматическая настройка областей")
        
        if (!SplitScreenUtils.isSplitScreenSupported(context)) {
            Log.w(TAG, "Разделенный экран не поддерживается")
            return
        }
        
        // Если есть настройки для одного окна, копируем в другое
        when {
            isWindowConfigured(WindowType.LEFT) && !isWindowConfigured(WindowType.RIGHT) -> {
                Log.d(TAG, "Копирование настроек из левого окна в правое")
                copyAreasToWindow(WindowType.LEFT, WindowType.RIGHT)
            }
            isWindowConfigured(WindowType.RIGHT) && !isWindowConfigured(WindowType.LEFT) -> {
                Log.d(TAG, "Копирование настроек из правого окна в левое")
                copyAreasToWindow(WindowType.RIGHT, WindowType.LEFT)
            }
            !isWindowConfigured(WindowType.LEFT) && !isWindowConfigured(WindowType.RIGHT) -> {
                Log.w(TAG, "Нет настроенных областей для автоконфигурации")
            }
            else -> {
                Log.d(TAG, "Оба окна уже настроены")
            }
        }
    }
    
    /**
     * Быстрая проверка готовности конфигурации БЕЗ инициализации областей
     */
    fun isConfigurationReady(): Boolean {
        val dualModeSettings = preferencesManager.getDualModeSettings()
        val splitScreenType = dualModeSettings.splitScreenType
        
        // Определяем какие окна нужно проверить в зависимости от типа разделения
        val (firstWindowType, secondWindowType) = when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> Pair(WindowType.LEFT, WindowType.RIGHT)
            SplitScreenType.VERTICAL -> Pair(WindowType.TOP, WindowType.BOTTOM)
        }
        
        // Проверяем наличие областей БЕЗ инициализации
        val firstAreasCount = preferencesManager.loadAreasForWindow(firstWindowType).size
        val secondAreasCount = preferencesManager.loadAreasForWindow(secondWindowType).size
        
        val isReady = firstAreasCount > 0 && secondAreasCount > 0
        
        Log.d(TAG, "Быстрая проверка готовности ($splitScreenType): $firstWindowType=$firstAreasCount областей, $secondWindowType=$secondAreasCount областей, готово=$isReady")
        
        return isReady
    }
    
    /**
     * Получает информацию о состоянии настройки окон
     */
    fun getConfigurationStatus(): WindowConfigurationStatus {
        // Получаем настройки разделения экрана
        val dualModeSettings = preferencesManager.getDualModeSettings()
        val splitScreenType = dualModeSettings.splitScreenType
        
        // Определяем какие окна нужно проверить в зависимости от типа разделения
        val (firstWindowType, secondWindowType) = when (splitScreenType) {
            SplitScreenType.HORIZONTAL -> Pair(WindowType.LEFT, WindowType.RIGHT)
            SplitScreenType.VERTICAL -> Pair(WindowType.TOP, WindowType.BOTTOM)
        }
        
        val firstWindowConfigured = isWindowConfigured(firstWindowType)
        val secondWindowConfigured = isWindowConfigured(secondWindowType)
        val firstAreasCount = getAreasForWindow(firstWindowType).size
        val secondAreasCount = getAreasForWindow(secondWindowType).size
        
        Log.d(TAG, "Статус конфигурации ($splitScreenType): $firstWindowType=$firstWindowConfigured ($firstAreasCount областей), $secondWindowType=$secondWindowConfigured ($secondAreasCount областей)")
        
        return WindowConfigurationStatus(
            leftWindowConfigured = firstWindowConfigured,
            rightWindowConfigured = secondWindowConfigured,
            leftAreasCount = firstAreasCount,
            rightAreasCount = secondAreasCount,
            splitScreenSupported = SplitScreenUtils.isSplitScreenSupported(context),
            currentActiveWindow = currentActiveWindow,
            splitScreenType = splitScreenType,
            firstWindowType = firstWindowType,
            secondWindowType = secondWindowType
        )
    }
    
    /**
     * Статус настройки окон
     */
    data class WindowConfigurationStatus(
        val leftWindowConfigured: Boolean,
        val rightWindowConfigured: Boolean,
        val leftAreasCount: Int,
        val rightAreasCount: Int,
        val splitScreenSupported: Boolean,
        val currentActiveWindow: WindowType,
        val splitScreenType: SplitScreenType,
        val firstWindowType: WindowType,
        val secondWindowType: WindowType
    ) {
        val bothWindowsReady: Boolean
            get() = leftWindowConfigured && rightWindowConfigured
        
        val readyForDualMode: Boolean
            get() = bothWindowsReady && splitScreenSupported
    }
    
    /**
     * Очищает кэш областей, заставляя перезагрузить их из настроек
     */
    fun invalidateAreas() {
        leftWindowAreas = emptyMap()
        rightWindowAreas = emptyMap()
        topWindowAreas = emptyMap()
        bottomWindowAreas = emptyMap()
        Log.d(TAG, "🔄 Кэш областей очищен, будет выполнена перезагрузка")
    }
    
    /**
     * Ручная настройка области результатов с точными координатами
     * Позволяет обойти автоматическое определение границ
     */
    fun setManualResultArea(windowType: WindowType, left: Int, top: Int, right: Int, bottom: Int) {
        Log.d(TAG, "🔧 Ручная настройка области RESULT для окна $windowType: [$left, $top, $right, $bottom]")
        
        val manualArea = ScreenArea(
            name = "manual_result",
            rect = Rect(left, top, right, bottom)
        )
        
        saveAreaForWindow(windowType, AreaType.BET_RESULT, manualArea)
        Log.d(TAG, "✅ Область результатов вручную установлена и сохранена")
    }
    
    /**
     * Сброс области результатов к автоматическому определению
     */
    fun resetResultAreaToAuto(windowType: WindowType) {
        Log.d(TAG, "🔄 Сброс области RESULT для окна $windowType к автоматическому определению")
        
        // Удаляем ручную настройку из сохраненных областей
        val currentAreas = getAreasForWindow(windowType).toMutableMap()
        currentAreas.remove(AreaType.BET_RESULT)
        
        // Обновляем кэш
        when (windowType) {
            WindowType.LEFT -> leftWindowAreas = currentAreas
            WindowType.RIGHT -> rightWindowAreas = currentAreas
            WindowType.TOP -> topWindowAreas = currentAreas
            WindowType.BOTTOM -> bottomWindowAreas = currentAreas
        }
        
        preferencesManager.saveAreasForWindow(windowType, currentAreas)
        Log.d(TAG, "✅ Область результатов сброшена, будет использоваться автоопределение")
        
        // 🚀 ИНВАЛИДИРУЕМ КЭШ после сброса
        areasLoaded = false
        lastLoadTime = 0L
    }
}
