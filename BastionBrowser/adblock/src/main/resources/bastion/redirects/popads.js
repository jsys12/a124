(function() {
    'use strict';
    var magic = String.fromCharCode(Date.now() % 26 + 97) + Math.floor(Math.random() * 982451653 + 982451653).toString(36);
    var oe = window.onerror;
    window.onerror = function(msg) {
        if (typeof msg === 'string' && msg.indexOf(magic) !== -1) { return true; }
        if (oe instanceof Function) { return oe.apply(this, arguments); }
    };
    var throwMagic = function() { throw new ReferenceError(magic); };
    ['PopAds', 'popns'].forEach(function(k) {
        try { Object.defineProperty(window, k, { get: throwMagic, set: throwMagic }); } catch (e) {}
    });
})();
