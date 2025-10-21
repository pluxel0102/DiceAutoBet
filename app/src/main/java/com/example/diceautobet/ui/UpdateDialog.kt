package com.example.diceautobet.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.example.diceautobet.R
import com.example.diceautobet.databinding.DialogUpdateBinding
import com.example.diceautobet.utils.UpdateInfo

object UpdateDialog {
    /**
     * Показать диалог обновления (современный дизайн 2025)
     */
    fun show(
        context: Context,
        updateInfo: UpdateInfo,
        currentVersion: String,
        onUpdate: () -> Unit,
        onSkip: (() -> Unit)? = null
    ) {
        val binding = DialogUpdateBinding.inflate(LayoutInflater.from(context))
        
        // Устанавливаем версии
        binding.tvVersions.text = "$currentVersion → ${updateInfo.latestVersion}"
        
        // Форматируем changelog
        val formattedChangelog = updateInfo.changelog
            .split("\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                when {
                    line.startsWith("•") -> line
                    line.startsWith("✅") || line.startsWith("🔧") || 
                    line.startsWith("🐛") || line.startsWith("✨") -> line
                    line.trim().startsWith("-") -> "• ${line.trim().substring(1).trim()}"
                    else -> "• $line"
                }
            }
        binding.tvChangelog.text = formattedChangelog
        
        // Показываем предупреждение если обязательное
        if (updateInfo.mandatory) {
            binding.cardMandatory.visibility = View.VISIBLE
        } else {
            binding.cardMandatory.visibility = View.GONE
        }
        
        // Создаём диалог
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setCancelable(!updateInfo.mandatory)
            .create()
        
        // Настраиваем кнопки
        binding.btnUpdate.setOnClickListener {
            dialog.dismiss()
            onUpdate()
        }
        
        if (!updateInfo.mandatory && onSkip != null) {
            binding.btnSkip.visibility = View.VISIBLE
            binding.btnSkip.setOnClickListener {
                dialog.dismiss()
                onSkip()
            }
        } else {
            binding.btnSkip.visibility = View.GONE
        }
        
        dialog.show()
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
