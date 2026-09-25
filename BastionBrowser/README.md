# Bastion — браузер для Android с многоуровневой блокировкой рекламы

Bastion — браузер на Android System WebView со встроенным движком блокировки рекламы.
Движок читает те же списки фильтров, что uBlock Origin и AdGuard (синтаксис ABP / uBO / AdGuard),
и применяет их на нескольких уровнях сразу — от сетевых запросов до скриптов внутри страницы.

## Скачать

APK собирается GitHub Actions при каждом пуше: **Actions → Bastion Browser → последний запуск → Artifacts →
`BastionBrowser-apk`**. Если поставить тег `v*` (например, `v1.0.0`), APK появится в **Releases**.

Нужен Android 8.0+ и обновлённый Android System WebView (Play Маркет → «Android System WebView»).

## Уровни защиты

| Уровень | Что делает |
|---|---|
| **Сеть** | Каждый запрос страницы (скрипты, картинки, фреймы, XHR, медиа, запросы Service Worker'ов) проверяется до отправки. Индексы по доменам и токенам: ~15 мкс на запрос на 650 тыс. правил. |
| **Подмены** | Вместо заблокированных Google Ads / GPT / GTM / Analytics / IMA / Amazon / FuckAdBlock подставляются безопасные заглушки (`$redirect`), чтобы сайты не ломались и не обнаруживали блокировщик. |
| **Скриптлеты** | Более 50 скриптлетов, совместимых с uBO/AdGuard (`set-constant`, `abort-on-property-read`, `abort-current-script`, `json-prune`, `no-fetch-if`, `no-xhr-if`, `nowoif`, `nostif`, `trusted-replace-fetch-response`, `m3u-prune`, `xml-prune`, …). Внедряются в каждый фрейм **до** выполнения скриптов страницы. |
| **Косметика** | Специфичные CSS-правила, ленивая генерик-косметика (правила по классам/id подгружаются только если такие элементы есть на странице), процедурные фильтры (`:has-text`, `:upward`, `:xpath`, `:matches-css`, `:remove()`, `:style()` и др.). |
| **Навигация** | Переходы на рекламные, трекинговые и вредоносные домены показывают страницу-предупреждение. Попапы без нажатия блокируются; окна, открытые по нажатию, проверяются по первому URL (`$popup`, доменные списки) и закрываются, если это реклама. Сайты не могут без нажатия запустить Play Маркет/приложение (`intent://`, `market://`). |
| **Ссылки** | `$removeparam` + встроенный список (`utm_*`, `fbclid`, `gclid`, `yclid`, …), `$urlskip` пропускает редиректы-трекеры. |
| **YouTube** | Реклама вырезается из ответов плеера (`JSON.parse`, `fetch`, `ytInitialPlayerResponse`); если что-то всё же проигрывается — ролик мгновенно проматывается без звука и нажимается «Пропустить». |
| **Эвристики** | Строгий режим скрывает сторонние фреймы стандартных рекламных размеров (300×250, 728×90, …), даже если их нет в списках. |
| **Вручную** | «Скрыть элемент»: коснитесь баннера — правило сохранится в «Мои правила». |
| **Приватность** | Global Privacy Control, блокировка сторонних cookie, удаление `ping` у ссылок, автоматический HTTPS, скрытие заголовка `X-Requested-With`, изолированный профиль инкогнито (WebView 118+). |

Списки по умолчанию (встроены в APK, обновляются раз в сутки): EasyList, EasyPrivacy, AdGuard Base,
AdGuard Mobile Ads, AdGuard Русский, RU AdList, uBlock filters / Privacy / Badware / Unbreak / Quick fixes,
AdGuard URL Tracking, HaGeZi Multi PRO, Peter Lowe, URLhaus, EasyList Cookie, AdGuard Mobile App Banners
и встроенный список Bastion. Дополнительно можно включить AdGuard Tracking Protection (CNAME-трекеры),
HaGeZi TIF, Phishing, наборы «раздражителей» или добавить любой список по URL.

### Сравнение с AdGuard

Внутри браузера Bastion применяет те же списки плюс вещи, которые системному блокировщику недоступны или
сложны: скриптлеты в каждом фрейме до загрузки страницы, проверку попапов по первому URL, запрет
автоматического запуска приложений, эвристику рекламных фреймов, встроенный пропуск рекламы YouTube.
При этом Bastion — браузер: рекламу **в других приложениях** он не блокирует (для этого нужен
системный VPN/DNS-блокировщик вроде AdGuard для Android).

## Возможности браузера

Вкладки и инкогнито, адресная строка снизу или сверху, поисковые подсказки (DuckDuckGo, Google, Яндекс,
Bing, Brave, Startpage, Ecosia), стартовая страница с быстрым доступом и статистикой, закладки, история,
загрузки (включая `blob:` и `data:`), поиск по странице, версия для ПК, печать и PDF, ярлыки на главный
экран, полноэкранное видео, загрузка файлов, камера/микрофон/геолокация по запросу, тёмная тема
(Material You) и затемнение сайтов, «потянуть для обновления», восстановление вкладок, работа как
браузер по умолчанию.

## Сборка

```bash
cd BastionBrowser
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # R8, ~8 МБ; подпись из переменных BASTION_KEYSTORE* или debug-ключ
```

Нужны JDK 17+ и Android SDK (platform 36). Сборка скачивает списки фильтров в APK
(`-PskipFilterDownload` — пропустить; тогда приложение скачает их при первом запуске).

Для подписи релиза в CI добавьте секреты `BASTION_KEYSTORE_B64` (keystore в base64),
`BASTION_KEYSTORE_PASSWORD`, `BASTION_KEY_ALIAS`, `BASTION_KEY_PASSWORD`.

## Тесты

```bash
./gradlew :adblock:test                                # движок фильтров
./gradlew :adblock:test -PlistsDir=/путь/к/спискам      # + компиляция и проверки на реальных списках
./gradlew :app:testDebugUnitTest                       # smoke-тесты UI на Robolectric
cd tools/jstest && npm install && node test.js         # скриптлеты/косметика/пипетка в headless Chromium
```

## Устройство

```
adblock/   чистый Kotlin/JVM-модуль: парсер фильтров, сетевой матчинг, косметика, redirect-ресурсы,
           removeparam/urlskip, бинарный снапшот скомпилированного движка
app/       Android-приложение (Kotlin, Jetpack Compose, Material 3)
  assets/bastion/   content.js (рантайм страницы), scriptlets.js, picker.js
  assets/filters/   встроенный список Bastion (остальные скачиваются при сборке)
  adblock/          загрузка/обновление списков, JS-мост, сборка content-скрипта
  browser/          вкладки, WebViewClient/ChromeClient, попапы, загрузки
  ui/               экраны
tools/jstest/     тесты content-рантайма
```

Списки фильтров принадлежат их авторам и распространяются по их лицензиям.
