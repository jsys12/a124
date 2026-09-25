package io.github.jsys12.bastion.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against downloaded lists when `-PlistsDir=...` is given; skipped otherwise. */
class RealListsTest {

    @Test
    fun compileAndMatchRealLists() {
        val dir = System.getProperty("listsDir")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val skip = System.getProperty("skipLists")?.split(',')?.toSet() ?: emptySet()
        val files = dir!!.listFiles { f -> f.name.endsWith(".txt") && f.name !in skip }!!.sortedBy { it.name }
        val sources = files.mapIndexed { i, f -> FilterSource(i, trusted = true) { f.bufferedReader() } }

        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        var t = System.nanoTime()
        val engine = EngineBuilder.compile(sources)
        val compileMs = (System.nanoTime() - t) / 1_000_000
        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        println("Lists: ${files.size}, compile: $compileMs ms, heap: ${(after - before) / 1_048_576} MB")
        println("Network filters: ${engine.networkFilterCount}, cosmetic: ${engine.cosmeticFilterCount}")
        println("Generic keys: ${engine.cosmetic.genericKeyCount()}, unkeyed: ${engine.cosmetic.genericUnkeyedCount()}, " +
            "unkeyed css: ${engine.cosmetic.unkeyedCss(emptySet()).length / 1024} KB")
        println(engine.debugStats())
        files.forEachIndexed { i, f -> println("  ${f.name}: ${engine.listCounts[i] ?: 0} network") }

        val page = HostInfo.of("https://www.example-news.com/article")
        val shouldBlock = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://www.google-analytics.com/analytics.js",
            "https://connect.facebook.net/en_US/fbevents.js",
            "https://an.yandex.ru/system/context.js",
            "https://mc.yandex.ru/metrika/tag.js",
            "https://ad.mail.ru/adq/?q=1",
            "https://static.criteo.net/js/ld/publishertag.js",
            "https://cdn.taboola.com/libtrc/site/loader.js",
            "https://widgets.outbrain.com/outbrain.js",
            "https://c.amazon-adsystem.com/aax2/apstag.js",
            "https://www.googletagmanager.com/gtm.js?id=GTM-XXXX",
            "https://ads.pubmatic.com/AdServer/js/pwt/1/pwt.js",
            "https://adservice.google.com/adsid/integrator.js",
            "https://yandex.ru/ads/system/context.js",
        )
        val shouldAllow = listOf(
            "https://www.example-news.com/static/app.js",
            "https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js",
            "https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js",
            "https://fonts.googleapis.com/css2?family=Roboto",
            "https://www.youtube.com/iframe_api",
            "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg",
            "https://yastatic.net/jquery/3.3.1/jquery.min.js",
            "https://www.gstatic.com/recaptcha/releases/abc/recaptcha__en.js",
        )
        for (u in shouldBlock) {
            val r = engine.match(u, page, RequestType.SCRIPT)
            println("BLOCK? ${r.blocked} ${r.redirect ?: ""} $u  <- ${r.describe()}")
            assertTrue("expected block: $u", r.blocked)
        }
        for (u in shouldAllow) {
            val r = engine.match(u, page, RequestType.SCRIPT)
            println("ALLOW? ${!r.blocked} $u ${r.describe()}")
            assertFalse("expected allow: $u", r.blocked)
        }

        // Throughput on a mixed batch of URLs.
        val urls = (shouldBlock + shouldAllow).flatMap { u -> (0 until 2000).map { "$u&n=$it" } }
        t = System.nanoTime()
        var blocked = 0
        for (u in urls) if (engine.match(u, page, RequestType.AMBIGUOUS).blocked) blocked++
        val us = (System.nanoTime() - t) / 1000.0 / urls.size
        println("match: %.2f µs/request over %d requests (%d blocked)".format(us, urls.size, blocked))

        val c = engine.cosmetics("https://www.youtube.com/watch?v=x", 0, true)
        println("youtube cosmetics: hide=${c.hide.size} proc=${c.procedural.size} scriptlets=${c.scriptlets.size} css=${c.css.size}")
        val ya = engine.cosmetics("https://yandex.ru/", 0, true)
        println("yandex cosmetics: hide=${ya.hide.size} proc=${ya.procedural.size} scriptlets=${ya.scriptlets.size}")
        println("removeparam: " + engine.removeParams("https://www.example.com/?utm_source=a&utm_medium=b&x=1&fbclid=z&yclid=5"))

        // Snapshot round trip must give identical decisions.
        val snap = File.createTempFile("engine", ".bin")
        t = System.nanoTime()
        Snapshot.save(engine, snap, "k1")
        val saveMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val loaded = Snapshot.load(snap, "k1")!!
        val loadMs = (System.nanoTime() - t) / 1_000_000
        println("snapshot: ${snap.length() / 1024} KB, save $saveMs ms, load $loadMs ms")
        repeat(2) {
            t = System.nanoTime()
            Snapshot.load(snap, "k1")!!
            println("snapshot warm load ${(System.nanoTime() - t) / 1_000_000} ms")
            t = System.nanoTime()
            EngineBuilder.compile(sources)
            println("warm compile ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        assertTrue(Snapshot.load(snap, "other") == null)
        for (u in urls.take(4000)) {
            val a = engine.match(u, page, RequestType.AMBIGUOUS)
            val b = loaded.match(u, page, RequestType.AMBIGUOUS)
            assertTrue("snapshot mismatch $u", a.blocked == b.blocked && a.redirect == b.redirect)
        }
        for (site in listOf("https://www.youtube.com/", "https://yandex.ru/", "https://www.example.com/")) {
            val a = engine.cosmetics(site, 0, true)
            val b = loaded.cosmetics(site, 0, true)
            assertTrue(a.hide == b.hide && a.procedural == b.procedural && a.scriptlets.map { it.toList() } == b.scriptlets.map { it.toList() })
        }
        assertTrue(engine.cosmetic.unkeyedCss(emptySet()) == loaded.cosmetic.unkeyedCss(emptySet()))
        snap.delete()
    }
}
