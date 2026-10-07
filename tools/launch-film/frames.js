const { chromium } = require('playwright');
(async () => {
  const [from, to] = process.argv.slice(2).map(Number);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => console.log('PAGEERR', e.message));
  await p.goto('http://localhost:8765/film.html');
  await p.evaluate(() => window.ready);
  for (let f = from; f < to; f++) {
    await p.evaluate(t => window.render(t), f / 30);
    await p.screenshot({ path: `frames/f${String(f).padStart(4, '0')}.jpg`, quality: 94, type: 'jpeg' });
  }
  await b.close();
})();
