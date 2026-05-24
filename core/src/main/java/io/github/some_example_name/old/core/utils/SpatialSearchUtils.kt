package io.github.some_example_name.old.core.utils

import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList

fun GridManager.collectParticles(gridX: Int, gridY: Int, radius: Int = 3): IntArray {
    val list = IntArrayList()
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            val arr = getParticles(gridX + dx, gridY + dy)
            for (v in arr) list.add(v)
        }
    }
    return list.toIntArray()
}

inline fun GridManager.forEachParticle(
    gridX: Int,
    gridY: Int,
    radius: Int,
    action: (Int) -> Unit
) {
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {

            val arr = getParticles(gridX + dx, gridY + dy)

            for (i in arr.indices) {
                action(arr[i])
            }
        }
    }
}

inline fun GridManager.forEachParticleOnRadius(
    gridX: Int,
    gridY: Int,
    radius: Int,
    action: (Int) -> Unit
) {
    // верх и низ
    for (dx in -radius..radius) {

        var arr = getParticles(gridX + dx, gridY - radius)
        for (i in arr.indices) {
            action(arr[i])
        }

        if (radius != 0) {
            arr = getParticles(gridX + dx, gridY + radius)
            for (i in arr.indices) {
                action(arr[i])
            }
        }
    }

    // лево и право без углов
    for (dy in -radius + 1 until radius) {

        var arr = getParticles(gridX - radius, gridY + dy)
        for (i in arr.indices) {
            action(arr[i])
        }

        if (radius != 0) {
            arr = getParticles(gridX + radius, gridY + dy)
            for (i in arr.indices) {
                action(arr[i])
            }
        }
    }
}
