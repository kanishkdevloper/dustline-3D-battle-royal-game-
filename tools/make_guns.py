#!/usr/bin/env python3
"""Builds the 13 gun meshes (assets/models/guns/<id>.gun) from scratch: only boxes and cylinders placed by this script.
No third-party model, no scan, no copy of any game asset is used, so the result is the developer's own work
(generic firearm silhouettes, no logos, no brand marks).

Run from the project root:   python3 tools/make_guns.py
.gun format (little endian), vertex-colour variant (textured = 0):
  int nv | int ni | int textured(0) | float len | float zmin | float my |
  float pos[nv*3] | int8 normal[nv*3] (x127) | uint8 colour[nv*3] | uint16 index[ni]
Game axes: muzzle towards -Z, up +Y, X right.  len = total length, zmin = -len/2, my = height of the sight line.
"""
import numpy as np, struct, os

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'assets', 'models', 'guns')

def rgb(h): return np.array([(h >> 16) & 255, (h >> 8) & 255, h & 255], float) / 255.0
STEEL, DARK, POLY, WOOD, OLIVE, TAN, GLASS = rgb(0x5a5f66), rgb(0x24272b), rgb(0x1a1c1f), rgb(0x6b4627), rgb(0x4a5238), rgb(0x8a7a58), rgb(0x2a4a6a)


class Mesh:
    def __init__(self, length):
        self.L = length; self.P = []; self.N = []; self.C = []; self.I = []; self.n = 0

    def _tri(self, a, b, c, col, inside):
        nrm = np.cross(b - a, c - a); ln = np.linalg.norm(nrm)
        if ln < 1e-12: return
        nrm /= ln
        if np.dot(nrm, (a + b + c) / 3 - inside) < 0: b, c, nrm = c, b, -nrm
        for v in (a, b, c):
            self.P.append(v); self.N.append(nrm); self.C.append(col)
        self.I += [self.n, self.n + 1, self.n + 2]; self.n += 3

    def box(self, x, y, d, sx, sy, sz, col, rx=0.0):
        """box centred at x,y; d = distance from the muzzle tip towards the back (box spans d..d+sz); rx = tilt about X (deg)"""
        zc = -self.L / 2 + d + sz / 2
        h = np.array([sx, sy, sz]) / 2
        c = np.array([[sgn[0] * h[0], sgn[1] * h[1], sgn[2] * h[2]] for sgn in
                      [(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1), (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]])
        a = np.radians(rx); R = np.array([[1, 0, 0], [0, np.cos(a), -np.sin(a)], [0, np.sin(a), np.cos(a)]])
        c = c @ R.T + np.array([x, y, zc]); ctr = np.array([x, y, zc])
        for f in [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (3, 2, 6, 7), (0, 3, 7, 4), (1, 2, 6, 5)]:
            self._tri(c[f[0]], c[f[1]], c[f[2]], col, ctr); self._tri(c[f[0]], c[f[2]], c[f[3]], col, ctr)

    def cyl(self, x, y, d, ln, r0, r1, col, n=10):
        """cylinder along Z from d to d+ln (distance from muzzle), radius r0 at the front end, r1 at the back end"""
        z0 = -self.L / 2 + d; z1 = z0 + ln
        ang = np.linspace(0, 2 * np.pi, n, endpoint=False)
        f = np.stack([x + r0 * np.cos(ang), y + r0 * np.sin(ang), np.full(n, z0)], 1)
        b = np.stack([x + r1 * np.cos(ang), y + r1 * np.sin(ang), np.full(n, z1)], 1)
        ctr = np.array([x, y, (z0 + z1) / 2]); cf = np.array([x, y, z0]); cb = np.array([x, y, z1])
        for i in range(n):
            j = (i + 1) % n
            self._tri(f[i], f[j], b[j], col, ctr); self._tri(f[i], b[j], b[i], col, ctr)
            self._tri(cf, f[i], f[j], col, ctr); self._tri(cb, b[i], b[j], col, ctr)

    def save(self, name, top_offset):
        P = np.array(self.P, float); lo = P.min(0); hi = P.max(0)
        shift = 0.11 - hi[1]; P[:, 1] += shift            # top of the gun at y = 0.11 like the rest of the game
        P[:, 0] -= (lo[0] + hi[0]) / 2                     # centre on X
        my = 0.11 - top_offset
        nv = len(P)
        assert nv < 65536
        N = np.clip(np.round(np.array(self.N) * 127), -127, 127).astype(np.int8)
        C = np.clip(np.round(np.array(self.C) * 255), 0, 255).astype(np.uint8)
        with open(os.path.join(OUT, name + '.gun'), 'wb') as f:
            f.write(struct.pack('<iiifff', nv, len(self.I), 0, self.L, -self.L / 2, my))
            f.write(P.astype('<f4').tobytes()); f.write(N.tobytes()); f.write(C.tobytes())
            f.write(np.array(self.I, '<u2').tobytes())
        print(f'{name:7s} nv={nv:5d} ni={len(self.I):5d} len={self.L:.2f} my={my:.3f}')


def sights(m, d_front, d_rear, y):
    m.box(0, y, d_front, .012, .03, .012, DARK); m.box(0, y, d_rear, .02, .022, .012, DARK)

def grip(m, d, y, col=POLY, tilt=-18, h=.15): m.box(0, y - h / 2, d, .034, h, .04, col, rx=tilt)
def mag(m, d, y, h, w=.03, ln=.045, col=DARK, tilt=0): m.box(0, y - h / 2, d, w, h, ln, col, rx=tilt)
def trigger_guard(m, d, y): m.box(0, y, d, .012, .012, .06, DARK)


# ---------------------------------------------------------------- SMGs
def mp5():
    m = Mesh(.85); m.cyl(0, 0, 0, .04, .02, .02, DARK); m.cyl(0, 0, .04, .25, .011, .011, STEEL)
    m.box(0, 0, .12, .052, .06, .30, POLY); m.box(0, 0, .30, .046, .06, .20, DARK)
    mag(m, .40, -.03, .17, tilt=-8, col=POLY); grip(m, .52, -.03); trigger_guard(m, .46, -.06)
    m.box(0, .005, .49, .03, .05, .36, POLY); sights(m, .06, .30, .045); m.save('mp5', .045); return m
def ump():
    m = Mesh(.95); m.cyl(0, 0, 0, .3, .012, .012, STEEL); m.box(0, 0, .15, .056, .07, .42, OLIVE); m.box(0, .04, .15, .03, .02, .42, DARK)
    mag(m, .42, -.035, .20, w=.034, ln=.05, col=DARK); grip(m, .56, -.035, OLIVE); trigger_guard(m, .50, -.07)
    m.box(0, 0, .57, .03, .07, .38, DARK); m.box(0, -.02, .86, .034, .1, .03, POLY); m.save('ump', .06)
def mp40():
    m = Mesh(.95); m.cyl(0, 0, 0, .06, .016, .016, DARK); m.cyl(0, 0, .06, .28, .02, .02, STEEL)
    m.box(0, 0, .2, .05, .055, .38, DARK); m.box(0, -.02, .35, .045, .03, .1, WOOD)
    m.box(0, -.12, .42, .028, .2, .05, DARK); m.box(0, -.03, .5, .032, .13, .035, WOOD, rx=-12)
    m.box(0, -.01, .58, .026, .035, .34, DARK); m.box(0, -.02, .9, .03, .08, .045, DARK); sights(m, .07, .3, .04); m.save('mp40', .04)
def vector():
    m = Mesh(.78); m.cyl(0, 0, 0, .12, .014, .014, DARK); m.box(0, 0, .06, .062, .075, .45, POLY); m.box(0, .045, .08, .03, .018, .3, DARK)
    mag(m, .24, -.04, .24, w=.032, ln=.045, col=DARK, tilt=-6); grip(m, .38, -.04, POLY, -30, .16)
    m.box(0, 0, .5, .03, .07, .25, DARK); m.box(0, -.02, .72, .036, .09, .03, POLY); sights(m, .07, .32, .062); m.save('vector', .07)
def bizon():
    m = Mesh(1.0); m.cyl(0, 0, 0, .3, .011, .011, STEEL); m.box(0, 0, .12, .05, .07, .40, TAN)
    m.cyl(0, -.1, .22, .34, .045, .045, DARK, n=12)  # long helical-style magazine under the barrel
    grip(m, .52, -.035, WOOD); m.box(0, .005, .56, .028, .07, .42, WOOD); sights(m, .06, .34, .05); m.save('bizon', .06)
def p90():
    m = Mesh(.72); m.cyl(0, -.005, 0, .08, .016, .016, DARK); m.box(0, 0, .04, .075, .095, .58, POLY)
    m.box(0, .06, .06, .05, .03, .5, DARK); m.box(0, .068, .12, .02, .02, .36, STEEL)
    grip(m, .35, -.05, POLY, -10, .12); m.box(0, -.07, .42, .03, .06, .20, POLY); m.box(0, -.01, .62, .06, .1, .08, POLY); m.save('p90', .075)
def vss():
    m = Mesh(1.15); m.cyl(0, 0, 0, .34, .026, .026, DARK, n=12); m.box(0, 0, .28, .05, .07, .30, OLIVE)
    m.cyl(0, .063, .3, .2, .016, .016, DARK); m.cyl(0, .063, .32, .035, .02, .02, GLASS)
    mag(m, .45, -.03, .14, tilt=-6, col=DARK); grip(m, .56, -.03, WOOD); trigger_guard(m, .5, -.06)
    m.box(0, -.01, .6, .026, .09, .55, WOOD); m.box(0, -.015, 1.0, .03, .11, .15, WOOD); m.save('vss', .082)
# ---------------------------------------------------------------- shotgun / DMR / snipers
def m590():
    m = Mesh(1.0); m.cyl(0, 0, 0, .55, .014, .014, STEEL); m.cyl(0, -.034, .06, .34, .016, .016, DARK)
    m.box(0, -.036, .22, .04, .045, .2, WOOD); m.box(0, 0, .5, .046, .07, .26, DARK); m.box(0, .045, .6, .02, .015, .12, STEEL)
    m.box(0, -.01, .75, .03, .08, .25, WOOD); m.box(0, -.025, .93, .034, .1, .045, POLY); trigger_guard(m, .64, -.045)
    m.box(0, .035, .04, .01, .02, .01, DARK); m.save('m590', .03)
def vsk94():
    m = Mesh(1.05); m.cyl(0, 0, 0, .3, .02, .02, DARK, n=12); m.box(0, 0, .26, .052, .07, .3, DARK)
    m.cyl(0, .062, .34, .22, .018, .018, DARK); m.cyl(0, .062, .34, .022, .022, .022, GLASS)
    mag(m, .4, -.035, .14, tilt=-5, col=DARK); grip(m, .53, -.035, POLY); trigger_guard(m, .47, -.065)
    m.box(0, 0, .56, .024, .08, .3, POLY); m.box(0, -.02, .82, .03, .12, .22, POLY); m.save('vsk94', .08)
def kar98k():
    m = Mesh(1.12); m.cyl(0, 0, 0, .62, .012, .012, STEEL); m.box(0, -.01, .0, .03, .05, .6, WOOD)
    m.box(0, 0, .55, .042, .066, .22, DARK); m.box(.04, .015, .66, .05, .012, .012, STEEL)   # bolt handle
    m.box(0, -.01, .5, .036, .06, .5, WOOD); m.box(0, -.035, .74, .04, .09, .06, DARK)
    m.box(0, -.04, .9, .035, .13, .22, WOOD); m.box(0, .035, .04, .01, .018, .01, DARK); m.box(0, .038, .52, .02, .012, .04, DARK); m.save('kar98k', .04)
def m24():
    m = Mesh(1.2); m.cyl(0, 0, 0, .66, .014, .016, DARK, n=12); m.box(0, 0, .5, .046, .07, .24, DARK)
    m.cyl(0, .068, .3, .3, .03, .03, POLY, n=12); m.cyl(0, .068, .27, .03, .036, .036, GLASS, n=12)
    m.box(0, .03, .3, .014, .04, .03, DARK); m.box(0, .03, .52, .014, .04, .03, DARK); m.box(.045, .02, .66, .045, .012, .012, STEEL)
    m.box(0, -.01, .5, .04, .08, .45, OLIVE); m.box(0, -.04, .92, .036, .15, .28, OLIVE); m.save('m24', .098)
def awm():
    m = Mesh(1.25); m.cyl(0, 0, 0, .1, .026, .026, DARK, n=12); m.cyl(0, 0, .1, .55, .015, .017, STEEL, n=12)
    m.box(0, 0, .5, .05, .07, .26, DARK); m.cyl(0, .07, .32, .32, .03, .03, POLY, n=12); m.cyl(0, .07, .29, .03, .037, .037, GLASS, n=12)
    mag(m, .56, -.035, .08, w=.03, ln=.07, col=DARK); m.box(.048, .02, .68, .05, .012, .012, STEEL)
    m.box(0, -.01, .5, .044, .08, .5, OLIVE); m.box(0, -.045, .95, .04, .16, .3, OLIVE); m.box(0, -.12, 1.0, .02, .08, .02, DARK); m.save('awm', .1)
def m82():
    m = Mesh(1.45); m.cyl(0, 0, 0, .12, .05, .05, DARK, n=12); m.cyl(0, 0, .12, .55, .028, .03, STEEL, n=12)
    m.box(0, 0, .5, .1, .12, .5, DARK); m.cyl(0, .105, .38, .4, .035, .035, POLY, n=12); m.cyl(0, .105, .34, .035, .043, .043, GLASS, n=12)
    mag(m, .72, -.075, .13, w=.05, ln=.14, col=DARK); m.box(.075, .03, .88, .06, .014, .014, STEEL)
    m.box(0, .0, .95, .06, .08, .25, POLY); m.box(0, -.04, 1.15, .05, .1, .3, POLY); m.box(0, -.01, 1.4, .07, .22, .05, POLY)
    m.box(.055, -.19, .12, .012, .2, .012, DARK, rx=12); m.box(-.055, -.19, .12, .012, .2, .012, DARK, rx=12); m.save('m82', .152)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for fn in (mp5, ump, mp40, vector, bizon, p90, vss, m590, vsk94, kar98k, m24, awm, m82): fn()
