"""Assets for the launch film, from the app's own mockup generator, palettes and site catalogue."""
import sys, os, re, json, types
import numpy as np
from PIL import Image

ROOT = '/home/claude/couplet-radar'
src = open(f'{ROOT}/tools/mockup-radar/radar.py').read()
src = src[:src.index('render("ref_full.png"')]  # functions only
mod = types.ModuleType('radar'); sys.argv = ['x', '/tmp/radar-unused']
exec(src, mod.__dict__)
OUT = 'assets'

# The app's real palettes (core/radar Palette.kt)
REF = [(5,(4,233,231)),(10,(1,159,244)),(15,(3,0,244)),(20,(2,253,2)),(25,(1,197,1)),(30,(0,142,0)),(35,(253,248,2)),
       (40,(229,188,0)),(45,(253,149,0)),(50,(253,0,0)),(55,(212,0,0)),(60,(188,0,0)),(65,(248,0,253)),(70,(152,84,198)),(75,(253,253,253))]
VEL = [(-40,(170,220,255)),(-30,(40,120,255)),(-15,(20,60,150)),(-2,(70,80,95)),(2,(95,80,70)),(15,(160,70,10)),(30,(255,140,20)),(40,(255,225,170))]
CC = [(0.20,(120,120,120)),(0.45,(20,30,160)),(0.65,(40,160,220)),(0.80,(70,200,90)),(0.90,(240,220,40)),(0.95,(240,140,30)),(0.98,(220,40,40)),(1.05,(250,170,210))]

def color(v, stops, mask, step=None):
    if step:  # palettes are stepped in the app
        v = np.floor(v / step) * step
    xs = np.array([s[0] for s in stops], float); cs = np.array([s[1] for s in stops], float)
    out = np.zeros(v.shape + (4,), np.uint8)
    for ch in range(3):
        out[..., ch] = np.interp(v, xs, cs[:, ch]).astype(np.uint8)
    out[..., 3] = np.where(mask, 255, 0)
    return out

def cc_field(x, y):
    z = mod.reflectivity(x, y)
    M = mod.M
    v = 0.985 + 0.01 * mod.fbm(x, y, 3, seed=9)
    v -= 0.06 * np.clip((z - 52) / 12, 0, 1)                     # hail core
    v -= 0.42 * mod.g(x, y, M[0] + 2, M[1] + 2, 6, 6)             # debris ball
    v -= 0.12 * np.clip((20 - z) / 12, 0, 1)                      # light echo edges
    return v

def make(tag, w, h, ox, oy, scale):
    z = mod.polar_sample(mod.reflectivity, w, h, ox, oy, scale)
    mask = z >= 8
    Image.fromarray(color(z, REF, mask, 5), 'RGBA').save(f'{OUT}/{tag}_ref.png')
    v = mod.polar_sample(mod.velocity, w, h, ox, oy, scale)
    Image.fromarray(color(v, VEL, mask), 'RGBA').save(f'{OUT}/{tag}_vel.png')
    c = mod.polar_sample(cc_field, w, h, ox, oy, scale)
    Image.fromarray(color(c, CC, mask), 'RGBA').save(f'{OUT}/{tag}_cc.png')
    mod.overlays(w, h, ox, oy, scale, 0.55).save(f'{OUT}/{tag}_roads.png')

# Stage: 1920x1080 view centred on the mesocyclone, rendered 1.25x for camera push-ins
make('stage', 2400, 1350, -60.0, 260.0, 2400 / 512 / 2)
# Phone: the full 390x844 portrait framing at 2x
make('phone', 780, 1688, 0.0, 0.0, 1.0)

# Cross-section: synthetic vertical slice through the storm (overhang + weak-echo region)
W, H = 640, 240
xs = np.linspace(0, 1, W)[None, :]; hs = np.linspace(1, 0, H)[:, None]   # top = 15 km
core = 66 * np.exp(-((xs - 0.55) / 0.14) ** 2) * np.exp(-((hs - 0.35) / 0.42) ** 2)
anvil = 34 * np.exp(-((hs - 0.78) / 0.07) ** 2) * (xs > 0.42) * np.exp(-((xs - 0.75) / 0.3) ** 2)
bwer = 30 * np.exp(-((xs - 0.47) / 0.04) ** 2) * np.exp(-((hs - 0.18) / 0.16) ** 2)
noise = 4 * np.sin(xs * 70 + hs * 23) * np.cos(xs * 31 - hs * 41)
zs = np.maximum(core, anvil) - bwer + noise
Image.fromarray(color(zs, REF, zs >= 10, 5), 'RGBA').save(f'{OUT}/xsec.png')

# Radar sites from the app's catalogue
kt = open(f'{ROOT}/core/data/src/main/kotlin/app/hookline/radar/core/data/SiteCatalog.kt').read()
sites = [(float(a), float(b), n) for a, b, n in re.findall(r'LatLon\(([-\d.]+), ([-\d.]+)\), Network\.(\w+)', kt)]
json.dump(sites, open(f'{OUT}/sites.json', 'w'))
print('sites', len(sites))
