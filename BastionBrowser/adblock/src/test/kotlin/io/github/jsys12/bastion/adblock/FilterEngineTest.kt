package io.github.jsys12.bastion.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    private fun engine(vararg lines: String, trusted: Boolean = false): FilterEngine {
        val b = EngineBuilder()
        b.collectBadFilters(lines.asSequence())
        b.addList(1, trusted, lines.asSequence())
        return b.build()
    }

    private fun FilterEngine.blocks(url: String, page: String = "https://news.example.org/", type: Int = RequestType.SCRIPT) =
        match(url, HostInfo.of(page), type).blocked

    @Test
    fun hostAnchoredAndPureHosts() {
        val e = engine("||ads.example.com^", "||tracker.net^\$third-party", "0.0.0.0 evil.org", "plainhost.io")
        assertTrue(e.blocks("https://ads.example.com/x.js"))
        assertTrue(e.blocks("https://cdn.ads.example.com/x.js"))
        assertFalse(e.blocks("https://notads.example.com/x.js"))
        assertFalse(e.blocks("https://ads.example.com.good.net/x.js"))
        assertTrue(e.blocks("https://t.tracker.net/p"))
        assertFalse(e.blocks("https://t.tracker.net/p", page = "https://www.tracker.net/"))
        assertTrue(e.blocks("http://evil.org/a"))
        assertTrue(e.blocks("https://x.plainhost.io/"))
        // pure hostname filters also block documents (strict blocking)
        assertTrue(e.blocks("https://ads.example.com/", page = "", type = RequestType.DOCUMENT))
        assertFalse(e.blocks("https://t.tracker.net/", page = "", type = RequestType.DOCUMENT))
    }

    @Test
    fun generalExceptionLiftsDocumentBlocking() {
        val e = engine("||cdn.example.net^", "||tracker.org^", "@@||cdn.example.net^", "@@||tracker.org^\$script")
        assertFalse(e.blocks("https://cdn.example.net/", page = "", type = RequestType.DOCUMENT))
        assertTrue(e.blocks("https://tracker.org/", page = "", type = RequestType.DOCUMENT))
        assertTrue(e.matchPopup("https://tracker.org/x", HostInfo.of("https://a.com/"), 0).blocked)
    }

    @Test
    fun patternsWildcardsSeparators() {
        val e = engine(
            "/banner/*/img^", "-ad-300x250.", "|https://start.example/", "swf|", "||cdn.site.com/ads/*.js\$script",
            "&ad_type=", "/adserve/*",
        )
        assertTrue(e.blocks("https://x.com/banner/123/img?x=1", type = RequestType.IMAGE))
        assertTrue(e.blocks("https://x.com/banner/123/img", type = RequestType.IMAGE))
        assertFalse(e.blocks("https://x.com/banner/123/imgx", type = RequestType.IMAGE))
        assertTrue(e.blocks("https://x.com/pic-ad-300x250.png", type = RequestType.IMAGE))
        assertTrue(e.blocks("https://start.example/foo"))
        assertFalse(e.blocks("https://other.com/?https://start.example/"))
        assertTrue(e.blocks("https://x.com/movie.swf"))
        assertFalse(e.blocks("https://x.com/movie.swf?x"))
        assertTrue(e.blocks("https://cdn.site.com/ads/a/b.js"))
        assertFalse(e.blocks("https://cdn.site.com/ads/a/b.js", type = RequestType.IMAGE))
        assertTrue(e.blocks("https://x.com/p?x=1&ad_type=2", type = RequestType.XHR))
        assertTrue(e.blocks("https://x.com/adserve/q", type = RequestType.XHR))
    }

    @Test
    fun exceptionsImportantAndBadfilter() {
        val e = engine(
            "||ads.com^", "@@||ads.com/allowed/", "||imp.com^\$important", "@@||imp.com^",
            "||bad.com^", "||bad.com^\$badfilter", "/pixel.gif\$image", "@@/pixel.gif\$image,domain=good.org",
        )
        assertTrue(e.blocks("https://ads.com/x"))
        assertFalse(e.blocks("https://ads.com/allowed/x"))
        assertTrue(e.blocks("https://imp.com/x"))
        assertFalse(e.blocks("https://bad.com/x"))
        assertTrue(e.blocks("https://t.com/pixel.gif", type = RequestType.IMAGE))
        assertFalse(e.blocks("https://t.com/pixel.gif", page = "https://www.good.org/", type = RequestType.IMAGE))
    }

    @Test
    fun domainOptionsAndEntities() {
        val e = engine(
            "||cdn.net/ad.js\$domain=site.com|~sub.site.com", "*\$script,3p,domain=strict.org",
            "/promo.js\$domain=google.*", "||x.com^\$denyallow=y.com|z.com,domain=q.com",
        )
        assertTrue(e.blocks("https://cdn.net/ad.js", page = "https://www.site.com/"))
        assertFalse(e.blocks("https://cdn.net/ad.js", page = "https://sub.site.com/"))
        assertFalse(e.blocks("https://cdn.net/ad.js", page = "https://other.com/"))
        assertTrue(e.blocks("https://any.cdn/lib.js", page = "https://strict.org/"))
        assertFalse(e.blocks("https://strict.org/lib.js", page = "https://strict.org/"))
        assertTrue(e.blocks("https://a.b/promo.js", page = "https://www.google.co.uk/"))
        assertFalse(e.blocks("https://a.b/promo.js", page = "https://www.bing.com/"))
    }

    @Test
    fun regexFilters() {
        val e = engine("/^https?:\\/\\/[a-z]{8,15}\\.(com|net)\\/[a-z0-9]{10,}\\.js\$/\$script,3p", "/\\/ads\\/[0-9]+\\.gif/")
        assertTrue(e.blocks("https://abcdefghij.com/0123456789ab.js"))
        assertFalse(e.blocks("https://abc.com/0123456789ab.js"))
        assertTrue(e.blocks("https://x.org/ads/123.gif", type = RequestType.IMAGE))
    }

    @Test
    fun redirects() {
        val e = engine(
            "||googletagservices.com/tag/js/gpt.js\$script,redirect=googletagservices_gpt.js",
            "||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,redirect-rule=google-ima.js",
            "||imasdk.googleapis.com^\$3p",
        )
        val r = e.match("https://www.googletagservices.com/tag/js/gpt.js", HostInfo.of("https://a.com/"), RequestType.SCRIPT)
        assertTrue(r.blocked)
        assertEquals("googletagservices_gpt.js", r.redirect)
        val r2 = e.match("https://imasdk.googleapis.com/js/sdkloader/ima3.js", HostInfo.of("https://a.com/"), RequestType.SCRIPT)
        assertTrue(r2.blocked)
        assertEquals("google-ima.js", r2.redirect)
        assertNotNull(Redirects.get("google-ima.js")!!.data.size.takeIf { it > 1000 })
    }

    @Test
    fun pageFlags() {
        val e = engine("@@||trusted.com^\$document", "@@||nohide.com^\$generichide", "@@||bank.com^\$elemhide")
        assertTrue(e.pageFlags("https://www.trusted.com/page") and PageFlags.ALLOW_ALL != 0)
        assertTrue(e.pageFlags("https://nohide.com/") and PageFlags.GENERICHIDE != 0)
        assertEquals(0, e.pageFlags("https://other.com/"))
    }

    @Test
    fun removeParamAndUrlSkip() {
        val e = engine(
            "\$removeparam=utm_source", "||shop.com^\$removeparam=ref", "\$removeparam=/^fbclid/",
            "||out.example.com/redirect?\$urlskip=?url",
        )
        assertEquals("https://a.com/p?x=1", e.removeParams("https://a.com/p?utm_source=tw&x=1&fbclid=abc"))
        assertEquals("https://shop.com/p", e.removeParams("https://shop.com/p?ref=aff"))
        assertNull(e.removeParams("https://a.com/p?x=1"))
        assertEquals("https://dest.org/a?b=1", e.urlSkip("https://out.example.com/redirect?url=https%3A%2F%2Fdest.org%2Fa%3Fb%3D1"))
        assertEquals("https://x.com/?a=1#h", ParamCleaner.stripDefault("https://x.com/?a=1&utm_medium=x&gclid=1#h"))
    }

    @Test
    fun cosmetics() {
        val e = engine(
            "##.ad-banner", "##div[id^=\"div-gpt-ad\"]", "example.com##.sidebar-promo", "example.com#@#.ad-banner",
            "~safe.org##.sponsored", "example.com##.x:has-text(Реклама)", "example.com##+js(set-constant, canRunAds, true)",
            "example.com##+js(trusted-set-cookie, a, b)", "example.com#\$#body { overflow: auto !important; }",
            "example.com##.hdr:style(top: 0 !important)", "##body", "google.*##.g-ad", "example.com##^div.x",
            "sub.example.com#@#+js(set-constant, canRunAds, true)", "example.com#%#window.x=1",
        )
        val c = e.cosmetics("https://www.example.com/page", 0, true)
        assertEquals(listOf(".sidebar-promo"), c.hide)
        assertTrue(".ad-banner" in c.exceptions)
        assertEquals(listOf(".x:has-text(Реклама)"), c.procedural)
        assertEquals(1, c.scriptlets.size)
        assertEquals("set-constant", c.scriptlets[0][0])
        assertTrue(c.css.contains("body{overflow: auto !important;}"))
        assertTrue(c.css.contains(".hdr{top: 0 !important}"))
        assertTrue(c.js.isEmpty()) // untrusted list
        val sub = e.cosmetics("https://sub.example.com/", 0, true)
        assertTrue(sub.scriptlets.isEmpty())
        val safe = e.cosmetics("https://safe.org/", 0, true)
        assertTrue(".sponsored" in safe.exceptions)
        val g = e.cosmetics("https://www.google.de/", 0, true)
        assertEquals(listOf(".g-ad"), g.hide)
        assertEquals(listOf(".ad-banner"), e.cosmetic.selectorsForKeys(listOf(".ad-banner", "#nothing"), emptySet()))
        assertTrue(e.cosmetic.unkeyedCss(emptySet()).contains("div[id^=\"div-gpt-ad\"]{display:none!important}"))
        assertFalse(e.cosmetic.unkeyedCss(emptySet()).contains("body{"))
    }

    @Test
    fun htmlScriptFiltersBecomeScriptlets() {
        val e = engine("site.com##^script:has-text(adblockDetected)", "other.com##^script[data-cfasync]:has-text(/popunder/i)", "x.com##^div.ad")
        val c = e.cosmetics("https://site.com/", 0, true)
        assertEquals(listOf("remove-node-text", "script", "adblockDetected"), c.scriptlets.single().toList())
        assertEquals("/popunder/i", e.cosmetics("https://other.com/", 0, true).scriptlets.single()[2])
        assertTrue(e.cosmetics("https://x.com/", 0, true).scriptlets.isEmpty())
    }

    @Test
    fun preprocessor() {
        val e = engine(
            "!#if env_mobile", "||mobile.com^", "!#endif", "!#if env_firefox", "||firefox.com^", "!#else",
            "||notfirefox.com^", "!#endif", "!#if cap_html_filtering", "||html.com^", "!#endif",
        )
        assertTrue(e.blocks("https://mobile.com/"))
        assertFalse(e.blocks("https://firefox.com/"))
        assertTrue(e.blocks("https://notfirefox.com/"))
        assertFalse(e.blocks("https://html.com/"))
    }

    @Test
    fun scriptletArgs() {
        assertEquals(listOf("set-constant", "a.b", "true"), FilterParser.parseUboScriptletArgs("set-constant.js, a.b, true"))
        assertEquals(listOf("rmnt", "script", "a,b"), FilterParser.parseUboScriptletArgs("rmnt, script, a\\,b"))
        assertEquals(listOf("nostif", "x, y", "100"), FilterParser.parseUboScriptletArgs("nostif, 'x, y', 100"))
        assertEquals(listOf("set-constant", "x", "false"), FilterParser.parseAdgScriptletArgs("'ubo-set-constant.js', 'x', \"false\""))
    }

    @Test
    fun cosmeticKeys() {
        assertEquals(".ad", CosmeticIndex.keyOf(".ad"))
        assertEquals(".ad", CosmeticIndex.keyOf("div.ad > span"))
        assertEquals("#banner", CosmeticIndex.keyOf("#banner"))
        assertNull(CosmeticIndex.keyOf("[id^=ad]"))
        assertNull(CosmeticIndex.keyOf(".a, .b"))
        assertNull(CosmeticIndex.keyOf("a[href*=ads]"))
    }

    @Test
    fun typeInference() {
        assertEquals(RequestType.SCRIPT, RequestType.infer("https://a.com/x.js?v=1", false, null))
        assertEquals(RequestType.IMAGE, RequestType.infer("https://a.com/x", false, mapOf("Accept" to "image/avif,image/webp")))
        assertEquals(RequestType.STYLESHEET, RequestType.infer("https://a.com/x", false, mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertEquals(RequestType.AMBIGUOUS, RequestType.infer("https://a.com/x", false, mapOf("Accept" to "*/*")))
        assertEquals(RequestType.DOCUMENT, RequestType.infer("https://a.com/", true, null))
    }
}
