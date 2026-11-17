package com.blenderremote.app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "Renderers"

object GlUtil {
    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: " + log)
        }
        return shader
    }

    fun program(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: " + log)
        }
        return program
    }

    fun floatBuffer(values: FloatArray, count: Int = values.size): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(values, 0, count)
        fb.position(0)
        return fb
    }
}

/** Draws the camera image (external OES texture filled by ARCore). */
class BackgroundRenderer {
    var textureId = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        quadCoords = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quadTexCoords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        program = GlUtil.program(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        if (frame.timestamp == 0L) return

        quadCoords.position(0)
        quadTexCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }
}

/** Draws the Blender model (triangle soup, simple two-sided lighting). */
class ModelRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uMv = 0
    private var uColor = 0
    private var vbo = 0
    private var vertexCount = 0

    @Volatile
    private var pending: MeshData? = null
    private var current: MeshData? = null

    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Called on the GL thread when the surface is (re)created. */
    fun create() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal = GLES20.glGetAttribLocation(program, "a_Normal")
        uMvp = GLES20.glGetUniformLocation(program, "u_MVP")
        uMv = GLES20.glGetUniformLocation(program, "u_MV")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        vbo = 0
        vertexCount = 0
        val keep = current
        if (keep != null) pending = keep
    }

    /** Any thread: the mesh is uploaded on the next GL frame. */
    fun setMesh(mesh: MeshData) {
        pending = mesh
    }

    private fun upload(mesh: MeshData) {
        if (vbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vbo = 0
        }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val floats = mesh.vertexCount * 6
        val buffer = GlUtil.floatBuffer(mesh.data, floats)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        vertexCount = mesh.vertexCount
        current = mesh
        Log.i(TAG, "model uploaded: " + mesh.triangles + " triangles")
    }

    fun draw(view: FloatArray, projection: FloatArray, model: FloatArray) {
        val p = pending
        if (p != null) {
            pending = null
            upload(p)
        }
        if (vbo == 0 || vertexCount == 0) return

        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uMv, 1, false, mv, 0)
        GLES20.glUniform4f(uColor, 0.85f, 0.87f, 0.92f, 1f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 u_MVP;
            uniform mat4 u_MV;
            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            varying vec3 v_Normal;
            void main() {
                v_Normal = (u_MV * vec4(a_Normal, 0.0)).xyz;
                gl_Position = u_MVP * a_Position;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            varying vec3 v_Normal;
            uniform vec4 u_Color;
            void main() {
                vec3 n = normalize(v_Normal);
                float d = abs(dot(n, normalize(vec3(0.25, 0.8, 0.5))));
                float light = 0.35 + 0.65 * d;
                gl_FragColor = vec4(u_Color.rgb * light, 1.0);
            }
        """
    }
}
