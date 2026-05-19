package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.blueColors

class PheromoneEmitter(cellTypeId: Int) : Cell(
    defaultColor = blueColors[3],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val lastImpulse = specialEntity.getPheromoneEmitterLastImpulse(cellIndex)
        if (lastImpulse > 0) return@with
        val impulse = neuronImpulseOutput[cellIndex]
        if (impulse <= 0) return@with

        worldCommandsManager.worldCommandBuffer[threadId].push(
            type = WorldCommandType.ADD_PHEROMONE,
            ints = intArrayOf(getParticleIndex(cellIndex), 2 /*todo pheromone type*/),
            floats = floatArrayOf(getX(cellIndex), getY(cellIndex))
        )
    }
}
