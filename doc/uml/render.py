"""Render a UML .dot file to PNG with open (two-line) navigability arrowheads.
Usage: python3 render.py in.dot out.png"""
import math, os, re, subprocess, sys, tempfile
src, out = sys.argv[1], sys.argv[2]
MARK = '#000001'
dot = open(src).read().replace('arrowhead=vee', f'arrowhead=vee arrowsize=1.3 color="{MARK}"')
svg = subprocess.run(['dot', '-Tsvg'], input=dot, capture_output=True, text=True, check=True).stdout

def open_head(m):
    pts = [tuple(map(float, p.split(','))) for p in m.group(1).split()]
    tip, w1, w2 = pts[0], pts[1], pts[-3]
    mid = ((w1[0] + w2[0]) / 2, (w1[1] + w2[1]) / 2)
    return (f'<polyline fill="none" stroke="black" stroke-width="1.2" '
            f'points="{w1[0]},{w1[1]} {tip[0]},{tip[1]} {w2[0]},{w2[1]}"/>'
            f'<line stroke="black" x1="{mid[0]}" y1="{mid[1]}" x2="{tip[0]}" y2="{tip[1]}"/>')
svg, n = re.subn(rf'<polygon fill="{MARK}" stroke="{MARK}" points="([^"]+)"/>', open_head, svg)
svg = svg.replace(MARK, 'black')
w, h = (float(x) for x in re.search(r'<svg width="([\d.]+)pt" height="([\d.]+)pt"', svg).groups())
scale = 1
# Chrome draws text a little wider than Graphviz measured it, so shrink text slightly to stay inside boxes.
svg = re.sub(r'font-size="([\d.]+)"', lambda m: f'font-size="{float(m.group(1)) * 0.96:.2f}"', svg)
# Graphviz 2.43 scales by dpi in both the size and a transform; keep only the size.
svg = re.sub(r'transform="scale\([\d.]+ [\d.]+\) ', 'transform="', svg, count=1)
tmp = tempfile.mkdtemp()
html = os.path.join(tmp, 'page.html')
open(html, 'w').write('<!doctype html><html><body style="margin:0;background:white">'
                      + re.sub(r'width="[\d.]+pt" height="[\d.]+pt"', f'width="{w}px" height="{h}px"', svg, count=1)
                      + '</body></html>')
subprocess.run(['google-chrome', '--headless', '--disable-gpu', '--hide-scrollbars', '--no-sandbox',
                f'--force-device-scale-factor={scale}', f'--window-size={math.ceil(w)},{math.ceil(h)}',
                f'--screenshot={os.path.abspath(out)}', html], capture_output=True, check=True)
print(f'{out}: {n} arrowheads opened')
