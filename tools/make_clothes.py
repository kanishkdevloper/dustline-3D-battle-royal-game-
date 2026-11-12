#!/usr/bin/env python3
"""
make_clothes.py - procedural clothes for the Dustline store (original work, designed with AI assistance).

Builds 6 JEANS / PANTS (blue denim, charcoal cargo, olive camo, black track, red tartan, ember flame) and 2 T-SHIRTS completely from code (no third-party mesh, texture or photo is used) and
appends them to app/src/main/assets/store/store.json, the same file / format as the other store tops:
  * every piece is a skinned mesh (positions, uint16 indices, 4 bone indices + 4 weights per vertex, baked vertex colours)
  * the bones are the 11 bones of the survivor rig (body, thighL/R, shinL/R, upperL/R, foreL/R, handL/R), so the clothes follow every animation
  * "fit": {"survivor": offset} = the piece fitted to the survivor (numbers below = body measurements in the rig's bind pose, metres)
It also renders the store pictures (store/<id>.jpg + the small "thumb" inside store.json) with a tiny software rasteriser that uses the
same lighting as the game's cloth shader, and refreshes assets/manifest.txt (sizes are checked at start-up: "Damaged file" otherwise).

Run:  python3 tools/make_clothes.py        (idempotent: running it again replaces these 8 items, the other items stay untouched)
Needs: python3, numpy, Pillow.
"""
import os, sys, io, json, base64, math
import numpy as np
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
STORE = os.path.join(ASSETS, 'store')

# bone ids (same order as CharRig.BONES)
BODY, THIGH_L, THIGH_R, SHIN_L, SHIN_R, UP_L, UP_R, FORE_L, FORE_R = 0, 1, 2, 3, 4, 5, 6, 7, 8
rng = np.random.RandomState(7)

def sstep(a, b, x):
    t = np.clip((np.asarray(x, dtype=np.float64) - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)

def lerp_tab(y, tab):
    t = np.array(tab, dtype=np.float64)
    return np.interp(y, t[:, 0], t[:, 1])

def mix(c0, c1, t):
    t = np.asarray(t)[..., None]
    return np.asarray(c0, dtype=np.float64) * (1 - t) + np.asarray(c1, dtype=np.float64) * t

def seg_dist(P, A, B):
    A = np.asarray(A, float); B = np.asarray(B, float)
    d = B - A; l2 = max(float(d @ d), 1e-12)
    t = np.clip(((P - A) @ d) / l2, 0, 1)
    q = A + t[:, None] * d
    return np.linalg.norm(P - q, axis=1)

def poly_dist(P, pts):
    out = np.full(len(P), 1e9)
    for i in range(len(pts) - 1):
        out = np.minimum(out, seg_dist(P, pts[i], pts[i + 1]))
    return out

def line_mask(P, pts, w):
    """1 on the line, 0 further than w (soft edge) - used for stitches / seams / print strokes"""
    return sstep(w, 0.25 * w, poly_dist(P, pts))

def circle_mask(P, c, r, w):
    d = np.abs(np.linalg.norm(P - np.asarray(c), axis=1) - r)
    return sstep(w, 0.25 * w, d)

def disc_mask(P, c, r, soft=0.003):
    return sstep(r + soft, r - soft, np.linalg.norm(P - np.asarray(c), axis=1))

def rect_mask(P, x0, x1, y0, y1, soft=0.003):
    mx = sstep(x0 - soft, x0 + soft, P[:, 0]) * sstep(x1 + soft, x1 - soft, P[:, 0])
    my = sstep(y0 - soft, y0 + soft, P[:, 1]) * sstep(y1 + soft, y1 - soft, P[:, 1])
    return mx * my

# ------------------------------------------------------------------------------------------------ mesh container
class Mesh:
    def __init__(self):
        self.pos = np.zeros((0, 3)); self.col = np.zeros((0, 3)); self.si = np.zeros((0, 4), int); self.sw = np.zeros((0, 4)); self.idx = np.zeros((0, 3), int)
    def add(self, pos, col, si, sw, idx):
        o = len(self.pos)
        self.pos = np.vstack([self.pos, pos]); self.col = np.vstack([self.col, col]); self.si = np.vstack([self.si, si])
        self.sw = np.vstack([self.sw, sw]); self.idx = np.vstack([self.idx, np.asarray(idx) + o])
    def n(self): return len(self.pos)

def tri_normals(pos, idx):
    nr = np.zeros_like(pos)
    a, b, c = pos[idx[:, 0]], pos[idx[:, 1]], pos[idx[:, 2]]
    fn = np.cross(b - a, c - a)
    for k in range(3): np.add.at(nr, idx[:, k], fn)
    l = np.linalg.norm(nr, axis=1, keepdims=True); l[l < 1e-12] = 1
    return nr / l

def grid_indices(R, K, flip=False):
    """R rings x K points (closed in K). vertex (i,k) = i*K+k. Outward = rings going up, k increasing counter-clockwise seen from above."""
    i = np.arange(R - 1)[:, None]; k = np.arange(K)[None, :]
    a = i * K + k; b = i * K + (k + 1) % K; c = (i + 1) * K + (k + 1) % K; d = (i + 1) * K + k
    t1 = np.stack([a, c, b], -1).reshape(-1, 3); t2 = np.stack([a, d, c], -1).reshape(-1, 3)
    idx = np.concatenate([t1, t2])
    return idx[:, ::-1] if flip else idx

def to_weights(pairs_list):
    """pairs_list: list over vertices of [(bone, w), ...] -> si (n,4), sw (n,4) (top 4, normalised)"""
    n = len(pairs_list); si = np.zeros((n, 4), int); sw = np.zeros((n, 4))
    for v, pr in enumerate(pairs_list):
        pr = sorted([p for p in pr if p[1] > 1e-4], key=lambda p: -p[1])[:4]
        s = sum(p[1] for p in pr) or 1.0
        for k, (b, w) in enumerate(pr): si[v, k] = b; sw[v, k] = w / s
    return si, sw

def sup(c, s, n):
    return np.sign(c) * np.abs(c) ** (2.0 / n)

# ------------------------------------------------------------------------------------------------ PANTS
# Body measurements of the survivor in the rig's bind pose (metres), +x leg ( = the character's RIGHT leg ); the left leg is the mirror image.
# columns: y, x_inner, x_outer, z_front, z_back  (z < 0 is the front of the character). Above the crotch (y > 0.8) the inner edge goes
# past the centre line (x < 0) so the two legs overlap into one hip / waist.
JEANS = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .076), (0.30, .073), (0.45, .064), (0.54, .055), (0.65, .030), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .208), (0.30, .201), (0.45, .196), (0.54, .195), (0.65, .197), (0.78, .196), (0.86, .192), (0.95, .186), (1.05, .176), (1.14, .166)],
    z0=[(0.14, -.040), (0.30, -.038), (0.45, -.078), (0.54, -.098), (0.65, -.112), (0.78, -.113), (0.86, -.113), (0.95, -.118), (1.05, -.126), (1.14, -.135)],
    z1=[(0.14, .100), (0.30, .098), (0.45, .078), (0.54, .074), (0.65, .072), (0.78, .083), (0.86, .092), (0.95, .120), (1.05, .134), (1.14, .126)],
    n=2.5)
CARGO = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .090), (0.19, .090), (0.26, .070), (0.40, .060), (0.54, .042), (0.65, .022), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .196), (0.19, .196), (0.26, .212), (0.40, .226), (0.54, .232), (0.65, .234), (0.78, .224), (0.86, .210), (0.95, .194), (1.05, .180), (1.14, .168)],
    z0=[(0.14, -.040), (0.19, -.043), (0.26, -.078), (0.40, -.100), (0.54, -.118), (0.65, -.128), (0.78, -.128), (0.86, -.126), (0.95, -.126), (1.05, -.130), (1.14, -.136)],
    z1=[(0.14, .100), (0.19, .100), (0.26, .100), (0.40, .095), (0.54, .090), (0.65, .092), (0.78, .098), (0.86, .106), (0.95, .124), (1.05, .136), (1.14, .128)],
    n=2.5)

SHIN_W = [(0.35, 1.0), (0.47, .9), (0.53, .68), (0.59, .3), (0.65, .07), (0.71, 0.0)]
BODY_W = [(0.71, 0.0), (0.77, .02), (0.83, .05), (0.89, .19), (0.95, .33), (1.01, .49), (1.07, .71), (1.13, .79), (1.19, .97)]

def leg_frame(prof, y):
    x0 = lerp_tab(y, prof['x0']); x1 = lerp_tab(y, prof['x1']); z0 = lerp_tab(y, prof['z0']); z1 = lerp_tab(y, prof['z1'])
    return (x0 + x1) / 2, (x1 - x0) / 2, (z0 + z1) / 2, (z1 - z0) / 2

CUR_LEFT = False     # set by build_leg: lets a colour function give the left leg a different pattern (camo)

def build_leg(prof, K, dy, bump_fn, color_fn, left):
    global CUR_LEFT
    CUR_LEFT = left
    ys = np.arange(prof['hem'], prof['top'] + 1e-6, dy); R = len(ys); th = 2 * np.pi * np.arange(K) / K
    cx, a, cz, b = [np.repeat(v, K) for v in leg_frame(prof, ys)]
    Y = np.repeat(ys, K); T = np.tile(th, R)
    px = cx + a * sup(np.cos(T), 1, prof['n']); pz = cz + b * sup(np.sin(T), 1, prof['n'])
    bump = bump_fn(Y, T, px, pz, cx, cz, a, b)
    dx = px - cx; dz = pz - cz; l = np.maximum(np.hypot(dx / a, dz / b), 1e-9)
    # push along the (unit) radial direction of the superellipse centre
    dl = np.maximum(np.hypot(dx, dz), 1e-9)
    px = px + dx / dl * bump; pz = pz + dz / dl * bump
    pos = np.stack([px, Y, pz], 1)
    col = color_fn(pos, T, cx, cz, a, b)
    # weights (right leg = +x : thighR / shinR)
    sh = lerp_tab(Y, SHIN_W); bd = np.clip(lerp_tab(Y, BODY_W), 0, 1)
    th_w = np.clip(1 - sh - bd, 0, 1)
    tb, sb = (THIGH_R, SHIN_R)
    pairs = [[(BODY, bd[v]), (tb, th_w[v]), (sb, sh[v])] for v in range(len(Y))]
    si, sw = to_weights(pairs)
    idx = grid_indices(R, K)
    if left:
        pos = pos * np.array([-1, 1, 1]); idx = idx[:, ::-1]
        si = np.where(si == THIGH_R, THIGH_L, np.where(si == SHIN_R, SHIN_L, si))
    return pos, col, si, sw, idx

def make_pants(spec):
    prof = spec['prof']; K = spec.get('K', 64); dy = 0.009
    m = Mesh()
    for left in (False, True):
        pos, col, si, sw, idx = build_leg(prof, K, dy, spec['bump'], spec['color'], left)
        m.add(pos, col, si, sw, idx)
    return m

# ---- JEANS: classic blue straight-leg denim with orange top-stitching, pockets, belt loops, fly and a copper button
JEAN_BASE = np.array([52, 86, 138.]); JEAN_DARK = np.array([32, 54, 94.]); JEAN_FADE = np.array([104, 146, 196.])
STITCH = np.array([224, 168, 72.]); COPPER = np.array([196, 124, 62.])

def jeans_bump(Y, T, px, pz, cx, cz, a, b):
    X = np.abs(px); front = pz < cz; back = ~front
    bump = np.zeros_like(Y)
    bump += 0.004 * sstep(1.06, 1.065, Y) * sstep(1.145, 1.14, Y)                   # waistband
    bump += 0.0035 * sstep(0.150, 0.146, Y)                                          # hem turn-up
    for lx in (0.045, 0.105, 0.165):                                                 # belt loops (front pair, back pair, sides)
        pass
    for lx, side in ((0.075, front), (0.17, np.ones_like(front)), (0.09, back), (0.02, back)):
        bump += 0.003 * sstep(0.008, 0.004, np.abs(X - lx)) * sstep(1.05, 1.06, Y) * sstep(1.145, 1.135, Y) * side
    return bump

def jeans_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)                         # 0 = outer seam, -pi/2 = front, +pi/2 = back, +-pi = inseam
    c = np.tile(JEAN_BASE, (len(Y), 1)).astype(float)
    # denim twill: fine diagonal weave + fade
    wv = 0.5 + 0.5 * np.sin(Y * 520 + X * 260 + Z * 180)
    c *= (0.94 + 0.10 * wv)[:, None]
    fade = (np.exp(-((phi + math.pi / 2) / 0.95) ** 2) * sstep(0.40, 0.62, Y) * sstep(1.0, 0.80, Y)         # front thighs
            + 0.8 * np.exp(-((phi + math.pi / 2) / 0.7) ** 2) * np.exp(-((Y - 0.52) / 0.07) ** 2)             # knees
            + 0.5 * np.exp(-((phi - math.pi / 2) / 0.8) ** 2) * np.exp(-((Y - 0.88) / 0.08) ** 2))            # seat
    c = mix(c, JEAN_FADE, np.clip(fade, 0, 1) * 0.55)
    c = mix(c, JEAN_DARK, sstep(0.30, 0.15, Y) * 0.55 + sstep(0.14, 0.145, Y) * 0)              # darker towards the hem
    c += rng.normal(0, 2.2, c.shape)
    # waistband
    wb = sstep(1.058, 1.064, Y)
    c = mix(c, JEAN_DARK * 1.05, wb * 0.8)
    st = np.zeros(len(Y))
    st = np.maximum(st, line_mask(np.stack([Y, np.zeros_like(Y)], 1), [(1.062, 0), (1.062, 0)], 0.004) * 0)
    for yy in (1.064, 1.137):                                                                    # waistband stitch rows
        st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - yy)))
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.165)))                               # hem stitch
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.152)) * 0.6)
    arc = np.abs(phi) * a                                                                        # arc length from the outer seam
    inner = (np.pi - np.abs(phi)) * a
    st = np.maximum(st, sstep(0.0032, 0.0008, arc) * sstep(0.17, 0.175, Y) * sstep(1.06, 1.055, Y))    # outseam
    st = np.maximum(st, sstep(0.0030, 0.0008, np.abs(arc - 0.011)) * sstep(0.17, 0.175, Y) * sstep(1.06, 1.055, Y) * 0.6)
    st = np.maximum(st, sstep(0.0032, 0.0008, inner) * sstep(0.17, 0.175, Y) * sstep(0.84, 0.80, Y))   # inseam
    # front: pockets + fly
    pocket = [(0.088, 1.060), (0.098, 1.015), (0.122, 0.975), (0.152, 0.955), (0.180, 0.955)]
    coin = [(0.110, 1.040), (0.150, 1.040), (0.150, 1.018)]
    fly = [(0.016, 1.060), (0.016, 0.950), (0.026, 0.918), (0.048, 0.908)]
    fr = front.astype(float)
    st = np.maximum(st, line_mask(P, pocket, 0.0050) * fr)
    st = np.maximum(st, line_mask(P, [(p[0], p[1] - 0.012) for p in pocket], 0.0042) * fr * 0.8)
    st = np.maximum(st, line_mask(P, coin, 0.0038) * fr * 0.8)
    st = np.maximum(st, line_mask(P, fly, 0.0050) * fr)
    st = np.maximum(st, line_mask(P, [(0.0, 1.06), (0.0, 0.93)], 0.0035) * fr * 0.55)             # centre front seam
    # back: pockets (pentagon with a V arch) + yoke
    bp = [(0.030, 0.990), (0.030, 0.905), (0.078, 0.862), (0.126, 0.905), (0.126, 0.990), (0.030, 0.990)]
    arch = [(0.032, 0.968), (0.078, 0.915), (0.124, 0.968)]
    bk = (~front).astype(float)
    st = np.maximum(st, line_mask(P, bp, 0.0048) * bk)
    st = np.maximum(st, line_mask(P, arch, 0.0048) * bk)
    st = np.maximum(st, line_mask(P, [(0.0, 1.035), (0.07, 1.012), (0.19, 1.03)], 0.0045) * bk * 0.9)   # back yoke
    c = mix(c, STITCH, np.clip(st, 0, 1) * 0.85)
    # belt loops (darker strips) , copper rivets and button
    loops = np.zeros(len(Y))
    for lx, side in ((0.075, front), (0.09, ~front), (0.02, ~front)):
        loops = np.maximum(loops, sstep(0.0075, 0.0045, np.abs(X - lx)) * sstep(1.05, 1.056, Y) * sstep(1.145, 1.138, Y) * side)
    c = mix(c, JEAN_DARK * 0.9, loops * 0.8)
    rivets = np.zeros(len(Y))
    for (rx, ry, sd) in ((0.088, 1.060, front), (0.180, 0.955, front), (0.030, 0.990, ~front), (0.126, 0.990, ~front)):
        rivets = np.maximum(rivets, disc_mask(P, (rx, ry), 0.0055, 0.002) * sd)
    rivets = np.maximum(rivets, disc_mask(P, (0.0, 1.100), 0.0105, 0.002) * front)               # button
    c = mix(c, COPPER, rivets)
    # leather-ish patch on the back right waist
    patch = rect_mask(P, 0.075, 0.115, 1.075, 1.12, 0.002) * (~front)
    c = mix(c, np.array([150, 104, 66.]), patch * 0.9)
    return np.clip(c, 0, 255)

# ---- CARGO: charcoal tactical cargo pants - thigh cargo pockets with flaps, knee seams, elasticated ankle cuffs
CARGO_BASE = np.array([64, 69, 76.]); CARGO_FLAP = np.array([80, 87, 94.]); CARGO_DARK = np.array([34, 36, 41.]); CARGO_ST = np.array([128, 134, 140.])
ACCENT = np.array([226, 112, 36.])

def cargo_phi_pocket():
    return -0.30      # centre of the thigh pockets (rad around the leg, 0 = outer seam, negative = to the front)

def cargo_bump(Y, T, px, pz, cx, cz, a, b):
    X = np.abs(px); phi = np.arctan2(pz - cz, X - cx)
    bump = np.zeros_like(Y)
    bump += 0.004 * sstep(1.06, 1.065, Y) * sstep(1.145, 1.14, Y)                                   # waistband
    # cuffs: ribbed band, a little thicker
    bump += 0.004 * sstep(0.215, 0.205, Y)
    # cargo pocket (box with bellows) on the outer thigh
    pm = sstep(0.50, 0.49, np.abs(phi - cargo_phi_pocket())) * sstep(0.45, 0.46, Y) * sstep(0.81, 0.80, Y)
    pm = sstep(0.48, 0.42, np.abs(phi - cargo_phi_pocket())) * sstep(0.46, 0.50, Y) * sstep(0.80, 0.76, Y)
    flap = sstep(0.48, 0.42, np.abs(phi - cargo_phi_pocket())) * sstep(0.735, 0.745, Y) * sstep(0.80, 0.78, Y)
    bump += 0.022 * pm + 0.007 * flap
    # knee darts: a ridge across the knee
    bump += 0.005 * np.exp(-((Y - 0.50) / 0.012) ** 2) * sstep(0.1, -0.2, np.cos(phi + math.pi / 2) * 0 - 0.0)
    return bump

def cargo_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)
    c = np.tile(CARGO_BASE, (len(Y), 1)).astype(float)
    wv = 0.5 + 0.5 * np.sin(Y * 700 + phi * 90)
    c *= (0.98 + 0.03 * wv)[:, None]
    c += rng.normal(0, 1.8, c.shape)
    arc = np.abs(phi) * a
    inner = (np.pi - np.abs(phi)) * a
    st = np.zeros(len(Y))
    # seams: outseam, inseam, waistband rows, knee rows, cuff
    st = np.maximum(st, sstep(0.0032, 0.0008, arc) * sstep(0.22, 0.23, Y) * sstep(1.06, 1.055, Y))
    st = np.maximum(st, sstep(0.0032, 0.0008, inner) * sstep(0.22, 0.23, Y) * sstep(0.84, 0.80, Y))
    for yy in (1.064, 1.137, 0.482, 0.518):
        st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - yy)) * (0.9 if yy > 1 else 0.45))
    # waistband
    c = mix(c, CARGO_DARK * 1.1, sstep(1.058, 1.064, Y) * 0.85)
    # ankle cuffs (ribbed): dark, with vertical ribs
    cuff = sstep(0.215, 0.205, Y)
    rib = 0.5 + 0.5 * np.sin(phi * a * 520)
    c = mix(c, CARGO_DARK * (0.8 + 0.4 * rib[:, None]), cuff * 0.95)
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.218)))
    # orange reflective tab on the outer ankle
    tab = sstep(0.16, 0.15, Y) * 0 + rect_mask(np.stack([phi * a, Y], 1), -0.022, 0.022, 0.150, 0.200, 0.002) * (np.abs(phi) < 0.6)
    c = mix(c, ACCENT, tab)
    # front pockets (slanted) + fly
    fr = front.astype(float)
    pocket = [(0.088, 1.060), (0.100, 1.020), (0.128, 0.985), (0.160, 0.968), (0.190, 0.970)]
    st = np.maximum(st, line_mask(P, pocket, 0.0050) * fr)
    st = np.maximum(st, line_mask(P, [(0.012, 1.060), (0.012, 0.945)], 0.0045) * fr)
    st = np.maximum(st, line_mask(P, [(0.0, 1.06), (0.0, 0.93)], 0.0035) * fr * 0.55)
    # cargo pockets: lighter flap, stitched box, button
    ph = np.abs(phi - cargo_phi_pocket())
    box = sstep(0.50, 0.44, ph) * sstep(0.455, 0.49, Y) * sstep(0.80, 0.76, Y)
    flap = sstep(0.50, 0.44, ph) * sstep(0.735, 0.745, Y) * sstep(0.805, 0.78, Y)
    c = mix(c, CARGO_FLAP, box * 0.7)
    c = mix(c, CARGO_FLAP * 1.08, flap * 0.8)
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.737)) * sstep(0.50, 0.46, ph) * 0.9)
    st = np.maximum(st, sstep(0.007, 0.0015, np.abs(ph - 0.47)) * sstep(0.46, 0.50, Y) * sstep(0.80, 0.76, Y) * 0.8)
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.472)) * sstep(0.50, 0.46, ph) * 0.8)
    st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - 0.797)) * sstep(0.50, 0.46, ph) * 0.8)
    c = mix(c, CARGO_ST, np.clip(st, 0, 1) * 0.8)
    btn = disc_mask(np.stack([(phi - cargo_phi_pocket()) * a, Y], 1), (0.0, 0.710), 0.0085, 0.002)
    c = mix(c, np.array([150, 156, 160.]), btn)
    # back pockets (flap) + belt loops
    bk = (~front).astype(float)
    st2 = line_mask(P, [(0.030, 0.985), (0.030, 0.905), (0.124, 0.905), (0.124, 0.985), (0.030, 0.985)], 0.0045) * bk
    c = mix(c, CARGO_ST, st2 * 0.7)
    loops = np.zeros(len(Y))
    for lx, side in ((0.075, front), (0.09, ~front), (0.02, ~front)):
        loops = np.maximum(loops, sstep(0.0075, 0.0045, np.abs(X - lx)) * sstep(1.05, 1.056, Y) * sstep(1.145, 1.138, Y) * side)
    c = mix(c, CARGO_DARK, loops * 0.8)
    c = mix(c, np.array([160, 164, 168.]), disc_mask(P, (0.0, 1.100), 0.0095, 0.002) * fr)
    return np.clip(c, 0, 255)

# ------------------------------------------------------------------------------------------------ T-SHIRTS
# torso rings: columns y, half-width, z_front, z_back (rig bind pose, metres). base = the survivor torso under a tee
TORSO = [(0.90, .206, -.178, .150), (0.96, .208, -.186, .153), (1.02, .206, -.178, .153), (1.14, .203, -.171, .150), (1.24, .201, -.170, .143),
         (1.34, .204, -.169, .141), (1.42, .216, -.171, .148), (1.50, .220, -.172, .152), (1.58, .221, -.166, .152), (1.64, .212, -.150, .145),
         (1.68, .196, -.138, .134), (1.72, .162, -.150, .116), (1.75, .134, -.166, .092), (1.78, .114, -.178, .060), (1.800, .110, -.178, .056)]
SHOULDER = np.array([.27, 1.67, 0.0]); ELBOW = np.array([.328, 1.305, .022])

def torso_frame(y, grow, hw_add=0.0):
    t = np.array(TORSO)
    hw = np.interp(y, t[:, 0], t[:, 1]) + grow * 0 + hw_add
    z0 = np.interp(y, t[:, 0], t[:, 2]) - grow; z1 = np.interp(y, t[:, 0], t[:, 3]) + grow
    return hw + grow, (z0 + z1) / 2, (z1 - z0) / 2

def bez(p0, p1, p2, u):
    u = u[:, None]; return (1 - u) ** 2 * p0 + 2 * (1 - u) * u * p1 + u ** 2 * p2

def make_shirt(spec):
    hem = spec['hem']; K = 128; top = 1.800
    # finer rings over the chest (prints look sharper), coarser elsewhere
    ys = np.unique(np.round(np.concatenate([np.arange(hem, 1.28, 0.012), np.arange(1.28, 1.52, 0.005), np.arange(1.52, top, 0.011), [top]]), 4)); R = len(ys)
    th = 2 * np.pi * np.arange(K) / K
    grow = spec['grow'] * np.ones(R) + spec.get('grow_hem', 0) * sstep(1.20, hem, ys)
    # boxy / slim fit: width change tapers to zero at the neck
    fit = (1 - sstep(1.62, 1.76, ys))
    hw = np.interp(ys, [r[0] for r in TORSO], [r[1] for r in TORSO]) + spec['wide'] * fit + spec.get('wide_hem', 0) * sstep(1.30, hem, ys)
    zf = np.interp(ys, [r[0] for r in TORSO], [r[2] for r in TORSO]) - spec['deep'] * fit
    zb = np.interp(ys, [r[0] for r in TORSO], [r[3] for r in TORSO]) + spec['deep'] * fit
    cz = (zf + zb) / 2; bz = (zb - zf) / 2
    Y = np.repeat(ys, K); T = np.tile(th, R)
    HW = np.repeat(hw, K); CZ = np.repeat(cz, K); BZ = np.repeat(bz, K)
    px = HW * sup(np.cos(T), 1, 2.6); pz = CZ + BZ * sup(np.sin(T), 1, 2.6)
    pos = np.stack([px, Y, pz], 1)
    extra = spec['bump'](pos, T)                      # radial bumps (collar, pocket ...)
    ctr = np.stack([np.zeros_like(Y), Y, CZ], 1)
    d = pos - ctr; d[:, 1] = 0; dl = np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
    pos = pos + d / dl * extra[:, None]
    idx = grid_indices(R, K)
    # torso weights: body, with a little thigh at the hem so a long tee follows the hips
    bd = np.ones(len(Y)); pairs = []
    thw = 0.40 * sstep(1.06, 0.90, Y)
    for v in range(len(Y)):
        if pos[v, 0] >= 0: pairs.append([(BODY, 1 - thw[v]), (THIGH_R, thw[v])])
        else: pairs.append([(BODY, 1 - thw[v]), (THIGH_L, thw[v])])
    si, sw = to_weights(pairs)
    # V neck: cut the front triangles above the V (centroid test), the trim colour follows the edge
    keep = np.ones(len(idx), bool)
    if spec.get('vneck'):
        ap, wd = spec['vneck']                          # apex y, half width of the V at the collar top
        cen = pos[idx].mean(1)
        vline = ap + (np.abs(cen[:, 0]) / wd) * (top - ap)
        cut = (cen[:, 2] < CZ[idx[:, 0]] - 0.03) & (((cen[:, 1] > vline) & (np.abs(cen[:, 0]) < wd * 1.02)) | ((cen[:, 1] > top - 0.013) & (np.abs(cen[:, 0]) < wd * 1.7)))
        keep &= ~cut
    col = spec['color'](pos, T, CZ, 'torso')
    m = Mesh(); m.add(pos, col, si, sw, idx[keep])
    # sleeves (left + right): tube along a curve from inside the torso, over the shoulder, down the upper arm
    for left in (False, True):
        p0 = np.array([.13, 1.665, -.004]); d = (ELBOW - SHOULDER) / np.linalg.norm(ELBOW - SHOULDER)
        pend = SHOULDER + d * spec['sleeve']; c1 = np.array([.30, 1.690, .002])
        S = 34; u = np.linspace(0, 1, S); path = bez(p0, c1, pend, u)
        tang = np.gradient(path, axis=0); tang /= np.linalg.norm(tang, axis=1, keepdims=True)
        ref = np.array([0, 0, 1.0]); n1 = np.cross(tang, ref); n1 /= np.linalg.norm(n1, axis=1, keepdims=True); n2 = np.cross(tang, n1)
        rad = np.interp(u, [0, .30, .55, 1], [.060, .068, spec['r_mid'], spec['r_end']])
        Ks = 40; ang = 2 * np.pi * np.arange(Ks) / Ks
        pp = []; 
        for i in range(S):
            for k in range(Ks):
                pp.append(path[i] + rad[i] * (np.cos(ang[k]) * n1[i] + np.sin(ang[k]) * n2[i]))
        pp = np.array(pp)
        sleeve_idx = grid_indices(S, Ks)
        # make the triangles point outwards (check against the ring centres)
        cen = np.repeat(path, Ks, axis=0)
        nrm = tri_normals(pp, sleeve_idx); 
        if np.mean(np.einsum('ij,ij->i', nrm, pp - cen)) < 0: sleeve_idx = sleeve_idx[:, ::-1]
        along = (pp - SHOULDER) @ d
        wu = sstep(-0.07, 0.06, along)
        wu = np.where(pp[:, 0] < 0.20, 0, wu) * 1.0
        pairs = [[(BODY, 1 - w), (UP_R, w)] for w in wu]
        sis, sws = to_weights(pairs)
        scol = spec['color'](pp, np.zeros(len(pp)), None, 'sleeve', u=np.repeat(u, Ks), along=along)
        if left:
            pp = pp * np.array([-1, 1, 1]); sleeve_idx = sleeve_idx[:, ::-1]; sis = np.where(sis == UP_R, UP_L, sis)
        m.add(pp, scol, sis, sws, sleeve_idx)
    return m

# ---- TEE A: boxy desert-stripe crew tee (sand, brown + rust stripes, ribbed collar, chest pocket)
SAND = np.array([208, 180, 124.]); SAND_D = np.array([178, 148, 96.]); BROWN = np.array([62, 44, 34.]); RUST = np.array([176, 84, 40.])
def tee_a_bump(pos, T):
    X, Y, Z = pos[:, 0], pos[:, 1], pos[:, 2]
    b = 0.006 * sstep(1.755, 1.775, Y)                                                       # ribbed collar stands out a little
    pocket = rect_mask(np.stack([X, Y], 1), -0.150, -0.060, 1.405, 1.505, 0.004) * (Z < -0.0)
    b += 0.0035 * pocket
    b += 0.003 * sstep(0.985, 0.96, Y) * 0                                                   # (hem stays flat)
    return b
def tee_a_color(pos, T, CZ, part, u=None, along=None):
    X, Y, Z = pos[:, 0], pos[:, 1], pos[:, 2]
    c = np.tile(SAND, (len(Y), 1)).astype(float)
    wv = 0.5 + 0.5 * np.sin(Y * 600) * np.sin(X * 400 + 1)
    c *= (0.96 + 0.07 * wv)[:, None]; c += rng.normal(0, 1.6, c.shape)
    if part == 'torso':
        # three wrap-around chest stripes
        for (y0, y1, col) in ((1.515, 1.545, BROWN), (1.478, 1.498, RUST), (1.435, 1.462, BROWN)):
            c = mix(c, col, sstep(y0 - 0.0025, y0 + 0.002, Y) * sstep(y1 + 0.0025, y1 - 0.002, Y))
        # darker hem band + stitch
        c = mix(c, SAND_D, sstep(0.975, 0.945, Y) * 0.7)
        c = mix(c, BROWN, sstep(0.0045, 0.0012, np.abs(Y - 0.972)) * 0.7)
        # collar rib
        collar = sstep(1.755, 1.768, Y)
        rib = 0.5 + 0.5 * np.sin(np.arctan2(Z - CZ, X) * 150)
        c = mix(c, BROWN * (0.85 + 0.3 * rib[:, None]), collar)
        # chest pocket on the wearer's left (x < 0) with stitching
        P = np.stack([X, Y], 1); front = Z < CZ
        pk = rect_mask(P, -0.150, -0.060, 1.405, 1.505, 0.002) * front
        c = mix(c, SAND * 0.93, pk * 0.8)
        edge = line_mask(P, [(-0.150, 1.505), (-0.060, 1.505), (-0.060, 1.405), (-0.105, 1.385), (-0.150, 1.405), (-0.150, 1.505)], 0.0045) * front
        c = mix(c, BROWN, np.clip(edge, 0, 1) * 0.75)
        # side seam shadow
        c = mix(c, SAND_D, sstep(0.012, 0.0, np.abs(np.abs(X) - np.interp(Y, [r[0] for r in TORSO], [r[1] for r in TORSO]) - 0.012)) * 0.0)
    else:
        # sleeves: two stripes near the hem and a dark cuff edge
        s = along
        c = mix(c, BROWN, sstep(0.0025, 0.0, np.abs(s - (A_SLEEVE - 0.045)) - 0.007) * 1.0)
        c = mix(c, RUST, sstep(0.0025, 0.0, np.abs(s - (A_SLEEVE - 0.073)) - 0.0045) * 1.0)
        c = mix(c, SAND_D, sstep(A_SLEEVE - 0.012, A_SLEEVE - 0.001, s) * 0.5)
    return np.clip(c, 0, 255)
A_SLEEVE = 0.205

# ---- TEE B: slim crimson V-neck with a cream crosshair print
CRIM = np.array([138, 30, 44.]); CRIM_D = np.array([104, 20, 34.]); CREAM = np.array([240, 228, 200.]); INK = np.array([34, 28, 32.])
B_SLEEVE = 0.150
def tee_b_bump(pos, T):
    X, Y, Z = pos[:, 0], pos[:, 1], pos[:, 2]
    return 0.0025 * sstep(1.70, 1.75, Y) * 0 + 0.0 * Y
def tee_b_color(pos, T, CZ, part, u=None, along=None):
    X, Y, Z = pos[:, 0], pos[:, 1], pos[:, 2]
    c = np.tile(CRIM, (len(Y), 1)).astype(float)
    wv = 0.5 + 0.5 * np.sin(Y * 640 + X * 120)
    c *= (0.95 + 0.08 * wv)[:, None]; c += rng.normal(0, 1.6, c.shape)
    if part == 'torso':
        P = np.stack([X, Y], 1); front = (Z < CZ)
        # soft darker sides (slim-fit shading panels)
        c = mix(c, CRIM_D, sstep(0.10, 0.19, np.abs(X)) * sstep(1.45, 1.20, Y) * 0.35)
        # hem band
        c = mix(c, CRIM_D, sstep(0.965, 0.93, Y) * 0.0)
        c = mix(c, CREAM, sstep(0.0035, 0.0010, np.abs(Y - 0.905)) * 0.0)
        # V-neck trim (cream) follows the cut line; the collar band only at the back and the sides
        ap, wd, top = 1.62, 0.105, 1.80
        vline = ap + (np.abs(X) / wd) * (top - ap)
        trim = sstep(0.020, 0.008, np.abs(Y - vline) * 0.85) * (np.abs(X) < wd * 1.02) * front
        trim = np.maximum(trim, sstep(1.768, 1.780, Y) * ((~front) | (np.abs(X) > wd * 1.02)))
        c = mix(c, CREAM, np.clip(trim, 0, 1) * 0.95)
        # crosshair print, centre chest: ring + 4 ticks + dot (bold strokes, the mesh is ~1 cm per vertex)
        cx0, cy0 = 0.0, 1.40
        ring = circle_mask(P, (cx0, cy0), 0.056, 0.0105) * front
        ticks = np.zeros(len(Y))
        for (a0, b0) in (((0, 0.030), (0, 0.098)), ((0, -0.030), (0, -0.098)), ((0.030, 0), (0.098, 0)), ((-0.030, 0), (-0.098, 0))):
            ticks = np.maximum(ticks, line_mask(P, [(cx0 + a0[0], cy0 + a0[1]), (cx0 + b0[0], cy0 + b0[1])], 0.0075))
        ticks = ticks * front
        dot = disc_mask(P, (cx0, cy0), 0.011, 0.003) * front
        pr = np.clip(np.maximum(np.maximum(ring, ticks), dot), 0, 1)
        c = mix(c, CREAM, pr * 0.97)
        # little stitched label bar bottom-left of the front + hem stitch
        c = mix(c, CREAM, sstep(0.0035, 0.0010, np.abs(Y - 0.985)) * 0.55)
        # back print: big cream number-ish stripe across the shoulder blades
        back = ~front
        c = mix(c, CREAM, rect_mask(P, -0.110, 0.110, 1.500, 1.522, 0.003) * back * 0.9)
        c = mix(c, CREAM, rect_mask(P, -0.060, 0.060, 1.468, 1.485, 0.003) * back * 0.9)
    else:
        s = along
        c = mix(c, CREAM, sstep(0.0035, 0.0, np.abs(s - (B_SLEEVE - 0.012)) - 0.005))   # cream cuff
        c = mix(c, CRIM_D, sstep(B_SLEEVE - 0.03, B_SLEEVE - 0.002, s) * 0.0)
    return np.clip(c, 0, 255)


# ------------------------------------------------------------------------------------------------ PANTS 3 + 4
# value noise (smooth, tileable lattice) for the camouflage
def vnoise(P, freq, seed):
    tab = np.random.RandomState(seed).rand(32, 32, 32)
    q = np.asarray(P, dtype=np.float64) * freq
    i0 = np.floor(q).astype(int); f = q - i0; f = f * f * (3 - 2 * f)
    out = 0.0
    for dx in (0, 1):
        for dy_ in (0, 1):
            for dz in (0, 1):
                w = (f[:, 0] if dx else 1 - f[:, 0]) * (f[:, 1] if dy_ else 1 - f[:, 1]) * (f[:, 2] if dz else 1 - f[:, 2])
                out = out + w * tab[(i0[:, 0] + dx) % 32, (i0[:, 1] + dy_) % 32, (i0[:, 2] + dz) % 32]
    return out

# CAMO: regular straight-leg combat pants, bloused hem with drawcord stitching
CAMO = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .082), (0.19, .080), (0.26, .072), (0.40, .062), (0.54, .050), (0.65, .026), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .204), (0.19, .206), (0.26, .210), (0.40, .214), (0.54, .216), (0.65, .220), (0.78, .214), (0.86, .204), (0.95, .190), (1.05, .178), (1.14, .168)],
    z0=[(0.14, -.040), (0.19, -.042), (0.26, -.072), (0.40, -.094), (0.54, -.110), (0.65, -.120), (0.78, -.121), (0.86, -.121), (0.95, -.123), (1.05, -.129), (1.14, -.135)],
    z1=[(0.14, .100), (0.19, .101), (0.26, .102), (0.40, .092), (0.54, .088), (0.65, .088), (0.78, .094), (0.86, .102), (0.95, .122), (1.05, .134), (1.14, .126)],
    n=2.5)
# TRACK: black jogger / track pants, relaxed thigh, snug ribbed ankle cuff
TRACK = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .090), (0.19, .086), (0.26, .070), (0.40, .062), (0.54, .046), (0.65, .024), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .198), (0.19, .202), (0.26, .208), (0.40, .210), (0.54, .212), (0.65, .214), (0.78, .206), (0.86, .198), (0.95, .188), (1.05, .176), (1.14, .166)],
    z0=[(0.14, -.040), (0.19, -.043), (0.26, -.070), (0.40, -.090), (0.54, -.108), (0.65, -.118), (0.78, -.120), (0.86, -.120), (0.95, -.122), (1.05, -.128), (1.14, -.135)],
    z1=[(0.14, .100), (0.19, .101), (0.26, .102), (0.40, .090), (0.54, .086), (0.65, .086), (0.78, .092), (0.86, .100), (0.95, .122), (1.05, .134), (1.14, .126)],
    n=2.5)

C_TAN = np.array([150, 128, 84.]); C_OLIVE = np.array([76, 92, 52.]); C_BROWN = np.array([88, 62, 40.]); C_BLACK = np.array([30, 34, 26.])
C_DRAB = np.array([62, 70, 44.]); C_THREAD = np.array([36, 40, 28.]); C_BRASS = np.array([158, 134, 70.])

def camo_bump(Y, T, px, pz, cx, cz, a, b):
    X = np.abs(px); front = pz < cz; phi = np.arctan2(pz - cz, X - cx)
    bump = np.zeros_like(Y)
    bump += 0.004 * sstep(1.06, 1.065, Y) * sstep(1.145, 1.14, Y)                                   # waistband
    bump += 0.008 * np.exp(-((Y - 0.225) / 0.032) ** 2)                                              # bloused hem
    bump += 0.0035 * sstep(0.175, 0.17, Y)                                                           # hem band
    knee = sstep(1.0, 0.55, np.abs(phi + math.pi / 2)) * sstep(0.405, 0.43, Y) * sstep(0.60, 0.575, Y)
    bump += 0.0045 * knee                                                                            # knee patch
    fl = sstep(0.07, 0.04, np.abs(X - 0.078)) * sstep(0.905, 0.92, Y) * sstep(0.995, 0.98, Y) * (~front)
    bump += 0.004 * fl                                                                               # back flap pockets
    return bump

def camo_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)
    Q = pos + (np.array([3.7, 1.3, 2.1]) if CUR_LEFT else 0.0)
    n1 = 0.6 * vnoise(Q, 9, 11) + 0.4 * vnoise(Q, 19, 12)
    n2 = 0.6 * vnoise(Q, 8, 13) + 0.4 * vnoise(Q, 17, 14)
    n3 = 0.7 * vnoise(Q, 26, 15) + 0.3 * vnoise(Q, 52, 16)
    c = np.tile(C_TAN, (len(Y), 1)).astype(float)
    c = mix(c, C_OLIVE, sstep(0.47, 0.53, n1))
    c = mix(c, C_BROWN, sstep(0.55, 0.60, n2))
    c = mix(c, C_BLACK, sstep(0.60, 0.65, n3))
    wv = 0.5 + 0.5 * np.sin(Y * 650 + phi * 60)
    c *= (0.95 + 0.07 * wv)[:, None]; c += rng.normal(0, 1.8, c.shape)
    fr = front.astype(float); bk = (~front).astype(float)
    # plain drab: waistband, hem band, knee patches
    c = mix(c, C_DRAB, sstep(1.058, 1.064, Y) * 0.92)
    c = mix(c, C_DRAB, sstep(0.178, 0.168, Y) * 0.92)
    kn = sstep(1.0, 0.55, np.abs(phi + math.pi / 2)) * sstep(0.405, 0.43, Y) * sstep(0.60, 0.575, Y)
    c = mix(c, C_DRAB * (0.92 + 0.12 * wv[:, None]), kn * 0.9)
    arc = np.abs(phi) * a; inner = (np.pi - np.abs(phi)) * a
    st = np.zeros(len(Y))
    for yy in (1.064, 1.137):
        st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - yy)))
    for yy in (0.168, 0.200):                                                                        # hem band + drawcord channel
        st = np.maximum(st, sstep(0.0045, 0.0012, np.abs(Y - yy)))
    st = np.maximum(st, sstep(0.0032, 0.0008, arc) * sstep(0.20, 0.205, Y) * sstep(1.06, 1.055, Y))
    st = np.maximum(st, sstep(0.0032, 0.0008, inner) * sstep(0.20, 0.205, Y) * sstep(0.84, 0.80, Y))
    pocket = [(0.088, 1.060), (0.100, 1.015), (0.126, 0.978), (0.158, 0.958), (0.190, 0.958)]       # slanted front pockets + fly
    st = np.maximum(st, line_mask(P, pocket, 0.0050) * fr)
    st = np.maximum(st, line_mask(P, [(0.014, 1.060), (0.014, 0.950), (0.026, 0.920)], 0.0048) * fr)
    st = np.maximum(st, line_mask(P, [(0.0, 1.06), (0.0, 0.93)], 0.0035) * fr * 0.55)
    flap = [(0.030, 0.995), (0.030, 0.905), (0.126, 0.905), (0.126, 0.995), (0.030, 0.995)]         # back pockets with flap
    st = np.maximum(st, line_mask(P, flap, 0.0048) * bk)
    st = np.maximum(st, line_mask(P, [(0.030, 0.968), (0.126, 0.968)], 0.0048) * bk)
    st = np.maximum(st, line_mask(P, [(0.0, 1.035), (0.07, 1.015), (0.19, 1.03)], 0.0045) * bk * 0.8)  # back yoke
    kb = sstep(1.0, 0.55, np.abs(phi + math.pi / 2))                                                 # knee patch outline
    st = np.maximum(st, (sstep(0.0045, 0.0012, np.abs(Y - 0.418)) + sstep(0.0045, 0.0012, np.abs(Y - 0.590))) * kb * 0.9)
    c = mix(c, C_THREAD, np.clip(st, 0, 1) * 0.8)
    loops = np.zeros(len(Y))
    for lx, side in ((0.075, front), (0.09, ~front), (0.02, ~front)):
        loops = np.maximum(loops, sstep(0.0075, 0.0045, np.abs(X - lx)) * sstep(1.05, 1.056, Y) * sstep(1.145, 1.138, Y) * side)
    c = mix(c, C_DRAB * 0.8, loops * 0.8)
    c = mix(c, C_BRASS, disc_mask(P, (0.0, 1.100), 0.0105, 0.002) * fr)                              # waist button
    for (rx, ry) in ((0.088, 1.060), (0.190, 0.958)):
        c = mix(c, C_BRASS, disc_mask(P, (rx, ry), 0.0050, 0.002) * fr)
    return np.clip(c, 0, 255)

T_BLACK = np.array([36, 38, 47.]); T_RIB = np.array([16, 16, 20.]); T_WHITE = np.array([238, 238, 242.]); T_GREY = np.array([84, 86, 94.])

def track_bump(Y, T, px, pz, cx, cz, a, b):
    bump = np.zeros_like(Y)
    bump += 0.006 * sstep(1.06, 1.068, Y) * sstep(1.145, 1.14, Y) * (0.8 + 0.2 * np.sin(Y * 900))   # elastic waistband
    bump += 0.010 * np.exp(-((Y - 0.265) / 0.040) ** 2)                                              # jogger blousing over the cuff
    bump += 0.004 * sstep(0.205, 0.195, Y)                                                           # ribbed cuff
    return bump

def track_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)
    fr = front.astype(float); bk = (~front).astype(float)
    c = np.tile(T_BLACK, (len(Y), 1)).astype(float)
    wv = 0.5 + 0.5 * np.sin(Y * 820 + phi * 40)
    c *= (0.94 + 0.12 * wv)[:, None]; c += rng.normal(0, 1.4, c.shape)
    # white double side stripes along the outer seam (signed distance round the leg from the seam)
    s = phi * a
    stripe = np.maximum(sstep(0.0075, 0.0030, np.abs(s + 0.0265)), sstep(0.0075, 0.0030, np.abs(s + 0.0635)))   # centred a little towards the front so they show in the shop picture
    stripe *= sstep(0.205, 0.215, Y) * sstep(1.05, 1.04, Y)
    c = mix(c, T_WHITE, stripe * 0.96)
    # elastic waistband, drawcord and eyelets
    wb = sstep(1.058, 1.064, Y)
    rib = 0.5 + 0.5 * np.sin(Y * 1500)
    c = mix(c, T_RIB * (0.9 + 0.35 * rib[:, None]), wb * 0.95)
    cord = np.maximum(line_mask(P, [(0.030, 1.100), (0.036, 1.045), (0.044, 1.000)], 0.0042), line_mask(P, [(0.012, 1.100), (0.060, 1.085), (0.058, 1.068)], 0.0042)) * fr
    c = mix(c, T_WHITE, cord * 0.95)
    for ex in (0.034, 0.064):
        c = mix(c, T_GREY * 1.4, disc_mask(P, (ex, 1.104), 0.0055, 0.002) * fr)
    c = mix(c, T_WHITE, disc_mask(P, (0.044, 0.998), 0.0055, 0.002) * fr)
    # ribbed ankle cuff with a thin white piping
    cuff = sstep(0.205, 0.195, Y)
    crib = 0.5 + 0.5 * np.sin(phi * a * 520)
    c = mix(c, T_RIB * (0.85 + 0.45 * crib[:, None]), cuff * 0.96)
    c = mix(c, T_WHITE, sstep(0.0035, 0.0010, np.abs(Y - 0.2075)) * 0.9)
    st = np.zeros(len(Y))
    inner = (np.pi - np.abs(phi)) * a
    st = np.maximum(st, sstep(0.0032, 0.0008, inner) * sstep(0.21, 0.215, Y) * sstep(0.84, 0.80, Y))   # inseam
    st = np.maximum(st, line_mask(P, [(0.0, 1.06), (0.0, 0.93)], 0.0035) * fr * 0.5)
    st = np.maximum(st, line_mask(P, [(0.150, 1.050), (0.170, 0.995), (0.176, 0.940)], 0.0040) * fr * 0.8)  # zip pocket slit
    st = np.maximum(st, line_mask(P, [(0.040, 0.978), (0.120, 0.978)], 0.0045) * bk)                        # back zip pocket
    c = mix(c, T_GREY, np.clip(st, 0, 1) * 0.7)
    c = mix(c, T_GREY * 1.3, disc_mask(P, (0.176, 0.940), 0.0045, 0.002) * fr)
    return np.clip(c, 0, 255)

# ------------------------------------------------------------------------------------------------ PANTS 5 + 6
# TARTAN: slim tapered leg. Every number is at least as loose as the tightest of the four earlier pants at the same height, so it clears the body / boots exactly like they do.
TARTAN = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .090), (0.19, .088), (0.26, .072), (0.40, .066), (0.54, .054), (0.65, .028), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .196), (0.19, .200), (0.26, .206), (0.40, .200), (0.54, .200), (0.65, .204), (0.78, .200), (0.86, .196), (0.95, .188), (1.05, .176), (1.14, .166)],
    z0=[(0.14, -.040), (0.19, -.042), (0.26, -.066), (0.40, -.080), (0.54, -.100), (0.65, -.112), (0.78, -.114), (0.86, -.115), (0.95, -.120), (1.05, -.128), (1.14, -.135)],
    z1=[(0.14, .100), (0.19, .101), (0.26, .102), (0.40, .088), (0.54, .082), (0.65, .082), (0.78, .088), (0.86, .098), (0.95, .122), (1.05, .134), (1.14, .126)],
    n=2.5)
# EMBER: straight leg with a bootcut flare from the knee down (only ever looser than the earlier pants), so the flames at the hem have room
EMBER = dict(
    hem=0.14, top=1.14,
    x0=[(0.14, .052), (0.19, .054), (0.30, .060), (0.45, .060), (0.54, .050), (0.65, .026), (0.78, .006), (0.86, -.02), (0.95, -.05), (1.05, -.06), (1.14, -.06)],
    x1=[(0.14, .246), (0.19, .240), (0.30, .226), (0.45, .216), (0.54, .216), (0.65, .220), (0.78, .214), (0.86, .204), (0.95, .190), (1.05, .178), (1.14, .168)],
    z0=[(0.14, -.088), (0.19, -.086), (0.30, -.082), (0.45, -.088), (0.54, -.110), (0.65, -.120), (0.78, -.121), (0.86, -.121), (0.95, -.123), (1.05, -.129), (1.14, -.135)],
    z1=[(0.14, .126), (0.19, .120), (0.30, .104), (0.45, .090), (0.54, .088), (0.65, .088), (0.78, .094), (0.86, .102), (0.95, .122), (1.05, .134), (1.14, .126)],
    n=2.5)

# ---- TARTAN: red / black tartan punk pants - slim leg, leather belt with silver buckle + studs, knee straps with buckles, swag chain, ankle zips, black ankle bands
TT_RED = np.array([176, 26, 38.]); TT_BLK = np.array([24, 22, 28.]); TT_CRM = np.array([236, 222, 186.]); TT_YEL = np.array([226, 176, 56.])
TT_SILV = np.array([208, 212, 220.]); TT_STEEL = np.array([88, 92, 102.]); TT_LEA = np.array([30, 28, 34.])
SETT = [(TT_RED, 36), (TT_BLK, 18), (TT_RED, 8), (TT_CRM, 5), (TT_RED, 8), (TT_YEL, 5), (TT_RED, 8), (TT_CRM, 5), (TT_RED, 8), (TT_BLK, 18), (TT_RED, 12)]   # thread colours, widths in mm
SETT_P = sum(w for _, w in SETT) / 1000.0
SETT_B = np.cumsum([w for _, w in SETT])[:-1] / 1000.0
SETT_C = np.array([c for c, _ in SETT])

def sett(t, e=0.0016):
    """tartan thread colour for the thread coordinate t (metres); repeats every 131 mm (first and last stripe are both red, so the repeat has no seam)"""
    x = np.mod(t, SETT_P)
    out = np.tile(SETT_C[0], (len(x), 1)).astype(float)
    for i, bnd in enumerate(SETT_B):
        out += sstep(bnd - e, bnd + e, x)[:, None] * (SETT_C[i + 1] - SETT_C[i])
    return out

def tartan_bump(Y, T, px, pz, cx, cz, a, b):
    X = np.abs(px); front = pz < cz
    bump = np.zeros_like(Y)
    bump += 0.0035 * sstep(1.100, 1.106, Y) * sstep(1.145, 1.14, Y)                                  # tartan waistband above the belt
    bump += 0.0050 * sstep(1.054, 1.060, Y) * sstep(1.102, 1.096, Y)                                  # leather belt
    bump += 0.0035 * sstep(0.0165, 0.0125, np.abs(Y - 0.470))                                         # knee straps
    bump += 0.0030 * sstep(0.176, 0.168, Y)                                                           # black ankle band
    bump += 0.0030 * sstep(0.040, 0.034, np.abs(X - 0.080)) * sstep(0.998, 0.992, Y) * sstep(0.900, 0.906, Y) * (~front)   # back patch pockets
    return bump

def tartan_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)
    fr = front.astype(float); bk = (~front).astype(float)
    u = phi * 0.105                                   # distance round the leg (m): 0 = outer seam, < 0 towards the front
    S = np.stack([u, Y], 1)                           # surface coordinates of the leg (round, up)
    # tartan = warp threads round the leg + weft threads along it, mixed 50 / 50, plus a fine twill
    c = 0.5 * (sett(u + 0.052) + sett(Y + 0.020))
    tw = 0.5 + 0.5 * np.sin((u + Y) * 1900)
    c *= (0.92 + 0.12 * tw)[:, None]; c += rng.normal(0, 1.3, c.shape)
    c = mix(c, TT_BLK, sstep(0.178, 0.170, Y) * 0.96)                                                 # black ankle band
    # leather belt, stitching, belt loops, studs, silver buckle
    belt = sstep(1.054, 1.060, Y) * sstep(1.102, 1.096, Y)
    leather = TT_LEA * (0.9 + 0.25 * (0.5 + 0.5 * np.sin(phi * 70 + Y * 400)))[:, None]
    c = mix(c, leather, belt * 0.98)
    bst = np.maximum(sstep(0.0026, 0.0007, np.abs(Y - 1.0625)), sstep(0.0026, 0.0007, np.abs(Y - 1.0935)))
    c = mix(c, TT_STEEL, bst * belt * 0.55)
    loops = np.zeros(len(Y))
    for lx, side in ((0.075, front), (0.105, ~front), (0.03, ~front)):
        loops = np.maximum(loops, sstep(0.0075, 0.0045, np.abs(X - lx)) * sstep(1.048, 1.054, Y) * sstep(1.110, 1.104, Y) * side)
    c = mix(c, TT_BLK, loops * 0.9)
    for sx in (0.050, 0.108, 0.140):
        c = mix(c, TT_SILV, disc_mask(P, (sx, 1.0785), 0.0050, 0.0015) * fr)
    frame = rect_mask(P, -0.02, 0.020, 1.052, 1.106, 0.0012) * (1 - rect_mask(P, -0.02, 0.0125, 1.061, 1.097, 0.0012))
    c = mix(c, TT_SILV, np.maximum(frame, line_mask(P, [(0.0, 1.0785), (0.0155, 1.0785)], 0.0028)) * fr)
    # slanted front pockets with black piping, fly
    pocket = [(0.088, 1.052), (0.100, 1.012), (0.126, 0.976), (0.158, 0.957), (0.190, 0.957)]
    c = mix(c, TT_BLK, line_mask(P, pocket, 0.0052) * fr * 0.95)
    c = mix(c, TT_BLK, np.maximum(line_mask(P, [(0.0, 1.052), (0.0, 0.93)], 0.0038), line_mask(P, [(0.020, 1.052), (0.020, 0.965), (0.012, 0.940), (0.0, 0.930)], 0.0030)) * fr * 0.9)
    # back patch pockets with silver rivets, centre seam
    bpk = [(0.040, 1.000), (0.040, 0.900), (0.122, 0.900), (0.122, 1.000), (0.040, 1.000)]
    c = mix(c, TT_BLK, line_mask(P, bpk, 0.0046) * bk * 0.95)
    for rx in (0.040, 0.122):
        c = mix(c, TT_SILV, disc_mask(P, (rx, 0.994), 0.0040, 0.0012) * bk)
    c = mix(c, TT_BLK, line_mask(P, [(0.0, 1.052), (0.0, 0.93)], 0.0035) * bk * 0.6)
    # knee straps with a silver buckle on the front-outer side and studs
    strap = sstep(0.0165, 0.0125, np.abs(Y - 0.470))
    c = mix(c, leather, strap * 0.98)
    c = mix(c, TT_STEEL, sstep(0.0022, 0.0006, np.abs(np.abs(Y - 0.470) - 0.0095)) * strap * 0.6)
    Pb = np.stack([(phi + 0.50) * 0.105, Y - 0.470], 1)
    bframe = rect_mask(Pb, -0.0165, 0.0165, -0.0155, 0.0155, 0.0012) * (1 - rect_mask(Pb, -0.0100, 0.0100, -0.0092, 0.0092, 0.0012))
    c = mix(c, TT_SILV, np.maximum(bframe, line_mask(Pb, [(0.0, -0.0155), (0.0, 0.0155)], 0.0026)))
    for q in (-0.062, -0.040, 0.040, 0.062):
        c = mix(c, TT_SILV, disc_mask(Pb, (q, 0.0), 0.0040, 0.0012))
    # swag chain on the outer-front hip
    for i, t in enumerate(np.linspace(0, 1, 17)):
        cs = 0.020 - 0.135 * t; cy = 1.075 - 0.165 * math.sin(math.pi * t) ** 0.9
        c = mix(c, TT_SILV if i % 2 == 0 else TT_STEEL * 1.5, disc_mask(S, (cs, cy), 0.0058, 0.0016))
    # ankle zip on the outer seam
    zp = rect_mask(S, -0.0060, 0.0060, 0.195, 0.400, 0.0010)
    c = mix(c, TT_BLK, rect_mask(S, -0.0100, 0.0100, 0.195, 0.400, 0.0010) * 0.85)
    c = mix(c, mix(TT_STEEL, TT_SILV, 0.5 + 0.5 * np.sin(Y * 2 * math.pi / 0.018)), zp * 0.95)
    c = mix(c, TT_SILV, disc_mask(S, (0.0, 0.410), 0.0068, 0.0015))
    return np.clip(c, 0, 255)

# ---- EMBER: black bootcut pants with red / orange / yellow flames licking up from the hem (smoky glow + a few embers above), orange piping
E_BASE = np.array([30, 25, 32.]); E_GLOW = np.array([112, 22, 16.]); E_DARK = np.array([20, 18, 24.])
E_RED_DK = np.array([146, 18, 12.]); E_RED = np.array([226, 56, 16.]); E_ORG_DK = np.array([236, 100, 14.]); E_ORG = np.array([255, 158, 26.])
E_YEL = np.array([255, 232, 104.]); E_WHT = np.array([255, 250, 216.]); E_PIPE = np.array([255, 128, 24.])
FLAME_C = 2 * math.pi * 0.105

def _flames():
    r = np.random.RandomState(31); out = []
    for fam, (nn, hmin, hmax, hwmin, hwmax) in enumerate(((9, 0.24, 0.44, 0.030, 0.040), (9, 0.12, 0.24, 0.020, 0.028))):
        for k in range(nn):
            sc = -FLAME_C / 2 + (k + (0.5 if fam == 0 else 1.0) + r.uniform(-0.16, 0.16)) * FLAME_C / nn
            hgt = r.uniform(hmin, hmax) * (0.72 + 0.34 * math.cos(sc / 0.105 + math.pi / 2))      # tallest on the front of the leg
            out.append((sc, hgt, r.uniform(hwmin, hwmax), r.uniform(-1, 1)))
    return out
FLAMES = _flames()

def flame_layer(u, Y, sh, sw):
    """union of all flame tongues (height scale sh, width scale sw): tapered, leaning a little sideways towards the tip"""
    y0 = 0.125; m = np.zeros(len(Y))
    for (sc, hgt, hw, lean) in FLAMES:
        H = hgt * sh; t = (Y - y0) / H; tt = np.clip(t, 0, 1)
        d = u - sc - lean * 0.045 * tt ** 1.8
        d = (d + FLAME_C / 2) % FLAME_C - FLAME_C / 2
        w = hw * sw * (1 - tt) ** 0.85
        m = np.maximum(m, sstep(w + 0.0035, w - 0.0035, np.abs(d)) * sstep(1.03, 0.97, t))
    return m

def ember_bump(Y, T, px, pz, cx, cz, a, b):
    X = np.abs(px); front = pz < cz
    bump = np.zeros_like(Y)
    bump += 0.0055 * sstep(1.062, 1.070, Y) * sstep(1.145, 1.14, Y) * (0.85 + 0.15 * np.sin(Y * 900))   # ribbed waistband
    bump += 0.0030 * sstep(0.172, 0.165, Y)                                                            # hem
    bump += 0.0035 * sstep(0.044, 0.036, np.abs(X - 0.080)) * sstep(0.998, 0.992, Y) * sstep(0.900, 0.906, Y) * (~front)   # back pockets
    return bump

def ember_color(pos, T, cx, cz, a, b):
    X = np.abs(pos[:, 0]); Y = pos[:, 1]; Z = pos[:, 2]; front = Z < cz; P = np.stack([X, Y], 1)
    phi = np.arctan2(Z - cz, X - cx)
    fr = front.astype(float); bk = (~front).astype(float)
    u = phi * 0.105
    Q = pos + (np.array([3.7, 1.3, 2.1]) if CUR_LEFT else 0.0)
    wv = 0.5 + 0.5 * np.sin(Y * 700 + phi * 55)
    c = np.tile(E_BASE, (len(Y), 1)).astype(float)
    c *= (0.92 + 0.12 * wv)[:, None]; c += rng.normal(0, 1.5, c.shape)
    # smoky red glow round the lower leg, then a few embers drifting above the flames
    glow = sstep(0.70, 0.16, Y) * (0.55 + 0.45 * vnoise(Q, 14, 51))
    c = mix(c, E_GLOW, glow * 0.50)
    spark = sstep(0.80, 0.88, vnoise(Q, 60, 53)) * sstep(0.82, 0.40, Y) * sstep(0.30, 0.38, Y)
    c = mix(c, E_YEL, spark * 0.9)
    # flames: red outer, orange middle, yellow inner, pale core (darker towards the tips)
    g = sstep(0.52, 0.16, Y)
    c = mix(c, mix(E_RED_DK, E_RED, g), flame_layer(u, Y, 1.00, 1.00))
    c = mix(c, mix(E_ORG_DK, E_ORG, g), flame_layer(u, Y, 0.78, 0.60))
    c = mix(c, mix(E_ORG, E_YEL, g), flame_layer(u, Y, 0.55, 0.34))
    c = mix(c, E_WHT, flame_layer(u, Y, 0.30, 0.16) * 0.9)
    # ribbed black waistband, orange piping, slanted pockets, fly, back pockets, loops, button
    rib = 0.5 + 0.5 * np.sin(Y * 1500)
    c = mix(c, E_DARK * (0.9 + 0.35 * rib[:, None]), sstep(1.062, 1.070, Y) * 0.96)
    c = mix(c, E_PIPE, np.maximum(sstep(0.0030, 0.0008, np.abs(Y - 1.064)), sstep(0.0030, 0.0008, np.abs(Y - 1.137))) * 0.9)
    pocket = [(0.088, 1.058), (0.100, 1.013), (0.126, 0.976), (0.158, 0.957), (0.190, 0.957)]
    c = mix(c, E_PIPE, line_mask(P, pocket, 0.0046) * fr * 0.85)
    c = mix(c, E_BASE * 2.4, line_mask(P, [(0.0, 1.058), (0.0, 0.93)], 0.0036) * fr * 0.7)
    bpk = [(0.040, 1.000), (0.040, 0.900), (0.122, 0.900), (0.122, 1.000), (0.040, 1.000)]
    c = mix(c, E_PIPE, line_mask(P, bpk, 0.0042) * bk * 0.85)
    loops = np.zeros(len(Y))
    for lx, side in ((0.075, front), (0.105, ~front), (0.03, ~front)):
        loops = np.maximum(loops, sstep(0.0075, 0.0045, np.abs(X - lx)) * sstep(1.060, 1.066, Y) * sstep(1.146, 1.140, Y) * side)
    c = mix(c, E_BASE * 1.6, loops * 0.8)
    c = mix(c, E_PIPE, disc_mask(P, (0.0, 1.102), 0.0105, 0.002) * fr)
    c = mix(c, E_DARK, disc_mask(P, (0.0, 1.102), 0.0050, 0.002) * fr)
    return np.clip(c, 0, 255)

ITEMS = [
    dict(id='jeans_blue_denim', name='BLUE DENIM JEANS', cat='pants', kind='pants',
         spec=dict(prof=JEANS, bump=jeans_bump, color=jeans_color), view=dict(yaw=math.pi, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
    dict(id='cargo_pants_charcoal', name='CHARCOAL CARGO PANTS', cat='pants', kind='pants',
         spec=dict(prof=CARGO, bump=cargo_bump, color=cargo_color), view=dict(yaw=math.pi, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
    dict(id='tee_desert_stripe', name='DESERT STRIPE TEE', cat='top', kind='shirt',
         spec=dict(hem=0.93, grow=0.012, wide=0.016, deep=0.012, wide_hem=0.010, sleeve=A_SLEEVE, r_mid=0.080, r_end=0.088, bump=tee_a_bump, color=tee_a_color),
         view=dict(yaw=math.pi, pitch=0.03, center=(0, 1.40, 0), scale=1.60)),
    dict(id='tee_crimson_crosshair', name='CROSSHAIR V-NECK', cat='top', kind='shirt',
         spec=dict(hem=0.97, grow=0.004, wide=-0.012, deep=-0.010, sleeve=B_SLEEVE, r_mid=0.066, r_end=0.062, bump=tee_b_bump, color=tee_b_color, vneck=(1.62, 0.105)),
         view=dict(yaw=math.pi, pitch=0.03, center=(0, 1.40, 0), scale=1.60)),
    dict(id='camo_olive_combat', name='OLIVE CAMO PANTS', cat='pants', kind='pants',
         spec=dict(prof=CAMO, bump=camo_bump, color=camo_color), view=dict(yaw=math.pi, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
    dict(id='track_black_stripe', name='BLACK TRACK PANTS', cat='pants', kind='pants',
         spec=dict(prof=TRACK, bump=track_bump, color=track_color, K=96), view=dict(yaw=math.pi + 0.55, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
    dict(id='tartan_red_punk', name='RED TARTAN PANTS', cat='pants', kind='pants',
         spec=dict(prof=TARTAN, bump=tartan_bump, color=tartan_color, K=96), view=dict(yaw=math.pi + 0.45, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
    dict(id='flame_ember_black', name='EMBER FLAME PANTS', cat='pants', kind='pants',
         spec=dict(prof=EMBER, bump=ember_bump, color=ember_color, K=96), view=dict(yaw=math.pi, pitch=0.05, center=(0, 0.64, 0), scale=1.55)),
]

# ------------------------------------------------------------------------------------------------ packing
def render_thumb(mesh, view, size):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import _render_preview as rd
    cv = rd.Canvas(size, size, ss=2)
    cam = dict(yaw=view['yaw'], pitch=view['pitch'], dist=4.0, center=view['center'], scale=view['scale'] * size * 2 * 0.45)
    cv.draw(mesh.pos, rd.normals(mesh.pos, mesh.idx), mesh.idx, mesh.col / 255.0, cam)
    return cv

def pack():
    sj = os.path.join(STORE, 'store.json')
    j = json.load(open(sj))
    ids = {it['id'] for it in ITEMS}
    old_items = [it for it in j['items'] if it['id'] not in ids]
    # keep the old blob bytes only up to the end of the old items (drop a previous run of this tool)
    blob = bytearray(base64.b64decode(j['bin']))
    if len(old_items) != len(j['items']):
        end = 0
        for it in old_items:
            n = it['n']; ni = it['i']
            for off in it['fit'].values(): end = max(end, off + n * 12)
            end = max(end, it['col'] + n * 3, it['sw'] + n * 4, it['idx'] + ni * 2)
        blob = blob[:(end + 3) // 4 * 4]
    new_items = []
    for it in ITEMS:
        m = make_pants(it['spec']) if it['kind'] == 'pants' else make_shirt(it['spec'])
        assert m.n() < 65535 and len(m.idx) * 3 % 3 == 0 and m.idx.max() < m.n()
        while len(blob) % 4: blob.append(0)
        idx_off = len(blob); blob += m.idx.astype('<u2').reshape(-1).tobytes()
        si_off = len(blob); blob += m.si.astype('u1').reshape(-1).tobytes()
        sw_off = len(blob); blob += np.clip(np.round(m.sw * 255), 0, 255).astype('u1').reshape(-1).tobytes()
        col_off = len(blob); blob += np.clip(np.round(m.col), 0, 255).astype('u1').reshape(-1).tobytes()
        while len(blob) % 4: blob.append(0)
        pos_off = len(blob); blob += m.pos.astype('<f4').reshape(-1).tobytes()
        big = render_thumb(m, it['view'], 264); big.save(os.path.join(STORE, it['id'] + '.jpg'))
        buf = io.BytesIO()
        Image.open(os.path.join(STORE, it['id'] + '.jpg')).convert('RGB').resize((132, 132), Image.LANCZOS).save(buf, 'JPEG', quality=80)
        # re-save the big picture as a JPEG (Canvas.save writes by extension)
        d = dict(id=it['id'], name=it['name'], cat=it['cat'], price=99, **{'new': True}, thumb=base64.b64encode(buf.getvalue()).decode(),
                 n=int(m.n()), i=int(len(m.idx) * 3), idx=idx_off, si=si_off, sw=sw_off, col=col_off, fit={'survivor': pos_off})
        new_items.append(d)
        print(it['id'], 'verts', m.n(), 'tris', len(m.idx))
    j['items'] = old_items + new_items
    j['bin'] = base64.b64encode(bytes(blob)).decode()
    json.dump(j, open(sj, 'w'), separators=(',', ':'))
    # manifest.txt: every asset file with its size (checked at start-up)
    mp = os.path.join(ASSETS, 'manifest.txt'); lines = open(mp).read().splitlines()
    sizes = {}
    for ln in lines:
        if '|' in ln: sizes[ln.split('|')[0]] = None
    for it in ITEMS: sizes['store/%s.jpg' % it['id']] = None
    sizes['store/store.json'] = None
    out = []
    seen = set()
    for ln in lines:
        if '|' in ln:
            p = ln.split('|')[0]; seen.add(p); out.append('%s|%d' % (p, os.path.getsize(os.path.join(ASSETS, p))) if p in sizes and os.path.exists(os.path.join(ASSETS, p)) else ln)
        else: out.append(ln)
    for p in sizes:
        if p not in seen: out.append('%s|%d' % (p, os.path.getsize(os.path.join(ASSETS, p))))
    had_nl = open(mp).read().endswith('\n')
    open(mp, 'w').write('\n'.join(out) + ('\n' if had_nl else ''))
    print('store.json', os.path.getsize(sj), 'bytes')

if __name__ == '__main__':
    pack()
