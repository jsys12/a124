/*
 * Element picker. Injected on demand; the native UI drives it through a randomly named global.
 * Tapping an element selects it; wider()/narrower() walk the tree; selector() reports the rule.
 */
(function() {
    'use strict';
    const NAME = '%PICKER%';
    if (window[NAME]) { window[NAME].start(); return; }

    let host = null;
    let box = null;
    let preview = null;
    let current = null;
    const trail = [];
    let active = false;

    function isRandomish(s) {
        return /\d{4,}|[0-9a-f]{8,}|^[a-z]{1,2}\d|[A-Z0-9]{6,}|--|__[a-z0-9]{5,}/.test(s) || s.length > 40;
    }

    function esc(s) {
        return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, '\\$&');
    }

    function count(sel) {
        try { return document.querySelectorAll(sel).length; } catch (e) { return 0; }
    }

    function compound(el) {
        let s = el.localName;
        if (el.id && !isRandomish(el.id)) { return '#' + esc(el.id); }
        const classes = Array.prototype.filter.call(el.classList, function(c) { return !isRandomish(c); }).slice(0, 3);
        if (classes.length) { s += '.' + classes.map(esc).join('.'); }
        return s;
    }

    function selectorFor(el) {
        if (el.id && !isRandomish(el.id) && count('#' + esc(el.id)) === 1) { return '#' + esc(el.id); }
        const own = compound(el);
        const n = count(own);
        // A class shared by many elements is only used when it looks like an ad slot.
        const adLike = /(^|[^a-z])(ad|ads|advert|banner|promo|sponsor|teaser|reklam|реклам|dfp|gpt|taboola|outbrain|adfox|yandex_rtb|direct)/i;
        if (own.indexOf('.') !== -1 && (n === 1 || (n > 1 && n <= 30 && adLike.test(own)))) { return own; }
        const parts = [];
        let e = el;
        while (e && e !== document.body && e !== document.documentElement && parts.length < 6) {
            let part = compound(e);
            if (part.charAt(0) === '#') { parts.unshift(part); break; }
            const p = e.parentElement;
            if (p) {
                const same = Array.prototype.filter.call(p.children, function(c) { return c.localName === e.localName; });
                if (same.length > 1) { part += ':nth-of-type(' + (same.indexOf(e) + 1) + ')'; }
            }
            parts.unshift(part);
            const sel = parts.join(' > ');
            if (count(sel) === 1) { return sel; }
            e = p;
        }
        return parts.join(' > ');
    }

    function describe(el) {
        const r = el.getBoundingClientRect();
        return el.localName + ' ' + Math.round(r.width) + '×' + Math.round(r.height);
    }

    function draw() {
        if (!box) { return; }
        if (!current) { box.style.display = 'none'; return; }
        const r = current.getBoundingClientRect();
        box.style.display = 'block';
        box.style.left = r.left + 'px';
        box.style.top = r.top + 'px';
        box.style.width = r.width + 'px';
        box.style.height = r.height + 'px';
    }

    function onClick(ev) {
        if (!active) { return; }
        ev.preventDefault();
        ev.stopPropagation();
        ev.stopImmediatePropagation();
        const x = ev.clientX;
        const y = ev.clientY;
        host.style.display = 'none';
        const el = document.elementFromPoint(x, y);
        host.style.display = '';
        if (!el || el === document.documentElement || el === document.body) { return; }
        trail.length = 0;
        current = el;
        draw();
    }

    function block(ev) {
        if (!active) { return; }
        ev.preventDefault();
        ev.stopPropagation();
        ev.stopImmediatePropagation();
    }

    const api = {
        start: function() {
            if (active) { return; }
            active = true;
            host = document.createElement('bastion-picker');
            const root = host.attachShadow({ mode: 'closed' });
            host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;pointer-events:none;';
            box = document.createElement('div');
            box.style.cssText = 'position:fixed;display:none;pointer-events:none;box-sizing:border-box;' +
                'border:2px solid #ff5252;background:rgba(255,82,82,.22);border-radius:4px;transition:all .12s ease;';
            root.appendChild(box);
            document.documentElement.appendChild(host);
            window.addEventListener('click', onClick, true);
            for (const t of ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchend', 'auxclick', 'contextmenu']) {
                window.addEventListener(t, block, true);
            }
            window.addEventListener('scroll', draw, true);
            window.addEventListener('resize', draw, true);
        },
        stop: function() {
            active = false;
            window.removeEventListener('click', onClick, true);
            for (const t of ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchend', 'auxclick', 'contextmenu']) {
                window.removeEventListener(t, block, true);
            }
            window.removeEventListener('scroll', draw, true);
            window.removeEventListener('resize', draw, true);
            api.preview(false);
            if (host) { host.remove(); }
            host = null; box = null; current = null; trail.length = 0;
            delete window[NAME];
        },
        wider: function() {
            if (!current) { return api.state(); }
            const p = current.parentElement;
            if (p && p !== document.body && p !== document.documentElement) {
                trail.push(current);
                current = p;
                draw();
            }
            return api.state();
        },
        narrower: function() {
            if (trail.length) { current = trail.pop(); draw(); }
            return api.state();
        },
        preview: function(on) {
            if (preview) { preview.remove(); preview = null; }
            if (on && current) {
                preview = document.createElement('style');
                preview.textContent = selectorFor(current) + '{display:none!important}';
                (document.head || document.documentElement).appendChild(preview);
            }
            return api.state();
        },
        state: function() {
            if (!current) { return JSON.stringify({ selected: false }); }
            const sel = selectorFor(current);
            return JSON.stringify({ selected: true, selector: sel, count: count(sel), label: describe(current) });
        },
    };
    Object.defineProperty(window, NAME, { value: api, configurable: true });
    api.start();
})();
