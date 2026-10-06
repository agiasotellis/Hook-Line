# RadarScope — Competitive Teardown

*Prepared 6 Oct 2026. Sources: Google Play listing (v. updated 8 Sep 2026), App Store pricing data, review aggregators, StormTrack forum threads, RadarOmega App Store listing.*

## 1. Snapshot

| | RadarScope (DTN) |
|---|---|
| Rating (Play) | 4.0 ★ · ~6.3K reviews · 100K+ downloads |
| Size | ~147 MB |
| Business model | Paid app (~$9.99) **+** Pro Tier 1 (annual, ~$9.99) **+** Pro Tier 2 (~$14.99/mo or ~$99.99/yr) |
| Audience | Meteorologists, spotters, chasers, emergency managers, enthusiasts |
| Platforms | Android, Wear OS, iOS, macOS/Windows (separate) |

### What the base purchase gets
Single-site radar for US/Canada/Guam/Denmark/Finland/Germany, **6-frame loops**, tornado/severe/flash-flood/marine/snow-squall/extreme-wind warnings, NWS storm tracks, inspector + distance tools, palettes, Spotter Network + mPING, bundled low-bandwidth maps, widgets, Wear OS.

### What is paywalled
| Tier 1 (annual) | Tier 2 (monthly/annual) |
|---|---|
| 30-frame loops | 50-frame loops |
| Lightning (CG) | Level II data + 30-yr archive |
| **Watches** (tornado/severe) | 30-day Level III/super-res archive |
| Special weather statements, MDs | GOES satellite |
| SPC / WPC outlooks | MRMS mosaics, RTMA |
| Local storm reports | Surface obs, soundings |
| Custom palettes | Hail/rotation contours |
| Multi-pane (2/4) | Detailed road/satellite maps |
| | Server-sent (faster) updates |
| | Cross-platform sharing |

## 2. What RadarScope does well (do not underestimate)

1. **Native radial rendering** — data shown in original polar format, no resampling artefacts. Pros trust it.
2. **Credibility** — AMS award, used by NWS staff and broadcast mets.
3. **No ads, no data selling** — Play data-safety: nothing shared with third parties.
4. **Responsive support** and steady bug-fix cadence.
5. **Low-bandwidth bundled maps** — works in rural chase areas with one bar of LTE.

## 3. Weaknesses → opportunities

Ranked by how much they hurt users and how cheaply we can beat them.

| # | Weakness | Evidence | Our answer |
|---|---|---|---|
| W1 | **Pay-then-subscribe.** Users pay for the app, then find essentials (watches, outlooks, lightning, multi-pane, longer loops) locked. Real first-year cost for full features ≈ **$110**, then ~$100/yr. | Dominant complaint theme in review aggregators ("aggressive paywall", "deceptive after purchase"). | **One price, everything.** All public-data features included forever. |
| W2 | **Public data resold as premium.** Level II, its 30-year archive, MRMS, GOES, SPC outlooks, watches and LSRs are free US-government data (NOAA on AWS Open Data needs no account; NWS API is free). | NOAA NEXRAD buckets are public, no-sign-request. | Fetch directly on-device from public sources → **near-zero server cost**, which is what makes a one-time price sustainable. |
| W3 | **6-frame loops in base.** Too short to judge storm motion or evolution. | Feature list. | 60-frame loops default, limited only by device storage. |
| W4 | **Watches are a Tier 1 upsell.** A tornado watch is basic safety context, not a pro feature. | Feature list. | All NWS products, all free, with location push. |
| W5 | **No "my location" alerting story.** The base feature list is a viewer; nothing about notifying you when a warning covers you. | Feature list. | **Storm Mode**: on-device polygon check + push when a warning/watch covers your saved places or GPS. |
| W6 | **Forced UI changes.** Sept 2026 update "toned down" road overlays; long-time Tier 1 users asked for a user setting. | Play reviews, Sep 2026. | Every map layer has an **intensity slider** + label density control. No opinionated defaults you can't undo. |
| W7 | **Reliability at the worst moment.** Historical reports of crashes/black screens and stale GPS during outbreaks; update regressions, billing glitches. | StormTrack threads; chrome-stats review summaries. | Reliability as a feature: crash-free target 99.9 %, **Outbreak Test** release gate (replay archived outbreak days), no account/billing to break. |
| W8 | **Expert-only UX.** Jargon-dense, no guidance. Enthusiasts (the largest buyer group) never learn what a CC drop means. | Product observation; RadarOmega reviews show same gap (tiny fonts, dated menus). | **Learn layer**: long-press any pixel → plain-English explanation of the signature. Pro mode hides it. |
| W9 | **Single-site only below Tier 2.** No national mosaic for a quick "what's going on" glance. | Feature list. | MRMS mosaic as the zoomed-out default; auto-switches to nearest radar as you zoom in. |
| W10 | **Desktop is a separate purchase.** | StormTrack thread (users emulating Android to avoid paying twice). | v2: Compose Multiplatform desktop build; Android buyers get a licence key via Play receipt. |
| W11 | **Heavy install (~147 MB).** | chrome-stats. | Target < 35 MB; map regions downloaded on demand. |
| W12 | **Limited international coverage.** | US/CA/Guam/DK/FI/DE only. | Add open European data (e.g. national open-data radar feeds) post-launch, country by country. |

## 4. Other competitors (for positioning)

| App | Model | Strength | Weakness |
|---|---|---|---|
| **RadarOmega** | $8.99 + 3 sub tiers ($4.99–$11.99/mo) + RapidSweep add-on | Huge feature set, 3D volumetric, desktop included | 3.5★ on iOS; laggy, tiny fonts, dated menus, black screens |
| **GR2Analyst / GRLevel3** | One-time Windows licences | Gold standard for analysts; placefiles ecosystem | Desktop only, Windows only |
| **MyRadar** | Free + ads/subs | Polished, mass market | Not a pro radar tool |
| **wX (open source)** | Free | Lots of NWS products | Rough UI |

**Gap in the market:** nobody offers *pro-grade Level II + all NWS products + archive* on Android for **a single fair price with a modern, learnable UI**.

## 5. Positioning statement

> **Couplet** is the professional radar you buy once. Every product, every tilt, every warning, thirty years of archive — no tiers, no subscriptions, no account.

## 6. Pricing recommendation

- **$19.99 one-time** (launch week $14.99). Still cheaper than RadarScope base + one year of Tier 1, and ~5× cheaper than year one of Tier 2.
- Family Library enabled.
- Optional **tip jar** (consumable IAP, no features) — keep it strictly cosmetic or drop it entirely; the "no IAP" badge is marketing gold against RadarScope.
- Regional pricing via Play's local price templates.

### Is one-time sustainable?
Yes, because the expensive part RadarScope pays for — data servers — we don't need:

| Data | Source | Cost to us |
|---|---|---|
| NEXRAD Level II real-time chunks + 1991→ archive | AWS Open Data (`unidata-nexrad-level2-chunks`, `unidata-nexrad-level2`) | $0, no account |
| Level III products | AWS Open Data (`unidata-nexrad-level3`) | $0 |
| Warnings, watches, statements, LSRs | NWS API (api.weather.gov) | $0 |
| SPC/WPC outlooks, MDs | NOAA SPC/WPC GeoJSON feeds | $0 |
| MRMS mosaics | NOAA on AWS Open Data | $0 |
| GOES-19/18 imagery + GLM lightning | NOAA on AWS Open Data | $0 |
| Base map tiles | OpenFreeMap / self-hosted PMTiles on a CDN | ~$0–20/mo |

The one thing we **won't** include is licensed ground-network lightning (that has real per-user cost). GLM total-lightning from GOES is free and covers the Americas — a defensible substitute.

Break-even: with a CDN bill under $50/mo and ~70 % net after Play's cut (15 % for the first $1M), roughly **4 sales/month** covers infrastructure.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Public endpoints rate-limit or change | Abstract every source behind a `DataSource` interface; keep a cheap optional mirror ready. |
| NWS API outages during outbreaks | Fallback to NOAA CAP/ATOM feed; cache last good set and show staleness clearly. |
| Push reliability without a server | Storm Mode foreground service (60 s polling) + 15-min background checks; optional free-tier relay later. Be honest in UI about which mode is active. |
| One-time revenue plateaus | Paid major versions (v2 desktop, v3 international) as separate SKUs; v1 buyers keep v1 forever. |
| Liability | Clear "not an official warning source" disclaimer; always link to NWS text. |
