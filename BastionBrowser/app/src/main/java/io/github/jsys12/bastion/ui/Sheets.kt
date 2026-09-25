package io.github.jsys12.bastion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TabUnselected
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jsys12.bastion.adblock.Urls
import io.github.jsys12.bastion.browser.BlockedEntry
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.ContextMenuState
import io.github.jsys12.bastion.browser.Downloads
import io.github.jsys12.bastion.browser.FaviconCache
import io.github.jsys12.bastion.browser.Screen
import io.github.jsys12.bastion.browser.Tab
import io.github.jsys12.bastion.browser.UrlUtil

// ------------------------------------------------------------------ tabs

@Composable
fun TabsSwitcher(c: BrowserController) {
    var incognito by remember { mutableStateOf(c.current?.incognito == true) }
    val list = c.tabs.filter { it.incognito == incognito }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.sheet = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        SegmentedButton(
                            selected = !incognito, onClick = { incognito = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = {},
                        ) { Text("Вкладки · ${c.tabs.count { !it.incognito }}", maxLines = 1) }
                        SegmentedButton(
                            selected = incognito, onClick = { incognito = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = {},
                        ) { Text("Инкогнито · ${c.tabs.count { it.incognito }}", maxLines = 1) }
                    }
                    TextButton(onClick = { c.closeAll(incognito) }, enabled = list.isNotEmpty()) { Text("Закрыть все") }
                }
                if (list.isEmpty()) {
                    EmptyState(if (incognito) Icons.Filled.VisibilityOff else Icons.Filled.TabUnselected, if (incognito) "Нет вкладок инкогнито" else "Нет открытых вкладок")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.id }) { t -> TabCard(c, t) }
                    }
                }
            }
            FloatingActionButton(
                onClick = {
                    c.sheet = null
                    c.newTab(incognito = incognito)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Icon(Icons.Filled.Add, "Новая вкладка") }
        }
    }
}

@Composable
private fun TabCard(c: BrowserController, t: Tab) {
    val selected = t === c.current
    Surface(
        onClick = {
            c.selectTab(t)
            c.sheet = null
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(t.favicon ?: FaviconCache.get(Urls.host(t.url)), t.displayTitle, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (t.homeVisible && t.url.isEmpty()) "Новая вкладка" else t.displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { c.closeTab(t) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Закрыть", Modifier.size(18.dp)) }
            }
            Box(
                Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = t.thumbnail
                if (thumb != null && !(t.homeVisible && t.url.isEmpty())) {
                    Image(thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        if (t.incognito) Icons.Filled.VisibilityOff else Icons.Filled.Shield, null,
                        tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(c: BrowserController) {
    val tab = c.current ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val version by c.db.version.collectAsState()
    val bookmarked = remember(version, tab.url) { tab.url.isNotEmpty() && c.db.isBookmarked(tab.url) }
    val onPage = !tab.homeVisible && tab.url.isNotEmpty()
    ModalBottomSheet(onDismissRequest = { c.sheet = null }, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", enabled = tab.canGoBack || tab.homeVisible) { c.sheet = null; c.goBack() }
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд", enabled = tab.canGoForward || tab.homeOverPage) { c.sheet = null; c.goForward() }
                CircleIconButton(if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, "Закладка", enabled = onPage) { c.toggleBookmark() }
                CircleIconButton(Icons.Filled.Refresh, "Обновить", enabled = onPage) { c.sheet = null; c.reload() }
                CircleIconButton(Icons.Filled.Share, "Поделиться", enabled = onPage) { c.sheet = null; c.share() }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            val items = listOf(
                MenuItem(Icons.Filled.Add, "Новая вкладка") { c.sheet = null; c.newTab() },
                MenuItem(Icons.Filled.VisibilityOff, "Инкогнито") { c.sheet = null; c.newTab(incognito = true) },
                MenuItem(Icons.Filled.Bookmarks, "Закладки") { c.openScreen(Screen.BOOKMARKS) },
                MenuItem(Icons.Filled.History, "История") { c.openScreen(Screen.HISTORY) },
                MenuItem(Icons.Filled.Download, "Загрузки") { c.sheet = null; Downloads.openDownloads(c.activity) },
                MenuItem(Icons.Filled.FindInPage, "Найти", onPage) { c.startFind() },
                MenuItem(Icons.Filled.DesktopWindows, if (tab.desktopMode) "Мобильная версия" else "Версия для ПК", onPage) { c.sheet = null; c.toggleDesktop() },
                MenuItem(Icons.Filled.TouchApp, "Скрыть элемент", onPage) { c.startPicker() },
                MenuItem(Icons.Filled.AddToHomeScreen, "На главный экран", onPage) { c.sheet = null; c.addToHomeScreen() },
                MenuItem(Icons.Filled.Print, "Печать / PDF", onPage) { c.sheet = null; c.print() },
                MenuItem(Icons.Filled.Insights, "Статистика") { c.openScreen(Screen.STATS) },
                MenuItem(Icons.Filled.Settings, "Настройки") { c.openScreen(Screen.SETTINGS) },
            )
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    row.forEach { item -> MenuTile(item, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private class MenuItem(val icon: ImageVector, val label: String, val enabled: Boolean = true, val action: () -> Unit)

@Composable
private fun MenuTile(item: MenuItem, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = item.enabled, onClick = item.action)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            item.icon, null,
            tint = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ shield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldSheet(c: BrowserController) {
    val tab = c.current ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val globalOn by c.settings.adblock.flow.collectAsState()
    val host = tab.pageHost ?: Urls.host(tab.url)
    val log = remember(tab.blockedCount, tab.hiddenCount) { tab.blockedLog() }
    ModalBottomSheet(onDismissRequest = { c.sheet = null }, sheetState = state) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SiteIcon(tab.favicon, host ?: "Bastion", 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (tab.homeVisible || host == null) "Bastion Shield" else UrlUtil.displayHost(tab.url),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    !globalOn -> "Блокировка выключена в настройках"
                                    tab.protectionOff -> "Защита на этом сайте выключена"
                                    else -> "Защита включена"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (globalOn && !tab.protectionOff) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (host != null && !tab.homeVisible && globalOn) {
                            Switch(checked = !tab.protectionOff, onCheckedChange = { c.setProtection(tab, it) })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CounterTile(tab.blockedCount.toString(), "запросов\nзаблокировано", Modifier.weight(1f))
                        CounterTile(tab.hiddenCount.toString(), "рекламных\nфреймов скрыто", Modifier.weight(1f))
                        CounterTile(tab.popupsBlocked.toString(), "всплывающих\nокон", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { c.startPicker() },
                            enabled = !tab.homeVisible && tab.url.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.TouchApp, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скрыть элемент")
                        }
                        OutlinedButton(onClick = { c.openScreen(Screen.SETTINGS) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Settings, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Настройки")
                        }
                    }
                    if (log.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Заблокировано на странице", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            items(log.take(200)) { e -> BlockedRow(e) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CounterTile(value: String, label: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun BlockedRow(e: BlockedEntry) {
    val host = Urls.host(e.url) ?: e.url
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Pill(e.kind, MaterialTheme.colorScheme.error, Modifier.width(64.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(host, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                e.rule + (e.list?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ context menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(c: BrowserController, m: ContextMenuState) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = { c.contextMenu = null }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                m.link ?: m.image ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            m.link?.let { link ->
                SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Открыть в новой вкладке") { dismiss(); c.newTab(link, incognito = m.tab.incognito, parent = m.tab) }
                SheetAction(Icons.Filled.TabUnselected, "Открыть в фоновой вкладке") {
                    dismiss(); c.newTab(link, incognito = m.tab.incognito, select = false, parent = m.tab)
                    c.showSnackbar("Вкладка открыта в фоне")
                }
                SheetAction(Icons.Filled.VisibilityOff, "Открыть в инкогнито") { dismiss(); c.newTab(link, incognito = true) }
                SheetAction(Icons.Filled.ContentCopy, "Копировать ссылку") { dismiss(); c.copyToClipboard(link) }
                SheetAction(Icons.Filled.Share, "Поделиться ссылкой") { dismiss(); c.share(link, null) }
            }
            m.image?.let { image ->
                if (m.link != null) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SheetAction(Icons.Filled.Image, "Открыть изображение") { dismiss(); c.newTab(image, incognito = m.tab.incognito, parent = m.tab) }
                if (image.startsWith("http") || image.startsWith("data:")) {
                    SheetAction(Icons.Filled.Download, "Скачать изображение") { dismiss(); c.download(m.tab, image, null, null, null) }
                }
                SheetAction(Icons.Filled.ContentCopy, "Копировать адрес изображения") { dismiss(); c.copyToClipboard(image) }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
