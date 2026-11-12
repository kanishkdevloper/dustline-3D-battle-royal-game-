package com.dustline.game

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 28 - state holders for the things the Phase 27 audit still listed as missing against the HTML:
 *   AirVis    skate-stance freefall + skateboard + the smoothed air pose (descend() / dskAirPose() / dskSm() of the HTML)
 *   LootCrate the wooden loot box that pops out of a dead bot (dxMakeCrate)
 *   MagDrop   the magazine that falls out of the gun on reload (dropMag of the reference pack)
 *   Sway      the breathing / hand drift numbers of the reference pack
 * No GL in here - plain math only, the renderer draws it.
 */

/** One wooden loot box (dxMakeCrate). The items are inside; they can be taken only while you are near and the box has popped up. */
class LootCrate(val x: Float, val y: Float, val z: Float, val yaw: Float) {
    val items = ArrayList<Loot>()
    var t = 0f            // seconds since the bot died
    var empty = 0f        // seconds since the last item was taken
    var scale = 0.001f    // pop-in / pop-out scale of the whole box
    var grow = 0f         // 0..1 after the 0.8 s delay; items can be taken above 0.9
    var open = false      // you are near + the box is grown + it still holds items
    var fx = false        // ring + light beam visible
    var hinted = false    // the "LOOT BOX" hint was shown once
}

/** A magazine that falls out on reload: 0.03 x 0.13 x 0.07 m box, tumbles, bounces, rests, then shrinks away (5 s). */
class MagDrop(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float,
              var wx: Float, var wy: Float, var wz: Float, var rx: Float, var ry: Float, var rz: Float) {
    var life = 5f
    var rest = false
}

/** Breathing / hand drift of the reference pack. out = [breath, driftX, driftY]; aimF = 0.4 while aiming, otherwise 1. */
object Sway {
    fun calc(t: Float, id: Float, aimF: Float, out: FloatArray) {
        out[0] = sin(t * 1.7f + id) * 0.0045f * aimF
        out[1] = (sin(t * 0.9f) * 0.0035f + sin(t * 2.3f) * 0.0016f) * aimF
        out[2] = sin(t * 1.3f + 1.7f) * 0.002f * aimF
    }
}

/**
 * Visual state of ONE fighter in the air (the player and every bot own one). Port of the animation half of descend() plus
 * dskAirPose() of the HTML: skate stance (board under the feet, body side-on), head-first dive and the parachute hang are all
 * driven by the same smoothed pose, so they blend into each other instead of snapping.
 * Call step() every frame while the fighter is in freefall or under the canopy; read rot* / off* / boardK / drop / cp to draw.
 */
class AirVis {
    /** the pose the character renderer draws (mode AIR, every angle explicit). Owned here so it survives until the draw call. */
    val cp = CharPose()

    private val sv = FloatArray(16)
    private val has = BooleanArray(16)
    private var hdInit = false
    private var turn = 0f
    private var bank = 0f
    private var nose = 0f

    var hd = 0f            // heading = travel direction (HTML e.hd, atan2(-vx, -vz))
    var yo = 0f            // side-on offset: 0 = facing travel direction, PI/2 = skate stance
    var airPitch = 0f
    var boardK = 0f        // 0..1: skateboard scale / visibility
    var drop = 0f          // bent legs are shorter: the body sinks so the feet stay on the board
    var skating = false

    // model root: rotation (radians, YXZ order like the HTML) and the offset added to the fighter's position
    var rotX = 0f; var rotY = 0f; var rotZ = 0f
    var offX = 0f; var offY = 0f; var offZ = 0f

    fun reset() {
        java.util.Arrays.fill(has, false)
        hdInit = false; turn = 0f; bank = 0f; nose = 0f
        hd = 0f; yo = 0f; airPitch = 0f; boardK = 0f; drop = 0f; skating = false
        rotX = 0f; rotY = 0f; rotZ = 0f; offX = 0f; offY = 0f; offZ = 0f
    }

    private fun kk(d: Float, r: Float): Float = 1f - exp(-d * r)

    private fun sm(d: Float, i: Int, tg: Float, r: Float): Float {
        if (!has[i]) { has[i] = true; sv[i] = tg }
        sv[i] += (tg - sv[i]) * (1f - exp(-d * r))
        return sv[i]
    }

    private fun legY(a: Float, b: Float, c: Float): Float =
        1.04f - (0.5f * cos(b) * cos(a) + 0.54f * (cos(c) * cos(b) * cos(a) - sin(c) * sin(a)))

    /**
     * skate = freefall without the dive, diving = head-first freefall, para = under the canopy (fl = landing flare 0..1).
     * isPlayer: the player's dive pitch follows the real velocity, bots dive at a fixed angle (HTML: -2.3).
     * v* = velocity in m/s, t = clock in seconds (only used for the little arm wobble).
     */
    fun step(dt: Float, skate: Boolean, diving: Boolean, para: Boolean, fl: Float, isPlayer: Boolean,
             vx: Float, vy: Float, vz: Float, t: Float) {
        val d = if (dt < 0.0005f) 0.0005f else dt
        skating = skate
        val hs = sqrt(vx * vx + vz * vz)
        // heading follows the travel direction; turn = how fast it changes (the board banks into the turn)
        if (!hdInit || hs > 0.25f) {
            val tt = atan2(-vx, -vz)
            if (!hdInit) { hd = tt; hdInit = true; turn = 0f }
            val s = atan2(sin(tt - hd), cos(tt - hd)) * kk(d, 7f)
            hd += s
            turn += (s / d - turn) * kk(d, 6f)
        }
        yo += ((if (skate) 1.5707964f else 0f) - yo) * kk(d, 7f)
        if (skate) {
            val bT = (-turn * 0.3f).coerceIn(-0.5f, 0.5f)
            val nT = ((vy + 9f) * 0.03f).coerceIn(-0.3f, 0.25f)
            bank += (bT - bank) * kk(d, 5f)
            nose += (nT - nose) * kk(d, 5f)
            airPitch += (bank - airPitch) * kk(d, 8f)
            rotX = airPitch; rotZ = nose
        } else {
            val tp: Float = if (para) -0.08f + 0.35f * fl
            else if (isPlayer) -(1.5707964f + atan2(-vy, hs + 0.01f).coerceIn(0.8f, 1.5f))
            else -2.3f
            airPitch += (tp - airPitch) * kk(d, if (diving) 5f else 4f)
            rotX = airPitch
            rotZ += (0f - rotZ) * kk(d, 6f)
        }
        rotY = hd + yo
        val wob = sin(t * 2.3f + hd) * 0.07f
        pose(d, skate, diving, fl, wob)
        boardK += ((if (skate) 1f else 0f) - boardK) * kk(d, if (skate) 6f else 10f)
        val th = if (skate) 0f else rotX
        val ph = rotY
        offX = -sin(th) * sin(ph)
        offY = 1f - cos(th) + 0.22f * boardK - drop
        offZ = -sin(th) * cos(ph)
    }

    /** dskAirPose: torso twist, arms, legs (all smoothed). Writes into cp. */
    private fun pose(d: Float, skate: Boolean, diving: Boolean, fl: Float, wob: Float) {
        cp.mode = CharPose.AIR
        cp.speed = 0f; cp.crouch = 0f; cp.sprint = false; cp.aimPitch = 0f
        // torso turns toward the travel direction, the head turns the rest of the way: body side-on, face looks ahead
        val tw = sm(d, 0, if (skate) 0.5f else 0f, 5f)
        val hw = sm(d, 1, if (skate) 0.9f else 0f, 6f)
        cp.exTw = tw; cp.headYaw = -hw
        if (diving) {
            cp.exArmRz = sm(d, 2, 2.7f, 99f); cp.exArmLz = sm(d, 3, -2.7f, 99f)
            cp.exArmRx = sm(d, 4, 0f, 99f); cp.exArmLx = sm(d, 5, 0f, 99f)
            cp.exForeRx = sm(d, 6, 0f, 99f); cp.exForeLx = sm(d, 7, 0f, 99f)
            cp.exForeRz = sm(d, 8, 0f, 99f); cp.exForeLz = sm(d, 9, 0f, 99f)
        } else {
            val up = 2.8f - 0.2f * fl
            cp.exArmRz = sm(d, 2, if (skate) 1.15f + wob else up, 5f)
            cp.exArmLz = sm(d, 3, if (skate) -1.55f - wob else -up, 5f)
            cp.exArmRx = sm(d, 4, 0f, 6f); cp.exArmLx = sm(d, 5, 0f, 6f)
            cp.exForeRx = sm(d, 6, if (skate) -0.18f else -0.2f, 6f)
            cp.exForeLx = sm(d, 7, if (skate) -0.1f else -0.2f, 6f)
            cp.exForeRz = sm(d, 8, if (skate) 0f else 0.35f, 6f)
            cp.exForeLz = sm(d, 9, if (skate) 0f else -0.35f, 6f)
        }
        // legs: skate = wide stance along the board, knees bent, front foot (right) leads; chute = relaxed hang, legs swing forward on flare
        val la: Float; val lb: Float; val lc: Float; val ra: Float; val rb: Float; val rc: Float
        if (skate) { la = 0.35f; lb = -0.36f; lc = -0.7f; ra = 0.5f; rb = 0.36f; rc = -1f }
        else if (diving) { la = 0.12f; lb = 0f; lc = 0f; ra = 0.12f; rb = 0f; rc = 0f }
        else { la = 0.22f + 0.25f * fl; lb = -0.04f; lc = -0.25f - 0.4f * fl; ra = 0.12f + 0.25f * fl; rb = 0.04f; rc = -0.2f - 0.4f * fl }
        val rt = if (diving) 99f else 6f
        cp.exLegLx = sm(d, 10, la, rt); cp.exLegLz = sm(d, 11, lb, rt); cp.exKneeL = sm(d, 12, lc, rt)
        cp.exLegRx = sm(d, 13, ra, rt); cp.exLegRz = sm(d, 14, rb, rt); cp.exKneeR = sm(d, 15, rc, rt)
        drop = (legY(cp.exLegLx, cp.exLegLz, cp.exKneeL) + legY(cp.exLegRx, cp.exLegRz, cp.exKneeR)) * 0.5f
    }
}
