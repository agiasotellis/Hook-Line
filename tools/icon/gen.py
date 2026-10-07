"""Logo I (oblique Hook H) as Android vector path data, skew baked into the coordinates."""
import math, sys
T = math.tan(math.radians(-9))

def xf(x, y):  # inner translate(4) -> skewX(-9) -> translate(16)
    x = x + 4
    return x + T * y + 16, y

def arc(cx, cy, r, a0, a1, n=24):  # degrees, y down; a=0 -> +x, 90 -> +y (down)
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

shapes_bone = [
    [(36, 32), (64, 32), (64, 168), (36, 168)],          # left stem
    [(60, 84), (134, 84), (134, 110), (60, 110)],        # crossbar
    [(128, 32), (156, 32)] + arc(118, 130, 38, 0, 180) + arc(118, 130, 10, 180, 0) + [(128, 32)],  # stem + hook
]
shape_red = [(80, 112), (108, 112), (108, 131), (80, 131)]

pts = [xf(*p) for s in shapes_bone + [shape_red] for p in s]
minx, maxx = min(p[0] for p in pts), max(p[0] for p in pts)
miny, maxy = min(p[1] for p in pts), max(p[1] for p in pts)

def path(poly, scale, ox, oy):
    q = [xf(*p) for p in poly]
    return 'M' + ' L'.join(f'{(x - minx) * scale + ox:.2f},{(y - miny) * scale + oy:.2f}' for x, y in q) + ' Z'

def paths(box, size):  # fit art into a box of `box` units, centred in `size`
    s = box / max(maxx - minx, maxy - miny)
    ox = (size - (maxx - minx) * s) / 2; oy = (size - (maxy - miny) * s) / 2
    return [path(p, s, ox, oy) for p in shapes_bone], path(shape_red, s, ox, oy)

def vector(size, box, bone, red, dp):
    b, r = paths(box, size)
    out = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{dp}dp" android:height="{dp}dp"\n    android:viewportWidth="{size}" android:viewportHeight="{size}">']
    for d in b: out.append(f'    <path android:fillColor="{bone}" android:pathData="{d}" />')
    out.append(f'    <path android:fillColor="{red}" android:pathData="{r}" />')
    out.append('</vector>\n')
    return '\n'.join(out)

res = sys.argv[1]
head = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Hookline mark (oblique Hook H). Generated from tools/icon/gen.py; edit there. -->\n'
open(f'{res}/drawable/ic_launcher_foreground.xml', 'w').write(head + vector(108, 48, '#FFE8E4DA', '#FFE4472F', 108))
open(f'{res}/drawable/ic_launcher_monochrome.xml', 'w').write(head + vector(108, 48, '#FFFFFFFF', '#FFFFFFFF', 108))
open(f'{res}/drawable/ic_hookline_mark.xml', 'w').write(head + vector(100, 100, '#FFE8E4DA', '#FFE4472F', 48))
print('bbox', round(maxx - minx, 1), round(maxy - miny, 1))
