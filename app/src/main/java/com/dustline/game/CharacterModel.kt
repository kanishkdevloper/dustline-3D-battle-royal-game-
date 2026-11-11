package com.dustline.game

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The rigged character of the Android build (SURVIVOR), ported 1:1:
 * every vertex is skinned to the same 11-bone rig (+ a 12th 'head' bone, verts above y=1.72 re-weighted exactly like crimInit()).
 * Mesh data = models/character/bin_0X.bin, rig / bind pose / rifle pose = models/character/<id>.json (taken from CRIM_DATA / SV_DATA).
 */
object CharRig {
    val BONES = arrayOf("body", "thighL", "thighR", "shinL", "shinR", "upperL", "upperR", "foreL", "foreR", "handL", "handR", "head")
    const val NB = 12
    val NECK = floatArrayOf(0f, 1.76f, -0.02f)
    const val SCALE = 0.8f // CHAR_K of the HTML
}

/** What the animation needs to know. Angles in radians, same meaning as m.left.rotation.x etc. of the HTML. */
class CharPose {
    var mode = RIFLE
    var walk = 0f        // walk phase (radians)
    var speed = 0f       // m/s
    var sprint = false
    var crouch = 0f      // 0..1
    var headYaw = 0f
    var headPitch = 0f
    var aimPitch = 0f    // raises / lowers the rifle arms a little
    var airT = 0f        // freefall pose blend 0..1 (CHUTE: landing flare)

    // JUMP (JumpAnim.kt): clip time (s) of the baked jump motion, blend 0..1 into the normal pose, root-lowering weight 0..1 (landing).
    // Only RIFLE / RELAXED use it; the caller must clear it (jumpW = 0) after drawing, the pose object is shared with the bots.
    var jumpT = -1f; var jumpW = 0f; var jumpDrop = 0f
    // written by CharModel.skin(): torso lean (rad, <0 = forward) and hip lowering (m) that were really applied, so the 3D gun can follow them
    var outLean = 0f; var outDrop = 0f
    // PRONE 0..1 (lying flat, face down). crouch stays 1 while prone; skin() blends crouch -> prone.
    var prone = 0f
    var supine = 0f   // 0..1 while prone: lie on the BACK (face up, feet first) instead of face down
    var swim = 0f     // 0..1 SWIMMING (front crawl): prone root + circling arms + flutter kick; walk = stroke phase (rad). Caller clears it after drawing (shared pose object)
    // written by skin() in RIFLE mode: frame of the shoulders (body space) the gun is attached to, so the 3D gun moves WITH the hands
    val gunFrame = FloatArray(16); var hasGun = false
    // id of the gun in the hands (RIFLE mode): selects the baked per-gun arm pose + grip (GunHold). null = the generic rifle pose. The caller sets it before every draw.
    var gunId: String? = null

    // BOXING (mode BOX = fists in hand): time drives the idle foot bounce; punchU = 0..1 progress of the running punch (< 0 = no punch),
    // punchKind 0 jab (lead / left), 1 cross (rear / right), 2 lead hook (left), 3 rear uppercut (right). See BoxAnim.
    var time = 0f
    var punchU = -1f
    var punchKind = 0

    // AIR mode (Phase 28): every angle is given directly - port of dskAirPose() of the HTML, which drives the skate stance,
    // the head-first dive and the parachute hang through ONE smoothed pose. Same meaning as m.armR.rotation.z etc.
    var exTw = 0f                       // torso twist (m.body.rotation.y = -tw); shoulders + head follow it
    var exArmLx = 0f; var exArmRx = 0f; var exArmLz = 0f; var exArmRz = 0f
    var exForeLx = 0f; var exForeRx = 0f; var exForeLz = 0f; var exForeRz = 0f
    var exLegLx = 0f; var exLegRx = 0f; var exLegLz = 0f; var exLegRz = 0f; var exKneeL = 0f; var exKneeR = 0f

    companion object { const val RIFLE = 0; const val RELAXED = 1; const val DIVE = 3; const val CHUTE = 4; const val AIR = 5; const val BOX = 6 }
}

/**
 * Boxing animation tables (fists in hand). Arm angles are those of CharModel.skin(): (upper arm x, upper arm z, forearm x, forearm z);
 * they were solved with a small inverse-kinematics script so the fist really lands where a boxer's fist lands (guard at the chin, straight jab,
 * cross over the centre line, hook with the elbow out, uppercut from below). Values are for the LEFT arm, the right arm uses the mirror
 * (z angles negated). Punch order of the combo: JAB (left) -> CROSS (right) -> HOOK (left) -> UPPERCUT (right).
 */
object BoxAnim {
    const val JAB = 0; const val CROSS = 1; const val HOOK = 2; const val UPPER = 3
    /** guard: elbows tucked, both fists up in front of the chin */
    val GUARD = floatArrayOf(0.336f, -0.117f, 2.222f, 0.506f)
    /** key frames per punch: rows of u, ax, az, fx, fz (u = 0..1 progress of the punch) */
    private val KEYS = arrayOf(
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.10f, -0.006f, -0.231f, 2.352f, 0.485f), floatArrayOf(0.36f, 1.572f, 0.133f, 0.03f, 0.204f), floatArrayOf(0.55f, 1.572f, 0.133f, 0.03f, 0.204f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.16f, -0.006f, -0.231f, 2.352f, 0.485f), floatArrayOf(0.42f, 1.545f, 0.251f, 0.03f, 0.099f), floatArrayOf(0.60f, 1.545f, 0.251f, 0.03f, 0.099f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.22f, 0.926f, -1.249f, 1.814f, 1.0f), floatArrayOf(0.50f, 1.143f, -0.306f, 1.369f, 1.0f), floatArrayOf(0.65f, 1.143f, -0.306f, 1.369f, 1.0f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f)),
        arrayOf(floatArrayOf(0f, 0.336f, -0.117f, 2.222f, 0.506f), floatArrayOf(0.25f, -0.055f, -0.185f, 1.308f, 0.562f), floatArrayOf(0.52f, 0.813f, 0.142f, 1.647f, 0.437f), floatArrayOf(0.66f, 0.813f, 0.142f, 1.647f, 0.437f), floatArrayOf(1f, 0.336f, -0.117f, 2.222f, 0.506f))
    )
    /** u at which the fist lands (damage is applied there) and u at which it starts to come back */
    val PEAK = floatArrayOf(0.36f, 0.42f, 0.50f, 0.52f)
    val HOLD = floatArrayOf(0.55f, 0.60f, 0.65f, 0.66f)
    /** the left arm throws jab + hook, the right arm cross + uppercut */
    fun leftArm(kind: Int) = kind == JAB || kind == HOOK
    /** extra shoulder / hip twist and forward lean at the strike (stance twist is -0.22: left shoulder forward) */
    val TWIST = floatArrayOf(-0.06f, 0.40f, -0.22f, 0.28f)
    val LEAN = floatArrayOf(-0.06f, -0.10f, -0.08f, -0.04f)
    const val STANCE_TWIST = -0.22f
    /** damage, cool-down (s) and animation length (s) of every punch */
    val DAMAGE = floatArrayOf(15f, 24f, 28f, 26f)
    val COOLDOWN = floatArrayOf(0.34f, 0.46f, 0.52f, 0.50f)
    val DURATION = floatArrayOf(0.30f, 0.38f, 0.44f, 0.42f)

    private fun sstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** out[0..3] = ax, az, fx, fz of the LEFT-arm frame at progress u of the punch `kind` */
    fun sample(kind: Int, u: Float, out: FloatArray) {
        val k = KEYS[kind.coerceIn(0, 3)]; val uu = u.coerceIn(0f, 1f)
        var i = 0
        while (i < k.size - 2 && uu > k[i + 1][0]) i++
        val a = k[i]; val b = k[i + 1]
        val t = sstep((uu - a[0]) / (b[0] - a[0]))
        for (j in 0..3) out[j] = a[j + 1] + (b[j + 1] - a[j + 1]) * t
    }

    /** 0..1: how far the punch is thrown (0 in guard, 1 at the strike, back to 0) - drives the body twist */
    fun envelope(kind: Int, u: Float): Float {
        val kk = kind.coerceIn(0, 3)
        return if (u <= 0f) 0f else if (u < PEAK[kk]) sstep(u / PEAK[kk]) else if (u < HOLD[kk]) 1f else 1f - sstep((u - HOLD[kk]) / (1f - HOLD[kk]))
    }
}

/**
 * GAIT TIMING (fix): the walk phase used to advance 1.45 rad per metre at every speed, so the feet slid over the ground 1.2 - 2.4x too fast
 * and the legs cycled far too slowly when walking / far too fast (4.6 steps/s) when sprinting. Now the phase follows a real human step rate
 * (steps per second grow with the speed: ~2.5 at 2.8 m/s, ~3.2 at 6 m/s, ~4 at 10 m/s) and the hip swing grows with the speed too (see skin()).
 */
object Gait {
    fun cadence(v: Float): Float = min(1.9f + 0.215f * v, 4.2f)          // steps per second
    /** phase speed (rad/s): one step = pi rad. crouch / prone move with a slower step rate. 0 when standing still. */
    fun omega(v: Float, crouch: Boolean = false, prone: Boolean = false): Float {
        val k = (v / 0.8f).coerceIn(0f, 1f); val kk = k * k * (3f - 2f * k)
        val c = cadence(v) * (if (prone) 0.45f else if (crouch) 0.6f else 1f)
        return Math.PI.toFloat() * c * kk
    }
    /** hip swing (rad) at ground speed v */
    fun amp(v: Float): Float = (0.50f + 0.065f * v).coerceIn(0.50f, 1.10f)
}

class CharModel(ctx: Context, val id: String) {
    class Prim(val vao: Int, val count: Int, val mat: Int, val vbo: Int = 0) {
        // kept only for meshes the store may trim (cut): CPU copy of the vertices / indices + the trimmed VAO per kind
        var vb: FloatArray? = null; var ib: IntArray? = null
        val alt = HashMap<String, IntArray>()   // kind -> [vao, indexCount]
    }

    val prims = ArrayList<Prim>()
    val texIds = ArrayList<Int>()
    val bind = Array(CharRig.NB) { FloatArray(16) }
    private val uq = Array(2) { FloatArray(4) }; private val fq = Array(2) { FloatArray(4) }; private val e1 = Array(2) { FloatArray(3) }
    private val elbow = Array(2) { FloatArray(3) }
    var vertexCount = 0

    init {
        val meta = JSONObject(Prot.open(ctx, "models/character/$id.json").use { String(it.readBytes()) })
        val bin = Prot.open(ctx, "models/character/" + meta.getString("bin")).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val bd = meta.getJSONObject("bind")
        for (i in 0 until 11) { val a = bd.getJSONArray(CharRig.BONES[i]); for (k in 0..15) bind[i][k] = a.getDouble(k).toFloat() }
        Matrix.setIdentityM(bind[11], 0); bind[11][12] = CharRig.NECK[0]; bind[11][13] = CharRig.NECK[1]; bind[11][14] = CharRig.NECK[2]
        val rf = meta.getJSONObject("rifle"); val rl = meta.getJSONObject("rel")
        for ((s, t) in arrayOf("L", "R").withIndex()) {
            val r = rf.getJSONObject(t)
            for (k in 0..3) { uq[s][k] = r.getJSONArray("uq").getDouble(k).toFloat(); fq[s][k] = r.getJSONArray("fq").getDouble(k).toFloat() }
            for (k in 0..2) { e1[s][k] = r.getJSONArray("E1").getDouble(k).toFloat(); elbow[s][k] = rl.getJSONObject(t).getJSONArray("elbow").getDouble(k).toFloat() }
        }
        // GUN HOLD FIX: re-targeted arm pose (2-bone IK): right hand on the grip, left hand under the handguard, gun level at chest height
        run {
            val uqL = floatArrayOf(0.381092f, -0.046634f, 0.23902f, 0.891888f); val fqL = floatArrayOf(0.557279f, -0.10629f, 0.433158f, 0.700369f); val e1L = floatArrayOf(0.119195f, -0.255274f, -0.239849f)
            val uqR = floatArrayOf(0.014167f, 0.004131f, 0.031077f, 0.999408f); val fqR = floatArrayOf(0.671785f, 0.113257f, -0.392101f, 0.61817f); val e1R = floatArrayOf(0.081052f, -0.360845f, 0.011021f)
            for (k in 0..3) { uq[0][k] = uqL[k]; fq[0][k] = fqL[k]; uq[1][k] = uqR[k]; fq[1][k] = fqR[k] }
            for (k in 0..2) { e1[0][k] = e1L[k]; e1[1][k] = e1R[k] }
        }
        val pa = meta.getJSONArray("prims")
        for (pi in 0 until pa.length()) {
            val p = pa.getJSONObject(pi); val n = p.getInt("n"); val ni = p.getInt("i")
            val pos = p.getInt("pos"); val nor = p.getInt("nor"); val uv = p.getInt("uv"); val idx = p.getInt("idx"); val si = p.getInt("si"); val sw = p.getInt("sw")
            val vb = FloatArray(n * 16)
            val idi = IntArray(4); val wf = FloatArray(4)
            for (v in 0 until n) {
                val o = v * 16
                val px = bb.getFloat(pos + v * 12); val py = bb.getFloat(pos + v * 12 + 4); val pz = bb.getFloat(pos + v * 12 + 8)
                vb[o] = px; vb[o + 1] = py; vb[o + 2] = pz
                vb[o + 3] = bin[nor + v * 3] / 127f; vb[o + 4] = bin[nor + v * 3 + 1] / 127f; vb[o + 5] = bin[nor + v * 3 + 2] / 127f
                vb[o + 6] = bb.getFloat(uv + v * 8); vb[o + 7] = bb.getFloat(uv + v * 8 + 4)
                var s = 0f
                for (k in 0..3) { idi[k] = bin[si + v * 4 + k].toInt() and 255; wf[k] = (bin[sw + v * 4 + k].toInt() and 255).toFloat(); s += wf[k] }
                if (s == 0f) s = 1f
                for (k in 0..3) wf[k] /= s
                // head bone: verts above the collar are re-weighted to bone 11 (smoothstep over 0.12 m), as in crimInit()
                if (py >= 1.72f) {
                    var bi = -1
                    for (k in 0..3) if (idi[k] == 0 && wf[k] > 0f) { bi = k; break }
                    if (bi >= 0) {
                        val u = ((py - 1.72f) / 0.12f).coerceIn(0f, 1f); val hw = wf[bi] * (u * u * (3f - 2f * u))
                        if (hw > 0f) {
                            var slot = -1
                            for (k in 0..3) if (k != bi && wf[k] <= 0f) { slot = k; break }
                            if (slot < 0) { var mn = 9f; for (k in 0..3) if (k != bi && wf[k] < mn) { mn = wf[k]; slot = k }; wf[bi] += wf[slot] }
                            wf[bi] -= hw; idi[slot] = 11; wf[slot] = hw
                        }
                    }
                }
                for (k in 0..3) { vb[o + 8 + k] = idi[k].toFloat(); vb[o + 12 + k] = wf[k] }
            }
            val ib = IntArray(ni) { (bin[idx + it * 2].toInt() and 255) or ((bin[idx + it * 2 + 1].toInt() and 255) shl 8) }
            prims.add(upload(vb, ib, p.getInt("mat")))
            if (Cloth.CFG[id]?.cut?.containsKey(pi) == true) { prims.last().vb = vb; prims.last().ib = ib }
            vertexCount += n
        }
        val ta = meta.getJSONArray("tex")
        for (i in 0 until ta.length()) texIds.add(loadTex(ctx, "models/character/" + ta.getString(i)))
    }

    private fun upload(v: FloatArray, idx: IntArray, mat: Int): Prim {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()); vb.asFloatBuffer().put(v)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, v.size * 4, vb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = 64
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 4, GL.GL_FLOAT, false, st, 32)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 4, GL.GL_FLOAT, false, st, 48)
        GL.glBindVertexArray(0)
        return Prim(vao[0], idx.size, mat, b[0])
    }

    /**
     * storeCutGeo(): a copy of the mesh without the triangles of the top part while a store top is worn.
     * kind 'sv' (Survivor jacket) hides centroid y in (1.2, 1.8); any other kind hides y > 1.05 and every arm triangle (dominant bone 5..8).
     * Returns [vao, indexCount] or null. Shares the vertex buffer with the original mesh.
     */
    fun ensureCut(p: Prim, kind: String): IntArray? {
        p.alt[kind]?.let { return it }
        val vb = p.vb ?: return null; val ib = p.ib ?: return null
        fun dom(v: Int): Int { var b = 0; var bw = -1f; for (k in 0..3) { val w = vb[v * 16 + 12 + k]; if (w > bw) { bw = w; b = vb[v * 16 + 8 + k].toInt() } }; return b }
        val out = ArrayList<Int>(ib.size)
        var t = 0
        while (t + 2 < ib.size) {
            val a = ib[t]; val b = ib[t + 1]; val c = ib[t + 2]
            val cy = (vb[a * 16 + 1] + vb[b * 16 + 1] + vb[c * 16 + 1]) / 3f
            val hide = if (kind == "sv") cy < 1.80f && cy > 1.2f
            else { val arm = { v: Int -> val d = dom(v); d in 5..8 }; cy > 1.05f || (arm(a) && arm(b) && arm(c)) }
            if (!hide) { out.add(a); out.add(b); out.add(c) }
            t += 3
        }
        val ia = out.toIntArray()
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, p.vbo)
        val st = 64
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 4, GL.GL_FLOAT, false, st, 32)
        GL.glEnableVertexAttribArray(4); GL.glVertexAttribPointer(4, 4, GL.GL_FLOAT, false, st, 48)
        val eb = IntArray(1); GL.glGenBuffers(1, eb, 0)
        val ibb = ByteBuffer.allocateDirect(ia.size * 4).order(ByteOrder.nativeOrder()); ibb.asIntBuffer().put(ia)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, eb[0]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, ia.size * 4, ibb, GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
        val r = intArrayOf(vao[0], ia.size); p.alt[kind] = r
        return r
    }

    private fun loadTex(ctx: Context, path: String): Int {
        val bmp = Prot.open(ctx, path).use { BitmapFactory.decodeStream(it) } ?: return 0
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT)
        return id[0]
    }

    // ---------------------------------------------------------------- animation -> 12 skinning matrices
    private val g = FloatArray(16); private val a = FloatArray(16); private val t = FloatArray(16); private val r = FloatArray(16)
    private val w = FloatArray(16); private val inv = FloatArray(16); private val tmp = FloatArray(16); private val lm = FloatArray(16)
    private val bq = FloatArray(4)   // sampled boxing key frame (BoxAnim.sample)
    private val jv = FloatArray(JumpData.COLS)   // sampled jump clip angles (see JumpAnim.sample)

    private fun rotX(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 1f, 0f, 0f) }
    private fun rotY(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 0f, 1f, 0f) }
    private fun rotZ(m: FloatArray, ang: Float) { if (ang != 0f) Matrix.rotateM(m, 0, Math.toDegrees(ang.toDouble()).toFloat(), 0f, 0f, 1f) }
    private fun quat(m: FloatArray, q: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val ww = q[3]
        r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y + z * ww); r[2] = 2 * (x * z - y * ww); r[3] = 0f
        r[4] = 2 * (x * y - z * ww); r[5] = 1 - 2 * (x * x + z * z); r[6] = 2 * (y * z + x * ww); r[7] = 0f
        r[8] = 2 * (x * z + y * ww); r[9] = 2 * (y * z - x * ww); r[10] = 1 - 2 * (x * x + y * y); r[11] = 0f
        r[12] = 0f; r[13] = 0f; r[14] = 0f; r[15] = 1f
        Matrix.multiplyMM(tmp, 0, m, 0, r, 0); System.arraycopy(tmp, 0, m, 0, 16)
    }
    /** out = world * inverse(bind). bind matrices are pure translations, so the inverse is a negated translation. */
    private fun emit(out: FloatArray, bone: Int, world: FloatArray) {
        System.arraycopy(bind[bone], 0, inv, 0, 16); inv[12] = -inv[12]; inv[13] = -inv[13]; inv[14] = -inv[14]
        Matrix.multiplyMM(out, bone * 16, world, 0, inv, 0)
    }

    private val rt = FloatArray(16); private val up = FloatArray(16)
    private fun sstep(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun footY(a: Float, z: Float, k: Float) = -0.5f * cos(z) * cos(a) + 0.015f * sin(a) + 0.54f * (-cos(z) * cos(k) * cos(a) + sin(k) * sin(a))

    fun skin(out: FloatArray, p: CharPose) {
        val pr = p.prone.coerceIn(0f, 1f); val su = p.supine.coerceIn(0f, 1f); val cw = p.crouch.coerceIn(0f, 1f) * (1f - pr)
        val moving = p.speed > 0.2f
        // GAIT (fixed): the stride, the knee lift, the arm pump and the forward lean now follow the REAL speed: walk (< 2.5 m/s) -> jog (~6 m/s) -> sprint (~10 m/s).
        // Before, every speed used the same straight-armed gait and the character never leaned into a run.
        val jog = sstep((p.speed - 2.5f) / 3.5f); val spr = sstep((p.speed - 6.5f) / 3.5f)
        val s0 = sin(p.walk); val c0 = cos(p.walk)
        val amp = Gait.amp(p.speed)                                        // hip swing (rad), grows with the real speed
        val sw = if (moving) s0 * amp else 0f
        var la = sw; var ra = -sw; var lk = 0f; var rk = 0f; var lz = 0f; var rz = 0f
        if (moving) { val kb = 0.6f + 0.5f * jog + 0.5f * spr; val k0 = 0.12f + 0.10f * jog; lk = -(k0 + kb * max(0f, c0)); rk = -(k0 + kb * max(0f, -c0)) }
        val onFoot = p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED || p.mode == CharPose.BOX
        val rm = sstep(p.speed / 1.2f)                                     // 0 standing .. 1 moving
        var armRx = 0f; var armLx = 0f; var armRz = 0f; var armLz = 0f; var foreRx = 0f; var foreLx = 0f; var foreRz = 0f; var foreLz = 0f
        var tw = 0f
        var rifle = p.mode == CharPose.RIFLE
        val ga = if (rifle) GunHold.arms(p.gunId) else null
        val aUq = ga?.uq ?: uq; val aFq = ga?.fq ?: fq; val aE1 = ga?.e1 ?: e1
        when (p.mode) {
            CharPose.DIVE -> { la = 0.12f; ra = 0.12f; lk = 0f; rk = 0f; armRz = -2.7f; armLz = 2.7f; rifle = false }
            CharPose.CHUTE -> { la = 0.22f; ra = 0.12f; lk = -0.25f; rk = -0.2f; armRz = -2.8f; armLz = 2.8f; foreRx = -0.2f; foreLx = -0.2f; rifle = false }
            CharPose.RELAXED, CharPose.BOX -> {
                // running arms: opposite to the legs, elbows bent (about 90 deg when jogging / sprinting) and pumping, hands swing past the hip
                val aA = 0.35f + 0.15f * jog + 0.35f * spr; val fB = 0.20f + 1.05f * jog + 0.30f * spr
                armLx = -s0 * aA * rm + 0.12f * jog; armRx = s0 * aA * rm + 0.12f * jog
                foreLx = fB - 0.30f * s0 * jog * rm; foreRx = fB + 0.30f * s0 * jog * rm
                armLz = 0.08f * jog; armRz = -0.08f * jog; foreLz = 0.12f * jog; foreRz = -0.12f * jog
            }
            CharPose.AIR -> {
                la = p.exLegLx; ra = p.exLegRx; lz = p.exLegLz; rz = p.exLegRz; lk = p.exKneeL; rk = p.exKneeR
                armLx = p.exArmLx; armRx = p.exArmRx; armLz = p.exArmLz; armRz = p.exArmRz
                foreLx = p.exForeLx; foreRx = p.exForeRx; foreLz = p.exForeLz; foreRz = p.exForeRz
                tw = p.exTw; rifle = false
            }
        }
        // sprint with the rifle: both arms together (the gun is attached to the hands, so the hands must stay together)
        if (rifle && p.sprint && p.speed > 1.5f) { val v = 0.12f + sin(p.walk * 2f) * 0.04f; armLx = v; armRx = v }
        if (rifle) { armLx += p.aimPitch; armRx += p.aimPitch }

        // CROUCH: standing still = deep squat. Moving = a tactical crouch-walk: higher hips, long alternating strides, the stepping foot lifts (knee bends more)
        // and the stance leg straightens a little; the shoulders rock gently with every step. (mvw blends squat -> walk smoothly with the speed)
        var cLean = 0f; var rollZ = 0f; var twY = 0f
        val mvw = sstep(p.speed / 1.6f)
        if (cw > 0.01f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED)) {
            val s1 = sin(p.walk); val c1 = cos(p.walk)
            val base = 1.20f + (0.92f - 1.20f) * mvw; val kb = -1.80f + (-1.38f + 1.80f) * mvw
            val swing = 0.40f * mvw
            val lkn = kb - mvw * (0.55f * max(0f, c1) - 0.12f * max(0f, -c1)); val rkn = kb - mvw * (0.55f * max(0f, -c1) - 0.12f * max(0f, c1))
            la = la * (1f - cw) + (base + swing * s1) * cw; ra = ra * (1f - cw) + (base - swing * s1) * cw
            lk = lk * (1f - cw) + lkn * cw; rk = rk * (1f - cw) + rkn * cw
            lz = lz * (1f - cw) - 0.03f * mvw * cw; rz = rz * (1f - cw) + 0.03f * mvw * cw
            val cl = -0.30f + 0.07f * mvw
            cLean = cl * cw
            rollZ = 0.035f * s1 * mvw * cw
            if (rifle) { armLx -= cl * cw; armRx -= cl * cw }   // keeps the gun level although the torso leans
        }
        // PRONE: lying flat. Standing still = small breathing pose. Moving = a leopard CRAWL: the legs are drawn up one after the other (knee goes up and out,
        // the other leg pushes), the hips / shoulders roll with every pull and the arms (with the gun) slide forward / back together.
        if (pr > 0.01f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED)) {
            val ph = p.walk; val cs = sin(ph); val mv = mvw
            val drawL = max(0f, cs); val drawR = max(0f, -cs); val face = 1f - su
            la = la * (1f - pr) + (0.10f * cs * mv + 0.22f * drawL * mv * face + 0.30f * drawL * mv * su) * pr
            ra = ra * (1f - pr) + (-0.10f * cs * mv + 0.22f * drawR * mv * face + 0.30f * drawR * mv * su) * pr
            lk = lk * (1f - pr) - (0.04f + 1.05f * (1f - 0.25f * su) * drawL * mv) * pr
            rk = rk * (1f - pr) - (0.04f + 1.05f * (1f - 0.25f * su) * drawR * mv) * pr
            la -= 0.08f * su * pr; ra -= 0.08f * su * pr
            lz = lz * (1f - pr) - (0.07f + 0.50f * drawL * mv) * pr; rz = rz * (1f - pr) + (0.07f + 0.50f * drawR * mv) * pr
            cLean += (0.40f * (1f - su) - 0.30f * su) * pr
            rollZ += 0.07f * cs * mv * pr
            twY = 0.05f * cs * mv * pr
            val hp = Math.PI.toFloat() / 2f
            val ap = ((hp - (if (rifle) 0.40f else 0f)) * (1f - su) + (-hp + (if (rifle) 0.5f else 0f)) * su) * pr
            val slide = sin(ph + 0.9f) * mv * pr * (0.07f * (1f - su) + 0.05f * su)
            armLx += ap + slide; armRx += ap + slide
        }

        // SWIMMING (front crawl): face-down prone root with the head up, the arms circle over the head one after the other (pull under the body,
        // recover above the water), flutter kick, slow body roll along the long axis. p.walk = stroke phase (rad), p.swim = blend 0..1.
        val swm = p.swim.coerceIn(0f, 1f)
        if (swm > 0.01f && pr > 0.01f && !rifle) {
            val pi = Math.PI.toFloat(); val ph = p.walk % (2f * pi); val kk = sin(ph * 2.6f)
            la = la * (1f - swm) + 0.32f * kk * swm; ra = ra * (1f - swm) - 0.32f * kk * swm
            lk = lk * (1f - swm) - (0.22f + 0.30f * max(0f, kk)) * swm; rk = rk * (1f - swm) - (0.22f + 0.30f * max(0f, -kk)) * swm
            lz = lz * (1f - swm) - 0.05f * swm; rz = rz * (1f - swm) + 0.05f * swm
            armLx = armLx * (1f - swm) + (-ph) * swm; armRx = armRx * (1f - swm) + (pi - ph) * swm
            armLz = armLz * (1f - swm) + 0.20f * swm; armRz = armRz * (1f - swm) - 0.20f * swm
            foreLx = foreLx * (1f - swm) + 0.35f * swm; foreRx = foreRx * (1f - swm) + 0.35f * swm
            foreLz *= (1f - swm); foreRz *= (1f - swm)
            twY = twY * (1f - swm) + 0.28f * sin(ph) * swm
        }

        // RUN LEAN + SHOULDER TWIST: the torso leans into the run (jog about 6 deg, sprint about 14 deg), the shoulders counter-rotate against the hips
        // (with a rifle only half the lean and a third of the twist, so the gun does not wobble). Not while crouching, prone or jumping.
        val rwgt = (1f - cw) * (1f - pr) * (1f - p.jumpW.coerceIn(0f, 1f))
        if (onFoot && rwgt > 0.001f) {
            val rl = -(0.10f * jog + 0.14f * spr) * rwgt * (if (rifle) 0.5f else 1f)
            cLean += rl
            if (rifle) { armLx -= rl * 0.6f; armRx -= rl * 0.6f }
            twY += (0.04f + 0.03f * jog + 0.07f * spr) * s0 * rm * rwgt * (if (rifle) 0.3f else 1f)
        }
        // BOXING (fists in hand): orthodox stance when standing (left foot + shoulder forward, knees bent, feet planted, small bounce), fists up at the
        // chin; while running the arms pump (above 7 m/s); the punch (jab / cross / hook / uppercut) overrides the throwing arm and twists the body.
        if (p.mode == CharPose.BOX && cw < 0.01f && pr < 0.01f) {
            val sb = 1f - sstep(p.speed / 1.5f); val bounce = 0.04f * sin(p.time * 7f)
            la = la * (1f - sb) + (0.26f + bounce) * sb; ra = ra * (1f - sb) + (-0.24f - bounce) * sb
            lk = lk * (1f - sb) + (-0.30f) * sb; rk = rk * (1f - sb) + (-0.36f) * sb
            lz = lz * (1f - sb) - 0.07f * sb; rz = rz * (1f - sb) + 0.07f * sb
            val runW = sstep((p.speed - 7f) / 2.5f); val gw = 1f - runW; val gd = BoxAnim.GUARD
            armLx = armLx * runW + gd[0] * gw; armLz = armLz * runW + gd[1] * gw; foreLx = foreLx * runW + gd[2] * gw; foreLz = foreLz * runW + gd[3] * gw
            armRx = armRx * runW + gd[0] * gw; armRz = armRz * runW - gd[1] * gw; foreRx = foreRx * runW + gd[2] * gw; foreRz = foreRz * runW - gd[3] * gw
            var pTw = 0f; var pLean = 0f
            if (p.punchU >= 0f) {
                val kd = p.punchKind.coerceIn(0, 3); val left = BoxAnim.leftArm(kd)
                BoxAnim.sample(kd, p.punchU, bq)
                val e = BoxAnim.envelope(kd, p.punchU)
                if (left) { armLx = bq[0]; armLz = bq[1]; foreLx = bq[2]; foreLz = bq[3] } else { armRx = bq[0]; armRz = -bq[1]; foreRx = bq[2]; foreRz = -bq[3] }
                pTw = BoxAnim.TWIST[kd] * e; pLean = BoxAnim.LEAN[kd] * e
                if (kd == BoxAnim.JAB) la += 0.10f * e   // small lunge on the lead leg
                if (kd == BoxAnim.CROSS) { ra -= 0.12f * e; la += 0.08f * e; rk -= 0.15f * e }   // rear leg drives, hips rotate into the cross
                if (kd == BoxAnim.UPPER) { lk -= 0.25f * e; rk -= 0.30f * e }                    // dip the knees, then drive up
            }
            twY += BoxAnim.STANCE_TWIST * gw + pTw
            cLean += pLean
        }

        // JUMP: legs + torso always follow the baked clip; the arms too unless the rifle is held (the two-hand rifle pose stays)
        var jLean = 0f; var jDrop = 0f
        if (p.jumpW > 0.001f && p.jumpT >= 0f && (p.mode == CharPose.RIFLE || p.mode == CharPose.RELAXED)) {
            val jw = p.jumpW.coerceIn(0f, 1f); val j = jv
            JumpAnim.sample(p.jumpT, j)
            la += (j[0] - la) * jw; lz += (j[1] - lz) * jw; lk += (j[2] - lk) * jw
            ra += (j[3] - ra) * jw; rz += (j[4] - rz) * jw; rk += (j[5] - rk) * jw
            if (!rifle) {
                armLx += (j[6] - armLx) * jw; armLz += (j[7] - armLz) * jw; foreLx += (j[8] - foreLx) * jw; foreLz += (j[9] - foreLz) * jw
                armRx += (j[10] - armRx) * jw; armRz += (j[11] - armRz) * jw; foreRx += (j[12] - foreRx) * jw; foreRz += (j[13] - foreRz) * jw
            }
            jLean = j[14] * jw * (if (rifle) 0.45f else 0.8f)
            if (p.jumpDrop > 0f) {
                val yl = footY(la, lz, lk); val yr = footY(ra, rz, rk)
                jDrop = ((1.04f + min(yl, yr)).coerceIn(0f, 0.7f)) * p.jumpDrop
            }
        }
        if (cw > 0.01f) { val d = (1.04f + min(footY(la, lz, lk), footY(ra, rz, rk))).coerceIn(0f, 0.75f); if (d > jDrop) jDrop = d }
        // FEET ON THE GROUND (fixed): the hips are lowered so the lowest foot touches the floor - fully for the boxing stance, partly while jogging / sprinting
        // (the rest is the small flight phase of a run). Walking keeps the old look (no lowering).
        if (onFoot && cw < 0.01f && pr < 0.01f && p.jumpW < 0.001f) {
            val lockK = if (p.mode == CharPose.BOX) (1f - sstep(p.speed / 1.5f)) else 0f
            if (lockK > 0.001f) { val d = (1.04f + min(footY(la, lz, lk), footY(ra, rz, rk))).coerceIn(0f, 0.75f) * lockK; if (d > jDrop) jDrop = d }
        }
        // RUN BOUNCE (fix): while jogging / sprinting the body is lowest at mid-stance (legs under the hips) and rises into the flight phase
        // when the legs are spread (|sin| = 1) - 5 cm when jogging, 10 cm when sprinting. This replaces the old constant hip lowering.
        if (onFoot && cw < 0.01f && pr < 0.01f && p.jumpW < 0.001f) jDrop -= (0.05f * jog + 0.05f * spr) * kotlin.math.abs(s0) * rm
        p.outLean = jLean + cLean; p.outDrop = jDrop

        val lean = jLean + cLean
        val hipY = (1.04f - jDrop) * (1f - pr) + (0.17f * (1f - su) + 0.22f * su) * pr
        // root = hip height + prone rotation about the hip; upper = root + torso lean about the hip
        Matrix.setIdentityM(rt, 0); Matrix.translateM(rt, 0, 0f, hipY, 0f); if (pr > 0f) rotX(rt, (Math.PI.toFloat() / 2f) * pr * (2f * su - 1f)); Matrix.translateM(rt, 0, 0f, -1.04f, 0f)
        System.arraycopy(rt, 0, up, 0, 16)
        if (lean != 0f || rollZ != 0f || twY != 0f) { Matrix.translateM(up, 0, 0f, 1.04f, 0f); rotX(up, lean); rotZ(up, rollZ); rotY(up, twY); Matrix.translateM(up, 0, 0f, -1.04f, 0f) }
        System.arraycopy(up, 0, lm, 0, 16)

        // body + head
        System.arraycopy(lm, 0, w, 0, 16); rotY(w, -tw); emit(out, 0, w)
        System.arraycopy(lm, 0, w, 0, 16); rotY(w, -tw); Matrix.translateM(w, 0, CharRig.NECK[0], CharRig.NECK[1], CharRig.NECK[2]); rotY(w, p.headYaw - twY * 0.75f); rotX(w, p.headPitch - lean * 0.85f + (1.49f * (1f - su) + 0.55f * su) * pr); emit(out, 11, w)
        // legs (follow the root, not the torso lean)
        for (s in 0..1) {
            val sx = if (s == 0) -0.12f else 0.12f
            System.arraycopy(rt, 0, g, 0, 16); Matrix.translateM(g, 0, sx, 1.04f, 0f); rotX(g, if (s == 0) la else ra); rotZ(g, if (s == 0) lz else rz)
            emit(out, 1 + s, g)
            System.arraycopy(g, 0, w, 0, 16); Matrix.translateM(w, 0, 0f, -0.5f, -0.015f); rotX(w, if (s == 0) lk else rk)
            emit(out, 3 + s, w)
        }
        // arms
        for (s in 0..1) {
            val sx = if (s == 0) -0.27f else 0.27f
            System.arraycopy(lm, 0, a, 0, 16); Matrix.translateM(a, 0, sx * cos(tw), 1.67f, sx * sin(tw)); rotX(a, if (s == 0) armLx else armRx); rotZ(a, if (s == 0) armLz else armRz)
            if (rifle) {
                System.arraycopy(a, 0, w, 0, 16); quat(w, aUq[s]); emit(out, 5 + s, w)
                System.arraycopy(a, 0, w, 0, 16); Matrix.translateM(w, 0, aE1[s][0], aE1[s][1], aE1[s][2]); quat(w, aFq[s])
                emit(out, 7 + s, w); emit(out, 9 + s, w)
            } else {
                emit(out, 5 + s, a)
                System.arraycopy(a, 0, w, 0, 16); Matrix.translateM(w, 0, elbow[s][0], elbow[s][1], elbow[s][2]); rotX(w, if (s == 0) foreLx else foreRx); rotZ(w, if (s == 0) foreLz else foreRz)
                emit(out, 7 + s, w); emit(out, 9 + s, w)
            }
        }
        // gun attaches to the shoulders: it turns with the arms (aim pitch), the lean, the crouch and the prone pose
        p.hasGun = rifle
        if (rifle) { System.arraycopy(lm, 0, p.gunFrame, 0, 16); Matrix.translateM(p.gunFrame, 0, 0f, 1.67f, 0f); rotX(p.gunFrame, (armLx + armRx) * 0.5f) }
    }
}

/** one arm solution per gun (2-bone IK, baked by tools/): [0] = left arm (upperL / foreL), [1] = right arm. Hand centres land on the right-hand grip and under the handguard. */
class GunArms(val uq: Array<FloatArray>, val fq: Array<FloatArray>, val e1: Array<FloatArray>)

/**
 * Where the gun sits relative to the shoulder pivot (gunFrame): stock tucked into the right shoulder, right hand on the pistol grip, left hand under the handguard.
 * Every gun has its own origin depth (oz) and its own baked arm pose (GunArms), so the hands really touch the grip / handguard of THAT gun.
 */
object GunHold {
    const val OX = 0.15f; const val OY = -0.19f
    private val OZ = hashMapOf(
        "mp5" to -0.3113f,
        "ump" to -0.3537f,
        "mp40" to -0.3495f,
        "vector" to -0.2560f,
        "bizon" to -0.3580f,
        "p90" to -0.2390f,
        "vss" to -0.4387f,
        "vsk94" to -0.3877f,
        "m590" to -0.3750f,
        "kar98k" to -0.4260f,
        "m24" to -0.4600f,
        "awm" to -0.4813f,
        "m82" to -0.5663f
    )
    fun oz(id: String) = OZ[id] ?: -0.34f
    private val ARMS = hashMapOf<String, GunArms>(
        "mp5" to GunArms(
            arrayOf(floatArrayOf(0.298503f, 0.279536f, 0.324246f, 0.853006f), floatArrayOf(-0.056072f, 0.025088f, 0.175555f, 0.982551f)),
            arrayOf(floatArrayOf(0.357417f, 0.073666f, 0.707456f, 0.605254f), floatArrayOf(0.665862f, 0.361031f, -0.292679f, 0.583629f)),
            arrayOf(floatArrayOf(0.118590f, -0.272225f, -0.220749f), floatArrayOf(0.182178f, -0.317369f, 0.054666f))),
        "ump" to GunArms(
            arrayOf(floatArrayOf(0.338457f, 0.282322f, 0.375893f, 0.815136f), floatArrayOf(-0.029211f, 0.017146f, 0.157108f, 0.987000f)),
            arrayOf(floatArrayOf(0.369875f, 0.106159f, 0.665061f, 0.640012f), floatArrayOf(0.690542f, 0.352309f, -0.269925f, 0.571114f)),
            arrayOf(floatArrayOf(0.136878f, -0.232432f, -0.253258f), floatArrayOf(0.169453f, -0.326668f, 0.038387f))),
        "mp40" to GunArms(
            arrayOf(floatArrayOf(0.323838f, 0.282885f, 0.355729f, 0.829796f), floatArrayOf(0.047944f, 0.000422f, 0.115958f, 0.992096f)),
            arrayOf(floatArrayOf(0.366316f, 0.093433f, 0.681667f, 0.626429f), floatArrayOf(0.740476f, 0.330639f, -0.224160f, 0.540486f)),
            arrayOf(floatArrayOf(0.129561f, -0.248412f, -0.241672f), floatArrayOf(0.140946f, -0.341880f, -0.012349f))),
        "vector" to GunArms(
            arrayOf(floatArrayOf(0.262863f, 0.266509f, 0.286004f, 0.882087f), floatArrayOf(-0.055730f, 0.024987f, 0.175377f, 0.982605f)),
            arrayOf(floatArrayOf(0.340061f, 0.050071f, 0.738765f, 0.579723f), floatArrayOf(0.646304f, 0.350820f, -0.304561f, 0.605358f)),
            arrayOf(floatArrayOf(0.105981f, -0.299077f, -0.190317f), floatArrayOf(0.182054f, -0.317475f, 0.054461f))),
        "bizon" to GunArms(
            arrayOf(floatArrayOf(0.339907f, 0.281167f, 0.379733f, 0.813149f), floatArrayOf(0.051460f, -0.000188f, 0.113160f, 0.992243f)),
            arrayOf(floatArrayOf(0.367178f, 0.112319f, 0.656348f, 0.649439f), floatArrayOf(0.707886f, 0.313724f, -0.242696f, 0.584442f)),
            arrayOf(floatArrayOf(0.138877f, -0.229871f, -0.254504f), floatArrayOf(0.139015f, -0.342574f, -0.014761f))),
        "p90" to GunArms(
            arrayOf(floatArrayOf(0.226177f, 0.244930f, 0.250592f, 0.908877f), floatArrayOf(-0.044132f, 0.021379f, 0.166968f, 0.984742f)),
            arrayOf(floatArrayOf(0.314081f, 0.038077f, 0.754834f, 0.574569f), floatArrayOf(0.673304f, 0.354948f, -0.284576f, 0.582830f)),
            arrayOf(floatArrayOf(0.093923f, -0.320951f, -0.158332f), floatArrayOf(0.176267f, -0.321820f, 0.047557f))),
        "vss" to GunArms(
            arrayOf(floatArrayOf(0.370367f, 0.273548f, 0.426754f, 0.778384f), floatArrayOf(0.161560f, -0.013231f, 0.064105f, 0.984690f)),
            arrayOf(floatArrayOf(0.372433f, 0.138087f, 0.622632f, 0.674206f), floatArrayOf(0.709048f, 0.268262f, -0.221407f, 0.613405f)),
            arrayOf(floatArrayOf(0.156269f, -0.189914f, -0.276428f), floatArrayOf(0.105340f, -0.342559f, -0.091961f))),
        "vsk94" to GunArms(
            arrayOf(floatArrayOf(0.370367f, 0.273548f, 0.426754f, 0.778384f), floatArrayOf(0.089078f, -0.005834f, 0.095305f, 0.991437f)),
            arrayOf(floatArrayOf(0.372433f, 0.138087f, 0.622632f, 0.674206f), floatArrayOf(0.705644f, 0.295423f, -0.236564f, 0.599024f)),
            arrayOf(floatArrayOf(0.156269f, -0.189914f, -0.276428f), floatArrayOf(0.126710f, -0.345223f, -0.040814f))),
        "m590" to GunArms(
            arrayOf(floatArrayOf(0.295736f, 0.278861f, 0.321041f, 0.855400f), floatArrayOf(0.047944f, 0.000422f, 0.115958f, 0.992096f)),
            arrayOf(floatArrayOf(0.356259f, 0.071667f, 0.710077f, 0.603104f), floatArrayOf(0.740476f, 0.330639f, -0.224160f, 0.540486f)),
            arrayOf(floatArrayOf(0.117504f, -0.274566f, -0.218417f), floatArrayOf(0.140946f, -0.341880f, -0.012349f))),
        "kar98k" to GunArms(
            arrayOf(floatArrayOf(0.368197f, 0.276073f, 0.420254f, 0.782049f), floatArrayOf(0.047983f, 0.000418f, 0.116230f, 0.992063f)),
            arrayOf(floatArrayOf(0.376112f, 0.130565f, 0.633509f, 0.663444f), floatArrayOf(0.756956f, 0.338075f, -0.213218f, 0.516974f)),
            arrayOf(floatArrayOf(0.152962f, -0.194744f, -0.274914f), floatArrayOf(0.141133f, -0.341802f, -0.012374f))),
        "m24" to GunArms(
            arrayOf(floatArrayOf(0.375081f, 0.272955f, 0.432311f, 0.773251f), floatArrayOf(0.089256f, -0.005922f, 0.097268f, 0.991230f)),
            arrayOf(floatArrayOf(0.375841f, 0.138169f, 0.623311f, 0.671667f), floatArrayOf(0.737943f, 0.310573f, -0.219668f, 0.557431f)),
            arrayOf(floatArrayOf(0.157645f, -0.184260f, -0.279457f), floatArrayOf(0.128082f, -0.344708f, -0.040884f))),
        "awm" to GunArms(
            arrayOf(floatArrayOf(0.362284f, 0.275373f, 0.415403f, 0.787629f), floatArrayOf(0.188799f, -0.014887f, 0.052740f, 0.980485f)),
            arrayOf(floatArrayOf(0.369043f, 0.134433f, 0.626761f, 0.672983f), floatArrayOf(0.707843f, 0.257902f, -0.216838f, 0.620827f)),
            arrayOf(floatArrayOf(0.152600f, -0.200417f, -0.271010f), floatArrayOf(0.097570f, -0.339113f, -0.111276f))),
        "m82" to GunArms(
            arrayOf(floatArrayOf(0.352288f, 0.274202f, 0.406633f, 0.797093f), floatArrayOf(0.220255f, -0.015591f, 0.037022f, 0.974615f)),
            arrayOf(floatArrayOf(0.358047f, 0.138898f, 0.617699f, 0.686264f), floatArrayOf(0.689645f, 0.238144f, -0.216596f, 0.648662f)),
            arrayOf(floatArrayOf(0.151517f, -0.210087f, -0.264208f), floatArrayOf(0.086682f, -0.333937f, -0.133687f)))
    )
    fun arms(id: String?): GunArms? = if (id == null) null else ARMS[id]
}

/** GPU skinning shader + draw call for CharModel. Lighting / fog are the same formulas as the map shader. */
class CharRenderer(private val ctx: Context) {
    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec2 aUV; layout(location=3) in vec4 aSI; layout(location=4) in vec4 aSW;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uBones[12]; uniform vec3 uEye;
out vec3 vN; out vec2 vUV; out float vD;
void main(){
  mat4 sk = aSW.x*uBones[int(aSI.x+0.5)] + aSW.y*uBones[int(aSI.y+0.5)] + aSW.z*uBones[int(aSI.z+0.5)] + aSW.w*uBones[int(aSI.w+0.5)];
  vec4 lp = sk*vec4(aPos,1.0); vec3 ln = mat3(sk)*aNor;
  vec4 w = uModel*lp; gl_Position = uVP*w; vN = mat3(uModel)*ln; vUV = aUV; vD = distance(w.xyz,uEye);
}"""
    private val FS = """#version 300 es
precision highp float;
in vec3 vN; in vec2 vUV; in float vD;
uniform sampler2D uTex; uniform float uFog; uniform vec3 uTint;
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
out vec4 o;
void main(){
  vec3 a = texture(uTex,vUV).rgb * uTint;
  vec3 n = normalize(vN); if(!gl_FrontFacing) n=-n;
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = a*(hemi*0.62 + vec3(1.0,0.91,0.78)*max(dot(n,SUN),0.0)*0.95) + vec3(0.12,0.115,0.10)*a;
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, vec3(0.74,0.84,0.93), f*f), 1.0);
}"""
    private var prog = 0
    private var uVP = 0; private var uModel = 0; private var uBones = 0; private var uEye = 0; private var uFog = 0; private var uTint = 0; private var uTex = 0
    private val models = HashMap<String, CharModel?>()
    private val bones = FloatArray(CharRig.NB * 16)
    private val cloth = ClothRenderer(ctx)

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Char shader: " + GL.glGetShaderInfoLog(s))
        return s
    }

    /** call on the GL thread (onSurfaceCreated). A model that fails to load is simply not drawn (callers fall back to boxes). */
    fun init() {
        models.clear(); cloth.init()
        try {
            prog = GL.glCreateProgram()
            GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
            GL.glLinkProgram(prog)
            uVP = GL.glGetUniformLocation(prog, "uVP"); uModel = GL.glGetUniformLocation(prog, "uModel"); uBones = GL.glGetUniformLocation(prog, "uBones")
            uEye = GL.glGetUniformLocation(prog, "uEye"); uFog = GL.glGetUniformLocation(prog, "uFog"); uTint = GL.glGetUniformLocation(prog, "uTint"); uTex = GL.glGetUniformLocation(prog, "uTex")
        } catch (e: Throwable) { prog = 0; return }
        for (id in arrayOf("survivor")) models[id] = try { CharModel(ctx, id) } catch (e: Throwable) { null }
    }

    val ready get() = prog != 0
    fun has(charId: String) = models[fileOf(charId)] != null
    /** Characters.ALL ids -> asset ids */
    fun fileOf(charId: String) = charId
    fun vertices(charId: String) = models[fileOf(charId)]?.vertexCount ?: 0

    /**
     * Draw one character. `model` = world matrix of the character root (position + yaw + scale already applied by the caller).
     * Leaves the skinning program bound: callers must glUseProgram(their own program) afterwards.
     */
    fun draw(charId: String, vp: FloatArray, model: FloatArray, ex: Float, ey: Float, ez: Float, fog: Float, pose: CharPose, tr: Float = 1f, tg: Float = 1f, tb: Float = 1f, wear: String? = null, pants: String? = Wear.pants) {
        val fid = fileOf(charId)
        val m = models[fid] ?: return
        m.skin(bones, pose)
        // store top: hide / trim the character's own jacket, draw the top + bare arms afterwards
        val wcfg = if (wear != null && cloth.has(wear)) Cloth.CFG[fid] else null
        val pcfg = if (pants != null && cloth.has(pants)) Cloth.CFG[fid] else null   // store pants: the character's own trousers are hidden
        GL.glUseProgram(prog)
        GL.glUniformMatrix4fv(uVP, 1, false, vp, 0); GL.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GL.glUniformMatrix4fv(uBones, CharRig.NB, false, bones, 0)
        GL.glUniform3f(uEye, ex, ey, ez); GL.glUniform1f(uFog, fog); GL.glUniform3f(uTint, tr, tg, tb); GL.glUniform1i(uTex, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        for ((pi, p) in m.prims.withIndex()) {
            var vao = p.vao; var cnt = p.count
            if (pcfg != null && pcfg.pantsHide.contains(pi)) continue
            if (wcfg != null) {
                if (wcfg.hide.contains(pi)) continue
                val kind = wcfg.cut[pi]
                if (kind != null) { val a = m.ensureCut(p, kind); if (a != null) { vao = a[0]; cnt = a[1] } }
            }
            GL.glBindTexture(GL.GL_TEXTURE_2D, m.texIds.getOrElse(p.mat) { 0 })
            GL.glBindVertexArray(vao); GL.glDrawElements(GL.GL_TRIANGLES, cnt, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glBindVertexArray(0)
        if (wcfg != null || pcfg != null) cloth.draw(if (wcfg != null) wear else null, if (pcfg != null) pants else null, fid, bones, vp, model, ex, ey, ez, fog)
    }
}
