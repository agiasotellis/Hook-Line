"""Builds a tiny GRIB2 message (template 3.0 grid, 5.41 PNG packing) for Grib2Test."""
import struct, zlib, gzip, sys

def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

def png16(w, h, rows):
    raw = b''.join(b'\x00' + b''.join(struct.pack('>H', v) for v in r) for r in rows)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 16, 0, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))

def sm32(v): return struct.pack('>I', (abs(v) | (0x80000000 if v < 0 else 0)))
def sm16(v): return struct.pack('>H', (abs(v) | (0x8000 if v < 0 else 0)))
def sec(n, body): return struct.pack('>IB', 5 + len(body), n) + body

W, H = 4, 3
# value = (R + X * 2^E) / 10^D with R = -99, E = 0, D = 1  ->  X = (value*10 + 99)... keep simple: R=-999, D=1
R, E, D = -999.0, 0, 1
vals = [[-99.9, 0.0, 10.5, 25.0], [30.0, 45.5, 55.0, 60.0], [5.0, -99.9, 12.3, 70.0]]
X = [[int(round(v * 10 ** D - R)) for v in r] for r in vals]

s1 = sec(1, struct.pack('>HHBBBHBBBBBBB', 7, 0, 2, 1, 0, 2026, 10, 7, 12, 30, 0, 0, 1))
grid = (bytes([6, 0]) + struct.pack('>I', 0) + bytes([0]) + struct.pack('>I', 0) + bytes([0]) + struct.pack('>I', 0)
        + struct.pack('>II', W, H) + struct.pack('>II', 0, 0xFFFFFFFF)
        + sm32(54_995_000) + struct.pack('>I', 230_005_000) + bytes([48])
        + sm32(54_975_000) + struct.pack('>I', 230_035_000)
        + struct.pack('>II', 10_000, 10_000) + bytes([0]))
s3 = sec(3, bytes([0]) + struct.pack('>I', W * H) + bytes([0, 0]) + struct.pack('>H', 0) + grid)
s4 = sec(4, struct.pack('>HH', 0, 0) + bytes([3, 34]) + bytes(20))
s5 = sec(5, struct.pack('>IH', W * H, 41) + struct.pack('>f', R) + sm16(E) + sm16(D) + bytes([16, 0]))
s6 = sec(6, bytes([255]))
s7 = sec(7, png16(W, H, X))
body = s1 + s3 + s4 + s5 + s6 + s7 + b'7777'
msg = b'GRIB' + bytes([0, 0, 209, 2]) + struct.pack('>Q', 16 + len(body)) + body
open(sys.argv[1], 'wb').write(gzip.compress(msg))
print('ok', len(msg))
