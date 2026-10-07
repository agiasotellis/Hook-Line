"""Generate the small synthetic ODIM_H5 files used by unit tests.

Values follow simple formulas so tests can assert exact numbers:
  scan DBZH raw[r, c] = (r + c) % 200 + 1   (0 = undetect, 255 = nodata), gain 0.5, offset -32
  scan VRADH raw[r, c] = 128 + (r % 50) - 25, gain 0.5, offset -64 (m/s)
  composite DBZH raw[y, x] = x % 100 + y % 50 + 2, undetect where x < 5, nodata where y < 3
"""
import sys, h5py, numpy as np
out = sys.argv[1]

def common(f, obj, lat=51.4055, lon=6.9669):
    f.attrs['Conventions'] = np.bytes_('ODIM_H5/V2_4')
    w = f.create_group('what')
    w.attrs['object'] = np.bytes_(obj); w.attrs['version'] = np.bytes_('H5rad 2.4')
    w.attrs['date'] = np.bytes_('20261007'); w.attrs['time'] = np.bytes_('061500')
    w.attrs['source'] = np.bytes_('WMO:10410,NOD:deess,PLC:Essen')
    wh = f.create_group('where'); wh.attrs['lat'] = lat; wh.attrs['lon'] = lon; wh.attrs['height'] = 185.0

def scan(path, quantity, libver, elangle=0.5, nrays=360, nbins=120):
    with h5py.File(path, 'w', libver=libver) as f:
        common(f, 'SCAN')
        ds = f.create_group('dataset1')
        ds.create_group('where').attrs.update({'elangle': elangle, 'nbins': np.int64(nbins), 'nrays': np.int64(nrays),
                                               'rstart': 0.0, 'rscale': 1000.0, 'a1gate': np.int64(0)})
        ds.create_group('what').attrs.update({'product': np.bytes_('SCAN'), 'startdate': np.bytes_('20261007'), 'starttime': np.bytes_('061500'),
                                              'enddate': np.bytes_('20261007'), 'endtime': np.bytes_('061545')})
        h = ds.create_group('how')
        h.attrs['startazA'] = np.arange(nrays, dtype=np.float64) * 360.0 / nrays
        h.attrs['stopazA'] = (np.arange(nrays, dtype=np.float64) + 1) * 360.0 / nrays
        d = ds.create_group('data1')
        r, c = np.meshgrid(np.arange(nrays), np.arange(nbins), indexing='ij')
        if quantity == 'DBZH':
            raw = ((r + c) % 200 + 1).astype(np.uint8)
            raw[0, 0] = 0; raw[0, 1] = 255
            attrs = {'quantity': np.bytes_('DBZH'), 'gain': 0.5, 'offset': -32.0, 'nodata': 255.0, 'undetect': 0.0}
        else:
            raw = (128 + (r % 50) - 25).astype(np.uint8)
            attrs = {'quantity': np.bytes_('VRADH'), 'gain': 0.5, 'offset': -64.0, 'nodata': 255.0, 'undetect': 0.0}
        d.create_group('what').attrs.update(attrs)
        d.create_dataset('data', data=raw, chunks=(90, nbins), compression='gzip', compression_opts=6)

def comp(path, libver):
    with h5py.File(path, 'w', libver=libver) as f:
        common(f, 'COMP', lat=55.0, lon=10.0)
        w = f['where']
        w.attrs['projdef'] = np.bytes_('+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0 +units=m +ellps=WGS84')
        # A 380 x 440 grid of 10 km pixels covering the OPERA domain.
        w.attrs['xsize'] = np.int64(380); w.attrs['ysize'] = np.int64(440)
        w.attrs['xscale'] = 10000.0; w.attrs['yscale'] = 10000.0
        from pyproj import Proj
        p = Proj(w.attrs['projdef'].decode())
        corners = {'UL': (0, 0), 'UR': (3800000, 0), 'LL': (0, -4400000), 'LR': (3800000, -4400000)}
        for k, (x, y) in corners.items():
            lon, lat = p(x, y, inverse=True)
            w.attrs[f'{k}_lon'] = lon; w.attrs[f'{k}_lat'] = lat
        ds = f.create_group('dataset1')
        ds.create_group('what').attrs.update({'product': np.bytes_('MAX'), 'startdate': np.bytes_('20261007'), 'starttime': np.bytes_('061000'),
                                              'enddate': np.bytes_('20261007'), 'endtime': np.bytes_('061500')})
        d = ds.create_group('data1')
        y, x = np.meshgrid(np.arange(440), np.arange(380), indexing='ij')
        raw = (x % 100 + y % 50 + 2).astype(np.uint8)
        raw[:, :5] = 0; raw[:3, :] = 255
        d.create_group('what').attrs.update({'quantity': np.bytes_('DBZH'), 'gain': 0.5, 'offset': -32.0, 'nodata': 255.0, 'undetect': 0.0})
        d.create_dataset('data', data=raw, chunks=(110, 95), compression='gzip')

scan(f'{out}/deess_scan_0.5_DBZH.h5', 'DBZH', 'earliest')
scan(f'{out}/deess_scan_0.5_VRADH_v114.h5', 'VRADH', ('v110', 'v114'))
scan(f'{out}/deess_scan_1.5_DBZH.h5', 'DBZH', 'earliest', elangle=1.5)
comp(f'{out}/opera_comp_laea.h5', 'earliest')
print('ok')
