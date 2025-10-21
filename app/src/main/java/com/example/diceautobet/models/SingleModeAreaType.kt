package com.example.diceautobet.models

/**
 * Типы областей для одиночного режима игры в нарды
 */
enum class SingleModeAreaType(val displayName: String, val description: String) {
    // Область детекции результата
    DICE_AREA("Область кубиков", "Область для детекции результатов игры"),
    
    // Выбор цвета ставки
    BET_BLUE("Ставка на синий", "Кнопка выбора синего цвета"),
    BET_RED("Ставка на красный", "Кнопка выбора красного цвета"),
    
    // Области ставок (до 20,000)
    BET_10("Ставка 10", "Кнопка ставки 10"),
    BET_50("Ставка 50", "Кнопка ставки 50"),
    BET_100("Ставка 100", "Кнопка ставки 100"),
    BET_500("Ставка 500", "Кнопка ставки 500"),
    BET_1000("Ставка 1000", "Кнопка ставки 1,000"),
    BET_2000("Ставка 2000", "Кнопка ставки 2,000"),
    BET_5000("Ставка 5000", "Кнопка ставки 5,000"),
    BET_10000("Ставка 10000", "Кнопка ставки 10,000"),
    BET_20000("Ставка 20000", "Кнопка ставки 20,000"),
    
    // Кнопка удвоения
    DOUBLE_BUTTON("Кнопка удвоить (Х2)", "Кнопка для удвоения текущей ставки"),
    
    // Ставка на дубль
    NO_DOUBLE_BET("Не выпадет дубль", "Кнопка 'Нет' в ставке на дубль");
    
    companion object {
        /**
         * Получить все доступные суммы ставок в порядке возрастания
         */
        fun getAvailableBetAmounts(): List<Int> {
            return listOf(20, 10, 50, 100, 500, 1000, 2000, 5000, 10000, 20000)
        }
        
        /**
         * Получить область ставки по сумме
         */
        fun getBetAreaByAmount(amount: Int): SingleModeAreaType? {
            return when (amount) {
                20 -> BET_10  // Ставка 20 получается через BET_10 + x2
                10 -> BET_10
                50 -> BET_50
                100 -> BET_100
                500 -> BET_500
                1000 -> BET_1000
                2000 -> BET_2000
                5000 -> BET_5000
                10000 -> BET_10000
                20000 -> BET_20000
                else -> null
            }
        }
        
        /**
         * Получить сумму ставки по области
         */
        fun getBetAmountByArea(areaType: SingleModeAreaType): Int? {
            return when (areaType) {
                BET_10 -> 10
                BET_50 -> 50
                BET_100 -> 100
                BET_500 -> 500
                BET_1000 -> 1000
                BET_2000 -> 2000
                BET_5000 -> 5000
                BET_10000 -> 10000
                BET_20000 -> 20000
                else -> null
            }
        }
        
        /**
         * Проверить, является ли область кнопкой ставки
         */
        fun isBetArea(areaType: SingleModeAreaType): Boolean {
            return getBetAmountByArea(areaType) != null
        }
        
        /**
         * Получить области ставок в порядке возрастания
         */
        fun getBetAreas(): List<SingleModeAreaType> {
            return listOf(BET_10, BET_50, BET_100, BET_500, BET_1000, 
                         BET_2000, BET_5000, BET_10000, BET_20000)
        }
    }
}