package com.dustline.game

import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the ULTRA NATURE pack (Nature.kt): one instanced draw call per 170 m tile and kind, frustum culled,
 * 3 LOD tiers (full model -> low-poly model -> hidden) exactly like DLF.lod() of the HTML, wind sway in the vertex shader (swayMat).
 * Same lighting / fog formula as the map shader of GameRenderer.
 */
class NatureGl(private val res: Nature.Result) {
    private class GeoGl(val vbo: Int, val count: Int)
    private class TileGl(val t: Nature.Tile, val ibo: Int, val vaoHi: Int, val vaoLo: Int, val hasLo: Boolean)

    private var prog = 0
    private var uVP = 0; private var uEye = 0; private var uFog = 0; private var uT = 0; private var uAmp = 0
    private val tiles = ArrayList<TileGl>()
    private val planes = FloatArray(24)
    /** number of tiles drawn last frame (debug) */
    var drawn = 0
    private var shOn = false; private var shVP = FloatArray(16); private var shTexel = 1f / 1024f
    private var uShOn = 0; private var uShVP = 0; private var uShTex = 0; private var uShMap = 0
    private var sprog = 0; private var suLVP = 0; private var suT = 0; private var suAmp = 0
    /** call before draw(): shadow receiving on / off, light matrix and 1 / depth-map size */
    fun setShadow(on: Boolean, lightVP: FloatArray, texel: Float) { shOn = on; shVP = lightVP; shTexel = texel }

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec3 aCol;
layout(location=3) in vec4 iM0; layout(location=4) in vec4 iM1; layout(location=5) in vec4 iM2; layout(location=6) in vec4 iM3; layout(location=7) in vec3 iTint;
uniform mat4 uVP; uniform vec3 uEye; uniform float uT; uniform float uAmp;
out vec3 vN; out vec3 vCol; out float vD; out vec3 vW;
void main(){
  mat4 M = mat4(iM0,iM1,iM2,iM3);
  vec3 p = aPos;
  float swp = iM3.x*0.21 + iM3.z*0.17 + uT*1.5; float swh = p.y*p.y;
  p.x += sin(swp + p.y*0.4)*uAmp*swh; p.z += cos(swp*0.8)*uAmp*0.6*swh;
  vec4 w = M*vec4(p,1.0); gl_Position = uVP*w;
  vN = mat3(M)*aNor; vCol = aCol*iTint; vD = distance(w.xyz,uEye); vW = w.xyz;
}"""
    private val FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vN; in vec3 vCol; in float vD; in vec3 vW;
uniform float uFog;
uniform sampler2DShadow uShMap; uniform mat4 uShVP; uniform float uShOn; uniform float uShTex;
float shadowAt(vec3 n, vec3 wp){
  if(uShOn<0.5) return 1.0;
  vec4 sc = uShVP*vec4(wp + n*0.12, 1.0);
  vec3 p = sc.xyz/sc.w*0.5+0.5;
  if(p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0||p.z>1.0) return 1.0;
  float b = 0.0003; float t = uShTex;
  float s = texture(uShMap, vec3(p.xy, p.z-b)) + texture(uShMap, vec3(p.xy+vec2(t,0.0), p.z-b)) + texture(uShMap, vec3(p.xy-vec2(t,0.0), p.z-b))
          + texture(uShMap, vec3(p.xy+vec2(0.0,t), p.z-b)) + texture(uShMap, vec3(p.xy-vec2(0.0,t), p.z-b));
  s *= 0.2;
  float e = smoothstep(0.0, 0.08, min(min(p.x,1.0-p.x), min(p.y,1.0-p.y)));
  return mix(1.0, s, e);
}
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
out vec4 o;
void main(){
  vec3 n = normalize(vN);
  float sh = shadowAt(n, vW);
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = vCol*(hemi*0.62 + vec3(1.0,0.91,0.78)*max(dot(n,SUN),0.0)*0.95*sh) + vec3(0.12,0.115,0.10)*vCol;
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, vec3(0.74,0.84,0.93), f*f), 1.0);
}"""

    private val SVS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=3) in vec4 iM0; layout(location=4) in vec4 iM1; layout(location=5) in vec4 iM2; layout(location=6) in vec4 iM3;
uniform mat4 uLVP; uniform float uT; uniform float uAmp;
void main(){
  mat4 M = mat4(iM0,iM1,iM2,iM3); vec3 p = aPos;
  float swp = iM3.x*0.21 + iM3.z*0.17 + uT*1.5; float swh = p.y*p.y;
  p.x += sin(swp + p.y*0.4)*uAmp*swh; p.z += cos(swp*0.8)*uAmp*0.6*swh;
  gl_Position = uLVP*(M*vec4(p,1.0));
}"""

    /** Shadow pass: pines, oaks, palms and boulders within `radius` of (cx, cz) (bushes do not cast, like the HTML). Caller re-selects its own program afterwards. */
    fun drawShadow(lightVP: FloatArray, cx: Float, cz: Float, radius: Float, time: Float) {
        if (sprog == 0 || tiles.isEmpty()) return
        GL.glUseProgram(sprog)
        GL.glUniformMatrix4fv(suLVP, 1, false, lightVP, 0); GL.glUniform1f(suT, time)
        var lastKind = -1
        for (t in tiles) {
            val tl = t.t
            if (tl.kind == Nature.BUSH) continue
            val dx = tl.cx - cx; val dz = tl.cz - cz; val rr = radius + tl.radius
            if (dx * dx + dz * dz > rr * rr) continue
            val g = (if (t.hasLo) res.lo[tl.kind] else res.hi[tl.kind]) ?: continue
            if (tl.kind != lastKind) { GL.glUniform1f(suAmp, Nature.SWAY[tl.kind]); lastKind = tl.kind }
            GL.glBindVertexArray(if (t.hasLo) t.vaoLo else t.vaoHi)
            GL.glDrawArraysInstanced(GL.GL_TRIANGLES, 0, g.verts, tl.count)
        }
        GL.glBindVertexArray(0)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Nature shader error: " + GL.glGetShaderInfoLog(s))
        return s
    }

    private fun buffer(data: FloatArray): Int {
        val id = IntArray(1); GL.glGenBuffers(1, id, 0)
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()); bb.asFloatBuffer().put(data); bb.position(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, id[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, data.size * 4, bb, GL.GL_STATIC_DRAW)
        return id[0]
    }

    private fun makeVao(geo: GeoGl, inst: Int): Int {
        val v = IntArray(1); GL.glGenVertexArrays(1, v, 0); GL.glBindVertexArray(v[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, geo.vbo)
        for (loc in 0..2) { GL.glEnableVertexAttribArray(loc); GL.glVertexAttribPointer(loc, 3, GL.GL_FLOAT, false, 36, loc * 12) }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, inst)
        for (c in 0..3) { val loc = 3 + c; GL.glEnableVertexAttribArray(loc); GL.glVertexAttribPointer(loc, 4, GL.GL_FLOAT, false, 76, c * 16); GL.glVertexAttribDivisor(loc, 1) }
        GL.glEnableVertexAttribArray(7); GL.glVertexAttribPointer(7, 3, GL.GL_FLOAT, false, 76, 64); GL.glVertexAttribDivisor(7, 1)
        GL.glBindVertexArray(0)
        return v[0]
    }

    /** Must run on the GL thread (onSurfaceCreated). */
    fun init() {
        tiles.clear()
        prog = GL.glCreateProgram()
        GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
        GL.glLinkProgram(prog)
        uVP = GL.glGetUniformLocation(prog, "uVP"); uEye = GL.glGetUniformLocation(prog, "uEye"); uFog = GL.glGetUniformLocation(prog, "uFog")
        uT = GL.glGetUniformLocation(prog, "uT"); uAmp = GL.glGetUniformLocation(prog, "uAmp")
        uShOn = GL.glGetUniformLocation(prog, "uShOn"); uShVP = GL.glGetUniformLocation(prog, "uShVP")
        uShTex = GL.glGetUniformLocation(prog, "uShTex"); uShMap = GL.glGetUniformLocation(prog, "uShMap")
        try {   // depth-only variant (instanced + same wind sway) for the shadow pass
            sprog = GL.glCreateProgram()
            GL.glAttachShader(sprog, compile(GL.GL_VERTEX_SHADER, SVS)); GL.glAttachShader(sprog, compile(GL.GL_FRAGMENT_SHADER, "#version 300 es\nprecision mediump float;\nvoid main(){}"))
            GL.glLinkProgram(sprog)
            suLVP = GL.glGetUniformLocation(sprog, "uLVP"); suT = GL.glGetUniformLocation(sprog, "uT"); suAmp = GL.glGetUniformLocation(sprog, "uAmp")
        } catch (e: Throwable) { sprog = 0 }
        val hi = arrayOfNulls<GeoGl>(Nature.KINDS); val lo = arrayOfNulls<GeoGl>(Nature.KINDS)
        for (k in 0 until Nature.KINDS) {
            res.hi[k]?.let { hi[k] = GeoGl(buffer(it.data), it.verts) }
            res.lo[k]?.let { lo[k] = GeoGl(buffer(it.data), it.verts) }
        }
        for (t in res.tiles) {
            val h = hi[t.kind] ?: continue
            val ibo = buffer(t.data)
            val l = lo[t.kind]
            tiles.add(TileGl(t, ibo, makeVao(h, ibo), if (l != null) makeVao(l, ibo) else 0, l != null))
        }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    private fun extractPlanes(m: FloatArray) {
        // row i of a column-major matrix = m[i], m[4+i], m[8+i], m[12+i]
        for (p in 0 until 6) {
            val axis = p / 2; val sign = if (p % 2 == 0) 1f else -1f
            var a = m[3] + sign * m[axis]; var b = m[7] + sign * m[4 + axis]; var c = m[11] + sign * m[8 + axis]; var d = m[15] + sign * m[12 + axis]
            val l = sqrt(a * a + b * b + c * c).let { if (it == 0f) 1f else it }
            a /= l; b /= l; c /= l; d /= l
            planes[p * 4] = a; planes[p * 4 + 1] = b; planes[p * 4 + 2] = c; planes[p * 4 + 3] = d
        }
    }

    private fun inView(cx: Float, cy: Float, cz: Float, r: Float): Boolean {
        for (p in 0 until 6) if (planes[p * 4] * cx + planes[p * 4 + 1] * cy + planes[p * 4 + 2] * cz + planes[p * 4 + 3] < -r) return false
        return true
    }

    /**
     * quality = vegScale (0.6 low / 0.85 mid / 1 high), zoom = scope zoom (>= 1), fogK = fog stretch of the main shader.
     * Caller must re-bind its own program afterwards.
     */
    fun draw(vp: FloatArray, ex: Float, ey: Float, ez: Float, fogK: Float, time: Float, quality: Float, zoom: Float) {
        if (prog == 0 || tiles.isEmpty()) return
        extractPlanes(vp)
        GL.glUseProgram(prog)
        GL.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GL.glUniform3f(uEye, ex, ey, ez); GL.glUniform1f(uFog, fogK); GL.glUniform1f(uT, time)
        GL.glUniform1f(uShOn, if (shOn) 1f else 0f); GL.glUniformMatrix4fv(uShVP, 1, false, shVP, 0); GL.glUniform1f(uShTex, shTexel); GL.glUniform1i(uShMap, 1)
        val far = min(900f * fogK * 1.04f, 2000f)
        var lastKind = -1; drawn = 0
        for (t in tiles) {
            val tl = t.t
            val dx = tl.cx - ex; val dz = tl.cz - ez
            val de = sqrt(dx * dx + dz * dz) * zoom
            val nd = max(0f, de - 85f) / quality
            var useLo = false
            if (t.hasLo) {
                if (de < 300f * quality) useLo = false else if (nd < far) useLo = true else continue
            } else {
                val lim = when { tl.kind == Nature.BUSH -> 620f; tl.kind == Nature.PALM -> 900f; tl.kind >= Nature.ROCK0 -> 700f; else -> 950f }
                if (nd >= lim || nd >= far) continue
            }
            if (!inView(tl.cx, tl.cy, tl.cz, tl.radius)) continue
            if (tl.kind != lastKind) { GL.glUniform1f(uAmp, Nature.SWAY[tl.kind]); lastKind = tl.kind }
            val g = if (useLo) res.lo[tl.kind] else res.hi[tl.kind]
            if (g == null) continue
            GL.glBindVertexArray(if (useLo) t.vaoLo else t.vaoHi)
            GL.glDrawArraysInstanced(GL.GL_TRIANGLES, 0, g.verts, tl.count)
            drawn++
        }
        GL.glBindVertexArray(0)
    }
}
