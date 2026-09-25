package io.github.jsys12.bastion.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.github.jsys12.bastion.adblock.Urls
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.FaviconCache
import io.github.jsys12.bastion.browser.SearchEngines
import io.github.jsys12.bastion.browser.Sheet
import io.github.jsys12.bastion.browser.Suggestions
import io.github.jsys12.bastion.browser.Tab
import io.github.jsys12.bastion.browser.UrlUtil
import io.github.jsys12.bastion.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(c: BrowserController) {
    val tab = c.current ?: return
    val bottom by c.settings.bottomBar.flow.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (!bottom) Bar(c, tab, bottom = false)
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            WebContent(c, tab)
            if (tab.homeVisible) HomePage(c)
            if (c.editingAddress) SuggestionsPanel(c, tab)
        }
        c.find?.let { FindBar(c, it.query, it.index, it.total) }
        c.picker?.let { PickerBar(c) }
        if (bottom) Bar(c, tab, bottom = true)
    }
}

@Composable
private fun Bar(c: BrowserController, tab: Tab, bottom: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (bottom) ProgressLine(tab)
            AddressBar(c, tab)
            if (!bottom) ProgressLine(tab)
        }
    }
}

@Composable
private fun ProgressLine(tab: Tab) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (tab.loading && !tab.homeVisible && tab.progress < 100) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun WebContent(c: BrowserController, tab: Tab) {
    val pull by c.settings.pullToRefresh.flow.collectAsState()
    val brand = MaterialTheme.colorScheme.primary.toArgb()
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val needsWebView = !(tab.homeVisible && tab.url.isEmpty()) || tab.webView != null
    val refreshing = c.refreshing
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SwipeRefreshLayout(ctx).apply {
                addView(
                    FrameLayout(ctx).apply { tag = WEB_CONTAINER },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                setColorSchemeColors(brand)
                setProgressBackgroundColorSchemeColor(surface)
                setOnRefreshListener {
                    c.refreshing = true
                    c.reload()
                }
                setOnChildScrollUpCallback { _, _ ->
                    val wv = c.current?.webView
                    wv == null || c.current?.homeVisible == true || !wv.canPullToRefresh()
                }
            }
        },
        update = { srl ->
            srl.isEnabled = pull
            if (srl.isRefreshing != refreshing) srl.isRefreshing = refreshing
            // SwipeRefreshLayout adds its own spinner view, so find our container by tag.
            val container = srl.findViewWithTag<FrameLayout>(WEB_CONTAINER)
            if (needsWebView) {
                val wv = c.ensureWebView(tab)
                if (wv.parent !== container) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    container.removeAllViews()
                    container.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            } else {
                container.removeAllViews()
            }
        },
    )
}

private const val WEB_CONTAINER = "bastion-web-container"

@Composable
private fun AddressBar(c: BrowserController, tab: Tab) {
    val engine = SearchEngines.byId(c.settings.searchEngine.flow.collectAsState().value)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!c.editingAddress) {
            ShieldButton(tab) { c.sheet = Sheet.SHIELD }
        } else {
            IconButton(onClick = { c.editingAddress = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Отмена") }
        }
        Box(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (c.editingAddress) {
                AddressField(c, tab)
            } else {
                Row(
                    Modifier.fillMaxSize().clickable { c.startEditing() }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val display = when {
                        tab.homeVisible -> null
                        else -> UrlUtil.searchTerms(tab.url, engine) ?: UrlUtil.displayHost(tab.url)
                    }
                    if (display != null && !tab.homeVisible) {
                        if (tab.sslError) Icon(Icons.Filled.Warning, "Небезопасно", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        else if (tab.url.startsWith("https://")) Icon(Icons.Filled.Lock, "Защищено", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Icon(Icons.Filled.Warning, "HTTP", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        display ?: (if (tab.incognito) "Инкогнито: поиск или адрес" else "Поиск или адрес"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (display == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!tab.homeVisible) {
                        if (tab.loading) {
                            IconButton(onClick = { c.stop() }, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Стоп", Modifier.size(20.dp)) }
                        } else {
                            IconButton(onClick = { c.reload() }, Modifier.size(40.dp)) { Icon(Icons.Filled.Refresh, "Обновить", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
        if (!c.editingAddress) {
            TabsButton(c.tabs.size, tab.incognito) {
                c.captureThumbnail(tab)
                c.sheet = Sheet.TABS
            }
            IconButton(onClick = { c.sheet = Sheet.MENU }) { Icon(Icons.Filled.MoreVert, "Меню") }
        }
    }
}

@Composable
private fun ShieldButton(tab: Tab, onClick: () -> Unit) {
    val count = tab.blockedCount + tab.hiddenCount
    val active = !tab.protectionOff
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (count > 0 && active && !tab.homeVisible) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                    Text(if (count > 99) "99+" else count.toString())
                }
            }
        }) {
            Icon(
                if (active) Icons.Filled.Shield else Icons.Outlined.RemoveModerator,
                "Защита",
                tint = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TabsButton(count: Int, incognito: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (incognito) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (incognito) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 99) ":D" else count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (incognito) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AddressField(c: BrowserController, tab: Tab) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val value = c.addressValue
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = { c.addressValue = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = {
                focusManager.clearFocus()
                c.openInput(value.text)
            }),
            modifier = Modifier.weight(1f).padding(start = 16.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            if (tab.incognito) "Инкогнито: поиск или адрес" else "Поиск или адрес",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.text.isNotEmpty()) {
            IconButton(onClick = { c.addressValue = TextFieldValue("") }) { Icon(Icons.Filled.Close, "Очистить", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun SuggestionsPanel(c: BrowserController, tab: Tab) {
    val text = c.addressValue.text
    val engineId by c.settings.searchEngine.flow.collectAsState()
    val suggestionsOn by c.settings.suggestions.flow.collectAsState()
    var history by remember { mutableStateOf(emptyList<HistoryEntry>()) }
    var remote by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(text) {
        history = if (tab.incognito) emptyList() else withContext(Dispatchers.IO) { c.db.suggest(text, 4) }
        if (!suggestionsOn || tab.incognito || text.isBlank()) {
            remote = emptyList(); return@LaunchedEffect
        }
        delay(150)
        remote = Suggestions.fetch(SearchEngines.byId(engineId), text)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (text.isEmpty() && !tab.homeVisible && tab.url.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SiteIcon(tab.favicon ?: FaviconCache.get(Urls.host(tab.url)), UrlUtil.displayHost(tab.url), 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tab.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { c.copyToClipboard(tab.url); c.editingAddress = false }) { Icon(Icons.Filled.ContentCopy, "Копировать") }
                        IconButton(onClick = { c.share(); c.editingAddress = false }) { Icon(Icons.Filled.Share, "Поделиться") }
                    }
                    HorizontalDivider()
                }
            }
            if (text.isNotBlank()) {
                item {
                    SuggestionRow(Icons.Filled.Search, text, "Искать в ${SearchEngines.byId(engineId).name}", onFill = null) { c.openInput(text) }
                }
            }
            items(history, key = { "h" + it.id }) { h ->
                SuggestionRow(Icons.Filled.History, h.title.ifBlank { h.url }, h.url, favicon = FaviconCache.get(Urls.host(h.url)), onFill = null) {
                    c.openInput(h.url)
                }
            }
            items(remote.filter { it != text }, key = { "s$it" }) { s ->
                SuggestionRow(Icons.Filled.Search, s, null, onFill = { c.addressValue = TextFieldValue(s, TextRange(s.length)) }) { c.openInput(s) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    favicon: android.graphics.Bitmap? = null,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favicon != null) SiteIcon(favicon, title, 28.dp) else Icon(icon, null, Modifier.size(28.dp).padding(2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onFill != null) {
            IconButton(onClick = onFill) { Icon(Icons.Filled.NorthWest, "Вставить", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun FindBar(c: BrowserController, query: String, index: Int, total: Int) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { c.findQuery(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { c.findNext(true) }),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Найти на странице", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            Text(if (query.isEmpty()) "" else "$index/$total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { c.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Назад") }
            IconButton(onClick = { c.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Далее") }
            IconButton(onClick = { c.closeFind() }) { Icon(Icons.Filled.Close, "Закрыть") }
        }
    }
}

@Composable
private fun PickerBar(c: BrowserController) {
    val state = c.picker ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Скрыть элемент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (!state.selected) {
                Text("Коснитесь рекламы или любого элемента на странице", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${state.label} · будет скрыто: ${state.count}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    state.selector,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { c.pickerNarrower() }, enabled = state.selected) { Text("Уже") }
                OutlinedButton(onClick = { c.pickerWider() }, enabled = state.selected) { Text("Шире") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { c.stopPicker() }) { Text("Отмена") }
                FilledTonalButton(onClick = { c.pickerConfirm() }, enabled = state.selected) { Text("Скрыть") }
            }
        }
    }
}

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

@Composable
fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
        }
    }
}
