package com.example.diceautobet.utils

import android.util.Log

/**
 * Обёртка над стандартным Log для автоматического дублирования в FileLogger
 * Использовать вместо android.util.Log во всех классах
 */
object LogHelper {
    
    /**
     * Debug лог
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        FileLogger.d(tag, message)
    }
    
    /**
     * Info лог
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        FileLogger.i(tag, message)
    }
    
    /**
     * Warning лог
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        FileLogger.w(tag, message)
    }
    
    /**
     * Error лог
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
        FileLogger.e(tag, message)
    }
    
    /**
     * Error лог с исключением
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        FileLogger.e(tag, "$message: ${throwable.message}\n${throwable.stackTraceToString()}")
    }
    
    /**
     * Verbose лог
     */
    fun v(tag: String, message: String) {
        Log.v(tag, message)
        FileLogger.v(tag, message)
    }
}
