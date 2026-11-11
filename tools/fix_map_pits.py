#!/usr/bin/env python3
"""
fix_map_pits.py - fills the dry, closed pits (holes with no way out and no water in them) of assets/maps/dustlineMap.bin.

A pit = a group of land cells that is lower than the lowest point of its own rim, so rain water would stay there but the map
has no water in it (the lake meshes are the only water). Lake basins stay as they are: every cell of a lake surface (and 3 cells
around it) counts as a drain, so nothing next to a lake is raised and no lake bed is buried.
Each pit is raised flat to its spill level (the lowest rim point). The height field (int16, 2 fu cells), the terrain mesh
(vertex height + normal) and the road ribbons are updated together, so physics, picture and minimap agree.
Run:  python3 tools/fix_map_pits.py      (safe to run twice: the second run finds nothing to fill)
Needs: numpy, scipy.
"""
import os, sys, json, struct, heapq
import numpy as np
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "app", "src", "main", "assets", "maps", "dustlineMap.bin")
HOUSES = os.path.join(ROOT, "app", "src", "main", "assets", "maps", "extra_houses.bin")
MIN_DEPTH = 0.2      # m: shallower dips are just bumps, left alone

d = bytearray(open(PATH, "rb").read())
jl = struct.unpack_from("<I", d, 0)[0]
J = json.loads(bytes(d[4:4 + jl]))
base = 4 + jl; base += (4 - base % 4) % 4
N = J["N"]; CELL = J["cell"]; X0 = J["x0"]; HS = J["hs"]; SC = 4.0

g16 = np.frombuffer(d, "<i2", count=N * N, offset=base + J["heights"]).reshape(N, N).copy()
g = g16.astype(np.float32) / HS

# lake surfaces (centre vertex + first rim vertex of every water fan)
water = np.zeros((N, N), bool)
yy, xx = np.mgrid[0:N, 0:N]; wx = X0 + CELL * xx; wz = X0 + CELL * yy
for grp in J["groups"]:
    if grp["mat"] != "water": continue
    for t in grp["tiles"]:
        p = np.frombuffer(d, "<f4", count=t["nv"] * 3, offset=base + t["p"]).reshape(-1, 3)
        r = float(np.hypot(p[1, 0] - p[0, 0], p[1, 2] - p[0, 2]))
        water |= np.hypot(wx - p[0, 0], wz - p[0, 2]) <= r
seed = ndi.binary_dilation(water, iterations=3)

# priority flood from the map edge and from the lakes
fill = g.copy(); vis = np.zeros(g.shape, bool); hp = []
def push(k, i):
    vis[k, i] = True; heapq.heappush(hp, (float(g[k, i]), k, i))
for i in range(N):
    for (k, ii) in ((0, i), (N - 1, i), (i, 0), (i, N - 1)):
        if not vis[k, ii]: push(k, ii)
for k, i in zip(*np.nonzero(seed)):
    if not vis[k, i]: push(k, i)
while hp:
    z, k, i = heapq.heappop(hp)
    for dk, di in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        kk, ii = k + dk, i + di
        if 0 <= kk < N and 0 <= ii < N and not vis[kk, ii]:
            vis[kk, ii] = True; nz = max(float(g[kk, ii]), z); fill[kk, ii] = nz; heapq.heappush(hp, (nz, kk, ii))

pit = ((fill - g) > MIN_DEPTH) & (g > 0) & (~seed)
lab, npit = ndi.label(pit)
print("pits to fill:", npit, " cells:", int(pit.sum()), " area (file m2):", int(pit.sum() * CELL * CELL))
if npit == 0:
    print("nothing to do"); sys.exit(0)
# include the thin rim cells between 0 and MIN_DEPTH so the filled floor has no step
grow = ndi.binary_dilation(pit, iterations=2) & (fill > g) & (g > 0) & (~seed)
new = np.where(grow, fill, g).astype(np.float32)

# houses must not be touched
dd = open(HOUSES, "rb").read()
nv, nt, nb, nh = struct.unpack_from("<4i", dd, 0)
ho = 16 + nv * 44 + nt * 12 + nb * 24
hs = np.frombuffer(dd, "<f4", count=nh * 5, offset=ho).reshape(nh, 5)
for (hx, hz, hw, hd, hy) in hs:
    fx = hx / (4 / 3); fz = hz / (4 / 3); r = max(hw, hd) / (4 / 3) / 2 + 6
    i0 = int((fx - r - X0) / CELL); i1 = int((fx + r - X0) / CELL) + 1; k0 = int((fz - r - X0) / CELL); k1 = int((fz + r - X0) / CELL) + 1
    assert not grow[max(k0, 0):k1, max(i0, 0):i1].any(), "a house stands next to a pit - check by hand"

# write heights
d16 = np.round(new * HS).astype(np.int16)
struct.pack_into("<%dh" % (N * N), d, base + J["heights"], *d16.ravel())
newq = d16.astype(np.float32) / HS

def bilin(arr, x, z):
    fx = (x - X0) / CELL; fz = (z - X0) / CELL
    i = np.clip(np.floor(fx).astype(int), 0, N - 2); k = np.clip(np.floor(fz).astype(int), 0, N - 2)
    tx = fx - i; tz = fz - k
    return arr[k, i] * (1 - tx) * (1 - tz) + arr[k, i + 1] * tx * (1 - tz) + arr[k + 1, i] * (1 - tx) * tz + arr[k + 1, i + 1] * tx * tz

# coarse height grid (400 x 400, 4 fu) from the new fine grid, for the vertex normals
Hc = newq[::2, ::2]
gy_, gx_ = np.gradient(Hc, SC)
NR = np.stack([-gx_, np.ones_like(gx_), -gy_], -1); NR /= np.linalg.norm(NR, axis=-1, keepdims=True)
changed_c = ndi.binary_dilation(grow[::2, ::2], iterations=1)

moved = 0
for grp in J["groups"]:
    if grp["mat"] == "terrain":
        for t in grp["tiles"]:
            po = base + t["p"]; no = base + t["n"]
            P = np.frombuffer(d, "<f4", count=t["nv"] * 3, offset=po).reshape(-1, 3).copy()
            Nn = np.frombuffer(d, "<f4", count=t["nv"] * 3, offset=no).reshape(-1, 3).copy()
            j = np.rint((P[:, 0] - X0) / SC).astype(int); k = np.rint((P[:, 2] - X0) / SC).astype(int)
            ch = changed_c[k, j]
            if not ch.any(): continue
            sel = grow[k * 2, j * 2]
            P[sel, 1] = newq[k[sel] * 2, j[sel] * 2]
            Nn[ch] = NR[k[ch], j[ch]]
            moved += int(sel.sum())
            d[po:po + P.nbytes] = P.astype("<f4").tobytes(); d[no:no + Nn.nbytes] = Nn.astype("<f4").tobytes()
    elif grp["mat"] == "road":
        for t in grp["tiles"]:
            po = base + t["p"]
            P = np.frombuffer(d, "<f4", count=t["nv"] * 3, offset=po).reshape(-1, 3).copy()
            old = bilin(g, P[:, 0], P[:, 2]); nw = bilin(newq, P[:, 0], P[:, 2])
            sel = np.abs(nw - old) > 0.01
            if sel.any():
                P[sel, 1] = nw[sel]; d[po:po + P.nbytes] = P.astype("<f4").tobytes()
                print("road vertices lowered/raised:", int(sel.sum()))
print("terrain vertices raised:", moved)
open(PATH, "wb").write(bytes(d))
print("saved", PATH)
