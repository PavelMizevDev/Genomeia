package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.core.DISimulationContainer.chunkSize
import io.github.some_example_name.old.core.DISimulationContainer.gridSize
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.DISimulationContainer.totalChunks
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.systems.simulation.SimulationSystem.Companion.DELTA_SIM_TICK_TIME
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ThreadManager(
    val simulationData: SimulationData
): WorldResizable {

    var executor = Executors.newFixedThreadPool(threadCount)
    val futures = mutableListOf<Future<*>>()

    var isRunning = false

    private fun shutdownExecutor(exec: ExecutorService) {
        exec.shutdown()
        try {
            if (!exec.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                exec.shutdownNow()
            }
        } catch (e: InterruptedException) {
            exec.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun dispose() {
        isRunning = false
        shutdownExecutor(executor)
        futures.clear()
    }

    fun stopSimulationLoop() {
        isRunning = false
    }

    inline fun runUpdateLoop(onUpdateTick: () -> Unit) {
        var lastTime = System.nanoTime()
        var accumulator = 0.0
        var wasMaxSpeed = false

        // для UPS // for UPS
        var updatesThisSecond = 0
        var lastUpsTime = System.nanoTime()

        try {
            while (isRunning) {
                if (simulationData.isPlay) {
                    val currentTime = System.nanoTime()
                    var frameTime = (currentTime - lastTime) / 1_000_000_000.0
                    lastTime = currentTime

                    // Сброс при выходе из maxSpeed // Reset when exiting maxSpeed
                    if (wasMaxSpeed && !simulationData.maxSpeed) {
                        accumulator = 0.0
                        frameTime = 0.0
                        wasMaxSpeed = false
                    }

                    val clampedFrameTime = minOf(frameTime, 0.25)
                    accumulator += clampedFrameTime

                    if (simulationData.maxSpeed) {
                        wasMaxSpeed = true
                        onUpdateTick.invoke()
                        updatesThisSecond++
                    } else {
                        // обычный фиксированный timestep // normal fixed timestep
                        var didUpdate = false
                        while (accumulator >= DELTA_SIM_TICK_TIME) {
                            onUpdateTick.invoke()
                            accumulator -= DELTA_SIM_TICK_TIME
                            updatesThisSecond++
                            didUpdate = true
                        }

                        // спим остаток кадра // we sleep for the rest of the frame
                        if (didUpdate) {
                            val elapsed = (System.nanoTime() - currentTime) / 1_000_000_000.0
                            val sleepTime = DELTA_SIM_TICK_TIME - elapsed
                            if (sleepTime > 0) {
                                try {
                                    Thread.sleep(
                                        (sleepTime * 1000).toLong(),
                                        ((sleepTime * 1_000_000) % 1000_000).toInt()
                                    )
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    }

                    // === UPS вывод каждые 1 сек === UPS output every 1 sec ===
                    val now = System.nanoTime()
                    if ((now - lastUpsTime) >= 1_000_000_000L) {
                        simulationData.ups = updatesThisSecond
                        updatesThisSecond = 0
                        lastUpsTime = now
                    }

                } else {
                    try {
                        Thread.sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            // выход // exit
        }
    }

    inline fun runChunkStage(
        isOdd: Boolean,
        crossinline job: (start: Int, end: Int, threadId: Int) -> Unit
    ) {
        var threadCounter = 0
        val first = if (isOdd) 1 else 0
        for (i in first until totalChunks step 2) {
            val start = i * chunkSize
            val end = if (i == totalChunks - 1) gridSize else (i + 1) * chunkSize
            val threadId = threadCounter++
            futures.add(executor.submit { job(start, end, threadId) })
        }
        futures.forEach { it.get() }   // <- barrier for this stage
        futures.clear()
    }

    override fun resize() {
        val oldExecutor = executor
        executor = Executors.newFixedThreadPool(threadCount)
        shutdownExecutor(oldExecutor)
        futures.clear()
    }
}
