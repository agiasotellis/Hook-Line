# Couplet — Product Spec (v1.0)

> Working name. "Couplet" nods to the velocity couplet, the signature of a rotating storm. Check trademark/Play availability before launch.

## 1. Who it's for

| Persona | Context | Job to be done | What RadarScope gets wrong for them |
|---|---|---|---|
| **Dana, storm chaser** | In a car at dusk, one bar of LTE, one hand on the phone | "Tell me where the rotation is *now* and how old this scan is." | 6-frame loops, Level II and fast updates behind $100/yr. |
| **Marcus, spotter / EM volunteer** | Activated by county EM, on foot, gloves | "Show warnings, watches and LSRs for my county at a glance." | Watches and LSRs behind Tier 1. |
| **Priya, weather enthusiast** | Couch, tornado watch, curious | "Is that storm dangerous? What am I looking at?" | No explanations; expert-only UI. |
| **Tom, retired met / analyst** | Desk, tablet, post-event review | "Replay 3 May 1999 at full resolution, 4 panes." | Archive and multi-pane need Tier 2. |

## 2. Product principles

1. **Buy once, own everything.** No tiers, no account, no ads, no tracking. If a feature uses free public data, it ships to every buyer.
2. **Truth over prettiness.** Native radial rendering, visible scan age, honest staleness. Never interpolate data and pass it off as real.
3. **Built for the worst five minutes.** Every core action reachable one-handed in ≤ 2 taps; survives bad signal; never crashes during an outbreak.
4. **Pro by default, teachable on demand.** No dumbing down — a Learn layer explains instead.
5. **Your map, your rules.** Every overlay, label and colour is user-adjustable. We don't remove control in an update.

## 3. Feature set (all included)

### Radar
- NEXRAD **Level II super-res** via real-time chunks: updates **as each elevation finishes** (~every 20–60 s at low tilts), not after the full volume.
- **All tilts**, tilt ladder with VCP awareness, SAILS/MESO-SAILS low-tilt rescans shown in order.
- Products: Reflectivity, Velocity, **Storm-relative velocity** (auto motion from NWS storm tracks or manual), Spectrum width, ZDR, CC, KDP, Hydrometeor class, Echo tops, VIL, Level III precip estimates.
- On-device **velocity dealiasing** (region-based) with toggle.
- **60-frame loops** (configurable 6–120; capped by storage, never by price).
- **National/regional mosaic (MRMS)** when zoomed out; auto-hand-off to nearest radar when zoomed in; manual site lock.
- Terminal Doppler (TDWR) sites.
- **Multi-pane**: 2 on phones, 4 on tablets/foldables, linked cursor and time.
- **3D cross-section** (RHI-style vertical slice along a drawn line) — v1.1.

### Warnings & context
- All NWS warnings, **watches**, advisories, special weather statements, MDs, SPC convective/tornado/hail/wind/fire outlooks, WPC ERO, **local storm reports**, NWS storm tracks with hail/TVS/meso attributes.
- **Storm Mode** (see §5) with push for saved places + GPS.
- Tap any polygon → full NWS text, expiry countdown, IBW tags (e.g. "Tornado: radar indicated · Hail: 1.75 in").

### Satellite & lightning
- GOES-19 / GOES-18 ABI: visible, IR, water vapour, GeoColor-style composites.
- **GLM total lightning** (flash extent density + individual flashes, ~20 s latency).

### Time Machine (archive)
- Any NEXRAD Level II volume **back to 1991**, plus archived NWS warnings for the same time.
- Curated "notable events" shelf (Moore 1999, Joplin 2011, El Reno 2013, etc.) as learning material.

### Tools
- Inspector (value at point, height ARL/MSL, beam width), distance + bearing, storm ETA to a place ("arrives in 23 min at 34 mph").
- **Placefiles** (GRLevelX format) and custom **palettes** (GR/RadarScope .pal import).
- Spotter Network position/reports, mPING submission.
- Export loop as **MP4/GIF** with timestamp + radar ID watermark for social sharing.

### Map
- MapLibre vector map, **offline regions** you download (state/radius).
- Per-layer **intensity slider** (roads, labels, counties, highways) and label density.
- Day (sunglasses) theme, Night theme, and **Red-night** (dark-adapted vision) theme.

### Platform
- Home-screen **widgets** (mini radar + nearest warning), **Wear OS** tile + complication, Quick Settings tile for Storm Mode.
- Tablets, foldables (table-top posture: map top, controls bottom), Chromebooks with keyboard shortcuts.
- Family Library.

### Learn layer
- Long-press any bin → value + plain-English meaning ("Inbound 62 kt next to outbound 58 kt: strong rotation, ~1.4 mi wide").
- Signature cards: hook echo, BWER, TDS/debris ball, hail spike, bow echo, RFD, inflow notch.
- Toggle off entirely in Pro mode.

### Deliberately out of scope (v1)
Licensed ground lightning networks, forecast model data, social feeds, accounts, cloud sync, ads, analytics SDKs (crash reporting is opt-in only).

## 4. Information architecture

```
Map (home)
├── Site chip ─────────── radar picker (list + map, favourites)
├── Product rail ──────── REF · VEL · SRV · CC · ZDR · KDP · more…
├── Tilt ladder
├── Time tape ─────────── play/pause · scrub · loop length · live
├── Layers (sheet) ────── warnings · watches · outlooks · LSR · lightning · satellite · placefiles · map style
├── Alerts (sheet) ────── active for my places · all in view · Storm Mode toggle
├── Tools (FAB menu) ──── inspector · measure · cross-section · export
└── Panes (1/2/4)
Time Machine
Places (saved locations for alerts)
Settings ─── palettes · units · data saver · Learn layer · themes · about/data sources
```

No bottom navigation bar: the map is the app. Secondary destinations live in a single overflow menu so the map keeps every pixel.

## 5. Storm Mode (location alerts without a server)

| State | Trigger | Behaviour | Battery |
|---|---|---|---|
| **Off** | User choice | Nothing in background | 0 |
| **Watching** (default after onboarding) | Always | WorkManager periodic check (15 min) of NWS alerts vs saved places + last location | ~negligible |
| **Active** | User taps Storm Mode, **or** auto when a watch covers a saved place (opt-in) | Foreground service, 60 s polling, GPS significant-change, persistent notification with nearest threat + age | ~2–4 %/hr |

UI is honest about which state you're in; the persistent notification states the polling interval. A tiny optional relay (free-tier serverless) can be added later for true push without changing UX.

## 6. Monetisation & distribution
- Paid app on Google Play, **$19.99** (launch $14.99), no IAP (optional cosmetic tip jar only).
- Play licensing check offline-tolerant (never lock out a user mid-storm because licence check failed).
- Future paid SKUs: v2 Desktop (Compose Multiplatform), v3 International pack. v1 owners keep v1 features forever.

## 7. Success metrics
| Metric | Target |
|---|---|
| Crash-free sessions | ≥ 99.9 % (≥ 99.95 % on days with active tornado watches) |
| Cold start → first radar frame | < 1.5 s cached, < 3 s network (mid-range device) |
| Scan latency (radar → screen) | median < 45 s for lowest tilt |
| Play rating | ≥ 4.6 |
| Refund rate | < 3 % |
| APK size | < 35 MB |

## 8. Release plan
| Milestone | Scope |
|---|---|
| **M0 — Spike (2 wks)** | Level II decoder + GL radial renderer on device, single site, REF/VEL |
| **M1 — Alpha (6 wks)** | Map, site picker, tilts, loops, warnings/watches, time tape, offline-tolerant cache |
| **M2 — Beta (6 wks)** | Dual-pol, SRV, MRMS, LSR/outlooks, Storm Mode, widgets, palettes, placefiles |
| **M3 — Launch (4 wks)** | Time Machine, satellite + GLM, multi-pane, Learn layer, Wear OS, Outbreak Test gate |
| **v1.1** | Cross-section, export MP4, foldable posture, Chromebook shortcuts |

Launch timing: aim for **Play release by mid-March 2027**, ahead of US peak tornado season (Apr–Jun).
