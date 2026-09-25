(function() {
    'use strict';
    var head = document.head;
    if (!head) { return; }
    var style = document.querySelector('style[amp-boilerplate]');
    if (style !== null) { style.remove(); }
})();
