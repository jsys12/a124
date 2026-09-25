(function() {
    'use strict';
    if (window.google && window.google.ima && window.google.ima.AdsLoader) { return; }
    var noop = function() {};
    function Emitter() { this._l = {}; }
    Emitter.prototype.addEventListener = function(types, fn, capture, scope) {
        var list = Array.isArray(types) ? types : [types];
        for (var i = 0; i < list.length; i++) {
            (this._l[list[i]] = this._l[list[i]] || []).push({ fn: fn, scope: scope });
        }
    };
    Emitter.prototype.removeEventListener = function(types, fn) {
        var list = Array.isArray(types) ? types : [types];
        for (var i = 0; i < list.length; i++) {
            var arr = this._l[list[i]];
            if (!arr) { continue; }
            this._l[list[i]] = arr.filter(function(e) { return e.fn !== fn; });
        }
    };
    Emitter.prototype._emit = function(type, ev) {
        var arr = (this._l[type] || []).slice();
        for (var i = 0; i < arr.length; i++) {
            try { arr[i].fn.call(arr[i].scope || this, ev); } catch (e) {}
        }
    };
    var AdEventType = {
        AD_BREAK_READY: 'adBreakReady', AD_BUFFERING: 'adBuffering', AD_CAN_PLAY: 'adCanPlay',
        AD_METADATA: 'adMetadata', AD_PROGRESS: 'adProgress', ALL_ADS_COMPLETED: 'allAdsCompleted',
        CLICK: 'click', COMPLETE: 'complete', CONTENT_PAUSE_REQUESTED: 'contentPauseRequested',
        CONTENT_RESUME_REQUESTED: 'contentResumeRequested', DURATION_CHANGE: 'durationChange',
        FIRST_QUARTILE: 'firstQuartile', IMPRESSION: 'impression', INTERACTION: 'interaction',
        LINEAR_CHANGED: 'linearChanged', LOADED: 'loaded', LOG: 'log', MIDPOINT: 'midpoint',
        PAUSED: 'pause', RESUMED: 'resume', SKIPPABLE_STATE_CHANGED: 'skippableStateChanged',
        SKIPPED: 'skip', STARTED: 'start', THIRD_QUARTILE: 'thirdQuartile', USER_CLOSE: 'userClose',
        VIDEO_CLICKED: 'videoClicked', VIDEO_ICON_CLICKED: 'videoIconClicked', VOLUME_CHANGED: 'volumeChange',
        VOLUME_MUTED: 'mute'
    };
    function AdEvent(type) { this.type = type; }
    AdEvent.prototype.getAd = function() { return null; };
    AdEvent.prototype.getAdData = function() { return {}; };
    AdEvent.Type = AdEventType;
    function AdError(msg) { this.message = msg; }
    AdError.prototype.getErrorCode = function() { return 1009; };
    AdError.prototype.getInnerError = function() { return null; };
    AdError.prototype.getMessage = function() { return this.message; };
    AdError.prototype.getType = function() { return 'adLoadError'; };
    AdError.prototype.getVastErrorCode = function() { return 303; };
    AdError.prototype.toString = function() { return 'AdError 1009: ' + this.message; };
    AdError.ErrorCode = { VAST_EMPTY_RESPONSE: 1009, UNKNOWN_ERROR: 900 };
    AdError.Type = { AD_LOAD: 'adLoadError', AD_PLAY: 'adPlayError' };
    function AdErrorEvent(err) { this.type = 'adError'; this._e = err; }
    AdErrorEvent.prototype.getError = function() { return this._e; };
    AdErrorEvent.prototype.getUserRequestContext = function() { return {}; };
    AdErrorEvent.Type = { AD_ERROR: 'adError' };
    function AdsManager() { Emitter.call(this); this.volume = 1; }
    AdsManager.prototype = Object.create(Emitter.prototype);
    ['collapse','configureAdsManager','destroy','discardAdBreak','expand','focus','init','pause','resize',
     'resume','setAdWillAutoPlay','setAdWillPlayMuted','setAutoPlayAdBreaks','skip','stop','updateAdsRenderingSettings',
     'clicked','requestNextAdBreak'].forEach(function(k) { AdsManager.prototype[k] = noop; });
    AdsManager.prototype.getAdSkippableState = function() { return false; };
    AdsManager.prototype.getCuePoints = function() { return []; };
    AdsManager.prototype.getCurrentAd = function() { return null; };
    AdsManager.prototype.getRemainingTime = function() { return 0; };
    AdsManager.prototype.getVolume = function() { return this.volume; };
    AdsManager.prototype.setVolume = function(v) { this.volume = v; };
    AdsManager.prototype.isCustomClickTrackingUsed = function() { return false; };
    AdsManager.prototype.isCustomPlaybackUsed = function() { return false; };
    AdsManager.prototype.start = function() {
        var self = this;
        setTimeout(function() {
            self._emit(AdEventType.CONTENT_RESUME_REQUESTED, new AdEvent(AdEventType.CONTENT_RESUME_REQUESTED));
            self._emit(AdEventType.ALL_ADS_COMPLETED, new AdEvent(AdEventType.ALL_ADS_COMPLETED));
        }, 5);
    };
    function AdsManagerLoadedEvent(mgr, ctx) { this.type = 'adsManagerLoaded'; this._m = mgr; this._c = ctx; }
    AdsManagerLoadedEvent.prototype.getAdsManager = function() { return this._m; };
    AdsManagerLoadedEvent.prototype.getUserRequestContext = function() { return this._c || {}; };
    AdsManagerLoadedEvent.Type = { ADS_MANAGER_LOADED: 'adsManagerLoaded' };
    function AdsLoader() { Emitter.call(this); this._settings = new ImaSdkSettings(); }
    AdsLoader.prototype = Object.create(Emitter.prototype);
    AdsLoader.prototype.contentComplete = noop;
    AdsLoader.prototype.destroy = noop;
    AdsLoader.prototype.getSettings = function() { return this._settings; };
    AdsLoader.prototype.getVersion = function() { return '3.600.0'; };
    AdsLoader.prototype.requestAds = function(req, ctx) {
        var self = this;
        setTimeout(function() {
            self._emit('adsManagerLoaded', new AdsManagerLoadedEvent(new AdsManager(), ctx));
        }, 5);
    };
    function AdDisplayContainer() {}
    AdDisplayContainer.prototype.destroy = noop;
    AdDisplayContainer.prototype.initialize = noop;
    function ImaSdkSettings() {}
    ['getCompanionBackfill','getDisableCustomPlaybackForIOS10Plus','getFeatureFlags','getLocale',
     'getNumRedirects','getPlayerType','getPlayerVersion','getPpid','isCookiesEnabled','isVpaidAdapter']
        .forEach(function(k) { ImaSdkSettings.prototype[k] = function() { return ''; }; });
    ['setAutoPlayAdBreaks','setCompanionBackfill','setCookiesEnabled','setDisableCustomPlaybackForIOS10Plus',
     'setFeatureFlags','setLocale','setNumRedirects','setPlayerType','setPlayerVersion','setPpid',
     'setSessionId','setVpaidAllowed','setVpaidMode','setSessionId']
        .forEach(function(k) { ImaSdkSettings.prototype[k] = noop; });
    ImaSdkSettings.CompanionBackfillMode = { ALWAYS: 'always', ON_MASTER_AD: 'on_master_ad' };
    ImaSdkSettings.VpaidMode = { DISABLED: 0, ENABLED: 1, INSECURE: 2 };
    function AdsRequest() {}
    AdsRequest.prototype.setAdWillAutoPlay = noop;
    AdsRequest.prototype.setAdWillPlayMuted = noop;
    AdsRequest.prototype.setContinuousPlayback = noop;
    function AdsRenderingSettings() {}
    function CompanionAdSelectionSettings() {}
    CompanionAdSelectionSettings.CreativeType = { ALL: 'All', FLASH: 'Flash', IMAGE: 'Image' };
    CompanionAdSelectionSettings.ResourceType = { ALL: 'All', HTML: 'Html', IFRAME: 'IFrame', STATIC: 'Static' };
    CompanionAdSelectionSettings.SizeCriteria = { IGNORE: 'IgnoreSize', SELECT_EXACT_MATCH: 'SelectExactMatch', SELECT_NEAR_MATCH: 'SelectNearMatch' };
    var ima = {
        AdCuePoints: function() {}, AdDisplayContainer: AdDisplayContainer, AdError: AdError,
        AdErrorEvent: AdErrorEvent, AdEvent: AdEvent, AdsLoader: AdsLoader, AdsManager: AdsManager,
        AdsManagerLoadedEvent: AdsManagerLoadedEvent, AdsRenderingSettings: AdsRenderingSettings,
        AdsRequest: AdsRequest, CompanionAdSelectionSettings: CompanionAdSelectionSettings,
        ImaSdkSettings: ImaSdkSettings, OmidAccessMode: { DOMAIN: 'domain', FULL: 'full', LIMITED: 'limited' },
        UiElements: { AD_ATTRIBUTION: 'adAttribution', COUNTDOWN: 'countdown' },
        UniversalAdIdInfo: function() {}, ViewMode: { FULLSCREEN: 'fullscreen', NORMAL: 'normal' },
        settings: new ImaSdkSettings(), VERSION: '3.600.0'
    };
    window.google = window.google || {};
    window.google.ima = ima;
})();
