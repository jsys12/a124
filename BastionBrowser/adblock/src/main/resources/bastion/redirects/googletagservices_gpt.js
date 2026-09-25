(function() {
    'use strict';
    var noop = function() {};
    var noopThis = function() { return this; };
    var noopNull = function() { return null; };
    var noopArr = function() { return []; };
    var noopStr = function() { return ''; };
    var companionAdsService = { addEventListener: noopThis, enableSyncLoading: noop, setRefreshUnfilledSlots: noop };
    var contentService = { addEventListener: noopThis, setContent: noop };
    function PassbackSlot() {}
    ['display','get','set','setClickUrl','setTagForChildDirectedTreatment','setTargeting','updateTargetingFromMap']
        .forEach(function(k) { PassbackSlot.prototype[k] = k === 'get' ? noopNull : noopThis; });
    PassbackSlot.prototype.display = noop;
    function SizeMappingBuilder() {}
    SizeMappingBuilder.prototype.addSize = noopThis;
    SizeMappingBuilder.prototype.build = noopNull;
    function Slot() {}
    var slotMethods = ['addService','clearCategoryExclusions','clearTargeting','defineSizeMapping','set',
        'setCategoryExclusion','setClickUrl','setCollapseEmptyDiv','setConfig','setForceSafeFrame',
        'setSafeFrameConfig','setTargeting','updateTargetingFromMap'];
    slotMethods.forEach(function(k) { Slot.prototype[k] = noopThis; });
    ['get','getAdUnitPath','getAttributeKeys','getCategoryExclusions','getDomId','getResponseInformation',
     'getSlotElementId','getSlotId','getTargeting','getTargetingKeys'].forEach(function(k) {
        Slot.prototype[k] = (k === 'getAttributeKeys' || k === 'getCategoryExclusions' || k === 'getTargeting' || k === 'getTargetingKeys') ? noopArr : noopNull;
    });
    Slot.prototype.getAdUnitPath = noopStr;
    Slot.prototype.getSlotElementId = noopStr;
    Slot.prototype.getHtml = noopStr;
    Slot.prototype.getSizes = noopArr;
    var pubAdsService = {
        addEventListener: noopThis, removeEventListener: noopThis, clear: noop, clearCategoryExclusions: noopThis,
        clearTagForChildDirectedTreatment: noopThis, clearTargeting: noopThis, collapseEmptyDivs: noop,
        defineOutOfPagePassback: function() { return new PassbackSlot(); },
        definePassback: function() { return new PassbackSlot(); },
        disableInitialLoad: noop, display: noop, enableAsyncRendering: noop, enableLazyLoad: noop,
        enableSingleRequest: noop, enableSyncRendering: noop, enableVideoAds: noop, get: noopNull,
        getAttributeKeys: noopArr, getTargeting: noopArr, getTargetingKeys: noopArr, getSlots: noopArr,
        refresh: noop, set: noopThis, setCategoryExclusion: noopThis, setCentering: noop, setCookieOptions: noopThis,
        setForceSafeFrame: noopThis, setLocation: noopThis, setPrivacySettings: noopThis,
        setPublisherProvidedId: noopThis, setRequestNonPersonalizedAds: noopThis, setSafeFrameConfig: noopThis,
        setTagForChildDirectedTreatment: noopThis, setTargeting: noopThis, setVideoContent: noopThis,
        updateCorrelator: noop
    };
    var gt = window.googletag || {};
    var cmd = gt.cmd || [];
    gt.apiReady = true;
    gt.cmd = [];
    gt.cmd.push = function(a) { try { a(); } catch (e) {} return 1; };
    gt.companionAds = function() { return companionAdsService; };
    gt.content = function() { return contentService; };
    gt.defineOutOfPageSlot = function() { return new Slot(); };
    gt.defineSlot = function() { return new Slot(); };
    gt.destroySlots = noop;
    gt.disablePublisherConsole = noop;
    gt.display = noop;
    gt.enableServices = noop;
    gt.getVersion = noopStr;
    gt.pubads = function() { return pubAdsService; };
    gt.pubadsReady = true;
    gt.setAdIframeTitle = noop;
    gt.setConfig = noop;
    gt.sizeMapping = function() { return new SizeMappingBuilder(); };
    window.googletag = gt;
    while (cmd.length !== 0) { gt.cmd.push(cmd.shift()); }
})();
