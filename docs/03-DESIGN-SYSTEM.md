# Couplet — Design System

## The one rule
**The map is the only colour.** Radar palettes already use every hue on the wheel — cyan through magenta. Any coloured UI chrome will collide with data and get misread (is that yellow button 45 dBZ?). So the interface is strictly neutral; state is shown with **fill, weight, shape and position**, never hue. The only coloured UI elements are NWS hazard colours on alerts, and they always carry a text label and a pattern so they can't be confused with data.

## Concept: road instrument
Couplet is used from a car seat at dusk. Its visual language borrows from **highway signage and vehicle instruments**: semi-condensed sign-face type, generous hit areas, a tape-style time scrubber like an altimeter, and labels that read at a glance through rain on the windshield.

## Colour

### Night (default)
| Token | Hex | Use |
|---|---|---|
| `surface.map-void` | `#141B22` | Map background where no tiles (blue-slate, not black — keeps OLED smear down and reads as "night sky") |
| `surface.chrome` | `#1C252E` @ 92 % | Floating controls over map |
| `surface.raised` | `#26313C` | Sheets, menus |
| `ink.primary` | `#ECE8DF` | Primary text — warm paper white, softer on dark-adapted eyes than pure white |
| `ink.secondary` | `#9AA5AE` | Secondary text, inactive icons |
| `ink.selected-on` | `#141B22` | Text on selected (filled) controls |
| `line.hair` | `#3A4652` | Dividers, outlines |

### Day (sunglasses)
Map tiles light; chrome becomes `#F3F1EC` with ink `#1A2128`. Contrast ≥ 7:1 for all text because users are outdoors.

### Red-night
All chrome rendered in `#B3261E`-family reds on `#0A0505`, map dimmed 40 %. For spotters protecting night vision.

### Hazard colours (NWS-derived, always paired with label + hatch)
| Hazard | Fill | Pattern |
|---|---|---|
| Tornado warning | `#E0245E` | solid outline 3 dp + diagonal hatch |
| Tornado emergency / PDS | `#B000B0` | double outline + cross hatch |
| Severe thunderstorm warning | `#F5A623` | solid outline 2.5 dp |
| Flash flood warning | `#2E9E5B` | dashed outline |
| Tornado watch | `#FFD84D` | dotted outline 1.5 dp, no fill |
| Severe watch | `#E8A6C8` | dotted outline 1.5 dp, no fill |

### Data staleness
Scan age chip: neutral until 6 min, then gains **diagonal hatching**; at 15 min the radar layer itself is overlaid with a 20 % hatch and the chip reads "Stale · 17 min". Staleness is never hidden.

## Type
| Role | Face | Size / line | Notes |
|---|---|---|---|
| Readout XL (scan age, values) | Barlow Semi Condensed SemiBold | 28 / 32 sp | tabular figures |
| Title | Barlow Semi Condensed SemiBold | 20 / 26 sp | |
| Control label | Barlow Semi Condensed Medium | 15 / 20 sp | product rail, chips |
| Body | Barlow Regular | 15 / 22 sp | alert text, Learn cards |
| Caption | Barlow Medium | 12 / 16 sp | map legend ticks |

Barlow's letterforms were drawn from California highway signage — right for a tool used on the road — and the semi-condensed width saves horizontal space on the map. Sentence case everywhere; product codes (REF, VEL, CC) stay as the official abbreviations because that's the vocabulary pros use.

## Space & shape
- 4 dp base grid; floating controls inset 12 dp from screen edges.
- Minimum touch target **56 dp** (larger than Material's 48 — gloves, bumps).
- Shape encodes role: **pills** = toggles/selection (product rail, tilt), **squircles 14 dp** = floating panels, **sheets** = 24 dp top corners, alerts = 6 dp (sharper = more urgent).
- No drop shadows on chrome over the map; separation comes from the 92 % surface + 1 dp `line.hair` outline (shadows smear radar data underneath).

## Signature components

### Time tape
A horizontal instrument tape above the product rail. Each frame is a tick; tick height = elevation (low tilts tall). The current frame sits under a fixed centre needle; you drag the tape, not a thumb. Shows absolute time (local) on the left and **scan age** on the right in XL readout. Tap the age to jump to live. Long-press to set loop length.

### Product rail
Bottom-anchored horizontal pills: REF · VEL · SRV · CC · ZDR · KDP · ⋯. Selected = filled `ink.primary` with dark text. Swiping the map horizontally with two fingers steps products (pros flip REF/VEL constantly).

### Tilt ladder
Right-edge vertical stack of elevation angles (0.5°, 0.9°, 1.3°…). Selected = filled. Rescans (SAILS) show a small superscript count. Thumb-reachable on the right for right-handed use; mirrors in left-hand mode.

### Site chip
Top-left: radar ID large ("KTLX"), city small ("Oklahoma City"), plus a lock icon when auto-switching is disabled.

### Learn card
Bottom sheet from long-press. Top: value readout (XL). Middle: one-sentence meaning. Bottom: "What to look for" with a tiny annotated thumbnail. Dismiss by releasing.

## Motion
- The only ambient motion is the radar loop itself. UI never animates on its own.
- Responses: sheet open 220 ms emphasised-decelerate; product switch cross-fade 120 ms (instant when reduced motion is on).
- New scan arrival: the scan-age readout ticks to "0:00" with a single 300 ms flash of the needle — the one moment of celebration in the app.

## Accessibility
- All text ≥ 4.5:1 (night), ≥ 7:1 (day).
- Colour-vision-deficiency palettes for velocity (blue/orange) and reflectivity (viridis-like) shipped by default, selectable at onboarding.
- TalkBack: map announces "Tornado warning covers your location, expires 7:42 PM" before data descriptions; Inspector values spoken with units.
- Haptics: distinct pattern for warning arrival vs. new scan.
- Font scaling to 200 % without clipping chrome (controls reflow into a second row).

## Copy voice
Plain, calm, specific. "Tornado warning for Moore until 7:42 PM", not "⚠️ DANGER!". Errors say what happened and what to do: "Can't reach the radar feed. Showing the scan from 7:12 PM. Retrying every 30 s."
