package com.dustline.game

import android.content.Context
import android.opengl.GLES30 as GL
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Id of the store top the player wears (account.store.eq). Kept in sync by Store.load / Store.save, read by the 3D character draw calls. */
object Wear { @Volatile var id: String? = null; @Volatile var pants: String? = null }   // pants = account.eqCat["pants"]

/** STORE_CH of the HTML: which meshes of the character are hidden / trimmed while a top is worn + where the bare forearm ends. */
class WearCfg(val chId: String, val hide: IntArray, val cut: Map<Int, String>, val w: FloatArray, val tt: Float, val skin: Int, val pantsHide: IntArray = intArrayOf())

/**
 * Store tops on the 3D body (port of storeInit / storeGeo / storeTubeGeo / storeCutGeo / storeApply of the HTML).
 * Every top is a skinned mesh fitted offline to the character (assets/store/store.json `bin`, 4 items), weighted to the same
 * 11-bone rig, so it follows every animation. While a top is worn the character's own jacket / suit top / vest is hidden or trimmed
 * (CharModel.ensureCut) and bare skin arms are drawn under the short sleeves.
 */
object Cloth {
    val CFG = mapOf(
        "survivor" to WearCfg("survivor", intArrayOf(6), mapOf(0 to "sv"), floatArrayOf(-.372f, 1.0f, -.015f), 1f, 0xFFc48e6a.toInt(), intArrayOf(7))   // prim 7 = the survivor's own trousers (hidden while store pants are worn)
    )
}

class ClothData(val id: String, val name: String, val n: Int, val ni: Int, val idxOff: Int, val siOff: Int, val swOff: Int, val colOff: Int, val fit: Map<String, Int>)

class ClothRenderer(private val ctx: Context) {
    private class Mesh(val vao: Int, val count: Int)

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec3 aCol; layout(location=3) in vec4 aSI; layout(location=4) in vec4 aSW;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uBones[12]; uniform vec3 uEye;
out vec3 vN; out vec3 vC; out float vD;
void main(){
  mat4 sk = aSW.x*uBones[int(aSI.x+0.5)] + aSW.y*uBones[int(aSI.y+0.5)] + aSW.z*uBones[int(aSI.z+0.5)] + aSW.w*uBones[int(aSI.w+0.5)];
  vec4 lp = sk*vec4(aPos,1.0); vec3 ln = mat3(sk)*aNor;
  vec4 w = uModel*lp; gl_Position = uVP*w; vN = mat3(uModel)*ln; vC = aCol; vD = distance(w.xyz,uEye);
}"""
    private val FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vC; in float vD;
uniform float uFog;
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
out vec4 o;
void main(){
  vec3 a = vC;
  vec3 n = normalize(vN); if(!gl_FrontFacing) n=-n;
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = a*(hemi*0.62 + vec3(1.0,0.91,0.78)*max(dot(n,SUN),0.0)*0.95) + vec3(0.12,0.115,0.10)*a;
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, vec3(0.74,0.84,0.93), f*f), 1.0);
}"""
    private var prog = 0
    private var uVP = 0; private var uModel = 0; private var uBones = 0; private var uEye = 0; private var uFog = 0
    private var items: Map<String, ClothData> = emptyMap()
    private var bin: ByteArray = ByteArray(0)
    private var loaded = false
    private val meshes = HashMap<String, Mesh?>()

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Cloth shader: " + GL.glGetShaderInfoLog(s))
        return s
    }

    fun init() {
        meshes.clear(); loaded = false; items = emptyMap()
        try {
            prog = GL.glCreateProgram()
            GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
            GL.glLinkProgram(prog)
            uVP = GL.glGetUniformLocation(prog, "uVP"); uModel = GL.glGetUniformLocation(prog, "uModel"); uBones = GL.glGetUniformLocation(prog, "uBones")
            uEye = GL.glGetUniformLocation(prog, "uEye"); uFog = GL.glGetUniformLocation(prog, "uFog")
        } catch (e: Throwable) { prog = 0 }
    }

    /** storeInit(): reads assets/store/store.json once (items + the shared binary blob). */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val j = JSONObject(ctx.assets.open("store/store.json").use { String(it.readBytes()) })
            bin = Base64.decode(j.getString("bin"), Base64.DEFAULT)
            val m = HashMap<String, ClothData>()
            val arr = j.getJSONArray("items")
            for (k in 0 until arr.length()) {
                val it = arr.getJSONObject(k); val f = it.getJSONObject("fit")
                val fit = HashMap<String, Int>(); for (key in f.keys()) fit[key] = f.getInt(key)
                m[it.getString("id")] = ClothData(it.getString("id"), it.optString("name"), it.getInt("n"), it.getInt("i"), it.getInt("idx"), it.getInt("si"), it.getInt("sw"), it.getInt("col"), fit)
            }
            items = m
        } catch (e: Throwable) { items = emptyMap() }
    }

    fun has(id: String?): Boolean { if (id == null || prog == 0) return false; ensureLoaded(); return items.containsKey(id) }

    // ------------------------------------------------------------------ geometry
    private fun normals(pos: FloatArray, idx: IntArray, nv: Int): FloatArray {
        val nr = FloatArray(nv * 3)
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (q in intArrayOf(a, b, c)) { nr[q] += nx; nr[q + 1] += ny; nr[q + 2] += nz }
            t += 3
        }
        for (v in 0 until nv) {
            val l = sqrt(nr[v * 3] * nr[v * 3] + nr[v * 3 + 1] * nr[v * 3 + 1] + nr[v * 3 + 2] * nr[v * 3 + 2])
            if (l > 1e-12f) { nr[v * 3] /= l; nr[v * 3 + 1] /= l; nr[v * 3 + 2] /= l }
        }
        return nr
    }

    private fun upload(vb: FloatArray, idx: IntArray): Mesh {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vbb = ByteBuffer.allocateDirect(vb.size * 4).order(ByteOrder.nativeOrder()); vbb.asFloatBuffer().put(vb)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, vb.size * 4, vbb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = 68
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 3, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 4, GL.GL_FLOAT, false, st, 36)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 4, GL.GL_FLOAT, false, st, 52)
        GL.glBindVertexArray(0)
        return Mesh(vao[0], idx.size)
    }

    /** storeGeo(): the top fitted to one character (vertex colours baked in the blob). */
    private fun buildTop(it: ClothData, pos: FloatArray, bb: ByteBuffer): Mesh {
        val n = it.n
        val idx = IntArray(it.ni) { k -> bb.getShort(it.idxOff + k * 2).toInt() and 0xFFFF }
        val nor = normals(pos, idx, n)
        val vb = FloatArray(n * 17)
        for (v in 0 until n) {
            val o = v * 17
            vb[o] = pos[v * 3]; vb[o + 1] = pos[v * 3 + 1]; vb[o + 2] = pos[v * 3 + 2]
            vb[o + 3] = nor[v * 3]; vb[o + 4] = nor[v * 3 + 1]; vb[o + 5] = nor[v * 3 + 2]
            for (k in 0..2) vb[o + 6 + k] = (bin[it.colOff + v * 3 + k].toInt() and 255) / 255f
            var s = 0f
            for (k in 0..3) s += (bin[it.swOff + v * 4 + k].toInt() and 255)
            if (s == 0f) s = 1f
            for (k in 0..3) { vb[o + 9 + k] = (bin[it.siOff + v * 4 + k].toInt() and 255).toFloat(); vb[o + 13 + k] = (bin[it.swOff + v * 4 + k].toInt() and 255) / s }
        }
        return upload(vb, idx)
    }

    /** storeTubeGeo(): bare arm (skin) under the sleeves, a tube along the rig's arm skinned to upper arm + forearm. */
    private fun tubeMesh(side: Int, wl: FloatArray, tt: Float, skin: Int): Mesh {
        val up = if (side < 0) 5 else 6; val fo = if (side < 0) 7 else 8; val sx = if (side < 0) 1f else -1f; val seg = 12
        val s = floatArrayOf(-.27f * sx, 1.67f, 0f); val e = floatArrayOf(-.328f * sx, 1.305f, .022f)
        val w0 = floatArrayOf(wl[0] * sx, wl[1], wl[2])
        val w = FloatArray(3) { k -> e[k] + (w0[k] - e[k]) * tt }
        fun dist(a: FloatArray, b: FloatArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        val nU = max(2, (dist(e, s) / .03f).toInt()); val nF = max(2, (dist(w, e) / .03f).toInt())
        val P = ArrayList<FloatArray>()
        for (i in 0..nU) P.add(FloatArray(3) { k -> s[k] + (e[k] - s[k]) * i / nU })
        for (i in 1..nF) P.add(FloatArray(3) { k -> e[k] + (w[k] - e[k]) * i / nF })
        val n = P.size
        val L = FloatArray(n); for (i in 1 until n) L[i] = L[i - 1] + dist(P[i], P[i - 1])
        val le = dist(e, s)
        fun ip(x: Float, xs: FloatArray, ys: FloatArray): Float {
            if (x <= xs[0]) return ys[0]
            for (i in 1 until xs.size) if (x <= xs[i]) { val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]); return ys[i - 1] + (ys[i] - ys[i - 1]) * t }
            return ys[ys.size - 1]
        }
        fun sm(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        val xs = floatArrayOf(0f, .08f, le * .55f, le, le + .1f, L[n - 1]); val ys = floatArrayOf(.034f, .044f, .045f, .040f, .037f, .033f)
        val pos = ArrayList<Float>(); val si = ArrayList<Float>(); val sw = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (i in 0 until n) {
            val a0 = P[max(0, i - 1)]; val a1 = P[minOf(n - 1, i + 1)]
            var t = floatArrayOf(a1[0] - a0[0], a1[1] - a0[1], a1[2] - a0[2]); val tl = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]); t = floatArrayOf(t[0] / tl, t[1] / tl, t[2] / tl)
            var a = floatArrayOf(t[1], -t[0], 0f); val al = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); a = floatArrayOf(a[0] / al, a[1] / al, a[2] / al)
            val b = floatArrayOf(t[1] * a[2] - t[2] * a[1], t[2] * a[0] - t[0] * a[2], t[0] * a[1] - t[1] * a[0])
            val r = ip(L[i], xs, ys); val wf = sm(le - .05f, le + .05f, L[i])
            for (k in 0 until seg) {
                val an = (2 * PI * k / seg).toFloat(); val c = cos(an); val sn = sin(an)
                pos.add(P[i][0] + r * (c * a[0] + sn * b[0])); pos.add(P[i][1] + r * (c * a[1] + sn * b[1])); pos.add(P[i][2] + r * (c * a[2] + sn * b[2]))
                si.add(up.toFloat()); si.add(fo.toFloat()); si.add(0f); si.add(0f)
                sw.add(1 - wf); sw.add(wf); sw.add(0f); sw.add(0f)
            }
        }
        for (i in 0 until n - 1) for (k in 0 until seg) {
            val k1 = (k + 1) % seg; val a0 = i * seg + k; val a1 = i * seg + k1; val b0 = a0 + seg; val b1 = a1 + seg
            idx.add(a0); idx.add(a1); idx.add(b1); idx.add(a0); idx.add(b1); idx.add(b0)
        }
        val c0 = pos.size / 3; pos.addAll(P[0].toList()); si.addAll(listOf(up.toFloat(), fo.toFloat(), 0f, 0f)); sw.addAll(listOf(1f, 0f, 0f, 0f))
        for (k in 0 until seg) { idx.add(c0); idx.add((k + 1) % seg); idx.add(k) }
        val c1 = pos.size / 3; pos.addAll(P[n - 1].toList()); si.addAll(listOf(up.toFloat(), fo.toFloat(), 0f, 0f)); sw.addAll(listOf(0f, 1f, 0f, 0f))
        for (k in 0 until seg) { idx.add(c1); idx.add((n - 1) * seg + k); idx.add((n - 1) * seg + (k + 1) % seg) }
        val nv = pos.size / 3
        val pa = pos.toFloatArray(); val ia = idx.toIntArray(); val nor = normals(pa, ia, nv)
        val cr = ((skin shr 16) and 255) / 255f; val cg = ((skin shr 8) and 255) / 255f; val cb = (skin and 255) / 255f
        val vb = FloatArray(nv * 17)
        for (v in 0 until nv) {
            val o = v * 17
            vb[o] = pa[v * 3]; vb[o + 1] = pa[v * 3 + 1]; vb[o + 2] = pa[v * 3 + 2]
            vb[o + 3] = nor[v * 3]; vb[o + 4] = nor[v * 3 + 1]; vb[o + 5] = nor[v * 3 + 2]
            vb[o + 6] = cr; vb[o + 7] = cg; vb[o + 8] = cb
            for (k in 0..3) { vb[o + 9 + k] = si[v * 4 + k]; vb[o + 13 + k] = sw[v * 4 + k] }
        }
        return upload(vb, ia)
    }

    private fun meshFor(key: String, make: () -> Mesh?): Mesh? { if (meshes.containsKey(key)) return meshes[key]; val m = try { make() } catch (e: Throwable) { null }; meshes[key] = m; return m }

    /**
     * Draws the worn top + the bare arms with the SAME 12 skinning matrices the character body was just drawn with.
     * `fileId` = "survivor". Leaves the cloth program bound (callers re-bind their own).
     */
    fun draw(wearId: String?, pantsId: String?, fileId: String, bones: FloatArray, vp: FloatArray, model: FloatArray, ex: Float, ey: Float, ez: Float, fog: Float) {
        if (prog == 0) return
        ensureLoaded()
        val cfg = Cloth.CFG[fileId] ?: return
        val topItem = if (wearId != null) items[wearId] else null
        val pantsItem = if (pantsId != null) items[pantsId] else null
        if (topItem == null && pantsItem == null) return
        val top = if (topItem != null) meshFor(wearId + "|" + cfg.chId) { topMesh2(topItem, cfg.chId) } else null
        val pn = if (pantsItem != null) meshFor(pantsId + "|" + cfg.chId) { topMesh2(pantsItem, cfg.chId) } else null
        val tl = if (topItem != null) meshFor("tubeL|" + fileId) { tubeMesh(-1, cfg.w, cfg.tt, cfg.skin) } else null
        val tr = if (topItem != null) meshFor("tubeR|" + fileId) { tubeMesh(1, cfg.w, cfg.tt, cfg.skin) } else null
        GL.glUseProgram(prog)
        GL.glUniformMatrix4fv(uVP, 1, false, vp, 0); GL.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GL.glUniformMatrix4fv(uBones, CharRig.NB, false, bones, 0)
        GL.glUniform3f(uEye, ex, ey, ez); GL.glUniform1f(uFog, fog)
        val cull = GL.glIsEnabled(GL.GL_CULL_FACE)
        if (cull) GL.glDisable(GL.GL_CULL_FACE)   // DoubleSide material of the HTML
        for (m in arrayOf(top, tl, tr, pn)) {
            if (m == null) continue
            GL.glBindVertexArray(m.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glBindVertexArray(0)
        if (cull) GL.glEnable(GL.GL_CULL_FACE)
    }

    private fun topMesh2(it: ClothData, chId: String): Mesh? {
        val off = it.fit[chId] ?: return null
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val pos = FloatArray(it.n * 3) { k -> bb.getFloat(off + k * 4) }
        return buildTop(it, pos, bb)
    }
}
