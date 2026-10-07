const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => console.log('PAGEERR', e.message));
  p.on('console', m => { if (m.type() === 'error') console.log('CONSOLE', m.text()); });
  await p.goto('http://localhost:8765/film.html');
  await p.evaluate(() => window.ready);
  const times = process.argv.slice(2).map(Number);
  for (const t of times) {
    await p.evaluate(t => window.render(t), t);
    await p.screenshot({ path: `stills/t${t.toFixed(2)}.jpg`, quality: 80, type: 'jpeg' });
  }
  await b.close();
})();
