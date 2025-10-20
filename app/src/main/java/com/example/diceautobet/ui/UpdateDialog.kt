package com.example.diceautobet.ui

import android.app.AlertDialog
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import com.example.diceautobet.utils.UpdateInfo

object UpdateDialog {
    /**
     * Показать диалог обновления
     */
    fun show(
        context: Context,
        updateInfo: UpdateInfo,
        currentVersion: String,
        onUpdate: () -> Unit,
        onSkip: (() -> Unit)? = null
    ) {
        val message = """
            📱 Текущая версия: $currentVersion
            ✨ Новая версия: ${updateInfo.latestVersion}
            
            📝 Что нового:
            ${updateInfo.changelog}
            
            ${if (updateInfo.mandatory) "⚠️ Это обязательное обновление!" else ""}
        """.trimIndent()

        val messageView = TextView(context).apply {
            text = message
            setPadding(50, 40, 50, 40)
            textSize = 16f
            movementMethod = ScrollingMovementMethod()
            maxLines = 10
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("🎉 Обновление доступно")
            .setView(messageView)
            .setPositiveButton("📥 Обновить") { _, _ -> onUpdate() }
            .setCancelable(!updateInfo.mandatory)

        if (!updateInfo.mandatory && onSkip != null) {
            builder.setNegativeButton("⏭️ Пропустить") { _, _ -> onSkip() }
            builder.setNeutralButton("❌ Отмена") { dialog, _ -> dialog.dismiss() }
        }

        val dialog = builder.create()
        dialog.show()

        // Увеличиваем размер кнопок для удобства
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.textSize = 16f
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.textSize = 16f
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.textSize = 16f
    }

    /**
     * Показать диалог проверки обновлений с ошибкой
     */
    fun showError(context: Context, error: String) {
        AlertDialog.Builder(context)
            .setTitle("❌ Ошибка")
            .setMessage("Не удалось проверить обновления:\n$error")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }
}
