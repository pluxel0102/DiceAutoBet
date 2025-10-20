package com.example.diceautobet.models

/**
 * Результат валидации настроек
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String = ""
) {
    companion object {
        fun success() = ValidationResult(true)
        fun error(message: String) = ValidationResult(false, message)
    }
}