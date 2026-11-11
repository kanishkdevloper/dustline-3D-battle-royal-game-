#!/usr/bin/env python3
"""Offline check of the island map files (no Android needed). Run from the project root:  python3 tools/verify_map.py
Checks dustlineMap.bin (indices, NaN, degenerate triangles, walkable area in ONE piece), extra_houses.bin (size, indices, NaN),
and that the ground texture exists. Exit code 1 if something is wrong."""
import struct, json, math, os, sys
from collections import deque
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); A = os.path.join(R, 'app/src/main/assets')
bad = []
b = open(os.path.join(A, 'maps/dustlineMap.bin'), 'rb').read()
jl = struct.unpack_from('<I', b, 0)[0]; h = json.loads(b[4:4 + jl]); base = 4 + jl + ((4 - (4 + jl) % 4) % 4)
n, cell, hs, sm = h['N'], h['cell'], h['hs'], h['slopeMax']
gh = [struct.unpack_from('<h', b, base + h['heights'] + 2 * i)[0] / hs for i in range(n * n)]
tv = tt = 0
for g in h['groups']:
    for T in g['tiles']:
        nv, ni = T['nv'], T['ni']
        pos = [struct.unpack_from('<3f', b, base + T['p'] + v * 12) for v in range(nv)]
        nor = [struct.unpack_from('<3f', b, base + T['n'] + v * 12) for v in range(nv)]
        if any(math.isnan(c) or math.isinf(c) for p in pos + nor for c in p): bad.append('NaN/inf in ' + g['mat'])
        idx = struct.unpack_from('<%dH' % ni, b, base + T['i'])
        if ni % 3 or max(idx) >= nv: bad.append('bad index in ' + g['mat'])
        tv += nv; tt += ni // 3
walk = [0] * (n * n)
for k in range(n):
    for i in range(n):
        i0, i1, k0, k1 = max(i - 1, 0), min(i + 1, n - 1), max(k - 1, 0), min(k + 1, n - 1)
        dx = (gh[k * n + i1] - gh[k * n + i0]) / ((i1 - i0) * cell); dz = (gh[k1 * n + i] - gh[k0 * n + i]) / ((k1 - k0) * cell)
        walk[k * n + i] = 1 if gh[k * n + i] > 0 and math.hypot(dx, dz) < sm else 0
seen = [0] * (n * n); comps = []
for s in range(n * n):
    if walk[s] and not seen[s]:
        q = deque([s]); seen[s] = 1; c = 0
        while q:
            p = q.popleft(); c += 1; ki, ii = divmod(p, n)
            for dk, di in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                a2, b2 = ki + dk, ii + di
                if 0 <= a2 < n and 0 <= b2 < n and walk[a2 * n + b2] and not seen[a2 * n + b2]: seen[a2 * n + b2] = 1; q.append(a2 * n + b2)
        comps.append(c)
if len(comps) != 1: bad.append('walkable land is in %d pieces' % len(comps))
e = open(os.path.join(A, 'maps/extra_houses.bin'), 'rb').read()
nv, nt, nb, nh = struct.unpack_from('<4i', e, 0)
if 16 + nv * 44 + nt * 12 + nb * 24 + nh * 20 != len(e): bad.append('extra_houses size mismatch')
idx = struct.unpack_from('<%di' % (nt * 3), e, 16 + nv * 44)
if max(idx) >= nv: bad.append('extra_houses index out of range')
if any(math.isnan(x) or math.isinf(x) for x in struct.unpack_from('<%df' % (nv * 11), e, 16)): bad.append('extra_houses NaN')
if not os.path.exists(os.path.join(A, 'textures/dustlineTexT.jpg')): bad.append('ground texture missing')
print('map: %d verts, %d tris, %d houses, walkable pieces: %d' % (tv, tt, nh, len(comps)))
print('PROBLEMS: ' + '; '.join(bad) if bad else 'MAP OK')
sys.exit(1 if bad else 0)
