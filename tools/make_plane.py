#!/usr/bin/env python3
"""Builds assets/models/plane/plane.bin (format v2) from the developer's own passenger-jet design (airplane_3d.html).
Geometry is the same as the three.js file (fuselage lathe + livery vertex colours, wings, fin, stabilisers, 2 engines with spinning fans).
HTML axes: nose +X, up +Y, right wing +Z.  Game axes: nose -Z, up +Y, wings along X.  (x,y,z)_html -> (z, y, -x)_game, scaled by S.
Format: int n | per part: int prop, float px,py,pz, int nv, int ni | float pos[nv*3] | float nor[nv*3] | float col[nv*3] | int idx[ni]   (little endian)
"""
import numpy as np, struct, sys, os
S = 0.85          # model scale in game metres
YOFF = 2.2        # lift so the fuselage sits where the old model's body was
OUT = sys.argv[1] if len(sys.argv) > 1 else 'plane.bin'

def hexc(h): return np.array([(h >> 16) & 255, (h >> 8) & 255, h & 255], float) / 255.0
def sstep(a, b, v):
    t = np.clip((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)

def T(p):  # html -> game axes (no scale/offset)
    p = np.asarray(p, float); return np.stack([p[..., 2], p[..., 1], -p[..., 0]], -1)

def rotx(a):
    c, s = np.cos(a), np.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
def roty(a):
    c, s = np.cos(a), np.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
def rotz(a):
    c, s = np.cos(a), np.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])

class Part:
    def __init__(self, prop=False, pivot=(0, 0, 0)):
        self.P, self.N, self.C, self.I = [], [], [], []; self.n = 0; self.prop = prop; self.pivot = np.array(pivot, float)
    def _add(self, P, N, C, tris):
        self.P.append(P); self.N.append(N); self.C.append(np.broadcast_to(C, P.shape).copy()); self.I.append(np.asarray(tris) + self.n); self.n += len(P)
    def flat(self, P, tris, color, inside=None):
        """flat-shaded triangles; orient outward from `inside` (point or per-tri array) when given"""
        P = np.asarray(P, float); tris = np.asarray(tris)
        a, b, c = P[tris[:, 0]], P[tris[:, 1]], P[tris[:, 2]]
        nrm = np.cross(b - a, c - a)
        if inside is not None:
            cen = (a + b + c) / 3; ins = np.asarray(inside, float)
            flip = np.einsum('ij,ij->i', nrm, cen - ins) < 0
            tris = tris.copy(); tris[flip] = tris[flip][:, [0, 2, 1]]
            nrm[flip] *= -1
        ln = np.linalg.norm(nrm, axis=1, keepdims=True); nrm = nrm / np.maximum(ln, 1e-12)
        PP = np.concatenate([P[tris[:, 0]], P[tris[:, 1]], P[tris[:, 2]]]) if False else P[tris.reshape(-1)]
        NN = np.repeat(nrm, 3, axis=0)
        self._add(PP, NN, np.asarray(color, float), np.arange(len(PP)).reshape(-1, 3))
    def smooth(self, P, tris, color, ref_flip=None):
        """smooth normals from winding. ref_flip: function(P,tris)->bool whether to flip all"""
        P = np.asarray(P, float); tris = np.asarray(tris)
        if ref_flip is not None and ref_flip(P, tris): tris = tris[:, [0, 2, 1]]
        a, b, c = P[tris[:, 0]], P[tris[:, 1]], P[tris[:, 2]]
        fn = np.cross(b - a, c - a)
        N = np.zeros_like(P)
        for k in range(3): np.add.at(N, tris[:, k], fn)
        N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-12)
        self._add(P, N, np.asarray(color, float), tris)

def convex_solid(part, P, tris, color):
    P = np.asarray(P, float); part.flat(P, tris, color, inside=P.mean(0))

def prism(shape2d, depth, M=np.eye(3), t=(0, 0, 0), zoff=0.0):
    """convex 2D polygon (x,y) extruded along z from zoff..zoff+depth, then M @ p + t.  returns P, tris"""
    s = np.asarray(shape2d, float); n = len(s)
    P = np.concatenate([np.c_[s, np.full(n, zoff)], np.c_[s, np.full(n, zoff + depth)]])
    tris = []
    for i in range(1, n - 1):
        tris.append([0, i, i + 1]); tris.append([n, n + i, n + i + 1])
    for i in range(n):
        j = (i + 1) % n; tris.append([i, j, n + j]); tris.append([i, n + j, n + i])
    return (P @ M.T) + np.asarray(t), np.array(tris)

def box(sx, sy, sz):
    h = np.array([sx, sy, sz]) / 2
    P = np.array([[x, y, z] for x in (-1, 1) for y in (-1, 1) for z in (-1, 1)]) * h
    q = [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]]
    tris = []
    for f in q: tris += [[f[0], f[1], f[2]], [f[0], f[2], f[3]]]
    return P, np.array(tris)

def cone(r, h, seg=20):
    """axis +Y, base at -h/2, apex at +h/2"""
    ang = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    ring = np.c_[r * np.cos(ang), np.full(seg, -h / 2), r * np.sin(ang)]
    P = np.concatenate([ring, [[0, h / 2, 0]], [[0, -h / 2, 0]]])
    tris = []
    for i in range(seg):
        j = (i + 1) % seg; tris.append([i, j, seg]); tris.append([i, j, seg + 1])
    return P, np.array(tris)

def uv_sphere(lat=14, lon=24):
    P = []
    for i in range(lat + 1):
        th = np.pi * i / lat
        for j in range(lon):
            ph = 2 * np.pi * j / lon
            P.append([np.sin(th) * np.cos(ph), np.cos(th), np.sin(th) * np.sin(ph)])
    tris = []
    for i in range(lat):
        for j in range(lon):
            a = i * lon + j; b = i * lon + (j + 1) % lon; c = (i + 1) * lon + j; d = (i + 1) * lon + (j + 1) % lon
            if i > 0: tris.append([a, c, b])
            if i < lat - 1: tris.append([b, c, d])
    return np.array(P), np.array(tris)

def catmull(pts, n):
    pts = np.asarray(pts, float); p = np.vstack([pts[0], pts, pts[-1]]); out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for t in np.linspace(0, 1, 40, endpoint=False):
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    out.append(pts[-1]); out = np.array(out)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(out, axis=0), axis=1))]
    u = np.linspace(0, d[-1], n)
    return np.c_[np.interp(u, d, out[:, 0]), np.interp(u, d, out[:, 1])]

def lathe_grid(prof, seg):
    """three.js LatheGeometry layout: (x,z) = (r sin, r cos), y along profile.  returns P (nprof*seg,3), tris"""
    np_ = len(prof); P = []
    for (r, y) in prof:
        for j in range(seg):
            ph = 2 * np.pi * j / seg; P.append([r * np.sin(ph), y, r * np.cos(ph)])
    tris = []
    for i in range(np_ - 1):
        for j in range(seg):
            a = i * seg + j; b = i * seg + (j + 1) % seg; c = (i + 1) * seg + j; d = (i + 1) * seg + (j + 1) % seg
            tris += [[a, b, c], [b, d, c]]
    return np.array(P), np.array(tris)

def outward_by_widest(P, tris):
    """True when the widest-radius face winding points away from the Y axis (lathe about Y)"""
    a, b, c = P[tris[:, 0]], P[tris[:, 1]], P[tris[:, 2]]
    fn = np.cross(b - a, c - a); cen = (a + b + c) / 3
    rad = np.hypot(cen[:, 0], cen[:, 2]); k = np.argmax(rad)
    return np.dot(fn[k], np.array([cen[k, 0], 0, cen[k, 2]])) < 0

R_LATHE = rotz(-np.pi / 2)    # lathe Y axis -> +X

parts = []    # (Part)
body = Part(); parts.append(body)

# ------------------------------------------------------------------ fuselage
prof_pts = [[0.02, -15], [0.25, -14.6], [0.5, -14.0], [0.75, -13.0], [1.0, -11.8], [1.3, -10.2], [1.6, -8.4], [1.82, -6.2],
            [1.9, -4], [1.9, 6], [1.86, 8.2], [1.74, 10.2], [1.5, 11.9], [1.15, 13.2], [0.78, 14.1], [0.4, 14.7], [0.02, 15.0]]
prof = catmull(prof_pts, 240); prof[:, 0] = np.maximum(prof[:, 0], 0)
SEG = 64
P, tris = lathe_grid(prof, SEG)
x, y, z = P[:, 0], P[:, 1], P[:, 2]
r = np.hypot(x, z); r[r == 0] = 1
ny = -x / r; sz = np.abs(z / r)
W = np.array([.93, .95, .97]); G = np.array([.58, .62, .68]); B = np.array([.06, .22, .55]); D = np.array([.03, .045, .07])
TIP = np.array([.5, .53, .58]); DR = np.array([.8, .83, .87])
col = np.tile(W, (len(P), 1))
def lerp(c, t, tgt): return c * (1 - t[:, None]) + tgt * t[:, None]
col = lerp(col, sstep(-0.3, -0.62, ny) * 0.95, G)
lenMask = sstep(-11, -10.2, y) * (1 - sstep(11, 11.8, y))
col = lerp(col, sstep(-0.14, -0.09, ny) * (1 - sstep(0.03, 0.08, ny)) * lenMask, B)
doorSide = sstep(0.72, 0.8, sz); doorY = (1 - sstep(0.5, 0.58, ny)) * sstep(-0.42, -0.34, ny)
door = np.maximum(sstep(8.55, 8.65, y) * (1 - sstep(9.65, 9.75, y)), sstep(-8.8, -8.7, y) * (1 - sstep(-7.7, -7.6, y))) * doorSide * doorY
col = lerp(col, door * 0.9, DR)
wsMask = sstep(11.3, 11.5, y) * (1 - sstep(12.9, 13.1, y)) * sstep(0.1, 0.25, ny) * (1 - sstep(0.7, 0.78, sz))
bar = np.maximum(1 - sstep(0.03, 0.06, sz), 1 - sstep(0.03, 0.06, np.abs(sz - 0.36)))
col = lerp(col, wsMask * (1 - bar), D)
side = sstep(11.2, 11.4, y) * (1 - sstep(12.1, 12.3, y)) * sstep(0.1, 0.2, ny) * (1 - sstep(0.55, 0.65, ny)) * sstep(0.78, 0.85, sz)
col = lerp(col, side, D)
col = lerp(col, sstep(14.0, 14.4, y), TIP)
body.smooth(P @ R_LATHE.T, tris, col, ref_flip=lambda PP, tt: outward_by_widest(P, tt))
radius_at = lambda yy: prof[np.argmin(np.abs(prof[:, 1] - yy)), 0]

# passenger windows (flat dark ovals on the skin)
GLASS = hexc(0x0b121c) * 1.6 + 0.03
ang = np.linspace(0, 2 * np.pi, 14, endpoint=False)
for xx in np.arange(-7.2, 8.31, 0.72):
    rr = radius_at(xx); yy = 0.5; zz = np.sqrt(rr * rr - yy * yy) + 0.035
    for s in (1, -1):
        n = np.array([0, yy / rr, s * zz / rr]); n /= np.linalg.norm(n)
        u = np.array([1.0, 0, 0]); v = np.cross(n, u); v /= np.linalg.norm(v)
        c0 = np.array([xx, yy, s * zz]); ring = [c0 + 0.15 * 0.9 * np.cos(a) * u + 0.15 * 1.25 * np.sin(a) * v for a in ang]
        Pw = np.array([c0] + ring); tw = [[0, 1 + i, 1 + (i + 1) % 14] for i in range(14)]
        body.flat(Pw, tw, GLASS, inside=c0 - n * 5)

# wing-body fairing
Ps, ts = uv_sphere(); Ps = Ps * np.array([4.6, 0.75, 1.55]) + np.array([0.1, -1.2, 0])
body.smooth(Ps, ts, hexc(0xcfd5dc), ref_flip=lambda PP, tt: np.dot(np.cross(PP[tt[40, 1]] - PP[tt[40, 0]], PP[tt[40, 2]] - PP[tt[40, 0]]), PP[tt[40, 0]] - np.array([0.1, -1.2, 0])) < 0)

WHITE = hexc(0xf3f5f8); BLUE = hexc(0x1646a0); GRAYC = hexc(0xc9d0d8); METAL = hexc(0x8b939c); DECAL = hexc(0xc3cad2); SLAT = hexc(0xdde2e8)

# vertical fin (+ blue stripe)
Pf, tf = prism([(-7.6, 1.2), (-11.6, 6.6), (-13.9, 6.6), (-14.4, 0.2)], 0.16, zoff=-0.08); convex_solid(body, Pf, tf, WHITE)
LEx = lambda yy: -7.6 - 0.7407 * (yy - 1.2) - 0.05
TEx = lambda yy: -14.4 + 0.078125 * (yy - 0.2) + 0.05
Pf, tf = prism([(LEx(3.8), 3.8), (LEx(5.4), 5.4), (TEx(5.4), 5.4), (TEx(3.8), 3.8)], 0.24, zoff=-0.12); convex_solid(body, Pf, tf, BLUE)

# ------------------------------------------------------------------ symmetric parts
def mirror(P, sgn): P = np.array(P, float); P[..., 2] *= sgn; return P

LE = lambda s: 3.5 - 6 * (s - 1.2) / 12.8
TE = lambda s: -3.0 - 1.5 * (s - 1.2) / 12.8
M_SHAPE_TO_WING = rotx(-np.pi / 2)       # three rotateX(-90deg) of the extruded shape
wa_rot = rotx(-0.06); wa_pos = np.array([1.2, -0.7, 0])
def to_wa(P): return (np.asarray(P) @ wa_rot.T) + wa_pos
def to_ha(P): return (np.asarray(P) @ rotx(-0.04).T) + np.array([0, 0.35, 0])

fans = []
for sgn in (1, -1):
    def out(P): return mirror(P, sgn)
    # wing
    Pw, tw = prism([(LE(1.2), -1.2), (LE(14), -14), (TE(14), -14), (TE(1.2), -1.2)], 0.14)
    Pw = Pw @ M_SHAPE_TO_WING.T
    Pw = out(to_wa(Pw)); convex_solid(body, Pw, tw, WHITE)
    # control-surface / slat decals on the top skin
    def decal(lst, c):
        pts = np.array([[xx, 0.158, s] for xx, s in lst]); n = len(pts)
        pts = out(to_wa(pts)); body.flat(pts, [[0, i, i + 1] for i in range(1, n - 1)], c, inside=pts.mean(0) - np.array([0, 5, 0]))
    flap = lambda s1, s2, ch: [(TE(s1) + ch, s1), (TE(s2) + ch, s2), (TE(s2), s2), (TE(s1), s1)]
    decal(flap(1.9, 6.9, 1.3), DECAL); decal(flap(7.1, 11.2, 1.0), DECAL); decal(flap(11.4, 13.7, 0.8), DECAL)
    decal([(LE(2.2), 2.2), (LE(13.6), 13.6), (LE(13.6) - 0.5, 13.6), (LE(2.2) - 0.5, 2.2)], SLAT)
    # sharklet winglet
    Pl, tl = prism([(-2.6, 0), (-4.5, 0), (-5.9, 2.1), (-5.3, 2.1)], 0.1, zoff=-0.05)
    Pl = Pl + np.array([0, 0.05, 13.9]); Pl = out(to_wa(Pl)); convex_solid(body, Pl, tl, WHITE)
    # navigation light
    Pn, tn = uv_sphere(6, 10); Pn = Pn * 0.13 + np.array([-3.8, 0.08, 14.05]); Pn = out(to_wa(Pn))
    body.smooth(Pn, tn, np.array([0.2, 1.0, 0.4]) if sgn > 0 else np.array([1.0, 0.19, 0.19]),
                ref_flip=lambda PP, tt: np.dot(np.cross(PP[tt[10, 1]] - PP[tt[10, 0]], PP[tt[10, 2]] - PP[tt[10, 0]]), PP[tt[10, 0]] - PP.mean(0)) < 0)
    # horizontal stabiliser
    Ph, th = prism([(-9.5, -0.3), (-12.7, -5.9), (-14.1, -5.9), (-13.2, -0.3)], 0.09)
    Ph = Ph @ M_SHAPE_TO_WING.T; Ph = out(to_ha(Ph)); convex_solid(body, Ph, th, WHITE)
    # pylon
    Pp, tp = box(2.8, 0.5, 0.2); Pp = out(Pp + np.array([3.0, -0.68, 5.0])); convex_solid(body, Pp, tp, WHITE)
    # engine nacelle (lathe, thin shell), ring, intake disc, exhaust cone
    ec = np.array([3.6, -1.5, 5.0])
    ep = catmull([[0.50, -2.1], [0.66, -1.7], [0.80, -1.0], [0.87, -0.2], [0.89, 1.0], [0.87, 1.85], [0.80, 2.12], [0.74, 2.1], [0.72, 1.7], [0.72, 0.8]], 70)
    Pe_l, te_l = lathe_grid(ep, 28)
    flipE = outward_by_widest(Pe_l, te_l) ^ (sgn < 0)   # mirroring reverses the winding
    Pe_g = out(Pe_l @ R_LATHE.T + ec)
    body.smooth(Pe_g, te_l, hexc(0xeceff2), ref_flip=lambda PP, tt: flipE)
    # lip ring (torus)
    ring_pts = []; tris_r = []; NR, NT = 36, 8
    for i in range(NR):
        a = 2 * np.pi * i / NR
        for j in range(NT):
            b = 2 * np.pi * j / NT; rr = 0.88 + 0.035 * np.cos(b)
            ring_pts.append([1.9 + 0.035 * np.sin(b), rr * np.cos(a), rr * np.sin(a)])
    for i in range(NR):
        for j in range(NT):
            a_ = i * NT + j; b_ = i * NT + (j + 1) % NT; c_ = ((i + 1) % NR) * NT + j; d_ = ((i + 1) % NR) * NT + (j + 1) % NT
            tris_r += [[a_, b_, c_], [b_, d_, c_]]
    ring_pts = out(np.array(ring_pts) + ec); tris_r = np.array(tris_r)
    ctr = np.array([[1.9 + ec[0], 0.88 * np.cos(2 * np.pi * (k // NT) / NR) + ec[1], 0.88 * np.sin(2 * np.pi * (k // NT) / NR) + ec[2]] for k in range(NR * NT)]); ctr[:, 2] *= sgn
    body.flat(ring_pts, tris_r, BLUE, inside=ctr[tris_r[:, 0]])
    # dark intake disc at x = 1.0
    ang2 = np.linspace(0, 2 * np.pi, 28, endpoint=False)
    disc = np.array([[1.0, 0, 0]] + [[1.0, 0.72 * np.cos(a), 0.72 * np.sin(a)] for a in ang2]) + ec
    disc = out(disc); body.flat(disc, [[0, 1 + i, 1 + (i + 1) % 28] for i in range(28)], hexc(0x15181c), inside=disc[0] - np.array([5, 0, 0]))
    # exhaust cone (apex toward -X)
    Pc, tc = cone(0.4, 1.0, 20); Pc = Pc @ rotz(np.pi / 2).T + np.array([-1.85, 0, 0]) + ec
    convex_solid(body, out(Pc), tc, hexc(0x5b6068))
    # fans (own part, pivot at the fan centre; spins around the nose axis in game)
    fan_pivot_html = ec + np.array([1.05, 0, 0])
    fp = Part(prop=True, pivot=T(out(fan_pivot_html[None]))[0] * S + np.array([0, YOFF, 0]))
    fp.pivot_html = out(fan_pivot_html[None])[0]
    for i in range(14):
        Pb, tb = box(0.05, 0.62, 0.17)
        Pb = Pb @ roty(0.55).T + np.array([0, 0.38, 0]); Pb = Pb @ rotx(i * 2 * np.pi / 14).T
        convex_solid(fp, out(Pb), tb, METAL)
    Pc2, tc2 = cone(0.2, 0.55, 20); Pc2 = Pc2 @ rotz(-np.pi / 2).T + np.array([0.3, 0, 0])
    convex_solid(fp, out(Pc2), tc2, GRAYC)
    fans.append(fp)

# beacon + tail light
for pos, c, rad in (((3, 1.98, 0), hexc(0xff2020), 0.12), (((-14.9, 0.1, 0)), hexc(0xffffff), 0.12)):
    Pb, tb = uv_sphere(6, 10); Pb = Pb * rad + np.array(pos)
    convex_solid(body, Pb, tb, c)

parts += fans

# ------------------------------------------------------------------ write
chunks = [struct.pack('<i', len(parts))]; allp = []
for pt in parts:
    P = np.concatenate(pt.P); N = np.concatenate(pt.N); C = np.concatenate(pt.C); I = np.concatenate(pt.I).reshape(-1)
    if pt.prop:
        # fan geometry is already built around the fan centre (local axes): it must NOT be shifted by the pivot (that threw the fans ~6 m away from the engines)
        Pg = T(P) * S; pv = T(pt.pivot_html[None])[0] * S + np.array([0, YOFF, 0]); piv = pv
    else:
        Pg = T(P) * S + np.array([0, YOFF, 0]); piv = np.zeros(3)
    Ng = T(N)
    allp.append(Pg + (piv if pt.prop else 0))
    chunks.append(struct.pack('<ifffii', 1 if pt.prop else 0, *piv, len(P), len(I)))
    chunks.append(Pg.astype('<f4').tobytes()); chunks.append(Ng.astype('<f4').tobytes()); chunks.append(C.astype('<f4').tobytes())
    chunks.append(I.astype('<i4').tobytes())
open(OUT, 'wb').write(b''.join(chunks))
a = np.vstack(allp); print('parts', len(parts), 'verts', sum(sum(len(x) for x in p.P) for p in parts), 'bbox', a.min(0).round(2), a.max(0).round(2), 'bytes', os.path.getsize(OUT))
