/*
 * Bastion content runtime. Injected at document start into every frame.
 * The host app fills in the bridge/guard names and prepends the scriptlet library and RESOURCES.
 */
(function() {
    'use strict';
    const W = window;
    const BRIDGE = '%BRIDGE%';
    const GUARD = '%GUARD%';
    try {
        if (Object.getOwnPropertyDescriptor(W, GUARD)) { return; }
        Object.defineProperty(W, GUARD, { value: true });
    } catch (e) { return; }

    const bridge = W[BRIDGE];
    try { delete W[BRIDGE]; } catch (e) { /* ignore */ }
    if (bridge === undefined || bridge === null) { return; }

    const isTop = W === W.top;
    let frameUrl = location.href;
    if (!/^https?:/.test(frameUrl)) {
        try { frameUrl = W.parent.location.href; } catch (e) {
            const ao = location.ancestorOrigins;
            frameUrl = ao && ao.length ? ao[0] + '/' : '';
        }
    }
    if (!/^https?:/.test(frameUrl)) { return; }

    const addListener = EventTarget.prototype.addEventListener;
    let cfg;
    try { cfg = JSON.parse(bridge.config(frameUrl, isTop)); } catch (e) { return; }
    if (!cfg || !cfg.on) { return; }

    const MO = W.MutationObserver;
    const setTimeoutSafe = W.setTimeout.bind(W);
    const ATTR = 'data-' + cfg.attr;

    // ------------------------------------------------------------ scriptlets
    if (cfg.sl && cfg.sl.length !== 0) {
        const lib = BASTION_SCRIPTLETS(RESOURCES);
        for (const s of cfg.sl) { lib.run(s[0], s.slice(1)); }
    }
    if (cfg.js && cfg.js.length !== 0) {
        for (const code of cfg.js) {
            try { (0, W.Function)(code)(); } catch (e) { /* CSP or broken rule */ }
        }
    }
    if (cfg.gpc) {
        // Hyperlink auditing (<a ping>) reports clicks to third parties.
        addListener.call(document, 'click', function(ev) {
            const t = ev.target;
            const a = t && t.closest ? t.closest('a[ping]') : null;
            if (a !== null) { a.removeAttribute('ping'); }
        }, true);
        try {
            Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: function() { return true; }, configurable: true });
            Object.defineProperty(Navigator.prototype, 'doNotTrack', { get: function() { return '1'; }, configurable: true });
        } catch (e) { /* ignore */ }
    }
    if (cfg.yt) { youtube(); }

    if (!cfg.cosm) { return; }

    // ------------------------------------------------------------ styles
    const sheets = [];
    let genericSheet = null;

    function adopt(sheet) {
        try {
            const cur = document.adoptedStyleSheets;
            if (cur.indexOf(sheet) === -1) { document.adoptedStyleSheets = cur.concat([sheet]); }
            return true;
        } catch (e) { return false; }
    }

    function addCss(text) {
        if (!text) { return; }
        try {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync(text);
            if (adopt(sheet)) { sheets.push(sheet); return; }
        } catch (e) { /* fall through */ }
        const style = document.createElement('style');
        style.textContent = text;
        const parent = document.head || document.documentElement;
        if (parent) { parent.appendChild(style); } else {
            addListener.call(document, 'DOMContentLoaded', function() {
                (document.head || document.documentElement).appendChild(style);
            });
        }
    }

    function addGenericRules(text) {
        if (genericSheet === null) {
            try {
                genericSheet = new CSSStyleSheet();
                if (!adopt(genericSheet)) { genericSheet = null; }
                else { sheets.push(genericSheet); }
            } catch (e) { genericSheet = null; }
        }
        if (genericSheet === null) { addCss(text); return; }
        const rules = text.split('\n');
        for (const r of rules) {
            if (r === '') { continue; }
            try { genericSheet.insertRule(r, genericSheet.cssRules.length); } catch (e) { /* invalid selector */ }
        }
    }

    function ensureSheets() {
        for (const s of sheets) { adopt(s); }
    }

    addCss(cfg.css);
    addCss('[' + ATTR + '~="h"]{display:none!important}');

    // ------------------------------------------------------------ lazy generic cosmetics
    if (cfg.gen) {
        const seen = new Set();
        let pending = [];
        const addKeys = function(el) {
            const id = el.id;
            if (typeof id === 'string' && id !== '') {
                const k = '#' + id;
                if (!seen.has(k)) { seen.add(k); pending.push(k); }
            }
            const cl = el.classList;
            if (cl !== undefined && cl !== null) {
                for (let i = 0; i < cl.length; i++) {
                    const k = '.' + cl[i];
                    if (!seen.has(k)) { seen.add(k); pending.push(k); }
                }
            }
        };
        const scan = function(root) {
            if (root.nodeType !== 1) { return; }
            addKeys(root);
            const list = root.querySelectorAll('[id],[class]');
            for (let i = 0; i < list.length; i++) { addKeys(list[i]); }
        };
        const flush = function() {
            if (pending.length === 0) { return; }
            const keys = pending.join('\n');
            pending = [];
            let css = '';
            try { css = bridge.generic(frameUrl, keys); } catch (e) { return; }
            if (css) { addGenericRules(css); }
        };
        const observer = new MO(function(mutations) {
            for (const m of mutations) {
                if (m.type === 'attributes') { addKeys(m.target); continue; }
                const added = m.addedNodes;
                for (let i = 0; i < added.length; i++) {
                    if (added[i].nodeType === 1) { scan(added[i]); }
                }
            }
            flush();
        });
        observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
        if (document.documentElement) { scan(document.documentElement); flush(); }
    }

    // ------------------------------------------------------------ procedural filters
    const procedural = [];
    let watchAttrs = false;

    const PROC_OPS = new Set([
        'has-text', '-abp-contains', 'contains', 'has', '-abp-has', 'if', 'if-not', 'not', 'upward',
        'nth-ancestor', 'xpath', 'matches-css', 'matches-css-before', 'matches-css-after', 'min-text-length',
        'matches-attr', 'matches-prop', 'matches-path', 'matches-media', 'others', 'watch-attr',
    ]);
    function hasProcOp(s) {
        return /:(has-text|-abp-has|-abp-contains|contains|if|if-not|upward|nth-ancestor|xpath|matches-css|matches-css-before|matches-css-after|min-text-length|matches-attr|matches-prop|matches-path|matches-media|others|watch-attr)\(/.test(s);
    }

    function findClose(s, open) {
        let depth = 0;
        let quote = null;
        for (let i = open; i < s.length; i++) {
            const c = s[i];
            if (quote !== null) {
                if (c === '\\') { i++; continue; }
                if (c === quote) { quote = null; }
                continue;
            }
            if (c === '\\') { i++; continue; }
            if (c === '"' || c === "'") { quote = c; continue; }
            if (c === '(') { depth++; } else if (c === ')') { depth--; if (depth === 0) { return i; } }
        }
        return -1;
    }

    function segment(s) {
        const out = [];
        let i = 0;
        let cssStart = 0;
        let bracket = 0;
        let paren = 0;
        let quote = null;
        while (i < s.length) {
            const c = s[i];
            if (quote !== null) {
                if (c === '\\') { i += 2; continue; }
                if (c === quote) { quote = null; }
                i++; continue;
            }
            if (c === '\\') { i += 2; continue; }
            if (c === '"' || c === "'") { if (bracket > 0) { quote = c; } i++; continue; }
            if (c === '[') { bracket++; i++; continue; }
            if (c === ']') { bracket--; i++; continue; }
            if (c === '(') { paren++; i++; continue; }
            if (c === ')') { paren--; i++; continue; }
            if (c === ':' && bracket === 0 && paren === 0) {
                const m = /^:([-a-z]+)\(/.exec(s.slice(i, i + 24));
                if (m !== null && PROC_OPS.has(m[1])) {
                    const open = i + m[0].length - 1;
                    const close = findClose(s, open);
                    if (close === -1) { return null; }
                    const arg = s.slice(open + 1, close);
                    const native = (m[1] === 'has' || m[1] === 'not') && !hasProcOp(arg);
                    if (!native) {
                        if (i > cssStart) { out.push({ css: s.slice(cssStart, i) }); }
                        out.push({ op: m[1], arg: arg });
                        i = close + 1;
                        cssStart = i;
                        continue;
                    }
                    i = close + 1;
                    continue;
                }
            }
            i++;
        }
        if (cssStart < s.length) { out.push({ css: s.slice(cssStart) }); }
        return out;
    }

    function textMatcher(arg) {
        const m = /^\/(.+)\/([imsu]*)$/.exec(arg);
        if (m !== null) {
            try { const re = new RegExp(m[1], m[2]); return function(t) { return re.test(t); }; } catch (e) { return null; }
        }
        let lit = arg;
        if (/^".*"$/.test(lit) || /^'.*'$/.test(lit)) { lit = lit.slice(1, -1); }
        return function(t) { return t.indexOf(lit) !== -1; };
    }

    function valueMatcher(arg) {
        const m = /^\/(.+)\/([imsu]*)$/.exec(arg);
        if (m !== null) {
            try { const re = new RegExp(m[1], m[2]); return function(t) { return re.test(t); }; } catch (e) { return null; }
        }
        if (arg.indexOf('*') !== -1) {
            const re = new RegExp('^' + arg.split('*').map(function(p) { return p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('.*') + '$');
            return function(t) { return re.test(t); };
        }
        return function(t) { return t === arg; };
    }

    function splitCompound(text) {
        // Leading compound (no combinator) and the rest.
        let bracket = 0;
        let paren = 0;
        for (let i = 0; i < text.length; i++) {
            const c = text[i];
            if (c === '[') { bracket++; } else if (c === ']') { bracket--; }
            else if (c === '(') { paren++; } else if (c === ')') { paren--; }
            else if (bracket === 0 && paren === 0 && (c === ' ' || c === '>' || c === '+' || c === '~')) {
                return [text.slice(0, i), text.slice(i)];
            }
        }
        return [text, ''];
    }

    function relative(nodes, text) {
        const t = text.trim();
        if (t === '') { return nodes; }
        const out = [];
        const c0 = text[0];
        if (c0 === ' ' || c0 === '>' || c0 === '\t') {
            const sel = ':scope ' + t;
            for (const n of nodes) {
                try { for (const e of n.querySelectorAll(sel)) { out.push(e); } } catch (e) { return []; }
            }
            return out;
        }
        if (c0 === '+' || c0 === '~') {
            const parts = splitCompound(t.slice(1).trim());
            for (const n of nodes) {
                let s = n.nextElementSibling;
                while (s !== null) {
                    try { if (s.matches(parts[0])) { out.push(s); } } catch (e) { return []; }
                    if (c0 === '+') { break; }
                    s = s.nextElementSibling;
                }
            }
            return parts[1] ? relative(out, parts[1]) : out;
        }
        const parts = splitCompound(text);
        for (const n of nodes) {
            try { if (n.matches(parts[0])) { out.push(n); } } catch (e) { return []; }
        }
        return parts[1] ? relative(out, parts[1]) : out;
    }

    function compileSelector(s) {
        const segs = segment(s.trim());
        if (segs === null || segs.length === 0) { return null; }
        const tasks = [];
        for (const seg of segs) {
            if (seg.css !== undefined) { tasks.push({ css: seg.css }); continue; }
            const t = compileOp(seg.op, seg.arg.trim());
            if (t === null) { return null; }
            tasks.push(t);
        }
        return tasks;
    }

    function compileOp(op, arg) {
        switch (op) {
            case 'has-text': case '-abp-contains': case 'contains': {
                const m = textMatcher(arg);
                return m === null ? null : { filter: function(n) { return m(n.textContent); } };
            }
            case 'min-text-length': {
                const len = parseInt(arg, 10);
                return { filter: function(n) { return n.textContent.length >= len; } };
            }
            case 'has': case '-abp-has': case 'if': case 'if-not': case 'not': {
                const sub = compileSelector(/^[>+~]/.test(arg) ? arg : ' ' + arg);
                if (sub === null) { return null; }
                const expect = op !== 'if-not' && op !== 'not';
                const isSibling = /^[+~]/.test(arg);
                return {
                    filter: function(n) {
                        const found = run(sub, [n], true, isSibling).length !== 0;
                        return found === expect;
                    },
                };
            }
            case 'upward': case 'nth-ancestor': {
                const num = parseInt(arg, 10);
                if (!isNaN(num) && String(num) === arg) {
                    if (num < 1 || num > 256) { return null; }
                    return {
                        map: function(n) {
                            let e = n;
                            for (let i = 0; i < num && e !== null; i++) { e = e.parentElement; }
                            return e;
                        },
                    };
                }
                if (op === 'nth-ancestor') { return null; }
                return {
                    map: function(n) {
                        const p = n.parentElement;
                        if (p === null) { return null; }
                        try { return p.closest(arg); } catch (e) { return null; }
                    },
                };
            }
            case 'xpath': {
                return {
                    xpath: function(ctx) {
                        const out = [];
                        try {
                            const r = document.evaluate(arg, ctx, null, XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
                            for (let i = 0; i < r.snapshotLength; i++) {
                                const node = r.snapshotItem(i);
                                if (node.nodeType === 1) { out.push(node); }
                            }
                        } catch (e) { /* ignore */ }
                        return out;
                    },
                };
            }
            case 'matches-css': case 'matches-css-before': case 'matches-css-after': {
                const pos = arg.indexOf(':');
                if (pos === -1) { return null; }
                const prop = arg.slice(0, pos).trim();
                const m = valueMatcher(arg.slice(pos + 1).trim());
                if (m === null) { return null; }
                const pseudo = op === 'matches-css-before' ? '::before' : op === 'matches-css-after' ? '::after' : null;
                return {
                    filter: function(n) {
                        const style = W.getComputedStyle(n, pseudo);
                        return style !== null && m(style.getPropertyValue(prop));
                    },
                };
            }
            case 'matches-attr': {
                const mm = /^\s*("?)([^"=]+)\1\s*(?:=\s*"?(.*?)"?)?\s*$/.exec(arg);
                if (mm === null) { return null; }
                const nameM = valueMatcher(mm[2]);
                const valM = mm[3] !== undefined ? valueMatcher(mm[3]) : null;
                return {
                    filter: function(n) {
                        for (const a of n.getAttributeNames()) {
                            if (!nameM(a)) { continue; }
                            if (valM === null || valM(n.getAttribute(a) || '')) { return true; }
                        }
                        return false;
                    },
                };
            }
            case 'matches-prop': {
                const mm = /^\s*("?)([^"=]+)\1\s*(?:=\s*"?(.*?)"?)?\s*$/.exec(arg);
                if (mm === null) { return null; }
                const chain = mm[2].split('.');
                const valM = mm[3] !== undefined ? valueMatcher(mm[3]) : null;
                return {
                    filter: function(n) {
                        let v = n;
                        for (const p of chain) {
                            if (v === null || v === undefined) { return false; }
                            v = v[p];
                        }
                        if (v === undefined) { return false; }
                        return valM === null || valM(String(v));
                    },
                };
            }
            case 'matches-path': {
                const m = textMatcher(arg);
                if (m === null) { return null; }
                return { gate: function() { return m(location.pathname + location.search); } };
            }
            case 'matches-media': {
                return { gate: function() { try { return W.matchMedia(arg).matches; } catch (e) { return false; } } };
            }
            case 'watch-attr': {
                watchAttrs = true;
                return { filter: function() { return true; } };
            }
            case 'others': {
                return {
                    expand: function(nodes) {
                        const keep = new Set();
                        for (const n of nodes) {
                            let e = n;
                            while (e !== null) { keep.add(e); e = e.parentElement; }
                        }
                        const out = [];
                        for (const n of nodes) {
                            let e = n;
                            while (e !== null && e !== document.body && e !== document.documentElement) {
                                const p = e.parentElement;
                                if (p === null) { break; }
                                for (const sib of p.children) {
                                    if (!keep.has(sib) && sib.localName !== 'script' && sib.localName !== 'style' && sib.localName !== 'head') {
                                        out.push(sib);
                                    }
                                }
                                e = p;
                            }
                        }
                        return out;
                    },
                };
            }
        }
        return null;
    }

    function run(tasks, roots, relativeFirst, siblingFirst) {
        let nodes = null;
        for (let i = 0; i < tasks.length; i++) {
            const t = tasks[i];
            if (t.css !== undefined) {
                if (nodes === null) {
                    if (relativeFirst) {
                        nodes = relative(roots, siblingFirst ? t.css.trim() : t.css);
                    } else {
                        try { nodes = Array.prototype.slice.call(document.querySelectorAll(t.css)); } catch (e) { return []; }
                    }
                } else {
                    nodes = relative(nodes, t.css);
                }
                continue;
            }
            if (t.gate !== undefined) {
                if (!t.gate()) { return []; }
                continue;
            }
            if (nodes === null) {
                if (t.xpath !== undefined) {
                    nodes = [];
                    for (const r of (relativeFirst ? roots : [document])) { nodes = nodes.concat(t.xpath(r)); }
                    continue;
                }
                nodes = relativeFirst ? roots.slice() : Array.prototype.slice.call(document.querySelectorAll('body *'));
                if (relativeFirst && t.filter !== undefined) {
                    // e.g. :has(:has-text(x)) -> test descendants
                    const desc = [];
                    for (const r of roots) { for (const e of r.querySelectorAll('*')) { desc.push(e); } }
                    nodes = desc;
                }
            }
            if (t.filter !== undefined) {
                nodes = nodes.filter(t.filter);
            } else if (t.map !== undefined) {
                const out = [];
                const seen = new Set();
                for (const n of nodes) {
                    const r = t.map(n);
                    if (r !== null && r !== undefined && !seen.has(r)) { seen.add(r); out.push(r); }
                }
                nodes = out;
            } else if (t.xpath !== undefined) {
                let out = [];
                for (const n of nodes) { out = out.concat(t.xpath(n)); }
                nodes = out;
            } else if (t.expand !== undefined) {
                nodes = t.expand(nodes);
            }
            if (nodes.length === 0) { return nodes; }
        }
        return nodes === null ? [] : nodes;
    }

    function compileProcedural(raw, index) {
        let s = raw.trim();
        let action = { type: 'hide' };
        const am = /:(remove|style|remove-attr|remove-class)\(/g;
        let last = null;
        let mm;
        while ((mm = am.exec(s)) !== null) { last = mm; }
        if (last !== null) {
            const open = last.index + last[0].length - 1;
            const close = findClose(s, open);
            if (close === s.length - 1) {
                action = { type: last[1], arg: s.slice(open + 1, close).trim() };
                s = s.slice(0, last.index);
            }
        }
        if (action.type === 'style') {
            if (/url\(|\{|\}|\/\*/i.test(action.arg)) { return null; }
            addCss('[' + ATTR + '~="s' + index + '"]{' + action.arg + '}');
        }
        const tasks = compileSelector(s);
        if (tasks === null) { return null; }
        return { tasks: tasks, action: action, token: 's' + index };
    }

    function mark(el, token) {
        const cur = el.getAttribute(ATTR);
        if (cur === null) { el.setAttribute(ATTR, token); return; }
        if ((' ' + cur + ' ').indexOf(' ' + token + ' ') === -1) { el.setAttribute(ATTR, cur + ' ' + token); }
    }

    function applyProcedural() {
        for (const f of procedural) {
            let nodes;
            try { nodes = run(f.tasks, [document], false, false); } catch (e) { continue; }
            for (const n of nodes) {
                switch (f.action.type) {
                    case 'hide': mark(n, 'h'); break;
                    case 'remove': n.remove(); break;
                    case 'style': mark(n, f.token); break;
                    case 'remove-attr':
                        for (const a of f.action.arg.split('|')) { n.removeAttribute(a.trim().replace(/^["']|["']$/g, '')); }
                        break;
                    case 'remove-class':
                        for (const c of f.action.arg.split('|')) { n.classList.remove(c.trim().replace(/^["']|["']$/g, '')); }
                        break;
                }
            }
        }
    }

    // ------------------------------------------------------------ heuristics (strong mode)
    const AD_SIZES = [
        [300, 250], [728, 90], [320, 50], [320, 100], [300, 600], [160, 600], [970, 250], [970, 90], [336, 280],
        [468, 60], [234, 60], [120, 600], [300, 50], [250, 250], [200, 200], [180, 150], [125, 125], [300, 100],
        [240, 400], [580, 400], [750, 100], [750, 200], [750, 300], [930, 180], [980, 120], [980, 90], [1000, 90],
        [120, 240], [88, 31], [320, 480], [300, 1050],
    ];
    const EMBED_OK = /(^|\.)(youtube(-nocookie)?\.com|youtu\.be|vimeo\.com|google\.com|googleapis\.com|gstatic\.com|recaptcha\.net|hcaptcha\.com|challenges\.cloudflare\.com|twitter\.com|x\.com|twimg\.com|facebook\.com|instagram\.com|tiktok\.com|disqus\.com|spotify\.com|soundcloud\.com|vk\.com|vkvideo\.ru|rutube\.ru|dzen\.ru|ok\.ru|yandex\.(ru|net|com)|dailymotion\.com|twitch\.tv|codepen\.io|jsfiddle\.net|github\.com|gist\.github\.com|reddit\.com|telegram\.org|t\.me|paypal\.com|stripe\.com|apple\.com|bandcamp\.com|coub\.com|streamable\.com|wistia\.(com|net)|brightcove\.net|jwplayer\.com|kinescope\.io|mail\.ru|sberbank\.ru|tinkoff\.ru|tbank\.ru)$/;

    function sizeIsAd(w, h) {
        for (const s of AD_SIZES) {
            if (Math.abs(w - s[0]) <= 2 && Math.abs(h - s[1]) <= 2) { return true; }
        }
        return false;
    }

    function checkIframes() {
        const frames = document.getElementsByTagName('iframe');
        for (let i = 0; i < frames.length; i++) {
            const f = frames[i];
            if (f.hasAttribute(ATTR)) { continue; }
            const src = f.src;
            if (!/^https?:/.test(src)) { continue; }
            let host;
            try { host = new URL(src).hostname; } catch (e) { continue; }
            if (host === cfg.site || host.endsWith('.' + cfg.site)) { continue; }
            if (EMBED_OK.test(host)) { continue; }
            const w = f.clientWidth || parseInt(f.getAttribute('width'), 10) || 0;
            const h = f.clientHeight || parseInt(f.getAttribute('height'), 10) || 0;
            if (w === 0 || h === 0) { continue; }
            if (sizeIsAd(w, h)) {
                mark(f, 'h');
                try { bridge.heuristic(frameUrl, src); } catch (e) { /* ignore */ }
            }
        }
    }

    // ------------------------------------------------------------ scheduling
    if (cfg.proc && cfg.proc.length !== 0) {
        for (let i = 0; i < cfg.proc.length; i++) {
            try {
                const f = compileProcedural(cfg.proc[i], i);
                if (f !== null) { procedural.push(f); }
            } catch (e) { /* skip invalid */ }
        }
    }
    let scheduled = false;
    let runs = 0;
    function tick() {
        scheduled = false;
        runs++;
        if (procedural.length !== 0) { applyProcedural(); }
        if (cfg.aggr) { checkIframes(); }
    }
    function schedule() {
        if (scheduled) { return; }
        scheduled = true;
        setTimeoutSafe(tick, runs < 20 ? 30 : 250);
    }

    if (procedural.length !== 0 || cfg.aggr) {
        const obs = new MO(function() { schedule(); });
        const opts = { childList: true, subtree: true };
        if (watchAttrs) { opts.attributes = true; }
        obs.observe(document, opts);
        schedule();
    }
    addListener.call(document, 'DOMContentLoaded', function() { ensureSheets(); schedule(); });
    addListener.call(W, 'load', function() { ensureSheets(); schedule(); });

    // ------------------------------------------------------------ YouTube
    function youtube() {
        const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
        const strip = function(o) {
            if (o === null || typeof o !== 'object') { return o; }
            for (const k of AD_KEYS) { if (k in o) { try { delete o[k]; } catch (e) { /* ignore */ } } }
            if (o.playerResponse && typeof o.playerResponse === 'object') { strip(o.playerResponse); }
            if (Array.isArray(o)) { for (const e of o) { if (e && e.playerResponse) { strip(e.playerResponse); } } }
            return o;
        };
        const looksLikePlayer = function(o) {
            return o !== null && typeof o === 'object' &&
                ('adPlacements' in o || 'playerAds' in o || 'adSlots' in o || (o.playerResponse && typeof o.playerResponse === 'object'));
        };
        JSON.parse = new Proxy(JSON.parse, {
            apply: function(target, thisArg, args) {
                const r = Reflect.apply(target, thisArg, args);
                try { if (looksLikePlayer(r)) { strip(r); } } catch (e) { /* ignore */ }
                return r;
            },
        });
        if (!Object.getOwnPropertyDescriptor(W, 'ytInitialPlayerResponse')) {
            let ipr;
            try {
                Object.defineProperty(W, 'ytInitialPlayerResponse', {
                    configurable: true,
                    get: function() { return ipr; },
                    set: function(v) { ipr = strip(v); },
                });
            } catch (e) { /* ignore */ }
        }
        const origFetch = W.fetch;
        W.fetch = new Proxy(origFetch, {
            apply: function(target, thisArg, args) {
                const p = Reflect.apply(target, thisArg, args);
                let url = '';
                try { url = args[0] instanceof Request ? args[0].url : String(args[0]); } catch (e) { /* ignore */ }
                if (!/\/youtubei\/v1\/(player|next|reel)/.test(url)) { return p; }
                return p.then(function(response) {
                    return response.clone().json().then(function(obj) {
                        if (!looksLikePlayer(obj)) { return response; }
                        strip(obj);
                        return new Response(JSON.stringify(obj), {
                            status: response.status, statusText: response.statusText, headers: response.headers,
                        });
                    }).catch(function() { return response; });
                });
            },
        });
        // Last line of defence: fast-forward any ad that still starts playing.
        let mutedByUs = null;
        W.setInterval(function() {
            const player = document.querySelector('.ad-showing');
            const video = document.querySelector('video');
            if (player === null) {
                if (mutedByUs !== null) {
                    try { mutedByUs.muted = false; mutedByUs.playbackRate = 1; } catch (e) { /* ignore */ }
                    mutedByUs = null;
                }
                return;
            }
            if (video !== null) {
                if (!video.muted) { video.muted = true; mutedByUs = video; }
                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
                    try { video.currentTime = video.duration - 0.1; } catch (e) { /* ignore */ }
                }
                try { video.playbackRate = 16; } catch (e) { /* ignore */ }
            }
            const btn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, .ytm-skip-ad-button, .ytp-ad-overlay-close-button');
            if (btn !== null) { try { btn.click(); } catch (e) { /* ignore */ } }
        }, 300);
    }
})();
