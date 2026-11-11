#!/usr/bin/env python3
"""
make_island.py - generates the ORIGINAL Dustline island from scratch (no third-party map data is read or used).

Writes (all inside app/src/main/assets):
  maps/dustlineMap.bin     terrain mesh, roads, lakes, height field, grass mask  (container of MapData.kt)
  maps/extra_houses.bin    enterable houses (mesh + collision boxes + footprints)            (MapData.loadExtraHouses)
  maps/roadpaint.bin       asphalt + dashed centre line raster                              (RoadPaint.kt)
  textures/dustlineTexT.jpg  ground picture (also the mini-map picture)                     (GameRenderer / MapImage)

Everything comes from the fixed SEED below, so the island is reproducible: python3 tools/make_island.py
Needs: numpy, scipy, Pillow.
"""
import os, sys, json, struct, zlib, math
import numpy as np
from scipy import ndimage as ndi
from scipy.spatial import cKDTree
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import dijkstra, minimum_spanning_tree
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
MAPK = 4.0 / 3.0
SEED = 20261109
rng = np.random.default_rng(SEED)

# ---- grids (file units, "fu"; the game multiplies x / z by MAPK = 4/3)
NC = 400; SC = 4.0; X0 = -800.0          # coarse grid = terrain mesh + grass mask
NF = 800; CF = 2.0                       # fine grid  = what MapData.kt reads (heights)
HS = 200                                 # height scale of the int16 height field
EXT = 783.71                             # texture extent (fu): u = 0.5 - x/(2*EXT), v = 0.5 + z/(2*EXT)
xs = X0 + SC * np.arange(NC)
XX, ZZ = np.meshgrid(xs, xs)             # arrays are [k (z), j (x)]


def sm01(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def noise(sigma, shape=(NC, NC)):
    a = ndi.gaussian_filter(rng.standard_normal(shape), sigma, mode="wrap")
    return a / a.std()


def bil(arr, x, z):
    """bilinear sample of a coarse-grid array at fu coordinates"""
    x = np.asarray(x, float); z = np.asarray(z, float)
    shp = np.broadcast(x, z).shape
    xb = np.broadcast_to(x, shp).ravel(); zb = np.broadcast_to(z, shp).ravel()
    r = ndi.map_coordinates(arr, [(zb - X0) / SC, (xb - X0) / SC], order=1, mode="nearest")
    return r.reshape(shp)


# =====================================================================================  1. island shape + heights
ang = np.arctan2(ZZ, XX); dist = np.hypot(XX, ZZ)
redge = 440 + 52 * np.cos(2 * ang + 0.7) + 42 * np.sin(3 * ang + 2.1) + 26 * np.cos(5 * ang + 0.3) + 12 * np.sin(7 * ang)
s_main = redge - dist
s_main -= 80 * np.exp(-(((XX - 40) ** 2 + (ZZ - 455) ** 2) / (2 * 70.0 ** 2)))      # bay in the south
ISL = (395.0, -330.0)
d2 = np.hypot(XX - ISL[0], ZZ - ISL[1])
s2 = 88 + 16 * np.cos(3 * np.arctan2(ZZ - ISL[1], XX - ISL[0])) - d2                  # small island north-east
S = np.maximum(s_main, s2) + 13 * noise(5)                                             # signed distance to the coast (fu, >0 = land)

t_b = sm01(S / 45.0)                    # beach ramp
inl = sm01(S / 120.0)                   # inland weight
hills = noise(9) * 0.55 + noise(4) * 0.25
hill_term = np.clip(hills * 0.6 + 0.35, 0, None) * 15.0 * inl
peaks = np.zeros_like(S)
for (px, pz, amp, sg) in [(-135, -165, 46, 78), (-150, -105, 22, 55), (195, 115, 30, 62), (-20, 210, 14, 70)]:
    peaks += amp * np.exp(-(((XX - px) ** 2 + (ZZ - pz) ** 2) / (2.0 * sg ** 2)))
peaks *= inl
H = 4.5 * t_b + hill_term + peaks
H = np.where(S > 0, H, -np.clip(-S * 0.09, 0, 8.0))

# ---- lakes: (cx, cz, radius fu)
LAKES = []
for (lx, lz, lr) in [(-262, 150, 56), (175, -118, 40)]:
    ring = [bil(H, lx + 1.25 * lr * math.cos(a), lz + 1.25 * lr * math.sin(a)) for a in np.linspace(0, 6.283, 16, endpoint=False)]
    level = max(4.5, float(np.mean(ring)) - 0.6)
    d = np.hypot(XX - lx, ZZ - lz)
    w = 1.0 - sm01((d - lr) / (0.8 * lr))
    prof = np.where(d < lr, level - 3.2 * (1 - (d / lr) ** 2), level + 2.5 * (d - lr) / (0.4 * lr))
    H = H * (1 - w) + prof * w
    LAKES.append((lx, lz, lr, level))
lake_d = np.full_like(H, 1e9)
for (lx, lz, lr, lv) in LAKES:
    lake_d = np.minimum(lake_d, np.hypot(XX - lx, ZZ - lz) - lr)

# =====================================================================================  2. towns (flat plateaus)
gy_, gx_ = np.gradient(H, SC)
SLOPE = np.hypot(gx_, gy_)
loc_std = np.sqrt(np.maximum(ndi.uniform_filter(H * H, 11) - ndi.uniform_filter(H, 11) ** 2, 0))
TOWN_GUESS = [(10, 25), (-300, -30), (225, 275), (-95, 305), (-250, -300), (85, -300), (330, 40), (-330, 255)]
TOWNS = []
for (gx, gz) in TOWN_GUESS:
    best = None
    for dz in range(-60, 61, 8):
        for dx in range(-60, 61, 8):
            x = gx + dx; z = gz + dz
            hh = float(bil(H, x, z)); ss = float(bil(S, x, z)); ld = float(bil(lake_d, x, z))
            if ss < 150 or hh < 3.5 or hh > 16 or ld < 70:
                continue
            sc = float(bil(loc_std, x, z)) + 0.004 * math.hypot(dx, dz)
            if best is None or sc < best[0]:
                best = (sc, x, z)
    if best is not None:
        TOWNS.append((best[1], best[2]))
print("towns:", len(TOWNS))
RT = 78.0
for (tx, tz) in TOWNS:
    d = np.hypot(XX - tx, ZZ - tz)
    core = d <= RT
    hp = float(H[core].mean())
    w = 1.0 - sm01((d - RT) / 45.0)
    H = H * (1 - w) + hp * w
# small outpost on the north-east island (3 houses, no road)
OUTPOST = (ISL[0] - 5, ISL[1] + 6)
d = np.hypot(XX - OUTPOST[0], YY := ZZ - OUTPOST[1]) if False else np.hypot(XX - OUTPOST[0], ZZ - OUTPOST[1])
hp = float(H[d <= 36].mean()); w = 1.0 - sm01((d - 36) / 25.0); H = H * (1 - w) + max(hp, 3.0) * w

gy_, gx_ = np.gradient(H, SC)
SLOPE = np.hypot(gx_, gy_)

# =====================================================================================  3. roads (least-cost paths on the coarse grid)
cost = 1.0 + 22.0 * SLOPE ** 1.3
cost = cost + np.where(H < 2.2, 25.0, 0.0) + np.where(H > 36, 15.0, 0.0)
cost = np.where(H < 0.9, 1e5, cost)
cost = np.where(lake_d < 22, 1e5, cost)
cost = np.where(S < 40, 1e5, cost)
idx = np.arange(NC * NC).reshape(NC, NC)


def build_graph(c):
    rows = []; cols = []; vals = []
    for (dk, dj) in [(0, 1), (1, 0), (1, 1), (1, -1)]:
        k0 = slice(0, NC - dk); k1 = slice(dk, NC)
        if dj >= 0:
            j0 = slice(0, NC - dj); j1 = slice(dj, NC)
        else:
            j0 = slice(-dj, NC); j1 = slice(0, NC + dj)
        a = idx[k0, j0].ravel(); b = idx[k1, j1].ravel()
        wgt = SC * math.hypot(dk, dj) * 0.5 * (c[k0, j0].ravel() + c[k1, j1].ravel())
        rows += [a, b]; cols += [b, a]; vals += [wgt, wgt]
    return coo_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(NC * NC, NC * NC)).tocsr()


def cell_of(x, z):
    return int(round((z - X0) / SC)), int(round((x - X0) / SC))


tid = [idx[cell_of(x, z)] for (x, z) in TOWNS]
G0 = build_graph(cost)
dm = np.zeros((len(TOWNS), len(TOWNS)))
for i, t in enumerate(tid):
    dd = dijkstra(G0, indices=t)
    for j, u in enumerate(tid):
        dm[i, j] = dd[u] + 1.0
mst = minimum_spanning_tree(coo_matrix(dm)).tocoo()
edges = sorted([(float(v), int(a), int(b)) for a, b, v in zip(mst.row, mst.col, mst.data)])
# two extra links so the roads form loops: the cheapest pairs that are not in the tree
inmst = {(min(a, b), max(a, b)) for _, a, b in edges}
extra = sorted([(dm[i, j], i, j) for i in range(len(TOWNS)) for j in range(i + 1, len(TOWNS)) if (i, j) not in inmst])
edges += [(float(v), int(a), int(b)) for v, a, b in extra[:2]]

road_mask = np.zeros((NC, NC), bool)
segments = []          # list of arrays of (x, z) fu
for (_, a, b) in edges:
    c2 = np.where(road_mask, cost * 0.25, cost)
    G = build_graph(c2)
    dd, pred = dijkstra(G, indices=tid[a], return_predecessors=True)
    node = tid[b]; path = []
    while node >= 0 and node != tid[a]:
        path.append(node); node = pred[node]
    path.append(tid[a]); path = path[::-1]
    pk = np.array([p // NC for p in path]); pj = np.array([p % NC for p in path])
    onr = road_mask[pk, pj]
    i = 0; n = len(path)
    while i < n:
        if onr[i] and i + 1 < n and onr[i + 1]:
            i += 1; continue
        # a new stretch starts at the last road cell before it (junction) and ends on the next road cell
        st = max(i - 1, 0) if onr[max(i - 1, 0)] or i == 0 else i
        if onr[i] and i + 1 < n and not onr[i + 1]:
            st = i
        j = i
        while j < n and not onr[j]:
            j += 1
        en = min(j, n - 1)
        if not onr[i] or st == i:
            pass
        st = i - 1 if (i > 0 and onr[i - 1]) else i
        seg = np.stack([X0 + SC * pj[st:en + 1], X0 + SC * pk[st:en + 1]], 1).astype(float)
        if len(seg) >= 3 or (len(seg) == 2 and not (onr[st] and onr[en])):
            segments.append(seg)
        i = max(j, i + 1)
    road_mask[pk, pj] = True

HW = 3.5 / MAPK            # road half width in fu (3.5 m world)
POLY = []                  # smoothed + resampled centre lines (fu), with arc length (m)
for seg in segments:
    if len(seg) < 3:
        continue
    p = seg.copy()
    sm = np.stack([ndi.gaussian_filter1d(p[:, 0], 1.6, mode="nearest"), ndi.gaussian_filter1d(p[:, 1], 1.6, mode="nearest")], 1)
    sm[0] = p[0]; sm[-1] = p[-1]
    seglen = np.hypot(*np.diff(sm, axis=0).T); cum = np.concatenate([[0], np.cumsum(seglen)])
    npts = max(int(cum[-1] / 3.0) + 1, 3)
    tt = np.linspace(0, cum[-1], npts)
    q = np.stack([np.interp(tt, cum, sm[:, 0]), np.interp(tt, cum, sm[:, 1])], 1)
    seglen = np.hypot(*np.diff(q, axis=0).T); arc = np.concatenate([[0], np.cumsum(seglen)]) * MAPK
    POLY.append((q, arc))
print("road segments:", len(POLY), " total length m:", int(sum(a[-1] for _, a in POLY)))
RS = np.concatenate([q for q, _ in POLY]); RS_T = cKDTree(RS)

# =====================================================================================  4. houses
HOUSE_TYPES = [(8, 10), (9, 9), (10, 12), (10, 12), (12, 14), (11, 16), (14, 18), (20, 13)]
WALL_COL = [(0.86, 0.82, 0.72), (0.78, 0.70, 0.58), (0.70, 0.76, 0.80), (0.82, 0.62, 0.50), (0.90, 0.88, 0.80), (0.62, 0.66, 0.58)]
ROOF_COL = [(0.55, 0.25, 0.18), (0.35, 0.36, 0.40), (0.28, 0.42, 0.30), (0.42, 0.30, 0.22), (0.20, 0.30, 0.45)]
HOUSES = []        # dict(cx, cz (fu), w, d (fu footprint), door ('+x'...), type)


def rect_road_dist(cx, cz, hw, hd):
    best = 1e9
    for i in RS_T.query_ball_point([cx, cz], hw + hd + 30):
        px, pz = RS[i]
        dx = max(abs(px - cx) - hw, 0); dz = max(abs(pz - cz) - hd, 0)
        best = min(best, math.hypot(dx, dz))
    return best


def try_place(cx, cz, tw, td):
    hw = tw / MAPK / 2; hd = td / MAPK / 2          # half sizes in fu (before rotation)
    # door side: toward the nearest road
    near = RS_T.query([cx, cz])
    door = "+z"
    if near[0] < 90:
        vx, vz = RS[near[1]][0] - cx, RS[near[1]][1] - cz
        door = ("+x" if vx > 0 else "-x") if abs(vx) > abs(vz) else ("+z" if vz > 0 else "-z")
    elif rng.random() < 0.5:
        door = ["+x", "-x", "+z", "-z"][rng.integers(0, 4)]
    if door in ("+x", "-x"):
        hw, hd = hd, hw
    if rect_road_dist(cx, cz, hw, hd) < 12.0:
        return False
    for h in HOUSES:
        if abs(h["cx"] - cx) < h["hw"] + hw + 3.5 and abs(h["cz"] - cz) < h["hd"] + hd + 3.5:
            return False
    pts = [(cx + a * hw, cz + b * hd) for a in (-1, 0, 1) for b in (-1, 0, 1)]
    hs = [float(bil(H, x, z)) for x, z in pts]
    if max(hs) - min(hs) > 0.9 or min(hs) < 2.5:
        return False
    for (lx, lz, lr, lv) in LAKES:
        if math.hypot(cx - lx, cz - lz) < lr * 1.5 + 12:
            return False
    if float(bil(S, cx, cz)) < 70:
        return False
    HOUSES.append(dict(cx=cx, cz=cz, hw=hw, hd=hd, door=door, tw=tw, td=td))
    return True


for (tx, tz) in TOWNS:
    cells = [(a, b) for a in range(-3, 4) for b in range(-3, 4)]
    rng.shuffle(cells)
    cnt = 0
    for (a, b) in cells:
        if cnt >= 12:
            break
        cx = tx + a * 27 + rng.uniform(-4, 4); cz = tz + b * 27 + rng.uniform(-4, 4)
        if math.hypot(cx - tx, cz - tz) > 84 or rng.random() < 0.18:
            continue
        tw, td = HOUSE_TYPES[rng.integers(0, len(HOUSE_TYPES))]
        if rng.random() < 0.5:
            tw, td = td, tw
        if try_place(cx, cz, tw, td):
            cnt += 1
for (ox, oz) in [(OUTPOST[0] - 12, OUTPOST[1]), (OUTPOST[0] + 14, OUTPOST[1] + 3), (OUTPOST[0] + 2, OUTPOST[1] - 14)]:
    tw, td = HOUSE_TYPES[rng.integers(0, 4)]
    try_place(ox, oz, tw, td)
print("houses:", len(HOUSES))

# =====================================================================================  5. flatten under houses (bilinear needs the grid points around them)
for h in HOUSES:
    d = np.hypot(np.maximum(np.abs(XX - h["cx"]) - h["hw"], 0), np.maximum(np.abs(ZZ - h["cz"]) - h["hd"], 0))
    core = d <= SC + 0.6
    hp = float(H[core].mean())
    w = 1.0 - sm01((d - (SC + 0.6)) / (2 * SC))
    H = H * (1 - w) + hp * w
    h["hp"] = hp

# =====================================================================================  6. carve the roads (last, so the road profile wins)
prof_pts = []
for q, arc in POLY:
    hh = bil(H, q[:, 0], q[:, 1])
    hh = ndi.gaussian_filter1d(hh, 5.0, mode="nearest")
    hh[0] = bil(H, q[0, 0], q[0, 1]) if False else hh[0]
    prof_pts.append(hh)
RS_H = np.concatenate(prof_pts)
dd, ii = RS_T.query(np.stack([XX.ravel(), ZZ.ravel()], 1))
dd = dd.reshape(NC, NC); tgt = RS_H[ii].reshape(NC, NC)
wr = 1.0 - sm01((dd - 4.5) / 3.5)
wr = np.where(dd <= 4.5, 1.0, wr)
H = H * (1 - wr) + tgt * wr
H = np.where(S <= 0, H, np.maximum(H, 0.02))        # land stays above sea level

# =====================================================================================  7. fine grid (what the game reads) + checks
fi = np.arange(NF) * CF
FX = (X0 + fi - X0) / SC
gh_f = ndi.map_coordinates(H, np.meshgrid(FX, FX, indexing="ij"), order=1, mode="nearest")   # [row = z, col = x] (the default 'xy' order transposed the whole height field)
gh_s = np.clip(np.round(gh_f * HS), -32768, 32767).astype("<i2")
gh = gh_s.astype(np.float32) / HS


def ground(x, z):
    x = np.asarray(x, float); z = np.asarray(z, float)
    shp = np.broadcast(x, z).shape
    fx = np.clip((np.broadcast_to(x, shp).ravel() - X0) / CF, 0, NF - 1.001); fz = np.clip((np.broadcast_to(z, shp).ravel() - X0) / CF, 0, NF - 1.001)
    return ndi.map_coordinates(gh, [fz, fx], order=1, mode="nearest").reshape(shp)


# =====================================================================================  8. terrain mesh (coarse grid, only where there is land / shallows)
def vnormals(Hc):
    gy, gx = np.gradient(Hc, SC)
    n = np.stack([-gx, np.ones_like(gx), -gy], -1)
    return n / np.linalg.norm(n, axis=-1, keepdims=True)


NRM = vnormals(H)


class Blob:
    def __init__(self):
        self.b = bytearray()

    def add(self, arr, align=4):
        while len(self.b) % align:
            self.b.append(0)
        off = len(self.b); self.b += arr.tobytes(); return off


blob = Blob()
groups = []
# ---- terrain tiles (80 x 80 cells)
use = np.zeros((NC - 1, NC - 1), bool)
mx = np.maximum.reduce([H[:-1, :-1], H[1:, :-1], H[:-1, 1:], H[1:, 1:]])
use = mx > -0.8
tiles = []
TS = 80
for tk in range(0, NC - 1, TS):
    for tj in range(0, NC - 1, TS):
        sub = use[tk:tk + TS, tj:tj + TS]
        if not sub.any():
            continue
        ks, js = np.nonzero(sub); ks += tk; js += tj
        vid = {}
        pos = []; nor = []; uv = []; ind = []
        def vtx(k, j):
            key = k * NC + j
            if key not in vid:
                vid[key] = len(pos)
                x = X0 + SC * j; z = X0 + SC * k
                pos.append((x, H[k, j], z)); nor.append(NRM[k, j]); uv.append((0.5 - x / (2 * EXT), 0.5 + z / (2 * EXT)))
            return vid[key]
        for k, j in zip(ks, js):
            a = vtx(k, j); b = vtx(k, j + 1); c = vtx(k + 1, j); d = vtx(k + 1, j + 1)
            ind += [a, c, b, b, c, d]
        tiles.append(dict(
            nv=len(pos), ni=len(ind),
            p=blob.add(np.array(pos, "<f4")), n=blob.add(np.array(nor, "<f4")), u=blob.add(np.array(uv, "<f4")), i=blob.add(np.array(ind, "<u2"))))
groups.append(dict(mat="terrain", tiles=tiles))
print("terrain tiles:", len(tiles), " verts:", sum(t["nv"] for t in tiles), " tris:", sum(t["ni"] for t in tiles) // 3)

# ---- roads: one ribbon per centre line, y = ground (the loader lifts it 9 cm)
rt = []
for q, arc in POLY:
    tang = np.gradient(q, axis=0); tang /= np.maximum(np.linalg.norm(tang, axis=1, keepdims=True), 1e-9)
    nrm = np.stack([-tang[:, 1], tang[:, 0]], 1)
    L = q + nrm * HW; R = q - nrm * HW
    pos = []
    for a in range(len(q)):
        pos.append((L[a, 0], float(ground(L[a, 0], L[a, 1])), L[a, 1]))
        pos.append((R[a, 0], float(ground(R[a, 0], R[a, 1])), R[a, 1]))
    ind = []
    for a in range(len(q) - 1):
        i0 = 2 * a; ind += [i0, i0 + 1, i0 + 2, i0 + 1, i0 + 3, i0 + 2]
    nor = np.tile(np.array([0, 1, 0], "<f4"), (len(pos), 1))
    rt.append(dict(nv=len(pos), ni=len(ind), p=blob.add(np.array(pos, "<f4")), n=blob.add(nor), i=blob.add(np.array(ind, "<u2"))))
groups.append(dict(mat="road", tiles=rt))

# ---- lakes
wt = []
for (lx, lz, lr, lv) in LAKES:
    seg = 40; rr = lr * 1.03
    pos = [(lx, lv, lz)] + [(lx + rr * math.cos(a), lv, lz + rr * math.sin(a)) for a in np.linspace(0, 2 * math.pi, seg, endpoint=False)]
    ind = []
    for a in range(seg):
        ind += [0, 1 + a, 1 + (a + 1) % seg]
    nor = np.tile(np.array([0, 1, 0], "<f4"), (len(pos), 1))
    wt.append(dict(nv=len(pos), ni=len(ind), p=blob.add(np.array(pos, "<f4")), n=blob.add(nor), i=blob.add(np.array(ind, "<u2"))))
groups.append(dict(mat="water", tiles=wt))

# ---- grass mask (vegetation zone): 400 x 400 cells of 4 fu
cx_ = (XX + SC / 2); cz_ = (ZZ + SC / 2)
hc = bil(H, cx_, cz_); sl = bil(SLOPE, cx_, cz_)
rd_, _ = RS_T.query(np.stack([cx_.ravel(), cz_.ravel()], 1)); rd_ = rd_.reshape(NC, NC)
grass = (hc > 4.2) & (hc < 46) & (sl < 0.62) & (rd_ > 5.5) & (bil(lake_d, cx_, cz_) > 8) & (noise(6) > -1.05)
for h in HOUSES:
    grass &= ~((np.abs(cx_ - h["cx"]) < h["hw"] + 3) & (np.abs(cz_ - h["cz"]) < h["hd"] + 3))
grass_bits = np.packbits(grass.ravel())
g_off = blob.add(grass_bits, 1)
h_off = blob.add(gh_s.ravel(), 2)

hdr = dict(S=11.0, cell=CF, N=NF, x0=X0, hs=HS, slopeMax=1.1, heights=h_off, blockers=[], houses=[],
           grass=dict(cell=SC, n=NC, off=g_off), sea="#0d384f", groups=groups, total=len(blob.b))
js = json.dumps(hdr, separators=(",", ":")).encode()
while (4 + len(js)) % 4:
    js += b" "
out = struct.pack("<I", len(js)) + js + bytes(blob.b)
os.makedirs(os.path.join(ASSETS, "maps"), exist_ok=True)
with open(os.path.join(ASSETS, "maps", "dustlineMap.bin"), "wb") as f:
    f.write(out)
print("dustlineMap.bin", len(out))

# =====================================================================================  9. houses mesh (world metres, axis aligned)
VERTS = []; TRIS = []; BOXES = []; HLIST = []


def addv(p, n, c):
    VERTS.append((p[0], p[1], p[2], n[0], n[1], n[2], 0.0, 0.0, c[0], c[1], c[2]))
    return len(VERTS) - 1


def quad(p, n, c):
    a, b, cc, d = [addv(x, n, c) for x in p]
    TRIS.append((a, b, cc)); TRIS.append((a, cc, d))


def tri(p, n, c):
    a, b, cc = [addv(x, n, c) for x in p]
    TRIS.append((a, b, cc))


def box_faces(x0, x1, y0, y1, z0, z1, c, inner=None, skip_bottom=True):
    ci = inner if inner else c
    quad([(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], (0, 1, 0), c)
    quad([(x0, y0, z0), (x0, y1, z0), (x0, y1, z1), (x0, y0, z1)], (-1, 0, 0), c)
    quad([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), c)
    quad([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), c)
    quad([(x0, y0, z1), (x0, y1, z1), (x1, y1, z1), (x1, y0, z1)], (0, 0, 1), c)


def wall_pieces(lo, hi, openings, H_):
    """openings: sorted (centre, width, y0, y1) along the wall axis -> list of (a, b, y0, y1) solid pieces"""
    pcs = []; cur = lo
    for (c, w, y0, y1) in openings:
        a = c - w / 2; b = c + w / 2
        if a > cur + 1e-4:
            pcs.append((cur, a, 0.0, H_))
        if y0 > 0:
            pcs.append((a, b, 0.0, y0))
        if y1 < H_:
            pcs.append((a, b, y1, H_))
        cur = b
    if hi > cur + 1e-4:
        pcs.append((cur, hi, 0.0, H_))
    return pcs


def xf_factory(door, cx, cz, gy):
    def pt(xl, yl, zl):
        if door == "+z":
            return (cx + xl, gy + yl, cz + zl)
        if door == "-z":
            return (cx - xl, gy + yl, cz - zl)
        if door == "+x":
            return (cx + zl, gy + yl, cz - xl)
        return (cx - zl, gy + yl, cz + xl)          # "-x"
    return pt


def build_house(h):
    door = h["door"]; cxw = h["cx"] * MAPK; czw = h["cz"] * MAPK; gy = h["hp"]
    tw, td = h["tw"], h["td"]                    # local width (x) and depth (z) in metres, door on local +z
    w, d = float(tw), float(td)
    t = 0.30; HT = 3.0
    pt = xf_factory(door, cxw, czw, gy)
    wc = WALL_COL[rng.integers(0, len(WALL_COL))]; rc = ROOF_COL[rng.integers(0, len(ROOF_COL))]
    inner = (0.88, 0.86, 0.80); floorc = (0.45, 0.34, 0.24)
    bx = []     # local boxes (x0, x1, y0, y1, z0, z1)
    # front wall (door) z in [d/2 - t, d/2]
    dx_ = float(rng.uniform(-(w / 2 - 2.4), (w / 2 - 2.4)))
    for (a, b, y0, y1) in wall_pieces(-w / 2, w / 2, [(dx_, 2.0, 0.0, 2.5)], HT):
        bx.append((a, b, y0, y1, d / 2 - t, d / 2))
    # back wall with windows
    wins = [(-w * 0.25, 1.4, 0.9, 2.1), (w * 0.25, 1.4, 0.9, 2.1)] if w >= 9 else [(0.0, 1.4, 0.9, 2.1)]
    for (a, b, y0, y1) in wall_pieces(-w / 2, w / 2, wins, HT):
        bx.append((a, b, y0, y1, -d / 2, -d / 2 + t))
    # side walls (one window each, slid along z)
    sz = float(rng.uniform(-d * 0.15, d * 0.15))
    for side in (-1, 1):
        x0 = -w / 2 if side < 0 else w / 2 - t
        for (a, b, y0, y1) in wall_pieces(-d / 2 + t, d / 2 - t, [(sz, 1.4, 0.9, 2.1)], HT):
            bx.append((x0, x0 + t, y0, y1, a, b))
    # partition (bigger houses): wall along z with a 1.6 m opening
    if w >= 11 and d >= 9:
        px = float(rng.uniform(-w * 0.18, w * 0.18))
        gz = float(rng.uniform(-d * 0.12, d * 0.12))
        for (a, b, y0, y1) in wall_pieces(-d / 2 + t, d * 0.12 + 1.0, [(gz, 1.6, 0.0, 2.3)], HT):
            bx.append((px - 0.1, px + 0.1, y0, y1, a, b))
    # floor + ceiling
    fx = [(-w / 2, w / 2, 0.0, 0.03, -d / 2, d / 2)]
    out_boxes = []
    for (x0, x1, y0, y1, z0, z1) in bx:
        # world mesh + collision box
        c1 = pt(x0, y0, z0); c2 = pt(x1, y1, z1)
        wx0, wx1 = sorted([c1[0], c2[0]]); wz0, wz1 = sorted([c1[2], c2[2]])
        box_faces(wx0, wx1, gy + y0, gy + y1, wz0, wz1, wc, inner)
        BOXES.append((wx0, wx1, gy + y0, gy + y1, wz0, wz1))
    for (x0, x1, y0, y1, z0, z1) in fx:
        c1 = pt(x0, y0, z0); c2 = pt(x1, y1, z1)
        wx0, wx1 = sorted([c1[0], c2[0]]); wz0, wz1 = sorted([c1[2], c2[2]])
        quad([(wx0, gy + y1, wz0), (wx0, gy + y1, wz1), (wx1, gy + y1, wz1), (wx1, gy + y1, wz0)], (0, 1, 0), floorc)
        quad([(wx0, gy + HT, wz0), (wx1, gy + HT, wz0), (wx1, gy + HT, wz1), (wx0, gy + HT, wz1)], (0, -1, 0), (0.92, 0.90, 0.85))
    # gable roof (ridge along local x)
    rh = 1.3 + d * 0.09; ov = 0.5
    ridge = HT + rh
    def P(xl, yl, zl): return pt(xl, yl, zl)
    nz = (0, rh, d / 2 + ov); l = math.hypot(nz[1], nz[2]); nzp = (0, nz[2] / l, nz[1] / l)
    # slanted planes: local normals (0, +d/2.., +-rh)
    def nl(sign):
        a = np.array([0.0, (d / 2 + ov), sign * rh]); a /= np.linalg.norm(a)
        # transform the direction like the points (rotation only)
        o = np.array(pt(0, 0, 0)); e = np.array(pt(a[0], a[1], a[2])) - np.array(pt(0, 0, 0)) + np.array([0, 0, 0])
        e = np.array(pt(a[0], 0, a[2])) - o
        return (float(e[0]), float(a[1]), float(e[2]))
    for sign in (1, -1):
        quad([P(-w / 2 - ov, HT - 0.1, sign * (d / 2 + ov)), P(w / 2 + ov, HT - 0.1, sign * (d / 2 + ov)), P(w / 2 + ov, ridge, 0), P(-w / 2 - ov, ridge, 0)], nl(sign), rc)
    for sx in (-1, 1):
        tri([P(sx * w / 2, HT, -d / 2), P(sx * w / 2, HT, d / 2), P(sx * w / 2, ridge, 0)], pt(sx, 0, 0)[0:1] + (0.0,) + (0.0,) if False else (0, 0, 0), wc)
    # collision of the roof (stops bullets)
    c1 = pt(-w / 2, HT, -d / 2); c2 = pt(w / 2, ridge, d / 2)
    wx0, wx1 = sorted([c1[0], c2[0]]); wz0, wz1 = sorted([c1[2], c2[2]])
    BOXES.append((wx0, wx1, gy + HT, gy + ridge, wz0, wz1))
    # footprint (world axes): centre, width, depth, ground
    fw = w if door in ("+z", "-z") else d
    fd = d if door in ("+z", "-z") else w
    HLIST.append((cxw, czw, fw, fd, gy))


for h in HOUSES:
    build_house(h)
VERTS = np.array(VERTS, "<f4"); TRIS = np.array(TRIS, "<i4"); BX = np.array(BOXES, "<f4"); HL = np.array(HLIST, "<f4")
# fix gable normals (flat shading uses the stored normal; recompute from the triangle for any face whose stored normal is zero)
for (a, b, c) in TRIS:
    n = VERTS[a, 3:6]
    if abs(n[0]) + abs(n[1]) + abs(n[2]) < 1e-6:
        p0 = VERTS[a, 0:3]; p1 = VERTS[b, 0:3]; p2 = VERTS[c, 0:3]
        nn = np.cross(p1 - p0, p2 - p0); nn = nn / max(np.linalg.norm(nn), 1e-9)
        for q in (a, b, c):
            VERTS[q, 3:6] = nn
with open(os.path.join(ASSETS, "maps", "extra_houses.bin"), "wb") as f:
    f.write(struct.pack("<4i", len(VERTS), len(TRIS), len(BX), len(HL)))
    f.write(VERTS.tobytes()); f.write(TRIS.tobytes()); f.write(BX.tobytes()); f.write(HL.tobytes())
print("houses mesh: verts", len(VERTS), "tris", len(TRIS), "boxes", len(BX))

# =====================================================================================  10. road paint raster (0.5 m texels, world metres)
tex_m = 0.5
allw = []
for q, arc in POLY:
    qw = q * MAPK
    d_ = np.hypot(*np.diff(qw, axis=0).T); cum = np.concatenate([[0], np.cumsum(d_)])
    n_ = max(int(cum[-1] / 0.5), 2)
    tt = np.linspace(0, cum[-1], n_)
    xw = np.interp(tt, cum, qw[:, 0]); zw = np.interp(tt, cum, qw[:, 1])
    dash = ((tt % 8.0) < 3.0) & (tt > 9.0) & (tt < cum[-1] - 9.0)
    allw.append(np.stack([xw, zw, dash.astype(float)], 1))
AW = np.concatenate(allw)
rx0 = math.floor(AW[:, 0].min() - 10); rx1 = math.ceil(AW[:, 0].max() + 10)
rz0 = math.floor(AW[:, 1].min() - 10); rz1 = math.ceil(AW[:, 1].max() + 10)
PW = int((rx1 - rx0) / tex_m); PH = int((rz1 - rz0) / tex_m); rx1 = rx0 + PW * tex_m; rz1 = rz0 + PH * tex_m
tr = cKDTree(AW[:, :2])
rg = np.zeros((PH, PW, 2), np.uint8)
CH = 400
gxp = rx0 + (np.arange(PW) + 0.5) * tex_m
for r0 in range(0, PH, CH):
    r1 = min(PH, r0 + CH)
    gzp = rz0 + (np.arange(r0, r1) + 0.5) * tex_m
    GXp, GZp = np.meshgrid(gxp, gzp)
    dq, iq = tr.query(np.stack([GXp.ravel(), GZp.ravel()], 1), distance_upper_bound=6.0)
    dq = np.where(np.isfinite(dq), dq, 99.0).reshape(r1 - r0, PW); iq = np.minimum(iq, len(AW) - 1).reshape(r1 - r0, PW)
    asph = np.clip((3.45 - dq) / 0.5 + 0.5, 0, 1)
    dsh = AW[iq, 2]
    dash = np.clip(1.0 - dq / 0.5, 0, 1) * dsh
    rg[r0:r1, :, 0] = (asph * 255).astype(np.uint8); rg[r0:r1, :, 1] = (dash * 255).astype(np.uint8)
with open(os.path.join(ASSETS, "maps", "roadpaint.bin"), "wb") as f:
    f.write(struct.pack("<ii", PW, PH) + struct.pack("<ffff", rx0, rz0, rx1, rz1))
    f.write(zlib.compress(rg.tobytes(), 6))
print("roadpaint", PW, PH)

# =====================================================================================  11. ground picture (also the mini-map picture)
SZ = 2048
u = (np.arange(SZ) + 0.5) / SZ
Uu, Vv = np.meshgrid(u, u)
Xf = (0.5 - Uu) * 2 * EXT; Zf = (Vv - 0.5) * 2 * EXT
hh = bil(H, Xf, Zf); slp = bil(SLOPE, Xf, Zf)
gm = ndi.map_coordinates(grass.astype(np.float32), [(Zf - X0) / SC - 0.5, (Xf - X0) / SC - 0.5], order=1, mode="nearest")
nz1 = ndi.gaussian_filter(rng.standard_normal((SZ, SZ)), 3.0); nz1 /= nz1.std()
nz2 = ndi.gaussian_filter(rng.standard_normal((SZ, SZ)), 11.0); nz2 /= nz2.std()
nz3 = ndi.gaussian_filter(rng.standard_normal((SZ, SZ)), 0.8); nz3 /= nz3.std()
lakew = np.zeros((SZ, SZ), bool)
for (lx, lz, lr, lv) in LAKES:
    lakew |= np.hypot(Xf - lx, Zf - lz) < lr


def col(r, g, b):
    return np.array([r, g, b], np.float32)


img = np.zeros((SZ, SZ, 3), np.float32)
deep = col(0.03, 0.20, 0.33); shal = col(0.08, 0.60, 0.66); sandc = col(0.80, 0.74, 0.55)
sea_t = sm01(1.0 + hh / 6.0)[..., None]
img[:] = deep * (1 - sea_t) + shal * sea_t
land = hh > 0.0
land3 = land[..., None]
sand_t = (1.0 - sm01((hh - 1.3) / 2.6))[..., None]
grass_a = col(0.34, 0.49, 0.21); grass_b = col(0.22, 0.37, 0.16); forest = col(0.13, 0.26, 0.11); rockc = col(0.45, 0.42, 0.37)
gmix = (0.5 + 0.18 * nz2)[..., None]
base = grass_a * (1 - gmix) + grass_b * gmix
base = base * (1 - 0.65 * (gm[..., None] > 0.5) * (nz2[..., None] > 0.3)) + forest * (0.65 * (gm[..., None] > 0.5) * (nz2[..., None] > 0.3))
rock_t = np.clip((slp - 0.45) / 0.35, 0, 1) * 0.9 + np.clip((hh - 34) / 12, 0, 1)
rock_t = np.clip(rock_t, 0, 1)[..., None]
lt = base * (1 - rock_t) + rockc * rock_t
lt = lt * (1 - sand_t) + sandc * sand_t
img = np.where(land3, lt, img)
# lakes
img = np.where(lakew[..., None], col(0.10, 0.37, 0.48), img)
# dirt around roads, dirt pads under houses
rdist, _ = RS_T.query(np.stack([Xf.ravel(), Zf.ravel()], 1)); rdist = rdist.reshape(SZ, SZ) * MAPK
dirt = col(0.43, 0.35, 0.25)
dt_ = (1.0 - sm01((rdist - 4.5) / 4.0))[..., None] * land3
img = img * (1 - 0.55 * dt_) + dirt * 0.55 * dt_
for h in HOUSES:
    dd = np.hypot(np.maximum(np.abs(Xf - h["cx"]) - h["hw"], 0), np.maximum(np.abs(Zf - h["cz"]) - h["hd"], 0)) * MAPK
    pw = (1.0 - sm01((dd - 2.0) / 5.0))[..., None]
    img = img * (1 - 0.6 * pw) + col(0.58, 0.52, 0.42) * 0.6 * pw
img = img * (1 + 0.07 * nz1[..., None] + 0.05 * nz3[..., None])
img = np.clip(img, 0, 1)
Image.fromarray((img * 255).astype(np.uint8)).save(os.path.join(ASSETS, "textures", "dustlineTexT.jpg"), quality=84, optimize=True)
print("texture written")
