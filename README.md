# Hookline Radar

**The professional weather radar you buy once.** Every product, every tilt, every warning and thirty years of archive, for one price on Android. No tiers, no subscriptions, no account, no ads. Works in the US and across Europe.

<p>
  <img src="docs/img/ref_full.png" width="240" alt="Reflectivity mockup with a hook-echo supercell">
  <img src="docs/img/vel_full.png" width="240" alt="Velocity mockup with a tight couplet">
</p>

> Named after the hook echo, the radar signature of a rotating storm. Mockup imagery is procedurally generated (`tools/mockup-radar/radar.py`), not real radar data.

## Why it exists
RadarScope is the benchmark for pro radar, but you pay for the app and then subscribe twice to unlock watches, outlooks, long loops, Level II and the archive — roughly **$110 in year one** for the full feature set. Nearly all of that data is free public NOAA/NWS data. Hookline reads it directly, so it can be sold once, at **$19.99**, with everything included.

Full teardown: [`docs/01-COMPETITIVE-ANALYSIS.md`](docs/01-COMPETITIVE-ANALYSIS.md)

## Docs
| | |
|---|---|
| [01 Competitive analysis](docs/01-COMPETITIVE-ANALYSIS.md) | RadarScope strengths, 12 weaknesses, pricing, risks |
| [02 Product spec](docs/02-PRODUCT-SPEC.md) | Personas, principles, full feature set, IA, Storm Mode, milestones |
| [03 Design system](docs/03-DESIGN-SYSTEM.md) | "The map is the only colour", tokens, type, signature components |
| [04 Architecture](docs/04-ARCHITECTURE.md) | Modules, data sources, rendering plan, reliability |

## Project layout
```
app/                 Compose UI (map screen), Storm Mode worker + foreground service
core/radar/          NEXRAD Level II + HDF5/ODIM decoders, projections, palettes, polar geometry, SRV
core/data/           NOAA S3 (NEXRAD), EUMETNET Open Radar Data (Europe), NWS alerts, site catalogue
tools/sites/         Generator for the site catalogue
tools/odim-fixtures/ Generator for the synthetic ODIM test files
tools/meteoalarm/    Packs MeteoAlarm warning-region outlines into the app
core/designsystem/   Hookline theme tokens and components
tools/mockup-radar/  Generator for the synthetic supercell imagery used in mockups
```

## Build
Requirements: JDK 17, Android SDK 35.

```bash
./gradlew :core:radar:test :core:data:test   # fast JVM tests
./gradlew :app:assembleDebug                  # debug APK
```

CI (GitHub Actions) runs both on every push and uploads the debug APK as an artifact.

## Status
Alpha (milestone M1 in progress).

Working now:
- **Coverage**: 291 radars with open single-site data (155 NEXRAD in the US, 136 OPERA radars in 21 European countries) plus the OPERA pan-European composite (1 km, every 5 minutes), which also covers countries that don't share single-site data, such as Greece. The app starts at your location and switches to the nearest open radar as you zoom in; zoomed out in Europe it shows the composite.
- **Live radar**: NEXRAD Level II real-time chunks (each tilt as soon as it's scanned) and European ODIM_H5 scans from EUMETNET's Open Radar Data cache; European tilts beyond the lowest download on demand to save data.
- **Own HDF5/ODIM decoder**: dependency-free Java, verified value-for-value against h5py on real Belgian, Dutch and Australian files and on synthetic files in old and new HDF5 layouts. Composite projections (LAEA, LCC, polar stereographic, Mercator) match PROJ to under 2 mm.
- **Base map**: MapLibre vector map (OpenFreeMap / OpenStreetMap) drawn *above* the radar on a transparent layer; one Web Mercator origin for all layers. Sliders for roads, names and borders.
- **Offline maps**: save the visible area (zoom 3–10) for chasing without signal.
- **Loops**: lowest-tilt REF / VEL / SRV for single radars (backfilled from earlier cycles); composite loops in Europe.
- **Storm motion**: SRV uses the NWS warning nearest where you're looking (US); storm tracks drawn to +45 min.
- **GPU renderer**: OpenGL ES 3; radar wedges and composite tiles both drawn from R32F value textures, nearest-sample, palette LUT in the shader.
- **Inspector**: long-press for the value, a plain-English meaning, beam height and distance (or the column maximum on the composite).
- **Warnings**: US NWS tornado, severe and flash-flood outlines; European yellow/orange/red warnings from MeteoAlarm (38 countries, region outlines bundled); a warnings list with full text.
- **Storm Mode**: background checks for your location in the US (NWS) and Europe (MeteoAlarm, orange and red by default), on/off in the menu.
- **US mosaic**: zoomed out over North America, NOAA's MRMS mosaic; zoom in for a single radar's raw data.
- **Satellite**: infrared clouds wherever no radar shares open data (South America, Africa, Asia, Oceania): NASA GIBS for GOES-East/West and Himawari, EUMETSAT Meteosat (hourly) for Africa and the Indian Ocean.
- **Dual-pol in Germany**: CC and ZDR for the 17 DWD radars from DWD open data (OPERA doesn't exchange dual-pol yet).
- **Menu**: radar search, warnings, Storm Mode, layers, guide to the products, data sources.

Not yet: saved places for Storm Mode, dual-pol for European radars outside Germany, satellite loops.

Test builds: every push to `main` publishes split APKs on the Releases page (`arm64-v8a` for most phones).

## Data
- US radar and warning data courtesy of NOAA / National Weather Service, via the NOAA Open Data Dissemination program on AWS and api.weather.gov.
- European radar data: EUMETNET OPERA and its member national weather services, via the EUMETNET Open Radar Data service (CC BY 4.0).
- US radar mosaic: NOAA / NWS MRMS via mapservices.weather.noaa.gov.
- European warnings: MeteoAlarm / EUMETNET and the national weather services (CC BY 4.0).
- German dual-pol: Source: Deutscher Wetterdienst (CC BY 4.0).
- Satellite: NASA GIBS / ESDIS (NOAA GOES, JMA Himawari); contains modified EUMETSAT Meteosat data (CC BY 4.0).
- Base map: OpenFreeMap, OpenMapTiles, © OpenStreetMap contributors.

Hookline is not affiliated with or endorsed by any of these organisations, and it is not an official warning service.

---
© 2026 — all rights reserved (commercial app; no open-source licence granted).
