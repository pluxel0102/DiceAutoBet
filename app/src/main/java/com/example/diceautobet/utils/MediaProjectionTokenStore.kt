package com.example.diceautobet.utils

import android.content.Intent

/**
 * Процессный (in-memory) стор для хранения Intent разрешения MediaProjection.
 * Нельзя надёжно сохранять этот Intent в SharedPreferences/Bundle, поэтому
 * держим его в памяти до завершения процесса. При рестарте приложения требуется
 * заново запросить разрешение у пользователя.
 */
object MediaProjectionTokenStore {
    @Volatile
    private var token: Intent? = null

    fun set(intent: Intent?) {
        token = intent
    }

    fun get(): Intent? = token

    fun clear() {
        token = null
    }
}
