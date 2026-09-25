package io.github.jsys12.bastion.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.DialogState
import io.github.jsys12.bastion.browser.Screen
import io.github.jsys12.bastion.browser.Sheet

@Composable
fun BrowserApp(c: BrowserController) {
    val screen = c.screens.lastOrNull()
    val bottomBar by c.settings.bottomBar.flow.collectAsState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            screen != null -> when (screen) {
                Screen.SETTINGS -> SettingsScreen(c)
                Screen.FILTERS -> FilterListsScreen(c)
                Screen.USER_RULES -> UserRulesScreen(c)
                Screen.ALLOWLIST -> AllowlistScreen(c)
                Screen.HISTORY -> HistoryScreen(c)
                Screen.BOOKMARKS -> BookmarksScreen(c)
                Screen.STATS -> StatsScreen(c)
                Screen.ABOUT -> AboutScreen(c)
            }
            c.sheet == Sheet.TABS -> TabsSwitcher(c)
            else -> BrowserScreen(c)
        }

        c.fullscreenView?.let { view ->
            key(view) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        (view.parent as? ViewGroup)?.removeView(view)
                        addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
            }
        }

        SnackbarHost(
            c.snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = if (bottomBar && screen == null) 64.dp else 8.dp),
        )
    }

    if (screen == null) {
        when (c.sheet) {
            Sheet.MENU -> MenuSheet(c)
            Sheet.SHIELD -> ShieldSheet(c)
            else -> {}
        }
        c.contextMenu?.let { ContextMenuSheet(c, it) }
    }
    c.dialog?.let { DialogHost(it) }
}

@Composable
private fun DialogHost(d: DialogState) {
    when (d) {
        is DialogState.Confirm -> AlertDialog(
            onDismissRequest = { d.onResult(false) },
            title = { Text(d.title) },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { d.onResult(true) }) { Text(d.confirm) } },
            dismissButton = { TextButton(onClick = { d.onResult(false) }) { Text(d.dismiss) } },
        )
        is DialogState.HttpAuth -> {
            var user by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { d.onResult(null) },
                title = { Text("Вход на ${d.host}") },
                text = {
                    Column {
                        if (d.realm.isNotBlank()) Text(d.realm, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(user, { user = it }, label = { Text("Имя пользователя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { d.onResult(user to pass) }) { Text("Войти") } },
                dismissButton = { TextButton(onClick = { d.onResult(null) }) { Text("Отмена") } },
            )
        }
        is DialogState.Input -> {
            var text by remember { mutableStateOf(d.initial) }
            AlertDialog(
                onDismissRequest = { d.onResult(null) },
                title = { Text(d.title) },
                text = {
                    OutlinedTextField(text, { text = it }, label = { Text(d.label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = { TextButton(onClick = { d.onResult(text) }) { Text(d.confirm) } },
                dismissButton = { TextButton(onClick = { d.onResult(null) }) { Text("Отмена") } },
            )
        }
    }
}
