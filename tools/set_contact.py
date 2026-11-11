#!/usr/bin/env python3
"""Sets the public privacy-policy link (and, if you want, a new name / email), then rebuilds the policy.

    python3 tools/set_contact.py https://<user>.github.io/<repo>/privacy-policy.html
    python3 tools/set_contact.py "Name" you@mail.com https://<user>.github.io/<repo>/privacy-policy.html

Name + email are stored in tools/developer.json (already filled). The policy itself is written by tools/make_privacy_policy.py."""
import json, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parent.parent
a = sys.argv[1:]
if len(a) not in (1, 3): sys.exit(__doc__)
p = root / 'tools/developer.json'; c = json.loads(p.read_text(encoding='utf-8'))
if len(a) == 3: c['developer_name'], c['contact_email'] = a[0], a[1]
c['privacy_policy_url'] = a[-1]
p.write_text(json.dumps(c, indent=1) + '\n', encoding='utf-8')
subprocess.check_call([sys.executable, str(root / 'tools/make_privacy_policy.py')])
for f in ('PLAY_STORE/store_listing.md',):
    q = root / f; s = q.read_text(encoding='utf-8')
    s = s.replace('`YOUR_EMAIL`', '`%s`' % c['contact_email'])
    q.write_text(s, encoding='utf-8')
