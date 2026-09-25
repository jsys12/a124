(function() {
    'use strict';
    var hash = String(Math.floor(Math.random() * 2e9));
    var fp2 = function() {};
    fp2.get = function(opts, cb) {
        if (!cb) { cb = opts; }
        setTimeout(function() { if (cb) { cb(hash, []); } }, 1);
    };
    fp2.getPromise = function() { return Promise.resolve([]); };
    fp2.getV18 = function() { return hash; };
    fp2.x64hash128 = function() { return hash; };
    fp2.prototype = { get: function(opts, cb) { if (!cb) { cb = opts; } setTimeout(function() { if (cb) { cb(hash, []); } }, 1); } };
    window.Fingerprint2 = fp2;
})();
