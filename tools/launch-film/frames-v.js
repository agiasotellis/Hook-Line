const { chromium } = require('playwright');
(async () => {
  const [from, to] = process.argv.slice(2).map(Number);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  p.on('pageerror', e => console.log('PAGEERR', e.message));
  await p.goto('http://localhost:8765/film-vertical.html');
  await p.evaluate(() => window.ready);
  for (let f = from; f < to; f++) {
    await p.evaluate(t => window.render(t), f / 30);
    await p.screenshot({ path: `frames-v/f${String(f).padStart(4, '0')}.jpg`, quality: 94, type: 'jpeg' });
  }
  await b.close();
})();
