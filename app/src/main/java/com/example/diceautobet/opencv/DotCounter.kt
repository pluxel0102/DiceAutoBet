package com.example.diceautobet.opencv

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt
import android.util.Log

object DotCounter {

    init { require(OpenCVLoader.initDebug()) }

    data class Result(val leftDots: Int, val rightDots: Int, val confidence: Float = 0.0f)

    fun count(srcBmp: Bitmap): Result {
        Log.d("DotCounter", "🔍 Начинаем анализ изображения: ${srcBmp.width}x${srcBmp.height}")
        Log.d("DotCounter", "🖼️ Bitmap конфигурация: ${srcBmp.config}")

        // Отладочное сохранение отключено - используется SimpleDualModeController
        val timestamp = System.currentTimeMillis()

        /* 1⃣ Bitmap → HSV ------------------------------------------------ */
        val hsv = Mat()
        Utils.bitmapToMat(srcBmp, hsv)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_BGR2HSV)

        Log.d("DotCounter", "✅ HSV матрица создана: ${hsv.width()}x${hsv.height()}")

        /* 2⃣ Улучшенная белая маска с множественными порогами ------------ */
        val white = Mat()

        // Основная маска для белых точек (более широкий диапазон)
        Core.inRange(
            hsv,
            Scalar(0.0,   0.0, 120.0),  // Понизил яркость с 150 до 120
            Scalar(180.0, 100.0, 255.0), // Увеличил насыщенность с 80 до 100
            white
        )

        // Дополнительная маска для ярких точек
        val bright = Mat()
        Core.inRange(
            hsv,
            Scalar(0.0,   0.0, 180.0),  // Понизил яркость с 200 до 180
            Scalar(180.0, 70.0, 255.0), // Увеличил насыщенность с 50 до 70
            bright
        )

        // Еще одна маска для серых/светлых точек
        val gray = Mat()
        Core.inRange(
            hsv,
            Scalar(0.0,   0.0, 100.0),  // Еще более низкая яркость
            Scalar(180.0, 120.0, 255.0), // Высокая насыщенность
            gray
        )

        // Объединяем все маски
        Core.bitwise_or(white, bright, white)
        Core.bitwise_or(white, gray, white)

        Log.d("DotCounter", "🎨 Создали HSV маску, применяем морфологические операции...")

        // Морфологические операции для улучшения качества
        Imgproc.medianBlur(white, white, 3)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)
        )
        Imgproc.dilate(white, white, kernel)

        // Убираем шум
        val noiseKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(2.0, 2.0)
        )
        Imgproc.erode(white, white, noiseKernel)

        /* 3⃣ Улучшенное обнаружение контуров ----------------------------- */
        val cnts = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            white, cnts, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        Log.d("DotCounter", "📊 Найдено контуров: ${cnts.size}")

        val validDots = mutableListOf<DotInfo>()
        cnts.forEachIndexed { index, c ->
            val area = Imgproc.contourArea(c)
            if (area < 8) { // Понизил минимальную площадь с 15 до 8
                Log.v("DotCounter", "❌ Контур $index слишком мал: area=$area")
                return@forEachIndexed
            }

            // НОВАЯ ПРОВЕРКА: максимальная площадь для предотвращения ложных срабатываний
            if (area > 200) { // Слишком большие области вряд ли точки
                Log.v("DotCounter", "❌ Контур $index слишком большой: area=$area")
                return@forEachIndexed
            }

            val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            val circ = 4 * Math.PI * area / (peri * peri + 1e-5)

            // Более мягкие критерии для круглости
            if (circ < 0.25) { // Понизил с 0.35 до 0.25
                Log.v("DotCounter", "❌ Контур $index не круглый: circularity=${String.format("%.3f", circ)}")
                return@forEachIndexed
            }

            val m = Imgproc.moments(c)
            if (m.m00 == 0.0) { // Проверяем деление на ноль
                Log.v("DotCounter", "❌ Контур $index: нулевая площадь момента")
                return@forEachIndexed
            }

            val centerX = (m.m10 / m.m00).toFloat()
            val centerY = (m.m01 / m.m00).toFloat()

            // Проверяем, что точка находится в разумных пределах изображения
            if (centerX < 0 || centerX >= srcBmp.width || centerY < 0 || centerY >= srcBmp.height) {
                Log.v("DotCounter", "❌ Точка $index за пределами изображения: ($centerX, $centerY)")
                return@forEachIndexed
            }

            val confidence = calculateDotConfidence(area, circ, peri)
            validDots.add(DotInfo(centerX, centerY, area, confidence))

            Log.d("DotCounter", "✅ Валидная точка $index: (${centerX.toInt()}, ${centerY.toInt()}), area=${area.toInt()}, circ=${String.format("%.3f", circ)}, confidence=${String.format("%.2f", confidence)}")
        }

        if (validDots.isEmpty()) {
            Log.w("DotCounter", "⚠️ Валидных точек не найдено! Возможно, кубики не видны или параметры фильтрации неподходящие")
            hsv.release(); white.release(); bright.release(); gray.release()
            return Result(0, 0, 0.0f)
        }

        Log.d("DotCounter", "✅ Валидных точек найдено: ${validDots.size}")

        /* 4⃣ Улучшенное разделение точек по кубикам -------------------- */
        val (left, right, confidence) = classifyDots(validDots, srcBmp.width)

        Log.d("DotCounter", "🎲 ИТОГОВЫЙ РЕЗУЛЬТАТ: left=$left, right=$right, confidence=${String.format("%.3f", confidence)}")

        // Отладочное сохранение отключено - используется SimpleDualModeController

        hsv.release(); white.release(); bright.release(); gray.release()
        return Result(left, right, confidence)
    }

    private data class DotInfo(
        val x: Float,
        val y: Float,
        val area: Double,
        val confidence: Float
    )

    private fun calculateDotConfidence(area: Double, circularity: Double, perimeter: Double): Float {
        // Вычисляем уверенность в том, что это действительно точка на кубике
        val areaScore = when {
            area < 10 -> 0.4f  // Повысил с 0.3f до 0.4f
            area < 30 -> 0.8f  // Понизил порог с 20 до 10, с 50 до 30
            area < 80 -> 0.9f  // Понизил с 100 до 80
            area < 150 -> 0.7f // Новый диапазон для средних точек
            else -> 0.4f // Слишком большие области всё ещё подозрительны
        }

        val circularityScore = when {
            circularity < 0.3 -> 0.3f  // Повысил с 0.2f до 0.3f
            circularity < 0.5 -> 0.7f  // Понизил порог с 0.4 до 0.3, с 0.6 до 0.5
            circularity < 0.7 -> 0.9f  // Понизил с 0.8 до 0.7
            else -> 1.0f
        }

        // Добавляем бонус за размер в разумных пределах
        val sizeBonus = if (area in 10.0..80.0) 0.1f else 0.0f

        return ((areaScore + circularityScore) / 2.0f + sizeBonus).coerceAtMost(1.0f)
    }

    private fun classifyDots(dots: List<DotInfo>, imageWidth: Int): Triple<Int, Int, Float> {
        when (dots.size) {
            0 -> return Triple(0, 0, 0.0f)
            1 -> {
                val dot = dots[0]
                val isLeft = dot.x < imageWidth / 2f
                Log.d("DotCounter", "🎯 Одна точка: x=${dot.x.toInt()}, imageWidth=$imageWidth, isLeft=$isLeft")

                val leftCount = if (isLeft) 1 else 0
                val rightCount = if (isLeft) 0 else 1

                // Валидация для случая одной точки (всегда валидная, но проверим)
                val validatedLeft = leftCount.coerceIn(0, 6)
                val validatedRight = rightCount.coerceIn(0, 6)

                return Triple(validatedLeft, validatedRight, dot.confidence)
            }
            2 -> {
                // Специальная обработка для 2 точек - проверяем их расположение
                Log.d("DotCounter", "🎯 Две точки: проверяем не анимация ли это...")
                
                // Проверка на анимацию загрузки: точки в горизонтальную линию
                val yDiff = kotlin.math.abs(dots[0].y - dots[1].y)
                val avgY = (dots[0].y + dots[1].y) / 2
                val imageHeight = avgY * 4 // Приблизительная высота изображения
                val verticalVariation = yDiff / imageHeight
                
                if (verticalVariation < 0.15f) { // Точки слишком близко по Y (горизонтальная линия)
                    Log.w("DotCounter", "⚠️ Обнаружена анимация загрузки: точки в горизонтальную линию (yDiff=$yDiff)")
                    return Triple(0, 0, 0.1f) // Возвращаем невалидный результат
                }
                
                Log.d("DotCounter", "✅ Не анимация: точки разбросаны вертикально (yDiff=$yDiff)")
                
                val centerX = imageWidth / 2f
                var leftCount = 0
                var rightCount = 0
                var totalConfidence = 0.0f

                dots.forEach { dot ->
                    if (dot.x < centerX) {
                        leftCount++
                    } else {
                        rightCount++
                    }
                    totalConfidence += dot.confidence
                }

                val avgConfidence = totalConfidence / dots.size

                // Для двух точек даем высокий separationScore, если они по разные стороны от центра
                val separationScore = if (leftCount > 0 && rightCount > 0) 0.9f else 0.6f
                val finalConfidence = avgConfidence * separationScore

                Log.d("DotCounter", "🎯 Простое разделение: left=$leftCount, right=$rightCount, conf=${String.format("%.3f", finalConfidence)}")
                
                val validatedLeft = leftCount.coerceIn(0, 6)
                val validatedRight = rightCount.coerceIn(0, 6)

                return Triple(validatedLeft, validatedRight, finalConfidence)
            }
            else -> {
                Log.d("DotCounter", "🔢 Обрабатываем ${dots.size} точек...")

                // Если слишком много точек, возможно это артефакты - фильтруем по уверенности
                val filteredDots = if (dots.size > 12) { // Больше чем максимум для двух кубиков (6+6)
                    Log.w("DotCounter", "⚠️ Слишком много точек (${dots.size}), фильтруем по уверенности...")
                    dots.sortedByDescending { it.confidence }.take(12) // Берем только 12 лучших
                } else {
                    dots
                }

                // Используем k-means для кластеризации
                val samples = Mat(filteredDots.size, 1, CvType.CV_32F)
                filteredDots.forEachIndexed { i, dot ->
                    samples.put(i, 0, floatArrayOf(dot.x))
                }

                val labels = Mat()
                val centers = Mat()
                val term = TermCriteria(
                    TermCriteria.EPS + TermCriteria.MAX_ITER, 10, 1.0
                )

                try {
                    // 🎯 СТАБИЛИЗАЦИЯ K-MEANS: запускаем несколько раз и берем самый частый результат
                    val results = mutableListOf<Pair<Int, Int>>()
                    
                    repeat(5) { // Делаем 5 попыток K-means
                        val tempLabels = Mat()
                        val tempCenters = Mat()
                        
                        Core.kmeans(
                            samples, 2, tempLabels, term, 3,
                            Core.KMEANS_PP_CENTERS, tempCenters
                        )
                        
                        val tempLeftLbl = if (tempCenters.get(0,0)[0] < tempCenters.get(1,0)[0]) 0 else 1
                        var tempLeft = 0
                        var tempRight = 0
                        
                        for (i in 0 until tempLabels.rows()) {
                            val label = tempLabels.get(i,0)[0].roundToInt()
                            if (label == tempLeftLbl) {
                                tempLeft++
                            } else {
                                tempRight++
                            }
                        }
                        
                        results.add(Pair(tempLeft, tempRight))
                        tempLabels.release()
                        tempCenters.release()
                    }
                    
                    // Находим самый частый результат
                    val mostCommon = results.groupBy { it }.maxByOrNull { it.value.size }?.key
                        ?: results.first()
                    
                    // 🎯 РАННЯЯ ВАЛИДАЦИЯ: корректируем нереалистичные результаты
                    val preValidatedLeft = mostCommon.first.coerceIn(0, 6)
                    val preValidatedRight = mostCommon.second.coerceIn(0, 6)
                    val correctedCommon = Pair(preValidatedLeft, preValidatedRight)
                    
                    if (mostCommon != correctedCommon) {
                        Log.w("DotCounter", "🔧 ПРЕДВАРИТЕЛЬНАЯ КОРРЕКЦИЯ K-means: ${mostCommon.first}:${mostCommon.second} → ${correctedCommon.first}:${correctedCommon.second}")
                    }
                    
                    // Используем самый частый результат для финального вычисления
                    Core.kmeans(
                        samples, 2, labels, term, 3,
                        Core.KMEANS_PP_CENTERS, centers
                    )

                    val leftLbl = if (centers.get(0,0)[0] < centers.get(1,0)[0]) 0 else 1
                    var left = correctedCommon.first  // Используем скорректированный результат
                    var right = correctedCommon.second // Используем скорректированный результат
                    var totalConfidence = 0.0f

                    // НЕ ПЕРЕСЧИТЫВАЕМ! Используем только стабилизированный результат
                    for (i in 0 until labels.rows()) {
                        val dot = filteredDots[i]
                        totalConfidence += dot.confidence
                    }

                    val avgConfidence = totalConfidence / filteredDots.size

                    // Дополнительная валидация: проверяем, что точки действительно разделены
                    val leftDots = filteredDots.filterIndexed { i, _ -> labels.get(i,0)[0].roundToInt() == leftLbl }
                    val rightDots = filteredDots.filterIndexed { i, _ -> labels.get(i,0)[0].roundToInt() != leftLbl }

                    val separationScore = calculateSeparationScore(leftDots, rightDots, imageWidth)
                    val finalConfidence = avgConfidence * separationScore

                    // ПОДРОБНОЕ логирование для отладки
                    Log.d("DotCounter", "🔄 K-means классификация: left=$left, right=$right")
                    Log.d("DotCounter", "📏 Разделение кластеров: leftAvgX=${leftDots.map { it.x }.average().toInt()}, rightAvgX=${rightDots.map { it.x }.average().toInt()}")
                    Log.d("DotCounter", "🎯 Итоговая уверенность: avgConf=${String.format("%.3f", avgConfidence)} * sepScore=${String.format("%.3f", separationScore)} = ${String.format("%.3f", finalConfidence)}")

                    // ДЕТАЛИЗАЦИЯ ПО ТОЧКАМ:
                    leftDots.forEachIndexed { idx, dot ->
                        Log.v("DotCounter", "🔴 Левая точка $idx: x=${dot.x.toInt()}, y=${dot.y.toInt()}, area=${dot.area.toInt()}, conf=${String.format("%.2f", dot.confidence)}")
                    }
                    rightDots.forEachIndexed { idx, dot ->
                        Log.v("DotCounter", "🟠 Правая точка $idx: x=${dot.x.toInt()}, y=${dot.y.toInt()}, area=${dot.area.toInt()}, conf=${String.format("%.2f", dot.confidence)}")
                    }

                    // КРИТИЧНО: Валидация результатов - кубик не может показывать больше 6 точек
                    val validatedLeft = left.coerceIn(0, 6)
                    val validatedRight = right.coerceIn(0, 6)

                    if (left != validatedLeft || right != validatedRight) {
                        Log.w("DotCounter", "⚠️ ВАЛИДАЦИЯ: Исходные результаты ($left, $right) ограничены до ($validatedLeft, $validatedRight)")
                        // Уменьшено снижение уверенности при валидации (с 0.3f до 0.7f)
                        val penalizedConfidence = finalConfidence * 0.7f
                        samples.release(); labels.release(); centers.release()
                        return Triple(validatedLeft, validatedRight, penalizedConfidence)
                    }

                    samples.release(); labels.release(); centers.release()
                    return Triple(validatedLeft, validatedRight, finalConfidence)

                } catch (e: Exception) {
                    // Log.e("DotCounter", "Ошибка k-means кластеризации", e)
                    samples.release(); labels.release(); centers.release()
                    return Triple(0, 0, 0.0f)
                }
            }
        }
    }

    private fun calculateSeparationScore(leftDots: List<DotInfo>, rightDots: List<DotInfo>, imageWidth: Int): Float {
        if (leftDots.isEmpty() || rightDots.isEmpty()) return 0.5f

        val leftAvgX = leftDots.map { it.x }.average()
        val rightAvgX = rightDots.map { it.x }.average()

        val separation = kotlin.math.abs(rightAvgX - leftAvgX) / imageWidth
        val totalDots = leftDots.size + rightDots.size

        // Для малого количества точек (2-4) используем более мягкие критерии
        return if (totalDots <= 4) {
            when {
                separation < 0.05 -> 0.6f // Очень близко, но для малого количества точек приемлемо
                separation < 0.15 -> 0.8f // Близко
                else -> 1.0f // Хорошо разделены
            }
        } else {
            // Для большого количества точек используем строгие критерии
            when {
                separation < 0.1 -> 0.3f // Слишком близко
                separation < 0.2 -> 0.6f
                separation < 0.3 -> 0.8f
                else -> 1.0f // Хорошо разделены
            }
        }
    }

    /**
     * Создаёт изображение с визуализацией найденных точек для отладки
     */
    private fun createVisualizationBitmap(
        originalBitmap: Bitmap,
        dots: List<DotInfo>,
        leftCount: Int,
        rightCount: Int
    ): Bitmap {
        val visualBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(visualBitmap)

        // Настройки для рисования
        val leftPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        val rightPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        val boundsPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }

        val imageWidth = originalBitmap.width
        val imageHeight = originalBitmap.height
        val midPoint = imageWidth / 2f

        // Рисуем границы изображения для диагностики
        canvas.drawRect(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat(), boundsPaint)

        // Рисуем разделительную линию
        canvas.drawLine(midPoint, 0f, midPoint, imageHeight.toFloat(), boundsPaint)

        // Добавляем информацию о размерах изображения
        canvas.drawText("${imageWidth}x${imageHeight}", 10f, 30f, textPaint)
        canvas.drawText("Mid: ${midPoint.toInt()}", 10f, 60f, textPaint)

        // Рисуем найденные точки
        dots.forEachIndexed { index, dot ->
            val isLeft = dot.x < midPoint
            val paint = if (isLeft) leftPaint else rightPaint
            val radius = 15f

            // Рисуем окружность вокруг точки
            canvas.drawCircle(dot.x, dot.y, radius, paint)

            // Подписываем точку с координатами и областью
            val label = "${index+1}:(${dot.x.toInt()},${dot.y.toInt()})"
            canvas.drawText(label, dot.x + 20f, dot.y - 5f, textPaint)
        }

        // Добавляем текст с результатами
        canvas.drawText("L:$leftCount R:$rightCount", 10f, 90f, textPaint)
        canvas.drawText("Total: ${dots.size} points", 10f, 115f, textPaint)

        return visualBitmap
    }
}
