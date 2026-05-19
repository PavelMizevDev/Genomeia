package io.github.some_example_name.old.entities

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.MAXIMUM_PHEROMONE_SPREAD_DIAMETER
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

private const val MAX = 3440

class PheromoneEntity(
    val gridManager: GridManager
): Entity(startMaxAmount = 100) {

    var x = FloatArray(maxAmount)
    var y = FloatArray(maxAmount)
    var time = FloatArray(maxAmount) // время заполнения
    var radiusSquared = FloatArray(maxAmount)
    var emitterIndex = IntArray(maxAmount)
    var color = IntArray(maxAmount)

    val pheromoneMapGrid = Int2ObjectOpenHashMap<IntOpenHashSet>()
    val emitterMap = Int2ObjectOpenHashMap<IntOpenHashSet>()

    fun pack(x: Int, y: Int): Int {
        require(x in 0..MAX && y in 0..MAX)
        return (x shl 12) or y          // 12 бит хватит с запасом (2^12 = 4096 > 3441)
    }

    fun addUnique(key: Int, value: Int): Boolean {
        val set = emitterMap.computeIfAbsent(key) { IntOpenHashSet(3) }
        return set.add(value)
    }

    fun addPheromone(x: Float, y: Float, emitterIndex: Int, type: Int): Int? {
        val exactKey = pack(x.toInt(), y.toInt())

        val isFirstAdded = addUnique(exactKey, emitterIndex)
        if (!isFirstAdded) return null
        val newIndex = add()

        this.x[newIndex] = x
        this.y[newIndex] = y
        this.time[newIndex] = 0f
        this.radiusSquared[newIndex] = 0f
        this.emitterIndex[newIndex] = emitterIndex
        this.color[newIndex] = when (type) {
            0 -> Color.RED
            1 -> Color.GREEN
            2 -> Color.BLUE
            else -> Color.FIREBRICK
        }.toIntBits()

        val bigGridX = x.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
        val bigGridY = y.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
        val gridKey = pack(bigGridX, bigGridY)

        pheromoneMapGrid.computeIfAbsent(gridKey) { IntOpenHashSet(8) }.add(newIndex)

        return newIndex
    }

    fun deletePheromone(pheromoneIndex: Int, pheromoneGeneration: Int) {
        if (isAlive[pheromoneIndex] && getGeneration(pheromoneIndex) == pheromoneGeneration) {

            val px = x[pheromoneIndex]
            val py = y[pheromoneIndex]
            val emitter = emitterIndex[pheromoneIndex]

            val gridX = px.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
            val gridY = py.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
            val gridKey = pack(gridX, gridY)

            pheromoneMapGrid.get(gridKey)?.let { set ->
                set.remove(pheromoneIndex)
                if (set.isEmpty()) {
                    pheromoneMapGrid.remove(gridKey)
                }
            }

            val exactKey = pack(px.toInt(), py.toInt())
            emitterMap.get(exactKey)?.let { set ->
                set.remove(emitter)
                if (set.isEmpty()) {
                    emitterMap.remove(exactKey)
                }
            }

            delete(pheromoneIndex)
//            println("deletePheromone $pheromoneIndex")
            x[pheromoneIndex] = 0f
            y[pheromoneIndex] = 0f
            time[pheromoneIndex] = 0f
            radiusSquared[pheromoneIndex] = 0f
            emitterIndex[pheromoneIndex] = -1
            color[pheromoneIndex] = 0
        } else {
            throw Exception("Not deleted")
        }
    }

    override fun onCopy() {
    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        x.clear()
        y.clear()
        time.clear()
        radiusSquared.clear()
        emitterIndex.clear(-1)
        color.clear()

        pheromoneMapGrid.clear()
        emitterMap.clear()
    }

    override fun onResize(oldMax: Int) {
        x = x.resize()
        y = y.resize()
        time = time.resize()
        radiusSquared = radiusSquared.resize()
        emitterIndex = emitterIndex.resize(-1)
        color = color.resize()
    }
}
