package io.github.some_example_name.old.systems.simulation

class SimulationData {
    var isRestart = false
    var isFinish = false
    var tickCounter = 0
    var timeSimulation = 0f

    //Пока вынес все глобальные переменные сюда, но кажется это все не совсем к месту
    var currentGenomeIndex = 0

    var isPlay = true
    var maxSpeed = false

    var targetUPS: Int = 60
        set(value) {
            field = value.coerceIn(1, 1000)
        }
    var ups = 60
    var selectedCellIndex = -1

    var showControllerKeys = false
    val controllerKeyTouched = BooleanArray(19)

    fun clear() {
        isRestart = false
        isFinish = false
        tickCounter = 0
        timeSimulation = 0f
        selectedCellIndex = -1
    }

}
