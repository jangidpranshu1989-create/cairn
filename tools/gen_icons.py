"""Generates android Ic.kt (Lucide icons as Compose ImageVectors). Lucide: ISC licence (see assets/LICENSE-Lucide.txt)."""
import re, sys, xml.etree.ElementTree as ET
SRC = sys.argv[1]; OUT = sys.argv[2]
M = dict(
 AccountTree='network', Add='plus', AddToHomeScreen='smartphone', ArrowBack='arrow-left', ArrowForward='arrow-right', ArrowUpward='arrow-up',
 Article='file-text', AutoAwesome='sparkles', BookmarkBorder='bookmark', Cancel='circle-x', CheckCircle='circle-check', ChevronRight='chevron-right',
 Close='x', CloudOff='cloud-off', Code='code', ContentCopy='copy', ContentPaste='clipboard-paste', Contrast='contrast', Cookie='cookie', DarkMode='moon',
 Delete='trash-2', DeleteSweep='eraser', DesktopWindows='monitor', Devices='monitor-smartphone', DoNotDisturbOn='circle-minus', Download='download',
 Edit='pencil', EditNote='square-pen', ExpandLess='chevron-up', ExpandMore='chevron-down', FileDownload='file-down', Fingerprint='fingerprint',
 Flight='plane', FolderOpen='folder-open', FolderZip='folder-archive', FormatSize='a-large-small', Gavel='gavel', GppBad='shield-x', GppGood='shield-check',
 GraphicEq='audio-lines', Headphones='headphones', HelpOutline='circle-question-mark', History='history', HourglassEmpty='hourglass', Https='lock', Image='image',
 ImageSearch='scan-search', Info='info', InsertDriveFile='file', Key='key-round', Keyboard='keyboard', KeyboardArrowDown='chevron-down', KeyboardArrowUp='chevron-up',
 Language='globe', Lightbulb='lightbulb', LocationOn='map-pin', Lock='lock', LockOpen='lock-open', Login='log-in', Logout='log-out', Mail='mail',
 ManageAccounts='user-cog', Memory='cpu', Mic='mic', MoreVert='ellipsis-vertical', More='ellipsis', Movie='film', MusicNote='music', NewReleases='badge-alert', Newspaper='newspaper',
 NorthEast='arrow-up-right', NorthWest='arrow-up-left', Notifications='bell', OpenInNew='external-link', PanTool='hand', Password='rectangle-ellipsis', Pause='pause',
 Payments='wallet', Person='user', PhoneAndroid='smartphone', PictureAsPdf='file-type', PlayArrow='play', Policy='scroll-text', Public='globe', QrCode='qr-code', QrCode2='qr-code',
 QrCodeScanner='scan-qr-code', QuestionAnswer='messages-square', ReadMore='book-open-text', RecordVoiceOver='speech', Refresh='rotate-cw', Replay='rotate-ccw', Replay10='rotate-ccw', Restore='archive-restore',
 Route='route', Schedule='clock', Screenshot='scan', Search='search', Security='shield', Settings='settings', Share='share', Shield='shield', ShoppingBag='shopping-bag',
 Speed='gauge', Star='star', StarOutline='star', Stop='square', Summarize='list', SwapVert='arrow-up-down', Tab='app-window', TextDecrease='a-arrow-down', TextIncrease='a-arrow-up',
 TouchApp='pointer', Translate='languages', Tune='sliders-horizontal', UnfoldLess='fold-vertical', VerifiedUser='shield-check', Videocam='video', Visibility='eye',
 VisibilityOff='eye-off', WarningAmber='triangle-alert', WorkspacePremium='award',
 # new
 Layers='layers', Lasso='lasso', Hotel='hotel', Car='car', Tag='tag', Ticket='ticket-percent', BellRing='bell-ring', TrendingDown='trending-down', Brain='brain',
 Pip='picture-in-picture-2', At='at-sign', Swarm='workflow', Wand='wand-sparkles', Grid='layout-grid', CreditCard='credit-card', Truck='truck', Package='package',
 Sparkle='sparkle', Check='check', Plus='plus', Minus='minus', Send='send', Command='command', Bot='bot', Eye='eye', Crop='crop', Copy='copy', Mouse='mouse-pointer-click',
 Home='house', PanelBottom='panel-bottom', ArrowDown='arrow-down', Sun='sun', Monitor='monitor', Text='text', ScanText='scan-text', Radar='radar', Zap='zap', Book='book-open',
 UserRound='user-round', MapPin='map-pin', Phone='phone', Building='building-2', Percent='percent', BadgePercent='badge-percent', Landmark='landmark', CircleDot='circle-dot',
 Maximize='maximize-2', Minimize='minimize-2', GripH='grip-horizontal', Library='library-big', Calendar='calendar', Undo='undo-2', Feather='feather', Filter='list-filter',
 Paperclip='paperclip', Camera='camera', ThumbUp='thumbs-up', ThumbDown='thumbs-down', Volume='volume-2', FileText='file-text', Images='images', FollowUp='corner-down-right', Link='link', Globe='globe',
)
FILLED = {'Star'}
def f(v): return float(v) if v is not None else 0.0
def el2d(e):
    t = e.tag.split('}')[-1]; a = e.attrib
    if t == 'path': return a['d']
    if t == 'circle':
        cx, cy, r = f(a.get('cx')), f(a.get('cy')), f(a['r'])
        return f"M{cx-r} {cy}a{r} {r} 0 1 0 {2*r} 0a{r} {r} 0 1 0 {-2*r} 0Z"
    if t == 'ellipse':
        cx, cy, rx, ry = f(a.get('cx')), f(a.get('cy')), f(a['rx']), f(a['ry'])
        return f"M{cx-rx} {cy}a{rx} {ry} 0 1 0 {2*rx} 0a{rx} {ry} 0 1 0 {-2*rx} 0Z"
    if t == 'line': return f"M{f(a.get('x1'))} {f(a.get('y1'))}L{f(a.get('x2'))} {f(a.get('y2'))}"
    if t in ('polyline', 'polygon'):
        pts = re.findall(r'-?[\d.]+', a['points']); ps = [(pts[i], pts[i+1]) for i in range(0, len(pts), 2)]
        d = 'M' + ' L'.join(f'{x} {y}' for x, y in ps); return d + ('Z' if t == 'polygon' else '')
    if t == 'rect':
        x, y, w, h = f(a.get('x')), f(a.get('y')), f(a['width']), f(a['height'])
        rx = f(a.get('rx', a.get('ry'))); ry = f(a.get('ry', a.get('rx')))
        if rx == 0: return f"M{x} {y}h{w}v{h}h{-w}Z"
        rx = min(rx, w/2); ry = min(ry, h/2)
        return (f"M{x+rx} {y}h{w-2*rx}a{rx} {ry} 0 0 1 {rx} {ry}v{h-2*ry}a{rx} {ry} 0 0 1 {-rx} {ry}h{-(w-2*rx)}a{rx} {ry} 0 0 1 {-rx} {-ry}v{-(h-2*ry)}a{rx} {ry} 0 0 1 {rx} {-ry}Z")
    raise Exception('unknown ' + t)
out = ['// Generated by tools/gen_icons.py from Lucide (ISC licence, https://lucide.dev). Do not edit by hand.',
       'package app.cairn.browser.ui', '', 'import androidx.compose.ui.graphics.vector.ImageVector', '',
       '@Suppress("unused")', 'object Ic {']
missing = []
for name, lu in M.items():
    try: root = ET.parse(f'{SRC}/{lu}.svg').getroot()
    except FileNotFoundError: missing.append((name, lu)); continue
    ds = [el2d(e) for e in root.iter() if e.tag.split('}')[-1] in ('path','circle','ellipse','line','polyline','polygon','rect')]
    args = ', '.join('"' + d.replace('"', '') + '"' for d in ds)
    out.append(f'    val {name}: ImageVector by lazy {{ lucide("{name}", {"true" if name in FILLED else "false"}, {args}) }}')
out.append('}')
open(OUT, 'w').write('\n'.join(out) + '\n')
if missing: print('MISSING', missing); sys.exit(1)
print('ok', len(M))
