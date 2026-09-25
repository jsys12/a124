package io.github.jsys12.bastion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jsys12.bastion.BuildConfig
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.FilterListInfo
import io.github.jsys12.bastion.adblock.FilterUpdater
import io.github.jsys12.bastion.adblock.Urls
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.DialogState
import io.github.jsys12.bastion.browser.Screen
import io.github.jsys12.bastion.browser.SearchEngines
import io.github.jsys12.bastion.data.Settings
import kotlinx.coroutines.launch

private fun BrowserController.back() {
    if (screens.isNotEmpty()) screens.removeAt(screens.size - 1)
}

@Composable
private fun Settings.Pref<Boolean>.state() = flow.collectAsState()

@Composable
fun SettingsScreen(c: BrowserController) {
    val s = c.settings
    val status by AdBlocker.status.collectAsState()
    var choice by remember { mutableStateOf<String?>(null) }
    var clearDialog by remember { mutableStateOf(false) }

    ScreenScaffold("Настройки", onBack = { c.back() }) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            SectionTitle("Защита")
            SwitchRow("Блокировка рекламы и трекеров", "Сетевая фильтрация, скриптлеты и скрытие элементов", Icons.Filled.Shield, s.adblock.state().value) { s.adblock.value = it }
            SettingRow(
                "Списки фильтров",
                "${formatCount((status.networkRules + status.cosmeticRules).toLong())} правил · ${AdBlocker.catalog.enabled().size} списков",
                Icons.Filled.FilterList,
                onClick = { c.openScreen(Screen.FILTERS) },
            )
            SwitchRow("Скрывать рекламные элементы", "Убирать баннеры и пустые места на страницах", Icons.Filled.Visibility, s.cosmetic.state().value) { s.cosmetic.value = it }
            SwitchRow("Строгий режим", "Эвристики: скрывать сторонние фреймы стандартных рекламных размеров", Icons.Filled.AutoAwesome, s.strictMode.state().value) { s.strictMode.value = it }
            SwitchRow("Блокировать переходы на рекламные сайты", "Показывать предупреждение вместо рекламной или вредоносной страницы", Icons.Filled.Block, s.strictBlocking.state().value) { s.strictBlocking.value = it }
            SwitchRow("Блокировать всплывающие окна", "Попандеры и окна, открытые без вашего нажатия", Icons.Filled.WebAsset, s.blockPopups.state().value) { s.blockPopups.value = it }
            SwitchRow("Запрещать запуск приложений", "Сайты не смогут открыть магазин или приложение без нажатия", Icons.Filled.Apps, s.blockAppRedirects.state().value) { s.blockAppRedirects.value = it }
            SwitchRow("Очищать ссылки от слежки", "Удалять utm_*, fbclid, yclid, gclid и пропускать редиректы-трекеры", Icons.Filled.Link, s.stripTracking.state().value) { s.stripTracking.value = it }
            SwitchRow("Без рекламы на YouTube", "Вырезать рекламу из плеера и мгновенно пропускать остатки", Icons.Filled.SmartDisplay, s.youtube.state().value) { s.youtube.value = it }
            SettingRow("Мои правила", "Свои фильтры в синтаксисе AdGuard / uBlock", Icons.Filled.Edit, onClick = { c.openScreen(Screen.USER_RULES) })
            SettingRow("Исключения", "Сайтов без блокировки: ${s.allowlist.flow.collectAsState().value.size}", Icons.Filled.PlaylistRemove, onClick = { c.openScreen(Screen.ALLOWLIST) })
            SwitchRow("Автообновление фильтров", "Проверять обновления раз в день", Icons.Filled.Refresh, s.autoUpdate.state().value) { s.autoUpdate.value = it }
            SwitchRow("Обновлять только по Wi-Fi", null, Icons.Filled.Wifi, s.updateWifiOnly.state().value) { s.updateWifiOnly.value = it }

            SectionTitle("Приватность")
            SwitchRow("Сторонние cookie", "Нужны некоторым виджетам и входу через соцсети", Icons.Filled.Cookie, s.thirdPartyCookies.state().value) { s.thirdPartyCookies.value = it }
            SwitchRow("Global Privacy Control", "Просить сайты не продавать и не передавать ваши данные", Icons.Filled.PrivacyTip, s.gpc.state().value) { s.gpc.value = it }
            SwitchRow("Автоматический HTTPS", "Открывать защищённую версию сайтов, когда она есть", Icons.Filled.Https, s.httpsUpgrade.state().value) { s.httpsUpgrade.value = it }
            SwitchRow("Безопасный просмотр", "Предупреждать о фишинге и вирусах (Google Safe Browsing)", Icons.Filled.GppGood, s.safeBrowsing.state().value) { s.safeBrowsing.value = it }
            SwitchRow("Сохранять историю", null, Icons.Filled.History, s.saveHistory.state().value) { s.saveHistory.value = it }
            SwitchRow("Очищать cookie и кэш при выходе", null, Icons.Filled.DeleteSweep, s.clearOnExit.state().value) { s.clearOnExit.value = it }
            SettingRow("Очистить данные браузера", "История, cookie, кэш", Icons.Filled.Delete, onClick = { clearDialog = true })

            SectionTitle("Внешний вид")
            val theme by s.theme.flow.collectAsState()
            SettingRow("Тема", THEMES.first { it.first == theme }.second, Icons.Filled.Palette, onClick = { choice = "theme" })
            SwitchRow("Тёмные сайты", "Затемнять страницы, когда включена тёмная тема", Icons.Filled.DarkMode, s.webDarkMode.state().value) { s.webDarkMode.value = it }
            SwitchRow("Адресная строка внизу", "Удобнее тянуться большим пальцем", Icons.Filled.VerticalAlignBottom, s.bottomBar.state().value) { s.bottomBar.value = it }
            SwitchRow("Потянуть вниз для обновления", null, Icons.Filled.Swipe, s.pullToRefresh.state().value) { s.pullToRefresh.value = it }
            val zoom by s.textZoom.flow.collectAsState()
            SettingRow("Размер текста", "$zoom%", Icons.Filled.TextFields)
            Slider(
                value = zoom.toFloat(),
                onValueChange = { s.textZoom.value = (Math.round(it / 10f) * 10) },
                valueRange = 50f..200f,
                steps = 14,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            SectionTitle("Общие")
            val engine by s.searchEngine.flow.collectAsState()
            SettingRow("Поисковая система", SearchEngines.byId(engine).name, Icons.Filled.Search, onClick = { choice = "engine" })
            SwitchRow("Поисковые подсказки", "Отправлять вводимый текст поисковой системе", Icons.Filled.Search, s.suggestions.state().value) { s.suggestions.value = it }
            val home by s.homepage.flow.collectAsState()
            SettingRow("Домашняя страница", home.ifBlank { "Стартовая страница Bastion" }, Icons.Filled.Home, onClick = {
                c.dialog = DialogState.Input("Домашняя страница", "Адрес (пусто — стартовая)", home) { v ->
                    c.dialog = null
                    if (v != null) s.homepage.value = v.trim().let { if (it.isEmpty() || it.startsWith("http")) it else "https://$it" }
                }
            })
            val apps by s.openApps.flow.collectAsState()
            SettingRow("Ссылки на приложения", OPEN_APPS.first { it.first == apps }.second, Icons.Filled.Apps, onClick = { choice = "apps" })
            SwitchRow("Восстанавливать вкладки", null, Icons.Filled.RestorePage, s.restoreTabs.state().value) { s.restoreTabs.value = it }
            SwitchRow("Версия для ПК по умолчанию", null, Icons.Filled.DesktopWindows, s.desktopDefault.state().value) { s.desktopDefault.value = it }
            SwitchRow("JavaScript", null, Icons.Filled.Javascript, s.javascript.state().value) { s.javascript.value = it }
            SwitchRow("Загружать изображения", "Выключите для экономии трафика", Icons.Filled.Image, s.loadImages.state().value) { s.loadImages.value = it }

            SectionTitle("О приложении")
            SettingRow("Статистика защиты", null, Icons.Filled.Insights, onClick = { c.openScreen(Screen.STATS) })
            SettingRow("О Bastion", "Версия ${BuildConfig.VERSION_NAME}", Icons.Filled.Info, onClick = { c.openScreen(Screen.ABOUT) })
            Spacer(Modifier.height(32.dp))
        }
    }

    when (choice) {
        "theme" -> ChoiceDialog("Тема", THEMES, s.theme.value, { s.theme.value = it }) { choice = null }
        "engine" -> ChoiceDialog("Поисковая система", SearchEngines.all.map { it.id to it.name }, s.searchEngine.value, { s.searchEngine.value = it }) { choice = null }
        "apps" -> ChoiceDialog("Ссылки на приложения", OPEN_APPS, s.openApps.value, { s.openApps.value = it }) { choice = null }
    }
    if (clearDialog) ClearDataDialog(c) { clearDialog = false }
}

private val THEMES = listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная")
private val OPEN_APPS = listOf("ask" to "Спрашивать", "always" to "Открывать сразу", "never" to "Никогда не открывать")

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = id == selected, role = Role.RadioButton, onClick = { onSelect(id); onDismiss() })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun ClearDataDialog(c: BrowserController, onDismiss: () -> Unit) {
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Очистить данные") },
        text = {
            Column {
                listOf(
                    Triple("История", history) { v: Boolean -> history = v },
                    Triple("Cookie и данные сайтов (выход из аккаунтов)", cookies) { v: Boolean -> cookies = v },
                    Triple("Кэш: изображения и файлы", cache) { v: Boolean -> cache = v },
                ).forEach { (label, checked, set) ->
                    Row(Modifier.fillMaxWidth().clickable { set(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = set)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.clearBrowsingData(history, cookies, cache)
                c.showSnackbar("Данные очищены")
                onDismiss()
            }) { Text("Очистить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ------------------------------------------------------------------ filter lists

private val CATEGORIES = listOf(
    "ads" to "Реклама",
    "regional" to "Региональные",
    "privacy" to "Приватность и трекеры",
    "dns" to "Большие списки доменов",
    "malware" to "Безопасность",
    "annoyances" to "Раздражители",
    "custom" to "Добавленные вами",
)

@Composable
fun FilterListsScreen(c: BrowserController) {
    val status by AdBlocker.status.collectAsState()
    val updating by FilterUpdater.updating.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val catalog = AdBlocker.catalog
    val lists = remember(version) { catalog.all() }

    ScreenScaffold("Списки фильтров", onBack = { c.back() }, actions = {
        if (updating) {
            CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = {
                scope.launch {
                    val r = FilterUpdater.updateAll(c.app, force = true)
                    version++
                    c.showSnackbar(
                        if (r.failed > 0) "Обновлено: ${r.changed}, ошибок: ${r.failed}"
                        else if (r.changed > 0) "Обновлено списков: ${r.changed}" else "Все списки актуальны"
                    )
                }
            }) { Icon(Icons.Filled.Refresh, "Обновить все") }
        }
    }) { modifier ->
        Box(modifier) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (status.loading) "Компиляция фильтров…" else "Активно ${formatCount((status.networkRules + status.cosmeticRules).toLong())} правил",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${formatCount(status.networkRules.toLong())} сетевых · ${formatCount(status.cosmeticRules.toLong())} косметических и скриптлетов" +
                                    if (!status.loading) " · загрузка ${status.loadMillis} мс" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (status.missing.isNotEmpty()) {
                                Text("Скачиваются: ${status.missing.size}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                for ((cat, title) in CATEGORIES) {
                    val inCat = lists.filter { it.category == cat }
                    if (inCat.isEmpty()) continue
                    item(key = "h_$cat") { SectionTitle(title) }
                    items(inCat, key = { it.id }) { list ->
                        FilterListRow(c, list, status.perList[list.id], version) { version++ }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = {
                    c.dialog = DialogState.Input("Добавить список", "URL списка фильтров", "https://", "Добавить") { url ->
                        c.dialog = null
                        val u = url?.trim() ?: return@Input
                        if (!u.startsWith("http") || Urls.host(u) == null) {
                            c.showSnackbar("Некорректный адрес"); return@Input
                        }
                        val info = catalog.addCustom(u, Urls.host(u) ?: u)
                        version++
                        scope.launch {
                            val r = FilterUpdater.updateOne(c.app, info)
                            version++
                            c.showSnackbar(if (r.failed > 0) "Не удалось скачать список" else "Список добавлен")
                        }
                    }
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Добавить список") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }
}

@Composable
private fun FilterListRow(c: BrowserController, list: FilterListInfo, rules: Int?, version: Int, onChanged: () -> Unit) {
    val catalog = AdBlocker.catalog
    val enabled = remember(version) { catalog.isEnabled(list) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toggle = { v: Boolean ->
        catalog.setEnabled(list.id, v)
        onChanged()
        AdBlocker.requestRebuild()
    }
    Row(
        Modifier.fillMaxWidth().clickable { toggle(!enabled) }.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(list.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (list.description.isNotBlank()) {
                Text(list.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val updated = catalog.lastUpdated(list.id)
            val error = catalog.lastError(list.id)
            val meta = buildList {
                if (enabled && rules != null) add("${formatCount(rules.toLong())} правил")
                if (list.url.isEmpty()) add("встроенный")
                else if (updated > 0) add("обновлён ${ago(updated)}")
                else if (list.bundled) add("из комплекта")
                else if (enabled) add("ещё не загружен")
                if (error != null) add("ошибка: $error")
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
        }
        Switch(checked = enabled, onCheckedChange = toggle)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (list.url.isNotEmpty()) {
                    DropdownMenuItem(text = { Text("Обновить") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = {
                        menu = false
                        scope.launch {
                            val r = FilterUpdater.updateOne(c.app, list)
                            onChanged()
                            c.showSnackbar(if (r.failed > 0) "Ошибка обновления" else if (r.changed > 0) "Список обновлён" else "Список актуален")
                        }
                    })
                }
                if (list.homepage.isNotEmpty()) {
                    DropdownMenuItem(text = { Text("Сайт списка") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = {
                        menu = false
                        c.back()
                        c.newTab(list.homepage)
                    })
                }
                if (list.custom) {
                    DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                        menu = false
                        catalog.removeCustom(list.id)
                        java.io.File(AdBlocker.filtersDir, "${list.id}.txt").delete()
                        onChanged()
                        AdBlocker.requestRebuild()
                    })
                }
            }
        }
    }
}

fun ago(time: Long): String {
    val d = System.currentTimeMillis() - time
    return when {
        d < 60_000 -> "только что"
        d < 3_600_000 -> "${d / 60_000} мин назад"
        d < 86_400_000 -> "${d / 3_600_000} ч назад"
        else -> "${d / 86_400_000} дн. назад"
    }
}

// ------------------------------------------------------------------ user rules

@Composable
fun UserRulesScreen(c: BrowserController) {
    var text by remember { mutableStateOf(AdBlocker.readUserRules()) }
    var dirty by remember { mutableStateOf(false) }
    ScreenScaffold("Мои правила", onBack = {
        if (dirty) AdBlocker.writeUserRules(text)
        c.back()
    }, actions = {
        IconButton(onClick = {
            AdBlocker.writeUserRules(text)
            dirty = false
            c.showSnackbar("Правила сохранены")
        }, enabled = dirty) { Icon(Icons.Filled.Check, "Сохранить") }
    }) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "||ads.example.com^ — заблокировать домен\n" +
                        "example.com##.banner — скрыть элемент на сайте\n" +
                        "##.ad-box — скрыть элемент везде\n" +
                        "@@||example.com^\$document — отключить защиту на сайте\n" +
                        "example.com##+js(set-constant, adblock, false)",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; dirty = true },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("Одно правило на строку") },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ allowlist

@Composable
fun AllowlistScreen(c: BrowserController) {
    val set by c.settings.allowlist.flow.collectAsState()
    ScreenScaffold("Исключения", onBack = { c.back() }) { modifier ->
        Box(modifier) {
            if (set.isEmpty()) {
                EmptyState(Icons.Filled.ToggleOn, "Здесь появятся сайты, на которых вы отключили защиту")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(set.sorted(), key = { it }) { host ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            SiteIcon(null, host, 36.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(host, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = {
                                c.settings.allowlist.value = set - host
                                c.tabs.forEach { t -> if (t.pageHost?.endsWith(host) == true) { t.pageAllowlisted = false; t.protectionOff = false } }
                            }) { Icon(Icons.Filled.Delete, "Удалить") }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = {
                    c.dialog = DialogState.Input("Добавить исключение", "Домен, например example.com", "", "Добавить") { v ->
                        c.dialog = null
                        val raw = v?.trim()?.lowercase() ?: return@Input
                        val host = Urls.host(if (raw.contains("://")) raw else "https://$raw") ?: return@Input
                        c.settings.setAllowlisted(host.removePrefix("www."), true)
                    }
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Добавить сайт") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ stats

@Composable
fun StatsScreen(c: BrowserController) {
    val stats by c.stats.state.collectAsState()
    val status by AdBlocker.status.collectAsState()
    ScreenScaffold("Статистика защиты", onBack = { c.back() }) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Всего заблокировано", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(formatCount(stats.total + stats.elements), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Сэкономлено ≈ ${formatBytes(stats.savedBytes)} трафика и ≈ ${formatDuration(stats.savedMillis)} времени",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(formatCount(stats.requests), "рекламных и трекинговых запросов", Modifier.weight(1f))
                BigStat(formatCount(stats.popups), "всплывающих окон", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(formatCount(stats.navigations), "переходов на опасные и рекламные сайты", Modifier.weight(1f))
                BigStat(formatCount(stats.appRedirects), "попыток открыть приложение", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(formatCount(stats.params), "ссылок очищено от трекинга", Modifier.weight(1f))
                BigStat(formatCount(stats.elements), "рекламных фреймов скрыто эвристикой", Modifier.weight(1f))
            }
            BigStat(
                formatCount((status.networkRules + status.cosmeticRules).toLong()),
                "активных правил фильтрации в ${AdBlocker.catalog.enabled().size} списках",
                Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                c.confirm("Сбросить статистику?", "Счётчики начнутся с нуля.", "Сбросить") { if (it) c.stats.reset() }
            }) { Text("Сбросить статистику") }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ about

@Composable
fun AboutScreen(c: BrowserController) {
    ScreenScaffold("О Bastion", onBack = { c.back() }) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Bastion ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Браузер с многоуровневой блокировкой рекламы и слежки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text("Уровни защиты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Сеть" to "каждый запрос страницы проверяется по сотням тысяч правил (EasyList, AdGuard, uBlock, HaGeZi и др.) до того, как он уйдёт в интернет",
                "Подмены" to "вместо заблокированных скриптов Google Ads, GTM, IMA и др. подставляются безопасные заглушки, чтобы сайты не ломались",
                "Скриптлеты" to "до загрузки страницы внедряются более 50 скриптлетов совместимых с uBlock/AdGuard: против антиадблока, таймеров, попапов, трекинга",
                "Косметика" to "рекламные блоки скрываются CSS-правилами, включая процедурные фильтры (:has-text, :upward, :xpath…)",
                "Навигация" to "переходы на рекламные и вредоносные сайты, попандеры и автоматический запуск приложений блокируются",
                "Ссылки" to "трекинг-параметры удаляются, редиректы-трекеры пропускаются",
                "YouTube" to "реклама вырезается из ответа плеера; остатки мгновенно проматываются",
                "Эвристики" to "в строгом режиме скрываются сторонние фреймы стандартных рекламных размеров",
            ).forEach { (title, text) ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Icon(Icons.Filled.Shield, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Text("$title — $text", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Списки фильтров", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Списки принадлежат их авторам и распространяются по их лицензиям (GPLv3, CC BY-SA и др.). Спасибо сообществам EasyList, AdGuard, uBlock Origin, HaGeZi, RU AdList, Peter Lowe и malware-filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AdBlocker.catalog.builtIn.filter { it.homepage.isNotEmpty() }.distinctBy { it.homepage }.forEach { l ->
                Text(
                    l.homepage,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable {
                        c.back()
                        c.newTab(l.homepage)
                    }.padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Web, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Движок: Android System WebView", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
