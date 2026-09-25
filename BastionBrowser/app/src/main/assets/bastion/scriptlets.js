/*
 * Bastion scriptlet library.
 * Independent implementations of the scriptlets used by uBlock Origin / AdGuard filter lists
 * (same names and argument conventions). Runs in the page's main world at document start.
 * Defines `BASTION_SCRIPTLETS(RESOURCES)` returning { run(name, args, trusted) }.
 */
// eslint-disable-next-line no-unused-vars
function BASTION_SCRIPTLETS(RESOURCES) {
    'use strict';
    const W = window;
    const safe = {
        Object_defineProperty: Object.defineProperty.bind(Object),
        Object_defineProperties: Object.defineProperties.bind(Object),
        Object_getOwnPropertyDescriptor: Object.getOwnPropertyDescriptor.bind(Object),
        Object_hasOwn: (o, k) => Object.prototype.hasOwnProperty.call(o, k),
        Object_keys: Object.keys,
        Object_fromEntries: Object.fromEntries,
        Reflect_apply: Reflect.apply,
        Reflect_construct: Reflect.construct,
        JSON_parse: JSON.parse.bind(JSON),
        JSON_stringify: JSON.stringify.bind(JSON),
        RegExp: W.RegExp,
        Proxy: W.Proxy,
        Error: W.Error,
        Math_random: Math.random,
        Math_floor: Math.floor,
        String_split: String.prototype.split,
        Function_toString: Function.prototype.toString,
        Array_from: Array.from,
        Promise: W.Promise,
        Response: W.Response,
        fetch: W.fetch,
        XMLHttpRequest: W.XMLHttpRequest,
        MutationObserver: W.MutationObserver,
        setTimeout: W.setTimeout.bind(W),
        clearTimeout: W.clearTimeout.bind(W),
        addEventListener: EventTarget.prototype.addEventListener,
        DOMParser: W.DOMParser,
        XMLSerializer: W.XMLSerializer,
    };

    // ------------------------------------------------------------------ helpers

    function escapeRegex(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    const NEVER = /[^\s\S]/;

    function patternToRegex(pattern, flags, verbatim) {
        if (pattern === undefined || pattern === '') { return /^/; }
        const m = /^\/(.+)\/([dgimsuvy]*)$/.exec(pattern);
        if (m !== null) {
            try { return new safe.RegExp(m[1], m[2] || flags); } catch (e) { return NEVER; }
        }
        let src = escapeRegex(pattern);
        if (verbatim) { src = '^' + src + '$'; }
        try { return new safe.RegExp(src, flags); } catch (e) { return NEVER; }
    }

    /** Pattern with optional leading "!" negation. */
    function initPattern(pattern, flags) {
        let expect = true;
        if (pattern && pattern.startsWith('!')) { expect = false; pattern = pattern.slice(1); }
        return { re: patternToRegex(pattern, flags), expect: expect, empty: !pattern };
    }

    function testPattern(details, haystack) {
        if (details.re.global || details.re.sticky) { details.re.lastIndex = 0; }
        return details.re.test(haystack) === details.expect;
    }

    function randomToken() {
        return String.fromCharCode(Date.now() % 26 + 97) + safe.Math_floor(safe.Math_random() * 982451653 + 982451653).toString(36);
    }

    const magics = [];
    let errorTrapInstalled = false;
    function registerMagic(magic) {
        magics.push(magic);
        if (errorTrapInstalled) { return; }
        errorTrapInstalled = true;
        const handler = function(ev) {
            const msg = ev && (ev.message || (ev.error && ev.error.message) || (ev.reason && ev.reason.message));
            if (typeof msg !== 'string') { return; }
            for (let i = 0; i < magics.length; i++) {
                if (msg.indexOf(magics[i]) !== -1) {
                    ev.preventDefault();
                    ev.stopImmediatePropagation();
                    return;
                }
            }
        };
        safe.addEventListener.call(W, 'error', handler, true);
        safe.addEventListener.call(W, 'unhandledrejection', handler, true);
    }

    /** Parses trailing "key, value" pairs used by newer scriptlets. */
    function extraArgs(args) {
        const out = {};
        for (let i = 0; i + 1 < args.length; i += 2) { out[args[i]] = args[i + 1]; }
        return out;
    }

    function runAt(fn, when) {
        const order = { loading: 1, asap: 1, interactive: 2, 'end': 2, '2': 2, complete: 3, idle: 3, '3': 3 };
        let target = 2;
        const tokens = String(when || '').split(/\s+/);
        for (const t of tokens) { if (order[t] !== undefined) { target = order[t]; } }
        const state = () => ({ loading: 1, interactive: 2, complete: 3 })[document.readyState] || 1;
        if (state() >= target) { fn(); return; }
        const listener = () => {
            if (state() < target) { return; }
            fn();
            document.removeEventListener('readystatechange', listener, true);
        };
        safe.addEventListener.call(document, 'readystatechange', listener, true);
    }

    const noopFunc = function() {};
    const trueFunc = function() { return true; };
    const falseFunc = function() { return false; };

    /** Values allowed in untrusted set-constant-like scriptlets. Returns [ok, value]. */
    function validateConstant(raw, trusted, extra) {
        const map = {
            'undefined': undefined, 'false': false, 'true': true, 'null': null, "''": '', 'emptyStr': '',
            'noopFunc': noopFunc, 'trueFunc': trueFunc, 'falseFunc': falseFunc,
            'throwFunc': function() { throw new safe.Error(); },
            'noopCallbackFunc': function() { return noopFunc; },
            'emptyArr': [], 'emptyArray': [], '[]': [], 'emptyObj': {}, '{}': {},
            'noopPromiseResolve': function() { return safe.Promise.resolve(); },
            'noopPromiseReject': function() { return safe.Promise.reject(); },
            'yes': 'yes', 'no': 'no', 'on': 'on', 'off': 'off', 'NaN': NaN, 'Infinity': Infinity,
            '-Infinity': -Infinity, '-1': -1, '-0': -0,
        };
        let value;
        if (safe.Object_hasOwn(map, raw)) {
            value = map[raw];
            if (value !== null && typeof value === 'object') { value = Array.isArray(value) ? [] : {}; }
        } else if (/^-?\d+$/.test(raw)) {
            value = parseInt(raw, 10);
            if (!trusted && Math.abs(value) > 0x7FFF) { return [false]; }
        } else if (/^'.*'$/.test(raw)) {
            value = raw.slice(1, -1);
        } else if (trusted) {
            if (raw.startsWith('{') || raw.startsWith('[') || raw.startsWith('"')) {
                try { value = safe.JSON_parse(raw); } catch (e) { value = raw; }
            } else {
                value = raw;
            }
        } else {
            return [false];
        }
        if (extra && extra.as) {
            const v = value;
            if (extra.as === 'function') { value = function() { return v; }; }
            else if (extra.as === 'callback') { value = function() { return function() { return v; }; }; }
            else if (extra.as === 'resolved') { value = safe.Promise.resolve(v); }
            else if (extra.as === 'rejected') { value = safe.Promise.reject(v); }
        }
        return [true, value];
    }

    function getScriptText(elem) {
        let text = elem.textContent;
        if (text.trim() !== '') { return text; }
        const src = elem.src || '';
        const m = /^data:([^,]*),(.+)$/.exec(src.trim());
        if (m !== null) {
            try { return m[1].endsWith(';base64') ? atob(m[2]) : decodeURIComponent(m[2]); } catch (e) { return ''; }
        }
        return src;
    }

    /** Resolves a property chain, trapping not-yet-defined intermediate objects. */
    function trapChain(owner, chain, onLeaf) {
        const pos = chain.indexOf('.');
        if (pos === -1) { onLeaf(owner, chain); return; }
        const prop = chain.slice(0, pos);
        const rest = chain.slice(pos + 1);
        let v;
        try { v = owner[prop]; } catch (e) { return; }
        if (v instanceof Object || (typeof v === 'object' && v !== null)) { trapChain(v, rest, onLeaf); return; }
        const desc = safe.Object_getOwnPropertyDescriptor(owner, prop);
        if (desc && desc.get !== undefined) { return; }
        if (desc && desc.configurable === false) { return; }
        try {
            safe.Object_defineProperty(owner, prop, {
                get: function() { return v; },
                set: function(a) {
                    v = a;
                    if (a instanceof Object) { trapChain(a, rest, onLeaf); }
                },
                configurable: true,
            });
        } catch (e) { /* ignore */ }
    }

    function resolveChain(chain) {
        let owner = W;
        const parts = chain.split('.');
        for (let i = 0; i < parts.length - 1; i++) {
            if (owner === null || owner === undefined) { return null; }
            owner = owner[parts[i]];
        }
        if (owner === null || owner === undefined) { return null; }
        return { owner: owner, prop: parts[parts.length - 1] };
    }

    function matchesStackTrace(reNeedle, logLevel) {
        const exceptionToken = randomToken();
        const error = new safe.Error(exceptionToken);
        const docURL = new URL(self.location.href);
        docURL.hash = '';
        const reLine = /(.*?@)?(\S+)(:\d+):\d+\)?$/;
        const lines = [];
        for (let line of String(error.stack).split(/[\n\r]+/)) {
            if (line.includes(exceptionToken)) { continue; }
            line = line.trim();
            const match = reLine.exec(line);
            if (match === null) { continue; }
            let url = match[2];
            if (url.startsWith('(')) { url = url.slice(1); }
            if (url === docURL.href) { url = 'inlineScript'; }
            else if (url.startsWith('<anonymous>')) { url = 'injectedScript'; }
            let fn = match[1] !== undefined ? match[1].slice(0, -1) : line.slice(0, match.index).trim();
            if (fn.startsWith('at')) { fn = fn.slice(2).trim(); }
            let rowcol = match[3];
            lines.push(' ' + (fn + ' ' + url + rowcol + ':1').trim());
        }
        lines[0] = 'stackDepth:' + (lines.length - 1);
        const stack = lines.join('\t');
        reNeedle.lastIndex = 0;
        return reNeedle.test(stack);
    }

    function proxyApplyFn(target, handler) {
        const r = resolveChain(target);
        if (r === null) { return; }
        const fn = r.owner[r.prop];
        if (typeof fn !== 'function') { return; }
        const proxy = new safe.Proxy(fn, {
            apply: function(t, thisArg, callArgs) {
                return handler({
                    thisArg: thisArg,
                    callArgs: callArgs,
                    reflect: function() { return safe.Reflect_apply(t, thisArg, callArgs); },
                });
            },
            construct: function(t, callArgs, newTarget) {
                return handler({
                    thisArg: undefined,
                    callArgs: callArgs,
                    reflect: function() { return safe.Reflect_construct(t, callArgs, newTarget); },
                });
            },
        });
        try { r.owner[r.prop] = proxy; } catch (e) { /* ignore */ }
    }

    // --------------------------------------------------------- object pruning

    function findOwner(root, path, prune) {
        const eq = path.indexOf('.[=].');
        if (eq !== -1) {
            const valuePath = path.slice(0, eq);
            const expected = path.slice(eq + 5);
            return findValue(root, valuePath, expected);
        }
        let owner = root;
        let chain = path;
        for (;;) {
            if (typeof owner !== 'object' || owner === null) { return false; }
            const pos = chain.indexOf('.');
            if (pos === -1) {
                if (!prune) { return safe.Object_hasOwn(owner, chain); }
                let modified = false;
                if (chain === '*') {
                    for (const key in owner) {
                        if (!safe.Object_hasOwn(owner, key)) { continue; }
                        delete owner[key];
                        modified = true;
                    }
                } else if (safe.Object_hasOwn(owner, chain)) {
                    delete owner[chain];
                    modified = true;
                }
                return modified;
            }
            const prop = chain.slice(0, pos);
            const next = chain.slice(pos + 1);
            let found = false;
            if (prop === '[-]' && Array.isArray(owner)) {
                let i = owner.length;
                while (i--) {
                    if (!findOwner(owner[i], next, false)) { continue; }
                    owner.splice(i, 1);
                    found = true;
                }
                return found;
            }
            if (prop === '{-}' && owner instanceof Object) {
                for (const key of safe.Object_keys(owner)) {
                    if (!findOwner(owner[key], next, false)) { continue; }
                    delete owner[key];
                    found = true;
                }
                return found;
            }
            if ((prop === '[]' && Array.isArray(owner)) || ((prop === '{}' || prop === '*') && owner instanceof Object)) {
                for (const key of safe.Object_keys(owner)) {
                    if (!findOwner(owner[key], next, prune)) { continue; }
                    found = true;
                }
                return found;
            }
            if (!safe.Object_hasOwn(owner, prop)) { return false; }
            owner = owner[prop];
            chain = next;
        }
    }

    function findValue(root, path, expected) {
        const re = /^\/.+\/[a-z]*$/.test(expected) ? patternToRegex(expected) : null;
        const test = function(v) {
            if (re !== null) { return typeof v === 'string' && re.test(v); }
            return String(v) === expected;
        };
        const walk = function(owner, chain) {
            if (typeof owner !== 'object' || owner === null) { return false; }
            const pos = chain.indexOf('.');
            if (pos === -1) {
                if (chain === '*' || chain === '[]') {
                    return safe.Object_keys(owner).some(function(k) { return test(owner[k]); });
                }
                return safe.Object_hasOwn(owner, chain) && test(owner[chain]);
            }
            const prop = chain.slice(0, pos);
            const next = chain.slice(pos + 1);
            if (prop === '*' || prop === '[]' || prop === '{}') {
                return safe.Object_keys(owner).some(function(k) { return walk(owner[k], next); });
            }
            if (!safe.Object_hasOwn(owner, prop)) { return false; }
            return walk(owner[prop], next);
        };
        return walk(root, path);
    }

    function pruneObject(obj, prunePaths, needlePaths) {
        if (prunePaths.length === 0) { return false; }
        if (typeof obj !== 'object' || obj === null) { return false; }
        for (const needle of needlePaths) {
            if (!findOwner(obj, needle, false)) { return false; }
        }
        let modified = false;
        for (const path of prunePaths) {
            if (findOwner(obj, path, true)) { modified = true; }
        }
        return modified;
    }

    function parsePropsToMatch(propsToMatch, implicit) {
        const needles = [];
        if (!propsToMatch) { return needles; }
        for (const condition of propsToMatch.split(/\s+/)) {
            if (condition === '') { continue; }
            const pos = condition.indexOf(':');
            let key;
            let value;
            if (pos !== -1 && /^[a-zA-Z]+$/.test(condition.slice(0, pos))) {
                key = condition.slice(0, pos);
                value = condition.slice(pos + 1);
            } else {
                key = implicit || 'url';
                value = condition;
            }
            needles.push({ key: key, details: initPattern(value) });
        }
        return needles;
    }

    function matchProps(needles, details) {
        for (const n of needles) {
            let v = details[n.key];
            if (v === undefined) { v = ''; }
            if (!testPattern(n.details, String(v))) { return false; }
        }
        return true;
    }

    function fetchDetails(args) {
        const details = { url: '', method: 'GET' };
        const a = args[0];
        try {
            if (a instanceof Request) {
                details.url = a.url;
                details.method = a.method;
                details.mode = a.mode;
                details.credentials = a.credentials;
            } else {
                details.url = String(a);
            }
        } catch (e) { /* ignore */ }
        const init = args[1];
        if (init instanceof Object) {
            for (const k of ['method', 'body', 'mode', 'credentials', 'cache', 'redirect', 'referrer', 'referrerPolicy', 'integrity', 'keepalive', 'signal', 'priority', 'headers']) {
                if (init[k] !== undefined) { details[k] = init[k]; }
            }
            if (typeof details.body !== 'string' && details.body !== undefined) {
                try { details.body = String(details.body); } catch (e) { details.body = ''; }
            }
        }
        return details;
    }

    function textResponse(original, text) {
        const response = new safe.Response(text, {
            status: original.status,
            statusText: original.statusText,
            headers: original.headers,
        });
        try {
            safe.Object_defineProperties(response, {
                ok: { value: original.ok },
                redirected: { value: original.redirected },
                type: { value: original.type },
                url: { value: original.url },
            });
        } catch (e) { /* ignore */ }
        return response;
    }

    /** Wraps fetch so matching responses' text can be rewritten. */
    function onFetchText(needles, transform) {
        W.fetch = new safe.Proxy(W.fetch, {
            apply: function(target, thisArg, args) {
                const details = fetchDetails(args);
                const p = safe.Reflect_apply(target, thisArg, args);
                if (needles.length !== 0 && !matchProps(needles, details)) { return p; }
                return p.then(function(response) {
                    return response.clone().text().then(function(text) {
                        const out = transform(text, details);
                        if (out === undefined || out === text) { return response; }
                        return textResponse(response, out);
                    }).catch(function() { return response; });
                });
            },
        });
    }

    const xhrTransforms = [];
    let xhrPatched = false;
    /** Wraps XMLHttpRequest so matching responses' text can be rewritten. */
    function onXhrText(needles, transform) {
        xhrTransforms.push({ needles: needles, transform: transform });
        if (xhrPatched) { return; }
        xhrPatched = true;
        const instances = new WeakMap();
        const Base = W.XMLHttpRequest;
        W.XMLHttpRequest = class extends Base {
            open(method, url) {
                instances.set(this, { method: method, url: String(url), xhr: this });
                return super.open.apply(this, arguments);
            }
            get response() {
                const r = super.response;
                const d = instances.get(this);
                if (d === undefined || this.readyState !== 4) { return r; }
                if (this.responseType !== '' && this.responseType !== 'text') {
                    if (this.responseType === 'json' && r !== null) {
                        const text = rewrite(d, safe.JSON_stringify(r));
                        if (text === undefined) { return r; }
                        try { return safe.JSON_parse(text); } catch (e) { return r; }
                    }
                    return r;
                }
                const text = rewrite(d, r);
                return text === undefined ? r : text;
            }
            get responseText() {
                const r = super.responseText;
                const d = instances.get(this);
                if (d === undefined || this.readyState !== 4) { return r; }
                const text = rewrite(d, r);
                return text === undefined ? r : text;
            }
        };
        function rewrite(d, text) {
            if (d.cache !== undefined && d.cacheSrc === text) { return d.cache; }
            let out = text;
            let changed = false;
            for (const t of xhrTransforms) {
                if (t.needles.length !== 0 && !matchProps(t.needles, d)) { continue; }
                const r = t.transform(out, d);
                if (r !== undefined && r !== out) { out = r; changed = true; }
            }
            d.cacheSrc = text;
            d.cache = changed ? out : undefined;
            return d.cache;
        }
    }

    function jsonTransform(prunePaths, needlePaths) {
        return function(text) {
            if (typeof text !== 'string' || text === '') { return undefined; }
            const c = text.trimStart()[0];
            if (c !== '{' && c !== '[') { return undefined; }
            let obj;
            try { obj = safe.JSON_parse(text); } catch (e) { return undefined; }
            if (!pruneObject(obj, prunePaths, needlePaths)) { return undefined; }
            return safe.JSON_stringify(obj);
        };
    }

    function replaceTransform(pattern, replacement) {
        const re = /^\/.+\/[a-z]*$/.test(pattern) ? patternToRegex(pattern, 'gms') : new safe.RegExp(escapeRegex(pattern), 'g');
        return function(text) {
            if (typeof text !== 'string') { return undefined; }
            re.lastIndex = 0;
            if (!re.test(text)) { return undefined; }
            re.lastIndex = 0;
            return text.replace(re, replacement);
        };
    }

    // --------------------------------------------------------------- scriptlets

    const S = {};

    S['abort-on-property-read'] = function(chain) {
        if (!chain) { return; }
        const magic = randomToken();
        registerMagic(magic);
        const abort = function() { throw new ReferenceError(magic); };
        trapChain(W, chain, function(owner, prop) {
            try { safe.Object_defineProperty(owner, prop, { get: abort, set: function() {} }); } catch (e) { /* ignore */ }
        });
    };

    S['abort-on-property-write'] = function(chain) {
        if (!chain) { return; }
        const magic = randomToken();
        registerMagic(magic);
        trapChain(W, chain, function(owner, prop) {
            try { delete owner[prop]; } catch (e) { /* ignore */ }
            try {
                safe.Object_defineProperty(owner, prop, { set: function() { throw new ReferenceError(magic); } });
            } catch (e) { /* ignore */ }
        });
    };

    S['abort-current-script'] = function(target, needle, context) {
        if (!target) { return; }
        const reNeedle = patternToRegex(needle || '');
        const reContext = patternToRegex(context || '');
        const magic = randomToken();
        registerMagic(magic);
        const thisScript = document.currentScript;
        const validate = function() {
            const e = document.currentScript;
            if (!(e instanceof HTMLScriptElement)) { return; }
            if (e === thisScript) { return; }
            if (context && !reContext.test(e.src)) { return; }
            if (needle && !reNeedle.test(getScriptText(e))) { return; }
            throw new ReferenceError(magic);
        };
        trapChain(W, target, function(owner, prop) {
            let value;
            try { value = owner[prop]; } catch (e) { /* ignore */ }
            let desc = safe.Object_getOwnPropertyDescriptor(owner, prop);
            if (!(desc instanceof Object) || desc.get === undefined) { desc = undefined; }
            try {
                safe.Object_defineProperty(owner, prop, {
                    get: function() {
                        validate();
                        return desc !== undefined ? desc.get.call(owner) : value;
                    },
                    set: function(a) {
                        validate();
                        if (desc !== undefined) { if (desc.set) { desc.set.call(owner, a); } } else { value = a; }
                    },
                });
            } catch (e) { /* ignore */ }
        });
    };

    S['abort-on-stack-trace'] = function(chain, needle) {
        if (!chain || typeof needle !== 'string') { return; }
        const reNeedle = patternToRegex(needle);
        const magic = randomToken();
        registerMagic(magic);
        trapChain(W, chain, function(owner, prop) {
            let value;
            try { value = owner[prop]; } catch (e) { /* ignore */ }
            try {
                safe.Object_defineProperty(owner, prop, {
                    get: function() {
                        if (matchesStackTrace(reNeedle)) { throw new ReferenceError(magic); }
                        return value;
                    },
                    set: function(a) {
                        if (matchesStackTrace(reNeedle)) { throw new ReferenceError(magic); }
                        value = a;
                    },
                });
            } catch (e) { /* ignore */ }
        });
    };

    function setConstantCore(trusted, chain, rawValue) {
        if (!chain || rawValue === undefined) { return; }
        const rest = Array.prototype.slice.call(arguments, 3);
        const extra = {};
        for (const r of rest) {
            if (r === 'asFunction') { extra.as = 'function'; }
            else if (r === 'asCallback') { extra.as = 'callback'; }
            else if (r === 'asResolved') { extra.as = 'resolved'; }
            else if (r === 'asRejected') { extra.as = 'rejected'; }
        }
        if (rawValue === '$remove$') {
            trapChain(W, chain, function(owner, prop) { try { delete owner[prop]; } catch (e) { /* ignore */ } });
            return;
        }
        const res = validateConstant(rawValue, trusted, extra);
        if (!res[0]) { return; }
        const cValue = res[1];
        const thisScript = document.currentScript;
        let normalValue = cValue;
        let aborted = false;
        const mustAbort = function(v) {
            if (trusted) { return false; }
            if (aborted) { return true; }
            aborted = v !== undefined && v !== null && normalValue !== undefined && normalValue !== null &&
                typeof v !== typeof normalValue;
            return aborted;
        };
        const trapProp = function(owner, prop, configurable, handler) {
            let initial;
            try { initial = owner[prop]; } catch (e) { /* ignore */ }
            if (handler.init(configurable ? initial : normalValue) === false) { return; }
            const odesc = safe.Object_getOwnPropertyDescriptor(owner, prop);
            let prevGetter;
            let prevSetter;
            if (odesc instanceof Object) {
                if (odesc.configurable === false) {
                    try { owner[prop] = normalValue; } catch (e) { /* ignore */ }
                    return;
                }
                if (odesc.get instanceof Function) { prevGetter = odesc.get; }
                if (odesc.set instanceof Function) { prevSetter = odesc.set; }
            }
            try {
                safe.Object_defineProperty(owner, prop, {
                    configurable: configurable,
                    get: function() {
                        if (prevGetter !== undefined) { prevGetter.call(owner); }
                        return handler.getter();
                    },
                    set: function(a) {
                        if (prevSetter !== undefined) { prevSetter.call(owner, a); }
                        handler.setter(a);
                    },
                });
            } catch (e) { /* ignore */ }
        };
        const trap = function(owner, ch) {
            const pos = ch.indexOf('.');
            if (pos === -1) {
                trapProp(owner, ch, false, {
                    v: undefined,
                    init: function(v) {
                        if (mustAbort(v)) { return false; }
                        this.v = v;
                        return true;
                    },
                    getter: function() {
                        if (document.currentScript === thisScript) { return this.v; }
                        return normalValue;
                    },
                    setter: function(a) {
                        if (mustAbort(a) === false) { return; }
                        normalValue = a;
                    },
                });
                return;
            }
            const prop = ch.slice(0, pos);
            let v;
            try { v = owner[prop]; } catch (e) { return; }
            const next = ch.slice(pos + 1);
            if (v instanceof Object || (typeof v === 'object' && v !== null)) {
                trap(v, next);
                return;
            }
            trapProp(owner, prop, true, {
                v: undefined,
                init: function(val) { this.v = val; return true; },
                getter: function() { return this.v; },
                setter: function(a) {
                    this.v = a;
                    if (a instanceof Object) { trap(a, next); }
                },
            });
        };
        trap(W, chain);
    }

    S['set-constant'] = function() { setConstantCore.apply(null, [false].concat(Array.prototype.slice.call(arguments))); };
    S['trusted-set-constant'] = function() { setConstantCore.apply(null, [true].concat(Array.prototype.slice.call(arguments))); };

    function timerDefuser(name) {
        return function(needleArg, delayArg) {
            if (needleArg === undefined && delayArg === undefined) { return; }
            const needle = initPattern(needleArg || '');
            let delay;
            let delayNot = false;
            if (delayArg !== undefined && delayArg !== '') {
                let d = delayArg;
                if (d.startsWith('!')) { delayNot = true; d = d.slice(1); }
                delay = parseInt(d, 10);
                if (isNaN(delay)) { delay = undefined; }
            }
            W[name] = new safe.Proxy(W[name], {
                apply: function(target, thisArg, args) {
                    const a = args[0] instanceof Function ? String(safe.Function_toString.call(args[0])) : String(args[0]);
                    const b = args[1];
                    let defuse;
                    if (delay !== undefined) { defuse = (b === delay) !== delayNot; }
                    if (defuse !== false && !needle.empty) { defuse = testPattern(needle, a); }
                    else if (defuse === undefined) { defuse = true; }
                    if (defuse) { args[0] = noopFunc; }
                    return safe.Reflect_apply(target, thisArg, args);
                },
            });
        };
    }
    S['no-setTimeout-if'] = timerDefuser('setTimeout');
    S['no-setInterval-if'] = timerDefuser('setInterval');
    S['no-requestAnimationFrame-if'] = function(needleArg) {
        const needle = initPattern(needleArg || '');
        W.requestAnimationFrame = new safe.Proxy(W.requestAnimationFrame, {
            apply: function(target, thisArg, args) {
                const a = args[0] instanceof Function ? String(safe.Function_toString.call(args[0])) : String(args[0]);
                if (testPattern(needle, a)) { args[0] = noopFunc; }
                return safe.Reflect_apply(target, thisArg, args);
            },
        });
    };

    function timerBooster(name, defaultDelay) {
        return function(needleArg, delayArg, boostArg) {
            const reNeedle = patternToRegex(needleArg || '');
            let delay = delayArg !== '*' ? parseInt(delayArg, 10) : -1;
            if (isNaN(delay) || !isFinite(delay)) { delay = defaultDelay; }
            let boost = parseFloat(boostArg);
            boost = !isNaN(boost) && isFinite(boost) ? Math.min(Math.max(boost, 0.001), 50) : 0.05;
            W[name] = new safe.Proxy(W[name], {
                apply: function(target, thisArg, args) {
                    const a = args[0];
                    const b = args[1];
                    const text = a instanceof Function ? String(safe.Function_toString.call(a)) : String(a);
                    if ((delay === -1 || b === delay) && reNeedle.test(text)) { args[1] = b * boost; }
                    return safe.Reflect_apply(target, thisArg, args);
                },
            });
        };
    }
    S['nano-setInterval-booster'] = timerBooster('setInterval', 1000);
    S['nano-setTimeout-booster'] = timerBooster('setTimeout', 1000);
    S['adjust-setInterval'] = timerBooster('setInterval', 1000);
    S['adjust-setTimeout'] = timerBooster('setTimeout', 1000);

    S['no-window-open-if'] = function(pattern, delay, decoy) {
        const details = initPattern(pattern || '');
        const fakeWindow = function(url) {
            const target = {};
            return new safe.Proxy(target, {
                get: function(t, prop) {
                    if (prop === 'closed') { return false; }
                    if (prop === 'location') { return { href: url || 'about:blank', assign: noopFunc, replace: noopFunc }; }
                    if (prop === 'document') { return document.implementation.createHTMLDocument(''); }
                    if (prop === 'window' || prop === 'self' || prop === 'top') { return this; }
                    if (safe.Object_hasOwn(t, prop)) { return t[prop]; }
                    return noopFunc;
                },
                set: function(t, prop, v) { t[prop] = v; return true; },
            });
        };
        W.open = new safe.Proxy(W.open, {
            apply: function(target, thisArg, args) {
                const haystack = Array.prototype.map.call(args, function(a) { return String(a); }).join(' ');
                if (!testPattern(details, haystack)) { return safe.Reflect_apply(target, thisArg, args); }
                if (delay === undefined || delay === '') { return null; }
                return fakeWindow(args[0]);
            },
        });
    };

    S['addEventListener-defuser'] = function(type, pattern) {
        const reType = patternToRegex(type || '', undefined, true);
        const rePattern = patternToRegex(pattern || '');
        const proto = EventTarget.prototype;
        proto.addEventListener = new safe.Proxy(proto.addEventListener, {
            apply: function(target, thisArg, args) {
                let t;
                let h;
                try {
                    t = String(args[0]);
                    const fn = args[1];
                    h = fn instanceof Function ? String(safe.Function_toString.call(fn))
                        : (fn instanceof Object && fn.handleEvent instanceof Function ? String(safe.Function_toString.call(fn.handleEvent)) : String(fn));
                } catch (e) { /* ignore */ }
                if ((type === undefined || type === '' || reType.test(t)) && rePattern.test(h)) { return undefined; }
                return safe.Reflect_apply(target, thisArg, args);
            },
        });
    };

    function replaceNodeTextCore(nodeName, pattern, replacement, rest) {
        const reNodeName = patternToRegex(nodeName || '', 'i', true);
        const rePattern = patternToRegex(pattern || '', 'gms');
        const extra = extraArgs(rest || []);
        const reCondition = patternToRegex(extra.condition || '', 'ms');
        const reIncludes = extra.includes ? patternToRegex(extra.includes, 'ms') : null;
        let quitAfter = parseInt(extra.quitAfter, 10) || 0;
        let sedCount = parseInt(extra.sedCount, 10) || 0;
        let observer;
        const handleNode = function(node) {
            const before = node.textContent;
            if (reIncludes !== null) {
                reIncludes.lastIndex = 0;
                if (!reIncludes.test(before)) { return true; }
            }
            reCondition.lastIndex = 0;
            if (!reCondition.test(before)) { return true; }
            rePattern.lastIndex = 0;
            if (!rePattern.test(before)) { return true; }
            rePattern.lastIndex = 0;
            const after = pattern !== '' ? before.replace(rePattern, replacement) : replacement;
            node.textContent = after;
            if (sedCount !== 0 && --sedCount === 0) { return false; }
            return true;
        };
        const handleMutations = function(mutations) {
            for (const m of mutations) {
                for (const node of m.addedNodes) {
                    if (!reNodeName.test(node.nodeName)) { continue; }
                    if (node.nodeName === 'SCRIPT' && node.src) { continue; }
                    if (!handleNode(node)) { stop(); return; }
                }
            }
        };
        const stop = function() { if (observer) { observer.disconnect(); observer = undefined; } };
        const root = document.documentElement;
        if (root) {
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
            let count = 0;
            for (;;) {
                const node = walker.nextNode();
                if (node === null) { break; }
                if (++count > 5000) { break; }
                if (!reNodeName.test(node.nodeName)) { continue; }
                if (!handleNode(node)) { return; }
            }
        }
        observer = new safe.MutationObserver(handleMutations);
        observer.observe(document, { childList: true, subtree: true });
        if (quitAfter > 0) { safe.setTimeout(stop, quitAfter * 1000); }
    }

    S['remove-node-text'] = function(nodeName, includes) {
        if (!includes) { return; }
        // Matching nodes lose their whole text.
        replaceNodeTextCore(nodeName, '', '', ['includes', includes].concat(Array.prototype.slice.call(arguments, 2)));
    };
    S['replace-node-text'] = function(nodeName, pattern, replacement) {
        replaceNodeTextCore(nodeName, pattern, replacement || '', Array.prototype.slice.call(arguments, 3));
    };

    function getCookie(name) {
        for (const part of document.cookie.split(/\s*;\s*/)) {
            const pos = part.indexOf('=');
            const k = pos === -1 ? part : part.slice(0, pos);
            if (k === name) { return pos === -1 ? '' : part.slice(pos + 1); }
        }
        return undefined;
    }

    function setCookieHelper(name, value, expires, path, domain, dontOverwrite) {
        if (dontOverwrite && getCookie(name) !== undefined) { return false; }
        const parts = [name, '=', value];
        if (expires) { parts.push('; expires=', expires); }
        if (path === '' || path === undefined) { path = '/'; }
        if (path !== 'none') { parts.push('; path=', path); }
        if (domain) { parts.push('; domain=', domain); }
        parts.push('; SameSite=Lax');
        try {
            const before = document.cookie;
            document.cookie = parts.join('');
            return document.cookie !== before;
        } catch (e) { return false; }
    }

    const COOKIE_VALUES = new Set([
        '', 'true', 'false', 'yes', 'y', 'no', 'n', 'ok', 'on', 'off', 'accept', 'accepted', 'notaccepted',
        'reject', 'rejected', 'allow', 'allowed', 'disallow', 'deny', 'denied', 'necessary', 'required',
        'hide', 'hidden', 'essential', 'nonessential', 'checked', 'unchecked', 'forever', 'session',
        'dismiss', 'dismissed', 'enable', 'enabled', 'disable', 'disabled', 'ok', 'done', 'x', 'null',
    ]);

    function setCookieCore(trusted, reload, name, value, path) {
        if (!name) { return; }
        let v = value === undefined ? '' : value;
        if (!trusted) {
            const unq = v.replace(/^(['"])(.*)\1$/, '$2');
            if (!COOKIE_VALUES.has(unq.toLowerCase()) && !/^-?\d+$/.test(unq)) { return; }
            v = encodeURIComponent(unq);
        } else {
            if (v === '$now$') { v = String(Date.now()); }
            else if (v === '$currentDate$') { v = new Date().toString(); }
        }
        const changed = setCookieHelper(encodeURIComponent(name), v, trusted ? 'Fri, 31 Dec 9999 23:59:59 GMT' : '', path, '', !reload);
        if (reload && changed) { W.location.reload(); }
    }

    S['set-cookie'] = function(name, value, path) { setCookieCore(false, false, name, value, path); };
    S['set-cookie-reload'] = function(name, value, path) { setCookieCore(false, true, name, value, path); };
    S['trusted-set-cookie'] = function(name, value, offset, path) { setCookieCore(true, false, name, value, path); };
    S['trusted-set-cookie-reload'] = function(name, value, offset, path) { setCookieCore(true, true, name, value, path); };

    S['remove-cookie'] = function(needle) {
        const re = patternToRegex(needle || '');
        const remove = function() {
            for (const part of document.cookie.split(';')) {
                const pos = part.indexOf('=');
                if (pos === -1) { continue; }
                const name = part.slice(0, pos).trim();
                if (!re.test(name)) { continue; }
                const expire = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
                document.cookie = expire;
                document.cookie = expire + '; path=/';
                const host = location.hostname;
                document.cookie = expire + '; path=/; domain=' + host;
                document.cookie = expire + '; path=/; domain=.' + host.split('.').slice(-2).join('.');
            }
        };
        remove();
        safe.addEventListener.call(W, 'beforeunload', remove);
        runAt(remove, 'complete');
    };

    function setStorageItemCore(which, trusted, key, value) {
        if (!key) { return; }
        let storage;
        try { storage = W[which]; } catch (e) { return; }
        if (!storage) { return; }
        if (value === '$remove$') {
            const re = patternToRegex(key);
            try {
                for (let i = storage.length - 1; i >= 0; i--) {
                    const k = storage.key(i);
                    if (re.test(k)) { storage.removeItem(k); }
                }
            } catch (e) { /* ignore */ }
            return;
        }
        let v;
        const allowed = {
            'undefined': 'undefined', 'false': 'false', 'true': 'true', 'null': 'null', "''": '', 'emptyStr': '',
            'emptyArr': '[]', '[]': '[]', 'emptyObj': '{}', '{}': '{}', 'yes': 'yes', 'no': 'no', 'on': 'on',
            'off': 'off', 'accept': 'accept', 'accepted': 'accepted', 'reject': 'reject', 'rejected': 'rejected',
        };
        if (safe.Object_hasOwn(allowed, value)) { v = allowed[value]; }
        else if (/^-?\d+$/.test(value)) { v = value; }
        else if (trusted) {
            v = value === '$now$' ? String(Date.now()) : value === '$currentDate$' ? new Date().toString() : value;
        } else { return; }
        try {
            if (storage.getItem(key) === v) { return; }
            storage.setItem(key, v);
        } catch (e) { /* ignore */ }
    }
    S['set-local-storage-item'] = function(k, v) { setStorageItemCore('localStorage', false, k, v); };
    S['set-session-storage-item'] = function(k, v) { setStorageItemCore('sessionStorage', false, k, v); };
    S['trusted-set-local-storage-item'] = function(k, v) { setStorageItemCore('localStorage', true, k, v); };
    S['trusted-set-session-storage-item'] = function(k, v) { setStorageItemCore('sessionStorage', true, k, v); };

    function noFetchCore(trusted, propsToMatch, responseBody, responseType) {
        const needles = parsePropsToMatch(propsToMatch || '', 'url');
        if (needles.length === 0 && propsToMatch !== '*') { return; }
        W.fetch = new safe.Proxy(W.fetch, {
            apply: function(target, thisArg, args) {
                const details = fetchDetails(args);
                if (propsToMatch !== '*' && !matchProps(needles, details)) {
                    return safe.Reflect_apply(target, thisArg, args);
                }
                let body = '';
                if (responseBody === 'emptyObj' || responseBody === '{}') { body = '{}'; }
                else if (responseBody === 'emptyArr' || responseBody === '[]') { body = '[]'; }
                else if (responseBody === 'true' || responseBody === 'false') { body = responseBody; }
                else if (responseBody === 'random') { body = randomToken(); }
                else if (/^length:\d+(-\d+)?$/.test(responseBody || '')) {
                    const m = /^length:(\d+)(?:-(\d+))?$/.exec(responseBody);
                    const min = parseInt(m[1], 10);
                    const max = m[2] ? parseInt(m[2], 10) : min;
                    const len = Math.min(min + safe.Math_floor(safe.Math_random() * (max - min + 1)), 500000);
                    body = new Array(len + 1).join('x');
                } else if (trusted && responseBody) { body = responseBody; }
                const response = new safe.Response(body, { status: 200, statusText: 'OK', headers: { 'Content-Length': String(body.length) } });
                try {
                    safe.Object_defineProperties(response, {
                        url: { value: details.url },
                        type: { value: responseType === 'opaque' ? 'opaque' : (responseType || 'basic') },
                    });
                } catch (e) { /* ignore */ }
                return safe.Promise.resolve(response);
            },
        });
    }
    S['no-fetch-if'] = function(p, b, t) { noFetchCore(false, p, b, t); };
    S['trusted-prevent-fetch'] = function(p, b, t) { noFetchCore(true, p, b, t); };

    function noXhrCore(trusted, propsToMatch, directive) {
        const needles = parsePropsToMatch(propsToMatch || '', 'url');
        if (needles.length === 0 && propsToMatch !== '*') { return; }
        const instances = new WeakMap();
        const Base = W.XMLHttpRequest;
        W.XMLHttpRequest = class extends Base {
            open(method, url) {
                const details = { method: String(method), url: String(url) };
                if (propsToMatch === '*' || matchProps(needles, details)) { instances.set(this, details); }
                return super.open.apply(this, arguments);
            }
            send() {
                const details = instances.get(this);
                if (details === undefined) { return super.send.apply(this, arguments); }
                let body = '';
                if (directive === 'true') { body = randomToken(); }
                else if (directive === 'emptyObj' || directive === '{}') { body = '{}'; }
                else if (directive === 'emptyArr' || directive === '[]') { body = '[]'; }
                else if (trusted && directive) { body = directive; }
                const xhr = this;
                let response = body;
                if (this.responseType === 'json') { try { response = safe.JSON_parse(body); } catch (e) { response = null; } }
                else if (this.responseType === 'document') { response = null; }
                try {
                    safe.Object_defineProperties(xhr, {
                        readyState: { value: 4, configurable: true },
                        response: { value: response, configurable: true },
                        responseText: { value: body, configurable: true },
                        responseURL: { value: details.url, configurable: true },
                        responseXML: { value: null, configurable: true },
                        status: { value: 200, configurable: true },
                        statusText: { value: 'OK', configurable: true },
                    });
                } catch (e) { /* ignore */ }
                safe.setTimeout(function() {
                    xhr.dispatchEvent(new Event('readystatechange'));
                    xhr.dispatchEvent(new ProgressEvent('load'));
                    xhr.dispatchEvent(new ProgressEvent('loadend'));
                }, 1);
            }
            getResponseHeader(name) {
                if (instances.has(this)) { return null; }
                return super.getResponseHeader(name);
            }
            getAllResponseHeaders() {
                if (instances.has(this)) { return ''; }
                return super.getAllResponseHeaders();
            }
        };
    }
    S['no-xhr-if'] = function(p, d) { noXhrCore(false, p, d); };
    S['trusted-prevent-xhr'] = function(p, d) { noXhrCore(true, p, d); };

    S['json-prune'] = function(rawPrunePaths, rawNeedlePaths, stackNeedle) {
        const prunePaths = rawPrunePaths ? rawPrunePaths.split(/ +/) : [];
        if (prunePaths.length === 0) { return; }
        const needlePaths = rawNeedlePaths ? rawNeedlePaths.split(/ +/) : [];
        const reStack = stackNeedle ? patternToRegex(stackNeedle) : null;
        JSON.parse = new safe.Proxy(JSON.parse, {
            apply: function(target, thisArg, args) {
                const obj = safe.Reflect_apply(target, thisArg, args);
                if (reStack !== null && !matchesStackTrace(reStack)) { return obj; }
                try { pruneObject(obj, prunePaths, needlePaths); } catch (e) { /* ignore */ }
                return obj;
            },
        });
        Response.prototype.json = new safe.Proxy(Response.prototype.json, {
            apply: function(target, thisArg, args) {
                return safe.Reflect_apply(target, thisArg, args).then(function(obj) {
                    try { pruneObject(obj, prunePaths, needlePaths); } catch (e) { /* ignore */ }
                    return obj;
                });
            },
        });
    };

    S['json-prune-fetch-response'] = function(rawPrunePaths, rawNeedlePaths) {
        const prunePaths = rawPrunePaths ? rawPrunePaths.split(/ +/) : [];
        if (prunePaths.length === 0) { return; }
        const needlePaths = rawNeedlePaths ? rawNeedlePaths.split(/ +/) : [];
        const extra = extraArgs(Array.prototype.slice.call(arguments, 2));
        onFetchText(parsePropsToMatch(extra.propsToMatch || '', 'url'), jsonTransform(prunePaths, needlePaths));
    };

    S['json-prune-xhr-response'] = function(rawPrunePaths, rawNeedlePaths) {
        const prunePaths = rawPrunePaths ? rawPrunePaths.split(/ +/) : [];
        if (prunePaths.length === 0) { return; }
        const needlePaths = rawNeedlePaths ? rawNeedlePaths.split(/ +/) : [];
        const extra = extraArgs(Array.prototype.slice.call(arguments, 2));
        onXhrText(parsePropsToMatch(extra.propsToMatch || '', 'url'), jsonTransform(prunePaths, needlePaths));
    };

    S['trusted-replace-fetch-response'] = function(pattern, replacement, propsToMatch) {
        if (!pattern) { return; }
        const p = pattern.replace(/^'(.*)'$/, '$1');
        onFetchText(parsePropsToMatch(propsToMatch || '', 'url'), replaceTransform(p, replacement || ''));
    };

    S['trusted-replace-xhr-response'] = function(pattern, replacement, propsToMatch) {
        if (!pattern) { return; }
        const p = pattern.replace(/^'(.*)'$/, '$1');
        onXhrText(parsePropsToMatch(propsToMatch || '', 'url'), replaceTransform(p, (replacement || '').replace(/^'(.*)'$/, '$1')));
    };

    S['xml-prune'] = function(selector, selectorCheck, urlPattern) {
        if (!selector) { return; }
        const reUrl = patternToRegex(urlPattern || '');
        const transform = function(text, details) {
            if (typeof text !== 'string' || !/^\s*</.test(text)) { return undefined; }
            if (!reUrl.test(details.url || '')) { return undefined; }
            try {
                const doc = new safe.DOMParser().parseFromString(text, 'text/xml');
                if (doc.querySelector('parsererror')) { return undefined; }
                const select = function(expr) {
                    if (expr.startsWith('xpath(') && expr.endsWith(')')) {
                        const r = doc.evaluate(expr.slice(6, -1), doc, null, XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
                        const out = [];
                        for (let i = 0; i < r.snapshotLength; i++) { out.push(r.snapshotItem(i)); }
                        return out;
                    }
                    return Array.prototype.slice.call(doc.querySelectorAll(expr));
                };
                if (selectorCheck && select(selectorCheck).length === 0) { return undefined; }
                const nodes = select(selector);
                if (nodes.length === 0) { return undefined; }
                for (const n of nodes) {
                    if (n.nodeType === 2) { n.ownerElement.removeAttributeNode(n); } else if (n.parentNode) { n.parentNode.removeChild(n); }
                }
                return new safe.XMLSerializer().serializeToString(doc);
            } catch (e) { return undefined; }
        };
        onFetchText([], transform);
        onXhrText([], transform);
    };

    S['m3u-prune'] = function(m3uPattern, urlPattern) {
        if (!m3uPattern) { return; }
        const reUrl = patternToRegex(urlPattern || '');
        const reM3u = patternToRegex(m3uPattern);
        const multiline = reM3u.multiline || reM3u.source.indexOf('\\n') !== -1;
        const transform = function(text, details) {
            if (typeof text !== 'string' || !text.startsWith('#EXTM3U')) { return undefined; }
            if (!reUrl.test(details.url || '')) { return undefined; }
            if (multiline) {
                const re = new safe.RegExp(reM3u.source, reM3u.flags.indexOf('g') === -1 ? reM3u.flags + 'g' : reM3u.flags);
                return text.replace(re, '');
            }
            const lines = text.split(/\n\r|\n|\r/);
            const out = [];
            for (let i = 0; i < lines.length; i++) {
                const line = lines[i];
                reM3u.lastIndex = 0;
                if (reM3u.test(line)) {
                    if (line.startsWith('#EXTINF') && i + 1 < lines.length && !lines[i + 1].startsWith('#')) { i++; }
                    continue;
                }
                out.push(line);
            }
            return out.join('\n');
        };
        onFetchText([], transform);
        onXhrText([], transform);
    };

    S['noeval-if'] = function(needle) {
        const re = patternToRegex(needle || '');
        W.eval = new safe.Proxy(W.eval, {
            apply: function(target, thisArg, args) {
                const a = String(args[0]);
                if (needle !== '' && re.test(a)) { return undefined; }
                return safe.Reflect_apply(target, thisArg, args);
            },
        });
    };
    S['noeval'] = function() {
        W.eval = new safe.Proxy(W.eval, { apply: function() { return undefined; } });
    };

    function attrScriptlet(apply) {
        return function(rawToken, rawSelector, behavior) {
            if (!rawToken) { return; }
            const tokens = rawToken.split(/\s*\|\s*/).filter(Boolean);
            const selector = rawSelector || '';
            const b = behavior || '';
            const stay = /\bstay\b/.test(b);
            let timer;
            const run = function() {
                timer = undefined;
                try { apply(tokens, selector); } catch (e) { /* ignore */ }
            };
            const start = function() {
                run();
                if (!stay) {
                    // Keep watching briefly for late-inserted elements.
                    const obs = new safe.MutationObserver(function() {
                        if (timer === undefined) { timer = safe.setTimeout(run, 50); }
                    });
                    obs.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: tokens });
                    safe.setTimeout(function() { obs.disconnect(); }, 10000);
                    return;
                }
                const obs = new safe.MutationObserver(function() {
                    if (timer === undefined) { timer = safe.setTimeout(run, 1); }
                });
                obs.observe(document, { childList: true, subtree: true, attributes: true });
            };
            runAt(start, /\basap\b/.test(b) ? 'loading' : /\bcomplete\b/.test(b) ? 'complete' : 'interactive');
        };
    }

    S['remove-attr'] = attrScriptlet(function(tokens, selector) {
        const sel = selector !== '' ? selector : tokens.map(function(t) { return '[' + CSS.escape(t) + ']'; }).join(',');
        for (const node of document.querySelectorAll(sel)) {
            for (const t of tokens) { node.removeAttribute(t); }
        }
    });

    S['remove-class'] = attrScriptlet(function(tokens, selector) {
        const sel = selector !== '' ? selector : tokens.map(function(t) { return '.' + CSS.escape(t); }).join(',');
        for (const node of document.querySelectorAll(sel)) {
            node.classList.remove.apply(node.classList, tokens);
        }
    });

    function setAttrCore(trusted, selector, attr, value) {
        if (!selector || !attr) { return; }
        let v = value === undefined ? '' : value;
        let copyFrom = null;
        if (/^\[.+\]$/.test(v)) { copyFrom = v.slice(1, -1); }
        else if (!trusted && !['', 'true', 'false'].includes(v) && !/^-?\d+$/.test(v)) { return; }
        const apply = function() {
            try {
                for (const el of document.querySelectorAll(selector)) {
                    const nv = copyFrom !== null ? el.getAttribute(copyFrom) : v;
                    if (nv === null) { continue; }
                    if (el.getAttribute(attr) !== nv) { el.setAttribute(attr, nv); }
                }
            } catch (e) { /* ignore */ }
        };
        runAt(function() {
            apply();
            let timer;
            new safe.MutationObserver(function() {
                if (timer === undefined) { timer = safe.setTimeout(function() { timer = undefined; apply(); }, 20); }
            }).observe(document, { childList: true, subtree: true });
        }, 'interactive');
    }
    S['set-attr'] = function(s, a, v) { setAttrCore(false, s, a, v); };
    S['trusted-set-attr'] = function(s, a, v) { setAttrCore(true, s, a, v); };

    S['href-sanitizer'] = function(selector, source) {
        if (!selector) { return; }
        const src = source || 'text';
        const extract = function(el) {
            let text;
            if (src === 'text') { text = el.textContent; }
            else if (/^\[.+\]$/.test(src)) { text = el.getAttribute(src.slice(1, -1)); }
            else if (src.startsWith('?')) {
                try { text = new URL(el.href).searchParams.get(src.slice(1)); } catch (e) { text = null; }
            }
            if (!text) { return null; }
            text = text.trim();
            try {
                const u = new URL(text, document.baseURI);
                if (u.protocol !== 'https:' && u.protocol !== 'http:') { return null; }
                return u.href;
            } catch (e) { return null; }
        };
        const run = function() {
            try {
                for (const el of document.querySelectorAll(selector)) {
                    const href = extract(el);
                    if (href && el.getAttribute('href') !== href) {
                        el.setAttribute('href', href);
                        el.removeAttribute('ping');
                    }
                }
            } catch (e) { /* ignore */ }
        };
        runAt(function() {
            run();
            let timer;
            new safe.MutationObserver(function() {
                if (timer === undefined) { timer = safe.setTimeout(function() { timer = undefined; run(); }, 50); }
            }).observe(document, { childList: true, subtree: true });
        }, 'interactive');
    };

    S['nowebrtc'] = function() {
        const names = ['RTCPeerConnection', 'webkitRTCPeerConnection'];
        for (const name of names) {
            if (typeof W[name] !== 'function') { continue; }
            const pc = function() {};
            pc.prototype = {
                close: noopFunc, createDataChannel: noopFunc, createOffer: noopFunc, setRemoteDescription: noopFunc,
                addEventListener: noopFunc, removeEventListener: noopFunc, toString: function() { return '[object RTCPeerConnection]'; },
            };
            try { W[name] = pc; } catch (e) { /* ignore */ }
        }
    };

    S['refresh-defuser'] = function(arg) {
        const defuse = function() {
            const meta = document.querySelector('meta[http-equiv="refresh" i][content]');
            if (meta === null) { return; }
            const s = arg === undefined || arg === '' ? meta.getAttribute('content') : arg;
            const ms = Math.max(parseFloat(s) || 0, 0) * 500;
            safe.setTimeout(function() { W.stop(); }, ms);
        };
        runAt(defuse, 'interactive');
    };

    S['disable-newtab-links'] = function() {
        safe.addEventListener.call(document, 'click', function(ev) {
            let t = ev.target;
            while (t !== null && t !== undefined) {
                if (t.localName === 'a' && t.hasAttribute('target')) {
                    ev.stopPropagation();
                    ev.preventDefault();
                    break;
                }
                t = t.parentNode;
            }
        }, { capture: true });
    };

    S['window.name-defuser'] = function() {
        if (W === W.top) { W.name = ''; }
    };

    S['close-window'] = function(pattern) {
        const re = patternToRegex(pattern || '');
        if (re.test(location.href)) { try { W.close(); } catch (e) { /* ignore */ } }
    };

    S['prevent-canvas'] = function(contextType) {
        const details = initPattern(contextType || '');
        const proto = HTMLCanvasElement.prototype;
        proto.getContext = new safe.Proxy(proto.getContext, {
            apply: function(target, thisArg, args) {
                if (testPattern(details, String(args[0]))) { return null; }
                return safe.Reflect_apply(target, thisArg, args);
            },
        });
    };

    S['call-nothrow'] = function(chain) {
        if (!chain) { return; }
        proxyApplyFn(chain, function(ctx) {
            try { return ctx.reflect(); } catch (e) { return undefined; }
        });
    };

    S['alert-buster'] = function() {
        W.alert = new safe.Proxy(W.alert, { apply: function() { return undefined; } });
    };

    S['trusted-replace-argument'] = function(propChain, argposRaw, argraw) {
        if (!propChain) { return; }
        const extra = extraArgs(Array.prototype.slice.call(arguments, 3));
        const argpos = parseInt(argposRaw, 10) || 0;
        const reCondition = patternToRegex(extra.condition || '');
        let replacer;
        if (typeof argraw === 'string' && argraw.startsWith('repl:/')) {
            const m = /^repl:\/(.+?)\/(.*?)\/([gimsu]*)$/.exec(argraw);
            if (m === null) { return; }
            let re;
            try { re = new safe.RegExp(m[1], m[3]); } catch (e) { return; }
            replacer = function(v) { return typeof v === 'string' ? v.replace(re, m[2]) : v; };
        } else if (typeof argraw === 'string' && argraw.startsWith('json:')) {
            let value;
            try { value = safe.JSON_parse(argraw.slice(5)); } catch (e) { return; }
            replacer = function() { return value; };
        } else {
            const res = validateConstant(argraw === undefined ? '' : argraw, true);
            if (!res[0]) { return; }
            replacer = function() { return res[1]; };
        }
        proxyApplyFn(propChain, function(ctx) {
            const args = ctx.callArgs;
            const pos = argpos >= 0 ? argpos : args.length + argpos;
            if (pos < 0 || pos >= args.length) { return ctx.reflect(); }
            let s;
            try { s = typeof args[pos] === 'function' ? String(safe.Function_toString.call(args[pos])) : String(args[pos]); } catch (e) { s = ''; }
            if (!reCondition.test(s)) { return ctx.reflect(); }
            args[pos] = replacer(args[pos]);
            return ctx.reflect();
        });
    };

    S['trusted-replace-outbound-text'] = function(propChain, rawPattern, rawReplacement) {
        if (!propChain) { return; }
        const extra = extraArgs(Array.prototype.slice.call(arguments, 3));
        const reCondition = patternToRegex(extra.condition || '');
        const pattern = patternToRegex(rawPattern || '', 'gms');
        const replacement = rawReplacement === undefined ? '' : rawReplacement;
        proxyApplyFn(propChain, function(ctx) {
            const encodedTextBefore = ctx.reflect();
            let textBefore = encodedTextBefore;
            if (extra.encoding === 'base64') { try { textBefore = atob(encodedTextBefore); } catch (e) { return encodedTextBefore; } }
            if (typeof textBefore !== 'string') { return encodedTextBefore; }
            if (!reCondition.test(textBefore)) { return encodedTextBefore; }
            pattern.lastIndex = 0;
            const textAfter = rawPattern ? textBefore.replace(pattern, replacement) : replacement;
            if (extra.encoding === 'base64') { try { return btoa(textAfter); } catch (e) { return encodedTextBefore; } }
            return textAfter;
        });
    };

    S['trusted-suppress-native-method'] = function(methodPath, signature, how) {
        if (!methodPath) { return; }
        const signatureArgs = (signature || '').split(/\s*\|\s*/).map(function(v) {
            if (/^".*"$/.test(v)) { return { type: 'pattern', re: patternToRegex(v.slice(1, -1)) }; }
            if (v === 'false' || v === 'true' || v === 'null' || v === 'undefined') { return { type: 'exact', value: validateConstant(v, true)[1] }; }
            if (/^-?\d+$/.test(v)) { return { type: 'exact', value: parseInt(v, 10) }; }
            return { type: 'pattern', re: patternToRegex(v) };
        });
        proxyApplyFn(methodPath, function(ctx) {
            const args = ctx.callArgs;
            if (signature) {
                for (let i = 0; i < signatureArgs.length; i++) {
                    const s = signatureArgs[i];
                    const a = args[i];
                    if (s.type === 'exact') { if (a !== s.value) { return ctx.reflect(); } }
                    else {
                        let text;
                        try { text = a instanceof Object ? safe.JSON_stringify(a) : String(a); } catch (e) { text = String(a); }
                        if (!s.re.test(text)) { return ctx.reflect(); }
                    }
                }
            }
            if (how === 'abort') { throw new ReferenceError(randomToken()); }
            return undefined;
        });
    };

    S['trusted-click-element'] = function(selectors, extraMatch, delayArg) {
        if (!selectors) { return; }
        const list = selectors.split(/\s*,\s*/).filter(Boolean);
        if (extraMatch) {
            for (const cond of extraMatch.split(/\s*,\s*/)) {
                const m = /^(!?)(cookie|localStorage):(.+)$/.exec(cond);
                if (m === null) { continue; }
                let present;
                if (m[2] === 'cookie') { present = getCookie(m[3]) !== undefined; }
                else { try { present = W.localStorage.getItem(m[3]) !== null; } catch (e) { present = false; } }
                if (present === (m[1] === '!')) { return; }
            }
        }
        const delay = Math.max(parseInt(delayArg, 10) || 0, 0);
        const deadline = Date.now() + 10000;
        let index = 0;
        const query = function(sel) {
            if (sel.indexOf('>>>') === -1) { return document.querySelector(sel); }
            let root = document;
            let el = null;
            for (const part of sel.split(/\s*>>>\s*/)) {
                el = root.querySelector(part);
                if (el === null) { return null; }
                root = el.shadowRoot || el;
            }
            return el;
        };
        const tick = function() {
            while (index < list.length) {
                const el = query(list[index]);
                if (el === null) { break; }
                try { el.click(); } catch (e) { /* ignore */ }
                index++;
            }
            if (index < list.length && Date.now() < deadline) { safe.setTimeout(tick, 200); }
        };
        runAt(function() { safe.setTimeout(tick, delay); }, 'interactive');
    };

    S['trusted-create-html'] = function(parentSelector, htmlStr, durationStr) {
        if (!parentSelector || !htmlStr) { return; }
        const duration = parseInt(durationStr, 10);
        runAt(function() {
            const parent = document.querySelector(parentSelector);
            if (parent === null) { return; }
            const tpl = document.createElement('template');
            tpl.innerHTML = htmlStr;
            const nodes = Array.prototype.slice.call(tpl.content.childNodes);
            parent.append.apply(parent, nodes);
            if (!isNaN(duration)) {
                safe.setTimeout(function() { for (const n of nodes) { n.remove(); } }, duration);
            }
        }, 'interactive');
    };

    S['spoof-css'] = function(selector) {
        if (!selector) { return; }
        const pairs = Array.prototype.slice.call(arguments, 1);
        const props = {};
        for (let i = 0; i + 1 < pairs.length; i += 2) { props[pairs[i]] = pairs[i + 1]; }
        const camel = function(s) { return s.replace(/-[a-z]/g, function(m) { return m[1].toUpperCase(); }); };
        const spoofed = {};
        for (const k of safe.Object_keys(props)) { spoofed[k] = props[k]; spoofed[camel(k)] = props[k]; }
        const matches = function(el) { try { return el instanceof Element && el.matches(selector); } catch (e) { return false; } };
        W.getComputedStyle = new safe.Proxy(W.getComputedStyle, {
            apply: function(target, thisArg, args) {
                const style = safe.Reflect_apply(target, thisArg, args);
                if (!matches(args[0])) { return style; }
                return new safe.Proxy(style, {
                    get: function(t, prop) {
                        if (typeof prop === 'string' && safe.Object_hasOwn(spoofed, prop)) { return spoofed[prop]; }
                        if (prop === 'getPropertyValue') {
                            return function(p) { return safe.Object_hasOwn(spoofed, p) ? spoofed[p] : t.getPropertyValue(p); };
                        }
                        const v = Reflect.get(t, prop, t);
                        return typeof v === 'function' ? v.bind(t) : v;
                    },
                });
            },
        });
        const rectProps = ['height', 'width', 'top', 'left', 'right', 'bottom', 'x', 'y'];
        if (rectProps.some(function(p) { return safe.Object_hasOwn(props, p); })) {
            Element.prototype.getBoundingClientRect = new safe.Proxy(Element.prototype.getBoundingClientRect, {
                apply: function(target, thisArg, args) {
                    const rect = safe.Reflect_apply(target, thisArg, args);
                    if (!matches(thisArg)) { return rect; }
                    const out = {};
                    for (const p of rectProps.concat(['toJSON'])) { out[p] = rect[p]; }
                    for (const p of rectProps) { if (safe.Object_hasOwn(props, p)) { out[p] = parseFloat(props[p]); } }
                    return out;
                },
            });
        }
    };

    S['prevent-innerHTML'] = function(selector, pattern) {
        const re = patternToRegex(pattern || '');
        const desc = safe.Object_getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
        if (!desc || !desc.set) { return; }
        safe.Object_defineProperty(Element.prototype, 'innerHTML', {
            configurable: true,
            get: desc.get,
            set: function(v) {
                try {
                    if ((!selector || this.matches(selector)) && re.test(String(v))) { return; }
                } catch (e) { /* ignore */ }
                return desc.set.call(this, v);
            },
        });
    };

    S['no-floc'] = function() {
        if (document.interestCohort instanceof Function) { document.interestCohort = undefined; }
        if (document.browsingTopics instanceof Function) { document.browsingTopics = undefined; }
    };

    // Resource-backed scriptlets (surrogates also usable as scriptlets).
    for (const name of safe.Object_keys(RESOURCES)) {
        S[name] = RESOURCES[name];
    }

    const ALIASES = {
        'aopr': 'abort-on-property-read', 'aopw': 'abort-on-property-write',
        'acs': 'abort-current-script', 'acis': 'abort-current-script', 'abort-current-inline-script': 'abort-current-script',
        'aost': 'abort-on-stack-trace', 'set': 'set-constant', 'trusted-set': 'trusted-set-constant',
        'nostif': 'no-setTimeout-if', 'setTimeout-defuser': 'no-setTimeout-if', 'prevent-setTimeout': 'no-setTimeout-if',
        'nosiif': 'no-setInterval-if', 'setInterval-defuser': 'no-setInterval-if', 'prevent-setInterval': 'no-setInterval-if',
        'norafif': 'no-requestAnimationFrame-if', 'prevent-requestAnimationFrame': 'no-requestAnimationFrame-if',
        'nano-sib': 'nano-setInterval-booster', 'nano-stb': 'nano-setTimeout-booster',
        'nowoif': 'no-window-open-if', 'window.open-defuser': 'no-window-open-if', 'prevent-window-open': 'no-window-open-if',
        'aeld': 'addEventListener-defuser', 'prevent-addEventListener': 'addEventListener-defuser',
        'rmnt': 'remove-node-text', 'rpnt': 'replace-node-text', 'trusted-rpnt': 'replace-node-text',
        'trusted-replace-node-text': 'replace-node-text',
        'prevent-fetch': 'no-fetch-if', 'prevent-xhr': 'no-xhr-if',
        'ra': 'remove-attr', 'rc': 'remove-class',
        'cookie-remover': 'remove-cookie',
        'prevent-eval-if': 'noeval-if', 'noeval-silent': 'noeval',
        'prevent-refresh': 'refresh-defuser',
        'nobab': 'nobab', 'prevent-bab': 'nobab', 'bab-defuser': 'nobab',
        'nofab': 'nofab', 'fuckadblock.js-3.2.0': 'nofab', 'prevent-fab-3.2.0': 'nofab',
        'popads-dummy': 'popads-dummy', 'popads.net': 'popads-dummy', 'prevent-popads-net': 'popads-dummy',
        'set-popads-dummy': 'popads-dummy',
        'google-ima3': 'google-ima', 'googlesyndication-adsbygoogle': 'googlesyndication_adsbygoogle',
        'googletagservices-gpt': 'googletagservices_gpt',
        'prevent-canvas': 'prevent-canvas', 'no-topics': 'no-floc',
        'window-close-if': 'close-window',
    };

    return {
        has: function(name) { return safe.Object_hasOwn(S, ALIASES[name] || name); },
        run: function(name, args) {
            const fn = S[ALIASES[name] || name];
            if (typeof fn !== 'function') { return false; }
            try { fn.apply(null, args); } catch (e) { /* a broken scriptlet must not break others */ }
            return true;
        },
    };
}
