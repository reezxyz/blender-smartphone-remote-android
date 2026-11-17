package com.blenderremote.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire format shared with the Blender addon (ZeroMQ + msgpack). */
object Protocol {
    const val DEFAULT_PORT = 61520
    const val CHUNK_SIZE = 1_000_000
    const val MODE_CAMERA = "CAMERA"
    const val MODE_OBJECT = "OBJECT"

    private fun text(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** msgpack array of float32 (what the addon reads with msgpack.unpackb). */
    fun packFloats(values: FloatArray): ByteArray {
        val n = values.size
        val headerSize = if (n < 16) 1 else 3
        val buf = ByteBuffer.allocate(headerSize + n * 5).order(ByteOrder.BIG_ENDIAN)
        if (n < 16) {
            buf.put((0x90 or n).toByte())
        } else {
            buf.put(0xdc.toByte())
            buf.putShort(n.toShort())
        }
        for (v in values) {
            buf.put(0xca.toByte())
            buf.putFloat(v)
        }
        return buf.array()
    }

    /**
     * One AR frame, in the same layout the old app used:
     * STATE <mode> NODE <anchor matrix> CAMERA <fov> <camera pose matrix>
     * Matrices are 4x4, column-major (android.opengl.Matrix layout).
     */
    fun frame(
        mode: String,
        anchorMatrix: FloatArray,
        fovLong: Float,
        fovShort: Float,
        cameraPose: FloatArray
    ): List<ByteArray> = listOf(
        text("STATE"), text(mode),
        text("NODE"), packFloats(anchorMatrix),
        text("CAMERA"), packFloats(floatArrayOf(fovLong, fovShort)), packFloats(cameraPose)
    )
}
