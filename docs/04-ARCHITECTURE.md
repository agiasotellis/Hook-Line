# Hookline — Architecture

## Goals that drive the design
1. **No backend required.** Every data product comes straight from public NOAA/NWS endpoints. Zero marginal cost per user is what makes a one-time price work.
2. **Truthful rendering.** Radials drawn in native polar form on the GPU; nearest-bin, never smoothed.
3. **Outbreak-proof.** Works on one bar of LTE, survives endpoint hiccups, never blocks the UI on network.

## Modules

```
app/                    Android app: Compose UI, Storm Mode (WorkManager + FGS), widgets (later)
core/radar/   (JVM)     Level II decoder, palettes, polar geometry, storm-relative, CPU rasterizer
core/data/    (JVM)     NOAA S3 (NEXRAD chunks + archive), NWS alerts API, geo helpers
core/designsystem/      Compose tokens + components (see 03-DESIGN-SYSTEM.md)
```

`core/radar` and `core/data` are plain Kotlin/JVM so they're unit-tested fast on CI and can be reused by a future Compose Multiplatform desktop build (v2).

Planned modules: `core/render` (OpenGL ES 3 radial renderer), `core/map` (MapLibre wrapper + offline regions), `feature/timemachine`, `feature/places`, `widget`, `wear`.

## Data flow (live, implemented in `LiveRadar` / `VolumeAssembler`)

```
S3 listing (every 10 s while foreground; newest volume found across the 1–999 wrap)
   └─ new chunk key? ─► fetch chunk (~100–600 KB) ─► Level2Decoder.decodeRadials
                                                     └─► VolumeAssembler (by elevation #, keeps newest complete sweep per tilt × moment)
                                                            └─► GL value textures (per moment)
                                                                   └─► RadialRenderer (shader + palette LUT)
NWS API (every 60 s foreground / Storm Mode) ─► Alert polygons ─► MapLibre GeoJSON layer
```

Updating on each chunk rather than each full volume is the latency win: the 0.5° tilt appears ~20–40 s after the radar finishes it.

## Data sources

| Product | Endpoint | Notes |
|---|---|---|
| Level II real-time | `unidata-nexrad-level2-chunks.s3.amazonaws.com/<SITE>/<vol>/<time>-<seq>-<S|I|E>` | Public, no auth. S = start (has volume header + metadata), E = end of volume |
| Level II archive | `unidata-nexrad-level2.s3.amazonaws.com/<yyyy>/<MM>/<dd>/<SITE>/` | 1991 → present |
| Level III | `unidata-nexrad-level3` bucket | Data saver mode, TDWR |
| Alerts, watches, LSRs | `api.weather.gov` | Requires identifying User-Agent |
| SPC outlooks / MDs | SPC GeoJSON / KML feeds | |
| MRMS | NOAA MRMS on AWS Open Data | Mosaic when zoomed out |
| GOES ABI + GLM | NOAA GOES-19/18 on AWS Open Data | GLM = free total lightning |
| Base map | OpenFreeMap or self-hosted PMTiles | Only recurring cost (CDN) |

## Rendering (implemented)
- `SweepTexture` packs one moment of one sweep as a radials × gates float grid, uploaded as an **R32F texture** sampled NEAREST. Sentinels (below threshold, range folded) are encoded as ±1e30 so the shader can test them portably.
- **One wedge (two triangles) per radial**, u running 0→1 across the gates. Because both wedge edges are radial, linear interpolation of u matches true range to within cos(0.25°) — every gate lands on exactly one texel, so the data is drawn in native polar form with no resampling.
- Fragment shader: value → palette LUT (`Palette.toLut`, 256 entries) → discard transparent. Range folded is drawn in the palette's RF colour.
- Range rings and warning outlines are triangulated thick lines (`ThickLines`) rebuilt at the current zoom, since `glLineWidth` > 1 isn't portable.
- `RadarGlView` (GLSurfaceView, render-when-dirty) owns pan/zoom/long-press; `Viewport` holds the maths and is unit-tested.
- Projection is local equirectangular around the site for now (< 0.5 % error within 460 km); it moves to Web Mercator with the MapLibre base map.
- Target: 60 fps pan/zoom with 4 panes on a 2021 mid-range device (Adreno 619 class).

The current `SweepRasterizer` (CPU) is the fallback for widgets, Wear tiles, loop export and the interim map.

## Storm Mode
| Mode | Mechanism |
|---|---|
| Watching | `StormModeWorker`, 15-min periodic, network-constrained |
| Active | `StormModeService` foreground service (type `location`), 60 s loop |
| Dedup | Alert ids stored in prefs; one notification per alert |

Android 12+ restricts starting a foreground service from the background, so auto-activation during a watch falls back to notification-only when refused.

## Reliability
- **Outbreak Test** release gate: replay archived outbreak days (e.g. 27 Apr 2011, 20 May 2013) through the decoder + renderer at 10× speed; fail the build on crash, ANR or frame drops > 5 %.
- Every network call has a timeout and a cached last-good result with visible age.
- Licence check (Play) is offline-tolerant: never block the map during a storm.

## Known gaps / TODO
- Legacy **Message 1** decoding (pre-2008 archives) for full 1991+ Time Machine.
- Velocity dealiasing (region-based) and dual-pol derived products (KDP, HHC from Level III).
- Bundle Barlow / Barlow Semi Condensed fonts (SIL OFL) in `core/designsystem/src/main/res/font`.
- MapLibre integration and offline regions.
