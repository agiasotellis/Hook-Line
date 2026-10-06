"""Procedurally render plausible supercell radar imagery for design mockups.
Coordinates are CSS px of a 390x844 phone screen; images rendered at 2x."""
import numpy as np
from PIL import Image, ImageDraw
import sys, os

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
S = 2  # pixel ratio
RADAR = np.array([470.0, 1000.0])   # off-screen bottom right
KM_PER_PX = 0.11
M = np.array([196.0, 404.0])        # mesocyclone

rng = np.random.default_rng(7)

def fbm(x, y, oct=5, seed=0):
    r = np.random.default_rng(seed)
    v = np.zeros_like(x)
    amp, f = 1.0, 0.012
    for _ in range(oct):
        a, b, c, d = r.uniform(0, 6.28, 4)
        v += amp * (np.sin(x * f + a + np.sin(y * f * 1.3 + b)) * np.cos(y * f * 0.9 + c + np.sin(x * f * 0.7 + d)))
        amp *= 0.5; f *= 2.1
    return v

def g(x, y, cx, cy, sx, sy, ang=0.0):
    ca, sa = np.cos(ang), np.sin(ang)
    dx, dy = x - cx, y - cy
    u = (dx * ca + dy * sa) / sx
    w = (-dx * sa + dy * ca) / sy
    return np.exp(-(u * u + w * w))

def reflectivity(x, y):
    n = fbm(x, y, seed=1)
    z = np.full_like(x, -20.0)
    # forward-flank / anvil precip to the NE
    z = np.maximum(z, 44 * g(x, y, 310, 240, 170, 62, -0.85) + 7 * n)
    # main core
    z = np.maximum(z, 65 * g(x, y, 230, 352, 52, 34, -0.8) + 4 * n)
    z = np.maximum(z, 55 * g(x, y, 262, 318, 40, 26, -0.8) + 4 * n)
    # hook: tube along an arc around the meso (cyclonic, wraps S then W)
    best = np.full_like(x, 0.0)
    for t in np.linspace(0, 1, 60):
        ang = np.deg2rad(-10 + 200 * t)      # screen coords, y down
        r = 28 - 10 * t
        cx = M[0] + r * np.cos(ang) * 1.1
        cy = M[1] + r * np.sin(ang) * 0.9
        best = np.maximum(best, (54 - 18 * t) * np.exp(-(((x - cx) ** 2 + (y - cy) ** 2) / (12 + 6 * (1 - t)) ** 2)))
    z = np.maximum(z, best + 3 * n)
    # inflow notch / BWER: carve east of the hook
    z -= 26 * g(x, y, 232, 410, 14, 11)
    # debris ball
    z = np.maximum(z, 58 * g(x, y, M[0] + 2, M[1] + 2, 5, 5))
    # trailing cells and stratiform
    for cx, cy, pk, s in [(92, 640, 52, 22), (330, 700, 44, 26), (70, 175, 38, 30), (150, 560, 34, 40), (380, 470, 30, 34)]:
        z = np.maximum(z, pk * g(x, y, cx, cy, s, s * 0.8, 0.6) + 5 * n)
    z = np.maximum(z, 34 * g(x, y, 120, 600, 260, 70, -0.7) + 12 * fbm(x, y, seed=3) - 6)
    return z

def velocity(x, y):
    # environmental flow from SW, m/s (screen y down => vy negative is north)
    vx = 9 + 4 * fbm(x, y, 3, seed=5)
    vy = -7 + 3 * fbm(x, y, 3, seed=6)
    dx, dy = x - M[0], y - M[1]
    r = np.hypot(dx, dy) + 1e-6
    rc = 11.0
    vt = np.where(r < rc, 42 * r / rc, 42 * rc / r)
    # cyclonic = counterclockwise on a north-up map; with y down that's (dy, -dx)/r
    vx = vx + vt * (dy / r)
    vy = vy + vt * (-dx / r)
    px, py = x - RADAR[0], y - RADAR[1]
    pr = np.hypot(px, py)
    return (vx * px + vy * py) / pr  # + outbound

def polar_sample(field, w, h, ox=0, oy=0, scale=1.0):
    """Evaluate field at the centre of the polar bin each pixel falls in."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    x = ox + (xx + 0.5) / (S * scale)
    y = oy + (yy + 0.5) / (S * scale)
    px, py = x - RADAR[0], y - RADAR[1]
    az = np.arctan2(py, px)
    rg = np.hypot(px, py) * KM_PER_PX
    daz = np.deg2rad(0.5); dr = 0.25
    azq = (np.floor(az / daz) + 0.5) * daz
    rq = (np.floor(rg / dr) + 0.5) * dr / KM_PER_PX
    bx = RADAR[0] + rq * np.cos(azq)
    by = RADAR[1] + rq * np.sin(azq)
    return field(bx, by)

REF_STOPS = [(5, "#04e9e7"), (10, "#019ff4"), (15, "#0300f4"), (20, "#02fd02"), (25, "#01c501"),
             (30, "#008e00"), (35, "#fdf802"), (40, "#e5bc00"), (45, "#fd9500"), (50, "#fd0000"),
             (55, "#d40000"), (60, "#bc0000"), (65, "#f800fd"), (70, "#9854c6")]
VEL_STOPS = [(-60, "#9cffb0"), (-50, "#00ff4c"), (-35, "#00b33a"), (-20, "#0b6b2b"), (-5, "#47584c"),
             (0, "#555555"), (5, "#5c4747"), (20, "#7a1414"), (35, "#c41616"), (50, "#ff2a2a"), (60, "#ffb3c8")]

def hex2(c): return np.array([int(c[i:i + 2], 16) for i in (1, 3, 5)], float)

def colorize(v, stops, minv):
    xs = np.array([s[0] for s in stops], float)
    cs = np.array([hex2(s[1]) for s in stops])
    out = np.zeros(v.shape + (4,), float)
    for ch in range(3):
        out[..., ch] = np.interp(v, xs, cs[:, ch])
    out[..., 3] = np.where(v >= minv, 235, 0)
    return out

def basemap(w, h, ox=0, oy=0, scale=1.0, roads_alpha=0.55):
    im = Image.new("RGBA", (w, h), (20, 27, 34, 255))
    return im

def overlays(w, h, ox=0, oy=0, scale=1.0, roads_alpha=0.6):
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = S * scale
    T = lambda X, Y: ((X - ox) * k, (Y - oy) * k)
    # county lines (straight PLSS-like grid)
    for X in range(-200, 1200, 210):
        d.line([T(X, -400), T(X + 6, 1600)], fill=(160, 175, 188, 70), width=max(1, int(1 * k)))
    for Y in range(-300, 1600, 190):
        d.line([T(-400, Y), T(1400, Y + 4)], fill=(160, 175, 188, 70), width=max(1, int(1 * k)))
    a = int(255 * roads_alpha)
    # I-35 (N-S) and I-40 (E-W), US-62 diagonal
    i35 = [T(262 + 10 * np.sin(Y / 140), Y) for Y in range(-400, 1700, 20)]
    d.line(i35, fill=(236, 232, 223, a), width=int(2.2 * k))
    i40 = [T(X, 520 + 14 * np.sin(X / 170)) for X in range(-400, 1500, 20)]
    d.line(i40, fill=(236, 232, 223, a), width=int(2.2 * k))
    d.line([T(-100, 820), T(520, 120)], fill=(236, 232, 223, int(a * 0.55)), width=int(1.3 * k))
    return im

def render(name, kind, w_css, h_css, ox=0, oy=0, scale=1.0, roads=0.6):
    w, h = int(w_css * S), int(h_css * S)
    if kind == "ref":
        v = polar_sample(reflectivity, w, h, ox, oy, scale)
        rgba = colorize(v, REF_STOPS, 8)
    else:
        z = polar_sample(reflectivity, w, h, ox, oy, scale)
        v = polar_sample(velocity, w, h, ox, oy, scale) * 1.94  # m/s -> kt
        rgba = colorize(v, VEL_STOPS, -999)
        rgba[..., 3] = np.where(z >= 8, 235, 0)
    data = Image.fromarray(rgba.astype(np.uint8), "RGBA")
    base = basemap(w, h)
    base.alpha_composite(data)
    base.alpha_composite(overlays(w, h, ox, oy, scale, roads))
    base.convert("RGB").save(os.path.join(OUT, name), optimize=True)

render("ref_full.png", "ref", 390, 844)
render("vel_full.png", "vel", 390, 844)
# zoomed velocity for the learn card thumbnail
render("vel_zoom.png", "vel", 160, 120, ox=M[0] - 40, oy=M[1] - 30, scale=2.0)
# panes: 390 x 360 each, centred on storm
render("ref_pane.png", "ref", 390, 360, ox=0, oy=210)
render("vel_pane.png", "vel", 390, 360, ox=0, oy=210)
# widget / thumbnails, zoomed out
render("ref_wide.png", "ref", 340, 200, ox=20, oy=180, scale=0.75)
print("ok")
