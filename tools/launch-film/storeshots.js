const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 2160 } });
  await p.goto('http://localhost:8765/store.html');
  await p.evaluate(async () => { await document.fonts.load('600 40px IT', 'Αβγ'); await document.fonts.load('500 20px ITG', 'Αβγ'); await document.fonts.load('500 20px PM'); });
  const out = '../store';
  for (const lang of ['en', 'el', 'de']) {
    const keys = ['ref', 'vel', 'warn', 'track', 'cc'];
    for (const [i, k] of keys.entries()) {
      await p.evaluate(([l, k]) => window.show('shot', l, k), [lang, k]);
      await (await p.$('.shot')).screenshot({ path: `${out}/${lang}/screenshot-${i + 1}-${k}.png` });
    }
    await p.evaluate(l => window.show('fg', l), lang);
    await (await p.$('.fg')).screenshot({ path: `${out}/${lang}/feature-graphic-1024x500.png` });
  }
  await p.evaluate(() => window.show('icon'));
  await (await p.$('.icon')).screenshot({ path: `${out}/icon-512.png` });
  await b.close();
})();
