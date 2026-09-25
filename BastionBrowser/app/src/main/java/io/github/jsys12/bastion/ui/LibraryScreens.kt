package io.github.jsys12.bastion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jsys12.bastion.adblock.Urls
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.DialogState
import io.github.jsys12.bastion.browser.FaviconCache
import io.github.jsys12.bastion.data.Bookmark
import io.github.jsys12.bastion.data.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun BrowserController.closeScreen() {
    if (screens.isNotEmpty()) screens.removeAt(screens.size - 1)
}

private fun BrowserController.openFromLibrary(url: String) {
    screens.clear()
    val tab = current
    if (tab != null && tab.homeVisible && tab.url.isEmpty()) load(tab, url) else newTab(url)
}

@Composable
fun HistoryScreen(c: BrowserController) {
    val version by c.db.version.collectAsState()
    var query by remember { mutableStateOf("") }
    val items = remember(version, query) { c.db.history(query, 500) }
    ScreenScaffold("История", onBack = { c.closeScreen() }, actions = {
        IconButton(onClick = {
            c.confirm("Очистить историю?", "Будет удалена вся история посещений.", "Очистить") { if (it) c.db.clearHistory() }
        }) { Icon(Icons.Filled.DeleteSweep, "Очистить") }
    }) { modifier ->
        Column(modifier) {
            SearchField(query, "Поиск в истории") { query = it }
            if (items.isEmpty()) {
                EmptyState(Icons.Filled.History, if (query.isEmpty()) "История пуста" else "Ничего не найдено")
            } else {
                val grouped = items.groupBy { dayLabel(it.visitedAt) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    grouped.forEach { (day, entries) ->
                        item(key = "d_$day") { SectionTitle(day) }
                        items(entries, key = { it.id }) { h -> HistoryRow(c, h) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(c: BrowserController, h: HistoryEntry) {
    Row(
        Modifier.fillMaxWidth().clickable { c.openFromLibrary(h.url) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(FaviconCache.get(Urls.host(h.url)), Urls.host(h.url) ?: h.url, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${TIME.format(Date(h.visitedAt))} · ${Urls.host(h.url) ?: h.url}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { c.db.deleteHistory(h.id) }) { Icon(Icons.Filled.Close, "Удалить", Modifier.size(20.dp)) }
    }
}

private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
private val DATE = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

private fun dayLabel(time: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = time
    val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when (day) {
        today -> "Сегодня"
        yesterday -> "Вчера"
        else -> DATE.format(Date(time))
    }
}

@Composable
private fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Очистить") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun BookmarksScreen(c: BrowserController) {
    val version by c.db.version.collectAsState()
    var query by remember { mutableStateOf("") }
    val all = remember(version) { c.db.bookmarks() }
    val items = all.filter { query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) }
    ScreenScaffold("Закладки", onBack = { c.closeScreen() }) { modifier ->
        Column(modifier) {
            SearchField(query, "Поиск в закладках") { query = it }
            if (items.isEmpty()) {
                EmptyState(Icons.Filled.Bookmarks, "Закладок пока нет. Нажмите ☆ в меню, чтобы добавить страницу.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(items, key = { it.id }) { b -> BookmarkRow(c, b) }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(c: BrowserController, b: Bookmark) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { c.openFromLibrary(b.url) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(FaviconCache.get(Urls.host(b.url)), b.title.ifBlank { Urls.host(b.url) ?: "" }, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (b.onHome) Icon(Icons.Filled.Home, "На главной", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Открыть в новой вкладке") }, onClick = { menu = false; c.screens.clear(); c.newTab(b.url) })
                DropdownMenuItem(text = { Text("Изменить название") }, onClick = {
                    menu = false
                    c.dialog = DialogState.Input("Название", "Название", b.title) { v ->
                        c.dialog = null
                        if (v != null) c.db.updateBookmark(b.id, b.url, v, b.onHome)
                    }
                })
                DropdownMenuItem(text = { Text("Изменить адрес") }, onClick = {
                    menu = false
                    c.dialog = DialogState.Input("Адрес", "URL", b.url) { v ->
                        c.dialog = null
                        if (v != null && v.startsWith("http")) c.db.updateBookmark(b.id, v, b.title, b.onHome)
                    }
                })
                DropdownMenuItem(
                    text = { Text(if (b.onHome) "Убрать с главной" else "Показать на главной") },
                    onClick = { menu = false; c.db.updateBookmark(b.id, b.url, b.title, !b.onHome) },
                )
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; c.db.deleteBookmark(b.id) })
            }
        }
    }
}
