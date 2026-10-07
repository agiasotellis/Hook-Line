const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 3 });
  await p.goto('http://localhost:8765/film.html');
  await p.evaluate(() => window.ready);
  const shots = { ref: 9.4, vel: 9.95, cc: 10.45, warn: 13.9, track: 16.05 };
  for (const [name, t] of Object.entries(shots)) {
    await p.evaluate(t => { window.render(t); const ph = document.getElementById('phone'); ph.style.transform = 'none';
      document.getElementById('grain').style.display = 'none'; document.getElementById('vig').style.display = 'none'; }, t);
    const el = await p.$('#phone');
    await el.screenshot({ path: `../store/raw/${name}.png`, omitBackground: true });
  }
  await b.close();
})();
