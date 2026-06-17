package io.github.some_example_name.old.editor.system.logic

import io.github.some_example_name.old.editor.entities.EditorCell

sealed interface UiScreenCommands

class ShowDivideDialog(
    clickedCell: EditorCell,
    clickedIndex: Int,
    newDividedCellPosition: Pair<Float, Float>
): UiScreenCommands

class ShowMutateDialog(
    clickedCell: EditorCell,
    clickedIndex: Int
): UiScreenCommands

class MutateOrDivideDialog(

):UiScreenCommands
