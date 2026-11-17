package com.blenderremote.app

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import kotlin.math.sqrt

/** Triangle soup: 6 floats per vertex (position xyz, normal xyz). */
class MeshData(val data: FloatArray, val vertexCount: Int, val bounds: FloatArray) {
    val triangles: Int get() = vertexCount / 3
}

private class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial)
    var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Small OBJ reader (v / vn / f only). Runs on a background thread. */
object ObjLoader {

    fun load(file: File): MeshData {
        val pos = FloatList(4096)
        val nor = FloatList(4096)
        val out = FloatList(1 shl 16)
        val pi = IntArray(64)
        val ni = IntArray(64)

        BufferedReader(FileReader(file), 1 shl 16).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.length > 2 && line[1] == ' ' && line[0] == 'v') {
                    parseFloats(line, 2, pos, 3)
                } else if (line.length > 3 && line[0] == 'v' && line[1] == 'n' && line[2] == ' ') {
                    parseFloats(line, 3, nor, 3)
                } else if (line.length > 2 && line[0] == 'f' && line[1] == ' ') {
                    addFace(line, pos, nor, out, pi, ni)
                }
                line = reader.readLine()
            }
        }

        if (out.size == 0) throw IllegalStateException("The model has no faces")

        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        )
        var i = 0
        while (i + 2 < pos.size) {
            for (k in 0..2) {
                val v = pos.data[i + k]
                if (v < b[k]) b[k] = v
                if (v > b[3 + k]) b[3 + k] = v
            }
            i += 3
        }
        return MeshData(out.toArray(), out.size / 6, b)
    }

    private fun parseFloats(line: String, start: Int, out: FloatList, count: Int) {
        var i = start
        var n = 0
        val len = line.length
        while (n < count && i < len) {
            while (i < len && line[i] == ' ') i++
            var j = i
            while (j < len && line[j] != ' ') j++
            if (j > i) {
                out.add(line.substring(i, j).toFloat())
                n++
            }
            i = j
        }
    }

    private fun resolve(index: Int, count: Int): Int = if (index < 0) count + index else index - 1

    private fun addFace(line: String, pos: FloatList, nor: FloatList, out: FloatList, pi: IntArray, ni: IntArray) {
        val tokens = line.substring(2).trim().split(' ')
        var n = 0
        val posCount = pos.size / 3
        val norCount = nor.size / 3
        for (t in tokens) {
            if (t.isEmpty() || n >= pi.size) continue
            val parts = t.split('/')
            val p = parts[0].toIntOrNull() ?: return
            pi[n] = resolve(p, posCount)
            ni[n] = if (parts.size >= 3 && parts[2].isNotEmpty()) {
                resolve(parts[2].toIntOrNull() ?: 0, norCount)
            } else {
                -1
            }
            if (pi[n] < 0 || pi[n] >= posCount) return
            if (ni[n] >= norCount) ni[n] = -1
            n++
        }
        if (n < 3) return
        for (k in 1 until n - 1) {
            emit(pos, nor, out, pi[0], ni[0], pi[k], ni[k], pi[k + 1], ni[k + 1])
        }
    }

    private fun emit(
        pos: FloatList, nor: FloatList, out: FloatList,
        a: Int, na: Int, b: Int, nb: Int, c: Int, nc: Int
    ) {
        val p = pos.data
        val ax = p[a * 3]; val ay = p[a * 3 + 1]; val az = p[a * 3 + 2]
        val bx = p[b * 3]; val by = p[b * 3 + 1]; val bz = p[b * 3 + 2]
        val cx = p[c * 3]; val cy = p[c * 3 + 1]; val cz = p[c * 3 + 2]

        // flat normal, used when the file has no normals
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var fx = uy * vz - uz * vy
        var fy = uz * vx - ux * vz
        var fz = ux * vy - uy * vx
        val len = sqrt(fx * fx + fy * fy + fz * fz)
        if (len > 0f) {
            fx /= len; fy /= len; fz /= len
        }

        put(out, ax, ay, az, nor, na, fx, fy, fz)
        put(out, bx, by, bz, nor, nb, fx, fy, fz)
        put(out, cx, cy, cz, nor, nc, fx, fy, fz)
    }

    private fun put(
        out: FloatList, x: Float, y: Float, z: Float,
        nor: FloatList, ni: Int, fx: Float, fy: Float, fz: Float
    ) {
        out.add(x); out.add(y); out.add(z)
        if (ni >= 0) {
            out.add(nor.data[ni * 3]); out.add(nor.data[ni * 3 + 1]); out.add(nor.data[ni * 3 + 2])
        } else {
            out.add(fx); out.add(fy); out.add(fz)
        }
    }
}
