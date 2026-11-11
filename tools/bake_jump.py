#!/usr/bin/env python3
"""Bakes the JUMP motion into app/src/main/java/com/dustline/game/JumpData.kt

Motion source: "Human character with jumping animation" by LukeDale65 (Sketchfab), CC-BY-4.0
  https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13
(author / licence / source are written inside the .glb: asset.extras)

Only the MOTION (rotations of 14 joints, sampled at 30 fps and re-targeted to the game's own 12-bone survivor rig) is used.
No mesh, texture or skeleton of that model is shipped in the app.

    python3 tools/bake_jump.py  path/to/human_character_with_jumping_animation.glb  [--preview out.png]

Clip timeline (seconds, 1 unit = 1 s of the original clip):
  0.86  last frame on the ground (take-off)      1.19 apex         1.48 touch-down (game air time of a jump = 0.62 s = 1.48 - 0.86)
  1.48 -> 2.10 landing absorb + recover
"""
import sys, json, struct, math
import numpy as np

T0, T1, FPS = 0.80, 2.20, 30          # baked window
# rest directions of the game rig (from models/character/survivor.json bind + mesh axes), LEFT side; the right side mirrors x
REST_THIGH = np.array([0.0, -0.5, -0.015]); REST_THIGH /= np.linalg.norm(REST_THIGH)
REST_UPPER = np.array([-0.05835415947889361, -0.36471349674308495, 0.021882809804585093]); REST_UPPER /= np.linalg.norm(REST_UPPER)
REST_FORE  = np.array([-0.095, -0.994, -0.045]); REST_FORE /= np.linalg.norm(REST_FORE)

# ------------------------------------------------------------------ glb / fk
def load(path):
    f = open(path, 'rb').read(); off = 12; ch = []
    while off < len(f):
        cl, ct = struct.unpack('<II', f[off:off + 8]); ch.append(f[off + 8:off + 8 + cl]); off += 8 + cl
    return json.loads(ch[0]), ch[1]

def acc(j, b, i):
    a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}[a['type']]
    dt = {5126: np.float32, 5123: np.uint16, 5121: np.uint8, 5125: np.uint32}[a['componentType']]
    o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    return np.frombuffer(b, dtype=dt, count=a['count'] * n, offset=o).reshape(a['count'], n)

def qmat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

def slerp(a, b, t):
    d = float(np.dot(a, b))
    if d < 0: b = -b; d = -d
    if d > 0.9995:
        r = a + (b - a) * t; return r / np.linalg.norm(r)
    th = math.acos(d); return (math.sin((1-t)*th)*a + math.sin(t*th)*b) / math.sin(th)

class Clip:
    def __init__(s, path):
        s.j, s.b = load(path); j = s.j
        s.names = [n.get('name') for n in j['nodes']]; s.idx = {n: i for i, n in enumerate(s.names)}
        an = j['animations'][0]; s.tr = {}; s.ro = {}
        for c in an['channels']:
            sm = an['samplers'][c['sampler']]; nd = c['target']['node']
            (s.tr if c['target']['path'] == 'translation' else s.ro)[nd] = (acc(j, s.b, sm['input'])[:, 0], acc(j, s.b, sm['output']))
        s.par = {}
        for i, n in enumerate(j['nodes']):
            for ch in n.get('children', []): s.par[ch] = i
    @staticmethod
    def _k(ts, t):
        k = min(max(int(np.searchsorted(ts, t)) - 1, 0), len(ts) - 2)
        f = (t - ts[k]) / (ts[k+1] - ts[k]) if ts[k+1] > ts[k] else 0.0
        return k, min(max(f, 0.0), 1.0)
    def local(s, i, t):
        n = s.j['nodes'][i]
        T = np.array(n.get('translation', [0, 0, 0]), float); Q = np.array(n.get('rotation', [0, 0, 0, 1]), float)
        if i in s.tr: ts, v = s.tr[i]; k, f = s._k(ts, t); T = v[k]*(1-f) + v[k+1]*f
        if i in s.ro: ts, v = s.ro[i]; k, f = s._k(ts, t); Q = slerp(v[k].astype(float), v[k+1].astype(float), f)
        M = np.eye(4); M[:3, :3] = qmat(Q); M[:3, 3] = T; return M
    def world(s, t):
        W = {}
        def rec(i):
            if i in W: return W[i]
            p = s.par.get(i); M = s.local(i, t); W[i] = (rec(p) @ M) if p is not None else M; return W[i]
        for i in range(len(s.names)): rec(i)
        return W
    def joints(s, t):
        W = s.world(t); g = lambda k: W[s.idx[[n for n in s.names if n and k in n][0]]][:3, 3]
        out = {}
        for k in ['Hips', 'LeftUpLeg', 'LeftLeg', 'LeftFoot', 'RightUpLeg', 'RightLeg', 'RightFoot', 'Neck',
                  'LeftArm', 'LeftForeArm', 'LeftHand', 'RightArm', 'RightForeArm', 'RightHand']:
            p = g(k + '_') if k not in ('RightArm',) else g('RightArm_00')
            out[k] = np.array([-p[0], p[1], -p[2]])         # glTF (faces +z, left = +x)  ->  game rig (faces -z, left = -x)
        return out

# ------------------------------------------------------------------ rig maths (same order as CharModel.skin: T * Rx * Rz)
def Rx(a): c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
def Rz(a): c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
def unit(v): return v / (np.linalg.norm(v) + 1e-9)

def solve(rest, d, prev):
    """angles (ax, az) with Rx(ax) @ Rz(az) @ rest == d. Of the two solutions the one nearest to prev (or the smaller |az|) is taken."""
    r = math.hypot(rest[0], rest[1]); psi = math.atan2(rest[1], rest[0])
    cz = max(-1.0, min(1.0, d[0] / r)); base = math.acos(cz)
    best = None
    for az in (base - psi, -base - psi):
        az = (az + math.pi) % (2*math.pi) - math.pi
        v1 = Rz(az) @ rest
        ax = math.atan2(d[2], d[1]) - math.atan2(v1[2], v1[1]); ax = (ax + math.pi) % (2*math.pi) - math.pi
        cost = (abs(az - prev[1]) + abs(ax - prev[0])) if prev is not None else abs(az) * 2 + abs(ax) * 0.1
        if best is None or cost < best[0]: best = (cost, ax, az)
    return best[1], best[2]

def unwrap(a, prev):
    if prev is None: return a
    while a - prev > math.pi: a -= 2*math.pi
    while a - prev < -math.pi: a += 2*math.pi
    return a

def mirror(v): return np.array([-v[0], v[1], v[2]])

def retarget(c):
    frames = []; prev = {}
    n = int(round((T1 - T0) * FPS)) + 1
    for i in range(n):
        t = T0 + i / FPS; J = c.joints(t)
        spine = J['Neck'] - J['Hips']; lean = math.atan2(spine[2], spine[1])      # >0 = leaning backwards (rig rotX sign), <0 = forward
        row = {}
        for side, S in (('L', 'Left'), ('R', 'Right')):
            thigh = unit(J[S + 'Leg'] - J[S + 'UpLeg']); shin = unit(J[S + 'Foot'] - J[S + 'Leg'])
            rest_t = REST_THIGH if side == 'L' else mirror(REST_THIGH)
            la, lz = solve(rest_t, thigh, prev.get('t' + side))
            sl = Rz(-lz) @ Rx(-la) @ shin                                               # shin direction in the thigh frame
            lk = math.atan2(-sl[2], -sl[1])
            la = unwrap(la, prev.get('t' + side, (None,))[0]) if 't' + side in prev else la
            # arms live in the torso frame (the torso leans by `lean` around the hip)
            R = Rx(-lean)
            up = R @ unit(J[S + 'ForeArm'] - J[S + 'Arm']); fo = R @ unit(J[S + 'Hand'] - J[S + 'ForeArm'])
            rest_u = REST_UPPER if side == 'L' else mirror(REST_UPPER); rest_f = REST_FORE if side == 'L' else mirror(REST_FORE)
            ax, az = solve(rest_u, up, prev.get('u' + side))
            loc = Rz(-az) @ Rx(-ax) @ fo
            fx, fz = solve(rest_f, loc, prev.get('f' + side))
            prev['t' + side] = (la, lz); prev['u' + side] = (ax, az); prev['f' + side] = (fx, fz)
            row[side] = dict(la=la, lz=lz, lk=lk, ax=ax, az=az, fx=fx, fz=fz)
        frames.append(dict(t=round(t, 4), lean=lean, **{k + s: v for s in 'LR' for k, v in row[s].items()}))
    return frames

ORDER = ['laL', 'lzL', 'lkL', 'laR', 'lzR', 'lkR', 'axL', 'azL', 'fxL', 'fzL', 'axR', 'azR', 'fxR', 'fzR', 'lean']

def write_kotlin(frames, path):
    lines = []
    for f in frames: lines.append('        ' + ', '.join('%.3ff' % f[k] for k in ORDER) + ',')
    src = f'''package com.dustline.game

/**
 * GENERATED by tools/bake_jump.py - do not edit by hand.
 *
 * Jump motion re-targeted onto the game's own 12-bone survivor rig.
 * Motion source: "Human character with jumping animation" by LukeDale65 (https://sketchfab.com/LukeDale65), CC-BY-4.0
 * (http://creativecommons.org/licenses/by/4.0/), https://sketchfab.com/3d-models/human-character-with-jumping-animation-8dc73a1346424e53ad02fb05163ead13
 * Changes: only the joint rotations were sampled (30 fps) and converted to the rig's angles; no mesh / texture / skeleton of that model is used.
 * Frame i = clip time T0 + i / FPS. Columns = {', '.join(ORDER)} (radians).
 */
object JumpData {{
    const val T0 = {T0}f
    const val FPS = {FPS}f
    const val COLS = {len(ORDER)}
    const val N = {len(frames)}
    val D = floatArrayOf(
{chr(10).join(lines)}
    )
}}
'''
    open(path, 'w').write(src)

def preview(frames, c, out):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    ts = [0.86, 1.0, 1.19, 1.35, 1.48, 1.67, 1.9]; fig, ax = plt.subplots(2, len(ts), figsize=(2.2*len(ts), 8))
    for k, t in enumerate(ts):
        f = frames[int(round((t - T0) * FPS))]; J = c.joints(t)
        for row, (ix, name) in enumerate(((2, 'side'), (0, 'front'))):
            a = ax[row, k]; a.set_aspect('equal'); a.set_xlim(-1.3, 1.3); a.set_ylim(-0.2, 2.4); a.set_xticks([]); a.set_yticks([])
            hx = (lambda p: p[2] * 0.01 if ix == 2 else p[0] * 0.01)
            h = J['Hips'] * 0.01; off = h[1]
            def P(p): return (p[2]*0.01 - h[2]*0.01 if ix == 2 else p[0]*0.01, p[1]*0.01 - off + 1.04)
            for a0, b0 in (('Hips', 'Neck'), ('LeftUpLeg', 'LeftLeg'), ('LeftLeg', 'LeftFoot'), ('RightUpLeg', 'RightLeg'), ('RightLeg', 'RightFoot'),
                           ('LeftArm', 'LeftForeArm'), ('LeftForeArm', 'LeftHand'), ('RightArm', 'RightForeArm'), ('RightForeArm', 'RightHand')):
                p, q = P(J[a0]), P(J[b0]); a.plot([p[0], q[0]], [p[1], q[1]], 'k-', lw=2, alpha=0.35)
            # rig FK
            s = (lambda v: v[2] if ix == 2 else v[0])
            lean = f['lean']
            def leg(side, sx):
                la, lz, lk = f['la' + side], f['lz' + side], f['lk' + side]
                hip = np.array([sx, 1.04, 0]); th = Rx(la) @ Rz(lz); knee = hip + th @ np.array([0, -0.5, -0.015]); shin = th @ Rx(lk) @ np.array([0, -1, 0]); return hip, knee, knee + 0.5 * shin
            def arm(side, sx, rest_u, rest_f):
                ax_, az_, fx, fz = f['ax' + side], f['az' + side], f['fx' + side], f['fz' + side]
                sh = np.array([sx, 1.67, 0]); T = np.array([0, 1.04, 0]); Rl = Rx(lean)
                sh = T + Rl @ (sh - T); ru = Rx(ax_) @ Rz(az_); elb = sh + Rl @ (ru @ (rest_u * 0.37)); hand = elb + Rl @ (ru @ Rx(fx) @ Rz(fz) @ (rest_f * 0.30)); return sh, elb, hand
            for side, sx in (('L', -0.12), ('R', 0.12)):
                p0, p1, p2 = leg(side, sx); a.plot([s(p0), s(p1), s(p2)], [p0[1], p1[1], p2[1]], 'r-', lw=2)
            for side, sx, ru, rf in (('L', -0.27, REST_UPPER, REST_FORE), ('R', 0.27, mirror(REST_UPPER), mirror(REST_FORE))):
                p0, p1, p2 = arm(side, sx, ru, rf); a.plot([s(p0), s(p1), s(p2)], [p0[1], p1[1], p2[1]], 'b-', lw=2)
            a.plot([0, 0], [1.04, 1.04 + 0.7*math.cos(lean)], 'r-', lw=2) if ix == 0 else a.plot([0, 0.7*math.sin(lean)], [1.04, 1.04 + 0.7*math.cos(lean)], 'r-', lw=2)
            a.set_title(f't={t:.2f} {name}', fontsize=8)
    plt.tight_layout(); plt.savefig(out, dpi=70)

if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args: sys.exit(__doc__)
    clip = Clip(args[0]); fr = retarget(clip)
    here = __file__.rsplit('/tools/', 1)[0] if '/tools/' in __file__ else '.'
    write_kotlin(fr, here + '/app/src/main/java/com/dustline/game/JumpData.kt')
    print('baked', len(fr), 'frames')
    if '--preview' in sys.argv: preview(fr, clip, sys.argv[sys.argv.index('--preview') + 1])
