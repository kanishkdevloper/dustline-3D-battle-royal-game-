#!/usr/bin/env python3
"""Builds the Dustline privacy policy from what the app REALLY contains, so it cannot drift from the app.

    python3 tools/make_privacy_policy.py            # (re)write the policy files
    python3 tools/make_privacy_policy.py --check    # exit 1 if the files are out of date (used by the GitHub build)

Reads:   tools/developer.json            developer name, email, public policy URL
         app/src/main/AndroidManifest.xml  permissions + allowBackup
         app/src/main/res/xml/*.xml        what Android may back up
         app/build.gradle.kts              versionName + every library (SDK) in the app
Writes:  docs/privacy-policy.html          (publish this page, e.g. GitHub Pages)
         PRIVACY_POLICY.md                 same text, readable copy
         app/.../Legal.kt                  in-app privacy text (between the TEXT quotes) + PRIVACY_POLICY_URL
         tools/privacy_state.json          fingerprint + "Last updated" date

"Last updated" changes ONLY when the content of the policy changes (new version, permission, SDK, backup rule, address).
SAFETY NET: the script STOPS with an error when the app has a permission / library / network or sensor API that it does not
know how to describe. That is on purpose: add it to the tables below (and answer the Play Console Data safety form again)
instead of publishing a policy that is wrong.
"""
import hashlib, html, json, pathlib, re, sys, datetime
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECK = '--check' in sys.argv
ANDROID = '{http://schemas.android.com/apk/res/android}'


def die(msg):
    print('PRIVACY POLICY ERROR: ' + msg)
    sys.exit(1)


# ------------------------------------------------------------------ facts read from the project
cfg = json.loads((ROOT / 'tools/developer.json').read_text(encoding='utf-8'))
NAME, EMAIL, URL = cfg['developer_name'].strip(), cfg['contact_email'].strip(), cfg.get('privacy_policy_url', '').strip()
if not NAME or not EMAIL or 'YOUR' in NAME + EMAIL:
    die('tools/developer.json has no developer name / email')
if URL and not URL.startswith('https://'):
    die('privacy_policy_url must start with https://')

gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
APP_VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
PACKAGE = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle).group(1)

manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
perms = [p.get(ANDROID + 'name') for p in manifest.findall('uses-permission')]
app_el = manifest.find('application')
allow_backup = app_el.get(ANDROID + 'allowBackup', 'true') != 'false'

PERM_TEXT = {
    'android.permission.INTERNET': 'Internet: only so that the optional rewarded video ads (Google AdMob) and the ad consent form can load.',
    'android.permission.ACCESS_NETWORK_STATE': 'Network state: only to check whether you are online before an ad is requested.',
    'com.google.android.gms.permission.AD_ID': "Advertising ID (AD_ID): lets Google AdMob use your device's advertising ID for advertising and fraud prevention. You can reset or delete your advertising ID in Android Settings > Privacy > Ads.",
    'android.permission.VIBRATE': 'Vibration: vibration feedback during play (can be switched off in the game settings).',
}
for p in perms:
    if p not in PERM_TEXT:
        die('the manifest has a permission this script cannot describe: %s  -> add it to PERM_TEXT in tools/make_privacy_policy.py '
            'and update the Play Console Data safety answers' % p)

LIBS = re.findall(r'implementation\("([^":]+):([^":]+):([^"]+)"\)', gradle)
lib_lines = []
uses_ads = False
for g, a, v in LIBS:
    key = g + ':' + a
    if key == 'com.google.android.gms:play-services-ads':
        lib_lines.append('Google Mobile Ads SDK (AdMob) %s - shows the optional rewarded video ads.' % v); uses_ads = True
    elif key == 'com.google.android.ump:user-messaging-platform':
        lib_lines.append('Google User Messaging Platform SDK %s - shows the ad consent form where the law requires it.' % v)
    elif key in ('androidx.core:core-ktx', 'androidx.appcompat:appcompat'):
        lib_lines.append('AndroidX %s %s - Google open-source user-interface library (not an advertising or analytics service).' % (a, v))
    else:
        die('the app uses a library this script cannot describe: %s:%s  -> add it to tools/make_privacy_policy.py and the Data safety form' % (key, v))

# backup: what Android may copy
def backup_scopes():
    out = []
    if not allow_backup:
        return None
    for f in ('backup_rules.xml', 'data_extraction_rules.xml'):
        p = ROOT / 'app/src/main/res/xml' / f
        if p.exists():
            for inc in ET.parse(p).getroot().iter('include'):
                out.append((f, inc.get('domain'), inc.get('path')))
    return out
BACKUP = backup_scopes()
backup_has_album = bool(BACKUP) and any(d == 'file' and (pth or '').startswith('album') for _, d, pth in BACKUP)

# SAFETY NET: code that would change the data story must not slip in unnoticed
code = ''
code_ui = ''   # same, but WITHOUT Legal.kt: its text is generated by this script, so it must not vouch for itself
for f in (ROOT / 'app/src/main/java').rglob('*.kt'):
    t = f.read_text(encoding='utf-8')
    t = re.sub(r'/\*.*?\*/', '', t, flags=re.S); t = re.sub(r'//[^\n]*', '', t)
    code += t
    if f.name != 'Legal.kt':
        code_ui += t
RISK = [r'LocationManager', r'FusedLocation', r'ANDROID_ID', r'getDeviceId', r'AdvertisingIdClient', r'FirebaseApp', r'FirebaseAnalytics',
        r'Crashlytics', r'HttpURLConnection', r'OkHttp', r'java\.net\.URL\(', r'MediaRecorder', r'AudioRecord', r'Manifest\.permission\.CAMERA',
        r'READ_CONTACTS', r'TelephonyManager', r'WifiManager', r'getInstalledPackages', r'MediaStore', r'getExternalFilesDir', r'Environment\.getExternalStorage']
hits = [r for r in RISK if re.search(r, code)]
if hits:
    die('the code now uses APIs that the policy does not describe: %s  -> describe them in the policy (edit this script) '
        'and update the Play Console Data safety answers first' % ', '.join(hits))

# CLAIMS THAT MUST BE TRUE IN THE CODE: the policy only says what the code really does
def need(label, what, quoted=True):
    hay = code_ui if quoted else code           # buttons: exact "LABEL" string in the UI code; identifiers: anywhere in the code
    needle = ('"' + label + '"') if quoted else label
    if needle not in hay:
        die('the policy mentions %s, but the code no longer contains it (%s). Fix the app or the policy text in this script.' % (what, label))
for lab, what in (('COPY UID', 'the COPY UID button'), ('INVITE FRIENDS', 'the INVITE FRIENDS button'), ('TAKE SNAPSHOT', 'the TAKE SNAPSHOT button'),
                  ('DELETE ALL MY DATA', 'the DELETE ALL MY DATA button'), ('DATA & PRIVACY SETTINGS', 'the DATA & PRIVACY SETTINGS button')):
    need(lab, what)
if uses_ads:
    need('canRequestAds', 'ads only after the consent check', quoted=False)
    need('RewardedAd.load', 'ad preloading', quoted=False)
teen_cap = 'MAX_AD_CONTENT_RATING_T' in code

# ------------------------------------------------------------------ the text (each section = title, list of blocks; block = str or ('ul', [..]))
S = []
S.append(('Summary', [
    'Dustline is a single-player game that works offline. Your game data (name, player ID, progress, settings, album pictures) is stored on your device. '
    'The game has no accounts and no server of its own. ' +
    ('The game shows optional rewarded video ads provided by Google AdMob; to do this, Google may collect certain device information (see section 4).'
     if uses_ads else 'The game shows no ads.')]))
S.append(('1. Who publishes this game', [
    'Dustline (package %s, app version %s) is published by %s. Contact: %s' % (PACKAGE, APP_VERSION, NAME, EMAIL)]))
S.append(('2. What the game stores on your device', [
    'The game saves the following in its private app storage on your device:',
    ('ul', [
        'the display name you type, a player ID (a random number the game creates) and the date your player was created;',
        'coins, diamonds, XP / level, the characters and outfits you own and the ones you wear, and your wish list;',
        'match statistics: matches played, wins, top-10 finishes, kills, best kills, play time, likes, quits, and a list of your recent matches (date, rank, kills, XP, coins);',
        'your chosen character and hair style / colour;',
        'settings: graphics, difficulty, camera view, sensitivity, sound, vibration, HUD layout and similar options;',
        'photo album: when you tap TAKE SNAPSHOT on the profile screen, the game saves a small JPEG screenshot of the game window in its own storage (only the 12 newest are kept).']),
    'The profile screen also shows the language of your device; this is only displayed on your screen and is not stored or sent. '
    'The game does not send this information to the publisher. The app does not ask for access to your contacts, camera, microphone, location, or your phone\'s photo gallery and storage.']))
S.append(('3. Permissions', [
    'The app declares these Android permissions:',
    ('ul', [PERM_TEXT[p] for p in perms]),
    'The app does not declare any other permission.']))
if uses_ads:
    S.append(('4. Advertising (Google AdMob)', [
        'Dustline contains optional rewarded video ads (the "WATCH VIDEO" button). Ads are served by Google AdMob. When an ad is requested, '
        'Google may collect and process: your device advertising ID, IP address (approximate location), device and operating-system information, '
        'app information, diagnostics and your interactions with ads. Google uses this to deliver and measure ads, to personalise ads where permitted and to prevent fraud.',
        'Ads are requested only when you are online and, where the law requires it (for example in the European Economic Area, the United Kingdom and Switzerland, and in the US states that have privacy laws such as California), '
        'after you made your choice in the consent form shown by Google\'s User Messaging Platform. You can change that choice in the game under Settings > ACCOUNT > DATA & PRIVACY SETTINGS. Where a privacy choices form applies to you (for example in the US states with privacy laws), the same button opens it so that you can change or withdraw your choice, including opting out of personalised ads. If no such form applies to your region, the button tells you so. '
        'You can also limit ad personalisation in your Android settings.',
        'The game loads a video ad in advance, while you are online and after the consent step, so that it is ready when you tap WATCH VIDEO. ' +
        ('Ad requests are limited to content rated Teen (T) or lower. ' if teen_cap else '') +
        'Your consent choice is saved on your device by Google\'s consent SDK.',
        'As an AdMob publisher the developer receives ad performance reports from Google. The game does not give the developer any personal data about you.',
        'Libraries inside the app:',
        ('ul', lib_lines),
        'More: Google - How Google uses data from sites or apps that use its services: https://policies.google.com/technologies/partner-sites . Google Privacy Policy: https://policies.google.com/privacy .']))
else:
    S.append(('4. Advertising', ['The app contains no advertising.', ('ul', lib_lines)]))
S.append(('5. Sharing that you choose', [
    'On the profile screen, "COPY UID" copies your player ID to the clipboard. "INVITE FRIENDS" opens the standard Android share sheet with a short text that contains your display name, level and player ID. '
    'Nothing is sent unless you pick an app and a recipient yourself.']))
if BACKUP is None:
    bk = 'The app switches Android backup off, so Android does not copy the game data to a backup.'
else:
    bk = ('If backups are turned on in your Android settings, Android may include the app\'s saved data (for example name, player ID, progress, settings'
          + (' and, when you move to a new phone by device transfer, the photo album' if backup_has_album else '') +
          ') in your device or Google-account backup so it can be restored on a new phone. This is controlled by your device and your Google account.')
S.append(('6. Device backups', [bk]))
S.append(('7. Deleting your data', [
    'In the game: Settings > ACCOUNT > DELETE ALL MY DATA erases everything the game stored on your device (name, player ID, progress, settings, album). '
    'Uninstalling the game, or choosing "Clear storage" for the app in Android settings, does the same. The game keeps this data on your device only until you delete it, and the publisher cannot see or delete it for you because it is never sent to the publisher. Backups made by Android can be managed in your Google account settings. '
    'Data processed by Google AdMob is governed by Google\'s policies linked above.']))
S.append(('8. Children', [
    'Dustline is intended for players aged 13 and over and is not directed to children under 13. The game contains simulated gun combat against computer-controlled characters. '
    'If you believe a child under 13 has used the game, contact %s.' % EMAIL]))
S.append(('9. Your rights', [
    'Because your game data stays on your device, you can view, change and delete it yourself in the game. Depending on where you live (for example under the GDPR or the CCPA / CPRA) '
    'you may have additional rights regarding data processed by Google; use the controls described in section 4 or contact Google. For questions to the publisher, write to %s.' % EMAIL]))
S.append(('10. Changes to this policy', [
    'When the app changes in a way that affects this policy (new permission, new library, new data), this page is updated and the date above changes. '
    'The version of the app this text describes is shown in section 1.']))
S.append(('11. Contact', ['%s - %s' % (NAME, EMAIL)]))

# ------------------------------------------------------------------ fingerprint -> date
def flat(sections):
    return json.dumps(sections, ensure_ascii=False, sort_keys=True)
digest = hashlib.sha256(flat(S).encode('utf-8')).hexdigest()
sp = ROOT / 'tools/privacy_state.json'
state = json.loads(sp.read_text(encoding='utf-8')) if sp.exists() else {}
if state.get('hash') == digest:
    date = state['date']
else:
    if CHECK:
        die('the policy content changed (new version / permission / library / address). Run:  python3 tools/make_privacy_policy.py  '
            'then publish docs/privacy-policy.html again (and commit the changed files).')
    date = datetime.date.today().strftime('%d %B %Y').lstrip('0')
    state = {'hash': digest, 'date': date}

# ------------------------------------------------------------------ renderers
def esc(s):
    return html.escape(s, quote=False)

def linkify(s):
    s = esc(s)
    s = re.sub(r'(https://[^\s,)]+?)(?=[\s,)]|\s\.|$)', r'<a href="\1">\1</a>', s)
    s = s.replace(EMAIL, '<a href="mailto:%s">%s</a>' % (EMAIL, EMAIL))
    return s

def render_html():
    o = ['<!doctype html>', '<html lang="en">', '<head>', '<meta charset="utf-8">',
         '<meta name="viewport" content="width=device-width, initial-scale=1">', '<title>Dustline - Privacy Policy</title>', '<style>',
         '  body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;max-width:760px;margin:0 auto;padding:24px 18px;line-height:1.6;color:#1b241f;background:#fff}',
         '  h1{font-size:1.7rem} h2{font-size:1.15rem;margin-top:1.8rem}',
         '  .muted{color:#5b6b62} code{background:#eef2ef;padding:1px 5px;border-radius:4px}',
         '  @media (prefers-color-scheme:dark){body{background:#101a16;color:#e6ece8}.muted{color:#9fb0a6}code{background:#1d2b24}a{color:#8fd3a8}}',
         '</style>', '</head>', '<body>', '<h1>Dustline - Privacy Policy</h1>', '<p class="muted">Last updated: %s</p>' % date]
    for i, (title, blocks) in enumerate(S):
        if i == 0:
            o.append('<p><strong>%s:</strong> %s</p>' % (esc(title), linkify(blocks[0])))
            continue
        o.append('<h2>%s</h2>' % esc(title))
        for b in blocks:
            if isinstance(b, tuple):
                o.append('<ul>' + ''.join('<li>%s</li>' % linkify(x) for x in b[1]) + '</ul>')
            else:
                o.append('<p>%s</p>' % linkify(b))
    o += ['</body>', '</html>', '']
    return '\n'.join(o)

def render_md():
    o = ['# Dustline - Privacy Policy', '', 'Last updated: %s' % date, '']
    for i, (title, blocks) in enumerate(S):
        if i == 0:
            o += ['**%s:** %s' % (title, blocks[0]), '']; continue
        o += ['## ' + title, '']
        for b in blocks:
            if isinstance(b, tuple):
                o += ['- ' + x for x in b[1]] + ['']
            else:
                o += [b, '']
    return '\n'.join(o)

def render_app():
    o = ['PRIVACY POLICY (app version %s, last updated %s)' % (APP_VERSION, date), '']
    for title, blocks in S:
        o.append(title.upper())
        for b in blocks:
            if isinstance(b, tuple):
                o += ['- ' + x for x in b[1]]
            else:
                o.append(b)
        o.append('')
    if URL:
        o.append('Online version: ' + URL)
    return '\n'.join(o).replace('$', '\\u0024') .rstrip()

# ------------------------------------------------------------------ write / check
legal_p = ROOT / 'app/src/main/java/com/dustline/game/Legal.kt'
legal = legal_p.read_text(encoding='utf-8')
new_legal = re.sub(r'(PRIVACY_POLICY_URL\s*=\s*)"[^"]*"', lambda m: m.group(1) + '"' + URL + '"', legal, count=1)
new_legal = re.sub(r'(SUPPORT_EMAIL\s*=\s*)"[^"]*"', lambda m: m.group(1) + '"' + EMAIL + '"', new_legal, count=1)
new_legal = re.sub(r'(private const val TEXT = """)(.*?)(""")', lambda m: m.group(1) + render_app() + m.group(3), new_legal, count=1, flags=re.S)
if '\\u0024' in new_legal:
    new_legal = new_legal.replace('\\u0024', '${\'$\'}')

outputs = {
    ROOT / 'docs/privacy-policy.html': render_html(),
    ROOT / 'docs/index.html': render_html(),      # same page as index.html: a host (Netlify) then serves it at the site root
    ROOT / 'PRIVACY_POLICY.md': render_md(),
    legal_p: new_legal,
    sp: json.dumps(state, indent=1) + '\n',
}
if CHECK:
    stale = [str(p.relative_to(ROOT)) for p, t in outputs.items() if not p.exists() or p.read_text(encoding='utf-8') != t]
    if stale:
        die('out of date: %s. Run  python3 tools/make_privacy_policy.py  and publish docs/privacy-policy.html again.' % ', '.join(stale))
    print('privacy policy is up to date (%s, app %s)' % (date, APP_VERSION))
    sys.exit(0)
for p, t in outputs.items():
    p.write_text(t, encoding='utf-8')
print('privacy policy written: %s, app %s, %d permissions, %d libraries, last updated %s' % (NAME, APP_VERSION, len(perms), len(LIBS), date))
if not URL:
    print('NOTE: privacy_policy_url in tools/developer.json is empty. Play Console REQUIRES the public https link of docs/privacy-policy.html.')
