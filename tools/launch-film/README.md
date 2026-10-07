# Launch film

Sources for the 22-second Hookline launch video (1920×1080, 30 fps).

- `gen_assets.py` renders the radar stills (from `tools/mockup-radar`), the cross-section and the site list into `assets/`.
- `film.html` draws every frame as a pure function of time (`render(t)`); `frames.js` captures them with Playwright from a local server.
- `score.py` synthesises the music and sound design (`score.wav`).
- Encode: `ffmpeg -framerate 30 -i frames/f%04d.jpg -i score.wav -c:v libx264 -crf 17 -pix_fmt yuv420p -c:a aac -shortest brag.mp4`

Fonts (Inter Tight, IBM Plex Mono) come from the @fontsource npm packages. Rendered output is not committed.
