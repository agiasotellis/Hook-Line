"""Score for the Hookline film: 120 BPM, D minor, every hit on a picture cut. Writes score.wav."""
import numpy as np
from scipy.signal import butter, sosfilt, lfilter
from scipy.io import wavfile

SR = 44100; DUR = 22.0; N = int(SR * DUR)
rng = np.random.default_rng(3)
L = np.zeros(N); R = np.zeros(N)
send = np.zeros(N)  # reverb send (mono)

def t_(d): return np.arange(int(d * SR)) / SR
def place(sig, at, gain=1.0, pan=0.0, rev=0.0):
    i = int(at * SR); j = min(N, i + len(sig)); s = sig[:j - i] * gain
    L[i:j] += s * np.sqrt((1 - pan) / 2) * 1.414; R[i:j] += s * np.sqrt((1 + pan) / 2) * 1.414
    send[i:j] += s * rev
def lp(x, fc, order=2): return sosfilt(butter(order, fc / (SR / 2), 'low', output='sos'), x)
def hp(x, fc, order=2): return sosfilt(butter(order, fc / (SR / 2), 'high', output='sos'), x)
def bp(x, lo, hi): return sosfilt(butter(2, [lo / (SR / 2), hi / (SR / 2)], 'band', output='sos'), x)
def adsr(n, a, d, s, r, sus_len):
    env = np.concatenate([np.linspace(0, 1, max(1, int(a * SR))), np.linspace(1, s, max(1, int(d * SR))),
                          np.full(max(0, int(sus_len * SR)), s), np.linspace(s, 0, max(1, int(r * SR)))])
    return env[:n] if len(env) >= n else np.pad(env, (0, n - len(env)))
def saw(f, d, det=0.0):
    t = t_(d); out = 0
    for k in (-det, 0, det):
        ph = (t * f * (1 + k)) % 1.0; out = out + (2 * ph - 1)
    return out / 3
def note(n): return 440.0 * 2 ** ((n - 69) / 12)

# --- pads (chord per section) ---
def pad(notes, start, length, gain, cutoff=1400, a=0.4, r=1.2):
    d = length + r
    sig = sum(saw(note(n), d, 0.004) for n in notes) / len(notes)
    sig = lp(sig, cutoff, 2) * adsr(len(sig), a, 0.5, 0.8, r, length - a - 0.5)
    place(sig, start, gain, 0, rev=0.5)
D2, F2, A2, C3, E3, D3, F3, A3, C4, E4 = 38, 41, 45, 48, 52, 50, 53, 57, 60, 64
pad([D3, A3, C4, F3], 0.0, 5.4, 0.10, cutoff=700, a=1.5)          # opening: Dm7, dark
pad([D3, F3, A3, C4, E4], 5.5, 3.0, 0.16, cutoff=1800, a=0.02)     # reveal: Dm9
prog = [(8.5, [D3, F3, A3, C4]), (10.5, [46, 50, 53, 57]), (12.5, [41, 48, 53, 57]), (14.5, [48, 52, 55, 60])]  # Dm7 Bb F C
for st, ns in prog: pad(ns, st, 2.0, 0.10, cutoff=1500, a=0.05, r=0.3)
pad([46, 50, 53, 57, 62], 16.5, 2.4, 0.12, cutoff=1100, a=0.08)    # promise: Bbmaj7 add
pad([D3, A3, E4, F3, 62], 18.95, 2.65, 0.17, cutoff=2200, a=0.02, r=0.9)  # end: Dm(add9)

# --- drone (hook + turn) ---
t = t_(5.6)
drone = (np.sin(2 * np.pi * 36.71 * t) * 0.8 + lp(saw(73.42, 5.6, 0.003), 300) * 0.5) * np.minimum(1, t / 1.2) * (1 - np.clip((t - 5.3) / 0.3, 0, 1))
drone *= 1 + 0.25 * np.sin(2 * np.pi * 0.5 * t)
place(drone, 0.0, 0.22)

# --- beam whooshes (filtered noise following the sweep) ---
def swept(d, f0, f1, kind='bp'):
    """Noise whose filter centre glides f0 -> f1: a bank of fixed filters, crossfaded (click-free)."""
    n = int(d * SR); x = rng.standard_normal(n); k = np.linspace(0, 1, n)
    centre = np.log(f0) + (np.log(f1) - np.log(f0)) * k
    bands = np.exp(np.linspace(np.log(f0), np.log(f1), 8)); out = np.zeros(n)
    for fc in bands:
        y = bp(x, fc * 0.7, min(fc * 1.4, SR / 2 - 100)) if kind == 'bp' else lp(x, fc)
        out += y * np.exp(-((np.log(fc) - centre) / 0.35) ** 2)
    return out
def whoosh(d, f0, f1, gain, at, pan0=0.6, pan1=-0.6):
    out = swept(d, f0, f1); n = len(out)
    env = np.sin(np.pi * np.linspace(0, 1, n)) ** 1.5
    half = n // 2
    place(out[:half] * env[:half], at, gain, pan0); place(out[half:] * env[half:], at + half / SR, gain, pan1)
whoosh(1.7, 400, 2400, 0.10, 0.05)
whoosh(0.75, 600, 3200, 0.09, 2.92)

# --- pings (sonar, D6 and A5) ---
def ping(f, d=1.6):
    t = t_(d); e = np.exp(-t * 3.2)
    return (np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t * 8)) * e * np.minimum(1, t / 0.004)
for at, f, g, pn in [(1.25, note(86), 0.14, 0.3), (4.0, note(81), 0.12, -0.3), (19.35, note(86), 0.12, 0.0)]:
    p = ping(f); place(p, at, g, pn, rev=0.8)
    for k, dl in enumerate([0.375, 0.75]): place(p, at + dl, g * 0.32 / (k + 1), -pn, rev=0.6)

# --- hits ---
def boom(d=1.2, f0=90, f1=34):
    t = t_(d); f = f1 + (f0 - f1) * np.exp(-t * 9)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 3.0)
def burst(d=0.25, fc=3000):
    x = rng.standard_normal(int(d * SR)); return lp(x, fc) * np.exp(-t_(d) * 18)
place(boom(0.6, 80, 40), 2.95, 0.38)
place(boom(1.4), 5.5, 0.55); place(burst(0.4, 2500), 5.5, 0.10, rev=0.6)
place(boom(1.0, 85, 36), 16.5, 0.36)
place(boom(1.6), 18.95, 0.55); place(burst(0.5, 2200), 18.95, 0.09, rev=0.7)

# --- risers ---
def riser(d, gain, at):
    t = t_(d); out = swept(d, 300, 8000, 'lp') * 0.6
    tone = np.sin(2 * np.pi * np.cumsum(220 * 2 ** (2 * t / d)) / SR) * 0.3
    place((out + tone) * (t / d) ** 2, at, gain, 0, rev=0.4)
riser(1.15, 0.12, 4.33); riser(0.5, 0.10, 18.45)

# --- logo tip lands: bright pluck ---
def pluck(f, d=0.5):
    t = t_(d); return (np.sin(2 * np.pi * f * t) + 0.4 * np.sin(2 * np.pi * f * 1.5 * t)) * np.exp(-t * 12)
place(pluck(note(81)), 6.47, 0.12, 0.2, rev=0.7); place(pluck(note(86)), 6.6, 0.08, -0.2, rev=0.7)

# --- groove 8.5 - 16.5 ---
def kick():
    t = t_(0.35); f = 45 + 80 * np.exp(-t * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9) + 0.15 * burst(0.02, 4000)[:len(t)] if False else np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9)
def hat(d=0.05):
    return hp(rng.standard_normal(int(d * SR)), 7000) * np.exp(-t_(d) * 70) * np.minimum(1, t_(d) / 0.0015)
beat = 0.5
for i in range(16):
    at = 8.5 + i * beat
    place(kick(), at, 0.42)
    place(hat(), at + beat / 2, 0.022, 0.35 if i % 2 else -0.35)
    if i % 4 == 2: place(hat(0.12), at, 0.02, 0)
# bass: 8ths on the root, sidechained by the kick
roots = [(8.5, 38), (10.5, 34), (12.5, 41), (14.5, 36)]
for st, rt in roots:
    for k in range(8):
        at = st + k * 0.25; d = 0.22
        b = lp(saw(note(rt), d, 0.002), 500) * adsr(int(d * SR), 0.005, 0.08, 0.6, 0.05, d - 0.14)
        place(b, at, 0.16 * (0.55 if k % 2 == 0 else 1.0))
# promise: half-time kicks on the words
for at in (16.65, 16.97, 17.29): place(boom(0.5, 70, 40), at, 0.2)
for at in (17.5, 18.5): place(kick(), at, 0.3)

# --- UI ticks (product / feature changes, notification) ---
def tick(f=2200, d=0.06):
    t = t_(d); return np.sin(2 * np.pi * f * t) * np.exp(-t * 90) * np.minimum(1, t / 0.002)
for at in (9.55, 10.15, 10.75): place(tick(2400), at, 0.05, 0.4, rev=0.2)
for at in (10.65, 12.7, 14.7): place(tick(1600), at, 0.05, -0.3, rev=0.3)
place(pluck(note(86), 0.4), 13.1, 0.07, 0.3, rev=0.5); place(pluck(note(93), 0.4), 13.22, 0.06, 0.3, rev=0.5)
for i in range(30):  # site dots: a quiet shimmer of tiny ticks across the map build
    place(tick(3000 + 40 * (i % 7), 0.03), 10.75 + i * 0.032, 0.012, -0.8 + 1.6 * i / 30)

# --- reverb: Schroeder (4 combs + 2 allpasses), stereo by decorrelated delays ---
def comb(x, d, g):
    return lfilter([1.0], np.r_[1.0, np.zeros(d - 1), -g], x)
def allpass(x, d, g):
    b = np.r_[-g, np.zeros(d - 1), 1.0]; a = np.r_[1.0, np.zeros(d - 1), -g]; return lfilter(b, a, x)
def reverb(x, off):
    y = sum(comb(x, int((dl + off) * SR / 1000), 0.83) for dl in (29.7, 37.1, 41.1, 43.7)) / 4
    for dl in (5.0, 1.7): y = allpass(y, int((dl + off * .1) * SR / 1000), 0.7)
    return lp(hp(y, 200), 6000)
wet = 0.32
L += reverb(send, 0) * wet; R += reverb(send, 2.3) * wet

# --- master ---
mix = np.stack([L, R], 1)
mix = hp(mix.T, 28).T
fade = np.ones(N); fade[-int(0.8 * SR):] = np.linspace(1, 0, int(0.8 * SR)) ** 2
mix *= fade[:, None]
mix = np.tanh(mix * 1.15) / np.tanh(1.15)
mix /= np.max(np.abs(mix)) / 0.89
wavfile.write('score.wav', SR, (mix * 32767).astype(np.int16))
print('peak ok, rms', float(np.sqrt(np.mean(mix ** 2))))
