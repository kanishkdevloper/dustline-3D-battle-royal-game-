package com.dustline.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Dustline island (original map made by tools/make_island.py), parsed from assets/maps/dustlineMap.bin:
 *   [u32 jsonLen][json][pad to 4][blobs]   tile: p=float3 pos, n=float3 normal, u=float2 uv / c=float3 colour, i=uint16 indices
 * x/z are multiplied by MAPK (4/3) exactly like the web version.
 */
class MapData(ctx: Context, progress: (Float) -> Unit) {
    class Group(val mat: String, val verts: FloatArray, val indices: IntArray) {
        /** First vertex index that was cloned / added by road-clear or the replacement houses (triangles using it are "moved" geometry; the satellite map redraws them). */
        @JvmField var movedFrom = Int.MAX_VALUE
    }
    class Box(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float, val minZ: Float, val maxZ: Float) { @JvmField var stamp = 0 }

    companion object {
        const val MAPK = 4f / 3f
        const val STRIDE = 11 // pos3 nor3 uv2 col3
        /** Water depth (m) of the open sea outside the baked map: deep enough to swim, same as the sea around the island (~7.7 m). */
        const val OPEN_SEA_DEPTH = 7.7f
        /** Only limit of the open sea: 3 km from the island centre (a safety stop so nobody swims off to infinity). Everything closer is open water, no wall. */
        const val FAR_LIMIT = 3000f

        /** Slab test, no allocations. Returns entry distance (>=0) or -1. */
        fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                   x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, maxD: Float): Float {
            var t0 = 0f; var t1 = maxD
            // X
            if (kotlin.math.abs(dx) < 1e-8f) { if (ox < x0 || ox > x1) return -1f }
            else { var a = (x0 - ox) / dx; var b = (x1 - ox) / dx; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            // Y
            if (kotlin.math.abs(dy) < 1e-8f) { if (oy < y0 || oy > y1) return -1f }
            else { var a = (y0 - oy) / dy; var b = (y1 - oy) / dy; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            // Z
            if (kotlin.math.abs(dz) < 1e-8f) { if (oz < z0 || oz > z1) return -1f }
            else { var a = (z0 - oz) / dz; var b = (z1 - oz) / dz; if (a > b) { val t = a; a = b; b = t }; if (a > t0) t0 = a; if (b < t1) t1 = b; if (t0 > t1) return -1f }
            return t0
        }
    }

    val groups = ArrayList<Group>()
    val blockers = ArrayList<Box>()
    val seaColor: Int
    val n: Int
    val cell: Float
    val x0: Float
    private val gh: FloatArray
    private val walk: ByteArray
    val spawn = FloatArray(3)
    private var roadMask = ByteArray(0)
    /** Green / flat / open ground mask of the HTML (H.grass): trees and bushes only grow here. */
    private var grassBits = ByteArray(0)
    private var grN = 0
    private var grCell = 1f
    /** Collision boxes in a 24 m grid (BERM.bgrid of the HTML): every box sits in all cells within 4 m of it. Includes tree trunks and boulders. */
    private val BC = 24f
    private var bgN = 0
    private var bgrid: Array<ArrayList<Box>?> = arrayOfNulls(0)
    private var stampNow = 0
    var treeBoxes = 0
    /** Water level grid (wl of the HTML): -999 = dry, else the surface height. Sea cells (ground <= 0) = 0, lakes / rivers from the water meshes. */
    private var wl = FloatArray(0)
    /** Trees / bushes / rocks of the ULTRA NATURE pack (Nature.kt), ready to be uploaded by NatureGl. */
    var nature: Nature.Result? = null
    /** The enterable houses of the island (`assets/maps/extra_houses.bin`, made by tools/make_island.py): x, z, width, depth, ground y. */
    val extraHouses = ArrayList<FloatArray>()
    /** Footprints of every house a player can walk into (DX PATCH `dxStockHouses` of the HTML): cx, cz, width, depth. Loot is only placed in / right next to these. */
    val houseSpots = ArrayList<FloatArray>()
    /** Photo rectangles (x0, x1, z0, z1 in world metres) of houses removed by [clearRoadZone]; the satellite picture and the terrain texture are wiped there. */
    val removedHouseRects = ArrayList<FloatArray>()
    /** PHASE 72: distance to the real road (edge strips + black asphalt of roadpaint.bin); trees, bushes, rocks, houses, loot keep away from it. */
    lateinit var roadZone: RoadZone

    init {
        val bytes = ctx.assets.open("maps/dustlineMap.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(0)
        val h = JSONObject(String(bytes, 4, jl, Charsets.UTF_8))
        val base = 4 + jl + ((4 - (4 + jl) % 4) % 4)
        progress(0.05f)

        n = h.getInt("N"); cell = h.getDouble("cell").toFloat() * MAPK; x0 = h.getDouble("x0").toFloat() * MAPK
        val hs = h.getDouble("hs").toFloat()
        h.optJSONObject("grass")?.let { gro ->
            grN = gro.getInt("n"); grCell = gro.getDouble("cell").toFloat() * MAPK
            val nb = (grN * grN + 7) / 8; val off = base + gro.getInt("off")
            grassBits = ByteArray(nb); System.arraycopy(bytes, off, grassBits, 0, nb)
        }
        val ho = base + h.getInt("heights")
        gh = FloatArray(n * n) { bb.getShort(ho + it * 2) / hs }
        // arch viaduct: lower the baked ridge under the open arches (HTML MDL.reshape) BEFORE the walk grid is computed
        Viaduct.load(ctx); Viaduct.reshape(gh, n, cell, x0)
        val slopeMax = h.getDouble("slopeMax").toFloat()
        walk = ByteArray(n * n)
        for (k in 0 until n) for (i in 0 until n) {
            val i0 = max(i - 1, 0); val i1 = min(i + 1, n - 1); val k0 = max(k - 1, 0); val k1 = min(k + 1, n - 1)
            val dx = (gh[k * n + i1] - gh[k * n + i0]) / ((i1 - i0) * h.getDouble("cell").toFloat())
            val dz = (gh[k1 * n + i] - gh[k0 * n + i]) / ((k1 - k0) * h.getDouble("cell").toFloat())
            walk[k * n + i] = if (gh[k * n + i] > 0f && hypot(dx, dz) < slopeMax) 1 else 0
        }
        roadMask = ByteArray(n * n)
        progress(0.15f)

        val gs = h.getJSONArray("groups")
        for (gi in 0 until gs.length()) {
            val g = gs.getJSONObject(gi)
            val tiles = g.getJSONArray("tiles")
            var nv = 0; var ni = 0
            for (t in 0 until tiles.length()) { nv += tiles.getJSONObject(t).getInt("nv"); ni += tiles.getJSONObject(t).getInt("ni") }
            val verts = FloatArray(nv * STRIDE); val idx = IntArray(ni)
            var vo = 0; var io = 0
            val isRoad = g.getString("mat") == "road"
            for (t in 0 until tiles.length()) {
                val T = tiles.getJSONObject(t); val c = T.getInt("nv")
                val hasUv = T.has("u"); val hasCol = T.has("c")
                for (v in 0 until c) {
                    val pb = base + T.getInt("p") + v * 12; val nb = base + T.getInt("n") + v * 12
                    val o = (vo + v) * STRIDE
                    val px = bb.getFloat(pb) * MAPK; val py = bb.getFloat(pb + 4) + (if (isRoad) 0.09f else 0f); val pz = bb.getFloat(pb + 8) * MAPK
                    verts[o] = px; verts[o + 1] = py; verts[o + 2] = pz
                    val nx = bb.getFloat(nb) / MAPK; val ny = bb.getFloat(nb + 4); val nz = bb.getFloat(nb + 8) / MAPK
                    val l = hypot(hypot(nx, ny), nz).let { if (it == 0f) 1f else it }
                    verts[o + 3] = nx / l; verts[o + 4] = ny / l; verts[o + 5] = nz / l
                    if (hasUv) { val ub = base + T.getInt("u") + v * 8; verts[o + 6] = bb.getFloat(ub); verts[o + 7] = bb.getFloat(ub + 4) }
                    if (hasCol) { val cb = base + T.getInt("c") + v * 12; verts[o + 8] = bb.getFloat(cb); verts[o + 9] = bb.getFloat(cb + 4); verts[o + 10] = bb.getFloat(cb + 8) }
                }
                val ib = base + T.getInt("i"); val cnt = T.getInt("ni")
                for (q in 0 until cnt) idx[io + q] = (bb.getShort(ib + q * 2).toInt() and 0xFFFF) + vo
                vo += c; io += cnt
            }
            groups.add(Group(g.getString("mat"), verts, idx))
            if (isRoad) rasterRoad(verts, idx)
            progress(0.15f + 0.7f * (gi + 1) / gs.length())
        }

        val bl = h.getJSONArray("blockers")
        for (i in 0 until bl.length()) {
            val r = bl.getJSONArray(i)
            var a = r.getDouble(0).toFloat(); var b = r.getDouble(1).toFloat()
            var c = r.getDouble(2).toFloat(); var d = r.getDouble(3).toFloat()
            var e = r.getDouble(4).toFloat(); var f = r.getDouble(5).toFloat()
            if (a > b) { val t = a; a = b; b = t }; if (c > d) { val t = c; c = d; d = t }; if (e > f) { val t = e; e = f; f = t }
            if (b - a < 0.3f || d - c < 0.3f || f - e < 0.05f) continue
            blockers.add(Box(a * MAPK, b * MAPK, e, f, c * MAPK, d * MAPK))
        }
        roadZone = try { RoadZone(this, ctx) } catch (e: Throwable) { RoadZone(this, null) }   // PHASE 72: a damaged paint file = road mesh only
        try { loadExtraHouses(ctx) } catch (e: Throwable) { extraHouses.clear() }   // a damaged file just means no extra houses
        try { buildHouseSpots(h) } catch (e: Throwable) { houseSpots.clear() }      // no list = loot falls back to the old open-ground spots
        seaColor = android.graphics.Color.parseColor("#1166CC")   // open sea: blue (the map file said dark teal #0d384f)
        try { dropInvisibleWaterBoxes() } catch (e: Throwable) { /* keep every box */ }
        try { openClosedHouses() } catch (e: Throwable) { /* keep the walls as they are */ }   // houses whose inside cannot be reached get a door   // invisible walls standing in the sea / on the shore
        try { clearRoadZone() } catch (e: Throwable) { /* keep the map as it is */ }   // PHASE 72: nothing stands on a road
        buildGrid()
        try { buildWater() } catch (e: Throwable) { wl = FloatArray(0) }   // no grid = no swimming, the rest still works
        progress(0.88f)
        // ULTRA NATURE (pines, oaks, palms, bushes, boulders, pebbles): placed before the spawn point is chosen so nobody spawns inside a tree
        try { nature = Nature.place(this) } catch (e: Throwable) { nature = null }

        // spawn: nearest walkable land cell to map centre that is not inside a blocker
        var best = Float.MAX_VALUE; var bx = 0f; var bz = 0f
        for (k in 0 until n step 2) for (i in 0 until n step 2) {
            if (walk[k * n + i].toInt() != 1) continue
            val x = x0 + i * cell; val z = x0 + k * cell
            val d = hypot(x, z)
            if (d < best && !blocked(x, z, 0.6f, gh[k * n + i])) { best = d; bx = x; bz = z }
        }
        spawn[0] = bx; spawn[1] = ground(bx, bz); spawn[2] = bz
        progress(1f)
    }

    /**
     * extra_houses.bin (little endian): int nVerts, nTris, nBoxes, nHouses; float[nVerts * 11] (pos3 nor3 uv2 col3, already world space);
     * int[nTris * 3]; float[nBoxes * 6] = minX, maxX, minY, maxY, minZ, maxZ; float[nHouses * 5] = x, z, w, d, groundY.
     * Mesh = the vertex-coloured houses of the island (walls with door and window openings, roof).
     */
    private fun loadExtraHouses(ctx: Context) {
        val bytes = ctx.assets.open("maps/extra_houses.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // header counts TRIANGLES in slot 1 (file size check: 16 + nv*44 + tri*12 + nb*24 + nh*20), so there are 3 indices per entry
        val nv = bb.getInt(0); val ni = bb.getInt(4) * 3; val nb = bb.getInt(8); val nh = bb.getInt(12)
        var o = 16
        val verts = FloatArray(nv * STRIDE)
        for (i in verts.indices) verts[i] = bb.getFloat(o + i * 4)
        o += nv * STRIDE * 4
        val idx = IntArray(ni)
        for (i in 0 until ni) idx[i] = bb.getInt(o + i * 4)
        o += ni * 4
        val newBoxes = ArrayList<Box>(nb)
        for (i in 0 until nb) {
            val q = o + i * 24
            newBoxes.add(Box(bb.getFloat(q), bb.getFloat(q + 4), bb.getFloat(q + 8), bb.getFloat(q + 12), bb.getFloat(q + 16), bb.getFloat(q + 20)))
        }
        o += nb * 24
        val newHouses = ArrayList<FloatArray>(nh)
        for (i in 0 until nh) {
            val q = o + i * 20
            newHouses.add(floatArrayOf(bb.getFloat(q), bb.getFloat(q + 4), bb.getFloat(q + 8), bb.getFloat(q + 12), bb.getFloat(q + 16)))
        }
        // everything parsed fine: only now touch the map (all or nothing)
        blockers.addAll(newBoxes)
        extraHouses.addAll(newHouses)
        val eg = Group("house", verts, idx); eg.movedFrom = 0
        groups.add(eg)
    }

    fun ground(x: Float, z: Float): Float {
        var fx = (x - x0) / cell; var fz = (z - x0) / cell
        fx = fx.coerceIn(0f, n - 1.001f); fz = fz.coerceIn(0f, n - 1.001f)
        val i = fx.toInt(); val k = fz.toInt(); val tx = fx - i; val tz = fz - k; val a = k * n + i
        val g = gh[a] * (1 - tx) * (1 - tz) + gh[a + 1] * tx * (1 - tz) + gh[a + n] * (1 - tx) * tz + gh[a + n + 1] * tx * tz
        return g
    }

    fun walkAt(x: Float, z: Float): Boolean {
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        return i in 0 until n && k in 0 until n && walk[k * n + i].toInt() == 1
    }


    /**
     * Returns how many boxes were removed.
     * INVISIBLE WALL FIX: a collision box that stands in the sea / on the shore (ground <= 0.3 m under its centre) but has NO visible object
     * (house, pier, bridge, boat ... = any vertex of the solid groups) within 3 m of it blocks swimmers and walkers for nothing. Such boxes are removed.
     * Boxes on dry land are never touched (rocks / cliffs are part of the terrain mesh).
     */
    private fun dropInvisibleWaterBoxes(): Int {
        var cnt = 0
        for (g in groups) if (g.mat != "terrain" && g.mat != "road" && g.mat != "water") cnt += g.verts.size / STRIDE
        val pts = FloatArray(cnt * 3); var o = 0
        for (g in groups) {
            if (g.mat == "terrain" || g.mat == "road" || g.mat == "water") continue
            val v = g.verts; var q = 0
            while (q + STRIDE <= v.size) { pts[o++] = v[q]; pts[o++] = v[q + 1]; pts[o++] = v[q + 2]; q += STRIDE }
        }
        val it = blockers.iterator(); var removed = 0
        while (it.hasNext()) {
            val b = it.next()
            if (ground((b.minX + b.maxX) * 0.5f, (b.minZ + b.maxZ) * 0.5f) > 0.3f) continue
            var seen = false; var i = 0
            while (i < o) {
                val x = pts[i]; val y = pts[i + 1]; val z = pts[i + 2]; i += 3
                if (x > b.minX - 3f && x < b.maxX + 3f && z > b.minZ - 3f && z < b.maxZ + 3f && y > b.minY - 3f && y < b.maxY + 3f) { seen = true; break }
            }
            if (!seen) { it.remove(); removed++ }
        }
        return removed
    }

    /** Terrain part of free(): water and cliffs block ground movement. */
    fun terrainBlocked(x: Float, z: Float, r: Float, y: Float = 0f, swim: Boolean = false): Boolean {
        if (y > 1.2f && y > ground(x, z) + 1.5f) return false   // BERM.blocked(): a body high above the ground (viaduct deck, roofs) is not stopped by water / cliffs below
        if (swim && wl.isNotEmpty()) {   // swimMode: water cells are walkable too. The sea goes on past the baked map in every direction: open water, NO wall and NO distance limit
            return !((walkAt(x, z) || wetCell(x, z)) && (walkAt(x + r, z) || wetCell(x + r, z)) && (walkAt(x - r, z) || wetCell(x - r, z)) &&
                (walkAt(x, z + r) || wetCell(x, z + r)) && (walkAt(x, z - r) || wetCell(x, z - r)))
        }
        return !(walkAt(x, z) && walkAt(x + r, z) && walkAt(x - r, z) && walkAt(x, z + r) && walkAt(x, z - r))
    }

    // ---- water (sea + lakes / rivers): waterLevelAt / waterDepth / swimSpeed / swimFloor of the HTML
    private fun buildWater() {
        val w = FloatArray(n * n) { -999f }
        for (i in 0 until n * n) if (gh[i] <= 0f) w[i] = 0f
        for (g in groups) {
            if (g.mat != "water") continue
            val v = g.verts; val ix = g.indices; val S = STRIDE
            var q = 0
            while (q + 2 < ix.size) {
                val a = ix[q] * S; val b = ix[q + 1] * S; val c = ix[q + 2] * S; q += 3
                val ax = v[a]; val az = v[a + 2]; val bx = v[b]; val bz = v[b + 2]; val cx = v[c]; val cz = v[c + 2]
                val den = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
                if (kotlin.math.abs(den) < 1e-9f) continue
                val i0 = max(0, kotlin.math.floor((min(ax, min(bx, cx)) - x0) / cell).toInt()); val i1 = min(n - 1, kotlin.math.ceil((max(ax, max(bx, cx)) - x0) / cell).toInt())
                val k0 = max(0, kotlin.math.floor((min(az, min(bz, cz)) - x0) / cell).toInt()); val k1 = min(n - 1, kotlin.math.ceil((max(az, max(bz, cz)) - x0) / cell).toInt())
                for (k in k0..k1) for (i in i0..i1) {
                    val px = x0 + i * cell; val pz = x0 + k * cell
                    val l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / den
                    val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / den
                    val l3 = 1f - l1 - l2
                    if (l1 >= -0.02f && l2 >= -0.02f && l3 >= -0.02f) {
                        val y = l1 * v[a + 1] + l2 * v[b + 1] + l3 * v[c + 1]
                        if (y > w[k * n + i]) w[k * n + i] = y
                    }
                }
            }
        }
        wl = w
    }

    fun wetCell(x: Float, z: Float): Boolean {
        if (wl.isEmpty()) return false
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        if (i !in 0 until n || k !in 0 until n) return true   // outside the baked map = open sea
        return wl[k * n + i] > -900f
    }

    fun waterLevelAt(x: Float, z: Float): Float {
        if (wl.isEmpty()) return -999f
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        return if (i in 0 until n && k in 0 until n) wl[k * n + i] else 0f   // outside the baked map = open sea at level 0
    }

    fun waterDepth(x: Float, z: Float): Float {
        val w = waterLevelAt(x, z); if (w < -900f) return 0f
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        if (i !in 0 until n || k !in 0 until n) return OPEN_SEA_DEPTH   // outside the baked map: deep open sea, independent of the edge height values
        val d = w - ground(x, z); return if (d > 0f) d else 0f
    }

    fun swimSpeed(x: Float, z: Float): Float { val d = waterDepth(x, z); return if (d > 1.25f) 0.55f else if (d > 0.45f) 0.8f else 1f }

    /** Height the feet float at when the water is deeper than 1.25 m (the body sinks to the neck and bobs a little), else -1e9. */
    fun swimFloor(x: Float, z: Float, t: Float): Float {
        val d = waterDepth(x, z); if (d <= 1.25f) return -1e9f
        return waterLevelAt(x, z) - 1.25f + 0.05f * kotlin.math.sin(t * 1000f / 450f)
    }

    private fun blocked(x: Float, z: Float, r: Float, y: Float): Boolean {
        val cl = boxesAt(x, z) ?: return false
        for (qi in 0 until cl.size) {
            val b = cl[qi]
            if (x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r && y < b.maxY - 0.3f && y + 1.7f > b.minY) return true
        }
        return false
    }

    // ---- collision grid (BERM.addBlocker / BERM.bl of the HTML)
    private fun buildGrid() {
        bgN = Math.ceil((n * cell / BC).toDouble()).toInt() + 2
        bgrid = arrayOfNulls(bgN * bgN)
        for (b in blockers) gridInsert(b)
    }

    private fun gridInsert(b: Box) {
        val i0 = kotlin.math.floor((b.minX - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val i1 = kotlin.math.floor((b.maxX + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k0 = kotlin.math.floor((b.minZ - 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        val k1 = kotlin.math.floor((b.maxZ + 4f - x0) / BC).toInt().coerceIn(0, bgN - 1)
        for (k in k0..k1) for (i in i0..i1) {
            var l = bgrid[k * bgN + i]
            if (l == null) { l = ArrayList(); bgrid[k * bgN + i] = l }
            l.add(b)
        }
    }

    /** Every box within 4 m of (x, z) (the list of its grid cell), or null. */
    fun boxesAt(x: Float, z: Float): ArrayList<Box>? {
        val i = kotlin.math.floor((x - x0) / BC).toInt(); val k = kotlin.math.floor((z - x0) / BC).toInt()
        if (i < 0 || k < 0 || i >= bgN || k >= bgN) return null
        return bgrid[k * bgN + i]
    }

    /** A tree trunk / boulder: a collision box like BERM.addBlocker(). */
    fun addTreeBox(minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float) {
        gridInsert(Box(minX, maxX, minY, maxY, minZ, maxZ)); treeBoxes++
    }

    /** free() of the HTML (placement check): inside the island circle, terrain ok, no collision box, no viaduct pier / rail. */
    fun freeSpot(x: Float, z: Float, r: Float, y: Float): Boolean {
        val R = 790f * MAPK
        if (x * x + z * z > R * R) return false
        if (terrainBlocked(x, z, r, y)) return false
        val cl = boxesAt(x, z)
        if (cl != null) for (qi in 0 until cl.size) {
            val b = cl[qi]
            if (y + 0.08f < b.maxY && y + 1.7f > b.minY + 0.025f && x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r) return false
        }
        if (Viaduct.blocks(x, z, r, y)) return false
        return true
    }

    /** grassOK() of the HTML. */
    fun grassOK(x: Float, z: Float): Boolean {
        if (grN == 0) return false
        val i = kotlin.math.floor((x - x0) / grCell).toInt(); val k = kotlin.math.floor((z - x0) / grCell).toInt()
        if (i < 0 || k < 0 || i >= grN || k >= grN) return false
        val q = k * grN + i
        return ((grassBits[q shr 3].toInt() shr (7 - (q and 7))) and 1) == 1
    }

    /** Push (x,z) out of any collision box the body overlaps. */
    fun resolve(pos: FloatArray, r: Float) {
        val y = pos[1]
        val cl = boxesAt(pos[0], pos[2]) ?: return
        for (qi in 0 until cl.size) {
            val b = cl[qi]
            if (y >= b.maxY - 0.3f || y + 1.7f <= b.minY) continue
            val cx = pos[0].coerceIn(b.minX, b.maxX); val cz = pos[2].coerceIn(b.minZ, b.maxZ)
            var dx = pos[0] - cx; var dz = pos[2] - cz
            val d2 = dx * dx + dz * dz
            if (d2 >= r * r) continue
            if (d2 > 1e-8f) { val d = kotlin.math.sqrt(d2); pos[0] = cx + dx / d * r; pos[2] = cz + dz / d * r }
            else { // centre inside the box: leave through the nearest face
                val l = pos[0] - b.minX; val rr = b.maxX - pos[0]; val t = pos[2] - b.minZ; val bt = b.maxZ - pos[2]
                val m = min(min(l, rr), min(t, bt))
                when (m) { l -> pos[0] = b.minX - r; rr -> pos[0] = b.maxX + r; t -> pos[2] = b.minZ - r; else -> pos[2] = b.maxZ + r }
            }
        }
    }

    /** Distance along the ray to the first world hit (collision boxes or terrain), or -1. */
    fun rayWorld(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        var best = -1f
        run { val vt = Viaduct.ray(ox, oy, oz, dx, dy, dz, maxD); if (vt > 0f && (best < 0f || vt < best)) best = vt }   // viaduct deck / arch slab / piers stop bullets
        // collision boxes: walk the 24 m grid along the ray (a box is listed in every cell within 4 m, so a 6 m step never misses one)
        stampNow++
        var s = 0f
        while (true) {
            val cl = boxesAt(ox + dx * s, oz + dz * s)
            if (cl != null) for (qi in 0 until cl.size) {
                val b = cl[qi]
                if (b.stamp == stampNow) continue
                b.stamp = stampNow
                val t = rayBox(ox, oy, oz, dx, dy, dz, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, maxD)
                if (t > 0.05f && (best < 0f || t < best)) best = t
            }
            if (s >= maxD) break
            s = min(s + 6f, maxD)
            if (best >= 0f && s > best + 6f) break
        }
        // terrain: march, then refine
        var t = 0.5f; var prev = 0f
        val limit = if (best > 0f) best else maxD
        while (t < limit) {
            val y = oy + dy * t
            if (y <= ground(ox + dx * t, oz + dz * t)) {
                var lo = prev; var hi = t
                for (i in 0 until 6) { val m = (lo + hi) / 2; if (oy + dy * m <= ground(ox + dx * m, oz + dz * m)) hi = m else lo = m }
                best = hi; break
            }
            prev = t; t += 1.0f
        }
        return best
    }

    /** True when a body of radius r standing at (x,y,z) overlaps a collision box. */
    fun boxBlocked(x: Float, z: Float, r: Float, y: Float) = blocked(x, z, r, y) || Viaduct.blocks(x, z, r, y)

    /** Head bump under a viaduct arch (MDL.ceil): underside height inside (y0, y1] or 1e9. */
    fun ceilAt(x: Float, z: Float, y0: Float, y1: Float): Float = Viaduct.ceil(x, z, y0, y1)

    /** Standing height: viaduct deck when one is within step height, else the ground. */
    fun footY(x: Float, z: Float, y: Float): Float {
        val f = Viaduct.floor(x, z, y + 0.58f)
        return if (f > -1e8f) f else ground(x, z)
    }

    /** Marks every grid cell whose centre lies inside a road triangle (HTML roadMask). */
    private fun rasterRoad(v: FloatArray, idx: IntArray) {
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t] * STRIDE; val b = idx[t + 1] * STRIDE; val c = idx[t + 2] * STRIDE
            t += 3
            val ax = v[a]; val az = v[a + 2]; val bx = v[b]; val bz = v[b + 2]; val cx = v[c]; val cz = v[c + 2]
            val den = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz)
            if (kotlin.math.abs(den) < 1e-9f) continue
            val i0 = max(0, kotlin.math.floor((min(ax, min(bx, cx)) - x0) / cell).toInt())
            val i1 = min(n - 1, kotlin.math.ceil((max(ax, max(bx, cx)) - x0) / cell).toInt())
            val k0 = max(0, kotlin.math.floor((min(az, min(bz, cz)) - x0) / cell).toInt())
            val k1 = min(n - 1, kotlin.math.ceil((max(az, max(bz, cz)) - x0) / cell).toInt())
            for (k in k0..k1) for (i in i0..i1) {
                val px = x0 + i * cell; val pz = x0 + k * cell
                val l1 = ((bz - cz) * (px - cx) + (cx - bx) * (pz - cz)) / den
                val l2 = ((cz - az) * (px - cx) + (ax - cx) * (pz - cz)) / den
                val l3 = 1f - l1 - l2
                if (l1 >= -0.02f && l2 >= -0.02f && l3 >= -0.02f) roadMask[k * n + i] = 1
            }
        }
    }

    fun roadAt(x: Float, z: Float): Boolean {
        val i = Math.round((x - x0) / cell); val k = Math.round((z - x0) / cell)
        return i in 0 until n && k in 0 until n && roadMask[k * n + i].toInt() == 1
    }

    /**
     * Every house footprint of the map data (the island file has none) plus every enterable house of extra_houses.bin becomes a loot spot.
     */
    private fun buildHouseSpots(h: JSONObject) {
        val hs = h.getJSONArray("houses")
        for (i in 0 until hs.length()) {
            val r = hs.getJSONArray(i)
            // EVERY house counts (also the ones the HTML flagged with h[5] != 0): each one gets loot (see GameRenderer.stockEveryHouse)
            val cx = r.getDouble(0).toFloat() * MAPK; val cz = r.getDouble(1).toFloat() * MAPK
            val w = r.getDouble(2).toFloat() * MAPK; val d = r.getDouble(3).toFloat() * MAPK
            if (w < 5f || d < 5f || w > 130f || d > 130f) continue
            houseSpots.add(floatArrayOf(cx, cz, w, d, 1f))
        }
        for (e in extraHouses) houseSpots.add(floatArrayOf(e[0], e[1], e[2], e[3], 1f))
    }


    /**
     * PHASE 72 - ROADS EMPTY: after every earlier pass, whatever still stands on / right next to a road is removed (all or nothing, computed first, applied last):
     *  - a house whose footprint comes closer than 5 m to the road (its triangles, collision boxes and loot spots go, the satellite photo is wiped there),
     *  - any other house / block triangle with a corner closer than 2 m to the road,
     *  - any collision box with a corner or the middle closer than 1 m to the road.
     * The road mesh, the bridge / viaduct are never touched. Returns the number of removed houses.
     */
    private fun clearRoadZone(): Int {
        val rz = roadZone
        val gone = ArrayList<FloatArray>(); val keep = ArrayList<FloatArray>()
        for (hs in houseSpots) {
            if (rz.rectMin(hs[0] - hs[2] / 2f, hs[0] + hs[2] / 2f, hs[1] - hs[3] / 2f, hs[1] + hs[3] / 2f) < 5f) gone.add(hs) else keep.add(hs)
        }
        fun inGone(x: Float, z: Float, m: Float): Boolean {
            for (r in gone) if (kotlin.math.abs(x - r[0]) <= r[2] / 2f + m && kotlin.math.abs(z - r[1]) <= r[3] / 2f + m) return true
            return false
        }
        fun inKept(x: Float, z: Float): Boolean {
            for (r in keep) if (kotlin.math.abs(x - r[0]) <= r[2] / 2f && kotlin.math.abs(z - r[1]) <= r[3] / 2f) return true
            return false
        }
        val newGroups = ArrayList<Group>(groups.size)
        for (g in groups) {
            if (g.mat != "atlas" && g.mat != "house") { newGroups.add(g); continue }
            val v = g.verts; val ia = g.indices
            val out = IntArray(ia.size); var no = 0; var q = 0
            while (q + 2 < ia.size) {
                val a = ia[q] * STRIDE; val b = ia[q + 1] * STRIDE; val c = ia[q + 2] * STRIDE
                val x = (v[a] + v[b] + v[c]) / 3f; val z = (v[a + 2] + v[b + 2] + v[c + 2]) / 3f
                var drop = inGone(x, z, 1.3f) && !inKept(x, z)
                if (!drop && (rz.at(v[a], v[a + 2]) < 2f || rz.at(v[b], v[b + 2]) < 2f || rz.at(v[c], v[c + 2]) < 2f)) drop = true
                if (!drop) { out[no] = ia[q]; out[no + 1] = ia[q + 1]; out[no + 2] = ia[q + 2]; no += 3 }
                q += 3
            }
            val ng = Group(g.mat, v, out.copyOf(no)); ng.movedFrom = g.movedFrom
            newGroups.add(ng)
        }
        val keepBoxes = ArrayList<Box>(blockers.size)
        for (b in blockers) {
            val mx = (b.minX + b.maxX) / 2f; val mz = (b.minZ + b.maxZ) / 2f
            var drop = inGone(mx, mz, 1.0f) && !inKept(mx, mz)
            if (!drop && (rz.at(mx, mz) < 1f || rz.at(b.minX, b.minZ) < 1f || rz.at(b.minX, b.maxZ) < 1f || rz.at(b.maxX, b.minZ) < 1f || rz.at(b.maxX, b.maxZ) < 1f)) drop = true
            if (!drop) keepBoxes.add(b)
        }
        val rects = ArrayList<FloatArray>(gone.size)
        for (r in gone) rects.add(floatArrayOf(r[0] - r[2] / 2f - 1.5f, r[0] + r[2] / 2f + 1.5f, r[1] - r[3] / 2f - 1.5f, r[1] + r[3] / 2f + 1.5f))
        // everything worked: now change the map
        groups.clear(); groups.addAll(newGroups)
        blockers.clear(); blockers.addAll(keepBoxes)
        houseSpots.clear(); houseSpots.addAll(keep)
        removedHouseRects.addAll(rects)
        return gone.size
    }

    /**
     * DOOR FIX: a house whose inside cannot be reached on foot (a flood fill with the 0.42 m body finds no way from outside to the middle) has a wall
     * that is one solid collision box. The thin wall box whose removal opens the house is cut: a 2.6 m door gap (lintel above 2.6 m) at the point
     * nearest to the middle of the house. Returns how many houses were opened. A house that stays shut is marked hh[4] = 0 (loot only on its porch).
     */
    private fun openClosedHouses(): Int {
        var opened = 0
        val st = 0.5f; val r = 0.42f
        for (hs in houseSpots) {
            val cx = hs[0]; val cz = hs[1]; val w = hs[2]; val d = hs[3]
            val gy = ground(cx, cz) + 0.05f
            val pad = 4f
            val gx0 = cx - w / 2f - pad; val gz0 = cz - d / 2f - pad
            val nxg = kotlin.math.ceil((w + 2f * pad) / st).toInt(); val nzg = kotlin.math.ceil((d + 2f * pad) / st).toInt()
            if (nxg * nzg > 90000) continue
            val near = ArrayList<Box>()
            for (b in blockers) if (b.maxX > gx0 - 2f && b.minX < gx0 + nxg * st + 2f && b.maxZ > gz0 - 2f && b.minZ < gz0 + nzg * st + 2f && gy < b.maxY - 0.3f && gy + 1.7f > b.minY) near.add(b)
            if (near.isEmpty()) continue
            fun reach(skip: Box?): Boolean {
                val blk = BooleanArray(nxg * nzg)
                for (b in near) {
                    if (b === skip) continue
                    val i0 = max(0, kotlin.math.floor((b.minX - r - gx0) / st).toInt()); val i1 = min(nxg - 1, kotlin.math.ceil((b.maxX + r - gx0) / st).toInt())
                    val k0 = max(0, kotlin.math.floor((b.minZ - r - gz0) / st).toInt()); val k1 = min(nzg - 1, kotlin.math.ceil((b.maxZ + r - gz0) / st).toInt())
                    for (k in k0..k1) for (i in i0..i1) {
                        val px = gx0 + i * st; val pz = gz0 + k * st
                        if (px > b.minX - r && px < b.maxX + r && pz > b.minZ - r && pz < b.maxZ + r) blk[k * nxg + i] = true
                    }
                }
                val seen = BooleanArray(nxg * nzg); val q = IntArray(nxg * nzg); var head = 0; var tail = 0
                for (k in 0 until nzg) for (i in 0 until nxg) {
                    if (i == 0 || k == 0 || i == nxg - 1 || k == nzg - 1) { val c = k * nxg + i; if (!blk[c]) { seen[c] = true; q[tail++] = c } }
                }
                while (head < tail) {
                    val c = q[head++]; val ci = c % nxg; val ck = c / nxg
                    if (ci > 0 && !blk[c - 1] && !seen[c - 1]) { seen[c - 1] = true; q[tail++] = c - 1 }
                    if (ci < nxg - 1 && !blk[c + 1] && !seen[c + 1]) { seen[c + 1] = true; q[tail++] = c + 1 }
                    if (ck > 0 && !blk[c - nxg] && !seen[c - nxg]) { seen[c - nxg] = true; q[tail++] = c - nxg }
                    if (ck < nzg - 1 && !blk[c + nxg] && !seen[c + nxg]) { seen[c + nxg] = true; q[tail++] = c + nxg }
                }
                var tot = 0; var got = 0
                for (k in 0 until nzg) for (i in 0 until nxg) {
                    val c = k * nxg + i
                    if (kotlin.math.abs(gx0 + i * st - cx) < w / 2f - 1.2f && kotlin.math.abs(gz0 + k * st - cz) < d / 2f - 1.2f && !blk[c]) { tot++; if (seen[c]) got++ }
                }
                return tot == 0 || got * 2 >= tot
            }
            if (reach(null)) continue
            var best: Box? = null; var bestLen = 1e9f
            for (b in near) {
                val sx = b.maxX - b.minX; val sz = b.maxZ - b.minZ
                val len = max(sx, sz)
                if (min(sx, sz) > 2.6f || len < 3.2f || len >= bestLen) continue
                if (reach(b)) { best = b; bestLen = len }
            }
            val wall = best
            if (wall == null) { hs[4] = 0f; continue }
            val alongX = (wall.maxX - wall.minX) >= (wall.maxZ - wall.minZ)
            val lo = if (alongX) wall.minX else wall.minZ; val hi = if (alongX) wall.maxX else wall.maxZ
            val gap = 1.3f
            val mid = (if (alongX) cx else cz).coerceIn(lo + gap + 0.2f, hi - gap - 0.2f)
            val g0 = mid - gap; val g1 = mid + gap
            blockers.remove(wall)
            fun piece(a: Float, b2: Float, y0: Float) {
                if (b2 - a < 0.3f) return
                blockers.add(if (alongX) Box(a, b2, y0, wall.maxY, wall.minZ, wall.maxZ) else Box(wall.minX, wall.maxX, y0, wall.maxY, a, b2))
            }
            piece(lo, g0, wall.minY); piece(g1, hi, wall.minY)
            val lintel = max(wall.minY, gy + 2.6f)
            if (lintel < wall.maxY - 0.3f) piece(g0, g1, lintel)
            opened++
        }
        return opened
    }

    /**
     * One try of `dxStockHouses()`: 65 % a point INSIDE a house footprint, 35 % on the porch / yard strip 1.4-3 m outside one wall.
     * Returns null if the point is in water, inside a wall / blocker or the list is empty (the caller tries again).
     */
    fun houseSpotTry(rnd: kotlin.random.Random): FloatArray? {
        if (houseSpots.isEmpty()) return null
        return houseSpotAt(rnd, rnd.nextInt(houseSpots.size))
    }

    /** Same as houseSpotTry() but for one chosen house. A house whose inside cannot be reached (hh[4] == 0) only gets porch spots. */
    fun houseSpotAt(rnd: kotlin.random.Random, i: Int): FloatArray? {
        val hh = houseSpots[i]
        val cx = hh[0]; val cz = hh[1]; val w = hh[2]; val d = hh[3]
        val x: Float; val z: Float
        if (hh[4] > 0.5f && rnd.nextFloat() < .65f) {
            x = cx + (rnd.nextFloat() - .5f) * (w - 3.4f).coerceAtLeast(0.5f)
            z = cz + (rnd.nextFloat() - .5f) * (d - 3.4f).coerceAtLeast(0.5f)
        } else {
            val side = rnd.nextInt(4); val t = rnd.nextFloat() * 2f - 1f; val off = 1.4f + rnd.nextFloat() * 1.6f
            when (side) {
                0 -> { x = cx + t * w / 2f; z = cz + d / 2f + off }
                1 -> { x = cx + t * w / 2f; z = cz - d / 2f - off }
                2 -> { x = cx + w / 2f + off; z = cz + t * d / 2f }
                else -> { x = cx - w / 2f - off; z = cz + t * d / 2f }
            }
        }
        if (terrainBlocked(x, z, 0.6f)) return null
        if (roadZone.at(x, z) < 3f) return null   // PHASE 72: no loot on or right next to a road
        val g = ground(x, z)
        if (g < 1f) return null
        val y = footY(x, z, g + 0.5f)
        if (blocked(x, z, 0.7f, y + 0.05f)) return null
        return floatArrayOf(x, y, z)
    }

    /** Random walkable land point between minD..maxD metres from (cx,cz); null if none found. */
    fun randomLand(rnd: kotlin.random.Random, cx: Float, cz: Float, minD: Float, maxD: Float): FloatArray? {
        for (n in 0 until 300) {
            val a = rnd.nextFloat() * 6.2831855f; val d = minD + rnd.nextFloat() * (maxD - minD)
            val x = cx + kotlin.math.sin(a) * d; val z = cz - kotlin.math.cos(a) * d
            if (terrainBlocked(x, z, 0.6f)) continue
            if (waterDepth(x, z) > 0.05f) continue   // never pick a spot under a lake (bots landing / wandering, loot fallback, spawn)
            val y = ground(x, z)
            if (y < 1f || blocked(x, z, 0.6f, y)) continue
            return floatArrayOf(x, y, z)
        }
        return null
    }
}
