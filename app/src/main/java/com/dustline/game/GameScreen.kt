package com.dustline.game

import android.content.Context
import android.graphics.*
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

/** GL map view + touch HUD: stick = move (body turns to the stick direction, mobile BR style), drag right = rotate camera only, FIRE / RELOAD / JUMP / RUN, weapon chip, EXIT. */
class GameScreen(ctx: Context, map: MapData, sfx: SoundFx?, private val onExit: (MatchResult?) -> Unit, private val onResult: (MatchResult) -> Unit, private val onRestart: () -> Unit, private val onSettings: () -> Unit, warm: Boolean = false, private val onWarmEnd: () -> Unit = {}, private val onFinish: (MatchSummary) -> Unit = {}) : FrameLayout(ctx) {
    private val renderer = GameRenderer(ctx, map, sfx)
    private var warmHandled = false
    init { if (warm) renderer.startWarmup() }
    private val mapImg = MapImage(ctx, map)
    private val gl = GLSurfaceView(ctx)
    private val hud: Hud

    init {
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        HudIcons.preload(ctx)
        hud = Hud(ctx)
        addView(gl, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // display cutout (notch / punch hole) + rounded corners: keep every HUD button inside the safe area
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            applySafe(s.left, s.right)
            insets
        }
    }

    private fun applySafe(l: Int, r: Int) {
        val lim = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        val a = l.coerceIn(0, lim); val b = r.coerceIn(0, lim)
        val lp = hud.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == a && lp.rightMargin == b) return
        lp.leftMargin = a; lp.rightMargin = b
        hud.layoutParams = lp
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ViewCompat.requestApplyInsets(this) }

    fun setCharacter(id: String) { renderer.playerChar = id }
    /** true while the real match is still in the plane / freefall / parachute (not the warm-up, not landed yet) */
    fun inAir(): Boolean = !renderer.warm && renderer.airState != 3
    /** true during the 30 s PRE-MATCH warm-up (before the real match starts) - the only time the match ad may be shown */
    fun inWarmup(): Boolean = renderer.warm && !renderer.warmEnded
    /** real match running: no warm-up, on the ground, not over / spectating (used by the in-match banner ads) */
    fun inLiveMatch(): Boolean = !renderer.warm && renderer.matchState == 0 && renderer.airState == 3 && renderer.playerAlive && !renderer.spec
    fun matchSeconds(): Float = renderer.matchTime
    fun onPause() { resetInput(); renderer.silence(); gl.onPause() }
    /** forget every finger and every held input (app paused, settings opened, window lost focus) so nothing stays "stuck" */
    private fun resetInput() {
        renderer.firing = false; renderer.moveX = 0f; renderer.moveY = 0f; renderer.run = false
        renderer.clearLook()
        hud.clearTouch()
    }
    fun onResume() = gl.onResume()
    /** Android back during the WINNER celebration / spectate: go straight to the result screen (true = handled) */
    fun finishNow(): Boolean { if (renderer.matchState == 0) return false; hud.finishNow(); return true }
    /** pause the simulation while the in-match settings are open */
    fun setPaused(p: Boolean) { renderer.paused = p; if (p) resetInput() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyGfx()
    }

    /** Graphics setting: render resolution of the 3D view (low 60 %, mid 85 %, high 100 %). */
    fun applyGfx() {
        renderer.resetPerf()
        val sc = when (Prefs.gfx) { "low" -> 0.6f; "mid" -> 0.85f; else -> 1f }
        if (width <= 0 || height <= 0) return
        if (sc >= 1f) gl.holder.setSizeFromLayout() else gl.holder.setFixedSize((width * sc).toInt().coerceAtLeast(320), (height * sc).toInt().coerceAtLeast(180))
    }

    private inner class Hud(c: Context) : View(c) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var stickId = -1; private var lookId = -1
        private var exitArmedAt = 0L   // EXIT must be tapped twice within 2.5 s (no accidental quit)
        private var sx = 0f; private var sy = 0f; private var kx = 0f; private var ky = 0f
        private var lx = 0f; private var ly = 0f
        private val exitR = RectF(); private val gunR = RectF(); private val listR = RectF()
        private val slotR = arrayOf(RectF(), RectF(), RectF())   // Phase 55: ITEM slots (medkit, grenade, mushroom)
        private val tmpR = RectF()
        private val restartR = RectF(); private val lobbyR = RectF(); private val scopeR = RectF(); private var reported = false
        private val gearR = RectF(); private val mmR = RectF(); private val bigR = RectF(); private val closeR = RectF()
        private val prevR = RectF(); private val nextR = RectF(); private val leaveR = RectF(); private var specLeft = false
        private var mapOpen = false; private var hasPin = false; private var pinX = 0f; private var pinZ = 0f
        private val vib = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        /** forget every finger (called when the settings open, so no stick / look / button stays stuck after they close) */
        fun clearTouch() { if (autoRun) { autoRun = false; renderer.run = false }; stickId = -1; lookId = -1; btnIds.clear(); btnTrack.clear(); renderer.firing = false; renderer.moveX = 0f; renderer.moveY = 0f; invalidate() }
        /** the window lost focus (notification shade, call, split screen, recents): a finger-up will never arrive, so drop all touches now */
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) clearTouch() }
        override fun onDetachedFromWindow() { clearTouch(); super.onDetachedFromWindow() }
        /** finger that pressed a button and may now DRAG the camera: [lastX, lastY, downX, downY, active(0/1)] */
        private val btnTrack = HashMap<Int, FloatArray>()
        /** buttons whose finger also turns the camera: FIRE always, JUMP / RUN / RELOAD / AIM / SIT-PRONE once the finger moved > 10 dp (a plain tap never moves the view) */
        private fun isDragBtn(b: Int) = b == 4 || b == 1 || b == 2 || b == 5 || b == 10 || b == 17
        private fun trackBtn(id: Int, x: Float, y: Float) {
            val b = btnIds[id] ?: return
            if (isDragBtn(b)) btnTrack[id] = floatArrayOf(x, y, x, y, if (b == 4) 1f else 0f)
        }
        private fun dragBtn(id: Int, x: Float, y: Float): Boolean {
            val t = btnTrack[id] ?: return false
            if (t[4] == 0f) {
                if (hypot(x - t[2], y - t[3]) <= 10f * d) return true     // still a tap: view stays still
                t[4] = 1f; t[0] = x; t[1] = y                              // threshold passed: start turning from here (no jump)
                return true
            }
            renderer.look(x - t[0], y - t[1]); t[0] = x; t[1] = y
            return true
        }
        private val btnIds = HashMap<Int, Int>() // pointer -> 1 jump, 2 run, 3 exit, 4 fire, 5 reload, 6/7 prev/next gun, 8 heal, 9 pick up
        private val R get() = 60f * d * HudLayout.scale(46)

        // ---------------------------------------------------------------- Phase 64: CONTEXT HUD (a button exists only while it is useful)
        /** RUN pressed with no finger on the stick = auto-run forward; touching the stick stops it (like mobile BR auto-sprint) */
        private var autoRun = false
        private fun stopAutoRun() { if (autoRun || renderer.run) { autoRun = false; renderer.run = false; if (stickId < 0) { renderer.moveX = 0f; renderer.moveY = 0f } } }
        private fun onRunTap() {
            if (autoRun) { stopAutoRun(); return }
            if (renderer.run) { renderer.run = false; return }              // sprint-with-stick switched off
            if (stickId >= 0) renderer.run = true                           // stick held: sprint in the stick direction
            else { autoRun = true; renderer.run = true; renderer.moveX = 0f; renderer.moveY = -1f }   // stick free: run straight ahead by itself
        }
        private fun startStick(id: Int, x: Float, y: Float) { if (autoRun) stopAutoRun(); stickId = id; sx = x; sy = y; kx = x; ky = y; renderer.moveX = 0f; renderer.moveY = 0f }
        private fun onGroundFoot() = renderer.airState == 3
        private fun isFists() = renderer.gunName == "FISTS"
        /** CHUTE shows only when the parachute may really be opened; DROP only inside the plane; nothing while already under the canopy */
        private fun dropVisible() = renderer.airState == 0 || (renderer.airState == 1 && renderer.chuteReady)
        /** SIT / PRONE are pointless while swimming (unless you are already down, then you must be able to stand up) */
        private fun poseVisible() = onGroundFoot() && (!renderer.swimming || renderer.crouching || renderer.prone)
        private fun runVisible() = onGroundFoot() && !renderer.swimming
        /** DIVE / SURFACE takes the RUN slot, only while you float in deep water (RUN is hidden there anyway) */
        private fun diveVisible() = onGroundFoot() && (renderer.swimming || renderer.diving)
        private fun reloadVisible() = onGroundFoot() && !isFists() && renderer.ammoMax > 0 && (renderer.reloadProgress >= 0f || (renderer.ammoNow < renderer.ammoMax && renderer.ammoReserve > 0))
        private fun aimVisible() = onGroundFoot() && !isFists()
        private fun medkitVisible() = renderer.medkits > 0 || renderer.healProgress >= 0f
        private fun nadeVisible() = renderer.grenadeCount > 0
        private fun shroomVisible() = renderer.mushrooms > 0
        // circle buttons: id, cx, cy, r, label
        private fun circle(id: Int, w: Float, h: Float): FloatArray = HudLayout.circle(id, w, h, d)
        /** draws a HUD block at the player's own position + size (HUD editor ids 48..52): code between wBegin / c.restore() is written for the DEFAULT position */
        private fun wBegin(c: Canvas, id: Int, w: Float, h: Float) {
            val k = HudLayout.circle(id, w, h, d); val b = HudLayout.base(id, w, h, d); val s = HudLayout.scale(id)
            c.save(); c.translate(k[0], k[1]); c.scale(s, s); c.translate(-b[0], -b[1])
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (autoRun && (!renderer.run || renderer.airState != 3 || renderer.swimming || renderer.spec || renderer.matchState != 0)) stopAutoRun()
            if (renderer.spec) { drawSpec(c, w, h); postInvalidateOnAnimation(); return }
            if (renderer.matchState == 1) { drawWinner(c, w, h); postInvalidateOnAnimation(); return }
            if (renderer.spec || renderer.matchState != 0) mapOpen = false
            if (renderer.scopeOverlay) { val sr = h * 0.44f; val sPath = Path(); sPath.addRect(0f, 0f, w, h, Path.Direction.CW); sPath.addCircle(w / 2, h / 2, sr, Path.Direction.CCW); p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawPath(sPath, p) }   // scope mask is drawn FIRST: buttons stay visible on top
            HudLayout.rectOf(40, w, h, d, exitR)
            HudLayout.rectOf(41, w, h, d, gearR)
            if (renderer.airState != 3) gearR.setEmpty()   // plane / freefall / parachute: no settings button
            val vm = renderer.vibMs; if (vm > 0) { renderer.vibMs = 0; buzz(vm) }
            HudLayout.rectOf(43, w, h, d, gunR)
            if (!onGroundFoot()) gunR.setEmpty()   // weapon chip: only on foot
            run {
            if (renderer.airState != 0) {   // the plane has no stick
            // stick
            val jk = HudLayout.circle(46, w, h, d); val cx = if (stickId >= 0) sx else jk[0]; val cy = if (stickId >= 0) sy else jk[1]
            if (!HudIcons.draw(c, context, "joy_base", cx, cy, R * 2.2f, 215)) {
                p.style = Paint.Style.FILL; p.color = 0x33FFFFFF; c.drawCircle(cx, cy, R, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0x88FFFFFF.toInt(); c.drawCircle(cx, cy, R, p)
            }
            if (!HudIcons.draw(c, context, "joy_knob", if (stickId >= 0) kx else cx, if (stickId >= 0) ky else cy, R * 0.95f, 240)) {
                p.style = Paint.Style.FILL; p.color = 0xAAFDB913.toInt()
                c.drawCircle(if (stickId >= 0) kx else cx, if (stickId >= 0) ky else cy, R * 0.45f, p)
            }
            }
            if (renderer.airState != 3) {   // Phase 64: plane = only DROP (+ EXIT); freefall = SKATE/DIVE (+ CHUTE when it may open); parachute = nothing
                bagOpen = false
                if (dropVisible()) ibtn(c, circle(101, w, h), if (renderer.airState == 0) "door" else null, if (renderer.airState == 0) "DROP" else "CHUTE", renderer.airState == 1 && renderer.chuteReady, 12f)
                if (renderer.airState == 1) ibtn(c, circle(102, w, h), if (renderer.skateMode) "jump" else "prone2", if (renderer.skateMode) "DIVE" else "SKATE", renderer.skateMode, 10f)
            } else {
            val fistsNow = renderer.gunName == "FISTS"   // nothing in the hand: the FIRE button becomes the fist (punch) button and RELOAD goes away
            ibtn(c, circle(1, w, h), "jump", "JUMP", false, 12f); if (runVisible()) ibtn(c, circle(2, w, h), "run", "RUN", renderer.run, 12f) else if (diveVisible()) ibtn(c, circle(2, w, h), "run", if (renderer.diving) "SURFACE" else "DIVE", renderer.diving, 10f); if (poseVisible()) ibtn(c, circle(17, w, h), "crouch", if (renderer.crouching) "STAND" else "SIT", renderer.crouching, 10f)
            ibtn(c, circle(4, w, h), if (fistsNow) "fist" else "fire", if (fistsNow) "PUNCH" else "FIRE", renderer.firing, 20f)
            if (reloadVisible()) ibtn(c, circle(5, w, h), "reload", "RELOAD", renderer.reloadProgress >= 0f, 10f)   // only when the magazine is not full and there is reserve ammo
            if (aimVisible()) ibtn(c, circle(7, w, h), "aim", "AIM", renderer.aiming, 13f)
            if (aimVisible() && renderer.aiming) { ibtn(c, circle(8, w, h), "fire", "FIRE", renderer.firing, 12f); ibtn(c, circle(9, w, h), "cancel", "SCOPE OFF", false, 9f) }
            if (poseVisible()) ibtn(c, circle(57, w, h), "prone", if (!renderer.prone) "PRONE" else if (!renderer.supine) "BACK" else "UP", renderer.prone, 9f)
            // the three ITEM slots of the reference sheet: medkit (HEAL), grenade (NADE), mushroom (EAT)
            HudLayout.rectOf(54, w, h, d, slotR[0]); HudLayout.rectOf(55, w, h, d, slotR[1]); HudLayout.rectOf(56, w, h, d, slotR[2])
            if (!medkitVisible()) slotR[0].setEmpty() else itemSlot(c, slotR[0], "medkit", "MEDKIT", renderer.medkits, renderer.healProgress >= 0f, HudLayout.scale(54))
            if (!nadeVisible()) slotR[1].setEmpty() else itemSlot(c, slotR[1], "grenade", "NADE", renderer.grenadeCount, false, HudLayout.scale(55))
            if (!shroomVisible()) slotR[2].setEmpty() else itemSlot(c, slotR[2], "heal", "MUSHROOM", renderer.mushrooms, false, HudLayout.scale(56))
            ibtn(c, circle(18, w, h), "bag", "BAG", bagOpen, 10f); ibtn(c, circle(19, w, h), "sound", if (Prefs.sfx) "SND" else "MUTE", false, 9f, slash = !Prefs.sfx)
            if (renderer.healProgress >= 0f) ibtn(c, circle(31, w, h), "cancel", "CANCEL", false, 9f)
            if (bagOpen) drawBag(c, w, h)
            }
            }
            rectIcon(c, exitR, "exit", "EXIT", 14f * HudLayout.scale(40)); if (!gearR.isEmpty) rectIcon(c, gearR, "gear", "", 14f)
            // top-centre: alive + kills
            wBegin(c, 48, w, h)
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt()
            val tb = RectF(w / 2 - 80f * d, 8f * d, w / 2 + 80f * d, 34f * d); c.drawRoundRect(tb, 6f * d, 6f * d, p)
            p.typeface = Ui.FONT_BOLD; p.textSize = 14f * d; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
            aliveKills(c, tb, renderer.botsAlive + 1, renderer.kills)
            c.restore()
            HudLayout.rectOf(49, w, h, d, tmpR); val bannerTop = tmpR.bottom + 6f * d   // Phase 64: banner + toast sit BELOW the zone bar (they used to be drawn on top of it)
            if (renderer.warm) {   // #warmBanner of the HTML
                p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(RectF(w / 2 - 110f * d, bannerTop, w / 2 + 110f * d, bannerTop + 42f * d), 6f * d, 6f * d, p)
                p.textAlign = Paint.Align.CENTER; p.textSize = 15f * d; p.color = Color.WHITE; c.drawText("PRE-MATCH: ${Math.ceil(renderer.warmLeft.toDouble()).toInt()}", w / 2, bannerTop + 18f * d, p)
                p.textSize = 10f * d; p.color = 0xFFFFAC78.toInt(); c.drawText("WARM-UP / NO DAMAGE", w / 2, bannerTop + 34f * d, p)
                if (renderer.warmEnded && !warmHandled) { warmHandled = true; post { onWarmEnd() } }
            }
            // zone timer + distance to the edge
            val outside = renderer.zoneDist < 0f
            wBegin(c, 49, w, h)
            val zr = RectF(w / 2 - 130f * d, 38f * d, w / 2 + 130f * d, 60f * d)
            p.color = if (outside) 0xCC8A1010.toInt() else 0x99000000.toInt(); c.drawRoundRect(zr, 6f * d, 6f * d, p)
            p.textSize = 11f * d; p.color = if (outside) 0xFFFFD0D0.toInt() else 0xFF9AD0FF.toInt()
            val pinTxt = if (hasPin) "  ·  PIN ${hypot(pinX - renderer.posX, pinZ - renderer.posZ).toInt()} m" else ""
            HudIcons.draw(c, context, "zone", zr.left + 14f * d, zr.centerY(), 16f * d)
            c.drawText((if (outside) "OUTSIDE ZONE · GO ${(-renderer.zoneDist).toInt()} m INSIDE" else renderer.zoneText + " · EDGE ${renderer.zoneDist.toInt()} m") + pinTxt, zr.centerX(), zr.centerY() + 4f * d, p)
            c.restore()
            // FPS | PING chip (reference sheet). The match is offline against bots, so the ping is a flat 0 ms
            wBegin(c, 53, w, h); val fb = HudLayout.base(53, w, h, d)
            tmpR.set(fb[0] - fb[2], fb[1] - fb[2] * 0.19f, fb[0] + fb[2], fb[1] + fb[2] * 0.19f)
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(tmpR, 6f * d, 6f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = 0x889AD0FF.toInt(); c.drawRoundRect(tmpR, 6f * d, 6f * d, p)
            c.drawLine(tmpR.centerX(), tmpR.top + 4f * d, tmpR.centerX(), tmpR.bottom - 4f * d, p)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 9.5f * d; p.textAlign = Paint.Align.CENTER
            val fpsN = renderer.fps
            p.color = if (fpsN >= 50) 0xFF7BE05A.toInt() else if (fpsN >= 30) 0xFFFFC21A.toInt() else 0xFFFF4B3A.toInt()
            c.drawText("FPS: $fpsN", tmpR.left + tmpR.width() * 0.27f, tmpR.centerY() + 3.4f * d, p)
            p.color = Color.WHITE; c.drawText("PING: 0ms", tmpR.left + tmpR.width() * 0.74f, tmpR.centerY() + 3.4f * d, p)
            p.textAlign = Paint.Align.LEFT
            c.restore()
            // EP / shield / HP bars (reference sheet): coloured tag + numbers on the left, the bar on the right; badges + vest / helmet pictures at the far left
            if (renderer.airState == 3) {
            wBegin(c, 50, w, h); val bb = HudLayout.base(50, w, h, d)
            val hw = 220f * d; val hx = bb[0] - 99f * d
            val hf = (renderer.playerHp / 200f).coerceIn(0f, 1f)
            val hpCol = if (hf > 0.4f) 0xFF19B5FF.toInt() else if (hf > 0.2f) 0xFFFFC21A.toInt() else 0xFFFF4B3A.toInt()
            val hy = h - 66f * d; val shy = h - 76f * d; val epy = h - 91f * d
            statBar(c, hx, epy, hw, 12f * d, (renderer.ep / 250f).coerceIn(0f, 1f), 0xFFF2B01E.toInt(), "EP", 0xFFFFD34D.toInt(), "${renderer.ep.toInt()}/250")
            HudIcons.draw(c, context, "pill", hx - 13f * d, epy + 6f * d, 14f * d)
            p.style = Paint.Style.FILL; p.color = 0xB30B1220.toInt(); c.drawRect(hx, shy, hx + hw, shy + 7f * d, p)
            p.color = 0xFF5FB4E8.toInt(); c.drawRect(hx + 2f * d, shy + 1.5f * d, hx + 2f * d + (hw - 4f * d) * (renderer.shield / 100f).coerceIn(0f, 1f), shy + 5.5f * d, p)
            HudIcons.drawRect(c, context, "badge_shield", hx - 22f * d, shy + 3.5f * d - 9f * d, hx - 4f * d, shy + 3.5f * d + 9f * d)
            statBar(c, hx, hy, hw, 12f * d, hf, hpCol, "HP", hpCol, "${renderer.playerHp.toInt()}/200")
            HudIcons.drawRect(c, context, "badge_hp", hx - 22f * d, hy + 6f * d - 9f * d, hx - 4f * d, hy + 6f * d + 9f * d)
            if (renderer.vestLvl > 0) sideTag(c, "vest", "Lv.${renderer.vestLvl}", hx - 26f * d, shy + 3.5f * d, 0xFFB9E3FF.toInt())
            if (renderer.helmLvl > 0) sideTag(c, "helmet", "Lv.${renderer.helmLvl}  ${renderer.helmHp.toInt()}", hx - 26f * d, epy + 6f * d, 0xFFFFD34D.toInt())
            c.restore()
            }
            // air-drop panel: plane / freefall / parachute info
            if (renderer.airState != 3 && renderer.airText.isNotEmpty()) {
                p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER
                val aw = p.measureText(renderer.airText) + 30f * d
                val ar = RectF(w / 2 - aw / 2, 100f * d, w / 2 + aw / 2, 126f * d)
                p.color = 0xCC000000.toInt(); c.drawRoundRect(ar, 6f * d, 6f * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Ui.GOLD; c.drawRoundRect(ar, 6f * d, 6f * d, p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawText(renderer.airText, ar.centerX(), ar.centerY() + 5f * d, p); p.textAlign = Paint.Align.LEFT
            }
            // airdrop tag (gold pill)
            if (renderer.airState == 3 && renderer.airdropText.isNotEmpty()) {
                p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 12f * d; p.textAlign = Paint.Align.CENTER
                val tw = p.measureText(renderer.airdropText) + 28f * d
                val ar = RectF(w / 2 - tw / 2, 154f * d, w / 2 + tw / 2, 174f * d)
                p.color = 0xB3281C00.toInt(); c.drawRoundRect(ar, 10f * d, 10f * d, p)
                p.color = 0xFFFFD34D.toInt(); c.drawText(renderer.airdropText, ar.centerX(), ar.centerY() + 4f * d, p); p.textAlign = Paint.Align.LEFT
                HudIcons.draw(c, context, "airdrop", ar.left - 16f * d, ar.centerY(), 30f * d)
            }
            drawMiniMap(c, w, h, d)
            drawFeed(c, w, d)
            // loot list (mobile BR style): one row per kind of item within 3 m, 4 rows at a time, drag to scroll, tap a row to take it
            drawLootList(c, w, h)
            // toast
            val tage = (System.nanoTime() - renderer.toastTime) / 1e9f
            if (renderer.toastTime != 0L && tage < 2.2f) {
                p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 14f * d; p.textAlign = Paint.Align.CENTER
                val tw = p.measureText(renderer.toastText) + 28f * d
                val ty0 = bannerTop + (if (renderer.warm) 48f * d else 0f)
                val tr = RectF(w / 2 - tw / 2, ty0, w / 2 + tw / 2, ty0 + 26f * d)
                p.color = Ui.GOLD; c.drawRoundRect(tr, 4f * d, 4f * d, p)
                p.color = Ui.INK; c.drawText(renderer.toastText, w / 2, tr.centerY() + 5f * d, p); p.textAlign = Paint.Align.LEFT
            }
            // damage flash (red edges)
            val dage = (System.nanoTime() - renderer.damageTime) / 1e9f
            if (renderer.damageTime != 0L && dage < 0.45f) {
                p.shader = RadialGradient(w / 2, h / 2, w * 0.7f, intArrayOf(0x00FF0000, 0x00FF0000, ((1f - dage / 0.45f) * 150).toInt() shl 24 or 0xE01010), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            // weapon chip: < NAME >
            val gs = HudLayout.scale(43); val ss = HudLayout.scale(44)
            if (!gunR.isEmpty) {
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(gunR, 6f * d, 6f * d, p)
            p.typeface = Ui.FONT_BOLD; p.color = Ui.GOLD; p.textAlign = Paint.Align.LEFT; p.textSize = 20f * d * gs
            c.drawText("‹", gunR.left + 12f * d * gs, gunR.centerY() + 7f * d * gs, p)
            p.textAlign = Paint.Align.RIGHT; c.drawText("›", gunR.right - 12f * d * gs, gunR.centerY() + 7f * d * gs, p)
            HudIcons.draw(c, context, "gun", gunR.left + 36f * d * gs, gunR.centerY(), gunR.height() * 0.62f)
            p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE; p.textSize = 15f * d * gs
            val nameW = p.measureText(renderer.gunName); val nameAv = gunR.width() - 104f * d * gs   // long names ("M82") shrink so they never touch the arrows / gun picture
            if (nameW > nameAv && nameAv > 0f) p.textSize = p.textSize * nameAv / nameW
            c.drawText(renderer.gunName, gunR.centerX(), gunR.centerY() + 5f * d * gs, p)
            }
            // scope tag (tap to cycle: AIM -> 1X ... 8X)
            HudLayout.rectOf(44, w, h, d, scopeR)
            if (!(onGroundFoot() && !isFists() && renderer.hasScopes)) scopeR.setEmpty()   // scope chip: only with a gun in hand AND at least one looted scope
            if (!scopeR.isEmpty) {
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(scopeR, 6f * d, 6f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = if (renderer.scopeLabel != "AIM") 0xFF35D0C8.toInt() else 0x66FFFFFF; c.drawRoundRect(scopeR, 6f * d, 6f * d, p)
            val aimOk = HudIcons.draw(c, context, "aim", scopeR.left + scopeR.height() * 0.42f, scopeR.centerY(), scopeR.height() * 0.58f)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = 14f * d * ss; p.color = Color.WHITE
            c.drawText(renderer.scopeLabel, if (aimOk) (scopeR.left + scopeR.height() * 0.8f + scopeR.right) / 2f else scopeR.centerX(), scopeR.centerY() + 5f * d * ss, p)
            }
            // ammo panel (reference sheet): gold frame, bullet picture | big magazine count / reserve
            if (onGroundFoot() && !isFists()) {
            wBegin(c, 51, w, h); val ab = HudLayout.base(51, w, h, d)
            val al = ab[0] - ab[2]; val arr = ab[0] + ab[2]; val at = ab[1] - ab[2] * 0.36f; val abm = ab[1] + ab[2] * 0.36f; val cut = 7f * d
            val frame = Ui.polygon(al, at, arr - cut, at, arr, at + cut, arr, abm - cut, arr - cut, abm, al, abm)
            p.style = Paint.Style.FILL; p.color = 0xCC0C1624.toInt(); c.drawPath(frame, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFF2B01E.toInt(); c.drawPath(frame, p)
            val ah = abm - at
            HudIcons.draw(c, context, "bullet", al + ah * 0.55f, ab[1], ah * 0.78f)
            p.strokeWidth = 1.2f * d; p.color = 0x88FFFFFF.toInt(); c.drawLine(al + ah * 1.05f, at + 5f * d, al + ah * 1.05f, abm - 5f * d, p)
            val ammoTxt = if (renderer.ammoMax == 0) "--" else "${renderer.ammoNow}"
            val resTxt = if (renderer.ammoMax > 0) "/${renderer.ammoReserve}" else ""
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.LEFT
            p.textSize = 20f * d; var nw = p.measureText(ammoTxt)
            p.textSize = 11f * d; var rw = if (resTxt.isEmpty()) 0f else p.measureText(resTxt)
            val x0 = al + ah * 1.05f + 5f * d; val avail = arr - cut * 0.5f - x0
            val fitK = if (nw + 2f * d + rw > avail) avail / (nw + 2f * d + rw) else 1f   // "30 /300" shrinks to fit the frame
            nw *= fitK; rw *= fitK
            val ax0 = x0 + maxOf(0f, avail - (nw + 2f * d + rw)) / 2f; val ty = ab[1] + 7.5f * d
            p.setShadowLayer(3f * d, 0f, 1.5f * d, 0xCC000000.toInt())
            p.textSize = 20f * d * fitK; p.color = if (renderer.ammoNow == 0 && renderer.ammoMax > 0) 0xFFFF6A5A.toInt() else Color.WHITE
            c.drawText(ammoTxt, ax0, ty, p)
            if (resTxt.isNotEmpty()) { p.textSize = 11f * d * fitK; p.color = 0xFFCCCCCC.toInt(); c.drawText(resTxt, ax0 + nw + 2f * d, ty, p) }
            p.clearShadowLayer(); p.textAlign = Paint.Align.LEFT
            c.restore()
            }
            if (renderer.reloadProgress >= 0f) {
                val bw = 120f * d; val bx = w / 2 - bw / 2; val by = h / 2 + 40f * d
                p.color = 0x88000000.toInt(); c.drawRect(bx, by, bx + bw, by + 6f * d, p)
                p.color = Ui.GOLD; c.drawRect(bx, by, bx + bw * renderer.reloadProgress, by + 6f * d, p)
            }
            if (renderer.underwater) { p.style = Paint.Style.FILL; p.color = 0x70104E6A; c.drawRect(0f, 0f, w, h, p) }
            if (renderer.diving) {
                p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 14f * d; p.textAlign = Paint.Align.CENTER
                val under = renderer.diveDepth > 0.45f
                p.color = if (under) 0xFFFF5A4D.toInt() else Color.WHITE
                c.drawText(if (under) String.format(java.util.Locale.US, "UNDER WATER %.1f m - HP DRAINS, GO UP!", renderer.diveDepth - 0.45f) else "SURFACE - look down + move to dive", w / 2, h * 0.2f, p); p.textAlign = Paint.Align.LEFT
            }
            // crosshair + hit marker
            val mx = w / 2; val my = h / 2
            val ads = renderer.adsAmount
            if (renderer.scopeOverlay) {
                // sniper scope: black mask with circular lens, cross-hair and mil dots
                val r = h * 0.44f
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = 0xFF111111.toInt(); c.drawCircle(mx, my, r, p)
                p.strokeWidth = 1.4f * d; p.color = 0xE6000000.toInt()
                c.drawLine(mx - r, my, mx - 18f * d, my, p); c.drawLine(mx + 18f * d, my, mx + r, my, p)
                c.drawLine(mx, my - r, mx, my - 18f * d, p); c.drawLine(mx, my + 18f * d, mx, my + r, p)
                p.strokeWidth = 2.2f * d; c.drawLine(mx - 18f * d, my, mx + 18f * d, my, p); c.drawLine(mx, my - 18f * d, mx, my + 18f * d, p)
                p.style = Paint.Style.FILL; for (k in 1..4) { c.drawCircle(mx + k * 22f * d, my, 1.8f * d, p); c.drawCircle(mx - k * 22f * d, my, 1.8f * d, p); c.drawCircle(mx, my + k * 22f * d, 1.8f * d, p) }
                p.color = Color.WHITE; p.textSize = 12f * d; p.textAlign = Paint.Align.CENTER; c.drawText(renderer.scopeLabel, mx, my + r + 16f * d, p); p.textAlign = Paint.Align.LEFT
            } else if (ads > 0.5f) {
                p.style = Paint.Style.FILL; p.color = if (renderer.scopeLabel == "1X") 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt(); c.drawCircle(mx, my, 2.5f * d, p)
            } else if (renderer.airState == 3) {   // Phase 64: no crosshair in the plane / freefall / parachute
                val gap = (5f + 6f * ads) * d
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xCCFFFFFF.toInt()
                c.drawLine(mx - gap - 9f * d, my, mx - gap, my, p); c.drawLine(mx + gap, my, mx + gap + 9f * d, my, p)
                c.drawLine(mx, my - gap - 9f * d, mx, my - gap, p); c.drawLine(mx, my + gap, mx, my + gap + 9f * d, p)
            }
            val age = (System.nanoTime() - renderer.hitTime) / 1e9f
            if (renderer.hitTime != 0L && age < 0.5f) {
                p.color = if (renderer.hitKill) 0xFFFF3B30.toInt() else if (renderer.hitHead) 0xFFFFD60A.toInt() else Color.WHITE
                p.strokeWidth = 3f * d
                for (sx2 in intArrayOf(-1, 1)) for (sy2 in intArrayOf(-1, 1)) c.drawLine(mx + sx2 * 6f * d, my + sy2 * 6f * d, mx + sx2 * 14f * d, my + sy2 * 14f * d, p)
                p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = 18f * d; p.typeface = Ui.FONT_BOLD
                c.drawText((if (renderer.hitHead) "HEADSHOT " else "") + renderer.hitDamage, mx, my - 30f * d - age * 30f * d, p)
                p.textAlign = Paint.Align.LEFT
            }
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 11f * d; p.color = Color.WHITE; p.textAlign = Paint.Align.RIGHT
            wBegin(c, 52, w, h); val cb = HudLayout.base(52, w, h, d)
            c.drawText(renderer.posText, cb[0] + cb[2], cb[1] + 4f * d, p)
            HudIcons.draw(c, context, "pin", cb[0] + cb[2] - p.measureText(renderer.posText) - 11f * d, cb[1], 15f * d); p.textAlign = Paint.Align.LEFT
            c.restore()
            val ms = renderer.matchState
            if (ms != 0) {
                val res = renderer.result
                if (res != null && !reported) { reported = true; post { onResult(res) } }
                if (res == null) reported = true
                p.style = Paint.Style.FILL; p.color = if (ms == 1) 0xB0102010.toInt() else 0xB0200808.toInt(); c.drawRect(0f, 0f, w, h, p)
                val cw = 380f * d; val ch = 250f * d
                val card = RectF(w / 2 - cw / 2, h / 2 - ch / 2, w / 2 + cw / 2, h / 2 + ch / 2)
                p.color = 0xF00E0B16.toInt(); c.drawRect(card, p)
                p.color = Ui.GOLD; c.drawRect(card.left, card.top, card.right, card.top + 4f * d, p)
                p.textAlign = Paint.Align.CENTER; p.typeface = Ui.FONT_BOLD
                p.textSize = 11f * d; p.color = 0xFFB9B2C6.toInt()
                c.drawText(if (ms == 1) "EXTRACTION COMPLETE / VICTORY" else "MATCH COMPLETE / DEBRIEF", card.centerX(), card.top + 24f * d, p)
                p.textSize = 26f * d; p.color = Color.WHITE
                c.drawText(if (ms == 1) "LAST ONE STANDING." else "BACK TO THE DUST.", card.centerX(), card.top + 56f * d, p)
                p.textSize = 14f * d; p.color = 0xFFCCCCCC.toInt()
                c.drawText("PLACE #${renderer.alivePlace}     KILLS ${renderer.kills}     TIME ${String.format(java.util.Locale.US, "%d:%02d", renderer.matchTime.toInt() / 60, renderer.matchTime.toInt() % 60)}", card.centerX(), card.top + 84f * d, p)
                if (res != null) {
                    p.color = Ui.GOLD; p.textSize = 16f * d; c.drawText("+${res.xp} XP     +${res.coins} COINS", card.centerX(), card.top + 112f * d, p)
                    p.color = 0xFF9A95A8.toInt(); p.textSize = 10f * d; c.drawText(res.label, card.centerX(), card.top + 130f * d, p)
                }
                val bw = 150f * d; val bh = 42f * d
                restartR.set(card.centerX() - bw - 8f * d, card.bottom - bh - 20f * d, card.centerX() - 8f * d, card.bottom - 20f * d)
                lobbyR.set(card.centerX() + 8f * d, card.bottom - bh - 20f * d, card.centerX() + bw + 8f * d, card.bottom - 20f * d)
                p.color = Ui.GOLD; c.drawRect(restartR, p); p.color = 0xFF2A2438.toInt(); c.drawRect(lobbyR, p)
                p.textSize = 15f * d; p.color = Ui.INK; c.drawText("DROP IN AGAIN", restartR.centerX(), restartR.centerY() + 5f * d, p)
                p.color = Color.WHITE; c.drawText("LOBBY", lobbyR.centerX(), lobbyR.centerY() + 5f * d, p)
                p.textAlign = Paint.Align.LEFT
            }
            if (mapOpen) drawBigMap(c, w, h, d)
            postInvalidateOnAnimation()
        }


        // ======================================================================== WINNER celebration
        private val WIN_BACK_AT = 1.6f                 // the BACK button (10 s countdown) appears after the banner has settled
        private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rockets = ArrayList<WinRocket>()
        private val sparks = ArrayList<WinSpark>()
        private val fwCols = intArrayOf(0xFFFFD60A.toInt(), 0xFFFF5A36.toInt(), 0xFF4CD6FF.toInt(), 0xFF7CFF6B.toInt(), 0xFFFF6BD6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFA23A.toInt())
        private var fxLast = 0L; private var fxSpawn = 0f; private var winBuzzed = false
        private val fxRnd = java.util.Random()

        private fun easeOut(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) * (1f - t) }
        private fun easeIO(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

        private fun stepFireworks(dt: Float, w: Float, h: Float, t: Float) {
            if (t > 0.25f) {
                fxSpawn -= dt
                if (fxSpawn <= 0f) {
                    fxSpawn = 0.28f + fxRnd.nextFloat() * 0.42f
                    val n = if (fxRnd.nextInt(3) == 0) 2 else 1
                    for (k in 0 until n) rockets.add(WinRocket(w * (0.1f + 0.8f * fxRnd.nextFloat()), h * 1.02f, h * (0.10f + 0.36f * fxRnd.nextFloat()), h * (0.95f + 0.5f * fxRnd.nextFloat()), fwCols[fxRnd.nextInt(fwCols.size)]))
                }
            }
            var i = rockets.size - 1
            while (i >= 0) {
                val r = rockets[i]; r.y -= r.vy * dt
                if (r.y <= r.ty) {
                    val ring = fxRnd.nextInt(3) == 0; val c2 = fwCols[fxRnd.nextInt(fwCols.size)]
                    renderer.playSfx("firework", 0.45f)
                    val cnt = if (ring) 30 else 44
                    for (k in 0 until cnt) {
                        val ang = (k * 6.2831855f / cnt) + if (ring) 0f else fxRnd.nextFloat() * 0.3f
                        val sp = (if (ring) 190f else 60f + fxRnd.nextFloat() * 220f) * d
                        sparks.add(WinSpark(r.x, r.y, cos(ang) * sp, sin(ang) * sp, 1.1f + fxRnd.nextFloat() * 0.8f, 1.9f, if (fxRnd.nextInt(4) == 0) r.col else if (fxRnd.nextInt(3) == 0) c2 else r.col, (1.6f + fxRnd.nextFloat() * 1.8f) * d))
                    }
                    rockets.removeAt(i)
                }
                i--
            }
            while (sparks.size > 700) sparks.removeAt(0)
            i = sparks.size - 1
            while (i >= 0) {
                val sp = sparks[i]; sp.life -= dt
                sp.vx *= (1f - 1.1f * dt); sp.vy = sp.vy * (1f - 1.1f * dt) + 150f * d * dt
                sp.x += sp.vx * dt; sp.y += sp.vy * dt
                if (sp.life <= 0f) sparks.removeAt(i); i--
            }
        }

        private fun drawFireworks(c: Canvas) {
            fxPaint.shader = null; fxPaint.style = Paint.Style.STROKE; fxPaint.strokeCap = Paint.Cap.ROUND
            for (r in rockets) {
                fxPaint.color = 0xFFFFF2B0.toInt(); fxPaint.strokeWidth = 2.6f * d
                c.drawLine(r.x, r.y, r.x, r.y + 26f * d, fxPaint)
                fxPaint.color = 0x55FFD060; fxPaint.strokeWidth = 5f * d; c.drawLine(r.x, r.y, r.x, r.y + 16f * d, fxPaint)
            }
            for (sp in sparks) {
                val a = (sp.life / sp.max * 1.4f).coerceIn(0f, 1f)
                val col = (sp.col and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
                fxPaint.color = col; fxPaint.strokeWidth = sp.sz
                c.drawLine(sp.x - sp.vx * 0.045f, sp.y - sp.vy * 0.045f, sp.x, sp.y, fxPaint)
                fxPaint.style = Paint.Style.FILL; fxPaint.color = (0xFFFFFF) or ((a * 0.9f * 255).toInt() shl 24); c.drawCircle(sp.x, sp.y, sp.sz * 0.55f, fxPaint)
                fxPaint.style = Paint.Style.STROKE
            }
            fxPaint.style = Paint.Style.FILL; fxPaint.strokeCap = Paint.Cap.BUTT
        }

        /** the animated "WINNER !" band: white flash, band opens from the middle, text slams in with a glitch, then slides to the top; fireworks all over; BACK (10s) at the bottom */
        private fun drawWinner(c: Canvas, w: Float, h: Float) {
            val res = renderer.result
            if (res != null && !reported) { reported = true; post { onResult(res) } }
            if (res == null) reported = true
            val t = renderer.winT
            if (!winBuzzed) { winBuzzed = true; buzz(90) }
            val now = System.nanoTime(); val dt = if (fxLast == 0L) 0.016f else ((now - fxLast) / 1e9f).coerceIn(0.001f, 0.05f); fxLast = now
            stepFireworks(dt, w, h, t); drawFireworks(c)
            // soft vignette so the text and the button read well
            fxPaint.shader = LinearGradient(0f, h * 0.7f, 0f, h, 0x00000000, 0x77000000, Shader.TileMode.CLAMP); c.drawRect(0f, h * 0.7f, w, h, fxPaint); fxPaint.shader = null
            // --- band
            val bandH = h * 0.17f
            val open = easeOut((t - 0.12f) / 0.38f)
            val slide = easeIO((t - 1.9f) / 0.7f)
            val cy = h * 0.43f + (h * 0.15f - h * 0.43f) * slide
            if (open > 0f) {
                val half = (w * 0.5f + 20f * d) * open; val bh2 = bandH * (0.55f + 0.45f * open)
                val l = w / 2 - half; val r = w / 2 + half; val top = cy - bh2 / 2; val bot = cy + bh2 / 2
                fxPaint.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0x00FFE620, 0xF2FFE620.toInt(), 0xF2FFD200.toInt(), 0xF2FFE620.toInt(), 0x00FFE620), floatArrayOf(0f, 0.16f, 0.5f, 0.84f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(l, top, r, bot, fxPaint); fxPaint.shader = null
                // glow lines on the edges of the band
                fxPaint.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(l, top - 1.5f * d, r, top + 1.5f * d, fxPaint); c.drawRect(l, bot - 1.5f * d, r, bot + 1.5f * d, fxPaint); fxPaint.shader = null
                // light sweep over the band (after it has opened)
                val sw = ((t - 0.9f) % 2.6f) / 0.9f
                if (t > 0.9f && sw in 0f..1f) {
                    c.save(); c.clipRect(l, top, r, bot)
                    val sx = l + (r - l) * sw; val sk = bandH * 0.5f
                    val path = Path(); path.moveTo(sx - 26f * d + sk, top); path.lineTo(sx + 26f * d + sk, top); path.lineTo(sx + 26f * d - sk, bot); path.lineTo(sx - 26f * d - sk, bot); path.close()
                    fxPaint.color = 0x66FFFFFF; c.drawPath(path, fxPaint); c.restore()
                }
                // text
                val tin = ((t - 0.28f) / 0.32f)
                if (tin > 0f) {
                    val ts = bandH * 0.78f
                    txPaint.typeface = Ui.FONT_BOLD; txPaint.textSize = ts; txPaint.textAlign = Paint.Align.CENTER; txPaint.letterSpacing = 0.06f; txPaint.style = Paint.Style.FILL
                    val sc = 1f + 1.1f * (1f - easeOut(tin)); val al = easeOut(tin)
                    val ty = cy + ts * 0.34f
                    c.save(); c.scale(sc, sc, w / 2, cy)
                    val glitch = t in 0.3f..1.35f
                    if (glitch) {
                        val rg = java.util.Random((t * 24f).toLong() * 7919L)
                        val j = (6f + rg.nextFloat() * 10f) * d * (1f - (t - 0.3f) / 1.1f).coerceIn(0f, 1f)
                        txPaint.color = 0xAA00E5FF.toInt(); c.drawText("WINNER !", w / 2 - j, ty, txPaint)
                        txPaint.color = 0xAAFF2A55.toInt(); c.drawText("WINNER !", w / 2 + j, ty, txPaint)
                        for (k in 0 until 3) {
                            val sy = cy - bandH * 0.45f + rg.nextFloat() * bandH * 0.9f; val shh = bandH * (0.06f + 0.1f * rg.nextFloat()); val dx = (rg.nextFloat() - 0.5f) * 40f * d
                            c.save(); c.clipRect(l, sy, r, sy + shh); c.translate(dx, 0f); txPaint.color = 0xFF1B1500.toInt(); c.drawText("WINNER !", w / 2, ty, txPaint); c.restore()
                        }
                    }
                    txPaint.color = ((al * 255).toInt() shl 24) or 0x2A1F00
                    c.drawText("WINNER !", w / 2, ty, txPaint)
                    c.restore()
                }
            }
            // sub line
            if (t > 1.1f) {
                val a = easeOut((t - 1.1f) / 0.5f)
                val sy = cy + bandH * 0.5f + 22f * d
                txPaint.typeface = Ui.FONT_BOLD; txPaint.textSize = 14f * d; txPaint.letterSpacing = 0.2f; txPaint.textAlign = Paint.Align.CENTER
                txPaint.color = ((a * 255).toInt() shl 24) or 0x000000; c.drawText("LAST ONE STANDING", w / 2 + 1.2f * d, sy + 1.2f * d, txPaint)
                txPaint.color = ((a * 255).toInt() shl 24) or 0xFFFFFF; c.drawText("LAST ONE STANDING", w / 2, sy, txPaint)
                txPaint.letterSpacing = 0f
            }
            // flash
            if (t < 0.75f) { fxPaint.color = ((1f - t / 0.75f) * 0.9f * 255).toInt() shl 24 or 0xFFFFFF; c.drawRect(0f, 0f, w, h, fxPaint) }
            // BACK (10s)
            if (t >= WIN_BACK_AT) {
                val a = easeOut((t - WIN_BACK_AT) / 0.35f)
                val left = Math.ceil((10f - (t - WIN_BACK_AT)).toDouble()).toInt().coerceAtLeast(0)
                val bw = 150f * d; val bh = 44f * d
                val by = h - bh - 26f * d + (1f - a) * 24f * d
                leaveR.set(w / 2 - bw / 2, by, w / 2 + bw / 2, by + bh)
                fxPaint.color = ((a * 0xE6).toInt() shl 24) or 0xECECEC; c.drawRoundRect(leaveR, 6f * d, 6f * d, fxPaint)
                txPaint.typeface = Ui.FONT_BOLD; txPaint.textSize = 17f * d; txPaint.letterSpacing = 0f; txPaint.textAlign = Paint.Align.CENTER
                txPaint.color = ((a * 255).toInt() shl 24) or 0x2A2A2A; c.drawText("BACK", leaveR.centerX() - 12f * d, leaveR.centerY() + 6f * d, txPaint)
                txPaint.textSize = 13f * d; txPaint.color = ((a * 255).toInt() shl 24) or 0x6A6A6A; c.drawText("(${left}s)", leaveR.centerX() + 30f * d, leaveR.centerY() + 5f * d, txPaint)
                if (left <= 0) finishMatch()
            } else leaveR.setEmpty()
        }

        /** BACK (button or countdown): hand the match numbers to the result screen (kills / damage / place of N) */
        fun finishNow() = finishMatch()
        private fun finishMatch() {
            if (specLeft) return
            specLeft = true
            val sum = renderer.summary()
            post { if (sum != null) onFinish(sum) else onExit(null) }
        }

        /** Spectate after death (HTML dl-spec): no debrief card; banner, PREV / NEXT bar and the BACK (10) countdown. */
        private fun drawSpec(c: Canvas, w: Float, h: Float) {
            val res = renderer.result
            if (res != null && !reported) { reported = true; post { onResult(res) } }
            if (res == null) reported = true
            val vm = renderer.vibMs; if (vm > 0) { renderer.vibMs = 0; buzz(vm) }
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER
            // match strip (alive / kills) stays visible
            p.color = 0x99000000.toInt()
            wBegin(c, 48, w, h)
            val tb = RectF(w / 2 - 80f * d, 8f * d, w / 2 + 80f * d, 34f * d); c.drawRoundRect(tb, 6f * d, 6f * d, p)
            p.textSize = 14f * d; p.color = Color.WHITE
            aliveKills(c, tb, renderer.botsAlive, renderer.kills)
            c.restore()
            drawFeed(c, w, d)
            // banner (fades after ~5 s, like the sp-banner animation)
            val bt = renderer.specBannerT
            if (bt < 5f) {
                val a = (if (bt < 0.3f) bt / 0.3f else if (bt > 4f) (5f - bt) else 1f).coerceIn(0f, 1f)
                p.color = ((a * 0xB0).toInt() shl 24) or 0x17251F; c.drawRect(w / 2 - 190f * d, 46f * d, w / 2 + 190f * d, 108f * d, p)
                p.color = ((a * 255).toInt() shl 24) or 0xFFAC78; p.textSize = 22f * d; c.drawText(renderer.specTitle, w / 2, 76f * d, p)
                p.color = ((a * 255).toInt() shl 24) or 0xF1EFE5; p.textSize = 12f * d; c.drawText(renderer.specSub, w / 2, 98f * d, p)
            }
            // bottom bar:  <  SPECTATING / NAME  >  BACK (n)
            val bw = 440f * d; val bh = 54f * d; val bx = w / 2 - bw / 2; val by = h - bh - 18f * d
            p.color = 0xD117251F.toInt(); c.drawRect(bx, by, bx + bw, by + bh, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0x40F1EFE5; c.drawRect(bx, by, bx + bw, by + bh, p)
            p.style = Paint.Style.FILL
            prevR.set(bx + 6f * d, by + 6f * d, bx + 62f * d, by + bh - 6f * d)
            leaveR.set(bx + bw - 122f * d, by + 6f * d, bx + bw - 6f * d, by + bh - 6f * d)
            nextR.set(leaveR.left - 62f * d, by + 6f * d, leaveR.left - 6f * d, by + bh - 6f * d)
            if (renderer.specOver) { prevR.setEmpty(); nextR.setEmpty() }
            else for (r in arrayOf(prevR, nextR)) { p.color = 0x14F1EFE5; c.drawRect(r, p) }
            p.textSize = 26f * d; p.color = Color.WHITE
            if (!prevR.isEmpty) c.drawText("‹", prevR.centerX(), prevR.centerY() + 9f * d, p)
            if (!nextR.isEmpty) c.drawText("›", nextR.centerX(), nextR.centerY() + 9f * d, p)
            val midL = if (prevR.isEmpty) bx + 12f * d else prevR.right; val midR = if (nextR.isEmpty) leaveR.left - 6f * d else nextR.left
            p.textSize = 10f * d; p.color = 0xFFB9B2C6.toInt(); c.drawText(renderer.specLabel, (midL + midR) / 2, by + 21f * d, p)
            p.textSize = 17f * d; p.color = Color.WHITE; c.drawText(renderer.specName, (midL + midR) / 2, by + 42f * d, p)
            p.color = 0x14FFAC78; c.drawRect(leaveR, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0xFFFFAC78.toInt(); c.drawRect(leaveR, p)
            p.style = Paint.Style.FILL; p.textSize = 13f * d; p.color = 0xFFFFAC78.toInt()
            val left = Math.ceil(renderer.specLeft.toDouble()).toInt()
            c.drawText("BACK ($left)", leaveR.centerX(), leaveR.centerY() + 5f * d, p)
            p.textAlign = Paint.Align.LEFT
            if (renderer.specLeft <= 0f) finishMatch()
        }

        private fun buzz(ms: Int) {
            try {
                val v = vib ?: return; if (!Prefs.vib) return
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
            } catch (e: Exception) { }
        }

        private fun drawFeed(c: Canvas, w: Float, d: Float) {
            // Phase 62: kill feed sits bottom-centre above the HP bars (reference: "PlayerA Eliminated PlayerB"), newest rows at most 4
            var y = height * 0.70f
            val now = System.nanoTime()
            p.typeface = Ui.FONT_BOLD; p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
            for (f in renderer.feed.filter { (now - it.time) / 1e9f <= 6f }.takeLast(4)) {
                val age = (now - f.time) / 1e9f
                val a = ((6f - age).coerceAtMost(1f) * 255).toInt()
                val tw = p.measureText(f.text); val cx = w * 1285f / 2576f
                p.color = (a * 0.55f).toInt() shl 24; c.drawRoundRect(RectF(cx - tw / 2 - 6f * d, y - 11f * d, cx + tw / 2 + 6f * d, y + 5f * d), 4f * d, 4f * d, p)
                p.color = (a shl 24) or (if (f.text.startsWith("YOU")) 0xFFD34D else 0xFFFFFF); c.drawText(f.text, cx, y, p)
                y += 20f * d
            }
            p.textAlign = Paint.Align.LEFT
        }

        // ---- maps (north-up picture of the island; world x/z in [-half, half])
        // world -> screen goes through the India-shape warp (looks only); mk[2] = local picture scale
        private val wk = FloatArray(3)
        private fun wUv(x: Float, z: Float) { mapImg.warp.fwd((x + mapImg.half) / (2f * mapImg.half), (z + mapImg.half) / (2f * mapImg.half), wk) }
        private fun wx(r: RectF, x: Float, z: Float): Float { wUv(x, z); return r.left + wk[0] * r.width() }
        private fun wz(r: RectF, x: Float, z: Float): Float { wUv(x, z); return r.top + wk[1] * r.height() }
        private fun ringPath(r: RectF, cx: Float, cz: Float, rad: Float): Path {
            val pt = Path()
            for (i in 0..72) {
                val a = i * (2.0 * Math.PI / 72.0)
                val x = wx(r, cx + (Math.cos(a) * rad).toFloat(), cz + (Math.sin(a) * rad).toFloat()); val y = wz(r, cx + (Math.cos(a) * rad).toFloat(), cz + (Math.sin(a) * rad).toFloat())
                if (i == 0) pt.moveTo(x, y) else pt.lineTo(x, y)
            }
            pt.close(); return pt
        }

        private fun drawMapContent(c: Canvas, r: RectF, d: Float, big: Boolean) {
            val pic = mapImg.pick(r.width())   // satellite picture (smaller copies for small / medium drawings)
            val src = Rect(0, 0, pic.width, pic.height)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.isFilterBitmap = true
            c.drawBitmap(pic, src, r, p)
            val k = r.width() / (2f * mapImg.half)
            // everything outside the safe zone is tinted blue
            val out = Path(); out.fillType = Path.FillType.EVEN_ODD
            out.addRect(if (big) r else RectF(-1e5f, -1e5f, 1e5f, 1e5f), Path.Direction.CW); out.addPath(ringPath(r, renderer.zoneCx, renderer.zoneCz, renderer.zoneRadius))
            p.color = 0x442F7BFF; c.drawPath(out, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (big) 2.5f else 1.6f) * d; p.color = 0xFF3D9BFF.toInt()
            c.drawPath(ringPath(r, renderer.zoneCx, renderer.zoneCz, renderer.zoneRadius), p)
            if (renderer.nextR > 1f) { p.color = Color.WHITE; c.drawPath(ringPath(r, renderer.nextX, renderer.nextZ, renderer.nextR), p) }
            // airdrops
            val dm = renderer.dropMarks
            var i = 0
            while (i + 1 < dm.size) {
                val x = wx(r, dm[i], dm[i + 1]).coerceIn(r.left + 4f * d, r.right - 4f * d); val y = wz(r, dm[i], dm[i + 1]).coerceIn(r.top + 4f * d, r.bottom - 4f * d)
                val s = (if (big) 6f else 3.5f) * d
                p.style = Paint.Style.FILL; p.color = 0xFFFFD34D.toInt(); c.drawRect(x - s, y - s, x + s, y + s, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d; p.color = 0xFF3A2A00.toInt(); c.drawRect(x - s, y - s, x + s, y + s, p)
                i += 2
            }
            // pin
            if (hasPin) {
                val x = wx(r, pinX, pinZ); val y = wz(r, pinX, pinZ)
                p.style = Paint.Style.FILL; p.color = 0xFFFF4D4D.toInt(); c.drawCircle(x, y, (if (big) 6f else 3f) * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Color.WHITE; c.drawCircle(x, y, (if (big) 9f else 5f) * d, p)
            }
            // player arrow (rotates with the camera)
            val px = if (big) wx(r, renderer.posX, renderer.posZ).coerceIn(r.left + 6f * d, r.right - 6f * d) else wx(r, renderer.posX, renderer.posZ)   // swimming past the map edge: the arrow stays on the big map border
            val py = if (big) wz(r, renderer.posX, renderer.posZ).coerceIn(r.top + 6f * d, r.bottom - 6f * d) else wz(r, renderer.posX, renderer.posZ)
            val s = (if (big) 9f else 5.5f) * d
            val sv = c.save(); c.translate(px, py); c.rotate(Math.toDegrees(renderer.yawNow.toDouble()).toFloat())
            if (big) {
                // India map screen: you = yellow ring + white dot, a short line shows where you look (minimap keeps the arrow)
                val rr = 8f * d
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * d; p.color = 0xFFFFE23A.toInt(); c.drawCircle(0f, 0f, rr, p)
                p.strokeWidth = 1.4f * d; c.drawLine(0f, -rr, 0f, -rr * 2.2f, p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(0f, 0f, 2.6f * d, p)
            } else {
                val ar = Ui.polygon(0f, -s * 1.3f, s * 0.8f, s, 0f, s * 0.45f, -s * 0.8f, s)
                p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawPath(ar, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * d; p.color = Ui.INK; c.drawPath(ar, p)
            }
            c.restoreToCount(sv)
            p.style = Paint.Style.FILL
        }

        /** how many metres of satellite ground the round minimap shows across (just the area around the player) */
        private val MM_VIEW = 190f

        // minimap window = a SQUARE (was a circle, then the India outline); cached per position / size
        private val mmPath = Path(); private val mmRegion = android.graphics.Region(); private val mmPic = RectF()
        private var mmCx = Float.NaN; private var mmCy = 0f; private var mmRad = 0f
        private fun mmShape(cx: Float, cy: Float, rad: Float) {
            if (cx == mmCx && cy == mmCy && rad == mmRad) return
            mmCx = cx; mmCy = cy; mmRad = rad
            IndiaShape.path(cx, cy, rad, mmPath)
            val b = RectF(); mmPath.computeBounds(b, true)
            mmRegion.setPath(mmPath, android.graphics.Region(Math.floor(b.left.toDouble()).toInt() - 2, Math.floor(b.top.toDouble()).toInt() - 2, Math.ceil(b.right.toDouble()).toInt() + 2, Math.ceil(b.bottom.toDouble()).toInt() + 2))
        }

        private fun drawMiniMap(c: Canvas, w: Float, h: Float, d: Float) {
            if (!Prefs.minimap || mapOpen) { mmR.setEmpty(); return }
            val mk = HudLayout.circle(47, w, h, d); val rad = mk[2]; val cx = mk[0]; val cy = mk[1]
            mmR.set(cx - rad, cy - rad, cx + rad, cy + rad)
            mmShape(cx, cy, rad)
            val sv = c.save(); c.clipPath(mmPath)
            // like Free Fire / PUBG: always only the ground right around you (MM_VIEW metres across), turning so that the way you look is always UP (the whole map is the big map: tap the minimap)
            val full = false
            val mr = RectF()
            if (full) { if (mapImg.warp.ok) mr.set(IndiaShape.pictureRect(mmR, mmPic)) else mr.set(mmR) } else {
                wUv(renderer.posX, renderer.posZ)
                val sz = 2f * rad * (2f * mapImg.half) / MM_VIEW * wk[2]
                val l = cx - wk[0] * sz
                val t = cy - wk[1] * sz
                mr.set(l, t, l + sz, t + sz)
            }
            val yawDeg = Math.toDegrees(renderer.yawNow.toDouble()).toFloat()
            p.style = Paint.Style.FILL; p.color = if (full) 0xCC0A1018.toInt() else 0xFF0D384F.toInt(); c.drawRect(mmR, p)
            val svRot = c.save()
            if (!full) c.rotate(-yawDeg, cx, cy)
            drawMapContent(c, mr, d, false)
            c.restoreToCount(svRot)
            if (!full) {   // red N marks north, sitting on the square's edge in the direction of north
                val a = Math.toRadians(yawDeg.toDouble())
                val dx = -Math.sin(a).toFloat(); val dy = -Math.cos(a).toFloat()
                val nr = (rad - 9f * d) / maxOf(Math.abs(dx), Math.abs(dy), 0.001f)
                val nx = cx + dx * nr; val ny = cy + dy * nr
                p.typeface = Ui.FONT_BOLD; p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = 0xBF000000.toInt(); c.drawText("N", nx, ny + 4f * d, p)
                p.style = Paint.Style.FILL; p.color = 0xFFFF5A4A.toInt(); c.drawText("N", nx, ny + 4f * d, p)
                p.textAlign = Paint.Align.LEFT
            }
            c.restoreToCount(sv)
            p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.MITER; p.strokeWidth = 2.5f * d; p.color = 0xFF1A1A1A.toInt(); c.drawPath(mmPath, p)
            p.strokeWidth = 1.5f * d; p.color = Ui.GOLD; c.drawPath(mmPath, p); p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER
            p.typeface = Ui.FONT_BOLD; p.textSize = 9f * d; p.color = 0xFFCCCCCC.toInt(); p.textAlign = Paint.Align.CENTER
            c.drawText("TAP = MAP", cx, cy + rad + 11f * d, p); p.textAlign = Paint.Align.LEFT
        }

        private fun drawBigMap(c: Canvas, w: Float, h: Float, d: Float) {
            // India map screen (LOOK ONLY): the whole screen is sea, the map picture (India + islands) floats in it with no frame
            p.style = Paint.Style.FILL; p.color = mapImg.seaColor; c.drawRect(0f, 0f, w, h, p)
            val sz = minOf(w - 40f * d, h - 24f * d)
            bigR.set(w / 2 - sz / 2, h / 2 - sz / 2, w / 2 + sz / 2, h / 2 + sz / 2)
            drawMapContent(c, bigR, d, true)
            closeR.set(bigR.right + 8f * d, bigR.top, bigR.right + 8f * d + 44f * d, bigR.top + 44f * d)
            if (closeR.right > w - 4f * d) closeR.offsetTo(w - 48f * d, 4f * d)
            p.style = Paint.Style.FILL; p.color = Ui.GOLD; c.drawRect(closeR, p)
            p.typeface = Ui.FONT_BOLD; p.textSize = 22f * d; p.color = Ui.INK; p.textAlign = Paint.Align.CENTER; c.drawText("X", closeR.centerX(), closeR.centerY() + 8f * d, p)
            p.textSize = 11f * d; p.color = Color.WHITE
            c.drawText("TAP THE MAP TO PLACE A PIN  ·  WHITE CIRCLE = NEXT SAFE ZONE  ·  GOLD SQUARE = AIRDROP", w / 2, bigR.bottom + 0f * d - 2f * d, p)
            p.textAlign = Paint.Align.LEFT
        }

        private var bagOpen = false
        private val bagR = RectF()
        private fun drawBag(c: Canvas, w: Float, h: Float) {
            val lines = renderer.backpackLines()
            val pw = 300f * d; val rh = 24f * d; val ph = 40f * d + rh * lines.size
            bagR.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
            p.style = Paint.Style.FILL; p.color = 0xEE0E0B16.toInt(); c.drawRoundRect(bagR, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFDB913.toInt(); c.drawRoundRect(bagR, 8f * d, 8f * d, p)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 15f * d; p.color = 0xFFFDB913.toInt(); p.textAlign = Paint.Align.LEFT
            c.drawText("BACKPACK", bagR.left + 14f * d, bagR.top + 26f * d, p)
            var y = bagR.top + 26f * d + rh * 0.9f
            for ((l, v) in lines) {
                p.textSize = 13f * d; p.color = 0xFFCCCCCC.toInt(); p.textAlign = Paint.Align.LEFT; c.drawText(l, bagR.left + 14f * d, y, p)
                p.color = Color.WHITE; p.textAlign = Paint.Align.RIGHT; c.drawText(v, bagR.right - 14f * d, y, p); y += rh
            }
            p.textAlign = Paint.Align.LEFT
        }

        /** [person] alive   [skull] kills inside the top-centre strip (words if a PNG is missing). Leaves the text alignment CENTER. */
        private fun aliveKills(c: Canvas, tb: RectF, alive: Int, kills: Int) {
            p.typeface = Ui.FONT_BOLD; p.textSize = 14f * d; p.color = Color.WHITE
            val aT = "$alive"; val kT = "$kills"
            if (HudIcons.get(context, "person") != null && HudIcons.get(context, "skull") != null) {
                p.textAlign = Paint.Align.LEFT
                val isz = 16f * d; val g1 = 4f * d; val g2 = 16f * d
                val tw = isz + g1 + p.measureText(aT) + g2 + isz + g1 + p.measureText(kT)
                var x0 = tb.centerX() - tw / 2f; val ty = tb.centerY() + 5f * d
                HudIcons.draw(c, context, "person", x0 + isz / 2f, tb.centerY(), isz); x0 += isz + g1
                c.drawText(aT, x0, ty, p); x0 += p.measureText(aT) + g2
                HudIcons.draw(c, context, "skull", x0 + isz / 2f, tb.centerY(), isz); x0 += isz + g1
                c.drawText(kT, x0, ty, p)
            } else { p.textAlign = Paint.Align.CENTER; c.drawText("ALIVE $aT   KILLS $kT", tb.centerX(), tb.centerY() + 5f * d, p) }
            p.textAlign = Paint.Align.CENTER
        }

        // ---------------------------------------------------------------- Phase 55: LOOT LIST + ITEM slots + stat bars
        private var listScroll = 0f; private var listRowH = 1f; private var listHeadH = 0f
        private var listDownY = 0f; private var listLastY = 0f; private var listMoved = false
        private val LIST_VIS = 4   // rows shown at the same time; more items = drag the list up / down

        private fun lootIcon(r: GameRenderer.LootRow): String = when (r.type) {
            LootType.WEAPON -> "gun"; LootType.AMMO -> "bullet"; LootType.VEST, LootType.VEST4 -> "vest"; LootType.HELM4 -> "helmet"
            LootType.MEDKIT -> "medkit"; LootType.SCOPE -> "aim"; LootType.GRENADE -> "grenade"; LootType.SHROOM -> "heal"; else -> "badge_shield"
        }

        private fun lootColor(r: GameRenderer.LootRow): Int = when (r.type) {
            LootType.WEAPON -> 0xFFFFD34D.toInt()
            LootType.AMMO -> AmmoInfo.COLORS[r.sub.coerceIn(0, 2)]
            LootType.VEST -> AmmoInfo.VEST_COLORS[r.sub.coerceIn(1, 3)]
            LootType.VEST4, LootType.HELM4 -> 0xFFE05AFF.toInt()
            LootType.MEDKIT -> 0xFFFF5A4A.toInt(); LootType.GRENADE -> 0xFF8FBF6A.toInt(); LootType.SHROOM -> 0xFF4AD66D.toInt()
            LootType.SCOPE -> 0xFF35D0C8.toInt(); else -> 0xFF5FB4E8.toInt()
        }

        private fun drawLootList(c: Canvas, w: Float, h: Float) {
            val rows = renderer.lootRows
            if (rows.isEmpty() || renderer.airState != 3) { listR.setEmpty(); listScroll = 0f; return }
            val k = HudLayout.circle(45, w, h, d); val s = HudLayout.scale(45)
            val hw = k[2]; val rowH = 29f * d * s; val headH = 20f * d * s
            val vis = minOf(rows.size, LIST_VIS)
            val top = k[1] - hw * 0.8f
            listRowH = rowH; listHeadH = headH
            val maxScroll = maxOf(0, rows.size - LIST_VIS) * rowH
            listScroll = listScroll.coerceIn(0f, maxScroll)
            listR.set(k[0] - hw, top, k[0] + hw, top + headH + vis * rowH)
            p.style = Paint.Style.FILL; p.color = 0xCC0A0F18.toInt(); c.drawRoundRect(listR, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = Ui.GOLD; c.drawRoundRect(listR, 8f * d, 8f * d, p)
            HudIcons.draw(c, context, "hand", listR.left + headH * 0.62f, top + headH / 2f, headH * 0.9f)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 10.5f * d * s; p.color = Ui.GOLD; p.textAlign = Paint.Align.LEFT
            c.drawText(if (rows.size > LIST_VIS) "NEARBY  (DRAG)" else "NEARBY", listR.left + headH * 1.2f, top + headH / 2f + 3.6f * d * s, p)
            p.textAlign = Paint.Align.RIGHT; p.color = 0xFFB9B2C6.toInt()
            c.drawText("${rows.size}", listR.right - 8f * d, top + headH / 2f + 3.6f * d * s, p)
            val clipTop = top + headH; val clipBot = clipTop + vis * rowH
            c.save(); c.clipRect(listR.left, clipTop, listR.right, clipBot)
            val rightPad = if (rows.size > LIST_VIS) 9f * d else 3f * d
            for (i in rows.indices) {
                val ry = clipTop + i * rowH - listScroll
                if (ry + rowH < clipTop || ry > clipBot) continue
                val r = rows[i]
                tmpR.set(listR.left + 3f * d, ry + 2f * d, listR.right - rightPad, ry + rowH - 2f * d)
                p.style = Paint.Style.FILL; p.color = if (r.blocked) 0x99141A26.toInt() else 0xCC1B2740.toInt(); c.drawRoundRect(tmpR, 5f * d, 5f * d, p)
                p.color = lootColor(r); c.drawRect(tmpR.left, tmpR.top + 3f * d, tmpR.left + 3f * d, tmpR.bottom - 3f * d, p)
                HudIcons.draw(c, context, lootIcon(r), tmpR.left + rowH * 0.55f, tmpR.centerY(), rowH * 0.74f, if (r.blocked) 110 else 255)
                p.typeface = Ui.FONT_BOLD; p.textSize = 11.5f * d * s; p.textAlign = Paint.Align.LEFT; p.color = if (r.blocked) 0xFF8A93A6.toInt() else Color.WHITE
                val nx = tmpR.left + rowH * 1.0f
                val maxW = tmpR.right - nx - (if (r.count > 1) 30f * d * s else 6f * d)
                var nm = r.label
                val fit = p.breakText(nm, true, maxW, null)
                if (fit < nm.length) nm = nm.substring(0, maxOf(1, fit - 1)) + "."
                c.drawText(nm, nx, tmpR.centerY() + 4f * d * s, p)
                if (r.count > 1) { p.textAlign = Paint.Align.RIGHT; p.color = Ui.GOLD; p.textSize = 12f * d * s; c.drawText("x${r.count}", tmpR.right - 6f * d, tmpR.centerY() + 4f * d * s, p) }
            }
            c.restore()
            if (rows.size > LIST_VIS) {   // scroll bar
                val tx = listR.right - 6f * d; val trackT = clipTop + 3f * d; val trackB = clipBot - 3f * d
                p.style = Paint.Style.FILL; p.color = 0x44FFFFFF; c.drawRect(tx, trackT, tx + 3f * d, trackB, p)
                val th = (trackB - trackT) * LIST_VIS.toFloat() / rows.size
                val ty = trackT + (trackB - trackT - th) * (listScroll / maxScroll)
                p.color = Ui.GOLD; c.drawRect(tx, ty, tx + 3f * d, ty + th, p)
            }
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
        }

        /** a short tap on a row of the loot list takes that item */
        private fun tapList(y: Float) {
            val rows = renderer.lootRows
            if (listR.isEmpty || y < listR.top + listHeadH) return
            val idx = ((y - (listR.top + listHeadH) + listScroll) / listRowH).toInt()
            if (idx in rows.indices) renderer.pickupKey(rows[idx].key)
        }

        /** ITEM slot of the reference sheet: dark square, picture, count bubble, name strip at the bottom. Dim picture = none left. */
        private fun itemSlot(c: Canvas, r: RectF, icon: String, label: String, count: Int, on: Boolean, s: Float) {
            val none = count < 1
            p.style = Paint.Style.FILL; p.color = 0xD90E1A2E.toInt(); c.drawRoundRect(r, 7f * d, 7f * d, p)
            val sh = r.height() * 0.26f
            p.color = 0xCC070D18.toInt()
            tmpR.set(r.left + 1.5f * d, r.bottom - sh, r.right - 1.5f * d, r.bottom - 1.5f * d); c.drawRoundRect(tmpR, 5f * d, 5f * d, p)
            HudIcons.draw(c, context, icon, r.centerX(), r.top + (r.height() - sh) / 2f, (r.height() - sh) * 0.9f, if (none) 110 else 255)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (on) 3f else 2f) * d; p.color = if (on) Ui.GOLD else 0xFF7FB3E6.toInt(); c.drawRoundRect(r, 7f * d, 7f * d, p)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textAlign = Paint.Align.CENTER; p.textSize = 7.5f * d * s
            p.color = if (none) 0x88FFFFFF.toInt() else Color.WHITE
            c.drawText(label, r.centerX(), tmpR.centerY() + 2.8f * d * s, p)
            if (count > 0) {
                val br = maxOf(8f * d, r.width() * 0.17f); val bx = r.right - br * 0.6f; val by = r.top + br * 0.7f
                p.color = 0xEE000000.toInt(); c.drawCircle(bx, by, br, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = Ui.GOLD; c.drawCircle(bx, by, br, p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = br * 1.15f; c.drawText("$count", bx, by + br * 0.4f, p)
            }
            p.textAlign = Paint.Align.LEFT
        }

        /** one stat bar of the reference sheet: dark pill, coloured tag + white numbers on the left, the coloured bar on the right */
        private fun statBar(c: Canvas, x: Float, y: Float, wd: Float, ht: Float, frac: Float, fill: Int, tag: String, tagCol: Int, num: String) {
            p.style = Paint.Style.FILL; p.color = 0xB30B1220.toInt()
            tmpR.set(x, y, x + wd, y + ht); c.drawRoundRect(tmpR, ht / 2f, ht / 2f, p)
            p.typeface = Ui.FONT_BOLD; p.textSize = 9.5f * d; p.textAlign = Paint.Align.LEFT
            val ty = y + ht * 0.5f + 3.4f * d
            p.color = tagCol; c.drawText(tag, x + 7f * d, ty, p)
            p.color = Color.WHITE; c.drawText(num, x + 7f * d + p.measureText(tag) + 4f * d, ty, p)
            val bl = x + 76f * d; val br = x + wd - 4f * d
            p.color = 0x66000000; tmpR.set(bl, y + 3f * d, br, y + ht - 3f * d); c.drawRoundRect(tmpR, 3f * d, 3f * d, p)
            if (frac > 0.005f) { p.color = fill; tmpR.set(bl, y + 3f * d, bl + (br - bl) * frac, y + ht - 3f * d); c.drawRoundRect(tmpR, 3f * d, 3f * d, p) }
        }

        /** vest / helmet picture + level text, right-aligned at rx */
        private fun sideTag(c: Canvas, icon: String, t: String, rx: Float, cy: Float, col: Int) {
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 10f * d; p.textAlign = Paint.Align.RIGHT; p.color = col
            c.drawText(t, rx, cy + 3.5f * d, p)
            HudIcons.draw(c, context, icon, rx - p.measureText(t) - 9f * d, cy, 15f * d)
            p.textAlign = Paint.Align.LEFT
        }

        /** PNG round button (HudIcons); when the PNG is missing the old text button is drawn instead */
        private fun ibtn(c: Canvas, k: FloatArray, icon: String?, label: String, on: Boolean, ts: Float, dim: Boolean = false, rot: Float = 0f, badge: String? = null, slash: Boolean = false, sub: String? = null) {
            if (!HudIcons.button(c, context, d, k, icon, label, ts, on, dim, rot, badge, slash, sub)) round(c, k, label, on, ts)
        }

        /** small rectangular button: dark pill + PNG icon on the left + text (empty text = the icon alone) */
        private fun rectIcon(c: Canvas, r: RectF, icon: String, t: String, ts: Float) {
            if (HudIcons.get(context, icon) == null) { rect(c, r, if (t.isEmpty()) "⚙" else t); return }
            if (t.isEmpty()) { HudIcons.draw(c, context, icon, r.centerX(), r.centerY(), r.height() * 1.15f); return }
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFDB913.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
            HudIcons.draw(c, context, icon, r.left + r.height() * 0.55f, r.centerY(), r.height() * 0.85f)
            val tx0 = r.left + r.height() * 1.05f
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = ts * d; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
            c.drawText(t, tx0 + (r.right - tx0) / 2f, r.centerY() + ts * d * 0.35f, p); p.textAlign = Paint.Align.LEFT
        }

        private fun round(c: Canvas, k: FloatArray, t: String, on: Boolean, ts: Float) {
            p.style = Paint.Style.FILL; p.color = if (on) 0xFFFDB913.toInt() else 0x99000000.toInt()
            c.drawCircle(k[0], k[1], k[2], p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFDB913.toInt(); c.drawCircle(k[0], k[1], k[2], p)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = ts * d; p.textAlign = Paint.Align.CENTER
            p.color = if (on) Ui.INK else Color.WHITE; c.drawText(t, k[0], k[1] + ts * d * 0.35f, p); p.textAlign = Paint.Align.LEFT
        }

        private fun rect(c: Canvas, r: RectF, t: String) {
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFDB913.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
            p.style = Paint.Style.FILL; p.typeface = Ui.FONT_BOLD; p.textSize = 16f * d; p.textAlign = Paint.Align.CENTER
            p.color = Color.WHITE; c.drawText(t, r.centerX(), r.centerY() + 6f * d, p); p.textAlign = Paint.Align.LEFT
        }

        /** the joystick may be moved anywhere in the HUD editor: a touch on it starts the stick even outside the left 45 % */
        private fun nearStick(x: Float, y: Float): Boolean { val k = HudLayout.circle(46, width.toFloat(), height.toFloat(), d); return hypot(x - k[0], y - k[1]) <= k[2] * 1.3f }

        private fun inCircle(id: Int, x: Float, y: Float): Boolean {
            val k = circle(id, width.toFloat(), height.toFloat()); return hypot(x - k[0], y - k[1]) <= k[2] * 1.15f
        }

        private var overWas = false
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val a = e.actionMasked
            if (mapOpen && (renderer.spec || renderer.matchState == 1)) mapOpen = false
            val overNow = mapOpen || renderer.spec || renderer.matchState != 0
            if (overNow && !overWas && (stickId >= 0 || lookId >= 0 || btnIds.isNotEmpty() || renderer.firing)) clearTouch()
            overWas = overNow   // fingers that were down before this screen took over would stay stuck
            if (mapOpen) {
                if (a == MotionEvent.ACTION_UP) {
                    if (closeR.contains(e.x, e.y) || !bigR.contains(e.x, e.y)) mapOpen = false
                    else {
                        val half = mapImg.half
                        val iv = FloatArray(2); mapImg.warp.inv((e.x - bigR.left) / bigR.width(), (e.y - bigR.top) / bigR.height(), iv)
                        pinX = iv[0] * 2f * half - half; pinZ = iv[1] * 2f * half - half; hasPin = true
                    }
                }
                return true
            }
            if (renderer.spec) {
                when (a) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                        when {
                            !prevR.isEmpty && prevR.contains(x, y) -> btnIds[id] = 40
                            !nextR.isEmpty && nextR.contains(x, y) -> btnIds[id] = 41
                            leaveR.contains(x, y) -> btnIds[id] = 42
                            lookId < 0 -> { lookId = id; lx = x; ly = y }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                        if (e.getPointerId(i) == lookId) { renderer.look(e.getX(i) - lx, e.getY(i) - ly); lx = e.getX(i); ly = e.getY(i) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        val i = e.actionIndex; val id = e.getPointerId(i)
                        if (id == lookId) lookId = -1
                        val b = btnIds.remove(id)
                        if (a != MotionEvent.ACTION_CANCEL) {
                            if (b == 40 && prevR.contains(e.getX(i), e.getY(i))) renderer.specCycle(-1)
                            if (b == 41 && nextR.contains(e.getX(i), e.getY(i))) renderer.specCycle(1)
                            if (b == 42 && leaveR.contains(e.getX(i), e.getY(i))) finishMatch()
                        }
                    }
                }
                return true
            }
            if (renderer.matchState == 1) {
                if (a == MotionEvent.ACTION_UP && renderer.winT >= WIN_BACK_AT && leaveR.contains(e.x, e.y)) finishMatch()
                return true
            }
            if (renderer.matchState != 0) {
                if (a == MotionEvent.ACTION_UP) {
                    if (restartR.contains(e.x, e.y)) onRestart()
                    else if (lobbyR.contains(e.x, e.y)) onExit(null)
                }
                return true
            }
            when (a) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                    when {
                        exitR.contains(x, y) -> btnIds[id] = 3
                        gearR.contains(x, y) -> btnIds[id] = 14
                        !mmR.isEmpty && mmR.contains(x, y) -> btnIds[id] = 13
                        dropVisible() && inCircle(101, x, y) -> { btnIds[id] = 1; renderer.jump() }
                        renderer.airState == 1 && inCircle(102, x, y) -> { btnIds[id] = 30; renderer.toggleDive() }
                        renderer.airState in 1..2 && (x < width * 0.45f || nearStick(x, y)) && stickId < 0 -> startStick(id, x, y)
                        renderer.airState != 3 && lookId < 0 -> { lookId = id; lx = x; ly = y }
                        renderer.airState != 3 -> { }   // in the air nothing else can be touched
                        bagOpen && bagR.contains(x, y) -> { btnIds[id] = 30; bagOpen = false }
                        inCircle(18, x, y) -> { btnIds[id] = 30; bagOpen = !bagOpen }
                        inCircle(19, x, y) -> { btnIds[id] = 30; Prefs.sfx = !Prefs.sfx; Prefs.save(context) }
                        renderer.healProgress >= 0f && inCircle(31, x, y) -> { btnIds[id] = 31; renderer.cancelHeal() }
                        slotR[2].contains(x, y) -> { btnIds[id] = 15; renderer.eat() }
                        slotR[0].contains(x, y) -> { btnIds[id] = 8; renderer.heal() }
                        slotR[1].contains(x, y) -> { btnIds[id] = 12; renderer.throwGrenade() }
                        poseVisible() && inCircle(57, x, y) -> { btnIds[id] = 17; renderer.toggleProne() }
                        aimVisible() && renderer.aiming && inCircle(8, x, y) -> { btnIds[id] = 4; renderer.firing = true }
                        aimVisible() && renderer.aiming && inCircle(9, x, y) -> { btnIds[id] = 30; renderer.aiming = false }
                        inCircle(4, x, y) -> { btnIds[id] = 4; renderer.firing = true }
                        inCircle(1, x, y) -> { btnIds[id] = 1; renderer.jump() }
                        diveVisible() && inCircle(2, x, y) -> { btnIds[id] = 2; renderer.toggleWaterDive() }
                        runVisible() && inCircle(2, x, y) -> { btnIds[id] = 2; onRunTap() }
                        poseVisible() && inCircle(17, x, y) -> { btnIds[id] = 17; renderer.toggleCrouch() }
                        reloadVisible() && inCircle(5, x, y) -> { btnIds[id] = 5; renderer.reload() }
                        aimVisible() && inCircle(7, x, y) -> { btnIds[id] = 10; renderer.toggleAim() }
                        scopeR.contains(x, y) -> { btnIds[id] = 11; renderer.cycleScope() }
                        !listR.isEmpty && listR.contains(x, y) -> { btnIds[id] = 60; listDownY = y; listLastY = y; listMoved = false }
                        gunR.contains(x, y) -> { btnIds[id] = if (x < gunR.centerX()) 6 else 7; renderer.switchGun(if (x < gunR.centerX()) -1 else 1) }
                        (x < width * 0.45f || nearStick(x, y)) && stickId < 0 -> startStick(id, x, y)
                        lookId < 0 -> { lookId = id; lx = x; ly = y }
                    }
                    trackBtn(id, x, y)
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                    if (id == stickId) {
                        var dx = x - sx; var dy = y - sy; val l = hypot(dx, dy)
                        if (l > R) { dx = dx / l * R; dy = dy / l * R }
                        kx = sx + dx; ky = sy + dy
                        renderer.moveX = dx / R; renderer.moveY = dy / R
                    } else if (id == lookId) { renderer.look(x - lx, y - ly); lx = x; ly = y }
                    else if (dragBtn(id, x, y)) { }
                    else if (btnIds[id] == 60) {   // finger on the loot list: drag = scroll, a short tap = take the row
                        if (!listMoved && Math.abs(y - listDownY) > 10f * d) listMoved = true
                        if (listMoved) listScroll -= y - listLastY
                        listLastY = y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val i = e.actionIndex; val id = e.getPointerId(i)
                    if (id == stickId) { stickId = -1; renderer.moveX = 0f; renderer.moveY = 0f; if (renderer.run) renderer.run = false }   // let go of the stick = stop (sprint ends too)
                    if (id == lookId) lookId = -1
                    val b = btnIds.remove(id); btnTrack.remove(id)
                    if (b == 4 && !btnIds.containsValue(4)) renderer.firing = false
                    if (b == 60 && a != MotionEvent.ACTION_CANCEL && !listMoved) tapList(e.getY(i))
                    if (b == 3 && a != MotionEvent.ACTION_CANCEL && exitR.contains(e.getX(i), e.getY(i))) {
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        if (nowMs - exitArmedAt <= 2500L) { exitArmedAt = 0L; onExit(renderer.quitResult()) }
                        else { exitArmedAt = nowMs; renderer.toastText = "TAP EXIT AGAIN TO LEAVE THE MATCH"; renderer.toastTime = System.nanoTime() }
                    }
                    if (b == 14 && a != MotionEvent.ACTION_CANCEL && gearR.contains(e.getX(i), e.getY(i))) onSettings()
                    if (b == 13 && a != MotionEvent.ACTION_CANCEL && mmR.contains(e.getX(i), e.getY(i))) { mapOpen = true; clearTouch() }
                    if (a == MotionEvent.ACTION_CANCEL) { renderer.firing = false; stickId = -1; lookId = -1; renderer.moveX = 0f; renderer.moveY = 0f; autoRun = false; renderer.run = false; btnIds.clear(); btnTrack.clear() }
                }
            }
            return true
        }
    }
}

/** WINNER fireworks: a rocket flies up and bursts into sparks */
private class WinRocket(var x: Float, var y: Float, val ty: Float, val vy: Float, val col: Int)
private class WinSpark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val col: Int, val sz: Float)
