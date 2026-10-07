"""Tiny GLM-like netCDF-4/HDF5 file for GlmLightningTest (packed, unsigned time offsets)."""
import sys, h5py, numpy as np
with h5py.File(sys.argv[1], 'w', libver=('earliest', 'v110')) as f:
    f.attrs['time_coverage_start'] = np.bytes_('2026-10-06T12:00:00.0Z')
    f.create_dataset('flash_lat', data=np.array([35.2, 36.1, 29.9], dtype='f4'))
    f.create_dataset('flash_lon', data=np.array([-97.4, -95.0, -90.1], dtype='f4'))
    # value = raw * 0.00038147 - 5 ; unsigned 16-bit stored as signed
    secs = np.array([0.5, 10.0, 19.9])
    raw = np.round((secs + 5) / 0.00038147).astype('u2').view('i2')
    d = f.create_dataset('flash_time_offset_of_first_event', data=raw, chunks=(3,), compression='gzip')
    d.attrs['scale_factor'] = np.float32(0.00038147)
    d.attrs['add_offset'] = np.float32(-5.0)
    d.attrs['_Unsigned'] = np.bytes_('true')
print('ok')
