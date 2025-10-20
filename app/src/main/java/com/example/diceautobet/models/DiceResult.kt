package com.example.diceautobet.models

/**
 * Результат анализа кубиков
 */
data class DiceResult(
    val leftDots: Int,
    val rightDots: Int,
    val confidence: Float = 1.0f,
    val isDraw: Boolean = false
) {
    val isValid: Boolean
        get() = leftDots in 1..6 && rightDots in 1..6 && confidence > 0.3f
}