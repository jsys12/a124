let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/opt/node22/lib/node_modules/playwright'); }
const { chromium } = playwright;
const fs = require('fs');
const path = require('path');
const { assemble } = require('./assemble');

let failures = 0;
function check(name, ok, extra) {
    console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? '  ' + JSON.stringify(extra) : ''));
    if (!ok) { failures++; }
}

const PAGE = `<!DOCTYPE html><html><head><title>t</title>
<script>
  window.results = {};
  try { window.results.canRunAds = window.canRunAds; } catch (e) { window.results.canRunAds = 'err'; }
  window.results.timerFired = false;
  setTimeout(function() { /* adblock detector */ window.results.timerFired = true; }, 10);
  setTimeout(function() { window.results.goodTimer = true; }, 10);
  window.results.parsed = JSON.parse('{"adPlacements":[1],"playerResponse":{"playerAds":[1],"x":1},"ok":2}');
  window.results.pruned = JSON.parse('{"data":{"ads":[1,2],"items":[{"ad":1,"v":1},{"v":2}]}}');
  window.results.opened = window.open('https://popunder.example/ad');
</script>
<script>
  // reads a property that aopr aborts; the rest of this script must not run
  window.results.beforeAbort = true;
  var x = window.adsbygoogleX.push;
  window.results.afterAbort = true;
</script>
<script>window.results.rmnt = 'script-ran';/* adblock-detect-marker */</script>
<style>.fixedbar{position:fixed;bottom:0}</style>
</head><body>
<div class="sidebar-promo" id="spec">specific</div>
<div class="ad-banner" id="gen1">generic keyed</div>
<div data-ad-slot="1" id="gen2">generic unkeyed</div>
<div class="post" id="p1">Обычный пост</div>
<div class="post" id="p2">Это Реклама партнёра</div>
<div class="card" id="c1"><span class="sponsored-label">Sponsored</span></div>
<div class="card" id="c2"><span>normal</span></div>
<div id="up"><div><span class="deep-ad" id="deep">x</span></div></div>
<div class="fixedbar" id="fx">fixed banner</div>
<div class="item" id="rm1">PROMO item</div>
<div class="z" id="st">styled</div>
<a id="lnk" href="https://tracker.example/click?u=1">https://real.example/page</a>
<div id="attr" onclick="bad()" class="keep">attr</div>
<iframe id="adframe" src="https://ads.thirdparty.net/banner" width="300" height="250"></iframe>
<iframe id="okframe" src="https://www.youtube.com/embed/x" width="300" height="250"></iframe>
<div id="late-holder"></div>
<script>window.results.rmnt2 = 'ok';</script>
</body></html>`;

(async () => {
    const browser = await chromium.launch();
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('pageerror', e => console.log('pageerror:', e.message));
    const cfg = {
        on: true, cosm: true, gen: true, aggr: true, yt: true, gpc: true, attr: 'bst1', site: 'test.example',
        css: '.sidebar-promo{display:none!important}\n[data-ad-slot]{display:none!important}\n',
        proc: [
            '.post:has-text(Реклама)',
            '.card:has(> .sponsored-label)',
            '.deep-ad:upward(2)',
            'div:matches-css(position: fixed)',
            '.item:has-text(/promo/i):remove()',
            '.z:style(color: rgb(255, 0, 0) !important)',
        ],
        sl: [
            ['set-constant', 'canRunAds', 'true'],
            ['abort-on-property-read', 'adsbygoogleX'],
            ['no-setTimeout-if', 'adblock'],
            ['nowoif'],
            ['json-prune', 'data.ads data.items.[].ad'],
            ['remove-node-text', 'script', 'adblock-detect-marker'],
            ['remove-attr', 'onclick', '#attr'],
            ['href-sanitizer', '#lnk'],
            ['set-cookie', 'consent', 'accept'],
            ['no-fetch-if', 'ads.js'],
            ['trusted-replace-fetch-response', 'SECRET_AD', 'clean', 'data.json'],
            ['nofab'],
        ],
        js: [],
    };
    const genericMap = { '.ad-banner': '.ad-banner', '#late-ad': '#late-ad' };
    await page.addInitScript(({ cfg, genericMap }) => {
        window.__B = {
            config: () => JSON.stringify(cfg),
            generic: (url, keys) => keys.split('\n').filter(k => genericMap[k]).map(k => genericMap[k] + '{display:none!important}').join('\n'),
            heuristic: () => { window.__heuristicHits = (window.__heuristicHits || 0) + 1; },
        };
    }, { cfg, genericMap });
    await page.addInitScript({ content: assemble('__B', '__G') });
    await page.route('https://test.example/**', route => {
        const u = route.request().url();
        if (u.endsWith('/data.json')) { return route.fulfill({ contentType: 'application/json', body: '{"v":"SECRET_AD"}' }); }
        if (u.endsWith('/ads.js')) { return route.fulfill({ contentType: 'application/javascript', body: 'window.realAds=1' }); }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: PAGE });
    });
    await page.route('https://ads.thirdparty.net/**', r => r.fulfill({ contentType: 'text/html', body: '<p>ad</p>' }));
    await page.route('https://www.youtube.com/**', r => r.fulfill({ contentType: 'text/html', body: '<p>yt</p>' }));
    await page.goto('https://test.example/page');
    await page.evaluate(() => {
        const d = document.createElement('div');
        d.id = 'late-ad';
        d.textContent = 'late';
        document.getElementById('late-holder').appendChild(d);
    });
    await page.waitForTimeout(600);

    const hidden = id => page.evaluate(id => { const e = document.getElementById(id); return e ? getComputedStyle(e).display === 'none' : 'missing'; }, id);
    check('specific css', await hidden('spec') === true);
    check('generic keyed (lazy)', await hidden('gen1') === true);
    check('generic unkeyed', await hidden('gen2') === true);
    check('generic keyed, added later', await hidden('late-ad') === true);
    check('procedural has-text', await hidden('p2') === true && await hidden('p1') === false);
    check('procedural :has(>)', await hidden('c1') === true && await hidden('c2') === false);
    check('procedural upward', await hidden('up') === true);
    check('procedural matches-css', await hidden('fx') === true);
    check('procedural remove()', await page.evaluate(() => document.getElementById('rm1') === null));
    check('procedural style()', await page.evaluate(() => getComputedStyle(document.getElementById('st')).color) === 'rgb(255, 0, 0)');
    const r = await page.evaluate(() => window.results);
    check('set-constant', r.canRunAds === true, r.canRunAds);
    check('aopr aborts script', r.beforeAbort === true && r.afterAbort === undefined);
    check('nostif defused', r.timerFired === false && r.goodTimer === true, r);
    check('nowoif', r.opened === null);
    check('youtube JSON.parse strip', r.parsed && !('adPlacements' in r.parsed) && !('playerAds' in r.parsed.playerResponse) && r.parsed.ok === 2, r.parsed);
    check('json-prune', r.pruned && !('ads' in r.pruned.data) && !('ad' in r.pruned.data.items[0]) && r.pruned.data.items[0].v === 1, r.pruned);
    check('rmnt', r.rmnt === undefined && r.rmnt2 === 'ok', r);
    check('remove-attr', await page.evaluate(() => document.getElementById('attr').hasAttribute('onclick')) === false);
    check('href-sanitizer', await page.evaluate(() => document.getElementById('lnk').href) === 'https://real.example/page');
    check('set-cookie', (await page.evaluate(() => document.cookie)).includes('consent=accept'));
    check('no-fetch-if', await page.evaluate(async () => { const t = await (await fetch('/ads.js')).text(); return t === ''; }));
    check('trusted-replace-fetch-response', await page.evaluate(async () => (await (await fetch('/data.json')).json()).v) === 'clean');
    check('nofab', await page.evaluate(() => typeof window.FuckAdBlock === 'function' && typeof window.fuckAdBlock.onDetected === 'function'));
    check('gpc', await page.evaluate(() => navigator.globalPrivacyControl === true));
    check('heuristic iframe hidden', await hidden('adframe') === true && await hidden('okframe') === false);
    check('bridge hidden from page', await page.evaluate(() => typeof window.__B === 'undefined'));

    // Picker
    const picker = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/bastion/picker.js'), 'utf8').split('%PICKER%').join('__P');
    await page.evaluate(picker);
    await page.mouse.click(5, 5);
    const box = await page.evaluate(() => { const r = document.getElementById('p1').getBoundingClientRect(); return { x: r.x, y: r.y }; });
    await page.mouse.click(box.x + 2, box.y + 2);
    let st = JSON.parse(await page.evaluate(() => window.__P.state()));
    check('picker selects', st.selected === true && st.count >= 1 && typeof st.selector === 'string', st);
    st = JSON.parse(await page.evaluate(() => window.__P.wider()));
    check('picker wider', st.selected === true, st);
    await page.evaluate(() => window.__P.stop());
    check('picker stop', await page.evaluate(() => window.__P === undefined && document.querySelector('bastion-picker') === null));

    await browser.close();
    console.log(failures === 0 ? '\nALL PASSED' : '\n' + failures + ' FAILED');
    process.exit(failures === 0 ? 0 : 1);
})();
