package com.dustline.game

import android.opengl.GLES30 as GL
import android.opengl.Matrix
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Real-time sun shadows (sunlight.castShadow of the HTML: one directional light, orthographic box around the player,
 * 1024 / 2048 depth map, normal bias). Pass 1 (this class) renders the casters depth-only into a depth texture, pass 2 (the main
 * and nature shaders) compares every fragment with it (hardware 2x2 PCF + 5 taps). The box follows the camera and snaps to whole texels
 * so shadows do not shimmer while you walk.
 */
class ShadowMap {
    companion object {
        /** direction TO the sun, same vector as SUN in the fragment shaders (HTML sunlight.position -55, 67.5, 35) */
        private const val SX = -0.5652f
        private const val SY = 0.6935f
        private const val SZ = 0.3596f
    }

    var size = 0; private set
    var half = 115f; private set
    var ready = false; private set
    val lightVP = FloatArray(16)
    private val view = FloatArray(16); private val proj = FloatArray(16)
    private var fbo = 0; private var depthTex = 0; private var prog = 0
    private var uLVP = 0; private var uModel = 0
    private val savedFbo = IntArray(1)
    private val savedVp = IntArray(4)

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; uniform mat4 uLVP; uniform mat4 uModel;
void main(){ gl_Position = uLVP*uModel*vec4(aPos,1.0); }"""
    private val FS = """#version 300 es
precision mediump float;
void main(){}"""

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shadow shader error: " + GL.glGetShaderInfoLog(s))
        return s
    }

    /** GL thread only. Safe to call again after the surface was re-created. */
    fun init() {
        ready = false; size = 0; fbo = 0; depthTex = 0
        try {
            prog = GL.glCreateProgram()
            GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
            GL.glLinkProgram(prog)
            uLVP = GL.glGetUniformLocation(prog, "uLVP"); uModel = GL.glGetUniformLocation(prog, "uModel")
            ensure(1024, 100f)
        } catch (e: Throwable) { ready = false }
    }

    /** (Re)build the depth texture when the quality setting changed. */
    fun ensure(sz: Int, halfSize: Float) {
        half = halfSize
        if (sz == size && ready) return
        if (depthTex != 0) { GL.glDeleteTextures(1, intArrayOf(depthTex), 0); depthTex = 0 }
        if (fbo != 0) { GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        val t = IntArray(1); GL.glGenTextures(1, t, 0); depthTex = t[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, depthTex)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_DEPTH_COMPONENT24, sz, sz, 0, GL.GL_DEPTH_COMPONENT, GL.GL_UNSIGNED_INT, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_COMPARE_MODE, GL.GL_COMPARE_REF_TO_TEXTURE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL)
        val f = IntArray(1); GL.glGenFramebuffers(1, f, 0); fbo = f[0]
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, depthTex, 0)
        GL.glDrawBuffers(1, intArrayOf(GL.GL_NONE), 0)
        GL.glReadBuffer(GL.GL_NONE)
        val ok = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
        size = sz; ready = ok
    }

    /** Start the depth pass around (cx, cy, cz). Everything drawn until end() goes into the depth map. */
    fun begin(cx: Float, cy: Float, cz: Float) {
        val l = sqrt(SX * SX + SY * SY + SZ * SZ); val sx = SX / l; val sy = SY / l; val sz = SZ / l
        // light basis (f = direction the light travels, r / u span the shadow plane) -> snap the centre to whole texels
        val fx = -sx; val fy = -sy; val fz = -sz
        var rx = fy * 0f - fz * 1f; var ry = fz * 0f - fx * 0f; var rz = fx * 1f - fy * 0f   // f x (0,1,0)
        val rl = sqrt(rx * rx + ry * ry + rz * rz); rx /= rl; ry /= rl; rz /= rl
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        val texel = 2f * half / size
        val dr = cx * rx + cy * ry + cz * rz; val du = cx * ux + cy * uy + cz * uz
        val mr = dr - floor(dr / texel) * texel; val mu = du - floor(du / texel) * texel
        val sxc = cx - rx * mr - ux * mu; val syc = cy - ry * mr - uy * mu; val szc = cz - rz * mr - uz * mu
        Matrix.setLookAtM(view, 0, sxc + sx * 150f, syc + sy * 150f, szc + sz * 150f, sxc, syc, szc, 0f, 1f, 0f)
        Matrix.orthoM(proj, 0, -half, half, -half, half, 1f, 300f)
        Matrix.multiplyMM(lightVP, 0, proj, 0, view, 0)

        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
        GL.glGetIntegerv(GL.GL_VIEWPORT, savedVp, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glViewport(0, 0, size, size)
        GL.glColorMask(false, false, false, false)
        GL.glDepthMask(true); GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_POLYGON_OFFSET_FILL); GL.glPolygonOffset(2.5f, 4f)
        useProgram()
    }

    /** (Re)select the depth program (after another program was used in between). */
    fun useProgram() {
        GL.glUseProgram(prog)
        GL.glUniformMatrix4fv(uLVP, 1, false, lightVP, 0)
    }

    fun setModel(m: FloatArray) { GL.glUniformMatrix4fv(uModel, 1, false, m, 0) }

    fun end() {
        GL.glDisable(GL.GL_POLYGON_OFFSET_FILL)
        GL.glColorMask(true, true, true, true)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, savedFbo[0])
        GL.glViewport(savedVp[0], savedVp[1], savedVp[2], savedVp[3])
    }

    /** Bind the depth map on texture unit 1 (the main shaders sample it from there). */
    fun bindForSampling() {
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_2D, depthTex)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }
}
