(function() {
    'use strict';
    var noop = function() {};
    var Gaq = function() {};
    Gaq.prototype.Na = noop; Gaq.prototype.O = noop; Gaq.prototype.Sa = noop; Gaq.prototype.Ta = noop;
    Gaq.prototype.Va = noop; Gaq.prototype._createAsyncTracker = noop; Gaq.prototype._getAsyncTracker = noop;
    Gaq.prototype._getPlugin = noop;
    Gaq.prototype.push = function(a) {
        if (typeof a === 'function') { try { a(); } catch (e) {} return; }
        if (Array.isArray(a) && a[0] === '_link' && typeof a[1] === 'string') { window.location.assign(a[1]); }
        if (Array.isArray(a) && a[0] === '_set' && a[1] === 'hitCallback' && typeof a[2] === 'function') { try { a[2](); } catch (e) {} }
    };
    var tracker = (function() {
        var out = {};
        ['_addIgnoredOrganic','_addIgnoredRef','_addItem','_addOrganic','_addTrans','_clearIgnoredOrganic',
         '_clearIgnoredRef','_clearOrganic','_cookiePathCopy','_deleteCustomVar','_getName','_setAccount',
         '_getAccount','_getClientInfo','_getDetectFlash','_getDetectTitle','_getLinkerUrl','_getLocalGifPath',
         '_getServiceMode','_getVersion','_getVisitorCustomVar','_initData','_link','_linkByPost',
         '_setAllowAnchor','_setAllowHash','_setAllowLinker','_setCampContentKey','_setCampMediumKey',
         '_setCampNameKey','_setCampNOKey','_setCampSourceKey','_setCampTermKey','_setCampaignCookieTimeout',
         '_setCampaignTrack','_setClientInfo','_setCookiePath','_setCookiePersistence','_setCookieTimeout',
         '_setCustomVar','_setDetectFlash','_setDetectTitle','_setDomainName','_setLocalGifPath',
         '_setLocalRemoteServerMode','_setLocalServerMode','_setReferrerOverride','_setRemoteServerMode',
         '_setSampleRate','_setSessionTimeout','_setSiteSpeedSampleRate','_setSessionCookieTimeout','_setVar',
         '_setVisitorCookieTimeout','_trackEvent','_trackPageLoadTime','_trackPageview','_trackSocial',
         '_trackTiming','_trackTrans','_visitCode'].forEach(function(k) { out[k] = noop; });
        out._getLinkerUrl = function(a) { return a; };
        out._link = function(a) { if (typeof a === 'string') { try { window.location.assign(a); } catch (e) {} } };
        return out;
    })();
    var gat = { _anonymizeIP: noop, _createTracker: function() { return tracker; }, _forceSSL: noop,
        _getPlugin: noop, _getTracker: function() { return tracker; }, _getTrackerByName: function() { return tracker; },
        _getTrackers: noop, aa: noop, ab: noop, hb: noop, la: noop, oa: noop, pa: noop, u: noop };
    var gaq = new Gaq();
    var old = window._gaq || [];
    if (Array.isArray(old)) { while (old[0]) { gaq.push(old.shift()); } }
    window._gat = gat;
    window._gaq = gaq;
})();
