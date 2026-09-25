// Builds the document-start script exactly like ContentScripts.kt does.
const fs = require('fs');
const path = require('path');
const assets = path.join(__dirname, '../../app/src/main/assets/bastion');
const redirects = path.join(__dirname, '../../adblock/src/main/resources/bastion/redirects');

const RESOURCE_SCRIPTLETS = {
    'nofab': 'fuckadblock.js',
    'nobab': 'fuckadblock.js',
    'popads-dummy': 'popads.js',
    'google-ima': 'google-ima.js',
    'googlesyndication_adsbygoogle': 'googlesyndication_adsbygoogle.js',
    'googletagservices_gpt': 'googletagservices_gpt.js',
    'prebid-ads': 'prebid-ads.js',
    'amazon_apstag': 'amazon_apstag.js',
};

function assemble(bridge, guard) {
    const lib = fs.readFileSync(path.join(assets, 'scriptlets.js'), 'utf8');
    const content = fs.readFileSync(path.join(assets, 'content.js'), 'utf8')
        .split('%BRIDGE%').join(bridge).split('%GUARD%').join(guard);
    const res = Object.entries(RESOURCE_SCRIPTLETS).map(([name, file]) =>
        JSON.stringify(name) + ': function() {\n' + fs.readFileSync(path.join(redirects, file), 'utf8') + '\n}').join(',\n');
    return '(function() {\n' + lib + '\nconst RESOURCES = {\n' + res + '\n};\n' + content + '\n})();';
}
module.exports = { assemble, RESOURCE_SCRIPTLETS };
