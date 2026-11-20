#!/usr/bin/env python3
"""Builds the static Cairn website (cairn/website/*.html) from shared layout + page bodies.
Run:  python3 tools/build_site.py
"""
import pathlib, datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'website'
PAGES = pathlib.Path(__file__).resolve().parent / 'site_pages'
VERSION = '3.0.0'
# Our own site (GitHub Pages). cairn.app belongs to someone else, so nothing may point there.
SITE = 'https://jangidpranshu1989-create.github.io/cairn'
RELEASES = 'https://github.com/jangidpranshu1989-create/cairn/releases/latest/download'

LOGO = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4.3 17.4c0-2 3.4-3.3 7.9-3.3 4.4 0 7.5 1.3 7.5 3.3 0 1.8-3 2.9-7.6 2.9-4.7 0-7.8-1.1-7.8-2.9Z"/><path fill="currentColor" d="M6.6 11c0-1.7 2.3-2.9 5.3-2.9 3 0 4.9 1.1 4.9 2.7 0 1.6-2.1 2.6-5.1 2.6-3 0-5.1-1-5.1-2.4Z"/><path fill="var(--agent)" d="M9.3 5.75c0-1.3 1.3-2.3 3.1-2.3 1.7 0 2.7.9 2.7 2.1 0 1.2-1.2 2-2.9 2-1.8 0-2.9-.7-2.9-1.8Z"/></svg>'

def ic(paths, w=1.8):
    return f'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">{paths}</svg>'

I = {
  'arrow': ic('<path d="M5 12h14M13 6l6 6-6 6"/>'),
  'check': ic('<path d="m5 12.5 4.5 4.5L19 7.5"/>', 2.2),
  'cart': ic('<path d="M3 4h2l2.4 11.2a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 2-1.5L21 8H6"/><circle cx="10" cy="20" r="1.3"/><circle cx="17" cy="20" r="1.3"/>'),
  'speak': ic('<path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>'),
  'spark': ic('<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"/>'),
  'doc': ic('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"/><path d="M14 3v5h5M9 13h6M9 17h4"/>'),
  'map': ic('<circle cx="12" cy="12" r="2.5"/><circle cx="4.5" cy="6" r="1.8"/><circle cx="19.5" cy="6" r="1.8"/><circle cx="4.5" cy="18" r="1.8"/><circle cx="19.5" cy="18" r="1.8"/><path d="m6 7 4 3.5M18 7l-4 3.5M6 17l4-3.5M18 17l-4-3.5"/>'),
  'shot': ic('<rect x="6" y="2.5" width="12" height="19" rx="2"/><path d="M9 7h6M9 11h6M9 15h4"/>'),
  'reader': ic('<path d="M4 5h7a2 2 0 0 1 2 2v12a2 2 0 0 0-2-2H4V5ZM20 5h-7a2 2 0 0 0-2 2v12a2 2 0 0 1 2-2h7V5Z"/>'),
  'phone': ic('<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M11 18.5h2"/>'),
  'lock': ic('<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>'),
  'key': ic('<circle cx="8" cy="15" r="4"/><path d="m11 12 8.5-8.5M16 7l2.5 2.5M14 9l2 2"/>'),
  'shield': ic('<path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.5 7.5 9.5 4.3-1 7.5-4.9 7.5-9.5V6L12 3Z"/><path d="m8.8 12 2.2 2.2 4.3-4.4"/>'),
  'finger': ic('<path d="M12 11v3.5a6 6 0 0 1-1.5 4M8.5 9.5a4 4 0 0 1 7.5 2v2a10 10 0 0 1-1 4.5M6 12.5V11a6 6 0 0 1 9.8-4.6M18 10.5v3a13 13 0 0 1-.7 4.2M4.5 15V11a7.5 7.5 0 0 1 1.3-4.2"/>'),
  'globe': ic('<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3Z"/>'),
  'folder': ic('<path d="M3.5 6.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11Z"/>'),
  'mic': ic('<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/>'),
  'eye': ic('<path d="M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7Z"/><circle cx="12" cy="12" r="3"/>'),
  'bolt': ic('<path d="M13 2.5 4.5 13.5H12l-1 8 8.5-11H12l1-8Z"/>'),
  'robot': ic('<rect x="4.5" y="8" width="15" height="11" rx="3"/><path d="M12 4v4M9 13h.01M15 13h.01M9.5 16.5h5"/>'),
  'info': ic('<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7.5h.01"/>'),
  'mail': ic('<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="m4 7 8 6 8-6"/>'),
  'win': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 5.3 10.4 4.3v7.1H3V5.3Zm0 13.4 7.4 1v-7H3v6Zm8.3 1.1L21 21v-8.4h-9.7v7.2Zm0-15.6v7.2H21V3l-9.7 1.2Z"/></svg>',
  'apple': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M16.4 12.6c0-2.5 2-3.7 2.1-3.8-1.2-1.7-3-1.9-3.6-2-1.5-.2-3 .9-3.8.9-.8 0-2-.9-3.3-.9-1.7 0-3.3 1-4.1 2.5-1.8 3.1-.5 7.6 1.3 10.1.8 1.2 1.8 2.6 3.1 2.5 1.3-.1 1.7-.8 3.2-.8 1.5 0 1.9.8 3.2.8 1.3 0 2.2-1.2 3-2.5.9-1.4 1.3-2.8 1.3-2.8s-2.5-1-2.4-4ZM14 5.3c.7-.8 1.1-1.9 1-3-1 0-2.1.7-2.8 1.5-.6.7-1.2 1.8-1 2.9 1 .1 2.1-.6 2.8-1.4Z"/></svg>',
  'linux': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2c-2.2 0-3.6 1.8-3.6 4.4 0 1.4.3 2.3-.6 3.6-1 1.5-2.7 3.4-2.7 6 0 .8.2 1.4.5 1.9-.6.4-1.6.7-1.6 1.5 0 1.1 1.5 1 2.7 1.4 1.1.4 1.8 1.2 3 1.2 1 0 1.6-.6 2.3-.6.7 0 1.3.6 2.3.6 1.2 0 1.9-.8 3-1.2 1.2-.4 2.7-.3 2.7-1.4 0-.8-1-1.1-1.6-1.5.3-.5.5-1.1.5-1.9 0-2.6-1.7-4.5-2.7-6-.9-1.3-.6-2.2-.6-3.6C15.6 3.8 14.2 2 12 2Zm-1.6 4.2c.4 0 .7.5.7 1.1s-.3 1.1-.7 1.1-.7-.5-.7-1.1.3-1.1.7-1.1Zm3.2 0c.4 0 .7.5.7 1.1s-.3 1.1-.7 1.1-.7-.5-.7-1.1.3-1.1.7-1.1ZM12 9.2c.9 0 2 .6 2 1s-1.1 1.2-2 1.2-2-.8-2-1.2 1.1-1 2-1Z"/></svg>',
  'android': '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7.2 8.6h9.6c.6 0 1 .4 1 1V17c0 .6-.4 1-1 1h-.9v2.6a1.3 1.3 0 0 1-2.6 0V18h-2.6v2.6a1.3 1.3 0 0 1-2.6 0V18h-.9c-.6 0-1-.4-1-1V9.6c0-.6.4-1 1-1ZM4.6 8.6c.7 0 1.3.6 1.3 1.3v5.2a1.3 1.3 0 0 1-2.6 0V9.9c0-.7.6-1.3 1.3-1.3Zm14.8 0c.7 0 1.3.6 1.3 1.3v5.2a1.3 1.3 0 0 1-2.6 0V9.9c0-.7.6-1.3 1.3-1.3ZM15.4 3.1l1-1.6c.1-.2 0-.4-.1-.5-.2-.1-.4 0-.5.1l-1 1.7a6.4 6.4 0 0 0-5.6 0l-1-1.7c-.1-.1-.3-.2-.5-.1-.1.1-.2.3-.1.5l1 1.6A5.3 5.3 0 0 0 6.2 7.6h11.6a5.3 5.3 0 0 0-2.4-4.5ZM9.6 5.8a.6.6 0 1 1 0-1.2.6.6 0 0 1 0 1.2Zm4.8 0a.6.6 0 1 1 0-1.2.6.6 0 0 1 0 1.2Z"/></svg>',
}

NAV = [('features.html', 'Features'), ('download.html', 'Download'), ('pricing.html', 'Pricing'), ('security.html', 'Security'), ('about.html', 'About')]

def header(active):
    links = ''.join(f'<a href="{h}"{" aria-current=\"page\"" if h == active else ""}>{t}</a>' for h, t in NAV)
    mlinks = ''.join(f'<a href="{h}">{t}</a>' for h, t in NAV + [('changelog.html', 'Changelog'), ('contact.html', 'Contact')])
    return f'''<a class="skip" href="#main">Skip to content</a>
<header class="hdr"><div class="wrap">
  <a class="brand" href="index.html" aria-label="Cairn home">{LOGO}<span>Cairn</span></a>
  <nav class="nav" aria-label="Main">{links}</nav>
  <div class="cta">
    <a class="btn sm hide-m" href="contact.html">Contact</a>
    <a class="btn sm primary" href="download.html">Download</a>
    <button class="menu-btn" aria-label="Menu" aria-expanded="false" aria-controls="mnav"><span></span></button>
  </div>
</div></header>
<nav class="mnav" id="mnav" aria-label="Mobile">{mlinks}<a class="btn lg primary" href="download.html">Download Cairn</a></nav>'''

FOOTER = f'''<footer class="ftr"><div class="wrap">
  <div class="grid">
    <div class="about"><a class="brand" href="index.html">{LOGO}<span>Cairn</span></a><p>The agentic browser that understands Hindi, Hinglish and every language you think in. Made in India.</p></div>
    <div><h4>Product</h4><ul><li><a href="features.html">Features</a></li><li><a href="download.html">Download</a></li><li><a href="pricing.html">Pricing</a></li><li><a href="changelog.html">Changelog</a></li></ul></div>
    <div><h4>Platforms</h4><ul><li><a href="download.html#windows">Windows</a></li><li><a href="download.html#mac">macOS</a></li><li><a href="download.html#linux">Linux</a></li><li><a href="download.html#android">Android</a></li></ul></div>
    <div><h4>Company</h4><ul><li><a href="about.html">About</a></li><li><a href="contact.html">Contact</a></li><li><a href="security.html">Security</a></li></ul></div>
    <div><h4>Legal</h4><ul><li><a href="privacy.html">Privacy policy</a></li><li><a href="terms.html">Terms of service</a></li><li><a href="security.html#disclosure">Responsible disclosure</a></li></ul></div>
  </div>
  <div class="base"><span>© <span data-year>2026</span> Cairn. All rights reserved.</span><span>v{VERSION} · Built with Groq &amp; NVIDIA NIM</span></div>
</div></footer>'''

def page(fname, title, desc, body, active=None):
    html = f'''<!doctype html>
<html lang="en" class="no-js">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">
<meta name="theme-color" content="#0F0F0F">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{desc}">
<meta property="og:type" content="website">
<meta property="og:image" content="{SITE}/assets/og.png">
<meta property="og:url" content="{SITE}/{"" if fname == "index.html" else fname}">
<link rel="canonical" href="{SITE}/{"" if fname == "index.html" else fname}">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="assets/favicon.svg" type="image/svg+xml">
<link rel="preload" href="fonts/InterDisplay-Bold.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="fonts/Inter-Regular.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="assets/site.css">
</head>
<body>
{header(active or fname)}
<main id="main">
{body}
</main>
{FOOTER}
<script src="assets/site.js" defer></script>
</body>
</html>
'''
    (ROOT / fname).write_text(html)

def render(src):
    t = src
    for k, v in I.items():
        t = t.replace('{{' + k + '}}', v)
    return t.replace('{{LOGO}}', LOGO).replace('{{VERSION}}', VERSION).replace('{{RELEASES}}', RELEASES).replace('{{SITE}}', SITE).replace('{{DATE}}', datetime.date.today().strftime('%d %B %Y'))

if __name__ == '__main__':
    import re
    for f in sorted(PAGES.glob('*.html')):
        raw = f.read_text()
        m = re.match(r'<!--\s*title:\s*(.*?)\n\s*desc:\s*(.*?)\n(?:\s*active:\s*(.*?)\n)?\s*-->\n', raw, re.S)
        title, desc, active = m.group(1).strip(), m.group(2).strip(), (m.group(3) or '').strip() or None
        page(f.name, title, desc, render(raw[m.end():]), active)
        print('built', f.name)
    (ROOT / 'assets' / 'favicon.svg').write_text(LOGO.replace('currentColor', '#EDEDEB').replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" '))
