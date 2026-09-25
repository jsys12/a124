(function() {
    'use strict';
    var noop = function() {};
    var ym = function() {
        var args = Array.prototype.slice.call(arguments);
        var last = args[args.length - 1];
        if (last && typeof last.callback === 'function') { setTimeout(last.callback, 1); }
        if (args[1] === 'reachGoal' && typeof args[3] === 'function') { setTimeout(args[3], 1); }
        if (args[1] === 'getClientID' && typeof args[2] === 'function') { setTimeout(function() { args[2](''); }, 1); }
    };
    ym.a = [];
    ym.l = Date.now();
    var Metrika = function() {};
    ['addFileExtension','extLink','file','getClientID','hit','notBounce','params','reachGoal','replacePhones',
     'setUserID','userParams'].forEach(function(k) { Metrika.prototype[k] = noop; });
    window.ym = ym;
    window.Ya = window.Ya || {};
    window.Ya.Metrika = Metrika;
    window.Ya.Metrika2 = Metrika;
})();
