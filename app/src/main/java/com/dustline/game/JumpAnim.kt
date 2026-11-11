package com.dustline.game

import kotlin.math.floor
import kotlin.math.min

/**
 * JUMP animation of the player's rigged character (third-person view).
 *
 * Motion data = JumpData.kt (baked by tools/bake_jump.py from "Human character with jumping animation" by LukeDale65,
 * CC-BY-4.0, https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13 ;
 * only the joint rotations were sampled and re-targeted to the game's own 12-bone rig - credit + changes notice are in
 * CREDITS.md and in-game Settings > Account > Credits & Licenses).
 *
 * Clip timeline (seconds of the original clip): 0.86 take-off, 1.19 apex, 1.48 touch-down, 2.10 end of the landing recovery.
 * The air time of the game's jump (vy 6.2 m/s, g 20 m/s^2 = 0.62 s) equals the air time of the clip (1.48 - 0.86 = 0.62 s),
 * so the clip is played 1:1 and is driven by the vertical speed (it stays in sync with the physics, ceiling bumps included).
 */
object JumpAnim {
    const val TAKEOFF = 0.86f
    const val TOUCHDOWN = 1.48f
    const val END = 2.10f
    const val JUMP_VY = 6.2f       // GameRenderer.step(): vy = 6.2f
    const val GRAV = 20f           // GameRenderer.step(): vy -= 20f * dt

    /** Interpolated joint angles of the baked clip at clip time [t] -> out[0 until JumpData.COLS] (same column order as JumpData). */
    fun sample(t: Float, out: FloatArray) {
        val f = ((t - JumpData.T0) * JumpData.FPS).coerceIn(0f, (JumpData.N - 1).toFloat())
        val i0 = floor(f).toInt(); val i1 = min(i0 + 1, JumpData.N - 1); val a = f - i0
        val c = JumpData.COLS
        for (k in 0 until c) out[k] = JumpData.D[i0 * c + k] * (1f - a) + JumpData.D[i1 * c + k] * a
    }
}

/**
 * State machine: in the air the clip follows the vertical speed, after the touch-down the landing absorb + recovery plays for LAND_DUR seconds.
 * Outputs (read by GameRenderer.drawPlayerBody -> CharPose): t = clip time, w = blend 0..1 into the normal walk / rifle pose,
 * drop = 0..1 how much the hips are lowered so the feet stay on the ground while the knees bend (landing only).
 */
class JumpAnimState {
    var t = -1f; var w = 0f; var drop = 0f
    private var air = false; private var sinceAir = 0f; private var w0 = 0f; private var lastVy = 0f
    private var landT = -1f; private var landAmp = 1f

    fun reset() { air = false; landT = -1f; t = -1f; w = 0f; drop = 0f; sinceAir = 0f; w0 = 0f }

    private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    /** @param enabled false while swimming / prone / dead or in the aircraft: the animation is cleared */
    fun update(dt: Float, onGround: Boolean, vy: Float, speed: Float, enabled: Boolean) {
        if (!enabled) { reset(); return }
        if (!onGround) {
            if (!air) { air = true; sinceAir = 0f; w0 = w; landT = -1f }
            sinceAir += dt; lastVy = vy
            t = JumpAnim.TAKEOFF + ((JumpAnim.JUMP_VY - vy) / JumpAnim.GRAV).coerceIn(0f, JumpAnim.TOUCHDOWN - JumpAnim.TAKEOFF)
            w = w0 + (1f - w0) * smooth(sinceAir / 0.07f); drop = 0f
            return
        }
        if (air) {            // touch-down: running landings only dip a little, a standing / hard landing uses the full clip
            air = false; landT = 0f; landAmp = if (speed > 1.2f && lastVy > -9f) 0.4f else 1f
        }
        if (landT >= 0f) {
            landT += dt
            val u = landT / LAND_DUR
            if (u >= 1f) { reset(); return }
            t = JumpAnim.TOUCHDOWN + u * (JumpAnim.END - JumpAnim.TOUCHDOWN)
            val amp = 1f + (landAmp - 1f) * smooth(u / 0.15f)
            w = amp * (1f - smooth((u - 0.55f) / 0.45f))
            drop = (landT / 0.06f).coerceIn(0f, 1f)
        } else { t = -1f; w = 0f; drop = 0f }
    }

    companion object { const val LAND_DUR = 0.42f }
}
