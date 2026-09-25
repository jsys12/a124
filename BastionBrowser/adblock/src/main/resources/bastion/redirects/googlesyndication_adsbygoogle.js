(function() {
    'use strict';
    window.adsbygoogle = { loaded: true, push: function() {} };
    var holders = document.querySelectorAll('.adsbygoogle');
    var css = 'height:1px!important;max-height:1px!important;max-width:1px!important;width:1px!important;';
    for (var i = 0; i < holders.length; i++) {
        var id = 'aswift_' + i;
        if (document.querySelector('iframe#' + id) !== null) { continue; }
        var fr = document.createElement('iframe');
        fr.id = id;
        fr.style = css;
        var inner = document.createElement('iframe');
        inner.id = 'google_ads_frame' + i;
        fr.appendChild(inner);
        holders[i].appendChild(fr);
    }
})();
