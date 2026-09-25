package io.github.jsys12.bastion.browser

import android.text.Html
import android.webkit.WebResourceResponse
import io.github.jsys12.bastion.adblock.MatchResult
import java.io.ByteArrayInputStream

/** Built-in HTML pages served in place of blocked or failed documents. */
object Pages {
    private fun esc(s: String): String = Html.escapeHtml(s)

    private const val STYLE = """
        :root{color-scheme:light dark;--bg:#f6f7fb;--card:#fff;--fg:#1b1d22;--muted:#6b7280;--accent:#2e6bff;--danger:#e5484d}
        @media (prefers-color-scheme:dark){:root{--bg:#101318;--card:#1a1e26;--fg:#e8eaf0;--muted:#9aa3b2;--accent:#6b9bff}}
        *{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
        font:16px/1.5 system-ui,-apple-system,Roboto,sans-serif;background:var(--bg);color:var(--fg);padding:24px}
        .card{max-width:520px;width:100%;background:var(--card);border-radius:24px;padding:28px 24px;box-shadow:0 8px 30px rgba(0,0,0,.08)}
        .icon{width:64px;height:64px;border-radius:20px;display:flex;align-items:center;justify-content:center;font-size:34px;margin-bottom:16px}
        h1{font-size:22px;margin:0 0 8px}p{margin:0 0 12px;color:var(--muted)}
        code{display:block;word-break:break-all;background:rgba(127,127,127,.12);padding:10px 12px;border-radius:12px;font-size:13px;margin:12px 0;color:var(--fg)}
        .row{display:flex;gap:10px;flex-wrap:wrap;margin-top:20px}
        a.btn,button{appearance:none;border:0;border-radius:14px;padding:12px 18px;font:600 15px system-ui,Roboto,sans-serif;text-decoration:none;cursor:pointer}
        .primary{background:var(--accent);color:#fff}.secondary{background:rgba(127,127,127,.14);color:var(--fg)}
    """

    fun blockedPage(url: String, r: MatchResult, list: String?, token: String): WebResourceResponse {
        val html = """<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Страница заблокирована</title>
<style>$STYLE .icon{background:rgba(229,72,77,.12)}</style></head><body><div class="card">
<div class="icon">🛡️</div>
<h1>Bastion заблокировал переход</h1>
<p>Этот адрес находится в списке рекламных, трекинговых или вредоносных сайтов${if (list != null) " («" + esc(list) + "»)" else ""}.</p>
<code>${esc(url)}</code>
<p>Правило: ${esc(r.describe())}</p>
<div class="row">
<a class="btn primary" href="bastion://back">Назад</a>
<a class="btn secondary" href="bastion://proceed?token=$token">Всё равно открыть</a>
</div></div></body></html>"""
        return WebResourceResponse(
            "text/html", "UTF-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "script-src 'none'"),
            ByteArrayInputStream(html.toByteArray()),
        )
    }

    fun errorPage(url: String, description: String): String {
        val host = UrlUtil.displayHost(url)
        return """<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(host)}</title>
<style>$STYLE .icon{background:rgba(46,107,255,.12)}</style></head><body><div class="card">
<div class="icon">📡</div>
<h1>Не удаётся открыть страницу</h1>
<p>Сайт <b>${esc(host)}</b> недоступен. Проверьте подключение к интернету или адрес.</p>
<code>${esc(description)}</code>
<div class="row"><button class="primary" onclick="location.reload()">Повторить</button></div>
</div></body></html>"""
    }
}
