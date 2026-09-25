(function() {
    'use strict';
    var noop = function() {};
    var q = (window.apstag && window.apstag._Q) || [];
    window.apstag = {
        _Q: [],
        fetchBids: function(cfg, cb) { if (typeof cb === 'function') { try { cb([]); } catch (e) {} } },
        init: noop,
        setDisplayBids: noop,
        targetingKeys: noop,
        punt: noop,
        dpa: noop,
        rpa: noop,
        upa: noop
    };
    q.length = 0;
})();
