package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.yellowColors


class Fat(cellTypeId: Int): Cell(
    defaultColor = yellowColors.first(),
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false,
    isDirected = true
) {
    override fun doOnTick(cellIndex: Int, threadId: Int) = with (cellEntity) {
        neuronImpulseOutput[cellIndex] = energy[cellIndex] / maxEnergy[cellIndex]
    }
}
