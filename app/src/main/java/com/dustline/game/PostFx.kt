package com.dustline.game

import android.opengl.GLES30 as GL
import kotlin.math.max
import kotlin.math.tan

/**
 * Post-processing (port of the DUSTLINE ULTRA PIPELINE of the HTML: bloom + ambient occlusion, switched off step by step by an auto-performance governor).
 * The 3D scene is drawn into an off-screen colour + depth texture, then:
 *   1. bright-pass -> quarter-res separable blur  (bloom)
 *   2. depth-only screen-space AO at half resolution (12 taps, noise-rotated, range-checked)
 *   3. composite onto the screen: scene * AO + bloom
 * Everything is optional: if the framebuffer cannot be made, [ready] stays false and the game draws straight to the screen as before.
 */
class PostFx {
    @Volatile var ready = false
    private var w = 0; private var h = 0
    private var sceneFbo = 0; private var sceneTex = 0; private var depthTex = 0
    private val bloomFbo = IntArray(2); private val bloomTex = IntArray(2); private var bw = 0; private var bh = 0
    private var aoFbo = 0; private var aoTex = 0; private var aw = 0; private var ah = 0
    private var vao = 0
    private var pBright = 0; private var pBlur = 0; private var pAo = 0; private var pComp = 0
    private var failed = false
    private val saved = IntArray(1)

    private val VS = """#version 300 es
out vec2 vUv;
void main(){ vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); vUv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }"""
    private val FS_BRIGHT = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D tS; out vec4 o;
void main(){ vec3 c = texture(tS, vUv).rgb; float l = dot(c, vec3(0.299, 0.587, 0.114)); o = vec4(c * smoothstep(0.86, 1.02, max(l, max(c.r, max(c.g, c.b)) * 0.93)), 1.0); }"""
    private val FS_BLUR = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D tS; uniform vec2 uDir; out vec4 o;
void main(){
  vec3 s = texture(tS, vUv).rgb * 0.2270270;
  s += (texture(tS, vUv + uDir * 1.3846154).rgb + texture(tS, vUv - uDir * 1.3846154).rgb) * 0.3162162;
  s += (texture(tS, vUv + uDir * 3.2307692).rgb + texture(tS, vUv - uDir * 3.2307692).rgb) * 0.0702703;
  o = vec4(s, 1.0);
}"""
    private val FS_AO = """#version 300 es
precision highp float;
in vec2 vUv; uniform sampler2D tD; uniform vec2 uNF; uniform vec2 uRes; uniform float uFocal; out vec4 o;
float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNF.x * uNF.y / (uNF.y + uNF.x - z * (uNF.y - uNF.x)); }
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main(){
  float d = texture(tD, vUv).r;
  if (d >= 0.9999) { o = vec4(1.0); return; }
  float z = lin(d);
  float rpx = clamp(0.9 * uFocal / z, 2.0, 36.0);            // 0.9 m sphere projected to pixels
  float ang = hash(gl_FragCoord.xy) * 6.2831853;
  float occ = 0.0;
  for (int i = 0; i < 12; i++) {
    float fi = float(i);
    float a = ang + fi * 2.3999632;
    float r = rpx * sqrt((fi + 0.5) / 12.0);
    vec2 uv = vUv + vec2(cos(a), sin(a)) * r / uRes;
    float zs = lin(texture(tD, uv).r);
    float dz = z - zs;                                        // > 0: the neighbour is in front of this pixel
    occ += clamp(dz - 0.06, 0.0, 0.5) * (1.0 - smoothstep(0.7, 1.5, dz));
  }
  float ao = clamp(1.0 - occ / 12.0 * 2.2, 0.35, 1.0);
  o = vec4(vec3(ao), 1.0);
}"""
    private val FS_COMP = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D tS; uniform sampler2D tB; uniform sampler2D tA; uniform float uBloom; uniform float uAo; out vec4 o;
void main(){
  vec3 c = texture(tS, vUv).rgb;
  float ao = mix(1.0, texture(tA, vUv).r, uAo);
  c = c * ao + texture(tB, vUv).rgb * uBloom;
  o = vec4(c, 1.0);
}"""

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.e("PostFx", GL.glGetShaderInfoLog(s)); return 0 }
        return s
    }
    private fun program(fs: String): Int {
        val v = compile(GL.GL_VERTEX_SHADER, VS); val f = compile(GL.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GL.glCreateProgram(); GL.glAttachShader(p, v); GL.glAttachShader(p, f); GL.glLinkProgram(p)
        val ok = IntArray(1); GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.e("PostFx", GL.glGetProgramInfoLog(p)); return 0 }
        return p
    }
    private fun colorTex(tw: Int, th: Int): Int {
        val t = IntArray(1); GL.glGenTextures(1, t, 0); GL.glBindTexture(GL.GL_TEXTURE_2D, t[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, tw, th, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return t[0]
    }
    private fun fboWith(tex: Int, depth: Int = 0): Int {
        val f = IntArray(1); GL.glGenFramebuffers(1, f, 0); GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, f[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex, 0)
        if (depth != 0) GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_TEXTURE_2D, depth, 0)
        return f[0]
    }
    private fun release() {
        if (sceneFbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
        if (aoFbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(aoFbo), 0)
        for (i in 0..1) if (bloomFbo[i] != 0) GL.glDeleteFramebuffers(1, intArrayOf(bloomFbo[i]), 0)
        val ts = intArrayOf(sceneTex, depthTex, aoTex, bloomTex[0], bloomTex[1]).filter { it != 0 }.toIntArray()
        if (ts.isNotEmpty()) GL.glDeleteTextures(ts.size, ts, 0)
        sceneFbo = 0; aoFbo = 0; bloomFbo[0] = 0; bloomFbo[1] = 0; sceneTex = 0; depthTex = 0; aoTex = 0; bloomTex[0] = 0; bloomTex[1] = 0
    }

    /** GL context was lost / recreated: forget every GL name. */
    fun lost() { sceneFbo = 0; aoFbo = 0; bloomFbo[0] = 0; bloomFbo[1] = 0; sceneTex = 0; depthTex = 0; aoTex = 0; bloomTex[0] = 0; bloomTex[1] = 0
        pBright = 0; pBlur = 0; pAo = 0; pComp = 0; vao = 0; w = 0; h = 0; ready = false; failed = false }

    /** (Re)creates the off-screen targets for this surface size. Returns false if the device cannot do it. */
    fun ensure(sw: Int, sh: Int): Boolean {
        if (failed) return false
        if (ready && sw == w && sh == h) return true
        try {
            GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, saved, 0)
            if (pComp == 0) {
                pBright = program(FS_BRIGHT); pBlur = program(FS_BLUR); pAo = program(FS_AO); pComp = program(FS_COMP)
                if (pBright == 0 || pBlur == 0 || pAo == 0 || pComp == 0) { failed = true; return false }
                val v = IntArray(1); GL.glGenVertexArrays(1, v, 0); vao = v[0]
            }
            release()
            w = sw; h = sh
            sceneTex = colorTex(w, h)
            val dt = IntArray(1); GL.glGenTextures(1, dt, 0); depthTex = dt[0]; GL.glBindTexture(GL.GL_TEXTURE_2D, depthTex)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_DEPTH_COMPONENT24, w, h, 0, GL.GL_DEPTH_COMPONENT, GL.GL_UNSIGNED_INT, null)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE); GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            sceneFbo = fboWith(sceneTex, depthTex)
            val st = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)
            bw = max(1, w / 4); bh = max(1, h / 4); aw = max(1, w / 2); ah = max(1, h / 2)
            for (i in 0..1) { bloomTex[i] = colorTex(bw, bh); bloomFbo[i] = fboWith(bloomTex[i]) }
            aoTex = colorTex(aw, ah); aoFbo = fboWith(aoTex)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, saved[0])
            if (st != GL.GL_FRAMEBUFFER_COMPLETE) { failed = true; ready = false; release(); return false }
            ready = true
            return true
        } catch (e: Throwable) { failed = true; ready = false; try { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0) } catch (_: Throwable) { }; return false }
    }

    /** Start drawing the scene into the off-screen target. */
    fun begin() {
        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, saved, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, sceneFbo); GL.glViewport(0, 0, w, h)
    }

    private fun tri() { GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3) }

    /** Post passes + composite to the screen. [fovDeg] is the vertical field of view of the camera that drew the scene. */
    fun finish(nearP: Float, farP: Float, fovDeg: Float, bloom: Boolean, ao: Boolean) {
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_CULL_FACE); GL.glDisable(GL.GL_BLEND); GL.glDepthMask(false)
        GL.glBindVertexArray(vao)
        if (bloom) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[0]); GL.glViewport(0, 0, bw, bh)
            GL.glUseProgram(pBright); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, sceneTex)
            GL.glUniform1i(GL.glGetUniformLocation(pBright, "tS"), 0); tri()
            GL.glUseProgram(pBlur); GL.glUniform1i(GL.glGetUniformLocation(pBlur, "tS"), 0)
            val ud = GL.glGetUniformLocation(pBlur, "uDir")
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[1]); GL.glBindTexture(GL.GL_TEXTURE_2D, bloomTex[0]); GL.glUniform2f(ud, 1f / bw, 0f); tri()
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[0]); GL.glBindTexture(GL.GL_TEXTURE_2D, bloomTex[1]); GL.glUniform2f(ud, 0f, 1f / bh); tri()
            // a second, wider round at the same cost class: blur the result once more
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[1]); GL.glBindTexture(GL.GL_TEXTURE_2D, bloomTex[0]); GL.glUniform2f(ud, 2f / bw, 0f); tri()
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, bloomFbo[0]); GL.glBindTexture(GL.GL_TEXTURE_2D, bloomTex[1]); GL.glUniform2f(ud, 0f, 2f / bh); tri()
        }
        if (ao) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, aoFbo); GL.glViewport(0, 0, aw, ah)
            GL.glUseProgram(pAo); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, depthTex)
            GL.glUniform1i(GL.glGetUniformLocation(pAo, "tD"), 0)
            GL.glUniform2f(GL.glGetUniformLocation(pAo, "uNF"), nearP, farP)
            GL.glUniform2f(GL.glGetUniformLocation(pAo, "uRes"), aw.toFloat(), ah.toFloat())
            // pixels per metre at 1 m distance, measured in the half-res AO target
            val focal = 0.5f * ah / tan(Math.toRadians(fovDeg / 2.0)).toFloat()
            GL.glUniform1f(GL.glGetUniformLocation(pAo, "uFocal"), focal)
            tri()
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, saved[0]); GL.glViewport(0, 0, w, h)
        GL.glUseProgram(pComp)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, sceneTex)
        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, if (bloom) bloomTex[0] else sceneTex)
        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, if (ao) aoTex else sceneTex)
        GL.glUniform1i(GL.glGetUniformLocation(pComp, "tS"), 0); GL.glUniform1i(GL.glGetUniformLocation(pComp, "tB"), 1); GL.glUniform1i(GL.glGetUniformLocation(pComp, "tA"), 2)
        GL.glUniform1f(GL.glGetUniformLocation(pComp, "uBloom"), if (bloom) 0.35f else 0f)
        GL.glUniform1f(GL.glGetUniformLocation(pComp, "uAo"), if (ao) 1f else 0f)
        tri()
        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
        GL.glBindVertexArray(0)
        GL.glDepthMask(true); GL.glEnable(GL.GL_DEPTH_TEST)
    }
}
