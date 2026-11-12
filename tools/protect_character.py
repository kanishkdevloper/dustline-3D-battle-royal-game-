#!/usr/bin/env python3
"""Scrambles / restores the licensed survivor character files (anti-extraction, see Prot.kt).

CGTrader "Royalty Free License (no AI)" allows the model in a game only if end users cannot pull the model
files out of the app. The files in app/src/main/assets/models/character/ are therefore stored scrambled
(same names, same sizes -> manifest.txt stays valid). Prot.kt un-scrambles them in memory.

    python3 tools/protect_character.py protect   # source_assets/character_plain/*  ->  assets/models/character/*
    python3 tools/protect_character.py restore   # assets/models/character/*       ->  source_assets/character_restored/*  (to edit)

source_assets/ is git-ignored: NEVER publish the plain files (that would be re-distributing the model).
"""
import os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAIN = os.path.join(ROOT, 'source_assets', 'character_plain')
OUTD  = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models', 'character')
REST  = os.path.join(ROOT, 'source_assets', 'character_restored')
SALT = 0x5D17E3A9
M = 0xFFFFFFFF

def scramble(path, data):           # symmetric: same call scrambles and un-scrambles
    h = 0x811C9DC5
    for ch in path: h = ((h ^ ord(ch)) * 16777619) & M
    s = (h ^ SALT) & M
    if s == 0: s = 1
    out = bytearray(data)
    for i in range(len(out)):
        s ^= (s << 13) & M; s ^= s >> 17; s ^= (s << 5) & M
        out[i] ^= (s >> 24) & 0xFF
    return bytes(out)

def run(src, dst):
    os.makedirs(dst, exist_ok=True)
    for fn in sorted(os.listdir(src)):
        data = open(os.path.join(src, fn), 'rb').read()
        open(os.path.join(dst, fn), 'wb').write(scramble('models/character/' + fn, data))
        print(fn, len(data))

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('protect', 'restore'): sys.exit(__doc__)
    if sys.argv[1] == 'protect': run(PLAIN, OUTD)
    else: run(OUTD, REST)
