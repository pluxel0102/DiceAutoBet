package com.example.diceautobet.game

import com.example.diceautobet.models.*
import com.example.diceautobet.validation.GameValidator
import com.example.diceautobet.validation.ValidationResult
import org.junit.Test
import org.junit.Assert.*

class GameValidatorTest {

    @Test
    fun `should validate bet amount correctly`() {
        // Valid bet amounts
        assertTrue(GameValidator.validateBetAmount(10).isValid)
        assertTrue(GameValidator.validateBetAmount(50).isValid)
        assertTrue(GameValidator.validateBetAmount(100).isValid)
        assertTrue(GameValidator.validateBetAmount(500).isValid)
        assertTrue(GameValidator.validateBetAmount(2500).isValid)
        
        // Invalid bet amounts
        assertFalse(GameValidator.validateBetAmount(0).isValid)
        assertFalse(GameValidator.validateBetAmount(-10).isValid)
        assertFalse(GameValidator.validateBetAmount(25).isValid)
        assertFalse(GameValidator.validateBetAmount(3000).isValid)
    }

    @Test
    fun `should validate game state correctly`() {
        // Valid game state
        val validState = GameState(
            baseBet = 10,
            currentBet = 50,  // Use valid bet amount
            maxAttempts = 5,
            consecutiveLosses = 2
        )
        val result = GameValidator.validateGameState(validState)
        assertTrue("Expected valid state to be valid: ${if (result is ValidationResult.Error) result.message else ""}", result.isValid)
        
        // Invalid game state - negative base bet
        val invalidState1 = validState.copy(baseBet = -10)
        assertFalse(GameValidator.validateGameState(invalidState1).isValid)
        
        // Invalid game state - too many attempts
        val invalidState2 = validState.copy(maxAttempts = 25)
        assertFalse(GameValidator.validateGameState(invalidState2).isValid)
        
        // Invalid game state - negative consecutive losses
        val invalidState3 = validState.copy(consecutiveLosses = -1)
        assertFalse(GameValidator.validateGameState(invalidState3).isValid)
    }

    @Test
    fun `should validate round result correctly`() {
        // Valid round result
        val validResult = RoundResult(
            redDots = 3,
            orangeDots = 5,
            winner = BetChoice.ORANGE,
            isDraw = false,
            confidence = 0.8f,
            isValid = true
        )
        assertTrue(GameValidator.validateRoundResult(validResult).isValid)
        
        // Invalid result - negative dots
        val invalidResult1 = validResult.copy(redDots = -1)
        assertFalse(GameValidator.validateRoundResult(invalidResult1).isValid)
        
        // Invalid result - too many dots
        val invalidResult2 = validResult.copy(orangeDots = 7)
        assertFalse(GameValidator.validateRoundResult(invalidResult2).isValid)
        
        // Invalid result - draw with winner
        val invalidResult3 = validResult.copy(
            redDots = 3,
            orangeDots = 3,
            winner = BetChoice.RED,
            isDraw = true
        )
        assertFalse(GameValidator.validateRoundResult(invalidResult3).isValid)
    }

    @Test
    fun `should validate timings correctly`() {
        // Valid timings
        assertTrue(GameValidator.validateTimings(500L, 5000L).isValid)
        assertTrue(GameValidator.validateTimings(1000L, 10000L).isValid)
        
        // Invalid timings - too short
        assertFalse(GameValidator.validateTimings(50L, 5000L).isValid)
        assertFalse(GameValidator.validateTimings(500L, 500L).isValid)
        
        // Invalid timings - too long
        assertFalse(GameValidator.validateTimings(15000L, 5000L).isValid)
        assertFalse(GameValidator.validateTimings(500L, 35000L).isValid)
    }

    @Test
    fun `should validate result history correctly`() {
        // Empty history should be valid
        assertTrue(GameValidator.validateResultHistory(emptyList()).isValid)
        
        // Normal history should be valid
        val normalHistory = listOf(
            RoundResult(2, 4, BetChoice.ORANGE, false, 0.8f, true),
            RoundResult(5, 1, BetChoice.RED, false, 0.7f, true),
            RoundResult(3, 3, null, true, 0.9f, true)
        )
        assertTrue(GameValidator.validateResultHistory(normalHistory).isValid)
        
        // History with too many consecutive same results should be invalid
        val suspiciousHistory = List(10) {
            RoundResult(3, 3, null, true, 0.5f, true)
        }
        assertFalse(GameValidator.validateResultHistory(suspiciousHistory).isValid)
        
        // History with low confidence should be invalid
        val lowConfidenceHistory = List(5) {
            RoundResult(2, 4, BetChoice.ORANGE, false, 0.1f, true)
        }
        assertFalse(GameValidator.validateResultHistory(lowConfidenceHistory).isValid)
    }
}
