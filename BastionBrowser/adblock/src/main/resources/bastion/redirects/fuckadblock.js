(function() {
    'use strict';
    var noop = function() {};
    var Fab = function() {};
    Fab.prototype.check = noop;
    Fab.prototype.clearEvent = noop;
    Fab.prototype.emitEvent = noop;
    Fab.prototype.on = function(detected, fn) { if (!detected) { try { fn(); } catch (e) {} } return this; };
    Fab.prototype.onDetected = function() { return this; };
    Fab.prototype.onNotDetected = function(fn) { try { fn(); } catch (e) {} return this; };
    Fab.prototype.setOption = noop;
    Fab.prototype.options = { set: noop, get: noop };
    var fab = new Fab();
    var defs = { FuckAdBlock: Fab, BlockAdBlock: Fab, SniffAdBlock: Fab, fuckAdBlock: fab, blockAdBlock: fab, sniffAdBlock: fab };
    Object.keys(defs).forEach(function(k) {
        try {
            Object.defineProperty(window, k, { get: function() { return defs[k]; }, set: noop, configurable: true });
        } catch (e) { window[k] = defs[k]; }
    });
})();
