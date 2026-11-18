package com.blenderremote.app

import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * Real "glassmorphism": what is behind a panel (camera image + model) is blurred
 * on the GPU and drawn inside the panel with a light tint, a rim highlight and a
 * soft sheen. The Android views on top only draw text and icons.
 *
 * Per frame, after the scene is drawn:
 *  1. the screen is copied into [sceneTex]
 *  2. it is blurred in 4 small passes (quarter resolution, gaussian H/V twice)
 *  3. each registered rectangle is drawn with the blurred picture.
 *
 * Rectangles come from the UI thread as a flat array (see [setRects]).
 */
class GlassRenderer {

    companion object {
        private const val TAG = "Glass"
        const val FLOATS_PER_RECT = 10   // x, y, w, h, radius, r, g, b, a, base (1 = blur, 0 = tint only)

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform vec4 uRect;      // x, y, w, h in pixels (GL origin: bottom left)
            uniform vec2 uScreen;    // pixels
            uniform float uPad;      // extra pixels around the rectangle (room for the shadow)
            varying vec2 vUv;
            varying vec2 vLocal;
            varying vec2 vInvScreen;
            void main() {
                vec2 size = uRect.zw + vec2(2.0 * uPad);
                vec2 px = uRect.xy - vec2(uPad) + aPos * size;
                vUv = px / uScreen;
                vLocal = aPos * size - vec2(uPad);
                vInvScreen = 1.0 / uScreen;
                gl_Position = vec4(px / uScreen * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val BLUR_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTex, vUv) * 0.2270270270;
                c += texture2D(uTex, vUv + uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(uTex, vUv - uDir * 1.3846153846) * 0.3162162162;
                c += texture2D(uTex, vUv + uDir * 3.2307692308) * 0.0702702703;
                c += texture2D(uTex, vUv - uDir * 3.2307692308) * 0.0702702703;
                gl_FragColor = vec4(c.rgb, 1.0);
            }
        """

        private const val GLASS_FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uBlur;
            uniform vec2 uSize;
            uniform float uRadius;
            uniform vec4 uTint;
            uniform float uBase;
            varying vec2 vUv;
            varying vec2 vLocal;
            varying vec2 vInvScreen;

            float roundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + vec2(r);
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            // direction pointing out of the rounded box at p
            vec2 boxNormal(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + vec2(r);
                vec2 g = vec2(0.0, 1.0);
                if (q.x > 0.0 && q.y > 0.0) {
                    g = normalize(q);
                } else if (q.x > q.y) {
                    g = vec2(1.0, 0.0);
                }
                return g * vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
            }

            void main() {
                vec2 hs = uSize * 0.5;
                vec2 p = vLocal - hs;
                float d = roundBox(p, hs, uRadius);
                float mask = 1.0 - smoothstep(-1.0, 0.5, d);

                // 1 at the top-left corner, 0 at the bottom-right one
                float corner = clamp(0.5 + 0.5 * ((-p.x / uSize.x) + (p.y / uSize.y)), 0.0, 1.0);
                // thin bright rim + soft light just inside it
                float rim = 1.0 - smoothstep(0.0, 1.7, -d);
                float glowEdge = 1.0 - smoothstep(0.0, 12.0, -d);
                float light = rim * (0.28 + 0.55 * corner) + glowEdge * (0.03 + 0.07 * corner);

                if (uBase < 0.5) {
                    // lighter layer on top of another glass (buttons inside a panel)
                    gl_FragColor = vec4(uTint.rgb + light, clamp(uTint.a + light * 0.8, 0.0, 1.0) * mask);
                    return;
                }

                // the picture behind bends towards the centre near the edge (refraction),
                // with a tiny colour split like real glass
                float band = 1.0 - smoothstep(0.0, 22.0, -d);
                vec2 off = boxNormal(p, hs, uRadius) * band * band * 14.0 * vInvScreen;
                vec3 bg;
                bg.r = texture2D(uBlur, vUv - off).r;
                bg.g = texture2D(uBlur, vUv - off * 1.08).g;
                bg.b = texture2D(uBlur, vUv - off * 1.16).b;

                float lum = dot(bg, vec3(0.299, 0.587, 0.114));
                bg = mix(vec3(lum), bg, 1.45);          // vivid colours behind the glass
                bg *= 0.86;                              // a little darker: white text stays readable
                vec3 col = mix(bg, uTint.rgb, uTint.a);
                // warm bottom-right glow, bright top-left, so the glass is never a flat grey
                col += mix(vec3(0.075, 0.035, 0.012), vec3(0.11), corner);
                col += light;

                // soft drop shadow outside
                float ds = roundBox(p + vec2(0.0, 5.0), hs, uRadius);
                float sh = (1.0 - smoothstep(-4.0, 16.0, ds)) * 0.30 * (1.0 - mask);
                float a = mask + sh;
                gl_FragColor = vec4(col * mask / max(a, 0.0001), a);
            }
        """
    }

    @Volatile
    private var rects = FloatArray(0)

    private var blurProgram = 0
    private var glassProgram = 0
    private var blurPos = 0
    private var glassPos = 0
    private var quad: FloatBuffer? = null

    private var width = 1
    private var height = 1
    private var bw = 1
    private var bh = 1
    private var sceneTex = 0
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var ready = false
    private var failed = false

    /** Called from the UI thread. Layout: [FLOATS_PER_RECT] floats per rectangle, y measured from the top. */
    fun setRects(values: FloatArray) {
        rects = values
    }

    fun create() {
        ready = false
        failed = false
        try {
            blurProgram = GlUtil.program(VERTEX, BLUR_FRAGMENT)
            glassProgram = GlUtil.program(VERTEX, GLASS_FRAGMENT)
            blurPos = GLES20.glGetAttribLocation(blurProgram, "aPos")
            glassPos = GLES20.glGetAttribLocation(glassProgram, "aPos")
            quad = GlUtil.floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            sceneTex = 0
            tex[0] = 0
            tex[1] = 0
            fbo[0] = 0
            fbo[1] = 0
        } catch (e: Exception) {
            Log.e(TAG, "glass setup failed", e)
            failed = true
        }
    }

    private fun makeTexture(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        return t[0]
    }

    fun resize(w: Int, h: Int) {
        if (failed) return
        width = maxOf(w, 1)
        height = maxOf(h, 1)
        bw = maxOf(width / 4, 1)
        bh = maxOf(height / 4, 1)
        try {
            release()
            sceneTex = makeTexture(width, height)
            for (i in 0..1) {
                tex[i] = makeTexture(bw, bh)
                val f = IntArray(1)
                GLES20.glGenFramebuffers(1, f, 0)
                fbo[i] = f[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw RuntimeException("framebuffer incomplete: " + status)
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            ready = true
        } catch (e: Exception) {
            Log.e(TAG, "glass resize failed", e)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            failed = true
            ready = false
        }
    }

    private fun release() {
        if (sceneTex != 0) GLES20.glDeleteTextures(1, intArrayOf(sceneTex), 0)
        for (i in 0..1) {
            if (tex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(tex[i]), 0)
            if (fbo[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo[i]), 0)
            tex[i] = 0
            fbo[i] = 0
        }
        sceneTex = 0
    }

    private fun bindQuad(posHandle: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quad)
    }

    private fun blurPass(source: Int, target: Int, dx: Float, dy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[target])
        GLES20.glViewport(0, 0, bw, bh)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (source < 0) sceneTex else tex[source])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uDir"), dx, dy)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Draws all glass rectangles over what is already on the screen. */
    fun render() {
        val list = rects
        if (!ready || failed || list.isEmpty()) return

        // 1. copy the finished scene
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex)
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(false)

        // 2. blur
        GLES20.glUseProgram(blurProgram)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(blurProgram, "uRect"), 0f, 0f, 1f, 1f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uScreen"), 1f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blurProgram, "uPad"), 0f)
        bindQuad(blurPos)
        blurPass(-1, 0, 3f / width, 0f)
        blurPass(0, 1, 0f, 1.2f / bh)
        GLES20.glDisableVertexAttribArray(blurPos)

        // 3. glass rectangles
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(glassProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(glassProgram, "uBlur"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(glassProgram, "uScreen"), width.toFloat(), height.toFloat())
        val uRect = GLES20.glGetUniformLocation(glassProgram, "uRect")
        val uSize = GLES20.glGetUniformLocation(glassProgram, "uSize")
        val uRadius = GLES20.glGetUniformLocation(glassProgram, "uRadius")
        val uTint = GLES20.glGetUniformLocation(glassProgram, "uTint")
        val uBase = GLES20.glGetUniformLocation(glassProgram, "uBase")
        val uPad = GLES20.glGetUniformLocation(glassProgram, "uPad")
        bindQuad(glassPos)

        val count = list.size / FLOATS_PER_RECT
        for (i in 0 until count) {
            val o = i * FLOATS_PER_RECT
            val w = list[o + 2]
            val h = list[o + 3]
            val x = list[o]
            val yGl = height - (list[o + 1] + h)       // view y is measured from the top
            GLES20.glUniform4f(uRect, x, yGl, w, h)
            GLES20.glUniform2f(uSize, w, h)
            GLES20.glUniform1f(uRadius, minOf(list[o + 4], minOf(w, h) / 2f))
            GLES20.glUniform4f(uTint, list[o + 5], list[o + 6], list[o + 7], list[o + 8])
            GLES20.glUniform1f(uBase, list[o + 9])
            GLES20.glUniform1f(uPad, if (list[o + 9] > 0.5f) 18f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(glassPos)

        // leave the state the way the scene drawing expects it
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
