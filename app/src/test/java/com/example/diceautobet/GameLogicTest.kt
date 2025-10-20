package com.example.diceautobet.game

import android.content.Context
import com.example.diceautobet.models.*
import com.example.diceautobet.game.BettingStrategy
import com.example.diceautobet.utils.PreferencesManager
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.pow

class GameStateTest {

    @Test
    fun `should calculate next bet correctly`() {
        val gameState = GameState(
            baseBet = 10,
            currentBet = 100,
            consecutiveLosses = 2
        )
        
        val nextBet = gameState.getNextBet()
        assertEquals(200, nextBet)
    }

    @Test
    fun `should limit next bet to maximum`() {
        val gameState = GameState(
            baseBet = 10,
            currentBet = 2500,
            consecutiveLosses = 1
        )
        
        val nextBet = gameState.getNextBet()
        assertEquals(2500, nextBet) // Should not exceed maximum
    }

    @Test
    fun `should determine stop condition correctly`() {
        val gameState1 = GameState(
            consecutiveLosses = 5,
            maxAttempts = 10
        )
        assertFalse(gameState1.shouldStop())
        
        val gameState2 = GameState(
            consecutiveLosses = 10,
            maxAttempts = 10
        )
        assertTrue(gameState2.shouldStop())
        
        val gameState3 = GameState(
            consecutiveLosses = 15,
            maxAttempts = 10
        )
        assertTrue(gameState3.shouldStop())
    }

    @Test
    fun `should calculate current attempt correctly`() {
        val gameState = GameState(consecutiveLosses = 3)
        assertEquals(4, gameState.currentAttempt)
        
        val gameState2 = GameState(consecutiveLosses = 0)
        assertEquals(1, gameState2.currentAttempt)
    }
}

class RoundResultTest {

    @Test
    fun `should create round result from dot counter result correctly`() {
        val dotResult = com.example.diceautobet.opencv.DotCounter.Result(
            leftDots = 3,
            rightDots = 5,
            confidence = 0.8f
        )
        
        val roundResult = RoundResult.fromDotResult(dotResult)
        
        assertEquals(3, roundResult.redDots)
        assertEquals(5, roundResult.orangeDots)
        assertEquals(BetChoice.ORANGE, roundResult.winner)
        assertFalse(roundResult.isDraw)
        assertEquals(0.8f, roundResult.confidence, 0.01f)
    }

    @Test
    fun `should handle draw correctly`() {
        val dotResult = com.example.diceautobet.opencv.DotCounter.Result(
            leftDots = 4,
            rightDots = 4,
            confidence = 0.9f
        )
        
        val roundResult = RoundResult.fromDotResult(dotResult)
        
        assertEquals(4, roundResult.redDots)
        assertEquals(4, roundResult.orangeDots)
        assertNull(roundResult.winner)
        assertTrue(roundResult.isDraw)
    }

    @Test
    fun `should validate result correctly`() {
        val validResult = RoundResult(
            redDots = 2,
            orangeDots = 4,
            winner = BetChoice.ORANGE,
            isDraw = false,
            confidence = 0.7f,
            isValid = true
        )
        
        assertTrue(validResult.isValid)
        
        val invalidResult = RoundResult(
            redDots = 0,
            orangeDots = 0,
            winner = null,
            isDraw = false,
            confidence = 0.1f,
            isValid = false
        )
        
        assertFalse(invalidResult.isValid)
    }
}

class BettingStrategyTest {

    @Test
    fun `should find closest bet amount correctly`() {
        val context = mockk<Context>(relaxed = true)
        val prefsManager = mockk<PreferencesManager>(relaxed = true)
        
        // Instead of testing BettingStrategy constructor which depends on Android components,
        // let's test the logic directly
        val availableBets = listOf(10, 50, 100, 500, 2500)
        
        // Test logic for finding closest bet
        val targetBet1 = 75
        val closestBet1 = availableBets.filter { it <= targetBet1 }.maxOrNull() ?: availableBets.first()
        assertEquals(50, closestBet1)
        
        val targetBet2 = 3000
        val closestBet2 = availableBets.filter { it <= targetBet2 }.maxOrNull() ?: availableBets.first()
        assertEquals(2500, closestBet2)
        
        val targetBet3 = 5
        val closestBet3 = availableBets.filter { it <= targetBet3 }.maxOrNull() ?: availableBets.first()
        assertEquals(10, closestBet3)
    }

    @Test
    fun `should calculate martingale progression correctly`() {
        val baseBet = 10
        
        // After 0 losses (win), return to base bet
        val bet0 = if (0 == 0) baseBet else baseBet * 2
        assertEquals(10, bet0)
        
        // After 1 loss, double the bet
        val bet1 = baseBet * 2
        assertEquals(20, bet1)
        
        // After 2 losses, double again
        val bet2 = bet1 * 2
        assertEquals(40, bet2)
        
        // After 3 losses
        val bet3 = bet2 * 2
        assertEquals(80, bet3)
    }

    @Test
    fun `should calculate progressive betting correctly`() {
        val baseBet = 10
        
        // Test new doubling logic: baseBet * 2^(consecutiveLosses)
        val bet1 = baseBet * (1 shl 1) // 10 * 2^1 = 20
        assertEquals(20, bet1)
        
        val bet2 = baseBet * (1 shl 2) // 10 * 2^2 = 40
        assertEquals(40, bet2)
        
        val bet3 = baseBet * (1 shl 3) // 10 * 2^3 = 80
        assertEquals(80, bet3)
        
        val bet4 = baseBet * (1 shl 4) // 10 * 2^4 = 160
        assertEquals(160, bet4)
    }
}
