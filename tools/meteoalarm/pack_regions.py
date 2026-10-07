"""Pack MeteoAlarm EMMA_ID region outlines into a compact binary for the app.

Input: geocodes.json (GeoJSON FeatureCollection, properties: code, name, country, type),
       as shipped by the MIT-licensed `meteoalarm` Python package (data: EUMETNET MeteoAlarm, CC BY 4.0).
Output format (little-endian):
  magic "HLRG", u16 version=1, u32 regionCount
  per region: u8 len + code UTF-8, u8 len + name UTF-8, u16 polygonCount,
    per polygon (outer ring only): u32 pointCount, then zigzag-varint deltas of
    lat*1e4 and lon*1e4 (first point relative to 0).
Holes are dropped: warning areas only need their outline for display and
point-in-region checks at ~1 km precision.
"""
import json, struct, sys
from shapely.geometry import shape, Polygon, MultiPolygon

src, dst = sys.argv[1], sys.argv[2]
tol = float(sys.argv[3]) if len(sys.argv) > 3 else 0.01
feats = json.load(open(src))['features']

def zz(n): return (n << 1) ^ (n >> 63)
def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return bytes(out)

out = bytearray(b'HLRG') + struct.pack('<HI', 1, 0)
count = points = 0
for f in feats:
    p = f['properties']
    if p.get('type') != 'EMMA_ID' or not f.get('geometry'): continue
    g = shape(f['geometry']).simplify(tol, preserve_topology=True)
    polys = [g] if isinstance(g, Polygon) else list(getattr(g, 'geoms', []))
    polys = [q for q in polys if isinstance(q, Polygon) and not q.is_empty and len(q.exterior.coords) >= 4]
    if not polys: continue
    code = p['code'].encode(); name = (p.get('name') or '').encode()[:255]
    out += struct.pack('<B', len(code)) + code + struct.pack('<B', len(name)) + name + struct.pack('<H', len(polys))
    for q in polys:
        coords = list(q.exterior.coords)[:-1]
        out += struct.pack('<I', len(coords))
        plat = plon = 0
        for lon, lat in coords:
            ilat, ilon = round(lat * 1e4), round(lon * 1e4)
            out += varint(zz(ilat - plat)) + varint(zz(ilon - plon))
            plat, plon = ilat, ilon
        points += len(coords)
    count += 1
struct.pack_into('<I', out, 6, count)
open(dst, 'wb').write(out)
print(f"{count} regions, {points} points, {len(out)/1e6:.2f} MB (tolerance {tol} deg)")
