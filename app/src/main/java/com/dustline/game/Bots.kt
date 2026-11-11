package com.dustline.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Bot(val name: String, val shirt: Int, val gun: Gun) {
    var x = 0f; var y = 0f; var z = 0f
    var hp = 200f; var alive = false
    var yaw = 0f; var walk = 0f; var speed = 0f
    var cooldown = 1f; var think = 0f; var strafe = 1f
    var visible = false; var deadAt = -100f; var barTime = 0f
    var wanderX = 0f; var wanderZ = 0f; var wanderT = 0f
    val avoid = Random.nextFloat() * 6.28f
    var grenades = 1; var nadeCd = 4f + Random.nextFloat() * 6f
    // air drop: 0 in plane, 1 freefall, 2 parachute, 3 on the ground (normal AI)
    var air = 3; var jumpAt = 0f; var tx = 0f; var tz = 0f
    var vx = 0f; var vy = 0f; var vz = 0f; var dive = true; var chuteT = 0f
    var reported = false   // kill-feed entry already written
    // smart cover AI
    var coverX = 0f; var coverZ = 0f; var coverT = 0f; var coverCd = 0f; var hurtT = 0f; var lastHp = 200f
    // bot-vs-bot fights
    var foe: Bot? = null; var kills = 0
    // Phase 28: air visuals (skate stance / dive / canopy pose = descend() + dskAirPose() of the HTML), sway only after the first shot
    // (HTML: e._pr exists from the first shot on)
    val av = AirVis(); var fired = false
}

class Tracer(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float, var life: Float)

/**
 * Port of botUpdate()/botShoot() from the HTML (normal difficulty): picks the player as target, sees 53 m with
 * line of sight, strafes inside 17 m and backs off inside 8 m, walks 3.65 m/s (2.1 when fighting), fires every
 * 0.45-0.9 s with spread 0.028 * gun factor for 9 damage.
 */
class BotSystem(private val map: MapData, private val rnd: Random, count: Int) {
    @Volatile var noDamage = false   // warm-up: nobody gets hurt
    companion object {
        val NAMES = listOf("KESTREL", "MICA", "ROOK", "SABLE", "FLINT", "NOVA", "DUNE", "COBALT", "WREN", "EMBER", "ASH", "ECHO", "SLATE", "HAZE", "QUILL")
        val SHIRTS = intArrayOf(0xFF7A2F2F.toInt(), 0xFF2F5A7A.toInt(), 0xFF5A7A2F.toInt(), 0xFF7A5A2F.toInt(), 0xFF5A2F7A.toInt(), 0xFF2F7A6A.toInt())
        const val HP = 200f
        const val DAMAGE = 9f
        const val EYE = 1.35f
        const val BODY_H = 1.62f
        const val HEAD_Y = 1.38f
    }

    val bots = ArrayList<Bot>()
    val tracers = ArrayList<Tracer>()
    /** called when a bot fires (for the shot sound) */
    var onShot: ((Bot) -> Unit)? = null
    /** called when a bot kills another bot: (killer, victim, weapon name) - renderer writes the kill feed and drops loot */
    var onBotKill: ((Bot, Bot, String) -> Unit)? = null
    private val angles = floatArrayOf(0f, .5f, -.5f, 1f, -1f, 1.55f, -1.55f, 2.2f, -2.2f, 3.1416f)
    private val tmp = FloatArray(3)

    init {
        for (i in 0 until count) {
            val g = Weapons.ALL[rnd.nextInt(Weapons.ALL.size)]
            bots.add(Bot(NAMES[i % NAMES.size] + (if (i >= NAMES.size) "${i / NAMES.size + 1}" else ""), SHIRTS[i % SHIRTS.size], g))
        }
    }

    fun spawnAll(px: Float, pz: Float, clock: Float) { for (b in bots) respawn(b, px, pz, clock) }

    private fun respawn(b: Bot, px: Float, pz: Float, clock: Float) {
        var p: FloatArray? = null
        for (n in 0 until 40) { // spread over the whole play area, at least 70 m from the player
            val c = map.randomLand(rnd, 0f, 0f, 20f, 760f) ?: continue
            if (hypot(c[0] - px, c[2] - pz) > 70f) { p = c; break }
        }
        if (p == null) { b.alive = false; return }
        b.x = p[0]; b.y = p[1]; b.z = p[2]; b.hp = HP; b.alive = true; b.cooldown = 1f + rnd.nextFloat() * 1.5f
        b.think = 0f; b.visible = false; b.barTime = 0f; b.speed = 0f
    }

    private fun between(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun free(b: Bot, x: Float, z: Float) = !map.terrainBlocked(x, z, 0.45f, b.y) && !map.boxBlocked(x, z, 0.45f, b.y) &&
        (map.waterDepth(x, z) < 0.6f || map.waterDepth(b.x, b.z) >= 0.6f)   // bots do not wade into deep lake water (but are never trapped if they already stand in it)

    private fun hasLos(b: Bot, px: Float, py: Float, pz: Float): Boolean {
        val ox = b.x; val oy = b.y + 1.4f; val oz = b.z
        var dx = px - ox; var dy = py + 1.3f - oy; var dz = pz - oz
        val d = sqrt(dx * dx + dy * dy + dz * dz); if (d < 0.01f) return true
        dx /= d; dy /= d; dz /= d
        val t = map.rayWorld(ox, oy, oz, dx, dy, dz, d)
        return t < 0f || t > d - 0.5f
    }

    /** onHit(damage, botName) is called when a bot bullet hits the player. */
    fun update(dt: Float, clock: Float, px: Float, py: Float, pz: Float, playerAlive: Boolean, zone: Float, zx: Float, zz: Float, zoneDmg: Float, onHit: (Float, String) -> Unit, onThrow: (Bot, Float, Float, Float) -> Unit) {
        val it = tracers.iterator(); while (it.hasNext()) { val t = it.next(); t.life -= dt; if (t.life <= 0f) it.remove() }
        for (b in bots) {
            if (!b.alive) continue
            if (b.air != 3) continue
            b.nadeCd -= dt; b.cooldown -= dt; b.think -= dt; b.barTime = max(0f, b.barTime - dt); b.wanderT -= dt
            b.coverCd -= dt; b.hurtT = max(0f, b.hurtT - dt)
            val dx0 = px - b.x; val dz0 = pz - b.z; val dist = hypot(dx0, dz0)
            if (b.think <= 0f) {
                b.think = between(.4f, .85f)
                b.visible = playerAlive && dist < 53f && hasLos(b, px, py, pz)
                // bot-vs-bot: nearest visible enemy bot (closer than the player if the player is visible too)
                b.foe = null
                run {
                    var fd = if (b.visible) dist else 53f
                    for (o in bots) {
                        if (o === b || !o.alive || o.air != 3) continue
                        val dd = hypot(o.x - b.x, o.z - b.z)
                        if (dd < fd && hasLos(b, o.x, o.y, o.z)) { fd = dd; b.foe = o }
                    }
                }
            }
            val rad = hypot(b.x - zx, b.z - zz); val urgent = rad > zone - 5f
            if (rad > zone && zoneDmg > 0f) { b.hp -= zoneDmg; if (b.hp <= 0f) { b.alive = false; b.deadAt = clock; continue } }
            val chase = !urgent && playerAlive && dist < 140f
            var foe = b.foe
            if (foe != null && (!foe.alive || foe.air != 3 || hypot(foe.x - b.x, foe.z - b.z) > 60f)) { b.foe = null; foe = null }
            if (b.hp < b.lastHp - 0.01f) b.hurtT = 3f
            b.lastHp = b.hp
            run {
                if (b.coverT > 0f) b.coverT -= dt
                else if (chase && b.visible && b.hurtT > 0f && dist > 6f && b.coverCd <= 0f) { // smart cover: hurt -> run behind a wall, peek again later
                    val cv = findCover(b, px, py, pz)
                    if (cv != null) { b.coverX = cv[0]; b.coverZ = cv[1]; b.coverT = between(2.5f, 4.5f); b.coverCd = 9f } else b.coverCd = 2f
                }
            }
            var gx: Float; var gz: Float
            if (urgent) { gx = zx; gz = zz }
            else if (b.coverT > 0f) { gx = b.coverX; gz = b.coverZ }
            else if (foe != null) { gx = foe.x; gz = foe.z }
            else if (chase) { gx = px; gz = pz }
            else {
                if (b.wanderT <= 0f) {
                    b.wanderT = between(6f, 11f)
                    val w = map.randomLand(rnd, b.x, b.z, 25f, 70f); if (w != null) { b.wanderX = w[0]; b.wanderZ = w[2] } else { b.wanderX = b.x; b.wanderZ = b.z }
                }
                gx = b.wanderX; gz = b.wanderZ
            }
            var dx = gx - b.x; var dz = gz - b.z
            val ex = if (foe != null) foe.x else px; val ez = if (foe != null) foe.z else pz
            val edist = if (foe != null) hypot(foe.x - b.x, foe.z - b.z) else dist
            val engaged = foe != null || (chase && b.visible)
            if (engaged && !urgent && edist < 17f && b.coverT <= 0f) {
                val f = if (sin(clock * .65f + b.avoid) > 0) 1f else -1f
                val old = dx; dx = dz * f; dz = -old * f
                if (edist < 8f) { dx += (b.x - ex) * 1.5f; dz += (b.z - ez) * 1.5f }
            }
            val len = hypot(dx, dz)
            if (len > 0.5f) {
                dx /= len; dz /= len
                var best = -1e9f; var bx = 0f; var bz = 0f
                for (a in angles) {
                    val x = dx * cos(a) - dz * sin(a); val z = dx * sin(a) + dz * cos(a)
                    if (free(b, b.x + x * .75f, b.z + z * .75f)) {
                        val score = x * dx + z * dz - abs(a) * .035f
                        if (score > best) { best = score; bx = x; bz = z }
                    }
                }
                val sp = if (urgent || b.coverT > 0f) 4.7f else if (engaged && edist < 20f) 2.1f else 3.65f
                val nx = b.x + bx * sp * dt; val nz = b.z + bz * sp * dt
                if (free(b, nx, b.z)) b.x = nx
                if (free(b, b.x, nz)) b.z = nz
                tmp[0] = b.x; tmp[1] = b.y; tmp[2] = b.z; map.resolve(tmp, 0.42f); b.x = tmp[0]; b.z = tmp[2]
                b.speed = if (best > -1e8f) sp else 0f
            } else b.speed = 0f
            val g = map.footY(b.x, b.z, b.y); b.y += (g - b.y) * minOf(1f, dt * 14f)
            b.walk += Gait.omega(b.speed) * dt
            b.yaw = if (foe != null && !urgent && edist < 55f) atan2(b.x - ex, b.z - ez) else if (chase && dist < 50f) atan2(b.x - px, b.z - pz) else atan2(-dx, -dz)
            if (foe != null) {
                if (b.grenades > 0 && b.nadeCd <= 0f && edist > 10f && edist < 28f) { b.grenades--; b.nadeCd = between(6f, 12f); onThrow(b, foe.x, foe.y, foe.z) }
                if (b.cooldown <= 0f && edist < 64f) { b.cooldown = between(.45f, .9f); shootBot(b, foe, clock) }
            } else
            if (playerAlive && b.visible && b.grenades > 0 && b.nadeCd <= 0f && dist > 10f && dist < 28f) {
                b.grenades--; b.nadeCd = between(6f, 12f); onThrow(b, px, py, pz)
            }
            if (playerAlive && b.visible && b.cooldown <= 0f && dist < 64f) {
                b.cooldown = between(.45f, .9f)
                shoot(b, px, py, pz, onHit)
            }
        }
    }

    // ---------------------------------------------------------------- smart cover
    private fun blockedLine(ox: Float, oy: Float, oz: Float, tx: Float, ty: Float, tz: Float): Boolean {
        var dx = tx - ox; var dy = ty - oy; var dz = tz - oz
        val d = sqrt(dx * dx + dy * dy + dz * dz); if (d < 0.01f) return false
        dx /= d; dy /= d; dz /= d
        val t = map.rayWorld(ox, oy, oz, dx, dy, dz, d)
        return t >= 0f && t < d - 0.3f
    }

    /** A free spot 3-11 m away that the player cannot see (head and chest height both blocked), nearest first. */
    private fun findCover(b: Bot, px: Float, py: Float, pz: Float): FloatArray? {
        var best: FloatArray? = null; var bd = 1e9f
        for (n in 0 until 14) {
            val a = rnd.nextFloat() * 6.2831855f; val r = between(3f, 11f)
            val x = b.x + sin(a) * r; val z = b.z + cos(a) * r
            if (!free(b, x, z)) continue
            val y = map.footY(x, z, b.y)
            if (abs(y - b.y) > 1.2f) continue
            if (hypot(x - px, z - pz) < 5f) continue
            if (!blockedLine(px, py + 1.5f, pz, x, y + 1.4f, z)) continue
            if (!blockedLine(px, py + 1.5f, pz, x, y + 0.6f, z)) continue
            val d = hypot(x - b.x, z - b.z)
            if (d < bd) { bd = d; best = floatArrayOf(x, z) }
        }
        return best
    }

    /** Bot fires at another bot: same spread / damage as shoot(), blocked by walls, kill credit + loot via onBotKill. */
    private fun shootBot(b: Bot, o: Bot, clock: Float) {
        b.fired = true
        val spread = Prefs.botSpread() * max(.35f, b.gun.spread / .01f)
        val ox = b.x; val oy = b.y + EYE; val oz = b.z
        var dx = o.x - ox; var dy = o.y + 1.2f - oy; var dz = o.z - oz
        val d0 = sqrt(dx * dx + dy * dy + dz * dz); if (d0 < 0.01f) return
        dx /= d0; dy /= d0; dz /= d0
        dx += between(-spread, spread); dy += between(-spread, spread); dz += between(-spread, spread)
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        val tw = map.rayWorld(ox, oy, oz, dx, dy, dz, 64f)
        val tp = MapData.rayBox(ox, oy, oz, dx, dy, dz, o.x - .3f, o.x + .3f, o.y, o.y + BODY_H, o.z - .25f, o.z + .25f, 64f)
        var end = if (tw > 0f) tw else 40f
        if (tp >= 0f && (tw < 0f || tp < tw)) {
            end = tp
            if (!noDamage) o.hp -= Prefs.botDamage(); o.barTime = 3f
            if (o.hp <= 0f && o.alive) { o.alive = false; o.deadAt = clock; o.reported = true; b.kills++; onBotKill?.invoke(b, o, b.gun.name) }
        }
        tracers.add(Tracer(ox + dx * .6f, oy - .1f, oz + dz * .6f, ox + dx * end, oy + dy * end, oz + dz * end, .08f))
        onShot?.invoke(b)
    }

    private fun shoot(b: Bot, px: Float, py: Float, pz: Float, onHit: (Float, String) -> Unit, spreadK: Float = 1f) {
        b.fired = true
        val spread = Prefs.botSpread() * max(.35f, b.gun.spread / .01f) * spreadK
        val ox = b.x; val oy = b.y + EYE; val oz = b.z
        var dx = px - ox + 0f; var dy = py + 1.2f - oy; var dz = pz - oz
        val d0 = sqrt(dx * dx + dy * dy + dz * dz); dx /= d0; dy /= d0; dz /= d0
        dx += between(-spread, spread); dy += between(-spread, spread); dz += between(-spread, spread)
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        val tw = map.rayWorld(ox, oy, oz, dx, dy, dz, 64f)
        val tp = MapData.rayBox(ox, oy, oz, dx, dy, dz, px - .3f, px + .3f, py, py + 1.7f, pz - .3f, pz + .3f, 64f)
        var end = if (tw > 0f) tw else 40f
        if (tp >= 0f && (tw < 0f || tp < tw)) { end = tp; onHit(Prefs.botDamage(), b.name) }
        tracers.add(Tracer(ox + dx * .6f, oy - .1f, oz + dz * .6f, ox + dx * end, oy + dy * end, oz + dz * end, .08f))
        onShot?.invoke(b)
    }
}
