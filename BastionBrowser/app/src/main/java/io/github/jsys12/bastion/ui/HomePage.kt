package io.github.jsys12.bastion.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.FilterUpdater
import io.github.jsys12.bastion.adblock.Urls
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.DialogState
import io.github.jsys12.bastion.browser.FaviconCache
import io.github.jsys12.bastion.browser.Screen
import io.github.jsys12.bastion.data.Bookmark

@Composable
fun HomePage(c: BrowserController) {
    val tab = c.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            if (tab?.incognito == true) IncognitoHeader(c) else Logo()
            Spacer(Modifier.height(24.dp))
            SearchPill { c.startEditing() }
            Spacer(Modifier.height(20.dp))
            if (tab?.incognito != true) {
                StatsCard(c)
                Spacer(Modifier.height(20.dp))
                SpeedDial(c)
                DefaultBrowserCard(c)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3D7BFF), Color(0xFF1737B8)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Bastion", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    }
}

@Composable
private fun IncognitoHeader(c: BrowserController) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
        Icon(Icons.Filled.VisibilityOff, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Режим инкогнито", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (c.incognitoIsolated) {
                "История, кэш и cookie этих вкладок не сохраняются и изолированы от обычных вкладок. Всё будет удалено, когда вы закроете последнюю вкладку инкогнито."
            } else {
                "История и кэш этих вкладок не сохраняются. Ваша версия WebView не поддерживает отдельный профиль, поэтому cookie общие с обычными вкладками."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).height(56.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Поиск или адрес сайта", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun StatsCard(c: BrowserController) {
    val stats by c.stats.state.collectAsState()
    val status by AdBlocker.status.collectAsState()
    val updating by FilterUpdater.updating.collectAsState()
    val enabled by c.settings.adblock.flow.collectAsState()
    Card(
        onClick = { c.openScreen(Screen.STATS) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield, null,
                    tint = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (enabled) "Защита активна" else "Защита выключена",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                if (status.loading || updating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (status.loading) "загрузка фильтров" else "обновление",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                formatCount(stats.total + stats.elements),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "заблокировано рекламы, трекеров и всплывающих окон",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("≈ ${formatBytes(stats.savedBytes)}", "трафика", Modifier.weight(1f))
                StatChip("≈ ${formatDuration(stats.savedMillis)}", "времени", Modifier.weight(1f))
                StatChip(formatCount((status.networkRules + status.cosmeticRules).toLong()), "правил", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, modifier: Modifier) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = onContainer.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = onContainer)
            Text(label, style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = 0.7f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDial(c: BrowserController) {
    val version by c.db.version.collectAsState()
    val items = remember(version) { c.db.bookmarks().filter { it.onHome } }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { b -> DialItem(c, b, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialItem(c: BrowserController, b: Bookmark, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(onClick = { c.openInput(b.url) }, onLongClick = { menu = true })
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SiteIcon(FaviconCache.get(Urls.host(b.url)), b.title.ifBlank { Urls.host(b.url) ?: "" }, 52.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                b.title.ifBlank { Urls.host(b.url) ?: b.url },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Открыть в новой вкладке") }, onClick = { menu = false; c.newTab(b.url) })
            DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                menu = false
                c.dialog = DialogState.Input("Название", "Название", b.title) { name ->
                    c.dialog = null
                    if (name != null) c.db.updateBookmark(b.id, b.url, name, b.onHome)
                }
            })
            DropdownMenuItem(text = { Text("Убрать с главной") }, onClick = {
                menu = false
                c.db.updateBookmark(b.id, b.url, b.title, false)
            })
            DropdownMenuItem(text = { Text("Удалить закладку") }, onClick = { menu = false; c.db.deleteBookmark(b.id) })
        }
    }
}

@Composable
private fun DefaultBrowserCard(c: BrowserController) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(c.settings.prefs.getBoolean("default_card_dismissed", false)) }
    val isDefault = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        } else {
            val resolved = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), 0,
            )
            resolved?.activityInfo?.packageName == context.packageName
        }
    }
    if (isDefault || dismissed) return
    Spacer(Modifier.height(16.dp))
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Блокируйте рекламу во всех ссылках", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Сделайте Bastion браузером по умолчанию — ссылки из мессенджеров и приложений будут открываться без рекламы.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val activity = c.activity
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val rm = activity.getSystemService(RoleManager::class.java)
                        try {
                            @Suppress("DEPRECATION")
                            activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 42)
                        } catch (e: Exception) {
                            activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        }
                    } else {
                        try { activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (e: Exception) { /* ignore */ }
                    }
                }) { Text("Сделать основным") }
                Text(
                    "Не сейчас",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            dismissed = true
                            c.settings.prefs.edit().putBoolean("default_card_dismissed", true).apply()
                        }
                        .padding(12.dp),
                )
            }
        }
    }
}
