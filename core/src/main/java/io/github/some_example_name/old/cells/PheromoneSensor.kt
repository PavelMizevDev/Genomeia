package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors

class PheromoneSensor(cellTypeId: Int) : Cell(
    defaultColor = blueColors[2],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        if (simulationData.tickCounter % 4 == 0) {
            var impulse = 0f

            val posX = cellEntity.getX(cellIndex)
            val posY = cellEntity.getY(cellIndex)
            //TODO думаю это можно как-то оптимизировать через среднее арифметическое для каждой ячекйи 32*32
            pheromonesManager.findAllPheromonesInPoint(posX, posY) { pheromoneIndex ->
                val dx = posX - pheromoneEntity.x[pheromoneIndex]// + 0.5f
                val dy = posY - pheromoneEntity.y[pheromoneIndex]// + 0.5f
                val distSq = dx * dx + dy * dy

                val a = pheromoneEntity.time[pheromoneIndex]
                val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]

                if (distSq <= radiusSquared) {
                    val result = pheromonesManager.f(distSq, a)
                    impulse += result
                }

                if (impulse >= 1f) {
                    //Отсекаем все что больше 1.0 это позволит не считать кучу источников феромонов
                    impulse = 1f
                    neuronImpulseOutput[cellIndex] = impulse + neuronImpulseInput[cellIndex]
                    return@with
                }
            }

            neuronImpulseOutput[cellIndex] = impulse + neuronImpulseInput[cellIndex]
        }
    }

}
