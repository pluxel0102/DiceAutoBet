package com.example.diceautobet.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import android.util.Log
import com.example.diceautobet.utils.CoordinateUtils

class AutoClickService : AccessibilityService() {

    companion object {
        private var instance: AutoClickService? = null

        fun getInstance(): AutoClickService? = instance

        fun performClick(x: Int, y: Int, callback: (Boolean) -> Unit = {}) {
            Log.d("AutoClickService", "Выполняем клик по координатам: x=$x, y=$y")
            instance?.clickDirect(x, y, callback)
        }

        fun performClick(rect: Rect, callback: (Boolean) -> Unit = {}) {
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            Log.d("AutoClickService", "Выполняем клик по центру области: rect=$rect, centerX=$centerX, centerY=$centerY")
            performClick(centerX, centerY, callback)
        }

        /**
         * Пытается найти видимый Accessibility-элемент с заданным текстом/описанием и выполнить ACTION_CLICK.
         * Возвращает true, если клик был выполнен.
         */
        fun clickByText(vararg captions: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false

            for (caption in captions) {
                // Поиск по тексту и по contentDescription
                val nodes = root.findAccessibilityNodeInfosByText(caption) ?: emptyList()
                if (nodes.isEmpty()) continue
                for (node in nodes) {
                    if (node.isClickable) {
                        val performed = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d("AutoClickService", "clickByText(): caption='$caption', performed=$performed")
                        return performed
                    }
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Мы не обрабатываем события, только выполняем клики
    }

    override fun onInterrupt() {
        // Вызывается при прерывании сервиса
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun clickDirect(x: Int, y: Int, callback: (Boolean) -> Unit) {
        Log.d("AutoClickService", "=== Прямой КЛИК ===")

        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }

        val stroke = GestureDescription.StrokeDescription(
            clickPath,
            0,
            100L
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("AutoClickService", "Клик выполнен успешно")
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d("AutoClickService", "Клик отменен")
                callback(false)
            }
        }, null)
    }

    private fun click(x: Int, y: Int, callback: (Boolean) -> Unit) {
        // Делегируем выполнение клика новому методу
        clickDirect(x, y, callback)
    }

    // Метод для серии кликов с задержкой
    fun performClickSequence(
        clicks: List<Pair<Rect, Long>>, // Список пар (область, задержка после клика)
        onComplete: () -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            for ((rect, delay) in clicks) {
                val clicked = suspendCancellableCoroutine<Boolean> { cont ->
                    performClick(rect) { success ->
                        cont.resume(success) {}
                    }
                }

                if (!clicked) {
                    onComplete()
                    return@launch
                }

                if (delay > 0) {
                    delay(delay)
                }
            }
            onComplete()
        }
    }
}