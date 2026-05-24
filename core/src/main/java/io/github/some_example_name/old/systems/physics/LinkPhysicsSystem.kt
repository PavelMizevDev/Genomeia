package io.github.some_example_name.old.systems.physics

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer.linkMaxLength2
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.DISimulationContainer.threadManager
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import kotlin.math.sqrt

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager,
    val diContext: DIContext
): Disposable, WorldResizable {

    private var evenLinkChunkPositionStack = Array(diContext.threadCount) { IntArray(5_000) }
    private var oddLinkChunkPositionStack = Array(diContext.threadCount) { IntArray(5_000) }
    private var evenLinkChunkGeneration = Array(diContext.threadCount) { IntArray(5_000) }
    private var oddLinkChunkGeneration = Array(diContext.threadCount) { IntArray(5_000) }
    private var evenLinkCounter = IntArray(diContext.threadCount)
    private var oddLinkCounter = IntArray(diContext.threadCount)

    // Эти 4 массива — по 2 * threadCount * threadCount * 4 байта
// При threadCount = 16 это всего ~2 КБ. При threadCount = 32 — ~8 КБ. Почти ничего.
    private val localEvenCounts = Array(diContext.threadCount) { IntArray(diContext.threadCount) }
    private val localOddCounts  = Array(diContext.threadCount) { IntArray(diContext.threadCount) }
    private val evenOffsets     = Array(diContext.threadCount) { IntArray(diContext.threadCount) }
    private val oddOffsets      = Array(diContext.threadCount) { IntArray(diContext.threadCount) }

    //TODO вообще не нравится, слишком громоздкое
    fun distributeLinksIndicesAcrossChunks() {
        evenLinkCounter.fill(0)
        oddLinkCounter.fill(0)

        val aliveList = linkEntity.aliveList
        val total = aliveList.size
        if (total == 0) return

        val threadCount = diContext.threadCount
        val perThread = (total + threadCount - 1) / threadCount

        // Обнуляем локальные счётчики
        for (t in 0 until threadCount) {
            localEvenCounts[t].fill(0)
            localOddCounts[t].fill(0)
        }

        // === PHASE 1: Параллельный подсчёт (промежуточные буферы) ===
        threadManager.futures.clear()
        for (t in 0 until threadCount) {
            val start = t * perThread
            val end = minOf(start + perThread, total)
            if (start >= end) continue

            threadManager.futures.add(
                threadManager.executor.submit {
                    for (i in start until end) {
                        val linkIndex = aliveList.getInt(i)
                        val cellIndex = linkEntity.links1[linkIndex]

                        if (cellEntity.isAlive[cellIndex] &&
                            cellEntity.getGeneration(cellIndex) == linkEntity.linksGeneration1[linkIndex]) {

                            val chunk = cellEntity.getGridId(cellIndex) / diContext.chunkSize
                            val remainder = chunk % 2
                            val targetThread = (chunk - remainder) / 2

                            if (targetThread in 0 until threadCount) {
                                if (remainder == 0) {
                                    localEvenCounts[t][targetThread]++
                                } else {
                                    localOddCounts[t][targetThread]++
                                }
                            }
                        }
                    }
                }
            )
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        // === PHASE 2: Вычисляем глобальные counters и offsets (merge) ===
        for (target in 0 until threadCount) {
            var evenSum = 0
            var oddSum = 0
            for (worker in 0 until threadCount) {
                evenOffsets[worker][target] = evenSum
                oddOffsets[worker][target] = oddSum
                evenSum += localEvenCounts[worker][target]
                oddSum += localOddCounts[worker][target]
            }
            evenLinkCounter[target] = evenSum
            oddLinkCounter[target] = oddSum
        }

        // Resize глобальных стеков (делаем один раз перед записью)
        for (target in 0 until threadCount) {
            // even
            val evenNeeded = evenLinkCounter[target]
            if (evenNeeded > evenLinkChunkPositionStack[target].size) {
                val newSize = maxOf(evenNeeded, evenLinkChunkPositionStack[target].size + (evenLinkChunkPositionStack[target].size shr 1))
                evenLinkChunkPositionStack[target] = evenLinkChunkPositionStack[target].copyOf(newSize)
                evenLinkChunkGeneration[target] = evenLinkChunkGeneration[target].copyOf(newSize)
            }
            // odd
            val oddNeeded = oddLinkCounter[target]
            if (oddNeeded > oddLinkChunkPositionStack[target].size) {
                val newSize = maxOf(oddNeeded, oddLinkChunkPositionStack[target].size + (oddLinkChunkPositionStack[target].size shr 1))
                oddLinkChunkPositionStack[target] = oddLinkChunkPositionStack[target].copyOf(newSize)
                oddLinkChunkGeneration[target] = oddLinkChunkGeneration[target].copyOf(newSize)
            }
        }

        // === PHASE 3: Параллельная запись в глобальные стеки ===
        for (t in 0 until threadCount) {
            val start = t * perThread
            val end = minOf(start + perThread, total)
            if (start >= end) continue

            threadManager.futures.add(
                threadManager.executor.submit {
                    val localEvenIdx = IntArray(threadCount)
                    val localOddIdx = IntArray(threadCount)

                    for (i in start until end) {
                        val linkIndex = aliveList.getInt(i)
                        val cellIndex = linkEntity.links1[linkIndex]

                        if (cellEntity.isAlive[cellIndex] &&
                            cellEntity.getGeneration(cellIndex) == linkEntity.linksGeneration1[linkIndex]) {

                            val chunk = cellEntity.getGridId(cellIndex) / diContext.chunkSize
                            val remainder = chunk % 2
                            val targetThread = (chunk - remainder) / 2

                            if (targetThread !in 0 until threadCount) continue

                            val generation = linkEntity.getGeneration(linkIndex)

                            if (remainder == 0) {
                                val idx = evenOffsets[t][targetThread] + localEvenIdx[targetThread]
                                evenLinkChunkPositionStack[targetThread][idx] = linkIndex
                                evenLinkChunkGeneration[targetThread][idx] = generation
                                localEvenIdx[targetThread]++
                            } else {
                                val idx = oddOffsets[t][targetThread] + localOddIdx[targetThread]
                                oddLinkChunkPositionStack[targetThread][idx] = linkIndex
                                oddLinkChunkGeneration[targetThread][idx] = generation
                                localOddIdx[targetThread]++
                            }
                        }
                    }
                }
            )
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }
/*    fun distributeLinksIndicesAcrossChunks() {
        evenLinkCounter.fill(0)
        oddLinkCounter.fill(0)

        linkEntity.aliveList.forEach { linkIndex ->
            val cellIndex = linkEntity.links1[linkIndex]
            if (cellEntity.isAlive[cellIndex] && cellEntity.getGeneration(cellIndex) == linkEntity.linksGeneration1[linkIndex]) {
                val chunk = cellEntity.getGridId(cellIndex) / diContext.chunkSize
                val remainder = chunk % 2
                val threadId = (chunk - remainder) / 2

                val stacks =
                    if (remainder == 0) evenLinkChunkPositionStack else oddLinkChunkPositionStack
                val generation =
                    if (remainder == 0) evenLinkChunkGeneration else oddLinkChunkGeneration
                val counters = if (remainder == 0) evenLinkCounter else oddLinkCounter

                val index = counters[threadId]
                var arr = stacks[threadId]
                var genArr = generation[threadId]

                if (index >= arr.size) {
                    arr = arr.copyOf(arr.size + (arr.size shr 1))
                    genArr = genArr.copyOf(genArr.size + (genArr.size shr 1))
                    stacks[threadId] = arr
                    generation[threadId] = genArr
                }

                arr[index] = linkIndex
                genArr[index] = linkEntity.getGeneration(linkIndex)
                counters[threadId] = index + 1
            }
        }
    }*/

    fun iterateLinks() {
        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<oddLinkCounter[chunk]) {
                    processLink(
                        linkIndex = oddLinkChunkPositionStack[chunk][i],
                        generation = oddLinkChunkGeneration[chunk][i],
                        threadId = chunk
                    )
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<evenLinkCounter[chunk]) {
                    processLink(
                        linkIndex = evenLinkChunkPositionStack[chunk][i],
                        generation = evenLinkChunkGeneration[chunk][i],
                        threadId = chunk
                    )
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    private fun processLink(
        linkIndex: Int,
        generation: Int,
        threadId: Int
    ) = with(particleEntity) {
        with(cellEntity) {
            with(linkEntity) {
                if (!isAlive[linkIndex] || linkEntity.getGeneration(linkIndex) != generation) return@with
                val linkCellA = links1[linkIndex]
                val linkCellB = links2[linkIndex]

                val linkAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[linkIndex]
                val linkBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[linkIndex]

                if (linkAIsDead || linkBIsDead) {
                    linkEntity.reinitParentLink(linkIndex)
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
                    )
                    if (linkAIsDead && !linkBIsDead) {
                        isOnEdge[linkCellB] = true
                        setColor(linkCellB, Color.RED.toIntBits())
                    }
                    if (linkBIsDead && !linkAIsDead) {
                        isOnEdge[linkCellA] = true
                        setColor(linkCellA, Color.RED.toIntBits())
                    }
                    return@with
                }

                val naturalLength = linksNaturalLength[linkIndex]

                if (naturalLength < 0) {
                    cellSystem.transportNeuralSignal(linkIndex, linkCellA, linkCellB)
                    return@with
                }

                val linkParticleA = getParticleIndex(linkCellA)
                val linkParticleB = getParticleIndex(linkCellB)

                val dx = x[linkParticleA] - x[linkParticleB]
                val dy = y[linkParticleA] - y[linkParticleB]

                cellSystem.transportEnergy(linkCellA, linkCellB)
                cellSystem.transportNeuralSignal(linkIndex, linkCellA, linkCellB)
                val parentCellA = parentIndex[linkCellA]
                val parentCellB = parentIndex[linkCellB]
                if (linkCellA == parentCellB) {
                    cellSystem.processCellAngle(linkCellB, linkCellA)
                }
                if (linkCellB == parentCellA) {
                    cellSystem.processCellAngle(linkCellA, linkCellB)
                }
                val distanceSquared = dx * dx + dy * dy

                if (distanceSquared > linkMaxLength2) {
                    linkEntity.reinitParentLink(linkIndex)
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
                    )
                    isOnEdge[linkCellB] = true
                    setColor(linkCellB, Color.RED.toIntBits())
                    isOnEdge[linkCellA] = true
                    setColor(linkCellA, Color.RED.toIntBits())
                    return
                }

                val stiffnessA = cellStiffness[linkParticleA]
                val stiffnessB = cellStiffness[linkParticleB]
                val stiffness = 2 * stiffnessA * stiffnessB / (stiffnessA + stiffnessB)

                if (distanceSquared < 0) throw Exception("distanceSquared < 0, distanceSquared = $distanceSquared")
                val dist = sqrt(distanceSquared)

                val degreeOfShorteningA = degreeOfShortening[linkCellA]
                val degreeOfShorteningB = degreeOfShortening[linkCellB]
                val degreeOfShortening = 2 * degreeOfShorteningA * degreeOfShorteningB / (degreeOfShorteningA + degreeOfShorteningB)

                val force = (dist - naturalLength * degreeOfShortening) * stiffness

                val dirX = dx / dist
                val dirY = dy / dist

                // Spring dampening
                val dvx = vx[linkParticleA] - vx[linkParticleB]
                val dvy = vy[linkParticleA] - vy[linkParticleB]

                val dampeningConstant = 0.3f
                val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

                val fx = (force + dampeningForce) * dirX
                val fy = (force + dampeningForce) * dirY

                vx[linkParticleB] += fx
                vy[linkParticleB] += fy
                vx[linkParticleA] -= fx
                vy[linkParticleA] -= fy

                if (parentIndex[linkCellA] == -1) reinitParentIndex(linkCellA, linkCellB)
                if (parentIndex[linkCellB] == -1) reinitParentIndex(linkCellB, linkCellA)
            }
        }
    }

    override fun dispose() {

    }

    override fun resize() {
        //evenLinkChunkPositionStack...
        TODO("Not yet implemented")
    }
}
