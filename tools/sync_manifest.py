#!/usr/bin/env python3
"""Keeps app/src/main/assets/manifest.txt in step with the real asset files.

The game checks every asset size at start-up ("Damaged file: ..." + RETRY otherwise). If an asset (e.g. store/store.json) is
edited and the manifest is forgotten, the game cannot start. This script rewrites the sizes from the real files, so that
can never happen again.   python3 tools/sync_manifest.py        (the GitHub build runs it before Gradle)
It adds assets that are missing from the manifest and stops with an error if a listed file does not exist.
"""
import os, sys, pathlib
A = pathlib.Path(__file__).resolve().parent.parent / 'app/src/main/assets'
mp = A / 'manifest.txt'
lines = [l for l in mp.read_text(encoding='utf-8').splitlines() if l.strip()]
out, seen, changed = [], set(), 0
for l in lines:
    if '|' not in l:
        out.append(l); continue
    p = l.split('|')[0].strip()
    f = A / p
    if not f.is_file():
        print('ERROR: manifest lists a file that does not exist: ' + p); sys.exit(1)
    size = f.stat().st_size
    if l != '%s|%d' % (p, size): changed += 1; print('fixed: %s -> %d bytes' % (p, size))
    out.append('%s|%d' % (p, size)); seen.add(p)
for f in sorted(A.rglob('*')):
    p = f.relative_to(A).as_posix()
    if f.is_file() and p != 'manifest.txt' and p not in seen:
        out.append('%s|%d' % (p, f.stat().st_size)); changed += 1; print('added: ' + p)
mp.write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')
print('manifest OK (%d entries, %d changed)' % (len(seen), changed))
