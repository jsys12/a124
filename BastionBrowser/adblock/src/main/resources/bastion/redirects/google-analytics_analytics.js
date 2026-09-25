(function() {
    'use strict';
    var noop = function() {};
    var Tracker = function() {};
    Tracker.prototype.get = noop;
    Tracker.prototype.set = noop;
    Tracker.prototype.send = noop;
    var w = window;
    var name = w.GoogleAnalyticsObject || 'ga';
    var queue = w[name];
    var ga = function() {
        var args = Array.prototype.slice.call(arguments);
        if (args.length === 0) { return; }
        var last = args[args.length - 1];
        var cb = null;
        if (last instanceof Object && typeof last.hitCallback === 'function') {
            cb = last.hitCallback;
        } else if (typeof last === 'function') {
            cb = function() { last(ga.create()); };
        } else {
            var pos = args.indexOf('hitCallback');
            if (pos !== -1 && typeof args[pos + 1] === 'function') { cb = args[pos + 1]; }
        }
        if (cb) { try { cb(); } catch (e) {} }
    };
    ga.create = function() { return new Tracker(); };
    ga.getByName = function() { return new Tracker(); };
    ga.getAll = function() { return [new Tracker()]; };
    ga.remove = noop;
    ga.loaded = true;
    w[name] = ga;
    var dl = w.dataLayer;
    if (dl instanceof Object) {
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
    }
    if (typeof queue === 'function' && Array.isArray(queue.q)) {
        var q = queue.q.slice();
        queue.q.length = 0;
        q.forEach(function(entry) { ga.apply(null, entry); });
    }
})();
