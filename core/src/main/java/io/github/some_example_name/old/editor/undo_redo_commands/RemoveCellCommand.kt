package io.github.some_example_name.old.editor.undo_redo_commands

import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage

class RemoveCellCommand(
    val clickedCell: EditorCell,
    val parentCell: EditorCell,
    stageInstruction: MutableList<GenomeStage>,
    currentStage: Int
) : UndoRedoCommand(
    stage = currentStage,
    genomeStageInstruction = stageInstruction
) {

    override fun execute() {
        val clickedCellDivideId = parentCell.divide?.id ?: return
        removeIfMutateNull(parentCell.id, stage)

        val deleteCellsIdList = mutableListOf<Int>()
        deleteCellsIdList.add(clickedCellDivideId)

        val deleteEmptyStageLists = mutableListOf<Int>()

        val addDeleteCellsIdList = mutableListOf<Int>()
        for (stage in stage + 1 until genomeStageInstruction.size ) {
            deleteCellsIdList.forEach {
                genomeStageInstruction[stage].cellActions.compute(it) { _, current ->
                    if (current == null) return@compute null
                    if (current.divide != null) {
                        addDeleteCellsIdList.add(current.divide!!.id)
                    }
                    null
                }
                genomeStageInstruction[stage - 1].cellActions.forEach { action ->
                    action.value.divide?.physicalLink?.remove(it)
                    action.value.mutate?.physicalLink?.remove(it)
                }
            }
            deleteCellsIdList.addAll(addDeleteCellsIdList)
            addDeleteCellsIdList.clear()

            if (genomeStageInstruction[stage].cellActions.isEmpty()) {
                deleteEmptyStageLists.add(stage)
            }
        }
        deleteCellsIdList.forEach {
            genomeStageInstruction.last().cellActions.forEach { action ->
                action.value.divide?.physicalLink?.remove(it)
                action.value.mutate?.physicalLink?.remove(it)
            }
        }
        deleteEmptyStageLists.sortedDescending().forEach {
            genomeStageInstruction.removeAt(it)
        }
    }

    fun removeIfMutateNull(cellId: Int, currentStage: Int) {
        genomeStageInstruction[currentStage].cellActions.compute(cellId) { _, current ->
            if (current == null) return@compute null
            current.divide = null
            if (current.mutate == null) null else current
        }
    }
}
