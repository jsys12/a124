(function() {
    'use strict';
    var visitorId = String(Math.floor(Math.random() * 2e9));
    var agent = { get: function() { return Promise.resolve({ components: {}, visitorId: visitorId, confidence: { score: 0.1 }, version: '3.4.0' }); } };
    window.FingerprintJS = {
        hashComponents: function() { return visitorId; },
        load: function() { return Promise.resolve(agent); }
    };
})();
