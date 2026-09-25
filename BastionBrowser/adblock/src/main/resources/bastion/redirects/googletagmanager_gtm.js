(function() {
    'use strict';
    var noop = function() {};
    var w = window;
    w.ga = w.ga || noop;
    var dl = w.dataLayer;
    if (dl instanceof Object === false) { return; }
    if (dl.hide instanceof Object && typeof dl.hide.end === 'function') {
        dl.hide.end();
        dl.hide.end = noop;
    }
    if (typeof dl.push === 'function') {
        var fire = function(item) {
            if (item instanceof Object && typeof item.eventCallback === 'function') {
                setTimeout(item.eventCallback, 1);
                item.eventCallback = noop;
            }
        };
        dl.push = new Proxy(dl.push, {
            apply: function(target, thisArg, a) { fire(a[0]); return Reflect.apply(target, thisArg, a); }
        });
        if (Array.isArray(dl)) { dl.slice().forEach(fire); }
    }
})();
