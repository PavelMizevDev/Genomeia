package io.github.some_example_name.old.systems.genomics

import kotlin.math.*

data class CellPosition(val x: Float, val y: Float)

class Morphogenesis {

    fun placeNewCell(
        xPheromone: Float,
        yPheromone: Float,
        xCellParent: Float,
        yCellParent: Float,
        rParent: Float,
        rNew: Float,
        xNeigh: FloatArray,
        yNeigh: FloatArray,
        rNeigh: FloatArray
    ): CellPosition? {
        //TODO optimize

        val dxP = xPheromone - xCellParent
        val dyP = yPheromone - yCellParent
        val distPhSq = dxP * dxP + dyP * dyP
        if (distPhSq < 1e-8f) return null

        val distPh = sqrt(distPhSq)
        val uxIdeal = dxP / distPh
        val uyIdeal = dyP / distPh

        val sumR = rParent + rNew
        val dMin = 0.6f * sumR
        val dMax = 0.9f * sumR

        // =============================================
        // ФИЛЬТРАЦИЯ — убираем родителя (на всякий случай)
        // =============================================
        val filteredX = mutableListOf<Float>()
        val filteredY = mutableListOf<Float>()
        val filteredR = mutableListOf<Float>()

        for (i in xNeigh.indices) {
            val dx = xNeigh[i] - xCellParent
            val dy = yNeigh[i] - yCellParent
            if (dx * dx + dy * dy > 0.01f) {
                filteredX.add(xNeigh[i])
                filteredY.add(yNeigh[i])
                filteredR.add(rNeigh[i])
            }
        }

        val xNeighF = filteredX.toFloatArray()
        val yNeighF = filteredY.toFloatArray()
        val rNeighF = filteredR.toFloatArray()

        // =============================================
        // ЭТАП 1: Идеальная позиция
        // =============================================
        val rIdeal = distPh.coerceIn(dMin, dMax)
        val txIdeal = xCellParent + rIdeal * uxIdeal
        val tyIdeal = yCellParent + rIdeal * uyIdeal

        if (isValidPosition(txIdeal, tyIdeal, rNew, xNeighF, yNeighF, rNeighF)) {
            return CellPosition(txIdeal, tyIdeal)
        }

        // =============================================
        // ЭТАП 2: Широкий плотный поиск (±120°)
        // =============================================
        val NUM_ANGLES = 72      // очень плотный сектор
        val MAX_OFFSET = 2.0944f // ≈ 120° в радианах
        val NUM_R = 12           // 12 расстояний

        val targetAngle = atan2(uyIdeal, uxIdeal)

        var bestDistSq = Float.MAX_VALUE
        var bestX = 0f
        var bestY = 0f
        var found = false

        for (i in 0 until NUM_ANGLES) {
            val t = i.toFloat() / (NUM_ANGLES - 1)
            val offset = -MAX_OFFSET + 2f * MAX_OFFSET * t
            val angle = targetAngle + offset
            val ux = cos(angle)
            val uy = sin(angle)

            for (j in 0 until NUM_R) {
                val frac = 1f - j.toFloat() / (NUM_R - 1)
                val d = dMin + frac * (dMax - dMin)

                val tx = xCellParent + d * ux
                val ty = yCellParent + d * uy

                if (isValidPosition(tx, ty, rNew, xNeighF, yNeighF, rNeighF)) {
                    val dpSq = (tx - xPheromone) * (tx - xPheromone) +
                        (ty - yPheromone) * (ty - yPheromone)

                    if (dpSq < bestDistSq) {
                        bestDistSq = dpSq
                        bestX = tx
                        bestY = ty
                        found = true
                    }
                }
            }
        }

        // Если ничего не нашли в широком секторе — возвращаем null
        // (значит в этом направлении действительно нет места)
        return if (found) CellPosition(bestX, bestY) else null
    }

    private fun isValidPosition(
        tx: Float, ty: Float, rNew: Float,
        xNeigh: FloatArray, yNeigh: FloatArray, rNeigh: FloatArray
    ): Boolean {
        for (k in xNeigh.indices) {
            val dxn = tx - xNeigh[k]
            val dyn = ty - yNeigh[k]
            val distSq = dxn * dxn + dyn * dyn
            val req = 0.6f * (rNew + rNeigh[k])
            if (distSq < req * req) return false
        }
        return true
    }
}
