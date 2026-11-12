package com.dustline.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Round-start aircraft intro: the plane flies in from behind (PLANE_FLY_IN s), then the camera swings from the nose around to behind it (PLANE_ORBIT s). */
private const val PLANE_FLY_IN = 6f
private const val PLANE_ORBIT = 5.2f

/** OpenGL ES 3.0 renderer: island map, first-person walking, 3D guns, shooting, reload, training targets. */
class GameRenderer(private val ctx: Context, private val map: MapData, private val sfx: SoundFx? = null) : GLSurfaceView.Renderer {
    // ---- input (written by UI thread)
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var run = false
    @Volatile var firing = false
    @Volatile private var jumpReq = false
    @Volatile private var reloadReq = false
    @Volatile private var switchDir = 0
    @Volatile private var dYaw = 0f
    @Volatile private var dPitch = 0f
    fun jump() { jumpReq = true }
    fun reload() { reloadReq = true }
    fun switchGun(dir: Int) { switchDir = dir }
    @Synchronized fun look(dx: Float, dy: Float) { dYaw += dx; dPitch += dy }
    @Synchronized fun clearLook() { dYaw = 0f; dPitch = 0f }
    @Synchronized private fun takeLook(): FloatArray { val r = floatArrayOf(dYaw, dPitch); dYaw = 0f; dPitch = 0f; return r }

    // ---- state shown on the HUD
    @Volatile var fps = 0
    @Volatile var posText = ""
    @Volatile var gunName = ""
    @Volatile var ammoNow = 0
    @Volatile var ammoMax = 0
    @Volatile var reloadProgress = -1f
    @Volatile var hitTime = 0L
    @Volatile var hitDamage = 0
    @Volatile var hitHead = false
    @Volatile var hitKill = false
    @Volatile var playerHp = 200f
    @Volatile var playerAlive = true
    @Volatile var kills = 0
    @Volatile var botsAlive = 0
    @Volatile var damageTime = 0L
    @Volatile var killedBy = ""
    @Volatile var respawnIn = 0f
    @Volatile var matchState = 0          // 0 playing, 1 won, 2 lost
    @Volatile var matchTime = 0f
    @Volatile var zoneRadius = 0f
    @Volatile var zoneText = ""
    @Volatile var zoneDist = 0f           // metres inside (+) / outside (-) the zone edge
    @Volatile var result: MatchResult? = null
    @Volatile var alivePlace = 0
    @Volatile var damageDealt = 0          // total damage the player did this match (shown on the result screen)
    fun playSfx(name: String, gain: Float = 1f) { sfx?.play(name, gain) }
    @Volatile var winT = 0f                // seconds since the player won (WINNER celebration)
    val totalPlayers: Int get() = botSys.bots.size + 1
    /** WINNER celebration: the camera swings in front of the player's own body (the first-person gun is hidden) */
    private val winCam: Boolean get() = matchState == 1 && playerAlive && !spec && airState == 3
    /** numbers for the result screen (null while the match is still running) */
    fun summary(): MatchSummary? = if (matchState == 0) null else MatchSummary(matchState == 1, alivePlace, totalPlayers, kills, damageDealt, matchTime, result?.xp ?: 0, result?.coins ?: 0)
    @Volatile var aiming = false
    @Volatile var grenadeCount = 2
    // ---- phase 10: mushrooms / EP, level-4 gear, airdrops, zone v39, kill feed, mini-map data
    @Volatile var ep = 0f
    @Volatile var mushrooms = 0
    @Volatile private var eatReq = false
    fun eat() { eatReq = true }
    private var epTimer = 0f
    private val EP_MAX = 250f
    @Volatile var helmLvl = 0
    @Volatile var helmHp = 0f
    @Volatile var paused = false
    @Volatile var vibMs = 0
    @Volatile var airdropText = ""
    @Volatile var posX = 0f
    @Volatile var posZ = 0f
    @Volatile var yawNow = 0f
    @Volatile var zoneCx = 0f
    @Volatile var zoneCz = 0f
    @Volatile var nextX = 0f
    @Volatile var nextZ = 0f
    @Volatile var nextR = 0f                      // <= 0: no next circle announced yet
    @Volatile var dropMarks = FloatArray(0)       // x,z pairs of crates that are falling or still hold loot
    class FeedItem(val text: String, val time: Long)
    val feed = java.util.concurrent.CopyOnWriteArrayList<FeedItem>()
    private fun addFeed(t: String) { feed.add(FeedItem(t, System.nanoTime())); while (feed.size > 6) feed.removeAt(0) }
    private val post = PostFx()
    private var postLevel = 2          // 2 = bloom + AO, 1 = bloom only, 0 = off (auto performance)
    private var frameAvg = 0.016f; private var slowT = 0f
    /** Called when the settings close: a bloom / AO / graphics change gets a fresh start (auto-performance may have shed them earlier). */
    fun resetPerf() { postLevel = 2; slowT = 0f; frameAvg = 0.016f }
    private val zone = ZoneSystem(map, Random(System.nanoTime() xor 0x1234567L))
    private var zoneTickT = 0f
    private var stepDist = 0f
    private class Drop(val x: Float, val z: Float, val g: Float, val dir: Int) {
        var state = 0; var t = 0f; var ty = 0f; var h = 210f; var landT = 0f; var life = 0f; var beam = 0.3f
        val items = ArrayList<Loot>()
    }
    private val drops = ArrayList<Drop>()
    private val AD_TIMES = floatArrayOf(45f, 150f, 270f)   // AD_TIMES of the HTML
    private var adN = 0
    private var adNext = 75f
    fun silence() { sfx?.stopAll() }
    @Volatile private var nadeReq = false
    fun throwGrenade() { nadeReq = true }
    @Volatile var adsAmount = 0f
    @Volatile var scopeOverlay = false
    @Volatile var scopeLabel = "AIM"
    private val scopesOwned = java.util.TreeSet<Int>()
    /** Phase 64: the SCOPE chip of the HUD exists only once at least one scope has been looted */
    val hasScopes: Boolean get() = scopesOwned.isNotEmpty()
    private var scopeSel = 0
    private var surfW = 1; private var surfH = 1
    fun toggleAim() { if (!aiming) run = false; aiming = !aiming }
    fun cycleScope() {
        val list = listOf(0) + scopesOwned.toList()
        if (list.size < 2) { toast("NO SCOPES YET - LOOT A 1X-8X SCOPE"); return }
        scopeSel = list[(list.indexOf(scopeSel) + 1) % list.size]
        scopeLabel = if (scopeSel > 0) scopeSel.toString() + "X" else "AIM"
        toast(if (scopeSel > 0) "SCOPE / " + scopeSel + "X" else "IRON SIGHTS")
    }
    // dlAimFov / dlScopeFov / dlSensScale from the HTML
    private fun aimFov(z: Int) = if (z >= 3) 25f else if (z == 2) 34f else if (z == 1) 44f else 48f
    private fun scopeFov(z: Int) = if (z >= 8) 8f else if (z >= 4) 16f else if (z >= 2) 34f else 54f
    private fun sensScale(z: Int) = if (z >= 8) 0.12f else if (z >= 4) 0.24f else if (z == 3) 0.37f else if (z == 2) 0.5f else if (z == 1) 0.8f else 1f
    private fun isScoped(g: Gun) = g.cls == "dmr" || g.cls == "sniper" || g.cls == "amr"
    fun quitResult(): MatchResult? = if (matchState != 0 || warm) null else Rewards.compute(kills, botsAlive + 1, false, matchTime, true)
    @Volatile var ammoReserve = 0
    @Volatile var shield = 0f
    @Volatile var vestLvl = 0
    @Volatile var medkits = 0
    @Volatile var healProgress = -1f
    @Volatile var promptLabel = ""
    @Volatile var toastText = ""
    @Volatile var toastTime = 0L
    @Volatile private var pickupReq = false
    @Volatile private var healReq = false
    fun pickup() { pickupReq = true }
    /** Phase 55 LOOT LIST: one row per kind of item within 3 m (identical items are stacked into ONE row with a count). The HUD only reads it. */
    class LootRow(val key: Int, val type: Int, val sub: Int, val label: String, val count: Int, val blocked: Boolean)
    @Volatile var lootRows: List<LootRow> = emptyList()
    @Volatile private var pickKey = -1
    /** tap on a row of the loot list: takes that item (a stack of the same supply is taken all at once, as far as the bag has room) */
    fun pickupKey(key: Int) { pickKey = key }
    fun heal() { healReq = true }
    @Volatile private var cancelHealReq = false
    /** CANCEL button: stop a medkit heal that is in progress (nothing is used up) */
    fun cancelHeal() { cancelHealReq = true }
    private fun toast(t: String) { toastText = t; toastTime = System.nanoTime() }

    private class GlMesh(val vao: Int, val count: Int)
    private class Layer(val mesh: GlMesh, val mat: String)
    private val layers = ArrayList<Layer>()
    private val gunGl = HashMap<String, Pair<GlMesh, GunModel>>()
    // real aircraft model (PlaneModel.kt) - replaces the old box plane
    private class PlanePartGl(val mesh: GlMesh, val prop: Boolean, val px: Float, val py: Float, val pz: Float)
    private val planeGl = ArrayList<PlanePartGl>()
    private var planeReady = false
    // real 3D parachute (ParachuteModel.kt, from parachute_simple.glb) - replaces the old sphere canopy + box line everywhere
    private var chuteMesh: GlMesh? = null
    private var chuteTex = 0

    private var seaMesh: GlMesh? = null
    private var cube: GlMesh? = null
    private var wall: GlMesh? = null
    private var sphere: GlMesh? = null
    private var prog = 0
    private var natureGl: NatureGl? = null
    // real-time sun shadows (ShadowMap.kt)
    private val shadow = ShadowMap()
    private var shadowDraw = false
    private val shm = FloatArray(16)
    // swimming effects (DSW of the HTML): ripple rings + splash drops
    private class Rip(val x: Float, val y: Float, val z: Float, val big: Float) { var life = 1f }
    private class Drop2(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val wy: Float) { var life = 1f }
    private val rips = ArrayList<Rip>()
    private val splashDrops = ArrayList<Drop2>()
    private var swimIn = false
    private var swimAcc = 0f
    /** true while the player floats in deep water (HUD can show it) */
    @Volatile var swimming = false
    /** DIVE (button only in deep water): head under water, HP drains faster the deeper you go. diveDepth = m under the surface, underwater = camera below it */
    @Volatile var diving = false
    @Volatile var diveDepth = 0f
    @Volatile var underwater = false
    private var diveAcc = 0f
    private var diveSink = 0f
    private var swimAmt = 0f; private var swimPh = 0f   // swimming pose blend 0..1 + front-crawl stroke phase
    fun toggleWaterDive() { if (!playerAlive) return; if (diving) diving = false else if (swimming) { diving = true; diveSink = 0.9f; sfx?.play("splash") } }
    private fun drownHit() {
        if (!playerAlive || warm) return
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f }
        playerHp -= 1f; damageTime = System.nanoTime(); vibMs = 20
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "DROWNING"; addFeed("DROWNING eliminated YOU"); endMatch(false); beginSpectate("DROWNING") }
    }
    /** eye view: first person, or third person while aiming (the camera slides into the eyes, so sights / scope look exactly like first person) */
    private val fppView: Boolean get() = !Prefs.thirdPerson || adsAmount > 0.4f
    private val tex = HashMap<String, Int>()
    private val vp = FloatArray(16); private val proj = FloatArray(16); private val view = FloatArray(16)
    private val inv = FloatArray(16); private val mm = FloatArray(16); private val ident = FloatArray(16)
    private val nm = FloatArray(9); private val nmIdent = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var uMode = 0; private var uColor = 0; private var uModel = 0; private var uNM = 0
    // rigged 3D characters (Survivor) - GPU skinned, see CharacterModel.kt
    private val chars = CharRenderer(ctx)
    private val cpose = CharPose()
    private val tmpM = FloatArray(16)
    private val jumpAnim = JumpAnimState()   // JUMP animation of the player's body (third person), see JumpAnim.kt
    private val cbase = FloatArray(16)
    private var camX = 0f; private var camY = 0f; private var camZ = 0f
    private var footSpeed = 0f
    private var sprintNow = false   // Phase 28: sprinting on the ground at > 1.5 m/s -> the gun refuses to fire (HTML playerShoot() guard)
    /** character the player picked in the lobby (Characters.ALL id): bots use it too */
    @Volatile var playerChar = "survivor"
    private val botChars = arrayOf("survivor")

    private val pos = floatArrayOf(map.spawn[0], map.spawn[1], map.spawn[2])
    private var yaw = 0f; private var pitch = 0f; private var vy = 0f; private var onGround = true
    /** mobile BR style: `yaw` is only the CAMERA direction. `bodyYaw` is where the character itself faces (turns toward the stick direction, or toward the crosshair while shooting / aiming). */
    private var bodyYaw = 0f; private var faceHold = 0f
    private var last = 0L; private var frames = 0; private var fpsT = 0L
    private var walkPhase = 0f

    // ---- weapons
    private val guns = Weapons.ALL
    private var gi = 0
    private var fists = false; private var punchT = 0f; private var punchHand = 1f   // FISTS slot of the HTML (weapons[4], melee)
    // BOXING: the punches come as a combo jab -> cross -> hook -> uppercut (BoxAnim); the hit lands when the fist reaches the target (BoxAnim.PEAK), not at the button press
    private var punchKind = 0; private var punchDur = 0.3f; private var comboKind = 0; private var comboClock = -10f; private var punchPending = false
    private val fm = FloatArray(16)
    private val ammo = IntArray(guns.size) { guns[it].mag }
    private val owned = intArrayOf(0, -1, -1) // gun index per slot (0 long, 1 short, 2 sniper); start with MP5
    private val pool = intArrayOf(90, 16, 20)  // reserve ammo: smg, sniper, sg  (index = AmmoInfo type)
    private var healLeft = 0f
    private val loot = ArrayList<Loot>()
    private var nearLoot: Loot? = null
    private val nearTmp = ArrayList<Loot>()
    private var lootGroups: List<ArrayList<Loot>> = emptyList()
    private var cooldown = 0f; private var reloadLeft = 0f; private var kick = 0f; private var flash = 0f
    private var triggerHeld = false

    // ---- bots + bullet marks
    private class Mark(val x: Float, val y: Float, val z: Float, var life: Float)
    private val marks = ArrayList<Mark>()
    private var clock = 0f
    private val rnd = Random(System.nanoTime())
    private val frnd = Random(System.nanoTime() xor 0x5DEECE66DL)  // flight route + bot landing spots (new every round)
    private val botSys = BotSystem(map, rnd, 35)
    private var deadAt = 0f

    // ---- air drop state (declared before init{} so initFlight() values are not reset)
    @Volatile var airState = 0            // 0 plane, 1 freefall, 2 parachute, 3 ground
    @Volatile var airText = ""
    @Volatile var chuteReady = false
    private var flightTime = 0f
    private var autoJumpAt = 36f
    private val LAT_MAX = 90f
    private var fdx = 0f; private var fdz = -1f; private var fsx = 0f; private var fsz = 0f; private var flightYaw = 0f
    private val FSPEED = 35.5f * 4f / 3f      // 47.3 m/s, HTML FLIGHT.speed
    private val AIR_Y = 520f
    private var pvx = 0f; private var pvz = 0f
    private var paraT = 0f; private var chuteT = 0f
    private var fogK = 8f; private var planeShown = true

    // ---- PRE-MATCH warm-up (flow 'preMatch' of the HTML): 30 s on the ground, no damage, then a fresh round starts in the plane
    @Volatile var warm = false
    @Volatile var warmLeft = 30f
    @Volatile var warmEnded = false
    private val spawn0 = pos.copyOf()

    // ---- Phase 28 state: skate-stance freefall, loot crates, dropped magazines, breathing sway, contact shadows
    @Volatile var skateMode = false          // freefall: false = head-first dive (default), true = skate stance (HTML toggleDive / V key)
    private val playerAv = AirVis()
    private val crates = ArrayList<LootCrate>()
    private val mags = ArrayList<MagDrop>()
    private val swayTmp = FloatArray(3)
    private val rlTmp = FloatArray(ReloadAnim.OUT)   // ReloadAnim sample
    private val mmRl = FloatArray(16)
    private var magDropped = false
    private var blobMesh: GlMesh? = null
    private var ringMesh: GlMesh? = null
    private var wheelMesh: GlMesh? = null

    /** SKATE / DIVE button (HTML toggleDive, V key): switches the freefall stance. Only works in freefall. */
    fun toggleDive() {
        if (airState != 1) return
        skateMode = !skateMode
        sfx?.play("jump")
        toast(if (skateMode) "SKATE STANCE - STICK = FORWARD / BACK / SIDEWAYS" else "DIVE - LOOK DOWN = FASTER")
    }

    fun startWarmup() {
        warm = true; warmLeft = 30f; warmEnded = false
        planeShown = false; airState = 3; airText = ""; onGround = true
        pos[0] = spawn0[0]; pos[1] = spawn0[1]; pos[2] = spawn0[2]; vy = 0f; yaw = 0f; bodyYaw = 0f; faceHold = 0f; pitch = -0.12f
        for (b in botSys.bots) {
            if (!b.alive) continue
            var q: FloatArray? = null
            for (k in 0 until 40) { val t = map.randomLand(frnd, pos[0], pos[2], 8f, 70f); if (t != null) { q = t; break } }
            if (q != null) { b.x = q[0]; b.y = q[1]; b.z = q[2] }
            b.air = 3; b.dive = false; b.vx = 0f; b.vy = 0f; b.vz = 0f
        }
        botSys.noDamage = true
        toast("WARM-UP: no damage. Move, aim and shoot freely for 30 seconds.")
    }

    init {
        Matrix.setIdentityM(ident, 0)
        botSys.spawnAll(pos[0], pos[2], 0f)
        botSys.onShot = { b -> sfx?.playAt("shot0", b.x, b.z, pos[0], pos[2], yaw) }
        botSys.onBotKill = { killer, victim, weapon -> addFeed(killer.name + " eliminated " + victim.name + "  [" + weapon + "]"); dropFromBot(victim) }
        spawnLoot()
        publishGun()
        initFlight()
    }

    // ---- air drop (plane -> freefall -> parachute -> ground), ported from eject()/deployChute()/descend() of the HTML

    private fun planeX(t: Float) = fsx + fdx * FSPEED * t
    private fun planeZ(t: Float) = fsz + fdz * FSPEED * t

    /** New random route every round: flies over the island in any direction, offset sideways like FLIGHT in the HTML. */
    private fun initFlight() {
        val a = frnd.nextFloat() * 6.2831855f
        fdx = sin(a); fdz = -cos(a); flightYaw = a
        val half = 800f * 4f / 3f; val lat = (frnd.nextFloat() * 2f - 1f) * LAT_MAX   // small sideways offset: the route ALWAYS passes (almost) through the middle, so the plane crosses the WHOLE island (never just a corner)
        fsx = -fdx * half - fdz * lat; fsz = -fdz * half + fdx * lat
        // time window in which the plane is over the island (circle r = 960 m: the India map reaches 886 m from the origin on the mainland, 942 m with the islands)
        val v = FSPEED; val t0 = -(fsx * fdx + fsz * fdz) / v
        val perp = kotlin.math.abs(fsx * fdz - fsz * fdx)
        val R = 960f; val hc = if (perp < R) sqrt(R * R - perp * perp) / v else 8f
        val tStart = max(PLANE_FLY_IN + 0.5f, t0 - hc); val tEnd = max(tStart + 6f, t0 + hc)
        autoJumpAt = max(36f, tEnd + 1f)      // the auto-jump only comes after the plane has flown over the whole island
        val n = botSys.bots.size
        for ((i, b) in botSys.bots.withIndex()) {
            if (!b.alive) continue
            b.air = 0; b.dive = true; b.vx = 0f; b.vy = 0f; b.vz = 0f; b.av.reset()
            b.jumpAt = tStart + (tEnd - tStart) * i / max(1, n - 1)
            val cx0 = planeX(b.jumpAt); val cz0 = planeZ(b.jumpAt)
            var tgt: FloatArray? = null
            for (k in 0 until 25) {
                val across = (frnd.nextFloat() * 2f - 1f) * 300f; val along = (frnd.nextFloat() * 2f - 1f) * 170f
                tgt = map.randomLand(frnd, cx0 - fdz * across + fdx * along, cz0 + fdx * across + fdz * along, 0f, 100f)
                if (tgt != null && hypot(tgt[0], tgt[2]) < 940f) break else tgt = null
            }
            if (tgt == null) tgt = map.randomLand(frnd, cx0, cz0, 0f, 350f)
            if (tgt == null) tgt = floatArrayOf(b.x, b.y, b.z)
            b.tx = tgt[0]; b.tz = tgt[2]
            b.x = cx0; b.y = AIR_Y - 1.7f; b.z = cz0
        }
        airState = 0; flightTime = 0f; planeShown = true; skateMode = false; playerAv.reset()
        pos[0] = planeX(0f); pos[1] = AIR_Y - 1.7f; pos[2] = planeZ(0f); onGround = false
        yaw = flightYaw; pitch = -0.15f
        toast("TAP DROP TO JUMP - AUTO-JUMP AT 36 S")
    }

    private fun eject() {
        if (airState != 0) return
        airState = 1; paraT = 0f; chuteReady = false; skateMode = false; playerAv.reset()
        pos[0] = planeX(flightTime); pos[1] = AIR_Y - 2.2f; pos[2] = planeZ(flightTime) + 3f
        vy = -3f; pvx = fdx * FSPEED; pvz = fdz * FSPEED
        pitch = -0.9f      // head-first dive, look up/down to change the angle
        sfx?.play("jump")
        toast("DIVE - LOOK DOWN = FASTER, STICK = SPEED, SKATE BUTTON = SKATE STANCE, CHUTE NEAR THE GROUND")
    }

    private fun deployChute() {
        if (airState != 1) return
        airState = 2; chuteT = 0f; sfx?.play("chute"); toast("PARACHUTE OPEN - STEER TO YOUR LANDING SPOT")
    }

    private fun landPlayer() {
        val p = floatArrayOf(pos[0], pos[1], pos[2])
        if (map.terrainBlocked(p[0], p[2], 0.5f, 0f, true) || p[0] * p[0] + p[2] * p[2] > 790f * 790f * MapData.MAPK * MapData.MAPK) {   // water near the island is fine (you swim); cliffs and the open sea far from the island are moved to land
            val q = map.randomLand(frnd, p[0], p[2], 0f, 60f) ?: map.randomLand(frnd, p[0], p[2], 0f, 300f)
            if (q != null) { p[0] = q[0]; p[2] = q[2] }
        }
        val ly = pos[1]
        p[1] = map.footY(p[0], p[2], ly); map.resolve(p, 0.42f)
        pos[0] = p[0]; pos[2] = p[2]; pos[1] = max(map.footY(p[0], p[2], ly), map.swimFloor(p[0], p[2], clock))
        vy = 0f; pvx = 0f; pvz = 0f; onGround = true; airState = 3; chuteReady = false; airText = ""; skateMode = false
        sfx?.play("land")
        matchTime = 0f; cooldown = 0.3f; pitch = -0.05f
        toast("LANDED - WEAPONS READY, SEARCH THE HOUSES FOR LOOT")
    }

    private fun stepAirPlayer(dt: Float) {
        val l = takeLook(); yaw += l[0] * 0.0045f * Prefs.lookK(); pitch = (pitch - l[1] * 0.0045f * Prefs.lookK() * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        firing = false; aiming = false; reloadReq = false; healReq = false; nadeReq = false; pickupReq = false
        val jump = jumpReq; jumpReq = false
        val kk = { v: Float -> 1f - exp(-dt * v) }
        if (airState == 0) {
            pos[0] = planeX(flightTime); pos[1] = AIR_Y - 1.7f; pos[2] = planeZ(flightTime)
            if (jump) { if (flightTime >= PLANE_FLY_IN) eject() else toast("WAIT UNTIL THE AIRCRAFT REACHES THE ISLAND") }
            if (airState == 0 && flightTime >= autoJumpAt) eject()
            val aboard = 1 + botSys.bots.count { it.alive && it.air == 0 }
            airText = "AIRCRAFT  ·  ALT ${AIR_Y.toInt()} m  ·  $aboard ABOARD  ·  AUTO-JUMP ${max(0, ceil(autoJumpAt - flightTime).toInt())}s"
            return
        }
        val gnd = map.ground(pos[0], pos[2]).coerceAtLeast(0f)
        val agl = pos[1] - gnd
        if (airState == 1) {
            chuteReady = agl <= 300f
            if (jump) { if (chuteReady) deployChute() else toast("PARACHUTE OPTION APPEARS NEAR THE GROUND") }
            if (airState == 1) { if (chuteReady) { paraT += dt; if (paraT >= 5f || agl < 90f) deployChute() } else paraT = 0f }
        }
        if (airState == 1 && skateMode) { // skate-stance freefall (skate branch of descend() in the HTML): slow fall, long glide, stick = forward / back / sideways
            val f = (-moveY).coerceIn(-1f, 1f); val r = moveX.coerceIn(-1f, 1f)
            val hx = sin(yaw); val hz = -cos(yaw)
            val sp = if (f >= 0f) 10f + 20f * f else 10f * (1f + f)
            val vT = if (f >= 0f) -(9f + 4f * f) else -(9f + 5.5f * f)
            val k = kk(2.4f)
            pvx += (hx * sp - hz * 12f * r - pvx) * k; pvz += (hz * sp + hx * 12f * r - pvz) * k
            vy += (vT - vy) * kk(2.2f)
        } else if (airState == 1) { // dlFreefall()
            val pd = (-pitch).coerceIn(0f, 1.35f); val s = sin(pd); val c = cos(pd)
            val f = (-moveY).coerceIn(-1f, 1f)
            var hT = 3f + 32f * c.pow(1.6f); hT *= if (f > 0f) 1f + 0.15f * f else 1f + 0.4f * f
            val vT = -(9f + 56f * s.pow(1.1f))
            pvx += (sin(yaw) * hT - pvx) * kk(1.6f); pvz += (-cos(yaw) * hT - pvz) * kk(1.6f)
            vy += (vT - vy) * kk(1.4f)
        } else { // parachute: descend() with fl = flare near the ground
            chuteT += dt
            val fl = max(0f, 1f - agl / 16f)
            vy = min(-5.5f + 3.6f * fl, vy + dt * 32f)
            val fx = sin(yaw); val fz = -cos(yaw); val rx = cos(yaw); val rz = sin(yaw)
            var mx = fx * -moveY + rx * moveX; var mz = fz * -moveY + rz * moveX
            val len = hypot(mx, mz); if (len > 1f) { mx /= len; mz /= len }
            val sp = 17f * (1f - 0.55f * fl)
            pvx += (mx * sp - pvx) * kk(3f); pvz += (mz * sp - pvz) * kk(3f)
        }
        pos[0] += pvx * dt; pos[2] += pvz * dt
        val g2 = map.ground(pos[0], pos[2]).coerceAtLeast(0f)
        val roof = Viaduct.floor(pos[0], pos[2], pos[1] + 0.58f)   // land on the viaduct deck
        if (roof > g2 && pos[1] + vy * dt <= roof) { pos[1] = roof; landPlayer(); return }
        if (pos[1] + vy * dt <= g2) { pos[1] = g2; landPlayer(); return }
        pos[1] += vy * dt
        val kmh = sqrt(pvx * pvx + vy * vy + pvz * pvz) * 3.6f
        val alt = max(0f, pos[1] - g2)
        playerAv.step(dt, airState == 1 && skateMode, airState == 1 && !skateMode, airState == 2,
            if (airState == 2) max(0f, 1f - alt / 16f) else 0f, true, pvx, vy, pvz, clock)
        airText = (if (airState == 2) "PARACHUTE" else "FREEFALL") + "  ·  ALT ${alt.toInt()} m  ·  ${kmh.toInt()} km/h" +
            (if (airState == 1 && chuteReady) "  ·  CHUTE IN ${max(0, ceil(5f - paraT).toInt())}s" else "")
    }

    private fun stepBotsAir(dt: Float) {
        val kk = { v: Float -> 1f - exp(-dt * v) }
        for (b in botSys.bots) {
            if (!b.alive || b.air == 3) continue
            if (b.air == 0) {
                b.x = planeX(flightTime); b.y = AIR_Y - 1.7f; b.z = planeZ(flightTime)
                if (flightTime >= b.jumpAt) { b.air = 1; b.y = AIR_Y - 2.2f; b.vy = -3f; b.vx = fdx * FSPEED; b.vz = fdz * FSPEED; b.dive = true }
                continue
            }
            val dx = b.tx - b.x; val dz = b.tz - b.z; val dist = hypot(dx, dz); val dv = max(8f, dist)
            val ux = dx / dv; val uz = dz / dv
            val gnd = map.ground(b.x, b.z).coerceAtLeast(0f); val agl = b.y - gnd
            if (b.air == 1) {
                b.dive = dist > 90f
                if (agl < 48f + (if (b.dive) 42f else 0f)) { b.air = 2; b.chuteT = 0f }
            }
            if (b.air == 1) {
                if (b.dive) { b.vy = max(-44f, b.vy - dt * 30f); b.vx += (ux * 34f - b.vx) * kk(2f); b.vz += (uz * 34f - b.vz) * kk(2f) }
                else { b.vy += (-13f - b.vy) * kk(2.2f); b.vx += (ux * 30f - b.vx) * kk(2.4f); b.vz += (uz * 30f - b.vz) * kk(2.4f) }
            } else {
                b.chuteT += dt
                val fl = max(0f, 1f - agl / 16f)
                b.vy = min(-5.5f + 3.6f * fl, b.vy + dt * 32f)
                val sp = 17f * (1f - 0.55f * fl)
                b.vx += (ux * sp - b.vx) * kk(3f); b.vz += (uz * sp - b.vz) * kk(3f)
            }
            b.x += b.vx * dt; b.z += b.vz * dt
            val g2 = map.ground(b.x, b.z).coerceAtLeast(0f)
            if (b.y + b.vy * dt <= g2) {
                b.y = g2
                if (map.terrainBlocked(b.x, b.z, 0.45f) || map.boxBlocked(b.x, b.z, 0.45f, b.y)) {
                    val q = map.randomLand(frnd, b.x, b.z, 0f, 60f) ?: map.randomLand(frnd, b.tx, b.tz, 0f, 60f)
                    if (q != null) { b.x = q[0]; b.y = q[1]; b.z = q[2] }
                }
                b.air = 3; b.vx = 0f; b.vy = 0f; b.vz = 0f; b.speed = 0f; b.think = 0f; b.cooldown = 1.5f + frnd.nextFloat(); b.av.reset()
            } else {
                b.y += b.vy * dt
                if (kotlin.math.abs(b.vx) + kotlin.math.abs(b.vz) > 0.5f) b.yaw = atan2(-b.vx, -b.vz)
                b.av.step(dt, b.air == 1 && !b.dive, b.air == 1 && b.dive, b.air == 2, if (b.air == 2) max(0f, 1f - (b.y - g2) / 16f) else 0f, false, b.vx, b.vy, b.vz, clock)
            }
        }
    }

    /** Backpack panel (ffBag of the HTML): weapons per slot, reserve ammo, medkits, mushrooms, armor, grenades. */
    fun backpackLines(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val slotName = arrayOf("LONG GUN", "SHORT GUN", "SNIPER")
        for (s in 0 until 3) { val g = owned[s]; out.add(slotName[s] to (if (g >= 0) guns[g].name + "  " + ammo[g] + "/" + guns[g].mag else "EMPTY")) }
        out.add("FISTS" to "ALWAYS")
        for (t in 0 until 3) out.add(("Reserve ammo " + AmmoInfo.NAMES[t]) to pool[t].toString())
        out.add("Medkits" to medkits.toString()); out.add("Mushrooms" to mushrooms.toString())
        out.add("Armor" to (shield.toInt().toString() + (if (vestLvl > 0) "  (Lv." + vestLvl + ")" else "")))
        out.add("Grenades" to grenadeCount.toString())
        return out
    }

    private fun publishGun() { if (fists) { gunName = "FISTS"; ammoMax = 0; ammoNow = 0; ammoReserve = 0; return }; val g = guns[gi]; gunName = g.name; ammoMax = g.mag; ammoNow = ammo[gi]; ammoReserve = pool[AmmoInfo.typeOf(g)] }

    private val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor; layout(location=2) in vec2 aUV; layout(location=3) in vec3 aCol;
uniform mat4 uVP; uniform mat4 uModel; uniform mat3 uNM; uniform vec3 uEye;
out vec3 vN; out vec2 vUV; out vec3 vCol; out float vD; out vec3 vW;
void main(){ vec4 w=uModel*vec4(aPos,1.0); gl_Position=uVP*w; vN=uNM*aNor; vUV=aUV; vCol=aCol; vD=distance(w.xyz,uEye); vW=w.xyz; }"""
    private val FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vN; in vec2 vUV; in vec3 vCol; in float vD; in vec3 vW;
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
uniform sampler2D uTex; uniform int uMode; uniform vec3 uColor; uniform float uAlpha; uniform float uFog;
uniform float uDeckOn;   // PHASE 67: arch-viaduct deck (bridge group) = black asphalt + white dashed centre line
uniform sampler2D uRoad; uniform vec4 uRoadXf; uniform float uRoadOn;   // ROAD PAINT: black asphalt + white dashed centre line (assets/maps/roadpaint.bin), terrain only
const vec3 ROADC=vec3(0.045,0.045,0.05);
const vec3 SUN=vec3(-0.5652,0.6935,0.3596);
out vec4 o;
void main(){
  if(uMode==3){ o=vec4(uColor,uAlpha); return; }
  if(uMode==5){ o=vec4(uColor,uAlpha*vCol.r); return; }
  vec4 tx = (uMode==0 || uMode==4) ? texture(uTex,vUV) : vec4(1.0);
  if(uMode==4 && tx.a<0.5) discard;
  vec3 a = (uMode==0 || uMode==4) ? tx.rgb : (uMode==1 ? vCol : uColor);
  if(uRoadOn>0.5){
    vec2 pm = texture(uRoad, (vW.xz-uRoadXf.xy)*uRoadXf.zw).rg;
    a = mix(a, ROADC, smoothstep(0.2,0.8,pm.r));
    a = mix(a, vec3(0.93), smoothstep(0.1,0.5,pm.g));
  }
  if(uDeckOn>0.5){
    // viaduct raster of the game: deck x 269.5..414, z 184.5..194.5, top ~20.55 m, centre line z = 189.5 (rails sit at the edges)
    float fy = abs(normalize(cross(dFdx(vW), dFdy(vW))).y);
    if(fy>0.9 && vW.y>19.8 && vW.y<20.8 && vW.x>269.5 && vW.x<414.0 && abs(vW.z-189.5)<4.3){
      float dz = abs(vW.z-189.5);
      float dash = (1.0-smoothstep(0.10,0.16,dz)) * (1.0-smoothstep(2.9,3.1,mod(vW.x-269.5,8.0)));
      a = mix(ROADC, vec3(0.93), dash);
    }
  }
  vec3 n = normalize(vN); if(!gl_FrontFacing) n=-n;
  float sh = shadowAt(n, vW);
  vec3 hemi = mix(vec3(0.49,0.54,0.31), vec3(0.81,0.89,1.0), 0.5+0.5*n.y);
  vec3 lit = a*(hemi*0.62 + vec3(1.0,0.91,0.78)*max(dot(n,SUN),0.0)*0.95*sh) + vec3(0.12,0.115,0.10)*a;
  float f = clamp((vD-160.0*uFog)/(740.0*uFog),0.0,1.0);
  o = vec4(mix(lit, vec3(0.74,0.84,0.93), f*f), 1.0);
}"""

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader error: " + GL.glGetShaderInfoLog(s))
        return s
    }

    private fun texFromBmp(bmp: Bitmap): Int {
        val id = IntArray(1); GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT)
        return id[0]
    }

    // ---- ROAD PAINT: every road of the map black with a white dashed centre line. One RG8 texture over the whole island
    // (R = asphalt coverage, G = dash coverage, 0.5 m per texel), sampled in world XZ by the terrain shader.
    private var roadTex = 0
    private val roadXf = floatArrayOf(0f, 0f, 1f, 1f)

    private fun loadRoadPaint(): Int {
        val d = RoadPaint.read(ctx) ?: return 0   // missing / damaged file = the old grey roads, the game still runs
        return try {
            val bb = ByteBuffer.allocateDirect(d.rg.size).order(ByteOrder.nativeOrder()); bb.put(d.rg); bb.position(0)
            val id = IntArray(1); GL.glGenTextures(1, id, 0)
            GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
            GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RG8, d.w, d.h, 0, GL.GL_RG, GL.GL_UNSIGNED_BYTE, bb)
            GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4)
            GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            roadXf[0] = d.x0; roadXf[1] = d.z0; roadXf[2] = 1f / (d.x1 - d.x0); roadXf[3] = 1f / (d.z1 - d.z0)
            id[0]
        } catch (e: Throwable) { 0 }
    }

    /** One flag per triangle of the "road" group: true = canal bank / dam rock (not a road, keeps its old grey look). */
    private fun loadRoadBlobFlags(): BooleanArray? = try {
        val raw = ctx.assets.open("maps/roadblob.bin").use { it.readBytes() }
        val n = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        BooleanArray(n) { (raw[4 + (it shr 3)].toInt() shr (it and 7)) and 1 == 1 }
    } catch (e: Throwable) { null }   // no file (the island has no canal banks) = every road triangle is plain road

    private fun loadTex(path: String): Int {
        val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        return uploadTex(bmp)
    }

    /** Ground texture with the pictures of the removed demo houses wiped off (TexClean); the plain texture if anything goes wrong. */
    private fun loadTerrainTex(): Int {
        val bmp = ctx.assets.open("textures/dustlineTexT.jpg").use { BitmapFactory.decodeStream(it) }
        val fixed: Bitmap? = try { TexClean.cleanTerrain(bmp, map.removedHouseRects) } catch (e: Throwable) { null }
        if (fixed != null) { bmp.recycle(); return uploadTex(fixed) }
        return uploadTex(bmp)
    }

    private fun uploadTex(bmp: Bitmap): Int {
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

    private fun upload(verts: FloatArray, idx: IntArray): GlMesh {
        val vao = IntArray(1); GL.glGenVertexArrays(1, vao, 0); GL.glBindVertexArray(vao[0])
        val b = IntArray(2); GL.glGenBuffers(2, b, 0)
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()); vb.asFloatBuffer().put(verts)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0]); GL.glBufferData(GL.GL_ARRAY_BUFFER, verts.size * 4, vb, GL.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()); ib.asIntBuffer().put(idx)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, b[1]); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GL.GL_STATIC_DRAW)
        val st = MapData.STRIDE * 4
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, st, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, st, 12)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, st, 24)
        GL.glEnableVertexAttribArray(3); GL.glVertexAttribPointer(3, 3, GL.GL_FLOAT, false, st, 32)
        GL.glBindVertexArray(0)
        return GlMesh(vao[0], idx.size)
    }

    /** unit cylinder (radius 1, height 1, axis Y, with caps) - the skateboard wheels (CylinderGeometry .09 / .07 of the HTML) */
    private fun unitCylinder(seg: Int): GlMesh {
        val st = MapData.STRIDE
        val nSide = (seg + 1) * 2; val nCap = seg + 2
        val v = FloatArray((nSide + 2 * nCap) * st); val idx = IntArray(seg * 6 + seg * 6)
        fun put(i: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            val o = i * st
            v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz
            v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f
        }
        var ii = 0
        for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val cx = cos(a); val sz = sin(a)
            put(k * 2, cx, -0.5f, sz, cx, 0f, sz); put(k * 2 + 1, cx, 0.5f, sz, cx, 0f, sz)
            if (k < seg) { val b = k * 2; idx[ii++] = b; idx[ii++] = b + 1; idx[ii++] = b + 2; idx[ii++] = b + 1; idx[ii++] = b + 3; idx[ii++] = b + 2 }
        }
        for (cap in 0..1) {
            val y = if (cap == 0) 0.5f else -0.5f; val ny = if (cap == 0) 1f else -1f
            val b = nSide + cap * nCap
            put(b, 0f, y, 0f, 0f, ny, 0f)
            for (k in 0..seg) { val a = k * 6.2831855f / seg; put(b + 1 + k, cos(a), y, sin(a), 0f, ny, 0f) }
            for (k in 0 until seg) { idx[ii++] = b; idx[ii++] = b + 1 + k; idx[ii++] = b + 2 + k }
        }
        return upload(v, idx)
    }

    /** flat ring in the XZ plane (RingGeometry of the loot-box marker) */
    private fun unitRing(seg: Int, r0: Float, r1: Float): GlMesh {
        val st = MapData.STRIDE
        val v = FloatArray((seg + 1) * 2 * st); val idx = IntArray(seg * 6)
        var ii = 0
        for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val c = cos(a); val s = sin(a)
            for (j in 0..1) { val r = if (j == 0) r0 else r1; val o = (k * 2 + j) * st; v[o] = c * r; v[o + 2] = s * r; v[o + 4] = 1f }
            if (k < seg) { val b = k * 2; idx[ii++] = b; idx[ii++] = b + 1; idx[ii++] = b + 2; idx[ii++] = b + 1; idx[ii++] = b + 3; idx[ii++] = b + 2 }
        }
        return upload(v, idx)
    }

    /** soft round blob: radial alpha 0.62 -> 0.28 (at 55 %) -> 0 exactly like the gradient texture of contact() in the reference pack. Alpha sits in the vertex colour red channel (shader mode 5). */
    private fun blobDisc(seg: Int): GlMesh {
        val st = MapData.STRIDE
        val rr = floatArrayOf(0f, 0.065f, 0.55f, 1f); val aa = floatArrayOf(0.62f, 0.62f, 0.28f, 0f)
        val nv = 1 + 3 * (seg + 1); val v = FloatArray(nv * st); val idx = IntArray(seg * 3 + 2 * seg * 6)
        v[4] = 1f; v[8] = 0.62f
        for (r in 1..3) for (k in 0..seg) {
            val a = k * 6.2831855f / seg; val o = (1 + (r - 1) * (seg + 1) + k) * st
            v[o] = cos(a) * rr[r]; v[o + 2] = sin(a) * rr[r]; v[o + 4] = 1f; v[o + 8] = aa[r]
        }
        var ii = 0
        for (k in 0 until seg) { idx[ii++] = 0; idx[ii++] = 1 + k; idx[ii++] = 2 + k }
        for (r in 1..2) for (k in 0 until seg) {
            val a0 = 1 + (r - 1) * (seg + 1) + k; val b0 = 1 + r * (seg + 1) + k
            idx[ii++] = a0; idx[ii++] = b0; idx[ii++] = a0 + 1; idx[ii++] = a0 + 1; idx[ii++] = b0; idx[ii++] = b0 + 1
        }
        return upload(v, idx)
    }

    private fun unitCube(): GlMesh {
        val n = arrayOf(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f))
        val u = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        val v = FloatArray(24 * MapData.STRIDE); val idx = IntArray(36)
        for (f in 0 until 6) {
            val nn = n[f]; val uu = u[f]
            val vv = floatArrayOf(nn[1] * uu[2] - nn[2] * uu[1], nn[2] * uu[0] - nn[0] * uu[2], nn[0] * uu[1] - nn[1] * uu[0])
            val sg = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
            for (k in 0 until 4) {
                val o = (f * 4 + k) * MapData.STRIDE
                for (a in 0..2) v[o + a] = 0.5f * (nn[a] + sg[k][0] * uu[a] + sg[k][1] * vv[a])
                v[o + 3] = nn[0]; v[o + 4] = nn[1]; v[o + 5] = nn[2]
            }
            val b = f * 4; val j = f * 6
            idx[j] = b; idx[j + 1] = b + 1; idx[j + 2] = b + 2; idx[j + 3] = b; idx[j + 4] = b + 2; idx[j + 5] = b + 3
        }
        return upload(v, idx)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        layers.clear(); tex.clear(); gunGl.clear(); planeGl.clear(); planeReady = false; chuteMesh = null; post.lost()
        prog = GL.glCreateProgram()
        GL.glAttachShader(prog, compile(GL.GL_VERTEX_SHADER, VS)); GL.glAttachShader(prog, compile(GL.GL_FRAGMENT_SHADER, FS))
        GL.glLinkProgram(prog)
        uMode = GL.glGetUniformLocation(prog, "uMode"); uColor = GL.glGetUniformLocation(prog, "uColor")
        uModel = GL.glGetUniformLocation(prog, "uModel"); uNM = GL.glGetUniformLocation(prog, "uNM")
        tex["terrain"] = loadTerrainTex()
        tex["atlas"] = tex["terrain"]!!   // the island has no textured-atlas buildings; the id is only a placeholder for the draw loop
        roadTex = loadRoadPaint()
        val blobFlags = loadRoadBlobFlags()
        for (g in map.groups) {
            if (g.mat == "road" && blobFlags != null && blobFlags.size * 3 == g.indices.size) {
                val thin = ArrayList<Int>(); val fat = ArrayList<Int>()
                for (t in blobFlags.indices) { val d = if (blobFlags[t]) fat else thin; d.add(g.indices[t * 3]); d.add(g.indices[t * 3 + 1]); d.add(g.indices[t * 3 + 2]) }
                layers.add(Layer(upload(g.verts, thin.toIntArray()), "road"))
                if (fat.isNotEmpty()) layers.add(Layer(upload(g.verts, fat.toIntArray()), "roadb"))
            } else layers.add(Layer(upload(g.verts, g.indices), g.mat))
        }
        val s = 4000f
        val sea = floatArrayOf(-s, 0f, -s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, s, 0f, -s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f,
            s, 0f, s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, -s, 0f, s, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
        seaMesh = upload(sea, intArrayOf(0, 2, 1, 0, 3, 2))
        cube = unitCube()
        wheelMesh = unitCylinder(14); ringMesh = unitRing(24, 0.85f, 0.97f); blobMesh = blobDisc(20)
        run { // unit sphere for explosions
            val la = 12; val lo = 16; val v = FloatArray((la + 1) * (lo + 1) * MapData.STRIDE); val idx = IntArray(la * lo * 6)
            for (i in 0..la) for (j in 0..lo) {
                val th = i * Math.PI.toFloat() / la; val ph = j * 6.2831855f / lo; val o = (i * (lo + 1) + j) * MapData.STRIDE
                v[o] = sin(th) * cos(ph); v[o + 1] = cos(th); v[o + 2] = sin(th) * sin(ph); v[o + 3] = v[o]; v[o + 4] = v[o + 1]; v[o + 5] = v[o + 2]
            }
            var k = 0
            for (i in 0 until la) for (j in 0 until lo) {
                val a = i * (lo + 1) + j; val b = a + lo + 1
                idx[k++] = a; idx[k++] = b; idx[k++] = a + 1; idx[k++] = a + 1; idx[k++] = b; idx[k++] = b + 1
            }
            sphere = upload(v, idx)
        }
        run { // zone wall: unit cylinder, 96 segments, y 0..1
            val seg = 96; val v = FloatArray((seg + 1) * 2 * MapData.STRIDE); val idx = IntArray(seg * 6)
            for (i in 0..seg) {
                val a = i * 6.2831855f / seg
                for (k in 0..1) { val o = (i * 2 + k) * MapData.STRIDE; v[o] = cos(a); v[o + 1] = k.toFloat(); v[o + 2] = sin(a); v[o + 4] = 1f }
            }
            for (i in 0 until seg) { val b = i * 2; val j = i * 6; idx[j] = b; idx[j + 1] = b + 1; idx[j + 2] = b + 2; idx[j + 3] = b + 1; idx[j + 4] = b + 3; idx[j + 5] = b + 2 }
            wall = upload(v, idx)
        }
        for (g in guns) {
            try {
                val m = GunModel(ctx, g.id)
                gunGl[g.id] = Pair(upload(m.verts, m.indices), m)
                if (m.textured && !tex.containsKey(g.id)) tex[g.id] = loadTex("models/guns/${g.id}_tex.jpg")
            } catch (e: Exception) { /* gun without model: only the HUD name is shown */ }
        }
        try {
            val plm = PlaneModel(ctx)
            planeGl.clear()
            for (pt in plm.parts) planeGl.add(PlanePartGl(upload(pt.verts, pt.indices), pt.prop, pt.px, pt.py, pt.pz))
            planeReady = true
        } catch (e: Exception) { planeReady = false }
        try {
            val pm3 = ParachuteModel(ctx)
            chuteTex = loadTex("models/parachute/parachute_tex.jpg")
            chuteMesh = upload(pm3.verts, pm3.indices)
        } catch (e: Exception) { chuteMesh = null }
        chars.init()
        shadow.init()
        natureGl = try { map.nature?.let { n -> NatureGl(n).also { it.init() } } } catch (e: Throwable) { null }   // trees / bushes / rocks: a failure just means no vegetation
        GL.glClearColor(0.74f, 0.84f, 0.93f, 1f)
        GL.glEnable(GL.GL_DEPTH_TEST)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GL.glViewport(0, 0, w, h); surfW = w; surfH = h
        Matrix.perspectiveM(proj, 0, 70f, w.toFloat() / h, 0.05f, 2500f)
    }

    // ---------------------------------------------------------------- gameplay
    private fun endMatch(won: Boolean) {
        if (matchState != 0) return
        matchState = if (won) 1 else 2
        alivePlace = if (won) 1 else botSys.bots.count { it.alive } + 1
        firing = false; healLeft = 0f; healProgress = -1f
        aiming = false; adsAmount = 0f; scopeOverlay = false; run = false; footSpeed = 0f; moveX = 0f; moveY = 0f; winT = 0f
        if (won) { crouching = false; prone = false; sfx?.play("win", 1f) }
        result = Rewards.compute(kills, alivePlace, won, matchTime, false)
    }

    // ---------------------------------------------------------------- brass casings + muzzle smoke
    private class Casing(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val shell: Boolean)
    private class Puff(var x: Float, var y: Float, var z: Float, var life: Float)
    private val casings = ArrayList<Casing>()
    private val puffs = ArrayList<Puff>()
    /** every shot: a brass casing (red shell for shotguns) flies out to the right and bounces on the ground, plus a short puff of smoke at the muzzle. */
    private fun shotFx(ox: Float, oy: Float, oz: Float, fwd: FloatArray, right: FloatArray, up: FloatArray, shell: Boolean) {
        val px = ox + right[0] * 0.17f + fwd[0] * 0.42f - up[0] * 0.14f; val py = oy + right[1] * 0.17f + fwd[1] * 0.42f - up[1] * 0.14f; val pz = oz + right[2] * 0.17f + fwd[2] * 0.42f - up[2] * 0.14f
        val r = 1.6f + rnd.nextFloat() * 1.4f; val u = 1.2f + rnd.nextFloat() * 1.4f; val f = (rnd.nextFloat() - 0.3f) * 0.8f
        casings.add(Casing(px, py, pz, right[0] * r + up[0] * u + fwd[0] * f, right[1] * r + up[1] * u + fwd[1] * f, right[2] * r + up[2] * u + fwd[2] * f, 1.8f, shell))
        while (casings.size > 40) casings.removeAt(0)
        puffs.add(Puff(ox + fwd[0] * 0.9f - up[0] * 0.1f, oy + fwd[1] * 0.9f - up[1] * 0.1f, oz + fwd[2] * 0.9f - up[2] * 0.1f, 0.55f))
        while (puffs.size > 14) puffs.removeAt(0)
    }
    private fun stepShotFx(dt: Float) {
        stepMags(dt)
        var i = casings.size - 1
        while (i >= 0) {
            val c = casings[i]; c.life -= dt
            c.vy -= 9.8f * dt; c.x += c.vx * dt; c.y += c.vy * dt; c.z += c.vz * dt
            val g = map.ground(c.x, c.z) + 0.02f
            if (c.y < g) { c.y = g; c.vy = if (c.vy < -0.9f) -c.vy * 0.3f else 0f; c.vx *= 0.55f; c.vz *= 0.55f }
            if (c.life <= 0f) casings.removeAt(i); i--
        }
        i = puffs.size - 1
        while (i >= 0) { val p = puffs[i]; p.life -= dt; p.y += 0.7f * dt; if (p.life <= 0f) puffs.removeAt(i); i-- }
    }
    private fun drawShotFx() {
        drawMags()
        for (c in casings) {
            if (c.shell) drawCube(c.x, c.y, c.z, 0.05f, 0.022f, 0.022f, 0.72f, 0.1f, 0.08f) else drawCube(c.x, c.y, c.z, 0.034f, 0.013f, 0.013f, 0.88f, 0.68f, 0.22f)
        }
        val sp = sphere ?: return
        if (puffs.isEmpty()) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glBindVertexArray(sp.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 0.78f, 0.78f, 0.8f)
        for (p in puffs) {
            val k = (p.life / 0.55f).coerceIn(0f, 1f)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, p.x, p.y, p.z); val sc = 0.08f + (1f - k) * 0.22f; Matrix.scaleM(mm, 0, sc, sc, sc)
            setModel(mm)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.38f * k)
            GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    // ---------------------------------------------------------------- blood / bone hit FX (DLFX.hit of the HTML: dark-red blobs + a few bone-white chips, shorter lived)
    private class Blood(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val s: Float, val bone: Boolean)
    private val blood = ArrayList<Blood>()
    private fun bloodBurst(x: Float, y: Float, z: Float, head: Boolean) {
        val nb = if (head) 9 else 6; val ns = if (head) 6 else 4
        for (i in 0 until nb) { val a = rnd.nextFloat() * 6.283f; val sp = 1.2f + rnd.nextFloat() * 2.6f
            blood.add(Blood(x, y, z, cos(a) * sp, 0.8f + rnd.nextFloat() * 2.2f, sin(a) * sp, 0.3f + rnd.nextFloat() * 0.25f, 0.035f + rnd.nextFloat() * 0.05f, false)) }
        for (i in 0 until ns) { val a = rnd.nextFloat() * 6.283f; val sp = 2.2f + rnd.nextFloat() * 2.4f
            blood.add(Blood(x, y, z, cos(a) * sp, 0.5f + rnd.nextFloat() * 2f, sin(a) * sp, 0.22f + rnd.nextFloat() * 0.16f, 0.03f, true)) }
        while (blood.size > 160) blood.removeAt(0)
    }
    private fun stepBlood(dt: Float) {
        var i = blood.size - 1
        while (i >= 0) { val b = blood[i]; b.life -= dt; b.vy -= 9f * dt; b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt; if (b.life <= 0f) blood.removeAt(i); i-- }
    }
    private fun drawBlood() {
        if (blood.isEmpty()) return
        for (b in blood) if (b.bone) drawCube(b.x, b.y, b.z, b.s, b.s, b.s, 0.93f, 0.9f, 0.8f) else drawCube(b.x, b.y, b.z, b.s, b.s, b.s, 0.45f, 0.02f, 0.04f)
    }

    // ---------------------------------------------------------------- crouch / SIT (crouching + crouchAmt of the HTML)
    @Volatile var crouching = false
    @Volatile var crouchAmt = 0f
    @Volatile private var crouchReq = false
    fun toggleCrouch() { crouchReq = true }
    /** Phase 55 PRONE: lie flat. Lower eye (0.45 m below the crouch height), slowest walk, steadiest aim. Third person still shows the crouch pose. */
    @Volatile var prone = false
    @Volatile var proneAmt = 0f
    /** lying prone and looking UP (pitch > 0.55) turns the body onto its back (face up, feet first); looking down again turns it back */
    @Volatile var supine = false
    @Volatile var supineLock = false   // PRONE button: 1st tap face down, 2nd tap on the back, 3rd tap crouch
    @Volatile var supineAmt = 0f
    @Volatile private var proneReq = false
    fun toggleProne() { proneReq = true }
    /** how far the eye / head is lowered (m): crouch 0.5 + prone 0.45 */
    private val eyeDrop: Float get() = 0.5f * crouchAmt + 0.45f * proneAmt
    private fun canStandUp(): Boolean = map.ceilAt(pos[0], pos[2], pos[1] + 0.9f, pos[1] + Viaduct.BODY_H) >= 1e8f
    private fun stepCrouch(dt: Float) {
        if (crouchReq) {
            crouchReq = false
            if (playerAlive && airState == 3 && matchState == 0) {
                if (crouching) { if (canStandUp()) { crouching = false; toast("STANDING") } else toast("Not enough room to stand up.") }
                else { crouching = true; run = false; aiming = false; toast("CROUCHING / slower, steadier aim, harder to spot") }
            }
        }
        if (proneReq) {
            proneReq = false
            if (playerAlive && airState == 3 && matchState == 0 && !swimming) {
                if (prone && !supineLock) { supineLock = true; toast("ON BACK / lying face up") }
                else if (prone) { prone = false; supineLock = false; toast("CROUCHING") }
                else { prone = true; supineLock = false; crouching = true; run = false; aiming = false; toast("PRONE / slowest, lowest, steadiest aim") }
            }
        }
        if (crouching && (airState != 3 || !playerAlive || swimming)) crouching = false
        if (crouching && run) { if (canStandUp()) crouching = false else run = false }   // RUN stands you up
        if (!crouching) prone = false
        val target = if (crouching) 1f else 0f
        crouchAmt += (target - crouchAmt) * (1f - exp(-dt * 12f)); if (abs(target - crouchAmt) < 0.002f) crouchAmt = target
        val pTarget = if (prone) 1f else 0f
        proneAmt += (pTarget - proneAmt) * (1f - exp(-dt * 12f)); if (abs(pTarget - proneAmt) < 0.002f) proneAmt = pTarget
        supine = prone && supineLock
        if (!prone) supineLock = false
        val sT = if (supine) 1f else 0f
        supineAmt += (sT - supineAmt) * (1f - exp(-dt * 6f)); if (abs(sT - supineAmt) < 0.002f) supineAmt = sT
    }

    // ---------------------------------------------------------------- spectate after death (SPEC of the HTML: mobile BR style, no debrief card)
    @Volatile var spec = false
    @Volatile var specOver = false
    @Volatile var specTitle = ""
    @Volatile var specSub = ""
    @Volatile var specLabel = "SPECTATING"
    @Volatile var specName = "-"
    @Volatile var specLeft = 10f          // the BACK (10) countdown of the HTML
    @Volatile var specBannerT = 0f        // seconds since the banner text changed
    private var specT = 0f
    private var specTarget: Bot? = null
    private var specKiller: Bot? = null
    private var specLost = 0f
    private var specUserT = -1e9f
    private var specOffX = 0f; private var specOffY = 0f; private var specOffZ = 0f
    private var deathYaw = 0f; private var fallDir = 1f
    @Volatile private var specCycleDir = 0
    private val specTmp = FloatArray(3)
    private val specCam = FloatArray(6)
    fun specCycle(dir: Int) { specCycleDir = dir }
    private fun easeS(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun specAlive(): List<Bot> = botSys.bots.filter { it.alive }
    private fun specBanner(title: String, sub: String) { specTitle = title; specSub = sub; specBannerT = 0f }
    private fun specSetTarget(b: Bot?) { specTarget = b; specLost = 0f; specName = b?.name?.uppercase() ?: "-" }
    private fun specPick() {
        val alive = specAlive(); if (alive.isEmpty()) { specSetTarget(null); return }
        val k = specKiller
        if (k != null && k.alive) { specSetTarget(k); return }
        val t = specTarget; val rx = t?.x ?: pos[0]; val rz = t?.z ?: pos[2]
        specSetTarget(alive.minByOrNull { (it.x - rx) * (it.x - rx) + (it.z - rz) * (it.z - rz) })
    }
    private fun specFinish(alive: List<Bot>) {
        if (specOver) return
        specOver = true
        specBanner("MATCH OVER", if (alive.size == 1) "WINNER / " + alive[0].name.uppercase() else "NO SURVIVORS")
        specLabel = if (alive.isNotEmpty()) "WINNER" else "MATCH OVER"
        if (alive.isNotEmpty()) specSetTarget(alive[0])
    }
    /** specBegin(): the body falls, the camera lifts up and back, then glides to the player who eliminated you. */
    private fun beginSpectate(by: String) {
        if (spec) return
        spec = true; specOver = false; specT = 0f; specLost = 0f; specUserT = -1e9f; specLeft = 10f; specLabel = "SPECTATING"
        specKiller = botSys.bots.firstOrNull { it.alive && it.name == by }
        specOffX = camX - pos[0]; specOffY = camY - (pos[1] + 0.55f); specOffZ = camZ - pos[2]
        deathYaw = if (Prefs.thirdPerson) bodyYaw else yaw
        val k = specKiller
        fallDir = if (k != null) { val fx = sin(yaw); val fz = -cos(yaw); if (fx * (k.x - pos[0]) + fz * (k.z - pos[2]) >= 0f) 1f else -1f } else if (rnd.nextBoolean()) 1f else -1f
        aiming = false; diving = false; diveDepth = 0f; diveSink = 0f; diveAcc = 0f; underwater = false; swimAmt = 0f; adsAmount = 0f; scopeOverlay = false; moveX = 0f; moveY = 0f; run = false; firing = false; reloadLeft = 0f; reloadProgress = -1f
        specBanner(if (k != null) "ELIMINATED BY " + k.name.uppercase() else "YOU WERE ELIMINATED",
            "#" + alivePlace + "  /  " + kills + " ELIM  /  " + String.format(java.util.Locale.US, "%d:%02d", matchTime.toInt() / 60, matchTime.toInt() % 60))
        specPick()
    }
    private fun specStep(dt: Float) {
        val l = takeLook()
        if (l[0] != 0f || l[1] != 0f) specUserT = clock
        yaw += l[0] * 0.0045f * Prefs.lookK(); pitch = (pitch - l[1] * 0.0045f * Prefs.lookK() * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        specT += dt; specBannerT += dt; specLeft = max(0f, specLeft - dt)
        val alive = specAlive()
        val cd = specCycleDir; specCycleDir = 0
        if (cd != 0 && !specOver && alive.isNotEmpty()) {
            var i = alive.indexOf(specTarget); i = if (i < 0) 0 else (i + cd + alive.size) % alive.size
            specSetTarget(alive[i]); specT = max(specT, 3.4f)
        }
        if (specOver) return
        if (alive.size <= 1) { specFinish(alive); return }
        val t = specTarget
        if (t == null || !t.alive) { specLost += dt; if (t == null || specLost > 1.4f) specPick() } else specLost = 0f
    }
    /** specClamp(): pull the camera in front of walls / terrain on the way from [from] to the wanted point. */
    private fun specClamp(fx: Float, fy: Float, fz: Float, tx: Float, ty: Float, tz: Float) {
        var x = tx; var y = ty; var z = tz
        val dx = tx - fx; val dy = ty - fy; val dz = tz - fz; val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len > 0.01f) {
            val th = map.rayWorld(fx, fy, fz, dx / len, dy / len, dz / len, len + 0.25f)
            if (th >= 0f) { val a = max(0.35f, th - 0.3f); if (a < len) { x = fx + dx / len * a; y = fy + dy / len * a; z = fz + dz / len * a } }
        }
        specTmp[0] = x; specTmp[1] = max(map.ground(x, z) + 0.3f, y); specTmp[2] = z
    }
    /** specCamera(): fills specCam with eye (0..2) and look-at point (3..5). */
    private fun specCamera(dt: Float) {
        val t = specT
        val bx = pos[0]; val by = pos[1] + 0.55f; val bz = pos[2]
        val rise = easeS(t / 1.7f)
        var hx = specOffX; var hz = specOffZ; var hl = hypot(hx, hz)
        if (hl < 0.3f) { hx = sin(deathYaw); hz = -cos(deathYaw); hl = 1f }
        hx /= hl; hz /= hl
        val dist = hl + (max(hl, 3.4f) - hl) * rise + 1f * rise
        val height = max(0.9f, specOffY) + 2.8f * rise
        specClamp(bx, by, bz, bx + hx * dist, by + height, bz + hz * dist)
        var px = specTmp[0]; var py = specTmp[1]; var pz = specTmp[2]
        var lx = bx; var ly = by; var lz = bz
        val tg = specTarget
        if (tg != null) {
            val h = -tg.yaw
            val free = clock - specUserT > 2.8f
            if (t < 2f) yaw = h else if (free) { val d = atan2(sin(h - yaw), cos(h - yaw)); yaw += d * (1f - exp(-dt * 1.8f)) }
            if (t < 2f || free) pitch += (-0.2f - pitch) * (1f - exp(-dt * 2.2f))
            val b = easeS((t - 2f) / 1.5f)
            if (b > 0f) {
                val tx = tg.x; val ty = tg.y + 1.55f; val tz = tg.z
                val cpp = cos(pitch); val fx = sin(yaw) * cpp; val fy = sin(pitch); val fz = -cos(yaw) * cpp
                specClamp(tx, ty, tz, tx - fx * 4.9f + cos(yaw) * 0.55f, ty - fy * 4.9f, tz - fz * 4.9f + sin(yaw) * 0.55f)
                px += (specTmp[0] - px) * b; py += (specTmp[1] - py) * b; pz += (specTmp[2] - pz) * b
                lx += (tx - lx) * b; ly += (ty - ly) * b; lz += (tz - lz) * b
            }
        }
        specCam[0] = px; specCam[1] = py; specCam[2] = pz; specCam[3] = lx; specCam[4] = ly; specCam[5] = lz
    }
    /** your own body stays where you fell (tickFall of the HTML: falls over in 0.85 s, slides 0.8 m, never disappears). */
    private fun drawPlayerCorpse() {
        if (!(chars.ready && chars.has(playerChar))) return
        val p = easeS((clock - deadAt) / 0.85f)
        Matrix.setIdentityM(cbase, 0); Matrix.translateM(cbase, 0, pos[0], pos[1], pos[2])
        Matrix.rotateM(cbase, 0, Math.toDegrees((-deathYaw).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.translateM(cbase, 0, 0f, 0.22f * p, 0.8f * p * fallDir)
        Matrix.rotateM(cbase, 0, Math.toDegrees((1.5f * p * fallDir).toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        cpose.mode = CharPose.RIFLE; cpose.walk = 0f; cpose.speed = 0f; cpose.sprint = false; cpose.gunId = null
        cpose.crouch = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.aimPitch = 0f; cpose.headYaw = 0f; cpose.headPitch = 0f
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, cpose, wear = Wear.id)
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
    }

    private fun hurtPlayer(dmg: Float, by: String) {
        if (!playerAlive || warm) return
        var d = dmg
        // airdrop gear (HTML damage() wrapper): Lv4 helmet takes 22 %, Lv4 vest 40 % before the normal armor rule
        if (helmLvl >= 4 && helmHp > 0f) { val r = minOf(helmHp, d * 0.22f); helmHp -= r; d -= r }
        if (vestLvl >= 4 && shield > 0f) { val x = minOf(shield, d * 0.4f); shield -= x; d -= x }
        val absorb = minOf(shield, d * 0.65f); shield -= absorb; d -= absorb
        sfx?.play("hurt"); vibMs = 45
        if (shield <= 0f && vestLvl > 0 && absorb > 0f) { vestLvl = 0; toast("VEST DESTROYED") }
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING INTERRUPTED") }
        playerHp -= d; damageTime = System.nanoTime()
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = by; addFeed(by + " eliminated YOU"); endMatch(false); beginSpectate(by) }
    }

    private fun zoneHurt(dmg: Float) {
        if (warm) return
        if (!playerAlive) return
        playerHp -= dmg; damageTime = System.nanoTime(); sfx?.play("hurt", 0.7f); vibMs = 30
        if (healLeft > 0f) { healLeft = 0f; healProgress = -1f }
        if (playerHp <= 0f) { playerHp = 0f; playerAlive = false; deadAt = clock; killedBy = "THE ZONE"; addFeed("THE ZONE eliminated YOU"); endMatch(false); beginSpectate("THE ZONE") }
    }

    /** v39 zone: updates circle / text / distance; returns the damage to apply now (non-zero once every 2 s while landed). */
    private fun updateZone(dt: Float): Float {
        val ps = ArrayList<FloatArray>()
        if (playerAlive) ps.add(floatArrayOf(pos[0], pos[2]))
        for (b in botSys.bots) if (b.alive) ps.add(floatArrayOf(b.x, b.z))
        val m = zone.update(matchTime, ps)
        if (m != null && airState == 3) toast(m)
        zoneRadius = zone.radius; zoneCx = zone.cx; zoneCz = zone.cz; zoneText = zone.text
        zoneDist = zoneRadius - hypot(pos[0] - zoneCx, pos[2] - zoneCz)
        val n = zone.next; if (n != null) { nextX = n.x; nextZ = n.z; nextR = n.r } else nextR = 0f
        if (airState != 3 || matchState != 0 || warm) return 0f
        zoneTickT += dt
        if (zoneTickT >= ZP.TICK) { zoneTickT -= ZP.TICK; return zone.dmgNow }
        return 0f
    }

    private fun ambience() {
        if (paused || matchState != 0) { sfx?.ambience(0f, 0f); return }
        val wind = when (airState) { 1 -> 0.11f; 2 -> 0.035f; 0 -> 0.018f; else -> 0f }
        val motor = if (airState == 0) 0.07f else if (flightTime < 52f) {
            val dx = planeX(flightTime) - pos[0]; val dz = planeZ(flightTime) - pos[2]; val dy = AIR_Y - pos[1]
            0.04f / (1f + sqrt(dx * dx + dy * dy + dz * dz) * 0.1f)
        } else 0f
        sfx?.ambience(wind, motor)
    }

    private fun step(dt: Float) {
        if (paused) { takeLook(); jumpReq = false; sfx?.ambience(0f, 0f); return }
        clock += dt
        posX = pos[0]; posZ = pos[2]; yawNow = yaw
        ambience()
        stepCrouch(dt)
        stepBlood(dt)
        stepShotFx(dt)
        if (warm && !warmEnded) { warmLeft -= dt; if (warmLeft <= 0f) { warmLeft = 0f; warmEnded = true } }
        if (matchState == 0 && airState == 3 && !warm) matchTime += dt
        if (airState != 3 || flightTime < 60f) flightTime += dt
        val zdmg = updateZone(dt)
        if (matchState != 0) {
            botSys.update(dt, clock, pos[0], pos[1], pos[2], false, zoneRadius, zoneCx, zoneCz, 0f, { _, _ -> }, { _, _, _, _ -> })
            botsAlive = botSys.bots.count { it.alive }
            if (matchState == 1) winT += dt
            if (spec) specStep(dt) else takeLook()
            return
        }
        stepBotsAir(dt)
        if (airState != 3) { bodyYaw = yaw; faceHold = 0f; jumpAnim.reset() }
        if (airState != 3) {
            stepAirPlayer(dt)
            botSys.update(dt, clock, pos[0], pos[1], pos[2], false, zoneRadius, zoneCx, zoneCz, 0f, { _, _ -> }, { _, _, _, _ -> })
            botsAlive = botSys.bots.count { it.alive }
            return
        }
        val gNow = guns[gi]
        if (aiming && (reloadLeft > 0f || healLeft > 0f || run)) aiming = false
        adsAmount += ((if (aiming) 1f else 0f) - adsAmount) * minOf(1f, dt * 12f)
        val scopedView = !fists && scopeSel >= 2
        scopeOverlay = adsAmount > 0.85f && scopedView
        val sens = (1f + (sensScale(scopeSel) - 1f) * adsAmount) * (1f + (Prefs.adsK(scopeSel) - 1f) * adsAmount) * Prefs.lookK() * (if (firing) Prefs.freeK() else 1f)
        val l = takeLook(); yaw += l[0] * 0.0045f * sens; pitch = (pitch - l[1] * 0.0045f * sens * Prefs.vertK()).coerceIn(-1.45f, 1.45f)
        val swimDepth = map.waterDepth(pos[0], pos[2])
        swimming = swimDepth > 1.25f
        val sprinting = run && !swimming && !crouching && !aiming && healLeft <= 0f
        val sp = (if (healLeft > 0f) 2.8f else if (sprinting) 10f else if (prone) 1.7f else if (crouching) 3.4f else if (aiming) 4.2f else 6.2f) * map.swimSpeed(pos[0], pos[2])   // DLM of the HTML: walk 6.2 / sprint 10 / crouch 3.4 / prone 1.7 / aim 4.2 / heal 2.8   // wading 0.8, swimming 0.55 and no sprint (BERM.swimSpeed)
        val fx = sin(yaw); val fz = -cos(yaw); val rx = cos(yaw); val rz = sin(yaw)
        var mx = (fx * -moveY + rx * moveX); var mz = (fz * -moveY + rz * moveX)
        val len = hypot(mx, mz); if (len > 1f) { mx /= len; mz /= len }
        // camera is free (drag); the body turns to the stick direction. While firing / aiming (and 0.6 s after) it turns to the crosshair.
        if (!Prefs.thirdPerson) { bodyYaw = yaw; faceHold = 0f }
        else {
            if (firing || aiming || adsAmount > 0.1f) faceHold = 0.6f else faceHold = max(0f, faceHold - dt)
            var tgt = bodyYaw; var rate = 13f
            if (faceHold > 0f) { tgt = yaw; rate = 22f } else if (len > 0.12f) tgt = atan2(mx, -mz)
            bodyYaw += atan2(sin(tgt - bodyYaw), cos(tgt - bodyYaw)) * (1f - exp(-dt * rate))
        }
        val nx = pos[0] + mx * sp * dt; val nz = pos[2] + mz * sp * dt
        val stuckInModel = Viaduct.blocks(pos[0], pos[2], 0.42f, pos[1])   // never trap the player inside a viaduct pier / rail
        if (!map.terrainBlocked(nx, pos[2], 0.5f, pos[1], true) && (stuckInModel || !Viaduct.blocks(nx, pos[2], 0.42f, pos[1]))) pos[0] = nx
        if (!map.terrainBlocked(pos[0], nz, 0.5f, pos[1], true) && (stuckInModel || !Viaduct.blocks(pos[0], nz, 0.42f, pos[1]))) pos[2] = nz
        map.resolve(pos, 0.42f)
        run { val dFar = hypot(pos[0], pos[2]); if (dFar > MapData.FAR_LIMIT) { val kf = MapData.FAR_LIMIT / dFar; pos[0] *= kf; pos[2] *= kf; toast("OPEN SEA LIMIT / 3 km from the island, turn back") } }   // the only limit of the sea: 3 km, far outside the map
        var g = max(map.footY(pos[0], pos[2], pos[1]), map.swimFloor(pos[0], pos[2], clock))
        if (diving && !swimming) diving = false
        if (diving) {   // DIVE: stick forward + look down = deeper, look up = back to the surface; with no input buoyancy lifts you slowly. No shooting under water.
            firing = false; aiming = false
            val surfY = map.waterLevelAt(pos[0], pos[2]) - 1.25f; val bedY = map.ground(pos[0], pos[2]) + 0.1f
            val fwd = -moveY
            var ny = pos[1] + (sin(pitch) * fwd * 3.4f + (if (kotlin.math.abs(fwd) < 0.1f) 0.6f else 0f)) * dt
            if (diveSink > 0f) { diveSink -= dt; ny -= 1.6f * dt }
            ny = ny.coerceIn(minOf(bedY, surfY), surfY)
            pos[1] = ny; g = ny; vy = 0f; onGround = true; jumpReq = false
            diveDepth = (surfY - ny).coerceAtLeast(0f)
            if (diveSink <= 0f && ny >= surfY - 0.001f && kotlin.math.abs(fwd) < 0.1f) diving = false
            if (diveDepth > 0.45f) { diveAcc += (1.2f + 3f * (diveDepth - 0.45f)) * dt; while (diveAcc >= 1f) { diveAcc -= 1f; drownHit() } } else diveAcc = 0f
        } else { diveDepth = 0f; diveAcc = 0f }   // deep water: you float with your head above the surface
        if (jumpReq && onGround && crouching) { if (canStandUp()) crouching = false else jumpReq = false }
        if (jumpReq && onGround) { vy = 6.2f; onGround = false; sfx?.play("jump") }; jumpReq = false
        if (!onGround) { vy -= 20f * dt; var ny = pos[1] + vy * dt
            if (vy > 0f) { val cl = map.ceilAt(pos[0], pos[2], pos[1] - 0.01f, ny + Viaduct.BODY_H); if (cl < 1e8f) { ny = minOf(ny, cl - Viaduct.BODY_H - 0.012f); vy = 0f } }   // head bump under a viaduct arch
            pos[1] = ny; if (pos[1] <= g) { if (vy < -5f) sfx?.play("land"); pos[1] = g; vy = 0f; onGround = true } }
        else pos[1] += (g - pos[1]) * minOf(1f, dt * 18f)
        footSpeed = hypot(mx, mz) * sp
        sprintNow = sprinting && onGround && footSpeed > 1.5f
        jumpAnim.update(dt, onGround, vy, footSpeed, !swimming && !prone && playerAlive)
        if (onGround) { walkPhase += Gait.omega(hypot(mx, mz) * sp, crouching, prone) * dt; stepDist += hypot(mx, mz) * sp * dt; if (stepDist >= (if (sprinting) 2.7f else if (crouching) 1.4f else 1.9f)) { stepDist = 0f; if (!swimming) sfx?.play("step", 0.9f) } }
        stepSwim(dt)
        swimAmt += ((if (swimming && playerAlive && airState == 3) 1f else 0f) - swimAmt) * minOf(1f, dt * 7f)
        swimPh = (swimPh + dt * (2.2f + 3.0f * minOf(1f, footSpeed / 3f))) % 6.2831855f
        if (swimAmt > 0.5f) { firing = false; aiming = false }   // gun is slung while swimming: no shooting / aiming

        stepWeapon(dt)
        stepLoot(dt)
        stepNades(dt)
        stepTail(dt, zdmg)
    }

    /** Bots, zone damage, airdrops, win check, bullet marks */
    private fun stepTail(dt: Float, zdmg: Float) {
        botSys.update(dt, clock, pos[0], pos[1], pos[2], true, zoneRadius, zoneCx, zoneCz, zdmg, { dmg, name -> hurtPlayer(dmg, name) }, { b, x, y, z -> botThrow(b, x, y, z) })
        botsAlive = botSys.bots.count { it.alive }
        for (b in botSys.bots) if (!b.alive && !b.reported && b.deadAt >= 0f && b.air == 3) { b.reported = true; addFeed(b.name + " died in the zone"); dropFromBot(b) }
        if (zoneDist < 0f && zdmg > 0f) { zoneHurt(zdmg); toast("OUTSIDE THE SAFE ZONE / -" + zdmg.toInt() + " HP every 2 seconds · move into the blue") }
        stepAirdrops(dt)
        if (matchState == 0 && botsAlive == 0) endMatch(true)
        val it = marks.iterator(); while (it.hasNext()) { val m = it.next(); m.life -= dt; if (m.life <= 0f) it.remove() }
    }

    private fun stepWeapon(dt: Float) {
        val g = guns[gi]
        cooldown -= dt; kick = maxOf(0f, kick - dt * 9f); flash = maxOf(0f, flash - dt)
        val sw = switchDir; switchDir = 0
        if (sw != 0) {
            val list = owned.filter { it >= 0 } + (-1)          // the last entry is FISTS
            val at = (if (fists) list.size - 1 else list.indexOf(gi)).coerceAtLeast(0)
            val nx = list[((at + sw) % list.size + list.size) % list.size]
            if (nx < 0) fists = true else { fists = false; gi = nx }
            reloadLeft = 0f; reloadProgress = -1f; cooldown = 0.35f; aiming = false; punchT = 0f; punchPending = false; publishGun(); sfx?.play("ready")
            return
        }
        if (fists) {
            aiming = false; reloadReq = false; reloadLeft = 0f; reloadProgress = -1f
            if (punchT > 0f) {
                punchT -= dt
                if (punchPending && 1f - punchT / punchDur >= BoxAnim.PEAK[punchKind]) { punchPending = false; punchLand() }   // the fist has arrived: now it hits
            } else punchPending = false
            if (firing && cooldown <= 0f) punch()
            triggerHeld = firing
            return
        }
        if (reloadReq) {
            reloadReq = false
            if (reloadLeft <= 0f && healLeft <= 0f && ammo[gi] < g.mag) {
                if (pool[AmmoInfo.typeOf(g)] <= 0) toast("NO RESERVE AMMO - FIND " + AmmoInfo.NAMES[AmmoInfo.typeOf(g)] + " AMMO") else { reloadLeft = g.reload; sfx?.play("reload") }
            }
        }
        if (reloadLeft > 0f) {
            reloadLeft -= dt
            reloadProgress = 1f - (reloadLeft / g.reload).coerceIn(0f, 1f)
            if (!magDropped && reloadProgress > 0.33f && g.pellets <= 1) { magDropped = true; dropMag() }
            if (reloadLeft <= 0f) {
                val t = AmmoInfo.typeOf(g); val n = minOf(g.mag - ammo[gi], pool[t])
                ammo[gi] += n; pool[t] -= n; reloadProgress = -1f; publishGun()
            }
            return
        }
        reloadProgress = -1f; magDropped = false
        if (firing && healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING INTERRUPTED") }
        val down = firing
        if (down && sprintNow) { // pressing FIRE while sprinting stops the sprint (the HUD drops auto-run too) and the gun fires right away
            run = false; sprintNow = false
        }
        if (down && cooldown <= 0f && !(g.semi && triggerHeld)) {
            if (ammo[gi] > 0) shoot(g) else if (pool[AmmoInfo.typeOf(g)] > 0) reloadReq = true
        }
        triggerHeld = down
    }

    /**
     * BOXING punch: starts the next punch of the combo (jab -> cross -> hook -> uppercut, restarts with a jab after 1.1 s without punching).
     * Every punch has its own damage / cool-down / length (BoxAnim). A punch stops the sprint and cancels healing. The hit itself lands in punchLand().
     */
    private fun punch() {
        if (clock - comboClock > 1.1f) comboKind = 0
        punchKind = comboKind; comboKind = (comboKind + 1) % 4; comboClock = clock
        cooldown = BoxAnim.COOLDOWN[punchKind]; punchDur = BoxAnim.DURATION[punchKind]; punchT = punchDur; punchPending = true
        punchHand = if (BoxAnim.leftArm(punchKind)) -1f else 1f
        healLeft = 0f; run = false; sprintNow = false; sfx?.play("throw")
    }

    /**
     * The fist lands (HTML punch(): reach about 3 m, x1.5 on the head). First the exact crosshair ray (head shots); if it misses, the nearest enemy inside a
     * ~33 degree cone in front of the player and within reach is hit in the body (aim assist - a thumb cannot aim a fist exactly), if no wall is in between.
     */
    private fun punchLand() {
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = -cos(yaw) * cp
        val ox = pos[0]; val oy = pos[1] + 1.7f - eyeDrop; val oz = pos[2]
        val reach = 2.2f   // a real punch: arm + lunge (was 3 m)
        val tw = map.rayWorld(ox, oy, oz, dx, dy, dz, reach)
        var tb = -1f; var best: Bot? = null; var bh = false
        for (d in botSys.bots) {
            if (!d.alive || d.air != 3) continue   // only enemies standing on the ground (not in the plane / sky)
            val t = MapData.rayBox(ox, oy, oz, dx, dy, dz, d.x - 0.3f, d.x + 0.3f, d.y, d.y + BotSystem.BODY_H, d.z - 0.25f, d.z + 0.25f, reach)
            if (t >= 0f && (tb < 0f || t < tb)) { tb = t; best = d; bh = (oy + dy * t) >= d.y + BotSystem.HEAD_Y }
        }
        var hx = ox + dx * tb; var hy = oy + dy * tb; var hz = oz + dz * tb
        if (best != null && tw >= 0f && tb >= tw) best = null   // a wall is in front of him
        if (best == null) {   // aim assist cone
            var bs = -1f
            for (d in botSys.bots) {
                if (!d.alive || d.air != 3) continue
                val cx = d.x; val cy = d.y + 1.0f; val cz = d.z
                val vx = cx - ox; val vy = cy - oy; val vz = cz - oz
                val dist = sqrt(vx * vx + vy * vy + vz * vz)
                if (dist < 0.05f || dist - 0.3f > reach - 0.2f) continue
                val dot = (vx * dx + vy * dy + vz * dz) / dist
                if (dot < 0.84f) continue
                val tww = map.rayWorld(ox, oy, oz, vx / dist, vy / dist, vz / dist, dist)
                if (tww >= 0f && tww < dist - 0.4f) continue
                val sc = dot - dist * 0.05f
                if (sc > bs) { bs = sc; best = d; hx = cx; hy = cy; hz = cz; bh = false }
            }
        }
        val b = best ?: return
        val dmg = BoxAnim.DAMAGE[punchKind] * (if (bh) 1.5f else 1f)
        if (!warm) { damageDealt += min(dmg, max(0f, b.hp)).toInt(); b.hp -= dmg }; b.barTime = 3f
        bloodBurst(hx, hy, hz, bh)
        var kill = false
        if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; kill = true; kills++; b.reported = true; addFeed("YOU eliminated " + b.name + "  [FISTS]"); dropFromBot(b) }
        hitDamage = dmg.toInt(); hitHead = bh; hitTime = System.nanoTime(); hitKill = kill; sfx?.play("hit")
    }

    private fun shoot(g: Gun) {
        ammo[gi]--; cooldown = g.delay; kick = 1f; flash = 0.05f
        sfx?.play("shot" + g.slot)
        val cp = cos(pitch)
        val fwd = floatArrayOf(sin(yaw) * cp, sin(pitch), -cos(yaw) * cp)
        val right = floatArrayOf(cos(yaw), 0f, sin(yaw))
        val up = floatArrayOf(-sin(yaw) * sin(pitch), cp, cos(yaw) * sin(pitch))
        val ox = pos[0]; val oy = pos[1] + 1.7f - eyeDrop; val oz = pos[2]
        if (Prefs.thirdPerson && airState == 3) {   // free camera sits behind / beside the body: the bullet flies from the head to the exact point under the crosshair (like mobile BR)
            var tCam = map.rayWorld(camX, camY, camZ, fwd[0], fwd[1], fwd[2], 400f); if (tCam < 0f) tCam = 400f
            for (d in botSys.bots) {
                if (!d.alive || d.air == 0) continue
                val t = MapData.rayBox(camX, camY, camZ, fwd[0], fwd[1], fwd[2], d.x - 0.3f, d.x + 0.3f, d.y, d.y + BotSystem.BODY_H, d.z - 0.25f, d.z + 0.25f, tCam)
                if (t >= 0f && t < tCam) tCam = t
            }
            val ax = camX + fwd[0] * tCam - ox; val ay = camY + fwd[1] * tCam - oy; val az = camZ + fwd[2] * tCam - oz
            val al = sqrt(ax * ax + ay * ay + az * az)
            if (tCam > 4f && al > 1f) { fwd[0] = ax / al; fwd[1] = ay / al; fwd[2] = az / al }
        }
        shotFx(ox, oy, oz, fwd, right, up, g.pellets > 1)
        var hit = false; var head = false; var kill = false; var total = 0
        for (p in 0 until g.pellets) {
            val sp = g.spread * (1f - 0.45f * adsAmount) * (1f - 0.4f * crouchAmt - 0.2f * proneAmt)
            val sx = (rnd.nextFloat() * 2 - 1) * sp; val sy = (rnd.nextFloat() * 2 - 1) * sp
            var dx = fwd[0] + right[0] * sx + up[0] * sy; var dy = fwd[1] + up[1] * sy; var dz = fwd[2] + right[2] * sx + up[2] * sy
            val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            val tw = map.rayWorld(ox, oy, oz, dx, dy, dz, 700f)
            var tb = -1f; var best: Bot? = null; var bh = false
            for (d in botSys.bots) {
                if (!d.alive || d.air == 0) continue
                val t = MapData.rayBox(ox, oy, oz, dx, dy, dz, d.x - 0.3f, d.x + 0.3f, d.y, d.y + BotSystem.BODY_H, d.z - 0.25f, d.z + 0.25f, 700f)
                if (t >= 0f && (tb < 0f || t < tb)) { tb = t; best = d; bh = (oy + dy * t) >= d.y + BotSystem.HEAD_Y }
            }
            if (best != null && (tw < 0f || tb < tw)) {
                var dmg = (if (bh) (g.head ?: g.damage * 2) else (g.body ?: g.damage)).toFloat()
                if (tb > g.eff) dmg *= 0.6f
                if (!warm) { damageDealt += min(dmg, max(0f, best.hp)).toInt(); best.hp -= dmg }; best.barTime = 3f; total += dmg.toInt(); hit = true; head = head || bh
                bloodBurst(ox + dx * tb, oy + dy * tb, oz + dz * tb, bh)
                if (best.hp <= 0f) { best.alive = false; best.deadAt = clock; kill = true; kills++; best.reported = true; addFeed("YOU eliminated " + best.name + "  [" + g.name + "]"); dropFromBot(best) }
            } else if (tw > 0f) {
                marks.add(Mark(ox + dx * tw, oy + dy * tw, oz + dz * tw, 8f)); if (marks.size > 60) marks.removeAt(0)
            }
        }
        if (hit) { hitDamage = total; hitHead = head; hitKill = kill; hitTime = System.nanoTime(); sfx?.play("hit") }
        val recK = (1f - 0.25f * adsAmount) * (1f - 0.15f * crouchAmt - 0.15f * proneAmt)
        pitch = (pitch + g.recoil * 2.5f * recK * (0.8f + rnd.nextFloat() * 0.4f)).coerceAtMost(1.45f)
        yaw += (rnd.nextFloat() - 0.5f) * g.recoil * 2f
        ammoNow = ammo[gi]
    }

    // ---------------------------------------------------------------- loot
    private fun addLoot(type: Int, sub: Int, p: FloatArray?) { if (p != null) loot.add(Loot(type, sub, p[0], p[1], p[2])) }

    private fun gunOfSlot(slot: Int): Int {
        val list = guns.indices.filter { guns[it].slot == slot }
        return list[rnd.nextInt(list.size)]
    }

    /**
     * DX PATCH of the HTML (`dxStockHouses`): ground loot lies only INSIDE houses or on their porch (mobile BR style), never out in the open.
     * Up to 90 tries per item, at least 1.3 m from every other item; if the map has no house list the old open-ground spot is used.
     */
    private fun houseSpot(): FloatArray? {
        if (map.houseSpots.isEmpty()) return map.randomLand(rnd, 0f, 0f, 10f, 900f)
        for (n in 0 until 90) {
            val sp = map.houseSpotTry(rnd) ?: continue
            var ok = true
            for (l in loot) { val dx = l.x - sp[0]; val dz = l.z - sp[2]; if (dx * dx + dz * dz < 1.69f) { ok = false; break } }
            if (ok) return sp
        }
        return null
    }

    /** Loot of the replacement houses (they stand where the 4 old landmark models were): 3 items per house, now inside / next to ANY house like the HTML. */
    private fun spawnVillageLoot() {
        for (hh in map.extraHouses) {
            for (k in 0 until 3) {
                val sp = houseSpot()
                val r = rnd.nextFloat()
                when {
                    r < .30f -> addLoot(LootType.WEAPON, gunOfSlot(if (rnd.nextFloat() < .5f) 0 else if (rnd.nextFloat() < .7f) 1 else 2), sp)
                    r < .55f -> addLoot(LootType.AMMO, rnd.nextInt(3), sp)
                    r < .65f -> addLoot(LootType.MEDKIT, 0, sp)
                    r < .75f -> addLoot(LootType.ARMOR, 0, sp)
                    r < .90f -> addLoot(LootType.SCOPE, intArrayOf(1, 2, 4, 8)[rnd.nextInt(4)], sp)
                    r < .95f -> addLoot(LootType.GRENADE, 0, sp)
                    else -> addLoot(LootType.SHROOM, 0, sp)
                }
            }
        }
    }

    private fun spawnLoot() {
        spawnVillageLoot()
        // a starter ring close to the spawn point so the first minute is playable
        for (k in 0 until 3) addLoot(LootType.WEAPON, gunOfSlot(k), houseSpot())
        addLoot(LootType.AMMO, 0, houseSpot()); addLoot(LootType.AMMO, 2, houseSpot())
        addLoot(LootType.AMMO, 1, houseSpot()); addLoot(LootType.MEDKIT, 0, houseSpot())
        addLoot(LootType.ARMOR, 0, houseSpot())
        addLoot(LootType.SCOPE, 1, houseSpot()); addLoot(LootType.SCOPE, 2, houseSpot())
        addLoot(LootType.GRENADE, 0, houseSpot())
        addLoot(LootType.SHROOM, 0, houseSpot())
        for (i in 0 until 20) addLoot(LootType.SHROOM, 0, houseSpot())
        for (i in 0 until 15) addLoot(LootType.GRENADE, 0, houseSpot())
        // map-wide (same counts family as spawnOutdoor())
        for (i in 0 until 36) { val r = rnd.nextFloat(); addLoot(LootType.SCOPE, if (r < .34f) 1 else if (r < .64f) 2 else if (r < .86f) 4 else 8, houseSpot()) }
        for (i in 0 until 60) { val r = rnd.nextFloat(); addLoot(LootType.WEAPON, gunOfSlot(if (r < .45f) 0 else if (r < .82f) 1 else 2), houseSpot()) }
        for (i in 0 until 40) addLoot(LootType.AMMO, 0, houseSpot())
        for (i in 0 until 26) addLoot(LootType.AMMO, 1, houseSpot())
        for (i in 0 until 18) addLoot(LootType.AMMO, 2, houseSpot())
        for (i in 0 until 15) addLoot(LootType.ARMOR, 0, houseSpot())
        for (i in 0 until 15) addLoot(LootType.MEDKIT, 0, houseSpot())
        stockEveryHouse()
    }

    /** One random ground item (same odds as the village houses) at sp. */
    private fun addRandomLootAt(sp: FloatArray?) {
        val r = rnd.nextFloat()
        when {
            r < .30f -> addLoot(LootType.WEAPON, gunOfSlot(if (rnd.nextFloat() < .5f) 0 else if (rnd.nextFloat() < .7f) 1 else 2), sp)
            r < .55f -> addLoot(LootType.AMMO, rnd.nextInt(3), sp)
            r < .65f -> addLoot(LootType.MEDKIT, 0, sp)
            r < .75f -> addLoot(LootType.ARMOR, 0, sp)
            r < .90f -> addLoot(LootType.SCOPE, intArrayOf(1, 2, 4, 8)[rnd.nextInt(4)], sp)
            r < .95f -> addLoot(LootType.GRENADE, 0, sp)
            else -> addLoot(LootType.SHROOM, 0, sp)
        }
    }

    /** EVERY house has loot: after the random scatter each house footprint that holds fewer than 3 items is topped up to 3 (inside, or on the porch if its inside is closed). */
    private fun stockEveryHouse() {
        for (i in map.houseSpots.indices) {
            val hh = map.houseSpots[i]
            var have = 0
            for (l in loot) if (kotlin.math.abs(l.x - hh[0]) < hh[2] / 2f + 3f && kotlin.math.abs(l.z - hh[1]) < hh[3] / 2f + 3f) have++
            var tries = 0
            while (have < 3 && tries < 400) {
                tries++
                val sp = map.houseSpotAt(rnd, i) ?: continue
                var ok = true
                for (l in loot) { val dx = l.x - sp[0]; val dz = l.z - sp[2]; if (dx * dx + dz * dz < 1.69f) { ok = false; break } }
                if (!ok) continue
                addRandomLootAt(sp); have++
            }
        }
    }

    /** Like damage() + dxMakeCrate() in the HTML: a dead bot drops its gun, one supply item and sometimes an armor plate / grenade / scope. */
    private fun dropFromBot(b: Bot) {
        // Phase 28: dxMakeCrate() of the HTML - a wooden crate pops out of the ground 0.8 s after the kill (ring + light beam), the items lie INSIDE it
        // (not on the ground any more) and can be taken when you stand within 3.4 m. Same loot as before: gun, one supply item, armor plate (35 %),
        // grenade (30 %), scope (22 %, rollScope odds 34 / 30 / 22 / 14 %).
        val gIdx = guns.indexOfFirst { it.id == b.gun.id }
        val baseY = map.footY(b.x, b.z, b.y + 0.4f)
        val cr = LootCrate(b.x, baseY, b.z, rnd.nextFloat() * 6.2831855f)
        fun put(l: Loot) {
            val a = cr.items.size * 1.9f
            l.x = cr.x + cos(a) * 0.12f; l.y = cr.y; l.z = cr.z + sin(a) * 0.12f
            l.crate = cr; cr.items.add(l); loot.add(l)
        }
        put(Loot(LootType.WEAPON, gIdx, b.x, b.y, b.z))
        val r = rnd.nextFloat()
        put(if (r < .40f) Loot(LootType.AMMO, AmmoInfo.typeOf(b.gun), b.x, b.y, b.z) else if (r < .55f) Loot(LootType.SHROOM, 0, b.x, b.y, b.z) else Loot(LootType.MEDKIT, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .35f) put(Loot(LootType.ARMOR, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .30f) put(Loot(LootType.GRENADE, 0, b.x, b.y, b.z))
        if (rnd.nextFloat() < .22f) { val q = rnd.nextFloat(); put(Loot(LootType.SCOPE, if (q < .34f) 1 else if (q < .64f) 2 else if (q < .86f) 4 else 8, b.x, b.y, b.z)) }
        crates.add(cr)
        while (crates.size > 14) { val old = crates.removeAt(0); loot.removeAll(old.items) }
    }

    /** an item inside a crate can only be taken while the crate is open (you are near and it has popped up) */
    private fun crateOk(l: Loot): Boolean { val c = l.crate; return c == null || c.open }

    /** dxCrates() of the HTML: pop-in after 0.8 s (grows with a little overshoot), items usable above grow 0.9 within 3.4 m, an empty box shrinks away. */
    private fun stepCrates(dt: Float) {
        var i = crates.size - 1
        while (i >= 0) {
            val c = crates[i]
            c.t += dt
            val u = ((c.t - 0.8f) / 0.4f).coerceIn(0f, 1f)
            c.grow = easeS(u)
            val sc = if (c.t < 0.8f) 0.001f else max(0.001f, c.grow * (1f + 0.12f * sin(3.1415927f * u)))
            var left = 0
            for (l in c.items) if (l.active) left++
            val near = playerAlive && airState == 3 && c.grow > 0.9f && hypot(pos[0] - c.x, pos[2] - c.z) < 3.4f && kotlin.math.abs(pos[1] - c.y) < 2.5f
            c.open = near && left > 0
            c.fx = c.grow > 0.5f && left > 0
            if (left > 0) {
                c.empty = 0f; c.scale = sc
                if (near && !c.hinted) { c.hinted = true; toast("LOOT BOX - TAP AN ITEM IN THE LIST TO TAKE IT") }
            } else {
                c.empty += dt
                c.scale = max(0.001f, (1f - easeS((c.empty - 1.2f) / 0.5f)) * sc)
                if (c.empty > 1.8f) { loot.removeAll(c.items); crates.removeAt(i) }
            }
            i--
        }
    }

    private fun blockedReason(l: Loot): String {
        return when (l.type) {
            LootType.AMMO -> if (pool[l.sub] >= AmmoInfo.CAP[l.sub]) AmmoInfo.NAMES[l.sub] + " AMMO FULL (" + AmmoInfo.CAP[l.sub] + ")" else ""
            LootType.ARMOR -> if (shield >= 100f) "ARMOR ALREADY FULL" else ""
            LootType.VEST -> if (vestLvl > l.sub) "YOU WEAR A BETTER VEST (Lv." + vestLvl + ")" else if (vestLvl == l.sub && shield >= 100f) "LEVEL " + l.sub + " VEST ALREADY EQUIPPED" else ""
            LootType.MEDKIT -> if (medkits >= 5) "MEDKITS FULL (5)" else ""
            LootType.GRENADE -> if (grenadeCount >= 6) "GRENADES FULL (6)" else ""
            LootType.SCOPE -> if (scopesOwned.contains(l.sub)) "SCOPE " + l.sub + "X ALREADY ATTACHED" else ""
            LootType.VEST4 -> if (vestLvl >= 4 && shield >= 100f) "LEVEL 4 VEST ALREADY EQUIPPED" else ""
            LootType.HELM4 -> if (helmLvl >= 4 && helmHp >= 100f) "LEVEL 4 HELMET ALREADY EQUIPPED" else ""
            LootType.SHROOM -> if (mushrooms >= 5) "MUSHROOMS FULL (5)" else ""
            else -> ""
        }
    }

    private fun collect(l: Loot) {
        val why = blockedReason(l); if (why.isNotEmpty()) { toast(why); return }
        when (l.type) {
            LootType.WEAPON -> {
                val g = guns[l.sub]
                val old = owned[g.slot]
                if (old == l.sub) { // already carrying it: take its ammo instead
                    toast("ALREADY CARRYING " + g.name); return
                }
                if (old >= 0) loot.add(Loot(LootType.WEAPON, old, pos[0] + 1.2f, pos[1], pos[2] + 0.4f).also { it.ammo = ammo[old] }) // drop replaced gun (keeps its magazine)
                owned[g.slot] = l.sub; ammo[l.sub] = if (l.ammo in 0..g.mag) l.ammo else g.mag
                gi = l.sub; fists = false; aiming = false; reloadLeft = 0f; reloadProgress = -1f; cooldown = 0.35f; publishGun()
                toast("EQUIPPED " + g.name)
            }
            LootType.AMMO -> { val n = minOf(AmmoInfo.BOX[l.sub], AmmoInfo.CAP[l.sub] - pool[l.sub]); pool[l.sub] += n; publishGun(); toast("+" + n + " " + AmmoInfo.NAMES[l.sub] + " AMMO") }
            LootType.VEST -> { vestLvl = l.sub; shield = 100f; toast("EQUIPPED " + AmmoInfo.VEST_NAMES[l.sub] + " (ARMOR FULL)") }
            LootType.ARMOR -> { shield = minOf(100f, shield + 40f); toast("+40 ARMOR") }
            LootType.GRENADE -> { grenadeCount++; toast("+1 GRENADE") }
            LootType.SCOPE -> { scopesOwned.add(l.sub); if (l.sub > scopeSel) { scopeSel = l.sub; scopeLabel = l.sub.toString() + "X" }; toast("SCOPE / " + l.sub + "X ATTACHED - TAP THE SCOPE TAG TO SWITCH") }
            LootType.VEST4 -> { vestLvl = 4; shield = 100f; toast("AIRDROP / LEVEL 4 VEST equipped (max armor)") }
            LootType.HELM4 -> { helmLvl = 4; helmHp = 100f; toast("AIRDROP / LEVEL 4 HELMET equipped") }
            LootType.SHROOM -> { mushrooms++; toast("SUPPLIES / +1 mushroom · tap EAT (+60 EP)") }
            else -> { medkits++; toast("+1 MEDKIT") }
        }
        l.active = false; sfx?.play("pickup")
    }

    private fun lootPrio(t: Int): Int = when (t) {
        LootType.WEAPON -> 0; LootType.VEST4, LootType.VEST -> 1; LootType.HELM4 -> 2; LootType.SCOPE -> 3; LootType.AMMO -> 4
        LootType.MEDKIT -> 5; LootType.GRENADE -> 6; LootType.SHROOM -> 7; else -> 8
    }

    /** takes one row of the loot list: supplies (ammo, medkit, grenade, mushroom, armor plate) are taken one after the other until the bag is full, everything else one piece at a time */
    private fun collectGroup(g: List<Loot>) {
        if (g.isEmpty()) return
        val t = g[0].type
        val stack = t == LootType.AMMO || t == LootType.MEDKIT || t == LootType.GRENADE || t == LootType.SHROOM || t == LootType.ARMOR
        for (l in g) {
            if (!l.active) continue
            collect(l)
            if (!stack || l.active) break   // not a stack, or the bag is full for this kind (collect() showed the reason)
        }
    }

    private fun stepLoot(dt: Float) {
        stepCrates(dt)
        // auto pick-up (settings: medkits / mushrooms / ammo / armor / grenades; radius 1.5 m, 2.6 m with FAST)
        val pr = Prefs.pickRadius(); val pr2 = pr * pr
        for (l in loot) {
            if (!l.active || !crateOk(l)) continue
            val on = when (l.type) {
                LootType.MEDKIT -> Prefs.pickMedkit; LootType.SHROOM -> Prefs.pickMushroom; LootType.AMMO -> Prefs.pickAmmo
                LootType.ARMOR, LootType.VEST -> Prefs.pickArmor; LootType.GRENADE -> Prefs.pickGrenade; else -> false
            }
            if (!on) continue
            val dx = l.x - pos[0]; val dz = l.z - pos[2]
            if (dx * dx + dz * dz <= pr2 && kotlin.math.abs(l.y - pos[1]) < 2.5f && blockedReason(l).isEmpty()) collect(l)
        }
        // eat a mushroom: +60 EP (max 250); EP converts to health, 1 EP -> 1 HP every second (epRegen in the HTML)
        if (eatReq) {
            eatReq = false
            if (mushrooms < 1) toast("NO MUSHROOMS - FIND RED MUSHROOMS") else if (ep >= EP_MAX) toast("EP IS ALREADY FULL")
            else { mushrooms--; ep = minOf(EP_MAX, ep + 60f); sfx?.play("heal"); toast("MUSHROOM EATEN / +60 EP") }
        }
        epTimer += dt
        while (epTimer >= 1f) { epTimer -= 1f; if (playerHp < 200f && ep > 0f) { ep = maxOf(0f, ep - 1f); playerHp = minOf(200f, playerHp + 1f) } }
        // Phase 55 LOOT LIST (mobile BR style): everything within 3 m, identical items stacked into one row, tap a row to take it
        nearTmp.clear()
        for (l in loot) {
            if (!l.active || !crateOk(l)) continue
            val dx = l.x - pos[0]; val dz = l.z - pos[2]
            if (dx * dx + dz * dz <= 9f && kotlin.math.abs(l.y - pos[1]) < 2.5f) nearTmp.add(l)
        }
        if (nearTmp.isEmpty()) {
            if (lootRows.isNotEmpty()) { lootRows = emptyList(); lootGroups = emptyList() }
            nearLoot = null; promptLabel = ""
        } else {
            nearTmp.sortBy { (it.x - pos[0]) * (it.x - pos[0]) + (it.z - pos[2]) * (it.z - pos[2]) }   // nearest first inside a stack
            val groups = ArrayList<ArrayList<Loot>>()
            for (l in nearTmp) {
                var g: ArrayList<Loot>? = null
                for (e in groups) if (e[0].type == l.type && e[0].sub == l.sub) { g = e; break }
                if (g == null) { g = ArrayList(); groups.add(g) }
                g.add(l)
            }
            groups.sortWith(compareBy<ArrayList<Loot>>({ lootPrio(it[0].type) }, { it[0].sub }))   // fixed order (no jumping rows while you walk)
            val rows = ArrayList<LootRow>(groups.size)
            for (g in groups) { val f = g[0]; rows.add(LootRow(f.type * 100 + f.sub, f.type, f.sub, lootLabel(f), g.size, blockedReason(f).isNotEmpty() || (f.type == LootType.WEAPON && owned[guns[f.sub].slot] == f.sub))) }
            lootGroups = groups; lootRows = rows
            nearLoot = groups[0][0]; promptLabel = lootLabel(groups[0][0])
        }
        val pk = pickKey
        if (pk >= 0) {
            pickKey = -1
            val rs = lootRows
            for (i in rs.indices) if (rs[i].key == pk && i < lootGroups.size) { collectGroup(lootGroups[i]); break }
        }
        if (pickupReq) { pickupReq = false; lootGroups.firstOrNull()?.let { collectGroup(it) } }
        // medkit heal: 2.2 s, restores 100 HP
        if (healReq) {
            healReq = false
            if (healLeft <= 0f) {
                if (playerHp >= 200f) toast("HEALTH ALREADY FULL") else if (medkits < 1) toast("NO MEDKITS - FIND WHITE SUPPLY PACKS") else { healLeft = 2.2f; reloadLeft = 0f; reloadProgress = -1f }
            }
        }
        if (cancelHealReq) { cancelHealReq = false; if (healLeft > 0f) { healLeft = 0f; healProgress = -1f; toast("HEALING CANCELLED") } }
        if (healLeft > 0f) {
            healLeft -= dt; healProgress = 1f - (healLeft / 2.2f).coerceIn(0f, 1f)
            if (healLeft <= 0f) { medkits--; playerHp = minOf(200f, playerHp + 100f); healProgress = -1f; sfx?.play("heal"); toast("HEALED +100") }
        }
    }

    // ---------------------------------------------------------------- airdrops (adPickSpot / adMake / adTick of the HTML)
    private fun adPickSpot(): FloatArray? {
        val r0 = max(150f, min(zoneRadius * 0.8f, 900f))
        for (i in 0 until 600) {
            val a = rnd.nextFloat() * 6.2832f; val r = sqrt(rnd.nextFloat()) * r0
            val x = sin(a) * r; val z = cos(a) * r; val g = map.ground(x, z)
            if (g < 1.5f) continue
            if (map.terrainBlocked(x, z, 3.2f) || map.boxBlocked(x, z, 3.2f, g + 0.05f)) continue
            if (hypot(x - pos[0], z - pos[2]) < 80f) continue
            return floatArrayOf(x, z, g)
        }
        return null
    }

    private fun landDrop(d: Drop) {
        val spec = arrayOf(
            intArrayOf(LootType.VEST4, 0), intArrayOf(LootType.HELM4, 0), intArrayOf(LootType.WEAPON, gunOfSlot(2)), intArrayOf(LootType.WEAPON, gunOfSlot(1)),
            intArrayOf(LootType.AMMO, 0), intArrayOf(LootType.AMMO, 0), intArrayOf(LootType.MEDKIT, 0), intArrayOf(LootType.GRENADE, 0))
        for ((i, sp) in spec.withIndex()) {
            val a = i / 8f * 6.2832f
            val l = Loot(sp[0], sp[1], d.x + sin(a) * 2.2f, d.g + 0.05f, d.z + cos(a) * 2.2f)
            loot.add(l); d.items.add(l)
        }
        toast("AIRDROP LANDED / Lv4 Vest + Lv4 Helmet inside. Follow the gold beam")
        fxs.add(Fx(d.x, d.g + 0.3f, d.z, 0.25f))
    }

    private fun stepAirdrops(dt: Float) {
        if (adN < AD_TIMES.size && matchTime >= adNext) {
            val sp = adPickSpot()
            if (sp != null) {
                adN++; adNext = if (adN < AD_TIMES.size) AD_TIMES[adN] else 1e9f
                drops.add(Drop(sp[0], sp[1], sp[2], if (rnd.nextBoolean()) 1 else -1))
                toast("AIRDROP INCOMING / watch the sky, gold beam shows the crate"); sfx?.play("alert"); sfx?.play("chute")
            } else adNext = matchTime + 2f
        }
        for (d in drops) {
            d.t += dt
            when (d.state) {
                0 -> if (d.t >= 260f / 110f) { d.state = 1; d.ty = 0f }
                1 -> {
                    d.ty += dt
                    d.h = max(0f, 210f - (if (d.ty < 3f) d.ty * d.ty * 3f else 27f + (d.ty - 3f) * 9.5f))
                    if (d.h <= 0.01f) { d.state = 2; d.h = 0f; d.landT = 0f; landDrop(d) }
                }
                else -> { d.landT += dt; d.life += dt; if (d.items.none { it.active } || d.life > 150f) d.beam = max(0f, d.beam - dt * 0.2f) }
            }
        }
        var best: Drop? = null; var bd = 1e9f
        val marks = ArrayList<Float>()
        for (d in drops) {
            if (d.state == 2 && d.items.none { it.active }) continue
            marks.add(d.x); marks.add(d.z)
            val dd = hypot(d.x - pos[0], d.z - pos[2]); if (dd < bd) { bd = dd; best = d }
        }
        dropMarks = marks.toFloatArray()
        airdropText = if (best != null) "AIRDROP " + (if (best.state == 2) "" else "(INCOMING) ") + "· " + bd.toInt() + " m" else ""
    }

    // ---------------------------------------------------------------- grenades
    private class Nade(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val bot: Bot?) { var fuse = 2.6f; var resting = false }
    private class Fx(val x: Float, val y: Float, val z: Float, var life: Float)
    private val nades = ArrayList<Nade>()
    private val fxs = ArrayList<Fx>()

    private fun playerThrow() {
        if (cooldown > 0f) return
        if (grenadeCount <= 0) { toast("NO GRENADES - FIND GRENADE SUPPLIES"); return }
        grenadeCount--; reloadLeft = 0f; reloadProgress = -1f; healLeft = 0f; healProgress = -1f; aiming = false; cooldown = 0.85f
        val p = pitch.coerceIn(-0.8f, 0.8f); val cp = cos(p)
        var dx = sin(yaw) * cp; var dy = sin(p) + 0.3f; var dz = -cos(yaw) * cp
        val l = sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l
        nades.add(Nade(pos[0], pos[1] + 1.52f, pos[2], dx * 15f, dy * 15f + 3f, dz * 15f, null)); sfx?.play("throw")
    }

    private fun botThrow(b: Bot, tx: Float, ty: Float, tz: Float) {
        val dx = tx - b.x; val dz = tz - b.z; val r = hypot(dx, dz).coerceAtLeast(3f)
        val v = sqrt(14f * r) * (0.94f + rnd.nextFloat() * 0.12f); val c = 0.7071f
        nades.add(Nade(b.x, b.y + 1.5f, b.z, dx / r * v * c, v * c, dz / r * v * c, b))
    }

    private fun stepNades(dt: Float) {
        if (nadeReq) { nadeReq = false; faceHold = 0.6f; bodyYaw = yaw; playerThrow() }
        val it = nades.iterator()
        while (it.hasNext()) {
            val g = it.next(); g.fuse -= dt
            if (g.fuse <= 0f) { it.remove(); detonate(g); continue }
            if (!g.resting) nadePhysics(g, dt)
        }
        val fi = fxs.iterator(); while (fi.hasNext()) { val f = fi.next(); f.life -= dt; if (f.life <= 0f) fi.remove() }
    }

    /** Same constants as updateGrenades(): gravity 14, bounce 0.42, floor friction 0.67, rests below 1 m/s, radius 0.18. */
    private fun nadePhysics(g: Nade, dt: Float) {
        g.vy -= 14f * dt
        val r = 0.18f
        val spd = sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz)
        val n = Math.ceil((spd * dt / 0.12f).toDouble()).toInt().coerceIn(1, 10); val h = dt / n
        for (s in 0 until n) {
            g.x += g.vx * h; g.y += g.vy * h; g.z += g.vz * h
            var nx = 0f; var ny = 0f; var nz = 0f; var hit = false
            val ncl = map.boxesAt(g.x, g.z)
            if (ncl != null) for (qi in 0 until ncl.size) {
                val b = ncl[qi]
                if (g.x > b.minX - r && g.x < b.maxX + r && g.y > b.minY - r && g.y < b.maxY + r && g.z > b.minZ - r && g.z < b.maxZ + r) {
                    val dxl = g.x - (b.minX - r); val dxr = (b.maxX + r) - g.x; val dyb = g.y - (b.minY - r); val dyt = (b.maxY + r) - g.y
                    val dzl = g.z - (b.minZ - r); val dzr = (b.maxZ + r) - g.z
                    val m = minOf(minOf(minOf(dxl, dxr), minOf(dyb, dyt)), minOf(dzl, dzr))
                    when (m) {
                        dxl -> { g.x = b.minX - r; nx = -1f }
                        dxr -> { g.x = b.maxX + r; nx = 1f }
                        dyb -> { g.y = b.minY - r; ny = -1f }
                        dyt -> { g.y = b.maxY + r; ny = 1f }
                        dzl -> { g.z = b.minZ - r; nz = -1f }
                        else -> { g.z = b.maxZ + r; nz = 1f }
                    }
                    hit = true; break
                }
            }
            val gy = map.footY(g.x, g.z, g.y) + r   // grenades also bounce on the viaduct deck / model floors
            if (!hit && g.y < gy) {
                g.y = gy
                if (gy - r > map.ground(g.x, g.z) + 0.5f) { nx = 0f; ny = 1f; nz = 0f; hit = true }   // flat deck / floor: normal straight up
                else {
                val gx = map.ground(g.x + 1f, g.z) - map.ground(g.x - 1f, g.z); val gz = map.ground(g.x, g.z + 1f) - map.ground(g.x, g.z - 1f)
                val l = sqrt(gx * gx + 4f + gz * gz); nx = -gx / l; ny = 2f / l; nz = -gz / l; hit = true }
            }
            if (hit) {
                val vn = g.vx * nx + g.vy * ny + g.vz * nz
                if (vn < 0f) { g.vx -= 1.42f * vn * nx; g.vy -= 1.42f * vn * ny; g.vz -= 1.42f * vn * nz }
                if (vn < -3f) sfx?.playAt("bounce", g.x, g.z, pos[0], pos[2], yaw)
                if (ny > 0.5f) {
                    g.vx *= 0.67f; g.vz *= 0.67f
                    if (sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz) < 1f) { g.vx = 0f; g.vy = 0f; g.vz = 0f; g.resting = true; return }
                }
            }
        }
    }

    /** 125 * (1 - d/9)^1.15 within 9 m, needs a clear line from the blast (as in detonate()). */
    private fun nadeDamage(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        val dx = tx - cx; val dy = ty - cy; val dz = tz - cz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= 9f) return 0f
        if (d > 0.05f) { val t = map.rayWorld(cx, cy, cz, dx / d, dy / d, dz / d, d); if (t in 0f..(d - 0.3f)) return 0f }
        return 125f * Math.pow((1f - d / 9f).toDouble(), 1.15).toFloat()
    }

    private fun detonate(g: Nade) {
        fxs.add(Fx(g.x, g.y + 0.2f, g.z, 0.5f)); sfx?.playAt("blast", g.x, g.z, pos[0], pos[2], yaw)
        if (playerAlive && matchState == 0) {
            val dmg = nadeDamage(g.x, g.y + 0.2f, g.z, pos[0], pos[1] + 1.05f, pos[2])
            if (dmg > 0f) hurtPlayer(dmg, if (g.bot == null) "YOUR GRENADE" else g.bot.name)
        }
        var dealt = 0; var killed = false
        for (b in botSys.bots) {
            if (!b.alive) continue
            val dmg = nadeDamage(g.x, g.y + 0.2f, g.z, b.x, b.y + 1.0f, b.z)
            if (dmg <= 0f) continue
            if (g.bot == null) { dealt += dmg.toInt(); if (!warm) damageDealt += min(dmg, max(0f, b.hp)).toInt() }; if (!warm) b.hp -= dmg; b.barTime = 3f
            if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; b.reported = true; if (g.bot == null) { kills++; killed = true }; addFeed((g.bot?.name ?: "YOU") + " eliminated " + b.name + "  [GRENADE]"); dropFromBot(b) }
        }
        if (dealt > 0) { hitDamage = dealt; hitHead = false; hitKill = killed; hitTime = System.nanoTime() }
    }

    // ---------------------------------------------------------------- drawing
    // ================================================================ Phase 28: skateboard, air body, loot crate, contact shadows, dropped magazine
    private val bdm = FloatArray(16)

    private fun degF(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    /** one box relative to a parent matrix: centre px,py,pz, rotation about Z in degrees, FULL sizes. The cube VAO must already be bound. */
    private fun boxIn(par: FloatArray, px: Float, py: Float, pz: Float, rotZ: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, mode: Int) {
        System.arraycopy(par, 0, pm, 0, 16)
        Matrix.translateM(pm, 0, px, py, pz)
        if (rotZ != 0f) Matrix.rotateM(pm, 0, rotZ, 0f, 0f, 1f)
        Matrix.scaleM(pm, 0, sx, sy, sz)
        setModel(pm)
        GL.glUniform1i(uMode, mode); GL.glUniform3f(uColor, r, g, b)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    /**
     * The skateboard under a skating fighter (dlMakeBoard of the HTML), drawn in the model-root frame so it scales / turns with the body:
     * deck + grip tape + orange rails + two kicktails + trucks + 4 wheels + cyan glow underneath. It spins in while boardK grows.
     */
    private fun drawSkateboard(rootM: FloatArray, av: AirVis) {
        if (av.boardK <= 0.03f) return
        val k = max(0.01f, av.boardK)
        System.arraycopy(rootM, 0, bdm, 0, 16)
        Matrix.translateM(bdm, 0, 0f, -0.06f + av.drop, 0f)
        Matrix.rotateM(bdm, 0, degF((1f - av.boardK) * 4f), 0f, 1f, 0f)
        Matrix.scaleM(bdm, 0, k, k, k)
        GL.glBindVertexArray(cube!!.vao)
        boxIn(bdm, 0f, 0f, 0f, 0f, 1.5f, 0.05f, 0.4f, 0.106f, 0.149f, 0.188f, 2)                 // deck #1b2630
        boxIn(bdm, 0f, 0.03f, 0f, 0f, 1.44f, 0.012f, 0.36f, 0.051f, 0.071f, 0.09f, 2)            // grip tape #0d1217
        boxIn(bdm, 0f, 0.004f, 0.2f, 0f, 1.5f, 0.03f, 0.025f, 1f, 0.353f, 0.165f, 2)             // orange rails #ff5a2a
        boxIn(bdm, 0f, 0.004f, -0.2f, 0f, 1.5f, 0.03f, 0.025f, 1f, 0.353f, 0.165f, 2)
        for (s in 0..1) {
            val sg = if (s == 0) -1f else 1f
            boxIn(bdm, sg * 0.8f, 0.045f, 0f, degF(sg * 0.42f), 0.24f, 0.05f, 0.4f, 0.106f, 0.149f, 0.188f, 2)   // kicktail
            boxIn(bdm, sg * 0.5f, -0.06f, 0f, 0f, 0.09f, 0.05f, 0.3f, 0.604f, 0.647f, 0.678f, 2)                // truck #9aa5ad
        }
        boxIn(bdm, 0f, -0.035f, 0f, 0f, 1.25f, 0.012f, 0.26f, 0.22f, 0.91f, 1f, 3)               // glow underside #38e8ff (unlit)
        val wm = wheelMesh
        if (wm != null) {
            GL.glBindVertexArray(wm.vao)
            for (s in 0..1) for (zz in 0..1) {
                val sg = if (s == 0) -1f else 1f; val z = if (zz == 0) -0.17f else 0.17f
                System.arraycopy(bdm, 0, pm, 0, 16)
                Matrix.translateM(pm, 0, sg * 0.5f, -0.1f, z); Matrix.rotateM(pm, 0, 90f, 1f, 0f, 0f); Matrix.scaleM(pm, 0, 0.09f, 0.07f, 0.09f)
                setModel(pm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.957f, 0.945f, 0.91f)   // wheels #f4f1e8
                GL.glDrawElements(GL.GL_TRIANGLES, wm.count, GL.GL_UNSIGNED_INT, 0)
            }
            GL.glBindVertexArray(cube!!.vao)
        }
    }

    /**
     * The 3D parachute model: canopy at y 4.5..6.4, the two riser ends (where the hands / the crate lid attach) at y = 1.1, a centre line down to y = 0.
     * So the caller puts the origin at (attach height - 1.1 * sc).
     * open = 0..1 inflates the canopy; heading = degrees around Y; sc = overall size; sway = small pendulum swing in degrees.
     */
    private fun drawChute(x: Float, y: Float, z: Float, open: Float, heading: Float, sc: Float, swayDeg: Float) {
        val m = chuteMesh ?: return
        val k = (0.3f + 0.7f * open) * sc
        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, x, y, z)
        Matrix.rotateM(pm, 0, heading, 0f, 1f, 0f)
        if (swayDeg != 0f) { Matrix.rotateM(pm, 0, swayDeg, 0f, 0f, 1f); Matrix.rotateM(pm, 0, swayDeg * 0.5f, 1f, 0f, 0f) }
        Matrix.scaleM(pm, 0, k, k, k)
        setModel(pm)
        GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, chuteTex)
        GL.glBindVertexArray(m.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.count, GL.GL_UNSIGNED_INT, 0)
        GL.glBindVertexArray(cube!!.vao)
    }

    /** Your own character in freefall / under the canopy, seen from behind like the HTML: skate stance + board, head-first dive, parachute hang. */
    private fun drawPlayerAir() {
        if (!(chars.ready && chars.has(playerChar))) return
        val av = playerAv
        Matrix.setIdentityM(cbase, 0)
        Matrix.translateM(cbase, 0, pos[0] + av.offX, pos[1] + av.offY, pos[2] + av.offZ)
        Matrix.rotateM(cbase, 0, degF(av.rotY), 0f, 1f, 0f)
        Matrix.rotateM(cbase, 0, degF(av.rotX), 1f, 0f, 0f)
        Matrix.rotateM(cbase, 0, degF(av.rotZ), 0f, 0f, 1f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, av.cp, wear = Wear.id)
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
        drawSkateboard(cbase, av)
        if (airState == 2) drawChute(pos[0], pos[1] + 1.0f, pos[2], (chuteT / 0.9f).coerceIn(0f, 1f), degF(-bodyYaw), 1f, 2.5f * sin(clock * 1.7f))
    }

    /** The wooden loot box (dxMakeCrate): body, lid, orange straps, dark green side bands; ring on the ground + light beam while it still holds items. */
    private fun drawCrates(ex: Float, ez: Float) {
        if (crates.isEmpty()) return
        GL.glBindVertexArray(cube!!.vao)
        for (c in crates) {
            if (c.scale < 0.01f) continue
            val dx = c.x - ex; val dz = c.z - ez
            if (dx * dx + dz * dz > 130f * 130f) continue
            Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, c.x, c.y, c.z)
            Matrix.rotateM(base, 0, degF(c.yaw), 0f, 1f, 0f); Matrix.scaleM(base, 0, c.scale, c.scale, c.scale)
            part(0f, 0.28f, 0f, 0f, 0f, 1.0f, 0.56f, 0.68f, 0xFF6E5A38.toInt())       // body (wood)
            part(0f, 0.6f, 0f, 0f, 0f, 1.06f, 0.1f, 0.74f, 0xFF8D7549.toInt())        // lid
            part(0f, 0.3f, 0.35f, 0f, 0f, 1.02f, 0.09f, 0.02f, 0xFFFFAC78.toInt())    // orange straps
            part(0f, 0.3f, -0.35f, 0f, 0f, 1.02f, 0.09f, 0.02f, 0xFFFFAC78.toInt())
            part(-0.3f, 0.3f, 0f, 0f, 0f, 0.08f, 0.58f, 0.7f, 0xFF43594D.toInt())     // dark green side bands
            part(0.3f, 0.3f, 0f, 0f, 0f, 0.08f, 0.58f, 0.7f, 0xFF43594D.toInt())
        }
        var any = false
        for (c in crates) if (c.fx) { any = true; break }
        if (!any) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")
        val rm = ringMesh; val wl = wall
        for (c in crates) {
            if (!c.fx) continue
            val dx = c.x - ex; val dz = c.z - ez
            if (dx * dx + dz * dz > 130f * 130f) continue
            if (rm != null) {   // RingGeometry(.85, .97) flat on the ground, opacity .85
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, c.x, c.y + 0.05f, c.z); setModel(mm)
                GL.glBindVertexArray(rm.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.675f, 0.471f); GL.glUniform1f(uA, 0.85f)
                GL.glDrawElements(GL.GL_TRIANGLES, rm.count, GL.GL_UNSIGNED_INT, 0)
            }
            if (wl != null) {   // CylinderGeometry(.05, .05, 3.2) light beam, opacity .35
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, c.x, c.y + 0.3f, c.z); Matrix.scaleM(mm, 0, 0.05f, 3.2f, 0.05f); setModel(mm)
                GL.glBindVertexArray(wl.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.675f, 0.471f); GL.glUniform1f(uA, 0.35f)
                GL.glDrawElements(GL.GL_TRIANGLES, wl.count, GL.GL_UNSIGNED_INT, 0)
            }
        }
        GL.glUniform1f(uA, 1f); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(cube!!.vao)
    }

    /** Soft contact shadow under every fighter on foot (contact() of the reference pack): 55 m range, fades with distance, a little smaller while crouching. */
    private fun drawContactShadows() {
        val bm = blobMesh ?: return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glEnable(GL.GL_POLYGON_OFFSET_FILL); GL.glPolygonOffset(-2f, -2f)
        GL.glBindVertexArray(bm.vao); GL.glUniform1i(uMode, 5); GL.glUniform3f(uColor, 0f, 0f, 0f)
        val uA = GL.glGetUniformLocation(prog, "uAlpha")
        if (playerAlive && airState == 3 && (Prefs.thirdPerson || winCam)) blobAt(bm, uA, pos[0], pos[1], pos[2], crouchAmt)
        for (b in botSys.bots) {
            if (!b.alive || b.air != 3) continue
            blobAt(bm, uA, b.x, b.y, b.z, 0f)
        }
        GL.glUniform1f(uA, 1f)
        GL.glDisable(GL.GL_POLYGON_OFFSET_FILL); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glBindVertexArray(cube!!.vao)
    }

    private fun blobAt(bm: GlMesh, uA: Int, x: Float, y: Float, z: Float, cr: Float) {
        val dx = x - camX; val dy = y - camY; val dz = z - camZ
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= 55f) return
        val fade = (1.4f - d / 55f).coerceIn(0f, 1f)
        val s = 0.605f * (1f - 0.2f * cr)
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y + 0.05f, z); Matrix.scaleM(mm, 0, s, 1f, s)
        setModel(mm)
        GL.glUniform1f(uA, 0.55f * fade)
        GL.glDrawElements(GL.GL_TRIANGLES, bm.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** Reload: the empty magazine drops out of the gun (dropMag of the reference pack): falls with 0.8 m/s, tumbles, bounces, rests, shrinks away after 5 s. */
    private fun dropMag() {
        val cp = cos(pitch); val sp = sin(pitch)
        val fx = sin(yaw) * cp; val fy = sp; val fz = -cos(yaw) * cp
        val rx = cos(yaw); val rz = sin(yaw)
        val ux = -sin(yaw) * sp; val uy = cp; val uz = cos(yaw) * sp
        val ox = pos[0]; val oy = pos[1] + 1.7f - eyeDrop; val oz = pos[2]
        val px = ox + rx * 0.17f + fx * 0.4f - ux * 0.3f
        val py = oy + fy * 0.4f - uy * 0.3f
        val pz = oz + rz * 0.17f + fz * 0.4f - uz * 0.3f
        val side = (rnd.nextFloat() - 0.5f) * 0.6f
        mags.add(MagDrop(px, py, pz, -ux * 0.8f + rx * side, -uy * 0.8f, -uz * 0.8f + rz * side,
            (rnd.nextFloat() - 0.5f) * 10f, (rnd.nextFloat() - 0.5f) * 6f, (rnd.nextFloat() - 0.5f) * 10f,
            rnd.nextFloat() * 6f, rnd.nextFloat() * 6f, rnd.nextFloat() * 6f))
        while (mags.size > 8) mags.removeAt(0)
    }

    private fun stepMags(dt: Float) {
        var i = mags.size - 1
        while (i >= 0) {
            val m = mags[i]; m.life -= dt
            if (m.life <= 0f) { mags.removeAt(i); i--; continue }
            if (!m.rest) {
                m.vy -= 9.8f * dt; m.x += m.vx * dt; m.y += m.vy * dt; m.z += m.vz * dt
                m.rx += m.wx * dt; m.ry += m.wy * dt; m.rz += m.wz * dt
                val fy = map.footY(m.x, m.z, m.y + 0.35f) + 0.05f
                if (m.y < fy) {
                    m.y = fy
                    if (kotlin.math.abs(m.vy) > 0.8f) { m.vy *= -0.34f; m.vx *= 0.62f; m.vz *= 0.62f; m.wx *= 0.55f; m.wy *= 0.55f; m.wz *= 0.55f }
                    else { m.rest = true; m.vx = 0f; m.vy = 0f; m.vz = 0f }
                }
            }
            i--
        }
    }

    private fun drawMags() {
        if (mags.isEmpty()) return
        GL.glBindVertexArray(cube!!.vao)
        for (m in mags) {
            val sc = if (m.life < 0.6f) max(0.001f, m.life / 0.6f) else 1f
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, m.x, m.y, m.z)
            Matrix.rotateM(mm, 0, degF(m.rx), 1f, 0f, 0f); Matrix.rotateM(mm, 0, degF(m.ry), 0f, 1f, 0f); Matrix.rotateM(mm, 0, degF(m.rz), 0f, 0f, 1f)
            Matrix.scaleM(mm, 0, 0.03f * sc, 0.13f * sc, 0.07f * sc)
            setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.145f, 0.157f, 0.173f)   // dark metal #25282c
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
    }

    /** the player's own rigged character, seen from behind in third-person view (same rifle pose as the bots) */
    private fun drawPlayerBody() {
        if (!(chars.ready && chars.has(playerChar))) return
        // reference pack: idle breathing (the body rises and falls 7 mm while you stand still), breathing sway in the rifle arms, the head follows the aim pitch
        Sway.calc(clock, 0f, if (aiming) 0.4f else 1f, swayTmp)
        val idleBob = if (footSpeed < 0.3f && onGround) sin(clock * 1.8f) * 0.007f else 0f
        val sa = if (winCam || airState != 3 || !playerAlive) 0f else swimAmt   // swimming pose 0..1: body lies on the water, back just above the surface
        Matrix.setIdentityM(cbase, 0); Matrix.translateM(cbase, 0, pos[0], pos[1] + idleBob + 1.04f * sa, pos[2])
        Matrix.rotateM(cbase, 0, Math.toDegrees((-bodyYaw).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(cbase, 0, CharRig.SCALE, CharRig.SCALE, CharRig.SCALE)
        cpose.mode = if (sa > 0.05f) CharPose.RELAXED else if (fists) CharPose.BOX else CharPose.RIFLE; cpose.walk = if (sa > 0.05f) swimPh else walkPhase; cpose.speed = footSpeed; cpose.sprint = run && footSpeed > 3f
        cpose.time = clock; cpose.punchKind = punchKind; cpose.punchU = if (fists && punchT > 0f) (1f - punchT / punchDur).coerceIn(0f, 1f) else -1f   // boxing: stance + the running punch
        cpose.crouch = crouchAmt; cpose.prone = if (winCam) 0f else max(proneAmt, sa); cpose.supine = if (winCam) 0f else supineAmt * (1f - sa); cpose.swim = sa; cpose.aimPitch = (if (winCam) 0f else pitch).coerceIn(-0.8f, 0.8f) + swayTmp[0] * 0.7f; cpose.headYaw = 0f; cpose.headPitch = if (winCam) 0f else (pitch * 0.5f).coerceIn(-0.42f, 0.42f)
        cpose.jumpT = jumpAnim.t; cpose.jumpW = jumpAnim.w; cpose.jumpDrop = jumpAnim.drop   // jump / landing animation (JumpAnim.kt)
        cpose.gunId = if (fists) null else guns[gi].id   // per-gun hold: stock on the shoulder, hands on the grip + handguard (GunHold)
        chars.draw(playerChar, vp, cbase, camX, camY, camZ, fogK, cpose, wear = Wear.id)
        val gf = cpose.gunFrame
        cpose.jumpT = -1f; cpose.jumpW = 0f; cpose.jumpDrop = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.swim = 0f; cpose.punchU = -1f   // the pose object is shared with the bots: never leave jump / prone / punch on
        GL.glUseProgram(prog)
        GL.glBindVertexArray(cube!!.vao)
        if (!fists && sa < 0.05f && cpose.hasGun) { // third-person + WINNER pose: the real 3D gun, attached to the hands (gunFrame)
            val wg = guns[gi]; val gm = gunGl[wg.id]; val gt = tex[wg.id]
            if (gm != null && (!gm.second.textured || gt != null)) {
                System.arraycopy(cbase, 0, pm, 0, 16)
                Matrix.multiplyMM(tmpM, 0, cbase, 0, gf, 0); System.arraycopy(tmpM, 0, pm, 0, 16)
                Matrix.translateM(pm, 0, GunHold.OX, GunHold.OY, GunHold.oz(wg.id))
                val rlP = if (reloadLeft > 0f && reloadProgress >= 0f) sin(reloadProgress * Math.PI.toFloat()) else 0f
                Matrix.rotateM(pm, 0, -rlP * 28f, 1f, 0f, 0f)   // dips while reloading
                Matrix.rotateM(pm, 0, rlP * 14f, 0f, 0f, 1f)
                Matrix.scaleM(pm, 0, 0.85f, 0.85f, 0.85f)
                setModel(pm)
                if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, gt!!) } else GL.glUniform1i(uMode, 1)
                GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                GL.glBindVertexArray(cube!!.vao)
                if (flash > 0f && !winCam) { // muzzle flash at the barrel tip
                    Matrix.translateM(pm, 0, 0f, gm.second.my, gm.second.zmin - 0.07f); Matrix.scaleM(pm, 0, 0.12f, 0.12f, 0.16f)
                    setModel(pm); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.85f, 0.4f)
                    GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
                }
            }
        }
    }

    private fun setModel(m: FloatArray) {
        GL.glUniformMatrix4fv(uModel, 1, false, m, 0)
        nm[0] = m[0]; nm[1] = m[1]; nm[2] = m[2]; nm[3] = m[4]; nm[4] = m[5]; nm[5] = m[6]; nm[6] = m[8]; nm[7] = m[9]; nm[8] = m[10]
        GL.glUniformMatrix3fv(uNM, 1, false, nm, 0)
    }

    private fun drawCube(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, mode: Int = 2) {
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.scaleM(mm, 0, sx, sy, sz)
        setModel(mm)
        GL.glUniform1i(uMode, mode); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(cube!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceAtMost(0.1f); last = now; val dt0 = dt
        step(dt)
        frames++; if (now - fpsT > 1_000_000_000L) { fps = frames; frames = 0; fpsT = now }
        posText = String.format(java.util.Locale.US, "X %.0f  Y %.1f  Z %.0f", pos[0], pos[1], pos[2])

        val gv = guns[gi]
        val targetFov = if (!fists && scopeSel >= 2) scopeFov(scopeSel) else aimFov(scopeSel)
        val airborne = airState != 3
        val aglNow = if (airborne) (if (airState == 0) AIR_Y else max(0f, pos[1] - map.ground(pos[0], pos[2]))) else 0f
        val introOn = airState == 0 && !spec && flightTime < PLANE_FLY_IN + PLANE_ORBIT
        val introS = ((flightTime - PLANE_FLY_IN) / PLANE_ORBIT).coerceIn(0f, 1f)
        val introFov = if (flightTime < PLANE_FLY_IN) 58f - 10f * smooth(flightTime / PLANE_FLY_IN * 1.1f) else 58f + 18f * smooth(introS * 1.25f)
        val fov = if (introOn) introFov else if (spec) 68f else if (airborne) (if (airState == 0) 76f else if (airState == 1) (if (skateMode) 77f else 83f) else 74f) else 70f + (targetFov - 70f) * adsAmount
        val nearP = if (introOn) 1f else if (airborne && aglNow > 30f) min(8f, max(0.6f, aglNow * 0.018f)) else 0.05f
        Matrix.perspectiveM(proj, 0, fov, surfW.toFloat() / surfH, nearP, if (airborne && aglNow > 30f) 7000f else 2500f)
        // clear sky in the plane / freefall, fog fades back in near the ground (like the HTML)
        val fogTarget = if (airState == 0) 8f else if (airborne) { val u = ((aglNow - 30f) / 110f).coerceIn(0f, 1f); 1f + 7f * u * u * (3f - 2f * u) } else 1f
        fogK += (fogTarget - fogK) * min(1f, dt0 * 4f)
        val cp = cos(pitch)
        var ex = pos[0]; var ey = pos[1] + 1.7f - eyeDrop; var ez = pos[2]
        if (airState == 0) { // chase camera around the plane: 26 m behind the look direction, 2.2 m to the right
            val ddx = sin(yaw) * cp; val ddy = sin(pitch); val ddz = -cos(yaw) * cp
            ex = planeX(flightTime) - ddx * 26f + cos(yaw) * 2.2f; ey = AIR_Y - ddy * 26f; ez = planeZ(flightTime) - ddz * 26f + sin(yaw) * 2.2f
        }
        // Phase 28: freefall + parachute are seen from behind like the HTML (updateCamera: 10 m boom, 8 m in skate stance, 0.8 m to the right)
        val airTp = (airState == 1 || airState == 2) && playerAlive && chars.ready && chars.has(playerChar)
        if (airTp) {
            val lenA = if (airState == 1 && skateMode) 8f else 10f
            val adx = sin(yaw) * cp; val ady = sin(pitch); val adz = -cos(yaw) * cp
            ex = pos[0] - adx * lenA + cos(yaw) * 0.8f; ey = pos[1] + 1.68f - ady * lenA; ez = pos[2] - adz * lenA + sin(yaw) * 0.8f
            val gyA = map.ground(ex, ez) + 0.5f
            if (ey < gyA) ey = gyA
        }
        if (Prefs.thirdPerson && airState == 3 && playerAlive) { // camera behind the player, pulled in by walls / terrain
            val hx = pos[0]; val hy = pos[1] + 1.6f - eyeDrop; val hz = pos[2]
            val ddx = sin(yaw) * cp; val ddy = sin(pitch); val ddz = -cos(yaw) * cp
            val bx = -ddx * 3.4f + cos(yaw) * 0.55f; val by = -ddy * 3.4f + 0.3f; val bz = -ddz * 3.4f + sin(yaw) * 0.55f
            val bl = sqrt(bx * bx + by * by + bz * bz)
            val th = map.rayWorld(hx, hy, hz, bx / bl, by / bl, bz / bl, bl)
            val f = if (th >= 0f && th < bl) max(0.3f, th - 0.25f) / bl else 1f
            ex = hx + bx * f; ey = hy + by * f; ez = hz + bz * f
            val gy3 = map.ground(ex, ez) + 0.4f
            if (ey < gy3) ey = gy3
            val ka = adsAmount.coerceIn(0f, 1f); ex += (pos[0] - ex) * ka; ey += (pos[1] + 1.7f - eyeDrop - ey) * ka; ez += (pos[2] - ez) * ka   // aiming: camera slides into the eyes
        }
        var lkx = ex + sin(yaw) * cp; var lky = ey + sin(pitch); var lkz = ez - cos(yaw) * cp
        if (winCam) { // WINNER: low camera in front of the player, slowly swinging left / right, looking up at the character
            val wt = winT; val e0 = easeS(wt / 1.2f)
            val ang = bodyYaw + 0.55f * sin(wt * 0.55f) * e0
            val dist = 4.6f - 1.2f * e0
            val hx = pos[0]; val hy = pos[1] + 1.2f; val hz = pos[2]
            val bx = sin(ang) * dist; val bz = -cos(ang) * dist; val by = -0.25f + 0.3f * (1f - e0)
            val bl = sqrt(bx * bx + by * by + bz * bz)
            val th = map.rayWorld(hx, hy, hz, bx / bl, by / bl, bz / bl, bl)
            val f = if (th >= 0f && th < bl) max(0.3f, th - 0.25f) / bl else 1f
            ex = hx + bx * f; ey = hy + by * f; ez = hz + bz * f
            val gyW = map.ground(ex, ez) + 0.35f
            if (ey < gyW) ey = gyW
            lkx = pos[0]; lky = pos[1] + 1.35f; lkz = pos[2]
        }
        if (introOn) { // aircraft intro: (1) fixed side camera, plane flies in from behind, (2) camera swings nose -> side -> behind and blends into the chase camera
            val rx = -fdz; val rz = fdx
            val rpx = planeX(flightTime); val rpz = planeZ(flightTime)
            if (flightTime < PLANE_FLY_IN) {
                val x = flightTime / PLANE_FLY_IN; val e = 1f - (1f - x) * (1f - x) * (1f - x); val q = (1f - e) * (1f - e)
                val ppx = rpx - fdx * 700f * (1f - e) - rx * 90f * q; val ppz = rpz - fdz * 700f * (1f - e) - rz * 90f * q
                ex = rpx + rx * 34f - fdx * 40f; ey = AIR_Y + 10f; ez = rpz + rz * 34f - fdz * 40f
                lkx = ppx; lky = AIR_Y + 60f * q + 1f; lkz = ppz
            } else {
                val s0 = introS
                val en = if (s0 < 0.5f) 4f * s0 * s0 * s0 else 1f - (-2f * s0 + 2f).pow(3) / 2f
                val phi = 3.1415927f * en; val sn = sin(3.1415927f * en)
                val dist = 15f + (24.3f - 15f) * en + 7f * sn; val hgt = 1.8f + (9.2f - 1.8f) * en + 1.5f * sn
                val ivx = rpx + (cos(phi) * fdx - sin(phi) * rx) * dist
                val ivy = AIR_Y + hgt
                val ivz = rpz + (cos(phi) * fdz - sin(phi) * rz) * dist
                val w = smooth((s0 - 0.72f) / 0.28f)
                ex = ivx + (ex - ivx) * w; ey = ivy + (ey - ivy) * w; ez = ivz + (ez - ivz) * w
                lkx = rpx + (lkx - rpx) * w; lky = AIR_Y + 1.2f + (lky - AIR_Y - 1.2f) * w; lkz = rpz + (lkz - rpz) * w
            }
        }
        if (spec) {
            specCamera(dt0)
            ex = specCam[0]; ey = specCam[1]; ez = specCam[2]; lkx = specCam[3]; lky = specCam[4]; lkz = specCam[5]
        }
        val wlv = map.waterLevelAt(ex, ez); underwater = airState == 3 && wlv > -900f && ey < wlv
        camX = ex; camY = ey; camZ = ez
        Matrix.setLookAtM(view, 0, ex, ey, ez, lkx, lky, lkz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // real-time shadows: depth pass from the sun around the camera (not in the plane / high freefall)
        shadowDraw = false
        if (Prefs.shadows && shadow.ready && (!airborne || aglNow < 120f)) {
            when (Prefs.gfx) { "high" -> shadow.ensure(2048, 115f); "low" -> shadow.ensure(1024, 80f); else -> shadow.ensure(1024, 100f) }
            if (shadow.ready) { renderShadows(ex, map.ground(ex, ez), ez); shadowDraw = true }
        }
        // post-processing (bloom + AO): the scene goes into an off-screen target, PostFx composites it to the screen at the end of the frame
        val farP = if (airborne && aglNow > 30f) 7000f else 2500f
        val useAo = Prefs.ao && Prefs.gfx == "high" && postLevel >= 2 && !airborne
        val useBloom = Prefs.bloom && Prefs.gfx != "low" && postLevel >= 1
        val usePost = (useAo || useBloom) && post.ensure(surfW, surfH)
        if (usePost) post.begin()
        // auto performance: shed AO first, then bloom, when the frame time stays high (never comes back inside the same match)
        if (usePost && clock > 8f) {
            frameAvg += (dt0 - frameAvg) * min(1f, dt0 * 0.6f)
            if (frameAvg > 0.032f) slowT += dt0 else slowT = max(0f, slowT - dt0)
            if (slowT > 2.5f) { postLevel = if (useAo) 1 else 0; slowT = 0f; frameAvg = 0.02f }
        }
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glUseProgram(prog)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uShOn"), if (shadowDraw) 1f else 0f)
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uShMap"), 1)
        if (shadowDraw) {
            shadow.bindForSampling()
            GL.glUniformMatrix4fv(GL.glGetUniformLocation(prog, "uShVP"), 1, false, shadow.lightVP, 0)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uShTex"), 1f / shadow.size)
        }
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        GL.glUniform3f(GL.glGetUniformLocation(prog, "uEye"), ex, ey, ez)
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uTex"), 0)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uFog"), fogK)
        GL.glDisable(GL.GL_CULL_FACE)
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        setModel(ident)

        val sc = map.seaColor
        GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, ((sc shr 16) and 255) / 255f, ((sc shr 8) and 255) / 255f, (sc and 255) / 255f)
        // the flat sea follows the eye (snapped to 100 m), so there is water in every direction, however far you swim
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, Math.round(ex / 100f) * 100f, 0f, Math.round(ez / 100f) * 100f); setModel(mm)
        GL.glBindVertexArray(seaMesh!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 6, GL.GL_UNSIGNED_INT, 0)
        setModel(ident)

        val uRoadOn = GL.glGetUniformLocation(prog, "uRoadOn")
        val uDeckOn = GL.glGetUniformLocation(prog, "uDeckOn")
        GL.glUniform1i(GL.glGetUniformLocation(prog, "uRoad"), 2)
        GL.glUniform4f(GL.glGetUniformLocation(prog, "uRoadXf"), roadXf[0], roadXf[1], roadXf[2], roadXf[3])
        if (roadTex != 0) { GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, roadTex); GL.glActiveTexture(GL.GL_TEXTURE0) }
        for (m in layers) {
            GL.glUniform1f(uRoadOn, 0f); GL.glUniform1f(uDeckOn, if (m.mat == "bridge") 1f else 0f)
            when (m.mat) {
                "terrain", "atlas", "bridge" -> {
                    GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0)
                    GL.glBindTexture(GL.GL_TEXTURE_2D, tex[if (m.mat == "terrain") "terrain" else "atlas"]!!)
                    if (m.mat == "terrain" && roadTex != 0) {   // black asphalt + white dashed centre line painted over the terrain
                        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, roadTex); GL.glActiveTexture(GL.GL_TEXTURE0)
                        GL.glUniform1f(uRoadOn, 1f)
                    }
                }
                "house" -> GL.glUniform1i(uMode, 1)
                "road" -> {   // PHASE 67: the road mesh itself carries the white dashed centre line (before it was flat black and hid the dashes painted on the terrain below)
                    GL.glUniform1i(uMode, 2)
                    if (roadTex != 0) { GL.glUniform3f(uColor, 0.045f, 0.045f, 0.05f); GL.glUniform1f(uRoadOn, 1f) } else GL.glUniform3f(uColor, 0.20f, 0.21f, 0.22f)
                }
                "roadb" -> { GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.20f, 0.21f, 0.22f) }
                "water" -> { GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.14f, 0.55f, 0.92f) }   // lake / pond: clear blue (was dark teal)
                else -> GL.glUniform1i(uMode, 2)
            }
            val isRoadMesh = m.mat == "road" || m.mat == "roadb"
            if (isRoadMesh) { GL.glEnable(GL.GL_POLYGON_OFFSET_FILL); GL.glPolygonOffset(-2f, -2f) }
            GL.glBindVertexArray(m.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.mesh.count, GL.GL_UNSIGNED_INT, 0)
            if (isRoadMesh) GL.glDisable(GL.GL_POLYGON_OFFSET_FILL)
        }
        GL.glUniform1f(uRoadOn, 0f); GL.glUniform1f(uDeckOn, 0f)

        natureGl?.let { ng ->
            val vq = when (Prefs.gfx) { "low" -> 0.6f; "high" -> 1f; else -> 0.85f }
            val vz = max(1f, 0.70021f / kotlin.math.tan(Math.toRadians(fov / 2.0)).toFloat())
            ng.setShadow(shadowDraw, shadow.lightVP, 1f / max(1, shadow.size))
            ng.draw(vp, ex, ey, ez, fogK, clock, vq, vz)
            GL.glUseProgram(prog)
        }
        drawLoot(ex, ez)
        drawCrates(ex, ez)
        drawDrops()
        // bots, tracers, bullet marks
        for (b in botSys.bots) drawBot(b)
        if (((Prefs.thirdPerson && !fppView) || winCam) && playerAlive && airState == 3) drawPlayerBody()
        if (airTp) drawPlayerAir()
        drawContactShadows()
        if (spec) drawPlayerCorpse()
        if (flightTime < 52f) drawPlane()
        for (t in botSys.tracers) drawTracer(t)
        for (k in marks) drawCube(k.x, k.y, k.z, 0.14f, 0.14f, 0.14f, 0.08f, 0.08f, 0.08f)

        for (g in nades) { drawCube(g.x, g.y, g.z, 0.24f, 0.28f, 0.24f, 0.4f, 0.5f, 0.42f); drawCube(g.x, g.y + 0.16f, g.z, 0.1f, 0.06f, 0.14f, 1f, 0.67f, 0.47f) }
        drawFx()
        drawBlood()
        drawShotFx()
        drawSwimFx()
        drawZoneWall()
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uShOn"), 0f)   // the gun in your hands is not shadowed by the world
        if (playerAlive && airState == 3 && fppView && !winCam && !diving && swimAmt < 0.5f) drawViewmodel()
        GL.glBindVertexArray(0)
        if (usePost) post.finish(nearP, farP, fov, useBloom, useAo)
    }

    private fun rgb(c: Int, f: (Float, Float, Float) -> Unit) = f(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    private fun drawLoot(ex: Float, ez: Float) {
        for (l in loot) {
            if (!l.active || l.crate != null) continue
            val dx = l.x - ex; val dz = l.z - ez
            if (dx * dx + dz * dz > 110f * 110f) continue
            val t = clock * 1.6f + l.spin
            val bob = 0.55f + sin(t) * 0.08f
            when (l.type) {
                LootType.WEAPON -> {
                    val gm = gunGl[guns[l.sub].id]
                    if (gm != null) {
                        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, l.x, l.y + bob, l.z)
                        Matrix.rotateM(pm, 0, Math.toDegrees(t.toDouble()).toFloat(), 0f, 1f, 0f); Matrix.scaleM(pm, 0, 1.1f, 1.1f, 1.1f)
                        setModel(pm)
                        if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, tex[guns[l.sub].id]!!) } else GL.glUniform1i(uMode, 1)
                        GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    halo(l, 0xFFFFC21A.toInt())
                }
                LootType.VEST -> {
                    val gm = gunGl["vest" + l.sub]
                    if (gm != null) {
                        Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, l.x, l.y + bob + 0.25f, l.z)
                        Matrix.rotateM(pm, 0, Math.toDegrees(t.toDouble()).toFloat(), 0f, 1f, 0f); Matrix.scaleM(pm, 0, 1.25f, 1.25f, 1.25f)
                        setModel(pm); GL.glUniform1i(uMode, 1)
                        GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    halo(l, AmmoInfo.VEST_COLORS[l.sub])
                }
                LootType.AMMO -> { rgb(AmmoInfo.COLORS[l.sub]) { r, g, b -> drawCube(l.x, l.y + bob, l.z, 0.38f, 0.26f, 0.26f, r, g, b) }; halo(l, AmmoInfo.COLORS[l.sub]) }
                LootType.MEDKIT -> {
                    drawCube(l.x, l.y + bob, l.z, 0.42f, 0.3f, 0.3f, 0.95f, 0.95f, 0.95f)
                    drawCube(l.x, l.y + bob, l.z, 0.30f, 0.09f, 0.32f, 0.85f, 0.1f, 0.1f); drawCube(l.x, l.y + bob, l.z, 0.09f, 0.22f, 0.32f, 0.85f, 0.1f, 0.1f)
                    halo(l, 0xFFFFFFFF.toInt())
                }
                LootType.GRENADE -> {
                    drawCube(l.x, l.y + bob, l.z, 0.24f, 0.28f, 0.24f, 0.4f, 0.5f, 0.42f); drawCube(l.x, l.y + bob + 0.17f, l.z, 0.1f, 0.06f, 0.14f, 1f, 0.67f, 0.47f); halo(l, 0xFF9BE05A.toInt())
                }
                LootType.SCOPE -> {
                    drawCube(l.x, l.y + bob, l.z, 0.12f, 0.12f, 0.34f, 0.12f, 0.12f, 0.14f)
                    drawCube(l.x, l.y + bob, l.z + 0.17f, 0.15f, 0.15f, 0.04f, 0.3f, 0.8f, 1f); halo(l, 0xFF35D0C8.toInt())
                }
                LootType.VEST4 -> {
                    drawBlob(l.x, l.y + bob + 0.3f, l.z, 0.42f, 0.53f, 0.24f, 0.85f, 0.70f, 0.23f)
                    drawCube(l.x, l.y + bob + 0.34f, l.z, 0.34f, 0.06f, 0.5f, 1f, 0.95f, 0.69f); halo(l, 0xFFFFD34D.toInt())
                }
                LootType.HELM4 -> {
                    drawBlob(l.x, l.y + bob + 0.2f, l.z, 0.34f, 0.26f, 0.36f, 0.85f, 0.70f, 0.23f)
                    drawCube(l.x, l.y + bob + 0.1f, l.z + 0.2f, 0.36f, 0.05f, 0.2f, 0.42f, 0.35f, 0.12f); halo(l, 0xFFFFD34D.toInt())
                }
                LootType.SHROOM -> {
                    drawCube(l.x, l.y + bob - 0.05f, l.z, 0.1f, 0.34f, 0.1f, 0.91f, 0.87f, 0.78f)
                    drawBlob(l.x, l.y + bob + 0.16f, l.z, 0.34f, 0.2f, 0.34f, 0.84f, 0.27f, 0.24f)
                    drawCube(l.x + 0.1f, l.y + bob + 0.27f, l.z + 0.08f, 0.06f, 0.03f, 0.06f, 0.96f, 0.94f, 0.88f)
                    drawCube(l.x - 0.1f, l.y + bob + 0.25f, l.z - 0.04f, 0.05f, 0.03f, 0.05f, 0.96f, 0.94f, 0.88f); halo(l, 0xFFE0584F.toInt())
                }
                else -> { drawCube(l.x, l.y + bob, l.z, 0.4f, 0.4f, 0.14f, 0.25f, 0.55f, 0.95f); halo(l, 0xFF5FB4E8.toInt()) }
            }
        }
        GL.glBindVertexArray(0)
    }

    private fun drawBlob(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) {
        val sp = sphere ?: return
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, x, y, z); Matrix.scaleM(mm, 0, sx, sy, sz)
        setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(sp.vao); GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
    }

    /** Crate on a parachute with a gold beam, and the cargo plane that dropped it (adMake in the HTML). */
    private fun drawDrops() {
        if (drops.isEmpty()) return
        val pulse = 0.55f + 0.4f * sin(clock * 5.5f)
        for (d in drops) {
            // the plane keeps flying across the island for ~8.7 s
            val off = 260f - d.t * 110f
            if (kotlin.math.abs(off) <= 700f) drawPlaneAt(d.x + d.dir * off, d.g + 215f, d.z, -d.dir * 1.5708f)
            if (d.state == 0) continue
            val cy = d.g + d.h
            GL.glBindVertexArray(cube!!.vao)
            Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, d.x, cy, d.z)
            part(0f, 0.7f, 0f, 0f, 0f, 1.7f, 1.3f, 1.7f, 0xFFA8321F.toInt())
            part(0f, 0.7f, 0f, 0f, 0f, 1.74f, 0.22f, 1.74f, 0xFFF2C230.toInt())
            part(0f, 0.7f, 0f, 0f, 0f, 0.22f, 1.34f, 1.74f, 0xFFF2C230.toInt())
            // lid opens after landing (hinge at the back edge)
            System.arraycopy(base, 0, pm, 0, 16)
            Matrix.translateM(pm, 0, 0f, 1.38f, -0.85f); Matrix.rotateM(pm, 0, -Math.toDegrees(minOf(1.9f, d.landT * 2.2f).toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.translateM(pm, 0, 0f, 0.05f, 0.85f); Matrix.scaleM(pm, 0, 1.76f, 0.14f, 1.76f)
            setModel(pm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.49f, 0.14f, 0.08f)
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            if (d.state == 1) { // 3D parachute above the crate (lines meet on the lid)
                drawChute(d.x, cy + 0.1f, d.z, 1f, degF(d.dir * 1.5708f), 1.25f, 3f * sin(clock * 1.3f + d.x))
            }
            // gold beam (additive) + pulsing glow
            val beamA = d.beam
            if (beamA > 0.01f) {
                val w = wall
                if (w != null) {
                    Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.x, cy - 4f, d.z); Matrix.scaleM(mm, 0, 1.1f, 300f, 1.1f)
                    setModel(mm)
                    GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
                    GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.68f, 0f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), beamA)
                    GL.glBindVertexArray(w.vao); GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
                    val sp = sphere
                    if (sp != null) {
                        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, d.x, cy + 2.5f, d.z); Matrix.scaleM(mm, 0, 1.1f, 1.1f, 1.1f)
                        setModel(mm); GL.glUniform3f(uColor, 1f, 0.94f, 0.63f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), pulse * 0.8f)
                        GL.glBindVertexArray(sp.vao); GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
                    }
                    GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
                    GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
                }
            }
        }
        GL.glBindVertexArray(0)
    }

    private fun halo(l: Loot, col: Int) { rgb(col) { r, g, b -> drawCube(l.x, l.y + 0.04f, l.z, 0.9f, 0.04f, 0.9f, r, g, b, 3) } }

    // ---------------------------------------------------------------- swimming effects (DSW of the HTML) + sun shadows

    private fun ripple(x: Float, y: Float, z: Float, big: Boolean) { if (rips.size >= 14) rips.removeAt(0); rips.add(Rip(x, y + 0.04f, z, if (big) 1.6f else 1f)) }

    private fun splash(x: Float, y: Float, z: Float, n: Int, power: Float) {
        for (i in 0 until n) {
            if (splashDrops.size >= 48) break
            val a = rnd.nextFloat() * 6.2831855f; val sp = (0.6f + rnd.nextFloat()) * power
            splashDrops.add(Drop2(x + (rnd.nextFloat() - 0.5f) * 0.6f, y, z + (rnd.nextFloat() - 0.5f) * 0.6f,
                cos(a) * sp * 0.6f, 2.2f + rnd.nextFloat() * 3.2f * power, sin(a) * sp * 0.6f, y - 0.05f))
        }
    }

    /** tick() of the swimming block: ripple rings, splash splashDrops, enter / leave water, wading strokes. */
    private fun stepSwim(dt: Float) {
        var i = rips.size - 1
        while (i >= 0) { val r = rips[i]; r.life -= dt * 0.85f; if (r.life <= 0f) rips.removeAt(i); i-- }
        i = splashDrops.size - 1
        while (i >= 0) {
            val d = splashDrops[i]; d.vy -= 14f * dt; d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt; d.life -= dt * 1.4f
            if (d.life <= 0f || d.y < d.wy) splashDrops.removeAt(i)
            i--
        }
        if (!playerAlive || airState != 3) { swimIn = false; swimming = false; return }
        val px = pos[0]; val pz = pos[2]; val depth = map.waterDepth(px, pz); val lvl = map.waterLevelAt(px, pz)
        val swim = depth > 1.25f; val wade = depth > 0.3f
        if (swim && !swimIn) { swimIn = true; ripple(px, lvl, pz, true); splash(px, lvl, pz, 22, 1.2f); sfx?.play("splash"); toast("SWIMMING / slower, no sprint. Swim to the shore to get out.") }
        else if (!swim && swimIn) { swimIn = false; ripple(px, lvl, pz, false); splash(px, lvl, pz, 10, 0.7f) }
        if (wade && footSpeed > 0.8f) {
            swimAcc -= dt
            if (swimAcc <= 0f) {
                swimAcc = if (swim) 0.26f else 0.34f
                ripple(px, lvl, pz, false)
                if (rnd.nextFloat() < 0.7f) splash(px, lvl, pz, if (swim) 4 else 3, 0.5f)
                if (swim) sfx?.play("swim")
            }
        }
    }

    private fun drawSwimFx() {
        if (rips.isEmpty() && splashDrops.isEmpty()) return
        val w = wall
        if (w != null && rips.isNotEmpty()) {
            GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
            GL.glUniform1i(uMode, 3); GL.glBindVertexArray(w.vao); GL.glUniform3f(uColor, 0.91f, 0.96f, 1f)
            for (r in rips) {
                val rad = (0.6f + (1f - r.life) * 3.6f * r.big) * 0.65f
                Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, r.x, r.y, r.z); Matrix.scaleM(mm, 0, rad, 0.07f, rad)
                setModel(mm)
                GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), max(0f, r.life) * 0.55f)
                GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
            }
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
            GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        }
        for (d in splashDrops) drawCube(d.x, d.y, d.z, 0.1f, 0.1f, 0.1f, 0.85f, 0.94f, 1f)
        setModel(ident)
    }

    /** one box of a shadow caster: model = T(x,y,z) * Ry(yawDeg) * T(lx,ly,lz) * S(sx,sy,sz) */
    private fun shCube(x: Float, y: Float, z: Float, yawDeg: Float, lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float) {
        Matrix.setIdentityM(shm, 0); Matrix.translateM(shm, 0, x, y, z); Matrix.rotateM(shm, 0, yawDeg, 0f, 1f, 0f)
        Matrix.translateM(shm, 0, lx, ly, lz); Matrix.scaleM(shm, 0, sx, sy, sz)
        shadow.setModel(shm)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    private fun humanShadow(x: Float, y: Float, z: Float, yawDeg: Float) {
        shCube(x, y, z, yawDeg, 0f, 0.42f, 0f, 0.36f, 0.84f, 0.22f)    // legs
        shCube(x, y, z, yawDeg, 0f, 1.14f, 0f, 0.46f, 0.60f, 0.26f)    // torso
        shCube(x, y, z, yawDeg, 0f, 1.58f, 0f, 0.22f, 0.24f, 0.22f)    // head
    }

    /** Depth pass from the sun: houses / baked buildings / bridge, trees + boulders, bots and your own body (third person). */
    private fun renderShadows(cx: Float, cy: Float, cz: Float) {
        shadow.begin(cx, cy, cz)
        val h = shadow.half
        Matrix.setIdentityM(shm, 0); shadow.setModel(shm)
        for (m in layers) {
            if (m.mat != "atlas" && m.mat != "house" && m.mat != "bridge") continue
            GL.glBindVertexArray(m.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, m.mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
        natureGl?.drawShadow(shadow.lightVP, cx, cz, h * 1.15f, clock)
        shadow.useProgram()
        val cb = cube
        if (cb != null) {
            GL.glBindVertexArray(cb.vao)
            for (b in botSys.bots) {
                if (!b.alive || b.air != 3) continue
                if (hypot(b.x - cx, b.z - cz) > h * 1.2f) continue
                humanShadow(b.x, b.y, b.z, Math.toDegrees(b.yaw.toDouble()).toFloat())
            }
            if ((Prefs.thirdPerson || winCam) && playerAlive && airState == 3) humanShadow(pos[0], pos[1], pos[2], Math.toDegrees((-bodyYaw).toDouble()).toFloat())
        }
        GL.glBindVertexArray(0)
        shadow.end()
    }

    private fun drawFx() {
        val sp = sphere ?: return
        if (fxs.isEmpty()) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE); GL.glDepthMask(false)
        GL.glBindVertexArray(sp.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.6f, 0.25f)
        for (f in fxs) {
            val k = (f.life / 0.5f).coerceIn(0f, 1f)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, f.x, f.y, f.z); val sc = 1.5f + (1f - k) * 3.5f; Matrix.scaleM(mm, 0, sc, sc, sc)
            setModel(mm)
            GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.8f * k)
            GL.glDrawElements(GL.GL_TRIANGLES, sp.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    private fun drawZoneWall() {
        val w = wall ?: return
        if (zoneRadius < 1f) return
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA); GL.glDepthMask(false)
        GL.glUniform1i(uMode, 3); GL.glBindVertexArray(w.vao)
        Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, zoneCx, -40f, zoneCz); Matrix.scaleM(mm, 0, zoneRadius, 320f, zoneRadius)
        setModel(mm); GL.glUniform3f(uColor, 0.25f, 0.62f, 1f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.28f)
        GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
        if (nextR > 1f) { // next safe zone: white wall (nextWall in the HTML)
            Matrix.setIdentityM(mm, 0); Matrix.translateM(mm, 0, nextX, -40f, nextZ); Matrix.scaleM(mm, 0, nextR, 320f, nextR)
            setModel(mm); GL.glUniform3f(uColor, 1f, 1f, 1f); GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 0.2f)
            GL.glDrawElements(GL.GL_TRIANGLES, w.count, GL.GL_UNSIGNED_INT, 0)
        }
        GL.glUniform1f(GL.glGetUniformLocation(prog, "uAlpha"), 1f)
        GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
    }

    private val base = FloatArray(16); private val pm = FloatArray(16)

    private fun part(px: Float, py: Float, pz: Float, rotX: Float, dropY: Float, sx: Float, sy: Float, sz: Float, col: Int) {
        System.arraycopy(base, 0, pm, 0, 16)
        Matrix.translateM(pm, 0, px, py, pz); if (rotX != 0f) Matrix.rotateM(pm, 0, rotX, 1f, 0f, 0f)
        Matrix.translateM(pm, 0, 0f, -dropY, 0f); Matrix.scaleM(pm, 0, sx, sy, sz)
        setModel(pm)
        GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, ((col shr 16) and 255) / 255f, ((col shr 8) and 255) / 255f, (col and 255) / 255f)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** The aircraft on its route. During the first PLANE_FLY_IN seconds it flies in from far behind (and above / to the left), levelling out. */
    private fun drawPlane() {
        var px = planeX(flightTime); var pz = planeZ(flightTime); var py = AIR_Y + sin(flightTime * 1.3f) * 0.12f
        var pitchD = 0f; var bankD = 0f
        if (flightTime < PLANE_FLY_IN) {
            val x = flightTime / PLANE_FLY_IN; val e = 1f - (1f - x) * (1f - x) * (1f - x); val q = (1f - e) * (1f - e)
            px -= fdx * 700f * (1f - e) + (-fdz) * 90f * q; pz -= fdz * 700f * (1f - e) + fdx * 90f * q; py += 60f * q
            pitchD = -0.10f * q * 57.29578f; bankD = 0.35f * q * 57.29578f
        }
        drawPlaneAt(px, py, pz, flightYaw, pitchD, bankD)
    }

    /** Real aircraft model (nose = -Z), 2 spinning engine fans. Also used for the cargo plane of the air drop. */
    private fun drawPlaneAt(px: Float, py: Float, pz: Float, heading: Float, pitchD: Float = 0f, bankD: Float = 0f) {
        if (!planeReady) return
        Matrix.setIdentityM(base, 0); Matrix.translateM(base, 0, px, py, pz)
        Matrix.rotateM(base, 0, Math.toDegrees((-heading).toDouble()).toFloat(), 0f, 1f, 0f)
        if (pitchD != 0f) Matrix.rotateM(base, 0, pitchD, 1f, 0f, 0f)
        Matrix.rotateM(base, 0, bankD + sin(flightTime * 0.9f) * 2f, 0f, 0f, 1f)
        GL.glUniform1i(uMode, 1)   // vertex colours (the jet has no texture)
        val spin = Math.toDegrees(((clock * 34f) % 6.2831855f).toDouble()).toFloat()
        for (p in planeGl) {
            System.arraycopy(base, 0, pm, 0, 16)
            if (p.prop) { Matrix.translateM(pm, 0, p.px, p.py, p.pz); Matrix.rotateM(pm, 0, spin, 0f, 0f, 1f) }
            setModel(pm)
            GL.glBindVertexArray(p.mesh.vao); GL.glDrawElements(GL.GL_TRIANGLES, p.mesh.count, GL.GL_UNSIGNED_INT, 0)
        }
    }

    private fun drawBot(b: Bot) {
        val dead = !b.alive
        if (dead && clock - b.deadAt > 6f) return
        if (!dead && b.air == 0) return
        GL.glBindVertexArray(cube!!.vao)
        val cid = botChars[(b.name.hashCode() and 0x7fffffff) % botChars.size]
        val cd = hypot(b.x - camX, b.z - camZ)
        val skinned = chars.ready && chars.has(cid) && cd < 45f
        // Phase 28: in freefall / under the canopy a skinned bot uses the same smoothed air pose as the player (skate stance + board, dive, chute hang)
        val airVis = !dead && skinned && (b.air == 1 || b.air == 2)
        Matrix.setIdentityM(base, 0)
        if (airVis) {
            val av = b.av
            Matrix.translateM(base, 0, b.x + av.offX, b.y + av.offY, b.z + av.offZ)
            Matrix.rotateM(base, 0, degF(av.rotY), 0f, 1f, 0f); Matrix.rotateM(base, 0, degF(av.rotX), 1f, 0f, 0f); Matrix.rotateM(base, 0, degF(av.rotZ), 0f, 0f, 1f)
        } else {
            Matrix.translateM(base, 0, b.x, b.y, b.z)
            Matrix.rotateM(base, 0, Math.toDegrees(b.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            if (!dead && b.air == 1 && b.dive) { Matrix.translateM(base, 0, 0f, 0.9f, 0f); Matrix.rotateM(base, 0, -80f, 1f, 0f, 0f) }
            if (dead) { val f = ((clock - b.deadAt) * 4f).coerceAtMost(1f); Matrix.translateM(base, 0, 0f, 0.15f * f, 0f); Matrix.rotateM(base, 0, -88f * f, 1f, 0f, 0f) }
        }
        Matrix.scaleM(base, 0, 0.8f, 0.8f, 0.8f)
        val sw = if (dead) 0f else sin(b.walk) * 35f
        val skin = 0xFFC58F6B.toInt(); val pants = 0xFF2B2B33.toInt()
        if (skinned) {
            if (airVis) {
                chars.draw(cid, vp, base, camX, camY, camZ, fogK, b.av.cp, pants = null)
            } else {
                cpose.mode = if (dead) CharPose.RIFLE else if (b.air == 1) CharPose.DIVE else if (b.air == 2) CharPose.CHUTE else CharPose.RIFLE
                cpose.walk = b.walk; cpose.speed = if (dead) 0f else b.speed; cpose.sprint = b.speed > 3.2f
                // breathing sway of the reference pack: the rifle arms drift a hair, only for fighters that already fired (HTML: e._pr exists from the first shot)
                var swayA = 0f
                if (b.fired && !dead && b.air == 3) { Sway.calc(clock, b.avoid, 1f, swayTmp); swayA = swayTmp[0] * 0.7f }
                cpose.crouch = 0f; cpose.prone = 0f; cpose.supine = 0f; cpose.aimPitch = swayA; cpose.headYaw = 0f; cpose.headPitch = 0f
                cpose.gunId = b.gun.id
                chars.draw(cid, vp, base, camX, camY, camZ, fogK, cpose, pants = null)
            }
            GL.glUseProgram(prog)
            GL.glBindVertexArray(cube!!.vao)
            if (airVis) drawSkateboard(base, b.av)
        } else {
            part(0f, 1.35f, 0f, 0f, 0f, 0.5f, 0.7f, 0.28f, b.shirt)
            part(0f, 1.85f, 0f, 0f, 0f, 0.26f, 0.26f, 0.26f, skin)
            part(-0.27f, 1.67f, 0f, 72f + sw * 0.15f, 0.3f, 0.14f, 0.6f, 0.14f, b.shirt)
            part(0.27f, 1.67f, 0f, 78f - sw * 0.15f, 0.3f, 0.14f, 0.6f, 0.14f, b.shirt)
            part(-0.12f, 1.04f, 0f, sw, 0.52f, 0.18f, 1.04f, 0.18f, pants)
            part(0.12f, 1.04f, 0f, -sw, 0.52f, 0.18f, 1.04f, 0.18f, pants)
        }
        // the bot's gun (real 3D model)
        val gm = gunGl[b.gun.id]
        if (gm != null && !airVis) {
            System.arraycopy(base, 0, pm, 0, 16)
            if (skinned && cpose.hasGun) { Matrix.multiplyMM(tmpM, 0, base, 0, cpose.gunFrame, 0); System.arraycopy(tmpM, 0, pm, 0, 16); Matrix.translateM(pm, 0, GunHold.OX, GunHold.OY, GunHold.oz(b.gun.id)) }
            else Matrix.translateM(pm, 0, 0.14f, 1.45f, -0.42f)
            Matrix.scaleM(pm, 0, 0.85f, 0.85f, 0.85f)
            setModel(pm)
            if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, tex[b.gun.id]!!) } else GL.glUniform1i(uMode, 1)
            GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
        }
        if (!dead && b.air == 2) { // 3D parachute (same model as the player's)
            drawChute(b.x, b.y + 0.8f, b.z, (b.chuteT / 0.9f).coerceIn(0f, 1f), Math.toDegrees(b.yaw.toDouble()).toFloat(), 0.85f, 2.5f * sin(clock * 1.7f + b.avoid))
        }
        // health bar for 3 s after a hit (always faces the camera)
        if (!dead && b.barTime > 0f) {
            val f = (b.hp / BotSystem.HP).coerceIn(0f, 1f)
            Matrix.setIdentityM(pm, 0); Matrix.translateM(pm, 0, b.x, b.y + 2.05f, b.z)
            Matrix.rotateM(pm, 0, -Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            val back = pm.copyOf()
            Matrix.scaleM(pm, 0, 1.0f, 0.09f, 0.03f); setModel(pm)
            GL.glBindVertexArray(cube!!.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 0.1f, 0.1f, 0.1f); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            Matrix.translateM(back, 0, -(1f - f) * 0.5f, 0f, 0.02f); Matrix.scaleM(back, 0, 0.96f * f, 0.06f, 0.03f); setModel(back)
            GL.glUniform3f(uColor, 1f, 0.67f, 0.47f); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
    }

    private fun drawTracer(t: Tracer) {
        val dx = t.x1 - t.x0; val dy = t.y1 - t.y0; val dz = t.z1 - t.z0
        val len = sqrt(dx * dx + dy * dy + dz * dz); if (len < 0.1f) return
        val ndx = dx / len; val ndy = dy / len; val ndz = dz / len
        Matrix.setIdentityM(pm, 0)
        Matrix.translateM(pm, 0, (t.x0 + t.x1) / 2, (t.y0 + t.y1) / 2, (t.z0 + t.z1) / 2)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.atan2(-ndx, -ndz).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(pm, 0, Math.toDegrees(kotlin.math.asin(ndy.coerceIn(-1f, 1f)).toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.scaleM(pm, 0, 0.04f, 0.04f, len)
        setModel(pm)
        GL.glBindVertexArray(cube!!.vao); GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.9f, 0.5f)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    /** Gun in front of the camera: depth cleared so it never sinks into walls. */
    /**
     * First-person BOXING hands (fix): before, two bare boxes moved on a 0.28 s sine that ignored the punch type and the real punch length
     * (long punches first flew BACKWARDS). Now both gloved arms stay in a boxing guard (fists at the chin, elbows down), and the punch is
     * the real combo of BoxAnim (jab -> cross -> hook -> uppercut) with the same timing as the body animation and the damage.
     */
    private val fpPos = FloatArray(3)
    private fun fpLerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    /** fist position (camera metres, -z = forward) of the hand `left` for punch `kind` at progress u (-1 = guard only) */
    private fun fistPos(left: Boolean, kind: Int, u: Float, out: FloatArray) {
        val sx = if (left) -1f else 1f
        var x = sx * 0.17f; var y = -0.17f; var z = -0.50f          // guard: in front of the chin
        val throwing = u >= 0f && BoxAnim.leftArm(kind) == left
        if (throwing) {
            val t = BoxAnim.envelope(kind, u)
            when (kind) {
                BoxAnim.JAB, BoxAnim.CROSS -> {                         // straight punch: along the line of sight, a little to the centre
                    val tx = if (kind == BoxAnim.JAB) -0.04f else -0.02f
                    x = fpLerp(x, tx, t); y = fpLerp(y, -0.10f, t); z = fpLerp(z, -1.05f, t)
                }
                BoxAnim.HOOK -> {                                       // chamber out to the side, then swing round in an arc
                    if (t < 0.4f) { val w = t / 0.4f; x = fpLerp(x, -0.50f, w); y = fpLerp(y, -0.14f, w); z = fpLerp(z, -0.55f, w) }
                    else { val w = (t - 0.4f) / 0.6f; x = fpLerp(-0.50f, 0.10f, w); y = -0.14f; z = fpLerp(-0.55f, -0.80f, w) - 0.12f * sin(Math.PI.toFloat() * w) }
                }
                else -> {                                               // uppercut: dip, then drive up from below
                    if (t < 0.4f) { val w = t / 0.4f; x = fpLerp(x, 0.20f, w); y = fpLerp(y, -0.50f, w); z = fpLerp(z, -0.40f, w) }
                    else { val w = (t - 0.4f) / 0.6f; x = fpLerp(0.20f, 0.06f, w); y = fpLerp(-0.50f, -0.02f, w); z = fpLerp(-0.40f, -0.75f, w) }
                }
            }
        }
        out[0] = x; out[1] = y; out[2] = z
    }

    private fun drawFists() {
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        Matrix.invertM(inv, 0, view, 0)
        val bob = kotlin.math.abs(sin(walkPhase)) * 0.012f * (if (run) 1.6f else 1f)
        val breath = sin(clock * 3f) * 0.006f
        val u = if (punchT > 0f) (1f - punchT / punchDur).coerceIn(0f, 1f) else -1f
        for (hand in intArrayOf(-1, 1)) {
            val left = hand < 0
            fistPos(left, punchKind, u, fpPos)
            val k = 1f / 0.8f   // vmArm works in unscaled gun space (x 0.8)
            vmArm(inv, fpPos[0] * k, (fpPos[1] + bob + breath) * k, fpPos[2] * k, hand * 0.40f * k, -0.62f * k, 0.10f * k, 0.4f)
        }
    }

    // ---- first-person hands + reload (motion: CC-BY pack, see ReloadAnim.kt) ----
    private val rlRig = ReloadAnim.Rig(); private var rlRigId = ""
    private val mmCam = FloatArray(16)

    /** a lit box in the first-person frame; centre (x,y,z) is in unscaled gun space (the gun model is drawn at 0.8), size is final */
    private fun vmBox(frame: FloatArray, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float) {
        System.arraycopy(frame, 0, mm, 0, 16)
        Matrix.translateM(mm, 0, x * 0.8f, y * 0.8f, z * 0.8f); Matrix.scaleM(mm, 0, sx, sy, sz)
        setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, r, g, b)
        GL.glBindVertexArray(cube!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    /** gloved hand at (hx,hy,hz) with a jacket sleeve that runs back to the anchor (ax,ay,az) below the screen edge */
    /** real hand: palm + 4 fingers (3 joints + nail) + thumb from HandRig, drawn as skin-coloured boxes round the gun */
    private fun vmHand(frame: FloatArray, hx: Float, hy: Float, hz: Float, shape: FloatArray) {
        GL.glBindVertexArray(cube!!.vao); GL.glUniform1i(uMode, 2)
        var o = 0
        while (o + HandRig.N <= shape.size) {
            val x0 = (hx + shape[o]) * 0.8f; val y0 = (hy + shape[o + 1]) * 0.8f; val z0 = (hz + shape[o + 2]) * 0.8f
            val x1 = (hx + shape[o + 3]) * 0.8f; val y1 = (hy + shape[o + 4]) * 0.8f; val z1 = (hz + shape[o + 5]) * 0.8f
            val ddx = x1 - x0; val ddy = y1 - y0; val ddz = z1 - z0
            val len = sqrt(ddx * ddx + ddy * ddy + ddz * ddz).coerceAtLeast(0.004f)
            val yawD = Math.toDegrees(kotlin.math.atan2(ddx.toDouble(), ddz.toDouble())).toFloat()
            val pitD = -Math.toDegrees(kotlin.math.asin((ddy / len).toDouble().coerceIn(-1.0, 1.0))).toFloat()
            System.arraycopy(frame, 0, mm, 0, 16)
            Matrix.translateM(mm, 0, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f)
            Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f)
            Matrix.scaleM(mm, 0, shape[o + 6] * 0.8f, shape[o + 7] * 0.8f, len)
            setModel(mm)
            val col = when (shape[o + 8].toInt()) { 1 -> HandRig.SHADE; 2 -> HandRig.NAIL; else -> HandRig.SKIN }
            GL.glUniform3f(uColor, col[0], col[1], col[2])
            GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            o += HandRig.N
        }
    }

    private fun vmArm(frame: FloatArray, hx: Float, hy: Float, hz: Float, ax: Float, ay: Float, az: Float, closed: Float, shape: FloatArray? = null) {
        val dx = (ax - hx) * 0.8f; val dy = (ay - hy) * 0.8f; val dz = (az - hz) * 0.8f
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
        val yawD = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        val pitD = -Math.toDegrees(kotlin.math.asin((dy / len).toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        // sleeve / forearm
        System.arraycopy(frame, 0, mm, 0, 16)
        val wr = if (shape != null) 0.11f else 0f   // with real hands a bare wrist shows before the sleeve starts
        Matrix.translateM(mm, 0, hx * 0.8f + ux * (0.33f + wr), hy * 0.8f + uy * (0.33f + wr), hz * 0.8f + uz * (0.33f + wr))
        Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f); Matrix.scaleM(mm, 0, 0.085f, 0.08f, 0.62f)
        setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.30f, 0.24f, 0.17f)
        GL.glBindVertexArray(cube!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        if (shape != null) {
            System.arraycopy(frame, 0, mm, 0, 16)   // dark cuff + wrist
            Matrix.translateM(mm, 0, hx * 0.8f + ux * (wr + 0.012f), hy * 0.8f + uy * (wr + 0.012f), hz * 0.8f + uz * (wr + 0.012f))
            Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f); Matrix.scaleM(mm, 0, 0.092f, 0.087f, 0.03f)
            setModel(mm); GL.glUniform3f(uColor, 0.12f, 0.12f, 0.13f); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            System.arraycopy(frame, 0, mm, 0, 16)
            Matrix.translateM(mm, 0, hx * 0.8f + ux * (wr * 0.5f), hy * 0.8f + uy * (wr * 0.5f), hz * 0.8f + uz * (wr * 0.5f))
            Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f); Matrix.scaleM(mm, 0, 0.062f, 0.05f, wr + 0.05f)
            setModel(mm); GL.glUniform3f(uColor, HandRig.SKIN[0], HandRig.SKIN[1], HandRig.SKIN[2]); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
            vmHand(frame, hx, hy, hz, shape)
            return
        }
        // glove (palm) - a little narrower when the hand is closed around the magazine
        System.arraycopy(frame, 0, mm, 0, 16)
        Matrix.translateM(mm, 0, hx * 0.8f, hy * 0.8f, hz * 0.8f)
        Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f); Matrix.scaleM(mm, 0, 0.09f - 0.012f * closed, 0.075f - 0.01f * closed, 0.13f)
        setModel(mm); GL.glUniform1i(uMode, 2); GL.glUniform3f(uColor, 0.10f, 0.10f, 0.11f)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        // thumb
        System.arraycopy(frame, 0, mm, 0, 16)
        Matrix.translateM(mm, 0, hx * 0.8f, hy * 0.8f, hz * 0.8f)
        Matrix.rotateM(mm, 0, yawD, 0f, 1f, 0f); Matrix.rotateM(mm, 0, pitD, 1f, 0f, 0f)
        Matrix.translateM(mm, 0, 0.04f, 0.035f, -0.045f); Matrix.scaleM(mm, 0, 0.03f, 0.03f, 0.08f)
        setModel(mm); GL.glUniform3f(uColor, 0.12f, 0.12f, 0.13f)
        GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
    }

    private fun drawViewmodel() {
        if (fists) { drawFists(); return }
        val g = guns[gi]; val gm = gunGl[g.id] ?: return
        GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
        Matrix.invertM(inv, 0, view, 0)
        System.arraycopy(inv, 0, mm, 0, 16)
        val bob = sin(walkPhase) * 0.012f * (if (run) 1.6f else 1f)
        if (adsAmount > 0.9f && scopeSel >= 2) return // scope overlay hides the gun
        val a = adsAmount
        if (rlRigId != g.id) { ReloadAnim.rigFor(g.id, gm.second.zmin, gm.second.my, gm.second.len, rlRig); rlRigId = g.id }
        val reloading = reloadLeft > 0f && reloadProgress >= 0f
        // reference pack: breathing sway + hand drift (a third as strong while aiming), added on top of the walk bob / recoil / reload motion
        Sway.calc(clock, 0f, if (aiming) 0.4f else 1f, swayTmp)
        val sbr = swayTmp[0]; val ssx = swayTmp[1]; val ssy = swayTmp[2]
        val hx = 0.21f + bob * 0.5f; val hyy = -0.2f + kotlin.math.abs(bob) + sbr * 0.5f; val hz = -0.62f + kick * 0.07f
        val ax = 0f; val ay = -(gm.second.my + 0.06f) * 0.8f + sbr * 0.5f; val az = -0.5f + kick * 0.03f
        Matrix.translateM(mm, 0, hx + (ax - hx) * a, hyy + (ay - hyy) * a, hz + (az - hz) * a)
        Matrix.rotateM(mm, 0, (kick * 3.2f) * (1f - 0.6f * a) + degF(sbr + ssy), 1f, 0f, 0f)
        Matrix.rotateM(mm, 0, degF(ssx), 0f, 1f, 0f)
        // reload: gun motion from the baked clip; hands and magazine are drawn in the camera-aligned frame (mmCam) so they follow the clip exactly
        var magOn = false
        if (reloading) {
            val u = reloadProgress.coerceIn(0f, 1f); ReloadAnim.sample(u, rlRig, rlTmp)
            Matrix.translateM(mm, 0, rlTmp[0], rlTmp[1], rlTmp[2])
            System.arraycopy(mm, 0, mmCam, 0, 16)
            Matrix.rotateM(mm, 0, rlTmp[5], 0f, 0f, 1f); Matrix.rotateM(mm, 0, rlTmp[4], 0f, 1f, 0f); Matrix.rotateM(mm, 0, rlTmp[3], 1f, 0f, 0f)
            val dm = kotlin.math.hypot(rlTmp[6] - rlRig.magX, kotlin.math.hypot(rlTmp[7] - rlRig.magY, rlTmp[8] - rlRig.magZ)) * 0.8f
            magOn = dm > 0.085f
        }
        System.arraycopy(mm, 0, mmRl, 0, 16)
        Matrix.scaleM(mm, 0, 0.8f, 0.8f, 0.8f)
        setModel(mm)
        if (gm.second.textured) { GL.glUniform1i(uMode, 0); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, tex[g.id] ?: return) }
        else GL.glUniform1i(uMode, 1)
        GL.glBindVertexArray(gm.first.vao); GL.glDrawElements(GL.GL_TRIANGLES, gm.first.count, GL.GL_UNSIGNED_INT, 0)
        if (magOn) { // the magazine that is pulled out / pushed back in (0.03 x H x 0.05 box, dark metal)
            if (rlRig.dirY > 0f) vmBox(mmCam, rlTmp[6], rlTmp[7], rlTmp[8], 0.03f * 0.8f, 0.04f, 0.18f, 0.165f, 0.176f, 0.192f)
            else vmBox(mmCam, rlTmp[6], rlTmp[7], rlTmp[8], 0.034f * 0.8f, rlRig.magH * 0.8f, 0.05f * 0.8f, 0.165f, 0.176f, 0.192f)
        }
        // hands (hidden while aiming down the sights, except during a reload)
        if (reloading || a < 0.6f) {
            val fr = if (reloading) mmCam else mmRl
            val lx = if (reloading) rlTmp[10] else rlRig.restX; val ly = if (reloading) rlTmp[11] else rlRig.restY; val lz = if (reloading) rlTmp[12] else rlRig.restZ
            val rx = if (reloading) rlTmp[13] else rlRig.gripX; val ry = if (reloading) rlTmp[14] else rlRig.gripY; val rz = if (reloading) rlTmp[15] else rlRig.gripZ
            val holdsMag = reloading && magOn
            vmArm(fr, lx, ly, lz, -0.35f, -0.95f, 0.90f, if (holdsMag) 1f else 0f, if (holdsMag) HandRig.LEFT_MAG else HandRig.LEFT)
            vmArm(fr, rx, ry, rz, 0.50f, -0.62f, 0.85f, 0f, HandRig.RIGHT)
        }
        if (flash > 0f) { // muzzle flash: bright unlit block at the barrel tip
            System.arraycopy(mmRl, 0, mm, 0, 16); Matrix.scaleM(mm, 0, 0.8f, 0.8f, 0.8f)
            Matrix.translateM(mm, 0, 0f, gm.second.my, gm.second.zmin - 0.07f)
            Matrix.scaleM(mm, 0, 0.12f, 0.12f, 0.16f)
            setModel(mm)
            GL.glUniform1i(uMode, 3); GL.glUniform3f(uColor, 1f, 0.85f, 0.4f)
            GL.glBindVertexArray(cube!!.vao); GL.glDrawElements(GL.GL_TRIANGLES, 36, GL.GL_UNSIGNED_INT, 0)
        }
    }
}
