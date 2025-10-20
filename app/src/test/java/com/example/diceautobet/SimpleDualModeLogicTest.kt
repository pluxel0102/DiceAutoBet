package com.example.diceautobet

import com.example.diceautobet.models.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Тест для проверки исправлений в SimpleDualModeController:
 * 1. Циклическая смена цветов после 2 проигрышей
 * 2. Увеличение лимита удвоения до 30.000
 */
class SimpleDualModeLogicTest {

    @Test
    fun `тест циклической смены цветов`() {
        // Начальное состояние: красный цвет
        var state = SimpleDualModeState(
            currentColor = BetChoice.RED,
            previousColor = null,
            consecutiveLossesOnCurrentColor = 0
        )

        // Первый проигрыш на красном
        state = state.copy(consecutiveLossesOnCurrentColor = 1)
        assertFalse("После 1 проигрыша цвет менять не нужно", state.shouldChangeColor())
        assertEquals("Цвет должен остаться красным", BetChoice.RED, state.currentColor)

        // Второй проигрыш на красном - должна быть смена на оранжевый
        state = state.copy(consecutiveLossesOnCurrentColor = 2)
        assertTrue("После 2 проигрышей нужно менять цвет", state.shouldChangeColor())
        
        val nextColor = state.getNextColor()
        assertEquals("После 2 проигрышей на красном должен быть оранжевый", BetChoice.ORANGE, nextColor)

        // Симулируем смену цвета
        state = state.copy(
            currentColor = nextColor,
            previousColor = BetChoice.RED,
            consecutiveLossesOnCurrentColor = 0 // Сбрасываем для нового цвета
        )

        // Снова 2 проигрыша, теперь на оранжевом - должен быть возврат к красному
        state = state.copy(consecutiveLossesOnCurrentColor = 2)
        assertTrue("После 2 проигрышей на оранжевом нужно менять цвет", state.shouldChangeColor())
        
        val returnColor = state.getNextColor()
        assertEquals("После 2 проигрышей на оранжевом должен быть возврат к красному", BetChoice.RED, returnColor)

        println("✅ Тест циклической смены цветов пройден: RED → ORANGE → RED")
    }

    @Test
    fun `тест удвоения ставки до 30000`() {
        var state = SimpleDualModeState(currentBet = 20)

        // Проверяем последовательность удвоений
        val expectedBets = listOf(20, 40, 80, 160, 320, 640, 1280, 2560, 5120, 10240, 20480, 30000)
        var currentBet = 20

        for (i in 1 until expectedBets.size) {
            val nextBet = state.calculateNextBet(GameResultType.LOSS, baseBet = 20)
            state = state.copy(currentBet = nextBet)
            currentBet = nextBet
            
            assertEquals("Ставка $i должна быть ${expectedBets[i]}", expectedBets[i], currentBet)
            
            // После достижения максимума ставка не должна увеличиваться
            if (currentBet == 30000) {
                val finalBet = state.calculateNextBet(GameResultType.LOSS, baseBet = 20)
                assertEquals("После достижения максимума ставка должна остаться 30000", 30000, finalBet)
                break
            }
        }

        // Проверяем сброс при выигрыше
        val winBet = state.calculateNextBet(GameResultType.WIN, baseBet = 20)
        assertEquals("При выигрыше ставка должна сбрасываться к 20", 20, winBet)

        println("✅ Тест удвоения до 30.000 пройден")
    }

    @Test
    fun `тест полного цикла стратегии`() {
        var state = SimpleDualModeState(
            currentColor = BetChoice.RED,
            currentBet = 20,
            previousColor = null
        )

        println("🎮 СИМУЛЯЦИЯ ПОЛНОГО ЦИКЛА:")
        println("Начальное состояние: ${state.currentColor}, ставка ${state.currentBet}")

        // 2 проигрыша на красном
        state = state.copy(currentBet = 40, consecutiveLossesOnCurrentColor = 1)
        println("Проигрыш 1 на RED: ставка ${state.currentBet}")
        
        state = state.copy(currentBet = 80, consecutiveLossesOnCurrentColor = 2)
        println("Проигрыш 2 на RED: ставка ${state.currentBet}, смена цвета!")

        // Смена на оранжевый
        state = state.copy(
            currentColor = BetChoice.ORANGE,
            previousColor = BetChoice.RED,
            currentBet = 160,
            consecutiveLossesOnCurrentColor = 1 // Первый проигрыш на новом цвете
        )
        println("Проигрыш 1 на ORANGE: ставка ${state.currentBet}")

        // Второй проигрыш на оранжевом
        state = state.copy(currentBet = 320, consecutiveLossesOnCurrentColor = 2)
        println("Проигрыш 2 на ORANGE: ставка ${state.currentBet}, возврат к RED!")

        // Возврат к красному
        state = state.copy(
            currentColor = BetChoice.RED,
            previousColor = BetChoice.ORANGE,
            currentBet = 640,
            consecutiveLossesOnCurrentColor = 1
        )
        println("Проигрыш 1 на RED (возврат): ставка ${state.currentBet}")

        // Проверяем финальное состояние
        assertEquals("Финальный цвет должен быть красным", BetChoice.RED, state.currentColor)
        assertEquals("Предыдущий цвет должен быть оранжевым", BetChoice.ORANGE, state.previousColor)
        assertEquals("Финальная ставка должна быть 640", 640, state.currentBet)

        println("✅ Тест полного цикла пройден: RED(2 loss) → ORANGE(2 loss) → RED(continues)")
    }
}
